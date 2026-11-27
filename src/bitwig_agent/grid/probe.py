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
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from bitwig_agent.audio import pipewire
from bitwig_agent.errors import BridgeError, HostChanged, bridge_error, changed_before, unknown_result
from bitwig_agent.grid.read import GridCall, device_target, host_call, module_names, read_graph
from bitwig_agent.grid.safety import RECORDING_FLAGS
from bitwig_agent.protocol.base import Verification
from bitwig_agent.protocol.limits import MAX_TIMEOUT_MS

AUDIO_OUT = "Audio Out"
MAX_EDITS = 32  # one grid.connections.set batch: all expectedSource checks first, then in order
PASS_THROUGH = ("bpm", "skipSeconds", "threshold", "method", "windowSeconds", "key")
CAPTURE_FIELDS = (
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

Source = tuple[str, str] | None  # (moduleId, portKey) feeding an input, None = unconnected
Input = tuple[str, str]  # (moduleId, input portKey)


def _incoming(cables: list[dict[str, Any]], inputs: set[Input]) -> dict[Input, Source]:
    live = {(c["targetModuleId"], c["targetPortKey"]): (c["sourceModuleId"], c["sourcePortKey"]) for c in cables}
    return {key: live.get(key) for key in inputs}


def _end(source: Source) -> dict[str, Any] | None:
    return None if source is None else {"moduleId": source[0], "portKey": source[1]}


def _edit(key: Input, expected: Source, source: Source) -> dict[str, Any]:
    return {
        "targetModuleId": key[0],
        "targetPortKey": key[1],
        "expectedSource": _end(expected),
        "source": _end(source),
    }


def _label(key: Input, source: Source, names: dict[str, str]) -> dict[str, Any]:
    return {
        "input": f"{names.get(key[0], key[0])}.{key[1]}",
        "source": None if source is None else f"{names.get(source[0], source[0])}.{source[1]}",
    }


@dataclass
class _Rewire:
    """The Audio Out inputs of a device, rewired to hear one port and put back afterwards."""

    read: GridCall
    write: GridCall
    names: dict[str, str]
    original: dict[Input, Source]
    probing: dict[Input, Source]

    @property
    def edits(self) -> list[dict[str, Any]]:
        return [
            _edit(k, self.original[k], self.probing[k]) for k in self.original if self.original[k] != self.probing[k]
        ]

    async def _set(self, edits: list[dict[str, Any]]) -> None:
        for start in range(0, len(edits), MAX_EDITS):
            result = await self.write("grid.connections.set", {"connections": edits[start : start + MAX_EDITS]})
            pipewire.require_success("grid.connections.set", result)

    async def _current(self) -> dict[Input, Source]:
        return _incoming((await read_graph(self.read)).cables, set(self.original))

    async def listen(self) -> None:
        await self._set(self.edits)

    async def restore(self) -> dict[str, Any]:
        """Put every Audio Out input back. A cable is only cut/rewired when it still shows the original or the probe wiring;
        anything else was changed by someone else meanwhile and is left alone and reported."""
        names, original = self.names, self.original
        try:
            current = await self._current()
            edits, foreign = [], []
            for key, want in original.items():
                now = current[key]
                if now == want:
                    continue
                if now == self.probing[key]:
                    edits.append(_edit(key, now, want))
                else:
                    foreign.append({**_label(key, now, names), "wanted": _label(key, want, names)["source"]})
            if edits:
                await self._set(edits)
                current = await self._current()
            left = [
                {**_label(k, current[k], names), "originally": _label(k, original[k], names)["source"]}
                for k in original
                if current[k] != original[k]
            ]
            return {"restored": not left, "left": left, "changedMeanwhile": foreign}
        except (BridgeError, HostChanged) as exc:
            return {
                "restored": False,
                "error": exc.error if isinstance(exc, BridgeError) else exc.result,
                "left": "unknown: reading or writing the graph failed; inspect with grid.export",
                "originally": [_label(k, v, names) for k, v in original.items()],
            }


def _parse_port(port: str) -> tuple[str, str]:
    module, _, port_key = port.rpartition(".")
    if not module or not port_key:
        raise bridge_error("invalid_params", "port must be '<module name>.<OUT port key>', e.g. 'H: osc.OUT'")
    return module, port_key


async def _require_silent(allow_audible: bool) -> None:
    if allow_audible:
        return
    state = pipewire.describe(await asyncio.to_thread(pipewire.current_links))
    if not state["silent"]:
        raise bridge_error(
            "not_isolated",
            "Bitwig output is not routed to the silent sink; call audio.route mode=silent first, "
            "or pass allowAudible:true (the probed signal is then audible)",
            **state,
        )


def _checked_keep_path(args: dict[str, Any]) -> str | None:
    """The wav to keep, refused before any cable is touched."""
    keep: str | None = args.get("outPath")
    if keep:
        if os.path.exists(keep) and not args.get("overwrite", False):
            raise bridge_error("target_exists", f"{keep} exists; pass overwrite:true to replace it")
        if not os.path.isdir(os.path.dirname(keep)):
            raise bridge_error("invalid_params", f"Directory does not exist: {os.path.dirname(keep)}")
    return keep


async def _playing(client: Any, timeout_ms: int) -> bool:
    """Whether the transport plays; refuses recording or automation write. Reads only."""
    transport = await client.call("transport.get", {}, timeout_ms)
    unsafe = [k for k in RECORDING_FLAGS if transport.get(k) is not False]
    if unsafe:
        raise bridge_error(
            "unsafe_state",
            f"Rewiring cables needs recording and automation write observed off; not confirmed off: {', '.join(unsafe)}",
        )
    return transport.get("playing") is True


def _source_row(
    rows: list[dict[str, Any]], names: dict[str, str], duplicates: list[str], module: str, port_key: str
) -> dict[str, Any]:
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
    return source


def _chosen_audio_out(outs: list[dict[str, Any]], original: dict[Input, Source]) -> dict[str, Any]:
    """An existing, already wired Audio Out is reused; the others are silenced for the duration."""
    wired = (r for r in outs if any(original.get((r["instanceId"], i["key"])) for i in r.get("inputs", [])))
    return next(wired, outs[0])


def _plan_rewire(
    rows: list[dict[str, Any]], cables: list[dict[str, Any]], source: dict[str, Any], port_key: str
) -> tuple[dict[str, Any], dict[Input, Source], dict[Input, Source]]:
    """The Audio Out to listen through, and every Audio Out input as it is and as the probe wires it."""
    outs = sorted((r for r in rows if r.get("typeName") == AUDIO_OUT), key=lambda r: (r["y"], r["x"]))
    if not outs:
        raise bridge_error(
            "invalid_params", "This device has no Audio Out module to probe through; add one with grid.apply first"
        )
    original = _incoming(cables, {(r["instanceId"], i["key"]) for r in outs for i in r.get("inputs", [])})
    chosen = _chosen_audio_out(outs, original)
    probing: dict[Input, Source] = dict.fromkeys(original)
    probing[(chosen["instanceId"], chosen["inputs"][0]["key"])] = (source["instanceId"], port_key)
    changing = sum(1 for k in original if original[k] != probing[k])
    if changing > MAX_EDITS:
        raise bridge_error(
            "invalid_params", f"{changing} Audio Out inputs would change; at most {MAX_EDITS} are supported"
        )
    return chosen, original, probing


async def _capture(
    client: Any, rewire: _Rewire, capture_args: dict[str, Any], timeout_ms: int
) -> tuple[dict[str, Any] | None, BaseException | None]:
    try:
        if rewire.edits:  # even a failed batch may have applied entries: restore reads the real state
            await rewire.listen()
        return pipewire.require_success("audio.capture", await pipewire.capture(client, capture_args, timeout_ms)), None
    except (BridgeError, HostChanged, asyncio.CancelledError) as exc:
        return None, exc


def _error_of(error: BaseException | None) -> dict[str, Any] | None:
    if isinstance(error, BridgeError):
        return error.error
    return error.result if isinstance(error, HostChanged) else None


async def _put_back(rewire: _Rewire, capture_error: BaseException | None) -> dict[str, Any]:
    if not rewire.edits:
        return {"restored": True, "left": [], "changedMeanwhile": []}
    restored: dict[str, Any] = await asyncio.shield(rewire.restore())
    if not restored["restored"]:
        raise HostChanged(
            unknown_result(
                "job_failed",
                "The probe could not put every Audio Out cable back; the listed inputs are still changed. "
                "Reconnect them with grid.apply before anything else.",
                left=restored.get("left"),
                originally=restored.get("originally"),
                restoreError=restored.get("error"),
                changedMeanwhile=restored.get("changedMeanwhile"),
                probeError=_error_of(capture_error),
            )
        )
    return restored


async def probe(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    pipewire.check_platform()
    allow_audible = args.get("allowAudible", False)
    module, port_key = _parse_port(args["port"])
    await _require_silent(allow_audible)
    keep = _checked_keep_path(args)
    await pipewire.require_engine(client, timeout_ms)  # before any cable is touched
    short = min(timeout_ms, MAX_TIMEOUT_MS)  # only topology edits may wait longer
    playing = await _playing(client, short)
    target = device_target(args)
    read = host_call(client, target, short)
    rows, cables = await read_graph(read)
    names, _, duplicates = module_names(rows)
    source = _source_row(rows, names, duplicates, module, port_key)
    chosen, original, probing = _plan_rewire(rows, cables, source, port_key)
    rewire = _Rewire(read, host_call(client, target, timeout_ms), names, original, probing)
    if playing:  # rewiring is a structural write; audio.capture stops the transport again afterwards
        pipewire.require_success("transport.set", await client.call("transport.set", {"playing": False}, short))
    workdir = Path(tempfile.mkdtemp(prefix="bitwig-probe-"))
    wav = keep or str(workdir / "probe.wav")
    capture_args = {
        "seconds": args["seconds"],
        "outPath": wav,
        "allowAudible": allow_audible,
        "fromStart": True,
        "overwrite": bool(keep),
    }
    try:
        captured, capture_error = await _capture(client, rewire, capture_args, short)
        restored = await _put_back(rewire, capture_error)
        if capture_error is not None:
            raise capture_error
        assert captured is not None
        analysis_args = {"path": wav, "mode": args["mode"], **{k: args[k] for k in PASS_THROUGH if k in args}}
        analysis = await pipewire.analyze(analysis_args)
    except BridgeError as exc:
        if not (playing or rewire.edits):
            raise
        raise changed_before(exc, transportStopped=playing, cablesChanged=len(rewire.edits), restored=True) from exc
    except HostChanged as exc:
        exc.result.setdefault("transportStopped", playing)
        raise
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    analysis.pop("path", None)
    return {
        "outcome": "applied",
        "verification": Verification.FILE_READBACK,
        "port": args["port"],
        "mode": args["mode"],
        "audioOut": names[chosen["instanceId"]],
        "cablesChanged": len(rewire.edits),
        "restored": True,
        **({"wavPath": keep} if keep else {}),
        **({"changedMeanwhile": restored["changedMeanwhile"]} if restored["changedMeanwhile"] else {}),
        "capture": {k: captured[k] for k in CAPTURE_FIELDS},
        "analysis": analysis,
        "transportStopped": True,
    }
