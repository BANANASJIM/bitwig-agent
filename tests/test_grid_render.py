"""grid.render against a fake host serving grid.get pages (real 131-module excerpt as fixture)."""

import asyncio
import copy
import json
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import cast

import pytest

from bitwig_agent import grid_render
from bitwig_agent.catalog import CLIENT_SIDE, validate_call
from bitwig_agent.cli import parse_arguments
from bitwig_agent.client import BridgeError

TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "grid_layout.json").read_text(encoding="utf-8"))


class FakeGrid:
    def __init__(self, modules, connections, cap=64):
        self.modules = copy.deepcopy(modules)
        self.connections = copy.deepcopy(connections)
        self.cap = cap  # the real host returns fewer rows than asked
        self.calls = []

    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, copy.deepcopy(params)))
        assert method == "grid.get"
        rows = [
            m
            for m in self.modules
            if m["instanceId"] in params.get("moduleIds", [m["instanceId"] for m in self.modules])
        ]
        page = rows[params["moduleOffset"] : params["moduleOffset"] + min(params["moduleLimit"], self.cap)]
        page = [m if params["includeParameters"] else {k: v for k, v in m.items() if k != "parameters"} for m in page]
        return {
            "status": "observed",
            "modules": page,
            "connections": self.connections,
            "window": {
                "offset": params["moduleOffset"],
                "total": len(rows),
                "complete": params["moduleOffset"] + len(page) >= len(rows),
            },
        }


def mod(name, typ, x, y, w=1, h=1, inputs=("IN",), outputs=("OUT",), custom=None, iid=None):  # noqa: PLR0913, PLR0917
    return {
        "instanceId": iid or name,
        "customName": name if custom is None else custom,
        "name": name,
        "typeName": typ,
        "x": x,
        "y": y,
        "width": w,
        "height": h,
        "inputs": [{"key": k, "name": k, "connected": False} for k in inputs],
        "outputs": [{"key": k, "name": k} for k in outputs],
        "parameters": [],
    }


def cable(a, b):
    return {"sourceModuleId": a, "sourcePortKey": "OUT", "targetModuleId": b, "targetPortKey": "IN"}


def run(host, tmp_path, name="out.svg", **extra):
    args, timeout = validate_call("grid.render", {**TARGET, "outPath": str(tmp_path / name), **extra})
    return asyncio.run(grid_render.render(host, args, timeout))


def test_fixture_pages_completely_and_renders_wellformed_svg(tmp_path):
    host = FakeGrid(FIXTURE["modules"], FIXTURE["connections"])
    result = run(host, tmp_path)
    assert len(FIXTURE["modules"]) > 128
    assert result["moduleCount"] == len(FIXTURE["modules"])
    assert result["connectionCount"] == len(FIXTURE["connections"])
    structure = [p for m, p in host.calls if not p["includeParameters"]]
    assert [p["moduleOffset"] for p in structure] == [0, 64, 128]
    assert sum(result["categories"].values()) == result["moduleCount"]
    root = ET.parse(tmp_path / "out.svg").getroot()  # well-formed XML
    assert root.tag.endswith("svg")
    text = " ".join(t.text or "" for t in root.iter() if t.tag.endswith("text"))
    comment = next(m for m in FIXTURE["modules"] if m["typeName"] == "Comment")
    assert comment["parameters"][0]["value"].strip().split("\n")[0][:10] in text  # comment TEXT is drawn
    assert any(m["typeName"] == "Label" and m["customName"][:8] in text for m in FIXTURE["modules"])
    assert result["status"] == "rendered"
    assert result["bytes"] == (tmp_path / "out.svg").stat().st_size
    assert result["density"] <= 1
    assert result["boundingBox"]["width"] > 0


def test_overlaps_unconnected_sections_and_density(tmp_path):
    mods = [
        mod("H: a", "Clock", 0, 0, 2, 1),
        mod("H: b", "AD", 1, 0, 2, 1),
        mod("M: c", "Dice", 5, 0),
        mod("lonely", "Delay", 0, 3),
        mod("note", "Comment", 8, 0, 3, 1, inputs=(), outputs=()),
    ]
    mods[4]["parameters"] = [{"parameterId": "TEXT", "value": "hello <&> world"}]
    host = FakeGrid(mods, [cable("H: a", "H: b"), cable("H: b", "M: c")])
    result = run(host, tmp_path, namespace="H: ")
    assert result["overlapCount"] == 1
    names = {result["overlaps"][0][0]["name"], result["overlaps"][0][1]["name"]}
    assert names == {"H: a", "H: b"}
    assert [m["name"] for m in result["unconnected"]] == ["lonely"]  # comments are never "unconnected"
    sections = {s["prefix"]: s for s in result["sections"]}
    assert set(sections) == {"H: ", "M: "}
    assert sections["H: "]["boundingBox"] == {"x": 0, "y": 0, "width": 3, "height": 1}
    assert sections["H: "]["moduleCount"] == 2
    assert result["boundingBox"] == {"x": 0, "y": 0, "width": 11, "height": 4}
    assert result["density"] == round(len({(0, 0), (1, 0), (2, 0), (5, 0), (0, 3), (8, 0), (9, 0), (10, 0)}) / 44, 3)
    assert result["namespace"] == {"prefix": "H: ", "moduleCount": 2}
    svg = (tmp_path / "out.svg").read_text(encoding="utf-8")
    ET.fromstring(svg)
    assert "hello &lt;&amp;&gt; world" in svg
    assert 'opacity="0.3"' in svg
    # structure first; parameters are fetched only for the Comment
    assert [p.get("moduleIds") for m, p in host.calls if p["includeParameters"]] == [["note"]]


def test_categories_come_from_one_table():
    assert grid_render.category("ADSR") == "envelope"
    assert grid_render.category("Label") == "text"
    assert grid_render.category("Audio Out") == "output"
    assert grid_render.category("Frobnicator") == "other"
    assert grid_render.category("Low-pass 12") == "filter"


def test_show_connections_false_and_scale(tmp_path):
    mods = [mod("a", "Clock", 0, 0), mod("b", "AD", 3, 0)]
    host = FakeGrid(mods, [cable("a", "b")])
    run(host, tmp_path, "on.svg")
    run(host, tmp_path, "off.svg", showConnections=False, scale=2)
    on, off = (ET.parse(tmp_path / n).getroot() for n in ("on.svg", "off.svg"))
    assert len([e for e in on.iter() if e.tag.endswith("path")]) == 1
    assert not [e for e in off.iter() if e.tag.endswith("path")]
    assert float(cast(str, off.get("width"))) == 2 * float(cast(str, on.get("width")))


def test_refuses_overwrite_unless_asked(tmp_path):
    host = FakeGrid([mod("a", "Clock", 0, 0)], [])
    run(host, tmp_path)
    host.calls.clear()
    with pytest.raises(BridgeError) as err:
        run(host, tmp_path)
    assert err.value.error["code"] == "file_exists"
    assert host.calls == []
    assert run(host, tmp_path, overwrite=True)["status"] == "rendered"


def test_incomplete_paging_fails_instead_of_drawing_part(tmp_path):
    host = FakeGrid([mod(f"m{i}", "Add", i * 2, 0) for i in range(5)], [])
    original = host.call

    async def short(method, params, timeout_ms=5000):
        result = await original(method, params, timeout_ms)
        if params["moduleOffset"]:
            result["modules"] = []
        return result

    host.cap = 2
    host.call = short  # type: ignore[method-assign]
    with pytest.raises(BridgeError) as err:
        run(host, tmp_path)
    assert err.value.error["code"] == "graph_incomplete"
    assert not (tmp_path / "out.svg").exists()


def test_validation_and_exposure(tmp_path):
    for bad in (
        {"outPath": "relative.svg"},
        {"outPath": "/tmp/../x.svg"},
        {"outPath": str(tmp_path / "x"), "format": "gif"},
        {"outPath": str(tmp_path / "x"), "scale": 9},
    ):
        with pytest.raises(ValueError, match=re.escape("1 validation error for GridRenderArgs")):
            validate_call("grid.render", {**TARGET, **bad})
    assert "grid.render" in CLIENT_SIDE
    params, _ = parse_arguments(
        "grid.render",
        [
            f"trackId={TRACK}",
            "deviceIndex=0",
            "expectedDeviceName=Poly Grid",
            f"outPath={tmp_path}/a.svg",
            "overwrite=true",
        ],
        None,
        None,
    )
    assert params["overwrite"] is True
    assert params["expectedDeviceName"] == "Poly Grid"


def test_png_uses_converter_or_errors_clearly(tmp_path, monkeypatch):
    host = FakeGrid([mod("a", "Clock", 0, 0)], [])
    monkeypatch.setattr(grid_render.shutil, "which", lambda name: None)
    with pytest.raises(BridgeError) as err:
        run(host, tmp_path, "a.png", format="png")
    assert "rsvg-convert or magick" in err.value.error["message"]
    assert host.calls == []
    monkeypatch.undo()
    if grid_render._converter() is None:
        pytest.skip("no SVG converter installed")
    run(host, tmp_path, "a.png", format="png")
    assert (tmp_path / "a.png").read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"
    assert shutil.which("rsvg-convert") or shutil.which("magick")
