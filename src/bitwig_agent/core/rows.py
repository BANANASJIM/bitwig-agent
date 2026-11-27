"""Record-list views of a result for pipes: field selection and JSON Lines, CSV and TSV rows."""

from __future__ import annotations

import csv
import difflib
import io
from collections.abc import Sequence
from typing import Any

from bitwig_agent.core.serialize import dumps

TSV_ESCAPES = (("\\", "\\\\"), ("\t", "\\t"), ("\n", "\\n"), ("\r", "\\r"))


def records_of(result: Any, key: str) -> list[dict[str, Any]]:
    """The declared array of objects of a result."""
    value = result.get(key) if isinstance(result, dict) else None
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise ValueError(f"The result has no array of objects named {key}")
    return value


def lookup(record: dict[str, Any], path: str) -> tuple[bool, Any]:
    value: Any = record
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return False, None
        value = value[part]
    return True, value


def project(records: list[dict[str, Any]], fields: Sequence[str]) -> tuple[list[dict[str, Any]], list[str]]:
    """Each record reduced to the named (dotted) fields (null where absent), and a hint per field no record has."""
    known = list(flatten(records[0])) if records else []
    unknown = []
    for name in fields:
        if records and not any(lookup(record, name)[0] for record in records):
            close = difflib.get_close_matches(name, known, n=1, cutoff=0.5)
            unknown.append(f"{name} (did you mean {close[0]}?)" if close else f"{name} (fields: {', '.join(known)})")
    return [{name: lookup(record, name)[1] for name in fields} for record in records], unknown


def flatten(value: dict[str, Any], prefix: str = "") -> dict[str, Any]:
    flat: dict[str, Any] = {}
    for key, item in value.items():
        if isinstance(item, dict) and item:
            flat.update(flatten(item, f"{prefix}{key}."))
        else:
            flat[f"{prefix}{key}"] = item
    return flat


def cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return value
    return dumps(value)


def tsv_cell(value: Any) -> str:
    text = cell(value)
    for raw, escaped in TSV_ESCAPES:
        text = text.replace(raw, escaped)
    return text


def union_columns(flat: list[dict[str, Any]]) -> list[str]:
    """Every key in first-seen order; a key that is only null where other records nest under it is not a column."""
    keys = list(dict.fromkeys(key for record in flat for key in record))
    return [
        key
        for key in keys
        if not (any(other.startswith(key + ".") for other in keys) and all(r.get(key) is None for r in flat))
    ]


def table(fmt: str, records: list[dict[str, Any]], fields: Sequence[str]) -> str:
    flat = records if fields else [flatten(record) for record in records]
    columns: list[Any] = list(fields) or union_columns(flat)
    if not columns:
        return ""
    header: list[list[Any]] = [columns, *([record.get(c) for c in columns] for record in flat)]
    if fmt == "tsv":
        return "".join("\t".join(tsv_cell(v) for v in row) + "\n" for row in header)
    out = io.StringIO()
    csv.writer(out, lineterminator="\n").writerows([[cell(v) for v in row] for row in header])
    return out.getvalue()


def lines(records: list[dict[str, Any]]) -> str:
    return "".join(dumps(record) + "\n" for record in records)
