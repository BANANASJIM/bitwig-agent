from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TRACK
from bitwig_agent.protocol.launcher import SLOT_REF
from bitwig_agent.protocol.topics import REFS

UUID = "abcdef01-2345-6789-abcd-0123456789ab"
TRACK_REF: dict[str, Any] = {"kind": "track", "id": TRACK, "name": "Lead"}
DEVICE: dict[str, Any] = {"kind": "device", "track": TRACK_REF, "index": 1, "name": "Instrument Layer"}
CHAIN: dict[str, Any] = {"kind": "chain", "device": DEVICE, "id": UUID, "name": "Pad"}
CHAIN_DEVICE: dict[str, Any] = {"kind": "device", "chain": CHAIN, "index": 0, "name": "Polysynth"}
PAGE: dict[str, Any] = {"kind": "remotePage", "device": DEVICE, "index": 0, "name": "Main"}
FILE = {"kind": "file", "path": "/tmp/Poly Sketch.bwpreset"}


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
        validate_call("devices.insert", {"to": {"parent": TRACK_REF}, "kind": kind, **fields})


def test_file_insertion_preserves_path_and_omits_id():
    args = {"to": {"parent": CHAIN}, "kind": "file", "path": "/tmp/Poly Sketch.bwpreset"}
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
        ("devices.insert", {"to": {"parent": TRACK_REF}, "kind": "file"}),
    ]
    for method, args in cases:
        with pytest.raises(ValidationError):
            validate_call(method, {**args, "path": path})


@pytest.mark.parametrize("parent", [TRACK_REF, CHAIN])
@pytest.mark.parametrize(
    "position",
    [
        "start",
        "end",
        {"before": DEVICE},
        {"after": CHAIN_DEVICE},
    ],
)
def test_insert_position_round_trips_for_a_track_and_a_chain(parent, position):
    args = {
        "to": {"parent": parent, "position": position},
        "kind": "bitwig",
        "id": "11111111-2222-4333-8444-555555555555",
    }
    params, _ = validate_call("devices.insert", args)
    assert params == args
    params, _ = validate_call("devices.insert", {**args, "to": {"parent": parent}})
    assert "position" not in params["to"]


@pytest.mark.parametrize(
    "changes",
    [
        {"to": {"parent": TRACK_REF, "position": "before"}},
        {"to": {"parent": TRACK_REF, "position": "middle"}},
        {"to": {"parent": TRACK_REF, "position": {"before": TRACK_REF}}},
        {"to": {"parent": TRACK_REF, "position": {"before": DEVICE, "after": DEVICE}}},
        {"to": {"parent": TRACK_REF, "position": 1}},
        {"to": {"parent": DEVICE}},
        {"to": {"parent": {"kind": "track", "id": TRACK}}},
        {"to": {"position": "end"}},
        {"to": TRACK_REF},
        {"parent": TRACK_REF},
        {"position": "end"},
        {"expectedDeviceCount": 2},
        {"trackId": TRACK},
        {"deviceIndex": 1},
    ],
)
def test_insert_rejects_old_fields_and_malformed_positions(changes):
    args = {"to": {"parent": TRACK_REF}, "kind": "clap", "id": "x.y", **changes}
    with pytest.raises(ValidationError):
        validate_call("devices.insert", args)


@pytest.mark.parametrize("method", ["devices.get", "devices.select", "devices.delete", "devices.remote.get"])
@pytest.mark.parametrize("target", [DEVICE, CHAIN_DEVICE])
def test_device_targets_take_a_root_or_a_chain_device_ref(method, target):
    params, _ = validate_call(method, {"target": target})
    assert params == {"target": target}


@pytest.mark.parametrize("method", ["devices.get", "devices.set", "devices.select", "devices.delete", "devices.move"])
@pytest.mark.parametrize(
    "target",
    [TRACK_REF, CHAIN, PAGE, {**DEVICE, "index": 16}, {"trackId": TRACK, "deviceIndex": 0}],
)
def test_device_targets_reject_other_refs_and_the_old_triple(method, target):
    required: dict[str, dict[str, Any]] = {
        "devices.set": {"enabled": True},
        "devices.move": {"to": {"position": "end"}},
    }
    args = required.get(method, {})
    with pytest.raises(ValidationError):
        validate_call(method, {"target": target, **args})


def test_set_requires_enabled_and_move_a_destination():
    assert validate_call("devices.set", {"target": DEVICE, "enabled": False})[0]["enabled"] is False
    with pytest.raises(ValidationError):
        validate_call("devices.set", {"target": DEVICE})
    for to in ({"position": "start"}, {"position": {"after": CHAIN_DEVICE}}, {"parent": CHAIN, "position": "end"}):
        params, _ = validate_call("devices.move", {"target": CHAIN_DEVICE, "to": to})
        assert params["to"] == to
    for bad in ({}, {"parent": CHAIN}, {"position": "middle"}, {"toIndex": 1}):
        with pytest.raises(ValidationError):
            validate_call("devices.move", {"target": CHAIN_DEVICE, "to": bad})
    with pytest.raises(ValidationError):
        validate_call("devices.move", {"target": CHAIN_DEVICE})


def test_lists_take_a_container_target_and_chains_a_device():
    for parent in (TRACK_REF, CHAIN):
        assert validate_call("devices.list", {"target": parent})[0] == {"target": parent}
    assert validate_call("chains.list", {"target": DEVICE})[0] == {"target": DEVICE}
    for method, parent in (("devices.list", DEVICE), ("chains.list", CHAIN), ("chains.list", TRACK_REF)):
        with pytest.raises(ValidationError):
            validate_call(method, {"target": parent})
    for method in ("devices.list", "chains.list", "parameters.list"):
        with pytest.raises(ValidationError):
            validate_call(method, {"trackId": TRACK})


@pytest.mark.parametrize("page", [0, 7, 4095])
def test_remote_get_takes_an_optional_page_index(page):
    assert validate_call("devices.remote.get", {"target": DEVICE, "page": page})[0]["page"] == page
    for bad in (-1, 4096, 1.0, True, "1", None):
        with pytest.raises(ValidationError):
            validate_call("devices.remote.get", {"target": DEVICE, "page": bad})


@pytest.mark.parametrize(
    "name",
    [
        "devices.layers.list",
        "devices.layers.insert",
        "devices.remote.page",
        "devices.remote.set",
        "parameters.layer.list",
        "parameters.layer.set",
    ],
)
def test_the_old_device_methods_are_gone(name):
    assert name not in TOOLS
    with pytest.raises(ValueError, match="Unknown method"):
        validate_call(name, {})


def test_catalog_is_a_filterable_record_list():
    assert TOOLS["devices.catalog"].records == "devices"
    assert validate_call("devices.catalog", {})[0] == {}
    args = {"query": "delay", "category": "Drum"}
    assert validate_call("devices.catalog", args)[0] == args
    for bad in ({"query": ""}, {"category": 1}, {"name": "x"}):
        with pytest.raises(ValidationError):
            validate_call("devices.catalog", bad)


def test_refs_topic_says_identity_correct_hand_built_refs_work():
    assert "works exactly when its identity fields are correct" in REFS
    assert "pass them back unchanged" in REFS
