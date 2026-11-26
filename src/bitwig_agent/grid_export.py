"""Client-side `grid.export` / `grid.graph`: read a live Grid device and name everything (no UUID joins).

`grid.export` returns a `grid.apply` spec of the device: applying it back to the same device is `unchanged`.
Read-only toward Bitwig; the only side effect is the optional file at outPath.
"""

from __future__ import annotations

import json
import math
import os
from collections import Counter
from typing import Any

from bitwig_agent.errors import BridgeError, bridge_error
from bitwig_agent.grid_apply import bundled_schema, generated_name

PAGE, PARAM_PAGE = 128, 64
VOICING_FIELDS = (
    "voices",
    "voiceStacking",
    "monoMode",
    "retriggerMode",
    "notePriority",
    "legatoGlide",
    "stealSameKey",
    "allowOverlappingNotes",
)
WRITABLE = ("float", "integer", "boolean", "enum", "string", "steps")


async def read_all(
    client: Any, target: dict[str, Any], timeout_ms: int, params: bool
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Every module and cable of the device (all pages)."""
    rows: list[dict[str, Any]] = []
    cables: dict[tuple[Any, ...], dict[str, Any]] = {}
    total, step = None, PARAM_PAGE if params else PAGE
    while total is None or len(rows) < total:
        page = await client.call(
            "grid.get",
            {**target, "moduleOffset": len(rows), "moduleLimit": step, "includeParameters": params},
            timeout_ms,
        )
        got = page.get("modules") if isinstance(page, dict) else None
        if not isinstance(got, list):
            raise bridge_error("not_applied", "grid.get returned an unusable result")
        rows += got
        for c in page.get("connections", []):
            cables[(c["targetModuleId"], c["targetPortKey"])] = c
        total = (page.get("window") or {}).get("total", len(rows))
        if not got and len(rows) < total:
            raise bridge_error("graph_incomplete", f"grid.get returned {len(rows)} of {total} modules")
    return rows, list(cables.values())


def module_names(rows: list[dict[str, Any]]) -> tuple[dict[str, str], list[str], list[str]]:
    """instanceId -> name (customName, else generated), the generated names, and customNames that are not unique."""
    counts = Counter(r["customName"] for r in rows if r.get("customName"))
    names, generated = {}, []
    for r in rows:
        n = r.get("customName")
        if n and counts[n] == 1:
            names[r["instanceId"]] = n
        else:
            names[r["instanceId"]] = generated_name(r)
            generated.append(names[r["instanceId"]])
    return names, generated, sorted(n for n, c in counts.items() if c > 1)


def _same(a: Any, b: Any) -> bool:
    if type(a) in (int, float) and type(b) in (int, float) and type(a) is not bool and type(b) is not bool:
        return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-12)
    return bool(a == b)


def export_params(row: dict[str, Any], mode: str, warnings: list[str], name: str) -> dict[str, Any]:
    """A module's params as a grid.apply `params` map: raw values, enum as {label}, step arrays as {steps}."""
    info = bundled_schema()["modules"].get(row.get("typeName") or "")
    defaults = {p["parameterId"]: p.get("default") for p in info["parameters"]} if info else {}
    out: dict[str, Any] = {}
    for p in row.get("parameters") or []:
        pid, kind, value = p.get("parameterId"), p.get("kind"), p.get("value")
        if not p.get("supported") or pid is None or kind not in WRITABLE:
            continue
        if kind == "steps":
            values = p.get("values")
            if not isinstance(values, list) or not values:
                continue
            if mode == "changed" and not any(values):
                continue
            out[pid] = {"steps": values}
            continue
        if isinstance(value, float) and not math.isfinite(value):
            warnings.append(f"{name}.{pid}: non-finite value skipped")
            continue
        if mode == "changed" and pid in defaults and _same(defaults[pid], value):
            continue
        if mode == "changed" and pid not in defaults and kind == "string" and value == "":
            continue
        if kind == "enum":
            labels = [o["label"] for o in p.get("options") or []]
            hit = next((o["label"] for o in p.get("options") or [] if o["value"] == value), None)
            out[pid] = {"label": hit} if hit is not None and labels.count(hit) == 1 else value
        else:
            out[pid] = value
    return out


def build(rows: list[dict[str, Any]], cables: list[dict[str, Any]], mode: str, namespace: str | None) -> dict[str, Any]:
    """Named modules, cables and the outside names they reference."""
    names, generated, duplicates = module_names(rows)
    warnings: list[str] = []
    if duplicates:
        warnings.append(
            f"customNames {duplicates} are not unique; those modules got generated names, rename them before applying"
        )
    if generated:
        warnings.append(
            f"{len(generated)} module(s) have no (unique) customName and carry a generated name; grid.apply matches unnamed modules by it"
        )
    inside = {r["instanceId"] for r in rows if not namespace or names[r["instanceId"]].startswith(namespace)}
    modules = []
    for r in sorted(
        (r for r in rows if r["instanceId"] in inside), key=lambda r: (r["y"], r["x"], names[r["instanceId"]])
    ):
        m: dict[str, Any] = {
            "name": names[r["instanceId"]],
            "type": r.get("typeName") or r["typeId"],
            "x": r["x"],
            "y": r["y"],
        }
        if mode != "none":
            params = export_params(r, mode, warnings, m["name"])
            if params:
                m["params"] = params
        modules.append(m)
    external: dict[str, None] = {}
    connections = []
    for c in sorted(cables, key=lambda c: (names.get(c["targetModuleId"], ""), c["targetPortKey"])):
        src, dst = c["sourceModuleId"], c["targetModuleId"]
        if src not in names or dst not in names or (src not in inside and dst not in inside):
            continue
        for end in (src, dst):
            if end not in inside:
                external[names[end]] = None
        connections.append({"from": f"{names[src]}.{c['sourcePortKey']}", "to": f"{names[dst]}.{c['targetPortKey']}"})
    return {
        "modules": modules,
        "connections": connections,
        "external": list(external),
        "unnamed": generated,
        "warnings": warnings,
        "sizes": {names[r["instanceId"]]: (r.get("width"), r.get("height")) for r in rows if r["instanceId"] in inside},
    }


async def read_voicing(
    client: Any, target: dict[str, Any], timeout_ms: int, warnings: list[str]
) -> dict[str, Any] | None:
    try:
        got = await client.call("grid.voicing.get", target, timeout_ms)
    except BridgeError as exc:
        warnings.append(f"voicing not exported: {exc.error.get('code')}")
        return None
    live = got.get("voicing") if isinstance(got, dict) and got.get("supported") else None
    return {k: live[k] for k in VOICING_FIELDS if k in live} if isinstance(live, dict) else None


def _target(args: dict[str, Any]) -> dict[str, Any]:
    return {k: args[k] for k in ("trackId", "deviceIndex", "expectedDeviceName")}


async def export(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    path, overwrite = args.get("outPath"), args.get("overwrite", False)
    if path is not None:
        if not overwrite and os.path.exists(path):
            raise bridge_error("file_exists", f"{path} exists; pass overwrite=true to replace it")
        if not os.path.isdir(os.path.dirname(path)):
            raise bridge_error("invalid_params", f"Parent directory does not exist: {os.path.dirname(path)}")
    mode, namespace, target = args.get("includeParams", "changed"), args.get("namespace"), _target(args)
    rows, cables = await read_all(client, target, timeout_ms, mode != "none")
    built = build(rows, cables, mode, namespace)
    spec: dict[str, Any] = {"target": target, "modules": built["modules"], "connections": built["connections"]}
    if built["external"]:
        spec["external"] = built["external"]
    if namespace:
        spec["namespace"] = namespace
    voicing = None if namespace else await read_voicing(client, target, timeout_ms, built["warnings"])
    if voicing:
        spec["voicing"] = voicing
    if not spec["modules"]:
        built["warnings"].append("no modules exported; an empty modules list is not a valid grid.apply spec")
    out = {
        "status": "exported",
        "moduleCount": len(spec["modules"]),
        "connectionCount": len(spec["connections"]),
        "includeParams": mode,
        **({"unnamed": built["unnamed"]} if built["unnamed"] else {}),
        **({"warnings": built["warnings"]} if built["warnings"] else {}),
    }
    if path is None:
        return {**out, "spec": spec}
    data = json.dumps(spec, indent=1, ensure_ascii=False).encode("utf-8")
    try:
        with open(path, "wb" if overwrite else "xb") as f:
            f.write(data)
    except OSError as exc:
        raise bridge_error("write_failed", f"Cannot write {path}: {exc}") from exc
    return {**out, "outPath": path, "bytes": len(data)}


async def graph(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    """Modules and cables by name (with sizes), for reading a patch without UUID joins."""
    mode, namespace = args.get("includeParams", "none"), args.get("namespace")
    rows, cables = await read_all(client, _target(args), timeout_ms, mode != "none")
    built = build(rows, cables, mode, namespace)
    generated = set(built["unnamed"])
    modules = [
        {**m, "size": list(built["sizes"][m["name"]]), **({"unnamed": True} if m["name"] in generated else {})}
        for m in built["modules"]
    ]
    return {
        "status": "observed",
        "moduleCount": len(modules),
        "connectionCount": len(built["connections"]),
        "modules": modules,
        "connections": built["connections"],
        **({"external": built["external"]} if built["external"] else {}),
        **({"warnings": built["warnings"]} if built["warnings"] else {}),
    }
