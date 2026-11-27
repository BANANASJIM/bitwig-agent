import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, invalid_params_message, invalid_params_reason, validate_call
from bitwig_agent.protocol.annotations import EDIT
from bitwig_agent.protocol.clips import MAX_TRANSFORM_TARGETS
from bitwig_agent.protocol.launcher import ARRANGER_CLIP_REF, CLIP_REF, SCENE_REF, SLOT_REF, TRACK_REF

SLOT = SLOT_REF
START = {"track": TRACK_REF, "start": 16}
NOTE = {"beat": 0, "pitch": 60, "velocity": 90, "duration": 1}


def note(**changes):
    return {**NOTE, **changes}


def reason(method, args):
    with pytest.raises(ValidationError) as error:
        validate_call(method, args)
    return invalid_params_reason(error.value)[0]


# ---- insert and import ----


def test_insert_into_a_slot_takes_a_loop_and_into_the_arranger_takes_a_length():
    launcher = {"to": SLOT, "loop": {"length": 4}, "name": "Sketch"}
    assert validate_call("clips.insert", launcher)[0] == launcher
    arranger = {"to": START, "length": 16, "name": "Verse"}
    assert validate_call("clips.insert", arranger)[0] == arranger
    assert validate_call("clips.insert", {**arranger, "length": 1 / 128})[0]["length"] == 1 / 128


@pytest.mark.parametrize(
    "args",
    [
        {"to": SLOT, "name": "x"},
        {"to": SLOT, "name": "x", "length": 4},
        {"to": SLOT, "name": "x", "loop": {"length": 4}, "length": 4},
        {"to": SLOT, "name": "x", "loop": {"length": 0}},
        {"to": SLOT, "name": "x", "loop": {"length": 4097}},
        {"to": SLOT, "name": "x", "loop": {"length": 1.5}},
        {"to": START, "name": "x", "loop": {"length": 4}},
        {"to": START, "name": "x"},
        {"to": START, "name": "x", "length": 0},
        {"to": START, "name": "x", "length": 65537},
        {"to": SCENE_REF, "name": "x", "loop": {"length": 4}},
        {"to": SLOT, "name": "", "loop": {"length": 4}},
        {"to": {"track": TRACK_REF, "start": -1}, "name": "x", "length": 4},
        {"trackId": TRACK_REF["id"], "slotIndex": 0, "length": 4, "name": "x"},
    ],
)
def test_insert_rejects_a_missing_wrong_or_mixed_length(args):
    with pytest.raises(ValidationError):
        validate_call("clips.insert", args)


def test_insert_notes_fail_before_dispatch_when_invalid():
    base = {"to": SLOT, "loop": {"length": 4}, "name": "Sketch"}
    params, _ = validate_call("clips.insert", {**base, "notes": [NOTE, {**NOTE, "beat": 1, "channel": 2}]})
    assert params["notes"][0] == NOTE
    assert params["notes"][1]["channel"] == 2
    bad: Any
    for bad in (
        {"notes": [{**NOTE, "beat": 3.5}]},
        {"notes": [NOTE, NOTE]},
        {"notes": [{**NOTE, "op": "set"}]},
        {"notes": []},
        {"notes": [NOTE], "channel": 1},
        {"notes": [NOTE], "detail": "full"},
        {"notes": [{**NOTE, "velocity": 0}]},
        {"notes": [{**NOTE, "channel": 16}]},
        {"notes": [{k: v for k, v in NOTE.items() if k != "duration"}]},
    ):
        with pytest.raises(ValidationError):
            validate_call("clips.insert", {**base, **bad})
    assert validate_call("clips.insert", {**base, "notes": [{**NOTE, "beat": 0.001}]})[0]["notes"][0]["beat"] == 0.001
    arranger = {"to": START, "length": 4, "name": "Verse"}
    assert validate_call("clips.insert", {**arranger, "notes": [{**NOTE, "channel": 1}]})[0]["notes"][0]["channel"] == 1
    with pytest.raises(ValidationError):
        validate_call("clips.insert", {**arranger, "notes": [{**NOTE, "beat": 3.5}]})


def test_unicode_length_matches_java_utf16_bounds():
    args = {"to": SLOT, "loop": {"length": 4}, "name": "𝄞" * 64}
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


def test_an_arranger_import_is_composed_by_the_client_and_never_reaches_the_host():
    args = {"to": START, "path": "/absolute/path/audio.wav"}
    assert validate_call("clips.import", args)[0] == args
    with pytest.raises(ValidationError):
        validate_call("clips.import", args, host=True)
    assert TOOLS["clips.import"].host_examples == TOOLS["clips.import"].examples[:1]


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
    "nextActionDelay": 0,
}


def test_set_takes_launcher_fields_in_one_call_without_splitting_the_paths():
    mixed = {
        "target": CLIP_REF,
        "name": "Verse",
        "color": "#ff8800",
        "loop": {"length": 8},
        **NEXT_ACTION,
        "launchQuantization": "1/4",
        "launchMode": "trigger",
        "useLoopStartAsQuantizationReference": True,
        "expect": {"name": "Hook", "loop": {"length": 4}, "nextAction": "stop"},
    }
    params, _ = validate_call("clips.set", mixed)
    assert params["loop"] == {"length": 8.0}
    assert {k: v for k, v in params.items() if k != "loop"} == {k: v for k, v in mixed.items() if k != "loop"}


def test_set_arranger_clip_takes_name_and_length_only():
    args = {"target": ARRANGER_CLIP_REF, "name": "Chorus", "length": 8, "expect": {"length": 4}}
    assert validate_call("clips.set", args)[0]["length"] == 8
    assert reason("clips.set", {"target": ARRANGER_CLIP_REF, "color": "#ff8800"}) == "unsupported_scope"
    assert reason("clips.set", {"target": ARRANGER_CLIP_REF, "loop": {"length": 8}}) == "unsupported_scope"
    assert reason("clips.set", {"target": ARRANGER_CLIP_REF, "nextAction": "stop"}) == "unsupported_scope"
    assert reason("clips.set", {"target": CLIP_REF, "length": 8}) == "unsupported_scope"


def test_set_refuses_expect_keys_the_clip_kind_cannot_have():
    arranger = {"target": ARRANGER_CLIP_REF, "name": "x"}
    assert validate_call("clips.set", {**arranger, "expect": {"length": 4}})
    assert reason("clips.set", {**arranger, "expect": {"nextAction": "stop"}}) == "unsupported_scope"
    assert reason("clips.set", {**arranger, "expect": {"loop": {"length": 4}}}) == "unsupported_scope"
    assert reason("clips.set", {"target": CLIP_REF, "name": "x", "expect": {"length": 4}}) == "unsupported_scope"


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
        {"loop": {"length": 0}},
        {"loop": {"length": 4097}},
        {"loop": {"length": 1 / 256}},
        {"loop": {}},
        {"nextAction": "random"},
        {"nextActionEnabled": 1},
        {"nextActionLoopCount": 0},
        {"nextActionLoopCount": 65},
        {"nextActionLoopCount": 1.0},
        {"nextActionDelay": None},
        {"launchQuantization": ""},
        {"launchQuantization": 4},
        {"launchMode": None},
        {"useLoopStartAsQuantizationReference": "yes"},
        {"loopLength": 16},
        {"playStop": 16},
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
    args = {"target": ARRANGER_CLIP_REF, "to": {"start": 32}}
    assert validate_call("clips.move", args)[0] == args
    assert reason("clips.move", {"target": CLIP_REF, "to": {"start": 32}}) == "unsupported_scope"
    for bad in ({"target": ARRANGER_CLIP_REF, "to": {"start": -1}}, {"target": ARRANGER_CLIP_REF, "to": {}}):
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


def test_notes_take_a_launcher_clip_ref_an_optional_range_and_records():
    params, _ = validate_call("notes.set", {"target": CLIP_REF, "notes": [note(), note(beat=1, pitch=64)]})
    assert set(params) == {"target", "notes"}
    params, _ = validate_call("notes.get", {"target": CLIP_REF})
    assert set(params) == {"target"}
    params, _ = validate_call("notes.get", {"target": CLIP_REF, "range": {"start": 0, "end": 4, "pitches": [36, 42]}})
    assert params["range"] == {"start": 0, "end": 4, "pitches": [36, 42]}
    assert validate_call("notes.set", {"target": CLIP_REF, "range": "all", "notes": []})[0]["notes"] == []
    for method, extra in (("notes.get", {}), ("notes.set", {"notes": [note()]})):
        for gone in ({"steps": 8}, {"channel": 0}, {"detail": "full"}, {"edits": [note()]}):
            with pytest.raises(ValidationError):
                validate_call(method, {"target": CLIP_REF, **gone, **extra})
        with pytest.raises(ValidationError):
            validate_call(method, {"target": ARRANGER_CLIP_REF, **extra})


def test_the_note_methods_wait_longer_by_default_for_the_public_grid():
    assert validate_call("notes.get", {"target": CLIP_REF})[1] == 30000
    assert validate_call("notes.set", {"target": CLIP_REF, "notes": [note()]})[1] == 30000
    insert = {"to": SLOT, "loop": {"length": 4}, "name": "Sketch"}
    assert validate_call("clips.insert", insert)[1] == 5000, "a clip without notes keeps the short default"
    assert validate_call("clips.insert", {**insert, "notes": [note()]})[1] == 30000
    assert validate_call("clips.insert", {**insert, "notes": [note()], "timeoutMs": 7000})[1] == 7000


@pytest.mark.parametrize(
    ("range_", "message"),
    [
        ({"start": 1, "end": 1.0000001}, "range.end must be at least 1/1048576 beat after range.start"),
        ({"start": 0, "end": 4, "pitches": [50, 40]}, "range.pitches is [low, high] with low <= high"),
        ("some", "range.all: Input should be 'all'"),
    ],
)
def test_a_bad_range_is_named_without_the_union_internals(range_, message):
    with pytest.raises(ValidationError) as error:
        validate_call("notes.get", {"target": CLIP_REF, "range": range_})
    assert invalid_params_message("notes.get", error.value) == message


def test_an_omitted_velocity_or_duration_is_an_update_of_an_existing_note():
    params, _ = validate_call("notes.set", {"target": CLIP_REF, "notes": [{"beat": 0, "pitch": 60, "velocity": 40}]})
    assert params["notes"] == [{"beat": 0, "pitch": 60, "velocity": 40}]


OPERATORS = {
    "chance": 0.85,
    "velocitySpread": 0.2,
    "releaseVelocity": 0,
    "repeat": {"count": -3, "curve": 0.3, "velocityEnd": -0.4, "velocityCurve": 1},
    "recurrence": {"length": 4, "mask": [False, False, False, True]},
    "occurrence": "not_prev_channel",
    "muted": True,
    "timbre": -1,
    "pressure": 1,
    "pan": 0.5,
    "gain": 0,
    "transpose": -96,
}


def test_operators_and_expressions_ride_on_the_note_record_and_null_reaches_the_wire():
    params, _ = validate_call("notes.set", {"target": CLIP_REF, "notes": [note(**OPERATORS)]})
    assert params["notes"] == [note(**OPERATORS)]
    off = dict.fromkeys(OPERATORS)
    params, _ = validate_call("notes.set", {"target": CLIP_REF, "notes": [{"beat": 0, "pitch": 60, **off}]})
    assert params["notes"] == [{"beat": 0, "pitch": 60, **off}], "explicit null turns a feature off"
    partial = {"beat": 0, "pitch": 60, "repeat": {"curve": 0.5}}
    assert validate_call("notes.set", {"target": CLIP_REF, "notes": [partial]})[0]["notes"] == [partial]
    insert = {"to": SLOT, "loop": {"length": 4}, "name": "Sketch", "notes": [note(**OPERATORS)]}
    assert validate_call("clips.insert", insert)[0]["notes"] == [note(**OPERATORS)]


def test_under_a_range_a_repeat_needs_its_count_and_without_one_it_may_patch():
    partial = {"beat": 0, "pitch": 60, "repeat": {"curve": 0.5}}
    text = message("notes.set", {"target": CLIP_REF, "range": "all", "notes": [partial]})
    assert "notes[0] repeat is off on this note: give repeat.count to turn it on" in text
    assert validate_call("notes.set", {"target": CLIP_REF, "notes": [partial]})[0]["notes"] == [partial]
    counted = {**partial, "repeat": {"count": 3}}
    assert validate_call("notes.set", {"target": CLIP_REF, "range": "all", "notes": [counted]})[0]["notes"] == [counted]


@pytest.mark.parametrize(
    "feature",
    [
        {"chance": 1.01},
        {"velocitySpread": -0.1},
        {"releaseVelocity": 128},
        {"repeat": {"count": 128}},
        {"repeat": {"count": 1, "curve": 1.5}},
        {"repeat": {"count": 1, "speed": 2}},
        {"recurrence": {"length": 9, "mask": [True] * 9}},
        {"recurrence": {"length": 4, "mask": [True]}},
        {"recurrence": {"length": 4}},
        {"occurrence": "sometimes"},
        {"occurrence": "FIRST"},
        {"muted": 1},
        {"timbre": -1.5},
        {"pressure": -0.1},
        {"gain": 1.1},
        {"transpose": 97},
    ],
)
def test_operator_values_outside_bitwigs_ranges_are_refused(feature):
    with pytest.raises(ValidationError):
        validate_call("notes.set", {"target": CLIP_REF, "notes": [note(**feature)]})


def test_a_new_note_turning_repeats_on_needs_a_count_and_arranger_notes_take_no_operators():
    insert = {"to": SLOT, "loop": {"length": 4}, "name": "Sketch"}
    text = message("clips.insert", {**insert, "notes": [note(repeat={"curve": 0.2})]})
    assert "notes[0] repeat is off on this note: give repeat.count to turn it on" in text
    arranger = {"to": START, "length": 16, "name": "Verse", "notes": [note(chance=0.5)]}
    assert reason("clips.insert", arranger) == "unsupported_scope"
    assert validate_call("clips.insert", {**arranger, "notes": [note()]})[0]["notes"] == [note()]


@pytest.mark.parametrize(
    "args",
    [
        {"notes": []},
        {"notes": [note(), note()]},
        {"notes": [note(), note(beat=0.5)]},
        {"notes": [note(), note(beat=0.0000001)]},
        {"notes": [note(pitch=True)]},
        {"notes": [note(velocity=0)]},
        {"notes": [note(velocity=128)]},
        {"notes": [note(extra=1)]},
        {"notes": [note(op="set")]},
        {"range": {"start": 2, "end": 2}, "notes": []},
        {"range": {"start": 0, "end": 4, "pitches": [62, 60]}, "notes": []},
        {"range": {"start": 0, "end": 4, "pitches": [60]}, "notes": []},
        {"range": "whole", "notes": []},
        {"range": {"start": 1, "end": 4}, "notes": [note(beat=0.5, duration=0.25)]},
        {"range": {"start": 0, "end": 4}, "notes": [note(beat=4)]},
        {"range": {"start": 0, "end": 4, "pitches": [36, 42]}, "notes": [note()]},
    ],
)
def test_rejects_ambiguous_or_out_of_range_notes(args):
    with pytest.raises(ValidationError):
        validate_call("notes.set", {"target": CLIP_REF, **args})


def message(method, args):
    with pytest.raises(ValidationError) as error:
        validate_call(method, args)
    return str(error.value)


def test_durations_need_no_grid_and_overlap_is_judged_as_stored():
    insert = {"to": SLOT, "loop": {"length": 8}, "name": "Bass"}
    eighths = [{**NOTE, "beat": b / 2, "duration": 0.45} for b in range(16)]
    params, _ = validate_call("clips.insert", {**insert, "notes": eighths})
    assert params["notes"][0]["duration"] == 0.45
    # 0.5000001 is stored as 0.5 (1/1048576-beat steps): it ends where the next same-pitch note starts
    validate_call("notes.set", {"target": CLIP_REF, "notes": [note(duration=0.5000001), note(beat=0.5)]})
    text = message("notes.set", {"target": CLIP_REF, "notes": [note(duration=0.5000006), note(beat=0.5)]})
    assert "notes[1] starts at beat 0.5 before notes[0] on pitch 60 ends at beat 0.5000006" in text
    validate_call("notes.set", {"target": CLIP_REF, "notes": [note(), note(beat=0.5, channel=1)]})


def test_every_broken_rule_is_reported_at_once_with_its_index_and_fix():
    insert = {"to": SLOT, "loop": {"length": 4}, "name": "Bass"}
    notes = [
        {**NOTE, "pitch": 33, "duration": 0.75},
        {**NOTE, "pitch": 33, "beat": 0.5, "duration": 0.25},
        {**NOTE, "pitch": 40, "beat": 1.3, "duration": 0.25},
        {**NOTE, "pitch": 41, "beat": 3.5},
        {**NOTE, "pitch": 40, "beat": 1.3, "duration": 0.25},
    ]
    text = message("clips.insert", {**insert, "notes": notes})
    assert "notes: 3 problems, nothing was written: " in text
    assert (
        "notes[1] starts at beat 0.5 before notes[0] on pitch 33 ends at beat 0.75: shorten notes[0] to duration <= 0.5"
        in text
    )
    assert "notes[2]" not in text.split("notes[3]")[0].split("notes[1]")[1], "an off-grid onset is a valid note"
    assert "notes[3] ends at beat 4.5, past the clip length 4: shorten it to duration <= 0.5" in text
    assert "notes[4] repeats the channel, beat and pitch of notes[2]: remove one" in text
    assert text.index("notes[1]") < text.index("notes[3]") < text.index("notes[4]")
    late = message("clips.insert", {**insert, "notes": [{**NOTE, "beat": 4}]})
    assert "notes[0] starts at beat 4, at or past the clip length 4" in late


def test_a_range_reports_every_listed_note_outside_it():
    text = message(
        "notes.set",
        {
            "target": CLIP_REF,
            "range": {"start": 1, "end": 4, "pitches": [60, 62]},
            "notes": [note(beat=0.5, duration=0.25), note(beat=5), note(beat=2, pitch=70)],
        },
    )
    assert "notes: 3 problems" in text
    assert "notes[0] beat 0.5 is before range.start 1: move it inside, or lower range.start" in text
    assert "notes[1] beat 5 is outside range [1, 4): move it inside, or raise range.end above 5" in text
    assert "notes[2] pitch 70 is outside range.pitches [60, 62]: move it inside, or widen range.pitches" in text
    assert "is empty" in message("notes.set", {"target": CLIP_REF, "notes": []})


SHARED_VECTORS = json.loads((Path(__file__).parent / "fixtures/note_rules.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("case", SHARED_VECTORS, ids=lambda case: case["name"])
def test_shared_note_rule_vectors(case):
    """The same file runs in the extension (NoteRulesVectorTest): same verdict, same text."""
    params = case["params"]
    if case["method"] == "notes.set":
        args = {"target": CLIP_REF, **params}
    else:
        args = {"to": SLOT, "loop": {"length": params["length"]}, "name": "x", "notes": params["notes"]}
    if case["error"] is None:
        validate_call(case["method"], args)
        return
    with pytest.raises(ValidationError) as error:
        validate_call(case["method"], args)
    assert error.value.errors()[0]["msg"].removeprefix("Value error, ") == case["error"]


def test_clips_record_takes_a_slot_ref_and_needs_the_engine():
    assert validate_call("clips.record", {"target": SLOT_REF})[0] == {"target": SLOT_REF}
    for bad in ({"target": CLIP_REF}, {}, {"target": SLOT_REF, "length": 4}):
        with pytest.raises(ValidationError):
            validate_call("clips.record", bad)
    spec = TOOLS["clips.record"]
    assert spec.requires == ("engine",)
    assert spec.inspect == "launcher.list"
    assert spec.annotations is EDIT
