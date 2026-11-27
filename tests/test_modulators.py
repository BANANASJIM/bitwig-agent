"""Device-modulator tools: offline docs on synthetic fixtures, strict schemas; a real install only for coverage."""

import asyncio
from pathlib import Path
from typing import Any

import pytest
from fakes import execute_cli as execute

from bitwig_agent import modulator_docs
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, validate_call
from bitwig_agent.protocol.annotations import REMOVE, resolve

REAL_ROOT = Path("/opt/bitwig-studio")
real = pytest.mark.skipif(
    not (REAL_ROOT / "resources/localization/Modulator-descriptions-resources.properties").is_file(),
    reason="Bitwig install not present",
)
TRACK = "12345678-1234-1234-1234-123456789abc"
MOD = "abcdef01-2345-6789-abcd-0123456789ab"
TRACK_REF = {"kind": "track", "id": TRACK, "name": "Lead"}
PARENT = {"kind": "device", "track": TRACK_REF, "index": 0, "name": "Poly Grid"}
SOURCE = {"kind": "modulator", "device": PARENT, "id": MOD, "name": "LFO"}
GRID_MODULE = {"kind": "gridModule", "device": PARENT, "id": "abcdef02-2345-6789-abcd-0123456789ab", "name": "AD"}
GRID_TARGET = {"kind": "parameter", "owner": GRID_MODULE, "id": "DECAY"}
MODULATOR_TARGET = {
    "kind": "parameter",
    "owner": {**SOURCE, "id": "abcdef03-2345-6789-abcd-0123456789ab", "name": "Random"},
    "id": "RATE",
}

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
    assert result["name"] == "Fake LFO"
    assert result["category"] == "LFO"
    assert result["keywords"] == ["wobble", "sync"]
    assert controls["bipolar"] == {"docKey": "bipolar", "title": "Bipolar [toggle]", "description": "Fake polarity"}
    assert controls["rate"]["documentedValues"] == [
        {"display": "-1.00", "meaning": "slow"},
        {"display": "0.00", "meaning": "medium"},
    ]
    assert controls["rate"]["documentedValuesStatus"] == modulator_docs.DOCUMENTED_STATUS
    assert result["sharedControls"] == [{"docKey": "input", "description": "Shared input text"}]
    assert "modulators.list" in result["liveFacts"]
    assert modulator_docs.describe({"type": "Chaos"})["controls"][0]["docKey"] == "smooth"
    assert modulator_docs.describe({"query": "wobble"})["modulators"][0]["name"] == "Fake LFO"
    assert modulator_docs.describe({})["total"] == 3
    with pytest.raises(ValueError, match="did you mean"):
        modulator_docs.describe({"type": "Fake LF"})


def test_client_side_routing_never_calls_the_host(root):
    assert "modulators.describe" in CLIENT_SIDE
    assert "modulators.catalog" not in TOOLS
    payload, code = asyncio.run(execute(["modulators.describe", "--type=Chaos"]))
    assert code == 0
    assert payload["result"]["name"] == "Chaos"
    payload, code = asyncio.run(execute(["modulators.describe"]))
    assert code == 0
    assert payload["result"]["total"] == 3


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
        "modulators.list",
        "modulators.get",
        "modulators.insert",
        "modulators.delete",
        "modulators.map",
        "modulators.unmap",
        "modulators.describe",
    ):
        for example in TOOLS[method].examples:
            validate_call(method, example)
    for method in ("modulators.insert", "modulators.delete", "modulators.map", "modulators.unmap"):
        assert not TOOLS[method].read_only
    assert TOOLS["modulators.get"].read_only
    assert TOOLS["modulators.list"].read_only


def test_map_is_an_upsert_whose_omitted_fields_stay_omitted():
    base = {"source": SOURCE, "target": GRID_TARGET}
    args, _ = validate_call("modulators.map", {**base, "amount": 0.5})
    assert not {"transfer", "enabled", "expect"} & set(args)
    full = {
        **base,
        "target": MODULATOR_TARGET,
        "amount": 1,
        "transfer": "rectify_pos",
        "enabled": False,
        "outputId": "OUT",
        "expect": {"amount": -1},
    }
    assert validate_call("modulators.map", full)[0] == full
    for bad in (
        {"amount": 1.5},
        {},
        {"amount": "0.5"},
        {"amount": 0.5, "transfer": "log"},
        {"amount": 0.5, "extra": 1},
        {"amount": True},
        {"amount": 0.5, "expect": {}},
        {"amount": 0.5, "expect": {"amount": 2}},
        {"amount": 0.5, "expectedAmount": None},
        {"amount": 0.5, "modulatorId": MOD},
        {
            "amount": 0.5,
            "target": {"kind": "parameter", "owner": {**GRID_MODULE, "device": {**PARENT, "index": 1}}, "id": "X"},
        },
    ):
        with pytest.raises(ValueError, match=r"validation errors? for ModulatorsMapArgs"):
            validate_call("modulators.map", {**base, **bad})
    with pytest.raises(ValueError, match=r"validation errors? for ModulatorsUnmapArgs"):
        validate_call("modulators.unmap", {**base, "amount": 0.5})
    assert validate_call("modulators.unmap", {**base, "expect": {"amount": 0.25}})[0]["expect"] == {"amount": 0.25}
    assert validate_call("modulators.unmap", base)[0] == base


def test_map_targets_take_only_grid_and_modulator_parameters():
    base = {"source": SOURCE, "amount": 0.5}
    device_parameter = {"kind": "parameter", "owner": PARENT, "id": "CONTENTS/GAIN"}
    track_parameter = {"kind": "parameter", "owner": TRACK_REF, "id": "volume"}
    for target in (device_parameter, track_parameter):
        with pytest.raises(ValueError, match=r"does not take a parameter owned by"):
            validate_call("modulators.map", {**base, "target": target})


def test_unmap_is_annotated_as_a_removal_and_the_catalog_is_describe():
    assert resolve("modulators.unmap", TOOLS["modulators.unmap"].annotations) == REMOVE
    assert TOOLS["modulators.describe"].host is False
    assert validate_call("modulators.describe", {})[0] == {}
    with pytest.raises(ValueError, match="at most one"):
        validate_call("modulators.describe", {"type": "Random", "query": "random"})


def test_insert_delete_and_get_schemas():
    validate_call("modulators.insert", {"to": PARENT, "typeName": "Random"})
    validate_call("modulators.insert", {"to": PARENT, "typeId": MOD})
    bad: Any
    for bad in (
        {},
        {"typeName": "Random", "typeId": MOD},
        {"typeName": ""},
        {"typeName": "Random", "expectedCount": 0},
        {"to": SOURCE, "typeName": "Random"},
        {"parent": PARENT, "typeName": "Random"},
    ):
        with pytest.raises(ValueError, match=r"validation errors? for ModulatorsInsert\w*Args"):
            validate_call("modulators.insert", {"to": PARENT, **bad})
    for method in ("modulators.get", "modulators.delete"):
        assert validate_call(method, {"target": SOURCE})[0] == {"target": SOURCE}
        for bad_target in (PARENT, {**SOURCE, "id": "nope"}, {"modulatorId": MOD}):
            with pytest.raises(ValueError, match=r"validation errors? for Modulators"):
                validate_call(method, {"target": bad_target})
