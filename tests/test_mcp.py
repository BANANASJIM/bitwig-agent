import pytest
from fakes import LAYER_UNKNOWN_RESULT, assert_rejected_before_tcp, string_panel_result
from mcp.shared.exceptions import MCPError

from bitwig_agent.protocol import CLIENT_SIDE, TOOLS
from bitwig_agent.protocol.base import REASONS
from bitwig_agent.protocol.clips import NOTE_WINDOW
from bitwig_agent.protocol.launcher import SLOT_REF

pytestmark = pytest.mark.anyio

TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 2, "expectedDeviceName": "Polymer"}
SET = {**TARGET, "contextToken": "context-1", "parameterId": "cutoff", "value": 0.75}


async def test_real_stdio_preserves_partial_domain_results(mcp_session, host):
    for method, spec in TOOLS.items():
        if method in CLIENT_SIDE or not spec.expose:
            continue
        params = spec.examples[0]
        # All domains must preserve a partial result instead of claiming that
        # dispatch, a selected window, or a subset of edits proves completion.
        result = {
            "outcome": "unknown",
            "reason": "not_observed",
            "verification": {"verified": False},
            "window": {"complete": False},
            "edits": [{"index": 0, "outcome": "unknown"}],
        }
        host.reply = lambda request, result=result: {"id": request["id"], "result": result}
        response = await mcp_session.call_tool(method, params)
        assert response.is_error is True, "an unknown outcome is flagged, never hidden"
        got = response.structured_content
        if method == "status":  # client adds a mismatch note when the host reports no extension version
            got.pop("extensionMismatch")
        assert got == result
        assert host.requests[-1]["params"] == params
    assert host.connections == sum(1 for method, spec in TOOLS.items() if spec.expose and method not in CLIENT_SIDE)


@pytest.mark.parametrize(
    "reply",
    [
        lambda request: {"id": request["id"], "error": {"code": 1, "message": "malformed code"}},
        lambda request: ('{"id":"' + request["id"] + '","result":{"observedValue":1e9999}}\n').encode(),
    ],
)
async def test_malformed_host_result_is_structured_unknown(mcp_session, host, reply):
    host.reply = reply
    response = await mcp_session.call_tool("parameters.set", SET)
    assert response.is_error is True
    assert response.structured_content["outcome"] == "unknown"
    assert response.structured_content["reason"] == "transport"
    assert host.connections == 1


@pytest.mark.parametrize(
    ("outcome", "is_error"),
    [("applied", False), ("already_matched", False), ("sent", False), ("not_applied", True), ("unknown", True)],
)
async def test_is_error_follows_the_outcome(mcp_session, host, outcome, is_error):
    host.reply = lambda request: {"id": request["id"], "result": {"outcome": outcome}}
    response = await mcp_session.call_tool("parameters.set", SET)
    assert response.is_error is is_error
    host.reply = lambda request: {"id": request["id"], "result": {"tracks": []}}
    assert (await mcp_session.call_tool("tracks.list", {})).is_error is False, "reads carry no outcome"


@pytest.mark.parametrize(
    "override",
    [
        {"value": -0.01},
        {"value": 1.01},
        {"value": "0.5"},
        {"value": True},
        {"deviceIndex": 16},
        {"deviceIndex": True},
        {"trackId": "not-a-uuid"},
        {"contextToken": ""},
        {"parameterId": ""},
        {"expectedDeviceName": ""},
        {"unexpected": 1},
        {"contextToken": "x" * 4097},
    ],
)
async def test_invalid_arguments_never_reach_tcp(mcp_session, host, override):
    response = await mcp_session.call_tool("parameters.set", {**SET, **override})
    assert response.is_error is True
    assert_rejected_before_tcp(response.structured_content, host)


async def test_unknown_tool_does_not_open_tcp(mcp_session, host):
    with pytest.raises(MCPError, match=r"Unknown method: project\.delete"):
        await mcp_session.call_tool("project.delete", {})
    assert host.connections == 0


@pytest.mark.parametrize(
    ("method", "params"),
    [
        ("actions.invoke", {}),
        ("actions.invoke", {"actionId": ""}),
        ("actions.invoke", {"actionId": 123}),
        ("actions.invoke", {"actionId": True}),
        ("actions.invoke", {"actionId": "x" * 4097}),
        ("actions.invoke", {"actionId": "save", "extra": True}),
        ("actions.list", {"extra": True}),
    ],
)
async def test_invalid_action_arguments_never_reach_tcp(mcp_session, host, method, params):
    response = await mcp_session.call_tool(method, params)
    assert response.is_error is True
    assert host.connections == 0


async def test_unknown_action_id_preserves_host_rejection(mcp_session, host):
    error = {"code": REASONS["unknown_name"].value, "reason": "unknown_name", "message": "Unknown ID"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("actions.invoke", {"actionId": "missing"})
    assert response.structured_content == error
    assert response.is_error is True
    assert host.connections == 1


async def test_absent_fields_and_explicit_nullable_fields_remain_distinct_over_mcp(mcp_session, host):
    response = await mcp_session.call_tool("tracks.set", {"trackId": TRACK, "mute": True})
    assert not response.is_error
    assert host.requests[-1]["params"] == {"trackId": TRACK, "mute": True}
    response = await mcp_session.call_tool(
        "notes.edit", {**NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60}]}
    )
    assert not response.is_error
    assert host.requests[-1]["params"]["edits"] == [{"op": "clear", "beat": 0, "pitch": 60}]


@pytest.mark.parametrize(
    ("method", "params"),
    [
        ("transport.set", {}),
        ("transport.set", {"playing": "false"}),
        ("transport.set", {"tempoBpm": 667}),
        ("transport.set", {"playing": None}),
        ("tracks.set", {"trackId": TRACK}),
        ("tracks.set", {"trackId": TRACK, "volumeNormalized": -1}),
        ("tracks.create", {"type": "audio", "position": True, "name": "x"}),
        ("devices.set", {**TARGET, "enabled": 1}),
        (
            "devices.remote.set",
            {
                **TARGET,
                "contextToken": "c",
                "expectedPageName": "Main",
                "parameterIndex": 8,
                "expectedParameterName": "Level",
                "normalizedValue": 0.5,
            },
        ),
        ("clips.insert", {"to": SLOT_REF, "lengthBeats": 4, "name": "x"}),
        ("clips.import", {"to": SLOT_REF, "path": "file:///tmp/audio.wav"}),
        (
            "clips.import",
            {"to": {**SLOT_REF, "scene": {"kind": "scene", "index": -1, "name": None}}, "path": "/tmp/audio.wav"},
        ),
        ("clips.import", {"to": SLOT_REF, "path": "/tmp/audio.wav", "overwrite": True}),
        ("notes.read", {**NOTE_WINDOW, "pitchStart": 127, "pitchCount": 2}),
        ("notes.edit", {**NOTE_WINDOW, "edits": [{"op": "set", "beat": 0, "pitch": 60}]}),
        ("notes.edit", {**NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60, "velocity": None}]}),
    ],
)
async def test_invalid_domain_edits_are_not_dispatched(mcp_session, host, method, params):
    response = await mcp_session.call_tool(method, params)
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_mcp_discovery_publishes_strict_layer_contracts(mcp_session):
    response = await mcp_session.list_tools()
    tools = {tool.name: tool for tool in response.tools}
    for method in ("devices.layers.list", "parameters.layer.list", "parameters.layer.set"):
        tool = tools[method]
        assert tool.annotations.read_only_hint is (method != "parameters.layer.set")
        schema = tool.input_schema
        assert schema["additionalProperties"] is False
        assert set(schema["required"]) == set(TOOLS[method].examples[0])
        reference = schema["properties"]["parent"]["$ref"]
        parent = schema["$defs"][reference.rsplit("/", 1)[1]]
        assert parent["additionalProperties"] is False
        assert set(parent["properties"]) == set(parent["required"]) == {"trackId", "deviceIndex", "expectedDeviceName"}
        assert parent["properties"]["deviceIndex"]["maximum"] == 15
        if method != "devices.layers.list":
            assert schema["properties"]["childDeviceIndex"]["maximum"] == 1


@pytest.mark.parametrize("method", ["devices.layers.list", "parameters.layer.list", "parameters.layer.set"])
@pytest.mark.parametrize(
    "parent",
    [
        None,
        {},
        {**TARGET, "deviceIndex": 1.0},
        {**TARGET, "deviceIndex": True},
        {**TARGET, "layerId": TRACK},
        {**TARGET, "timeoutMs": 250},
        {**TARGET, "padIndex": 0},
        {**TARGET, "slotIndex": 0},
    ],
)
async def test_invalid_layer_parent_never_reaches_tcp(mcp_session, host, method, parent):
    response = await mcp_session.call_tool(method, {**TOOLS[method].examples[0], "parent": parent})
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


@pytest.mark.parametrize(
    "changes",
    [
        {"childDeviceIndex": 2},
        {"childDeviceIndex": 1.0},
        {"childDeviceIndex": True},
        {"layerId": "1-1-1-1-1"},
        {"discoveryToken": None},
        {"expectedChildDeviceName": ""},
        {"contextToken": ""},
        {"parameterId": ""},
        {"value": True},
        {"value": 1.01},
        {"layerIndex": 0},
        {"path": []},
    ],
)
async def test_invalid_layer_write_never_reaches_tcp(mcp_session, host, changes):
    response = await mcp_session.call_tool(
        "parameters.layer.set", {**TOOLS["parameters.layer.set"].examples[0], **changes}
    )
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_layer_mcp_preserves_unknown_without_retry(mcp_session, host):
    unknown = LAYER_UNKNOWN_RESULT
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    params = TOOLS["parameters.layer.set"].examples[0]
    response = await mcp_session.call_tool("parameters.layer.set", params)
    assert response.is_error
    assert response.structured_content == unknown
    assert host.requests[0]["params"] == params
    assert host.requests[0]["timeoutMs"] == TOOLS["parameters.layer.set"].default_timeout_ms
    assert len(host.requests) == host.connections == 1


async def test_modulator_mcp_publishes_generated_read_only_contract(mcp_session):
    response = await mcp_session.list_tools()
    tools = {tool.name: tool for tool in response.tools}
    for method in ("modulators.capabilities", "modulators.list", "modulators.parameters.get"):
        tool = tools[method]
        assert tool.annotations.read_only_hint is True
        assert tool.annotations.destructive_hint is False
        schema = tool.input_schema
        assert schema["additionalProperties"] is False
        if method != "modulators.capabilities":
            parent = schema["$defs"]["DeviceParent"]
            assert parent["additionalProperties"] is False
            assert set(parent["required"]) == {"trackId", "deviceIndex", "expectedDeviceName"}
        if method == "modulators.parameters.get":
            assert set(schema["required"]) == {"parent", "modulatorId", "parameterId"}
            assert schema["properties"]["parameterId"]["maxLength"] == 1024
            assert schema["properties"]["parameterId"].get("minLength", 0) == 0


@pytest.mark.parametrize(
    "changes",
    [
        {"parent": {**TARGET, "deviceIndex": True}},
        {"parent": {**TARGET, "deviceIndex": 1.0}},
        {"parent": {**TARGET, "layerId": TRACK}},
        {"modulatorId": "1-1-1-1-1"},
        {"modulatorId": None},
        {"parameterId": None},
        {"parameterId": "𝄞" * 513},
        {"value": ""},
    ],
)
async def test_modulator_mcp_rejects_invalid_args_before_tcp(mcp_session, host, changes):
    response = await mcp_session.call_tool(
        "modulators.parameters.get", {**TOOLS["modulators.parameters.get"].examples[0], **changes}
    )
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


@pytest.mark.parametrize("value", ["", " \tMacro \u03b1𝄞\n "])
async def test_modulator_mcp_preserves_raw_string_and_unavailable_metadata(mcp_session, host, value):
    params = {**TOOLS["modulators.parameters.get"].examples[0], "parameterId": ""}
    result = string_panel_result(params["modulatorId"], value)
    host.reply = lambda request: {"id": request["id"], "result": result}
    response = await mcp_session.call_tool("modulators.parameters.get", params)
    assert response.is_error is False
    assert response.structured_content == result
    assert host.requests[0]["params"]["parameterId"] == ""
    assert len(host.requests) == host.connections == 1


async def test_modulator_mcp_preserves_partial_inventory_without_inventing_contents(mcp_session, host):
    result = {
        "scope": "primary_modulators_on_root_device",
        "window": {"offset": 0, "limit": 1, "total": 2, "returned": 1},
        "modulators": [
            {
                "index": 0,
                "modulatorId": TRACK,
                "name": None,
                "customName": None,
                "contentsAvailable": False,
                "typeId": None,
                "parameters": None,
            }
        ],
    }
    host.reply = lambda request: {"id": request["id"], "result": result}
    response = await mcp_session.call_tool("modulators.list", {"parent": TARGET, "limit": 1})
    assert response.is_error is False
    assert response.structured_content == result
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize(
    "error_code",
    [
        "native_unavailable",
        "out_of_bounds",
        "unsupported_scope",
        "stale_context",
    ],
)
async def test_modulator_mcp_preserves_read_errors_without_retry(mcp_session, host, error_code):
    error = {"code": REASONS[error_code].value, "reason": error_code, "message": "Read rejected"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("modulators.parameters.get", TOOLS["modulators.parameters.get"].examples[0])
    assert response.is_error
    assert response.structured_content == error
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("offset", True),
        ("offset", 1.0),
        ("offset", None),
        ("limit", 1.0),
        ("limit", "1"),
        ("limit", 129),
    ],
)
async def test_modulator_mcp_pagination_rejects_coercion_before_tcp(mcp_session, host, field, value):
    response = await mcp_session.call_tool("modulators.list", {"parent": TARGET, field: value})
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)
