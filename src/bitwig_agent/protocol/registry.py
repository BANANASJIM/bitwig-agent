from typing import Any

from bitwig_agent.protocol import (
    actions,
    arranger,
    audio,
    automation,
    devices,
    grid,
    grid_workflow,
    launcher,
    markers,
    modulators,
    parameters,
    presets,
    project,
    render,
    routing,
    tracks,
    transport,
)
from bitwig_agent.protocol.base import ToolSpec

_DOMAINS = (
    actions,
    arranger,
    audio,
    automation,
    devices,
    grid,
    grid_workflow,
    launcher,
    markers,
    modulators,
    parameters,
    presets,
    project,
    render,
    routing,
    tracks,
    transport,
)


def _collect() -> dict[str, ToolSpec]:
    specs: dict[str, ToolSpec] = {}
    for domain in _DOMAINS:
        duplicated = specs.keys() & domain.SPECS.keys()
        if duplicated:
            raise ValueError(f"Tools declared twice: {sorted(duplicated)}")
        specs.update(domain.SPECS)
    return specs


TOOLS: dict[str, ToolSpec] = _collect()


CLIENT_SIDE = frozenset(name for name, spec in TOOLS.items() if not spec.host)


def unknown_method_message(method: str) -> str:
    return f"Unknown method: {method}. Use list or describe <method> offline."


def validate_call(method: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], int]:
    if method not in TOOLS:
        raise ValueError(unknown_method_message(method))
    args: Any = TOOLS[method].model.model_validate(arguments)
    return args.model_dump(exclude={"timeoutMs"}, exclude_unset=True, by_alias=True), args.timeoutMs
