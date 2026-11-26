"""Advisory warnings for a spec: measured traps (grid_notes rules) that the host accepts but that rarely do what was meant."""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Collection
from dataclasses import dataclass
from typing import Any, NamedTuple

from bitwig_agent.grid import raw_scales
from bitwig_agent.grid.bundled import measured_notes
from bitwig_agent.grid.display import enum_context
from bitwig_agent.grid.read import Graph
from bitwig_agent.grid.spec import Resolved, Spec

IsPoly = Callable[[], Awaitable[bool]]


def measured_warnings(spec: Spec, existing: Collection[str]) -> list[str]:
    """Cheap checks against measured behaviour: an oscillator PITCH_IN is inert while PITCH_MOD is 0."""
    out = []
    for edge in spec.edges:
        module = spec.resolved.get(edge.dst)
        info = module.info if module else None
        if edge.dst_port != "PITCH_IN" or module is None or info is None:
            continue
        mod = next((p for p in info["parameters"] if p["parameterId"] == "PITCH_MOD"), None)
        if mod is None:
            continue
        given = module.params.get("PITCH_MOD")
        if given is not None:
            value = given.written if type(given.written) in (int, float) else None
        else:  # an existing module may have been set earlier: only the default of a new one is certain
            value = None if edge.dst in existing else mod.get("default")
        if value == 0:
            out.append(
                f"{edge.dst}: PITCH_IN is connected but PITCH_MOD is 0, so it has no effect (raw 1.0 = 120 semitones)"
            )
    return out


@dataclass
class _Facts:
    name: str
    resolved: Resolved
    info: dict[str, Any]
    spec_params: dict[str, Any]
    exists: bool
    ids: dict[str, Any]
    wired_in: bool
    wired_out: bool
    ins: set[tuple[Any, str]]

    def value(self, pid: str) -> Any:
        """Value of a parameter after the apply when certain: given in the spec, or the default of a module not inserted yet."""
        given = self.resolved.params.get(pid)
        if given is not None:
            want = given.checked
            if (
                isinstance(want, dict) and "shown" not in want and type(want.get("value")) in (int, float, bool)
            ):  # a label resolves to its value
                return want["value"]
            return given.written if type(given.written) in (int, float, bool) else None
        p = next((p for p in self.info["parameters"] if p["parameterId"] == pid), None)
        return None if self.exists or p is None else p.get("default")


def _device_phase(f: _Facts, rule: dict[str, Any] | None) -> str | None:
    if rule and rule["parameter"] in f.ids and f.wired_in and f.value(rule["parameter"]) is True:
        return str(rule["message"])
    return None


async def _keytrack(f: _Facts, rule: dict[str, Any] | None, is_poly: IsPoly) -> str | None:
    if not rule or type(f.ids.get(rule["parameter"], {}).get("default")) is not float:
        return None
    value = f.value(rule["parameter"])
    if type(value) in (int, float) and value > 0 and await is_poly():
        return str(rule["message"].format(value=value))
    return None


def _unconnected(f: _Facts, rule: dict[str, Any] | None) -> str | None:
    if (
        rule
        and f.resolved.type_name not in rule["skipTypes"]
        and (f.info["inputs"] or f.info["outputs"])
        and not f.wired_in
        and not f.wired_out
    ):
        return str(rule["message"])
    return None


def _dead_end(f: _Facts, rule: dict[str, Any] | None) -> str | None:
    if rule and f.resolved.type_name in rule["types"] and f.wired_in and not f.wired_out:
        return str(rule["message"])
    return None


def _retrigger_off(f: _Facts, rule: dict[str, Any] | None) -> str | None:
    if (
        rule
        and f.resolved.type_name in rule["types"]
        and (f.name, rule["gatePort"]) in f.ins
        and rule["parameter"] in f.ids
        and f.value(rule["parameter"]) is False
    ):
        return str(rule["message"])
    return None


def _unit_ignores_time(f: _Facts, rule: dict[str, Any] | None) -> str | None:
    """TIME written while UNIT is a note value."""
    if (
        not rule
        or rule["timeParameter"] not in f.ids
        or rule["unitParameter"] not in f.ids
        or rule["timeParameter"] not in f.resolved.params
    ):
        return None
    options = {o["value"]: o["label"] for o in f.ids[rule["unitParameter"]].get("options") or []}
    label = options.get(f.value(rule["unitParameter"]))
    if label is None or label.casefold() == rule["secondsLabel"].casefold():
        return None
    return str(rule["message"].format(unit=label))


class _RawScaleRules(NamedTuple):
    skip_time: bool
    non_linear: dict[str, Any] | None
    tiny: dict[str, Any] | None
    context: dict[str, Any]


def _scale_message(template: str, pid: str, raw: float, scale: Any) -> str:
    return str(
        template.format(parameter=pid, raw=raw, effective=raw_scales.effective(scale, raw), example=scale.example)
    )


def _raw_scale_message(f: _Facts, pid: str, rules: _RawScaleRules) -> str | None:
    spec = f.resolved.params[pid]
    want, raw = spec.checked, spec.written
    if (
        (rules.skip_time and pid == "TIME")
        or type(raw) not in (int, float)
        or not want
        or "shown" in want
        or "display" in want
    ):
        return None
    found = raw_scales.scale_for(f.resolved.type_name, pid, rules.context)
    tiny, rule = rules.tiny, rules.non_linear
    if (
        tiny
        and found is not None
        and pid == tiny["parameter"]
        and found[1].name == tiny["scale"]
        and 0 < abs(raw) < tiny["below"]
    ):
        return _scale_message(tiny["message"], pid, raw, found[1])
    if not rule or found is None or found[1].name not in rule["scales"] or raw == f.ids[pid].get("default"):
        return None
    return _scale_message(rule["message"], pid, raw, found[1])


def _raw_scale_warnings(f: _Facts, skip_time: bool, rules: dict[str, Any]) -> list[str]:
    """A raw number written to a non-linear scale rarely means what its display shows."""
    scales = _RawScaleRules(
        skip_time,
        rules.get("rawNonLinear"),
        rules.get("rawTiny"),
        enum_context(f.spec_params, f.resolved.info),
    )
    if not scales.non_linear and not scales.tiny:
        return []
    return [m for pid in f.resolved.params if (m := _raw_scale_message(f, pid, scales))]


async def _module_warnings(f: _Facts, rules: dict[str, Any], is_poly: IsPoly) -> list[str]:
    ignored_time = _unit_ignores_time(f, rules.get("unitIgnoresTime"))
    messages = [
        _device_phase(f, rules.get("devicePhase")),
        await _keytrack(f, rules.get("keytrack"), is_poly),
        _unconnected(f, rules.get("unconnected")),
        _dead_end(f, rules.get("deadEnd")),
        _retrigger_off(f, rules.get("retriggerOff")),
        ignored_time,
        *_raw_scale_warnings(f, ignored_time is not None, rules),
    ]
    return [f"{f.name}: {m}" for m in messages if m]


async def trap_warnings(spec: Spec, existing: Collection[str], graph: Graph, is_poly: IsPoly) -> list[str]:
    """Measured traps (grid_notes rules): ignored phase cable, filter keytrack in a Poly Grid, dangling modules."""
    rules = measured_notes().get("rules", {})
    label = {r["instanceId"]: r["customName"] for r in graph.rows}
    ins = {(e.dst, e.dst_port) for e in spec.edges} | {
        (label.get(c["targetModuleId"]), c["targetPortKey"]) for c in graph.cables
    }
    outs = {(e.src, e.src_port) for e in spec.edges} | {
        (label.get(c["sourceModuleId"]), c["sourcePortKey"]) for c in graph.cables
    }
    out: list[str] = []
    for name in spec.names:
        resolved = spec.resolved[name]
        if resolved.info is None:
            continue
        facts = _Facts(
            name=name,
            resolved=resolved,
            info=resolved.info,
            spec_params=spec.by_spec[name].get("params") or {},
            exists=name in existing,
            ids={p["parameterId"]: p for p in resolved.info["parameters"]},
            wired_in=any(n == name for n, _ in ins),
            wired_out=any(n == name for n, _ in outs),
            ins=ins,
        )
        out += await _module_warnings(facts, rules, is_poly)
    return out
