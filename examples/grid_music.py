#!/usr/bin/env python3
"""Create two muted Grid music tracks using native presets and the public toolkit.

Run only in an explicitly named disposable project. Existing tracks/clips are
never overwritten. This is an ordinary client recipe, not a transaction: failure
leaves recorded partial changes for inspection, and no request is retried.
Audio rendering and native GUI verification are separate E2E steps.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

from bitwig_agent.client import BridgeClient, BridgeError, configured_port


def note(beat: float, pitch: int, duration: float, velocity: int) -> dict:
    return {"op": "set", "beat": beat, "pitch": pitch,
            "durationBeats": duration, "velocity": velocity}


def pluck_phrase() -> list[dict]:
    pitches = [60, 64, 67, 72, 67, 64, 62, 67, 59, 62, 67, 71, 67, 62, 57, 64]
    return [note(i * 0.5, pitch, 0.25, 76 + (i % 4) * 5)
            for i, pitch in enumerate(pitches)]


def pad_phrase() -> list[dict]:
    return [note(beat, pitch, 3.5, 72)
            for beat, pitches in [(0.0, (48, 55, 60, 64)), (4.0, (45, 52, 57, 60))]
            for pitch in pitches]


async def create(args: argparse.Namespace) -> int:
    presets = [("Pluck", args.pluck_preset, pluck_phrase()),
               ("Triangle Pad", args.pad_preset, pad_phrase())]
    for _, path, _ in presets:
        if not path.is_file() or path.suffix.lower() != ".bwpreset":
            raise ValueError(f"Expected an existing native .bwpreset: {path}")
    if not args.allow_write:
        raise ValueError("Pass --allow-write only after selecting the disposable project")
    args.artifacts.mkdir(parents=True, exist_ok=False)
    client = BridgeClient(configured_port())
    report = {"expectedProjectName": args.project, "status": "running", "createdTracks": [],
              "scope": "muted MIDI/preset authoring; not audio, topology API or persistence verification",
              "presets": [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                          for _, path, _ in presets]}

    async def call(method: str, params: dict | None = None) -> dict:
        if method in {"tracks.create", "tracks.set", "devices.insert",
                      "launcher.create", "notes.edit", "notes.read"}:
            await safety_guard()
        params = params or {}
        entry = {"at": datetime.now(timezone.utc).isoformat(), "method": method, "params": params}
        try:
            result = await client.call(method, params)
            entry["result"] = result
        except BridgeError as error:
            entry["error"] = error.error
            if error.error.get("outcome") == "unknown":
                report.setdefault("uncertainOperations", []).append(entry)
            raise
        finally:
            with (args.artifacts / "calls.jsonl").open("a", encoding="utf-8") as output:
                output.write(json.dumps(entry, ensure_ascii=False, allow_nan=False) + "\n")
        if result.get("status") != "observed":
            report.setdefault("uncertainOperations", []).append(entry)
            raise RuntimeError(f"{method} did not establish its postcondition; inspect calls.jsonl before another write")
        return result

    async def safety_guard() -> None:
        transport = await call("transport.get")
        if transport.get("projectName") != args.project:
            raise RuntimeError("Active project name changed; refusing further writes")

        if any(transport.get(key) is not False for key in ("arrangerRecordEnabled",
                                                         "arrangerAutomationWriteEnabled",
                                                         "clipLauncherAutomationWriteEnabled")):
            raise RuntimeError("Recording/automation is enabled or unobserved; use a separate stopped fixture")
        if transport.get("playing") is not False:
            raise RuntimeError("Playback is active or unobserved; stop the disposable project before running this recipe")

    try:
        await safety_guard()
        tracks = await call("tracks.list")
        if not tracks["window"]["complete"] or len(tracks["tracks"]) + len(presets) > tracks["window"]["capacity"]:
            raise RuntimeError("Both new tracks must fit the completely observed track window")
        for label, path, edits in presets:
            created = await call("tracks.create", {"type": "instrument", "position": -1,
                                                   "name": f"Toolkit {label}"})
            track_id = created["trackId"]
            report["createdTracks"].append({"trackId": track_id, "label": label})
            await call("tracks.set", {"trackId": track_id, "mute": True, "arm": False,
                                      "volumeNormalized": 0.5})
            await call("devices.insert", {"trackId": track_id, "expectedDeviceCount": 0,
                                          "kind": "file", "path": str(path)})
            devices = await call("devices.list", {"trackId": track_id})
            if len(devices["devices"]) != 1:
                raise RuntimeError("New track does not contain exactly one inserted preset device")
            await call("launcher.create", {"trackId": track_id, "slotIndex": 0,
                                           "lengthBeats": 8, "name": f"{label} eight-beat sketch"})
            window = {"trackId": track_id, "slotIndex": 0, "channel": 0,
                      "stepSizeBeats": 0.25, "startStep": 0, "steps": 32,
                      "pitchStart": 0, "pitchCount": 128}
            await call("notes.edit", {**window, "edits": edits})
            readback = await call("notes.read", window)
            report["createdTracks"][-1]["noteReadback"] = readback
            # Clip selection can auto-arm according to existing host preferences.
            # Restore safety explicitly without changing those user preferences.
            await call("tracks.set", {"trackId": track_id, "mute": True, "arm": False})
            observed = {(item["beat"], item["pitch"]): item for item in readback["notes"]}
            expected = {(item["beat"], item["pitch"]): item for item in edits}
            if not readback["completeWindow"] or observed.keys() != expected.keys():
                raise RuntimeError("Observed note onsets differ from the authored phrase")
            for key, wanted in expected.items():
                actual = observed[key]
                if (abs(actual["durationBeats"] - wanted["durationBeats"]) > 1e-6
                        or abs(actual["velocityNormalized"] - wanted["velocity"] / 127) > 1 / 127):
                    raise RuntimeError(f"Observed duration/velocity differs at {key}")
        report["status"] = "authored_observed"
        report["next"] = "Independently inspect exact notes and loaded timbres, render muted pre-fader audio, and save/reopen the disposable project. No automatic save, play, undo or retry was performed."
        return 0
    except (BridgeError, RuntimeError, ValueError, KeyError) as error:
        report["status"] = "incomplete_inspect_state"
        report["error"] = str(error)
        report["recovery"] = "Inspect calls.jsonl and createdTracks. Never rerun the whole recipe blindly: earlier creation/writes may have succeeded."
        return 1
    finally:
        (args.artifacts / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Exact active disposable project name; not a full-path identity guarantee")
    parser.add_argument("--pluck-preset", type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--pad-preset", type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--artifacts", type=lambda value: Path(value).resolve(), required=True,
                        help="New output directory; existing evidence is never overwritten")
    parser.add_argument("--allow-write", action="store_true")
    args = parser.parse_args()
    try:
        return asyncio.run(create(args))
    except (ValueError, OSError) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
