"""Partial grid.apply entries: {name, params} set the params of the live module of that name."""

from __future__ import annotations

from typing import Any

from bitwig_agent.grid.channel import Channel
from bitwig_agent.grid.problems import SpecError, near
from bitwig_agent.grid.read import Graph, label_of, read_graph
from bitwig_agent.protocol.grid_workflow import is_partial


def _why(found: list[dict[str, Any]]) -> str:
    if not found:
        return "is not in the device"
    return "matches several modules" if len(found) > 1 else "has no readable type"


async def resolve_partials(channel: Channel, args: dict[str, Any]) -> tuple[dict[str, Any], Graph | None]:
    """The args with every partial entry made whole from the live module it names (its type and position stay), and the graph read for it."""
    modules: list[dict[str, Any]] = args.get("modules") or []
    if not any(is_partial(m) for m in modules):
        return args, None
    by_name: dict[str, list[dict[str, Any]]] = {}
    graph = await channel.read(read_graph)
    for row in graph.rows:
        by_name.setdefault(label_of(row), []).append(row)
    problems: list[str] = []
    whole: list[dict[str, Any]] = []
    for module in modules:
        if not is_partial(module):
            whole.append(module)
            continue
        found = by_name.get(module["name"], [])
        if len(found) != 1 or not isinstance(found[0].get("typeId"), str):
            problems.append(f"partial entry {module['name']!r} {_why(found)}.{near(module['name'], by_name)}")
            continue
        row = found[0]
        whole.append({**module, "typeId": row["typeId"], "x": row["x"], "y": row["y"]})
    if problems:
        raise SpecError(problems)
    return {**args, "modules": whole}, graph
