"""Small pieces shared by the text views: numbers, quoted names, display-width columns and a cell ruler."""

from __future__ import annotations

import json
import re
from collections.abc import Callable, Iterator, Sequence
from fractions import Fraction

import wcwidth

TICK = Fraction(1, 1048576)
"""Bitwig stores beats in 1/1048576 beat: two positions this close are the same position."""
CONTROL = re.compile(r"[\x00-\x1f\x7f]")
ELLIPSIS = "…"


def number(value: float | Fraction) -> str:
    """A beat or bar exactly as JSON writes it: 4, 0.5, 0.22500038146972656 (the shortest round-trip form)."""
    shown = float(value)
    return str(int(shown)) if shown.is_integer() else repr(shown)


def quoted(name: object) -> str:
    """A name in double quotes, escaped as JSON (null as "")."""
    return json.dumps(name if isinstance(name, str) else "", ensure_ascii=False)


def on_grid(position: Fraction, step: Fraction) -> bool:
    """Whether position lies on a multiple of step, to Bitwig's storage resolution."""
    return abs(position - round(position / step) * step) <= TICK


def label(text: str, width: int) -> str:
    """text on one line, cut to width display columns (with an ellipsis) and padded to exactly width (wcwidth)."""
    text = CONTROL.sub(" ", text)
    if wcwidth.width(text) > width:
        text = wcwidth.clip(text, 0, width - 1) + ELLIPSIS
    return wcwidth.ljust(text, width)


def width(text: str) -> int:
    return int(wcwidth.width(CONTROL.sub(" ", text)))


def blocks(count: int, size: int) -> Iterator[range]:
    """Column ranges of at most size columns covering count columns."""
    for start in range(0, count, size):
        yield range(start, min(start + size, count))


def ruler(columns: range, spacing: int, mark: Callable[[int], str | None], fill: str) -> str:
    """One header line over the columns: each column's mark, else fill unless a longer mark runs into it."""
    line = [" "] * (len(columns) * spacing)
    for offset, column in enumerate(columns):
        text = mark(column)
        at = offset * spacing
        if text is None:
            if fill and line[at] == " " and (at == 0 or line[at - 1] == " "):
                line[at] = fill
            continue
        for index, char in enumerate(text):
            if at + index < len(line):
                line[at + index] = char
    return "".join(line).rstrip()


def join_entries(entries: Sequence[str]) -> str:
    return " · ".join(entries) if entries else "none"
