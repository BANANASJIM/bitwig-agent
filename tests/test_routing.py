import pytest
from pydantic import ValidationError

from bitwig_agent.catalog import TOOLS, validate_call

TRACK = "11111111-1111-1111-1111-111111111111"
OTHER = "22222222-2222-2222-2222-222222222222"
DEVICE = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Compressor"}
SIDECHAIN = {**DEVICE, "componentIndex": 0, "expectedComponentId": "SIDECHAIN", "source": {"kind": "pre_fader", "trackId": OTHER}, "expectedSource": {"kind": "none"}}


def test_routing_get_and_choices():
    assert validate_call("tracks.routing.get", {"trackId": TRACK})[0] == {"trackId": TRACK}
    assert validate_call("tracks.routing.get", {"trackId": TRACK, "includeChoices": True})[0]["includeChoices"] is True
    for bad in ({"trackId": "track"}, {"trackId": TRACK, "includeChoices": 1}, {"trackId": TRACK, "output": {"kind": "none"}}):
        with pytest.raises(ValidationError):
            validate_call("tracks.routing.get", bad)


@pytest.mark.parametrize("args", [
    {"output": {"kind": "track_audio_input", "trackId": OTHER}, "expectedOutput": {"kind": "none"}},
    {"output": {"kind": "track_note_input", "trackId": OTHER}, "expectedOutput": {"kind": "other", "name": "Out 1/2"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "track_audio_input", "trackId": OTHER}},
    {"input": {"kind": "post_fader", "trackId": OTHER}, "expectedInput": {"kind": "none"}},
    {"input": {"kind": "all_ins"}, "expectedInput": {"kind": "track_notes", "trackId": OTHER}},
    {"input": {"kind": "onscreen_keyboard"}, "expectedInput": {"kind": "computer_keyboard"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "none"}, "input": {"kind": "none"}, "expectedInput": {"kind": "none"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "other", "name": "", "nativeType": "fe"}},
])
def test_routing_set_accepts_paired_entries(args):
    params, _ = validate_call("tracks.routing.set", {"trackId": TRACK, **args})
    assert params == {"trackId": TRACK, **args}


@pytest.mark.parametrize("args", [
    {},
    {"output": {"kind": "none"}},
    {"expectedOutput": {"kind": "none"}},
    {"input": {"kind": "none"}, "expectedOutput": {"kind": "none"}},
    {"output": {"kind": "pre_fader", "trackId": OTHER}, "expectedOutput": {"kind": "none"}},
    {"input": {"kind": "track_audio_input", "trackId": OTHER}, "expectedInput": {"kind": "none"}},
    {"output": {"kind": "track_audio_input"}, "expectedOutput": {"kind": "none"}},
    {"output": {"kind": "none", "trackId": OTHER}, "expectedOutput": {"kind": "none"}},
    {"output": {"kind": "other", "name": "Out"}, "expectedOutput": {"kind": "none"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "other"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "none", "nativeType": "fe"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "other", "name": "x", "nativeType": ""}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "none", "name": "x"}},
    {"output": {"kind": "none"}, "expectedOutput": {"kind": "none", "extra": 1}},
    {"input": {"kind": "hardware"}, "expectedInput": {"kind": "none"}},
    {"input": {"kind": "pre_fader", "trackId": "nope"}, "expectedInput": {"kind": "none"}},
    {"input": {"kind": "none"}, "expectedInput": None},
])
def test_routing_set_rejects_ambiguous_or_unsupported(args):
    with pytest.raises(ValidationError):
        validate_call("tracks.routing.set", {"trackId": TRACK, **args})


def test_sidechain_get_and_set():
    assert validate_call("devices.sidechain.get", {**DEVICE, "includeChoices": True})[0]["includeChoices"] is True
    assert validate_call("devices.sidechain.set", SIDECHAIN)[0] == SIDECHAIN
    by_id = {**{k: v for k, v in SIDECHAIN.items() if k not in ("componentIndex", "expectedComponentId")}, "componentId": "SIDECHAIN"}
    assert validate_call("devices.sidechain.set", by_id)[0] == by_id
    on_modulator = {**SIDECHAIN, "expectedModulatorId": OTHER}
    assert validate_call("devices.sidechain.set", on_modulator)[0] == on_modulator


@pytest.mark.parametrize("changes", [
    {"componentId": "SIDECHAIN"}, {"componentIndex": 256}, {"expectedComponentId": ""}, {"expectedModulatorId": "mod"}, {"componentIndex": -1}, {"deviceIndex": 16},
    {"source": {"kind": "track_audio_input", "trackId": OTHER}}, {"source": {"kind": "other", "name": "In 1"}},
    {"expectedSource": {"kind": "other"}}, {"expectedDeviceName": ""},
])
def test_sidechain_set_rejects_invalid(changes):
    with pytest.raises(ValidationError):
        validate_call("devices.sidechain.set", {**SIDECHAIN, **changes})
    for drop in (("componentIndex",), ("expectedComponentId",), ("componentIndex", "expectedComponentId")):
        with pytest.raises(ValidationError):
            validate_call("devices.sidechain.set", {k: v for k, v in SIDECHAIN.items() if k not in drop})


def test_routing_tools_are_writes_only_where_they_change_state():
    assert TOOLS["tracks.routing.get"].read_only and TOOLS["devices.sidechain.get"].read_only
    assert not TOOLS["tracks.routing.set"].read_only and not TOOLS["devices.sidechain.set"].read_only
