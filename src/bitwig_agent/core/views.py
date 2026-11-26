"""Discovery data read from the method table; frontends only lay it out."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from bitwig_agent.client import package_version
from bitwig_agent.protocol import (
    CLIENT_SIDE,
    TOOLS,
    ToolSpec,
    unknown_arguments_message,
    unknown_method_message,
)
from bitwig_agent.protocol.annotations import READ, Annotations, resolve
from bitwig_agent.protocol.base import HELP_POINTER
from bitwig_agent.protocol.limits import DESCRIPTION_LIMIT
from bitwig_agent.protocol.topics import TOPICS


@dataclass(frozen=True)
class ToolView:
    name: str
    description: str
    input_schema: dict[str, Any]
    annotations: Annotations


HELP_NAME = "help"
HELP_DESCRIPTION = (
    "Guidance beyond a tool's short description. Without topic: the topic names and every tool. "
    "topic=<topic> returns that guide; topic=<tool name> returns the tool's full description and an example."
)
HELP_SCHEMA = {
    "type": "object",
    "properties": {"topic": {"type": "string", "description": "A topic or a tool name; omit to list them."}},
    "additionalProperties": False,
}


def version() -> str:
    return package_version()


def _exposed() -> dict[str, ToolSpec]:
    return {name: spec for name, spec in TOOLS.items() if spec.expose}


def names() -> list[str]:
    return list(_exposed())


def exists(name: str) -> bool:
    return name in _exposed()


def unknown_tool_message(name: str) -> str:
    return unknown_method_message(name)


def is_composed(name: str) -> bool:
    return name in CLIENT_SIDE


def index(prefix: str = "") -> dict[str, dict[str, Any]]:
    return {
        name: {"description": spec.description, "readOnly": spec.read_only}
        for name, spec in _exposed().items()
        if not prefix or name.startswith(prefix)
    }


def method(name: str) -> dict[str, Any]:
    if not exists(name):
        raise ValueError(unknown_method_message(name))
    return {"method": name, **TOOLS[name].describe()}


def _advertised(name: str, spec: ToolSpec) -> str:
    text = spec.description + (HELP_POINTER.format(name=name) if spec.doc else "")
    example = f" Example arguments: {json.dumps(spec.examples[0], ensure_ascii=False)}"
    return text + example if len(text) + len(example) <= DESCRIPTION_LIMIT else text


def _without_timeout(schema: dict[str, Any]) -> dict[str, Any]:
    schema = {**schema, "properties": {k: v for k, v in schema["properties"].items() if k != "timeoutMs"}}
    if "required" in schema:
        schema["required"] = [key for key in schema["required"] if key != "timeoutMs"]
    return schema


def tools() -> list[ToolView]:
    """What an MCP client is offered: the exposed methods, the timeout left to the client, and help."""
    views = [
        ToolView(
            name,
            _advertised(name, spec),
            _without_timeout(spec.model.model_json_schema()),
            resolve(name, spec.annotations),
        )
        for name, spec in _exposed().items()
    ]
    return [*views, ToolView(HELP_NAME, HELP_DESCRIPTION, HELP_SCHEMA, READ)]


def instructions() -> str:
    return f"{TOPICS['loop']} Call help for more: {', '.join(TOPICS)}, or any tool name."


def help(topic: str | None = None) -> dict[str, Any]:
    if topic is None:
        return {"topics": list(TOPICS), "tools": names()}
    if topic in TOPICS:
        return {"topic": topic, "text": TOPICS[topic]}
    if not exists(topic):
        raise ValueError(
            f"Unknown topic or tool: {topic}. Topics: {', '.join(TOPICS)}; call help without topic for all."
        )
    spec = TOOLS[topic]
    return {
        "tool": topic,
        "description": spec.full_description,
        "example": spec.examples[0],
        "requires": list(spec.requires),
        "readOnly": spec.read_only,
    }


def field_types(name: str) -> dict[str, Any]:
    """Field name to its JSON schema type (None where the schema gives none)."""
    if not exists(name):
        raise ValueError(unknown_method_message(name))
    return {key: prop.get("type") for key, prop in TOOLS[name].model.model_json_schema()["properties"].items()}


def unknown_field_message(name: str, key: str) -> str:
    return unknown_arguments_message(name, [key])
