from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TARGET, TRACK


@pytest.mark.parametrize("method", ["modulators.list", "modulators.parameters.get"])
@pytest.mark.parametrize(
    "changes",
    [
        {"trackId": "1-1-1-1-1"},
        {"trackId": "{" + TRACK + "}"},
        {"trackId": TRACK.replace("-", "")},
        {"trackId": None},
        {"deviceIndex": True},
        {"deviceIndex": 1.0},
        {"deviceIndex": "1"},
        {"deviceIndex": -1},
        {"deviceIndex": 16},
        {"expectedDeviceName": ""},
        {"expectedDeviceName": None},
        {"expectedDeviceName": "𝄞" * 2049},
        {"layerId": TRACK},
        {"timeoutMs": 500},
        {"selected": True},
    ],
)
def test_modulator_parent_reuses_strict_root_device_contract(method, changes):
    args = TOOLS[method].examples[0]
    with pytest.raises(ValidationError):
        validate_call(method, {**args, "parent": {**args["parent"], **changes}})


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
            validate_call("modulators.list", {"parent": TARGET, field: value})


def test_modulator_defaults_are_local_and_layer_wire_shape_is_unchanged():
    model: Any = TOOLS["modulators.list"].model.model_validate({"parent": TARGET})
    assert (model.offset, model.limit) == (0, 64)
    assert validate_call("modulators.list", {"parent": TARGET}) == ({"parent": TARGET}, 5000)
    assert validate_call("devices.layers.list", {"parent": TARGET}) == ({"parent": TARGET}, 5000)
    for offset, limit in [(0, 1), (100000, 128)]:
        args = {"parent": TARGET, "offset": offset, "limit": limit}
        assert validate_call("modulators.list", args)[0] == args


@pytest.mark.parametrize("parameter_id", ["", " LABEL \t\n", "𝄞" * 512, "x" * 1024])
def test_modulator_exact_ids_preserve_empty_unicode_and_whitespace(parameter_id):
    args = {
        "parent": {**TARGET, "trackId": TRACK.upper(), "deviceIndex": 15},
        "modulatorId": TRACK.upper(),
        "parameterId": parameter_id,
    }
    assert validate_call("modulators.parameters.get", args) == (args, 5000)


@pytest.mark.parametrize(
    "changes",
    [
        {"modulatorId": "1-1-1-1-1"},
        {"modulatorId": TRACK.replace("-", "")},
        {"modulatorId": "Macro"},
        {"modulatorId": 0},
        {"modulatorId": None},
        {"parameterId": None},
        {"parameterId": True},
        {"parameterId": 0},
        {"parameterId": "𝄞" * 513},
        {"parameterId": "x" * 1025},
        {"parent": None},
        {"parent": {}},
        {"offset": 0},
        {"value": ""},
        {"typeId": TRACK},
        {"modulatorIndex": 0},
        {"name": "Macro"},
    ],
)
def test_modulator_get_rejects_aliases_coercion_null_and_write_fields(changes):
    with pytest.raises(ValidationError):
        validate_call("modulators.parameters.get", {**TOOLS["modulators.parameters.get"].examples[0], **changes})


@pytest.mark.parametrize("method", ["modulators.capabilities", "modulators.list", "modulators.parameters.get"])
def test_modulator_methods_reject_extra_fields_and_missing_required_arguments(method):
    args = TOOLS[method].examples[0]
    with pytest.raises(ValidationError):
        validate_call(method, {**args, "unexpected": None})
    required = TOOLS[method].model.model_json_schema().get("required", [])
    for field in required:
        with pytest.raises(ValidationError):
            validate_call(method, {key: value for key, value in args.items() if key != field})
