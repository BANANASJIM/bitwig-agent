"""grid.describe: parser, mapping and search on synthetic fixtures; coverage against a real install when present."""

import asyncio
import re
from pathlib import Path
from typing import Any

import pytest

from bitwig_agent.cli import execute
from bitwig_agent.grid import docs as grid_docs
from bitwig_agent.grid.bundled import bundled_schema
from bitwig_agent.protocol import validate_call

REAL_ROOT = Path("/opt/bitwig-studio")
real = pytest.mark.skipif(
    not (REAL_ROOT / "resources/localization/Module-descriptions-resources.properties").is_file(),
    reason="Bitwig install not present",
)

DESCRIPTIONS = """\
#comment
module-category.Mix.desc=Fake routers
module.select_in.desc=Fake selector
module.select_in.keywords=router selector
module.chance.desc=Fake random trigger gate
module.chance.keywords=weighted noise
module-category.Math.desc=Fake math
module.add.desc=Fake adder
module.add.keywords=sum plus
"""
MODULES = """\
select_in.logic_in.desc=Low routes 1\\nhigh routes 2
chance.trigger_in.desc=Trigger in text
chance.out.desc=Short triggers
chance.extra.desc=Unmatched leftover
math.out.desc=Shared math out
"""
SCHEMA: dict[str, dict[str, Any]] = {
    "Select In": {
        "typeId": "1",
        "inputs": [{"key": "SEL", "name": "Select In"}],
        "outputs": [{"key": "OUT", "name": "Signal Out"}],
        "parameters": [],
    },
    "Chance": {
        "typeId": "2",
        "inputs": [{"key": "IN", "name": "Trigger In"}],
        "outputs": [{"key": "OUT", "name": "Trigger Out"}],
        "parameters": [{"parameterId": "PROBABILITY", "name": "Chance"}],
    },
    "Add": {"typeId": "3", "inputs": [], "outputs": [{"key": "OUT", "name": "A + B"}], "parameters": []},
    "Mystery": {"typeId": "4", "inputs": [], "outputs": [], "parameters": []},
}


@pytest.fixture
def root(tmp_path, monkeypatch):
    directory = tmp_path / "resources" / "localization"
    directory.mkdir(parents=True)
    (directory / "Module-descriptions-resources.properties").write_text(DESCRIPTIONS, encoding="utf-8")
    (directory / "Modules-resources.properties").write_bytes(
        b"\xef\xbb\xbf" + MODULES.encode()
    )  # real files carry a BOM
    (directory / "Module-descriptions-resources_de.properties").write_text(
        "module.select_in.desc=Auswahl\n", encoding="utf-8"
    )
    monkeypatch.setattr(grid_docs, "_cache", {})
    monkeypatch.setenv("BITWIG_INSTALLATION_ROOT", str(tmp_path))
    return tmp_path


def test_parse_properties_escapes_and_continuations():
    text = (
        "﻿# c\n! c\na=1\nb : two words\nc\\ d=k\\=v\nlong=one \\\n    two\nu=\\u00f8 \\u2192\\n!\nempty=\nnovalue\n"
        "back=ends\\\\\nnext=1\nsur=\\ud83d\\ude00\n"
    )
    got = grid_docs.parse_properties(text)
    assert got["a"] == "1"
    assert got["b"] == "two words"
    assert got["c d"] == "k=v"
    assert got["long"] == "one two"
    assert got["u"] == "ø →\n!"
    assert got["empty"] == ""
    assert got["novalue"] == ""
    assert got["back"] == "ends\\"
    assert got["next"] == "1"
    assert got["sur"] == "\U0001f600"


def test_describe_maps_ports_and_keeps_raw_entries(root):
    docs = grid_docs.get_docs()
    select = grid_docs.describe_module(docs, "Select In", SCHEMA["Select In"])
    assert select["resourceId"] == "select_in"
    assert select["description"] == "Fake selector"
    assert select["category"] == "Mix"
    sel = next(p for p in select["ports"] if p["key"] == "SEL")
    assert sel["description"] == "Low routes 1\nhigh routes 2"
    assert select["rawEntries"] == {"select_in.logic_in.desc": "Low routes 1\nhigh routes 2"}
    chance = grid_docs.describe_module(docs, "Chance", SCHEMA["Chance"])
    by_key = {p["key"]: p.get("description") for p in chance["ports"]}
    assert by_key == {"IN": "Trigger in text", "OUT": "Short triggers"}
    assert "chance.extra.desc" in chance["rawEntries"]
    assert chance["keywords"] == ["weighted", "noise"]
    add = grid_docs.describe_module(docs, "Add", SCHEMA["Add"])  # category-shared entry, no module-own entries
    assert add["ports"][0]["description"] == "Shared math out"
    assert add["rawEntries"] == {}
    mystery = grid_docs.describe_module(docs, "Mystery", SCHEMA["Mystery"])
    assert mystery["resourceId"] is None
    assert "note" in mystery


def test_language_overlay_falls_back_to_english(root):
    assert grid_docs.get_docs(lang="de").descriptions["module.select_in.desc"] == "Auswahl"
    assert grid_docs.get_docs(lang="de").descriptions["module.chance.desc"] == "Fake random trigger gate"


def test_search_ranks_name_then_keywords(root, monkeypatch):
    monkeypatch.setattr(grid_docs, "bundled_schema", lambda: {"modules": SCHEMA})
    docs = grid_docs.get_docs()
    names = [m["name"] for m in grid_docs.search(docs, SCHEMA, "random trigger", 10)]
    assert names[0] == "Chance"
    assert [m["name"] for m in grid_docs.search(docs, SCHEMA, "router", 10)] == ["Select In"]
    assert grid_docs.search(docs, SCHEMA, "nothingmatches", 10) == []


def test_type_resolution_by_name_loose_name_or_type_id():
    assert grid_docs.resolve_type(SCHEMA, "select in") == "Select In"
    assert grid_docs.resolve_type(SCHEMA, "2") == "Chance"
    with pytest.raises(ValueError, match="did you mean"):
        grid_docs.resolve_type(SCHEMA, "Chanse")


def test_missing_install_is_an_actionable_error(tmp_path, monkeypatch):
    monkeypatch.setenv("BITWIG_INSTALLATION_ROOT", str(tmp_path))
    with pytest.raises(grid_docs.DocsUnavailable, match="installationRoot"):
        grid_docs.get_docs()


def test_argument_validation():
    assert validate_call("grid.describe", {"type": "Chance"})[0] == {"type": "Chance"}
    for bad in ({}, {"type": "A", "query": "b"}, {"type": "A", "limit": 3}, {"query": "x", "lang": "xx"}):
        with pytest.raises(ValueError, match=re.escape("1 validation error for GridDescribeArgs")):
            validate_call("grid.describe", bad)


def test_cli_runs_offline(root):
    payload, code = asyncio.run(execute(["grid.describe", "type=Select In"]))
    assert code == 0
    assert payload["result"]["description"] == "Fake selector"
    payload, code = asyncio.run(execute(["grid.describe", "query=selector", "installationRoot=/nonexistent"]))
    assert code == 2
    assert "installationRoot" in payload["error"]["message"]


def test_overrides_and_aliases_name_real_schema_modules():
    modules = bundled_schema()["modules"]
    assert set(grid_docs.ID_OVERRIDES) <= set(modules)
    assert set(grid_docs.PREFIX_ALIASES) <= set(modules)


@real
def test_real_install_maps_nearly_every_module():
    report = grid_docs.mapping_report(REAL_ROOT)
    assert report["mapped"] >= 200, report["unmapped"]
    docs = grid_docs.get_docs(REAL_ROOT)
    modules = bundled_schema()["modules"]
    select = grid_docs.describe_module(docs, "Select In", modules["Select In"])
    assert "selector" in select["description"].lower()
    assert "high" in next(p for p in select["ports"] if p["key"] == "SEL")["description"].lower()
    chance = grid_docs.describe_module(docs, "Chance", modules["Chance"])
    assert "trigger" in next(p for p in chance["ports"] if p["direction"] == "out")["description"].lower()
    assert grid_docs.describe_module(docs, "Shift Register", modules["Shift Register"])["description"]
    assert grid_docs.describe({"type": "Select In", "lang": "ja"})["description"]
    assert grid_docs.describe({"query": "random trigger"})["matches"]
