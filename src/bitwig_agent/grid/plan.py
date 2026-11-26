"""What a spec means for one live device: which modules exist, change, go and arrive, and whether the host allows it."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, NamedTuple

from bitwig_agent.grid.layout import Cell, Footprints, footprints, order_moves
from bitwig_agent.grid.params import plan_params
from bitwig_agent.grid.problems import PREVIEW_ITEMS, SpecError, near, preview
from bitwig_agent.grid.read import Graph, label_of
from bitwig_agent.grid.spec import Edge, Spec
from bitwig_agent.grid.wiring import check_live_ports

# Upper bound only to fail early; the host enforces its own (version-dependent) device module cap.
MAX_DEVICE_MODULES = 1024
LIMIT_SECTIONS = ("moduleInsert", "moduleMove", "moduleRename")
ADMISSION_SECTIONS = (*LIMIT_SECTIONS, "moduleDelete")
ModuleParams = dict[str, tuple[list[dict[str, Any]], int]]


class VoicingPlan(NamedTuple):
    changes: dict[str, Any]
    expected: dict[str, Any]
    matched: int


@dataclass
class Plan:
    existing_by_name: dict[str, dict[str, Any]]
    external_rows: dict[str, dict[str, Any]]
    deletes: list[dict[str, Any]]
    replaced_names: list[str]
    dropped_cables: list[str]
    inserts: list[str]
    ids: dict[str, str]
    to_name_first: list[str]
    current: dict[str, Cell]
    pre_moves: list[tuple[str, Cell]]
    layout: Footprints
    gone_ids: set[str]
    notes: list[str]
    matched_modules: int
    matched_positions: int
    graph: Graph
    params: ModuleParams = field(default_factory=dict)
    voicing: VoicingPlan = field(default_factory=lambda: VoicingPlan({}, {}, 0))


def plan_voicing(want: dict[str, Any], got: dict[str, Any]) -> VoicingPlan:
    """Plan only the differing voicing fields; refuse a device without a voicing model."""
    if not got.get("supported") or not isinstance(got.get("voicing"), dict):
        raise SpecError(
            [
                f"voicing: this Grid device has no voicing model (only Poly Grid does): {got.get('reason') or 'unsupported'}"
            ]
        )
    live = got["voicing"]
    problems = [f"voicing.{k}: the host did not report this field" for k in want if k not in live]
    if problems:
        raise SpecError(problems)
    changes = {k: v for k, v in want.items() if live[k] != v}
    return VoicingPlan(changes, {k: live[k] for k in changes}, len(want) - len(changes))


def unreadable_names(caps: Any, rows: list[dict[str, Any]]) -> list[str]:
    bad = [r for r in rows if not isinstance(r.get("customName"), str)]
    problems = (
        [f"{len(bad)} module(s) report no readable customName; refusing to write because identity would be unknown"]
        if bad
        else []
    )
    names = caps.get("moduleNames")
    if isinstance(names, dict) and names.get("available") is False:
        problems.append("the host reports module names as unavailable; refusing to write")
    return problems


def _sections(caps: dict[str, Any], keys: tuple[str, ...]) -> dict[str, dict[str, Any]]:
    return {k: caps[k] for k in keys if isinstance(caps.get(k), dict)}


def module_limit(caps: dict[str, Any]) -> int:
    """Smallest maximumModules the host reports for insert/move/rename, else the early-failure constant."""
    found = [
        s["maximumModules"] for s in _sections(caps, LIMIT_SECTIONS).values() if type(s.get("maximumModules")) is int
    ]
    return int(min([*found, MAX_DEVICE_MODULES]))


def admission(caps: dict[str, Any], track_id: Any, inserting: bool, deleting: bool) -> list[str]:
    sections = _sections(caps, ADMISSION_SECTIONS)
    problems = []
    if caps.get("topologyAdmissionBlocked"):
        problems.append("the host reports topologyAdmissionBlocked; a native topology lifetime is pending")
    if any(d.get("trackId") == track_id for d in (caps.get("topologyQuarantine") or {}).get("devices") or []):
        problems.append(
            "a Grid device on this track is quarantined (topologyQuarantine.devices); inspect it with grid.get, "
            "then release it with ack <id> (status lists the holds)"
        )
    if inserting and any(sections.get(k, {}).get("available") is False for k in ("moduleInsert", "moduleRename")):
        problems.append("the host reports module insert/rename as unavailable")
    if deleting and sections.get("moduleDelete", {}).get("available") is False:
        problems.append("the host reports module delete as unavailable")
    return problems


def _external_problem(
    name: str, found: list[dict[str, Any]], rows: list[dict[str, Any]], by_custom: dict[str, list[dict[str, Any]]]
) -> str:
    if found:
        return f"external module {name!r} matches {len(found)} modules; names must be unique"
    generated = [
        label_of(r)
        for r in rows
        if not r["customName"]
        and name.casefold() in (str(r.get("typeName") or "").casefold(), str(r.get("name") or "").casefold())
    ]
    point = (
        f" Unnamed {name!r} modules are referenced by their generated name (the grid.graph/grid.export name): {', '.join(map(repr, generated[:5]))}."
        if generated
        else near(name, by_custom)
    )
    return f"external module {name!r} is not in the device.{point}"


def _external_port_problems(edges: list[Edge], ext_rows: dict[str, dict[str, Any]]) -> list[str]:
    problems = []
    for src, sport, dst, dport in edges:
        for name, port, want in ((src, sport, "outputs"), (dst, dport, "inputs")):
            row = ext_rows.get(name)
            keys = [p["key"] for p in (row or {}).get(want, [])]
            if row is not None and port not in keys:
                problems.append(
                    f"external module {name!r} ({row.get('typeName') or row.get('typeId')}) has no {want[:-1]} port {port!r}; valid {want}: {', '.join(keys) or 'none'}"
                )
    return problems


class _Matching(NamedTuple):
    existing: dict[str, dict[str, Any]]
    replaced: list[dict[str, Any]]
    ext_rows: dict[str, dict[str, Any]]
    problems: list[str]


def _match_modules(spec: Spec, by_custom: dict[str, list[dict[str, Any]]]) -> _Matching:
    existing: dict[str, dict[str, Any]] = {}
    replaced: list[dict[str, Any]] = []
    problems: list[str] = []
    for name in spec.names:
        found = by_custom.get(name, [])
        if len(found) > 1:
            problems.append(f"{len(found)} modules in the device are already named {name!r}; names must be unique")
        elif found:
            live_type = found[0].get("typeId")
            if isinstance(live_type, str) and live_type.lower() == spec.resolved[name].type_id:
                existing[name] = found[0]
            elif spec.by_spec[name].get("replace", spec.replace):
                replaced.append(found[0])
            else:
                problems.append(
                    f"{name!r} exists as {found[0].get('typeName') or live_type} but the spec wants {spec.resolved[name].type_name}"
                )
    return _Matching(existing, replaced, {}, problems)


def _match_device(spec: Spec, rows: list[dict[str, Any]]) -> _Matching:
    by_custom: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        by_custom.setdefault(label_of(r), []).append(r)  # unnamed: matched by its generated name
    matched = _match_modules(spec, by_custom)
    ext_rows: dict[str, dict[str, Any]] = {}
    problems = matched.problems
    for name in spec.external:
        found = by_custom.get(name, [])
        if len(found) == 1:
            ext_rows[name] = found[0]
        else:
            problems.append(_external_problem(name, found, rows, by_custom))
    problems += _external_port_problems(spec.edges, ext_rows)  # ports of externals are known only from live rows
    return _Matching(matched.existing, matched.replaced, ext_rows, problems)


def _plan_deletes(
    spec: Spec, rows: list[dict[str, Any]], replaced: list[dict[str, Any]]
) -> tuple[list[dict[str, Any]], list[str]]:
    deletes, notes = list(replaced), []
    if spec.prune_all:
        stale = [r for r in rows if label_of(r) not in spec.by_spec and label_of(r) not in spec.external]
        deletes += stale
        if stale:
            notes.append(
                f"pruneAll: deleting {len(stale)} module(s) the spec does not declare: "
                + preview([label_of(r) for r in stale], ", ")
            )
        if replaced:
            changed = [
                f"{label_of(r)} ({r.get('typeName') or r.get('typeId')} -> {spec.resolved[label_of(r)].type_name})"
                for r in replaced[:PREVIEW_ITEMS]
            ]
            notes.append(
                f"pruneAll: replacing {len(replaced)} module(s) whose type changed: "
                + ", ".join(changed)
                + (" ..." if len(replaced) > PREVIEW_ITEMS else "")
            )
    elif spec.prune and spec.namespace:
        deletes += [
            r
            for r in rows
            if r["customName"].startswith(spec.namespace)
            and r["customName"] not in spec.by_spec
            and r["customName"] not in spec.external
        ]
    return deletes, notes


def _dropped_cables(
    edges: list[Edge], replaced: list[dict[str, Any]], rows: list[dict[str, Any]], conns: list[dict[str, Any]]
) -> list[str]:
    """Live cables on replaced modules that the spec does not re-declare (deleting the module removes them)."""
    gone = {r["instanceId"] for r in replaced}
    label = {r["instanceId"]: r.get("customName") or r.get("name") for r in rows}
    declared: set[tuple[Any, ...]] = set(edges)
    out = []
    for c in conns:
        if c["sourceModuleId"] in gone or c["targetModuleId"] in gone:
            s, t = label.get(c["sourceModuleId"]), label.get(c["targetModuleId"])
            if (s, c["sourcePortKey"], t, c["targetPortKey"]) not in declared:
                out.append(f"{s}.{c['sourcePortKey']} -> {t}.{c['targetPortKey']}")
    return out


def _capacity_problems(caps: dict[str, Any], total: int) -> list[str]:
    limit = module_limit(caps)
    return [f"the device would hold {total} modules; the host limit is {limit}"] if total > limit else []


def build_plan(spec: Spec, caps: dict[str, Any], graph: Graph, dry: bool) -> Plan:
    """Match the spec to the live graph; raise SpecError with every reason it cannot be applied."""
    rows, conns = graph
    match = _match_device(spec, rows)
    deletes, notes = _plan_deletes(spec, rows, match.replaced)
    dropped = _dropped_cables(spec.edges, match.replaced, rows, conns)
    if dropped:
        notes.append(f"replace drops {len(dropped)} cable(s) the spec does not declare: {preview(dropped)}")
    existing = match.existing
    inserts = [n for n in spec.names if n not in existing]
    capacity = _capacity_problems(caps, len(rows) - len(deletes) + len(inserts))
    if not dry:
        capacity += admission(caps, spec.target.get("trackId"), bool(inserts), bool(deletes))
    layout = footprints(spec, existing, rows, deletes)
    problems = [*match.problems, *capacity, *layout.problems]
    if problems:
        raise SpecError(problems)
    if spec.live_schema:
        check_live_ports(spec, {**existing, **match.ext_rows})
    current = {n: (r["x"], r["y"]) for n, r in existing.items()}
    target = {n: spec.position(n) for n in existing}
    return Plan(
        existing_by_name=existing,
        external_rows=match.ext_rows,
        deletes=deletes,
        replaced_names=[label_of(r) for r in match.replaced],
        dropped_cables=dropped,
        inserts=inserts,
        ids={n: r["instanceId"] for n, r in existing.items()},
        # An unnamed live module matched by its generated name that has to move is named first, or the move would change that name and the next run would see a stranger.
        to_name_first=[n for n, r in existing.items() if not r["customName"] and current[n] != spec.position(n)],
        current=current,
        pre_moves=order_moves(current, target, layout.known_sizes, layout.unmanaged),
        layout=layout,
        gone_ids={r["instanceId"] for r in deletes},
        notes=notes,
        matched_modules=len(existing),
        matched_positions=sum(1 for n in existing if current[n] == target[n]),
        graph=graph,
    )


def params_to_read(spec: Spec, plan: Plan) -> list[str]:
    return [r["instanceId"] for n, r in plan.existing_by_name.items() if spec.resolved[n].params]


def plan_module_params(spec: Spec, plan: Plan, rows: dict[str, dict[str, Any]]) -> ModuleParams:
    """Parameter writes of every spec module against the live rows read for the existing ones."""
    return {
        n: plan_params(
            n,
            spec.resolved[n],
            rows.get(plan.existing_by_name[n]["instanceId"]) if n in plan.existing_by_name else None,
        )
        for n in spec.names
    }
