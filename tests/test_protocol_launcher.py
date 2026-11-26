from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TRACK
from bitwig_agent.protocol.launcher import NOTE_WINDOW
from bitwig_agent.protocol.limits import SCENE_WINDOW

TARGET = {"trackId": "11111111-1111-1111-1111-111111111111", "slotIndex": 0, "expectedClipName": "Verse"}


def test_next_clip_after_one_loop():
    args = {
        **TARGET,
        "nextAction": "play_next",
        "nextActionEnabled": True,
        "nextActionLinkToLoop": True,
        "nextActionLoopCount": 1,
        "nextActionDelayBeats": 0,
    }
    params, _ = validate_call("launcher.set", args)
    assert params == args
    assert validate_call("launcher.get", TARGET)[0] == TARGET


@pytest.mark.parametrize(
    "changes",
    [
        {},
        {"nextAction": "random"},
        {"nextActionEnabled": 1},
        {"nextActionLoopCount": 0},
        {"nextActionLoopCount": 65},
        {"nextActionLoopCount": 1.0},
        {"loopLengthBeats": 16},
        {"loopEnabled": True},
        {"playStartBeats": 0},
        {"playStopBeats": 16},
        {"playStartBeats": 4, "playStopBeats": 4},
        {"loopStartBeats": 4194304, "loopLengthBeats": 1},
        {"nextActionDelayBeats": None},
        {"expectedClipName": ""},
        {"slotIndex": SCENE_WINDOW},
        {"extra": 1},
    ],
)
def test_invalid_settings_rejected(changes):
    with pytest.raises(ValidationError):
        validate_call("launcher.set", {**TARGET, **changes})


def test_launch_fields_are_their_own_path():
    args = {**TARGET, "launchQuantization": "1/4", "launchMode": "trigger", "useLoopStartAsQuantizationReference": True}
    assert validate_call("launcher.set", args)[0] == args
    assert validate_call("launcher.set", {**TARGET, "launchMode": "gate"})[0] == {**TARGET, "launchMode": "gate"}


@pytest.mark.parametrize(
    "changes",
    [
        {"launchQuantization": ""},
        {"launchQuantization": 4},
        {"launchMode": None},
        {"useLoopStartAsQuantizationReference": "yes"},
        {"launchMode": "gate", "nextAction": "stop"},
        {"launchQuantization": "1", "nextActionEnabled": True},
    ],
)
def test_invalid_or_mixed_launch_fields_rejected(changes):
    with pytest.raises(ValidationError):
        validate_call("launcher.set", {**TARGET, **changes})


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
    "path", ["/absolute/path/audio.wav", "/tmp/Audio Sketch.bwclip", "/tmp/音楽.flac", "C:\\Audio Sketch\\音楽.flac"]
)
@pytest.mark.parametrize("slot", [0, SCENE_WINDOW - 1])
def test_launcher_import_preserves_local_path_without_filesystem_checks(path, slot):
    args = {"trackId": TRACK, "slotIndex": slot, "path": path}
    params, timeout = validate_call("launcher.import", args)
    assert params == args
    assert timeout == 5000


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


def test_unicode_length_matches_java_utf16_bounds():
    args = {"trackId": TRACK, "slotIndex": 0, "lengthBeats": 4, "name": "𝄞" * 64}
    params, _ = validate_call("launcher.create", args)
    assert params["name"] == args["name"]
    with pytest.raises(ValidationError):
        validate_call("launcher.create", {**args, "name": "𝄞" * 65})


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
