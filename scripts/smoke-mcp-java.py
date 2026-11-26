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

import bound_parity
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from bitwig_agent.protocol import CLIENT_SIDE, TOOLS

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("extension_build", ROOT / "scripts/build.py")
assert spec is not None
assert spec.loader is not None
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

FIXTURE = """
package dev.bitwigagent;
import com.google.gson.*;
import java.util.concurrent.CompletableFuture;
public class McpTransportFixture {
  public static void main(String[] args) throws Exception {
    try (RpcServer server = new RpcServer(0, (method, params, deadline) -> {
      HostBridge.validate(method, params);
      if (method.equals("parameters.set")) {
        CompletableFuture<JsonObject> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RpcException("fixture_unknown", "Observed ambiguity", "unknown"));
        return failed;
      }
      JsonObject result = new JsonObject();
      result.addProperty("status", "observed");
      result.addProperty("fixtureOnly", true);
      result.addProperty("method", method);
      result.add("echo", params);
      result.add("supportedMethods", new Gson().toJsonTree(HostBridge.supportedMethods()));
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


def payload(response):
    return response.structured_content or json.loads(response.content[0].text)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


async def exercise(folder: Path, api: Path) -> None:  # noqa: PLR0915
    fixture = folder / "McpTransportFixture.java"
    fixture.write_text(FIXTURE, encoding="utf-8")
    gson = build.gson_jar()
    build.compile_java([*build.verification_sources(), fixture], folder, [gson, api], build.lint_flag(api))
    process = await asyncio.create_subprocess_exec(
        os.environ.get("JAVA", "java"),
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
        server = StdioServerParameters(
            command=sys.executable, args=["-c", "from bitwig_agent.server import main; main()"], env=env
        )
        async with stdio_client(server) as (read, write), ClientSession(read, write) as session:
            await session.initialize()
            names = {tool.name for tool in (await session.list_tools()).tools}
            response = await session.call_tool("status", {})
            status = payload(response)
            require(not response.is_error, f"Java status fixture failed: {status}")
            java_names = set(status["result"]["supportedMethods"])
            require(
                names - CLIENT_SIDE == java_names == set(TOOLS) - CLIENT_SIDE and names >= CLIENT_SIDE,
                f"Registry mismatch: MCP={sorted(names)}, Java={sorted(java_names)}",
            )
            calls = 0
            for method in sorted(names - CLIENT_SIDE):
                examples = TOOLS[method].examples
                require(bool(examples), f"Missing executable example for {method}")
                for example in examples:
                    params = dict(example)
                    if "expectedDeviceName" in params:
                        params["expectedDeviceName"] = "音色 \u03b1"
                    # Preserve omitted optional fields; the real JVM independently
                    # validates what the adapter actually sends.
                    expected = (
                        TOOLS[method].model.model_validate(params).model_dump(exclude={"timeoutMs"}, exclude_unset=True)
                    )
                    response = await session.call_tool(method, params)
                    body = payload(response)
                    if method == "parameters.set":
                        require(
                            response.is_error
                            and body.get("error", {}).get("outcome") == "unknown"
                            and body["error"]["code"] == "fixture_unknown",
                            f"Lost Java ambiguity for {method}: {body}",
                        )
                    else:
                        require(not response.is_error, f"{method} failed Java/schema validation: {body}")
                        result = body["result"]
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
            vectors = await bound_parity.run(port)
        print(
            f"PASS: {len(names)} matching Java/MCP tools, {calls} example roundtrips, "
            f"Unicode, strict MCP arguments, unknown error propagation, {vectors} bound/optional parity vectors. "
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
