"""Raw <-> display scales of Grid parameters, natively measured on Bitwig 6.1.4.

`numericUnit` names the unit the host displays, not how the raw value maps to it. This table records the mapping per
(module type, parameter) so display writes ("-12 st", "-6 dB", "4 Hz") can be converted to raw client-side and agents can read
the scale in `grid.describe`. Most other seconds scales (cbrt/log10) live in grid_apply.SECONDS_PARAMS.

status: verified = a schema default raw/display pair fits this scale and not the obvious alternatives;
        measured = measured natively, but the schema default does not discriminate (zero or 1.0), so it only has to agree.
Parameters whose scale is not known are listed in UNVERIFIED and never converted.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any, Callable

_TEXT = re.compile(r"([+-]?(?:\d+(?:\.(\d*))?|\.(\d+)))\s*([A-Za-z%]+)", re.ASCII)
_JAVA_TRIM = "".join(map(chr, range(33)))
_ident = lambda n: n  # noqa: E731


@dataclass(frozen=True)
class Scale:
    name: str
    formula: str
    suffixes: dict[str, Callable[[float], float]]  # display unit suffix (lower case) -> base value
    to_raw: Callable[[float], float]
    from_raw: Callable[[float], float]
    client: bool = True  # False: the host's own display write already matches this scale
    example: str = ""  # a display write to suggest when a raw value was written to this non-linear scale


SCALES = {
    s.name: s
    for s in (
        Scale("linear_st", "raw = semitones", {"st": _ident}, _ident, _ident),
        Scale(
            "cbrt_st120",
            "raw = cbrt(st / 120)",
            {"st": _ident},
            lambda v: math.cbrt(v / 120),
            lambda r: 120 * r**3,
            example='{"display": "12 st"}',
        ),
        Scale(
            "linear_st120",
            "raw = st / 120",
            {"st": _ident},
            lambda v: v / 120,
            lambda r: 120 * r,
            example='{"display": "12 st"}',
        ),
        Scale("linear_seconds", "raw = seconds", {"s": _ident, "ms": lambda n: n / 1000}, _ident, _ident),
        Scale(
            "cbrt_seconds",
            "raw = cbrt(seconds)",
            {"s": _ident, "ms": lambda n: n / 1000},
            lambda v: math.cbrt(v) if v >= 0 else math.nan,
            lambda r: r**3,
            example='{"display": "250 ms"}',
        ),
        Scale(
            "log10_seconds",
            "raw = log10(seconds)",
            {"s": _ident, "ms": lambda n: n / 1000},
            lambda v: math.log10(v) if v > 0 else math.nan,
            lambda r: 10**r,
            example='{"display": "250 ms"}',
        ),
        Scale("linear_db", "raw = dB", {"db": _ident}, _ident, _ident),
        Scale("linear_hz", "raw = Hz", {"hz": _ident, "khz": lambda n: n * 1000}, _ident, _ident),
        Scale("linear_khz", "raw = kHz", {"hz": lambda n: n / 1000, "khz": _ident}, _ident, _ident),
        Scale(
            "cbrt_hz",
            "raw = cbrt(Hz); range +-3 = +-27 Hz",
            {"hz": _ident, "khz": lambda n: n * 1000},
            lambda f: math.cbrt(f),
            lambda r: r**3,
            example='{"display": "1.5 Hz"}',
        ),
        Scale("linear_fraction", "raw = percent / 100", {"%": lambda n: n / 100}, _ident, _ident),
        Scale(
            "cbrt_gain",
            "raw = cbrt(linear gain); 100 % = 0 dB = raw 1",
            {"%": lambda n: n / 100, "db": lambda n: 10 ** (n / 20)},
            lambda g: math.cbrt(g) if g >= 0 else math.nan,
            lambda r: r**3,
            example='{"display": "-6 dB"}',
        ),
        Scale(
            "midi_pitch_hz",
            "raw = MIDI note; Hz = 440 * 2^((raw - 69) / 12)",
            {"hz": _ident, "khz": lambda n: n * 1000},
            lambda f: 69 + 12 * math.log2(f / 440) if f > 0 else math.nan,
            lambda r: 440 * 2 ** ((r - 69) / 12),
            client=False,
            example='{"display": "800 Hz"}',
        ),
    )
}
BASE_UNIT = {
    "linear_st": "st",
    "cbrt_st120": "st",
    "linear_st120": "st",
    "linear_seconds": "s",
    "cbrt_seconds": "s",
    "log10_seconds": "s",
    "linear_db": "dB",
    "linear_hz": "Hz",
    "linear_khz": "kHz",
    "cbrt_hz": "Hz",
    "linear_fraction": "fraction",
    "cbrt_gain": "gain",
    "midi_pitch_hz": "Hz",
}


@dataclass(frozen=True)
class Entry:
    scale: str | dict[str, Any]  # scale name, or {"by": <enum parameter id>, "options": {enum value: scale name}}
    status: str
    evidence: tuple[tuple[float, str], ...]
    note: str = ""


_OSCILLATORS = (
    "Sine",
    "Sawtooth",
    "Pulse",
    "Triangle",
    "Phasor",
    "Scrawl",
    "Swarm",
    "Union",
    "Wavetable",
    "Bite",
    "Phase-1",
)
_RATE_BY_TIMEBASE = {"by": "TIMEBASE", "options": {0: "linear_hz", 1: "linear_khz"}}

RAW_SCALES: dict[tuple[str, str], Entry] = {}


def _add(types: tuple[str, ...] | list[str], pid: str, entry: Entry) -> None:
    for t in types:
        RAW_SCALES[(t, pid)] = entry


_add(
    (*_OSCILLATORS, "Sub"),
    "DETUNE",
    Entry(
        "cbrt_hz",
        "measured",
        ((1.1, "1.33 Hz"), (0.25, "0.02 Hz"), (0.0, "0.00 Hz")),
        "raw is the cube root of the Hz display (range +-3); raw below 0.5 is under 0.13 Hz, effectively no detune",
    ),
)
_add(
    ("Transpose",),
    "VALUE",
    Entry("linear_st", "measured", ((0.0, "0.00 st"),), "measured on Transpose: raw = semitones"),
)
_add(_OSCILLATORS, "PITCH", Entry("linear_st", "measured", ((0.0, "0.00 st"),), "measured on Sine: raw = semitones"))
_add(
    _OSCILLATORS,
    "PITCH_MOD",
    Entry(
        "cbrt_st120",
        "measured",
        ((0.584, "23.9 st"), (1.0, "120 st"), (0.112, "0.17 st")),
        "raw 1.0 = 120 st; display is the full-scale modulation depth",
    ),
)
_add(
    ("SVF", "Low-pass LD", "Low-pass MG", "Sallen-Key", "XP", "Rasp", "Ripple", "Comb", "Fizz"),
    "CUTOFF_MOD",
    Entry(
        "linear_st120",
        "measured",
        ((0.5, "60.00 st"), (0.584804, "70.18 st"), (1.0, "120 st")),
        "LINEAR, unlike the cube-root oscillator PITCH_MOD: display st = 120 * raw (read back on SVF, Comb, Low-pass LD/MG, Sallen-Key, XP, Rasp, Ripple, Fizz)",
    ),
)
_add(
    (
        "SVF",
        "Low-pass",
        "High-pass",
        "Crossover-2",
        "Crossover-3",
        "Low-pass LD",
        "Low-pass MG",
        "Sallen-Key",
        "XP",
        "Fizz",
        "Ripple",
        "Rasp",
    ),
    "CUTOFF",
    Entry(
        "midi_pitch_hz",
        "verified",
        ((60.0, "262 Hz"), (96.0, "2.09 kHz"), (102.8, "3.10 kHz")),
        "the host's own Hz display write already handles this",
    ),
)
_add(
    ("Amplify",),
    "VALUE",
    Entry("cbrt_gain", "verified", ((1.0, "100 %"), (0.7937, "50 %"), (1.072, "123 %")), "accepts % and dB"),
)
for _n in range(1, 7):
    _add(
        ("Mixer",),
        f"LEVEL_{_n}",
        Entry("cbrt_gain", "verified", ((1.0, "0.0 dB"), (0.6727, "-10.3 dB")), "accepts dB and %"),
    )
_add(
    ("All-pass Delay",),
    "TIME",
    Entry("linear_seconds", "verified", ((0.033, "33.0 ms"),), "linear, unlike the cbrt delays"),
)
_add(("Pitch Shift",), "PITCH", Entry("linear_st", "measured", ((0.0, "0.00 st"),), "raw = semitones"))
_add(
    ("Distortion", "Hard Clip", "Wavefolder", "Heat"),
    "DRIVE",
    Entry(
        "linear_db",
        "measured",
        ((0.0, "0.0 dB"), (6.0, "+6.0 dB"), (40.0, "+40.0 dB")),
        "raw = dB; the default 0 does not discriminate, the pairs do",
    ),
)
_add(
    ("Attenuate",),
    "VALUE",
    Entry("linear_fraction", "measured", ((1.0, "100 %"), (0.25, "25.0 %")), "linear, unlike the cubic Amplify"),
)
_add(("Value",), "VALUE", Entry("linear_fraction", "measured", ((0.0, "0.00 %"), (0.5, "50.0 %"))))
_add(
    ("Long Delay",),
    "TIME",
    Entry(
        "cbrt_seconds",
        "measured",
        ((1.0, "1.00 s"), (0.025, "0.02 ms"), (0.1, "1.00 ms"), (0.5, "125 ms"), (2.0, "8.00 s")),
        "the default 1.0 = 1 s is ambiguous; the pairs fix cbrt. Ignored while UNIT is a note value (default 16th): set UNIT Seconds",
    ),
)
_add(
    ("Comb",),
    "DECAY",
    Entry("cbrt_seconds", "verified", ((0.1563, "3.82 ms"), (0.1, "1.00 ms"), (0.5, "125 ms"), (2.0, "8.00 s"))),
)
_add(("Audio Out",), "HOLD_TIME", Entry("linear_seconds", "verified", ((0.05, "50.0 ms"), (0.1, "100 ms"))))
_add(
    ("Gate Repeat",),
    "RATE",
    Entry(
        "log10_seconds",
        "verified",
        ((-1.301, "50.0 ms"), (-2.0, "10.0 ms"), (-1.5, "31.6 ms"), (-1.0, "100 ms"), (0.0, "1.00 s")),
    ),
)
_add(("Clock",), "RATE", Entry("linear_hz", "measured", ((1.0, "1.00 Hz"),), "raw = Hz"))
_add(
    ("LFO", "S/H LFO"),
    "RATE",
    Entry(
        _RATE_BY_TIMEBASE,
        "measured",
        (),
        "raw = Hz with TIMEBASE Hertz, kHz with Kilohertz; other timebases are not hertz and not converted",
    ),
)
_add(("Chance",), "PROBABILITY", Entry("linear_fraction", "verified", ((0.5, "50.0 %"),)))
_add(("SVF",), "RESONANCE", Entry("linear_fraction", "verified", ((0.6, "60.0 %"),)))
_add(
    ("Mod Delay",),
    "FEEDBACK",
    Entry(
        "linear_fraction", "measured", ((0.0, "0.00 %"),), "default 0: the host cannot confirm a percent display write"
    ),
)

# (module, parameter) -> why no scale is recorded
UNVERIFIED: dict[tuple[str, str], str] = {}


def scale_for(type_name: str | None, pid: str, context: dict[str, Any] | None = None) -> tuple[Entry, Scale] | None:
    """The recorded (entry, scale) for a parameter; conditional entries need the controlling enum value in `context`."""
    entry = RAW_SCALES.get((type_name, pid))
    if entry is None:
        return None
    name = entry.scale
    if isinstance(name, dict):
        name = name["options"].get((context or {}).get(name["by"]))
        if name is None:
            return None
    return entry, SCALES[name]


def effective(scale: Scale, raw: float) -> str:
    """The display a raw value has on this scale, as text."""
    base = scale.from_raw(raw)
    unit = BASE_UNIT[scale.name]
    if unit == "gain":
        return f"{base * 100:.3g} % ({20 * math.log10(base):.1f} dB)" if base > 0 else "0 % (-inf dB)"
    if unit == "s":
        return f"{base * 1000:.3g} ms" if base < 1 else f"{base:.3g} s"
    return f"{base:.4g} {unit}"


def is_client(type_name: str | None, pid: str, context: dict[str, Any] | None = None) -> bool:
    found = scale_for(type_name, pid, context)
    return bool(found and found[1].client)


def parse(scale: Scale, text: str | None) -> tuple[float, float, float] | None:
    """(base value, base lower, base upper) for the display precision of `text`, or None when unparsable."""
    if not isinstance(text, str):
        return None
    m = _TEXT.fullmatch(text.replace("−", "-").strip(_JAVA_TRIM))
    fn = scale.suffixes.get(m.group(4).lower()) if m else None
    if fn is None:
        return None
    fraction = m.group(2) if m.group(2) is not None else m.group(3) if m.group(3) is not None else ""
    n, half = float(m.group(1)), 0.5 * 10 ** -len(fraction)
    lo, hi = sorted((fn(n - half), fn(n + half)))
    return fn(n), lo, hi


def convert(
    type_name: str | None, pid: str, text: str, lo: Any, hi: Any, context: dict[str, Any] | None = None
) -> float:
    """The raw value for a display text; raises ValueError with a hint when not writable."""
    found = scale_for(type_name, pid, context)
    if found is None:
        raise ValueError(f"no verified scale for {type_name!r} {pid}")
    entry, scale = found
    parsed = parse(scale, text)
    if parsed is None:
        raise ValueError(f"display {text!r} must be a number with a unit suffix {sorted(scale.suffixes)}")
    raw = scale.to_raw(parsed[0])
    if not math.isfinite(raw) or (lo is not None and not lo <= raw <= hi):
        raise ValueError(f"{text!r} converts to raw {raw:.4g}, outside {lo}..{hi}")
    return raw


def describe(type_name: str, pid: str) -> dict[str, Any] | None:
    """The `rawScale` block for grid.describe, or None."""
    entry = RAW_SCALES.get((type_name, pid))
    if entry is None:
        reason = UNVERIFIED.get((type_name, pid))
        return {"scale": None, "status": "unverified", "note": reason} if reason else None
    if isinstance(entry.scale, dict):
        scales = {str(v): SCALES[s].formula for v, s in entry.scale["options"].items()}
        block: dict[str, Any] = {
            "scale": f"by {entry.scale['by']}",
            "formulaByValue": scales,
            "displayWrites": f"only while {entry.scale['by']} is one of {sorted(scales)}",
        }
        units = sorted({u for s in entry.scale["options"].values() for u in SCALES[s].suffixes})
    else:
        scale = SCALES[entry.scale]
        block = {"scale": scale.name, "formula": scale.formula, "displayWrites": scale.client or "host"}
        units = sorted(scale.suffixes)
    shown = {"db": "dB", "hz": "Hz", "khz": "kHz"}
    block.update(
        {
            "status": entry.status,
            "units": [shown.get(u, u) for u in units],
            "evidence": [{"raw": r, "display": d} for r, d in entry.evidence],
        }
    )
    if entry.note:
        block["note"] = entry.note
    return block
