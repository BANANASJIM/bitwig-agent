"""What a real grid.apply would be refused for, knowable before writing anything (dryRun's `wouldFail`)."""

from __future__ import annotations

from bitwig_agent.grid.channel import Channel
from bitwig_agent.grid.plan import Plan
from bitwig_agent.grid.spec import Spec
from bitwig_agent.grid.wiring import WiringPlan

Refusal = dict[str, str]


async def _transport_blocks(channel: Channel) -> list[Refusal]:
    try:
        state = await channel.client.call("transport.get", {}, channel.timeout_ms)
    except Exception:  # noqa: BLE001 - preflight is advisory
        return []
    busy = [k for k in ("playing", "recording") if isinstance(state, dict) and state.get(k) is True]
    if not busy:
        return []
    return [
        {
            "code": "unsafe_state",
            "message": f"transport is {' and '.join(busy)}; Grid writes need it stopped (transport.set playing=false)",
        }
    ]


def _unverified_displays(spec: Spec, plan: Plan) -> list[Refusal]:
    return [
        {
            "code": "display_unverified",
            "message": f"{name}.{w['parameterId']}: the host converts the display {w['to']['display']!r} itself and may refuse it (unsupported_unit); write the raw value to be certain",
        }
        for name in spec.names
        for w in plan.params[name][0]
        if isinstance(w.get("to"), dict) and "display" in w["to"] and "rawValue" not in w
    ]


async def preflight(channel: Channel, spec: Spec, plan: Plan, wiring: WiringPlan) -> list[Refusal]:
    writes = bool(
        plan.inserts
        or plan.deletes
        or plan.name_existing
        or plan.pre_moves
        or plan.voicing.changes
        or wiring.edits
        or any(plan.params[n][0] for n in spec.names)
    )
    return [*(await _transport_blocks(channel) if writes else []), *_unverified_displays(spec, plan)]
