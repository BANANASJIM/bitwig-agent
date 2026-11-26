import pytest
from pydantic import ValidationError

from bitwig_agent.core import views
from bitwig_agent.errors import bridge_error
from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.annotations import ADD, resolve
from bitwig_agent.protocol.base import ReasonError, StrictModel
from bitwig_agent.protocol.limits import NORMALIZED_TOLERANCE, PARAMETER_RESOLUTION, bundle_limits
from bitwig_agent.protocol.values import EXACT, NORMALIZED, Display, Expect, Normalized, raw


class Write(StrictModel):
    value: Normalized


def test_a_host_parameter_takes_its_normalized_value_or_its_display():
    assert Write.model_validate({"value": 0.5}).value == 0.5
    assert Write.model_validate({"value": {"display": "-6 dB"}}).value == Display(display="-6 dB")
    for wrong in (1.5, {"label": "Center"}, {"display": ""}, "0.5", True):
        with pytest.raises(ValidationError):
            Write.model_validate({"value": wrong})


def test_read_backs_match_by_their_kinds_rule_like_the_extension():
    assert NORMALIZED.matches(0.5, 0.5 + 1 / (PARAMETER_RESOLUTION - 1))
    assert not NORMALIZED.matches(0.5, 0.5 + 2 / (PARAMETER_RESOLUTION - 1))
    assert not NORMALIZED.matches(0.5, True)
    assert not NORMALIZED.matches(0.5, float("nan"))
    assert raw(0.01).matches(440, 440.005)
    assert not raw(0.01).matches(440, 440.5)
    assert EXACT.matches(1, 1.0)
    assert not EXACT.matches(1, True)
    assert EXACT.matches({"kind": "track_audio_input", "trackId": "t"}, {"trackId": "t", "kind": "track_audio_input"})
    assert not EXACT.matches({"kind": "none"}, {"kind": "none", "trackId": "t"})
    assert EXACT.matches([1, "a"], [1.0, "a"])
    assert not EXACT.matches([1], [1, 2])
    assert bundle_limits()["normalizedTolerance"] == NORMALIZED_TOLERANCE


def test_expect_names_at_least_one_bare_value():
    class VolumeExpect(Expect):
        volume: float | None = None

    assert VolumeExpect.model_validate({"volume": 0.5}).volume == 0.5
    with pytest.raises(ValidationError):
        VolumeExpect.model_validate({})


def test_errors_carry_current_observed_and_entries_only_when_given():
    current = {"kind": "marker", "index": 0, "name": "B", "beat": 4}
    error = bridge_error("target_changed", "moved", fields={"current": current, "observed": {"mute": False}}).error
    assert (error["current"], error["observed"]) == (current, {"mute": False})
    entries = [{"index": 1, "code": "stale", "reason": "target_not_found", "message": "gone"}]
    assert bridge_error("target_not_found", "1 of 2", fields={"entries": entries}).error["entries"] == entries
    assert set(bridge_error("busy", "m").error) == {"code", "reason", "message"}


def test_a_validator_names_only_an_invalid_params_reason():
    assert ReasonError("unsupported_scope", "m", "grid.apply").next == "grid.apply"
    with pytest.raises(ValueError, match="not an invalid_params reason"):
        ReasonError("busy", "m")


def test_status_takes_an_optional_full_detail():
    assert validate_call("status", {"detail": "full"})[0] == {"detail": "full"}
    assert validate_call("status", {})[0] == {}
    with pytest.raises(ValidationError):
        validate_call("status", {"detail": "brief"})


def test_copy_is_an_add_verb_and_help_explains_refs_and_values():
    assert resolve("clips.copy", None) == ADD
    for topic, words in {
        "refs": ("target_changed", "current", "unnamed scene"),
        "values": ("pan 0.5", "expect"),
    }.items():
        text = views.help(topic)["text"]
        assert all(word in text for word in words), topic
    assert "status" in TOOLS
