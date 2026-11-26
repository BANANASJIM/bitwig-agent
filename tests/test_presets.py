import asyncio
import struct
from pathlib import Path

import pytest
from pydantic import ValidationError

from bitwig_agent import presets
from bitwig_agent.core.composed import run
from bitwig_agent.protocol import CLIENT_SIDE, validate_call


def _string(key, value):
    return struct.pack(">II", 1, len(key)) + key.encode() + b"\x08" + struct.pack(">I", len(value)) + value.encode()


def _plain(device, kind, category, creator, comment=""):
    meta = b"".join(
        _string(k, v)
        for k, v in [
            ("comment", comment),
            ("creator", creator),
            ("device_name", device),
            ("device_type", kind),
            ("preset_category", category),
        ]
    )
    ids = struct.pack(">II", 1, 3) + b"ids" + b"\x19" + struct.pack(">I", 1) + struct.pack(">I", 4) + b"abcd"
    return (
        b"BtWg0003000200c6000016bf000000000000000000"
        + struct.pack(">I", 4)
        + b"meta"
        + meta
        + ids
        + b"\x00" * 16
        + b"PAYLOAD"
    )


@pytest.fixture
def library(tmp_path):
    user = tmp_path / "Library"
    (user / "Presets" / "Polymer").mkdir(parents=True)
    (user / "Presets" / "Polymer" / "Warm Pad Study.bwpreset").write_bytes(
        _plain("Polymer", "note_to_audio", "Pad", "Ada", "Slow attack, wide")
    )
    (user / "Presets" / "Reverb").mkdir()
    (user / "Presets" / "Reverb" / "Big Hall.bwpreset").write_bytes(
        _plain("Convolution", "audio_to_audio", "Reverb", "Ada")
    )
    (user / "Presets" / "Notes.txt").write_text("not a preset")
    packages = tmp_path / "installed-packages" / "5.0"
    (packages / "Bitwig" / "Essentials" / "Presets" / "Convolution").mkdir(parents=True)
    (packages / "Bitwig" / "Essentials" / "Presets" / "Convolution" / "Warm Room.bwpreset").write_bytes(
        b"BtWg0003000400b80000052d0000" + bytes(range(256))
    )
    (packages / "Bitwig" / "Drums").mkdir(parents=True)
    (packages / "Bitwig" / "Drums" / "Kick Heavy.bwpreset").write_bytes(b"BtWg" + b"\x00" * 100)
    return [("user", user), ("package", tmp_path / "installed-packages")]


def names(result):
    return [p["name"] for p in result["presets"]]


def test_plain_headers_are_read_and_encrypted_ones_degrade_to_paths(library):
    result = presets.search({}, library)
    assert result["scanned"]["presets"] == 4
    assert result["scanned"]["metadataReadable"] == 2
    by = {p["name"]: p for p in result["presets"]}
    pad = by["Warm Pad Study"]
    assert (pad["device"], pad["deviceType"], pad["category"], pad["creator"], pad["comment"]) == (
        "Polymer",
        "instrument",
        "Pad",
        "Ada",
        "Slow attack, wide",
    )
    assert by["Big Hall"]["deviceType"] == "audio-effect"
    room = by["Warm Room"]
    assert (room["source"], room["creator"], room["package"], room["folders"], room["metadataReadable"]) == (
        "package",
        "Bitwig",
        "Essentials",
        ["Presets", "Convolution"],
        False,
    )
    assert "device" not in room
    assert room["insert"] == {"kind": "file", "path": room["path"]}
    assert Path(room["path"]).is_absolute()


def test_query_filters_rank_and_page(library):
    assert names(presets.search({"query": "warm"}, library)) == ["Warm Pad Study", "Warm Room"]
    assert names(presets.search({"query": "WARM-pad"}, library)) == ["Warm Pad Study"]
    assert names(presets.search({"query": "slow wide"}, library)) == ["Warm Pad Study"]  # comment words count
    assert names(presets.search({"deviceName": "convolution"}, library)) == [
        "Big Hall",
        "Warm Room",
    ]  # metadata or folder
    assert names(presets.search({"deviceType": "audio-effect"}, library)) == ["Big Hall"]
    assert names(presets.search({"category": "pad"}, library)) == ["Warm Pad Study"]
    assert names(presets.search({"category": "drums"}, library)) == ["Kick Heavy"]  # folder when no metadata
    assert names(presets.search({"query": "nothing-here"}, library)) == []
    page = presets.search({"limit": 2, "offset": 1}, library)
    assert page["window"] == {"offset": 1, "limit": 2, "total": 4, "complete": False}
    assert len(page["presets"]) == 2


def test_missing_roots_are_reported_not_invented(tmp_path):
    result = presets.search({"query": "x"}, [("user", tmp_path / "absent")])
    assert result["presets"] == []
    assert result["roots"] == []
    assert result["window"]["total"] == 0


def test_env_override_replaces_default_roots(monkeypatch, tmp_path):
    monkeypatch.setenv("BITWIG_PRESET_ROOTS", str(tmp_path))
    (tmp_path / "A.bwpreset").write_bytes(b"BtWg")
    assert names(presets.search({})) == ["A"]


def test_blob_values_do_not_hide_later_keys():
    blob = struct.pack(">II", 1, 18) + b"orig_file_checksum" + b"\x0d" + struct.pack(">I", 4) + b"\x00\x01\x02\x03"
    data = (
        b"BtWg0003000200c6"
        + struct.pack(">I", 4)
        + b"meta"
        + _string("device_name", "Polymer")
        + blob
        + _string("preset_category", "Pad")
    )
    assert presets.read_meta(data) == {"device_name": "Polymer", "preset_category": "Pad"}


def test_truncated_or_garbage_headers_do_not_raise():
    assert presets.read_meta(b"") is None
    assert presets.read_meta(b"BtWg" + b"\x00" * 10) is None
    full = _plain("Polymer", "note_to_audio", "Pad", "Ada")
    for cut in range(0, len(full), 7):
        presets.read_meta(full[:cut])  # never raises


def test_tool_is_client_side_with_bounded_args(library, monkeypatch):
    assert "presets.search" in CLIENT_SIDE
    for bad in ({"limit": 0}, {"limit": 201}, {"deviceType": "synth"}, {"query": ""}, {"extra": 1}):
        with pytest.raises(ValidationError):
            validate_call("presets.search", bad)
    monkeypatch.setenv("BITWIG_PRESET_ROOTS", str(library[0][1]))
    result = asyncio.run(run("presets.search", None, {"query": "big hall"}, 5000))
    assert names(result) == ["Big Hall"]
