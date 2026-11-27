import dataclasses
import difflib
from typing import Any

from bitwig_agent.protocol import (
    actions,
    arranger,
    audio,
    automation,
    batch,
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
    batch,
)


INSPECT: dict[str, str] = {
    "project.save": "status",
    "project.saveAs": "status",
    "project.activateEngine": "status",
    "project.key.set": "project.key.get",
    "ack": "status",
    "tracks.set": "tracks.get",
    "tracks.sends.set": "tracks.get",
    "tracks.select": "tracks.get",
    "tracks.insert": "tracks.list",
    "tracks.group": "tracks.list",
    "tracks.delete": "tracks.list",
    "tracks.routing.set": "tracks.routing.get",
    "tracks.bounce": "jobs.get",
    "export.audio": "jobs.get",
    "markers.insert": "markers.list",
    "markers.set": "markers.list",
    "markers.delete": "markers.list",
    "devices.sidechain.set": "devices.sidechain.get",
    "transport.set": "transport.get",
    "chains.set": "chains.list",
    "chains.insert": "chains.list",
    "devices.set": "devices.get",
    "devices.select": "devices.get",
    "devices.insert": "devices.list",
    "devices.delete": "devices.list",
    "devices.move": "devices.list",
    "devices.remote.define": "devices.remote.get",
    "devices.remote.delete": "devices.remote.get",
    "parameters.set": "parameters.list",
    "grid.parameters.set": "grid.get",
    "grid.voicing.set": "grid.voicing.get",
    "grid.modules.insert": "grid.get",
    "grid.modules.set": "grid.get",
    "grid.modules.move": "grid.get",
    "grid.modules.delete": "grid.get",
    "grid.connections.set": "grid.get",
    "modulators.insert": "modulators.list",
    "modulators.delete": "modulators.list",
    "modulators.map": "modulators.get",
    "modulators.unmap": "modulators.get",
    "launcher.stop": "launcher.list",
    "launcher.returnToArrangement": "launcher.list",
    "clips.insert": "launcher.list",
    "clips.import": "launcher.list",
    "clips.delete": "launcher.list",
    "clips.copy": "launcher.list",
    "clips.move": "arranger.list",
    "clips.set": "clips.get",
    "clips.launch": "clips.get",
    "clips.transpose": "clips.get",
    "clips.quantize": "clips.get",
    "clips.transform": "clips.get",
    "notes.get": "clips.get",
    "notes.set": "clips.get",
    "scenes.insert": "scenes.list",
    "scenes.set": "scenes.list",
    "scenes.launch": "scenes.list",
    "scenes.duplicate": "scenes.list",
    "scenes.delete": "scenes.list",
    "automation.points.set": "automation.points.get",
    "actions.invoke": "context.get",
}
"""By host write, the read method an `unknown` result points at: the place to look before another write."""


def with_inspect(specs: dict[str, ToolSpec], table: dict[str, str]) -> dict[str, ToolSpec]:
    """The specs with each host write's `inspect` set from `table`; every write needs a host read."""
    stray = table.keys() - specs.keys()
    if stray:
        raise ValueError(f"INSPECT names unknown methods: {sorted(stray)}")
    out = dict(specs)
    for name, spec in specs.items():
        write = spec.host and not spec.read_only
        target = table.get(name)
        if write and target is None:
            raise ValueError(f"Host write without an inspect method: {name}")
        if target is None:
            continue
        if not write:
            raise ValueError(f"INSPECT names a method that is not a host write: {name}")
        if target not in specs or not specs[target].host or not specs[target].read_only:
            raise ValueError(f"{name} inspects {target}, which is not a host read")
        out[name] = dataclasses.replace(spec, inspect=target)
    return out


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
    return with_inspect(specs, INSPECT)


TOOLS: dict[str, ToolSpec] = _collect()


CLIENT_SIDE = frozenset(name for name, spec in TOOLS.items() if not spec.host)
COMPOSED = CLIENT_SIDE | {name for name, spec in TOOLS.items() if spec.wire}
"""Methods the client runs itself: the client-side tools, and the host writes it composes with configure steps."""


MAX_SUGGESTIONS = 3
READ_VERBS = frozenset({"read", "fetch", "show"})


def suggest_methods(method: str) -> list[str]:
    """The close spelling of method (a read verb as get), unless it has another verb: then the same verb under other
    resources."""
    resource, _, verb = method.rpartition(".")
    if verb in READ_VERBS:
        method, verb = f"{resource}.get", "get"
    exposed = [name for name, spec in TOOLS.items() if spec.expose]
    close = difflib.get_close_matches(method, exposed, n=1, cutoff=0.6)
    if close and close[0].rpartition(".")[2] == verb:
        return close
    same_verb = [name for name in exposed if name.rpartition(".")[2] == verb and name != method]
    return same_verb[:MAX_SUGGESTIONS] or close


def unknown_method_message(method: str) -> str:
    names = suggest_methods(method)
    hint = f" (did you mean {', '.join(names[:-1])} or {names[-1]}?)" if len(names) > 1 else ""
    hint = hint or (f" (did you mean {names[0]}?)" if names else "")
    return f"Unknown method: {method}{hint}. Call help to list the available methods."


def validate_call(method: str, arguments: dict[str, Any], *, host: bool = False) -> tuple[dict[str, Any], int]:
    """The arguments as the tool takes them, or with host=True as the extension receives them."""
    if method not in TOOLS:
        raise ValueError(unknown_method_message(method))
    spec = TOOLS[method]
    args: Any = (spec.host_model if host else spec.model).model_validate(arguments)
    return args.model_dump(exclude={"timeoutMs"}, exclude_unset=True, by_alias=True), args.timeoutMs
