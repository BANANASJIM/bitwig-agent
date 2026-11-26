"""Cable edits planned against the live graph, and port checks against live rows."""

from __future__ import annotations

from collections.abc import Collection
from dataclasses import dataclass
from typing import Any

from bitwig_agent.grid.problems import SpecError, hint, preview
from bitwig_agent.grid.read import label_of
from bitwig_agent.grid.spec import Spec

Incoming = dict[tuple[str, str], tuple[str, str]]  # (target module id, input key) -> (source module id, output key)
HELD_NOTE = "prune kept {n} cable(s) into modules outside namespace {ns!r}; allowOutsideDisconnect:true cuts them: {l}"
CUT_NOTE = "allowOutsideDisconnect: disconnecting {n} input(s) of modules outside namespace {ns!r}: {l}"


@dataclass
class WiringPlan:
    edits: list[dict[str, Any]]
    matched: int
    notes: list[str]


def _source_text(source: tuple[str, str], name_of: dict[str, str]) -> str:
    return f"{name_of.get(source[0], source[0])}.{source[1]}"


def _declared_edits(
    spec: Spec, rows: dict[str, dict[str, Any]], incoming: Incoming, name_of: dict[str, str]
) -> tuple[list[dict[str, Any]], int, set[tuple[str, str]]]:
    edits: list[dict[str, Any]] = []
    wanted: set[tuple[str, str]] = set()
    matched = 0
    for src, sport, dst, dport in spec.edges:
        drow, srow = rows.get(dst), rows.get(src)
        if drow is not None:
            wanted.add((drow["instanceId"], dport))
        current = incoming.get((drow["instanceId"], dport)) if drow else None
        desired = (srow["instanceId"], sport) if srow else None
        if drow and srow and current == desired:
            matched += 1
            continue
        edit: dict[str, Any] = {
            "from": f"{src}.{sport}",
            "to": f"{dst}.{dport}",
            "previous": _source_text(current, name_of) if current else None,
        }
        if drow and srow:
            edit["write"] = {
                "targetModuleId": drow["instanceId"],
                "targetPortKey": dport,
                "expectedSource": {"moduleId": current[0], "portKey": current[1]} if current else None,
                "source": {"moduleId": srow["instanceId"], "portKey": sport},
            }
        edits.append(edit)
    return edits, matched, wanted


def _cut(name: str, row: dict[str, Any], key: str, current: tuple[str, str], name_of: dict[str, str]) -> dict[str, Any]:
    return {
        "from": None,
        "to": f"{name}.{key}",
        "previous": _source_text(current, name_of),
        "write": {
            "targetModuleId": row["instanceId"],
            "targetPortKey": key,
            "expectedSource": {"moduleId": current[0], "portKey": current[1]},
            "source": None,
        },
    }


@dataclass
class _Pruned:
    edits: list[dict[str, Any]]
    held: list[str]  # kept: the module is outside the namespace
    cut: list[str]  # cut although outside the namespace (allowOutsideDisconnect)
    undeclared: list[str]  # cut by pruneAll


def _prune(
    spec: Spec,
    rows: dict[str, dict[str, Any]],
    incoming: Incoming,
    wanted: set[tuple[str, str]],
    name_of: dict[str, str],
) -> _Pruned:
    pruned = _Pruned([], [], [], [])
    for name, row in rows.items():
        if name in spec.external:  # referenced only: never pruned
            continue
        outside = bool(spec.namespace and not name.startswith(spec.namespace)) and not spec.prune_all
        for port in row.get("inputs", []):
            key = (row["instanceId"], port["key"])
            if key not in incoming or key in wanted:
                continue
            end = f"{name}.{port['key']} (from {_source_text(incoming[key], name_of)})"
            if outside and not spec.outside_ok:
                pruned.held.append(end)
                continue
            if outside:
                pruned.cut.append(end)
            elif spec.prune_all:
                pruned.undeclared.append(end)
            pruned.edits.append(_cut(name, row, port["key"], incoming[key], name_of))
    return pruned


def _prune_notes(spec: Spec, pruned: _Pruned) -> list[str]:
    notes = [
        text.format(n=len(items), ns=spec.namespace, l=preview(items))
        for items, text in ((pruned.held, HELD_NOTE), (pruned.cut, CUT_NOTE))
        if items
    ]
    if pruned.undeclared:
        notes.append(
            f"pruneAll: disconnecting {len(pruned.undeclared)} input(s) the spec does not declare: "
            + preview(pruned.undeclared)
        )
    return notes


def plan_connections(
    spec: Spec,
    rows: dict[str, dict[str, Any]],
    conns: list[dict[str, Any]],
    gone_ids: Collection[str],
    known_rows: list[dict[str, Any]],
) -> WiringPlan:
    """Edits (as host entries + readable fields) for rows keyed by module name; modules absent from rows are new."""
    incoming: Incoming = {
        (c["targetModuleId"], c["targetPortKey"]): (c["sourceModuleId"], c["sourcePortKey"])
        for c in conns
        if c["sourceModuleId"] not in gone_ids and c["targetModuleId"] not in gone_ids
    }
    name_of = {r["instanceId"]: label_of(r) for r in [*known_rows, *rows.values()]}
    edits, matched, wanted = _declared_edits(spec, rows, incoming, name_of)
    notes: list[str] = []
    if spec.prune:
        pruned = _prune(spec, rows, incoming, wanted, name_of)
        edits += pruned.edits
        notes = _prune_notes(spec, pruned)
    return WiringPlan(edits, matched, notes)


def check_live_ports(spec: Spec, rows: dict[str, dict[str, Any]]) -> None:
    """Port validation against live rows, needed when no bundled schema covers the host version."""
    problems = []
    for src, sport, dst, dport in spec.edges:
        for name, port, want in ((src, sport, "outputs"), (dst, dport, "inputs")):
            if name in rows and port not in {p["key"] for p in rows[name].get(want, [])}:
                problems.append(
                    f"{name!r} has no {want[:-1]} {port!r}.{hint(port, [p['key'] for p in rows[name].get(want, [])])}"
                )
    if problems:
        raise SpecError(problems)
