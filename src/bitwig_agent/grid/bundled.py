"""Data shipped with the package: the Bitwig host target, the facts shared with the Java extension, and the Grid module
schema and measured notes for that host."""

from __future__ import annotations

import json
from functools import cache
from importlib import resources
from typing import Any


@cache
def _load(path: str) -> dict[str, Any]:
    text = resources.files("bitwig_agent").joinpath(path).read_text(encoding="utf-8")
    loaded: dict[str, Any] = json.loads(text)
    return loaded


def shared(name: str) -> dict[str, Any]:
    """`shared/<name>.json`: also bundled into the extension jar, read by both sides."""
    return _load(f"shared/{name}.json")


def host_version() -> str:
    version: str = shared("host")["version"]
    return version


def _versioned(stem: str) -> dict[str, Any]:
    return _load(f"data/{stem}_{host_version().replace('.', '_')}.json")


def bundled_schema() -> dict[str, Any]:
    return _versioned("grid_schema")


def measured_notes() -> dict[str, Any]:
    return _versioned("grid_notes")
