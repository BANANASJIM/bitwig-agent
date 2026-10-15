import argparse
import importlib.util
import json
from pathlib import Path

import pytest


@pytest.mark.anyio
@pytest.mark.parametrize("transition", ["recording_started", "project_changed", "safety_unobserved", "creation_unknown"])
async def test_recipe_stops_after_session_changes_during_creation(tmp_path, monkeypatch, transition):
    spec = importlib.util.spec_from_file_location("grid_music_recipe", Path(__file__).resolve().parents[1] / "examples/grid_music.py")
    recipe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(recipe)
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
                return {"status": "observed", "projectName": "Other" if created and transition == "project_changed" else "Disposable"}
            if method == "transport.get":
                return {"status": "observed", "playing": False,
                        "arrangerRecordEnabled": None if created and transition == "safety_unobserved" else created and transition == "recording_started",
                        "arrangerAutomationWriteEnabled": False, "clipLauncherAutomationWriteEnabled": False}
            if method == "tracks.list":
                return {"status": "observed", "tracks": [], "window": {"complete": True, "capacity": 16}}
            mutations.append(method)
            if method == "tracks.create":
                created = True
                if transition == "creation_unknown":
                    return {"status": "dispatched", "completion": "unknown", "nameApplied": False}
                return {"status": "observed", "trackId": "11111111-1111-1111-1111-111111111111"}
            raise AssertionError(f"A changed session must not receive another mutation: {method}")

    monkeypatch.setattr(recipe, "BridgeClient", lambda port: Session())
    monkeypatch.setattr(recipe, "configured_port", lambda: 61172)
    assert await recipe.create(args) == 1
    assert mutations == ["tracks.create"]
    report = json.loads((args.artifacts / "report.json").read_text())
    assert report["status"] == "incomplete_inspect_state"
    if transition == "creation_unknown":
        assert report["createdTracks"] == []
        assert report["uncertainOperations"][0]["method"] == "tracks.create"
        assert report["uncertainOperations"][0]["result"]["completion"] == "unknown"
        assert report["uncertainOperations"][0]["result"]["nameApplied"] is False
    else:
        assert report["createdTracks"] == [{"trackId": "11111111-1111-1111-1111-111111111111", "label": "Pluck"}]
