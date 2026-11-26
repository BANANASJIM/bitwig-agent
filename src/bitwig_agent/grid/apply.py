"""Client-side `grid.apply`: a declarative, re-appliable Grid patch composed from the guarded grid.* host methods.

No host method is added. Every write is an ordinary guarded call with fresh CAS values, one request at a time,
stopping at the first error/unknown/partial outcome and never retrying.
"""

from __future__ import annotations

from typing import Any

from bitwig_agent.diagnostics import diagnostics
from bitwig_agent.errors import BridgeError, bridge_error
from bitwig_agent.grid.blocks import expand_blocks
from bitwig_agent.grid.bundled import bundled_schema
from bitwig_agent.grid.channel import Channel, Stop
from bitwig_agent.grid.execute import Executor
from bitwig_agent.grid.plan import Plan, build_plan, params_to_read, plan_module_params, plan_voicing, unreadable_names
from bitwig_agent.grid.preflight import preflight
from bitwig_agent.grid.problems import SpecError, invalid_spec
from bitwig_agent.grid.read import read_catalog, read_graph, read_params
from bitwig_agent.grid.report import Ledger
from bitwig_agent.grid.spec import Spec, TypeCatalog, parse_spec
from bitwig_agent.grid.warnings import measured_warnings, trap_warnings
from bitwig_agent.grid.wiring import plan_connections


async def _load_spec(channel: Channel, args: dict[str, Any]) -> tuple[dict[str, Any], Spec]:
    caps = await channel.call("grid.capabilities", {"detail": "full"})
    schema = bundled_schema()
    if caps.get("hostVersion") == schema["hostVersion"]:
        catalog = TypeCatalog.bundled(schema)
    else:
        catalog = TypeCatalog.live(await channel.read(read_catalog))
    return caps, parse_spec(args, catalog)


class Session:
    """One grid.apply run against one device: validate, plan, then dry-run or write."""

    def __init__(self, channel: Channel, caps: dict[str, Any], spec: Spec, instances: list[dict[str, Any]]):
        self.channel, self.caps, self.spec = channel, caps, spec
        self.ledger = Ledger(spec, instances)
        self._poly: bool | None = None

    async def _is_poly(self) -> bool:
        if self._poly is None:
            try:
                self._poly = bool((await self.channel.call("grid.voicing.get", {})).get("supported"))
            except Stop:
                self._poly = False
        return self._poly

    async def run(self) -> dict[str, Any]:
        spec, ledger = self.spec, self.ledger
        try:
            voicing = (
                plan_voicing(spec.voicing, await self.channel.call("grid.voicing.get", {})) if spec.voicing else None
            )
            ledger.phase = "read"
            graph = await self.channel.read(read_graph)
        except Stop as stop:  # a dry run reports a target it cannot read instead of failing
            if spec.dry_run and isinstance(stop.raw.get("error"), dict):
                ledger.warnings += measured_warnings(spec, ())
                return ledger.unreachable_report(stop.raw["error"])
            raise
        problems = unreadable_names(self.caps, graph.rows)
        if problems:
            raise SpecError(problems)
        plan = build_plan(spec, self.caps, graph, spec.dry_run)
        if voicing is not None:
            plan.voicing = voicing
            ledger.matched["voicingFields"] = voicing.matched
        ledger.ids = dict(plan.ids)
        ledger.matched["modules"], ledger.matched["positions"] = plan.matched_modules, plan.matched_positions
        for note in plan.notes:
            ledger.warn(note)
        ledger.warnings += measured_warnings(spec, plan.existing)
        ledger.warnings += await trap_warnings(spec, plan.existing, graph, self._is_poly)
        # Spec-detectable parameter problems of existing modules surface before the first write.
        rows = await self.channel.read(read_params, params_to_read(spec, plan), full=spec.has_steps, require=True)
        plan.params = plan_module_params(spec, plan, rows)
        ledger.pending = {"deleted": len(plan.deletes), "inserted": len(plan.inserts)}
        return await (self._dry_run(plan) if spec.dry_run else self._write(plan))

    async def _dry_run(self, plan: Plan) -> dict[str, Any]:
        spec, ledger = self.spec, self.ledger
        for name in spec.names:
            ledger.matched["params"] += plan.params[name][1]
        wiring = plan_connections(
            spec, {**plan.existing, **plan.ext_rows}, plan.graph.cables, plan.gone_ids, plan.graph.rows
        )
        ledger.absorb(wiring)
        return ledger.dry_report(plan, wiring, await preflight(self.channel, spec, plan, wiring))

    async def _write(self, plan: Plan) -> dict[str, Any]:
        ledger = self.ledger
        try:
            await Executor(self.channel, ledger, self.spec, plan).run()
        except Stop as stop:
            return ledger.stop_report(stop)
        except Exception as exc:  # noqa: BLE001 - keep the report of work already dispatched
            raw = {"error": {"code": "internal_error", "message": f"{type(exc).__name__}: {exc}", "outcome": "unknown"}}
            return ledger.stop_report(Stop("internal", raw, "unknown"))
        return ledger.report("applied" if any(ledger.applied.values()) else "unchanged")


async def with_marker(client: Any, result: dict[str, Any], wrote: bool, timeout_ms: int) -> dict[str, Any]:
    """After a build, attach a host.diagnostics marker: sinceMarker=<it> shows only rejections that happened after the build."""
    if not wrote or result.get("status") == "dryRun":
        return result
    try:
        marker = (await diagnostics(client, {"maxEvents": 1, "sinceSeconds": 1}, timeout_ms)).get("marker")
    except Exception:  # noqa: BLE001 - diagnostics are best effort
        marker = None
    return result | ({"diagnosticsMarker": marker} if marker else {})


def _unwritten(stop: Stop) -> BridgeError:
    """The failure of a run that stopped before writing anything."""
    error = stop.raw.get("error")
    if error is not None:
        return BridgeError(error)
    return BridgeError(
        {
            "code": "not_applied",
            "message": f"{stop.method} returned an unusable result; nothing was written",
            "outcome": "not_dispatched",
            "details": stop.raw,
        }
    )


async def apply(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    """Run a validated grid.apply spec (dict from the protocol's GridApplyArgs) through `client.call(method, params, timeout_ms)`."""
    channel = Channel(client, args["target"], timeout_ms)
    try:
        args, instances = expand_blocks(args)
        caps, spec = await _load_spec(channel, args)
        result = await Session(channel, caps, spec, instances).run()
    except SpecError as exc:
        raise invalid_spec(exc.problems) from exc
    except Stop as stop:
        raise _unwritten(stop) from stop
    except BridgeError:
        raise
    except Exception as exc:
        raise bridge_error("internal_error", f"{type(exc).__name__}: {exc}") from exc
    return await with_marker(client, result, channel.wrote, timeout_ms)
