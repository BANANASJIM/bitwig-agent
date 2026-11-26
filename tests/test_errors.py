import pytest

from bitwig_agent.core import composed
from bitwig_agent.core.invoke import Error, Result, invoke, stopped
from bitwig_agent.errors import HostChanged, bridge_error, unknown_result
from bitwig_agent.protocol import TOOLS, export_bundle
from bitwig_agent.protocol.base import REASONS, SUCCEEDED, ErrorCode, Outcome, vocabulary
from bitwig_agent.protocol.registry import INSPECT, with_inspect

pytestmark = pytest.mark.anyio


def test_every_reason_belongs_to_one_of_the_ten_codes():
    assert len(ErrorCode) == 10
    assert set(REASONS.values()) <= set(ErrorCode)
    assert vocabulary()["outcomes"] == ["already_matched", "applied", "sent", "not_applied", "unknown"]


def test_success_is_three_outcomes():
    assert {Outcome.ALREADY_MATCHED, Outcome.APPLIED, Outcome.SENT} == SUCCEEDED


def test_an_error_carries_the_code_of_its_reason_and_never_an_outcome():
    error = bridge_error("target_changed", "moved", next="Re-read tracks.list.", track="t").error
    assert error == {
        "code": "stale",
        "reason": "target_changed",
        "message": "moved",
        "next": "Re-read tracks.list.",
        "details": {"track": "t"},
    }
    with pytest.raises(KeyError):
        bridge_error("not_a_reason", "x")


def test_an_unknown_result_names_a_closed_reason():
    assert unknown_result("deadline", "late", next="Inspect.", job="render:1") == {
        "outcome": "unknown",
        "reason": "deadline",
        "message": "late",
        "job": "render:1",
        "next": "Inspect.",
    }
    with pytest.raises(KeyError):
        unknown_result("late", "x")


async def test_a_composed_step_that_may_have_changed_state_returns_its_unknown_result(monkeypatch):
    async def step(client, args, timeout_ms):
        raise HostChanged(unknown_result("job_failed", "pw-link may have run"))

    monkeypatch.setitem(composed.HANDLERS, "audio.analyze", step)
    envelope = await invoke("audio.analyze", {"path": "/tmp/x.wav", "mode": "level"})
    assert isinstance(envelope, Result)
    assert envelope.result["outcome"] == "unknown"
    assert envelope.failed


async def test_failed_follows_the_outcome():
    assert not Result({"tracks": []}).failed
    assert not Result({"outcome": "sent"}).failed
    assert Result({"outcome": "not_applied"}).failed
    assert Error(bridge_error("busy", "x").error).failed


def test_every_host_write_names_the_host_read_an_unknown_result_points_at():
    methods = export_bundle()["methods"]
    for name, spec in TOOLS.items():
        if spec.host and not spec.read_only:
            assert spec.inspect is not None
            assert TOOLS[spec.inspect].host
            assert TOOLS[spec.inspect].read_only
            assert methods[name]["inspect"] == spec.inspect
        else:
            assert spec.inspect is None
            assert methods[name]["inspect"] is None


def test_a_write_without_a_host_read_to_inspect_is_refused_when_the_registry_is_built():
    write, read = "transport.set", "transport.get"
    specs = dict(TOOLS)
    with pytest.raises(ValueError, match="without an inspect method"):
        with_inspect(specs, {})
    with pytest.raises(ValueError, match="unknown methods"):
        with_inspect(specs, {**INSPECT, "no.such": read})
    with pytest.raises(ValueError, match="not a host write"):
        with_inspect(specs, {**INSPECT, read: read})
    with pytest.raises(ValueError, match="not a host read"):
        with_inspect(specs, {**INSPECT, write: write})
    with pytest.raises(ValueError, match="not a host read"):
        with_inspect(specs, {**INSPECT, write: "grid.apply"})


def test_a_write_cut_short_on_the_caller_side_points_at_the_methods_read():
    result = stopped("transport.set", "cancelled", "interrupted")
    assert isinstance(result, Result)
    assert result.result["next"] == "transport.get"
