import pytest

from bitwig_agent.catalog import TOOLS, NOTE_WINDOW

pytestmark = pytest.mark.anyio

TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 2, "expectedDeviceName": "Polymer"}
SET = {**TARGET, "contextToken": "context-1", "parameterId": "cutoff", "value": 0.75}


async def test_real_stdio_preserves_partial_domain_results(mcp_session, host):
    for method, spec in TOOLS.items():
        params = spec.examples[0]
        # All domains must preserve a partial result instead of claiming that
        # dispatch, a selected window, or a subset of edits proves completion.
        result = {"status": "dispatched", "verification": {"verified": False},
                  "window": {"complete": False}, "edits": [{"index": 0, "outcome": "unknown"}]}
        host.reply = lambda request, result=result: {"id": request["id"], "result": result}
        response = await mcp_session.call_tool(method, params)
        assert response.isError is False
        assert response.structuredContent == {"result": result}
        assert host.requests[-1]["params"] == params
    assert host.connections == len(TOOLS)


async def test_host_unknown_error_survives_mcp(mcp_session, host):
    error = {"code": "deadline_exceeded", "message": "Observe before another write", "outcome": "unknown", "details": {"dispatched": True}}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("parameters.set", {**SET, "timeoutMs": 100})
    assert response.isError is True
    assert response.structuredContent == {"error": error}
    assert host.requests[0]["timeoutMs"] == 100
    assert host.connections == 1


@pytest.mark.parametrize("reply", [
    lambda request: {"id": request["id"], "error": {"code": "bad", "message": "missing outcome"}},
    lambda request: ('{"id":"' + request["id"] + '","result":{"observedValue":1e9999}}\n').encode(),
])
async def test_malformed_host_result_is_structured_unknown(mcp_session, host, reply):
    host.reply = reply
    response = await mcp_session.call_tool("parameters.set", SET)
    assert response.isError is True
    assert response.structuredContent["error"]["outcome"] == "unknown"
    assert response.structuredContent["error"]["code"] == "transport_error"
    assert host.connections == 1


@pytest.mark.parametrize("override", [
    {"value": -0.01}, {"value": 1.01}, {"value": "0.5"}, {"value": True},
    {"deviceIndex": 16}, {"deviceIndex": True}, {"trackId": "not-a-uuid"},
    {"contextToken": ""}, {"parameterId": ""}, {"expectedDeviceName": ""},
    {"timeoutMs": 99}, {"timeoutMs": 10001}, {"timeoutMs": True},
    {"unexpected": 1}, {"contextToken": "x" * 4097},
])
async def test_invalid_arguments_never_reach_tcp(mcp_session, host, override):
    response = await mcp_session.call_tool("parameters.set", {**SET, **override})
    assert response.isError is True
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_unknown_tool_does_not_open_tcp(mcp_session, host):
    response = await mcp_session.call_tool("project.delete", {})
    assert response.isError is True
    assert host.connections == 0



@pytest.mark.parametrize("method,params", [
    ("actions.invoke", {}), ("actions.invoke", {"actionId": ""}),
    ("actions.invoke", {"actionId": 123}), ("actions.invoke", {"actionId": True}),
    ("actions.invoke", {"actionId": "x" * 4097}),
    ("actions.invoke", {"actionId": "save", "extra": True}),
    ("actions.list", {"extra": True}),
])
async def test_invalid_action_arguments_never_reach_tcp(mcp_session, host, method, params):
    response = await mcp_session.call_tool(method, params)
    assert response.isError is True
    assert host.connections == 0


async def test_unknown_action_id_preserves_host_rejection(mcp_session, host):
    error = {"code": "unknown_action", "message": "Unknown ID", "outcome": "not_applied"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("actions.invoke", {"actionId": "missing"})
    assert response.structuredContent == {"error": error}
    assert response.isError is True
    assert host.connections == 1


async def test_optional_fields_remain_absent_over_mcp(mcp_session, host):
    response = await mcp_session.call_tool("tracks.set", {"trackId": TRACK, "mute": True})
    assert not response.isError
    assert host.requests[-1]["params"] == {"trackId": TRACK, "mute": True}
    response = await mcp_session.call_tool("notes.edit", {
        **NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60}]})
    assert not response.isError
    assert host.requests[-1]["params"]["edits"] == [{"op": "clear", "beat": 0, "pitch": 60}]


@pytest.mark.parametrize("method,params", [
    ("transport.set", {}), ("transport.set", {"playing": "false"}),
    ("transport.set", {"tempoBpm": 667}), ("transport.set", {"playing": None}),
    ("tracks.set", {"trackId": TRACK}),
    ("tracks.set", {"trackId": TRACK, "volumeNormalized": -1}),
    ("tracks.create", {"type": "audio", "position": True, "name": "x"}),
    ("devices.set", {**TARGET, "enabled": 1}),
    ("devices.remote.set", {**TARGET, "contextToken": "c", "expectedPageName": "Main",
                            "parameterIndex": 8, "expectedParameterName": "Level", "normalizedValue": 0.5}),
    ("launcher.create", {"trackId": TRACK, "slotIndex": 16, "lengthBeats": 4, "name": "x"}),
    ("launcher.import", {"trackId": TRACK, "slotIndex": 0, "path": "file:///tmp/audio.wav"}),
    ("launcher.import", {"trackId": TRACK, "slotIndex": 16, "path": "/tmp/audio.wav"}),
    ("launcher.import", {"trackId": TRACK, "slotIndex": 0, "path": "/tmp/audio.wav", "overwrite": True}),
    ("notes.read", {**NOTE_WINDOW, "pitchStart": 127, "pitchCount": 2}),
    ("notes.edit", {**NOTE_WINDOW, "edits": [{"op": "set", "beat": 0, "pitch": 60}]}),
    ("notes.edit", {**NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60, "velocity": None}]}),
])
async def test_invalid_domain_edits_are_not_dispatched(mcp_session, host, method, params):
    response = await mcp_session.call_tool(method, params)
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_grid_parameter_utf16_limit_is_checked_beyond_json_schema(mcp_session, host):
    response = await mcp_session.call_tool("grid.parameters.get", {
        **TARGET, "moduleId": TRACK, "parameterId": "𝄞" * 513})
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0
