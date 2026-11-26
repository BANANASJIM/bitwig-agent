"""Parameter writes of one spec module planned against its live row."""

from __future__ import annotations

from typing import Any, cast

from bitwig_agent.grid import raw_scales
from bitwig_agent.grid.display import DisplayBasis, check_display, display_want
from bitwig_agent.grid.problems import SpecError, hint
from bitwig_agent.grid.spec import ParamSpec, Resolved
from bitwig_agent.grid.values import check_value, f32, value_matches


def plan_steps(
    name: str, row: dict[str, Any], pid: str, lp: dict[str, Any], wanted: list[float]
) -> dict[str, Any] | None:
    """CAS write for a step array against the live row, or None when the live prefix already holds the values (float32 compare)."""
    if lp.get("kind") != "steps":
        raise SpecError([f"{name!r}: {pid} is {lp.get('kind')} live, not a step array"])
    n = len(wanted)
    if lp.get("arrayLength") is not None and n > lp["arrayLength"]:
        raise SpecError([f"{name!r}: {pid} has {n} steps but the array holds {lp['arrayLength']}"])
    live = lp.get("values") or []
    if len(live) < n:
        raise SpecError([f"{name!r}: the live row of {pid} returned {len(live)} entries, fewer than the {n} wanted"])
    if all(f32(a) == f32(b) for a, b in zip(live, wanted, strict=False)):
        return None
    count = lp.get("stepCount")
    return {
        "moduleId": row["instanceId"],
        "parameterId": pid,
        "expectedKind": "steps",
        "expectedValues": list(live[:n]),
        "values": list(wanted),
        **({"full": True} if count is None or n > count else {}),
    }


def _new_write(name: str, pid: str, spec: ParamSpec) -> dict[str, Any]:
    return {
        "module": name,
        "parameterId": pid,
        "to": spec.written,
        **({"rawValue": spec.checked["value"]} if spec.checked and "shown" in spec.checked else {}),
    }


class _ModulePlanner:
    def __init__(self, name: str, resolved: Resolved, row: dict[str, Any]):
        self.name, self.resolved, self.row = name, resolved, row
        self.live = {
            p["parameterId"]: p for p in row.get("parameters", []) if p.get("supported") and "parameterId" in p
        }
        self.context = {k: v.get("value") for k, v in self.live.items() if v.get("kind") == "enum"}

    def _checked_display(self, pid: str, text: str, lp: dict[str, Any]) -> float:
        try:
            return check_display(pid, text, DisplayBasis.live(lp), self.resolved.type_name, self.context)
        except SpecError as exc:
            raise SpecError([f"{self.name!r}: {p}" for p in exc.problems]) from exc

    def _live_want(self, pid: str, spec: ParamSpec, lp: dict[str, Any]) -> dict[str, Any]:
        want = spec.checked or check_value(pid, spec.written, lp)
        client = raw_scales.is_client(self.resolved.type_name, pid, self.context)
        if "display" in want and (
            lp.get("numericUnit") == "seconds" or client
        ):  # no bundled schema: convert against the live pair
            return display_want("seconds", want["display"], self._checked_display(pid, want["display"], lp))
        return want

    def _check_kind(self, pid: str, lp: dict[str, Any]) -> None:
        info = self.resolved.info
        if info is None:
            return
        known = next(p for p in info["parameters"] if p["parameterId"] == pid)
        if known["kind"] != lp["kind"]:
            raise SpecError([f"{self.name!r}: {pid} is {lp['kind']} live but {known['kind']} in the bundled schema"])

    def _recheck_display(self, pid: str, want: dict[str, Any], lp: dict[str, Any]) -> None:
        recorded = "shown" in want and (self.resolved.type_name, pid) in raw_scales.RAW_SCALES
        if ("display" in want or "shown" in want) and not recorded:
            self._checked_display(pid, cast(str, want.get("display", want.get("shown"))), lp)

    def _live_write(self, pid: str, spec: ParamSpec) -> dict[str, Any] | None:
        """The write for one parameter, or None when the live value already matches."""
        if pid not in self.live:
            raise SpecError(
                [f"{self.name!r}: parameter {pid!r} is not a supported live parameter.{hint(pid, self.live)}"]
            )
        lp = self.live[pid]
        want = self._live_want(pid, spec, lp)
        if "steps" in want:
            write = plan_steps(self.name, self.row, pid, lp, want["steps"])
            return (
                None if write is None else {"module": self.name, "parameterId": pid, "to": spec.written, "write": write}
            )
        self._check_kind(pid, lp)
        if value_matches(lp["kind"], want, lp):
            return None
        self._recheck_display(pid, want, lp)
        return {
            "module": self.name,
            "parameterId": pid,
            "to": spec.written,
            **({"rawValue": want["value"]} if "shown" in want else {}),
            "write": {
                "moduleId": self.row["instanceId"],
                "parameterId": pid,
                "expectedKind": lp["kind"],
                "expectedValue": lp["value"],
                **{k: v for k, v in want.items() if k != "shown"},
            },
        }

    def plan(self) -> tuple[list[dict[str, Any]], int]:
        writes, matched = [], 0
        for pid, spec in self.resolved.params.items():
            write = self._live_write(pid, spec)
            if write is None:
                matched += 1
            else:
                writes.append(write)
        return writes, matched


def plan_params(name: str, resolved: Resolved, row: dict[str, Any] | None) -> tuple[list[dict[str, Any]], int]:
    """Writes for one module against a live row (None: module not inserted yet, everything is a write)."""
    if row is None:
        return [_new_write(name, pid, spec) for pid, spec in resolved.params.items()], 0
    return _ModulePlanner(name, resolved, row).plan()
