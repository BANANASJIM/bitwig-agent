"""Reusable blocks: expand `blocks` + `instances` of a spec into plain modules and connections."""

from __future__ import annotations

import copy
import re
from typing import Any

from bitwig_agent.grid.problems import SpecError, near

_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)\}")
INT_RANGE = range(-(2**31), 2**31)


def _fill(value: Any, values: dict[str, Any], where: str, problems: list[str]) -> Any:
    """Whole-value ${name} substitution (the value keeps its type) inside a params value, also within {display}/{label}."""
    if isinstance(value, str) and (m := _PLACEHOLDER.fullmatch(value)):
        if m.group(1) in values:
            return values[m.group(1)]
        problems.append(f"{where}: placeholder {value} is not a param of the block.{near(m.group(1), values)}")
    elif isinstance(value, dict):
        return {k: _fill(v, values, where, problems) for k, v in value.items()}
    return value


def _place_module(
    m: dict[str, Any], inst: dict[str, Any], values: dict[str, Any], where: str, problems: list[str]
) -> dict[str, Any]:
    e = copy.deepcopy(m)
    e["name"], e["x"], e["y"] = (
        inst.get("prefix", "") + m["name"],
        m["x"] + inst.get("dx", 0),
        m["y"] + inst.get("dy", 0),
    )
    if e["x"] not in INT_RANGE or e["y"] not in INT_RANGE:
        problems.append(f"{where}: module {m['name']!r} lands outside the 32-bit grid after dx/dy")
    if "params" in e:
        e["params"] = {
            k: _fill(v, values, f"{where}, module {m['name']!r}, param {k}", problems) for k, v in e["params"].items()
        }
    return e


def _prefixed_connection(
    c: dict[str, Any], prefix: str, local: list[str], where: str, problems: list[str]
) -> dict[str, str]:
    ends = {}
    for key in ("from", "to"):
        name, dot, _port = c[key].rpartition(".")
        if not dot or name not in local:
            problems.append(
                f"{where}: block connection {c[key]!r} must be '<local module name>.<PORT_KEY>'.{near(name or c[key], local)}"
            )
        ends[key] = prefix + c[key]
    return ends


def _expand_instance(
    i: int, inst: dict[str, Any], blocks: dict[str, Any], out: dict[str, Any], problems: list[str]
) -> dict[str, Any] | None:
    prefix = inst.get("prefix", "")
    where = f"instance {i} (block {inst['block']!r}, prefix {prefix!r})"
    block = blocks.get(inst["block"])
    if block is None:
        problems.append(f"{where}: unknown block.{near(inst['block'], blocks)} Blocks: {list(blocks)}")
        return None
    defaults, given = block.get("params") or {}, inst.get("args") or {}
    unknown = [k for k in given if k not in defaults]
    if unknown:
        problems.append(f"{where}: unknown args {unknown}; block params {list(defaults)}")
    values = {**defaults, **{k: v for k, v in given.items() if k in defaults}}
    names = []
    for m in block["modules"]:
        placed = _place_module(m, inst, values, where, problems)
        out["modules"].append(placed)
        names.append(placed["name"])
    local = [m["name"] for m in block["modules"]]
    out["connections"] += [
        _prefixed_connection(c, prefix, local, where, problems) for c in block.get("connections") or []
    ]
    return {"block": inst["block"], "prefix": prefix, "modules": names}


def _duplicate_problems(out: dict[str, Any]) -> list[str]:
    problems = []
    for label, items in (
        ("module name", [m["name"] for m in out["modules"]]),
        ("connection target", [c["to"] for c in out["connections"]]),
    ):
        dupes = sorted({n for n in items if items.count(n) > 1})
        if dupes:
            problems.append(f"expanded spec repeats {label}s {dupes}; give instances distinct prefixes")
    return problems


def expand_blocks(args: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Replace blocks+instances by plain modules/connections (names prefixed, positions shifted); no nesting.

    Returns the expanded args and, per instance, {block, prefix, modules}. Runs before any other validation."""
    instances = args.get("instances") or []
    out = {k: v for k, v in args.items() if k not in ("blocks", "instances")}
    out["modules"] = list(args.get("modules") or [])
    out["connections"] = list(args.get("connections") or [])
    if not instances:
        return out, []
    blocks = args.get("blocks") or {}
    problems: list[str] = []
    mapping = []
    for i, inst in enumerate(instances):
        expanded = _expand_instance(i, inst, blocks, out, problems)
        if expanded is not None:
            mapping.append(expanded)
    problems += _duplicate_problems(out)
    if problems:
        raise SpecError(problems)
    return out, mapping
