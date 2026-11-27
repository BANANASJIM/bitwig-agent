"""The read-only text views: golden renderings and the properties that make them safe to read."""

import copy
import re
from pathlib import Path
from typing import Any

import pytest
import wcwidth
from hypothesis import given, settings
from hypothesis import strategies as st

from bitwig_agent.core import arranger_timeline, notes_grid, text_views
from bitwig_agent.protocol import TOOLS

GRID_GUIDANCE = notes_grid.GUIDANCE
TIMELINE_GUIDANCE = arranger_timeline.GUIDANCE


def note(beat, pitch, velocity, duration=0.25, **extra):
    return {"beat": beat, "pitch": pitch, "velocity": velocity, "duration": duration, **extra}


def clip_result(notes, length=4, name="drums A", **extra):
    return {
        "ref": {"kind": "clip", "name": name},
        "loop": {"start": 0, "length": length},
        "count": len(notes),
        "complete": True,
        "exact": True,
        "notes": notes,
        **extra,
    }


def lines(*text):
    return "\n".join(text) + "\n"


DRUMS = [
    note(0, 36, 118),
    note(1.75, 36, 100),
    note(2.5, 36, 115),
    *(note(beat / 2, 42, 90 if beat % 2 == 0 else 50) for beat in range(8)),
    note(1, 38, 100),
    note(3, 38, 100),
    note(3.5, 38, 40),
]


def test_a_drum_pattern_is_a_pitch_by_step_grid_with_velocity_marks():
    assert notes_grid.render(clip_result(DRUMS)) == lines(
        '"drums A"  beats 0-4  step 1/16',
        notes_grid.LEGEND,
        "pitch   1 . . . 2 . . . 3 . . . 4 . . .",
        "42 F#1  x . o . x . o . x . o . x . o .",
        "38 D1   . . . . x . . . . . . . x . o .",
        "36 C1   X . . . . . . x . . X . . . . .",
        "off-grid: none",
        GRID_GUIDANCE,
    )


def test_onsets_off_the_step_are_listed_exactly_and_not_drawn():
    result = clip_result([note(0.01, 60, 90), note(1, 60, 90), note(5, 60, 90)], length=2, name="lead")
    assert notes_grid.render(result) == lines(
        '"lead"  beats 0-2  step 1/16',
        notes_grid.LEGEND,
        "pitch  1 . . . 2 . . .",
        "60 C3  . . . . x . . .",
        "off-grid: 60@0.01 v90 d0.25",
        "outside beats 0-2: 60@5 v90 d0.25",
        GRID_GUIDANCE,
    )


def test_an_onset_a_tick_before_the_window_end_is_off_grid_not_a_column_past_it():
    last = 4 - 1 / 1048576
    text = notes_grid.render(clip_result([note(0, 60, 90), note(last, 60, 90)], length=4))
    assert text.splitlines()[3] == "60 C3  x . . . . . . . . . . . . . . ."
    assert "off-grid: 60@3.9999990463256836 v90 d0.25" in text


def test_the_step_is_the_coarsest_every_onset_lies_on_and_held_cells_show_duration():
    triplets = clip_result([note(0, 60, 90, 1 / 3), note(1 / 3, 62, 90), note(2 / 3, 64, 90)], length=1)
    assert notes_grid.render(triplets).splitlines()[0].endswith("step 1/16T")
    assert notes_grid.render(triplets).splitlines()[2:6] == [
        "pitch  1 . . . . .",
        "64 E3  . . . . x -",
        "62 D3  . . x - . .",
        "60 C3  x - . . . .",
    ]
    for beat, step in [(0.125, "1/32"), (1 / 12, "1/32T"), (0.0625, "1/64")]:
        result = clip_result([note(0, 60, 90), note(beat, 62, 30)], length=1)
        assert notes_grid.render(result).splitlines()[0].endswith(f"step {step}")


def test_channels_split_rows_and_extra_fields_are_footnoted_operators():
    result = clip_result(
        [
            note(0, 60, 90, 1),
            note(0, 60, 40, channel=2),
            note(1, 127, 90, chance=0.5, repeat={"count": 3}),
            note(1.5, 127, 90, chance=0.5, repeat={"count": 3}),
            note(2, 0, 120, chance=0.25, channel=1),
        ],
        length=4,
        complete=False,
        count=9,
        exact=False,
        incompleteReason="past_loop_end",
    )
    assert notes_grid.render(result) == lines(
        '"drums A"  beats 0-4  step 1/16',
        notes_grid.LEGEND,
        "pitch       1 . . . 2 . . . 3 . . . 4 . . .",
        "127 G8 ch0  . . . . x . x . . . . . . . . .",
        "60 C3 ch0   x - - - . . . . . . . . . . . .",
        "60 C3 ch2   o . . . . . . . . . . . . . . .",
        "0 C-2 ch1   . . . . . . . . X . . . . . . .",
        'operators: 127@1,1.5 chance 0.5, repeat {"count":3} · 0@2 ch1 chance 0.25',
        "off-grid: none",
        "incomplete (past_loop_end): 5 of 9 notes listed; read the rest by range",
        "exact=false: read through the public note grid, onsets on the 1/128-beat grid",
        GRID_GUIDANCE,
    )


def test_a_range_is_the_window_and_an_empty_one_says_so():
    assert notes_grid.render(clip_result([], range={"start": 2, "end": 3, "pitches": [36, 36]})) == lines(
        '"drums A"  beats 2-3  step 1/16',
        notes_grid.LEGEND,
        "no notes",
        "off-grid: none",
        GRID_GUIDANCE,
    )


def test_a_long_clip_wraps_into_blocks_of_whole_beats_with_beat_numbers():
    text = notes_grid.render(clip_result([note(beat, 36, 100) for beat in range(20)], length=20))
    rulers = [line for line in text.splitlines() if line.startswith("pitch")]
    assert rulers == [
        "pitch  1 . . . 2 . . . 3 . . . 4 . . . 5 . . . 6 . . . 7 . . . 8 . . . 9 . . . 10  . . 11  . . 12  . . "
        "13  . . 14  . . 15  . . 16  . .",
        "pitch  17  . . 18  . . 19  . . 20  . .",
    ]
    assert "\n\npitch  17" in text


def test_a_window_too_wide_for_the_grid_is_refused_with_the_fix():
    with pytest.raises(ValueError, match=r"1048576 columns at 1/16: give range \{start, end\} of at most 256 beats"):
        notes_grid.render(clip_result([], range={"start": 0, "end": 262144}))


@pytest.mark.parametrize(
    ("result", "message"),
    [
        ({"notes": [{"beat": 0}], "loop": {"start": 0, "length": 4}}, "lacks beat, pitch, velocity or duration"),
        ({"notes": []}, "neither range"),
        ({"notes": [], "loop": {"start": 0, "length": 0}}, "window is empty"),
        ({"loop": {"start": 0, "length": 4}}, "no array of objects named notes"),
    ],
)
def test_a_result_without_what_the_grid_needs_is_refused(result, message):
    with pytest.raises(ValueError, match=message):
        notes_grid.render(result)


def track(track_id, name):
    return {"kind": "track", "id": track_id, "name": name}


def row(track_ref, name, start, end, with_end=True):
    clip = {"ref": {"kind": "clip", "track": track_ref, "start": start, "name": name}, "start": start}
    return {**clip, "length": end - start, "name": name, **({"end": end} if with_end else {})}


DRUMS_TRACK, BASS, PAD = track("d", "Drums"), track("b", "Bass"), track("p", "Pad")
SONG = {
    "clips": [
        row(DRUMS_TRACK, "drums A", 32, 96),
        row(DRUMS_TRACK, "drums drop", 96, 128),
        row(BASS, "bass A", 32, 64),
        row(BASS, "bass drop", 97, 128, with_end=False),
        row(PAD, "pad", 0, 32),
        row(PAD, "pad", 64, 128),
    ],
    "markers": [{"name": "drop", "beat": 96}, {"name": "intro", "beat": 0}, {"name": "verse", "beat": 32}],
    "markersComplete": True,
    "tempo": 128,
    "timeSignature": "4/4",
    "window": {"offset": 0, "capacity": 16, "total": 3, "complete": True},
}


def test_an_arrangement_is_a_tracks_by_bars_timeline_with_a_legend_in_beats():
    assert arranger_timeline.render(SONG) == lines(
        "tempo 128  4/4  cell = 1 bar (4 beats)",
        "markers: 1 intro · 9 verse · 25 drop",
        "bar    1       9       17      25",
        "Drums  ........AAAAAAAAAAAAAAAABBBBBBBB",
        "Bass   ........CCCCCCCC........DDDDDDDD",
        "Pad    EEEEEEEE........EEEEEEEEEEEEEEEE",
        'Drums: A "drums A" 32-96 · B "drums drop" 96-128',
        'Bass: C "bass A" 32-64 · D "bass drop" 97-128',
        'Pad: E "pad" 0-32, 64-128',
        'off-grid: Bass D "bass drop" 97-128',
        TIMELINE_GUIDANCE,
    )


def test_wide_track_names_align_by_display_width_and_long_ones_are_cut():
    drums, long = track("a", "鼓组 Drums"), track("b", "A very long track name that goes on")
    result = {
        "clips": [row(drums, "A", 0, 6), row(drums, "B", 6, 12), row(long, "x", 3, 9)],
        "markers": [{"name": "v", "beat": 4.5}],
        "markersComplete": False,
        "tempo": None,
        "timeSignature": "3/4",
        "window": {"offset": 0, "total": 40, "complete": False},
    }
    text = arranger_timeline.render(result)
    assert text == lines(
        "tempo ?  3/4  cell = 1 bar (3 beats)",
        "markers: 2.5 v (incomplete)",
        "bar                       1",
        "鼓组 Drums                AABB",
        "A very long track name …  .CC.",
        '鼓组 Drums: A "A" 0-6 · B "B" 6-12',
        'A very long track name that goes on: C "x" 3-9',
        "off-grid: none",
        "tracks: only the track window is listed (offset 0, 40 tracks in the project)",
        TIMELINE_GUIDANCE,
    )
    grid_rows = text.splitlines()[3:5]
    assert {wcwidth.width(line) for line in grid_rows} == {30}


def test_a_long_arrangement_widens_the_cell_and_wraps_into_blocks():
    result = {"clips": [row(track("a", "T"), "long", 0, 1200)], "markers": [], "tempo": 120.5, "timeSignature": None}
    text = arranger_timeline.render(result)
    assert text.splitlines()[:4] == [
        "tempo 120.5  ?/?  cell = 2 bars (8 beats)",
        "time signature not observed: a bar is taken as 4 beats",
        "markers: none",
        "bar  1       17      33      49      65      81      97      113",
    ]
    assert "\n\nbar  129     145" in text
    assert text.splitlines()[-4] == "T    " + "A" * 22


def test_clips_past_the_alphabet_share_the_overflow_mark_and_a_cell_shows_its_biggest_clip():
    many = {"clips": [row(track("a", "T"), str(i), i * 4, i * 4 + 4) for i in range(63)], "timeSignature": "4/4"}
    assert arranger_timeline.render(many).splitlines()[3].endswith("0123456789*")
    split = {"clips": [row(track("a", "T"), "a", 0, 1), row(track("a", "T"), "b", 1, 4)], "timeSignature": "4/4"}
    assert arranger_timeline.render(split).splitlines()[3] == "T    B"
    tie = {"clips": [row(track("a", "T"), "a", 0, 2), row(track("a", "T"), "b", 2, 4)], "timeSignature": "4/4"}
    assert arranger_timeline.render(tie).splitlines()[3] == "T    A"


def test_a_clip_that_ends_inside_a_bar_is_off_grid():
    result = {"clips": [row(track("a", "T"), "riff", 0, 6)], "timeSignature": "4/4"}
    assert 'off-grid: T A "riff" 0-6' in arranger_timeline.render(result)


def test_an_empty_arrangement_and_a_malformed_row():
    assert arranger_timeline.render({"clips": [], "markers": [], "tempo": 120, "timeSignature": "4/4"}) == lines(
        "tempo 120  4/4  cell = 1 bar (4 beats)",
        "markers: none",
        "no arranger clips",
        "off-grid: none",
        TIMELINE_GUIDANCE,
    )
    with pytest.raises(ValueError, match=r"lacks ref\.track, start or end"):
        arranger_timeline.render({"clips": [{"start": 0, "end": 4}]})


@pytest.mark.parametrize("signature", [None, "4", "a/4", "0/4", "4/0", "4/4/4"])
def test_a_time_signature_that_is_not_n_over_d_is_not_observed(signature):
    assert arranger_timeline.bar_beats(signature) is None


def test_every_declared_view_has_a_renderer_and_only_reads_declare_them():
    declared = {(name, view) for name, spec in TOOLS.items() for view in spec.views}
    assert declared == set(text_views.VIEWS)


# ---- properties ----

NOTE_RECORDS = st.lists(
    st.builds(
        lambda beat, pitch, velocity, duration, channel, chance: {
            "beat": beat,
            "pitch": pitch,
            "velocity": velocity,
            "duration": duration,
            **({"channel": channel} if channel else {}),
            **({"chance": chance} if chance is not None else {}),
        },
        st.integers(0, 8 * 1024).map(lambda tick: tick / 1024),
        st.integers(0, 127),
        st.integers(1, 127),
        st.integers(1, 4096).map(lambda tick: tick / 1024),
        st.integers(0, 15),
        st.none() | st.sampled_from([0.25, 0.5]),
    ),
    max_size=40,
)
MARKS = re.compile(r"[Xxo]")


@settings(max_examples=150, deadline=None)
@given(NOTE_RECORDS, st.integers(1, 6), st.randoms(use_true_random=False))
def test_the_grid_is_a_pure_function_of_the_records(records, length, rng):
    result = clip_result(records, length=length)
    before = copy.deepcopy(result)
    text = notes_grid.render(result)
    assert result == before
    shuffled = copy.deepcopy(result)
    rng.shuffle(shuffled["notes"])
    shuffled.update(status="observed", window={"total": 1})
    assert notes_grid.render(shuffled) == text


@settings(max_examples=150, deadline=None)
@given(NOTE_RECORDS, st.integers(1, 6))
def test_every_note_is_shown_once_as_a_mark_or_an_exact_footnote(records, length):
    unique = list({(r["beat"], r["pitch"], r.get("channel", 0)): r for r in records}.values())
    text = notes_grid.render(clip_result(unique, length=length))
    body, footer = text.split("\noff-grid: ")
    grid_lines = [
        line for line in body.splitlines()[3:] if line and not line.startswith(("pitch", "no notes", "operators:"))
    ]
    marks = sum(len(MARKS.findall(line.split("  ", 1)[1])) for line in grid_lines)
    listed = footer.splitlines()
    off = [] if listed[0] == "none" else listed[0].split(" · ")
    outside = [line for line in listed if line.startswith("outside")]
    outside_count = len(outside[0].split(": ", 1)[1].split(" · ")) if outside else 0
    assert marks + len(off) + outside_count == len(unique)


@settings(max_examples=60, deadline=None)
@given(
    st.lists(st.tuples(st.sampled_from("abc"), st.integers(0, 64), st.integers(1, 32)), max_size=12),
    st.sampled_from(["4/4", "3/4", "7/8", None]),
)
def test_the_timeline_is_a_pure_function_of_the_rows(spans, signature):
    tracks = {key: track(key, f"轨 {key}") for key in "abc"}
    result: dict[str, Any] = {"clips": [row(tracks[key], f"c{i}", s, s + n) for i, (key, s, n) in enumerate(spans)]}
    result.update(markers=[], tempo=120, timeSignature=signature)
    before = copy.deepcopy(result)
    text = arranger_timeline.render(result)
    assert result == before
    assert arranger_timeline.render(copy.deepcopy(result)) == text
    for block in text.split("\n\n"):
        track_rows = [line for line in block.splitlines() if re.match(r"(轨 [abc]|bar) ", line) and ": " not in line]
        assert len({wcwidth.width(line) for line in track_rows[1:]}) <= 1


def test_the_protocol_examples_are_what_the_views_print():
    protocol = (Path(__file__).resolve().parents[1] / "PROTOCOL.md").read_text()
    assert f"```\n{notes_grid.render(clip_result(DRUMS))}```" in protocol
    assert f"```\n{arranger_timeline.render(SONG)}```" in protocol
