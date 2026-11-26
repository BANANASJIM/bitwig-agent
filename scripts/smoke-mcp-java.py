#!/usr/bin/env python3
"""Real MCP stdio/client/Java transport + registry validation, with a fixture handler.

Uses BITWIG_API_JAR when set, else the pinned public extension API; requires a JDK. This exercises no Bitwig instance and cannot
prove edits, observer delivery, audio, UI, or persistence. Run with check.py.
"""

import asyncio
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import vector_parity
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from tooling import host_paths

from bitwig_agent.protocol import CLIENT_SIDE, TOOLS

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("extension_build", ROOT / "scripts/build.py")
assert spec is not None
assert spec.loader is not None
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

TEST_ROOT = ROOT / "extension/src/test/java"
FIXTURE_SOURCES = (
    "HostFixture.java",
    "FakeHost.java",
    "ScheduledTasks.java",
    "TestServices.java",
    "device/FakeChains.java",
)
FIXTURE = """
package dev.bitwigagent;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.MethodSpecs;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.rpc.RpcServer;
import java.util.concurrent.CompletableFuture;
public class McpTransportFixture {
  public static void main(String[] args) throws Exception {
    MethodSpecs specs = MethodSpecs.load();
    HostBridge bridge = HostFixture.withoutServices().bridge(specs);
    try (RpcServer server = new RpcServer(0, specs, (method, params, deadline) -> {
      bridge.validate(method, params);
      if (method.equals("parameters.set")) {
        CompletableFuture<JsonObject> failed = new CompletableFuture<>();
        failed.completeExceptionally(Errors.unknown(Reason.NOT_OBSERVED, "Observed ambiguity"));
        return failed;
      }
      JsonObject result = new JsonObject();
      result.addProperty("fixtureOnly", true);
      result.addProperty("method", method);
      result.add("echo", params);
      result.add("supportedMethods", new Gson().toJsonTree(specs.methods()));
      return CompletableFuture.completedFuture(result);
    })) {
      server.start();
      System.out.println(server.getPort());
      System.out.flush();
      System.in.read();
    }
  }
}
"""


SERVER = "from bitwig_agent.frontends.mcp import main; main()"
SERVER_EXPOSING_ALL = (
    "import dataclasses\n"
    "from bitwig_agent.protocol import TOOLS\n"
    "TOOLS.update({name: dataclasses.replace(spec, expose=True) for name, spec in TOOLS.items()})\n" + SERVER
)


def payload(response):
    return response.structured_content or json.loads(response.content[0].text)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


async def exercise(folder: Path, api: Path) -> None:  # noqa: PLR0915
    fixture = folder / "McpTransportFixture.java"
    fixture.write_text(FIXTURE, encoding="utf-8")
    gson = build.gson_jar()
    build.compile_java(
        [*build.verification_sources(), *(TEST_ROOT / "dev/bitwigagent" / name for name in FIXTURE_SOURCES), fixture],
        folder,
        [gson, api],
        build.lint_flag(api),
    )
    build.write_resources(folder)
    process = await asyncio.create_subprocess_exec(
        "java",
        "-cp",
        os.pathsep.join(map(str, [folder, gson, api])),
        "dev.bitwigagent.McpTransportFixture",
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
    )
    assert process.stdout is not None
    try:
        port = int((await asyncio.wait_for(process.stdout.readline(), 10)).decode())
        env = dict(os.environ, BITWIG_AGENT_PORT=str(port))
        server = StdioServerParameters(command=sys.executable, args=["-c", SERVER_EXPOSING_ALL], env=env)
        surface = StdioServerParameters(command=sys.executable, args=["-c", SERVER], env=env)
        async with stdio_client(surface) as (read, write), ClientSession(read, write) as session:
            await session.initialize()
            offered = {tool.name for tool in (await session.list_tools()).tools}
        exposed = {name for name, spec in TOOLS.items() if spec.expose}
        require(offered == exposed | {"help"}, f"Surface mismatch: {sorted(offered ^ (exposed | {'help'}))}")
        async with stdio_client(server) as (read, write), ClientSession(read, write) as session:
            await session.initialize()
            names = {tool.name for tool in (await session.list_tools()).tools} - {"help"}
            response = await session.call_tool("status", {})
            status = payload(response)
            require(not response.is_error, f"Java status fixture failed: {status}")
            java_names = set(status["supportedMethods"])
            require(
                names - CLIENT_SIDE == java_names == set(TOOLS) - CLIENT_SIDE and names >= CLIENT_SIDE,
                f"Registry mismatch: MCP={sorted(names)}, Java={sorted(java_names)}",
            )
            calls = 0
            for method in sorted(names - CLIENT_SIDE):
                examples = TOOLS[method].examples
                require(bool(examples), f"Missing executable example for {method}")
                for example in examples:
                    params = host_paths(example)
                    if "expectedDeviceName" in params:
                        params["expectedDeviceName"] = "音色 \u03b1"
                    # Preserve omitted optional fields; the real JVM independently
                    # validates what the adapter actually sends.
                    expected = (
                        TOOLS[method]
                        .model.model_validate(params)
                        .model_dump(exclude={"timeoutMs"}, exclude_unset=True, by_alias=True)
                    )
                    response = await session.call_tool(method, params)
                    body = payload(response)
                    if method == "parameters.set":
                        require(
                            response.is_error and body.get("outcome") == "unknown" and body["reason"] == "not_observed",
                            f"Lost Java ambiguity for {method}: {body}",
                        )
                    else:
                        require(not response.is_error, f"{method} failed Java/schema validation: {body}")
                        result = body
                        require(
                            result.get("fixtureOnly") is True
                            and result.get("method") == method
                            and result.get("echo") == expected,
                            f"Forwarding mismatch for {method}: {body}",
                        )
                    calls += 1
            # Exercise the actual MCP validation boundary, not model validation alone.
            invalid = await session.call_tool("status", {"unexpected": True})
            require(invalid.is_error, "MCP accepted an extra argument")
            vectors = await vector_parity.run(port)
        print(
            f"PASS: {len(names)} matching Java/MCP tools, {calls} example roundtrips, "
            f"Unicode, strict MCP arguments, unknown outcome propagation, {vectors} generated parity vectors. "
            "FIXTURE ONLY; no native host."
        )
    finally:
        if process.stdin:
            process.stdin.close()
        try:
            await asyncio.wait_for(process.wait(), 5)
        except TimeoutError:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), 5)
            except TimeoutError:
                process.kill()
                await process.wait()


async def main() -> None:
    build.BUILD.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mcp-java-smoke-", dir=build.BUILD) as folder:
        await asyncio.wait_for(exercise(Path(folder), build.api_jar()), timeout=180)


if __name__ == "__main__":
    asyncio.run(main())
