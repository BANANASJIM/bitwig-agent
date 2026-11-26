import json
import re
from pathlib import Path
from typing import Any, cast

import pytest
from fakes import SPEC, FakeGrid
from fakes import execute_cli as execute
from mcp.shared.exceptions import MCPError
from mcp.types import CallToolRequestParams

from bitwig_agent.core import views
from bitwig_agent.core.invoke import Error, invoke
from bitwig_agent.frontends.mcp import call_tool, create_server, list_tools
from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.annotations import VERBS, resolve, verb
from bitwig_agent.protocol.base import TRACK
from bitwig_agent.protocol.limits import DESCRIPTION_LIMIT
from bitwig_agent.protocol.topics import LOOP, TOPICS

pytestmark = pytest.mark.anyio

NO_CONTEXT: Any = None  # the handlers ignore the request context
ROOT = Path(__file__).resolve().parents[1]
HIDDEN = {
    "grid.modules.insert",
    "grid.modules.rename",
    "grid.modules.move",
    "grid.modules.delete",
    "grid.connections.set",
    "grid.voicing.set",
}
EXPOSED = [name for name, spec in TOOLS.items() if spec.expose]


def test_only_grid_write_parts_that_grid_apply_covers_are_hidden():
    assert {name for name, spec in TOOLS.items() if not spec.expose} == HIDDEN


def test_every_method_has_annotations_and_derived_reads_agree_with_the_table():
    for name, spec in TOOLS.items():
        assert spec.annotations is not None or verb(name) in VERBS, f"{name} is a custom verb without annotations"
        annotations = resolve(name, spec.annotations)
        if spec.annotations is None:
            assert annotations.read_only is spec.read_only, name
        else:
            assert not (annotations.read_only and not spec.read_only), name


def test_explicit_annotations_are_only_for_custom_verbs_or_overrides_that_differ():
    for name, spec in TOOLS.items():
        assert spec.annotations is None or verb(name) not in VERBS or spec.annotations != VERBS[verb(name)], name


def test_tools_that_write_files_or_retrigger_are_not_read_only_or_idempotent():
    for name in ("grid.export", "grid.render"):
        annotations = resolve(name, TOOLS[name].annotations)
        assert annotations.read_only is False
        assert annotations.destructive is True
    for name in ("clips.launch", "scenes.launch"):
        annotations = resolve(name, TOOLS[name].annotations)
        assert annotations.idempotent is False
        assert annotations.destructive is False
    for name in ("export.audio", "audio.capture", "grid.probe", "project.saveAs", "project.save"):
        assert resolve(name, TOOLS[name].annotations).destructive is True


@pytest.mark.parametrize("name", EXPOSED)
def test_advertised_description_fits_the_limit_and_keeps_its_example(name):
    spec = TOOLS[name]
    advertised = next(view.description for view in views.tools() if view.name == name)
    assert len(advertised) <= DESCRIPTION_LIMIT
    assert "…" not in advertised
    example = json.dumps(spec.examples[0], ensure_ascii=False) if spec.examples[0] else ""
    assert example in advertised
    if len(spec.description) + len(example) > DESCRIPTION_LIMIT:
        assert views.HELP_POINTER.format(name=name) in advertised


def test_core_write_tools_carry_hand_written_summaries_with_their_call_critical_facts():
    expected = {
        "notes.edit": ("velocity", "durationBeats", "stepSizeBeats"),
        "grid.parameters.set": ("option value", "grid.get"),
        "clips.set": ("first failure", "stopped"),
        "notes.read": ("2048", "completeWindow"),
        "devices.insert": ("requestedIdentifierVerified",),
        "tracks.set": ("not dB",),
        "grid.apply": ("dryRun", "customName"),
    }
    for name, words in expected.items():
        advertised = next(view.description for view in views.tools() if view.name == name)
        assert TOOLS[name].summary
        for word in words:
            assert word in advertised, (name, word)


def test_nothing_an_agent_reads_names_an_unexposed_method():
    pattern = re.compile("|".join(re.escape(name) + r"(?!\w)" for name in sorted(HIDDEN)))
    texts = {f"description of {name}": TOOLS[name].description for name in EXPOSED}
    texts |= {f"summary of {name}": TOOLS[name].summary for name in EXPOSED}
    texts |= {f"topic {name}": text for name, text in TOPICS.items()}
    texts["tool help"] = views.HELP_DESCRIPTION
    texts["USAGE.md"] = (ROOT / "USAGE.md").read_text()
    for path in sorted((ROOT / "skills").rglob("*.md")):
        texts[str(path.relative_to(ROOT))] = path.read_text()
    for where, text in texts.items():
        assert not pattern.search(text), f"{where} names an unexposed method"


def test_nothing_an_agent_reads_names_a_method_that_does_not_exist():
    domains = {name.split(".")[0] for name in TOOLS if "." in name}
    token = re.compile(r"`((?:" + "|".join(sorted(domains)) + r")\.[a-z][A-Za-z]*(?:\.[a-z][A-Za-z]*)*)`")
    texts = {f"description of {name}": spec.description for name, spec in TOOLS.items() if spec.expose}
    texts |= {f"summary of {name}": spec.summary for name, spec in TOOLS.items() if spec.expose}
    texts |= {f"topic {name}": text for name, text in TOPICS.items()}
    texts["USAGE.md"] = (ROOT / "USAGE.md").read_text()
    for path in sorted((ROOT / "skills").rglob("*.md")):
        texts[str(path.relative_to(ROOT))] = path.read_text()
    for where, text in texts.items():
        unknown = sorted({name for name in token.findall(text) if name not in TOOLS})
        assert not unknown, f"{where} names methods that do not exist: {unknown}"


def test_help_lists_topics_and_exposed_tools_and_rejects_the_unknown():
    listing = views.help()
    assert {"loop", "outcomes", "recovery"} <= set(listing["topics"])
    assert set(listing["tools"]) == set(EXPOSED)
    with pytest.raises(ValueError, match="Unknown topic or tool"):
        views.help("grid.modules.insert")


def test_help_for_a_tool_returns_its_full_text():
    for name in ("grid.apply", "tracks.set"):
        assert views.help(name)["description"] == TOOLS[name].description


def test_instructions_carry_the_loop_topic():
    assert views.instructions().startswith(LOOP)


def test_method_description_refuses_unexposed_methods():
    with pytest.raises(ValueError, match="Unknown method"):
        views.method("grid.voicing.set")
    assert views.method("grid.apply")["description"] == TOOLS["grid.apply"].description


async def test_invoke_refuses_unexposed_methods_before_validation_or_tcp(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    envelope = await invoke("grid.modules.insert", {})
    assert isinstance(envelope, Error)
    assert envelope.error["code"] == "unknown_method"
    assert "help" in envelope.error["message"]
    assert host.connections == 0


@pytest.mark.parametrize("timeout", [99, 5000, 10001, True])
async def test_mcp_refuses_timeout_ms_but_the_core_still_validates_it(mcp_session, host, timeout):
    response = await mcp_session.call_tool("tracks.list", {"timeoutMs": timeout})
    assert response.is_error is True
    assert response.structured_content["code"] == "invalid_params"
    assert "timeoutMs" in response.structured_content["message"]
    assert host.connections == 0


async def test_surface_lists_exposed_tools_with_help_and_no_timeout(mcp_session):
    listed = (await mcp_session.list_tools()).tools
    assert [tool.name for tool in listed] == [*EXPOSED, "help"]
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


async def test_surface_hides_unexposed_methods_as_protocol_errors(mcp_session, host):
    for name in HIDDEN:
        with pytest.raises(MCPError, match="Unknown method"):
            await mcp_session.call_tool(name, {})
    assert host.connections == 0


async def test_result_object_is_the_structured_content_without_wrapper(mcp_session, host):
    result = {"outcome": "applied", "tracks": []}
    host.reply = lambda request: {"id": request["id"], "result": result}
    response = await mcp_session.call_tool("tracks.list", {})
    assert response.is_error is False
    assert response.structured_content == result
    assert json.loads(response.content[0].text) == result


async def test_error_object_is_the_structured_content_and_flags_is_error(mcp_session, host):
    error = {"code": "stale", "reason": "stale_target", "message": "changed", "next": "tracks.list"}
    host.reply = lambda request: {"id": request["id"], "error": error}
    response = await mcp_session.call_tool("tracks.list", {})
    assert response.is_error is True
    assert response.structured_content == error


async def test_help_tool_serves_topics_and_method_docs(mcp_session):
    topics = await mcp_session.call_tool("help", {})
    assert topics.is_error is False
    assert "loop" in topics.structured_content["topics"]
    guide = await mcp_session.call_tool("help", {"topic": "outcomes"})
    assert "unknown" in guide.structured_content["text"]
    method = await mcp_session.call_tool("help", {"topic": "grid.apply"})
    assert method.structured_content["description"] == TOOLS["grid.apply"].description
    missing = await mcp_session.call_tool("help", {"topic": "grid.modules.insert"})
    assert missing.is_error is True
    assert missing.structured_content["code"] == "invalid_params"


@pytest.mark.parametrize("arguments", [{"topic": 5}, {"topic": None, "extra": 1}, {"other": "x"}])
async def test_help_validates_its_arguments(arguments):
    response = await call_tool(NO_CONTEXT, CallToolRequestParams(name="help", arguments=arguments))
    assert response.is_error is True
    assert response.structured_content["code"] == "invalid_params"


@pytest.mark.parametrize(
    "arguments",
    [
        ["grid.modules.insert"],
        ["grid", "modules", "insert", "--nonsense=1"],
        ["grid", "modules", "insert", f"--trackId={TRACK}", "--deviceIndex=0", "--timeoutMs=oops"],
        ["help", "grid.modules.insert"],
        ["grid", "modules", "insert", "--help"],
    ],
)
async def test_cli_refuses_unexposed_methods_before_parsing_fields(host, monkeypatch, arguments):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    payload, code = await execute(arguments)
    assert code == 2
    assert payload["error"]["code"] == "unknown_method"
    assert "grid.modules.insert" in payload["error"]["message"] or "grid.modules" in payload["error"]["message"]
    assert host.connections == 0


async def test_handlers_run_in_process_for_help_unexposed_and_methods(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: {"id": request["id"], "result": {"outcome": "not_applied", "reason": "busy"}}
    listed = await list_tools(NO_CONTEXT, None)
    assert listed.tools[-1].name == "help"
    assert (await call_tool(NO_CONTEXT, CallToolRequestParams(name="help"))).is_error is False
    bad_topic = await call_tool(NO_CONTEXT, CallToolRequestParams(name="help", arguments={"topic": "nope"}))
    assert bad_topic.is_error is True
    with pytest.raises(MCPError):
        await call_tool(NO_CONTEXT, CallToolRequestParams(name="grid.voicing.set"))
    flagged = await call_tool(NO_CONTEXT, CallToolRequestParams(name="tracks.select", arguments={"trackId": TRACK}))
    assert flagged.is_error is True
    assert flagged.structured_content["outcome"] == "not_applied"
    refused = await call_tool(NO_CONTEXT, CallToolRequestParams(name="tracks.select", arguments={}))
    assert refused.is_error is True
    assert refused.structured_content["code"] == "invalid_params"
    assert create_server().name == "Bitwig Agent"


async def test_grid_apply_still_reaches_the_hidden_blocks_through_the_client():
    host = FakeGrid()
    envelope = await invoke("grid.apply", SPEC, client=cast(Any, host))
    assert not isinstance(envelope, Error)
    assert envelope.result["outcome"] == "applied"
    reached = {method for method, _ in host.calls}
    assert {"grid.modules.insert", "grid.modules.rename", "grid.connections.set"} <= reached


@pytest.mark.parametrize("name", ["no.such.tool", "grid.modules.insert"])
async def test_timeout_ms_does_not_change_how_an_unknown_or_hidden_name_is_refused(name):
    with pytest.raises(MCPError, match="Unknown method"):
        await call_tool(NO_CONTEXT, CallToolRequestParams(name=name, arguments={"timeoutMs": 5000}))
