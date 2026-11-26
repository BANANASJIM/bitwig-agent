"""Behaviour hints for a method: derived from its verb, explicit only for a custom verb."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Annotations:
    read_only: bool
    destructive: bool
    idempotent: bool
    open_world: bool = False


READ = Annotations(read_only=True, destructive=False, idempotent=True)
ADD = Annotations(read_only=False, destructive=False, idempotent=False)
SET = Annotations(read_only=False, destructive=True, idempotent=True)
STEER = Annotations(read_only=False, destructive=False, idempotent=True)
REMOVE = Annotations(read_only=False, destructive=True, idempotent=True)
RETRIGGER = Annotations(read_only=False, destructive=False, idempotent=False)
EDIT = Annotations(read_only=False, destructive=True, idempotent=False)

VERBS: dict[str, Annotations] = {
    **dict.fromkeys(
        (
            "list",
            "get",
            "catalog",
            "capabilities",
            "describe",
            "status",
            "meters",
            "search",
            "graph",
            "analyze",
            "diagnostics",
        ),
        READ,
    ),
    **dict.fromkeys(("insert", "create", "append", "duplicate", "import", "copy"), ADD),
    **dict.fromkeys(("set", "rename", "map", "unmap"), SET),
    **dict.fromkeys(("select", "page", "stop"), STEER),
    **dict.fromkeys(("delete", "deletePage", "move"), REMOVE),
    "edit": EDIT,
}


def verb(method: str) -> str:
    return method.rsplit(".", 1)[-1]


def resolve(method: str, explicit: Annotations | None) -> Annotations:
    """The explicit annotations of a custom verb, else the ones its verb carries."""
    return explicit if explicit is not None else VERBS[verb(method)]


OPEN_WORLD_EDIT = Annotations(read_only=False, destructive=True, idempotent=False, open_world=True)
