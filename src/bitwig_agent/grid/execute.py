"""The writes of a planned grid.apply, phase by phase, each a guarded call that stops the run on an unclean result."""

from __future__ import annotations

import time
from typing import Any

from bitwig_agent.errors import bridge_error
from bitwig_agent.grid.channel import Channel, Stop, verify
from bitwig_agent.grid.layout import Cell, has_rect, order_moves, row_rect, size_of
from bitwig_agent.grid.params import plan_params
from bitwig_agent.grid.plan import Plan
from bitwig_agent.grid.problems import SpecError
from bitwig_agent.grid.read import label_of, read_graph, read_params
from bitwig_agent.grid.report import Ledger
from bitwig_agent.grid.spec import Spec
from bitwig_agent.grid.wiring import check_live_ports, plan_connections
from bitwig_agent.protocol.limits import LONG_TIMEOUT_MS

INSERT_BATCH, PARAM_BATCH, CONNECTION_BATCH = 16, 64, 32
# Adaptive insert batches: size each call to take at most this share of the topology timeout at the measured
# per-entry time, and start halved on graphs this large (each entry's verification reads the whole graph).
INSERT_BATCH_BUDGET = 0.25
LARGE_GRAPH_MODULES = 128
monotonic = time.monotonic  # replaceable by tests


def next_insert_batch(size: int, elapsed_s: float, result: dict[str, Any]) -> int:
    """Next insert batch size from the measured per-entry time; a host stop at its deadline margin also shrinks it."""
    ran = max(1, sum(1 for e in result.get("modules", []) if e.get("status") != "not_dispatched"))
    per_entry = max(elapsed_s / ran, result.get("slowestEntryMs", 0) / 1000)
    fit = INSERT_BATCH if per_entry <= 0 else int(LONG_TIMEOUT_MS / 1000 * INSERT_BATCH_BUDGET / per_entry)
    if result.get("stopReason") == "deadline_margin":
        fit = min(fit, size // 2 or 1)
    return max(1, min(INSERT_BATCH, fit))


def _invalid_stop(exc: SpecError) -> Stop:
    return Stop(
        "grid.get", {"error": bridge_error("invalid_spec", "; ".join(exc.problems), "not_applied").error}, "not_applied"
    )


def _stale_stop(message: str) -> Stop:
    return Stop("grid.get", {"error": bridge_error("stale_context", message, "not_applied").error}, "not_applied")


class Executor:
    def __init__(self, channel: Channel, ledger: Ledger, spec: Spec, plan: Plan):
        self.channel, self.ledger, self.spec, self.plan = channel, ledger, spec, plan
        self.clock = monotonic

    async def run(self) -> None:
        await self._voicing()
        await self._delete()
        await self._name_existing()
        await self._premove()
        await self._insert()
        if self.plan.inserts:  # only insertion can displace modules; otherwise the pre-moves were exact
            await self._settle_moves()
        await self._params()
        await self._wire()

    async def _write(self, method: str, params: dict[str, Any], key: str | None = None) -> dict[str, Any]:
        result = await self.channel.call(method, params)
        verify(method, result, key)
        return result

    async def _voicing(self) -> None:
        self.ledger.phase = "voicing"
        voicing = self.plan.voicing
        if not voicing.changes:
            return
        result = await self._write("grid.voicing.set", {"expected": voicing.expected, "set": voicing.changes})
        self.ledger.applied["voicingFields"] += len(voicing.changes)
        self.ledger.steps.append({"op": "voicing", "fields": sorted(voicing.changes)})
        if result.get("changedUnrequested"):
            self.ledger.warnings.append(f"voicing: the native change also moved {result['changedUnrequested']}")

    async def _delete(self) -> None:
        self.ledger.phase = "delete"
        for row in self.plan.deletes:
            await self._write("grid.modules.delete", {"moduleId": row["instanceId"]})
            self.ledger.applied["deleted"] += 1
            self.ledger.pending["deleted"] -= 1
            name = label_of(row)
            self.ledger.steps.append(
                {"op": "delete", "name": name, **({"replace": True} if name in self.plan.replaced_names else {})}
            )

    async def _rename(self, name: str, expected: str) -> None:
        module_id = self.ledger.ids[name]
        await self._write(
            "grid.modules.rename", {"moduleId": module_id, "expectedCustomName": expected, "customName": name}
        )
        self.ledger.applied["renamed"] += 1
        self.ledger.unnamed.pop(module_id, None)
        self.ledger.steps.append({"op": "rename", "name": name})

    async def _name_existing(self) -> None:
        self.ledger.phase = "rename"
        for name in self.plan.name_existing:
            await self._rename(name, "")

    async def _move_to(self, name: str, pos: dict[str, Cell], at: Cell) -> None:
        module_id = self.ledger.ids[name]
        result = await self._write(
            "grid.modules.move",
            {
                "moduleId": module_id,
                "expectedX": pos[module_id][0],
                "expectedY": pos[module_id][1],
                "x": at[0],
                "y": at[1],
            },
        )
        self.ledger.applied["moved"] += 1
        for m in result.get("modulesMoved", []):
            pos[m["moduleId"]] = (m["to"]["x"], m["to"]["y"])
        actual = result.get("actualPosition")
        pos[module_id] = (actual["x"], actual["y"]) if actual else at
        self.ledger.steps.append({"op": "move", "name": name, "to": list(pos[module_id])})

    async def _premove(self) -> None:
        self.ledger.phase = "premove"
        pos = {self.ledger.ids[n]: p for n, p in self.plan.current.items()}
        for name, at in self.plan.pre_moves:
            await self._move_to(name, pos, at)

    async def _insert(self) -> None:
        queue = list(self.plan.inserts)
        size = INSERT_BATCH if len(self.plan.graph.rows) < LARGE_GRAPH_MODULES else INSERT_BATCH // 2
        while queue:
            self.ledger.phase = "insert"
            chunk = queue[:size]
            started = self.clock()
            result = await self.channel.call(
                "grid.modules.insert",
                {"modules": [self._insert_entry(n) for n in chunk]},
            )
            size = next_insert_batch(size, self.clock() - started, result)
            fresh = self._record_inserted(chunk, result)
            await self._name_inserted(fresh, result)
            self.ledger.phase = "insert"
            cut = self._check_insert(chunk, fresh, result)
            queue = queue[len(fresh) :] if cut else queue[len(chunk) :]

    def _insert_entry(self, name: str) -> dict[str, Any]:
        x, y = self.spec.position(name)
        return {"typeId": self.spec.resolved[name].type_id, "x": x, "y": y}

    def _record_inserted(self, chunk: list[str], result: dict[str, Any]) -> list[str]:
        fresh = []
        for name, entry in zip(chunk, result.get("modules", []), strict=False):
            if entry.get("status") == "applied" and entry.get("moduleId"):
                self.ledger.ids[name] = entry["moduleId"]
                self.ledger.unnamed[entry["moduleId"]] = name
                self.ledger.applied["inserted"] += 1
                self.ledger.pending["inserted"] -= 1
                fresh.append(name)
        self.ledger.steps.append({"op": "insert", "count": len(chunk), "status": result.get("batchStatus")})
        return fresh

    async def _name_inserted(self, fresh: list[str], result: dict[str, Any]) -> None:
        """Name what landed before anything can stop the run, or a re-apply would insert duplicates."""
        entries = result.get("modules", [])
        unsettled = (
            result.get("batchStatus") == "unknown"
            or result.get("completion") == "unknown"
            or any(e.get("status") == "unknown" for e in entries)
        )
        if unsettled:
            return
        self.ledger.phase = "rename"
        added = {r["instanceId"]: r for r in result.get("modulesAdded", [])}
        for name in fresh:
            await self._rename(name, added.get(self.ledger.ids[name], {}).get("customName") or "")

    def _check_insert(self, chunk: list[str], fresh: list[str], result: dict[str, Any]) -> bool:
        """Verify the batch; True when it was cut short only by the host deadline margin (applied entries, then not_dispatched), which just continues."""
        entries = result.get("modules", [])
        if len(entries) != len(chunk):
            raise Stop("grid.modules.insert", result, "unknown")
        cut = (
            result.get("batchStatus") == "partial"
            and result.get("status") in (None, "observed")
            and bool(fresh)
            and all(e.get("status") in ("applied", "not_dispatched") for e in entries)
        )
        if not cut:
            verify("grid.modules.insert", result, "modules")
        return cut

    async def _settle_moves(self) -> None:
        self.ledger.phase = "move"
        ids, spec, layout = self.ledger.ids, self.spec, self.plan.layout
        by_id = {r["instanceId"]: r for r in (await self.channel.read(read_graph)).rows}
        for n in spec.names:
            if ids[n] not in by_id:
                raise _stale_stop(f"module {n!r} is missing after insertion")
        pos = {i: (r["x"], r["y"]) for i, r in by_id.items()}
        for n in spec.names:
            if n not in layout.size and (found := size_of(by_id[ids[n]])) is not None:
                layout.size[n] = found
        managed = {ids[n] for n in spec.names}
        fixed = [row_rect(r) for i, r in by_id.items() if i not in managed and has_rect(r)]
        try:
            moves = order_moves(
                {n: pos[ids[n]] for n in spec.names}, {n: spec.position(n) for n in spec.names}, layout.size, fixed
            )
        except SpecError as exc:
            raise _invalid_stop(exc) from exc
        for name, at in moves:
            await self._move_to(name, pos, at)

    async def _param_writes(self) -> list[dict[str, Any]]:
        spec, ids = self.spec, self.ledger.ids
        with_params = [n for n in spec.names if spec.resolved[n].params]
        rows = (
            await self.channel.read(read_params, [ids[n] for n in with_params], full=spec.has_steps, require=True)
            if self.channel.topo_written  # else nothing structural changed since the validation read: reuse its CAS values
            else None
        )
        writes: list[dict[str, Any]] = []
        try:
            for n in with_params:
                got, matched = self.plan.params[n] if rows is None else plan_params(n, spec.resolved[n], rows[ids[n]])
                self.ledger.matched["params"] += matched
                writes += [w["write"] for w in got]
        except SpecError as exc:
            raise _invalid_stop(exc) from exc
        return writes

    async def _params(self) -> None:
        self.ledger.phase = "params"
        writes = await self._param_writes()
        for i in range(0, len(writes), PARAM_BATCH):
            chunk = writes[i : i + PARAM_BATCH]
            result = await self.channel.call("grid.parameters.set", {"writes": chunk})
            entries = result.get("writes", [])
            self.ledger.applied["paramsWritten"] += sum(1 for e in entries if e.get("status") == "applied")
            self.ledger.matched["params"] += sum(1 for e in entries if e.get("status") == "already_matched")
            self.ledger.warnings += [
                f"{e['parameterId']}: displayMismatch {e['displayMismatch']}"
                for e in entries
                if e.get("displayMismatch")
            ]
            self.ledger.steps.append({"op": "params", "count": len(chunk), "status": result.get("batchStatus")})
            verify("grid.parameters.set", result, "writes")

    async def _wire(self) -> None:
        self.ledger.phase = "connections"
        spec, plan, ids = self.spec, self.plan, self.ledger.ids
        graph = await self.channel.read(read_graph) if self.channel.topo_written else plan.graph
        by_id = {r["instanceId"]: r for r in graph.rows}
        by_name = {n: by_id[ids[n]] for n in spec.names if ids[n] in by_id}
        if len(by_name) != len(spec.names):
            raise _stale_stop("a declared module vanished before wiring")
        external = {n: by_id[r["instanceId"]] for n, r in plan.ext_rows.items() if r["instanceId"] in by_id}
        if len(external) != len(plan.ext_rows):
            raise _stale_stop("an external module vanished before wiring")
        rows = {**by_name, **external}
        try:
            if spec.live_schema:
                check_live_ports(spec, rows)
        except SpecError as exc:
            raise _invalid_stop(exc) from exc
        wiring = plan_connections(spec, rows, graph.cables, plan.gone_ids, plan.graph.rows)
        self.ledger.absorb(wiring)
        for n, row in by_name.items():
            if (row["x"], row["y"]) != spec.position(n):
                self.ledger.warnings.append(
                    f"{n}: at {[row['x'], row['y']]}, wanted {list(spec.position(n))} (native reflow or collision)"
                )
        for i in range(0, len(wiring.edits), CONNECTION_BATCH):
            chunk = wiring.edits[i : i + CONNECTION_BATCH]
            result = await self.channel.call("grid.connections.set", {"connections": [e["write"] for e in chunk]})
            entries = result.get("connections", [])
            done = [e for e, r in zip(chunk, entries, strict=False) if r.get("status") == "applied"]
            self.ledger.applied["connections"] += sum(1 for e in done if e["from"])
            self.ledger.applied["disconnected"] += sum(1 for e in done if not e["from"])
            self.ledger.matched["connections"] += sum(1 for e in entries if e.get("status") == "already_matched")
            self.ledger.steps.append({"op": "connections", "count": len(chunk), "status": result.get("batchStatus")})
            verify("grid.connections.set", result, "connections")
