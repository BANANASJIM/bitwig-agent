"""The Grid module schema and measured notes bundled for the Bitwig version this package was measured on."""

from __future__ import annotations

import json
from functools import cache
from importlib import resources
from typing import Any


@cache
def _load(name: str) -> dict[str, Any]:
    text = resources.files("bitwig_agent").joinpath(f"data/{name}").read_text(encoding="utf-8")
    loaded: dict[str, Any] = json.loads(text)
    return loaded


def bundled_schema() -> dict[str, Any]:
    return _load("grid_schema_6_1_4.json")


def measured_notes() -> dict[str, Any]:
    return _load("grid_notes_6_1_4.json")
