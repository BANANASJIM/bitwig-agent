"""Discovery data read from the method table; frontends only lay it out."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from bitwig_agent.client import package_version
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, unknown_arguments_message, unknown_method_message


@dataclass(frozen=True)
class ToolView:
    name: str
    description: str
    example: dict[str, Any]
    input_schema: dict[str, Any]
    read_only: bool


def version() -> str:
    return package_version()


def names() -> list[str]:
    return list(TOOLS)


def exists(name: str) -> bool:
    return name in TOOLS


def is_composed(name: str) -> bool:
    return name in CLIENT_SIDE


def index(prefix: str = "") -> dict[str, dict[str, Any]]:
    return {
        name: {"description": spec.description, "readOnly": spec.read_only}
        for name, spec in TOOLS.items()
        if not prefix or name.startswith(prefix)
    }


def method(name: str) -> dict[str, Any]:
    return {"method": name, **TOOLS[name].describe()}


def tools() -> list[ToolView]:
    return [
        ToolView(name, spec.description, spec.examples[0], spec.model.model_json_schema(), spec.read_only)
        for name, spec in TOOLS.items()
    ]


def field_types(name: str) -> dict[str, Any]:
    """Field name to its JSON schema type (None where the schema gives none)."""
    if name not in TOOLS:
        raise ValueError(unknown_method_message(name))
    return {key: prop.get("type") for key, prop in TOOLS[name].model.model_json_schema()["properties"].items()}


def unknown_field_message(name: str, key: str) -> str:
    return unknown_arguments_message(name, [key])
