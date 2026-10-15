import asyncio
import json
import os
import sys

import pytest

from bitwig_agent.cli import execute, main, parse_arguments, parser
from bitwig_agent.catalog import NOTE_WINDOW, TARGET, TOOLS, TRACK

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


async def test_cli_preserves_grid_unknown_outcome_and_raw_expected_value_without_retry(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    error = {"code": "deadline_exceeded", "message": "Inspect actual parameter before editing again",
             "outcome": "unknown", "details": {"dispatched": True}}
    host.reply = lambda request: {"id": request["id"], "error": error}
    payload, code = await execute([
        "grid.parameters.set", f"trackId={TRACK}", "deviceIndex=0", "expectedDeviceName=Poly Grid",
        f"moduleId={TRACK}", "parameterId=", "expectedKind=float",
        "expectedValue=9007199254740993", "value=9007199254740994",
    ])
    assert code == 1
    assert payload == {"error": error}
    assert host.connections == 1
    assert len(host.requests) == 1
    assert host.requests[0]["params"]["expectedValue"] == 9007199254740993
    assert type(host.requests[0]["params"]["expectedValue"]) is int
    assert host.requests[0]["params"]["parameterId"] == ""


async def test_cli_lost_response_is_unknown_not_replayed(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: None
    payload, code = await execute(["transport.set", "playing=false"])
    assert code == 1
    assert payload["error"]["code"] == "transport_error"
    assert payload["error"]["outcome"] == "unknown"
    assert host.connections == 1


async def test_empty_grid_parameter_id_is_not_omitted_or_retargeted(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    args = {"trackId": TRACK, "deviceIndex": 15, "expectedDeviceName": "Poly Grid",
            "moduleId": TRACK, "parameterId": ""}
    arguments = [f"{key}={value}" for key, value in args.items()]
    error = {"code": "stale_context", "message": "Module is no longer present", "outcome": "not_applied"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    payload, code = await execute(["grid.parameters.get", *arguments, "--timeout-ms", "250"])
    assert code == 1
    assert payload == {"error": error}
    assert host.requests[0]["params"] == args
    assert host.requests[0]["timeoutMs"] == 250
    assert host.connections == 1


@pytest.mark.parametrize("value", ["1.0", "1" + "0" * 400])
async def test_grid_cli_numeric_tokens_fail_cleanly_before_tcp(host, monkeypatch, value):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute([
        "grid.parameters.set", "--params",
        f'{{"trackId":"{TRACK}","deviceIndex":0,"expectedDeviceName":"Poly Grid",'
        f'"moduleId":"{TRACK}","parameterId":"","expectedKind":"integer",'
        f'"expectedValue":0,"value":{value}}}',
    ])
    assert code == 2
    assert payload["error"]["code"] == "invalid_params"
    assert payload["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("field", ["expectedX", "expectedY", "x", "y"])
@pytest.mark.parametrize("token", [
    "null", "true", "false", "1.0", "1e0", "-0.0", "0e0", '"1"',
    "-2147483649", "2147483648", "1e9999",
])
async def test_grid_move_cli_rejects_non_integer_tokens_before_tcp(host, monkeypatch, raw, field, token):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    tokens = {"trackId": json.dumps(TRACK), "deviceIndex": "0",
              "expectedDeviceName": '"Poly Grid"', "moduleId": json.dumps(TRACK),
              "expectedX": "0", "expectedY": "0", "x": "4", "y": "2", field: token}
    arguments = (["--params", "{" + ",".join(f'"{key}":{value}' for key, value in tokens.items()) + "}"]
                 if raw else [f"{key}={value}" for key, value in tokens.items()])
    payload, code = await execute(["grid.modules.move", *arguments])
    assert code == 2
    assert payload["error"]["code"] == "invalid_params"
    assert payload["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("expected,source", [
    (None, {"moduleId": TRACK, "portKey": ""}),
    ({"moduleId": TRACK, "portKey": "out=α"}, None),
    (None, None),
])
def test_connection_cli_parses_required_null_and_endpoint_without_defaults(raw, expected, source):
    values = {**TARGET, "targetModuleId": TRACK, "targetPortKey": "",
              "expectedSource": expected, "source": source}
    arguments = (["--params", json.dumps(values)] if raw else [
        f"{key}={value if isinstance(value, str) else json.dumps(value)}"
        for key, value in values.items()
    ])
    parsed = parser().parse_intermixed_args(["grid.connections.set", *arguments, "--timeout-ms", "250"])
    params, timeout = parse_arguments(parsed.command, parsed.arguments, parsed.params, parsed.timeout_ms)
    assert params == values
    assert timeout == 250


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("field", ["expectedSource", "source"])
@pytest.mark.parametrize("token", [
    None, '"null"', "[]", "false", "{}",
    '{"moduleId":"1-1-1-1-1","portKey":""}',
    '{"moduleId":"' + TRACK + '","portKey":null}',
    '{"moduleId":"' + TRACK + '","portKey":"","extra":1}',
    '{"moduleId":"' + TRACK + '","portKey":"","portKey":"other"}',
])
async def test_connection_cli_rejects_missing_or_invalid_cas_endpoint_before_tcp(host, monkeypatch, raw, field, token):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    tokens = {key: json.dumps(value) for key, value in {
        **TARGET, "targetModuleId": TRACK, "targetPortKey": "",
        "expectedSource": None, "source": None,
    }.items()}
    if token is None:
        del tokens[field]
    else:
        tokens[field] = token
    arguments = (["--params", "{" + ",".join(f'"{key}":{value}' for key, value in tokens.items()) + "}"]
                 if raw else [f"{key}={value}" for key, value in tokens.items()])
    payload, code = await execute(["grid.connections.set", *arguments])
    assert code == 2
    assert payload["error"]["code"] == "invalid_params"
    assert payload["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_offline_discovery_exposes_connection_examples_and_complete_registry():
    payload, code = await execute(["list"])
    assert code == 0
    assert set(payload["result"]["methods"]) == set(TOOLS)
    assert len(payload["result"]["methods"]) == 37
    payload, code = await execute(["describe", "grid.connections.set"])
    assert code == 0
    description = payload["result"]
    assert description["readOnly"] is False
    assert description["examples"][0]["expectedSource"] is None
    assert description["examples"][1]["source"] is None


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
