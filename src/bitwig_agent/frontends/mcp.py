"""MCP stdio adapter over the core.

Public SDK APIs: https://github.com/modelcontextprotocol/python-sdk/tree/v2.3.0
Importing this module does not create a server or configure any global client.
"""

from __future__ import annotations

import asyncio
import json

from mcp.server import Server, ServerRequestContext
from mcp.server.stdio import stdio_server
from mcp.types import (
    CallToolRequestParams,
    CallToolResult,
    ListToolsResult,
    PaginatedRequestParams,
    TextContent,
    Tool,
    ToolAnnotations,
)

from bitwig_agent.core import views
from bitwig_agent.core.invoke import Error, invoke


def create_server() -> Server:
    async def list_tools(ctx: ServerRequestContext, params: PaginatedRequestParams | None) -> ListToolsResult:
        return ListToolsResult(
            tools=[
                Tool(
                    name=view.name,
                    description=view.description
                    + " Example arguments: "
                    + json.dumps(view.example, ensure_ascii=False),
                    input_schema=view.input_schema,
                    annotations=ToolAnnotations(
                        read_only_hint=view.read_only, destructive_hint=not view.read_only, idempotent_hint=False
                    ),
                )
                for view in views.tools()
            ]
        )

    async def call_tool(ctx: ServerRequestContext, params: CallToolRequestParams) -> CallToolResult:
        envelope = await invoke(params.name, params.arguments or {})
        return CallToolResult(
            content=[
                TextContent(type="text", text=json.dumps(envelope.payload, allow_nan=False, separators=(",", ":")))
            ],
            structured_content=envelope.payload,
            is_error=isinstance(envelope, Error),
        )

    return Server(
        "Bitwig Agent",
        version=views.version(),
        instructions="Discover capabilities and tracks.list first. Our indices are zero-based. Use exact discovered targets and inspect bounded windows and verification. Timeouts are not cancellation; reconcile unknown outcomes before another write. No automatic retries. The agent may use Computer Use for API gaps; never blindly repeat an uncertain write through the GUI.",
        on_list_tools=list_tools,
        on_call_tool=call_tool,
    )


async def run() -> None:
    server = create_server()
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


def main() -> None:
    asyncio.run(run())
