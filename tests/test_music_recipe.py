import argparse
import copy
import importlib.util
import json
from pathlib import Path

import pytest
from fakes import RECIPE_TARGET, RecipeGrid

from bitwig_agent.errors import bridge_error


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
        "error_refused",
    ],
)
async def test_recipe_stops_after_session_changes_during_creation(tmp_path, monkeypatch, transition, recipe):  # noqa: C901
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
                    return {
                        "outcome": "unknown",
                        "reason": "not_observed",
                        "message": "Native completion is unresolved",
                        "nameApplied": False,
                    }
                if transition == "observed_unknown":
                    return {
                        "outcome": "unknown",
                        "reason": "deadline",
                        "message": "Native completion is unresolved",
                        "trackId": "11111111-1111-1111-1111-111111111111",
                        "nameApplied": True,
                    }
                if transition == "error_refused":
                    raise bridge_error("host_refused", "The host refused the track")
                return {"outcome": "applied", "trackId": "11111111-1111-1111-1111-111111111111"}
            raise AssertionError(f"A changed session must not receive another mutation: {method}")

    monkeypatch.setattr(recipe, "BridgeClient", lambda port: Session())
    monkeypatch.setattr(recipe, "configured_port", lambda: 61172)
    assert await recipe.create(args) == 1
    assert mutations == ["tracks.create"]
    report = json.loads((args.artifacts / "report.json").read_text())
    assert report["status"] == "incomplete_inspect_state"
    if transition == "error_refused":
        assert report["createdTracks"] == []
        assert "uncertainOperations" not in report
    elif transition in {"creation_unknown", "observed_unknown"}:
        assert report["createdTracks"] == []
        uncertain = report["uncertainOperations"][0]
        assert uncertain["method"] == "tracks.create"
        assert uncertain["result"]["outcome"] == "unknown"
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
async def test_recipe_rejects_mixed_or_incomplete_inputs_before_connecting(  # noqa: PLR0913, PLR0917
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
    scene = {"kind": "scene", "index": 0, "name": None}
    clip = {
        "kind": "clip",
        "track": {"kind": "track", "id": track_id, "name": "Toolkit Pluck"},
        "slot": {"scene": scene},
        "name": "Pluck eight-beat sketch",
    }

    static = {
        "transport.get": {
            "status": "observed",
            "projectName": "Disposable",
            "playing": False,
            "arrangerRecordEnabled": False,
            "arrangerAutomationWriteEnabled": False,
            "clipLauncherAutomationWriteEnabled": False,
        },
        "tracks.list": {"status": "observed", "tracks": [], "window": {"complete": True, "capacity": 16}},
        "scenes.list": {"scenes": [{"ref": scene}]},
        "clips.insert": {"outcome": "applied", "ref": clip},
    }

    class Session:
        async def call(self, method, params):
            nonlocal inserted
            if method in static:
                return static[method]
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
                    "ref": {**clip, "name": "other"} if problem == "wrong_target" else clip,
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
                    "loop": {"lengthBeats": 8, "startBeats": 0},
                    "loopEnabled": None if problem == "loop_unobserved" else problem != "loop_disabled",
                    "playStartBeats": 4 if problem == "play_start_shifted" else 0,
                }
            elif method not in {"tracks.set", "notes.edit"}:
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
    target = RECIPE_TARGET
    connection = {
        "sourceModuleId": "oscillator",
        "sourcePortKey": "OUT",
        "targetModuleId": "output",
        "targetPortKey": "IN",
    }
    graph = {
        "target": copy.deepcopy(target["target"]),
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
        graph["target"]["track"]["id"] = "another-track"
    elif problem == "wrong_device":
        graph["target"]["index"] = 1
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


@pytest.mark.anyio
@pytest.mark.parametrize("label", ["Pluck", "Pad"])
async def test_grid_patch_uses_diffs_and_one_parameter_batch(recipe, label):
    grid = RecipeGrid()
    report = await recipe.build_grid(grid, RECIPE_TARGET, RecipeGrid.TYPES, label)
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
    grid = RecipeGrid()
    original = grid.__call__

    async def surprising(method, params):
        result = await original(method, params)
        if method == "grid.modules.insert":
            result["modulesRemoved"] = [{"moduleId": "default-1"}]
        return result

    with pytest.raises(RuntimeError, match="Insertion did not establish"):
        await recipe.build_grid(surprising, RECIPE_TARGET, RecipeGrid.TYPES, "Pluck")
    assert not any(method.startswith("grid.parameters") or method == "grid.connections.set" for method, _ in grid.calls)
