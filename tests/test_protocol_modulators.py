from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TRACK

UUID = "abcdef01-2345-6789-abcd-0123456789ab"
TRACK_REF = {"kind": "track", "id": TRACK, "name": "Lead"}
DEVICE = {"kind": "device", "track": TRACK_REF, "index": 0, "name": "Poly Grid"}
MODULATOR = {"kind": "modulator", "device": DEVICE, "id": UUID, "name": "LFO"}


@pytest.mark.parametrize(
    "changes",
    [
        {"track": {**TRACK_REF, "id": "1-1-1-1-1"}},
        {"track": {**TRACK_REF, "id": TRACK.replace("-", "")}},
        {"track": {**TRACK_REF, "name": None}},
        {"index": 16},
        {"index": True},
        {"name": None},
        {"selected": True},
    ],
)
def test_the_parent_device_ref_is_strict(changes):
    with pytest.raises(ValidationError):
        validate_call("modulators.list", {"parent": {**DEVICE, **changes}})


@pytest.mark.parametrize(
    "changes",
    [
        {"id": "1-1-1-1-1"},
        {"id": TRACK.replace("-", "")},
        {"id": None},
        {"name": None},
        {"index": 0},
        {"selected": True},
    ],
)
def test_the_modulator_ref_is_strict(changes):
    with pytest.raises(ValidationError):
        validate_call("modulators.get", {"target": {**MODULATOR, **changes}})


@pytest.mark.parametrize("parent", [MODULATOR, TRACK_REF, {"trackId": TRACK, "deviceIndex": 0}])
def test_list_takes_only_a_device_ref(parent):
    with pytest.raises(ValidationError):
        validate_call("modulators.list", {"parent": parent})


@pytest.mark.parametrize(
    ("field", "values"),
    [
        ("offset", [None, True, 1.0, "1", -1, 100001]),
        ("limit", [None, False, 1.0, "1", 0, 129]),
    ],
)
def test_modulator_pagination_requires_bounded_integer_tokens(field, values):
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("modulators.list", {"parent": DEVICE, field: value})


def test_list_defaults_are_local():
    model: Any = TOOLS["modulators.list"].model.model_validate({"parent": DEVICE})
    assert (model.offset, model.limit) == (0, 64)
    assert validate_call("modulators.list", {"parent": DEVICE}) == ({"parent": DEVICE}, 5000)
    for offset, limit in [(0, 1), (100000, 128)]:
        args = {"parent": DEVICE, "offset": offset, "limit": limit}
        assert validate_call("modulators.list", args)[0] == args


@pytest.mark.parametrize(
    "method", ["modulators.get", "modulators.delete", "modulators.insert", "modulators.map", "modulators.unmap"]
)
def test_methods_reject_the_old_address_and_missing_arguments(method):
    example = TOOLS[method].examples[0]
    for field in set(example) - {"expect", "outputId", "transfer", "enabled", "offset", "limit"}:
        with pytest.raises(ValidationError):
            validate_call(method, {key: value for key, value in example.items() if key != field})
    for old in ({"modulatorId": UUID}, {"expectedCount": 0}, {"expectedAmount": 0.5}, {"unknown": 1}):
        with pytest.raises(ValidationError):
            validate_call(method, {**example, **old})


@pytest.mark.parametrize(
    "name",
    ["modulators.capabilities", "modulators.catalog", "modulators.parameters.get", "modulators.parameters.set"],
)
def test_the_old_modulator_methods_are_gone(name):
    assert name not in TOOLS
    with pytest.raises(ValueError, match="Unknown method"):
        validate_call(name, {})


def test_a_modulator_control_is_written_with_parameters_set():
    target = {"kind": "parameter", "owner": MODULATOR, "id": "RATE"}
    for value in (0.25, 2, True, {"display": "2 Hz"}, {"label": "Bipolar"}):
        params, _ = validate_call("parameters.set", {"target": target, "value": value, "expect": {"value": 0.5}})
        assert params["value"] == value
