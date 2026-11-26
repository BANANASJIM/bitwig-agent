"""Discovery data read from the method table; frontends only lay it out."""

from __future__ import annotations

import json
import re
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


def read_only(name: str) -> bool:
    return TOOLS[name].read_only


def records(name: str) -> str | None:
    """The top-level array of objects a read returns, when it has one: the key row output prints."""
    return TOOLS[name].records


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
    spec = TOOLS[name]
    return {"method": name, **spec.describe(), "requires": list(spec.requires)}


def type_label(prop: dict[str, Any]) -> str:
    """A short, human-readable type of one JSON schema property."""
    if "enum" in prop:
        return "|".join(str(value) for value in prop["enum"])
    if "$ref" in prop:
        return str(prop["$ref"]).rsplit("/", 1)[-1]
    for union in ("anyOf", "oneOf"):
        if union in prop:
            return " | ".join(dict.fromkeys(type_label(branch) for branch in prop[union]))
    if prop.get("type") == "array":
        return f"array<{type_label(prop.get('items', {}))}>"
    return str(prop.get("type", "any"))


def fields(name: str) -> list[dict[str, Any]]:
    """Top-level arguments of one method: name, type label, required, description."""
    schema = TOOLS[name].model.model_json_schema()
    required = set(schema.get("required", ()))
    return [
        {
            "name": key,
            "type": type_label(prop),
            "required": key in required,
            "description": prop.get("description", ""),
        }
        for key, prop in sorted(schema["properties"].items(), key=lambda item: item[0] == "timeoutMs")
    ]


HELP_POINTER = " Full guide: help {name}."
ADVERTISED_EXAMPLE_LIMIT = DESCRIPTION_LIMIT // 2
_SENTENCE_END = re.compile(r"(?<!e\.g)(?<!i\.e)\.\s+(?=[A-Z])")


def _lead(name: str, text: str, budget: int) -> str:
    """The leading whole sentences of text that fit budget."""
    lead = ""
    start = 0
    for match in _SENTENCE_END.finditer(text):
        sentence = text[start : match.end()].strip()
        if len(lead) + len(sentence) + 1 > budget:
            break
        lead = f"{lead} {sentence}".strip()
        start = match.end()
    if not lead:
        raise ValueError(f"No whole sentence of {name} fits {budget} characters: give it a summary")
    return lead


def _example(spec: ToolSpec) -> str:
    """The first example as advertised: left out when it is a ref chain too long to leave room for the summary."""
    text = json.dumps(spec.examples[0], ensure_ascii=False) if spec.examples[0] else ""
    return f" Example arguments: {text}" if text and len(text) <= ADVERTISED_EXAMPLE_LIMIT else ""


def _advertised(name: str, spec: ToolSpec) -> str:
    """What the agent reads with the tool: the description, or its summary and a help pointer, then the example."""
    example = _example(spec)
    pointer = HELP_POINTER.format(name=name)
    hidden = pointer if spec.examples[0] and not example else ""  # help shows the example that is not advertised
    if len(spec.description) + len(hidden) + len(example) <= DESCRIPTION_LIMIT:
        return spec.description + hidden + example
    budget = DESCRIPTION_LIMIT - len(pointer) - len(example)
    lead = spec.summary or _lead(name, spec.description, budget)
    if len(lead) > budget:
        raise ValueError(f"The summary of {name} is {len(lead)} characters, over its {budget} budget")
    return lead + pointer + example


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
        "description": spec.description,
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
