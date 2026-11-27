import pytest
from fakes import LAYER_UNKNOWN_RESULT, assert_rejected_before_tcp
from mcp.shared.exceptions import MCPError

from bitwig_agent.protocol import CLIENT_SIDE, TOOLS
from bitwig_agent.protocol.base import REASONS
from bitwig_agent.protocol.clips import NOTE_WINDOW
from bitwig_agent.protocol.launcher import SLOT_REF

pytestmark = pytest.mark.anyio

TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 2, "expectedDeviceName": "Polymer"}
TRACK_REF = {"kind": "track", "id": TRACK, "name": "Lead"}
DEVICE = {"kind": "device", "track": TRACK_REF, "index": 2, "name": "Polymer"}
MODULATOR = {"kind": "modulator", "device": DEVICE, "id": TRACK, "name": "LFO"}
SET = {"target": {"kind": "parameter", "owner": DEVICE, "id": "cutoff"}, "value": 0.75}


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
        {"value": {"label": "Bipolar"}},
        {"target": {"kind": "parameter", "owner": {**DEVICE, "index": 16}, "id": "cutoff"}},
        {"target": {"kind": "parameter", "owner": {**DEVICE, "index": True}, "id": "cutoff"}},
        {"target": {"kind": "parameter", "owner": DEVICE, "id": ""}},
        {"target": {"kind": "parameter", "owner": {**DEVICE, "name": None}, "id": "cutoff"}},
        {"unexpected": 1},
        {"contextToken": "context-1"},
        {"parameterId": "cutoff"},
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
    response = await mcp_session.call_tool("tracks.set", {"target": TRACK_REF, "mute": True})
    assert not response.is_error
    assert host.requests[-1]["params"] == {"target": TRACK_REF, "mute": True}
    response = await mcp_session.call_tool(
        "notes.set", {**NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60}]}
    )
    assert not response.is_error
    assert host.requests[-1]["params"]["edits"] == [{"op": "clear", "beat": 0, "pitch": 60}]


@pytest.mark.parametrize(
    ("method", "params"),
    [
        ("transport.set", {}),
        ("transport.set", {"playing": "false"}),
        ("transport.set", {"tempo": 667}),
        ("transport.set", {"playing": None}),
        ("tracks.set", {"target": TRACK_REF}),
        ("tracks.set", {"target": TRACK_REF, "volume": -1}),
        ("tracks.insert", {"type": "audio", "position": True, "name": "x"}),
        ("devices.set", {"target": DEVICE, "enabled": 1}),
        ("devices.set", {"target": DEVICE}),
        (
            "parameters.set",
            {
                "target": {
                    "kind": "parameter",
                    "owner": {"kind": "remotePage", "device": DEVICE, "index": 0, "name": "Main"},
                    "id": "8",
                    "name": "Level",
                },
                "value": 0.5,
            },
        ),
        ("clips.insert", {"to": SLOT_REF, "length": 4, "name": "x"}),
        ("clips.import", {"to": SLOT_REF, "path": "file:///tmp/audio.wav"}),
        (
            "clips.import",
            {"to": {**SLOT_REF, "scene": {"kind": "scene", "index": -1, "name": None}}, "path": "/tmp/audio.wav"},
        ),
        ("clips.import", {"to": SLOT_REF, "path": "/tmp/audio.wav", "overwrite": True}),
        ("notes.get", {**NOTE_WINDOW, "pitchStart": 127, "pitchCount": 2}),
        ("notes.set", {**NOTE_WINDOW, "edits": [{"op": "set", "beat": 0, "pitch": 60}]}),
        ("notes.set", {**NOTE_WINDOW, "edits": [{"op": "clear", "beat": 0, "pitch": 60, "velocity": None}]}),
    ],
)
async def test_invalid_domain_edits_are_not_dispatched(mcp_session, host, method, params):
    response = await mcp_session.call_tool(method, params)
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_mcp_discovery_publishes_the_device_ref_contracts(mcp_session):
    response = await mcp_session.list_tools()
    tools = {tool.name: tool for tool in response.tools}
    for method, field in (
        ("devices.list", "target"),
        ("chains.list", "target"),
        ("devices.insert", "to"),
        ("devices.get", "target"),
        ("devices.move", "target"),
        ("parameters.list", "target"),
        ("parameters.set", "target"),
        ("modulators.list", "target"),
        ("modulators.map", "source"),
    ):
        schema = tools[method].input_schema
        assert schema["additionalProperties"] is False
        assert field in schema["required"], method
        assert not {"trackId", "deviceIndex", "expectedDeviceName", "contextToken"} & set(schema["properties"])
    assert tools["devices.list"].annotations.read_only_hint is True
    assert tools["chains.list"].annotations.read_only_hint is True
    assert tools["parameters.set"].annotations.idempotent_hint is True
    assert tools["modulators.unmap"].annotations.destructive_hint is True
    assert tools["modulators.unmap"].annotations.idempotent_hint is True
    assert "modulators.catalog" not in tools
    assert "devices.layers.list" not in tools


@pytest.mark.parametrize("method", ["devices.list", "chains.list", "parameters.list", "modulators.list"])
@pytest.mark.parametrize(
    "parent",
    [None, {}, TARGET, {**DEVICE, "index": 1.0}, {**DEVICE, "index": True}, {**DEVICE, "layerId": TRACK}],
)
async def test_invalid_parent_never_reaches_tcp(mcp_session, host, method, parent):
    response = await mcp_session.call_tool(method, {"target": parent})
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_device_write_mcp_preserves_unknown_without_retry(mcp_session, host):
    unknown = LAYER_UNKNOWN_RESULT
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    params = TOOLS["parameters.set"].examples[1]
    response = await mcp_session.call_tool("parameters.set", params)
    assert response.is_error
    assert response.structured_content == unknown
    assert host.requests[0]["params"] == params
    assert host.requests[0]["timeoutMs"] == TOOLS["parameters.set"].default_timeout_ms
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize(
    "changes",
    [
        {"target": {**MODULATOR, "id": "1-1-1-1-1"}},
        {"target": None},
        {"target": DEVICE},
        {"value": ""},
        {"expect": {}},
    ],
)
async def test_modulator_control_write_rejects_invalid_args_before_tcp(mcp_session, host, changes):
    params = {"target": {"kind": "parameter", "owner": MODULATOR, "id": "RATE"}, "value": 0.25, **changes}
    response = await mcp_session.call_tool("parameters.set", params)
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_modulator_mcp_preserves_partial_inventory_without_inventing_contents(mcp_session, host):
    result = {
        "scope": "primary_modulators_on_root_device",
        "window": {"offset": 0, "limit": 1, "total": 2, "returned": 1},
        "modulators": [
            {"ref": MODULATOR, "name": None, "contentsAvailable": False, "typeId": None, "parameters": None}
        ],
    }
    host.reply = lambda request: {"id": request["id"], "result": result}
    response = await mcp_session.call_tool("modulators.list", {"target": DEVICE, "limit": 1})
    assert response.is_error is False
    assert response.structured_content == result
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("error_code", ["native_unavailable", "out_of_bounds", "unsupported_scope", "target_not_found"])
async def test_modulator_mcp_preserves_read_errors_without_retry(mcp_session, host, error_code):
    error = {"code": REASONS[error_code].value, "reason": error_code, "message": "Read rejected"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("modulators.get", {"target": MODULATOR})
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
    response = await mcp_session.call_tool("modulators.list", {"target": DEVICE, field: value})
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)
