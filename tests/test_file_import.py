"""clips.import {to: {track, start}}: a file onto the arranger through an empty slot, copy and delete, step by step."""

import asyncio
import wave
from typing import Any

import pytest
import test_configured_insert

from bitwig_agent import file_import
from bitwig_agent.core.invoke import Error, invoke
from bitwig_agent.core.serialize import dumps
from bitwig_agent.errors import bridge_error, unknown_result
from bitwig_agent.protocol.base import leaf_rule_holds
from bitwig_agent.protocol.launcher import ARRANGER_CLIP_REF, CLIP_REF, SLOT_REF, TRACK_REF

API = {"outcome": "applied", "verification": "api_readback"}
NATIVE = {"outcome": "applied", "verification": "native_readback"}
SLOT: dict[str, Any] = SLOT_REF
EMPTY = {**SLOT, "scene": {**SLOT["scene"], "index": 1}}
TEMPORARY = {**CLIP_REF, "slot": {"scene": EMPTY["scene"]}, "name": "beat"}
ARRANGER = {**ARRANGER_CLIP_REF, "start": 8, "name": "beat"}
TO = {"track": TRACK_REF, "start": 8}


def slots(*content, complete=True, offset=0):
    rows = [
        {"ref": {**SLOT, "scene": {**SLOT["scene"], "index": offset + i}}, "hasContent": full}
        for i, full in enumerate(content)
    ]
    listed = {"ref": TRACK_REF, "slots": rows, "complete": complete}
    return listed if complete else {**listed, "next": {"sceneOffset": offset + len(content)}}


def Host(**overrides):
    """The fake extension of the configured-insert tests, answering the methods an arranger import calls."""
    defaults = {
        "launcher.list": slots(True, False),
        "clips.import": {**API, "ref": TEMPORARY, "requestedFileVerified": False},
        "clips.copy": {**NATIVE, "ref": ARRANGER, "clip": {"start": 8, "length": 4, "name": "beat"}},
        "clips.delete": API,
    }
    return test_configured_insert.Host(**{**defaults, **overrides})


def run(host, args):
    return asyncio.run(invoke("clips.import", args, host))


def call(host, args):
    envelope = run(host, args)
    assert not isinstance(envelope, Error), envelope
    return envelope.result


def steps(result):
    return [(e["step"], e["outcome"], e.get("reason")) for e in result["entries"]]


@pytest.fixture
def beat(tmp_path):
    """A one-second silent mono WAV at 8 kHz."""
    path = tmp_path / "beat.wav"
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(8000)
        out.writeframes(b"\0\0" * 8000)
    return str(path)


def test_an_arranger_import_goes_through_the_first_empty_slot_then_copies_and_deletes(beat):
    host = Host()
    result = call(host, {"to": TO, "path": beat})
    assert host.calls == [
        ("launcher.list", {"target": TRACK_REF}),
        ("clips.import", {"to": EMPTY, "path": beat}),
        ("clips.copy", {"from": TEMPORARY, "to": TO}),
        ("clips.delete", {"target": TEMPORARY}),
    ]
    assert steps(result) == [("import", "applied", None), ("copy", "applied", None), ("delete", "applied", None)]
    assert result["outcome"] == "applied"
    assert "verification" not in result  # the entries do not share one
    assert result["ref"] == ARRANGER
    assert result["clip"]["length"] == 4
    assert result["file"] == {"name": "beat", "seconds": 1.0}
    assert leaf_rule_holds(result)


def test_a_slot_import_is_the_plain_host_call():
    host = Host()
    result = call(host, {"to": SLOT_REF, "path": "/absolute/path/audio.wav"})
    assert host.calls == [("clips.import", {"to": SLOT_REF, "path": "/absolute/path/audio.wav"})]
    assert result == host.answers["clips.import"]


def test_the_empty_slot_is_searched_through_every_scene_window():
    host = Host(**{"launcher.list": [slots(True, True, complete=False), slots(False, offset=2)]})
    call(host, {"to": TO, "path": "/samples/beat.wav"})
    assert host.calls[:2] == [
        ("launcher.list", {"target": TRACK_REF}),
        ("launcher.list", {"target": TRACK_REF, "sceneOffset": 2}),
    ]
    assert host.calls[2][1]["to"]["scene"]["index"] == 2


def test_a_track_without_an_empty_slot_is_refused_before_anything_is_written():
    host = Host(**{"launcher.list": slots(True, True)})
    envelope = run(host, {"to": TO, "path": "/samples/beat.wav"})
    assert isinstance(envelope, Error)
    assert envelope.error["reason"] == "limit_reached"
    assert "scenes.insert" in envelope.error["next"]
    assert [method for method, _ in host.calls] == ["launcher.list"]


def test_an_import_error_is_the_call_error():
    host = Host(**{"clips.import": bridge_error("invalid_file", "Import requires a readable regular local file.")})
    envelope = run(host, {"to": TO, "path": "/samples/missing.wav"})
    assert isinstance(envelope, Error)
    assert envelope.error["reason"] == "invalid_file"


def test_an_unknown_import_runs_nothing_else():
    unknown = unknown_result("not_observed", "No slot content transition.", next="Inspect launcher.list, then ack.")
    host = Host(**{"clips.import": unknown})
    result = call(host, {"to": TO, "path": "/samples/beat.wav"})
    assert steps(result) == [
        ("import", "unknown", "not_observed"),
        ("copy", "not_applied", "not_reached"),
        ("delete", "not_applied", "not_reached"),
    ]
    assert result["reason"] == "not_observed"
    assert result["next"] == "Inspect launcher.list, then ack."
    assert "ref" not in result


def test_a_refused_copy_leaves_the_launcher_clip_named_in_next():
    host = Host(**{"clips.copy": bridge_error("overlap", "Another clip is there.")})
    result = call(host, {"to": TO, "path": "/samples/beat.wav"})
    assert steps(result) == [
        ("import", "applied", None),
        ("copy", "not_applied", "overlap"),
        ("delete", "not_applied", "not_reached"),
    ]
    assert result["reason"] == "overlap"
    assert "do not repeat clips.import" in result["next"]
    assert dumps(TEMPORARY) in result["next"]  # JSON an agent can pass back, not a Python repr
    assert "ref" not in result


def test_an_unknown_copy_says_to_inspect_the_arranger_before_anything_else():
    unknown = unknown_result("not_observed", "The arranger copy was not observed.", next="arranger.list")
    host = Host(**{"clips.copy": unknown})
    result = call(host, {"to": TO, "path": "/samples/beat.wav"})
    assert steps(result) == [
        ("import", "applied", None),
        ("copy", "unknown", "not_observed"),
        ("delete", "not_applied", "not_reached"),
    ]
    assert result["next"] == (
        f"The copy is unknown and the clip may already be on the arranger: inspect arranger.list "
        f"{dumps({'target': TRACK_REF})} before any further write, then ack arranger.quarantine. Only if the clip is "
        f"there, delete the launcher clip {dumps(TEMPORARY)} with clips.delete; never resend clips.copy or "
        "clips.import. The copy step said: arranger.list"
    )


def test_an_unknown_delete_says_to_inspect_the_slot_never_to_resend_the_delete():
    unknown = unknown_result("not_observed", "Clip delete was not observed.")
    host = Host(**{"clips.delete": unknown})
    result = call(host, {"to": TO, "path": "/samples/beat.wav"})
    assert result["outcome"] == "unknown"
    assert result["ref"] == ARRANGER
    assert result["next"] == (
        f"The arranger clip is in this result; the delete of the launcher clip {dumps(TEMPORARY)} is unknown: inspect "
        f"launcher.list {dumps({'target': TRACK_REF})} before any further write; never resend clips.delete."
    )
    assert leaf_rule_holds(result)


def test_a_refused_delete_names_the_slot_clip_to_delete():
    host = Host(**{"clips.delete": bridge_error("busy", "Another write is running.")})
    result = call(host, {"to": TO, "path": "/samples/beat.wav"})
    assert result["ref"] == ARRANGER
    assert result["next"].endswith(f"the launcher clip {dumps(TEMPORARY)} remains: delete it with clips.delete.")


def test_a_file_soundfile_cannot_read_has_no_seconds(tmp_path):
    midi = tmp_path / "riff.mid"
    midi.write_bytes(b"MThd")
    assert file_import.describe(str(midi)) == {"name": "riff", "seconds": None}
