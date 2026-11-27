"""batch, the core composed method: N calls in, N entries out, in order, under the batch rule (PROTOCOL.md "Batches")."""

import asyncio
import itertools
from typing import Any

import pytest
import test_configured_insert

from bitwig_agent.core import batch
from bitwig_agent.core.invoke import Error, invoke
from bitwig_agent.errors import BridgeError, bridge_error
from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import SUCCEEDED, Outcome, leaf_rule_holds, shared_verification, worst
from bitwig_agent.protocol.tracks import EXAMPLE_TRACK

NOT_REACHED = {"outcome": "not_applied", "reason": "not_reached"}
WRITE = {"method": "transport.set", "params": {"playing": False}}
ANSWERS: dict[str, Any] = {
    "native": {"outcome": "applied", "verification": "native_readback"},
    "api": {"outcome": "applied", "verification": "api_readback"},
    "matched": {"outcome": "already_matched"},
    "sent": {"outcome": "sent"},
    "not_applied": {"outcome": "not_applied", "reason": "limit_reached"},
    "unknown": {"outcome": "unknown", "reason": "not_observed", "next": "Inspect with transport.get."},
    "error": BridgeError(bridge_error("unsafe_state", "Stop the transport first.").error),
}
STOPS = {"not_applied", "unknown", "error"}


class Host:
    """Answers each call from a script, after checking it is what the extension accepts; each result names its turn."""

    def __init__(self, script):
        self.script = list(script)
        self.calls = []

    async def call(self, method, params, timeout_ms=5000):
        validate_call(method, {**params, "timeoutMs": timeout_ms}, host=True)
        turn = len(self.calls)
        self.calls.append(method)
        answer = ANSWERS[self.script[turn]]
        if isinstance(answer, Exception):
            raise answer
        return {**answer, "turn": turn}


def batch_call(host, args):
    """The batch method through the single call path, on the fake host."""
    return asyncio.run(invoke("batch", args, host))


async def run(script, calls=None):
    host, emitted = Host(script), list[Any]()
    envelope = await batch.run(calls or [WRITE] * len(script), host, emitted.append)
    return host, emitted, envelope


def every_script(kinds, longest):
    return [script for n in range(1, longest + 1) for script in itertools.product(kinds, repeat=n)]


def test_n_calls_give_n_entries_in_order_and_nothing_runs_after_the_stop():
    """Every script of up to four answers: the property holds exhaustively, not just on sampled scripts."""

    async def check_all():
        for script in every_script(list(ANSWERS), 4):
            host, emitted, envelope = await run(script)
            result = envelope.result
            stop = next((i for i, kind in enumerate(script) if kind in STOPS), None)
            ran = len(script) if stop is None else stop + 1
            entries = result["entries"]
            assert len(entries) == len(emitted) == len(script), script
            assert len(host.calls) == ran, script  # nothing after the stop ran; sent, like any success, never stops
            for turn, kind in enumerate(script[:ran]):
                if kind == "error":
                    assert (entries[turn]["outcome"], entries[turn]["error"]["code"]) == ("not_applied", "unsafe_state")
                else:
                    assert entries[turn]["turn"] == turn, script  # input order
            assert entries[ran:] == [NOT_REACHED] * (len(script) - ran), script
            outcome = worst(entry["outcome"] for entry in entries)
            assert result["outcome"] == outcome
            shared = shared_verification(entries) if outcome is Outcome.APPLIED else None
            assert result.get("verification") == (shared and shared.value), script
            assert ("next" in result) == ("reason" in result) == (stop is not None), script
            assert envelope.failed == (outcome not in SUCCEEDED)
            assert leaf_rule_holds(result), script

    asyncio.run(check_all())


INVALID: dict[str, dict[str, Any]] = {
    "unknown": {"method": "tracks.sett", "params": {}},
    "read": {"method": "tracks.list"},
    "ack": {"method": "ack", "params": {"id": "routing.quarantine"}},
    "nested": {"method": "batch", "params": {"calls": [WRITE]}},
    "hidden": {"method": "grid.parameters.set", "params": {}},
    "params": {"method": "transport.set", "params": {"tempo": 19}},
}


def test_one_refused_call_refuses_the_batch_with_one_entry_per_call_and_runs_nothing():
    async def check_all():
        for script in every_script(["ok", *INVALID], 3):
            if "ok" in script and len(set(script)) == 1:
                continue
            calls = [WRITE if kind == "ok" else INVALID[kind] for kind in script]
            host, emitted, envelope = await run(["native"] * len(script), calls)
            assert isinstance(envelope, Error), script
            assert (host.calls, emitted) == ([], [])
            entries = envelope.error["entries"]
            assert len(entries) == len(script)
            for kind, entry in zip(script, entries, strict=True):
                assert (entry == NOT_REACHED) == (kind == "ok"), script
            first = next(i for i, kind in enumerate(script) if kind != "ok")
            assert envelope.error["code"] == "invalid_params"
            assert f"Call {first + 1} ({calls[first]['method']})" in envelope.error["message"]

    asyncio.run(check_all())


@pytest.mark.parametrize(
    ("call", "fragment"),
    [
        (INVALID["read"], "tracks.list is a read"),
        (INVALID["ack"], "ack cannot be batched"),
        (INVALID["nested"], "batch cannot contain batch"),
        (INVALID["unknown"], "Unknown method: tracks.sett"),
    ],
)
def test_reads_ack_and_batch_itself_cannot_be_batched(call, fragment):
    envelope = batch_call(Host([]), {"calls": [WRITE, call]})
    assert isinstance(envelope, Error)
    assert fragment in envelope.error["entries"][1]["message"]
    assert envelope.error["entries"][0] == NOT_REACHED


def test_the_next_hint_names_what_is_done_and_what_is_left():
    def hint(script):
        return asyncio.run(run(script))[2].result["next"]

    assert hint(["native", "not_applied", "native", "native"]) == (
        "Call 2 (transport.set) was not applied: fix it and send it again with calls 3-4; the calls before it are done."
    )
    assert hint(["unknown", "native"]) == (
        "Call 1 (transport.set) stopped: Inspect with transport.get. Never resend it. Call 2 was not run."
    )
    assert hint(["error"]).endswith("fix it and send it again; the calls before it are done.")


def test_an_unknown_call_without_its_own_next_is_inspected_and_never_resent():
    result = batch.report([WRITE, WRITE, WRITE], [batch.call_path.Result({"outcome": "unknown", "reason": "deadline"})])
    assert result["next"] == (
        "Call 1 (transport.set) is unknown: inspect the real state before any further write. Never resend it. "
        "Then send only calls 2-3."
    )


def test_a_stopping_insert_that_already_created_its_device_is_never_resent():
    refused = bridge_error("unsupported_unit", "Cutoff shows a text display.")
    host = test_configured_insert.Host(**{"parameters.set": [refused]})
    insert = {"to": EXAMPLE_TRACK, "insert": test_configured_insert.FILTER, "params": {"Cutoff": {"display": "LP"}}}
    calls = [{"method": "devices.insert", "params": insert}, WRITE, WRITE]
    envelope = batch_call(host, {"calls": calls})
    assert not isinstance(envelope, Error)
    hint = envelope.result["next"]
    assert hint.startswith("Call 1 (devices.insert) stopped: The insert applied")
    assert "do not repeat devices.insert" in hint
    assert "send it again" not in hint
    assert hint.endswith("Calls 2-3 were not run.")
    assert envelope.result["entries"][0]["ref"] == test_configured_insert.NEW_DEVICE
    assert [method for method, _ in host.calls] == ["devices.insert", "parameters.list", "parameters.set"]


def test_mcp_refuses_a_timeout_inside_a_call_and_takes_malformed_calls_to_validation():
    refused = batch.refuse_call_timeouts({"calls": [WRITE, {"method": "tracks.set", "params": {"timeoutMs": 1000}}]})
    assert refused is not None
    assert refused.error["code"] == "invalid_params"
    assert refused.error["message"].startswith("Call 2 (tracks.set): timeoutMs is not accepted")
    for args in ({"calls": [WRITE]}, {"calls": "x"}, {"calls": [1, {"params": 2}]}, {}):
        assert batch.refuse_call_timeouts(args) is None


def test_a_batch_of_dry_runs_carries_no_outcome():
    report = batch.report([WRITE], [batch.call_path.Result({"dryRun": True})])
    assert report == {"entries": [{"dryRun": True}]}


def test_the_batch_tool_example_validates_and_composes_calls_the_client_runs():
    spec = TOOLS["batch"]
    assert not spec.host
    assert not spec.read_only
    for example in spec.examples:
        validate_call("batch", example)
    host = Host(["native", "native"])
    envelope = batch_call(host, spec.examples[0])
    assert not isinstance(envelope, Error)
    assert envelope.result["outcome"] == "applied"
    assert envelope.result["verification"] == "native_readback"
    assert host.calls == ["tracks.set", "transport.set"]


def test_a_batch_is_one_to_a_thousand_and_twenty_four_calls():
    with pytest.raises(ValueError, match="calls"):
        validate_call("batch", {"calls": []})
    with pytest.raises(ValueError, match="calls"):
        validate_call("batch", {"calls": [WRITE] * 1025})
    target = {"method": "tracks.set", "params": {"target": EXAMPLE_TRACK, "mute": True}}
    assert validate_call("batch", {"calls": [target] * 1024})[0]["calls"][0] == target


@pytest.mark.anyio
async def test_mcp_batch_tool_runs_writes_in_order_and_returns_one_entry_each(mcp_session, host):
    replies = iter(
        [
            {"outcome": "applied", "verification": "api_readback"},
            {"outcome": "sent"},
            {"outcome": "unknown", "reason": "not_observed"},
        ]
    )
    host.reply = lambda request: {"id": request["id"], "result": next(replies)}
    calls = [{"method": "tracks.set", "params": {"target": EXAMPLE_TRACK, "mute": True}}, WRITE, WRITE, WRITE]
    response = await mcp_session.call_tool("batch", {"calls": calls})
    result = response.structured_content
    assert response.is_error is True
    assert [e["outcome"] for e in result["entries"]] == ["applied", "sent", "unknown", "not_applied"]
    assert result["entries"][3] == NOT_REACHED
    assert (result["outcome"], result["reason"]) == ("unknown", "not_observed")
    assert [r["method"] for r in host.requests] == ["tracks.set", "transport.set", "transport.set"]

    timed = await mcp_session.call_tool("batch", {"calls": [{**WRITE, "params": {"playing": False, "timeoutMs": 900}}]})
    assert timed.is_error is True
    assert timed.structured_content["code"] == "invalid_params"
    assert "timeoutMs" in timed.structured_content["message"]

    refused = await mcp_session.call_tool("batch", {"calls": [WRITE, {"method": "tracks.list"}]})
    assert refused.is_error is True
    assert [e.get("code") for e in refused.structured_content["entries"]] == [None, "invalid_params"]
    assert len(host.requests) == 3

    listed = {tool.name: tool for tool in (await mcp_session.list_tools()).tools}
    assert listed["batch"].input_schema["required"] == ["calls"]
