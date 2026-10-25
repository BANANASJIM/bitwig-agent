"""MCP stdio adapter over the shared domain catalog and loopback client.

Public SDK APIs: https://github.com/modelcontextprotocol/python-sdk/tree/v1.29.1
Importing this module does not create a server or configure any global client.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import CallToolResult, TextContent, Tool, ToolAnnotations

from bitwig_agent.catalog import TOOLS, validate_call
from bitwig_agent.client import BridgeClient, BridgeError, configured_port


def create_server(client: BridgeClient | None = None) -> Server:
    bridge = client if client is not None else BridgeClient(configured_port())
    server = Server("Bitwig Agent", version="0.1.1", instructions="Discover capabilities and tracks.list first. Our indices are zero-based. Use exact discovered targets and inspect bounded windows and verification. Timeouts are not cancellation; reconcile unknown outcomes before another write. No automatic retries. The agent may use Computer Use for API gaps; never blindly repeat an uncertain write through the GUI.")

    @server.list_tools()
    async def list_tools() -> list[Tool]:
        return [
            Tool(name=name, description=spec.description + " Example arguments: " + json.dumps(spec.examples[0], ensure_ascii=False),
                 inputSchema=spec.model.model_json_schema(),
                 annotations=ToolAnnotations(readOnlyHint=spec.read_only, destructiveHint=not spec.read_only, idempotentHint=False))
            for name, spec in TOOLS.items()
        ]

    # The shared strict validator also enforces cross-field host invariants.
    # Handle its errors ourselves so MCP and CLI both return JSON envelopes.
    @server.call_tool(validate_input=False)
    async def call_tool(name: str, arguments: dict[str, Any]) -> CallToolResult:
        try:
            params, timeout_ms = validate_call(name, arguments)
            payload = {"result": await bridge.call(name, params, timeout_ms)}
            failed = False
        except BridgeError as exc:
            payload = {"error": exc.error}
            failed = True
        except (ValueError, TypeError) as exc:
            payload = {"error": {"code": "invalid_params", "message": str(exc), "outcome": "not_dispatched"}}
            failed = True
        return CallToolResult(content=[TextContent(type="text", text=json.dumps(payload, allow_nan=False, separators=(",", ":")))], structuredContent=payload, isError=failed)

    return server


async def run() -> None:
    server = create_server()
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


def main() -> None:
    asyncio.run(run())
