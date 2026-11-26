"""Parameter values in a spec: offline validation against a parameter description, and comparison with live values."""

from __future__ import annotations

import math
import struct
from typing import Any, cast

from bitwig_agent.grid.display import display_matches
from bitwig_agent.grid.problems import SpecError, near

MAX_STRING_UNITS = 1024
STEP_TYPES = ("Steps", "Pitches", "Gates", "Probabilities", "Accents")
STEP_ENTRIES, STEP_BOUND = 256, 128


def f32(x: float) -> float:
    return float(struct.unpack("f", struct.pack("f", x))[0])


def is_steps(raw: Any) -> bool:
    return isinstance(raw, dict) and "steps" in raw


def check_steps(pid: str, raw: Any) -> dict[str, Any]:
    """Validate a {steps:[...]} spec value offline; return the write fields {steps: [...]}."""
    values = raw["steps"] if is_steps(raw) else raw
    if not isinstance(values, list):
        raise SpecError(
            [
                f"{pid}: steps must be a list of numbers (a '${{name}}' placeholder needs a block arg that is a list), got {values!r}"
            ]
        )
    if not 1 <= len(values) <= STEP_ENTRIES:
        raise SpecError([f"{pid}: steps takes 1..{STEP_ENTRIES} entries, got {len(values)}"])
    for i, v in enumerate(values):
        if type(v) not in (int, float) or not math.isfinite(v) or not -STEP_BOUND <= v <= STEP_BOUND:
            raise SpecError([f"{pid}: steps[{i}] = {v!r} must be a finite number within -{STEP_BOUND}..{STEP_BOUND}"])
    return {"steps": list(values)}


def _check_steps_value(pid: str, raw: Any, kind: Any) -> dict[str, Any]:
    if kind != "steps":
        raise SpecError([f"{pid}: steps need a step-array parameter, this is {kind}"])
    if not is_steps(raw) and not isinstance(raw, list):
        raise SpecError([f"{pid}: step-array parameter needs {{steps:[...]}}, got {raw!r}"])
    return check_steps(pid, raw)


def _check_label(pid: str, raw: dict[str, Any], p: dict[str, Any]) -> dict[str, Any]:
    if p.get("kind") != "enum":
        raise SpecError([f"{pid}: label needs an enum parameter, this is {p.get('kind')}"])
    labels = {o["label"]: o["value"] for o in p.get("options") or []}
    folded = {k.casefold(): v for k, v in labels.items()}
    if raw["label"] in labels:
        return {"value": labels[raw["label"]]}
    if raw["label"].casefold() in folded:
        return {"value": folded[raw["label"].casefold()]}
    raise SpecError([f"{pid}: unknown label {raw['label']!r}.{near(raw['label'], labels)} Options: {list(labels)}"])


def _check_enum_option(pid: str, raw: int, p: dict[str, Any]) -> None:
    if p.get("options") is not None and raw not in {o["value"] for o in p["options"]}:
        raise SpecError(
            [f"{pid}: {raw} is not an option value; options {[(o['value'], o['label']) for o in p['options']]}"]
        )


def _check_string(pid: str, raw: Any) -> None:
    if type(raw) is not str:
        raise SpecError([f"{pid}: string parameter needs a string, got {raw!r}"])
    if len(raw.encode("utf-16-le", "surrogatepass")) // 2 > MAX_STRING_UNITS:
        raise SpecError([f"{pid}: string longer than {MAX_STRING_UNITS} UTF-16 units"])


def _check_scalar_type(pid: str, raw: Any, p: dict[str, Any]) -> None:
    kind = p.get("kind")
    if kind == "boolean":
        if type(raw) is not bool:
            raise SpecError([f"{pid}: boolean parameter needs true/false, got {raw!r}"])
    elif kind in ("integer", "enum"):
        if type(raw) is not int:
            raise SpecError([f"{pid}: {kind} parameter needs an integer, got {raw!r}"])
        if kind == "enum":
            _check_enum_option(pid, raw, p)
    elif kind == "string":
        _check_string(pid, raw)
    elif kind == "float":
        if type(raw) not in (int, float):
            raise SpecError([f"{pid}: float parameter needs a number, got {raw!r}"])
    else:
        raise SpecError([f"{pid}: {kind} parameters cannot be written"])


def check_value(pid: str, raw: Any, p: dict[str, Any]) -> dict[str, Any]:
    """Validate one spec value against a parameter description {kind, options?, minimum?, maximum?}; return the write fields."""
    kind = p.get("kind")
    if kind == "steps" or is_steps(raw):
        return _check_steps_value(pid, raw, kind)
    if isinstance(raw, dict) and "display" in raw:
        if kind != "float":
            raise SpecError([f"{pid}: display needs a float parameter, this is {kind}"])
        return {"display": raw["display"]}
    if isinstance(raw, dict):
        return _check_label(pid, raw, p)
    _check_scalar_type(pid, raw, p)
    if kind in ("integer", "float") and p.get("minimum") is not None and not p["minimum"] <= raw <= p["maximum"]:
        raise SpecError([f"{pid}: {raw} outside {p['minimum']}..{p['maximum']}"])
    return {"value": raw}


def value_matches(kind: str, want: dict[str, Any], row_param: dict[str, Any]) -> bool:
    if "display" in want or "shown" in want:
        if (
            "shown" in want
            and type(row_param.get("value")) in (int, float)
            and math.isclose(row_param["value"], want["value"], rel_tol=1e-5, abs_tol=1e-9)
        ):
            return True  # converted client-side: the live raw already is the converted value
        return display_matches(
            cast(str, want.get("display", want.get("shown"))), row_param.get("display"), row_param.get("numericUnit")
        )
    live, new = row_param.get("value"), want["value"]
    if kind == "boolean":
        return type(live) is bool and live == new
    if kind == "string":
        return type(live) is str and live == new
    if kind == "float":
        return type(live) in (int, float) and math.isclose(cast(float, live), new, rel_tol=1e-9, abs_tol=1e-12)
    return type(live) is int and live == new
