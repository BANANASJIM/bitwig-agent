import pytest
from fakes import INVALID_NAMES, assert_rejected_before_tcp

from bitwig_agent.core import views
from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.grid import TARGET

pytestmark = pytest.mark.anyio

TRACK = "12345678-1234-1234-1234-123456789abc"


@pytest.mark.parametrize("field", ["expectedX", "expectedY", "x", "y"])
async def test_grid_move_mcp_rejects_float_despite_json_schema_integer_semantics(core, host, field):
    response = await core.call_tool(
        "grid.modules.move", {**TARGET, "moduleId": TRACK, "expectedX": 0, "expectedY": 0, "x": 4, "y": 2, field: 1.0}
    )
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


def test_hidden_blocks_keep_their_required_nullable_schema_contract(all_methods):
    tools = {view.name: view for view in views.tools()}
    tool = tools["grid.connections.set"]
    assert tool.annotations.read_only is False
    schema = tool.input_schema
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"target"}
    assert schema["oneOf"] == [
        {"required": ["expectedSource", "source", "targetModuleId", "targetPortKey"]},
        {"required": ["connections"]},
    ]
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
    assert insertion.annotations.read_only is False
    schema = insertion.input_schema
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"target"}
    assert {tuple(sorted(branch["required"])) for branch in schema["oneOf"]} == {("x", "y"), ("modules",)}
    assert {"typeId", "typeName", "modules"} <= set(schema["properties"])
    assert schema["properties"]["modules"]["minItems"] == 1
    assert "maxItems" not in schema["properties"]["modules"]
    for field in ("x", "y"):
        coordinate = schema["properties"][field]
        assert coordinate["type"] == "integer"
        assert coordinate["minimum"] == -(2**31)
        assert coordinate["maximum"] == 2**31 - 1
        assert "default" not in coordinate
    rename = tools["grid.modules.set"]
    assert rename.annotations.read_only is False
    schema = rename.input_schema
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {
        "target",
        "moduleId",
        "expectedCustomName",
        "customName",
    }
    for field in ("expectedCustomName", "customName"):
        name = schema["properties"][field]
        assert name["type"] == "string"
        assert name["maxLength"] == 1024
        assert name.get("minLength", 0) == 0
        assert "default" not in name


@pytest.mark.parametrize("field", ["x", "y"])
@pytest.mark.parametrize("value", [None, True, 1.0, "1", -(2**31) - 1, 2**31])
async def test_grid_insert_mcp_rejects_non_int32_before_tcp(core, host, field, value):
    response = await core.call_tool(
        "grid.modules.insert",
        {
            **TARGET,
            "typeId": TRACK,
            "x": 0,
            "y": 0,
            field: value,
        },
    )
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_grid_insert_mcp_preserves_loader_unknown_without_retry(core, host):
    unknown = {
        "outcome": "unknown",
        "reason": "deadline",
        "message": "Inspect actual graph",
        "details": {"loaderDispatched": True, "placementDispatched": False},
    }
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    params = {**TARGET, "typeId": TRACK, "x": -(2**31), "y": 2**31 - 1}
    response = await core.call_tool("grid.modules.insert", params)
    assert response.is_error
    assert response.structured_content == unknown
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("field", ["expectedSource", "source"])
@pytest.mark.parametrize(
    "value",
    [
        "null",
        False,
        [],
        {},
        {"moduleId": TRACK},
        {"moduleId": "1-1-1-1-1", "portKey": ""},
        {"moduleId": TRACK, "portKey": None},
        {"moduleId": TRACK, "portKey": "", "extra": True},
        {"moduleId": TRACK, "portKey": "𝄞" * 513},
    ],
)
async def test_connection_mcp_rejects_nested_endpoint_errors_before_tcp(core, host, field, value):
    response = await core.call_tool(
        "grid.connections.set",
        {
            **TARGET,
            "targetModuleId": TRACK,
            "targetPortKey": "",
            "expectedSource": None,
            "source": None,
            field: value,
        },
    )
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


@pytest.mark.parametrize("field", ["expectedSource", "source"])
async def test_connection_mcp_never_infers_missing_cas_null(core, host, field):
    params = {**TARGET, "targetModuleId": TRACK, "targetPortKey": "", "expectedSource": None, "source": None}
    del params[field]
    response = await core.call_tool("grid.connections.set", params)
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


@pytest.mark.parametrize("field", ["expectedCustomName", "customName"])
@pytest.mark.parametrize("value", INVALID_NAMES)
async def test_grid_rename_mcp_rejects_non_string_and_utf16_overflow_before_tcp(core, host, field, value):
    response = await core.call_tool(
        "grid.modules.set",
        {
            **TARGET,
            "moduleId": TRACK,
            "expectedCustomName": "",
            "customName": "",
            field: value,
        },
    )
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


@pytest.mark.parametrize("field", ["expectedCustomName", "customName"])
async def test_grid_rename_mcp_missing_name_is_not_defaulted_to_reset(core, host, field):
    params = {**TARGET, "moduleId": TRACK, "expectedCustomName": "", "customName": ""}
    del params[field]
    response = await core.call_tool("grid.modules.set", params)
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_grid_rename_mcp_preserves_unknown_raw_reset_outcome_without_retry(core, host):
    params = {**TARGET, "moduleId": TRACK, "expectedCustomName": " \t\u03b1𝄞 ", "customName": ""}
    unknown = {
        "outcome": "unknown",
        "reason": "stale_context",
        "message": "Inspect actual raw name",
        "details": {"mutationDispatched": True, "rollbackVerified": False},
    }
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    response = await core.call_tool("grid.modules.set", params)
    assert response.is_error
    assert response.structured_content == unknown
    assert host.requests[0]["params"] == params
    assert len(host.requests) == host.connections == 1


async def test_absent_and_explicit_null_connection_fields_stay_distinct(core, host):
    params = {
        **TARGET,
        "targetModuleId": TRACK,
        "targetPortKey": "",
        "expectedSource": {"moduleId": TRACK, "portKey": ""},
        "source": None,
    }
    response = await core.call_tool("grid.connections.set", params)
    assert not response.is_error
    assert host.requests[-1]["params"] == params


GRID_WRITE = {"moduleId": TRACK, "parameterId": "", "expectedKind": "boolean", "expectedValue": False, "value": True}


async def test_grid_host_unknown_error_survives_the_core_without_retry(core, host):
    unknown = {
        "outcome": "unknown",
        "reason": "deadline",
        "message": "Observe before another write",
        "details": {"dispatched": True},
    }
    host.reply = lambda request: {"id": request["id"], "result": unknown}
    response = await core.call_tool("grid.parameters.set", {**TARGET, **GRID_WRITE})
    assert response.is_error is True
    assert response.structured_content == unknown
    assert host.requests[0]["timeoutMs"] == TOOLS["grid.parameters.set"].default_timeout_ms
    assert host.connections == 1
    assert len(host.requests) == 1
    assert host.requests[0]["params"]["expectedValue"] is False
    assert host.requests[0]["params"]["value"] is True


async def test_grid_parameter_utf16_limit_is_checked_beyond_json_schema(core, host):
    response = await core.call_tool("grid.parameters.set", {**TARGET, **GRID_WRITE, "parameterId": "𝄞" * 513})
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)


async def test_grid_integer_float_token_is_rejected_despite_json_schema_integer_semantics(core, host):
    write = {**GRID_WRITE, "expectedKind": "integer", "expectedValue": 0, "value": 1.0}
    response = await core.call_tool("grid.parameters.set", {**TARGET, **write})
    assert response.is_error
    assert_rejected_before_tcp(response.structured_content, host)
