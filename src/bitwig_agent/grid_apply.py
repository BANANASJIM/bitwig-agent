"""Client-side `grid.apply`: a declarative, re-appliable Grid patch composed from the guarded grid.* host methods.

No host method is added. Every write is an ordinary guarded call with fresh CAS values, one request at a time,
stopping at the first error/unknown/partial outcome and never retrying.
"""

from __future__ import annotations

import copy
import difflib
import json
import math
import re
import struct
from importlib import resources
from typing import Any

from bitwig_agent import raw_scales
from bitwig_agent.client import BridgeError

BUNDLED_HOST_VERSION = "6.1.4"
INSERT_BATCH, PARAM_BATCH, CONNECTION_BATCH, READ_IDS, READ_PAGE = 16, 64, 32, 32, 128
# Upper bound only to fail early; the host enforces its own (version-dependent) device module cap.
MAX_DEVICE_MODULES = 1024
MAX_STRING_UNITS = 1024
WRITE_TIMEOUT_MS = 10000
CLEAN = ("applied", "already_matched")
_schema_cache: dict[str, Any] = {}
_notes_cache: dict[str, Any] = {}


def bundled_schema() -> dict[str, Any]:
    if not _schema_cache:
        text = resources.files("bitwig_agent").joinpath("data/grid_schema_6_1_4.json").read_text(encoding="utf-8")
        _schema_cache.update(json.loads(text))
    return _schema_cache


def measured_notes() -> dict[str, Any]:
    if not _notes_cache:
        text = resources.files("bitwig_agent").joinpath("data/grid_notes_6_1_4.json").read_text(encoding="utf-8")
        _notes_cache.update(json.loads(text))
    return _notes_cache


def _norm(text: str) -> str:
    return "".join(ch for ch in text.casefold() if ch.isalnum())


def _near(word: str, choices) -> str:
    close = difflib.get_close_matches(word, list(choices), n=4, cutoff=0.5)
    return f" Did you mean: {', '.join(map(repr, close))}?" if close else ""


class SpecError(Exception):
    def __init__(self, problems: list[str]):
        self.problems = problems
        super().__init__("; ".join(problems))


class Stop(Exception):
    """A dispatched call ended unclean; carries the raw response."""

    def __init__(self, method: str, raw: dict[str, Any], outcome: str):
        self.method, self.raw, self.outcome = method, raw, outcome
        super().__init__(method)


# --- blocks -------------------------------------------------------------------------------------------------
_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)\}")
INT_RANGE = range(-(2**31), 2**31)


def _fill(value: Any, values: dict[str, Any], where: str, problems: list[str]) -> Any:
    """Whole-value ${name} substitution (the value keeps its type) inside a params value, also within {display}/{label}."""
    if isinstance(value, str) and (m := _PLACEHOLDER.fullmatch(value)):
        if m.group(1) in values:
            return values[m.group(1)]
        problems.append(f"{where}: placeholder {value} is not a param of the block.{_near(m.group(1), values)}")
    elif isinstance(value, dict):
        return {k: _fill(v, values, where, problems) for k, v in value.items()}
    return value


def expand_blocks(args: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Replace blocks+instances by plain modules/connections (names prefixed, positions shifted); no nesting.

    Returns the expanded args and, per instance, {block, prefix, modules}. Runs before any other validation."""
    instances = args.get("instances") or []
    out = {k: v for k, v in args.items() if k not in ("blocks", "instances")}
    out["modules"] = list(args.get("modules") or [])
    out["connections"] = list(args.get("connections") or [])
    if not instances:
        return out, []
    blocks = args.get("blocks") or {}
    problems: list[str] = []
    mapping: list[dict[str, Any]] = []
    for i, inst in enumerate(instances):
        prefix, dx, dy = inst.get("prefix", ""), inst.get("dx", 0), inst.get("dy", 0)
        where = f"instance {i} (block {inst['block']!r}, prefix {prefix!r})"
        block = blocks.get(inst["block"])
        if block is None:
            problems.append(f"{where}: unknown block.{_near(inst['block'], blocks)} Blocks: {list(blocks)}")
            continue
        defaults, given = block.get("params") or {}, inst.get("args") or {}
        unknown = [k for k in given if k not in defaults]
        if unknown:
            problems.append(f"{where}: unknown args {unknown}; block params {list(defaults)}")
        values = {**defaults, **{k: v for k, v in given.items() if k in defaults}}
        local = [m["name"] for m in block["modules"]]
        names = []
        for m in block["modules"]:
            e = copy.deepcopy(m)
            e["name"], e["x"], e["y"] = prefix + m["name"], m["x"] + dx, m["y"] + dy
            if e["x"] not in INT_RANGE or e["y"] not in INT_RANGE:
                problems.append(f"{where}: module {m['name']!r} lands outside the 32-bit grid after dx/dy")
            if "params" in e:
                e["params"] = {k: _fill(v, values, f"{where}, module {m['name']!r}, param {k}", problems) for k, v in e["params"].items()}
            out["modules"].append(e)
            names.append(e["name"])
        for c in block.get("connections") or []:
            ends = {}
            for key in ("from", "to"):
                name, dot, _port = c[key].rpartition(".")
                if not dot or name not in local:
                    problems.append(f"{where}: block connection {c[key]!r} must be '<local module name>.<PORT_KEY>'.{_near(name or c[key], local)}")
                ends[key] = prefix + c[key]
            out["connections"].append(ends)
        mapping.append({"block": inst["block"], "prefix": prefix, "modules": names})
    for label, items in (("module name", [m["name"] for m in out["modules"]]), ("connection target", [c["to"] for c in out["connections"]])):
        dupes = sorted({n for n in items if items.count(n) > 1})
        if dupes:
            problems.append(f"expanded spec repeats {label}s {dupes}; give instances distinct prefixes")
    if problems:
        raise SpecError(problems)
    return out, mapping


def _pos(row: dict[str, Any]) -> tuple[int, int]:
    return row["x"], row["y"]


def generated_name(row: dict[str, Any]) -> str:
    """Stable stand-in name of an unnamed live module (grid.export writes it, grid.apply matches unnamed modules by it)."""
    return f"_{row.get('typeName') or row.get('typeId')}_{row['x']}_{row['y']}"


# Mirror of GridDisplayUnits.java: natural = what the display text measures (Hz, percent points, dB).
DISPLAY_UNITS = {
    "hertz": ({"hz": 1.0, "khz": 1000.0}, lambda f: 69 + 12 * math.log2(f / 440) if f > 0 else math.nan, lambda raw: 440 * 2 ** ((raw - 69) / 12)),
    "percent": ({"%": 1.0}, lambda p: p / 100, lambda raw: raw * 100),
    "decibel": ({"db": 1.0}, lambda d: d, lambda raw: raw),
    "seconds": ({"s": 1.0, "ms": 0.001}, None, None),  # raw mapping differs per parameter: SECONDS_PARAMS
}
# Seconds parameters: the raw<->seconds mapping is not uniform, so only (module type, parameter) pairs whose default
# raw/display pair fits one mapping exactly are writable by display. Natively measured on 6.1.4 (notes: grid_notes_6_1_4.json);
# every row is (mapping, default raw, default display) and is re-checked against the bundled schema by a test.
SECONDS_SCALES = {
    "cbrt": (lambda s: math.cbrt(s) if s >= 0 else math.nan, lambda raw: raw ** 3),
    "log10": (lambda s: math.log10(s) if s > 0 else math.nan, lambda raw: 10 ** raw),
}
SECONDS_PARAMS = {
    ("AD", "ATTACK"): ("cbrt", 0.2717, "20.1 ms"),
    ("AD", "DECAY"): ("cbrt", 0.694, "334 ms"),
    ("ADSR", "ATTACK"): ("cbrt", 0.2217, "10.9 ms"),
    ("ADSR", "DECAY"): ("cbrt", 0.944, "841 ms"),
    ("ADSR", "RELEASE"): ("cbrt", 0.63, "250 ms"),
    ("AR", "ATTACK"): ("cbrt", 0.2717, "20.1 ms"),
    ("AR", "RELEASE"): ("cbrt", 0.63, "250 ms"),
    ("Average", "TIME"): ("log10", -1.523, "30.0 ms"),
    ("Comb", "DECAY"): ("cbrt", 0.1563, "3.82 ms"),
    ("Curves", "TIME"): ("log10", -2.0, "10.0 ms"),
    ("CV Out", "SMOOTH"): ("log10", -3.0, "1.00 ms"),
    ("CV Pitch Out", "SMOOTH"): ("log10", -3.0, "1.00 ms"),
    ("Delay", "TIME"): ("cbrt", 0.5, "125 ms"),
    ("Follower", "TIME"): ("log10", -1.456, "35.0 ms"),
    ("Follower RF", "ATTACK"): ("log10", -2.304, "4.97 ms"),
    ("Follower RF", "DECAY"): ("log10", -0.376, "421 ms"),
    ("Freq Shift+", "DELAYTIME"): ("cbrt", 0.5, "125 ms"),
    ("Gate Length", "LENGTH"): ("log10", -1.301, "50.0 ms"),
    ("Gate Repeat", "RATE"): ("log10", -1.301, "50.0 ms"),
    ("Lag", "TIME"): ("log10", -1.301, "50.0 ms"),
    ("Logic Delay", "DELAY"): ("log10", -0.921, "120 ms"),
    ("Mod Delay", "TIME"): ("cbrt", 0.6694, "300 ms"),
    ("Pluck", "ATTACK"): ("cbrt", 0.2217, "10.9 ms"),
    ("Pluck", "DECAY"): ("cbrt", 0.794, "501 ms"),
    ("Pluck", "RELEASE"): ("cbrt", 0.6251, "244 ms"),
    ("Sampler", "GRAIN_SIZE"): ("log10", -0.87, "135 ms"),
    ("Segments", "TIME"): ("log10", -2.0, "10.0 ms"),
    ("Slope \u2197", "TIME"): ("log10", -2.825, "1.50 ms"),
    ("Slope \u2198", "TIME"): ("log10", -1.0, "100 ms"),
    ("Velo Mult", "TIME"): ("log10", -3.02, "0.95 ms"),
    ("\u00d8 Lag", "RATE"): ("log10", -1.5229, "30.0 ms"),
}
# Seconds parameters deliberately absent (default pair fits neither mapping, or only by the raw 1.0 = 1 s coincidence):
# Audio Out HOLD_TIME, Long Delay TIME (raw_scales.UNVERIFIED); All-pass Delay TIME is linear (raw_scales).
_TEXT = re.compile(r"([+-]?(?:\d+(?:\.(\d*))?|\.(\d+)))\s*([A-Za-z%]+)", re.ASCII)
_JAVA_TRIM = "".join(map(chr, range(33)))


def parse_quantity(unit: str, text: str | None) -> tuple[float, float] | None:
    """(natural value, half display step) or None; same grammar as the host."""
    if not isinstance(text, str) or unit not in DISPLAY_UNITS:
        return None
    m = _TEXT.fullmatch(text.replace("\u2212", "-").strip(_JAVA_TRIM))
    scale = DISPLAY_UNITS[unit][0].get(m.group(4).lower()) if m else None
    if scale is None:
        return None
    fraction = m.group(2) if m.group(2) is not None else m.group(3) if m.group(3) is not None else ""
    return float(m.group(1)) * scale, 0.5 * 10 ** -len(fraction) * scale


def _near_quantity(shown: tuple[float, float], natural: float) -> bool:
    return abs(shown[0] - natural) <= shown[1] * 1.000001 + 1e-12


def display_matches(wanted: str, live: str | None, unit: str | None = None) -> bool:
    """True when the live display already shows the wanted text at the live display's precision."""
    if live is None:
        return False
    if wanted.strip().casefold() == live.strip().casefold():
        return True
    a, b = parse_quantity(unit or "", wanted), parse_quantity(unit or "", live)
    return bool(a and b and _near_quantity(b, a[0]))


def check_display(pid: str, text: str, unit: str | None, raw_default: Any, display_default: Any, lo: Any, hi: Any,
                  type_name: str | None = None, context: dict[str, Any] | None = None) -> float:
    """Reject, from description data alone, display writes the host would refuse (unsupported_unit / invalid_params).

    Returns the raw value of `text`; seconds parameters are written as that raw value (the host has no seconds display write)."""
    if (type_name, pid) in raw_scales.RAW_SCALES and raw_scales.scale_for(type_name, pid, context) is None:
        raise SpecError([f"{pid}: its raw scale depends on {raw_scales.RAW_SCALES[(type_name, pid)].scale['by']}; set it to Hertz/Kilohertz in the same module to write by display, or write the raw value"])
    if raw_scales.is_client(type_name, pid, context):  # recorded scale: converted here, no live pair needed
        try:
            return raw_scales.convert(type_name, pid, text, lo, hi, context)
        except ValueError as exc:
            raise SpecError([f"{pid}: {exc}"]) from exc
    if unit not in DISPLAY_UNITS:
        raise SpecError([f"{pid}: display writes support numericUnit hertz/percent/decibel/seconds, not {unit!r}; write the raw value instead"])
    to_raw, from_raw = DISPLAY_UNITS[unit][1:]
    if unit == "seconds":
        scale = SECONDS_PARAMS.get((type_name, pid))
        if scale is None:
            raise SpecError([f"{pid}: no verified seconds scale for {type_name!r}; write the raw value instead"])
        to_raw, from_raw = SECONDS_SCALES[scale[0]]
    wanted = parse_quantity(unit, text)
    if wanted is None:
        raise SpecError([f"{pid}: display {text!r} must be a number with a unit suffix {sorted(DISPLAY_UNITS[unit][0])}"])
    shown = parse_quantity(unit, display_default)
    if shown is None or not _near_quantity(shown, from_raw(raw_default)):
        raise SpecError([f"{pid}: its default raw/display pair does not fit the verified {unit} scale; write the raw value instead"])
    if abs(shown[0]) < shown[1]:
        raise SpecError([f"{pid}: the host cannot confirm the {unit} scale while the display reads zero (default {display_default!r}); write the raw value instead"])
    raw = to_raw(wanted[0])
    if not math.isfinite(raw) or (lo is not None and not lo <= raw <= hi):
        raise SpecError([f"{pid}: {text!r} converts to raw {raw:.4g}, outside {lo}..{hi}"])
    return raw


def display_want(unit: str | None, text: str, raw: float, client: bool = False) -> dict[str, Any]:
    """The write for a checked display: hertz/percent/decibel go to the host as display text, seconds and recorded raw scales as a converted raw value."""
    return {"value": raw, "shown": text} if unit == "seconds" or client else {"display": text}


def check_display_live(pid: str, text: str, row_param: dict[str, Any], type_name: str | None = None,
                       context: dict[str, Any] | None = None) -> float:
    """The same checks against a live row (current raw/display pair)."""
    return check_display(pid, text, row_param.get("numericUnit"), row_param.get("value"), row_param.get("display"),
                         row_param.get("minimum"), row_param.get("maximum"), type_name, context)


def enum_context(params: dict[str, Any], info: dict[str, Any] | None) -> dict[str, Any]:
    """Enum parameter values written in a spec, by id (labels resolved), for conditional raw scales."""
    out: dict[str, Any] = {}
    options = {p["parameterId"]: p.get("options") or [] for p in (info or {}).get("parameters", ())}
    for pid, raw in params.items():
        if type(raw) is int:
            out[pid] = raw
        elif isinstance(raw, dict) and "label" in raw:
            out[pid] = next((o["value"] for o in options.get(pid, ()) if o["label"].casefold() == str(raw["label"]).casefold()), None)
    return out


# --- footprints ---------------------------------------------------------------------------------------------
Rect = tuple[int, int, int, int]


def overlaps(a: Rect, b: Rect) -> bool:
    return a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]


def order_moves(current: dict[str, tuple[int, int]], target: dict[str, tuple[int, int]], size: dict[str, tuple[int, int]],
                fixed: list[Rect]) -> list[tuple[str, tuple[int, int]]]:
    """Moves that never land on an occupied footprint: vacate-first, a cycle is broken through a free anchor far right.

    Bounded: every module is parked at most once, so at most 2 * len(current) moves.
    """
    pos = dict(current)
    todo = [n for n in current if current[n] != target[n]]
    parked: set[str] = set()
    plan: list[tuple[str, tuple[int, int]]] = []

    def rect(n, at):
        return (at[0], at[1], *size.get(n, (1, 1)))

    def free(n, at):
        r = rect(n, at)
        return not any(overlaps(r, f) for f in fixed) and not any(overlaps(r, rect(m, q)) for m, q in pos.items() if m != n)

    while todo:
        progressed = False
        for n in list(todo):
            if free(n, target[n]):
                plan.append((n, target[n]))
                pos[n] = target[n]
                todo.remove(n)
                progressed = True
        if todo and not progressed:
            n = next((m for m in todo if m not in parked), None)
            if n is None:
                raise SpecError(["footprints cannot be ordered into a collision-free move sequence"])
            right = max([f[0] + f[2] for f in fixed] + [q[0] + size.get(m, (1, 1))[0] for m, q in pos.items()]
                        + [q[0] + size.get(m, (1, 1))[0] for m, q in target.items()])
            spot = (right + 2, pos[n][1])
            if spot == pos[n] or not free(n, spot):
                raise SpecError(["footprints cannot be ordered into a collision-free move sequence"])
            pos[n] = spot
            plan.append((n, spot))
            parked.add(n)
    return plan


STEP_TYPES = ("Steps", "Pitches", "Gates", "Probabilities", "Accents")
STEP_ENTRIES, STEP_BOUND = 256, 128


def f32(x: float) -> float:
    return struct.unpack("f", struct.pack("f", x))[0]


def is_steps(raw: Any) -> bool:
    return isinstance(raw, dict) and "steps" in raw


def check_steps(pid: str, raw: Any) -> dict[str, Any]:
    """Validate a {steps:[...]} spec value offline; return the write fields {steps: [...]}."""
    values = raw["steps"] if is_steps(raw) else raw
    if not isinstance(values, list):
        raise SpecError([f"{pid}: steps must be a list of numbers (a '${{name}}' placeholder needs a block arg that is a list), got {values!r}"])
    if not 1 <= len(values) <= STEP_ENTRIES:
        raise SpecError([f"{pid}: steps takes 1..{STEP_ENTRIES} entries, got {len(values)}"])
    for i, v in enumerate(values):
        if type(v) not in (int, float) or not math.isfinite(v) or not -STEP_BOUND <= v <= STEP_BOUND:
            raise SpecError([f"{pid}: steps[{i}] = {v!r} must be a finite number within -{STEP_BOUND}..{STEP_BOUND}"])
    return {"steps": list(values)}


def check_value(pid: str, raw: Any, p: dict[str, Any]) -> dict[str, Any]:
    """Validate one spec value against a parameter description {kind, options?, minimum?, maximum?}; return the write fields."""
    kind = p.get("kind")
    if kind == "steps" or is_steps(raw):
        if kind != "steps":
            raise SpecError([f"{pid}: steps need a step-array parameter, this is {kind}"])
        if not is_steps(raw) and not isinstance(raw, list):
            raise SpecError([f"{pid}: step-array parameter needs {{steps:[...]}}, got {raw!r}"])
        return check_steps(pid, raw)
    if isinstance(raw, dict) and "display" in raw:
        if kind != "float":
            raise SpecError([f"{pid}: display needs a float parameter, this is {kind}"])
        return {"display": raw["display"]}
    if isinstance(raw, dict):
        options = p.get("options") or []
        if kind != "enum":
            raise SpecError([f"{pid}: label needs an enum parameter, this is {kind}"])
        labels = {o["label"]: o["value"] for o in options}
        folded = {k.casefold(): v for k, v in labels.items()}
        if raw["label"] in labels:
            return {"value": labels[raw["label"]]}
        if raw["label"].casefold() in folded:
            return {"value": folded[raw["label"].casefold()]}
        raise SpecError([f"{pid}: unknown label {raw['label']!r}.{_near(raw['label'], labels)} Options: {list(labels)}"])
    if kind == "boolean":
        if type(raw) is not bool:
            raise SpecError([f"{pid}: boolean parameter needs true/false, got {raw!r}"])
    elif kind in ("integer", "enum"):
        if type(raw) is not int:
            raise SpecError([f"{pid}: {kind} parameter needs an integer, got {raw!r}"])
        if kind == "enum" and p.get("options") is not None and raw not in {o["value"] for o in p["options"]}:
            raise SpecError([f"{pid}: {raw} is not an option value; options {[(o['value'], o['label']) for o in p['options']]}"])
    elif kind == "string":
        if type(raw) is not str:
            raise SpecError([f"{pid}: string parameter needs a string, got {raw!r}"])
        if len(raw.encode("utf-16-le", "surrogatepass")) // 2 > MAX_STRING_UNITS:
            raise SpecError([f"{pid}: string longer than {MAX_STRING_UNITS} UTF-16 units"])
    elif kind == "float":
        if type(raw) not in (int, float):
            raise SpecError([f"{pid}: float parameter needs a number, got {raw!r}"])
    else:
        raise SpecError([f"{pid}: {kind} parameters cannot be written"])
    if kind in ("integer", "float") and p.get("minimum") is not None and not p["minimum"] <= raw <= p["maximum"]:
        raise SpecError([f"{pid}: {raw} outside {p['minimum']}..{p['maximum']}"])
    return {"value": raw}


def value_matches(kind: str, want: dict[str, Any], row_param: dict[str, Any]) -> bool:
    if "display" in want or "shown" in want:
        if "shown" in want and type(row_param.get("value")) in (int, float) and math.isclose(row_param["value"], want["value"], rel_tol=1e-5, abs_tol=1e-9):
            return True  # converted client-side: the live raw already is the converted value
        return display_matches(want.get("display", want.get("shown")), row_param.get("display"), row_param.get("numericUnit"))
    live, new = row_param.get("value"), want["value"]
    if kind == "boolean":
        return type(live) is bool and live == new
    if kind == "string":
        return type(live) is str and live == new
    if kind == "float":
        return type(live) in (int, float) and math.isclose(live, new, rel_tol=1e-9, abs_tol=1e-12)
    return type(live) is int and live == new


class Apply:
    def __init__(self, client: Any, args: dict[str, Any], timeout_ms: int, instances: list[dict[str, Any]] | None = None):
        self.client, self.args, self.timeout_ms = client, args, timeout_ms
        self.instances = instances or []
        self.target = args["target"]
        self.dry = bool(args.get("dryRun"))
        self.prune = bool(args.get("prune"))
        self.replace_default = bool(args.get("replace"))
        self.replaced_names: list[str] = []
        self.external: list[str] = args.get("external") or []  # live modules the spec only references
        self.outside_ok = bool(args.get("allowOutsideDisconnect"))
        self.ext_rows: dict[str, dict[str, Any]] = {}
        self.dropped: list[str] = []
        self.namespace = args.get("namespace")
        self.modules: list[dict[str, Any]] = args.get("modules", [])
        self.names = [m["name"] for m in self.modules]
        self.applied = dict.fromkeys(("inserted", "renamed", "moved", "paramsWritten", "connections", "disconnected", "deleted"), 0)
        self.matched = dict.fromkeys(("modules", "positions", "params", "connections"), 0)
        if args.get("voicing"):
            self.applied["voicingFields"] = 0
        self.steps: list[dict[str, Any]] = []
        self.ids: dict[str, str] = {}
        self.warnings: list[str] = []
        self.wrote = False
        self.topo_written = False
        self.unnamed: dict[str, str] = {}
        self.phase = "validate"
        self.live_schema = False
        self.has_steps = any(is_steps(v) for m in self.modules for v in (m.get("params") or {}).values())
        self.voicing_want: dict[str, Any] = args.get("voicing") or {}
        self.voicing_set: dict[str, Any] = {}
        self.voicing_expected: dict[str, Any] = {}

    # ---- transport -------------------------------------------------------------------------------------------
    async def call(self, method: str, params: dict[str, Any], *, write: bool = False) -> dict[str, Any]:
        timeout = max(self.timeout_ms, WRITE_TIMEOUT_MS) if write else self.timeout_ms
        if write:
            self.wrote = True
            self.topo_written |= method not in ("grid.parameters.set", "grid.connections.set", "grid.voicing.set")
        try:
            host_params = params if method in ("grid.capabilities", "grid.catalog") else {**self.target, **params}
            result = await self.client.call(method, host_params, timeout)
        except BridgeError as exc:
            raise Stop(method, {"error": exc.error}, exc.error.get("outcome", "unknown")) from exc
        return result

    def verify(self, method: str, result: dict[str, Any], key: str | None = None) -> None:
        entries = result.get(key) if key else None
        bad = result.get("status") not in (None, "observed") or result.get("batchStatus") not in (None, "applied") \
            or result.get("completion") == "unknown" or result.get("matchesRequested") is False and method == "grid.connections.set" \
            or any(e.get("status") not in CLEAN for e in entries or [])
        if bad:
            unknown = result.get("batchStatus") == "unknown" or result.get("completion") == "unknown" \
                or any(e.get("status") == "unknown" for e in entries or []) or result.get("status") == "unknown"
            raise Stop(method, result, "unknown" if unknown else "not_applied")

    # ---- reads -----------------------------------------------------------------------------------------------
    async def read_graph(self, *, params: bool = False, ids: list[str] | None = None) -> tuple[list[dict], list[dict]]:
        rows: list[dict] = []
        conns: list[dict] = []
        offset = 0
        while True:
            req: dict[str, Any] = {"moduleOffset": offset, "moduleLimit": READ_PAGE, "includeParameters": params}
            if params and self.has_steps:
                req["full"] = True
            if ids:
                req["moduleIds"] = ids
            result = await self.call("grid.get", req)
            rows += result.get("modules", [])
            conns += result.get("connections", [])
            window = result.get("window") or {}
            total = window.get("total", len(rows))
            offset += len(result.get("modules", []))
            if ids:
                if result.get("missingModuleIds"):
                    raise Stop("grid.get", result, "not_applied")
                break
            if not result.get("modules") or offset >= total:
                break
        if not ids and len(rows) < total:
            raise Stop("grid.get", {"error": {"code": "graph_incomplete", "message": "grid.get did not return the whole bounded graph"}}, "not_applied")
        return rows, conns

    async def read_params(self, ids: list[str]) -> dict[str, dict]:
        rows: dict[str, dict] = {}
        for i in range(0, len(ids), READ_IDS):
            got, _ = await self.read_graph(params=True, ids=ids[i:i + READ_IDS])
            rows.update({r["instanceId"]: r for r in got})
        return rows

    # ---- validation ------------------------------------------------------------------------------------------
    async def load_types(self) -> None:
        caps = self.caps = await self.call("grid.capabilities", {"detail": "full"})
        schema = bundled_schema()
        self.live_schema = caps.get("hostVersion") != schema["hostVersion"]
        if not self.live_schema:
            self.info = schema["modules"]
            self.by_name = {n: [m["typeId"]] for n, m in self.info.items()}
        else:
            self.info, self.by_name, offset = None, {}, 0
            while True:
                page = await self.call("grid.catalog", {"offset": offset, "limit": 256})
                entries = page.get("entries", [])
                for e in entries:
                    self.by_name.setdefault(e["name"], []).append(e["typeId"])
                offset += len(entries)
                if (page.get("window") or {}).get("complete", True) or not entries:
                    break
        self.by_id = {t.lower(): n for n, ids in self.by_name.items() for t in ids}

    def resolve_type(self, spec: dict[str, Any]) -> tuple[str, str]:
        if "typeId" in spec:
            name = self.by_id.get(spec["typeId"].lower())
            if name is None:
                raise SpecError([f"{spec['name']!r}: typeId {spec['typeId']} is not a catalog module"])
            return spec["typeId"].lower(), name
        wanted = spec["type"]
        exact = [n for n in self.by_name if n == wanted] or [n for n in self.by_name if n.casefold() == wanted.casefold()]
        if not exact and _norm(wanted):
            exact = [n for n in self.by_name if _norm(n) == _norm(wanted)]
        if len(exact) != 1:
            raise SpecError([f"{spec['name']!r}: unknown module type {wanted!r}.{_near(wanted, self.by_name)}"])
        ids = self.by_name[exact[0]]
        if len(ids) != 1:
            raise SpecError([f"{spec['name']!r}: type {wanted!r} is ambiguous; use typeId from {ids}"])
        return ids[0].lower(), exact[0]

    def split_endpoint(self, endpoint: str, problems: list[str]) -> tuple[str, str] | None:
        name, dot, port = endpoint.rpartition(".")
        if not dot or not name or (name not in self.by_spec and name not in self.external):
            problems.append(f"connection endpoint {endpoint!r}: expected '<declared or external module name>.<PORT_KEY>'.{_near(name or endpoint, self.names + self.external)}")
            return None
        return name, port

    def validate_spec(self) -> None:
        problems: list[str] = []
        self.by_spec = {m["name"]: m for m in self.modules}
        problems += [f"external {n!r} is also declared in modules; declare it once" for n in self.external if n in self.by_spec]
        self.resolved: dict[str, dict[str, Any]] = {}
        for m in self.modules:
            try:
                type_id, type_name = self.resolve_type(m)
            except SpecError as exc:
                problems += exc.problems
                continue
            info = self.info[type_name] if self.info else None
            writes: dict[str, dict] = {}
            for pid, raw in (m.get("params") or {}).items():
                if is_steps(raw):  # the bundled schema has no step-array parameters: the id is matched against the live row
                    try:
                        want = check_steps(pid, raw)
                    except SpecError as exc:
                        problems += [f"{m['name']!r}: {p}" for p in exc.problems]
                        continue
                    if info is not None and type_name not in STEP_TYPES:
                        problems.append(f"{m['name']!r} ({type_name}): has no step array; steps work on {', '.join(STEP_TYPES)}")
                        continue
                    if info is not None and any(p["parameterId"] == pid for p in info["parameters"]):
                        problems.append(f"{m['name']!r} ({type_name}): {pid} is not a step array")
                        continue
                    writes[pid] = {"raw": raw, "want": want}
                    continue
                if info is None:
                    writes[pid] = {"raw": raw, "want": None}
                    continue
                pinfo = {p["parameterId"]: p for p in info["parameters"]}
                if pid not in pinfo:
                    problems.append(f"{m['name']!r} ({type_name}): unknown parameter {pid!r}.{_near(pid, pinfo)}")
                    continue
                try:
                    want = check_value(pid, raw, pinfo[pid])
                    if "display" in want:
                        q = pinfo[pid]
                        ctx = enum_context(m.get("params") or {}, info)
                        raw_value = check_display(pid, want["display"], q.get("numericUnit"), q.get("default"), q.get("defaultDisplay"),
                                                  q.get("minimum"), q.get("maximum"), type_name, ctx)
                        want = display_want(q.get("numericUnit"), want["display"], raw_value, raw_scales.is_client(type_name, pid, ctx))
                    writes[pid] = {"raw": raw, "want": want}
                except SpecError as exc:
                    problems += [f"{m['name']!r}: {p}" for p in exc.problems]
            self.resolved[m["name"]] = {"typeId": type_id, "typeName": type_name, "info": info, "params": writes}
        self.edges: list[tuple[str, str, str, str]] = []
        for c in self.args.get("connections", []):
            src, dst = self.split_endpoint(c["from"], problems), self.split_endpoint(c["to"], problems)
            if not src or not dst:
                continue
            for (name, port), want, label in ((src, "outputs", "output"), (dst, "inputs", "input")):
                info = self.resolved.get(name, {}).get("info")
                if info is not None and port not in {p["key"] for p in info[want]}:
                    problems.append(f"{name!r} ({self.resolved[name]['typeName']}) has no {label} {port!r}.{_near(port, [p['key'] for p in info[want]])}")
            self.edges.append((src[0], src[1], dst[0], dst[1]))
        if problems:
            raise SpecError(problems)

    # ---- planning --------------------------------------------------------------------------------------------
    def plan_params(self, name: str, row: dict | None) -> tuple[list[dict], int]:
        """Writes for one module against a live row (None: module not inserted yet, everything is a write)."""
        entry = self.resolved[name]
        writes, matched = [], 0
        live = {p["parameterId"]: p for p in (row or {}).get("parameters", []) if p.get("supported") and "parameterId" in p}
        for pid, spec in entry["params"].items():
            if row is None:
                writes.append({"module": name, "parameterId": pid, "to": spec["raw"], **({"rawValue": spec["want"]["value"]} if spec["want"] and "shown" in spec["want"] else {})})
                continue
            if pid not in live:
                raise SpecError([f"{name!r}: parameter {pid!r} is not a supported live parameter.{_near(pid, live)}"])
            lp = live[pid]
            want = spec["want"] or check_value(pid, spec["raw"], lp)
            ctx = {k: v.get("value") for k, v in live.items() if v.get("kind") == "enum"}
            client = raw_scales.is_client(entry["typeName"], pid, ctx)
            if "display" in want and (lp.get("numericUnit") == "seconds" or client):  # no bundled schema: convert against the live pair
                try:
                    want = display_want("seconds", want["display"], check_display_live(pid, want["display"], lp, entry["typeName"], ctx))
                except SpecError as exc:
                    raise SpecError([f"{name!r}: {p}" for p in exc.problems]) from exc
            if "steps" in want:
                write = self.plan_steps(name, row, pid, lp, want["steps"])
                if write is None:
                    matched += 1
                else:
                    writes.append({"module": name, "parameterId": pid, "to": spec["raw"], "write": write})
                continue
            if entry["info"] is not None:
                known = next(p for p in entry["info"]["parameters"] if p["parameterId"] == pid)
                if known["kind"] != lp["kind"]:
                    raise SpecError([f"{name!r}: {pid} is {lp['kind']} live but {known['kind']} in the bundled schema"])
            if value_matches(lp["kind"], want, lp):
                matched += 1
                continue
            if ("display" in want or "shown" in want) and not ("shown" in want and (entry["typeName"], pid) in raw_scales.RAW_SCALES):
                try:
                    check_display_live(pid, want.get("display", want.get("shown")), lp, entry["typeName"], ctx)
                except SpecError as exc:
                    raise SpecError([f"{name!r}: {p}" for p in exc.problems]) from exc
            writes.append({"module": name, "parameterId": pid, "to": spec["raw"], **({"rawValue": want["value"]} if "shown" in want else {}),
                           "write": {"moduleId": row["instanceId"], "parameterId": pid, "expectedKind": lp["kind"],
                                     "expectedValue": lp["value"], **{k: v for k, v in want.items() if k != "shown"}}})
        return writes, matched

    @staticmethod
    def plan_steps(name: str, row: dict, pid: str, lp: dict, wanted: list[float]) -> dict | None:
        """CAS write for a step array against the live row, or None when the live prefix already holds the values (float32 compare)."""
        if lp.get("kind") != "steps":
            raise SpecError([f"{name!r}: {pid} is {lp.get('kind')} live, not a step array"])
        n = len(wanted)
        if lp.get("arrayLength") is not None and n > lp["arrayLength"]:
            raise SpecError([f"{name!r}: {pid} has {n} steps but the array holds {lp['arrayLength']}"])
        live = lp.get("values") or []
        if len(live) < n:
            raise SpecError([f"{name!r}: the live row of {pid} returned {len(live)} entries, fewer than the {n} wanted"])
        if all(f32(a) == f32(b) for a, b in zip(live, wanted)):
            return None
        count = lp.get("stepCount")
        return {"moduleId": row["instanceId"], "parameterId": pid, "expectedKind": "steps", "expectedValues": list(live[:n]),
                "values": list(wanted), **({"full": True} if count is None or n > count else {})}

    def plan_connections(self, rows: dict[str, dict], conns: list[dict]) -> list[dict]:
        """Edits (as host entries + readable fields) for rows keyed by module name; modules absent from rows are new."""
        incoming = {(c["targetModuleId"], c["targetPortKey"]): (c["sourceModuleId"], c["sourcePortKey"]) for c in conns}
        name_of = {r["instanceId"]: r.get("customName") or generated_name(r) for r in [*self.rows0, *rows.values()]}
        edits, wanted = [], set()
        for src, sport, dst, dport in self.edges:
            drow, srow = rows.get(dst), rows.get(src)
            if drow is not None:
                wanted.add((drow["instanceId"], dport))
            current = incoming.get((drow["instanceId"], dport)) if drow else None
            desired = (srow["instanceId"], sport) if srow else None
            if drow and srow and current == desired:
                self.matched["connections"] += 1
                continue
            edit = {"from": f"{src}.{sport}", "to": f"{dst}.{dport}",
                    "previous": f"{name_of.get(current[0], current[0])}.{current[1]}" if current else None}
            if drow and srow:
                edit["write"] = {"targetModuleId": drow["instanceId"], "targetPortKey": dport,
                                 "expectedSource": {"moduleId": current[0], "portKey": current[1]} if current else None,
                                 "source": {"moduleId": srow["instanceId"], "portKey": sport}}
            edits.append(edit)
        if self.prune:
            held, cut = [], []
            for name, row in rows.items():
                if name in self.external:  # referenced only: never pruned
                    continue
                outside = bool(self.namespace) and not name.startswith(self.namespace)
                for port in row.get("inputs", []):
                    key = (row["instanceId"], port["key"])
                    if key in incoming and key not in wanted:
                        cur = incoming[key]
                        end = f"{name}.{port['key']} (from {name_of.get(cur[0], cur[0])}.{cur[1]})"
                        if outside and not self.outside_ok:
                            held.append(end)
                            continue
                        if outside:
                            cut.append(end)
                        edits.append({"from": None, "to": f"{name}.{port['key']}",
                                      "previous": f"{name_of.get(cur[0], cur[0])}.{cur[1]}",
                                      "write": {"targetModuleId": row["instanceId"], "targetPortKey": port["key"],
                                                "expectedSource": {"moduleId": cur[0], "portKey": cur[1]}, "source": None}})
            for items, text in ((held, "prune kept {n} cable(s) into modules outside namespace {ns!r}; allowOutsideDisconnect:true cuts them: {l}"),
                                (cut, "allowOutsideDisconnect: disconnecting {n} input(s) of modules outside namespace {ns!r}: {l}")):
                if items:
                    self.warnings.append(text.format(n=len(items), ns=self.namespace, l="; ".join(items[:10]) + (" ..." if len(items) > 10 else "")))
        return edits

    def live_checks(self, rows: dict[str, dict]) -> None:
        """Port validation against live rows, needed when no bundled schema covers the host version."""
        problems = []
        for src, sport, dst, dport in self.edges:
            for name, port, want in ((src, sport, "outputs"), (dst, dport, "inputs")):
                if name in rows and port not in {p["key"] for p in rows[name].get(want, [])}:
                    problems.append(f"{name!r} has no {want[:-1]} {port!r}.{_near(port, [p['key'] for p in rows[name].get(want, [])])}")
        if problems:
            raise SpecError(problems)

    # ---- phases ----------------------------------------------------------------------------------------------
    def footprints(self, existing, rows, deletes):
        """Sizes of spec modules, rects of untouched modules, and offline overlap validation of the spec layout."""
        gone = {r["instanceId"] for r in deletes}
        known = lambda v: type(v) is int  # noqa: E731
        self.size: dict[str, tuple[int, int]] = {}
        for n in self.names:
            info = self.resolved[n]["info"]
            row = existing.get(n)
            if info is not None and known(info.get("width")) and known(info.get("height")):
                self.size[n] = (info["width"], info["height"])
            elif row is not None and known(row.get("width")) and known(row.get("height")):
                self.size[n] = (row["width"], row["height"])
        spec_ids = {r["instanceId"] for r in existing.values()}
        self.fixed: list[Rect] = []
        fixed_names = []
        for r in rows:
            if r["instanceId"] in spec_ids or r["instanceId"] in gone:
                continue
            if all(known(r.get(k)) for k in ("x", "y", "width", "height")):
                self.fixed.append((r["x"], r["y"], r["width"], r["height"]))
                fixed_names.append(r.get("customName") or r.get("name") or r["instanceId"])
        problems = []
        rects = {n: (self.by_spec[n]["x"], self.by_spec[n]["y"], *self.size[n]) for n in self.names if n in self.size}
        items = list(rects.items())
        for i, (a, ra) in enumerate(items):
            problems += [f"{a!r} and {b!r} overlap at their requested positions {ra[:2]} / {rb[:2]}" for b, rb in items[i + 1:] if overlaps(ra, rb)]
            problems += [f"{a!r} at {ra[:2]} overlaps existing module {fn!r}, which the spec does not manage" for fn, f in zip(fixed_names, self.fixed) if overlaps(ra, f)]
        return problems

    def module_limit(self) -> int:
        """Smallest maximumModules the host reports for insert/move/rename, else the early-failure constant."""
        found = [self.caps[k]["maximumModules"] for k in ("moduleInsert", "moduleMove", "moduleRename")
                 if isinstance(self.caps.get(k), dict) and type(self.caps[k].get("maximumModules")) is int]
        return min(found + [MAX_DEVICE_MODULES])

    def admission(self, inserting: bool, deleting: bool) -> list[str]:
        caps, problems = self.caps, []
        sections = {k: caps.get(k) for k in ("moduleInsert", "moduleMove", "moduleRename", "moduleDelete") if isinstance(caps.get(k), dict)}
        if caps.get("topologyQuarantined") or any(v.get("topologyQuarantined") for v in sections.values()):
            problems.append("the host reports topologyQuarantined; topology edits are blocked until Bitwig restarts")
        if inserting and any(sections.get(k, {}).get("available") is False for k in ("moduleInsert", "moduleRename")):
            problems.append("the host reports module insert/rename as unavailable")
        if deleting and sections.get("moduleDelete", {}).get("available") is False:
            problems.append("the host reports module delete as unavailable")
        return problems

    @staticmethod
    def unreadable_names(caps, rows) -> list[str]:
        bad = [r for r in rows if not isinstance(r.get("customName"), str)]
        problems = [f"{len(bad)} module(s) report no readable customName; refusing to write because identity would be unknown"] if bad else []
        names = caps.get("moduleNames")
        if isinstance(names, dict) and names.get("available") is False:
            problems.append("the host reports module names as unavailable; refusing to write")
        return problems

    async def run(self) -> dict[str, Any]:
        await self.load_types()
        self.validate_spec()
        if self.voicing_want:
            await self.plan_voicing()
        self.phase = "read"
        rows, conns = await self.read_graph()
        self.rows0, self.conns0 = rows, conns
        problems = self.unreadable_names(self.caps, rows)
        if problems:
            raise SpecError(problems)
        by_custom: dict[str, list[dict]] = {}
        for r in rows:
            by_custom.setdefault(r["customName"] or generated_name(r), []).append(r)  # unnamed: matched by its generated name
        existing = {}
        replaced: list[dict] = []
        for name in self.names:
            found = by_custom.get(name, [])
            if len(found) > 1:
                problems.append(f"{len(found)} modules in the device are already named {name!r}; names must be unique")
            elif found:
                live_type = found[0].get("typeId")
                if not isinstance(live_type, str) or live_type.lower() != self.resolved[name]["typeId"]:
                    if self.by_spec[name].get("replace", self.replace_default):
                        replaced.append(found[0])
                        continue
                    problems.append(f"{name!r} exists as {found[0].get('typeName') or live_type} but the spec wants {self.resolved[name]['typeName']}")
                else:
                    existing[name] = found[0]
        for name in self.external:
            found = by_custom.get(name, [])
            if len(found) == 1:
                self.ext_rows[name] = found[0]
            else:
                problems.append(f"external module {name!r} " + (f"matches {len(found)} modules; names must be unique" if found else f"is not in the device.{_near(name, by_custom)}"))
        deletes = list(replaced)
        if self.prune and self.namespace:
            deletes += [r for r in rows if r["customName"].startswith(self.namespace) and r["customName"] not in self.by_spec
                        and r["customName"] not in self.external]
        self.replaced_names = [r["customName"] for r in replaced]
        self.dropped = self.dropped_cables(replaced, rows, conns)
        if self.dropped:
            self.warnings.append(f"replace drops {len(self.dropped)} cable(s) the spec does not declare: {'; '.join(self.dropped[:10])}" + (" ..." if len(self.dropped) > 10 else ""))
        self.measured_warnings(existing)
        inserts = [n for n in self.names if n not in existing]
        total = len(rows) - len(deletes) + len(inserts)
        limit = self.module_limit()
        if total > limit:
            problems.append(f"the device would hold {total} modules; the host limit is {limit}")
        if not self.dry:
            problems += self.admission(bool(inserts), bool(deletes))
        problems += self.footprints(existing, rows, deletes)
        if problems:
            raise SpecError(problems)
        self.ids = {n: r["instanceId"] for n, r in existing.items()}
        self.matched["modules"] = len(existing)
        if self.live_schema:
            self.live_checks({**existing, **self.ext_rows})
        current = {n: _pos(r) for n, r in existing.items()}
        target = {n: (self.by_spec[n]["x"], self.by_spec[n]["y"]) for n in existing}
        self.matched["positions"] = sum(1 for n in existing if current[n] == target[n])
        self.pre_moves = order_moves(current, target, self.size, self.fixed)
        # Spec-detectable parameter problems of existing modules surface before the first write.
        param_rows = await self.read_params([r["instanceId"] for n, r in existing.items() if self.resolved[n]["params"]])
        self.pre_params = {n: self.plan_params(n, param_rows.get(existing[n]["instanceId"]) if n in existing else None) for n in self.names}
        pending = {"deleted": len(deletes), "inserted": len(inserts)}
        if self.dry:
            return self.dry_run(existing, conns, deletes, inserts, current)
        try:
            await self.execute(deletes, inserts, current, pending)
        except Stop as stop:
            return self.report_stop(stop, pending)
        except Exception as exc:  # noqa: BLE001 - keep the report of work already dispatched
            raw = {"error": {"code": "internal_error", "message": f"{type(exc).__name__}: {exc}", "outcome": "unknown"}}
            return self.report_stop(Stop("internal", raw, "unknown"), pending)
        return self.report("applied" if any(self.applied.values()) else "unchanged")

    async def plan_voicing(self) -> None:
        """Read the device voicing (read-only) before anything is written: refuse a device without one, plan only the differing fields."""
        got = await self.call("grid.voicing.get", {})
        if not got.get("supported") or not isinstance(got.get("voicing"), dict):
            raise SpecError([f"voicing: this Grid device has no voicing model (only Poly Grid does): {got.get('reason') or 'unsupported'}"])
        live = got["voicing"]
        problems = [f"voicing.{k}: the host did not report this field" for k in self.voicing_want if k not in live]
        if problems:
            raise SpecError(problems)
        self.voicing_set = {k: v for k, v in self.voicing_want.items() if live[k] != v}
        self.voicing_expected = {k: live[k] for k in self.voicing_set}
        self.matched["voicingFields"] = len(self.voicing_want) - len(self.voicing_set)

    def dry_run(self, existing, conns, deletes, inserts, current) -> dict[str, Any]:
        param_plan, planned_params = [], 0
        for name in self.names:
            writes, matched = self.pre_params[name]
            self.matched["params"] += matched
            param_plan += [{k: v for k, v in w.items() if k != "write"} for w in writes]
            planned_params += len(writes)
        moves, pos = [], dict(current)
        for name, at in self.pre_moves:
            moves.append({"name": name, "from": list(pos[name]), "to": list(at)})
            pos[name] = at
        edits = self.plan_connections({**existing, **self.ext_rows}, conns)
        plan = {"deletes": [r["customName"] for r in deletes], **({"replaces": self.replaced_names} if self.replaced_names else {}),
                **({"droppedConnections": self.dropped} if self.dropped else {}),
                "inserts": [{"name": n, "type": self.resolved[n]["typeName"], "x": self.by_spec[n]["x"], "y": self.by_spec[n]["y"]} for n in inserts],
                "renames": list(inserts), "moves": moves, "params": param_plan,
                "connections": [{k: v for k, v in e.items() if k != "write"} for e in edits],
                **({"voicing": {"set": self.voicing_set, "from": self.voicing_expected}} if self.voicing_want else {})}
        planned = {"inserted": len(inserts), "renamed": len(inserts), "moved": len(moves), "paramsWritten": planned_params,
                   "connections": sum(1 for e in edits if e["from"]), "disconnected": sum(1 for e in edits if not e["from"]), "deleted": len(deletes),
                   **({"voicingFields": len(self.voicing_set)} if self.voicing_want else {})}
        notes = ["modules already in the device are moved before inserting; inserted modules are only moved if the host reflows them"] if inserts else []
        if self.live_schema:
            notes.append("no bundled schema for this host version: ports, parameters and footprints of inserted modules are validated only after insertion")
        result = {"status": "dryRun", "planned": planned, "alreadyMatched": self.matched, "plan": plan, "steps": []}
        return result | ({"instances": self.instances} if self.instances else {}) | ({"warnings": self.warnings} if self.warnings else {}) | self.notes_field(notes)

    def dropped_cables(self, replaced: list[dict], rows: list[dict], conns: list[dict]) -> list[str]:
        """Live cables on replaced modules that the spec does not re-declare (deleting the module removes them)."""
        gone = {r["instanceId"] for r in replaced}
        label = {r["instanceId"]: r.get("customName") or r.get("name") for r in rows}
        declared = {(a, ap, b, bp) for a, ap, b, bp in self.edges}
        out = []
        for c in conns:
            if c["sourceModuleId"] in gone or c["targetModuleId"] in gone:
                s, t = label.get(c["sourceModuleId"]), label.get(c["targetModuleId"])
                if (s, c["sourcePortKey"], t, c["targetPortKey"]) not in declared:
                    out.append(f"{s}.{c['sourcePortKey']} -> {t}.{c['targetPortKey']}")
        return out

    def measured_warnings(self, existing: dict[str, dict]) -> None:
        """Cheap checks against measured behaviour: an oscillator PITCH_IN is inert while PITCH_MOD is 0."""
        for _src, _sport, dst, dport in self.edges:
            info = self.resolved.get(dst, {}).get("info")
            if dport != "PITCH_IN" or info is None:
                continue
            mod = next((p for p in info["parameters"] if p["parameterId"] == "PITCH_MOD"), None)
            if mod is None:
                continue
            given = self.resolved[dst]["params"].get("PITCH_MOD")
            if given is not None:
                value = given["raw"] if type(given["raw"]) in (int, float) else None
            else:  # an existing module may have been set earlier: only the default of a new one is certain
                value = None if dst in existing else mod.get("default")
            if value == 0:
                self.warnings.append(f"{dst}: PITCH_IN is connected but PITCH_MOD is 0, so it has no effect (raw 1.0 = 120 semitones)")

    def notes_field(self, extra: list[str] | None = None) -> dict[str, list[str]]:
        data = measured_notes()
        notes = list(extra or [])
        if self.names:
            used = dict.fromkeys(self.resolved[n]["typeName"] for n in self.names)
            notes += [f"{t}: {x}" for t in used for x in data["modules"].get(t, [])] + data["general"]
        return {"notes": notes} if notes else {}

    async def move_to(self, name: str, pos: dict[str, tuple[int, int]], at: tuple[int, int]) -> None:
        mid = self.ids[name]
        result = await self.call("grid.modules.move", {"moduleId": mid, "expectedX": pos[mid][0], "expectedY": pos[mid][1],
                                                      "x": at[0], "y": at[1]}, write=True)
        self.verify("grid.modules.move", result)
        self.applied["moved"] += 1
        for m in result.get("modulesMoved", []):
            pos[m["moduleId"]] = (m["to"]["x"], m["to"]["y"])
        actual = result.get("actualPosition")
        pos[mid] = (actual["x"], actual["y"]) if actual else at
        self.steps.append({"op": "move", "name": name, "to": list(pos[mid])})

    async def execute(self, deletes, inserts, current, pending) -> None:
        self.phase = "voicing"
        if self.voicing_set:
            result = await self.call("grid.voicing.set", {"expected": self.voicing_expected, "set": self.voicing_set}, write=True)
            self.verify("grid.voicing.set", result)
            self.applied["voicingFields"] += len(self.voicing_set)
            self.steps.append({"op": "voicing", "fields": sorted(self.voicing_set)})
            if result.get("changedUnrequested"):
                self.warnings.append(f"voicing: the native change also moved {result['changedUnrequested']}")
        self.phase = "delete"
        for row in deletes:
            result = await self.call("grid.modules.delete", {"moduleId": row["instanceId"]}, write=True)
            self.verify("grid.modules.delete", result)
            self.applied["deleted"] += 1
            pending["deleted"] -= 1
            self.steps.append({"op": "delete", "name": row["customName"], **({"replace": True} if row["customName"] in self.replaced_names else {})})
        self.phase = "premove"
        pos = {self.ids[n]: p for n, p in current.items()}
        for name, at in self.pre_moves:
            await self.move_to(name, pos, at)
        queue = list(inserts)
        while queue:
            self.phase = "insert"
            chunk = queue[:INSERT_BATCH]
            result = await self.call("grid.modules.insert", {"modules": [
                {"typeId": self.resolved[n]["typeId"], "x": self.by_spec[n]["x"], "y": self.by_spec[n]["y"]} for n in chunk]}, write=True)
            entries = result.get("modules", [])
            added = {r["instanceId"]: r for r in result.get("modulesAdded", [])}
            fresh = []
            for n, e in zip(chunk, entries):
                if e.get("status") == "applied" and e.get("moduleId"):
                    self.ids[n] = e["moduleId"]
                    self.unnamed[e["moduleId"]] = n
                    self.applied["inserted"] += 1
                    pending["inserted"] -= 1
                    fresh.append(n)
            self.steps.append({"op": "insert", "count": len(chunk), "status": result.get("batchStatus")})
            # Name what landed before anything can stop the run, or a re-apply would insert duplicates.
            unsettled = result.get("batchStatus") == "unknown" or result.get("completion") == "unknown" \
                or any(e.get("status") == "unknown" for e in entries)
            if not unsettled:
                self.phase = "rename"
                for n in fresh:
                    renamed = await self.call("grid.modules.rename", {
                        "moduleId": self.ids[n], "expectedCustomName": added.get(self.ids[n], {}).get("customName") or "", "customName": n}, write=True)
                    self.verify("grid.modules.rename", renamed)
                    self.applied["renamed"] += 1
                    self.unnamed.pop(self.ids[n], None)
                    self.steps.append({"op": "rename", "name": n})
            self.phase = "insert"
            if len(entries) != len(chunk):
                raise Stop("grid.modules.insert", result, "unknown")
            # A batch cut short only by the host deadline margin (applied entries, then not_dispatched) just continues.
            cut = result.get("batchStatus") == "partial" and result.get("status") in (None, "observed") and bool(fresh) \
                and all(e.get("status") in ("applied", "not_dispatched") for e in entries)
            if not cut:
                self.verify("grid.modules.insert", result, "modules")
            queue = queue[len(fresh):] if cut else queue[len(chunk):]
        if inserts:  # only insertion can displace modules; otherwise the pre-moves were exact
            self.phase = "move"
            rows, _ = await self.read_graph()
            by_id = {r["instanceId"]: r for r in rows}
            for n in self.names:
                if self.ids[n] not in by_id:
                    raise Stop("grid.get", {"error": {"code": "stale_context", "message": f"module {n!r} is missing after insertion"}}, "not_applied")
            pos = {i: _pos(r) for i, r in by_id.items()}
            for n in self.names:
                row = by_id[self.ids[n]]
                if n not in self.size and type(row.get("width")) is int and type(row.get("height")) is int:
                    self.size[n] = (row["width"], row["height"])
            spec_ids = {self.ids[n] for n in self.names}
            fixed = [(r["x"], r["y"], r["width"], r["height"]) for i, r in by_id.items()
                     if i not in spec_ids and all(type(r.get(k)) is int for k in ("x", "y", "width", "height"))]
            try:
                plan = order_moves({n: pos[self.ids[n]] for n in self.names}, {n: (self.by_spec[n]["x"], self.by_spec[n]["y"]) for n in self.names},
                                   self.size, fixed)
            except SpecError as exc:
                raise Stop("grid.get", {"error": {"code": "invalid_spec", "message": "; ".join(exc.problems)}}, "not_applied") from exc
            for name, at in plan:
                await self.move_to(name, pos, at)
        self.phase = "params"
        with_params = [n for n in self.names if self.resolved[n]["params"]]
        writes: list[dict] = []
        try:
            if not self.topo_written:  # nothing structural changed since the validation read: reuse its CAS values
                for n in with_params:
                    got, matched = self.pre_params[n]
                    self.matched["params"] += matched
                    writes += [w["write"] for w in got]
            else:
                param_rows = await self.read_params([self.ids[n] for n in with_params])
                for n in with_params:
                    got, matched = self.plan_params(n, param_rows[self.ids[n]])
                    self.matched["params"] += matched
                    writes += [w["write"] for w in got]
        except SpecError as exc:
            raise Stop("grid.get", {"error": {"code": "invalid_spec", "message": "; ".join(exc.problems)}}, "not_applied") from exc
        for i in range(0, len(writes), PARAM_BATCH):
            chunk = writes[i:i + PARAM_BATCH]
            result = await self.call("grid.parameters.set", {"writes": chunk}, write=True)
            entries = result.get("writes", [])
            self.applied["paramsWritten"] += sum(1 for e in entries if e.get("status") == "applied")
            self.matched["params"] += sum(1 for e in entries if e.get("status") == "already_matched")
            self.warnings += [f"{e['parameterId']}: displayMismatch {e['displayMismatch']}" for e in entries if e.get("displayMismatch")]
            self.steps.append({"op": "params", "count": len(chunk), "status": result.get("batchStatus")})
            self.verify("grid.parameters.set", result, "writes")
        self.phase = "connections"
        rows, conns = (self.rows0, self.conns0) if not self.topo_written else await self.read_graph()
        by_id = {r["instanceId"]: r for r in rows}
        by_name = {n: by_id[self.ids[n]] for n in self.names if self.ids[n] in by_id}
        if len(by_name) != len(self.names):
            raise Stop("grid.get", {"error": {"code": "stale_context", "message": "a declared module vanished before wiring"}}, "not_applied")
        ext = {n: by_id[r["instanceId"]] for n, r in self.ext_rows.items() if r["instanceId"] in by_id}
        if len(ext) != len(self.ext_rows):
            raise Stop("grid.get", {"error": {"code": "stale_context", "message": "an external module vanished before wiring"}}, "not_applied")
        try:
            if self.live_schema:
                self.live_checks({**by_name, **ext})
        except SpecError as exc:
            raise Stop("grid.get", {"error": {"code": "invalid_spec", "message": "; ".join(exc.problems)}}, "not_applied") from exc
        edits = self.plan_connections({**by_name, **ext}, conns)
        for n in self.names:
            want = (self.by_spec[n]["x"], self.by_spec[n]["y"])
            if _pos(by_name[n]) != want:
                self.warnings.append(f"{n}: at {list(_pos(by_name[n]))}, wanted {list(want)} (native reflow or collision)")
        for i in range(0, len(edits), CONNECTION_BATCH):
            chunk = edits[i:i + CONNECTION_BATCH]
            result = await self.call("grid.connections.set", {"connections": [e["write"] for e in chunk]}, write=True)
            entries = result.get("connections", [])
            done = [e for e, r in zip(chunk, entries) if r.get("status") == "applied"]
            self.applied["connections"] += sum(1 for e in done if e["from"])
            self.applied["disconnected"] += sum(1 for e in done if not e["from"])
            self.matched["connections"] += sum(1 for e in entries if e.get("status") == "already_matched")
            self.steps.append({"op": "connections", "count": len(chunk), "status": result.get("batchStatus")})
            self.verify("grid.connections.set", result, "connections")

    # ---- reports ---------------------------------------------------------------------------------------------
    def report(self, status: str) -> dict[str, Any]:
        out = {"status": status, "applied": self.applied, "alreadyMatched": self.matched, "steps": self.steps, "moduleIds": self.ids}
        return out | ({"instances": self.instances} if self.instances else {}) | ({"warnings": self.warnings} if self.warnings else {}) | self.notes_field()

    def report_stop(self, stop: Stop, pending: dict[str, int]) -> dict[str, Any]:
        order = ["voicing", "delete", "premove", "insert", "rename", "move", "params", "connections"]
        status = "unknown" if stop.outcome == "unknown" else ("partial" if any(self.applied.values()) else "failed")
        out = self.report(status)
        out["failure"] = {"phase": self.phase, "method": stop.method, "response": stop.raw}
        out["notDispatched"] = {"phasesNotStarted": order[order.index(self.phase) + 1:] if self.phase in order else order,
                                "failedPhaseIncomplete": self.phase, **{k: v for k, v in pending.items() if v}}
        if self.unnamed:
            out["unnamedModules"] = dict(self.unnamed)
        out["next"] = ("Outcome unknown: inspect with grid.get before any further write." if status == "unknown"
                       else "Fix the cause and re-apply the same spec; applied work is reused." + (" Modules listed in unnamedModules are not renamed yet: rename them by hand (customName = value) or delete them first." if self.unnamed else ""))
        return out


def invalid_spec(problems: list[str]) -> BridgeError:
    return BridgeError({"code": "invalid_spec", "message": f"{len(problems)} problem(s); nothing was written: " + " | ".join(problems[:20]),
                        "outcome": "not_dispatched", "details": {"problems": problems}})


async def apply(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    """Run a validated grid.apply spec (dict from schema.GridApplyArgs) through `client.call(method, params, timeout_ms)`."""
    try:
        args, instances = expand_blocks(args)
    except SpecError as exc:
        raise invalid_spec(exc.problems) from exc
    run = Apply(client, args, timeout_ms, instances)
    try:
        return await run.run()
    except SpecError as exc:
        raise invalid_spec(exc.problems) from exc
    except Stop as stop:
        if run.wrote:
            return run.report_stop(stop, {})
        error = stop.raw.get("error")
        if error is not None:
            raise BridgeError(error) from stop
        raise BridgeError({"code": "not_applied", "message": f"{stop.method} returned an unusable result; nothing was written",
                           "outcome": "not_dispatched", "details": stop.raw}) from stop
    except BridgeError:
        raise
    except Exception as exc:  # noqa: BLE001
        if run.wrote:
            raw = {"error": {"code": "internal_error", "message": f"{type(exc).__name__}: {exc}", "outcome": "unknown"}}
            return run.report_stop(Stop("internal", raw, "unknown"), {})
        raise BridgeError({"code": "internal_error", "message": f"{type(exc).__name__}: {exc}", "outcome": "not_dispatched"}) from exc
