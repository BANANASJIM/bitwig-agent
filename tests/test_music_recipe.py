import argparse
import importlib.util
import json
from pathlib import Path

import pytest


@pytest.fixture
def recipe():
    spec = importlib.util.spec_from_file_location("grid_music_recipe", Path(__file__).resolve().parents[1] / "examples/grid_music.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.anyio
@pytest.mark.parametrize("transition", ["recording_started", "project_changed", "project_unobserved", "safety_unobserved", "creation_unknown", "observed_unknown", "error_unknown"])
async def test_recipe_stops_after_session_changes_during_creation(tmp_path, monkeypatch, transition, recipe):
    preset = tmp_path / "test.bwpreset"
    preset.write_bytes(b"Test-only file; no native host is contacted")
    args = argparse.Namespace(pluck_preset=preset, pad_preset=preset, allow_write=True,
                              artifacts=tmp_path / "evidence", project="Disposable")
    mutations = []
    created = False

    class Session:
        async def call(self, method, params):
            nonlocal created
            if method == "status":
                return {"status": "observed", "projectName": "Disposable"}
            if method == "transport.get":
                project_name = "Disposable"
                if created and transition == "project_changed":
                    project_name = "Other"
                elif created and transition == "project_unobserved":
                    project_name = None
                return {"status": "observed", "projectName": project_name, "playing": False,
                        "arrangerRecordEnabled": None if created and transition == "safety_unobserved" else created and transition == "recording_started",
                        "arrangerAutomationWriteEnabled": False, "clipLauncherAutomationWriteEnabled": False}
            if method == "tracks.list":
                return {"status": "observed", "tracks": [], "window": {"complete": True, "capacity": 16}}
            mutations.append(method)
            if method == "tracks.create":
                created = True
                if transition == "creation_unknown":
                    return {"status": "dispatched", "completion": "unknown", "nameApplied": False}
                if transition == "observed_unknown":
                    return {"status": "observed", "completion": "unknown",
                            "trackId": "11111111-1111-1111-1111-111111111111", "nameApplied": True}
                if transition == "error_unknown":
                    raise recipe.BridgeError({"code": "timeout", "outcome": "unknown",
                                              "message": "Native completion is unresolved", "mutationDispatched": True})
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
@pytest.mark.parametrize(("scratch", "pluck", "pad"), [
    (True, True, False), (True, False, True), (True, True, True),
    (False, False, False), (False, True, False), (False, False, True),
])
async def test_recipe_rejects_mixed_or_incomplete_inputs_before_connecting(tmp_path, monkeypatch, recipe, scratch, pluck, pad):
    args = argparse.Namespace(from_scratch=scratch, pluck_preset=tmp_path / "pluck.bwpreset" if pluck else None,
                              pad_preset=tmp_path / "pad.bwpreset" if pad else None,
                              allow_write=True, artifacts=tmp_path / "evidence", project="Disposable")

    def unexpected_client(port):
        pytest.fail("Invalid authoring inputs must not connect to the host")

    monkeypatch.setattr(recipe, "BridgeClient", unexpected_client)
    with pytest.raises(ValueError):
        await recipe.create(args)
    assert not args.artifacts.exists()


@pytest.mark.anyio
@pytest.mark.parametrize("problem", [
    "wrong_target", "missing_onsets", "incomplete_window", "malformed_readback",
    "loop_disabled", "loop_unobserved", "play_start_shifted",
])
async def test_recipe_does_not_report_authored_when_clip_readback_is_wrong(tmp_path, monkeypatch, capsys, recipe, problem):
    preset = tmp_path / "test.bwpreset"
    preset.write_bytes(b"Test-only input; no native host is contacted")
    args = argparse.Namespace(pluck_preset=preset, pad_preset=preset, allow_write=True,
                              artifacts=tmp_path / "evidence", project="Disposable")
    track_id = "11111111-1111-1111-1111-111111111111"
    inserted = False
    creations = []

    class Session:
        async def call(self, method, params):
            nonlocal inserted
            if method == "transport.get":
                return {"status": "observed", "projectName": "Disposable", "playing": False,
                        "arrangerRecordEnabled": False, "arrangerAutomationWriteEnabled": False,
                        "clipLauncherAutomationWriteEnabled": False}
            if method == "tracks.list":
                return {"status": "observed", "tracks": [], "window": {"complete": True, "capacity": 16}}
            if method == "tracks.create":
                creations.append(track_id)
                return {"status": "observed", "trackId": track_id}
            if method == "devices.list":
                return {"status": "observed", "trackId": track_id, "window": {"complete": True},
                        "devices": [{"deviceIndex": 0, "name": "Test preset"}] if inserted else []}
            if method == "devices.insert":
                inserted = True
            elif method == "notes.read":
                return {"status": "observed", "trackId": "other" if problem == "wrong_target" else track_id,
                        "slotIndex": 0, "window": {"channel": 0, "stepSizeBeats": 0.25, "startStep": 0,
                                                 "steps": 32, "pitchStart": 0, "pitchCount": 128},
                        "notes": None if problem == "malformed_readback" else [] if problem == "missing_onsets" else [
                            {"beat": item["beat"], "pitch": item["pitch"],
                             "durationBeats": item["durationBeats"],
                             "velocityNormalized": item["velocity"] / 127}
                            for item in recipe.pluck_phrase()],
                        "completeWindow": problem != "incomplete_window",
                        "continuingAtWindowStart": [], "loopStartBeats": 0, "loopLengthBeats": 8,
                        "loopEnabled": None if problem == "loop_unobserved" else problem != "loop_disabled",
                        "playStartBeats": 4 if problem == "play_start_shifted" else 0}
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
    target = {"trackId": "11111111-1111-1111-1111-111111111111", "deviceIndex": 0,
              "expectedDeviceName": "Poly Grid"}
    connection = {"sourceModuleId": "oscillator", "sourcePortKey": "OUT",
                  "targetModuleId": "output", "targetPortKey": "IN"}
    graph = {"target": {**target, "nativeOwningTrack": "corroborated_before_and_after_capture"},
             "window": {"offset": 0, "total": 2, "complete": True},
             "coverage": {"structure": "mapped_modules_and_ports"},
             "modules": [
                 {"instanceId": "oscillator", "typeId": "oscillator-type", "inputs": [],
                  "outputs": [{"key": "OUT"}], "parameters": [
                      {"supported": True, "parameterId": "PITCH", "kind": "float", "value": 0.0}]},
                 {"instanceId": "output", "typeId": "output-type",
                  "inputs": [{"key": "IN", "connected": True}], "outputs": [], "parameters": []},
             ], "connections": [connection]}
    expected = {"moduleTypes": {"oscillator": "oscillator-type", "output": "output-type"},
                "parameters": [{"moduleId": "oscillator", "parameterId": "PITCH", "kind": "float", "value": 0.0}],
                "connections": [dict(connection)]}
    return target, graph, expected


@pytest.mark.parametrize("problem", ["wrong_track", "wrong_device", "uncorroborated_owner", "partial_modules",
                                         "replaced_module", "wrong_control", "missing_cable", "missing_port"])
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
