from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TRACK
from bitwig_agent.protocol.grid import DEVICE, TARGET


@pytest.mark.parametrize(
    ("field", "values"),
    [
        ("query", [None, 1, True, [], "x" * 129, "𝄞" * 65]),
        ("offset", [-1, 100001, 0.0, "0", True, None]),
        ("limit", [0, 257, 1.0, "1", False, None]),
    ],
)
def test_grid_catalog_rejects_unbounded_or_coerced_queries(field, values):
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("grid.catalog", {field: value})


@pytest.mark.parametrize(
    ("field", "values"),
    [
        ("moduleOffset", [-1, 100001, 0.0, "0", True, None]),
        ("moduleLimit", [0, 257, 1.0, "1", False, None]),
        ("includeParameters", [0, 1, "true", None, []]),
    ],
)
def test_grid_get_rejects_implicit_targets_and_invalid_windows(field, values):
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("grid.get", {**TARGET, field: value})


@pytest.mark.parametrize(
    ("method", "args", "extra"),
    [
        ("grid.catalog", {}, {"includeDevices": True}),
        ("grid.catalog", {}, {"moduleLimit": 64}),
        ("grid.get", TARGET, {"selected": True}),
        ("grid.get", TARGET, {"offset": 0}),
        ("grid.get", TARGET, {"revision": "invented"}),
    ],
)
def test_grid_reads_reject_extra_fields_and_wrong_method_options(method, args, extra):
    with pytest.raises(ValidationError):
        validate_call(method, {**args, **extra})


def test_grid_accepts_explicit_false_and_unicode_window_boundaries():
    for query in ("", "x" * 128, "𝄞" * 64):
        args = {"query": query, "offset": 100000, "limit": 256}
        assert validate_call("grid.catalog", args)[0] == args
    for offset, limit in ((0, 1), (100000, 256)):
        args = {
            **TARGET,
            "moduleOffset": offset,
            "moduleLimit": limit,
            "includeParameters": False,
            "timeoutMs": 10000,
        }
        params, timeout = validate_call("grid.get", args)
        assert params == {key: value for key, value in args.items() if key != "timeoutMs"}
        assert timeout == 10000


def device(**changes):
    return {**DEVICE, **changes}


@pytest.mark.parametrize(
    "target",
    [
        None,
        {},
        {"kind": "track", "id": TRACK, "name": "x"},
        device(index=16),
        device(index=-1),
        device(index=True),
        device(name=None),
        {key: value for key, value in DEVICE.items() if key != "track"},
        {**DEVICE, "trackId": TRACK},
        {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"},
    ],
)
@pytest.mark.parametrize("method", ["grid.get", "grid.voicing.get", "grid.apply", "grid.export", "grid.render"])
def test_grid_methods_take_a_device_ref_as_target(method, target):
    args = {"target": target, "modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}], "outPath": "/tmp/g.svg"}
    args = {k: v for k, v in args.items() if k in TOOLS[method].model.model_fields}
    with pytest.raises(ValidationError):
        validate_call(method, args)


def test_grid_targets_may_sit_in_a_chain_ref_and_must_not_be_missing():
    chain = {
        "kind": "device",
        "chain": {"kind": "chain", "device": DEVICE, "id": TRACK, "name": "Layer"},
        "index": 0,
        "name": "Grid",
    }
    assert validate_call("grid.get", {"target": chain})[0] == {"target": chain}
    with pytest.raises(ValidationError):
        validate_call("grid.get", {})


GRID_SET = {
    **TARGET,
    "moduleId": TRACK,
    "parameterId": "",
    "expectedKind": "float",
    "expectedValue": 0.25,
    "value": 0.5,
}


@pytest.mark.parametrize(
    ("kind", "expected", "value"),
    [
        ("boolean", False, True),
        ("integer", -(2**31), 2**31 - 1),
        ("enum", 17, -3),
        ("float", 2**53 + 1, 2**53 + 2),
        ("float", -0.0, 0),
        ("float", 1.7976931348623157e308, 5e-324),
    ],
)
def test_grid_parameter_set_preserves_raw_types_without_normalizing(kind, expected, value):
    args = {**GRID_SET, "expectedKind": kind, "expectedValue": expected, "value": value}
    params, _timeout = validate_call("grid.parameters.set", args)
    assert params == args
    assert type(params["expectedValue"]) is type(expected)
    assert type(params["value"]) is type(value)


@pytest.mark.parametrize(
    "changes",
    [
        {"expectedKind": "boolean", "expectedValue": False, "value": 1},
        {"expectedKind": "boolean", "expectedValue": 0, "value": True},
        {"expectedKind": "integer", "expectedValue": 0, "value": 1.0},
        {"expectedKind": "integer", "expectedValue": False, "value": 1},
        {"expectedKind": "integer", "expectedValue": 0, "value": 2**31},
        {"expectedKind": "enum", "expectedValue": -(2**31) - 1, "value": 0},
        {"expectedKind": "enum", "expectedValue": 0.0, "value": 1},
        {"expectedValue": True},
        {"value": False},
        {"expectedValue": "0.25"},
        {"value": None},
        {"expectedValue": float("nan")},
        {"value": float("inf")},
        {"expectedValue": 10**400},
        {"value": -(10**400)},
        {"expectedKind": "Float"},
        {"expectedKind": None},
        {"contextToken": "not-a-grid-token"},
        {"normalizedValue": 0.5},
    ],
)
def test_grid_parameter_set_rejects_kind_mismatch_nonfinite_and_coercion(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {**GRID_SET, **changes})


def test_grid_parameter_set_requires_explicit_compare_and_set_value():
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {key: value for key, value in GRID_SET.items() if key != "expectedValue"})


GRID_MODULE = {**TARGET, "moduleId": TRACK}
GRID_MOVE = {**GRID_MODULE, "expectedX": 0, "expectedY": 0, "x": 4, "y": 2}
GRID_INSERT = {**TARGET, "typeId": TRACK, "x": 4, "y": 2}


@pytest.mark.parametrize("field", ["x", "y"])
@pytest.mark.parametrize(
    "value",
    [
        None,
        True,
        False,
        1.0,
        "1",
        [],
        {},
        -(2**31) - 1,
        2**31,
    ],
)
def test_grid_insert_rejects_non_int32_coordinates(field, value):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.insert", {**GRID_INSERT, field: value})


@pytest.mark.parametrize("value", [-(2**31), 2**31 - 1])
def test_grid_insert_accepts_int32_endpoints_for_host_geometry_validation(value):
    params, timeout = validate_call("grid.modules.insert", {**GRID_INSERT, "x": value, "y": value})
    assert params == {**GRID_INSERT, "x": value, "y": value}
    assert timeout == 5000


@pytest.mark.parametrize(
    "changes",
    [
        {"typeId": "1-1-1-1-1"},
        {"typeId": TRACK.replace("-", "")},
        {"typeId": "oscillator"},
        {"typeId": None},
        {"typeId": True},
        {"moduleId": TRACK},
        {"path": "/tmp/module.bwmodule"},
        {"expectedX": 0},
        {"relative": True},
        {"selected": True},
        {"timeoutMs": None},
        {"timeoutMs": True},
        {"timeoutMs": 99},
        {"timeoutMs": 60001},
    ],
)
def test_grid_insert_rejects_invalid_targets_and_implicit_options(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.insert", {**GRID_INSERT, **changes})


@pytest.mark.parametrize("field", ["expectedX", "expectedY", "x", "y"])
@pytest.mark.parametrize(
    "value",
    [
        None,
        True,
        False,
        1.0,
        "1",
        [],
        {},
        -(2**31) - 1,
        2**31,
    ],
)
def test_grid_module_move_rejects_non_int32_coordinates(field, value):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.move", {**GRID_MOVE, field: value})


@pytest.mark.parametrize("value", [-(2**31), -65537, 65537, 2**31 - 1])
def test_grid_module_move_leaves_dynamic_geometry_bounds_to_host(value):
    fields = ("expectedX", "expectedY", "x", "y")
    params, _ = validate_call("grid.modules.move", {**GRID_MODULE, **dict.fromkeys(fields, value)})
    assert tuple(params[field] for field in fields) == (value,) * 4


@pytest.mark.parametrize(
    ("method", "args"),
    [
        ("grid.modules.move", GRID_MOVE),
        ("grid.modules.delete", GRID_MODULE),
        ("grid.modules.insert", GRID_INSERT),
    ],
)
def test_grid_module_edits_require_each_explicit_field(method, args):
    for field in args:
        with pytest.raises(ValidationError):
            validate_call(method, {key: value for key, value in args.items() if key != field})


@pytest.mark.parametrize(
    ("method", "args"),
    [
        ("grid.modules.move", GRID_MOVE),
        ("grid.modules.delete", GRID_MODULE),
    ],
)
@pytest.mark.parametrize(
    "changes",
    [
        {"moduleId": "selected"},
        {"moduleId": "1-1-1-1-1"},
        {"moduleId": None},
        {"moduleId": True},
        {"moduleId": 1.0},
        {"moduleId": TRACK.replace("-", "")},
        {"selected": True},
        {"moduleIndex": 0},
        {"typeId": TRACK},
        {"relative": True},
        {"copy": False},
        {"parameterId": ""},
        {"force": True},
        {"timeoutMs": None},
        {"timeoutMs": 99},
        {"timeoutMs": 60001},
    ],
)
def test_grid_module_edits_reject_invalid_targets_and_implicit_options(method, args, changes):
    with pytest.raises(ValidationError):
        validate_call(method, {**args, **changes})


@pytest.mark.parametrize("field", ["expectedX", "expectedY", "x", "y"])
def test_grid_module_delete_accepts_no_coordinate_options(field):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.delete", {**GRID_MODULE, field: 0})


GRID_CONNECTION = {**TARGET, "targetModuleId": TRACK, "targetPortKey": "", "expectedSource": None, "source": None}
GRID_SOURCE = {"moduleId": TRACK, "portKey": ""}
GRID_SOURCE_NON_STRING_KEY: dict[Any, Any] = {**GRID_SOURCE, 1: "not-a-string-key"}


@pytest.mark.parametrize("expected", [None, GRID_SOURCE])
@pytest.mark.parametrize("source", [None, GRID_SOURCE])
def test_grid_connection_preserves_required_nulls_and_endpoint_objects(expected, source):
    args = {**GRID_CONNECTION, "expectedSource": expected, "source": source}
    params, _ = validate_call("grid.connections.set", args)
    assert params == args


@pytest.mark.parametrize("field", list(GRID_CONNECTION))
def test_grid_connection_requires_every_target_and_cas_field(field):
    with pytest.raises(ValidationError):
        validate_call("grid.connections.set", {key: value for key, value in GRID_CONNECTION.items() if key != field})


@pytest.mark.parametrize("field", ["expectedSource", "source"])
@pytest.mark.parametrize(
    "value",
    [
        "",
        "null",
        True,
        0,
        1.0,
        [],
        [GRID_SOURCE],
        {},
        {"moduleId": TRACK},
        {"portKey": ""},
        {**GRID_SOURCE, "moduleId": "1-1-1-1-1"},
        {**GRID_SOURCE, "moduleId": TRACK.replace("-", "")},
        {**GRID_SOURCE, "moduleId": None},
        {**GRID_SOURCE, "moduleId": 1},
        {**GRID_SOURCE, "portKey": None},
        {**GRID_SOURCE, "portKey": 0},
        {**GRID_SOURCE, "portKey": False},
        {**GRID_SOURCE, "portKey": []},
        {**GRID_SOURCE, "extra": True},
        GRID_SOURCE_NON_STRING_KEY,
    ],
)
def test_grid_connection_rejects_malformed_nested_endpoints(field, value):
    with pytest.raises(ValidationError):
        validate_call("grid.connections.set", {**GRID_CONNECTION, field: value})


@pytest.mark.parametrize(
    "changes",
    [
        {"targetModuleId": "1-1-1-1-1"},
        {"targetModuleId": TRACK.replace("-", "")},
        {"targetModuleId": None},
        {"targetModuleId": True},
        {"targetPortKey": None},
        {"targetPortKey": 0},
        {"targetPortKey": False},
        {"force": True},
        {"moduleId": TRACK},
        {1: "not-a-string-key"},
    ],
)
def test_grid_connection_rejects_invalid_targets_and_extra_fields(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.connections.set", {**GRID_CONNECTION, **changes})


@pytest.mark.parametrize("field", ["targetPortKey", "expectedSource", "source"])
@pytest.mark.parametrize("key", ["", "a" * 1024, "𝄞" * 512])
def test_grid_connection_accepts_exact_utf16_key_boundary(field, key):
    value = key if field == "targetPortKey" else {**GRID_SOURCE, "portKey": key}
    params, _ = validate_call("grid.connections.set", {**GRID_CONNECTION, field: value})
    assert params[field] == value


@pytest.mark.parametrize("field", ["targetPortKey", "expectedSource", "source"])
@pytest.mark.parametrize("key", ["a" * 1025, "𝄞" * 513, "a" + "𝄞" * 512])
def test_grid_connection_rejects_keys_exceeding_utf16_boundary(field, key):
    value = key if field == "targetPortKey" else {**GRID_SOURCE, "portKey": key}
    with pytest.raises(ValidationError):
        validate_call("grid.connections.set", {**GRID_CONNECTION, field: value})


GRID_RENAME = {**TARGET, "moduleId": TRACK, "expectedCustomName": "", "customName": ""}


@pytest.mark.parametrize("field", list(GRID_RENAME))
def test_grid_rename_requires_each_identity_and_raw_cas_field(field):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.set", {key: value for key, value in GRID_RENAME.items() if key != field})


@pytest.mark.parametrize("field", ["expectedCustomName", "customName"])
@pytest.mark.parametrize(
    "value",
    [
        None,
        True,
        False,
        0,
        1.0,
        [],
        {},
        b"name",
        "x" * 1025,
        "𝄞" * 513,
        "x" * 1023 + "𝄞",
    ],
)
def test_grid_rename_rejects_coercion_null_and_utf16_overflow(field, value):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.set", {**GRID_RENAME, field: value})


@pytest.mark.parametrize(
    "changes",
    [
        {"moduleId": "Union"},
        {"moduleId": "1-1-1-1-1"},
        {"moduleId": TRACK.replace("-", "")},
        {"moduleId": None},
        {"moduleId": True},
        {"typeId": TRACK},
        {"name": "Lead"},
        {"parameterId": ""},
        {"selected": True},
        {"includeParameters": False},
        {"force": True},
        {"timeoutMs": None},
    ],
)
def test_grid_rename_rejects_aliases_implicit_selection_and_wrong_target_types(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.set", {**GRID_RENAME, **changes})


GRID_WRITE = {"moduleId": TRACK, "parameterId": "", "expectedKind": "float", "expectedValue": 0.25, "value": 0.5}


def test_grid_parameter_set_accepts_a_batch_and_preserves_each_raw_entry():
    writes = [
        GRID_WRITE,
        {**GRID_WRITE, "parameterId": "B", "expectedKind": "boolean", "expectedValue": False, "value": True},
    ]
    params, _ = validate_call("grid.parameters.set", {**TARGET, "writes": writes})
    assert params == {**TARGET, "writes": writes}
    assert type(params["writes"][1]["value"]) is bool
    assert validate_call(
        "grid.parameters.set", {**TARGET, "writes": [{**GRID_WRITE, "parameterId": str(i)} for i in range(64)]}
    )


@pytest.mark.parametrize(
    "changes",
    [
        {"writes": []},
        {"writes": [{**GRID_WRITE, "parameterId": str(i)} for i in range(65)]},
        {"writes": [GRID_WRITE, GRID_WRITE]},
        {"writes": [GRID_WRITE, {**GRID_WRITE, "moduleId": TRACK.upper()}]},
        {"writes": [{**GRID_WRITE, "value": "0.5"}]},
        {"writes": [{**GRID_WRITE, "expectedKind": "boolean"}]},
        {"writes": [{key: value for key, value in GRID_WRITE.items() if key != "expectedValue"}]},
        {"writes": [{**GRID_WRITE, "extra": 1}]},
        {"writes": [GRID_WRITE], "value": 0.5},
        {"writes": [GRID_WRITE], "moduleId": TRACK},
        {"writes": None},
    ],
)
def test_grid_parameter_set_rejects_malformed_or_mixed_batches(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {**TARGET, **changes})


def test_grid_parameter_set_rejects_a_partial_single_form_and_an_empty_call():
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {**TARGET, "moduleId": TRACK, "parameterId": "", "value": 0.5})
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", dict(TARGET))


def test_grid_get_accepts_module_filter_and_diagnostics_and_rejects_bad_ones():
    params, _ = validate_call("grid.get", {**TARGET, "moduleIds": [TRACK], "diagnostics": True})
    assert params == {**TARGET, "moduleIds": [TRACK], "diagnostics": True}
    assert validate_call("grid.get", {**TARGET, "moduleIds": [TRACK] * 256})
    bad: Any
    for bad in ([], [TRACK] * 257, ["not-a-uuid"], [None], None, TRACK):
        with pytest.raises(ValidationError):
            validate_call("grid.get", {**TARGET, "moduleIds": bad})
    for bad in (0, 1, "true", None):
        with pytest.raises(ValidationError):
            validate_call("grid.get", {**TARGET, "diagnostics": bad})


@pytest.mark.parametrize(
    ("method", "args"),
    [
        ("grid.modules.insert", GRID_INSERT),
        ("grid.modules.move", GRID_MOVE),
        ("grid.modules.delete", GRID_MODULE),
        ("grid.modules.set", {**GRID_MODULE, "expectedCustomName": "", "customName": "x"}),
        (
            "grid.connections.set",
            {**TARGET, "targetModuleId": TRACK, "targetPortKey": "in", "expectedSource": None, "source": None},
        ),
    ],
)
def test_topology_writes_take_a_diff_or_full_graph_mode_only(method, args):
    for mode in ("diff", "full"):
        assert validate_call(method, {**args, "graph": mode})[0] == {**args, "graph": mode}
    for bad in ("compact", "", None, True, "FULL"):
        with pytest.raises(ValidationError):
            validate_call(method, {**args, "graph": bad})
    assert "graph" not in validate_call(method, args)[0]


def test_graph_mode_is_not_accepted_by_reads_or_parameter_writes():
    for method, args in (
        ("grid.get", TARGET),
        ("grid.parameters.set", GRID_SET),
    ):
        with pytest.raises(ValidationError):
            validate_call(method, {**args, "graph": "full"})


GRID_EDIT = {"targetModuleId": TRACK, "targetPortKey": "", "expectedSource": None, "source": GRID_SOURCE}


def test_grid_connection_batch_preserves_each_entry_and_explicit_nulls():
    edits = [GRID_EDIT, {**GRID_EDIT, "targetPortKey": "b", "expectedSource": GRID_SOURCE, "source": None}]
    params, _ = validate_call("grid.connections.set", {**TARGET, "connections": edits, "graph": "full"})
    assert params == {**TARGET, "connections": edits, "graph": "full"}
    assert validate_call(
        "grid.connections.set", {**TARGET, "connections": [{**GRID_EDIT, "targetPortKey": str(i)} for i in range(32)]}
    )


@pytest.mark.parametrize(
    "changes",
    [
        {"connections": []},
        {"connections": [{**GRID_EDIT, "targetPortKey": str(i)} for i in range(33)]},
        {"connections": [GRID_EDIT, GRID_EDIT]},
        {"connections": [GRID_EDIT, {**GRID_EDIT, "targetModuleId": TRACK.upper()}]},
        {"connections": [{key: value for key, value in GRID_EDIT.items() if key != "source"}]},
        {"connections": [{key: value for key, value in GRID_EDIT.items() if key != "expectedSource"}]},
        {"connections": [{**GRID_EDIT, "extra": 1}]},
        {"connections": [GRID_EDIT], "targetPortKey": ""},
        {"connections": [GRID_EDIT], "source": None},
        {"connections": None},
    ],
)
def test_grid_connection_batch_rejects_malformed_or_mixed_forms(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.connections.set", {**TARGET, **changes})


def test_grid_insert_accepts_type_name_or_type_id_but_not_both_or_neither():
    named = {key: value for key, value in GRID_INSERT.items() if key != "typeId"} | {"typeName": "Low-pass"}
    assert validate_call("grid.modules.insert", named)[0] == named
    for bad in (
        {**GRID_INSERT, "typeName": "Low-pass"},
        {key: value for key, value in GRID_INSERT.items() if key != "typeId"},
        {**named, "typeName": ""},
        {**named, "typeName": "x" * 129},
        {**named, "typeName": None},
    ):
        with pytest.raises(ValidationError):
            validate_call("grid.modules.insert", bad)


def test_grid_insert_batch_is_exclusive_bounded_and_per_entry_strict():
    entry = {"typeName": "Low-pass", "x": 4, "y": 2}
    base = {key: GRID_INSERT[key] for key in TARGET}
    params, _ = validate_call("grid.modules.insert", {**base, "modules": [entry, {"typeId": TRACK, "x": 8, "y": 2}]})
    assert params["modules"][1] == {"typeId": TRACK, "x": 8, "y": 2}
    validate_call("grid.modules.insert", {**base, "modules": [entry] * 17})
    for bad in (
        {**base, "modules": []},
        {**base, "modules": [entry], "x": 1},
        {**base, "modules": [entry], "typeName": "a"},
        {**base, "modules": [{"x": 1, "y": 1}]},
        {**base, "modules": [{**entry, "typeId": TRACK}]},
        {**base, "modules": [{**entry, "x": 1.5}]},
        {**base, "modules": [{**entry, "extra": 1}]},
        {**base, "modules": [{"typeName": "a", "x": 1}]},
        {**base, "modules": None},
    ):
        with pytest.raises(ValidationError):
            validate_call("grid.modules.insert", bad)


def test_grid_parameter_set_display_replaces_value_for_float_only():
    display = {key: value for key, value in GRID_SET.items() if key != "value"} | {"display": "1.2 kHz"}
    assert validate_call("grid.parameters.set", display)[0] == display
    batch: dict[str, Any] = {
        **TARGET,
        "writes": [
            {key: display[key] for key in ("moduleId", "parameterId", "expectedKind", "expectedValue", "display")},
            {
                **{k: GRID_SET[k] for k in ("moduleId", "parameterId", "expectedKind", "expectedValue")},
                "parameterId": "other",
                "value": 1,
            },
        ],
    }
    assert validate_call("grid.parameters.set", batch)[0] == batch
    for bad in (
        {**display, "value": 0.5},
        {key: value for key, value in display.items() if key != "display"},
        {**display, "display": ""},
        {**display, "display": "x" * 65},
        {**display, "display": 5},
        {**display, "display": None},
        {**display, "expectedKind": "integer", "expectedValue": 1},
        {**TARGET, "writes": [{**batch["writes"][0], "value": 1}]},
        {**TARGET, "writes": [{key: value for key, value in batch["writes"][0].items() if key != "display"}]},
        {**TARGET, "writes": [batch["writes"][0]], "display": "1 Hz"},
    ):
        with pytest.raises(ValidationError):
            validate_call("grid.parameters.set", bad)


def test_grid_insert_type_name_accepts_symbol_only_names():
    named = {key: value for key, value in GRID_INSERT.items() if key != "typeId"}
    for ok in ("<", "=", ">", "\u2260", "\u2264", "\u2265", "Slope \u2197", "Osc 2", "---"):
        validate_call("grid.modules.insert", {**named, "typeName": ok})
        validate_call(
            "grid.modules.insert",
            {
                "target": GRID_INSERT["target"],
                **{k: named[k] for k in named if k not in ("typeName", "x", "y")},
                "modules": [{"typeName": ok, "x": 1, "y": 1}],
            },
        )
    with pytest.raises(ValidationError):
        validate_call("grid.modules.insert", {**named, "typeName": ""})


@pytest.mark.parametrize(
    ("expected", "value"), [("", "x"), ("Pad\n\U0001f600 ", ""), ("a" * 1024, "b" * 1024), ("1", "true")]
)
def test_grid_parameter_set_accepts_string_panel_text_verbatim(expected, value):
    args = {**GRID_SET, "expectedKind": "string", "expectedValue": expected, "value": value}
    params, _ = validate_call("grid.parameters.set", args)
    assert params == args
    batch = {**TARGET, "writes": [{**GRID_WRITE, "expectedKind": "string", "expectedValue": expected, "value": value}]}
    assert validate_call("grid.parameters.set", batch)[0] == batch


@pytest.mark.parametrize(
    "changes",
    [
        {"value": "a" * 1025},
        {"expectedValue": "a" * 1025},
        {"value": "\U0001f600" * 513},
        {"value": 1},
        {"expectedValue": True},
    ],
)
def test_grid_parameter_set_rejects_bad_string_panel_writes(changes):
    args = {**GRID_SET, "expectedKind": "string", "expectedValue": "a", "value": "b", **changes}
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", args)


def test_grid_parameter_set_rejects_display_for_string_kind():
    args = {key: value for key, value in GRID_SET.items() if key != "value"}
    args.update(expectedKind="string", expectedValue="a", display="1 kHz")
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", args)


@pytest.mark.parametrize(("kind", "field"), [("float", "value"), ("boolean", "expectedValue"), ("integer", "value")])
def test_grid_parameter_set_rejects_text_for_scalar_kinds(kind, field):
    base = {"float": (0.25, 0.5), "boolean": (False, True), "integer": (0, 1)}[kind]
    args = {**GRID_SET, "expectedKind": kind, "expectedValue": base[0], "value": base[1], field: "text"}
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", args)


GRID_STEPS = {
    **TARGET,
    "moduleId": TRACK,
    "parameterId": "DATA",
    "expectedKind": "steps",
    "expectedValues": [0.1, 0.0, -1, 0.5],
    "values": [0.5, 1.0],
}


def test_grid_steps_write_is_a_prefix_cas_in_single_and_batch_form():
    params, _ = validate_call("grid.parameters.set", GRID_STEPS)
    assert params == GRID_STEPS
    assert validate_call("grid.parameters.set", {**GRID_STEPS, "full": True})
    entry = {key: GRID_STEPS[key] for key in ("moduleId", "parameterId", "expectedKind", "expectedValues", "values")}
    assert validate_call(
        "grid.parameters.set", {**TARGET, "writes": [entry, {**entry, "parameterId": "B", "full": False}]}
    )
    assert validate_call(
        "grid.parameters.set",
        {**GRID_STEPS, "expectedValues": [0.0] * 256, "values": [-128, 128.0] * 128, "full": True},
    )


@pytest.mark.parametrize(
    "changes",
    [
        {"expectedValues": []},
        {"values": []},
        {"expectedValues": [0.0] * 257},
        {"values": [0.0] * 257},
        {"values": [0.0] * 5},
        {"values": [128.0000001]},
        {"values": [-128.0000001]},
        {"values": [float("nan")]},
        {"expectedValues": [float("inf")]},
        {"values": ["0.5"]},
        {"values": [True]},
        {"expectedValues": [None]},
        {"values": 0.5},
        {"full": 1},
        {"value": 0.5},
        {"expectedValue": 0.0},
        {"display": "1 kHz"},
    ],
)
def test_grid_steps_write_rejects_malformed_arrays_and_mixed_forms(changes):
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {**GRID_STEPS, **changes})
    entry = {key: GRID_STEPS[key] for key in ("moduleId", "parameterId", "expectedKind", "expectedValues", "values")}
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {**TARGET, "writes": [{**entry, **changes}]})


@pytest.mark.parametrize("key", ["expectedValues", "values"])
def test_grid_steps_write_needs_both_arrays(key):
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {k: v for k, v in GRID_STEPS.items() if k != key})


@pytest.mark.parametrize("extra", [{"expectedValues": [0.0]}, {"values": [0.5]}, {"full": True}])
def test_scalar_writes_reject_step_array_fields(extra):
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.set", {**GRID_SET, **extra})


def test_grid_reads_accept_full_for_step_arrays():
    assert validate_call("grid.get", {**TARGET, "full": True})
    with pytest.raises(ValidationError):
        validate_call("grid.get", {**TARGET, "full": "yes"})


def test_grid_voicing_get_and_set():
    assert TOOLS["grid.voicing.get"].read_only
    assert not TOOLS["grid.voicing.set"].read_only
    assert validate_call("grid.voicing.get", TARGET)
    args = {**TARGET, "expected": {"voices": 1, "monoMode": "true_mono"}, "set": {"voices": 8, "monoMode": "digi_mono"}}
    params, _ = validate_call("grid.voicing.set", args)
    assert params == args
    everything = {
        "voices": 64,
        "voiceStacking": 16,
        "monoMode": "digi_mono",
        "retriggerMode": "always",
        "notePriority": "high",
        "legatoGlide": True,
        "stealSameKey": False,
        "allowOverlappingNotes": True,
    }
    assert validate_call("grid.voicing.set", {**TARGET, "expected": everything, "set": everything})


@pytest.mark.parametrize(
    "changes",
    [
        {"set": {}},
        {"expected": {}},
        {"expected": {"voices": 1}, "set": {"voices": 2, "voiceStacking": 2}},
        {"set": {"voices": 0}},
        {"set": {"voices": 65}},
        {"set": {"voiceStacking": 17}},
        {"set": {"voices": 1.0}},
        {"set": {"voices": True}},
        {"set": {"monoMode": "poly"}},
        {"set": {"retriggerMode": "sometimes"}},
        {"set": {"notePriority": "FIRST"}},
        {"set": {"legatoGlide": 1}},
        {"set": {"bogus": 1}},
        {"expected": {"voices": 65}},
    ],
)
def test_grid_voicing_set_rejects_bad_values_and_missing_preconditions(changes):
    args = {**TARGET, "expected": {"voices": 1}, "set": {"voices": 2}, **changes}
    with pytest.raises(ValidationError):
        validate_call("grid.voicing.set", args)


GRID_MODULE_REF = {"kind": "gridModule", "device": DEVICE, "id": TRACK, "name": "Mx: darkness"}
SLOT = {"kind": "parameter", "owner": GRID_MODULE_REF, "id": "VALUE"}
PAGE = {"kind": "remotePage", "device": DEVICE, "index": 1, "name": "Macros"}
OWN = {"kind": "parameter", "owner": DEVICE, "id": "CONTENTS/CUTOFF", "name": "Cutoff"}


def test_remote_page_define_is_an_upsert_by_name_and_delete_takes_the_page_ref():
    assert not TOOLS["devices.remote.define"].read_only
    assert not TOOLS["devices.remote.delete"].read_only
    args = {
        **TARGET,
        "name": "Macros",
        "slots": [{"parameter": SLOT}, None, {"parameter": {**SLOT, "id": "GATE"}, "label": "Gate"}],
    }
    assert validate_call("devices.remote.define", args)[0] == args
    assert validate_call(
        "devices.remote.define", {**TARGET, "name": "P" * 256, "slots": [None] * 7 + [{"parameter": SLOT}]}
    )
    assert validate_call("devices.remote.delete", {"target": PAGE})[0] == {"target": PAGE}
    own = {**TARGET, "name": "Macros", "slots": [{"parameter": OWN}, {"parameter": SLOT}]}
    assert validate_call("devices.remote.define", own)[0] == own
    fields = TOOLS["devices.remote.define"].model.model_fields
    assert "mode" not in fields
    assert "pageName" not in fields


@pytest.mark.parametrize(
    "changes",
    [
        {"name": ""},
        {"name": "P" * 257},
        {"slots": []},
        {"slots": [None] * 9},
        {"mode": "create"},
        {"pageName": "Macros"},
        {"slots": [{"moduleId": TRACK, "parameterId": "VALUE"}]},
        {"slots": [SLOT]},
        {"slots": [{"parameter": SLOT}, {"parameter": SLOT, "label": "again"}]},
        {"slots": [{"parameter": SLOT, "label": "l" * 257}]},
        {"slots": [{"parameter": SLOT, "label": None}]},
        {"slots": [{"parameter": SLOT}, {"parameter": {**SLOT, "owner": {**GRID_MODULE_REF, "id": TRACK.upper()}}}]},
        {
            "slots": [
                {"parameter": {**SLOT, "owner": {"kind": "modulator", "device": DEVICE, "id": TRACK, "name": "LFO"}}}
            ]
        },
        {"slots": [{"parameter": OWN}, {"parameter": OWN, "label": "again"}]},
        {
            "slots": [
                {
                    "parameter": {
                        "kind": "parameter",
                        "owner": {"kind": "track", "id": TRACK, "name": "T"},
                        "id": "volume",
                    }
                }
            ]
        },
        {"slots": [1]},
    ],
)
def test_remote_page_define_rejects_bad_requests(changes):
    with pytest.raises(ValidationError):
        validate_call("devices.remote.define", {**TARGET, "name": "Macros", "slots": [{"parameter": SLOT}], **changes})


@pytest.mark.parametrize(
    "args", [{}, {"target": DEVICE}, {"target": PAGE, "pageName": "Macros"}, {**TARGET, "target": PAGE, "mode": "x"}]
)
def test_remote_page_delete_takes_only_a_remote_page_ref(args):
    with pytest.raises(ValidationError):
        validate_call("devices.remote.delete", args)


def test_single_parameter_write_schema_carries_one_closed_branch_per_kind():
    branches = TOOLS["grid.parameters.set"].model.model_json_schema()["oneOf"]
    kinds = [b["properties"].get("expectedKind", {}).get("enum") for b in branches]
    assert kinds == [["boolean"], ["integer", "enum"], ["float"], ["string"], ["float"], ["steps"], None]
    assert all(b["additionalProperties"] is False for b in branches)
    assert [b["required"][-1] for b in branches] == ["value"] * 4 + ["display", "values", "writes"]
    assert branches[2]["properties"]["value"] == {"type": "number"}
    assert "display" not in branches[2]["properties"]
    assert "value" not in branches[4]["properties"]
    assert set(branches[-1]["properties"]) == {"target", "timeoutMs", "writes"}


def test_hidden_grid_parts_stay_out_of_the_surface():
    hidden = {name for name, spec in TOOLS.items() if name.startswith("grid.") and not spec.expose}
    assert hidden == {
        "grid.catalog",
        "grid.get",
        "grid.parameters.set",
        "grid.voicing.get",
        "grid.voicing.set",
        "grid.modules.insert",
        "grid.modules.set",
        "grid.modules.move",
        "grid.modules.delete",
        "grid.connections.set",
    }
    assert {"grid.capabilities", "grid.parameters.get", "grid.graph", "grid.modules.rename"}.isdisjoint(TOOLS)


@pytest.mark.parametrize("params", [{"CUTOFF": {"display": "800 Hz"}}, {"CUTOFF": 84}])
def test_partial_apply_entries_take_name_and_params_only(params):
    module = {"name": "F: lp", "params": params}
    assert validate_call("grid.apply", {**TARGET, "modules": [module]})[0]["modules"] == [module]


@pytest.mark.parametrize(
    "module",
    [
        {"name": "F: lp"},
        {"name": "F: lp", "params": {"CUTOFF": 84}, "x": 0},
        {"name": "F: lp", "params": {"CUTOFF": 84}, "y": 0},
        {"name": "F: lp", "params": {"CUTOFF": 84}, "replace": True},
        {"name": "F: lp", "type": "Low-pass", "params": {"CUTOFF": 84}},
        {"name": "F: lp", "typeId": TRACK, "x": 0},
    ],
)
def test_partial_apply_entries_refuse_position_replace_and_incomplete_whole_entries(module):
    with pytest.raises(ValidationError):
        validate_call("grid.apply", {**TARGET, "modules": [module]})


@pytest.mark.parametrize("extra", [{"prune": True, "namespace": "F: "}, {"pruneAll": True}, {"replace": True}])
def test_partial_apply_entries_refuse_prune_and_replace(extra):
    partial = {"name": "F: lp", "params": {"CUTOFF": 84}}
    with pytest.raises(ValidationError, match="partial"):
        validate_call("grid.apply", {**TARGET, "modules": [partial], **extra})
