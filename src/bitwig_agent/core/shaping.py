"""Response shaping, the same for every front end: `format` and `fields` (CLI --format/--fields, MCP arguments).

json (the default) is the result object; jsonl|csv|tsv print a read's declared record list; a named text view
(notes.get grid, arranger.list timeline) is a read-only rendering of the same result. A result that carries an
outcome (a failure after a read selected something) is never reshaped.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from bitwig_agent.core import rows, text_views, views
from bitwig_agent.core.serialize import dumps

JSON = views.JSON
PAGING = re.compile(r"offset|limit|cursor", re.IGNORECASE)


@dataclass(frozen=True)
class Shape:
    format: str = JSON
    fields: tuple[str, ...] = ()

    @property
    def plain(self) -> bool:
        return self.format == JSON and not self.fields


@dataclass(frozen=True)
class Shaped:
    """The result as JSON (text None, records projected when fields were given) or as text, and its warnings."""

    result: dict[str, Any]
    text: str | None = None
    warnings: tuple[dict[str, str], ...] = ()


def check(name: str, shape: Shape) -> None:
    """Refuse a shape the method's result does not take, before anything is sent."""
    if shape.plain:
        return
    allowed = views.formats(name)
    if allowed == (JSON,):
        why = "returns an outcome" if not views.read_only(name) else "has no record list"
        raise ValueError(f"{name} {why} and always returns JSON; format and fields apply to record-list reads")
    if shape.format not in allowed:
        raise ValueError(f"format for {name} must be one of {', '.join(allowed)}, got {shape.format!r}")
    if shape.fields and shape.format in views.text_views_of(name):
        raise ValueError(f"fields does not apply to format={shape.format}: a text view shows fixed fields")


def comma_fields(text: str) -> tuple[str, ...]:
    """The CLI spelling of fields: comma-separated names."""
    names = tuple(item.strip() for item in text.split(","))
    if not all(names):
        raise ValueError("fields takes comma-separated field names: --fields=trackId,name")
    return names


def split(name: str, arguments: Mapping[str, Any]) -> tuple[dict[str, Any], Shape]:
    """The method's own arguments and the shape, for a front end that passes format/fields with them (MCP)."""
    arguments = dict(arguments)
    if not views.exists(name) or not views.shapeable(name):
        return arguments, Shape()
    fmt = arguments.pop("format", JSON)
    fields = arguments.pop("fields", [])
    if not isinstance(fmt, str):
        raise ValueError(f"format is a string: one of {', '.join(views.formats(name))}")
    if not isinstance(fields, list) or not all(isinstance(item, str) and item for item in fields):
        raise ValueError('fields is an array of field names, e.g. ["name", "ref.track.name"]')
    shape = Shape(fmt, tuple(fields))
    check(name, shape)
    return arguments, shape


def is_partial(result: dict[str, Any], returned: int) -> bool:
    """A window decides alone: its complete flag, else offset + returned < total; top-level complete only without one."""
    window = result.get("window")
    if not isinstance(window, dict):
        return result.get("complete") is False
    if "complete" in window:
        return window["complete"] is False
    offset, total = window.get("offset", 0), window.get("total")
    return isinstance(offset, int) and isinstance(total, int) and offset + returned < total


def partial_warning(name: str, result: dict[str, Any], returned: int) -> tuple[dict[str, str], ...]:
    """One warning when a record list covers only part of what exists."""
    if not is_partial(result, returned):
        return ()
    paging = [f["name"] for f in views.fields(name) if PAGING.search(f["name"])]
    how = f"page with {' / '.join(paging)}" if paging else "this method has no paging fields: narrow the request"
    return ({"warning": "partial", "message": f"The list is incomplete; {how}"},)


def apply(name: str, result: dict[str, Any], shape: Shape) -> Shaped:
    """The result in the requested shape; ValueError when it lacks what the shape needs."""
    if shape.plain or "outcome" in result:
        return Shaped(result)
    if shape.format in views.text_views_of(name):
        return Shaped(result, text_views.render(name, shape.format, result))
    key = str(views.records(name))
    records = rows.records_of(result, key)
    warnings: tuple[dict[str, str], ...] = ()
    if shape.fields:
        records, unknown = rows.project(records, shape.fields)
        if unknown:
            warnings = ({"warning": "unknown_field", "message": "No record has: " + "; ".join(unknown)},)
    if shape.format == JSON:
        return Shaped({**result, key: records}, warnings=warnings)
    text = rows.lines(records) if shape.format == "jsonl" else rows.table(shape.format, records, shape.fields)
    return Shaped(result, text, warnings + partial_warning(name, result, len(records)))


def warning_lines(shaped: Shaped) -> str:
    return "".join(dumps(warning) + "\n" for warning in shaped.warnings)
