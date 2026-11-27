import re
import tomllib
from pathlib import Path

import pytest

from bitwig_agent.grid import display, docs
from bitwig_agent.grid.bundled import bundled_schema, host_version, measured_notes, shared
from bitwig_agent.grid.names import resolve
from bitwig_agent.grid.spec import TypeCatalog

ROOT = Path(__file__).resolve().parents[1]
VECTORS = shared("display")["vectors"]


@pytest.mark.parametrize("row", VECTORS["quantity"], ids=lambda r: f"{r['unit']}:{r['text']}")
def test_quantity_vectors(row):
    got = display.parse_quantity(row["unit"], row["text"])
    if row["natural"] is None:
        assert got is None
    else:
        assert got is not None
        assert got[0] == pytest.approx(row["natural"])
        assert got[1] == pytest.approx(row["halfStep"])


@pytest.mark.parametrize("row", VECTORS["convert"], ids=lambda r: f"{r['formula']}:{r['raw']}")
def test_formula_vectors(row):
    to_raw, to_natural = display.FORMULAS[row["formula"]](row.get("factor", 1.0))
    assert to_natural(row["raw"]) == pytest.approx(row["natural"])
    assert to_raw(row["natural"]) == pytest.approx(row["raw"])


@pytest.mark.parametrize("row", VECTORS["near"], ids=lambda r: f"{r['wanted']}~{r['live']}")
def test_near_vectors(row):
    assert display.display_matches(row["wanted"], row["live"], row["unit"]) is row["near"]


@pytest.mark.parametrize("row", VECTORS["toRaw"], ids=lambda r: f"{r['unit']}:{r['requested']}")
def test_to_raw_vectors(row):
    basis = display.DisplayBasis(row["unit"], row["value"], row["display"], None, None)
    if row["raw"] is None:
        with pytest.raises(display.SpecError):
            display.check_display("X", row["requested"], basis)
    else:
        assert display.check_display("X", row["requested"], basis) == pytest.approx(row["raw"])


@pytest.mark.parametrize("row", VECTORS["names"], ids=lambda r: repr(r["query"]))
def test_name_vectors(row):
    assert resolve(VECTORS["nameCatalog"], str, row["query"]) == row["hits"]


def test_every_formula_the_data_names_is_implemented():
    data = shared("display")
    named = {u["formula"] for u in data["units"].values()} | {p["formula"] for p in data["seconds"]["params"]}
    assert named <= set(display.FORMULAS)


def test_data_is_the_only_table():
    assert set(display.DISPLAY_UNITS) == {*shared("display")["units"], "seconds"}
    assert len(display.SECONDS_PARAMS) == len(shared("display")["seconds"]["params"])


def test_docs_and_spec_resolve_types_by_the_shared_rule():
    modules = {
        "Slope ↗": {"typeId": "a"},
        "Slope ↘": {"typeId": "b"},
        "Lag": {"typeId": "c"},
        "Low-pass": {"typeId": "d"},
    }
    assert docs.resolve_type(modules, "lag") == "Lag"
    assert docs.resolve_type(modules, "B") == "Slope ↘"
    assert docs.resolve_type(modules, "lowpass") == "Low-pass"
    with pytest.raises(ValueError, match="ambiguous"):
        docs.resolve_type(modules, "slope")
    catalog = TypeCatalog(None, {name: [info["typeId"]] for name, info in modules.items()})
    assert catalog.resolve({"name": "m", "type": "lowpass"}) == ("d", "Low-pass")


def test_each_host_target_is_one_fact():
    hosts = shared("host")
    assert host_version() == hosts[-1]["version"]
    assert bundled_schema()["hostVersion"] == host_version()
    assert measured_notes()["hostVersion"] == host_version()
    api = tomllib.loads((ROOT / "extension/deps.toml").read_text())["extension-api"]
    for host in hosts:
        assert set(host) == {"version", "apiVersion", "jarSha256", "names"}
        assert re.fullmatch(r"[0-9a-f]{64}", host["jarSha256"])
        assert host["names"] == f"names_{host['version'].replace('.', '_')}"
        names = shared(host["names"])
        assert set(names) == {"classes", "members"}
        assert api["file"] == f"extension-api-{host['apiVersion']}.jar"
        assert f"/{host['apiVersion']}/" in api["url"]
