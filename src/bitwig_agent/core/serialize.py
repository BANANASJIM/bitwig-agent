"""The one JSON text form of results and errors, shared by every frontend."""

from __future__ import annotations

import json
from typing import Any


def dumps(value: Any, pretty: bool = False) -> str:
    """Compact one-line JSON (indented when pretty); non-finite numbers are refused, non-ASCII kept."""
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        indent=2 if pretty else None,
        separators=None if pretty else (",", ":"),
    )
