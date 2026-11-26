"""Module footprints on the Grid: overlap validation of a spec layout and collision-free move ordering."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from bitwig_agent.grid.problems import SpecError
from bitwig_agent.grid.spec import Spec

Rect = tuple[int, int, int, int]
Cell = tuple[int, int]


def overlaps(a: Rect, b: Rect) -> bool:
    return a[0] < b[0] + b[2] and b[0] < a[0] + a[2] and a[1] < b[1] + b[3] and b[1] < a[1] + a[3]


def order_moves(
    current: dict[str, Cell],
    target: dict[str, Cell],
    size: dict[str, Cell],
    fixed: list[Rect],
) -> list[tuple[str, Cell]]:
    """Moves that never land on an occupied footprint: vacate-first, a cycle is broken through a free anchor far right.

    Bounded: every module is parked at most once, so at most 2 * len(current) moves.
    """
    pos = dict(current)
    todo = [n for n in current if current[n] != target[n]]
    parked: set[str] = set()
    plan: list[tuple[str, Cell]] = []

    def rect(n: str, at: Cell) -> Rect:
        return (at[0], at[1], *size.get(n, (1, 1)))

    def free(n: str, at: Cell) -> bool:
        r = rect(n, at)
        return not any(overlaps(r, f) for f in fixed) and not any(
            overlaps(r, rect(m, q)) for m, q in pos.items() if m != n
        )

    while todo:
        progressed = False
        for n in list(todo):
            if free(n, target[n]):
                plan.append((n, target[n]))
                pos[n] = target[n]
                todo.remove(n)
                progressed = True
        if todo and not progressed:
            stuck = next((m for m in todo if m not in parked), None)
            if stuck is None:
                raise SpecError(["footprints cannot be ordered into a collision-free move sequence"])
            right = max(
                [f[0] + f[2] for f in fixed]
                + [q[0] + size.get(m, (1, 1))[0] for m, q in pos.items()]
                + [q[0] + size.get(m, (1, 1))[0] for m, q in target.items()]
            )
            spot = (right + 2, pos[stuck][1])
            if spot == pos[stuck] or not free(stuck, spot):
                raise SpecError(["footprints cannot be ordered into a collision-free move sequence"])
            pos[stuck] = spot
            plan.append((stuck, spot))
            parked.add(stuck)
    return plan


def _known(v: Any) -> bool:
    return type(v) is int


def size_of(source: dict[str, Any]) -> Cell | None:
    """Width and height of a schema entry or live row, when both are known."""
    if _known(source.get("width")) and _known(source.get("height")):
        return source["width"], source["height"]
    return None


def has_rect(row: dict[str, Any]) -> bool:
    return all(_known(row.get(k)) for k in ("x", "y", "width", "height"))


def row_rect(row: dict[str, Any]) -> Rect:
    return row["x"], row["y"], row["width"], row["height"]


@dataclass
class Footprints:
    size: dict[str, Cell]  # spec modules whose size is known
    fixed: list[Rect]  # modules the spec does not manage
    problems: list[str]


def _spec_sizes(spec: Spec, existing: dict[str, dict[str, Any]]) -> dict[str, Cell]:
    sizes: dict[str, Cell] = {}
    for n in spec.names:
        info, row = spec.resolved[n].info, existing.get(n)
        found = (size_of(info) if info is not None else None) or (size_of(row) if row is not None else None)
        if found is not None:
            sizes[n] = found
    return sizes


def _unmanaged(rows: list[dict[str, Any]], managed: set[str]) -> tuple[list[Rect], list[str]]:
    fixed, labels = [], []
    for r in rows:
        if r["instanceId"] in managed or not has_rect(r):
            continue
        fixed.append(row_rect(r))
        where = f"{r.get('typeName') or r.get('name') or 'module'}@{r['x']},{r['y']}"
        labels.append(f"'{r['customName']}' ({where})" if r.get("customName") else where)
    return fixed, labels


def footprints(
    spec: Spec, existing: dict[str, dict[str, Any]], rows: list[dict[str, Any]], deletes: list[dict[str, Any]]
) -> Footprints:
    """Sizes of spec modules, rects of untouched modules, and offline overlap validation of the spec layout."""
    size = _spec_sizes(spec, existing)
    managed = {r["instanceId"] for r in existing.values()} | {r["instanceId"] for r in deletes}
    fixed, labels = _unmanaged(rows, managed)
    problems = []
    items = [(n, (*spec.position(n), *size[n])) for n in spec.names if n in size]
    for i, (a, ra) in enumerate(items):
        problems += [
            f"{a!r} and {b!r} overlap at their requested positions {ra[:2]} / {rb[:2]}"
            for b, rb in items[i + 1 :]
            if overlaps(ra, rb)
        ]
        problems += [
            f"{a!r} at {ra[:2]} overlaps existing module {fn}, which the spec does not manage"
            for fn, f in zip(labels, fixed, strict=False)
            if overlaps(ra, f)
        ]
    return Footprints(size, fixed, problems)
