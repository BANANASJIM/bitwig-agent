"""Client-side `grid.probe`: listen to one Grid output port, then put the cables back exactly.

Snapshot the cables into every Audio Out of the device, reroute the probe port to one Audio Out, record with
`audio.capture`, restore from a fresh read (each cable compare-and-set against what this call put there) and analyze.
Nothing is retried; a restore that cannot finish is reported cable by cable.
"""

from __future__ import annotations

import asyncio
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any

from bitwig_agent import audio
from bitwig_agent.errors import BridgeError, bridge_error
from bitwig_agent.grid_export import module_names, read_all
from bitwig_agent.schema import MAX_TIMEOUT_MS

AUDIO_OUT = "Audio Out"
MAX_EDITS = 32  # one grid.connections.set batch: all expectedSource checks first, then in order
UNSAFE_FLAGS = ("arrangerRecordEnabled", "arrangerAutomationWriteEnabled", "clipLauncherAutomationWriteEnabled")
PASS_THROUGH = ("bpm", "skipSeconds", "threshold", "method", "windowSeconds", "key")


Source = tuple[str, str] | None  # (moduleId, portKey) feeding an input, None = unconnected


def _incoming(cables: list[dict[str, Any]], inputs: set[tuple[str, str]]) -> dict[tuple[str, str], Source]:
    live = {(c["targetModuleId"], c["targetPortKey"]): (c["sourceModuleId"], c["sourcePortKey"]) for c in cables}
    return {key: live.get(key) for key in inputs}


def _end(source: Source) -> dict[str, Any] | None:
    return None if source is None else {"moduleId": source[0], "portKey": source[1]}


def _edit(key: tuple[str, str], expected: Source, source: Source) -> dict[str, Any]:
    return {
        "targetModuleId": key[0],
        "targetPortKey": key[1],
        "expectedSource": _end(expected),
        "source": _end(source),
    }


async def _set(client: Any, target: dict[str, Any], edits: list[dict[str, Any]], timeout_ms: int) -> None:
    for start in range(0, len(edits), MAX_EDITS):
        result = await client.call(
            "grid.connections.set", {**target, "connections": edits[start : start + MAX_EDITS]}, timeout_ms
        )
        if result.get("batchStatus") != "applied":
            raise bridge_error(
                "not_applied",
                f"grid.connections.set reported batchStatus {result.get('batchStatus')!r}",
                "unknown",
                result=result,
            )


def _label(key: tuple[str, str], source: Source, names: dict[str, str]) -> dict[str, Any]:
    return {
        "input": f"{names.get(key[0], key[0])}.{key[1]}",
        "source": None if source is None else f"{names.get(source[0], source[0])}.{source[1]}",
    }


async def _restore(  # noqa: PLR0913, PLR0917
    client: Any,
    target: dict[str, Any],
    original: dict[tuple[str, str], Source],
    probing: dict[tuple[str, str], Source],
    names: dict[str, str],
    timeout_ms: int,
) -> dict[str, Any]:
    """Put every Audio Out input back. A cable is only cut/rewired when it still shows the original or the probe wiring;
    anything else was changed by someone else meanwhile and is left alone and reported."""
    try:
        _rows, cables = await read_all(client, target, min(timeout_ms, MAX_TIMEOUT_MS), False)
        current = _incoming(cables, set(original))
        edits, foreign = [], []
        for key, want in original.items():
            now = current[key]
            if now == want:
                continue
            if now == probing[key]:
                edits.append(_edit(key, now, want))
            else:
                foreign.append({**_label(key, now, names), "wanted": _label(key, want, names)["source"]})
        if edits:
            await _set(client, target, edits, timeout_ms)
            _, cables = await read_all(client, target, min(timeout_ms, MAX_TIMEOUT_MS), False)
            current = _incoming(cables, set(original))
        left = [
            {**_label(k, current[k], names), "originally": _label(k, original[k], names)["source"]}
            for k in original
            if current[k] != original[k]
        ]
        return {"restored": not left, "left": left, "changedMeanwhile": foreign}
    except BridgeError as exc:
        return {
            "restored": False,
            "error": exc.error,
            "left": "unknown: reading or writing the graph failed; inspect with grid.graph",
            "originally": [_label(k, v, names) for k, v in original.items()],
        }


async def probe(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:  # noqa: C901, PLR0912, PLR0915
    audio.check_platform()
    target = {k: args[k] for k in ("trackId", "deviceIndex", "expectedDeviceName")}
    allow_audible = args.get("allowAudible", False)
    module, _, port_key = args["port"].rpartition(".")
    if not module or not port_key:
        raise bridge_error("invalid_params", "port must be '<module name>.<OUT port key>', e.g. 'H: osc.OUT'")
    if not allow_audible:
        state = audio.describe(await asyncio.to_thread(audio.current_links))
        if not state["silent"]:
            raise bridge_error(
                "not_silent",
                "Bitwig output is not routed to the silent sink; call audio.route mode=silent first, "
                "or pass allowAudible:true (the probed signal is then audible)",
                **state,
            )
    keep = args.get("outPath")
    if keep:  # refuse before any cable is touched
        if os.path.exists(keep) and not args.get("overwrite", False):
            raise bridge_error("file_exists", f"{keep} exists; pass overwrite:true to replace it")
        if not os.path.isdir(os.path.dirname(keep)):
            raise bridge_error("invalid_params", f"Directory does not exist: {os.path.dirname(keep)}")
    await audio.require_engine(client, timeout_ms)  # before any cable is touched
    # Grid writes need a stopped, non-recording transport; audio.capture stops it again afterwards, so this leaves it stopped too.
    short = min(timeout_ms, MAX_TIMEOUT_MS)  # only topology edits may wait longer
    transport = await client.call("transport.get", {}, short)
    unsafe = [k for k in UNSAFE_FLAGS if transport.get(k) is not False]
    if unsafe:
        raise bridge_error(
            "unsafe_state",
            f"Grid writes need recording and automation write observed off; not confirmed off: {', '.join(unsafe)}",
        )
    if transport.get("playing") is True:
        await client.call("transport.set", {"playing": False}, short)
    rows, cables = await read_all(client, target, short, False)
    names, _, duplicates = module_names(rows)
    by_name = {names[r["instanceId"]]: r for r in rows}
    source = by_name.get(module)
    if source is None or module in duplicates:
        raise bridge_error(
            "invalid_params",
            f"No module named {module!r} with a unique name in this device; names: {sorted(by_name)[:30]}",
        )
    if port_key not in {o["key"] for o in source.get("outputs", [])}:
        raise bridge_error(
            "invalid_params",
            f"{module!r} has no output port {port_key!r}; outputs: {[o['key'] for o in source.get('outputs', [])]}",
        )
    outs = sorted((r for r in rows if r.get("typeName") == AUDIO_OUT), key=lambda r: (r["y"], r["x"]))
    if not outs:
        raise bridge_error(
            "invalid_params", "This device has no Audio Out module to probe through; add one with grid.apply first"
        )
    inputs = {(r["instanceId"], i["key"]) for r in outs for i in r.get("inputs", [])}
    original = _incoming(cables, inputs)
    # An existing, already wired Audio Out is reused; the others are silenced for the duration.
    chosen = next(
        (r for r in outs if any(original.get((r["instanceId"], i["key"])) for i in r.get("inputs", []))), outs[0]
    )
    probe_end = (source["instanceId"], port_key)
    probing: dict[tuple[str, str], Source] = dict.fromkeys(original)
    probing[(chosen["instanceId"], chosen["inputs"][0]["key"])] = probe_end
    edits = [_edit(k, original[k], probing[k]) for k in original if original[k] != probing[k]]
    if len(edits) > MAX_EDITS:
        raise bridge_error(
            "invalid_params", f"{len(edits)} Audio Out inputs would change; at most {MAX_EDITS} are supported"
        )
    seconds = args["seconds"]
    workdir = Path(tempfile.mkdtemp(prefix="bitwig-probe-"))
    wav = keep or str(workdir / "probe.wav")
    captured: dict[str, Any] | None = None
    capture_error: BaseException | None = None
    rewired = False
    try:
        try:
            if edits:
                rewired = True  # even a failed batch may have applied entries: restore reads the real state
                await _set(client, target, edits, timeout_ms)
            captured = await audio.capture(
                client,
                {
                    "seconds": seconds,
                    "outPath": wav,
                    "allowAudible": allow_audible,
                    "fromStart": True,
                    "overwrite": bool(keep),
                },
                short,
            )
        except (BridgeError, asyncio.CancelledError) as exc:
            capture_error = exc
        restored = (
            await asyncio.shield(_restore(client, target, original, probing, names, timeout_ms))
            if rewired
            else {"restored": True, "left": [], "changedMeanwhile": []}
        )
        if not restored["restored"]:
            raise bridge_error(
                "probe_restore_failed",
                "The probe could not put every Audio Out cable back; the listed inputs are still changed. "
                "Reconnect them with grid.connections.set (or grid.apply) before anything else.",
                "unknown",
                left=restored.get("left"),
                originally=restored.get("originally"),
                restoreError=restored.get("error"),
                changedMeanwhile=restored.get("changedMeanwhile"),
                probeError=capture_error.error if isinstance(capture_error, BridgeError) else None,
            )
        if capture_error is not None:
            raise capture_error
        assert captured is not None
        analysis_args = {"path": wav, "mode": args["mode"], **{k: args[k] for k in PASS_THROUGH if k in args}}
        analysis = await audio.analyze(analysis_args)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    for key in ("path",):
        analysis.pop(key, None)
    return {
        "status": "probed",
        "port": args["port"],
        "mode": args["mode"],
        "audioOut": names[chosen["instanceId"]],
        "cablesChanged": len(edits),
        "restored": True,
        **({"wavPath": keep} if keep else {}),
        **({"changedMeanwhile": restored["changedMeanwhile"]} if restored["changedMeanwhile"] else {}),
        "capture": {
            k: captured[k]
            for k in (
                "durationSeconds",
                "peak",
                "peakDb",
                "rms",
                "rmsDb",
                "clipping",
                "silent",
                "audible",
                "transportStopped",
            )
        },
        "analysis": analysis,
        "transportStopped": True,
    }
