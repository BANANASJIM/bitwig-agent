import argparse
import importlib.util
import json
from pathlib import Path
from typing import Any, ClassVar

import pytest


@pytest.fixture
def recipe():
    spec = importlib.util.spec_from_file_location(
        "grid_music_recipe", Path(__file__).resolve().parents[1] / "examples/grid_music.py"
    )
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.anyio
@pytest.mark.parametrize(
    "transition",
    [
        "recording_started",
        "project_changed",
        "project_unobserved",
        "safety_unobserved",
        "creation_unknown",
        "observed_unknown",
        "error_unknown",
    ],
)
async def test_recipe_stops_after_session_changes_during_creation(tmp_path, monkeypatch, transition, recipe):
    preset = tmp_path / "test.bwpreset"
    preset.write_bytes(b"Test-only file; no native host is contacted")
    args = argparse.Namespace(
        pluck_preset=preset, pad_preset=preset, allow_write=True, artifacts=tmp_path / "evidence", project="Disposable"
    )
    mutations = []
    created = False

    class Session:
        async def call(self, method, params):
            nonlocal created
            if method == "status":
                return {"status": "observed", "projectName": "Disposable"}
            if method == "transport.get":
                project_name: str | None = "Disposable"
                if created and transition == "project_changed":
                    project_name = "Other"
                elif created and transition == "project_unobserved":
                    project_name = None
                return {
                    "status": "observed",
                    "projectName": project_name,
                    "playing": False,
                    "arrangerRecordEnabled": None
                    if created and transition == "safety_unobserved"
                    else created and transition == "recording_started",
                    "arrangerAutomationWriteEnabled": False,
                    "clipLauncherAutomationWriteEnabled": False,
                }
            if method == "tracks.list":
                return {"status": "observed", "tracks": [], "window": {"complete": True, "capacity": 16}}
            mutations.append(method)
            if method == "tracks.create":
                created = True
                if transition == "creation_unknown":
                    return {"status": "dispatched", "completion": "unknown", "nameApplied": False}
                if transition == "observed_unknown":
                    return {
                        "status": "observed",
                        "completion": "unknown",
                        "trackId": "11111111-1111-1111-1111-111111111111",
                        "nameApplied": True,
                    }
                if transition == "error_unknown":
                    raise recipe.BridgeError(
                        {
                            "code": "timeout",
                            "outcome": "unknown",
                            "message": "Native completion is unresolved",
                            "mutationDispatched": True,
                        }
                    )
                return {"status": "observed", "trackId": "11111111-1111-1111-1111-111111111111"}
            raise AssertionError(f"A changed session must not receive another mutation: {method}")

    monkeypatch.setattr(recipe, "BridgeClient", lambda port: Session())
    monkeypatch.setattr(recipe, "configured_port", lambda: 61172)
    assert await recipe.create(args) == 1
    assert mutations == ["tracks.create"]
    report = json.loads((args.artifacts / "report.json").read_text())
    assert report["status"] == "incomplete_inspect_state"
    if transition in {"creation_unknown", "observed_unknown", "error_unknown"}:
        assert report["createdTracks"] == []
        uncertain = report["uncertainOperations"][0]
        assert uncertain["method"] == "tracks.create"
        if transition == "error_unknown":
            assert uncertain["error"]["outcome"] == "unknown"
            assert uncertain["error"]["mutationDispatched"] is True
        else:
            assert uncertain["result"]["completion"] == "unknown"
            assert uncertain["result"]["status"] == ("observed" if transition == "observed_unknown" else "dispatched")
            assert uncertain["result"]["nameApplied"] is (transition == "observed_unknown")
    else:
        assert [item["trackId"] for item in report["createdTracks"]] == ["11111111-1111-1111-1111-111111111111"]


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("scratch", "pluck", "pad"),
    [
        (True, True, False),
        (True, False, True),
        (True, True, True),
        (False, False, False),
        (False, True, False),
        (False, False, True),
    ],
)
async def test_recipe_rejects_mixed_or_incomplete_inputs_before_connecting(
    tmp_path, monkeypatch, recipe, scratch, pluck, pad
):
    args = argparse.Namespace(
        from_scratch=scratch,
        pluck_preset=tmp_path / "pluck.bwpreset" if pluck else None,
        pad_preset=tmp_path / "pad.bwpreset" if pad else None,
        allow_write=True,
        artifacts=tmp_path / "evidence",
        project="Disposable",
    )

    def unexpected_client(port):
        pytest.fail("Invalid authoring inputs must not connect to the host")

    monkeypatch.setattr(recipe, "BridgeClient", unexpected_client)
    with pytest.raises(ValueError, match="--from-scratch"):
        await recipe.create(args)
    assert not args.artifacts.exists()


@pytest.mark.anyio
@pytest.mark.parametrize(
    "problem",
    [
        "wrong_target",
        "missing_onsets",
        "incomplete_window",
        "malformed_readback",
        "loop_disabled",
        "loop_unobserved",
        "play_start_shifted",
    ],
)
async def test_recipe_does_not_report_authored_when_clip_readback_is_wrong(
    tmp_path, monkeypatch, capsys, recipe, problem
):
    preset = tmp_path / "test.bwpreset"
    preset.write_bytes(b"Test-only input; no native host is contacted")
    args = argparse.Namespace(
        pluck_preset=preset, pad_preset=preset, allow_write=True, artifacts=tmp_path / "evidence", project="Disposable"
    )
    track_id = "11111111-1111-1111-1111-111111111111"
    inserted = False
    creations = []

    class Session:
        async def call(self, method, params):
            nonlocal inserted
            if method == "transport.get":
                return {
                    "status": "observed",
                    "projectName": "Disposable",
                    "playing": False,
                    "arrangerRecordEnabled": False,
                    "arrangerAutomationWriteEnabled": False,
                    "clipLauncherAutomationWriteEnabled": False,
                }
            if method == "tracks.list":
                return {"status": "observed", "tracks": [], "window": {"complete": True, "capacity": 16}}
            if method == "tracks.create":
                creations.append(track_id)
                return {"status": "observed", "trackId": track_id}
            if method == "devices.list":
                return {
                    "status": "observed",
                    "trackId": track_id,
                    "window": {"complete": True},
                    "devices": [{"deviceIndex": 0, "name": "Test preset"}] if inserted else [],
                }
            if method == "devices.insert":
                inserted = True
            elif method == "notes.read":
                return {
                    "status": "observed",
                    "trackId": "other" if problem == "wrong_target" else track_id,
                    "slotIndex": 0,
                    "window": {
                        "channel": 0,
                        "stepSizeBeats": 0.25,
                        "startStep": 0,
                        "steps": 32,
                        "pitchStart": 0,
                        "pitchCount": 128,
                    },
                    "notes": None
                    if problem == "malformed_readback"
                    else []
                    if problem == "missing_onsets"
                    else [
                        {
                            "beat": item["beat"],
                            "pitch": item["pitch"],
                            "durationBeats": item["durationBeats"],
                            "velocity": item["velocity"],
                        }
                        for item in recipe.pluck_phrase()
                    ],
                    "completeWindow": problem != "incomplete_window",
                    "continuingAtWindowStart": [],
                    "loopStartBeats": 0,
                    "loopLengthBeats": 8,
                    "loopEnabled": None if problem == "loop_unobserved" else problem != "loop_disabled",
                    "playStartBeats": 4 if problem == "play_start_shifted" else 0,
                }
            elif method not in {"tracks.set", "launcher.create", "notes.edit"}:
                raise AssertionError(f"Unexpected operation after invalid clip: {method}")
            return {"status": "observed"}

    monkeypatch.setattr(recipe, "BridgeClient", lambda port: Session())
    monkeypatch.setattr(recipe, "configured_port", lambda: 61172)
    assert await recipe.create(args) == 1
    report = json.loads((args.artifacts / "report.json").read_text())
    assert report["status"] == "incomplete_inspect_state"
    assert len(creations) == 1
    summary = json.loads(capsys.readouterr().out)
    assert summary["status"] == "incomplete_inspect_state"
    assert summary["createdTracks"][0]["noteCount"] is None


@pytest.fixture
def owned_patch():
    target = {"trackId": "11111111-1111-1111-1111-111111111111", "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
    connection = {
        "sourceModuleId": "oscillator",
        "sourcePortKey": "OUT",
        "targetModuleId": "output",
        "targetPortKey": "IN",
    }
    graph = {
        "target": {**target, "nativeOwningTrack": "corroborated_before_and_after_capture"},
        "window": {"offset": 0, "total": 2, "complete": True},
        "coverage": {"structure": "mapped_modules_and_ports"},
        "modules": [
            {
                "instanceId": "oscillator",
                "typeId": "oscillator-type",
                "inputs": [],
                "outputs": [{"key": "OUT"}],
                "parameters": [{"supported": True, "parameterId": "PITCH", "kind": "float", "value": 0.0}],
            },
            {
                "instanceId": "output",
                "typeId": "output-type",
                "inputs": [{"key": "IN", "connected": True}],
                "outputs": [],
                "parameters": [],
            },
        ],
        "connections": [connection],
    }
    expected = {
        "moduleTypes": {"oscillator": "oscillator-type", "output": "output-type"},
        "parameters": [{"moduleId": "oscillator", "parameterId": "PITCH", "kind": "float", "value": 0.0}],
        "connections": [dict(connection)],
    }
    return target, graph, expected


@pytest.mark.parametrize(
    "problem",
    [
        "wrong_track",
        "wrong_device",
        "uncorroborated_owner",
        "partial_modules",
        "replaced_module",
        "wrong_control",
        "missing_cable",
        "missing_port",
    ],
)
def test_owned_patch_rejects_wrong_or_incomplete_native_readback(recipe, owned_patch, problem):
    target, graph, expected = owned_patch
    if problem == "wrong_track":
        graph["target"]["trackId"] = "another-track"
    elif problem == "wrong_device":
        graph["target"]["deviceIndex"] = 1
    elif problem == "uncorroborated_owner":
        graph["target"]["nativeOwningTrack"] = None
    elif problem == "partial_modules":
        graph["window"]["complete"] = False
    elif problem == "replaced_module":
        graph["modules"][0]["typeId"] = "another-type"
    elif problem == "wrong_control":
        graph["modules"][0]["parameters"][0]["value"] = 12.0
    elif problem == "missing_cable":
        graph["connections"] = []
        graph["modules"][1]["inputs"][0]["connected"] = False
    elif problem == "missing_port":
        graph["modules"][0]["outputs"] = []
    with pytest.raises(RuntimeError):
        recipe.checked_patch(graph, target, expected)


TRACK_ID = "11111111-1111-1111-1111-111111111111"
GRID_TARGET = {"trackId": TRACK_ID, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}


class FakeGrid:
    """Just enough native behavior for the diff/batch protocol; counts every call."""

    TYPES: ClassVar[dict[str, str]] = {"Union": "type-union", "ADSR": "type-adsr", "Audio Out": "type-out"}

    def __init__(self):
        self.calls = []
        self.modules: dict[str, Any] = {}
        self.cables: dict[tuple[str, str], Any] = {}  # (targetModuleId, targetPortKey) -> source endpoint
        for number in range(2):
            self.add(f"default-{number}", "type-default", [], [])
        self.cables[("default-1", "IN")] = {"moduleId": "default-0", "portKey": "OUT"}
        self.modules["default-0"]["outputs"].append({"key": "OUT", "name": "Out"})
        self.modules["default-1"]["inputs"].append({"key": "IN", "name": "In", "connected": True})

    def add(self, module_id, type_id, inputs, outputs, parameters=()):
        self.modules[module_id] = {
            "instanceId": module_id,
            "typeId": type_id,
            "name": type_id,
            "customName": "",
            "x": 0,
            "y": 0,
            "inputs": inputs,
            "outputs": outputs,
            "parameters": list(parameters),
        }

    def rows(self, ids=None, parameters=True):
        rows = []
        for module_id, module in self.modules.items():
            if ids is not None and module_id not in ids:
                continue
            row = {key: value for key, value in module.items() if key != "parameters"}
            row["inputs"] = [
                {**port, "connected": (module_id, port["key"]) in self.cables} for port in module["inputs"]
            ]
            row["parameters"] = [dict(item) for item in module["parameters"]] if parameters else []
            rows.append(row)
        return rows

    def connections(self):
        return [
            {
                "sourceModuleId": source["moduleId"],
                "sourcePortKey": source["portKey"],
                "targetModuleId": target[0],
                "targetPortKey": target[1],
            }
            for target, source in self.cables.items()
        ]

    def parameter(self, module_id, parameter_id):
        return next(item for item in self.modules[module_id]["parameters"] if item["parameterId"] == parameter_id)

    async def __call__(self, method, params):
        self.calls.append((method, params))
        if method == "grid.get":
            ids = set(params["moduleIds"]) if "moduleIds" in params else None
            rows = self.rows(ids, params.get("includeParameters", True))
            return {
                "status": "observed",
                "target": {**GRID_TARGET, "nativeOwningTrack": "corroborated_before_and_after_capture"},
                "window": {"offset": 0, "total": len(rows), "complete": True},
                "modules": rows,
                "connections": [c for c in self.connections() if ids is None or c["targetModuleId"] in ids],
            }
        if method == "grid.modules.delete":
            module_id = params["moduleId"]
            removed = self.modules.pop(module_id)
            self.cables = {
                key: value
                for key, value in self.cables.items()
                if key[0] != module_id and value["moduleId"] != module_id
            }
            return {
                "status": "observed",
                "modulesRemoved": [{"moduleId": module_id, "typeId": removed["typeId"]}],
                "moduleCount": len(self.modules),
            }
        if method == "grid.modules.insert":
            module_id = f"module-{len(self.modules)}-{params['typeId']}"
            name = next(key for key, value in self.TYPES.items() if value == params["typeId"])
            ports = {
                "Union": ([], [{"key": "OUT", "name": "Signal Out"}]),
                "ADSR": ([{"key": "IN", "name": "Signal In"}], [{"key": "OUT", "name": "Signal Out"}]),
                "Audio Out": ([{"key": "IN", "name": "Audio In"}], []),
            }[name]

            def floats(*names):
                return [
                    {
                        "supported": True,
                        "parameterId": f"{name}.{item}",
                        "name": item,
                        "kind": "float",
                        "value": 0.5,
                        "minimum": 0.0,
                        "maximum": 2.0,
                    }
                    for item in names
                ]

            def booleans(*names):
                return [
                    {
                        "supported": True,
                        "parameterId": f"{name}.{item}",
                        "name": item,
                        "kind": "boolean",
                        "value": False,
                    }
                    for item in names
                ]

            parameters = {
                "Union": booleans("Key Tracking")
                + floats("Pitch Offset", "Saw Level", "Triangle Level", "Pulse Level"),
                "ADSR": booleans("Gate on Notes", "Affect Voice Lifetime")
                + floats("Attack", "Decay", "Sustain", "Release")
                + [
                    {
                        "supported": True,
                        "parameterId": "ADSR.Model",
                        "name": "Model",
                        "kind": "enum",
                        "value": 0,
                        "options": [{"value": 0, "label": "Digital"}, {"value": 3, "label": "Analog"}],
                    }
                ],
                "Audio Out": [],
            }[name]
            self.add(module_id, params["typeId"], *ports, parameters=parameters)
            row = self.rows({module_id}, parameters=False)[0]
            row.pop("parameters")
            return {
                "status": "observed",
                "moduleId": module_id,
                "modulesAdded": [row],
                "moduleCount": len(self.modules),
            }
        if method == "grid.parameters.set":
            entries = params["writes"]
            for entry in entries:
                current = self.parameter(entry["moduleId"], entry["parameterId"])
                if current["kind"] != entry["expectedKind"] or current["value"] != entry["expectedValue"]:
                    raise recipe_module().BridgeError(
                        {"code": "stale_context", "outcome": "not_applied", "message": "stale"}
                    )
            results = []
            for entry in entries:
                current = self.parameter(entry["moduleId"], entry["parameterId"])
                changed = current["value"] != entry["value"]
                results.append(
                    {
                        "moduleId": entry["moduleId"],
                        "parameterId": entry["parameterId"],
                        "status": "applied" if changed else "already_matched",
                        "previousValue": current["value"],
                        "value": entry["value"],
                    }
                )
                current["value"] = entry["value"]
            return {
                "status": "observed",
                "batchStatus": "applied",
                "completion": "observed",
                "written": len(results),
                "writes": results,
            }
        if method == "grid.connections.set":
            edits = params["connections"]
            assert all(
                edit["expectedSource"] == self.cables.get((edit["targetModuleId"], edit["targetPortKey"]))
                for edit in edits
            )
            for edit in edits:
                self.cables[edit["targetModuleId"], edit["targetPortKey"]] = edit["source"]
            return {
                "status": "observed",
                "batchStatus": "applied",
                "completion": "observed",
                "written": len(edits),
                "connections": [
                    {
                        "targetModuleId": edit["targetModuleId"],
                        "targetPortKey": edit["targetPortKey"],
                        "status": "applied",
                        "matchesRequested": True,
                    }
                    for edit in edits
                ],
                "connectionsChanged": [
                    {
                        "target": {"moduleId": edit["targetModuleId"], "portKey": edit["targetPortKey"]},
                        "from": None,
                        "to": edit["source"],
                    }
                    for edit in edits
                ],
                "moduleCount": len(self.modules),
            }
        raise AssertionError(method)


_RECIPE: list[Any] = []


def recipe_module():
    return _RECIPE[0]


@pytest.mark.anyio
@pytest.mark.parametrize("label", ["Pluck", "Pad"])
async def test_grid_patch_uses_diffs_and_one_parameter_batch(recipe, label):
    _RECIPE[:] = [recipe]
    grid = FakeGrid()
    report = await recipe.build_grid(grid, GRID_TARGET, FakeGrid.TYPES, label)
    methods = [method for method, _ in grid.calls]
    assert methods.count("grid.parameters.set") == 1
    assert "grid.parameters.get" not in methods
    batch = next(params for method, params in grid.calls if method == "grid.parameters.set")
    assert len(batch["writes"]) == 12
    # 1 read, 2 deletes, 3 inserts, 1 filtered read, 1 batch, 1 cable batch, 1 final read
    assert len(grid.calls) == 10
    assert methods.count("grid.connections.set") == 1
    assert methods.count("grid.get") == 3
    filtered = next(params for method, params in grid.calls if method == "grid.get" and "moduleIds" in params)
    assert len(filtered["moduleIds"]) == 2
    assert {
        item["value"]
        for item in grid.modules[batch["writes"][0]["moduleId"]]["parameters"]
        if item["parameterId"] == "ADSR.Model"
    } == {3}
    assert len(report["expectedPatch"]["connections"]) == 2
    assert len(grid.cables) == 2


@pytest.mark.anyio
async def test_grid_patch_stops_when_a_diff_shows_an_unexpected_change(recipe):
    _RECIPE[:] = [recipe]
    grid = FakeGrid()
    original = grid.__call__

    async def surprising(method, params):
        result = await original(method, params)
        if method == "grid.modules.insert":
            result["modulesRemoved"] = [{"moduleId": "default-1"}]
        return result

    with pytest.raises(RuntimeError, match="Insertion did not establish"):
        await recipe.build_grid(surprising, GRID_TARGET, FakeGrid.TYPES, "Pluck")
    assert not any(method.startswith("grid.parameters") or method == "grid.connections.set" for method, _ in grid.calls)
