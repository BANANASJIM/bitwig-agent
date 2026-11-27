"""Client-side `grid.export`: read a live Grid device and name everything (no UUID joins).

`grid.export` returns a `grid.apply` spec of the device: applying it back to the same device is `already_matched`.
Read-only toward Bitwig; it writes no file.
"""

from __future__ import annotations

import math
from typing import Any

from bitwig_agent.errors import BridgeError
from bitwig_agent.grid.bundled import bundled_schema
from bitwig_agent.grid.read import device_target, host_call, module_names, read_graph

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


def _same(a: Any, b: Any) -> bool:
    if type(a) in (int, float) and type(b) in (int, float) and type(a) is not bool and type(b) is not bool:
        return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-12)
    return bool(a == b)


def export_params(row: dict[str, Any], mode: str, warnings: list[str], name: str, host: str | None) -> dict[str, Any]:
    """A module's params as a grid.apply `params` map: raw values, enum as {label}, step arrays as {steps}."""
    info = bundled_schema(host)["modules"].get(row.get("typeName") or "")
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


def build(
    rows: list[dict[str, Any]], cables: list[dict[str, Any]], mode: str, namespace: str | None, host: str | None = None
) -> dict[str, Any]:
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
            params = export_params(r, mode, warnings, m["name"], host)
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
        "refs": {names[r["instanceId"]]: r["ref"] for r in rows if r["instanceId"] in inside},
        "sizes": {names[r["instanceId"]]: [r.get("width"), r.get("height")] for r in rows if r["instanceId"] in inside},
    }


async def read_voicing(
    client: Any, target: dict[str, Any], timeout_ms: int, warnings: list[str]
) -> dict[str, Any] | None:
    try:
        got = await client.call("grid.voicing.get", target, timeout_ms)
    except BridgeError as exc:
        warnings.append(f"voicing not exported: {exc.error['reason']}")
        return None
    live = got.get("voicing") if isinstance(got, dict) and got.get("supported") else None
    return {k: live[k] for k in VOICING_FIELDS if k in live} if isinstance(live, dict) else None


async def export(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    mode, namespace, target = args.get("includeParams", "changed"), args.get("namespace"), device_target(args)
    rows, cables = await read_graph(host_call(client, target, timeout_ms), params=mode != "none")
    host = (await client.call("status", {}, timeout_ms))["hostVersion"] if mode == "changed" else None
    built = build(rows, cables, mode, namespace, host)
    spec: dict[str, Any] = {"target": args["target"], "modules": built["modules"], "connections": built["connections"]}
    if built["external"]:
        spec["external"] = built["external"]
    if namespace:
        spec["namespace"] = namespace
    voicing = None if namespace else await read_voicing(client, target, timeout_ms, built["warnings"])
    if voicing:
        spec["voicing"] = voicing
    if not spec["modules"]:
        built["warnings"].append("no modules exported; an empty modules list is not a valid grid.apply spec")
    return {
        "moduleCount": len(spec["modules"]),
        "connectionCount": len(spec["connections"]),
        "includeParams": mode,
        "spec": spec,
        "refs": built["refs"],
        "sizes": built["sizes"],
        **({"unnamed": built["unnamed"]} if built["unnamed"] else {}),
        **({"warnings": built["warnings"]} if built["warnings"] else {}),
    }
