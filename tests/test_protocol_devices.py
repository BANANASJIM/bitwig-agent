from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TARGET, TRACK
from bitwig_agent.protocol.devices import LAYER_PARENT, LAYER_TARGET
from bitwig_agent.protocol.launcher import SLOT_REF


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
    cases: list[tuple[str, dict[str, Any]]] = [
        ("clips.import", {"to": SLOT_REF}),
        ("devices.insert", {"trackId": TRACK, "expectedDeviceCount": 0, "kind": "file"}),
    ]
    for method, args in cases:
        with pytest.raises(ValidationError):
            validate_call(method, {**args, "path": path})


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
