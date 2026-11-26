from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call


def test_export_audio_and_bounce_in_place_are_strict_long_write_tools():

    track = "12345678-1234-1234-1234-123456789abc"
    base = {
        "sources": ["master", track],
        "directory": "/home/me/Music",
        "baseName": "Mix",
        "startBeats": 0,
        "endBeats": 16,
        "format": "wav",
    }
    assert validate_call("export.audio", base) == (base, 30000)
    assert (
        validate_call(
            "export.audio",
            base
            | {
                "format": "flac",
                "bitDepth": 24,
                "sampleRate": 48000,
                "tap": "pre_fader",
                "dither": True,
                "overwrite": True,
            },
        )[0]["bitDepth"]
        == 24
    )
    assert TOOLS["export.audio"].read_only is False
    assert TOOLS["bounce.inPlace"].read_only is False
    assert TOOLS["jobs.get"].read_only is True
    assert TOOLS["export.audio"].max_timeout_ms == TOOLS["bounce.inPlace"].max_timeout_ms == 60000
    assert TOOLS["jobs.get"].max_timeout_ms == 10000
    bad: Any
    for bad in (
        {"sources": []},
        {"sources": ["master", "master"]},
        {"sources": [track, track.upper()]},
        {"sources": ["Master"]},
        {"directory": "relative"},
        {"directory": "/a/../b"},
        {"baseName": ""},
        {"baseName": ".x"},
        {"baseName": "a/b"},
        {"baseName": " a"},
        {"baseName": "x" * 129},
        {"endBeats": 0},
        {"endBeats": 65537},
        {"format": "mp3"},
        {"bitDepth": 20},
        {"format": "flac", "bitDepth": 32},
        {"sampleRate": 96000},
        {"tap": "pre_fx"},
        {"overwrite": "yes"},
        {"realtime": True},
    ):
        with pytest.raises(ValidationError):
            validate_call("export.audio", base | bad)
    bounce = {"trackIds": [track], "startBeats": 8, "endBeats": 24, "tap": "pre_fx"}
    assert validate_call("bounce.inPlace", bounce) == (bounce, 30000)
    for bad in ({"trackIds": ["master"]}, {"trackIds": [track, track]}, {"tap": "custom"}, {"endBeats": 8}):
        with pytest.raises(ValidationError):
            validate_call("bounce.inPlace", bounce | bad)
    with pytest.raises(ValidationError):
        validate_call("bounce.inPlace", {k: v for k, v in bounce.items() if k != "tap"})
