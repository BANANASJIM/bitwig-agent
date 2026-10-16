"""Native preflight safety parsing only; no host, /proc or desktop access."""
import importlib.util
from pathlib import Path
import asyncio
import json
from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest
from mcp.types import CallToolResult, TextContent

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/smoke-native.py"
spec = importlib.util.spec_from_file_location("native_smoke", SCRIPT)
native_smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native_smoke)

HEADER = "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"


def table(address, *, port="EEF4", state="0A", inode="16083494"):
    # Proc rows combine tx/rx and tr/tm fields; inode is the tenth whitespace field.
    return HEADER + (f"  5: {address}:{port} 00000000000000000000000000000000:0000 "
                     f"{state} 00000000:00000000 00:00000000 00000000 1000 0 {inode} 1\n")


@pytest.mark.parametrize("address,byteorder", [
    ("0100007F", "little"),
    # Native host observation supplied by root: TCP4 connections work while the
    # listening inode is listed only in tcp6 with this mapped address.
    ("0000000000000000FFFF00000100007F", "little"),
    ("7F000001", "big"),
    ("00000000000000000000FFFF7F000001", "big"),
])
def test_accepts_exact_owned_ipv4_or_mapped_loopback(address, byteorder):
    assert native_smoke.has_owned_loopback_listener(
        table(address), 61172, {"16083494"}, byteorder=byteorder)


@pytest.mark.parametrize("address", [
    "00000000",  # IPv4 wildcard
    "00000000000000000000000000000000",  # IPv6 wildcard
    "0000000000000000FFFF000000000000",  # mapped IPv4 wildcard
    "0200007F",  # 127.0.0.2 is not the configured 127.0.0.1 endpoint
    "00000000000000000000000001000000",  # native IPv6 ::1, not mapped IPv4
    "0101A8C0",  # 192.168.1.1
    "not_hex!",
])
def test_rejects_wildcard_other_addresses_and_malformed_addresses(address):
    assert not native_smoke.has_owned_loopback_listener(
        table(address), 61172, {"16083494"}, byteorder="little")


@pytest.mark.parametrize("changes,owners", [
    ({"inode": "999999"}, {"16083494"}),
    ({}, set()),
    ({"port": "EEF5"}, {"16083494"}),
    ({"state": "01"}, {"16083494"}),
])
def test_rejects_foreign_socket_wrong_port_or_non_listener(changes, owners):
    assert not native_smoke.has_owned_loopback_listener(
        table("0000000000000000FFFF00000100007F", **changes),
        61172, owners, byteorder="little")


def test_ignores_short_rows_and_finds_later_owned_listener():
    rows = HEADER + "  0: incomplete\n"
    assert not native_smoke.has_owned_loopback_listener(rows, 61172, {"16083494"})
    rows += table("00000000").splitlines()[1] + "\n"
    rows += table("0000000000000000FFFF00000100007F").splitlines()[1] + "\n"
    assert native_smoke.has_owned_loopback_listener(rows, 61172, {"16083494"}, byteorder="little")


def test_nested_task_group_failures_retain_actionable_leaf_causes():
    error = ExceptionGroup("task group", [
        ExceptionGroup("session", [RuntimeError("devices.remote.get failed")]),
        TimeoutError("tracks.get deadline exceeded"),
    ])
    detail = native_smoke.describe_error(error)
    assert "devices.remote.get" in detail
    assert "tracks.get" in detail
    assert "RuntimeError" in detail and "TimeoutError" in detail


@pytest.mark.parametrize("envelope", [
    {"error": {"code": "grid_bounds_exceeded", "outcome": "unknown"}},
    # Real native tracks.set can show the requested values without a fresh
    # write observation. Checking only error.outcome misses this uncertainty.
    {"result": {"status": "dispatched", "completion": "unknown",
                "fields": {"mute": {"observed": True, "verification": "not_observed"}}}},
    {"result": {"status": "observed", "topologyQuarantined": True}},
    {"result": {"status": "observed", "topologyAdmissionBlocked": True}},
    {"error": {"code": "unavailable"}},
    {"error": None},
    {"result": None},
    {"result": {"status": "observed"}, "error": None},
    {},
])
def test_native_uncertainty_latches_before_another_operation(envelope):
    guard = native_smoke.NativeOutcomeGuard()
    with pytest.raises(native_smoke.NativeRunStopped):
        guard.observe(envelope, allow_dispatch_only=True)
    assert guard.stopped
    # Catching the first exception, or seeing a later healthy-looking result,
    # cannot authorize the next RPC/GUI operation.
    with pytest.raises(native_smoke.NativeRunStopped):
        guard.before_call()
    with pytest.raises(native_smoke.NativeRunStopped):
        guard.observe({"result": {"status": "observed"}})


@pytest.mark.parametrize("outcome", ["not_applied", "not_dispatched"])
def test_known_negative_cases_do_not_poison_native_run(outcome):
    guard = native_smoke.NativeOutcomeGuard()
    guard.observe({"error": {"code": "invalid_params", "outcome": outcome}})
    guard.before_call()
    guard.observe({"result": {"status": "observed"}})
    assert not guard.stopped


def test_dispatch_only_requires_explicit_case_expectation():
    response = {"result": {"status": "dispatched", "completion": "not_observed"}}
    strict = native_smoke.NativeOutcomeGuard()
    with pytest.raises(native_smoke.NativeRunStopped):
        strict.observe(response)
    assert strict.stopped
    expected_action = native_smoke.NativeOutcomeGuard()
    expected_action.observe(response, allow_dispatch_only=True)
    assert not expected_action.stopped


def test_transport_or_evidence_failure_latches_original_reason():
    guard = native_smoke.NativeOutcomeGuard()
    with pytest.raises(native_smoke.NativeRunStopped, match="lost response"):
        guard.stop("lost response")
    with pytest.raises(native_smoke.NativeRunStopped, match="lost response"):
        guard.stop("later cleanup failure")
    with pytest.raises(native_smoke.NativeRunStopped, match="lost response"):
        guard.before_call()


@pytest.mark.parametrize("failure_stage", ["target", "transport", "decode", "persist"])
def test_probe_failure_latches_before_a_caught_failure_can_continue(monkeypatch, tmp_path, failure_stage):
    guard = native_smoke.NativeOutcomeGuard()
    monkeypatch.setattr(native_smoke, "NativeOutcomeGuard", lambda: guard)
    dispatched = []

    @asynccontextmanager
    async def fake_stdio(_):
        yield None, None

    class Session:
        def __init__(self, *_):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_):
            pass

        async def initialize(self):
            pass

        async def list_tools(self):
            return SimpleNamespace(tools=[SimpleNamespace(name=name) for name in native_smoke.TOOLS])

        async def call_tool(self, method, _):
            dispatched.append(method)
            if failure_stage == "transport":
                raise ConnectionError("native response lost")
            return CallToolResult(
                structuredContent=None if failure_stage == "decode" else {"result": {"status": "observed"}},
                content=[TextContent(type="text", text="{"),
                         TextContent(type="text", text="原始尾部 Ω")],
                isError=False,
            )

    def target_guard():
        if failure_stage == "target":
            raise OSError("host identity unavailable")

    target = SimpleNamespace(
        config={"port": 61172},
        artifacts=tmp_path / "missing" if failure_stage == "persist" else tmp_path,
        guard=target_guard,
    )
    monkeypatch.setattr(native_smoke, "stdio_client", fake_stdio)
    monkeypatch.setattr(native_smoke, "ClientSession", Session)
    with pytest.raises((OSError, ValueError)):
        asyncio.run(native_smoke.probe(target, {}))
    assert dispatched == ([] if failure_stage == "target" else ["status"])
    assert guard.stopped
    if failure_stage == "decode":
        records = [json.loads(line) for line in (tmp_path / "mcp-results.jsonl").read_text().splitlines()]
        assert records[0]["response"]["structuredContent"] is None
        assert [part["text"] for part in records[0]["response"]["content"]] == ["{", "原始尾部 Ω"]
    with pytest.raises(native_smoke.NativeRunStopped):
        guard.before_call()


def test_cancelled_native_attempt_keeps_uncertainty_latched():
    guard = native_smoke.NativeOutcomeGuard()
    cancellation = asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError) as caught:
        with guard.attempt():
            raise cancellation
    assert caught.value is cancellation
    assert guard.stopped
    with pytest.raises(native_smoke.NativeRunStopped):
        with guard.attempt():
            pytest.fail("A caught cancellation must not reopen native admission")
