"""What a real grid.apply would certainly be refused for, knowable before writing anything (dryRun's `wouldFail`)."""

from __future__ import annotations

from bitwig_agent.grid.channel import Channel
from bitwig_agent.grid.plan import Plan
from bitwig_agent.grid.safety import blocking
from bitwig_agent.grid.spec import Spec
from bitwig_agent.grid.wiring import WiringPlan

Refusal = dict[str, str]


async def _transport_blocks(channel: Channel, structural: bool) -> list[Refusal]:
    try:
        state = await channel.client.call("transport.get", {}, channel.timeout_ms)
    except Exception:  # noqa: BLE001 - preflight is advisory
        return []
    busy = blocking(state, structural)
    if not busy:
        return []
    fix = "stop playback and turn them off" if "playing" in busy else "turn them off"
    return [
        {
            "reason": "unsafe_state",
            "message": f"{', '.join(busy)} blocks this write; {fix} with transport.set, then retry",
        }
    ]


def unverified_displays(spec: Spec, plan: Plan) -> list[str]:
    """Display writes the host converts itself: it may refuse them, which is a warning, not a certain failure."""
    return [
        f"{name}.{w['parameterId']}: the host converts the display {w['to']['display']!r} itself and may refuse it (unsupported_unit); write the raw value to be certain"
        for name in spec.names
        for w in plan.params[name][0]
        if isinstance(w.get("to"), dict) and "display" in w["to"] and "rawValue" not in w
    ]


async def preflight(channel: Channel, spec: Spec, plan: Plan, wiring: WiringPlan) -> list[Refusal]:
    # pre_moves is every move of an existing module (grid.modules.move), so a move-only spec is structural too
    structural = bool(plan.inserts or plan.deletes or plan.to_name_first or plan.pre_moves or wiring.edits)
    writes = structural or bool(plan.voicing.changes or any(plan.params[n][0] for n in spec.names))
    return await _transport_blocks(channel, structural) if writes else []
