"""arranger.list format=timeline: a read-only tracks x bars text view of the arranger rows.

A pure function of the result. One cell is one bar at the observed time signature (4 beats when it is not
observed), widened to 2, 4, 8 ... bars when the arrangement would need more than MAX_CELLS cells. A cell shows the
letter of the clip that covers most of it; the legend gives every clip's exact beats, and a clip that starts or
ends inside a cell is listed under off-grid. Track names are aligned by display width (wcwidth).
"""

from __future__ import annotations

import math
import string
from dataclasses import dataclass
from fractions import Fraction
from typing import Any

from bitwig_agent.core import rows
from bitwig_agent.core.serialize import dumps
from bitwig_agent.core.text_cells import blocks, join_entries, label, number, on_grid, one_line, quoted, ruler, width

LETTERS = string.ascii_uppercase + string.ascii_lowercase + string.digits
OVERFLOW = "*"
MAX_CELLS = 256
BLOCK_CELLS = 64
RULER_EVERY = 8
NAME_WIDTH = 24
EMPTY = "."
GAP = "  "
GUIDANCE = (
    "read-only: exact beats are in the legend and the JSON rows; write with clips.insert, clips.copy, clips.move, "
    "clips.set and markers.insert/set, never with this text"
)


@dataclass(frozen=True)
class Clip:
    track: str
    track_name: str
    name: str
    start: Fraction
    end: Fraction
    letter: str = OVERFLOW

    def span(self) -> str:
        return f"{number(self.start)}-{number(self.end)}"


def parse(record: dict[str, Any]) -> Clip:
    try:
        track = record["ref"]["track"]
        start = Fraction(record["start"])
        end = Fraction(record["end"]) if record.get("end") is not None else start + Fraction(record["length"])
        name = record.get("name")
        return Clip(track["id"], str(track.get("name") or ""), name if isinstance(name, str) else "", start, end)
    except (KeyError, TypeError, ValueError, AttributeError) as exc:
        raise ValueError(f"An arranger row lacks ref.track, start or end: {dumps(record)}") from exc


def bar_beats(signature: Any) -> Fraction | None:
    """Beats (quarter notes) in one bar of "n/d"; None when it is not observed."""
    if not isinstance(signature, str) or signature.count("/") != 1:
        return None
    top, bottom = signature.split("/")
    if not (top.isdigit() and bottom.isdigit()) or int(top) == 0 or int(bottom) == 0:
        return None
    return Fraction(int(top) * 4, int(bottom))


def lettered(clips: list[Clip]) -> list[Clip]:
    """Each (track, name) gets the next letter in order of appearance; past the alphabet they share OVERFLOW."""
    keys: dict[tuple[str, str], str] = {}
    for clip in clips:
        keys.setdefault((clip.track, clip.name), LETTERS[len(keys)] if len(keys) < len(LETTERS) else OVERFLOW)
    return [Clip(c.track, c.track_name, c.name, c.start, c.end, keys[(c.track, c.name)]) for c in clips]


def cells_of(clips: list[Clip], cell: Fraction, count: int) -> str:
    """One track's row: per cell the letter of the clip covering most of it (the earlier clip on a tie)."""
    best: list[tuple[Fraction, Fraction, str]] = [(Fraction(0), Fraction(0), EMPTY)] * count
    for clip in clips:
        for index in range(math.floor(clip.start / cell), min(count, math.ceil(clip.end / cell))):
            covered = min(clip.end, (index + 1) * cell) - max(clip.start, index * cell)
            if covered > 0 and (covered, -clip.start) > best[index][:2]:
                best[index] = (covered, -clip.start, clip.letter)
    return "".join(letter for _, _, letter in best)


def marker_entries(result: dict[str, Any], bar: Fraction) -> str:
    entries = [
        f"{number(1 + Fraction(m['beat']) / bar)} {one_line(m['name'])}".rstrip()
        for m in sorted(result.get("markers", []), key=lambda m: (m["beat"], m["name"]))
    ]
    incomplete = " (incomplete)" if result.get("markersComplete") is False else ""
    return "markers: " + join_entries(entries) + incomplete


def header(result: dict[str, Any], bar: Fraction | None, cell_bars: int, cell: Fraction) -> list[str]:
    tempo = result.get("tempo")
    shown_tempo = number(tempo) if isinstance(tempo, (int, float)) else "?"
    signature = result.get("timeSignature") if bar is not None else "?/?"
    unit = f"{cell_bars} bar{'s' if cell_bars > 1 else ''}"
    lines = [f"tempo {shown_tempo}  {signature}  cell = {unit} ({number(cell)} beats)"]
    if bar is None:
        lines.append("time signature not observed: a bar is taken as 4 beats")
    return lines


def legend(order: list[str], clips: list[Clip], names: dict[str, str]) -> list[str]:
    lines = []
    for track in order:
        spans: dict[tuple[str, str], list[str]] = {}
        for clip in clips:
            if clip.track == track:
                spans.setdefault((clip.letter, clip.name), []).append(clip.span())
        entries = [f"{letter} {quoted(name)} {', '.join(found)}" for (letter, name), found in spans.items()]
        lines.append(f"{one_line(names[track])}: {join_entries(entries)}")
    return lines


def footer(result: dict[str, Any], clips: list[Clip], cell: Fraction) -> list[str]:
    off = [c for c in clips if not (on_grid(c.start, cell) and on_grid(c.end, cell))]
    entries = [f"{one_line(c.track_name)} {c.letter} {quoted(c.name)} {c.span()}" for c in off]
    lines = ["off-grid: " + join_entries(entries)]
    window = result.get("window")
    if isinstance(window, dict) and window.get("complete") is False:
        lines.append(
            f"tracks: only the track window is listed (offset {window.get('offset', '?')}, "
            f"{window.get('total', '?')} tracks in the project)"
        )
    lines.append(GUIDANCE)
    return lines


def render(result: dict[str, Any]) -> str:
    clips = lettered([parse(record) for record in rows.records_of(result, "clips")])
    bar = bar_beats(result.get("timeSignature"))
    unit = bar if bar is not None else Fraction(4)
    marker_beats = [Fraction(m["beat"]) for m in result.get("markers", [])]
    end = max([unit, *(c.end for c in clips), *marker_beats])
    cell_bars = 1
    while math.ceil(end / (unit * cell_bars)) > MAX_CELLS:
        cell_bars *= 2
    cell = unit * cell_bars
    count = math.ceil(end / cell)
    lines = [*header(result, bar, cell_bars, cell), marker_entries(result, unit)]
    order = list(dict.fromkeys(c.track for c in clips))
    names = {c.track: c.track_name for c in clips}
    name_width = min(NAME_WIDTH, max([width("bar"), *(width(n) for n in names.values())]))
    tracks = {track: cells_of([c for c in clips if c.track == track], cell, count) for track in order}

    def bar_mark(column: int) -> str | None:
        return str(1 + column * cell_bars) if column % RULER_EVERY == 0 else None

    if not clips:
        lines.append("no arranger clips")
    for index, part in enumerate(blocks(count if clips else 0, BLOCK_CELLS)):
        lines += [""] * bool(index)
        lines.append(label("bar", name_width) + GAP + ruler(part, 1, bar_mark, ""))
        lines += [label(names[track], name_width) + GAP + tracks[track][part.start : part.stop] for track in order]
    return "\n".join([*lines, *legend(order, clips, names), *footer(result, clips, cell)]) + "\n"
