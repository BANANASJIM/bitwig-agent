import json
from pathlib import Path

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


VECTORS = json.loads((Path(__file__).parent / "fixtures" / "value_match.json").read_text(encoding="utf-8"))
"""Shared with the extension's ValueTest: both comparators must decide every vector the same way."""


@pytest.mark.parametrize("vector", VECTORS, ids=lambda vector: vector["why"])
def test_read_backs_match_by_the_shared_vectors(vector):
    rule = {"normalized": NORMALIZED, "exact": EXACT}.get(vector["rule"]) or raw(vector.get("tolerance", 0))
    assert rule.matches(vector["requested"], vector["observed"]) is vector["matches"]


def test_a_normalized_value_never_matches_nan_and_the_tolerance_is_one_host_step():
    assert not NORMALIZED.matches(0.5, float("nan"))
    assert NORMALIZED.matches(0.5, 0.5 + 1 / (PARAMETER_RESOLUTION - 1))
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
