import pytest
from pydantic import ValidationError

from bitwig_agent.catalog import validate_call

TRACK = "11111111-1111-1111-1111-111111111111"
OTHER = "22222222-2222-2222-2222-222222222222"
NOTE = {"beat": 0, "pitch": 60, "velocity": 100, "durationBeats": 1}
CREATE = {"trackId": TRACK, "startBeats": 16, "lengthBeats": 4, "name": "Verse"}
EDIT = {"trackId": TRACK, "clipIndex": 0, "expectedStartBeats": 16, "expectedName": "Verse"}


def test_group_keeps_explicit_track_order_and_optional_name():
    params, _ = validate_call("tracks.group", {"trackIds": [OTHER, TRACK], "name": "Drums"})
    assert params == {"trackIds": [OTHER, TRACK], "name": "Drums"}
    assert validate_call("tracks.group", {"trackIds": [TRACK]})[0] == {"trackIds": [TRACK]}


@pytest.mark.parametrize(
    "args",
    [
        {"trackIds": []},
        {"trackIds": [TRACK, TRACK]},
        {"trackIds": [TRACK, TRACK.upper()]},
        {"trackIds": ["not-a-uuid"]},
        {"trackIds": [f"00000000-0000-0000-0000-{i:012d}" for i in range(17)]},
        {"trackIds": [TRACK], "name": ""},
        {"trackIds": [TRACK], "position": 0},
        {"name": "Drums"},
    ],
)
def test_group_rejects_ambiguous_requests(args):
    with pytest.raises(ValidationError):
        validate_call("tracks.group", args)


def test_arranger_create_with_notes_and_channel():
    args = {
        **CREATE,
        "notes": [NOTE, {**NOTE, "beat": 1}, {**NOTE, "pitch": 64, "beat": 3.5, "durationBeats": 0.5}],
        "channel": 2,
    }
    assert validate_call("arranger.clips.create", args)[0] == args
    assert validate_call("arranger.clips.create", {**CREATE, "lengthBeats": 1 / 128})[0]["lengthBeats"] == 1 / 128


@pytest.mark.parametrize(
    "changes",
    [
        {"startBeats": -1},
        {"lengthBeats": 0},
        {"lengthBeats": 65537},
        {"name": ""},
        {"name": "x" * 129},
        {"channel": 1},
        {"notes": []},
        {"notes": [{**NOTE, "op": "set"}]},
        {"notes": [NOTE, {**NOTE, "beat": 0.5}]},
        {"notes": [NOTE, NOTE]},
        {"notes": [{**NOTE, "beat": 3.5}]},
        {"notes": [{**NOTE, "beat": 0.001}]},
        {"notes": [{**NOTE, "velocity": 0}]},
        {"trackId": "track"},
    ],
)
def test_arranger_create_rejects_invalid(changes):
    with pytest.raises(ValidationError):
        validate_call("arranger.clips.create", {**CREATE, **changes})


def test_arranger_copy_and_list():
    copy = {"trackId": TRACK, "slotIndex": 3, "expectedClipName": "Verse", "startBeats": 32}
    assert validate_call("arranger.clips.copyFromLauncher", copy)[0] == copy
    assert validate_call("arranger.clips.list", {"trackId": TRACK})[0] == {"trackId": TRACK}
    for bad in ({"slotIndex": 16}, {"expectedClipName": ""}, {"startBeats": -0.5}):
        with pytest.raises(ValidationError):
            validate_call("arranger.clips.copyFromLauncher", {**copy, **bad})


@pytest.mark.parametrize(
    ("op", "payload"),
    [("move", {"startBeats": 32}), ("resize", {"lengthBeats": 8}), ("rename", {"name": "Chorus"}), ("delete", {})],
)
def test_arranger_edit_ops_take_exactly_their_payload(op, payload):
    args = {**EDIT, "op": op, **payload}
    assert validate_call("arranger.clips.edit", args)[0] == args
    assert validate_call("arranger.clips.edit", {**args, "expectedName": ""})[0]["expectedName"] == ""


@pytest.mark.parametrize(
    "args",
    [
        {**EDIT, "op": "move"},
        {**EDIT, "op": "delete", "startBeats": 1},
        {**EDIT, "op": "move", "startBeats": 1, "lengthBeats": 2},
        {**EDIT, "op": "split"},
        {**EDIT, "op": "rename", "name": ""},
        {**EDIT, "op": "resize", "lengthBeats": 0},
        {**EDIT, "clipIndex": 4096, "op": "delete"},
        {**EDIT, "expectedName": "x" * 257, "op": "delete"},
    ],
)
def test_arranger_edit_rejects_invalid(args):
    with pytest.raises(ValidationError):
        validate_call("arranger.clips.edit", args)
