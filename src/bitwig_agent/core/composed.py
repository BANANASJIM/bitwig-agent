"""Handlers for protocol.CLIENT_SIDE tools: Python composites over the host methods."""

from __future__ import annotations

import asyncio
from typing import Any

from bitwig_agent import modulator_docs, presets
from bitwig_agent.audio import pipewire
from bitwig_agent.diagnostics import diagnostics
from bitwig_agent.grid import docs as grid_docs
from bitwig_agent.grid.apply import apply as grid_apply
from bitwig_agent.grid.export import export as grid_export
from bitwig_agent.grid.export import graph as grid_graph
from bitwig_agent.grid.probe import probe as grid_probe
from bitwig_agent.grid.render import render as grid_render


async def audio_route(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await asyncio.to_thread(pipewire.route, args)


async def audio_analyze(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await pipewire.analyze(args)


async def grid_describe(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return grid_docs.describe(args)  # offline: reads the local Bitwig install, never the host


async def modulators_catalog(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return modulator_docs.catalog(args)  # offline: the local Bitwig install, never the host


async def modulators_describe(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return modulator_docs.describe(args)


async def presets_search(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await asyncio.to_thread(presets.search, args)  # offline: reads the local library, never the host


HANDLERS = {
    "modulators.catalog": modulators_catalog,
    "modulators.describe": modulators_describe,
    "grid.apply": grid_apply,
    "grid.export": grid_export,
    "grid.graph": grid_graph,
    "grid.probe": grid_probe,
    "grid.describe": grid_describe,
    "grid.render": grid_render,
    "audio.route": audio_route,
    "audio.capture": pipewire.capture,
    "audio.analyze": audio_analyze,
    "diagnostics.get": diagnostics,
    "presets.search": presets_search,
}


async def run(method: str, client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await HANDLERS[method](client, args, timeout_ms)
