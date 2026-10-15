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


async def test_grid_host_unknown_error_survives_mcp_without_retry(mcp_session, host):
    error = {"code": "deadline_exceeded", "message": "Observe before another write", "outcome": "unknown", "details": {"dispatched": True}}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("grid.parameters.set", {
        **TARGET, "moduleId": TRACK, "parameterId": "", "expectedKind": "boolean",
        "expectedValue": False, "value": True, "timeoutMs": 100})
    assert response.isError is True
    assert response.structuredContent == {"error": error}
    assert host.requests[0]["timeoutMs"] == 100
    assert host.connections == 1
    assert len(host.requests) == 1
    assert host.requests[0]["params"]["expectedValue"] is False
    assert host.requests[0]["params"]["value"] is True


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


async def test_absent_fields_and_explicit_nullable_fields_remain_distinct_over_mcp(mcp_session, host):
    response = await mcp_session.call_tool("tracks.set", {"trackId": TRACK, "mute": True})
    assert not response.isError
    assert host.requests[-1]["params"] == {"trackId": TRACK, "mute": True}
    response = await mcp_session.call_tool("notes.edit", {
        **NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60}]})
    assert not response.isError
    assert host.requests[-1]["params"]["edits"] == [{"op": "clear", "beat": 0, "pitch": 60}]
    params = {**TARGET, "targetModuleId": TRACK, "targetPortKey": "",
              "expectedSource": {"moduleId": TRACK, "portKey": ""}, "source": None}
    response = await mcp_session.call_tool("grid.connections.set", params)
    assert not response.isError
    assert host.requests[-1]["params"] == params


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


async def test_grid_mcp_rejects_integer_float_token_despite_json_schema_integer_semantics(mcp_session, host):
    response = await mcp_session.call_tool("grid.parameters.set", {
        **TARGET, "moduleId": TRACK, "parameterId": "", "expectedKind": "integer",
        "expectedValue": 0, "value": 1.0})
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


@pytest.mark.parametrize("field", ["expectedX", "expectedY", "x", "y"])
async def test_grid_move_mcp_rejects_float_despite_json_schema_integer_semantics(mcp_session, host, field):
    response = await mcp_session.call_tool("grid.modules.move", {
        **TARGET, "moduleId": TRACK, "expectedX": 0, "expectedY": 0, "x": 4, "y": 2,
        field: 1.0})
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_mcp_discovery_publishes_required_nullable_connection_contract(mcp_session):
    response = await mcp_session.list_tools()
    tools = {tool.name: tool for tool in response.tools}
    assert len(response.tools) == len(tools) == 41
    assert set(tools) == set(TOOLS)
    tool = tools["grid.connections.set"]
    assert tool.annotations.readOnlyHint is False
    schema = tool.inputSchema
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {
        "trackId", "deviceIndex", "expectedDeviceName",
        "targetModuleId", "targetPortKey", "expectedSource", "source",
    }
    for field in ("expectedSource", "source"):
        endpoint = schema["properties"][field]
        assert "default" not in endpoint
        alternatives = endpoint["anyOf"]
        assert {"type": "null"} in alternatives
        reference = next(branch["$ref"] for branch in alternatives if "$ref" in branch)
        nested = schema["$defs"][reference.rsplit("/", 1)[1]]
        assert nested["additionalProperties"] is False
        assert set(nested["required"]) == {"moduleId", "portKey"}
        assert nested["properties"]["portKey"]["type"] == "string"
        assert nested["properties"]["portKey"]["maxLength"] == 1024
    insertion = tools["grid.modules.insert"]
    assert insertion.annotations.readOnlyHint is False
    schema = insertion.inputSchema
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {
        "trackId", "deviceIndex", "expectedDeviceName", "typeId", "x", "y",
    }
    for field in ("x", "y"):
        coordinate = schema["properties"][field]
        assert coordinate["type"] == "integer"
        assert coordinate["minimum"] == -(2**31)
        assert coordinate["maximum"] == 2**31 - 1
        assert "default" not in coordinate


@pytest.mark.parametrize("field", ["x", "y"])
@pytest.mark.parametrize("value", [None, True, 1.0, "1", -(2**31) - 1, 2**31])
async def test_grid_insert_mcp_rejects_non_int32_before_tcp(mcp_session, host, field, value):
    response = await mcp_session.call_tool("grid.modules.insert", {
        **TARGET, "typeId": TRACK, "x": 0, "y": 0, field: value,
    })
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_grid_insert_mcp_preserves_loader_unknown_without_retry(mcp_session, host):
    error = {"code": "deadline_exceeded", "message": "Inspect actual graph",
             "outcome": "unknown",
             "details": {"loaderDispatched": True, "placementDispatched": False}}
    host.reply = lambda request: {"id": request["id"], "error": error}
    params = {**TARGET, "typeId": TRACK, "x": -(2**31), "y": 2**31 - 1}
    response = await mcp_session.call_tool("grid.modules.insert", {**params, "timeoutMs": 250})
    assert response.isError
    assert response.structuredContent == {"error": error}
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("field", ["expectedSource", "source"])
@pytest.mark.parametrize("value", [
    "null", False, [], {}, {"moduleId": TRACK},
    {"moduleId": "1-1-1-1-1", "portKey": ""},
    {"moduleId": TRACK, "portKey": None},
    {"moduleId": TRACK, "portKey": "", "extra": True},
    {"moduleId": TRACK, "portKey": "𝄞" * 513},
])
async def test_connection_mcp_rejects_nested_endpoint_errors_before_tcp(mcp_session, host, field, value):
    response = await mcp_session.call_tool("grid.connections.set", {
        **TARGET, "targetModuleId": TRACK, "targetPortKey": "",
        "expectedSource": None, "source": None, field: value,
    })
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


@pytest.mark.parametrize("field", ["expectedSource", "source"])
async def test_connection_mcp_never_infers_missing_cas_null(mcp_session, host, field):
    params = {**TARGET, "targetModuleId": TRACK, "targetPortKey": "",
              "expectedSource": None, "source": None}
    del params[field]
    response = await mcp_session.call_tool("grid.connections.set", params)
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_mcp_discovery_publishes_strict_layer_contracts(mcp_session):
    response = await mcp_session.list_tools()
    tools = {tool.name: tool for tool in response.tools}
    for method in ("devices.layers.list", "parameters.layer.list", "parameters.layer.set"):
        tool = tools[method]
        assert tool.annotations.readOnlyHint is (method != "parameters.layer.set")
        schema = tool.inputSchema
        assert schema["additionalProperties"] is False
        assert set(schema["required"]) == set(TOOLS[method].examples[0])
        reference = schema["properties"]["parent"]["$ref"]
        parent = schema["$defs"][reference.rsplit("/", 1)[1]]
        assert parent["additionalProperties"] is False
        assert set(parent["properties"]) == set(parent["required"]) == {
            "trackId", "deviceIndex", "expectedDeviceName"}
        assert parent["properties"]["deviceIndex"]["maximum"] == 15
        if method != "devices.layers.list":
            assert schema["properties"]["childDeviceIndex"]["maximum"] == 1


@pytest.mark.parametrize("method", ["devices.layers.list", "parameters.layer.list", "parameters.layer.set"])
@pytest.mark.parametrize("parent", [
    None, {}, {**TARGET, "deviceIndex": 1.0}, {**TARGET, "deviceIndex": True},
    {**TARGET, "layerId": TRACK}, {**TARGET, "timeoutMs": 250},
    {**TARGET, "padIndex": 0}, {**TARGET, "slotIndex": 0},
])
async def test_invalid_layer_parent_never_reaches_tcp(mcp_session, host, method, parent):
    response = await mcp_session.call_tool(method, {**TOOLS[method].examples[0], "parent": parent})
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


@pytest.mark.parametrize("changes", [
    {"childDeviceIndex": 2}, {"childDeviceIndex": 1.0}, {"childDeviceIndex": True},
    {"layerId": "1-1-1-1-1"}, {"discoveryToken": None}, {"expectedChildDeviceName": ""},
    {"contextToken": ""}, {"parameterId": ""}, {"value": True},
    {"value": 1.01}, {"layerIndex": 0}, {"path": []},
])
async def test_invalid_layer_write_never_reaches_tcp(mcp_session, host, changes):
    response = await mcp_session.call_tool("parameters.layer.set", {
        **TOOLS["parameters.layer.set"].examples[0], **changes})
    assert response.isError
    assert response.structuredContent["error"]["code"] == "invalid_params"
    assert response.structuredContent["error"]["outcome"] == "not_dispatched"
    assert host.connections == 0


async def test_layer_mcp_preserves_unknown_without_retry(mcp_session, host):
    error = {"code": "deadline_exceeded", "message": "Inspect child before another write",
             "outcome": "unknown", "details": {"dispatched": True}}
    host.reply = lambda request: {"id": request["id"], "error": error}
    params = TOOLS["parameters.layer.set"].examples[0]
    response = await mcp_session.call_tool("parameters.layer.set", {**params, "timeoutMs": 250})
    assert response.isError
    assert response.structuredContent == {"error": error}
    assert host.requests[0]["params"] == params
    assert host.requests[0]["timeoutMs"] == 250
    assert len(host.requests) == host.connections == 1
