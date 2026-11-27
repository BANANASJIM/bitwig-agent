#!/usr/bin/env python3
"""Smoke an installed package's offline CLI and real MCP discovery, not Bitwig.

Run with the consumer environment's Python, preferably with -I. All entry points
run outside the checkout; no extension download, installation, or host calls.
"""

import argparse
import asyncio
import json
import subprocess
import sys
import tempfile
from importlib.metadata import version
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from bitwig_agent.grid.bundled import bundled_schema, host_version, shared


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-version", help="Require this installed release version")
    args = parser.parse_args()
    installed_version = version("bitwig-agent")
    if args.expected_version is not None and installed_version != args.expected_version:
        raise RuntimeError(f"Expected {args.expected_version}, installed {installed_version}")
    if bundled_schema()["hostVersion"] != host_version() or not shared("display")["units"]:
        raise RuntimeError("Bundled shared data does not match the host target")
    executables = Path(sys.executable).parent
    installer = executables / "bitwig-agent-install"

    with tempfile.TemporaryDirectory() as cwd:
        subprocess.run([str(executables / "bitwig-agent"), "--help"], cwd=cwd, check=True, timeout=30)
        subprocess.run([str(installer), "--version"], cwd=cwd, check=True, timeout=30)
        config = json.loads(
            subprocess.check_output([str(installer), "--mcp-config"], cwd=cwd, text=True, encoding="utf-8", timeout=30)
        )["mcpServers"]["bitwig-agent"]

        async def check() -> None:
            async with (
                stdio_client(StdioServerParameters(**config, cwd=cwd)) as streams,
                ClientSession(*streams) as session,
            ):
                initialized = await session.initialize()
                if initialized.server_info.version != installed_version:
                    raise RuntimeError(f"MCP version mismatch: {initialized.server_info.version}")
                tools = await session.list_tools()
                names = {tool.name for tool in tools.tools}
                if not {"tracks.list", "notes.set"} <= names:
                    raise RuntimeError(f"Missing expected MCP tools: {names}")
                print(
                    f"{initialized.server_info.name} {installed_version}: "
                    f"{len(names)} tools via installed configuration; "
                    f"MCP SDK {version('mcp')}; no native-host calls"
                )

        asyncio.run(asyncio.wait_for(check(), timeout=30))


if __name__ == "__main__":
    main()
