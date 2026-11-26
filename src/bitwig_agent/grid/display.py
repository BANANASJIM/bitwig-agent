"""Display-text writes: units, the verified seconds scales and the offline checks that mirror the host's refusals."""

from __future__ import annotations

import math
import re
from collections.abc import Callable
from typing import Any, NamedTuple, cast

from bitwig_agent.grid import raw_scales
from bitwig_agent.grid.bundled import shared
from bitwig_agent.grid.problems import SpecError

_DATA = shared("display")
Converter = Callable[[float], float]
FORMULAS: dict[str, Callable[[float], tuple[Converter, Converter]]] = {
    "midi_note_hz": lambda _: (
        lambda f: 69 + 12 * math.log2(f / 440) if f > 0 else math.nan,
        lambda raw: 440 * 2 ** ((raw - 69) / 12),
    ),
    "scale": lambda factor: (lambda p: p / factor, lambda raw: raw * factor),
    "identity": lambda _: (lambda d: d, lambda raw: raw),
    "cbrt": lambda _: (lambda s: math.cbrt(s) if s >= 0 else math.nan, lambda raw: raw**3),
    "log10": lambda _: (lambda s: math.log10(s) if s > 0 else math.nan, lambda raw: 10**raw),
}
# natural = what the display text measures (Hz, percent points, dB); seconds has no uniform raw mapping: SECONDS_PARAMS.
DISPLAY_UNITS: dict[str, tuple[dict[str, float], Converter | None, Converter | None]] = {
    name: (unit["suffixes"], *FORMULAS[unit["formula"]](unit.get("factor", 1.0)))
    for name, unit in _DATA["units"].items()
}
DISPLAY_UNITS["seconds"] = (_DATA["seconds"]["suffixes"], None, None)
# Only (module type, parameter) pairs whose default raw/display pair fits one mapping exactly are writable by display,
# natively measured on 6.1.4 and re-checked against the bundled schema by a test: (mapping, default raw, default display).
SECONDS_PARAMS: dict[tuple[str | None, str], tuple[str, float, str]] = {
    (row["type"], row["parameter"]): (row["formula"], row["raw"], row["display"]) for row in _DATA["seconds"]["params"]
}
SECONDS_SCALES: dict[str, tuple[Converter, Converter]] = {
    formula: FORMULAS[formula](1.0) for formula, _, _ in SECONDS_PARAMS.values()
}
_TEXT = re.compile(_DATA["quantity"]["pattern"], re.ASCII)
_TOLERANCE_RELATIVE: float = _DATA["quantity"]["toleranceRelative"]
_TOLERANCE_ABSOLUTE: float = _DATA["quantity"]["toleranceAbsolute"]
_JAVA_TRIM = "".join(map(chr, range(33)))


class DisplayBasis(NamedTuple):
    """The raw/display pair and range a display write is checked against: a schema default or a live row."""

    unit: str | None
    raw: Any
    display: Any
    lo: Any
    hi: Any

    @classmethod
    def schema(cls, p: dict[str, Any]) -> DisplayBasis:
        return cls(p.get("numericUnit"), p.get("default"), p.get("defaultDisplay"), p.get("minimum"), p.get("maximum"))

    @classmethod
    def live(cls, p: dict[str, Any]) -> DisplayBasis:
        return cls(p.get("numericUnit"), p.get("value"), p.get("display"), p.get("minimum"), p.get("maximum"))


def parse_quantity(unit: str, text: str | None) -> tuple[float, float] | None:
    """(natural value, half display step) or None; same grammar as the host."""
    if not isinstance(text, str) or unit not in DISPLAY_UNITS:
        return None
    m = _TEXT.fullmatch(text.replace("\u2212", "-").strip(_JAVA_TRIM))
    if m is None:
        return None
    scale = DISPLAY_UNITS[unit][0].get(m.group(4).lower())
    if scale is None:
        return None
    fraction = m.group(2) if m.group(2) is not None else m.group(3) if m.group(3) is not None else ""
    return float(m.group(1)) * scale, 0.5 * 10 ** -len(fraction) * scale


def _near_quantity(shown: tuple[float, float], natural: float) -> bool:
    return abs(shown[0] - natural) <= shown[1] * _TOLERANCE_RELATIVE + _TOLERANCE_ABSOLUTE


def display_matches(wanted: str, live: str | None, unit: str | None = None) -> bool:
    """True when the live display already shows the wanted text at the live display's precision."""
    if live is None:
        return False
    if wanted.strip().casefold() == live.strip().casefold():
        return True
    a, b = parse_quantity(unit or "", wanted), parse_quantity(unit or "", live)
    return bool(a and b and _near_quantity(b, a[0]))


def _converters(unit: str, pid: str, type_name: str | None) -> tuple[Converter, Converter]:
    if unit == "seconds":
        scale = SECONDS_PARAMS.get((type_name, pid))
        if scale is None:
            raise SpecError([f"{pid}: no verified seconds scale for {type_name!r}; write the raw value instead"])
        return SECONDS_SCALES[scale[0]]
    _, to_raw, from_raw = DISPLAY_UNITS[unit]
    assert to_raw is not None
    assert from_raw is not None
    return to_raw, from_raw


def _verified_raw(pid: str, text: str, basis: DisplayBasis, type_name: str | None) -> float:
    unit = basis.unit
    if unit not in DISPLAY_UNITS:
        raise SpecError(
            [
                f"{pid}: display writes support numericUnit hertz/percent/decibel/seconds, not {unit!r}; write the raw value instead"
            ]
        )
    to_raw, from_raw = _converters(unit, pid, type_name)
    wanted = parse_quantity(unit, text)
    if wanted is None:
        raise SpecError(
            [f"{pid}: display {text!r} must be a number with a unit suffix {sorted(DISPLAY_UNITS[unit][0])}"]
        )
    shown = parse_quantity(unit, basis.display)
    if shown is None or not _near_quantity(shown, from_raw(basis.raw)):
        raise SpecError(
            [f"{pid}: its default raw/display pair does not fit the verified {unit} scale; write the raw value instead"]
        )
    if abs(shown[0]) < shown[1]:
        raise SpecError(
            [
                f"{pid}: the host cannot confirm the {unit} scale while the display reads zero (default {basis.display!r}); write the raw value instead"
            ]
        )
    raw = to_raw(wanted[0])
    if not math.isfinite(raw) or (basis.lo is not None and not basis.lo <= raw <= basis.hi):
        raise SpecError([f"{pid}: {text!r} converts to raw {raw:.4g}, outside {basis.lo}..{basis.hi}"])
    return raw


def check_display(
    pid: str,
    text: str,
    basis: DisplayBasis,
    type_name: str | None = None,
    context: dict[str, Any] | None = None,
) -> float:
    """Reject, from description data alone, display writes the host would refuse (unsupported_unit / invalid_params).

    Returns the raw value of `text`; seconds parameters are written as that raw value (the host has no seconds display write)."""
    if (type_name, pid) in raw_scales.RAW_SCALES and raw_scales.scale_for(type_name, pid, context) is None:
        raise SpecError(
            [
                f"{pid}: its raw scale depends on {cast(dict[str, Any], raw_scales.RAW_SCALES[(type_name, pid)].scale)['by']}; set it to Hertz/Kilohertz in the same module to write by display, or write the raw value"
            ]
        )
    if raw_scales.is_client(type_name, pid, context):  # recorded scale: converted here, no live pair needed
        try:
            return raw_scales.convert(type_name, pid, text, basis.lo, basis.hi, context)
        except ValueError as exc:
            raise SpecError([f"{pid}: {exc}"]) from exc
    return _verified_raw(pid, text, basis, type_name)


def display_want(unit: str | None, text: str, raw: float, client: bool = False) -> dict[str, Any]:
    """The write for a checked display: hertz/percent/decibel go to the host as display text, seconds and recorded raw scales as a converted raw value."""
    return {"value": raw, "shown": text} if unit == "seconds" or client else {"display": text}


def enum_context(params: dict[str, Any], info: dict[str, Any] | None) -> dict[str, Any]:
    """Enum parameter values written in a spec, by id (labels resolved), for conditional raw scales."""
    out: dict[str, Any] = {}
    options = {p["parameterId"]: p.get("options") or [] for p in (info or {}).get("parameters", ())}
    for pid, raw in params.items():
        if type(raw) is int:
            out[pid] = raw
        elif isinstance(raw, dict) and "label" in raw:
            out[pid] = next(
                (o["value"] for o in options.get(pid, ()) if o["label"].casefold() == str(raw["label"]).casefold()),
                None,
            )
    return out
