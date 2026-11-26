import difflib
from typing import Any

from bitwig_agent.protocol import (
    actions,
    arranger,
    audio,
    automation,
    clips,
    devices,
    diagnostics,
    grid,
    grid_workflow,
    launcher,
    markers,
    modulators,
    parameters,
    presets,
    project,
    recovery,
    render,
    routing,
    scenes,
    tracks,
    transport,
)
from bitwig_agent.protocol.base import ToolSpec

_DOMAINS = (
    project,
    diagnostics,
    recovery,
    tracks,
    markers,
    routing,
    transport,
    devices,
    parameters,
    presets,
    grid,
    grid_workflow,
    modulators,
    launcher,
    clips,
    scenes,
    arranger,
    automation,
    render,
    audio,
    actions,
)


def _collect() -> dict[str, ToolSpec]:
    specs: dict[str, ToolSpec] = {}
    for domain in _DOMAINS:
        duplicated = specs.keys() & domain.SPECS.keys()
        if duplicated:
            raise ValueError(f"Tools declared twice: {sorted(duplicated)}")
        specs.update(domain.SPECS)
    writes = [name for name, spec in specs.items() if spec.records and not spec.read_only]
    if writes:
        raise ValueError(f"Only reads declare a record list: {writes}")
    return specs


TOOLS: dict[str, ToolSpec] = _collect()


CLIENT_SIDE = frozenset(name for name, spec in TOOLS.items() if not spec.host)


def unknown_method_message(method: str) -> str:
    exposed = [name for name, spec in TOOLS.items() if spec.expose]
    close = difflib.get_close_matches(method, exposed, n=1, cutoff=0.6)
    hint = f" (did you mean {close[0]}?)" if close else ""
    return f"Unknown method: {method}{hint}. Call help to list the available methods."


def validate_call(method: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], int]:
    if method not in TOOLS:
        raise ValueError(unknown_method_message(method))
    args: Any = TOOLS[method].model.model_validate(arguments)
    return args.model_dump(exclude={"timeoutMs"}, exclude_unset=True, by_alias=True), args.timeoutMs
