"""MCP stdio adapter over the core.

Public SDK APIs: https://github.com/modelcontextprotocol/python-sdk/tree/v2.3.0
Importing this module does not create a server or configure any global client.
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from mcp.server import Server, ServerRequestContext
from mcp.server.stdio import stdio_server
from mcp.shared.exceptions import MCPError
from mcp.types import (
    INVALID_PARAMS,
    CallToolRequestParams,
    CallToolResult,
    ListToolsResult,
    PaginatedRequestParams,
    TextContent,
    Tool,
    ToolAnnotations,
)

from bitwig_agent.core import views
from bitwig_agent.core.invoke import Envelope, Error, ask_help, invoke


def text_result(payload: dict[str, Any], is_error: bool) -> CallToolResult:
    return CallToolResult(
        content=[TextContent(type="text", text=json.dumps(payload, allow_nan=False, separators=(",", ":")))],
        structured_content=payload,
        is_error=is_error,
    )


def envelope_result(envelope: Envelope) -> CallToolResult:
    if isinstance(envelope, Error):
        return text_result(envelope.error, True)
    return text_result(envelope.result, envelope.failed)


async def list_tools(ctx: ServerRequestContext, params: PaginatedRequestParams | None) -> ListToolsResult:
    return ListToolsResult(
        tools=[
            Tool(
                name=view.name,
                description=view.description,
                input_schema=view.input_schema,
                annotations=ToolAnnotations(
                    read_only_hint=view.annotations.read_only,
                    destructive_hint=view.annotations.destructive,
                    idempotent_hint=view.annotations.idempotent,
                    open_world_hint=view.annotations.open_world,
                ),
            )
            for view in views.tools()
        ]
    )


async def call_tool(ctx: ServerRequestContext, params: CallToolRequestParams) -> CallToolResult:
    arguments = params.arguments or {}
    if params.name == views.HELP_NAME:
        return envelope_result(ask_help(arguments.get("topic")))
    if not views.exists(params.name):
        raise MCPError(INVALID_PARAMS, views.unknown_tool_message(params.name))
    return envelope_result(await invoke(params.name, arguments))


def create_server() -> Server:
    return Server(
        "Bitwig Agent",
        version=views.version(),
        instructions=views.instructions(),
        on_list_tools=list_tools,
        on_call_tool=call_tool,
    )


async def run() -> None:
    server = create_server()
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


def main() -> None:
    asyncio.run(run())
