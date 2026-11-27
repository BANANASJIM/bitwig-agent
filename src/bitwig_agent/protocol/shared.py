"""Reads `shared/<name>.json`, the facts both the Python package and the Java extension read."""

from __future__ import annotations

import json
from functools import cache
from importlib import resources
from typing import Any


@cache
def shared(name: str) -> Any:
    text = resources.files("bitwig_agent").joinpath(f"shared/{name}.json").read_text(encoding="utf-8")
    return json.loads(text)
