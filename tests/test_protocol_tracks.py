from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.annotations import ADD, resolve

TRACK = {"kind": "track", "id": "12345678-1234-1234-1234-123456789abc", "name": "Lead"}
OTHER = {"kind": "track", "id": "12345678-1234-1234-1234-123456789abd", "name": "Bass"}
SEND = {"kind": "send", "track": TRACK, "index": 0, "name": "A-Reverb"}


def test_tracks_are_addressed_by_ref_and_the_old_names_are_gone():
    assert validate_call("tracks.get", {"target": TRACK})[0] == {"target": TRACK}
    for gone in ("tracks.create", "tracks.meters"):
        assert gone not in TOOLS
    for name in ("tracks.insert", "meters.get"):
        assert name in TOOLS
    with pytest.raises(ValidationError):
        validate_call("tracks.get", {"trackId": TRACK["id"]})
    with pytest.raises(ValidationError):
        validate_call("tracks.get", {"target": {**TRACK, "name": None}})


@pytest.mark.parametrize(
    ("method", "args", "field"),
    [
        ("tracks.set", {"target": TRACK, "mute": False}, "name"),
        ("transport.set", {"playing": False}, "loop"),
    ],
)
def test_absent_changes_not_serialized_and_explicit_null_rejected(method, args, field):
    params, _ = validate_call(method, args)
    assert params == args
    with pytest.raises(ValidationError):
        validate_call(method, {**args, field: None})


def test_track_color_activated_monitor_and_crossfade():
    args = {"target": TRACK, "color": "#ff8800", "activated": False, "monitorMode": "ON", "crossFadeMode": "AB"}
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
            validate_call("tracks.set", {"target": TRACK, **bad})


def test_volume_and_pan_take_the_read_value_or_a_display():
    args = {"target": TRACK, "volume": 0.5, "pan": {"display": "25% L"}}
    assert validate_call("tracks.set", args)[0] == args
    assert validate_call("tracks.set", {"target": TRACK, "volume": {"display": "-inf dB"}, "pan": 0.5})[0]
    for bad in (
        {"volume": 1.5},
        {"pan": -0.1},
        {"pan": {"label": "C"}},
        {"volume": {"display": ""}},
        {"volume": {"display": "-6 dB", "label": "x"}},
        {"volumeDb": -6},
        {"panPercent": 10},
        {"volumeNormalized": 0.5},
        {"volumeDisplay": "-6 dB"},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.set", {"target": TRACK, **bad})


def test_a_write_changes_something_and_expect_holds_bare_values():
    with pytest.raises(ValidationError):
        validate_call("tracks.set", {"target": TRACK})
    with pytest.raises(ValidationError):
        validate_call("tracks.set", {"target": TRACK, "expect": {"mute": False}})
    expect = {"mute": False, "pan": 0.5, "color": "#000000", "monitorMode": "AUTO"}
    args = {"target": TRACK, "mute": True, "expect": expect}
    assert validate_call("tracks.set", args)[0] == args
    for bad in ({}, {"pan": {"display": "C"}}, {"mute": 1}, {"volumeDb": 1}):
        with pytest.raises(ValidationError):
            validate_call("tracks.set", {"target": TRACK, "mute": True, "expect": bad})


def test_send_ref_value_and_expect():
    assert validate_call("tracks.sends.set", {"target": SEND, "value": 0.3})[0] == {"target": SEND, "value": 0.3}
    args = {
        "target": SEND,
        "value": {"display": "-12 dB"},
        "enabled": True,
        "expect": {"value": 0.25, "enabled": False},
    }
    assert validate_call("tracks.sends.set", args)[0] == args
    assert validate_call("tracks.sends.set", {"target": SEND, "enabled": False})[0]
    mode = {"target": SEND, "mode": "PRE", "expect": {"mode": "POST"}}
    assert validate_call("tracks.sends.set", mode)[0] == mode
    for bad in (
        {"target": SEND},
        {"target": SEND, "value": 1.1},
        {"target": {**SEND, "index": 8}, "value": 0.1},
        {"target": {**SEND, "index": -1}, "value": 0.1},
        {"target": TRACK, "value": 0.1},
        {"target": SEND, "value": {"display": ""}},
        {"target": SEND, "mode": ""},
        {"target": SEND, "value": 0.1, "expect": {}},
        {"target": SEND, "value": 0.1, "levelDb": -6},
        {"trackId": TRACK["id"], "sendIndex": 0, "valueNormalized": 0.1},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.sends.set", bad)


def test_insert_positions_are_the_shared_vocabulary_and_only_the_top_level_is_open():
    assert validate_call("tracks.insert", {"type": "audio", "name": "Bass"})[0] == {"type": "audio", "name": "Bass"}
    for position in ("start", "end", {"before": TRACK}, {"after": OTHER}):
        args = {"type": "effect", "name": "Verb", "position": position}
        assert validate_call("tracks.insert", args)[0] == args
    for bad in (
        {"position": 0},
        {"position": -1},
        {"position": "middle"},
        {"position": {"before": TRACK, "after": OTHER}},
        {"position": {"after": SEND}},
        {"type": "group"},
        {"name": ""},
        {"parent": TRACK},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.insert", {"type": "audio", "name": "x", **bad})


def test_group_and_delete_and_meters_take_refs():
    assert resolve("tracks.group", TOOLS["tracks.group"].annotations) == ADD
    args = {"targets": [TRACK, OTHER], "name": "Drums"}
    assert validate_call("tracks.group", args)[0] == args
    assert validate_call("tracks.group", {"targets": [TRACK]})[0] == {"targets": [TRACK]}
    invalid: list[dict[str, Any]] = [
        {"targets": []},
        {"targets": [TRACK, {**TRACK, "name": "Again"}]},
        {"targets": [TRACK["id"]]},
        {"targets": [TRACK], "name": ""},
        {"trackIds": [TRACK["id"]]},
    ]
    for bad in invalid:
        with pytest.raises(ValidationError):
            validate_call("tracks.group", bad)
    assert validate_call("tracks.delete", {"target": TRACK})[0] == {"target": TRACK}
    with pytest.raises(ValidationError):
        validate_call("tracks.delete", {"target": TRACK, "expectedName": "Lead"})
    assert validate_call("tracks.select", {"target": TRACK})[0] == {"target": TRACK}


def test_meters_arguments():
    assert validate_call("meters.get", {"targets": [TRACK, OTHER], "windowMs": 500})[0]
    for bad in (
        {"targets": [], "windowMs": 500},
        {"targets": [TRACK, {**TRACK, "name": "Again"}], "windowMs": 500},
        {"targets": [TRACK], "windowMs": 49},
        {"targets": [TRACK], "windowMs": 5001},
        {"targets": [TRACK["id"]], "windowMs": 100},
        {"trackIds": [TRACK["id"]], "windowMs": 100},
    ):
        with pytest.raises(ValidationError):
            validate_call("meters.get", bad)
    assert TOOLS["meters.get"].read_only


def test_duplicate_convert_and_ungroup_take_a_track_ref():
    for method in ("tracks.duplicate", "tracks.ungroup"):
        assert validate_call(method, {"target": TRACK})[0] == {"target": TRACK}
        with pytest.raises(ValidationError):
            validate_call(method, {"target": TRACK["id"]})
    for to in ("hybrid", "audio", "instrument"):
        assert validate_call("tracks.convert", {"target": TRACK, "to": to})[0] == {"target": TRACK, "to": to}
    for bad in ({"target": TRACK}, {"target": TRACK, "to": "effect"}, {"target": TRACK, "to": "Hybrid"}):
        with pytest.raises(ValidationError):
            validate_call("tracks.convert", bad)
    assert resolve("tracks.duplicate", TOOLS["tracks.duplicate"].annotations) == ADD
    assert resolve("tracks.convert", TOOLS["tracks.convert"].annotations).destructive
    assert resolve("tracks.ungroup", TOOLS["tracks.ungroup"].annotations).destructive
    assert TOOLS["tracks.convert"].inspect == "tracks.get"
