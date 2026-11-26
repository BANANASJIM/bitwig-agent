"""The one Grid graph reader behind apply, export, render and probe."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from bitwig_agent.errors import BridgeError
from bitwig_agent.grid import read


def rows(count: int) -> list[dict[str, Any]]:
    return [{"instanceId": f"m{i}", "customName": f"n{i}", "x": i, "y": 0} for i in range(count)]


class Paged:
    """A host that returns at most `cap` modules per grid.get page and the same cables on every page."""

    def __init__(self, modules: list[dict[str, Any]], cap: int = 50):
        self.modules, self.cap = modules, cap
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def __call__(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        self.calls.append((method, params))
        ids = params.get("moduleIds")
        chosen = [m for m in self.modules if ids is None or m["instanceId"] in ids]
        page = chosen[params["moduleOffset"] : params["moduleOffset"] + min(params["moduleLimit"], self.cap)]
        missing = [i for i in ids or [] if i not in {m["instanceId"] for m in self.modules}]
        return {
            "modules": page,
            "connections": [
                {"targetModuleId": "m1", "targetPortKey": "IN", "sourceModuleId": "m0", "sourcePortKey": "OUT"}
            ],
            "window": {"total": len(chosen)},
            **({"missingModuleIds": missing} if missing else {}),
        }


def test_read_graph_pages_until_the_window_is_complete_and_dedupes_cables():
    host = Paged(rows(120))
    graph = asyncio.run(read.read_graph(host))
    assert [r["instanceId"] for r in graph.rows] == [f"m{i}" for i in range(120)]
    assert len(graph.cables) == 1
    assert [p["moduleOffset"] for _, p in host.calls] == [0, 50, 100]
    assert {p["moduleLimit"] for _, p in host.calls} == {read.PAGE}


def test_read_graph_with_parameters_uses_the_smaller_page():
    host = Paged(rows(3))
    asyncio.run(read.read_graph(host, params=True))
    assert host.calls[0][1] == {"moduleOffset": 0, "moduleLimit": read.PARAM_PAGE, "includeParameters": True}


def test_read_graph_refuses_to_return_part_of_a_device():
    async def stalled(method, params):
        return {"modules": [] if params["moduleOffset"] else rows(2), "window": {"total": 5}}

    with pytest.raises(BridgeError) as err:
        asyncio.run(read.read_graph(stalled))
    assert err.value.error["reason"] == "not_ready"
    assert "2 of 5" in err.value.error["message"]


@pytest.mark.parametrize("page", [None, [], {"modules": None}, {"status": "observed"}])
def test_read_graph_rejects_an_unusable_page(page):
    async def broken(method, params):
        return page

    with pytest.raises(BridgeError) as err:
        asyncio.run(read.read_graph(broken))
    assert err.value.error["reason"] == "internal"


def test_read_params_chunks_ids_and_can_require_every_module():
    host = Paged(rows(70), cap=128)
    ids = [f"m{i}" for i in range(70)]
    got = asyncio.run(read.read_params(host, ids, full=True))
    assert set(got) == set(ids)
    assert [len(p["moduleIds"]) for _, p in host.calls] == [32, 32, 6]
    assert all(p["full"] and p["includeParameters"] for _, p in host.calls)
    assert asyncio.run(read.read_params(host, ["m1", "gone"])).keys() == {"m1"}
    with pytest.raises(BridgeError) as err:
        asyncio.run(read.read_params(host, ["m1", "gone"], require=True))
    assert err.value.error["details"]["missingModuleIds"] == ["gone"]


def test_read_params_without_ids_makes_no_call():
    host = Paged(rows(3))
    assert asyncio.run(read.read_params(host, [])) == {}
    assert host.calls == []


def test_read_catalog_pages_until_complete():
    entries = [{"name": f"T{i}", "typeId": f"id{i}"} for i in range(5)]

    async def catalog(method, params):
        assert method == "grid.catalog"
        got = entries[params["offset"] : params["offset"] + 2]
        return {"entries": got, "window": {"complete": params["offset"] + len(got) >= len(entries)}}

    assert asyncio.run(read.read_catalog(catalog)) == entries


def test_host_call_addresses_the_device_on_every_method():
    class Client:
        async def call(self, method, params, timeout_ms):
            return {"method": method, "params": params, "timeout": timeout_ms}

    call = read.host_call(Client(), {"trackId": "t"}, 1234)
    assert asyncio.run(call("grid.get", {"moduleOffset": 0})) == {
        "method": "grid.get",
        "params": {"trackId": "t", "moduleOffset": 0},
        "timeout": 1234,
    }


def test_module_names_are_the_names_the_host_gives_its_refs():
    def row(iid, custom, ref_name):
        return {
            "instanceId": iid,
            "customName": custom,
            "ref": {"kind": "gridModule", "id": iid, "name": ref_name},
        }

    modules = [
        row("a", "one", "one"),
        row("b", "dup", "_Add_1_0"),
        row("c", "dup", "_Add_2_0"),
        row("d", "", "_Mul_3_4"),
    ]
    names, generated, duplicates = read.module_names(modules)
    assert names == {"a": "one", "b": "_Add_1_0", "c": "_Add_2_0", "d": "_Mul_3_4"}
    assert generated == ["_Add_1_0", "_Add_2_0", "_Mul_3_4"]
    assert duplicates == ["dup"]
    assert read.label_of(modules[3]) == "_Mul_3_4"
