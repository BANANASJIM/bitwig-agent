#!/usr/bin/env python3
"""Opt-in read-only native MCP preflight, not music/UI/persistence E2E acceptance.

Usage: python scripts/smoke-native.py --allow-native --config /absolute/run.json
JSON config (all paths absolute, artifact directory must not already exist):
  {"fixture":"/path/to/Disposable.bwproject", "pid":12345,
   "display":":1", "window_id":"0x12345", "port":61172,
   "artifacts":"/path/to/new-run", "isolated_display_confirmed":true}
No launch, installation, mutation, GUI input, screenshot, default display, or
automatic retry is performed. notes.read is excluded because it selects a clip.
Requires Linux /proc, xprop and the existing Python development environment.
A matching title/project name is NOT proof of the loaded full file path; exact
file identity and actual reopen/music/UI behavior require independent evidence.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from bitwig_agent.catalog import TOOLS

def has_owned_loopback_listener(table, port, owned_sockets, *, byteorder=sys.byteorder):
    """Read Linux /proc/net/tcp{,6}: each address word is native-endian u32."""
    loopback = b"\x7f\x00\x00\x01"
    mapped_loopback = b"\x00" * 10 + b"\xff\xff" + loopback
    for line in table.splitlines()[1:]:
        row = line.split()
        if len(row) < 10 or row[3] != "0A" or row[9] not in owned_sockets:
            continue
        try:
            address, port_hex = row[1].split(":")
            if len(address) not in (8, 32) or int(port_hex, 16) != port:
                continue
            packed = b"".join(int(address[index:index + 8], 16).to_bytes(4, byteorder)
                              for index in range(0, len(address), 8))
        except (ValueError, OverflowError):
            continue
        # A Java IPv4 listener may appear as an IPv4-mapped IPv6 socket. Neither
        # wildcard nor native ::1 is evidence for our exact 127.0.0.1 endpoint.
        if packed in (loopback, mapped_loopback):
            return True
    return False



class NativeTarget:
    def __init__(self, config):
        required = {"fixture", "pid", "display", "window_id", "port", "artifacts", "isolated_display_confirmed"}
        if not isinstance(config, dict) or set(config) != required:
            raise ValueError("Config requires exactly the documented safety keys")
        if config["isolated_display_confirmed"] is not True:
            raise ValueError("Explicit isolated_display_confirmed=true is required")
        if not isinstance(config["display"], str) or not re.fullmatch(r":[1-9][0-9]*(?:\.[0-9]+)?", config["display"]):
            raise ValueError("Explicit non-default local X display such as :1 is required")
        if not isinstance(config["window_id"], str) or not re.fullmatch(r"0x[0-9a-fA-F]+|[1-9][0-9]*", config["window_id"]):
            raise ValueError("Explicit X window ID is required")
        if type(config["pid"]) is not int or config["pid"] <= 1:
            raise ValueError("pid must identify the existing Bitwig process")
        if type(config["port"]) is not int or not 1 <= config["port"] <= 65535:
            raise ValueError("port must be in 1..65535")
        self.config = config
        self.fixture = Path(config["fixture"])
        self.artifacts = Path(config["artifacts"])
        if not self.fixture.is_absolute() or self.fixture.suffix != ".bwproject" or not self.fixture.is_file():
            raise ValueError("fixture must be an existing absolute .bwproject path")
        if not self.artifacts.is_absolute() or self.artifacts.exists():
            raise ValueError("artifacts must be a new absolute directory; existing evidence is never overwritten")
        self.fixture = self.fixture.resolve(strict=True)
        self.process = Path("/proc") / str(config["pid"])
        self.start_time = self.process_start()
        self.initial_guard = self.guard()
        self.fixture_hash = self.hash_fixture()

    def hash_fixture(self):
        with self.fixture.open("rb") as source:
            return hashlib.file_digest(source, "sha256").hexdigest()

    def process_start(self):
        if self.process.stat().st_uid != os.getuid():
            raise RuntimeError("Refusing a host process owned by another user")
        return (self.process / "stat").read_text().rsplit(")", 1)[1].split()[19]

    def guard(self):
        if self.process_start() != self.start_time:
            raise RuntimeError("Host PID was reused; refusing to continue")
        command = (self.process / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
        if "bitwig" not in command.lower():
            raise RuntimeError("Target process command line does not identify Bitwig")
        window = subprocess.run(
            ["xprop", "-display", self.config["display"], "-id", self.config["window_id"],
             "_NET_WM_PID", "WM_CLASS", "_NET_WM_NAME", "WM_NAME"],
            check=True, capture_output=True, text=True, timeout=5,
        ).stdout
        owner = re.search(r"_NET_WM_PID\([^)]*\)\s*=\s*(\d+)", window)
        if not owner or int(owner.group(1)) != self.config["pid"]:
            raise RuntimeError("The explicitly targeted window is not owned by the supplied PID")
        title_lines = [line for line in window.splitlines() if line.startswith(("WM_NAME(", "_NET_WM_NAME("))]
        class_lines = [line for line in window.splitlines() if line.startswith("WM_CLASS(")]
        if not any("bitwig" in line.lower() for line in class_lines) or not any(self.fixture.stem in line for line in title_lines):
            raise RuntimeError("Window class/title does not identify Bitwig and the saved fixture name")
        owned_sockets = set()
        for fd in (self.process / "fd").iterdir():
            try:
                link = os.readlink(fd)
            except FileNotFoundError:
                continue
            if link.startswith("socket:["):
                owned_sockets.add(link[8:-1])
        tables = []
        for name in ("tcp", "tcp6"):
            path = self.process / "net" / name
            try:
                tables.append(path.read_text())
            except FileNotFoundError:
                continue  # A kernel may have IPv6 disabled.
        if not any(has_owned_loopback_listener(table, self.config["port"], owned_sockets)
                   for table in tables):
            raise RuntimeError("Requested IPv4 loopback listener is not owned by the supplied host PID")
        return {"processStartTicks": self.start_time, "commandLine": command, "windowProperties": window,
                "listener": f"127.0.0.1:{self.config['port']}", "pid": self.config["pid"]}


async def probe(target, report):
    env = dict(os.environ, BITWIG_AGENT_PORT=str(target.config["port"]))
    server = StdioServerParameters(command=sys.executable,
                                  args=["-c", "from bitwig_agent.server import main; main()"], env=env)
    calls_path = target.artifacts / "calls.jsonl"
    async with stdio_client(server) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            advertised = {tool.name for tool in (await session.list_tools()).tools}

            async def call(method, params=None):
                if method not in TOOLS or not TOOLS[method].read_only:
                    raise RuntimeError(f"Refusing non-read-only method: {method}")
                target.guard()
                arguments = params or {}
                response = await session.call_tool(method, arguments)
                body = response.structuredContent or json.loads(response.content[0].text)
                record = {"time": datetime.now(timezone.utc).isoformat(), "method": method,
                          "params": arguments, "isError": response.isError, "response": body}
                with calls_path.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
                if response.isError:
                    raise RuntimeError(f"Native {method} failed; inspect calls.jsonl (no retry)")
                result = body["result"]
                if result.get("status") != "observed":
                    raise RuntimeError(f"Native {method} did not return an observed read")
                return result

            before = await call("status")
            report["beforeStatus"] = before
            if before.get("projectName") != target.fixture.stem:
                raise RuntimeError("Observed project name differs from explicit fixture; refusing further queries")
            capabilities = await call("capabilities")
            if set(capabilities["methods"]) != advertised:
                raise RuntimeError("Native Java registry differs from the installed MCP adapter")
            report["beforeTransport"] = await call("transport.get")
            tracks = await call("tracks.list")
            report["trackWindow"] = tracks.get("window")
            await call("devices.catalog")
            await call("actions.list")
            report["deviceReadScope"] = "First observed top-level device per returned track; not all devices or private Grid topology"
            device_count = 0
            for track in tracks["tracks"]:
                track_params = {"trackId": track["trackId"]}
                await call("tracks.get", track_params)
                await call("launcher.list", track_params)
                devices = await call("devices.list", track_params)
                if devices["devices"]:
                    device = devices["devices"][0]
                    params = {**track_params, "deviceIndex": device["deviceIndex"], "expectedDeviceName": device["name"]}
                    await call("devices.get", params)
                    await call("devices.remote.get", params)
                    await call("parameters.list", params)
                    device_count += 1
            report["tracksRead"] = len(tracks["tracks"])
            report["devicesRead"] = device_count
            if not tracks["tracks"]:
                report["notExercised"].append("Per-track/launcher/device reads: fixture has no observed tracks")
            elif not device_count:
                report["notExercised"].append("devices.get/devices.remote.get/parameters.list: no observed devices")
            report["notExercised"].append("notes.read: selects a GUI clip; excluded from no-selection preflight")
            report["afterTransport"] = await call("transport.get")
            report["afterStatus"] = await call("status")
            if report["afterStatus"].get("projectName") != target.fixture.stem:
                raise RuntimeError("Project name changed during read-only probe")


def describe_error(error):
    if isinstance(error, BaseExceptionGroup):
        return "; ".join(describe_error(child) for child in error.exceptions)
    return f"{type(error).__name__}: {error}"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--allow-native", action="store_true")
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    if not args.allow_native:
        parser.error("--allow-native is required; no host interaction occurred")
    target = NativeTarget(json.loads(args.config.read_text(encoding="utf-8")))
    target.artifacts.mkdir(parents=True, exist_ok=False)
    report = {"scope": "read_only_native_preflight_not_E2E_acceptance", "status": "running",
              "config": target.config, "fixtureSha256Before": target.fixture_hash,
              "exactLoadedFileIdentity": "unverified: path supplied; title and API expose only project name",
              "guardBefore": target.initial_guard, "notExercised": [
                  "Mutations, audio/music quality, GUI behavior, private Grid graph APIs and actual save/reopen"
              ]}
    try:
        asyncio.run(asyncio.wait_for(probe(target, report), timeout=150))
        report["guardAfter"] = target.guard()
        report["fixtureSha256After"] = target.hash_fixture()
        if report["fixtureSha256After"] != target.fixture_hash:
            raise RuntimeError("Supplied saved fixture bytes changed during read-only probe")
        report["status"] = "passed_read_only_preflight"
        return 0
    except Exception as exc:
        report["status"] = "failed"
        report["error"] = describe_error(exc)
        print(report["error"], file=sys.stderr)
        return 1
    finally:
        report["recordedAt"] = datetime.now(timezone.utc).isoformat()
        (target.artifacts / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"Native preflight {report['status']}; artifacts: {target.artifacts}; NOT E2E acceptance")


if __name__ == "__main__":
    sys.exit(main())
