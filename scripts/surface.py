#!/usr/bin/env python3
"""Print the sizes an MCP agent reads: tools/list as serialized, instructions, and the skill entry file.

A reported number for PRs that change the tool surface, not a gate. Needs no Bitwig.
"""

import asyncio
import json
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

SKILL = Path(__file__).resolve().parent.parent / "skills" / "bitwig-agent" / "SKILL.md"


async def measure() -> dict[str, int]:
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
        "skill_md_bytes": SKILL.stat().st_size,
    }


def main() -> None:
    print(json.dumps(asyncio.run(asyncio.wait_for(measure(), timeout=60)), indent=2))


if __name__ == "__main__":
    main()
