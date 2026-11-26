"""Value convention: a write takes the bare `value` a read returned, or {display} / {label}; one match rule per kind."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Self

from pydantic import model_validator

from bitwig_agent.protocol.base import Nonempty, StrictModel, UnitValue
from bitwig_agent.protocol.limits import NORMALIZED_TOLERANCE


class Display(StrictModel):
    """A value as the host displays it ("-6 dB", "800 Hz"), resolved by the host; text displays are unsupported_unit."""

    display: Nonempty


class Label(StrictModel):
    """An enum option by label, only where the label differs from the option value."""

    label: Nonempty


Normalized = UnitValue | Display
"""A host parameter (volume, pan, send, device or Remote Control parameter): normalized 0..1 or its display."""


@dataclass(frozen=True)
class ValueMatch:
    """Whether a read-back value counts as the requested one; write verification and `expect` share it."""

    exact: bool
    tolerance: float = 0.0

    def matches(self, requested: Any, observed: Any) -> bool:
        if self.exact:
            return _same_json(requested, observed)
        if not (_number(requested) and _number(observed)):
            return False
        return math.isfinite(observed) and abs(observed - requested) <= self.tolerance


NORMALIZED = ValueMatch(exact=False, tolerance=NORMALIZED_TOLERANCE)
"""A normalized host parameter: within one host step."""
EXACT = ValueMatch(exact=True)
"""Integers, enums, booleans, strings, colours, routing entries and hashes: equal JSON values."""


def raw(tolerance: float) -> ValueMatch:
    """A raw float, within the tolerance its domain already verifies with."""
    return ValueMatch(exact=False, tolerance=tolerance)


def _number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _same_json(left: Any, right: Any) -> bool:
    """JSON equality: numbers by value (1 == 1.0), never a boolean for a number, containers element by element."""
    if _number(left) and _number(right):
        return bool(left == right)
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(_same_json(left[k], right[k]) for k in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(_same_json(a, b) for a, b in zip(left, right, strict=True))
    return type(left) is type(right) and left == right


class Expect(StrictModel):
    """Optional value compare-and-set: bare values only, keyed like the write; a mismatch is stale/target_changed."""

    @model_validator(mode="after")
    def validate_nonempty(self) -> Self:
        if not self.model_fields_set:
            raise ValueError("expect names at least one field")
        return self
