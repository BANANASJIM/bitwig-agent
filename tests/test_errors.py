import pytest

from bitwig_agent.core import composed
from bitwig_agent.core.invoke import Error, Result, invoke
from bitwig_agent.errors import HostChanged, bridge_error, unknown_result
from bitwig_agent.protocol.base import REASONS, SUCCEEDED, ErrorCode, Outcome, vocabulary

pytestmark = pytest.mark.anyio


def test_every_reason_belongs_to_one_of_the_ten_codes():
    assert len(ErrorCode) == 10
    assert set(REASONS.values()) <= set(ErrorCode)
    assert vocabulary()["outcomes"] == ["already_matched", "applied", "sent", "not_applied", "unknown"]


def test_success_is_three_outcomes():
    assert {Outcome.ALREADY_MATCHED, Outcome.APPLIED, Outcome.SENT} == SUCCEEDED


def test_an_error_carries_the_code_of_its_reason_and_never_an_outcome():
    error = bridge_error("stale_target", "moved", next="Re-read tracks.list.", track="t").error
    assert error == {
        "code": "stale",
        "reason": "stale_target",
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
