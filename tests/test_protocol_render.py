from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call


def ref(track: str, name: str = "Lead") -> dict[str, str]:
    return {"kind": "track", "id": track, "name": name}


def test_export_audio_and_tracks_bounce_are_strict_long_write_tools():
    track = "12345678-1234-1234-1234-123456789abc"
    base = {
        "sources": ["master", ref(track)],
        "directory": "/home/me/Music",
        "baseName": "Mix",
        "start": 0,
        "end": 16,
        "fileType": "wav",
    }
    assert validate_call("export.audio", base) == (base, 30000)
    assert (
        validate_call(
            "export.audio",
            base
            | {
                "fileType": "flac",
                "bitDepth": 24,
                "sampleRate": 48000,
                "tap": "pre_fader",
                "dither": True,
                "overwrite": True,
            },
        )[0]["bitDepth"]
        == 24
    )
    for types in ("mp3", "ogg", "opus", ["wav", "mp3", "ogg", "opus", "flac"], ["flac"]):
        assert validate_call("export.audio", base | {"fileType": types})[0]["fileType"] == types
    assert validate_call("export.audio", base | {"fileType": ["wav", "mp3"], "bitDepth": 32})[0]["bitDepth"] == 32
    assert TOOLS["export.audio"].read_only is False
    assert TOOLS["tracks.bounce"].read_only is False
    assert TOOLS["jobs.get"].read_only is True
    assert TOOLS["export.audio"].max_timeout_ms == TOOLS["tracks.bounce"].max_timeout_ms == 60000
    assert TOOLS["jobs.get"].max_timeout_ms == 10000
    bad: Any
    for bad in (
        {"sources": []},
        {"sources": ["master", "master"]},
        {"sources": [ref(track), ref(track.upper(), "Other")]},
        {"sources": [track]},
        {"sources": ["Master"]},
        {"sources": [{"kind": "scene", "index": 0, "name": None}]},
        {"directory": "relative"},
        {"directory": "/a/../b"},
        {"baseName": ""},
        {"baseName": ".x"},
        {"baseName": "a/b"},
        {"baseName": " a"},
        {"baseName": "x" * 129},
        {"end": 0},
        {"end": 65537},
        {"fileType": "aiff"},
        {"fileType": []},
        {"fileType": ["wav", "wav"]},
        {"fileType": ["wav", "aiff"]},
        {"fileType": ["mp3", "flac"], "bitDepth": 32},
        {"format": "wav"},
        {"bitDepth": 20},
        {"fileType": "flac", "bitDepth": 32},
        {"sampleRate": 96000},
        {"tap": "pre_fx"},
        {"overwrite": "yes"},
        {"realtime": True},
    ):
        with pytest.raises(ValidationError):
            validate_call("export.audio", base | bad)
    bounce = {"targets": [ref(track)], "start": 8, "end": 24, "tap": "pre_fx", "inPlace": True}
    assert validate_call("tracks.bounce", bounce) == (bounce, 30000)
    assert validate_call("tracks.bounce", {k: v for k, v in bounce.items() if k != "inPlace"})[0] == {
        k: v for k, v in bounce.items() if k != "inPlace"
    }
    assert TOOLS["tracks.bounce"].model.model_json_schema()["properties"]["inPlace"]["default"] is True
    for bad in (
        {"targets": ["master"]},
        {"targets": [track]},
        {"targets": [ref(track), ref(track)]},
        {"trackIds": [track]},
        {"inPlace": False},
        {"tap": "custom"},
        {"end": 8},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.bounce", bounce | bad)
    with pytest.raises(ValidationError):
        validate_call("tracks.bounce", {k: v for k, v in bounce.items() if k != "tap"})


def test_the_old_render_names_are_gone():
    assert "bounce.inPlace" not in TOOLS
