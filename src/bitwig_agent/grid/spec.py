"""A grid.apply spec resolved against the module catalog: types, parameter writes and connection endpoints."""

from __future__ import annotations

from dataclasses import dataclass
from functools import cached_property
from typing import Any, NamedTuple

from bitwig_agent.grid import raw_scales
from bitwig_agent.grid.display import DisplayBasis, check_display, display_want, enum_context
from bitwig_agent.grid.names import resolve
from bitwig_agent.grid.problems import SpecError, hint, near
from bitwig_agent.grid.values import STEP_TYPES, check_steps, check_value, is_steps


@dataclass
class TypeCatalog:
    """Module types by name and id; `info` carries ports, parameters and sizes only for the bundled schema."""

    info: dict[str, Any] | None
    by_name: dict[str, list[str]]

    @classmethod
    def bundled(cls, schema: dict[str, Any]) -> TypeCatalog:
        modules = schema["modules"]
        return cls(modules, {n: [m["typeId"]] for n, m in modules.items()})

    @classmethod
    def live(cls, entries: list[dict[str, Any]]) -> TypeCatalog:
        by_name: dict[str, list[str]] = {}
        for e in entries:
            by_name.setdefault(e["name"], []).append(e["typeId"])
        return cls(None, by_name)

    @property
    def is_live(self) -> bool:
        return self.info is None

    @cached_property
    def by_id(self) -> dict[str, str]:
        return {t.lower(): n for n, ids in self.by_name.items() for t in ids}

    def resolve(self, spec: dict[str, Any]) -> tuple[str, str]:
        if "typeId" in spec:
            name = self.by_id.get(spec["typeId"].lower())
            if name is None:
                raise SpecError([f"{spec['name']!r}: typeId {spec['typeId']} is not a catalog module"])
            return spec["typeId"].lower(), name
        wanted = spec["type"]
        exact = resolve(self.by_name, str, wanted)
        if len(exact) != 1:
            raise SpecError([f"{spec['name']!r}: unknown module type {wanted!r}.{near(wanted, self.by_name)}"])
        ids = self.by_name[exact[0]]
        if len(ids) != 1:
            raise SpecError([f"{spec['name']!r}: type {wanted!r} is ambiguous; use typeId from {ids}"])
        return ids[0].lower(), exact[0]


@dataclass(frozen=True)
class ParamSpec:
    written: Any
    checked: dict[str, Any] | None


@dataclass
class Resolved:
    type_id: str
    type_name: str
    info: dict[str, Any] | None
    params: dict[str, ParamSpec]


class Edge(NamedTuple):
    src: str
    src_port: str
    dst: str
    dst_port: str


@dataclass
class Spec:
    target: dict[str, Any]
    modules: list[dict[str, Any]]
    names: list[str]
    by_spec: dict[str, dict[str, Any]]
    resolved: dict[str, Resolved]
    edges: list[Edge]
    external: list[str]
    namespace: str | None
    prune_all: bool  # manage the whole device: no namespace, unnamed modules included
    prune: bool
    replace: bool
    outside_ok: bool
    voicing: dict[str, Any]
    has_steps: bool
    live_schema: bool
    dry_run: bool
    host: str | None  # the connected host version, whose measured notes apply

    def position(self, name: str) -> tuple[int, int]:
        return self.by_spec[name]["x"], self.by_spec[name]["y"]


def _display_write(pid: str, text: str, q: dict[str, Any], type_name: str, ctx: dict[str, Any]) -> dict[str, Any]:
    raw_value = check_display(pid, text, DisplayBasis.schema(q), type_name, ctx)
    return display_want(q.get("numericUnit"), text, raw_value, raw_scales.is_client(type_name, pid, ctx))


def _resolve_steps(m: dict[str, Any], type_name: str, info: dict[str, Any] | None, pid: str) -> ParamSpec:
    """The bundled schema has no step-array parameters: the id is matched against the live row."""
    raw = m["params"][pid]
    try:
        want = check_steps(pid, raw)
    except SpecError as exc:
        raise SpecError([f"{m['name']!r}: {p}" for p in exc.problems]) from exc
    if info is not None and type_name not in STEP_TYPES:
        raise SpecError([f"{m['name']!r} ({type_name}): has no step array; steps work on {', '.join(STEP_TYPES)}"])
    if info is not None and any(p["parameterId"] == pid for p in info["parameters"]):
        raise SpecError([f"{m['name']!r} ({type_name}): {pid} is not a step array"])
    return ParamSpec(raw, want)


def _resolve_param(m: dict[str, Any], type_name: str, info: dict[str, Any] | None, pid: str) -> ParamSpec:
    raw = m["params"][pid]
    if is_steps(raw):
        return _resolve_steps(m, type_name, info, pid)
    if info is None:
        return ParamSpec(raw, None)
    pinfo = {p["parameterId"]: p for p in info["parameters"]}
    if pid not in pinfo:
        raise SpecError([f"{m['name']!r} ({type_name}): unknown parameter {pid!r}.{hint(pid, pinfo)}"])
    try:
        want = check_value(pid, raw, pinfo[pid])
        if "display" in want:
            want = _display_write(pid, want["display"], pinfo[pid], type_name, enum_context(m["params"], info))
    except SpecError as exc:
        raise SpecError([f"{m['name']!r}: {p}" for p in exc.problems]) from exc
    return ParamSpec(raw, want)


def _resolve_module(m: dict[str, Any], catalog: TypeCatalog, problems: list[str]) -> Resolved | None:
    try:
        type_id, type_name = catalog.resolve(m)
    except SpecError as exc:
        problems += exc.problems
        return None
    info = catalog.info[type_name] if catalog.info else None
    writes: dict[str, ParamSpec] = {}
    for pid in m.get("params") or {}:
        try:
            writes[pid] = _resolve_param(m, type_name, info, pid)
        except SpecError as exc:
            problems += exc.problems
    return Resolved(type_id, type_name, info, writes)


def _endpoint(
    endpoint: str, declared: dict[str, Any], external: list[str], problems: list[str]
) -> tuple[str, str] | None:
    name, dot, port = endpoint.rpartition(".")
    if not dot or not name or (name not in declared and name not in external):
        problems.append(
            f"connection endpoint {endpoint!r}: expected '<declared or external module name>.<PORT_KEY>'.{near(name or endpoint, [*declared, *external])}"
        )
        return None
    return name, port


def _check_ports(
    resolved: dict[str, Resolved], src: tuple[str, str], dst: tuple[str, str], problems: list[str]
) -> None:
    for (name, port), side, label in ((src, "outputs", "output"), (dst, "inputs", "input")):
        module = resolved.get(name)
        if module is None or module.info is None:
            continue
        keys = [p["key"] for p in module.info[side]]
        if port not in keys:
            problems.append(f"{name!r} ({module.type_name}) has no {label} {port!r}.{hint(port, keys)}")


def _resolve_edges(
    connections: list[dict[str, Any]],
    declared: dict[str, Any],
    external: list[str],
    resolved: dict[str, Resolved],
    problems: list[str],
) -> list[Edge]:
    edges = []
    for c in connections:
        src = _endpoint(c["from"], declared, external, problems)
        dst = _endpoint(c["to"], declared, external, problems)
        if not src or not dst:
            continue
        _check_ports(resolved, src, dst, problems)
        edges.append(Edge(src[0], src[1], dst[0], dst[1]))
    return edges


def parse_spec(args: dict[str, Any], catalog: TypeCatalog, host: str | None = None) -> Spec:
    """Resolve every module type, parameter write and connection endpoint; raise SpecError with all problems."""
    modules: list[dict[str, Any]] = args.get("modules", [])
    external: list[str] = args.get("external") or []
    by_spec = {m["name"]: m for m in modules}
    problems = [f"external {n!r} is also declared in modules; declare it once" for n in external if n in by_spec]
    resolved: dict[str, Resolved] = {}
    for m in modules:
        module = _resolve_module(m, catalog, problems)
        if module is not None:
            resolved[m["name"]] = module
    edges = _resolve_edges(args.get("connections", []), by_spec, external, resolved, problems)
    if problems:
        raise SpecError(problems)
    prune_all = bool(args.get("pruneAll"))
    return Spec(
        target=args["target"],
        modules=modules,
        names=[m["name"] for m in modules],
        by_spec=by_spec,
        resolved=resolved,
        edges=edges,
        external=external,
        namespace=args.get("namespace"),
        prune_all=prune_all,
        prune=bool(args.get("prune")) or prune_all,
        replace=bool(args.get("replace")) or prune_all,
        outside_ok=bool(args.get("allowOutsideDisconnect")),
        voicing=args.get("voicing") or {},
        has_steps=any(is_steps(v) for m in modules for v in (m.get("params") or {}).values()),
        live_schema=catalog.is_live,
        dry_run=bool(args.get("dryRun")),
        host=host,
    )
