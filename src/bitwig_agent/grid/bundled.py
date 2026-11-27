"""Data shipped with the package: the Bitwig host targets, the facts shared with the Java extension, and the Grid module
schema and measured notes per host."""

from __future__ import annotations

import json
from functools import cache
from importlib import resources
from typing import Any

from bitwig_agent.protocol.shared import shared as _shared


@cache
def _load(path: str) -> dict[str, Any]:
    text = resources.files("bitwig_agent").joinpath(path).read_text(encoding="utf-8")
    loaded: dict[str, Any] = json.loads(text)
    return loaded


shared = _shared  # `shared/<name>.json`: also bundled into the extension jar, read by both sides


def host_version(connected: str | None = None) -> str:
    """The supported host whose Grid schema and notes apply: the connected one, else (offline) the newest."""
    versions: list[str] = [host["version"] for host in shared("host")]
    return connected if connected is not None and connected in versions else versions[-1]


def _versioned(stem: str, connected: str | None) -> dict[str, Any]:
    return _load(f"data/{stem}_{host_version(connected).replace('.', '_')}.json")


def bundled_schema(connected: str | None = None) -> dict[str, Any]:
    return _versioned("grid_schema", connected)


def measured_notes(connected: str | None = None) -> dict[str, Any]:
    return _versioned("grid_notes", connected)
