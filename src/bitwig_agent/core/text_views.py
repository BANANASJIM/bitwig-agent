"""The named read-only text views of read results: which method has which, and how each is rendered."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from bitwig_agent.core import arranger_timeline, notes_grid

Renderer = Callable[[dict[str, Any]], str]

VIEWS: dict[tuple[str, str], tuple[Renderer, str]] = {
    ("notes.get", "grid"): (
        notes_grid.render,
        "a read-only pitch x step grid of the same notes (velocity marks, off-grid onsets listed exactly)",
    ),
    ("arranger.list", "timeline"): (
        arranger_timeline.render,
        "a read-only tracks x bars timeline with a clip legend in beats, markers, tempo and off-grid clips",
    ),
}


def render(name: str, view: str, result: dict[str, Any]) -> str:
    return VIEWS[(name, view)][0](result)


def summary(name: str, view: str) -> str:
    return VIEWS[(name, view)][1]
