#!/usr/bin/env python3
"""Print the sizes an agent reads, in bytes, as served: SKILL.md, the CLI help at every level, the `help` topics and
the serialized MCP tools/list with its instructions.

A reported number for PRs that change the tool surface, not a gate. Needs no Bitwig.
  L0 `bitwig-agent --help`, L1 `bitwig-agent <resource> --help`, L2 `bitwig-agent <resource> <verb> --help`,
  topics `bitwig-agent help <topic>`.
"""

import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from bitwig_agent.core import views
from bitwig_agent.frontends import cli

SKILL = Path(__file__).resolve().parent.parent / "skills" / "bitwig-agent" / "SKILL.md"


def help_bytes(*argv: str) -> int:
    return len(asyncio.run(cli.execute(list(argv))).stdout.encode())


def cli_sizes() -> dict[str, object]:
    methods = views.names()
    return {
        "l0_help_bytes": help_bytes("--help"),
        "l1_help_bytes": {resource: help_bytes(resource, "--help") for resource in cli.resources()},
        "l2_help_bytes": {name: help_bytes(*name.split("."), "--help") for name in methods},
        "help_topic_bytes": {topic: help_bytes("help", topic) for topic in views.help()["topics"]},
    }


async def mcp_sizes() -> dict[str, int]:
    params = StdioServerParameters(
        command=sys.executable, args=["-c", "from bitwig_agent.frontends.mcp import main; main()"]
    )
    async with stdio_client(params) as streams, ClientSession(*streams) as session:
        initialized = await session.initialize()
        listing = await session.list_tools()
    tools = json.dumps(listing.model_dump(mode="json", by_alias=True, exclude_none=True), separators=(",", ":"))
    return {
        "tools": len(listing.tools),
        "tools_list_bytes": len(tools.encode()),
        "instructions_bytes": len((initialized.instructions or "").encode()),
    }


def main() -> None:
    sizes = {"skill_md_bytes": SKILL.stat().st_size, **cli_sizes(), **asyncio.run(asyncio.wait_for(mcp_sizes(), 60))}
    print(json.dumps(sizes, indent=2))


if __name__ == "__main__":
    main()
