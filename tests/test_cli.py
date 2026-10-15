import asyncio
import json
import os
import sys

import pytest

from bitwig_agent.cli import execute, main
from bitwig_agent.catalog import NOTE_WINDOW, TRACK

pytestmark = pytest.mark.anyio


@pytest.mark.parametrize("arguments", [[], ["list"], ["list", "notes"], ["help"],
                                         ["describe", "notes.edit"], ["notes.edit", "--help"]])
async def test_offline_discovery_ignores_host_configuration(monkeypatch, arguments):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "not-a-port")
    payload, code = await execute(arguments)
    assert code == 0
    assert "result" in payload
    if arguments == ["describe", "notes.edit"]:
        assert payload["result"]["inputSchema"]["additionalProperties"] is False
        assert payload["result"]["examples"][0]["edits"][0]["op"] == "set"


async def test_typed_arguments_forward_without_null_defaults(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    result = {"status": "dispatched", "verification": {"verified": False}}
    host.reply = lambda request: {"id": request["id"], "result": result}
    payload, code = await execute(["tracks.set", f"trackId={TRACK}", "mute=false",
                                   "volumeNormalized=0.25", "name=Sketch = α", "--timeout-ms", "250"])
    assert code == 0
    assert payload == {"result": result}
    request = host.requests[0]
    assert request["params"] == {"trackId": TRACK, "mute": False,
                                  "volumeNormalized": 0.25, "name": "Sketch = α"}
    assert request["timeoutMs"] == 250
    assert host.connections == 1


async def test_nested_note_arguments_and_raw_json_are_equivalent(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    edits = [{"op": "set", "beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1},
             {"op": "clear", "beat": 1, "pitch": 64}]
    args = {**NOTE_WINDOW, "edits": edits}
    typed = [f"{key}={value if isinstance(value, str) else json.dumps(value)}" for key, value in args.items()]
    for argv in [["notes.edit", *typed], ["notes.edit", "--params", json.dumps(args)]]:
        payload, code = await execute(argv)
        assert code == 0, payload
        assert host.requests[-1]["params"] == args
    assert "durationBeats" not in host.requests[-1]["params"]["edits"][1]


@pytest.mark.parametrize("arguments", [
    ["missing"], ["status", "unexpected=1"], ["status", "broken"],
    ["transport.set", "playing=False"], ["transport.set", "playing=1"],
    ["transport.set", "playing=null"], ["transport.set", "playing=true", "playing=false"],
    ["transport.set", "tempoBpm=NaN"], ["transport.set", "tempoBpm=1e9999"],
    ["transport.set", "tempoBpm=19"], ["transport.set"],
    ["tracks.set", f"trackId={TRACK}", "mute=true", "volumeNormalized=2"],
    ["tracks.create", "type=instrument", "position=1.5", "name=x"],
    ["launcher.import", f"trackId={TRACK}", "slotIndex=0", "path=relative.wav"],
    ["launcher.import", f"trackId={TRACK}", "slotIndex=0", "path=/tmp/audio.wav", "overwrite=true"],
    ["status", "--params", "[]"], ["status", "--params", "null"],
    ["transport.set", "--params", '{"playing":true,"playing":false}'],
    ["transport.set", "--params", '{"playing":true}', "playing=false"],
    ["status", "--timeout-ms", "99"], ["status", "--timeout-ms", "abc"],
    ["status", "timeoutMs=500", "--timeout-ms", "500"],
    ["status", "--unknown"], ["describe", "missing"], ["list", "unknown"],
])
async def test_invalid_cli_inputs_return_json_error_without_tcp(host, monkeypatch, arguments):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(arguments)
    assert code == 2
    assert payload["error"]["code"] == "invalid_params"
    assert payload["error"]["outcome"] == "not_dispatched"
    assert payload["error"]["message"]
    assert host.connections == 0


async def test_cli_preserves_unknown_outcome_without_retry(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    error = {"code": "deadline_exceeded", "message": "Inspect actual clip before editing again",
             "outcome": "unknown", "details": {"dispatched": True, "appliedEdits": [0]}}
    host.reply = lambda request: {"id": request["id"], "error": error}
    payload, code = await execute(["transport.set", "playing=false"])
    assert code == 1
    assert payload == {"error": error}
    assert host.connections == 1


async def test_cli_lost_response_is_unknown_not_replayed(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: None
    payload, code = await execute(["transport.set", "playing=false"])
    assert code == 1
    assert payload["error"]["code"] == "transport_error"
    assert payload["error"]["outcome"] == "unknown"
    assert host.connections == 1


def test_main_prints_one_exact_json_envelope(capsys, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "bad")
    assert main(["status"]) == 2
    captured = capsys.readouterr()
    assert captured.err == ""
    assert len(captured.out.splitlines()) == 1
    assert json.loads(captured.out)["error"]["outcome"] == "not_dispatched"


async def test_offline_entrypoint_and_client_do_not_import_mcp():
    script = "import sys; import bitwig_agent.cli; import bitwig_agent.client; assert not any(n == 'mcp' or n.startswith('mcp.') for n in sys.modules)"
    process = await asyncio.create_subprocess_exec(sys.executable, "-c", script,
                                                 stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    stdout, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    assert stdout == b""
    process = await asyncio.create_subprocess_exec(sys.executable, "-m", "bitwig_agent.cli", "describe", "notes.edit",
                                                 env={**os.environ, "BITWIG_AGENT_PORT": "bad"},
                                                 stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    stdout, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    assert json.loads(stdout)["result"]["method"] == "notes.edit"
