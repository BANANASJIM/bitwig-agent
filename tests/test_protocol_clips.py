from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, invalid_params_reason, validate_call
from bitwig_agent.protocol.clips import MAX_TRANSFORM_TARGETS, NOTE_WINDOW
from bitwig_agent.protocol.launcher import ARRANGER_CLIP_REF, CLIP_REF, SCENE_REF, SLOT_REF, TRACK_REF

SLOT = SLOT_REF
START = {"track": TRACK_REF, "startBeats": 16}
NOTE = {"beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1}


def note(**changes):
    return {"op": "set", "beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1, **changes}


def reason(method, args):
    with pytest.raises(ValidationError) as error:
        validate_call(method, args)
    return invalid_params_reason(error.value)[0]


# ---- insert and import ----


def test_insert_into_a_slot_takes_a_loop_and_into_the_arranger_takes_a_length():
    launcher = {"to": SLOT, "loop": {"lengthBeats": 4}, "name": "Sketch"}
    assert validate_call("clips.insert", launcher)[0] == launcher
    arranger = {"to": START, "lengthBeats": 16, "name": "Verse"}
    assert validate_call("clips.insert", arranger)[0] == arranger
    assert validate_call("clips.insert", {**arranger, "lengthBeats": 1 / 128})[0]["lengthBeats"] == 1 / 128


@pytest.mark.parametrize(
    "args",
    [
        {"to": SLOT, "name": "x"},
        {"to": SLOT, "name": "x", "lengthBeats": 4},
        {"to": SLOT, "name": "x", "loop": {"lengthBeats": 4}, "lengthBeats": 4},
        {"to": SLOT, "name": "x", "loop": {"lengthBeats": 0}},
        {"to": SLOT, "name": "x", "loop": {"lengthBeats": 4097}},
        {"to": SLOT, "name": "x", "loop": {"lengthBeats": 1.5}},
        {"to": START, "name": "x", "loop": {"lengthBeats": 4}},
        {"to": START, "name": "x"},
        {"to": START, "name": "x", "lengthBeats": 0},
        {"to": START, "name": "x", "lengthBeats": 65537},
        {"to": SCENE_REF, "name": "x", "loop": {"lengthBeats": 4}},
        {"to": SLOT, "name": "", "loop": {"lengthBeats": 4}},
        {"to": {"track": TRACK_REF, "startBeats": -1}, "name": "x", "lengthBeats": 4},
        {"trackId": TRACK_REF["id"], "slotIndex": 0, "lengthBeats": 4, "name": "x"},
    ],
)
def test_insert_rejects_a_missing_wrong_or_mixed_length(args):
    with pytest.raises(ValidationError):
        validate_call("clips.insert", args)


def test_insert_notes_fail_before_dispatch_when_invalid():
    base = {"to": SLOT, "loop": {"lengthBeats": 4}, "name": "Sketch"}
    params, _ = validate_call("clips.insert", {**base, "notes": [NOTE, {**NOTE, "beat": 1}], "channel": 2})
    assert params["notes"][0] == NOTE
    assert params["channel"] == 2
    bad: Any
    for bad in (
        {"notes": [{**NOTE, "beat": 3.5}]},
        {"notes": [NOTE, NOTE]},
        {"notes": [{**NOTE, "op": "set"}]},
        {"notes": []},
        {"channel": 1},
        {"notes": [{**NOTE, "velocity": 0}]},
        {"notes": [{**NOTE, "beat": 0.001}]},
    ):
        with pytest.raises(ValidationError):
            validate_call("clips.insert", {**base, **bad})
    arranger = {"to": START, "lengthBeats": 4, "name": "Verse"}
    assert validate_call("clips.insert", {**arranger, "notes": [NOTE], "channel": 1})[0]["channel"] == 1
    for bad in ({"channel": 1}, {"notes": [{**NOTE, "beat": 3.5}]}, {"notes": [NOTE], "detail": "full"}):
        with pytest.raises(ValidationError):
            validate_call("clips.insert", {**arranger, **bad})


def test_unicode_length_matches_java_utf16_bounds():
    args = {"to": SLOT, "loop": {"lengthBeats": 4}, "name": "𝄞" * 64}
    assert validate_call("clips.insert", args)[0]["name"] == args["name"]
    with pytest.raises(ValidationError):
        validate_call("clips.insert", {**args, "name": "𝄞" * 65})


@pytest.mark.parametrize(
    "path", ["/absolute/path/audio.wav", "/tmp/Audio Sketch.bwclip", "/tmp/音楽.flac", "C:\\Audio Sketch\\音楽.flac"]
)
def test_import_preserves_local_path_without_filesystem_checks(path):
    args = {"to": SLOT, "path": path}
    params, timeout = validate_call("clips.import", args)
    assert params == args
    assert timeout == 5000


@pytest.mark.parametrize(
    "override",
    [
        {"to": TRACK_REF},
        {"to": None},
        {"to": START},
        {"to": CLIP_REF},
        {"path": "file:///tmp/audio.wav"},
        {"path": "relative.wav"},
        {"path": ""},
        {"overwrite": False},
        {"unknown": 1},
        {"timeoutMs": 99},
    ],
)
def test_import_rejects_invalid_targets_and_extra_fields(override):
    with pytest.raises(ValidationError):
        validate_call("clips.import", {"to": SLOT, "path": "/absolute/path/audio.wav", **override})


@pytest.mark.parametrize("field", ["to", "path"])
def test_import_requires_explicit_destination_and_path(field):
    args = {"to": SLOT, "path": "/absolute/path/audio.wav"}
    del args[field]
    with pytest.raises(ValidationError):
        validate_call("clips.import", args)


# ---- get, set, delete, copy, move, launch ----


def test_get_delete_launch_take_a_clip_ref_and_get_only_a_launcher_clip():
    for method in ("clips.get", "clips.launch"):
        assert validate_call(method, {"target": CLIP_REF})[0] == {"target": CLIP_REF}
        with pytest.raises(ValidationError):
            validate_call(method, {"target": ARRANGER_CLIP_REF})
    for target in (CLIP_REF, ARRANGER_CLIP_REF):
        assert validate_call("clips.delete", {"target": target})[0] == {"target": target}
    assert TOOLS["clips.get"].read_only
    assert not TOOLS["clips.delete"].read_only


NEXT_ACTION = {
    "nextAction": "play_next",
    "nextActionEnabled": True,
    "nextActionLinkToLoop": True,
    "nextActionLoopCount": 1,
    "nextActionDelayBeats": 0,
}


def test_set_takes_launcher_fields_in_one_call_without_splitting_the_paths():
    mixed = {
        "target": CLIP_REF,
        "name": "Verse",
        "color": "#ff8800",
        "loop": {"lengthBeats": 8},
        **NEXT_ACTION,
        "launchQuantization": "1/4",
        "launchMode": "trigger",
        "useLoopStartAsQuantizationReference": True,
        "expect": {"name": "Hook", "loop": {"lengthBeats": 4}, "nextAction": "stop"},
    }
    params, _ = validate_call("clips.set", mixed)
    assert params["loop"] == {"lengthBeats": 8.0}
    assert {k: v for k, v in params.items() if k != "loop"} == {k: v for k, v in mixed.items() if k != "loop"}


def test_set_arranger_clip_takes_name_and_length_only():
    args = {"target": ARRANGER_CLIP_REF, "name": "Chorus", "lengthBeats": 8, "expect": {"lengthBeats": 4}}
    assert validate_call("clips.set", args)[0]["lengthBeats"] == 8
    assert reason("clips.set", {"target": ARRANGER_CLIP_REF, "color": "#ff8800"}) == "unsupported_scope"
    assert reason("clips.set", {"target": ARRANGER_CLIP_REF, "loop": {"lengthBeats": 8}}) == "unsupported_scope"
    assert reason("clips.set", {"target": ARRANGER_CLIP_REF, "nextAction": "stop"}) == "unsupported_scope"
    assert reason("clips.set", {"target": CLIP_REF, "lengthBeats": 8}) == "unsupported_scope"


def test_set_refuses_expect_keys_the_clip_kind_cannot_have():
    arranger = {"target": ARRANGER_CLIP_REF, "name": "x"}
    assert validate_call("clips.set", {**arranger, "expect": {"lengthBeats": 4}})
    assert reason("clips.set", {**arranger, "expect": {"nextAction": "stop"}}) == "unsupported_scope"
    assert reason("clips.set", {**arranger, "expect": {"loop": {"lengthBeats": 4}}}) == "unsupported_scope"
    assert reason("clips.set", {"target": CLIP_REF, "name": "x", "expect": {"lengthBeats": 4}}) == "unsupported_scope"


@pytest.mark.parametrize(
    "changes",
    [
        {},
        {"expect": {"name": "Hook"}},
        {"name": ""},
        {"name": "a" * 129},
        {"color": "ff0000"},
        {"color": "#ff00"},
        {"color": "#gg0000"},
        {"color": "#ff0000\n"},
        {"loop": {"lengthBeats": 0}},
        {"loop": {"lengthBeats": 4097}},
        {"loop": {"lengthBeats": 1 / 256}},
        {"loop": {}},
        {"nextAction": "random"},
        {"nextActionEnabled": 1},
        {"nextActionLoopCount": 0},
        {"nextActionLoopCount": 65},
        {"nextActionLoopCount": 1.0},
        {"nextActionDelayBeats": None},
        {"launchQuantization": ""},
        {"launchQuantization": 4},
        {"launchMode": None},
        {"useLoopStartAsQuantizationReference": "yes"},
        {"loopLengthBeats": 16},
        {"playStopBeats": 16},
        {"expect": {}},
        {"name": "x", "expect": {"color": "#ff0000"}},
        {"name": "x", "expect": {"launchMode": "gate"}},
        {"name": "x", "expect": {"nextAction": "random"}},
        {"name": "x", "extra": 1},
    ],
)
def test_set_rejects_empty_unknown_or_malformed_fields(changes):
    with pytest.raises(ValidationError):
        validate_call("clips.set", {"target": CLIP_REF, **changes})


def test_set_requires_a_clip_ref():
    for target in (SLOT, SCENE_REF, TRACK_REF, None):
        with pytest.raises(ValidationError):
            validate_call("clips.set", {"target": target, "name": "x"})


def test_copy_goes_from_a_launcher_clip_to_a_slot_or_the_arranger():
    for destination in (SLOT, START):
        args = {"from": CLIP_REF, "to": destination}
        assert validate_call("clips.copy", args)[0] == args
    for bad in (
        {"from": ARRANGER_CLIP_REF, "to": SLOT},
        {"from": CLIP_REF, "to": TRACK_REF},
        {"from": CLIP_REF},
        {"to": SLOT},
        {"from_": CLIP_REF, "to": SLOT},
    ):
        with pytest.raises(ValidationError):
            validate_call("clips.copy", bad)


def test_move_takes_an_arranger_clip_and_a_start():
    args = {"target": ARRANGER_CLIP_REF, "to": {"startBeats": 32}}
    assert validate_call("clips.move", args)[0] == args
    assert reason("clips.move", {"target": CLIP_REF, "to": {"startBeats": 32}}) == "unsupported_scope"
    for bad in ({"target": ARRANGER_CLIP_REF, "to": {"startBeats": -1}}, {"target": ARRANGER_CLIP_REF, "to": {}}):
        with pytest.raises(ValidationError):
            validate_call("clips.move", bad)


# ---- note content ----


@pytest.mark.parametrize("semitones", [-48, -1, 1, 48])
def test_transpose_accepts_a_nonzero_shift(semitones):
    args = {"target": CLIP_REF, "semitones": semitones}
    assert validate_call("clips.transpose", args)[0] == args


@pytest.mark.parametrize("semitones", [0, 49, -49, 1.5, None])
def test_transpose_rejects_a_zero_or_out_of_range_shift(semitones):
    with pytest.raises(ValidationError):
        validate_call("clips.transpose", {"target": CLIP_REF, "semitones": semitones})


def test_quantize_and_transform():
    for amount in (0.01, 1):
        assert validate_call("clips.quantize", {"target": CLIP_REF, "amount": amount})[0]["amount"] == amount
    for amount in (0, 1.01, -0.01):
        with pytest.raises(ValidationError):
            validate_call("clips.quantize", {"target": CLIP_REF, "amount": amount})
    args = {"targets": [CLIP_REF], "op": "doubleContent"}
    assert validate_call("clips.transform", args)[0] == args
    for bad in (
        {"targets": [], "op": "doubleContent"},
        {"targets": [CLIP_REF] * (MAX_TRANSFORM_TARGETS + 1), "op": "doubleContent"},
        {"targets": [CLIP_REF], "op": "reverse"},
        {"targets": [ARRANGER_CLIP_REF], "op": "doubleContent"},
        {"target": CLIP_REF, "op": "doubleContent"},
    ):
        with pytest.raises(ValidationError):
            validate_call("clips.transform", bad)


def test_clips_edit_is_gone_and_its_ops_are_methods():
    assert "clips.edit" not in TOOLS
    assert {"clips.set", "clips.delete", "clips.copy", "clips.transpose", "clips.quantize", "clips.transform"} <= set(
        TOOLS
    )


# ---- notes ----


def test_notes_take_a_clip_ref_and_the_window_is_optional_but_all_or_none():
    params, _ = validate_call("notes.edit", {"target": CLIP_REF, "edits": [note(), note(beat=1, pitch=64)]})
    assert set(params) == {"target", "edits"}
    params, _ = validate_call("notes.read", {"target": CLIP_REF})
    assert set(params) == {"target"}
    for method, extra in (("notes.read", {}), ("notes.edit", {"edits": [note()]})):
        with pytest.raises(ValidationError):
            validate_call(method, {"target": CLIP_REF, "steps": 8, **extra})
    for method in ("notes.read", "notes.edit"):
        with pytest.raises(ValidationError):
            validate_call(
                method, {"target": ARRANGER_CLIP_REF, **({"edits": [note()]} if method.endswith("edit") else {})}
            )


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
        validate_call("notes.edit", {"target": CLIP_REF, "edits": edits})


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


def test_detail_is_edit_and_insert_only_and_enumerated():
    params, _ = validate_call("notes.edit", {"target": CLIP_REF, "edits": [note()], "detail": "full"})
    assert params["detail"] == "full"
    insert = {"to": SLOT, "loop": {"lengthBeats": 4}, "name": "x"}
    params, _ = validate_call("clips.insert", {**insert, "notes": [NOTE], "detail": "full"})
    assert params["detail"] == "full"
    for method, args in (
        ("notes.edit", {"target": CLIP_REF, "edits": [note()], "detail": "verbose"}),
        ("notes.read", {"target": CLIP_REF, "detail": "full"}),
        ("clips.insert", {**insert, "detail": "full"}),
    ):
        with pytest.raises(ValidationError):
            validate_call(method, args)
