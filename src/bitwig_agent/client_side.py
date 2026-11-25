"""Dispatch for catalog.CLIENT_SIDE tools: Python composites over the host methods."""

from __future__ import annotations

from typing import Any

from bitwig_agent.grid_apply import apply as grid_apply
from bitwig_agent.grid_render import render as grid_render

HANDLERS = {"grid.apply": grid_apply, "grid.render": grid_render}


async def run(method: str, client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await HANDLERS[method](client, args, timeout_ms)
