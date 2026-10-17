#!/usr/bin/env python3
"""Create two muted Grid music tracks from modules or explicit native presets.

Run only in an explicitly named disposable project. Existing tracks/clips are
never overwritten. This is an ordinary client recipe, not a transaction: failure
leaves recorded partial changes for inspection, and no request is retried.
Audio rendering and native GUI verification are separate E2E steps. The scratch
Pad needs an explicit GUI step: set its Poly Grid VOICES to 8 before rendering.
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


def checked_graph(graph: dict, target: dict, observed_target: dict | None = None) -> dict[str, dict]:
    """Require one fully covered owned module window, not a lossless export."""
    identity = graph["target"] if observed_target is None else observed_target
    if any(identity.get(key) != value for key, value in target.items()):
        raise RuntimeError("Grid readback target differs from the owned device")
    if identity.get("nativeOwningTrack") != "corroborated_before_and_after_capture":
        raise RuntimeError("Grid native owning track was not corroborated")
    window = graph["window"]
    if (window.get("complete") is not True or window.get("offset") != 0
            or window.get("total") != len(graph["modules"])
            or graph["coverage"].get("structure") != "mapped_modules_and_ports"):
        raise RuntimeError("Grid module/port window is incomplete")
    modules = {module["instanceId"]: module for module in graph["modules"]}
    if len(modules) != len(graph["modules"]):
        raise RuntimeError("Grid module identities are ambiguous")
    incoming = set()
    for module in modules.values():
        for direction in ("inputs", "outputs"):
            keys = [port["key"] for port in module[direction]]
            if len(set(keys)) != len(keys):
                raise RuntimeError("Grid direction-specific port keys are ambiguous")
    for connection in graph["connections"]:
        source_id, destination_id = connection["sourceModuleId"], connection["targetModuleId"]
        endpoint = (destination_id, connection["targetPortKey"])
        if (source_id not in modules or destination_id not in modules or endpoint in incoming
                or connection["sourcePortKey"] not in {port["key"] for port in modules[source_id]["outputs"]}
                or connection["targetPortKey"] not in {port["key"] for port in modules[destination_id]["inputs"]}):
            raise RuntimeError("Grid cable endpoint is missing or ambiguous in the owned graph")
        incoming.add(endpoint)
    for module_id, module in modules.items():
        for port in module["inputs"]:
            if port.get("connected") is not ((module_id, port["key"]) in incoming):
                raise RuntimeError("Grid input connection state differs from stored cables")
    return modules


def checked_notes(readback: dict, window: dict, edits: list[dict]) -> None:
    if any(readback.get(key) != window[key] for key in ("trackId", "slotIndex")):
        raise RuntimeError("Note readback target differs from the owned clip")
    if any(readback["window"].get(key) != value for key, value in window.items()
           if key not in {"trackId", "slotIndex"}):
        raise RuntimeError("Note readback window differs from the authored window")
    observed = {(item["beat"], item["pitch"]): item for item in readback["notes"]}
    expected = {(item["beat"], item["pitch"]): item for item in edits}
    if (readback.get("completeWindow") is not True
            or len(observed) != len(readback["notes"]) or observed.keys() != expected.keys()
            or readback.get("continuingAtWindowStart") != []
            or readback.get("loopStartBeats") != 0 or readback.get("loopLengthBeats") != 8
            or readback.get("loopEnabled") is not True or readback.get("playStartBeats") != 0):
        raise RuntimeError("Observed note onsets or clip bounds differ from the authored phrase")
    for key, wanted in expected.items():
        actual = observed[key]
        if (abs(actual["durationBeats"] - wanted["durationBeats"]) > 1e-6
                or abs(actual["velocityNormalized"] - wanted["velocity"] / 127) > 1 / 127):
            raise RuntimeError(f"Observed duration/velocity differs at {key}")


def checked_patch(graph: dict, target: dict, expected: dict) -> None:
    modules = checked_graph(graph, target)
    if {key: module["typeId"] for key, module in modules.items()} != expected["moduleTypes"]:
        raise RuntimeError("Owned Grid module identities/types changed")
    fields = ("sourceModuleId", "sourcePortKey", "targetModuleId", "targetPortKey")
    if sorted(tuple(item[key] for key in fields) for item in graph["connections"]) != sorted(
            tuple(item[key] for key in fields) for item in expected["connections"]):
        raise RuntimeError("Owned Grid cables differ from the authored patch")
    for wanted in expected["parameters"]:
        matches = [parameter for parameter in modules[wanted["moduleId"]]["parameters"]
                   if parameter.get("parameterId") == wanted["parameterId"]]
        if (len(matches) != 1 or matches[0].get("supported") is not True
                or matches[0].get("kind") != wanted["kind"]
                or matches[0].get("value") != wanted["value"]):
            raise RuntimeError("Owned Grid control differs from the authored patch")


async def build_grid(call, target: dict, types: dict[str, str], label: str) -> dict:
    """Build only inside this recipe's newly inserted, owned factory device."""
    graph = await call("grid.get", {**target, "includeParameters": False})
    modules = checked_graph(graph, target)
    # Remove only this new device's observed defaults, then insert actual native
    # MODULE resources: factory initialization is not patch construction.
    for module_id in list(modules):
        result = await call("grid.modules.delete", {**target, "moduleId": module_id})
        after = checked_graph(result["graph"], target, result["target"])
        if set(after) != set(modules) - {module_id}:
            raise RuntimeError("Deleting an owned default changed unexpected modules")
        modules = after
    for x, name in enumerate(("Union", "ADSR", "Audio Out")):
        result = await call("grid.modules.insert", {**target, "typeId": types[name], "x": x * 4, "y": 0})
        after = checked_graph(result["graph"], target, result["target"])
        added = set(after) - set(modules)
        if (len(added) != 1 or set(after) - added != set(modules)
                or result.get("moduleId") not in added
                or after[result["moduleId"]]["typeId"] != types[name]):
            raise RuntimeError("Insertion did not establish the requested owned module")
        modules = after
    graph = await call("grid.get", target)
    modules = checked_graph(graph, target)
    by_name = {}
    for name, type_id in types.items():
        matches = [module for module in modules.values() if module["typeId"] == type_id]
        if len(matches) != 1:
            raise RuntimeError(f"Missing or ambiguous {name} module")
        by_name[name] = matches[0]

    def port(module_name: str, direction: str, name: str) -> dict:
        module = by_name[module_name]
        matches = [item for item in module[direction] if item.get("name") == name]
        if len(matches) != 1:
            raise RuntimeError(f"Missing or ambiguous {module_name} {direction} {name}")
        return {"moduleId": module["instanceId"], "portKey": matches[0]["key"]}

    wires = [(port("Union", "outputs", "Signal Out"), port("ADSR", "inputs", "Signal In")),
             (port("ADSR", "outputs", "Signal Out"), port("Audio Out", "inputs", "Audio In"))]
    if graph["connections"]:
        raise RuntimeError("Newly inserted modules have unexpected stored cables")
    # Native-observed Analog ADSR RAW settings, not seconds or a unit parser:
    # pluck 2 ms/180 ms/0%/100 ms; pad 350 ms/800 ms/70%/1.20 s.
    envelope = ((0.12599210498948732, 0.5646216173286172, 0.0, 0.4641588833612779)
                if label == "Pluck" else
                (0.7047298732064893, 0.9283177667225558, 0.7, 1.0626585691826111))
    settings = [("Union", "Key Tracking", "boolean", True),
                ("Union", "Pitch Offset", "float", 0.0),
                ("Union", "Saw Level", "float", 0.0),
                # Eight Pad voices cover four-note chords plus overlapping releases.
                ("Union", "Triangle Level", "float", 1.0 if label == "Pluck" else 0.125),
                ("Union", "Pulse Level", "float", 0.0),
                ("ADSR", "Gate on Notes", "boolean", True),
                ("ADSR", "Affect Voice Lifetime", "boolean", True)]
    settings += [("ADSR", name, "float", value)
                 for name, value in zip(("Attack", "Decay", "Sustain", "Release"), envelope)]
    # Resolve EVERY needed port/control before dependent control/cable writes.
    expected = {"moduleTypes": {key: value["typeId"] for key, value in modules.items()},
                "parameters": [], "connections": []}
    for module_name, name, kind, value in settings:
        module = by_name[module_name]
        matches = [item for item in module["parameters"] if item.get("name") == name]
        if len(matches) != 1 or matches[0].get("supported") is not True or matches[0].get("kind") != kind:
            raise RuntimeError(f"Missing, ambiguous or unsupported {module_name} control {name}")
        if (sum(item.get("parameterId") == matches[0]["parameterId"] for item in module["parameters"]) != 1
                or kind == "float" and not matches[0]["minimum"] <= value <= matches[0]["maximum"]):
            raise RuntimeError(f"Ambiguous identifier or unsupported raw range: {module_name} {name}")
        expected["parameters"].append({"moduleId": module["instanceId"],
                                       "parameterId": matches[0]["parameterId"], "kind": kind, "value": value})
    model = [item for item in by_name["ADSR"]["parameters"] if item.get("name") == "Model"]
    if len(model) != 1 or model[0].get("supported") is not True or model[0].get("kind") != "enum":
        raise RuntimeError("Calibrated envelope requires an observed ADSR Model control")
    analog = [option["value"] for option in model[0]["options"] if option.get("label") == "Analog"]
    if (len(analog) != 1
            or sum(item.get("parameterId") == model[0]["parameterId"]
                   for item in by_name["ADSR"]["parameters"]) != 1):
        raise RuntimeError("Calibrated envelope requires one unambiguous Analog ADSR model")
    expected["parameters"].insert(0, {"moduleId": by_name["ADSR"]["instanceId"],
                                      "parameterId": model[0]["parameterId"], "kind": "enum", "value": analog[0]})
    for wanted in expected["parameters"]:
        address = {**target, "moduleId": wanted["moduleId"], "parameterId": wanted["parameterId"]}
        fresh = await call("grid.parameters.get", address)
        actual = fresh["parameter"]
        if (fresh.get("moduleId") != wanted["moduleId"] or actual.get("supported") is not True
                or actual.get("parameterId") != wanted["parameterId"] or actual.get("kind") != wanted["kind"]):
            raise RuntimeError("Fresh Grid control target/kind differs from discovery")
        if actual["value"] != wanted["value"]:
            written = await call("grid.parameters.set", {**address, "expectedKind": actual["kind"],
                                                         "expectedValue": actual["value"], "value": wanted["value"]})
            if (written.get("moduleId") != wanted["moduleId"]
                    or any(written["parameter"].get(key) != wanted[key] for key in ("parameterId", "kind", "value"))):
                raise RuntimeError("Native parameter write did not establish the requested control value")
    for source, destination in wires:
        result = await call("grid.connections.set", {**target, "targetModuleId": destination["moduleId"],
                                                    "targetPortKey": destination["portKey"],
                                                    "expectedSource": None, "source": source})
        if result.get("matchesRequested") is not True or result.get("actualSource") != source:
            raise RuntimeError("Native cable repair did not retain the requested source")
        after = checked_graph(result["graph"], target, result["target"])
        if {key: module["typeId"] for key, module in after.items()} != expected["moduleTypes"]:
            raise RuntimeError("Native cable write changed the owned module identities/types")
        expected["connections"].append({"sourceModuleId": source["moduleId"], "sourcePortKey": source["portKey"],
                                         "targetModuleId": destination["moduleId"], "targetPortKey": destination["portKey"]})
        fields = ("sourceModuleId", "sourcePortKey", "targetModuleId", "targetPortKey")
        if sorted(tuple(item[key] for key in fields) for item in result["graph"]["connections"]) != sorted(
                tuple(item[key] for key in fields) for item in expected["connections"]):
            raise RuntimeError("Native cable graph differs from the requested patch")
    graph = await call("grid.get", target)
    checked_patch(graph, target, expected)
    return {"target": target, "expectedPatch": expected, "graphReadback": graph}


def report_summary(report: dict, artifacts: Path) -> dict:
    """Keep agent stdout small; full observations remain in the artifact files."""
    result = {"status": report["status"], "mode": report["mode"],
              "reportPath": str(artifacts / "report.json"),
              "callsPath": str(artifacts / "calls.jsonl"),
              "createdTracks": [{"trackId": item["trackId"], "label": item["label"],
                                 "slotIndex": item.get("slotIndex"),
                                 "noteCount": item.get("noteCount")}
                                for item in report["createdTracks"]]}
    if "next" in report:
        result["next"] = report["next"]
    if "error" in report:
        result["error"] = report["error"]
        result["recovery"] = report["recovery"]
    return result


async def create(args: argparse.Namespace) -> int:
    scratch = getattr(args, "from_scratch", False)
    presets = [("Pluck", args.pluck_preset, pluck_phrase()),
               ("Triangle Pad", args.pad_preset, pad_phrase())]
    if scratch and any(path is not None for _, path, _ in presets):
        raise ValueError("--from-scratch cannot be combined with either preset path")
    if not scratch:
        if any(path is None for _, path, _ in presets):
            raise ValueError("Choose --from-scratch or supply both --pluck-preset and --pad-preset")
        for _, path, _ in presets:
            if not path.is_file() or path.suffix.lower() != ".bwpreset":
                raise ValueError(f"Expected an existing native .bwpreset: {path}")
    if not args.allow_write:
        raise ValueError("Pass --allow-write only after selecting the disposable project")
    args.artifacts.mkdir(parents=True, exist_ok=False)
    client = BridgeClient(configured_port())
    report = {"expectedProjectName": args.project, "status": "running", "createdTracks": [],
              "mode": "from_scratch" if scratch else "supplied_presets",
              "scope": "owned muted MIDI and native model authoring; not audio, DSP or persistence verification",
              "presets": [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                          for _, path, _ in presets if path is not None]}

    async def call(method: str, params: dict | None = None) -> dict:
        if method in {"tracks.create", "tracks.set", "devices.insert",
                      "launcher.create", "notes.edit", "notes.read", "grid.modules.insert",
                      "grid.modules.delete", "grid.parameters.set", "grid.connections.set"}:
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
        except BaseException as error:
            entry["exception"] = f"{type(error).__name__}: {error}"
            report.setdefault("uncertainOperations", []).append(entry)
            raise
        finally:
            with (args.artifacts / "calls.jsonl").open("a", encoding="utf-8") as output:
                output.write(json.dumps(entry, ensure_ascii=False, allow_nan=False) + "\n")
        if (result.get("status") != "observed" or result.get("completion") == "unknown"
                or result.get("topologyQuarantined") is True or result.get("topologyAdmissionBlocked") is True):
            report.setdefault("uncertainOperations", []).append(entry)
            raise RuntimeError(f"{method} did not establish its postcondition; inspect calls.jsonl before another write")
        if "expectedDeviceName" in params:
            identity = result["target"]
            if (any(identity.get(key) != params[key] for key in ("trackId", "deviceIndex", "expectedDeviceName"))
                    or identity.get("nativeOwningTrack") != "corroborated_before_and_after_capture"):
                raise RuntimeError(f"{method} did not corroborate the owned Grid target")
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
        types = {}
        factory_path = None
        if scratch:
            catalog = await call("devices.catalog")
            candidates = [item for item in catalog["devices"]
                          if item.get("name") == "Poly Grid" and item.get("kind") == "file"
                          and item.get("sourceKind") == "installed_bitwig_factory_device"]
            if catalog.get("supported") is not True or len(candidates) != 1:
                raise RuntimeError("Exactly one installed factory Poly Grid file must be observed")
            factory_path = candidates[0]["path"]
            report["factoryDevice"] = candidates[0]
            for name in ("Union", "ADSR", "Audio Out"):
                catalog = await call("grid.catalog", {"query": name, "limit": 256})
                matches = [item for item in catalog["entries"] if item.get("name") == name]
                if catalog["window"].get("complete") is not True or len(matches) != 1:
                    raise RuntimeError(f"Missing or ambiguous complete native MODULE catalog entry: {name}")
                types[name] = matches[0]["typeId"]
        for label, path, edits in presets:
            created = await call("tracks.create", {"type": "instrument", "position": -1,
                                                   "name": f"Toolkit {label}"})
            track_id = created["trackId"]
            if not track_id or track_id in {item["trackId"] for item in tracks["tracks"] + report["createdTracks"]}:
                raise RuntimeError("Creation did not identify a new owned track")
            report["createdTracks"].append({"trackId": track_id, "label": label})
            await call("tracks.set", {"trackId": track_id, "mute": True, "arm": False,
                                      "volumeNormalized": 0.5})
            empty = await call("devices.list", {"trackId": track_id})
            if empty.get("trackId") != track_id or empty["window"].get("complete") is not True or empty["devices"]:
                raise RuntimeError("Owned new track does not have a completely observed empty device chain")
            await call("devices.insert", {"trackId": track_id, "expectedDeviceCount": 0,
                                          "kind": "file", "path": factory_path if scratch else str(path)})
            devices = await call("devices.list", {"trackId": track_id})
            if (devices.get("trackId") != track_id or devices["window"].get("complete") is not True
                    or len(devices["devices"]) != 1):
                raise RuntimeError("New track does not contain exactly one observed inserted device")
            if scratch:
                device = devices["devices"][0]
                if device["name"] != "Poly Grid":
                    raise RuntimeError("Inserted device is not the observed factory Poly Grid")
                target = {"trackId": track_id, "deviceIndex": device["deviceIndex"],
                          "expectedDeviceName": device["name"]}
                report["createdTracks"][-1]["patch"] = await build_grid(call, target, types, label)
            await call("launcher.create", {"trackId": track_id, "slotIndex": 0,
                                           "lengthBeats": 8, "name": f"{label} eight-beat sketch"})
            report["createdTracks"][-1]["slotIndex"] = 0
            window = {"trackId": track_id, "slotIndex": 0, "channel": 0,
                      "stepSizeBeats": 0.25, "startStep": 0, "steps": 32,
                      "pitchStart": 0, "pitchCount": 128}
            await call("notes.edit", {**window, "edits": edits})
            readback = await call("notes.read", window)
            report["createdTracks"][-1]["noteReadback"] = readback
            # Clip selection can auto-arm according to existing host preferences.
            # Restore safety explicitly without changing those user preferences.
            await call("tracks.set", {"trackId": track_id, "mute": True, "arm": False})
            checked_notes(readback, window, edits)
            report["createdTracks"][-1]["noteCount"] = len(readback["notes"])
        await safety_guard()
        for owned in report["createdTracks"]:
            state = await call("tracks.get", {"trackId": owned["trackId"]})
            if state.get("trackId") != owned["trackId"] or state.get("mute") is not True or state.get("arm") is not False:
                raise RuntimeError("Owned output track is not observed muted and disarmed")
            if scratch:
                patch = owned["patch"]
                graph = await call("grid.get", patch["target"])
                checked_patch(graph, patch["target"], patch["expectedPatch"])
                patch["graphReadback"] = graph
        report["status"] = "authored_observed"
        report["next"] = "Only createdTracks and their slot 0 clips/devices are owned outputs. Independently inspect notes/timbres, render these muted tracks pre-fader, and save/reopen the disposable project. No automatic save, play, undo or retry was performed. Stored graph/parameter readback is not DSP or audible-music proof."
        if scratch:
            report["next"] = ("GUI follow-up required on the owned Triangle Pad Poly Grid: set VOICES to 8 "
                              "(four-note chords plus overlapping releases). The observed factory device is Mono; "
                              "existing direct parameters do not expose this setting. Per-voice triangle level is "
                              "1/8 for headroom. ") + report["next"]
        return 0
    except BaseException as error:
        report["status"] = "incomplete_inspect_state"
        report["error"] = str(error)
        report["recovery"] = "Inspect calls.jsonl and createdTracks. Never rerun the whole recipe blindly: earlier creation/writes may have succeeded."
        if not isinstance(error, Exception):
            raise
        return 1
    finally:
        (args.artifacts / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report_summary(report, args.artifacts), ensure_ascii=False))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Exact active disposable project name; not a full-path identity guarantee")
    parser.add_argument("--from-scratch", action="store_true",
                        help="Construct two note-driven Poly Grid patches from native MODULE resources; no presets")
    parser.add_argument("--pluck-preset", type=lambda value: Path(value).resolve())
    parser.add_argument("--pad-preset", type=lambda value: Path(value).resolve())
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
