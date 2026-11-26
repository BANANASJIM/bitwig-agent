from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TARGET, TRACK
from bitwig_agent.protocol.devices import LAYER_PARENT, LAYER_TARGET
from bitwig_agent.protocol.launcher import NOTE_WINDOW
from bitwig_agent.protocol.limits import SCENE_WINDOW


def note(**changes):
    return {"op": "set", "beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1, **changes}


@pytest.mark.parametrize(
    "edits",
    [
        [note(), note()],
        [note(), note(beat=0.5)],
        [note(beat=3.5)],
        [note(beat=0.1)],
        [note(durationBeats=0.3)],
        [note(pitch=True)],
        [note(velocity=0)],
        [note(velocity=128)],
        [note(extra=1)],
        [{"op": "clear", "beat": 0, "pitch": 60, "durationBeats": 1}],
        [{"op": "clear", "beat": 0, "pitch": 60, "velocity": None}],
    ],
)
def test_rejects_ambiguous_or_out_of_window_note_edits(edits):
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {**NOTE_WINDOW, "edits": edits})


def test_decimal_grid_alignment_is_exact_not_float_rounding():
    args = {**NOTE_WINDOW, "stepSizeBeats": 0.1, "edits": [note(beat=0.3, durationBeats=0.2)]}
    params, _ = validate_call("notes.edit", args)
    assert params["edits"][0]["beat"] == 0.3
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {**args, "edits": [note(beat=0.30000000000000004, durationBeats=0.2)]})


def test_chords_adjacent_notes_and_absolute_window_offsets_are_valid():
    edits = [note(beat=4), note(beat=4, pitch=64), note(beat=5)]
    params, _ = validate_call("notes.edit", {**NOTE_WINDOW, "startStep": 16, "edits": edits})
    assert params["edits"] == edits
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {**NOTE_WINDOW, "startStep": 16, "edits": [note(beat=0)]})


@pytest.mark.parametrize(
    ("kind", "fields"),
    [
        ("file", {"path": "relative.bwpreset"}),
        ("file", {"path": "/tmp/../preset.bwpreset"}),
        ("file", {"path": "/tmp//preset.bwpreset"}),
        ("file", {"path": "//tmp/preset.bwpreset"}),
        ("file", {"path": "/tmp/preset\u0085.bwpreset"}),
        ("file", {"path": "/tmp/preset.bwpreset", "id": "unexpected"}),
        ("clap", {"id": "has space"}),
        ("clap", {"id": "org.valid", "path": "/tmp/preset"}),
        ("clap", {"id": None}),
        ("vst2", {"id": "2147483648"}),
        ("vst2", {"id": "0001"}),
        ("vst3", {"id": "not-32-hex"}),
        ("bitwig", {"id": "missing-hyphens"}),
    ],
)
def test_insertion_rejects_ambiguous_or_malformed_identifiers(kind, fields):
    with pytest.raises(ValidationError):
        validate_call("devices.insert", {"trackId": TRACK, "expectedDeviceCount": 0, "kind": kind, **fields})


def test_file_insertion_preserves_path_and_omits_id():
    args = {"trackId": TRACK, "expectedDeviceCount": 2, "kind": "file", "path": "/tmp/Poly Sketch.bwpreset"}
    params, _ = validate_call("devices.insert", args)
    assert params == args


@pytest.mark.parametrize(
    "path", ["/absolute/path/audio.wav", "/tmp/Audio Sketch.bwclip", "/tmp/音楽.flac", "C:\\Audio Sketch\\音楽.flac"]
)
@pytest.mark.parametrize("slot", [0, SCENE_WINDOW - 1])
def test_launcher_import_preserves_local_path_without_filesystem_checks(path, slot):
    args = {"trackId": TRACK, "slotIndex": slot, "path": path}
    params, timeout = validate_call("launcher.import", args)
    assert params == args
    assert timeout == 5000


@pytest.mark.parametrize(
    "path",
    [
        "",
        "audio.wav",
        "./audio.wav",
        "~/audio.wav",
        "file:///tmp/audio.wav",
        "https://example.com/audio.wav",
        "C:/audio.wav",
        "C:audio.wav",
        "C:\\..\\audio.wav",
        "//tmp/audio.wav",
        "/tmp/../audio.wav",
        "/tmp/./audio.wav",
        "/tmp//audio.wav",
        "/tmp/audio.wav/",
        "/tmp/audio\x00.wav",
        "/tmp/audio\n.wav",
        "/tmp/audio\x7f.wav",
        "/tmp/audio\u0085.wav",
        None,
        True,
        123,
        [],
        {},
        "/" + "a" * 4096,
        "/" + "𝄞" * 2048,
    ],
)
def test_file_import_and_device_insertion_share_path_syntax(path):
    for method, args in [
        ("launcher.import", {"trackId": TRACK, "slotIndex": 0}),
        ("devices.insert", {"trackId": TRACK, "expectedDeviceCount": 0, "kind": "file"}),
    ]:
        with pytest.raises(ValidationError):
            validate_call(method, {**args, "path": path})


@pytest.mark.parametrize(
    "override",
    [
        {"trackId": ""},
        {"trackId": None},
        {"trackId": 1},
        {"trackId": True},
        {"trackId": "Audio 2"},
        {"slotIndex": -1},
        {"slotIndex": SCENE_WINDOW},
        {"slotIndex": 0.0},
        {"slotIndex": "0"},
        {"slotIndex": True},
        {"slotIndex": None},
        {"overwrite": False},
        {"unknown": 1},
        {"timeoutMs": 99},
    ],
)
def test_launcher_import_rejects_invalid_targets_and_extra_fields(override):
    with pytest.raises(ValidationError):
        validate_call(
            "launcher.import", {"trackId": TRACK, "slotIndex": 0, "path": "/absolute/path/audio.wav", **override}
        )


@pytest.mark.parametrize("field", ["trackId", "slotIndex", "path"])
def test_launcher_import_requires_explicit_target_and_path(field):
    args = {"trackId": TRACK, "slotIndex": 0, "path": "/absolute/path/audio.wav"}
    del args[field]
    with pytest.raises(ValidationError):
        validate_call("launcher.import", args)


@pytest.mark.parametrize(
    ("method", "args", "field"),
    [
        ("tracks.set", {"trackId": TRACK, "mute": False}, "name"),
        ("transport.set", {"playing": False}, "loopDurationBeats"),
    ],
)
def test_absent_changes_not_serialized_and_explicit_null_rejected(method, args, field):
    params, _ = validate_call(method, args)
    assert params == args
    with pytest.raises(ValidationError):
        validate_call(method, {**args, field: None})


def test_unicode_length_matches_java_utf16_bounds():
    args = {"trackId": TRACK, "slotIndex": 0, "lengthBeats": 4, "name": "𝄞" * 64}
    params, _ = validate_call("launcher.create", args)
    assert params["name"] == args["name"]
    with pytest.raises(ValidationError):
        validate_call("launcher.create", {**args, "name": "𝄞" * 65})


def test_remote_control_target_and_identity_fields_are_required():
    args = {
        **TARGET,
        "contextToken": "observed-page-token",
        "expectedPageName": "Main",
        "parameterIndex": 7,
        "expectedParameterName": "Macro",
        "normalizedValue": 0.5,
    }
    params, _ = validate_call("devices.remote.set", args)
    assert params == args
    for field in args:
        with pytest.raises(ValidationError):
            validate_call("devices.remote.set", {key: value for key, value in args.items() if key != field})


def test_transport_rejects_finite_fields_whose_loop_end_overflows():
    with pytest.raises(ValidationError):
        validate_call("transport.set", {"loopStartBeats": 1e308, "loopDurationBeats": 1e308})


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
        ("trackId", ["selected", "", None, 1]),
        ("deviceIndex", [-1, 16, 0.0, "0", True, None]),
        ("expectedDeviceName", ["", None, 1, "x" * 4097]),
    ],
)
def test_grid_get_rejects_implicit_targets_and_invalid_windows(field, values):
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("grid.get", {**TARGET, field: value})


@pytest.mark.parametrize("field", ["trackId", "deviceIndex", "expectedDeviceName"])
def test_grid_get_requires_every_explicit_device_identity_field(field):
    with pytest.raises(ValidationError):
        validate_call("grid.get", {key: value for key, value in TARGET.items() if key != field})


@pytest.mark.parametrize(
    ("method", "args", "extra"),
    [
        ("grid.capabilities", {}, {"trackId": TRACK}),
        ("grid.capabilities", {}, {"force": True}),
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
    for offset, limit, device in ((0, 1, 0), (100000, 256, 15)):
        args = {
            **TARGET,
            "deviceIndex": device,
            "moduleOffset": offset,
            "moduleLimit": limit,
            "includeParameters": False,
            "timeoutMs": 10000,
        }
        params, timeout = validate_call("grid.get", args)
        assert params == {key: value for key, value in args.items() if key != "timeoutMs"}
        assert timeout == 10000


@pytest.mark.parametrize(
    ("parameter_id", "module_id"),
    [
        ("", TRACK.upper()),
        ("x" * 1024, TRACK),
        ("𝄞" * 512, TRACK),
        (" 01=音楽 ", TRACK),
    ],
)
def test_grid_parameter_get_preserves_exact_ids_and_utf16_boundary(parameter_id, module_id):
    args = {**TARGET, "moduleId": module_id, "parameterId": parameter_id, "timeoutMs": 10000}
    params, timeout = validate_call("grid.parameters.get", args)
    assert params == {key: value for key, value in args.items() if key != "timeoutMs"}
    assert timeout == 10000


@pytest.mark.parametrize(
    ("field", "values"),
    [
        (
            "moduleId",
            ["", "Union", "1-1-1-1-1", TRACK.replace("-", ""), "{" + TRACK + "}", TRACK + "\n", None, True, 1, [], {}],
        ),
        ("parameterId", [None, True, 1, 1.0, [], {}, "x" * 1025, "𝄞" * 513, "x" * 1023 + "𝄞"]),
        ("trackId", ["selected", "", None, True, 1]),
        ("deviceIndex", [-1, 16, 0.0, "0", True, None]),
        ("expectedDeviceName", ["", None, 1, "x" * 4097]),
        ("timeoutMs", [99, 10001, True, None, "5000"]),
    ],
)
def test_grid_parameter_get_rejects_invalid_identity_and_types(field, values):
    args = {**TARGET, "moduleId": TRACK, "parameterId": ""}
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("grid.parameters.get", {**args, field: value})


@pytest.mark.parametrize("field", ["trackId", "deviceIndex", "expectedDeviceName", "moduleId", "parameterId"])
def test_grid_parameter_get_requires_every_identity_field(field):
    args = {**TARGET, "moduleId": TRACK, "parameterId": ""}
    del args[field]
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.get", args)


@pytest.mark.parametrize(
    "extra",
    [
        {"selected": True},
        {"value": 0.5},
    ],
)
def test_grid_parameter_get_rejects_selection_aliases_writes_and_unknown_keys(extra):
    with pytest.raises(ValidationError):
        validate_call("grid.parameters.get", {**TARGET, "moduleId": TRACK, "parameterId": "", **extra})


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
        {"trackId": "selected"},
        {"trackId": None},
        {"deviceIndex": True},
        {"deviceIndex": 0.0},
        {"deviceIndex": -1},
        {"deviceIndex": 16},
        {"expectedDeviceName": ""},
        {"expectedDeviceName": None},
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
        {"trackId": "selected"},
        {"trackId": None},
        {"deviceIndex": True},
        {"deviceIndex": 0.0},
        {"deviceIndex": "0"},
        {"deviceIndex": None},
        {"deviceIndex": -1},
        {"deviceIndex": 16},
        {"expectedDeviceName": None},
        {"expectedDeviceName": ""},
        {"expectedDeviceName": False},
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
        {"trackId": "selected"},
        {"deviceIndex": 0.0},
        {"deviceIndex": True},
        {"deviceIndex": -1},
        {"deviceIndex": 16},
        {"expectedDeviceName": ""},
        {"expectedDeviceName": None},
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


@pytest.mark.parametrize("method", ["devices.layers.list", "parameters.layer.list", "parameters.layer.set"])
def test_layer_contract_requires_all_fields_and_preserves_nested_target(method):
    args = TOOLS[method].examples[0]
    params, timeout = validate_call(method, {**args, "timeoutMs": 250})
    assert params == args
    assert timeout == 250
    for field in args:
        for invalid in ({key: value for key, value in args.items() if key != field}, {**args, field: None}):
            with pytest.raises(ValidationError):
                validate_call(method, invalid)
    for field in LAYER_PARENT:
        with pytest.raises(ValidationError):
            validate_call(
                method, {**args, "parent": {key: value for key, value in LAYER_PARENT.items() if key != field}}
            )


@pytest.mark.parametrize(
    "changes",
    [
        {"trackId": "1-1-1-1-1"},
        {"trackId": None},
        {"deviceIndex": -1},
        {"deviceIndex": 16},
        {"deviceIndex": True},
        {"deviceIndex": 1.0},
        {"deviceIndex": "1"},
        {"expectedDeviceName": ""},
        {"expectedDeviceName": None},
        {"expectedDeviceName": "𝄞" * 2049},
        {"timeoutMs": 250},
        {"parent": LAYER_PARENT},
        {"layerId": TRACK},
        {"padIndex": 0},
        {"slotIndex": 0},
        {"unexpected": None},
    ],
)
@pytest.mark.parametrize("method", ["devices.layers.list", "parameters.layer.list", "parameters.layer.set"])
def test_layer_parent_is_strict_nonrecursive_and_has_no_nested_timeout(method, changes):
    with pytest.raises(ValidationError):
        validate_call(method, {**TOOLS[method].examples[0], "parent": {**LAYER_PARENT, **changes}})


@pytest.mark.parametrize(
    "changes",
    [
        {"parent": None},
        {"parent": []},
        {"parent": "selected"},
        {"layerId": "1-1-1-1-1"},
        {"layerId": ""},
        {"childDeviceIndex": -1},
        {"childDeviceIndex": 2},
        {"childDeviceIndex": True},
        {"childDeviceIndex": 1.0},
        {"childDeviceIndex": "1"},
        {"expectedChildDeviceName": ""},
        {"expectedChildDeviceName": "𝄞" * 2049},
        {"discoveryToken": ""},
        {"discoveryToken": "x" * 4097},
        {"layerIndex": 0},
        {"padIndex": 0},
        {"slotIndex": 0},
        {"path": []},
        {"child": LAYER_TARGET},
        {"trackId": TRACK},
        {"selected": True},
    ],
)
@pytest.mark.parametrize("method", ["parameters.layer.list", "parameters.layer.set"])
def test_layer_parameters_reject_ambiguous_or_deeper_targets(method, changes):
    with pytest.raises(ValidationError):
        validate_call(method, {**TOOLS[method].examples[0], **changes})


@pytest.mark.parametrize("value", [None, True, "0.5", -0.01, 1.01, float("nan"), float("inf"), -float("inf")])
def test_layer_set_requires_finite_normalized_number(value):
    with pytest.raises(ValidationError):
        validate_call("parameters.layer.set", {**TOOLS["parameters.layer.set"].examples[0], "value": value})


@pytest.mark.parametrize("value", [0, 0.5, 1])
@pytest.mark.parametrize("index", [0, 1])
def test_layer_set_accepts_window_and_normalized_endpoints(value, index):
    args = {
        **TOOLS["parameters.layer.set"].examples[0],
        "childDeviceIndex": index,
        "value": value,
        "parent": {**LAYER_PARENT, "deviceIndex": 15, "trackId": TRACK.upper()},
        "layerId": TRACK.upper(),
    }
    params, _ = validate_call("parameters.layer.set", args)
    assert params == args


@pytest.mark.parametrize(
    "method",
    [
        "devices.get",
        "devices.set",
        "devices.select",
        "devices.remote.get",
        "devices.remote.page",
        "devices.remote.set",
        "parameters.list",
        "parameters.set",
        "grid.get",
        "grid.parameters.get",
        "grid.parameters.set",
        "grid.modules.insert",
        "grid.modules.move",
        "grid.modules.delete",
        "grid.connections.set",
    ],
)
def test_existing_flat_device_contracts_do_not_accept_layer_targets(method):
    args = TOOLS[method].examples[0]
    for field, value in LAYER_TARGET.items():
        with pytest.raises(ValidationError):
            validate_call(method, {**args, field: value})


@pytest.mark.parametrize("method", ["devices.layers.list", "parameters.layer.list"])
def test_layer_reads_reject_write_fields(method):
    for field, value in {"contextToken": "c", "parameterId": "p", "value": 0.5}.items():
        with pytest.raises(ValidationError):
            validate_call(method, {**TOOLS[method].examples[0], field: value})


@pytest.mark.parametrize("field", ["contextToken", "parameterId"])
@pytest.mark.parametrize("value", ["", "x" * 4097, False])
def test_layer_set_rejects_invalid_write_identity(field, value):
    with pytest.raises(ValidationError):
        validate_call("parameters.layer.set", {**TOOLS["parameters.layer.set"].examples[0], field: value})


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("layerId", TRACK),
        ("childDeviceIndex", 0),
        ("discoveryToken", "d"),
        ("padIndex", 0),
        ("slotIndex", 0),
        ("path", []),
        ("unknown", None),
    ],
)
def test_layer_discovery_accepts_only_parent_and_top_level_timeout(field, value):
    with pytest.raises(ValidationError):
        validate_call("devices.layers.list", {"parent": LAYER_PARENT, field: value})


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


GRID_RENAME = {**TARGET, "moduleId": TRACK, "expectedCustomName": "", "customName": ""}


@pytest.mark.parametrize("field", list(GRID_RENAME))
def test_grid_rename_requires_each_identity_and_raw_cas_field(field):
    with pytest.raises(ValidationError):
        validate_call("grid.modules.rename", {key: value for key, value in GRID_RENAME.items() if key != field})


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
        validate_call("grid.modules.rename", {**GRID_RENAME, field: value})


@pytest.mark.parametrize(
    "changes",
    [
        {"trackId": "selected"},
        {"moduleId": "Union"},
        {"moduleId": "1-1-1-1-1"},
        {"moduleId": TRACK.replace("-", "")},
        {"moduleId": None},
        {"moduleId": True},
        {"deviceIndex": -1},
        {"deviceIndex": 16},
        {"deviceIndex": True},
        {"deviceIndex": "0"},
        {"deviceIndex": 0.0},
        {"expectedDeviceName": ""},
        {"expectedDeviceName": None},
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
        validate_call("grid.modules.rename", {**GRID_RENAME, **changes})


def test_note_window_is_optional_but_all_or_none():
    params, _ = validate_call(
        "notes.edit", {"trackId": TRACK, "slotIndex": 0, "edits": [note(), note(beat=1, pitch=64)]}
    )
    assert set(params) == {"trackId", "slotIndex", "edits"}
    params, _ = validate_call("notes.read", {"trackId": TRACK, "slotIndex": 0})
    assert set(params) == {"trackId", "slotIndex"}
    for method, extra in (("notes.read", {}), ("notes.edit", {"edits": [note()]})):
        with pytest.raises(ValidationError):
            validate_call(method, {"trackId": TRACK, "slotIndex": 0, "steps": 8, **extra})


@pytest.mark.parametrize(
    "edits",
    [
        [note(), note()],
        [note(), note(beat=0.5)],
        [note(beat=0.001)],
        [note(durationBeats=0.001)],
    ],
)
def test_derived_window_still_rejects_cell_and_overlap_errors(edits):
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {"trackId": TRACK, "slotIndex": 0, "edits": edits})


def test_launcher_create_notes_fail_before_dispatch_when_invalid():
    base = {"trackId": TRACK, "slotIndex": 0, "lengthBeats": 4, "name": "Sketch"}
    n = {"beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1}
    params, _ = validate_call("launcher.create", {**base, "notes": [n, {**n, "op": "set", "beat": 1}], "channel": 2})
    assert params["notes"][0] == n
    assert params["channel"] == 2
    bad: Any
    for bad in (
        {"notes": [{**n, "beat": 3.5}]},
        {"notes": [n, n]},
        {"notes": [{**n, "op": "clear"}]},
        {"notes": []},
        {"channel": 1},
        {"notes": [{**n, "velocity": 0}]},
    ):
        with pytest.raises(ValidationError):
            validate_call("launcher.create", {**base, **bad})


def test_scenes_append_count_bounds():
    assert validate_call("scenes.append", {})[0] == {}
    assert validate_call("scenes.append", {"count": SCENE_WINDOW})[0] == {"count": SCENE_WINDOW}
    for count in (0, SCENE_WINDOW + 1, 1.5, None):
        with pytest.raises(ValidationError):
            validate_call("scenes.append", {"count": count})


def test_detail_is_edit_and_create_only_and_enumerated():
    edits = [note()]
    params, _ = validate_call("notes.edit", {"trackId": TRACK, "slotIndex": 0, "edits": edits, "detail": "full"})
    assert params["detail"] == "full"
    params, _ = validate_call(
        "launcher.create",
        {"trackId": TRACK, "slotIndex": 0, "lengthBeats": 4, "name": "x", "notes": edits, "detail": "full"},
    )
    assert params["detail"] == "full"
    for method, args in (
        ("notes.edit", {"edits": edits, "detail": "verbose"}),
        ("notes.read", {"detail": "full"}),
        ("launcher.create", {"lengthBeats": 4, "name": "x", "detail": "full"}),
    ):
        with pytest.raises(ValidationError):
            validate_call(method, {"trackId": TRACK, "slotIndex": 0, **args})


def test_context_get_takes_no_parameters():
    params, _ = validate_call("context.get", {})
    assert params == {}
    assert TOOLS["context.get"].read_only
    with pytest.raises(ValidationError):
        validate_call("context.get", {"trackId": TRACK})


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
        ("grid.modules.rename", {**GRID_MODULE, "expectedCustomName": "", "customName": "x"}),
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
        ("grid.parameters.get", {**TARGET, "moduleId": TRACK, "parameterId": ""}),
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


def test_grid_capabilities_detail_is_summary_or_full_only():
    assert validate_call("grid.capabilities", {"detail": "full"})[0] == {"detail": "full"}
    assert validate_call("grid.capabilities", {})[0] == {}
    for bad in ("all", "", True, None):
        with pytest.raises(ValidationError):
            validate_call("grid.capabilities", {"detail": bad})


def test_actions_list_filters_and_invoke_consent_flag_serialize_only_when_given():
    params, _ = validate_call("actions.list", {"query": "save", "category": "File", "limit": 5})
    assert params == {"query": "save", "category": "File", "limit": 5}
    for bad in ({"query": ""}, {"category": ""}, {"limit": 0}, {"limit": 1001}, {"query": None}):
        with pytest.raises(ValidationError):
            validate_call("actions.list", bad)
    assert validate_call("actions.invoke", {"actionId": "Quit"})[0] == {"actionId": "Quit"}
    assert (
        validate_call("actions.invoke", {"actionId": "Quit", "discardUnsavedChanges": True})[0]["discardUnsavedChanges"]
        is True
    )
    with pytest.raises(ValidationError):
        validate_call("actions.invoke", {"actionId": "Quit", "discardUnsavedChanges": "yes"})


def test_project_save_is_a_write_tool_without_arguments():
    assert TOOLS["project.save"].read_only is False
    assert validate_call("project.save", {})[0] == {}
    with pytest.raises(ValidationError):
        validate_call("project.save", {"force": True})
    assert "projectModified" in TOOLS["extension.restart"].description


def test_project_save_as_takes_a_final_bwproject_path_and_explicit_flags():

    path = "/home/me/Bitwig Studio/Projects/Song/Song.bwproject"
    assert TOOLS["project.saveAs"].read_only is False
    assert validate_call("project.saveAs", {"path": path}) == ({"path": path}, 30000)
    assert validate_call("project.saveAs", {"path": path, "overwrite": True, "allowAnyPath": False})[0] == {
        "path": path,
        "overwrite": True,
        "allowAnyPath": False,
    }
    assert TOOLS["project.saveAs"].max_timeout_ms == 60000
    for bad in (
        {},
        {"path": "Song/Song.bwproject"},
        {"path": "~/Song/Song.bwproject"},
        {"path": "/p/Song/Song.txt"},
        {"path": "/p/../Song/Song.bwproject"},
        {"path": "/Song.bwproject"},
        {"path": "/p/Song/ Song.bwproject"},
        {"path": "/p/Song/.bwproject"},
        {"path": "/p/t/Template.bwproject"},
        {"path": "/p/auto-backups/S/S.bwproject"},
        {"path": path, "overwrite": "yes"},
        {"path": path, "overwrite": None},
        {"path": path, "force": True},
    ):
        with pytest.raises(ValidationError):
            validate_call("project.saveAs", bad)


def test_export_audio_and_bounce_in_place_are_strict_long_write_tools():

    track = "12345678-1234-1234-1234-123456789abc"
    base = {
        "sources": ["master", track],
        "directory": "/home/me/Music",
        "baseName": "Mix",
        "startBeats": 0,
        "endBeats": 16,
        "format": "wav",
    }
    assert validate_call("export.audio", base) == (base, 30000)
    assert (
        validate_call(
            "export.audio",
            base
            | {
                "format": "flac",
                "bitDepth": 24,
                "sampleRate": 48000,
                "tap": "pre_fader",
                "dither": True,
                "overwrite": True,
            },
        )[0]["bitDepth"]
        == 24
    )
    assert TOOLS["export.audio"].read_only is False
    assert TOOLS["bounce.inPlace"].read_only is False
    assert TOOLS["render.status"].read_only is True
    assert TOOLS["export.audio"].max_timeout_ms == TOOLS["bounce.inPlace"].max_timeout_ms == 60000
    assert TOOLS["render.status"].max_timeout_ms == 10000
    bad: Any
    for bad in (
        {"sources": []},
        {"sources": ["master", "master"]},
        {"sources": [track, track.upper()]},
        {"sources": ["Master"]},
        {"directory": "relative"},
        {"directory": "/a/../b"},
        {"baseName": ""},
        {"baseName": ".x"},
        {"baseName": "a/b"},
        {"baseName": " a"},
        {"baseName": "x" * 129},
        {"endBeats": 0},
        {"endBeats": 65537},
        {"format": "mp3"},
        {"bitDepth": 20},
        {"format": "flac", "bitDepth": 32},
        {"sampleRate": 96000},
        {"tap": "pre_fx"},
        {"overwrite": "yes"},
        {"realtime": True},
    ):
        with pytest.raises(ValidationError):
            validate_call("export.audio", base | bad)
    bounce = {"trackIds": [track], "startBeats": 8, "endBeats": 24, "tap": "pre_fx"}
    assert validate_call("bounce.inPlace", bounce) == (bounce, 30000)
    for bad in ({"trackIds": ["master"]}, {"trackIds": [track, track]}, {"tap": "custom"}, {"endBeats": 8}):
        with pytest.raises(ValidationError):
            validate_call("bounce.inPlace", bounce | bad)
    with pytest.raises(ValidationError):
        validate_call("bounce.inPlace", {k: v for k, v in bounce.items() if k != "tap"})


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
                "trackId": GRID_INSERT["trackId"],
                **{k: named[k] for k in named if k not in ("typeName", "x", "y")},
                "modules": [{"typeName": ok, "x": 1, "y": 1}],
            },
        )
    with pytest.raises(ValidationError):
        validate_call("grid.modules.insert", {**named, "typeName": ""})


CLIP_EDIT = {"trackId": TRACK, "slotIndex": 0, "expectedClipName": "Riff"}


@pytest.mark.parametrize(
    "fields",
    [
        {"op": "rename", "name": "a" * 128},
        {"op": "resize", "lengthBeats": 4096},
        {"op": "color", "rgb": "#aB09fF"},
        {"op": "transpose", "semitones": -48},
        {"op": "transpose", "semitones": 48},
        {"op": "quantize", "amount": 0.01},
        {"op": "quantize", "amount": 1},
        {"op": "duplicateContent"},
        {"op": "delete"},
        {"op": "copyTo", "targetSlotIndex": 15},
    ],
)
def test_clips_edit_accepts_one_operation(fields):
    params, _ = validate_call("clips.edit", {**CLIP_EDIT, **fields})
    assert params == {**CLIP_EDIT, **fields}


@pytest.mark.parametrize(
    "fields",
    [
        {"op": "bogus"},
        {"op": "rename"},
        {"op": "rename", "name": ""},
        {"op": "rename", "name": "a" * 129},
        {"op": "rename", "name": "x", "semitones": 1},
        {"op": "delete", "name": "x"},
        {"op": "duplicateContent", "amount": 1},
        {"op": "resize", "lengthBeats": 0},
        {"op": "resize", "lengthBeats": 4097},
        {"op": "resize", "lengthBeats": 1 / 256},
        {"op": "color", "rgb": "ff0000"},
        {"op": "color", "rgb": "#ff00"},
        {"op": "color", "rgb": "#gg0000"},
        {"op": "color", "rgb": "#ff0000\n"},
        {"op": "transpose", "semitones": 49},
        {"op": "transpose", "semitones": -49},
        {"op": "transpose"},
        {"op": "quantize", "amount": 0},
        {"op": "transpose", "semitones": 0},
        {"op": "quantize", "amount": 1.01},
        {"op": "quantize", "amount": -0.01},
        {"op": "copyTo"},
        {"op": "copyTo", "targetSlotIndex": 16},
        {"op": "delete", "force": True},
    ],
)
def test_clips_edit_rejects_bad_or_mixed_operations(fields):
    with pytest.raises(ValidationError):
        validate_call("clips.edit", {**CLIP_EDIT, **fields})


def test_clips_edit_requires_exact_target():
    for missing in ("trackId", "slotIndex", "expectedClipName", "op"):
        args = {**CLIP_EDIT, "op": "delete"}
        del args[missing]
        with pytest.raises(ValidationError):
            validate_call("clips.edit", args)
    with pytest.raises(ValidationError):
        validate_call("clips.edit", {**CLIP_EDIT, "op": "delete", "expectedClipName": "a" * 257})
    assert TOOLS["clips.edit"].read_only is False
    assert {e["op"] for e in TOOLS["clips.edit"].examples} == {
        "rename",
        "resize",
        "color",
        "transpose",
        "quantize",
        "duplicateContent",
        "delete",
        "copyTo",
    }


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
    assert validate_call("grid.parameters.get", {**TARGET, "moduleId": TRACK, "parameterId": "DATA", "full": True})
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


def test_remote_page_define_and_delete():
    assert not TOOLS["devices.remote.define"].read_only
    assert not TOOLS["devices.remote.deletePage"].read_only
    slot = {"moduleId": TRACK, "parameterId": "VALUE"}
    args = {
        **TARGET,
        "pageName": "Macros",
        "mode": "replace",
        "slots": [slot, None, {"moduleName": "Mx: grit", "parameterId": "", "label": ""}],
    }
    params, _ = validate_call("devices.remote.define", args)
    assert params == args
    assert validate_call("devices.remote.define", {**TARGET, "pageName": "P" * 256, "slots": [None] * 7 + [slot]})
    assert validate_call("devices.remote.deletePage", {**TARGET, "pageName": "Macros"})


@pytest.mark.parametrize(
    "changes",
    [
        {"pageName": ""},
        {"pageName": "P" * 257},
        {"pageName": "\U0001f600" * 129},
        {"slots": []},
        {"slots": [None] * 9},
        {"mode": "upsert"},
        {"mode": None},
        {"slots": [{"parameterId": "VALUE"}]},
        {"slots": [{"moduleId": TRACK, "moduleName": "M", "parameterId": "VALUE"}]},
        {"slots": [{"moduleId": "not-a-uuid", "parameterId": "VALUE"}]},
        {"slots": [{"moduleName": "", "parameterId": "VALUE"}]},
        {"slots": [{"moduleName": "M"}]},
        {"slots": [{"moduleName": "M", "parameterId": "V", "label": "l" * 257}]},
        {"slots": [{"moduleName": "M", "parameterId": "V", "label": None}]},
        {"slots": [{"moduleName": "M", "parameterId": "V", "extra": 1}]},
        {"slots": [{"moduleId": TRACK, "parameterId": "V"}, {"moduleId": TRACK.upper(), "parameterId": "V"}]},
        {"slots": [1]},
    ],
)
def test_remote_page_define_rejects_bad_requests(changes):
    args = {**TARGET, "pageName": "Macros", "slots": [{"moduleName": "M", "parameterId": "VALUE"}], **changes}
    with pytest.raises(ValidationError):
        validate_call("devices.remote.define", args)


def test_remote_page_delete_rejects_extra_fields():
    with pytest.raises(ValidationError):
        validate_call("devices.remote.deletePage", {**TARGET, "pageName": "Macros", "mode": "replace"})


@pytest.mark.parametrize(
    "fields",
    [
        {"position": "start"},
        {"position": "end"},
        {"position": "before", "deviceIndex": 1, "expectedDeviceName": "EQ-5"},
        {"position": "after", "deviceIndex": 0, "expectedDeviceName": "Polymer"},
    ],
)
def test_insert_position_round_trips(fields):
    args = {
        "trackId": TRACK,
        "expectedDeviceCount": 2,
        "kind": "bitwig",
        "id": "11111111-2222-4333-8444-555555555555",
        **fields,
    }
    params, _ = validate_call("devices.insert", args)
    assert params == args


@pytest.mark.parametrize(
    "fields",
    [
        {"position": "before"},
        {"position": "after", "deviceIndex": 1},
        {"position": "before", "expectedDeviceName": "EQ-5"},
        {"position": "end", "deviceIndex": 1, "expectedDeviceName": "EQ-5"},
        {"deviceIndex": 1, "expectedDeviceName": "EQ-5"},
        {"position": "middle"},
        {"position": "before", "deviceIndex": 16, "expectedDeviceName": "EQ-5"},
    ],
)
def test_insert_position_anchor_rules(fields):
    with pytest.raises(ValidationError):
        validate_call(
            "devices.insert", {"trackId": TRACK, "expectedDeviceCount": 2, "kind": "clap", "id": "x.y", **fields}
        )


def test_delete_move_and_layer_insert_shapes():
    assert validate_call("devices.delete", TARGET)[0] == TARGET
    move = {**TARGET, "toIndex": 3}
    assert validate_call("devices.move", move)[0] == move
    for bad in ({**TARGET, "toIndex": 0}, {**TARGET, "toIndex": 16}, TARGET, {**TARGET, "toIndex": 1, "extra": 1}):
        with pytest.raises(ValidationError):
            validate_call("devices.move", bad)
    layer = {
        "parent": LAYER_PARENT,
        "layerId": LAYER_TARGET["layerId"],
        "expectedChildCount": 0,
        "kind": "file",
        "path": "/tmp/FX.bwdevice",
        "position": "start",
    }
    assert validate_call("devices.layers.insert", layer)[0] == layer
    for bad in (
        {**layer, "position": "before"},
        {**layer, "expectedChildCount": -1},
        {**layer, "id": "x"},
        {**layer, "layerId": "not-a-uuid"},
        {k: v for k, v in layer.items() if k != "parent"},
    ):
        with pytest.raises(ValidationError):
            validate_call("devices.layers.insert", bad)


def test_activate_engine_is_a_write_tool_without_arguments():
    assert TOOLS["project.activateEngine"].read_only is False
    assert validate_call("project.activateEngine", {})[0] == {}
    with pytest.raises(ValidationError):
        validate_call("project.activateEngine", {"force": True})


def test_transport_guide_settings():
    args = {
        "metronomeVolumeNormalized": 0.5,
        "preRoll": "one_bar",
        "punchInEnabled": True,
        "punchOutEnabled": False,
        "defaultLaunchQuantization": "1",
        "recordQuantizationGrid": "1/16",
    }
    assert validate_call("transport.set", args)[0] == args
    for bad in (
        {"metronomeVolumeNormalized": 1.1},
        {"metronomeVolumeNormalized": -0.1},
        {"preRoll": ""},
        {"preRoll": 1},
        {"punchInEnabled": 1},
        {"punchOutEnabled": None},
        {"defaultLaunchQuantization": None},
        {"recordQuantizationGrid": ""},
    ):
        with pytest.raises(ValidationError):
            validate_call("transport.set", bad)


def test_track_color_activated_monitor_and_crossfade():
    args = {"trackId": TRACK, "color": "#ff8800", "activated": False, "monitorMode": "ON", "crossFadeMode": "AB"}
    assert validate_call("tracks.set", args)[0] == args
    for bad in (
        {"color": "ff8800"},
        {"color": "#ff88"},
        {"color": "#gg0000"},
        {"activated": "no"},
        {"monitorMode": ""},
        {"crossFadeMode": 1},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.set", {"trackId": TRACK, **bad})


def test_scene_tools_use_index_and_exact_name_guard():
    assert validate_call("scenes.list", {})[0] == {}
    target = {"sceneIndex": 3, "expectedName": ""}
    for method in ("scenes.launch", "scenes.duplicate", "scenes.delete"):
        assert validate_call(method, target)[0] == target
        assert not TOOLS[method].read_only
    assert validate_call("scenes.rename", {**target, "name": "Verse"})[0] == {**target, "name": "Verse"}
    assert TOOLS["scenes.list"].read_only
    for method, bad in (
        ("scenes.delete", {"sceneIndex": SCENE_WINDOW, "expectedName": ""}),
        ("scenes.delete", {"sceneIndex": -1, "expectedName": ""}),
        ("scenes.delete", {"sceneIndex": 0}),
        ("scenes.delete", {"expectedName": ""}),
        ("scenes.launch", {"sceneIndex": 0, "expectedName": "x" * 257}),
        ("scenes.rename", {**target, "name": ""}),
        ("scenes.rename", target),
        ("scenes.duplicate", {**target, "extra": 1}),
        ("scenes.list", {"sceneIndex": 0}),
    ):
        with pytest.raises(ValidationError):
            validate_call(method, bad)
