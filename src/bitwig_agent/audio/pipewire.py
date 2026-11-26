"""Client-side audio tools: silent PipeWire routing, capture and analysis (Linux/PipeWire only).

Composed from the pw-link/pactl/pw-record CLIs with explicit argument lists and timeouts. Only the
links of the 'Bitwig Studio' output ports are ever touched; a capture only adds (and later removes) its own links.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any

from bitwig_agent.audio import analysis
from bitwig_agent.errors import BridgeError, UnknownOutcome, bridge_error, unknown_result
from bitwig_agent.protocol.base import SUCCEEDED

NODE = "Bitwig Studio"
SINK = "bwtest"
OUT_PORTS = {f"{NODE}:out1": f"{SINK}:playback_FL", f"{NODE}:out2": f"{SINK}:playback_FR"}
TOOLS_NEEDED = ("pw-link", "pactl", "pw-record")
RUN_TIMEOUT = 5.0
RATE = 48000


def require_success(method: str, result: dict[str, Any]) -> dict[str, Any]:
    """A host write that ended anything but a success stops the composed call with that result."""
    outcome = result.get("outcome")
    if outcome not in SUCCEEDED:
        raise UnknownOutcome(
            {**result, "outcome": outcome or "unknown", "message": result.get("message", f"{method} ended {outcome}")}
        )
    return result


def _failure(exc: BridgeError | UnknownOutcome) -> dict[str, Any]:
    """The unknown result for a cleanup step that failed after the main flow was done."""
    if isinstance(exc, UnknownOutcome):
        return exc.result
    return unknown_result(exc.error["reason"], exc.error["message"])


async def require_engine(bridge: Any, timeout_ms: int) -> None:
    """Fail fast when status observes the audio engine inactive: a capture would only record silence."""
    status = await bridge.call("status", {}, min(timeout_ms, 5000))
    if status.get("engineActive") is False:
        raise bridge_error(
            "engine_inactive",
            "The audio engine is not active for this project (status.engineActive=false), so playback would be silent. "
            "Run project.activateEngine, confirm engineActive=true, then retry. Nothing changed.",
        )


# Seams for tests: no real subprocess or sleeping there.
def run(args: list[str], timeout: float = RUN_TIMEOUT) -> tuple[int, str, str]:
    try:
        done = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise bridge_error("deadline", f"{args[0]} did not finish within {timeout:g}s") from exc
    except OSError as exc:
        raise bridge_error("job_failed", f"Cannot run {args[0]}: {exc.strerror}") from exc
    return done.returncode, done.stdout, done.stderr


def spawn(args: list[str]) -> subprocess.Popen[bytes]:
    return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


sleep = asyncio.sleep


def check_platform() -> None:
    if not sys.platform.startswith("linux"):
        raise bridge_error("unsupported_platform", "audio.route and audio.capture need Linux with PipeWire")
    missing = [t for t in TOOLS_NEEDED if shutil.which(t) is None]
    if missing:
        raise bridge_error("unsupported_platform", f"Missing PipeWire/PulseAudio tools: {', '.join(missing)}")


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
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
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
        raise bridge_error("job_failed", f"pw-link -l failed: {err.strip() or code}")
    return parse_links(out)


def sink_exists() -> bool:
    code, out, _ = run(["pactl", "list", "short", "sinks"])
    return code == 0 and any(len(cols := line.split("\t")) > 1 and cols[1] == SINK for line in out.splitlines())


def ensure_sink() -> bool:
    """Create the null sink if absent; True when this call created it."""
    if sink_exists():
        return False
    code, _, err = run(
        ["pactl", "load-module", "module-null-sink", f"sink_name={SINK}", f"sink_properties=device.description={SINK}"]
    )
    if code != 0:
        raise bridge_error("job_failed", f"Cannot create the {SINK} null sink: {err.strip() or code}")
    return True


def link(args: list[str], what: str) -> None:
    """A pw-link that fails or times out may follow links that already changed: the result is unknown, not an error."""
    try:
        code, _, err = run(["pw-link", *args])
    except BridgeError as exc:
        raise UnknownOutcome(_failure(exc)) from exc
    if code != 0 and "exists" not in err.lower():
        raise UnknownOutcome(
            unknown_result(
                "job_failed",
                f"pw-link {what} failed: {err.strip() or code}",
                "Inspect the links with audio.route mode=status before another change.",
            )
        )


def describe(links: list[tuple[str, str]]) -> dict[str, Any]:
    return {
        "links": [{"from": a, "to": b} for a, b in links],
        "silent": bool(links) and all(b.startswith(f"{SINK}:") for _, b in links),
    }


def _route_silent(links: list[tuple[str, str]], saved: dict[str, list[str]]) -> dict[str, Any]:
    if not links and not saved:
        raise bridge_error("not_ready", f"No '{NODE}' output links in PipeWire; is Bitwig running and playing out?")
    created = ensure_sink()
    previous = {k: list(v) for k, v in saved.items()}
    for out, target in links:
        if not target.startswith(f"{SINK}:") and target not in previous.setdefault(out, []):
            previous[out].append(target)
    save_state(previous)  # persist before touching any link so restore can always undo
    moved = created
    for out, target in links:
        if not target.startswith(f"{SINK}:"):
            link(["-d", out, target], "disconnect")
            moved = True
    for out, sink_port in OUT_PORTS.items():
        if out in previous and (out, sink_port) not in links:
            link([out, sink_port], "connect")
            moved = True
    return {
        "outcome": "applied" if moved else "already_matched",
        "mode": "silent",
        "sinkCreated": created,
        **describe(current_links()),
        "savedPrevious": previous,
    }


def _route_restore(links: list[tuple[str, str]], saved: dict[str, list[str]]) -> dict[str, Any]:
    if not saved:
        return {
            "outcome": "already_matched",
            "mode": "restore",
            "restored": [],
            "note": "nothing saved; links unchanged",
            **describe(links),
        }
    restored = []
    for out, targets in saved.items():
        for target in targets:
            link([out, target], "reconnect")
            restored.append({"from": out, "to": target})
    for out, target in current_links():
        if target.startswith(f"{SINK}:"):
            link(["-d", out, target], "disconnect")
    save_state({})
    return {"outcome": "applied", "mode": "restore", "restored": restored, **describe(current_links())}


def route(args: dict[str, Any]) -> dict[str, Any]:
    check_platform()
    mode = args["mode"]
    links = current_links()
    saved = load_state()
    if mode == "status":
        return {"mode": "status", **describe(links), "sinkExists": sink_exists(), "savedPrevious": saved}
    if mode == "silent":
        return _route_silent(links, saved)
    return _route_restore(links, saved)


async def playing_tracks(bridge: Any, timeout_ms: int) -> list[dict[str, str]] | None:
    """Tracks with a playing launcher clip as [{trackId, name}] (one context.get), or None when it cannot be read."""
    try:
        ctx = await bridge.call("context.get", {}, timeout_ms)
    except BridgeError:
        return None
    return [
        {"trackId": t.get("trackId", ""), "name": t.get("name", "")}
        for t in ctx.get("tracks") or []
        if any(s.get("playing") for s in t.get("launcherSlots") or [])
    ]


async def isolate(bridge: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    """Which other tracks would end up in the recording; optionally refuse or stop them first. Raises not_isolated before any recording."""
    solo, stop_others = args.get("soloTrack"), args.get("stopOthers", False)
    playing = await playing_tracks(bridge, timeout_ms)
    if playing is None:
        return {
            "warnings": [
                "could not read which tracks are playing (context.get failed): the recording may mix several tracks"
            ]
        }
    others = [t for t in playing if t["trackId"] != solo]
    stopped: list[str] = []
    if stop_others and solo:
        for t in others:
            require_success("launcher.stop", await bridge.call("launcher.stop", {"trackId": t["trackId"]}, timeout_ms))
            stopped.append(t["name"])
        others = []
    if (
        solo and others
    ):  # the solo track itself may sound without a launcher clip (a Poly Grid, a held note): the real solo covers that
        raise bridge_error(
            "not_isolated",
            f"soloTrack {solo} is not the only playing track (other tracks are playing: {[t['name'] for t in others]}); "
            "stop the others or pass stopOthers:true",
            otherTracksPlaying=[t["name"] for t in others],
        )
    out: dict[str, Any] = {"otherTracksPlaying": [t["name"] for t in others] if solo or len(others) > 1 else []}
    if stopped:
        out["stoppedTracks"] = stopped
    if out["otherTracksPlaying"]:
        out["warnings"] = [
            f"launcher clips are playing on {out['otherTracksPlaying']}: the recording is a mix, not one patch; "
            "pass soloTrack (and stopOthers) to isolate it"
        ]
    return out


async def read_solo_states(bridge: Any, timeout_ms: int) -> dict[str, bool]:
    """trackId -> solo state of every track in the bank (tracks.list + tracks.get); not_ready when any state cannot be read."""
    listed = await bridge.call("tracks.list", {}, timeout_ms)
    states: dict[str, bool] = {}
    for row in listed.get("tracks") or []:
        tid = row.get("trackId")
        got = await bridge.call("tracks.get", {"trackId": tid}, timeout_ms)
        if not isinstance(got.get("solo"), bool):
            raise bridge_error(
                "not_ready",
                f"Cannot read the solo state of track {row.get('name') or tid}; refusing to change solos it could not restore.",
            )
        states[tid] = got["solo"]
    return states


async def restore_solo(bridge: Any, previous: dict[str, bool], changed: list[str], timeout_ms: int) -> dict[str, Any]:
    """Put back the solo state of every track this capture changed, in reverse order; failures are reported, not raised."""
    restored, failed = [], []
    for tid in reversed(changed):
        try:
            put_back = await bridge.call("tracks.set", {"trackId": tid, "solo": previous[tid]}, timeout_ms)
            require_success("tracks.set", put_back)
            restored.append({"trackId": tid, "solo": previous[tid]})
        except (BridgeError, UnknownOutcome) as exc:
            failed.append({"trackId": tid, "solo": previous[tid], "error": str(exc)})
    return {"restored": restored, **({"restoreFailed": failed} if failed else {})}


async def apply_solo(bridge: Any, solo: str, timeout_ms: int) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    """Solo `solo` exclusively (the host has no exclusive-solo write): set it on the target and clear every other soloed track.

    Returns (previous states of every track, tracks changed so far, result block); a failure part-way restores what was changed.
    """
    previous = await read_solo_states(bridge, timeout_ms)
    if solo not in previous:
        raise bridge_error("target_not_found", f"soloTrack {solo} is not in the track bank (tracks.list)")
    block: dict[str, Any] = {
        "soloed": solo,
        "previousSolo": sorted(t for t, v in previous.items() if v),
        "unsoloed": [],
    }
    changed: list[str] = []
    try:
        for tid in [solo, *[t for t in previous if t != solo]]:  # the target first, then clear every other soloed track
            want = tid == solo
            if previous[tid] != want:
                changed.append(tid)
                require_success(
                    "tracks.set", await bridge.call("tracks.set", {"trackId": tid, "solo": want}, timeout_ms)
                )
                if tid != solo:
                    block["unsoloed"].append(tid)
    except BaseException:
        await restore_solo(bridge, previous, changed, timeout_ms)
        raise
    return previous, changed, block


def bitwig_outputs() -> list[str]:
    """Bitwig's own output ports (out1, out2), from `pw-link -o`."""
    code, out, err = run(["pw-link", "-o"])
    if code != 0:
        raise bridge_error("job_failed", f"pw-link -o failed: {err.strip() or code}")
    ports = [line.strip() for line in out.splitlines()]
    wanted = (f"{NODE}:out1", f"{NODE}:out2")
    found = [p for p in wanted if p in ports]
    if len(found) != len(wanted):
        raise bridge_error(
            "not_ready",
            f"Expected '{NODE}:out1' and '{NODE}:out2' in pw-link -o; is Bitwig running with its audio engine on?",
        )
    return found


async def wait_for_inputs(node: str) -> list[str]:
    wanted = [f"{node}:input_FL", f"{node}:input_FR"]
    for _ in range(40):
        code, out, _ = run(["pw-link", "-i"])
        if code == 0 and set(wanted) <= {line.strip() for line in out.splitlines()}:
            return wanted
        await sleep(0.05)
    raise bridge_error("job_failed", f"The capture node {node} did not appear in PipeWire")


def check_capture_target(path: str, overwrite: bool, allow_audible: bool) -> None:
    if os.path.exists(path) and not overwrite:
        raise bridge_error("target_exists", f"{path} exists; pass overwrite:true to replace it")
    if not os.path.isdir(os.path.dirname(path)):
        raise bridge_error("invalid_params", f"Directory does not exist: {os.path.dirname(path)}")
    if not allow_audible:
        state = describe(current_links())
        if not state["silent"]:
            raise bridge_error(
                "not_isolated",
                "Bitwig output is not routed to the silent sink; call audio.route mode=silent "
                "first, or pass allowAudible:true to record without the silent route (the signal stays audible)",
                **state,
            )


async def stop_recorder(proc: Any) -> None:
    if proc is None or proc.poll() is not None:
        return
    proc.send_signal(signal.SIGINT)  # lets pw-record finalize the WAV header
    try:
        await asyncio.to_thread(proc.wait, 3)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


async def capture(bridge: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    check_platform()
    seconds = args["seconds"]
    path = args["outPath"]
    allow_audible = args.get("allowAudible", False)
    check_capture_target(path, args.get("overwrite", False), allow_audible)
    sources = bitwig_outputs()
    await require_engine(bridge, timeout_ms)
    isolation = await isolate(bridge, args, timeout_ms)
    solo = args.get("soloTrack")
    previous_solo: dict[str, bool] = {}
    changed: list[str] = []
    solo_block: dict[str, Any] = {}
    if solo:
        previous_solo, changed, solo_block = await apply_solo(bridge, solo, timeout_ms)
    from_start = args.get("fromStart", True)
    transport_started = False
    unsettled: dict[str, Any] = {}
    node = f"bwagent-capture-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    created: list[tuple[str, str]] = []
    proc = None
    try:
        proc = spawn(
            [
                "pw-record",
                "-P",
                f"{{ node.name={node} node.autoconnect=false }}",
                "--rate",
                str(RATE),
                "--channels",
                "2",
                "--format",
                "s32",
                path,
            ]
        )
        inputs = await wait_for_inputs(node)
        if proc.poll() is not None:
            err = (proc.stderr.read() or b"").decode(errors="replace").strip() if proc.stderr else ""
            raise bridge_error("job_failed", f"pw-record exited early: {err or proc.returncode}")
        # only links this capture creates; the user's links are never changed
        for src, dst in zip(sources, inputs, strict=False):
            link([src, dst], "connect")
            created.append((src, dst))
        if from_start:
            transport_started = True
            for change in ({"playing": False}, {"positionBeats": 0}, {"playing": True}):
                require_success("transport.set", await bridge.call("transport.set", change, timeout_ms))
        started = time.monotonic()
        await sleep(seconds)
        elapsed = time.monotonic() - started
    finally:
        if transport_started:
            try:
                require_success("transport.set", await bridge.call("transport.set", {"playing": False}, timeout_ms))
            except (BridgeError, UnknownOutcome) as exc:  # the main flow's error is more useful; reported below
                unsettled = _failure(exc)
        await stop_recorder(proc)
        for src, dst in created:
            with contextlib.suppress(BridgeError):
                run(["pw-link", "-d", src, dst])
        if solo:
            solo_block.update(await restore_solo(bridge, previous_solo, changed, timeout_ms))
    stats = await asyncio.to_thread(analysis.analyze, {"path": path, "mode": "level", "windowSeconds": 60})
    if solo_block.get("restoreFailed") and not unsettled:
        unsettled = unknown_result(
            "job_failed",
            "A solo state this capture changed could not be put back (restoreFailed).",
            "Inspect tracks.list and restore the listed solo states with tracks.set.",
        )
    return {
        "outcome": "applied",
        "path": path,
        "requestedSeconds": seconds,
        "elapsedSeconds": round(elapsed, 3),
        "durationSeconds": stats["durationSeconds"],
        "sampleRate": stats["sampleRate"],
        "channels": stats["channels"],
        "peak": stats["peak"],
        "peakDb": stats["peakDb"],
        "rms": stats["rms"],
        "rmsDb": stats["rmsDb"],
        "clipping": stats["clipping"],
        "silent": stats["peak"] < analysis.SILENT_PEAK,
        "fromStart": from_start,
        "audible": allow_audible,
        "transportStopped": transport_started,
        "source": {"ports": sources, "node": node},
        **isolation,
        **solo_block,
        **unsettled,
    }


async def analyze(args: dict[str, Any]) -> dict[str, Any]:
    try:
        return await asyncio.to_thread(analysis.analyze, args)
    except analysis.WavError as exc:
        raise bridge_error("invalid_params", str(exc)) from exc
