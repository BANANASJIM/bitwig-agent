"""Client-side `grid.apply`: a declarative, re-appliable Grid patch composed from the guarded grid.* host methods.

No host method is added. Every write is an ordinary guarded call with fresh CAS values, one request at a time,
stopping at the first error or non-success outcome and never retrying.
"""

from __future__ import annotations

from typing import Any

from bitwig_agent.diagnostics import diagnostics
from bitwig_agent.errors import BridgeError, bridge_error, unknown_result
from bitwig_agent.grid.blocks import expand_blocks
from bitwig_agent.grid.bundled import bundled_schema
from bitwig_agent.grid.channel import Channel, Stop
from bitwig_agent.grid.execute import Executor
from bitwig_agent.grid.partial import resolve_partials
from bitwig_agent.grid.plan import Plan, build_plan, params_to_read, plan_module_params, plan_voicing, unreadable_names
from bitwig_agent.grid.preflight import preflight, unverified_displays
from bitwig_agent.grid.problems import SpecError, invalid_spec
from bitwig_agent.grid.read import Graph, device_target, read_catalog, read_graph, read_params
from bitwig_agent.grid.report import Ledger
from bitwig_agent.grid.spec import Spec, TypeCatalog, parse_spec
from bitwig_agent.grid.warnings import measured_warnings, trap_warnings
from bitwig_agent.grid.wiring import plan_connections
from bitwig_agent.protocol.base import shared_verification
from bitwig_agent.protocol.grid_workflow import is_partial


async def _load_spec(channel: Channel, args: dict[str, Any]) -> tuple[dict[str, Any], Spec]:
    caps = (await channel.call("status", {"detail": "full"}))["grid"]
    schema = bundled_schema(caps.get("hostVersion"))
    if caps.get("hostVersion") == schema["hostVersion"]:
        catalog = TypeCatalog.bundled(schema)
    else:
        catalog = TypeCatalog.live(await channel.read(read_catalog))
    return caps, parse_spec(args, catalog, caps.get("hostVersion"))


class Session:
    """One grid.apply run against one device: validate, plan, then dry-run or write."""

    def __init__(
        self, channel: Channel, caps: dict[str, Any], spec: Spec, instances: list[dict[str, Any]], graph: Graph | None
    ):
        self.channel, self.caps, self.spec, self.graph = channel, caps, spec, graph
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
            graph = self.graph if self.graph is not None else await self.channel.read(read_graph)
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
        ledger.warnings += measured_warnings(spec, plan.existing_by_name)
        ledger.warnings += await trap_warnings(spec, plan.existing_by_name, graph, self._is_poly)
        # Spec-detectable parameter problems of existing modules surface before the first write.
        rows = await self.channel.read(read_params, params_to_read(spec, plan), full=spec.has_steps, require=True)
        plan.params = plan_module_params(spec, plan, rows)
        ledger.unreached = {"deleted": len(plan.deletes), "inserted": len(plan.inserts)}
        return await (self._dry_run(plan) if spec.dry_run else self._write(plan))

    async def _dry_run(self, plan: Plan) -> dict[str, Any]:
        spec, ledger = self.spec, self.ledger
        for name in spec.names:
            ledger.matched["params"] += plan.params[name][1]
        wiring = plan_connections(
            spec, {**plan.existing_by_name, **plan.external_rows}, plan.graph.cables, plan.gone_ids, plan.graph.rows
        )
        ledger.absorb(wiring)
        for note in unverified_displays(spec, plan):
            ledger.warn(note)
        return ledger.dry_report(plan, wiring, await preflight(self.channel, spec, plan, wiring))

    async def _write(self, plan: Plan) -> dict[str, Any]:
        ledger = self.ledger
        try:
            await Executor(self.channel, ledger, self.spec, plan).run()
        except Stop as stop:
            return ledger.stop_report(stop)
        except Exception as exc:  # noqa: BLE001 - keep the report of work already dispatched
            return ledger.stop_report(Stop("internal", unknown_result("internal", f"{type(exc).__name__}: {exc}")))
        applied = any(ledger.applied.values())
        return ledger.report(
            "applied" if applied else "already_matched", shared_verification(self.channel.applied) if applied else None
        )


async def _unreachable(channel: Channel, args: dict[str, Any], error: dict[str, Any]) -> dict[str, Any]:
    """The dry-run report of a device whose partial entries could not be read: the whole entries are planned as they would be."""
    whole = {**args, "modules": [m for m in args.get("modules", []) if not is_partial(m)]}
    whole, instances = expand_blocks(whole)
    _, spec = await _load_spec(channel, whole)
    ledger = Ledger(spec, instances)
    ledger.warnings += measured_warnings(spec, ())
    return ledger.unreachable_report(error)


async def with_marker(client: Any, result: dict[str, Any], wrote: bool, timeout_ms: int) -> dict[str, Any]:
    """After a build, attach a diagnostics.get marker: sinceMarker=<it> shows only rejections that happened after the build."""
    if not wrote:
        return result
    try:
        marker = (await diagnostics(client, {"maxEvents": 1, "sinceSeconds": 1}, timeout_ms)).get("marker")
    except Exception:  # noqa: BLE001 - diagnostics are best effort
        marker = None
    return result | ({"diagnosticsMarker": marker} if marker else {})


async def apply(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    """Run a validated grid.apply spec (dict from the protocol's GridApplyArgs) through `client.call(method, params, timeout_ms)`."""
    channel = Channel(client, device_target(args), timeout_ms)
    try:
        try:
            args, graph = await resolve_partials(channel, args)
        except Stop as stop:  # a dry run reports a target it cannot read instead of failing
            if not (args.get("dryRun") and isinstance(stop.raw.get("error"), dict)):
                raise
            return await _unreachable(channel, args, stop.raw["error"])
        args, instances = expand_blocks(args)
        caps, spec = await _load_spec(channel, args)
        result = await Session(channel, caps, spec, instances, graph).run()
    except SpecError as exc:
        raise invalid_spec(exc.problems) from exc
    except Stop as stop:
        raise BridgeError(stop.raw["error"]) from stop
    except BridgeError:
        raise
    except Exception as exc:
        raise bridge_error("internal", f"{type(exc).__name__}: {exc}") from exc
    return await with_marker(client, result, channel.wrote, timeout_ms)
