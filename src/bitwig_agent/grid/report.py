"""The running account of one grid.apply call and the result payloads built from it."""

from __future__ import annotations

from typing import Any

from bitwig_agent.grid.bundled import measured_notes
from bitwig_agent.grid.channel import Stop
from bitwig_agent.grid.plan import Plan
from bitwig_agent.grid.read import label_of
from bitwig_agent.grid.spec import Spec
from bitwig_agent.grid.wiring import WiringPlan

PHASES = ("voicing", "delete", "premove", "insert", "rename", "move", "params", "connections")
APPLIED_COUNTS = ("inserted", "renamed", "moved", "paramsWritten", "connections", "disconnected", "deleted")
MATCHED_COUNTS = ("modules", "positions", "params", "connections")


def notes_field(spec: Spec, extra: list[str] | None = None) -> dict[str, list[str]]:
    data = measured_notes()
    notes = list(extra or [])
    if spec.names:
        used = dict.fromkeys(spec.resolved[n].type_name for n in spec.names)
        notes += [f"{t}: {x}" for t in used for x in data["modules"].get(t, [])] + data["general"]
    return {"notes": notes} if notes else {}


class Ledger:
    """Counters, steps and warnings of a run, and the payloads that report them."""

    def __init__(self, spec: Spec, instances: list[dict[str, Any]]):
        self.spec, self.instances = spec, instances
        self.applied = dict.fromkeys(APPLIED_COUNTS, 0)
        self.matched = dict.fromkeys(MATCHED_COUNTS, 0)
        if spec.voicing:
            self.applied["voicingFields"] = 0
        self.steps: list[dict[str, Any]] = []
        self.ids: dict[str, str] = {}
        self.warnings: list[str] = []
        self.unnamed: dict[str, str] = {}  # inserted modules whose rename has not landed
        self.pending: dict[str, int] = {}  # work not yet dispatched, by kind
        self.phase = "validate"

    def warn(self, text: str) -> None:
        if text not in self.warnings:  # planning repeats (preflight, dry run, report)
            self.warnings.append(text)

    def absorb(self, wiring: WiringPlan) -> None:
        self.matched["connections"] += wiring.matched
        for note in wiring.notes:
            self.warn(note)

    def _payload(self, base: dict[str, Any], notes: dict[str, list[str]]) -> dict[str, Any]:
        return (
            base
            | ({"instances": self.instances} if self.instances else {})
            | ({"warnings": self.warnings} if self.warnings else {})
            | notes
        )

    def report(self, status: str) -> dict[str, Any]:
        base = {
            "status": status,
            "applied": self.applied,
            "alreadyMatched": self.matched,
            "steps": self.steps,
            "moduleIds": self.ids,
        }
        return self._payload(base, notes_field(self.spec))

    def stop_report(self, stop: Stop) -> dict[str, Any]:
        status = "unknown" if stop.outcome == "unknown" else ("partial" if any(self.applied.values()) else "failed")
        out = self.report(status)
        out["failure"] = {"phase": self.phase, "method": stop.method, "response": stop.raw}
        started = PHASES.index(self.phase) + 1 if self.phase in PHASES else 0
        out["notDispatched"] = {
            "phasesNotStarted": list(PHASES[started:]),
            "failedPhaseIncomplete": self.phase,
            **{k: v for k, v in self.pending.items() if v},
        }
        if self.unnamed:
            out["unnamedModules"] = dict(self.unnamed)
        out["next"] = (
            "Outcome unknown: inspect with grid.get before any further write; this device may now be quarantined (grid.capabilities topologyQuarantine.devices): grid.topology.reconcile on it re-verifies and releases it, no restart."
            if status == "unknown"
            else "Fix the cause and re-apply the same spec; applied work is reused."
            + (
                " Modules listed in unnamedModules are not renamed yet: rename them by hand (customName = value) or delete them first."
                if self.unnamed
                else ""
            )
        )
        return out

    def dry_report(self, plan: Plan, wiring: WiringPlan, would_fail: list[dict[str, str]]) -> dict[str, Any]:
        spec = self.spec
        param_plan = [{k: v for k, v in w.items() if k != "write"} for name in spec.names for w in plan.params[name][0]]
        moves, pos = [], dict(plan.current)
        for name, at in plan.pre_moves:
            moves.append({"name": name, "from": list(pos[name]), "to": list(at)})
            pos[name] = at
        edits = wiring.edits
        voicing = plan.voicing
        steps_plan = {
            "deletes": [label_of(r) for r in plan.deletes],
            **({"replaces": plan.replaced_names} if plan.replaced_names else {}),
            **({"droppedConnections": plan.dropped} if plan.dropped else {}),
            "inserts": [
                {"name": n, "type": spec.resolved[n].type_name, "x": spec.position(n)[0], "y": spec.position(n)[1]}
                for n in plan.inserts
            ],
            "renames": [*plan.name_existing, *plan.inserts],
            "moves": moves,
            "params": param_plan,
            "connections": [{k: v for k, v in e.items() if k != "write"} for e in edits],
            **({"voicing": {"set": voicing.changes, "from": voicing.expected}} if spec.voicing else {}),
        }
        planned = {
            "inserted": len(plan.inserts),
            "renamed": len(plan.inserts) + len(plan.name_existing),
            "moved": len(moves),
            "paramsWritten": len(param_plan),
            "connections": sum(1 for e in edits if e["from"]),
            "disconnected": sum(1 for e in edits if not e["from"]),
            "deleted": len(plan.deletes),
            **({"voicingFields": len(voicing.changes)} if spec.voicing else {}),
        }
        notes = (
            [
                "modules already in the device are moved before inserting; inserted modules are only moved if the host reflows them"
            ]
            if plan.inserts
            else []
        )
        if spec.live_schema:
            notes.append(
                "no bundled schema for this host version: ports, parameters and footprints of inserted modules are validated only after insertion"
            )
        result: dict[str, Any] = {
            "status": "dryRun",
            "planned": planned,
            "alreadyMatched": self.matched,
            "plan": steps_plan,
            "steps": [],
        }
        result |= {"wouldFail": would_fail} if would_fail else {}
        return self._payload(result, notes_field(spec, notes))

    def unreachable_report(self, error: dict[str, Any]) -> dict[str, Any]:
        """dryRun when the target device cannot be read: the spec itself validated, the real apply would fail on the target."""
        fail = {
            "code": error.get("code", "unknown"),
            "message": error.get("message", "the target device could not be read"),
        }
        return (
            {"status": "dryRun", "validated": True, "wouldFail": [fail], "plan": {}}
            | ({"warnings": self.warnings} if self.warnings else {})
            | notes_field(
                self.spec, ["the spec is valid, but the target device could not be read, so no plan was computed"]
            )
        )
