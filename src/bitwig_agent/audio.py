"""Client-side audio tools: silent PipeWire routing, capture and analysis (Linux/PipeWire only).

Composed from the pw-link/pactl/parec CLIs with explicit argument lists and timeouts. Only the
links of the 'Bitwig Studio' output ports are ever touched.
"""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

from bitwig_agent import audio_analysis
from bitwig_agent.client import BridgeError

NODE = "Bitwig Studio"
SINK = "bwtest"
OUT_PORTS = {f"{NODE}:out1": f"{SINK}:playback_FL", f"{NODE}:out2": f"{SINK}:playback_FR"}
TOOLS_NEEDED = ("pw-link", "pactl", "parec")
RUN_TIMEOUT = 5.0
RATE = 48000


def fail(code: str, message: str, outcome: str = "not_dispatched", **details: Any) -> BridgeError:
    error = {"code": code, "message": message, "outcome": outcome}
    if details:
        error["details"] = details
    return BridgeError(error)


# Seams for tests: no real subprocess or sleeping there.
def run(args: list[str], timeout: float = RUN_TIMEOUT) -> tuple[int, str, str]:
    try:
        done = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise fail("audio_tool_timeout", f"{args[0]} did not finish within {timeout:g}s", "unknown") from exc
    except OSError as exc:
        raise fail("audio_tool_failed", f"Cannot run {args[0]}: {exc.strerror}") from exc
    return done.returncode, done.stdout, done.stderr


def spawn(args: list[str]) -> subprocess.Popen:
    return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


sleep = asyncio.sleep


def check_platform() -> None:
    if not sys.platform.startswith("linux"):
        raise fail("unsupported_platform", "audio.route and audio.capture need Linux with PipeWire")
    missing = [t for t in TOOLS_NEEDED if shutil.which(t) is None]
    if missing:
        raise fail("unsupported_platform", f"Missing PipeWire/PulseAudio tools: {', '.join(missing)}")


def state_path() -> Path:
    base = os.environ.get("XDG_CACHE_HOME") or os.path.join(os.path.expanduser("~"), ".cache")
    return Path(base) / "bitwig-agent" / "audio-route.json"


def load_state() -> dict[str, list[str]]:
    try:
        data = json.loads(state_path().read_text(encoding="utf-8"))
        return {k: list(v) for k, v in data["previous"].items()}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {}


def save_state(previous: dict[str, list[str]]) -> None:
    path = state_path()
    if not previous:
        path.unlink(missing_ok=True)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8")
    with handle:
        json.dump({"previous": previous}, handle)
    os.replace(handle.name, path)


def parse_links(text: str) -> list[tuple[str, str]]:
    """(output port, input port) pairs from `pw-link -l`, for Bitwig output ports only."""
    links, port = [], None
    for line in text.splitlines():
        if not line.strip():
            continue
        if not line[0].isspace():
            port = line.strip()
        elif port in OUT_PORTS and line.strip().startswith("|->"):
            links.append((port, line.strip()[3:].strip()))
    return links


def current_links() -> list[tuple[str, str]]:
    code, out, err = run(["pw-link", "-l"])
    if code != 0:
        raise fail("audio_tool_failed", f"pw-link -l failed: {err.strip() or code}")
    return parse_links(out)


def sink_exists() -> bool:
    code, out, _ = run(["pactl", "list", "short", "sinks"])
    return code == 0 and any(len(cols := line.split("\t")) > 1 and cols[1] == SINK for line in out.splitlines())


def ensure_sink() -> bool:
    """Create the null sink if absent; True when this call created it."""
    if sink_exists():
        return False
    code, _, err = run(["pactl", "load-module", "module-null-sink", f"sink_name={SINK}",
                        f"sink_properties=device.description={SINK}"])
    if code != 0:
        raise fail("audio_tool_failed", f"Cannot create the {SINK} null sink: {err.strip() or code}")
    return True


def link(args: list[str], what: str) -> None:
    code, _, err = run(["pw-link", *args])
    if code != 0 and "exists" not in err.lower():
        raise fail("audio_tool_failed", f"pw-link {what} failed: {err.strip() or code}", "unknown")


def describe(links: list[tuple[str, str]]) -> dict[str, Any]:
    return {"links": [{"from": a, "to": b} for a, b in links],
            "silent": bool(links) and all(b.startswith(f"{SINK}:") for _, b in links)}


def route(args: dict[str, Any]) -> dict[str, Any]:
    check_platform()
    mode = args["mode"]
    links = current_links()
    saved = load_state()
    if mode == "status":
        return {"mode": "status", **describe(links), "sinkExists": sink_exists(), "savedPrevious": saved}
    if mode == "silent":
        if not links and not saved:
            raise fail("bitwig_not_found", f"No '{NODE}' output links in PipeWire; is Bitwig running and playing out?")
        created = ensure_sink()
        previous = {k: list(v) for k, v in saved.items()}
        for out, target in links:
            if not target.startswith(f"{SINK}:"):
                if target not in previous.setdefault(out, []):
                    previous[out].append(target)
        save_state(previous)  # persist before touching any link so restore can always undo
        for out, target in links:
            if not target.startswith(f"{SINK}:"):
                link(["-d", out, target], "disconnect")
        for out, sink_port in OUT_PORTS.items():
            if out in previous and (out, sink_port) not in links:
                link([out, sink_port], "connect")
        return {"mode": "silent", "sinkCreated": created, **describe(current_links()), "savedPrevious": previous}
    # restore
    if not saved:
        return {"mode": "restore", "restored": [], "note": "nothing saved; links unchanged", **describe(links)}
    restored = []
    for out, targets in saved.items():
        for target in targets:
            link([out, target], "reconnect")
            restored.append({"from": out, "to": target})
    for out, target in current_links():
        if target.startswith(f"{SINK}:"):
            link(["-d", out, target], "disconnect")
    save_state({})
    return {"mode": "restore", "restored": restored, **describe(current_links())}


async def capture(bridge: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    check_platform()
    seconds = args["seconds"]
    path = args["outPath"]
    if os.path.exists(path) and not args.get("overwrite", False):
        raise fail("file_exists", f"{path} exists; pass overwrite:true to replace it")
    if not os.path.isdir(os.path.dirname(path)):
        raise fail("invalid_params", f"Directory does not exist: {os.path.dirname(path)}")
    allow_audible = args.get("allowAudible", False)
    source = f"{SINK}.monitor"
    if not allow_audible:
        state = describe(current_links())
        if not state["silent"]:
            raise fail("not_silent", "Bitwig output is not routed to the silent sink; call audio.route mode=silent "
                       "first, or pass allowAudible:true to record the default output audibly", **state)
    else:
        source = "@DEFAULT_MONITOR@"
    from_start = args.get("fromStart", True)
    transport_started = False
    proc = spawn(["parec", "-d", source, "--file-format=wav", f"--rate={RATE}", "--channels=2", path])
    try:
        await sleep(0.3)
        if proc.poll() is not None:
            err = (proc.stderr.read() or b"").decode(errors="replace").strip() if proc.stderr else ""
            raise fail("audio_tool_failed", f"parec exited early: {err or proc.returncode}")
        if from_start:
            await bridge.call("transport.set", {"playing": False}, timeout_ms)
            await bridge.call("transport.set", {"positionBeats": 0}, timeout_ms)
            await bridge.call("transport.set", {"playing": True}, timeout_ms)
            transport_started = True
        started = time.monotonic()
        await sleep(seconds)
        elapsed = time.monotonic() - started
    finally:
        if transport_started:
            try:
                await bridge.call("transport.set", {"playing": False}, timeout_ms)
            except BridgeError:
                pass  # the error from the main flow, if any, is more useful; transport state is reported below
        if proc.poll() is None:
            proc.send_signal(signal.SIGINT)  # lets parec finalize the WAV header
            try:
                await asyncio.to_thread(proc.wait, 3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
    stats = await asyncio.to_thread(audio_analysis.analyze, {"path": path, "mode": "level", "windowSeconds": 60})
    return {"path": path, "requestedSeconds": seconds, "elapsedSeconds": round(elapsed, 3),
            "durationSeconds": stats["durationSeconds"], "sampleRate": stats["sampleRate"], "channels": stats["channels"],
            "peak": stats["peak"], "peakDb": stats["peakDb"], "rms": stats["rms"], "rmsDb": stats["rmsDb"],
            "clipping": stats["clipping"], "silent": stats["peak"] < audio_analysis.SILENT_PEAK,
            "fromStart": from_start, "audible": allow_audible, "transportStopped": transport_started}


async def analyze(args: dict[str, Any]) -> dict[str, Any]:
    try:
        return await asyncio.to_thread(audio_analysis.analyze, args)
    except audio_analysis.WavError as exc:
        raise fail("invalid_params", str(exc)) from exc
