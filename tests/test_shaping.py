"""format and fields: one core shaping for the CLI and MCP, record formats and the read-only text views."""

import json
from typing import Any

import pytest
from mcp.types import CallToolRequestParams, CallToolResult, TextContent

from bitwig_agent.core import notes_grid, shaping, views
from bitwig_agent.core.shaping import Shape
from bitwig_agent.frontends import cli
from bitwig_agent.frontends.mcp import call_tool, list_tools
from bitwig_agent.protocol.launcher import CLIP_REF

pytestmark = pytest.mark.anyio
NO_CONTEXT: Any = None
NOTES = {
    "ref": CLIP_REF,
    "loop": {"start": 0, "length": 1},
    "count": 2,
    "complete": True,
    "exact": True,
    "notes": [
        {"beat": 0, "pitch": 36, "velocity": 120, "duration": 0.25},
        {"beat": 0.5, "pitch": 42, "velocity": 80, "duration": 0.25, "channel": 1},
    ],
}
SONG = {
    "clips": [
        {
            "ref": {"kind": "clip", "track": {"kind": "track", "id": "t", "name": "鼓"}, "start": 0, "name": "A"},
            "start": 0,
            "length": 8,
            "end": 8,
            "name": "A",
        }
    ],
    "markers": [],
    "tempo": 120,
    "timeSignature": "4/4",
}


def text(response: CallToolResult, index: int) -> str:
    content = response.content[index]
    assert isinstance(content, TextContent)
    return content.text


TRACKS = {"tracks": [{"name": "Lead", "ref": {"id": "x"}}], "window": {"complete": False}}


def test_formats_follow_the_method_table():
    assert views.formats("status") == ("json",)
    assert views.formats("tracks.list") == ("json", "jsonl", "csv", "tsv")
    assert views.formats("notes.get") == ("json", "jsonl", "csv", "tsv", "grid")
    assert views.formats("arranger.list") == ("json", "jsonl", "csv", "tsv", "timeline")


def test_the_offered_schema_carries_format_and_fields_only_where_they_apply():
    assert views.shaping_schema("status") == {}
    schema = views.shaping_schema("notes.get")
    assert schema["format"]["enum"] == ["json", "jsonl", "csv", "tsv", "grid"]
    assert "grid: a read-only pitch x step grid" in schema["format"]["description"]
    assert schema["fields"]["type"] == "array"


@pytest.mark.parametrize(
    ("name", "arguments", "message"),
    [
        ("tracks.list", {"format": 1}, "format is a string"),
        ("tracks.list", {"fields": "name"}, "fields is an array"),
        ("tracks.list", {"fields": [""]}, "fields is an array"),
        ("tracks.list", {"format": "grid"}, "must be one of json, jsonl, csv, tsv"),
        ("arranger.list", {"format": "timeline", "fields": ["name"]}, "fields does not apply to format=timeline"),
    ],
)
def test_split_refuses_a_shape_the_method_does_not_take(name, arguments, message):
    with pytest.raises(ValueError, match=message):
        shaping.split(name, arguments)


def test_split_leaves_a_method_without_shapes_and_an_unknown_method_alone():
    assert shaping.split("status", {"format": "csv"}) == ({"format": "csv"}, Shape())
    assert shaping.split("no.such", {"format": "csv"}) == ({"format": "csv"}, Shape())
    assert shaping.split("notes.get", {"target": CLIP_REF, "format": "grid"}) == ({"target": CLIP_REF}, Shape("grid"))


def test_a_result_with_an_outcome_is_never_reshaped():
    unknown = {"outcome": "unknown", "reason": "not_observed"}
    assert shaping.apply("notes.get", unknown, Shape("grid")) == shaping.Shaped(unknown)


async def test_mcp_returns_a_text_view_as_text_content_only(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: {"id": request["id"], "result": NOTES}
    params = CallToolRequestParams(name="notes.get", arguments={"target": CLIP_REF, "format": "grid"})
    response = await call_tool(NO_CONTEXT, params)
    assert (response.is_error, response.structured_content) == (False, None)
    assert text(response, 0) == notes_grid.render(NOTES)
    assert host.requests[-1]["params"] == {"target": CLIP_REF}


async def test_mcp_fields_project_the_json_result_and_warn_as_extra_text(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: {"id": request["id"], "result": TRACKS}
    params = CallToolRequestParams(name="tracks.list", arguments={"fields": ["name", "nme"]})
    response = await call_tool(NO_CONTEXT, params)
    assert response.structured_content["tracks"] == [{"name": "Lead", "nme": None}]
    assert json.loads(text(response, 1))["warning"] == "unknown_field"
    rows = await call_tool(NO_CONTEXT, CallToolRequestParams(name="tracks.list", arguments={"format": "tsv"}))
    assert text(rows, 0) == "name\tref.id\nLead\tx\n"
    assert json.loads(text(rows, 1))["warning"] == "partial"


async def test_mcp_refuses_a_bad_shape_before_the_call_and_a_result_without_records_after(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    refused = await call_tool(NO_CONTEXT, CallToolRequestParams(name="tracks.list", arguments={"format": "grid"}))
    assert (refused.is_error, refused.structured_content["code"]) == (True, "invalid_params")
    assert host.connections == 0
    unshaped = await call_tool(NO_CONTEXT, CallToolRequestParams(name="status", arguments={"format": "csv"}))
    assert "format" in unshaped.structured_content["message"]
    host.reply = lambda request: {"id": request["id"], "result": {"other": []}}
    broken = await call_tool(NO_CONTEXT, CallToolRequestParams(name="tracks.list", arguments={"format": "jsonl"}))
    assert "no array of objects named tracks" in broken.structured_content["message"]


async def test_mcp_lists_format_on_shapeable_tools_only():
    tools = {tool.name: tool for tool in (await list_tools(NO_CONTEXT, None)).tools}
    assert "grid" in tools["notes.get"].input_schema["properties"]["format"]["enum"]
    assert "timeline" in tools["arranger.list"].input_schema["properties"]["format"]["enum"]
    assert "format" not in tools["status"].input_schema["properties"]


async def test_cli_prints_the_views_and_names_them_in_help(host, monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", str(host.port))
    host.reply = lambda request: {"id": request["id"], "result": NOTES}
    target = f"--target={json.dumps(CLIP_REF)}"
    grid = await cli.execute(["notes", "get", target, "--format=grid"])
    assert (grid.code, grid.stdout, grid.stderr) == (0, notes_grid.render(NOTES), "")
    tsv = await cli.execute(["notes", "get", target, "--format=tsv", "--fields=beat,pitch,channel"])
    assert tsv.stdout == "beat\tpitch\tchannel\n0\t36\t\n0.5\t42\t1\n"
    host.reply = lambda request: {"id": request["id"], "result": SONG}
    timeline = await cli.execute(["arranger", "list", "--format=timeline"])
    assert timeline.stdout.splitlines()[3] == "鼓   AA"
    assert "View: --format=grid prints a read-only" in (await cli.execute(["notes", "get", "--help"])).stdout
    assert "--format=timeline" in (await cli.execute(["--help"])).stdout
