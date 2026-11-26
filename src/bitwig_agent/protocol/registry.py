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
from bitwig_agent.protocol.base import MAX_TIMEOUT_MS, ToolSpec

# Composed in Python from host methods; never sent to the host as one request.
CLIENT_SIDE = frozenset(
    {
        "modulators.catalog",
        "modulators.describe",
        "grid.apply",
        "grid.export",
        "grid.graph",
        "grid.probe",
        "grid.render",
        "grid.describe",
        "audio.route",
        "audio.capture",
        "audio.analyze",
        "host.diagnostics",
        "presets.search",
    }
)

TOOL_ORDER = (
    "status",
    "capabilities",
    "tracks.list",
    "tracks.get",
    "tracks.set",
    "tracks.sends.set",
    "tracks.meters",
    "markers.list",
    "markers.insert",
    "markers.rename",
    "markers.delete",
    "tracks.select",
    "tracks.create",
    "tracks.group",
    "tracks.delete",
    "context.get",
    "tracks.routing.get",
    "tracks.routing.set",
    "transport.get",
    "transport.set",
    "devices.list",
    "devices.get",
    "devices.layers.list",
    "devices.set",
    "devices.select",
    "devices.catalog",
    "devices.insert",
    "devices.delete",
    "devices.move",
    "devices.sidechain.get",
    "devices.sidechain.set",
    "devices.layers.insert",
    "presets.search",
    "devices.remote.get",
    "devices.remote.page",
    "devices.remote.set",
    "devices.remote.define",
    "devices.remote.deletePage",
    "parameters.list",
    "parameters.set",
    "parameters.layer.list",
    "parameters.layer.set",
    "modulators.capabilities",
    "modulators.list",
    "modulators.parameters.get",
    "modulators.catalog",
    "modulators.describe",
    "modulators.get",
    "modulators.insert",
    "modulators.delete",
    "modulators.parameters.set",
    "modulators.map",
    "modulators.unmap",
    "grid.capabilities",
    "grid.catalog",
    "grid.get",
    "grid.parameters.get",
    "grid.parameters.set",
    "grid.topology.reconcile",
    "grid.voicing.get",
    "grid.voicing.set",
    "grid.modules.insert",
    "grid.modules.rename",
    "grid.modules.move",
    "grid.modules.delete",
    "grid.connections.set",
    "grid.describe",
    "grid.apply",
    "grid.probe",
    "grid.export",
    "grid.graph",
    "grid.render",
    "audio.route",
    "audio.capture",
    "audio.analyze",
    "launcher.list",
    "launcher.get",
    "launcher.set",
    "launcher.create",
    "scenes.append",
    "scenes.list",
    "scenes.rename",
    "scenes.launch",
    "scenes.duplicate",
    "scenes.delete",
    "launcher.import",
    "arranger.clips.list",
    "arranger.clips.create",
    "arranger.clips.copyFromLauncher",
    "arranger.clips.edit",
    "automation.points.get",
    "automation.points.set",
    "export.audio",
    "bounce.inPlace",
    "render.status",
    "clips.edit",
    "launcher.launch",
    "launcher.stop",
    "notes.read",
    "notes.edit",
    "extension.restart",
    "actions.list",
    "project.save",
    "project.saveAs",
    "project.activateEngine",
    "actions.invoke",
    "host.diagnostics",
)

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
    if set(specs) != set(TOOL_ORDER):
        raise ValueError(f"TOOL_ORDER differs from declared tools: {sorted(set(specs) ^ set(TOOL_ORDER))}")
    return {name: specs[name] for name in TOOL_ORDER}


TOOLS: dict[str, ToolSpec] = _collect()


def max_timeout_ms(method: str) -> int:
    """The method's timeoutMs ceiling (topology edits allow longer than the default)."""
    for constraint in TOOLS[method].model.model_fields["timeoutMs"].metadata:
        if getattr(constraint, "le", None) is not None:
            return int(constraint.le)
    return MAX_TIMEOUT_MS


def validate_call(method: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], int]:
    if method not in TOOLS:
        raise ValueError(f"Unknown method: {method}. Use list or describe <method> offline.")
    args: Any = TOOLS[method].model.model_validate(arguments)
    return args.model_dump(exclude={"timeoutMs"}, exclude_unset=True, by_alias=True), args.timeoutMs
