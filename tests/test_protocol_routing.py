import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call

TRACK = {"kind": "track", "id": "11111111-1111-1111-1111-111111111111", "name": "Lead"}
OTHER = "22222222-2222-2222-2222-222222222222"
OTHER_TRACK = {"kind": "track", "id": OTHER, "name": "Bass"}
DEVICE = {"kind": "device", "track": TRACK, "index": 0, "name": "Compressor"}
SIDECHAIN_REF = {"kind": "sidechain", "device": DEVICE, "index": 0, "id": "SIDECHAIN"}
SIDECHAIN = {
    "target": SIDECHAIN_REF,
    "source": {"kind": "pre_fader", "track": OTHER_TRACK},
    "expect": {"source": {"kind": "none"}},
}


def test_routing_get_and_choices():
    assert validate_call("tracks.routing.get", {"target": TRACK})[0] == {"target": TRACK}
    assert validate_call("tracks.routing.get", {"target": TRACK, "includeChoices": True})[0]["includeChoices"] is True
    for bad in (
        {"trackId": TRACK["id"]},
        {"target": TRACK["id"]},
        {"target": TRACK, "includeChoices": 1},
        {"target": TRACK, "output": {"kind": "none"}},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.routing.get", bad)


@pytest.mark.parametrize(
    "args",
    [
        {"output": {"kind": "track_audio_input", "track": OTHER_TRACK}, "expect": {"output": {"kind": "none"}}},
        {
            "output": {"kind": "track_note_input", "track": OTHER_TRACK},
            "expect": {"output": {"kind": "other", "name": "Out 1/2"}},
        },
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "track_audio_input", "track": OTHER_TRACK}}},
        {"input": {"kind": "post_fader", "track": OTHER_TRACK}, "expect": {"input": {"kind": "none"}}},
        {"input": {"kind": "all_ins"}, "expect": {"input": {"kind": "track_notes", "track": OTHER_TRACK}}},
        {"input": {"kind": "onscreen_keyboard"}},
        {"output": {"kind": "none"}},
        {
            "output": {"kind": "none"},
            "input": {"kind": "none"},
            "expect": {"output": {"kind": "none"}, "input": {"kind": "none"}},
        },
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "other", "name": "", "nativeType": "fe"}}},
    ],
)
def test_routing_set_accepts_entries_with_an_optional_expect(args):
    params, _ = validate_call("tracks.routing.set", {"target": TRACK, **args})
    assert params == {"target": TRACK, **args}


@pytest.mark.parametrize(
    "args",
    [
        {},
        {"expect": {"output": {"kind": "none"}}},
        {"input": {"kind": "none"}, "expect": {"output": {"kind": "none"}}},
        {"output": {"kind": "none"}, "expect": {}},
        {"output": {"kind": "none"}, "expectedOutput": {"kind": "none"}},
        {"output": {"kind": "pre_fader", "track": OTHER_TRACK}},
        {"input": {"kind": "track_audio_input", "track": OTHER_TRACK}},
        {"output": {"kind": "track_audio_input"}},
        {"output": {"kind": "none", "track": OTHER_TRACK}},
        {"output": {"kind": "other", "name": "Out"}},
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "other"}}},
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "none", "nativeType": "fe"}}},
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "other", "name": "x", "nativeType": ""}}},
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "none", "name": "x"}}},
        {"output": {"kind": "none"}, "expect": {"output": {"kind": "none", "extra": 1}}},
        {"input": {"kind": "hardware"}},
        {"input": {"kind": "pre_fader", "trackId": OTHER}},
        {"input": {"kind": "pre_fader", "track": {"kind": "track", "id": OTHER}}},
        {"input": {"kind": "none"}, "expect": {"input": None}},
    ],
)
def test_routing_set_rejects_ambiguous_or_unsupported(args):
    with pytest.raises(ValidationError):
        validate_call("tracks.routing.set", {"target": TRACK, **args})


def test_sidechain_get_takes_a_top_level_device_ref():
    assert validate_call("devices.sidechain.get", {"target": DEVICE, "includeChoices": True})[0]["includeChoices"]
    chained = {
        "kind": "device",
        "chain": {"kind": "chain", "device": DEVICE, "id": OTHER, "name": "Pad"},
        "index": 0,
        "name": "Tool",
    }
    for bad in ({"target": chained}, {"target": TRACK}, {"trackId": TRACK["id"], "deviceIndex": 0}):
        with pytest.raises(ValidationError):
            validate_call("devices.sidechain.get", bad)


def test_sidechain_set_addresses_the_component_by_its_ref():
    assert validate_call("devices.sidechain.set", SIDECHAIN)[0] == SIDECHAIN
    bare = {"target": SIDECHAIN_REF, "source": {"kind": "none"}}
    assert validate_call("devices.sidechain.set", bare)[0] == bare
    on_modulator = {**SIDECHAIN, "target": {**SIDECHAIN_REF, "modulatorId": OTHER}}
    assert validate_call("devices.sidechain.set", on_modulator)[0] == on_modulator
    on_module = {**SIDECHAIN, "target": {**SIDECHAIN_REF, "moduleId": OTHER}}
    assert validate_call("devices.sidechain.set", on_module)[0] == on_module


@pytest.mark.parametrize(
    "changes",
    [
        {"componentIndex": 0},
        {"componentId": "SIDECHAIN"},
        {"target": {**SIDECHAIN_REF, "id": ""}},
        {"target": {**SIDECHAIN_REF, "modulatorId": "mod"}},
        {"target": {**SIDECHAIN_REF, "moduleId": "module"}},
        {"target": {**SIDECHAIN_REF, "index": -1}},
        {"target": DEVICE},
        {"source": {"kind": "track_audio_input", "track": OTHER_TRACK}},
        {"source": {"kind": "other", "name": "In 1"}},
        {"expect": {"source": {"kind": "other"}}},
        {"expect": {}},
        {"expectedSource": {"kind": "none"}},
    ],
)
def test_sidechain_set_rejects_invalid(changes):
    with pytest.raises(ValidationError):
        validate_call("devices.sidechain.set", {**SIDECHAIN, **changes})
    with pytest.raises(ValidationError):
        validate_call("devices.sidechain.set", {k: v for k, v in SIDECHAIN.items() if k != "source"})


def test_routing_tools_are_writes_only_where_they_change_state():
    assert TOOLS["tracks.routing.get"].read_only
    assert TOOLS["devices.sidechain.get"].read_only
    assert not TOOLS["tracks.routing.set"].read_only
    assert not TOOLS["devices.sidechain.set"].read_only
