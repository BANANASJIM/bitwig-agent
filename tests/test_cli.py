import asyncio
import csv
import io
import json
import os
import shlex
import sys

import pytest
from fakes import INVALID_NAMES, LAYER_UNKNOWN_RESULT, assert_rejected_before_tcp, parse_arguments, string_panel_result
from fakes import execute_cli as execute
from pydantic import ValidationError

from bitwig_agent.core import views
from bitwig_agent.frontends import cli
from bitwig_agent.frontends.cli import main
from bitwig_agent.protocol import TOOLS, invalid_params_message, validate_call
from bitwig_agent.protocol.base import REASONS, TARGET, TRACK
from bitwig_agent.protocol.launcher import NOTE_WINDOW

pytestmark = [pytest.mark.anyio, pytest.mark.usefixtures("all_methods")]  # parser tests cover hidden blocks too


def flags(method, params):
    """The method's arguments as --field=value flags: a string field is literal, any other is JSON."""
    types = views.field_types(method)
    return [
        f"--{key}={value if types[key] == 'string' and isinstance(value, str) else json.dumps(value, ensure_ascii=False)}"
        for key, value in params.items()
    ]


async def test_typed_arguments_forward_without_null_defaults(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    result = {"status": "dispatched", "verification": {"verified": False}}
    host.reply = lambda request: {"id": request["id"], "result": result}
    payload, code = await execute(
        [
            "tracks.set",
            f"--trackId={TRACK}",
            "--mute=false",
            "--volumeNormalized=0.25",
            "--name=Sketch = \u03b1",
            "--timeoutMs",
            "250",
        ]
    )
    assert code == 0
    assert payload == {"result": result}
    request = host.requests[0]
    assert request["params"] == {"trackId": TRACK, "mute": False, "volumeNormalized": 0.25, "name": "Sketch = \u03b1"}
    assert request["timeoutMs"] == 250
    assert host.connections == 1


async def test_nested_note_arguments_and_raw_json_are_equivalent(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    edits = [
        {"op": "set", "beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1},
        {"op": "clear", "beat": 1, "pitch": 64},
    ]
    args = {**NOTE_WINDOW, "edits": edits}
    typed = flags("notes.edit", args)
    for argv in [["notes.edit", *typed], ["notes.edit", json.dumps(args)]]:
        payload, code = await execute(argv)
        assert code == 0, payload
        assert host.requests[-1]["params"] == args
    assert "durationBeats" not in host.requests[-1]["params"]["edits"][1]


@pytest.mark.parametrize(
    "arguments",
    [
        ["status", "--unexpected=1"],
        ["status", "broken"],
        ["transport.set", "--playing=False"],
        ["transport.set", "--playing=1"],
        ["transport.set", "--playing=null"],
        ["transport.set", "--playing=true", "--playing=false"],
        ["transport.set", "--tempoBpm=NaN"],
        ["transport.set", "--tempoBpm=1e9999"],
        ["transport.set", "--tempoBpm=19"],
        ["transport.set"],
        ["tracks.set", f"--trackId={TRACK}", "--mute=true", "--volumeNormalized=2"],
        ["tracks.create", "--type=instrument", "--position=1.5", "--name=x"],
        ["launcher.import", f"--trackId={TRACK}", "--slotIndex=0", "--path=relative.wav"],
        ["launcher.import", f"--trackId={TRACK}", "--slotIndex=0", "--path=/tmp/audio.wav", "--overwrite=true"],
        ["status", "[]"],
        ["status", "null"],
        ["transport.set", '{"playing":true,"playing":false}'],
        ["transport.set", '{"playing":true}', "--playing=false"],
        ["status", "--timeoutMs=99"],
        ["status", "--timeoutMs=abc"],
        ["status", "--timeoutMs=500", '{"timeoutMs":500}'],
        ["status", "--unknown"],
        ["status", "-x"],
        ["status", "{}", "{}"],
        ["status", "@/nonexistent/args.json"],
        ["status", "-"],
        ["tracks"],
    ],
)
async def test_invalid_cli_inputs_return_json_error_without_tcp(host, monkeypatch, arguments):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(arguments)
    assert code == 2
    assert payload["error"]["code"] == "invalid_params"
    assert "outcome" not in payload["error"]
    assert payload["error"]["message"]
    assert host.connections == 0


async def test_cli_preserves_grid_unknown_outcome_and_raw_expected_value_without_retry(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    unknown = LAYER_UNKNOWN_RESULT
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    payload, code = await execute(
        [
            "grid.parameters.set",
            f"--trackId={TRACK}",
            "--deviceIndex=0",
            "--expectedDeviceName=Poly Grid",
            f"--moduleId={TRACK}",
            "--parameterId=",
            "--expectedKind=float",
            "--expectedValue=9007199254740993",
            "--value=9007199254740994",
        ]
    )
    assert code == 3
    assert payload == {"result": unknown}
    assert host.connections == 1
    assert len(host.requests) == 1
    assert host.requests[0]["params"]["expectedValue"] == 9007199254740993
    assert type(host.requests[0]["params"]["expectedValue"]) is int
    assert host.requests[0]["params"]["parameterId"] == ""


async def test_cli_lost_response_is_unknown_not_replayed(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: None
    payload, code = await execute(["transport.set", "--playing=false"])
    assert code == 3
    assert payload["result"]["reason"] == "transport"
    assert payload["result"]["outcome"] == "unknown"
    assert host.connections == 1


async def test_empty_grid_parameter_id_is_not_omitted_or_retargeted(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    args = {
        "trackId": TRACK,
        "deviceIndex": 15,
        "expectedDeviceName": "Poly Grid",
        "moduleId": TRACK,
        "parameterId": "",
    }
    arguments = flags("grid.parameters.get", args)
    error = {
        "code": REASONS["stale_context"].value,
        "reason": "stale_context",
        "message": "Module is no longer present",
    }
    host.reply = lambda request: {"id": request["id"], "error": error}
    payload, code = await execute(["grid.parameters.get", *arguments, "--timeoutMs", "250"])
    assert code == 1
    assert payload == {"error": error}
    assert host.requests[0]["params"] == args
    assert host.requests[0]["timeoutMs"] == 250
    assert host.connections == 1


@pytest.mark.parametrize("value", ["1.0", "1" + "0" * 400])
async def test_grid_cli_numeric_tokens_fail_cleanly_before_tcp(host, monkeypatch, value):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(
        [
            "grid.parameters.set",
            f'{{"trackId":"{TRACK}","deviceIndex":0,"expectedDeviceName":"Poly Grid",'
            f'"moduleId":"{TRACK}","parameterId":"","expectedKind":"integer",'
            f'"expectedValue":0,"value":{value}}}',
        ]
    )
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


def token_arguments(method, tokens, raw):
    """JSON-text tokens as one ARGS object, or as flags (a string field's JSON string becomes the literal)."""
    if raw:
        return ["{" + ",".join(f'"{key}":{value}' for key, value in tokens.items()) + "}"]
    types = views.field_types(method)
    return [
        f"--{key}={json.loads(value) if types[key] == 'string' and value.startswith(chr(34)) else value}"
        for key, value in tokens.items()
    ]


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("field", ["expectedX", "expectedY", "x", "y"])
@pytest.mark.parametrize(
    "token",
    [
        "null",
        "true",
        "false",
        "1.0",
        "1e0",
        "-0.0",
        "0e0",
        '"1"',
        "-2147483649",
        "2147483648",
        "1e9999",
    ],
)
async def test_grid_move_cli_rejects_non_integer_tokens_before_tcp(host, monkeypatch, raw, field, token):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    tokens = {
        "trackId": json.dumps(TRACK),
        "deviceIndex": "0",
        "expectedDeviceName": '"Poly Grid"',
        "moduleId": json.dumps(TRACK),
        "expectedX": "0",
        "expectedY": "0",
        "x": "4",
        "y": "2",
        field: token,
    }
    arguments = token_arguments("grid.modules.move", tokens, raw)
    payload, code = await execute(["grid.modules.move", *arguments])
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize(
    ("expected", "source"),
    [
        (None, {"moduleId": TRACK, "portKey": ""}),
        ({"moduleId": TRACK, "portKey": "out=\u03b1"}, None),
        (None, None),
    ],
)
def test_connection_cli_parses_required_null_and_endpoint_without_defaults(raw, expected, source):
    values = {**TARGET, "targetModuleId": TRACK, "targetPortKey": "", "expectedSource": expected, "source": source}
    arguments = [json.dumps(values)] if raw else flags("grid.connections.set", values)
    params, timeout = parse_arguments("grid.connections.set", [*arguments, "--timeoutMs", "250"])
    assert params == values
    assert timeout == 250


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("field", ["expectedSource", "source"])
@pytest.mark.parametrize(
    "token",
    [
        None,
        '"null"',
        "[]",
        "false",
        "{}",
        '{"moduleId":"1-1-1-1-1","portKey":""}',
        '{"moduleId":"' + TRACK + '","portKey":null}',
        '{"moduleId":"' + TRACK + '","portKey":"","extra":1}',
        '{"moduleId":"' + TRACK + '","portKey":"","portKey":"other"}',
    ],
)
async def test_connection_cli_rejects_missing_or_invalid_cas_endpoint_before_tcp(host, monkeypatch, raw, field, token):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    tokens = {
        key: json.dumps(value)
        for key, value in {
            **TARGET,
            "targetModuleId": TRACK,
            "targetPortKey": "",
            "expectedSource": None,
            "source": None,
        }.items()
    }
    if token is None:
        del tokens[field]
    else:
        tokens[field] = token
    arguments = token_arguments("grid.connections.set", tokens, raw)
    payload, code = await execute(["grid.connections.set", *arguments])
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


@pytest.mark.parametrize("raw", [False, True])
async def test_grid_insert_cli_preserves_loader_unknown_without_retry(host, monkeypatch, raw):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    params = {**TARGET, "typeId": TRACK, "x": -(2**31), "y": 2**31 - 1}
    arguments = [json.dumps(params)] if raw else flags("grid.modules.insert", params)
    unknown = {
        "outcome": "unknown",
        "reason": "deadline",
        "message": "Inspect actual graph",
        "details": {"loaderDispatched": True, "placementDispatched": False},
    }
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    payload, code = await execute(["grid.modules.insert", *arguments, "--timeoutMs", "250"])
    assert code == 3
    assert payload == {"result": unknown}
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("field", ["x", "y"])
@pytest.mark.parametrize("token", ["null", "true", "1.0", "1e0", '"1"', "-2147483649", "2147483648"])
async def test_grid_insert_cli_rejects_non_int32_tokens_before_tcp(host, monkeypatch, raw, field, token):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    tokens = {
        key: json.dumps(value)
        for key, value in {
            **TARGET,
            "typeId": TRACK,
            "x": 0,
            "y": 0,
        }.items()
    }
    tokens[field] = token
    arguments = token_arguments("grid.modules.insert", tokens, raw)
    payload, code = await execute(["grid.modules.insert", *arguments])
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


async def test_grid_insert_offline_schema_exposes_complete_contract(monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "not-a-port")
    out = await cli.execute(["help", "grid.modules.insert"])
    assert out.code == 0
    description = json.loads(out.stdout)
    assert description["readOnly"] is False
    assert set(description["inputSchema"]["required"]) == {"trackId", "deviceIndex", "expectedDeviceName"}
    assert {tuple(sorted(branch["required"])) for branch in description["inputSchema"]["oneOf"]} == {
        ("x", "y"),
        ("modules",),
    }
    assert description["inputSchema"]["additionalProperties"] is False


def test_main_prints_the_error_on_stderr_only(capsys, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "bad")
    assert main(["status"]) == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert len(captured.err.splitlines()) == 1
    assert "outcome" not in json.loads(captured.err)


async def test_offline_entrypoint_and_client_do_not_import_mcp():
    script = "import sys; import bitwig_agent.frontends.cli; import bitwig_agent.client; assert not any(n == 'mcp' or n.startswith('mcp.') for n in sys.modules)"
    process = await asyncio.create_subprocess_exec(
        sys.executable, "-c", script, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    stdout, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    assert stdout == b""
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-m",
        "bitwig_agent.frontends.cli",
        "help",
        "notes.edit",
        env={**os.environ, "BITWIG_AGENT_PORT": "bad"},
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    assert json.loads(stdout)["method"] == "notes.edit"


@pytest.mark.parametrize(
    "changes",
    [
        {"parent": None},
        {"parent": {**TARGET, "timeoutMs": 250}},
        {"parent": {**TARGET, "deviceIndex": 1.0}},
        {"parent": {**TARGET, "padIndex": 0}},
        {"parent": {**TARGET, "slotIndex": 0}},
        {"parent": {**TARGET, "parent": TARGET}},
        {"layerId": "1-1-1-1-1"},
        {"childDeviceIndex": 2},
        {"childDeviceIndex": True},
        {"childDeviceIndex": 1.0},
        {"discoveryToken": ""},
        {"contextToken": None},
        {"expectedChildDeviceName": ""},
        {"value": "0.5"},
        {"value": True},
        {"value": -0.01},
        {"path": []},
        {"trackId": TRACK},
    ],
)
async def test_layer_cli_rejects_invalid_nested_params_before_tcp(host, monkeypatch, changes):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    params = {**TOOLS["parameters.layer.set"].examples[0], **changes}
    payload, code = await execute(["parameters.layer.set", json.dumps(params)])
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


async def test_layer_cli_preserves_unknown_without_retry(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    unknown = {
        "outcome": "unknown",
        "reason": "deadline",
        "message": "Inspect child before another write",
        "details": {"dispatched": True},
    }
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    params = TOOLS["parameters.layer.set"].examples[0]
    payload, code = await execute(["parameters.layer.set", json.dumps(params)])
    assert code == 3
    assert payload == {"result": unknown}
    assert host.requests[0]["params"] == params
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("method", ["modulators.capabilities", "modulators.list", "modulators.parameters.get"])
async def test_modulator_generated_help_and_parse_work_offline(monkeypatch, method):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "not-a-port")
    out = await cli.execute([*method.split("."), "--help"])
    assert out.code == 0
    assert "(read-only)" in out.stdout
    schema = json.loads((await cli.execute(["help", method])).stdout)
    assert schema == views.method(method)
    args = schema["examples"][0]
    assert parse_arguments(method, flags(method, args)) == (args, 5000)
    if method != "modulators.capabilities":
        parent = schema["inputSchema"]["$defs"]["DeviceParent"]
        assert parent["additionalProperties"] is False
        assert set(parent["required"]) == {"trackId", "deviceIndex", "expectedDeviceName"}
    listing = (await cli.execute(["modulators", "--help"])).stdout
    for name in views.names():
        if name.startswith("modulators."):
            assert f"\n  {name[len('modulators.') :].replace('.', ' ')} " in listing


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize("value", ["", " \tMacro \u03b1𝄞\n "])
async def test_modulator_cli_preserves_raw_string_and_null_metadata(host, monkeypatch, raw, value):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    params = {**TOOLS["modulators.parameters.get"].examples[0], "parameterId": ""}
    result = string_panel_result(params["modulatorId"], value)
    host.reply = lambda request: {"id": request["id"], "result": result}
    arguments = [json.dumps(params)] if raw else flags("modulators.parameters.get", params)
    payload, code = await execute(["modulators.parameters.get", *arguments])
    assert code == 0
    assert json.loads(json.dumps(payload)) == {"result": result}
    assert host.requests[0]["params"]["parameterId"] == ""
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize(
    "changes",
    [
        {"parent": {**TARGET, "deviceIndex": True}},
        {"parent": {**TARGET, "deviceIndex": 1.0}},
        {"modulatorId": "Macro"},
        {"parameterId": None},
        {"parameterId": "𝄞" * 513},
        {"value": "forbidden"},
    ],
)
async def test_modulator_cli_invalid_inputs_never_connect(host, monkeypatch, changes):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    params = {**TOOLS["modulators.parameters.get"].examples[0], **changes}
    payload, code = await execute(["modulators.parameters.get", json.dumps(params)])
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


@pytest.mark.parametrize(
    "error_code",
    [
        "native_unavailable",
        "out_of_bounds",
        "unsupported_scope",
        "stale_context",
    ],
)
async def test_modulator_cli_preserves_host_error_without_retry(host, monkeypatch, error_code):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    error = {"code": REASONS[error_code].value, "reason": error_code, "message": "Read rejected"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    payload, code = await execute(
        ["modulators.parameters.get", json.dumps(TOOLS["modulators.parameters.get"].examples[0])]
    )
    assert code == (2 if error["code"] == "invalid_params" else 1)
    assert payload == {"error": error}
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("raw", [False, True])
@pytest.mark.parametrize(
    ("expected", "requested"),
    [
        ("", ""),
        (" \t原音=𝄞\n", "  Lead = \u03b1  "),
        ("null", "false"),
        ("𝄞" * 512, ""),
        ("", '"quoted"'),
    ],
)
def test_grid_rename_cli_preserves_raw_cas_and_reset_without_coercion(raw, expected, requested):
    params = {**TARGET, "moduleId": TRACK, "expectedCustomName": expected, "customName": requested}
    arguments = [json.dumps(params)] if raw else flags("grid.modules.rename", params)
    actual, _ = parse_arguments("grid.modules.rename", arguments)
    assert actual == params


@pytest.mark.parametrize("field", ["expectedCustomName", "customName"])
@pytest.mark.parametrize("value", INVALID_NAMES)
async def test_grid_rename_cli_rejects_invalid_raw_json_name_before_tcp(host, monkeypatch, field, value):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    params = {**TARGET, "moduleId": TRACK, "expectedCustomName": "", "customName": "", field: value}
    payload, code = await execute(["grid.modules.rename", json.dumps(params)])
    assert code == 2
    assert_rejected_before_tcp(payload["error"], host)


@pytest.mark.parametrize("raw", [False, True])
async def test_grid_rename_cli_unknown_preserves_native_uncertainty_without_retry(host, monkeypatch, raw):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    params = {**TARGET, "moduleId": TRACK, "expectedCustomName": " \t\u03b1 ", "customName": ""}
    arguments = [json.dumps(params)] if raw else flags("grid.modules.rename", params)
    unknown = {
        "outcome": "unknown",
        "reason": "stale_context",
        "message": "Inspect actual raw name",
        "details": {"mutationDispatched": True, "rollbackVerified": False},
    }
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    payload, code = await execute(["grid.modules.rename", *arguments])
    assert code == 3
    assert payload == {"result": unknown}
    assert host.requests[0]["params"] == params
    assert len(host.requests) == host.connections == 1


def test_unknown_argument_names_closest_field():

    with pytest.raises(ValidationError) as error:
        validate_call("grid.connections.set", {"moduleId": "x"})
    message = invalid_params_message("grid.connections.set", error.value)
    assert "did you mean targetModuleId?" in message
    assert "errors.pydantic.dev" not in message
    assert "timeoutMs" not in message.split("Accepted:")[1]


def test_unknown_argument_synonyms_hint_but_never_alias():

    target = {"trackId": "12345678-1234-1234-1234-123456789abc", "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
    for method, bad, want in (
        ("grid.get", {**target, "offset": 0, "limit": 5}, ["moduleOffset", "moduleLimit"]),
        ("transport.set", {"tempo": 120}, ["tempoBpm"]),
        ("grid.connections.set", {**target, "instanceId": "x"}, ["targetModuleId"]),
    ):
        with pytest.raises(ValidationError) as error:
            validate_call(method, bad)
        message = invalid_params_message(method, error.value)
        assert "no alias is accepted" in message, message
        assert all(w in message for w in want), message


def test_nested_unknown_argument_names_full_path_and_siblings():

    args = {
        "trackId": "abcdef01-2345-6789-abcd-0123456789ab",
        "name": "x",
        "startBeats": 0,
        "lengthBeats": 4,
        "notes": [
            {"beat": 0, "pitch": 60, "velocity": 0.8, "durationBeats": 1},
            {"beat": 1, "pitch": 62, "velocity": 0.8, "durationBeats": 1, "channel": 2},
        ],
    }
    with pytest.raises(ValidationError) as error:
        validate_call("arranger.clips.create", args)
    message = invalid_params_message("arranger.clips.create", error.value)
    assert "notes[1].channel" in message
    assert "Accepted at notes[]: " in message
    assert "durationBeats" in message
    assert "velocity" in message
    assert "notes (did you mean notes?)" not in message
    assert "notes.1" not in message


ACTIONS = {
    "scope": "host_action_catalog",
    "total": 3,
    "complete": True,
    "actions": [
        {"actionId": "a1", "name": "Plain", "category": {"id": "c", "name": "Edit"}},
        {"actionId": "a2", "name": 'Quote "x", comma', "category": None},
        {"actionId": "a3", "name": "Tab\there\nnewline", "category": {"id": "d", "name": "View"}},
    ],
}


def reply_with(host, result):
    host.reply = lambda request: {"id": request["id"], "result": result}


async def stdout_of(argv, host, monkeypatch, result=ACTIONS):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, result)
    return await cli.execute(argv)


@pytest.mark.parametrize("outcome", ["applied", "already_matched", "sent"])
async def test_successful_outcomes_exit_zero(host, monkeypatch, outcome):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"outcome": outcome})
    payload, code = await execute(["transport", "set", "--playing=false"])
    assert (code, payload) == (0, {"result": {"outcome": outcome}})


@pytest.mark.parametrize(("outcome", "expected"), [("not_applied", 1), ("unknown", 3)])
async def test_unsuccessful_outcomes_keep_the_result_on_stdout(host, monkeypatch, outcome, expected):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    result = {"outcome": outcome, "reason": "deadline", "message": "m", "entries": [{"outcome": outcome}]}
    reply_with(host, result)
    out = await cli.execute(["transport", "set", "--playing=false"])
    assert (out.code, json.loads(out.stdout), out.stderr) == (expected, result, "")


@pytest.mark.parametrize("code", sorted(set(REASONS.values()), key=str))
async def test_every_error_code_has_an_exit_code(host, monkeypatch, code):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reason = next(r for r, c in REASONS.items() if c == code)
    error = {"code": code.value, "reason": reason, "message": "m", "next": "n"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    out = await cli.execute(["status"])
    assert out.stdout == ""
    assert json.loads(out.stderr) == error
    assert out.code == (2 if code.value in {"invalid_params", "unknown_method"} else 1)


async def test_reads_exit_zero_without_outcome(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"scope": "flat_track_bank", "tracks": []})
    assert (await execute(["tracks", "list"])) == ({"result": {"scope": "flat_track_bank", "tracks": []}}, 0)


async def test_unknown_method_and_verb_are_usage_errors_with_suggestions(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    for argv in (["trakcs", "list"], ["tracks", "lst"]):
        payload, code = await execute(argv)
        assert code == 2
        assert payload["error"]["code"] == "unknown_method"
        assert "did you mean tracks.list?" in payload["error"]["message"]
    assert host.connections == 0


async def test_json_is_compact_in_a_pipe_and_indented_on_a_terminal(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"a": {"b": 1}})
    piped = await cli.execute(["tracks", "list"])
    shown = await cli.execute(["tracks", "list"], pretty=True)
    assert piped.stdout == '{"a":{"b":1}}\n'
    assert shown.stdout == '{\n  "a": {\n    "b": 1\n  }\n}\n'


def test_flag_forms_bare_boolean_separate_value_and_operand_end():
    params, timeout = parse_arguments("transport.set", ["--playing", "--timeoutMs", "250", "--", '{"tempoBpm":120}'])
    assert params == {"playing": True, "tempoBpm": 120}
    assert timeout == 250
    assert parse_arguments("tracks.set", [f"--trackId={TRACK}", "--name", "Bass"])[0]["name"] == "Bass"
    with pytest.raises(ValueError, match="needs a value"):
        parse_arguments("tracks.set", [f"--trackId={TRACK}", "--name", "-7 Bass"])


async def test_stdin_feeds_one_value_or_the_whole_operand(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"outcome": "applied"})
    assert (await execute(["transport", "set", "--tempoBpm=-"], stdin="120.5"))[1] == 0
    assert host.requests[-1]["params"] == {"tempoBpm": 120.5}
    assert (await execute(["transport", "set", "-"], stdin='{"playing": false}'))[1] == 0
    assert host.requests[-1]["params"] == {"playing": False}
    payload, code = await execute(["transport", "set", "--tempoBpm=-", "-"], stdin="1")
    assert code == 2
    assert "stdin can be read once" in payload["error"]["message"]
    assert len(host.requests) == 2


async def test_operand_file_and_flags_merge_and_a_repeat_is_an_error(host, monkeypatch, tmp_path):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"outcome": "applied"})
    path = tmp_path / "args.json"
    path.write_text('{"playing": true}')
    assert (await execute(["transport", "set", f"@{path}", "--tempoBpm=100"]))[1] == 0
    assert host.requests[-1]["params"] == {"playing": True, "tempoBpm": 100}
    payload, code = await execute(["transport", "set", f"@{path}", "--playing=false"])
    assert code == 2
    assert "Given both as flag and in ARGS: playing" in payload["error"]["message"]


async def test_a_pipeline_feeds_one_result_into_the_next_call(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"tracks": [{"trackId": TRACK, "name": "Bass"}], "scope": "s"})
    listed = await cli.execute(["tracks", "list", "--format=jsonl", "--fields=trackId"])
    ident = json.loads(listed.stdout.splitlines()[0])["trackId"]
    reply_with(host, {"outcome": "applied"})
    assert (await execute(["tracks", "set", f"--trackId={ident}", "--mute"]))[1] == 0
    assert host.requests[-1]["params"] == {"trackId": TRACK, "mute": True}


async def test_row_formats_print_only_the_records_and_json_keeps_the_rest(host, monkeypatch):
    out = await stdout_of(["actions", "list", "--format=jsonl"], host, monkeypatch)
    assert (out.code, out.stderr) == (0, "")
    assert "host_action_catalog" not in out.stdout
    assert (
        json.loads((await stdout_of(["actions", "list"], host, monkeypatch)).stdout)["scope"] == "host_action_catalog"
    )


PARTIAL = {"actions": [{"actionId": "a1"}], "total": 9, "complete": False}


async def test_a_partial_list_warns_once_on_stderr_and_still_exits_zero(host, monkeypatch):
    out = await stdout_of(["actions", "list", "--format=jsonl"], host, monkeypatch, result=PARTIAL)
    assert (out.code, out.stdout) == (0, '{"actionId":"a1"}\n')
    warning = json.loads(out.stderr)
    assert warning["warning"] == "partial"
    assert "--limit" in warning["message"]
    assert out.stderr.count("\n") == 1
    windowed = {"tracks": [{"trackId": TRACK}], "window": {"complete": False}}
    out = await stdout_of(["tracks", "list", "--format=csv"], host, monkeypatch, result=windowed)
    assert json.loads(out.stderr)["warning"] == "partial"
    plain = await stdout_of(["actions", "list"], host, monkeypatch, result=PARTIAL)
    assert plain.stderr == ""


async def test_jsonl_is_one_record_per_line(host, monkeypatch):
    out = await stdout_of(["actions", "list", "--format=jsonl"], host, monkeypatch)
    assert out.code == 0
    assert [json.loads(line) for line in out.stdout.splitlines()] == ACTIONS["actions"]
    assert out.stdout.count("\n") == 3


async def test_tsv_flattens_nested_fields_and_escapes_separators(host, monkeypatch):
    out = await stdout_of(["actions", "list", "--format=tsv"], host, monkeypatch)
    lines = out.stdout.split("\n")
    assert lines[0] == "actionId\tname\tcategory.id\tcategory.name"
    assert lines[1] == "a1\tPlain\tc\tEdit"
    assert lines[2] == 'a2\tQuote "x", comma\t\t'
    assert lines[3] == "a3\tTab\\there\\nnewline\td\tView"
    assert all(len(line.split("\t")) == 4 for line in lines[:-1])


async def test_csv_quotes_with_rfc_4180_and_selects_fields_in_order(host, monkeypatch):
    out = await stdout_of(
        ["actions", "list", "--format=csv", "--fields=name,category.name,actionId"], host, monkeypatch
    )
    table = list(csv.reader(io.StringIO(out.stdout), strict=True))
    assert table == [
        ["name", "category.name", "actionId"],
        ["Plain", "Edit", "a1"],
        ['Quote "x", comma', "", "a2"],
        ["Tab\there\nnewline", "View", "a3"],
    ]


async def test_fields_in_json_keep_the_other_fields_of_the_result(host, monkeypatch):
    out = await stdout_of(["actions", "list", "--fields=actionId,category.id"], host, monkeypatch)
    result = json.loads(out.stdout)
    assert result["scope"] == "host_action_catalog"
    assert result["actions"] == [
        {"actionId": "a1", "category.id": "c"},
        {"actionId": "a2", "category.id": None},
        {"actionId": "a3", "category.id": "d"},
    ]


@pytest.mark.parametrize(
    ("argv", "fragment"),
    [
        (["actions", "list", "--format=yaml"], "--format must be one of"),
        (["actions", "list", "--fields=a,,b"], "comma-separated"),
    ],
)
async def test_bad_row_options_are_usage_errors(host, monkeypatch, argv, fragment):
    out = await stdout_of(argv, host, monkeypatch)
    assert (out.code, out.stdout) == (2, "")
    assert fragment in json.loads(out.stderr)["message"]


@pytest.mark.parametrize(
    ("argv", "fragment"),
    [
        (["status", "--format=csv"], "has no record list"),
        (["notes", "read", "--format=csv"], "returns an outcome"),
        (["transport", "set", "--playing=false", "--fields=outcome"], "returns an outcome"),
    ],
)
async def test_methods_without_a_declared_record_list_refuse_row_options_before_the_call(
    host, monkeypatch, argv, fragment
):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(argv)
    assert code == 2
    assert fragment in payload["error"]["message"]
    assert host.connections == 0


async def test_the_declared_key_picks_the_records_among_several_arrays(host, monkeypatch):
    result = {"modules": [{"name": "a"}, {"name": "b"}], "connections": [{"from": "a", "to": "b"}], "warnings": []}
    out = await stdout_of([*["grid", "get"], *flags("grid.get", TARGET), "--format=jsonl"], host, monkeypatch, result)
    assert (out.code, out.stderr) == (0, "")
    assert out.stdout == '{"name":"a"}\n{"name":"b"}\n'
    out = await stdout_of(["tracks", "list", "--format=jsonl"], host, monkeypatch, {"tracks": [], "spare": []})
    assert (out.code, out.stdout) == (0, "")


async def test_a_declared_key_missing_from_the_result_is_a_usage_error(host, monkeypatch):
    out = await stdout_of(["tracks", "list", "--format=jsonl"], host, monkeypatch, {"other": []})
    assert out.code == 2
    assert "no array of objects named tracks" in json.loads(out.stderr)["message"]


async def test_a_field_no_record_has_is_an_empty_column_and_one_warning(host, monkeypatch):
    out = await stdout_of(["actions", "list", "--format=csv", "--fields=actionId,nme"], host, monkeypatch)
    assert out.code == 0
    assert out.stdout.splitlines()[:2] == ["actionId,nme", "a1,"]
    warning = json.loads(out.stderr)
    assert warning["warning"] == "unknown_field"
    assert "nme (did you mean name?)" in warning["message"]
    assert out.stderr.count("\n") == 1


def test_record_keys_are_declared_once_on_reads():
    assert views.records("tracks.list") == "tracks"
    assert views.records("grid.get") == "modules"
    assert views.records("status") is None
    assert all(spec.read_only for name, spec in TOOLS.items() if spec.records)


async def test_row_options_on_a_write_are_refused_before_the_call(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(["transport", "set", "--playing=false", "--fields=outcome"])
    assert code == 2
    assert "returns an outcome" in payload["error"]["message"]
    assert host.connections == 0


def test_a_methods_own_format_field_is_not_the_output_format():
    arguments = flags("grid.render", {**TARGET, "outPath": "/tmp/a.svg"})
    params, _ = parse_arguments("grid.render", [*arguments, "--format=png"])
    assert params["format"] == "png"


async def test_help_layers_are_plain_text_on_stdout_and_exit_zero(monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "not-a-port")
    for argv in ([], ["--help"], ["help"], ["-h"]):
        out = await cli.execute(argv)
        assert (out.code, out.stderr) == (0, "")
        assert "Exit codes:" in out.stdout
        assert "\x1b" not in out.stdout
    overview = (await cli.execute(["--help"])).stdout
    assert len(overview.encode()) < 2500
    assert all(f"  {name} " in overview for name in {n.split(".")[0] for n in TOOLS})
    resource = (await cli.execute(["tracks", "--help"])).stdout
    assert "\n  list " in resource
    assert "\n  sends set " in resource
    method = (await cli.execute(["tracks", "set", "--help"])).stdout
    assert "(writes)" in method
    assert "(required)" in method
    assert "Example:\n  bitwig-agent tracks set --trackId=" in method
    assert (await cli.execute(["help", "tracks"])).stdout == resource
    assert json.loads((await cli.execute(["help", "tracks.set"])).stdout)["method"] == "tracks.set"


async def test_version_and_unknown_help_target():
    out = await cli.execute(["--version"])
    assert out.stdout == f"bitwig-agent {views.version()}\n"
    out = await cli.execute(["help", "nope"])
    assert (out.code, out.stdout) == (2, "")


@pytest.mark.parametrize("method", sorted(TOOLS))
def test_every_help_example_round_trips_through_the_parser(method):
    arguments = TOOLS[method].examples[0]
    line = cli.example_command(method, arguments)
    tokens = shlex.split(line)[1 + len(method.split(".")) :]
    assert parse_arguments(method, tokens) == validate_call(method, arguments)


def test_main_writes_data_to_stdout_and_the_exit_code(capsys, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "bad")
    assert main(["--version"]) == 0
    captured = capsys.readouterr()
    assert captured.out.startswith("bitwig-agent ")
    assert captured.err == ""


def lines_of(*calls):
    return "\n".join(json.dumps(call) for call in calls) + "\n"


async def test_batch_runs_lines_in_order_and_prints_one_result_line_each(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"outcome": "applied"})
    stdin = lines_of(
        {"method": "tracks.set", "params": {"trackId": TRACK, "mute": True}},
        {"method": "transport.set", "params": {"playing": False}},
    )
    out = await cli.execute(["batch"], stdin=lambda: stdin)
    assert (out.code, out.stderr) == (0, "")
    assert out.stdout == '{"outcome":"applied"}\n' * 2
    assert [r["method"] for r in host.requests] == ["tracks.set", "transport.set"]


async def test_batch_stops_at_the_first_unsuccessful_outcome_with_its_code(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    outcomes = iter(["applied", "unknown", "applied"])
    host.reply = lambda request: {"id": request["id"], "result": {"outcome": next(outcomes)}}
    stdin = lines_of(*({"method": "transport.set", "params": {"playing": False}},) * 3)
    out = await cli.execute(["batch", "-"], stdin=lambda: stdin)
    assert out.code == 3
    assert out.stdout.splitlines() == ['{"outcome":"applied"}', '{"outcome":"unknown"}']
    assert len(host.requests) == 2


async def test_batch_stops_at_the_first_error_and_names_the_line(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    responses = iter(
        [
            {"result": {"outcome": "applied"}},
            {"error": {"code": "unsafe_state", "reason": "unsafe_state", "message": "m"}},
        ]
    )
    host.reply = lambda request: {"id": request["id"], **next(responses)}
    stdin = lines_of(*({"method": "transport.set", "params": {"playing": False}},) * 3)
    out = await cli.execute(["batch"], stdin=lambda: stdin)
    assert out.code == 1
    assert out.stdout == '{"outcome":"applied"}\n'
    assert json.loads(out.stderr)["message"].startswith("line 2: ")
    assert len(host.requests) == 2


@pytest.mark.parametrize(
    ("stdin", "fragment"),
    [
        ("not json\n", "line 1: not strict JSON"),
        ('{"params":{}}\n', "line 1: expected"),
        ('{"method":"status","params":[]}\n', "line 1: expected"),
        ('{"method":"status","extra":1}\n', "line 1: expected"),
        ("\n \n", "no calls"),
        ('{"method":"nope"}\n', "line 1: Unknown method"),
        ('{"method":"status"}\n{"method":"transport.set","params":{"tempoBpm":19}}\n', "line 2: "),
    ],
)
async def test_batch_validates_every_line_before_sending_any(host, monkeypatch, stdin, fragment):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    out = await cli.execute(["batch"], stdin=lambda: stdin)
    assert (out.code, out.stdout) == (2, "")
    assert fragment in json.loads(out.stderr)["message"]
    assert host.connections == 0


async def test_batch_reads_a_file_and_has_help(host, monkeypatch, tmp_path):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"outcome": "sent"})
    path = tmp_path / "calls.jsonl"
    path.write_text(lines_of({"method": "transport.set", "params": {"playing": False}}))
    assert (await cli.execute(["batch", f"@{path}"])).code == 0
    assert (await cli.execute(["batch", "a", "b"])).code == 2
    for argv in (["batch", "--help"], ["help", "batch"]):
        assert "JSON Lines" in (await cli.execute(argv)).stdout


def test_interrupt_of_a_write_prints_an_unknown_result_line_and_keeps_finished_output():
    progress = cli.Progress(inflight="tracks.set", completed=2, stdout=['{"outcome":"applied"}\n'])
    out = cli.interrupted(progress)
    first, second = (json.loads(line) for line in out.stdout.splitlines())
    assert (out.code, out.stderr) == (130, "")
    assert first == {"outcome": "applied"}
    assert (second["outcome"], second["reason"], second["method"]) == ("unknown", "cancelled", "tracks.set")
    assert "in flight" in second["message"]
    assert "inspect" in second["message"]
    assert "2 call(s) finished" in second["message"]


def test_interrupt_with_nothing_in_flight_is_a_cancelled_refusal_that_changed_nothing():
    out = cli.interrupted(cli.Progress())
    error = json.loads(out.stderr)
    assert (out.code, out.stdout) == (130, "")
    assert (error["code"], error["reason"]) == ("refused", "cancelled")
    assert "nothing changed" in error["message"]


def test_main_turns_ctrl_c_into_one_stderr_line_and_130(capsys, monkeypatch):
    def interrupt(coro):
        coro.close()
        raise KeyboardInterrupt

    monkeypatch.setattr(cli.asyncio, "run", interrupt)
    assert main(["status"]) == 130
    captured = capsys.readouterr()
    assert captured.out == ""
    assert len(captured.err.splitlines()) == 1


async def test_batch_streams_each_result_before_the_next_call_returns(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    seen: list[str] = []
    first_seen_at_second_call = []

    def reply(request):
        if seen:
            first_seen_at_second_call.append(list(seen))
        return {"id": request["id"], "result": {"outcome": "applied"}}

    host.reply = reply
    stdin = lines_of(*({"method": "transport.set", "params": {"playing": False}},) * 2)
    progress = cli.Progress(emit=seen.append)
    out = await cli.execute(["batch"], stdin=lambda: stdin, progress=progress)
    assert first_seen_at_second_call == [['{"outcome":"applied"}\n']]
    assert seen == ['{"outcome":"applied"}\n'] * 2
    assert (out.stdout, out.code) == ("", 0)


async def test_batch_stops_dispatching_when_stdout_closes(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    reply_with(host, {"outcome": "applied"})
    delivered: list[str] = []

    def reader_closes_after_the_first_line(line):
        if delivered:
            raise BrokenPipeError
        delivered.append(line)

    stdin = lines_of(*({"method": "transport.set", "params": {"playing": False}},) * 3)
    out = await cli.execute(
        ["batch"], stdin=lambda: stdin, progress=cli.Progress(emit=reader_closes_after_the_first_line)
    )
    assert (out.code, out.stdout) == (141, "")
    assert len(delivered) == 1
    assert len(host.requests) == 2
    message = json.loads(out.stderr)["message"]
    assert "no further call was sent" in message
    assert "line 2" in message
    assert "outcome is unknown" in message


@pytest.mark.parametrize(
    ("argv", "fragment"),
    [
        (["--verison"], "did you mean --version?"),
        (["-x"], "Unknown option -x"),
        (["tracks list"], "bitwig-agent tracks list"),
        (["{}"], "Unknown command"),
    ],
)
async def test_a_first_token_that_is_not_a_word_is_named(argv, fragment):
    out = await cli.execute(argv)
    assert (out.code, out.stdout) == (2, "")
    assert fragment in json.loads(out.stderr)["message"]
    assert argv[0] in json.loads(out.stderr)["message"]


@pytest.mark.parametrize("argv", [["tracks", "sett", "--help"], ["help", "tracks", "sett"], ["tracks", "sett"]])
async def test_an_unknown_verb_is_unknown_method_even_when_asking_for_help(argv):
    out = await cli.execute(argv)
    assert (out.code, out.stdout) == (2, "")
    error = json.loads(out.stderr)
    assert error["code"] == "unknown_method"
    assert "did you mean tracks.set?" in error["message"]


async def test_batch_takes_only_stdin_or_a_file(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    out = await cli.execute(["batch", "calls.jsonl"])
    assert out.code == 2
    assert "batch takes - (stdin, the default) or @file" in json.loads(out.stderr)["message"]


async def test_a_bare_boolean_flag_does_not_take_a_separate_value():
    out = await cli.execute(["tracks", "set", f"--trackId={TRACK}", "--mute", "false"])
    assert out.code == 2
    assert "write --mute=false" in json.loads(out.stderr)["message"]


async def test_help_of_record_reads_mentions_the_row_flags():
    assert "Rows: --format=jsonl|csv|tsv" in (await cli.execute(["tracks", "list", "--help"])).stdout
    assert "Rows:" not in (await cli.execute(["status", "--help"])).stdout


@pytest.mark.parametrize(
    ("argv", "hint"),
    [
        (["tracks", "list", "--forma=csv"], "did you mean --format?"),
        (["tracks", "list", "--timeoutM=5"], "--timeoutMs"),
    ],
)
async def test_unknown_flags_suggest_the_cli_flags_too(argv, hint):
    message = json.loads((await cli.execute(argv)).stderr)["message"]
    assert hint in message
    assert "Also accepted: --timeoutMs, --format, --fields" in message


@pytest.mark.parametrize("flag", ["--format=csv", "--fields=name"])
async def test_a_repeated_row_flag_is_an_error(flag):
    out = await cli.execute(["actions", "list", flag, flag])
    assert out.code == 2
    assert "Duplicate argument" in json.loads(out.stderr)["message"]


async def test_partial_uses_the_window_key_when_it_exists_and_else_the_top_level_key(host, monkeypatch):
    partial_at_top = {"modulators": [{"modulatorId": "m"}], "window": {"total": 3}, "complete": False}
    out = await stdout_of(
        [*["modulators", "list"], *flags("modulators.list", {"parent": TARGET})], host, monkeypatch, partial_at_top
    )
    assert out.code == 0
    assert out.stderr == ""
    argv = [*["modulators", "list"], *flags("modulators.list", {"parent": TARGET}), "--format=jsonl"]
    assert json.loads((await stdout_of(argv, host, monkeypatch, partial_at_top)).stderr)["warning"] == "partial"
    window_wins = {"modulators": [], "window": {"complete": True}, "complete": False}
    assert (await stdout_of(argv, host, monkeypatch, window_wins)).stderr == ""


async def test_python_dash_m_bitwig_agent_is_the_cli():
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-m",
        "bitwig_agent",
        "--version",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    assert stdout.decode().startswith("bitwig-agent ")


async def test_the_generated_mcp_config_starts_the_mcp_server_not_the_cli():
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        "-m",
        "bitwig_agent.install",
        "--mcp-config",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await process.communicate()
    assert process.returncode == 0, stderr.decode()
    server = json.loads(stdout)["mcpServers"]["bitwig-agent"]
    assert server["args"] == ["-m", "bitwig_agent.frontends.mcp"]


async def test_a_method_without_fields_names_no_empty_accepted_list():
    message = json.loads((await cli.execute(["tracks", "list", "--formt=csv"])).stderr)["message"]
    assert "Accepted:" not in message
    assert ".." not in message
    assert message.endswith("Also accepted: --timeoutMs, --format, --fields (did you mean --format?)")
