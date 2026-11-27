"""notes.get format=grid: a read-only pitch x step text view of the note records.

A pure function of the result: the window is the echoed range {start, end}, else the clip loop; the step is the
coarsest of 1/16, 1/16T, 1/32, 1/32T, 1/64 that every onset in the window lies on (1/16 when none does). A note
whose onset is not on the shown step is not drawn: it is listed under off-grid, exactly. Pitch names follow
Bitwig (C3 = 60).
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass
from fractions import Fraction
from typing import Any

from bitwig_agent.core import rows
from bitwig_agent.core.serialize import dumps
from bitwig_agent.core.text_cells import blocks, join_entries, number, on_grid, quoted, ruler

STEPS = (
    (Fraction(1, 4), "1/16"),
    (Fraction(1, 6), "1/16T"),
    (Fraction(1, 8), "1/32"),
    (Fraction(1, 12), "1/32T"),
    (Fraction(1, 16), "1/64"),
)
MAX_COLUMNS = 1024
BLOCK_COLUMNS = 64
NOTE_FIELDS = frozenset({"beat", "pitch", "velocity", "duration", "channel"})
NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
LOUD, MEDIUM = 110, 60
EMPTY, HELD = ".", "-"
STRENGTH = {EMPTY: 0, HELD: 0, "o": 1, "x": 2, "X": 3}
LEGEND = f"cells: X velocity >={LOUD} · x {MEDIUM}-{LOUD - 1} · o <{MEDIUM} · - held · . empty"
GUIDANCE = (
    "read-only: each mark is one notes.get record {beat, pitch, velocity, duration, channel}; "
    "write with notes.set records (notes.get without format returns them), never with this text"
)


@dataclass(frozen=True)
class Note:
    beat: Fraction
    pitch: int
    velocity: int
    duration: Fraction
    channel: int
    operators: str

    @property
    def row(self) -> tuple[int, int]:
        return (-self.pitch, self.channel)

    def entry(self) -> str:
        channel = f" ch{self.channel}" if self.channel else ""
        return f"{self.pitch}@{number(self.beat)} v{self.velocity} d{number(self.duration)}{channel}"


def pitch_name(pitch: int) -> str:
    return f"{NAMES[pitch % 12]}{pitch // 12 - 2}"


def mark(velocity: int) -> str:
    return "X" if velocity >= LOUD else "x" if velocity >= MEDIUM else "o"


def operator_text(record: dict[str, Any]) -> str:
    """Every field beyond the note itself (chance, repeat, ...), as `name value`."""
    extra = sorted(key for key in record if key not in NOTE_FIELDS)
    return ", ".join(f"{key} {value_text(record[key])}" for key in extra)


def value_text(value: Any) -> str:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return number(value)
    return dumps(value)


def parse(record: dict[str, Any]) -> Note:
    try:
        return Note(
            Fraction(record["beat"]),
            int(record["pitch"]),
            int(record["velocity"]),
            Fraction(record["duration"]),
            int(record.get("channel", 0)),
            operator_text(record),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError(f"A note record lacks beat, pitch, velocity or duration: {dumps(record)}") from exc


def window(result: dict[str, Any]) -> tuple[Fraction, Fraction]:
    """[start, end) in beats: the echoed range when it has bounds, else the clip loop."""
    shown = result.get("range")
    source = shown if isinstance(shown, dict) else result.get("loop")
    if not isinstance(source, dict):
        raise ValueError("The result has neither range {start, end} nor loop {start, length}")
    start = Fraction(source.get("start", 0))
    end = Fraction(source["end"]) if "end" in source else start + Fraction(source.get("length", 0))
    if end <= start:
        raise ValueError("The grid window is empty: the loop or range has no length")
    return start, end


def columns(origin: Fraction, end: Fraction, step: Fraction) -> int:
    return math.ceil((end - origin) / step)


def choose_step(origin: Fraction, end: Fraction, onsets: list[Fraction]) -> tuple[Fraction, str]:
    """The coarsest step every onset lies on, within MAX_COLUMNS; the coarsest that fits when none does."""
    fitting = [(step, name) for step, name in STEPS if columns(origin, end, step) <= MAX_COLUMNS]
    if not fitting:
        beats = MAX_COLUMNS * STEPS[0][0]
        raise ValueError(
            f"The grid would need {columns(origin, end, STEPS[0][0])} columns at 1/16: give range "
            f"{{start, end}} of at most {number(beats)} beats"
        )
    return next(((s, n) for s, n in fitting if all(on_grid(beat, s) for beat in onsets)), fitting[0])


def header(name: Any, start: Fraction, end: Fraction, label: str) -> str:
    return f"{quoted(name)}  beats {number(start)}-{number(end)}  step {label}"


def draw(notes: list[Note], origin: Fraction, step: Fraction, count: int) -> dict[tuple[int, int], list[str]]:
    """The cells of each row: onset marks first (the strongest wins a shared cell), then held cells."""
    grid: dict[tuple[int, int], list[str]] = {}
    for note in notes:
        cells = grid.setdefault(note.row, [EMPTY] * count)
        column = round((note.beat - origin) / step)
        if STRENGTH[mark(note.velocity)] > STRENGTH[cells[column]]:
            cells[column] = mark(note.velocity)
    for note in notes:
        cells = grid[note.row]
        column = round((note.beat - origin) / step) + 1
        while column < count and origin + column * step < note.beat + note.duration and cells[column] == EMPTY:
            cells[column] = HELD
            column += 1
    return grid


def row_label(row: tuple[int, int], channels: bool) -> str:
    pitch, channel = -row[0], row[1]
    return f"{pitch} {pitch_name(pitch)}" + (f" ch{channel}" if channels else "")


def operator_line(notes: list[Note]) -> str | None:
    grouped: dict[tuple[int, int, str], list[str]] = defaultdict(list)
    for note in notes:
        if note.operators:
            grouped[(note.pitch, note.channel, note.operators)].append(number(note.beat))
    if not grouped:
        return None
    entries = [
        f"{pitch}@{','.join(beats)}{f' ch{channel}' if channel else ''} {text}"
        for (pitch, channel, text), beats in grouped.items()
    ]
    return "operators: " + join_entries(entries)


def footer(result: dict[str, Any], notes: list[Note], off: list[Note], outside: list[Note], span: str) -> list[str]:
    lines = [line for line in [operator_line(notes)] if line]
    lines.append("off-grid: " + join_entries([note.entry() for note in off]))
    if outside:
        lines.append(f"outside beats {span}: " + join_entries([note.entry() for note in outside]))
    if result.get("complete") is False:
        reason = f" ({result['incompleteReason']})" if result.get("incompleteReason") else ""
        lines.append(
            f"incomplete{reason}: {len(notes)} of {result.get('count', '?')} notes listed; read the rest by range"
        )
    if result.get("exact") is False:
        lines.append("exact=false: read through the public note grid, onsets on the 1/128-beat grid")
    lines.append(GUIDANCE)
    return lines


def render(result: dict[str, Any]) -> str:
    notes = sorted(
        (parse(record) for record in rows.records_of(result, "notes")),
        key=lambda n: (n.beat, n.pitch, n.channel, n.velocity, n.duration, n.operators),
    )
    start, end = window(result)
    origin = Fraction(math.floor(start))
    inside = [note for note in notes if start <= note.beat < end]
    step, step_name = choose_step(origin, end, [note.beat for note in inside])
    count = columns(origin, end, step)
    drawn: list[Note] = []
    off: list[Note] = []
    for note in inside:
        placed = on_grid(note.beat, step) and round((note.beat - origin) / step) < count
        (drawn if placed else off).append(note)
    outside = [note for note in notes if not start <= note.beat < end]
    span = f"{number(start)}-{number(end)}"
    lines = [header(result.get("ref", {}).get("name"), start, end, step_name), LEGEND]
    grid = draw(drawn, origin, step, count)
    for row in {note.row for note in notes} - grid.keys():
        grid[row] = [EMPTY] * count
    channels = any(note.channel for note in notes)
    labels = {row: row_label(row, channels) for row in grid}
    width = max([len("pitch"), *map(len, labels.values())]) + 2
    per_beat = int(1 / step)
    size = per_beat * 2 ** int(math.log2(max(1, BLOCK_COLUMNS // per_beat)))

    def beat_mark(column: int) -> str | None:
        position = origin + column * step
        return str(int(position) + 1) if position.denominator == 1 else None

    if not grid:
        lines.append("no notes")
    for index, part in enumerate(blocks(count if grid else 0, size)):
        lines += [""] * bool(index)
        lines.append("pitch".ljust(width) + ruler(part, 2, beat_mark, EMPTY))
        lines += [labels[row].ljust(width) + " ".join(grid[row][part.start : part.stop]) for row in sorted(grid)]
    return "\n".join([*lines, *footer(result, notes, off, outside, span)]) + "\n"
