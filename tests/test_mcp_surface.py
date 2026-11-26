import json

import pytest
from mcp.shared.exceptions import MCPError
from mcp.types import CallToolRequestParams

from bitwig_agent.core import views
from bitwig_agent.core.invoke import Error, invoke
from bitwig_agent.frontends.cli import execute
from bitwig_agent.frontends.mcp import call_tool, create_server, list_tools
from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.annotations import VERBS, resolve, verb
from bitwig_agent.protocol.base import TRACK
from bitwig_agent.protocol.limits import DESCRIPTION_LIMIT
from bitwig_agent.protocol.topics import LOOP

pytestmark = pytest.mark.anyio

HIDDEN = {
    "grid.modules.insert",
    "grid.modules.rename",
    "grid.modules.move",
    "grid.modules.delete",
    "grid.connections.set",
    "grid.voicing.set",
}


def test_only_grid_write_parts_that_grid_apply_covers_are_hidden(real_surface):
    assert {name for name, spec in TOOLS.items() if not spec.expose} == HIDDEN


def test_every_method_has_annotations_and_reads_agree_with_the_table(real_surface):
    for name, spec in TOOLS.items():
        assert spec.annotations is not None or verb(name) in VERBS, f"{name} is a custom verb without annotations"
        assert resolve(name, spec.annotations).read_only is spec.read_only, name


def test_explicit_annotations_are_only_for_custom_verbs(real_surface):
    for name, spec in TOOLS.items():
        assert spec.annotations is None or verb(name) not in VERBS, f"{name} repeats what its verb derives"


def test_advertised_descriptions_fit_the_limit_in_final_form(real_surface):
    for view in views.tools():
        assert len(view.description) <= DESCRIPTION_LIMIT, view.name


def test_long_descriptions_keep_their_full_text_in_help(real_surface):
    long = [name for name, spec in TOOLS.items() if spec.doc and spec.expose]
    assert "grid.apply" in long
    for name in long:
        assert views.help(name)["description"] == TOOLS[name].doc
        assert len(TOOLS[name].doc) > len(TOOLS[name].description)


def test_help_lists_topics_and_exposed_tools_and_rejects_the_unknown(real_surface):
    listing = views.help()
    assert {"loop", "outcomes", "recovery"} <= set(listing["topics"])
    assert set(listing["tools"]) == {name for name, spec in TOOLS.items() if spec.expose}
    with pytest.raises(ValueError, match="Unknown topic or tool"):
        views.help("grid.modules.insert")


def test_instructions_carry_the_loop_topic(real_surface):
    assert views.instructions().startswith(LOOP)


async def test_invoke_refuses_unexposed_methods_before_validation_or_tcp(real_surface, host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    envelope = await invoke("grid.modules.insert", {})
    assert isinstance(envelope, Error)
    assert envelope.error["code"] == "unknown_method"
    assert host.connections == 0


async def test_surface_lists_exposed_tools_with_help_and_no_timeout(real_mcp_session):
    listed = (await real_mcp_session.list_tools()).tools
    assert [tool.name for tool in listed] == [*[n for n, s in TOOLS.items() if s.expose], "help"]
    for tool in listed:
        assert "timeoutMs" not in tool.input_schema["properties"]
        assert "timeoutMs" not in tool.input_schema.get("required", [])
        assert len(tool.description) <= DESCRIPTION_LIMIT
    by_name = {tool.name: tool for tool in listed}
    assert by_name["tracks.list"].annotations.read_only_hint is True
    assert by_name["tracks.delete"].annotations.destructive_hint is True
    assert by_name["tracks.delete"].annotations.idempotent_hint is True
    assert by_name["tracks.create"].annotations.destructive_hint is False
    assert by_name["tracks.set"].annotations.idempotent_hint is True
    assert by_name["actions.invoke"].annotations.open_world_hint is True
    assert by_name["tracks.list"].annotations.open_world_hint is False


async def test_surface_hides_unexposed_methods_as_protocol_errors(real_mcp_session, host):
    for name in HIDDEN:
        with pytest.raises(MCPError, match="Unknown method"):
            await real_mcp_session.call_tool(name, {})
    assert host.connections == 0


async def test_result_object_is_the_structured_content_without_wrapper(real_mcp_session, host):
    result = {"outcome": "applied", "tracks": []}
    host.reply = lambda request: {"id": request["id"], "result": result}
    response = await real_mcp_session.call_tool("tracks.list", {})
    assert response.is_error is False
    assert response.structured_content == result
    assert json.loads(response.content[0].text) == result


async def test_error_object_is_the_structured_content_and_flags_is_error(real_mcp_session, host):
    error = {"code": "stale", "reason": "stale_target", "message": "changed", "next": "tracks.list"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await real_mcp_session.call_tool("tracks.list", {})
    assert response.is_error is True
    assert response.structured_content == error


async def test_help_tool_serves_topics_and_method_docs(real_mcp_session):
    topics = await real_mcp_session.call_tool("help", {})
    assert topics.is_error is False
    assert "loop" in topics.structured_content["topics"]
    guide = await real_mcp_session.call_tool("help", {"topic": "outcomes"})
    assert "unknown" in guide.structured_content["text"]
    method = await real_mcp_session.call_tool("help", {"topic": "grid.apply"})
    assert method.structured_content["description"] == TOOLS["grid.apply"].doc
    missing = await real_mcp_session.call_tool("help", {"topic": "grid.modules.insert"})
    assert missing.is_error is True
    assert missing.structured_content["code"] == "invalid_params"


@pytest.mark.parametrize("arguments", [["grid.modules.insert"], ["describe", "grid.modules.insert"]])
async def test_cli_refuses_unexposed_methods_too(real_surface, host, monkeypatch, arguments):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(arguments)
    assert code == 2
    assert payload["error"]["code"] in {"unknown_method", "invalid_params"}
    assert host.connections == 0


async def test_handlers_run_in_process_for_help_unexposed_and_methods(real_surface, host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: {"id": request["id"], "result": {"outcome": "not_applied", "reason": "busy"}}
    listed = await list_tools(None, None)
    assert listed.tools[-1].name == "help"
    assert (await call_tool(None, CallToolRequestParams(name="help"))).is_error is False
    bad_topic = await call_tool(None, CallToolRequestParams(name="help", arguments={"topic": "nope"}))
    assert bad_topic.is_error is True
    with pytest.raises(MCPError):
        await call_tool(None, CallToolRequestParams(name="grid.voicing.set"))
    flagged = await call_tool(None, CallToolRequestParams(name="tracks.select", arguments={"trackId": TRACK}))
    assert flagged.is_error is True
    assert flagged.structured_content["outcome"] == "not_applied"
    refused = await call_tool(None, CallToolRequestParams(name="tracks.select", arguments={}))
    assert refused.is_error is True
    assert refused.structured_content["code"] == "invalid_params"
    assert create_server().name == "Bitwig Agent"


def test_method_description_refuses_unexposed_methods(real_surface):
    with pytest.raises(ValueError, match="Unknown method"):
        views.method("grid.voicing.set")
    assert views.method("grid.apply")["description"] == TOOLS["grid.apply"].doc
