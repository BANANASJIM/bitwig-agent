"""The one reader of a Grid device: every Grid read and catalog page, validated, plus the names modules go by."""

from __future__ import annotations

from collections import Counter
from collections.abc import Callable, Coroutine
from typing import Any, NamedTuple

from bitwig_agent.errors import bridge_error

GridCall = Callable[[str, dict[str, Any]], Coroutine[Any, Any, dict[str, Any]]]
PAGE, PARAM_PAGE, IDS_PER_READ, CATALOG_PAGE = 128, 64, 32, 256


class Graph(NamedTuple):
    rows: list[dict[str, Any]]
    cables: list[dict[str, Any]]


def device_target(args: dict[str, Any]) -> dict[str, Any]:
    """The argument that addresses one Grid device: its device ref."""
    return {"target": args["target"]}


def host_call(client: Any, target: dict[str, Any], timeout_ms: int) -> GridCall:
    """A GridCall that addresses `target` on every method."""

    async def call(method: str, params: dict[str, Any]) -> dict[str, Any]:
        result: dict[str, Any] = await client.call(method, {**target, **params}, timeout_ms)
        return result

    return call


async def _page(call: GridCall, request: dict[str, Any]) -> dict[str, Any]:
    page = await call("grid.get", request)
    if not isinstance(page, dict) or not isinstance(page.get("modules"), list):
        raise bridge_error(
            "internal",
            "the Grid read returned an unusable result",
            **(page if isinstance(page, dict) else {}),
        )
    return page


async def read_graph(call: GridCall, *, params: bool = False) -> Graph:
    """Every module and cable of the device (all pages); fails rather than return part of it."""
    rows: list[dict[str, Any]] = []
    cables: dict[tuple[Any, ...], dict[str, Any]] = {}
    total, limit = None, PARAM_PAGE if params else PAGE
    while total is None or len(rows) < total:
        page = await _page(call, {"moduleOffset": len(rows), "moduleLimit": limit, "includeParameters": params})
        rows += page["modules"]
        for c in page.get("connections", []):
            cables[(c["targetModuleId"], c["targetPortKey"])] = c
        total = (page.get("window") or {}).get("total", len(rows))
        if not page["modules"] and len(rows) < total:
            raise bridge_error("not_ready", f"the Grid read returned {len(rows)} of {total} modules")
    return Graph(rows, list(cables.values()))


async def read_params(
    call: GridCall, ids: list[str], *, full: bool = False, require: bool = False
) -> dict[str, dict[str, Any]]:
    """Rows with parameters for the given module ids, by instanceId; `require` fails when the host lost one."""
    rows: dict[str, dict[str, Any]] = {}
    for i in range(0, len(ids), IDS_PER_READ):
        request: dict[str, Any] = {
            "moduleOffset": 0,
            "moduleLimit": PAGE,
            "includeParameters": True,
            "moduleIds": ids[i : i + IDS_PER_READ],
        }
        if full:
            request["full"] = True
        page = await _page(call, request)
        if require and page.get("missingModuleIds"):
            raise bridge_error(
                "target_not_found",
                "the Grid read did not return every requested module",
                missingModuleIds=page["missingModuleIds"],
            )
        rows.update({r["instanceId"]: r for r in page["modules"]})
    return rows


async def read_catalog(call: GridCall) -> list[dict[str, Any]]:
    """Every module type the host's catalog lists (all pages)."""
    entries: list[dict[str, Any]] = []
    while True:
        page = await call("grid.catalog", {"offset": len(entries), "limit": CATALOG_PAGE})
        got = page.get("entries", [])
        entries += got
        if (page.get("window") or {}).get("complete", True) or not got:
            return entries


def generated_name(row: dict[str, Any]) -> str:
    """Stable stand-in name of an unnamed live module, as the host names its ref (grid.export writes it, grid.apply matches it)."""
    return str(row["ref"]["name"])


def label_of(row: dict[str, Any]) -> str:
    return row.get("customName") or generated_name(row)


def module_names(rows: list[dict[str, Any]]) -> tuple[dict[str, str], list[str], list[str]]:
    """instanceId -> the name the host gives its ref (customName, else generated), the generated names, and customNames that are not unique."""
    counts = Counter(r["customName"] for r in rows if r.get("customName"))
    names = {r["instanceId"]: str(r["ref"]["name"]) for r in rows}
    generated = [names[r["instanceId"]] for r in rows if not r.get("customName") or counts[r["customName"]] > 1]
    return names, generated, sorted(n for n, c in counts.items() if c > 1)
