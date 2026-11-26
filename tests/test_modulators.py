"""Device-modulator tools: offline docs on synthetic fixtures, strict schemas; a real install only for coverage."""

import asyncio
from pathlib import Path

import pytest

from bitwig_agent import modulator_docs
from bitwig_agent.catalog import CLIENT_SIDE, TOOLS, validate_call
from bitwig_agent.cli import execute

REAL_ROOT = Path("/opt/bitwig-studio")
real = pytest.mark.skipif(
    not (REAL_ROOT / "resources/localization/Modulator-descriptions-resources.properties").is_file(),
    reason="Bitwig install not present",
)
TRACK = "12345678-1234-1234-1234-123456789abc"
MOD = "abcdef01-2345-6789-abcd-0123456789ab"
PARENT = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
GRID_TARGET = {"moduleId": "abcdef02-2345-6789-abcd-0123456789ab", "parameterId": "DECAY"}

DESCRIPTIONS = """\
#Mon Jan 01 00:00:00 CET 2024

#lfo.
modulator.fake_lfo.desc=Fake wobble
modulator.fake_lfo.keywords=wobble sync

modulator.chaos.desc=Fake random value
modulator.chaos.keywords=noise dice

#voice_stacking.
modulator.stack_thing.desc=Fake stacker
"""
ENTRIES = """\
modulator_general.input.desc=Shared input text
fake_lfo-modulator.rate.desc=Fake rate where: \\n\u2022 '-1.00' = slow \\n\u2022 '0.00' = medium
fake_lfo-modulator.bipolar.title=Bipolar [toggle]
fake_lfo-modulator.bipolar.desc=Fake polarity
chaos.smooth.desc=Fake smoothing
"""


@pytest.fixture
def root(tmp_path, monkeypatch):
    directory = tmp_path / "resources" / "localization"
    directory.mkdir(parents=True)
    (directory / "Module-descriptions-resources.properties").write_text("module.x.desc=x\n", encoding="utf-8")
    (directory / "Modulator-descriptions-resources.properties").write_bytes(b"\xef\xbb\xbf" + DESCRIPTIONS.encode())
    (directory / "Modulators-resources.properties").write_text(ENTRIES, encoding="utf-8")
    (directory / "Modulator-descriptions-resources_de.properties").write_text(
        "modulator.chaos.desc=Zufall\n", encoding="utf-8"
    )
    library = tmp_path / "Library" / "modulators"
    library.mkdir(parents=True)
    for name in ("Fake LFO", "Chaos", "Stack Thing"):
        (library / f"{name}.bwmodulator").write_bytes(b"")
    monkeypatch.setattr(modulator_docs, "_cache", {})
    monkeypatch.setenv("BITWIG_INSTALLATION_ROOT", str(tmp_path))
    return tmp_path


def test_catalog_lists_names_categories_and_descriptions(root):
    result = modulator_docs.catalog({})
    rows = {row["name"]: row for row in result["modulators"]}
    assert result["total"] == 3
    assert rows["Fake LFO"] == {"name": "Fake LFO", "description": "Fake wobble", "category": "LFO"}
    assert rows["Stack Thing"]["category"] == "Voice stacking"
    assert "keywords" not in rows["Chaos"]


def test_catalog_query_ranks_and_localizes(root):
    assert modulator_docs.catalog({"query": "dice"})["modulators"][0]["name"] == "Chaos"
    assert modulator_docs.catalog({"query": "lfo"})["modulators"][0]["name"] == "Fake LFO"
    assert modulator_docs.catalog({"query": "chaos", "lang": "de"})["modulators"][0]["description"] == "Zufall"


def test_describe_collects_controls_and_documented_values(root):
    result = modulator_docs.describe({"type": "fake lfo"})
    controls = {row["docKey"]: row for row in result["controls"]}
    assert result["name"] == "Fake LFO" and result["category"] == "LFO" and result["keywords"] == ["wobble", "sync"]
    assert controls["bipolar"] == {"docKey": "bipolar", "title": "Bipolar [toggle]", "description": "Fake polarity"}
    assert controls["rate"]["documentedValues"] == [
        {"display": "-1.00", "meaning": "slow"},
        {"display": "0.00", "meaning": "medium"},
    ]
    assert controls["rate"]["documentedValuesStatus"] == modulator_docs.DOCUMENTED_STATUS
    assert result["sharedControls"] == [{"docKey": "input", "description": "Shared input text"}]
    assert "modulators.get" in result["liveFacts"]
    assert modulator_docs.describe({"type": "Chaos"})["controls"][0]["docKey"] == "smooth"
    assert modulator_docs.describe({"query": "wobble"})["matches"][0]["name"] == "Fake LFO"
    with pytest.raises(ValueError, match="did you mean"):
        modulator_docs.describe({"type": "Fake LF"})


def test_client_side_routing_never_calls_the_host(root):
    assert {"modulators.catalog", "modulators.describe"} <= CLIENT_SIDE
    payload, code = asyncio.run(execute(["modulators.describe", "type=Chaos"]))
    assert code == 0 and payload["result"]["name"] == "Chaos"


@real
def test_real_install_covers_every_modulator(monkeypatch):
    monkeypatch.setattr(modulator_docs, "_cache", {})
    rows = modulator_docs.catalog({"installationRoot": str(REAL_ROOT)})["modulators"]
    assert len(rows) == 43
    missing = [row["name"] for row in rows if not row.get("description") or not row.get("category")]
    assert not missing, missing
    for name in ("Beat LFO", "Random", "Steps", "ADSR", "Macro-4"):
        assert modulator_docs.describe({"type": name, "installationRoot": str(REAL_ROOT)})["controls"], name


def test_examples_validate_and_write_tools_are_not_read_only():
    for method in (
        "modulators.get",
        "modulators.insert",
        "modulators.delete",
        "modulators.parameters.set",
        "modulators.map",
        "modulators.unmap",
        "modulators.catalog",
        "modulators.describe",
    ):
        for example in TOOLS[method].examples:
            validate_call(method, example)
    for method in (
        "modulators.insert",
        "modulators.delete",
        "modulators.parameters.set",
        "modulators.map",
        "modulators.unmap",
    ):
        assert not TOOLS[method].read_only
    assert TOOLS["modulators.get"].read_only


def test_map_schema_mirrors_host_rules():
    base = {"parent": PARENT, "modulatorId": MOD, "target": GRID_TARGET}
    args, _ = validate_call("modulators.map", {**base, "expectedAmount": None, "amount": 0.5})
    assert args["expectedAmount"] is None  # explicit null is sent: it means "create"
    validate_call(
        "modulators.map",
        {
            **base,
            "target": {"modulatorId": MOD, "parameterId": "RATE"},
            "expectedAmount": -1,
            "amount": 1,
            "transfer": "rectify_pos",
            "enabled": False,
            "outputId": "OUT",
        },
    )
    for bad in (
        {"expectedAmount": None, "amount": 1.5},
        {"amount": 0.5},
        {"expectedAmount": None, "amount": "0.5"},
        {"expectedAmount": None, "amount": 0.5, "transfer": "log"},
        {"expectedAmount": None, "amount": 0.5, "extra": 1},
        {"expectedAmount": None, "amount": 0.5, "target": {**GRID_TARGET, "modulatorId": MOD}},
        {"expectedAmount": None, "amount": True},
    ):
        with pytest.raises(ValueError):
            validate_call("modulators.map", {**base, **bad})
    with pytest.raises(ValueError):
        validate_call("modulators.unmap", {**base, "expectedAmount": None})
    validate_call("modulators.unmap", {**base, "expectedAmount": 0.25})


def test_insert_delete_and_parameter_schemas():
    validate_call("modulators.insert", {"parent": PARENT, "expectedCount": 0, "typeName": "Random"})
    validate_call("modulators.insert", {"parent": PARENT, "expectedCount": 4096, "typeId": MOD})
    for bad in (
        {"expectedCount": 0},
        {"expectedCount": 0, "typeName": "Random", "typeId": MOD},
        {"expectedCount": -1, "typeName": "Random"},
        {"expectedCount": 0, "typeName": ""},
    ):
        with pytest.raises(ValueError):
            validate_call("modulators.insert", {"parent": PARENT, **bad})
    with pytest.raises(ValueError):
        validate_call("modulators.delete", {"parent": PARENT, "modulatorId": MOD, "expectedCount": 0})
    ok = {"parent": PARENT, "modulatorId": MOD, "parameterId": "RATE", "expectedKind": "float", "expectedValue": 0.5}
    validate_call("modulators.parameters.set", {**ok, "value": 0.25})
    validate_call("modulators.parameters.set", {**ok, "display": "2 Hz"})
    validate_call("modulators.parameters.set", {**ok, "expectedKind": "enum", "expectedValue": 1, "value": 2})
    for bad in (
        {"value": 0.25, "display": "2 Hz"},
        {},
        {"expectedKind": "string", "value": "x"},
        {"expectedKind": "integer", "value": 1.5},
        {"expectedKind": "boolean", "expectedValue": True, "value": 1},
    ):
        with pytest.raises(ValueError):
            validate_call("modulators.parameters.set", {**ok, **bad})
