import asyncio
import json
import os
import sys

import pytest
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


@pytest.fixture
def anyio_backend():
    return "asyncio"


class MockHost:
    def __init__(self):
        self.requests = []
        self.connections = 0
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


@pytest.fixture
async def mcp_session(host):
    params = StdioServerParameters(
        command=sys.executable,
        args=["-c", "from bitwig_agent.server import main; main()"],
        env=dict(os.environ, BITWIG_AGENT_PORT=str(host.port)),
    )
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            initialized = await session.initialize()
            assert initialized.server_info.name == "Bitwig Agent"
            yield session

