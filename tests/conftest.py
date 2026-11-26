import asyncio
import dataclasses
import json
import os
import sys

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from bitwig_agent.protocol import TOOLS


@pytest.fixture
def anyio_backend():
    return "asyncio"


class MockHost:
    def __init__(self):
        self.requests = []
        self.connections = 0
        self.port = 0
        self.reply = lambda request: {"id": request["id"], "result": {"status": "observed"}}
        self.delay = 0
        self.tasks = set()

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        self.connections += 1
        try:
            request = json.loads(await reader.readline())
            self.requests.append(request)
            await asyncio.sleep(self.delay)
            response = self.reply(request)
            if response is not None:
                writer.write(response if isinstance(response, bytes) else (json.dumps(response) + "\n").encode())
                await writer.drain()
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, OSError):
                pass  # A deliberately malformed/oversized fixture may be disconnected.
            finally:
                self.tasks.discard(task)


@pytest.fixture
async def host():
    host = MockHost()
    server = await asyncio.start_server(host.handle, "127.0.0.1", 0)
    host.port = server.sockets[0].getsockname()[1]
    try:
        async with server:
            yield host
    finally:
        pending = list(host.tasks)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)


SERVER = "from bitwig_agent.frontends.mcp import main; main()"
SERVER_EXPOSING_ALL = (
    "import dataclasses\n"
    "from bitwig_agent.protocol import TOOLS\n"
    "TOOLS.update({name: dataclasses.replace(spec, expose=True) for name, spec in TOOLS.items()})\n" + SERVER
)


@pytest.fixture(autouse=True)
def every_method_exposed(request, monkeypatch):
    """Method tests drive hidden building blocks too; tests of the surface itself ask for real_surface."""
    if "real_surface" in request.fixturenames:
        return
    for name, spec in list(TOOLS.items()):
        monkeypatch.setitem(TOOLS, name, dataclasses.replace(spec, expose=True))


@pytest.fixture
def real_surface():
    """Marks a test that must see the surface exactly as the method table exposes it."""


async def stdio_session(host, code):
    params = StdioServerParameters(
        command=sys.executable, args=["-c", code], env=dict(os.environ, BITWIG_AGENT_PORT=str(host.port))
    )
    async with stdio_client(params) as (read, write), ClientSession(read, write) as session:
        initialized = await session.initialize()
        assert initialized.server_info.name == "Bitwig Agent"
        yield session


@pytest.fixture
async def mcp_session(host):
    async for session in stdio_session(host, SERVER_EXPOSING_ALL):
        yield session


@pytest.fixture
async def real_mcp_session(host, real_surface):
    async for session in stdio_session(host, SERVER):
        yield session
