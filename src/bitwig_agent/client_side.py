"""Dispatch for catalog.CLIENT_SIDE tools: Python composites over the host methods."""

from __future__ import annotations

import asyncio
from typing import Any

from bitwig_agent import audio
from bitwig_agent.grid_apply import apply as grid_apply
from bitwig_agent.grid_render import render as grid_render


async def audio_route(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await asyncio.to_thread(audio.route, args)


async def audio_analyze(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await audio.analyze(args)


HANDLERS = {"grid.apply": grid_apply, "grid.render": grid_render, "audio.route": audio_route,
            "audio.capture": audio.capture, "audio.analyze": audio_analyze}


async def run(method: str, client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await HANDLERS[method](client, args, timeout_ms)
