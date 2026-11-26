from __future__ import annotations

from typing import Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    TOPOLOGY_TIMEOUT_MS,
    UTF16_BYTES_PER_UNIT,
    StatusArgs,
    StrictModel,
    ToolSpec,
    omitted,
    tool,
    validate_local_path,
)
from bitwig_agent.protocol.limits import SCENE_WINDOW, TRACK_WINDOW

MAX_PROJECT_NAME_UNITS = 128
MIN_PROJECT_PATH_PARTS = 3


class ExtensionRestartArgs(StatusArgs):
    discardUnresolved: bool = omitted()


PROJECT_EXTENSION = ".bwproject"


class ProjectSaveAsArgs(StrictModel):
    timeoutMs: int = Field(default=30000, ge=100, le=TOPOLOGY_TIMEOUT_MS)
    path: str = Field(
        max_length=4096,
        description="Final project file: <dir>/<Name>/<Name>.bwproject for a new project folder (Bitwig creates <Name>/), or any .bwproject inside an existing project folder. Absolute; no ~ expansion.",
    )
    overwrite: bool = omitted(
        description="Allow replacing an existing file at path (Bitwig keeps the old one in the project's auto-backups folder)."
    )
    allowAnyPath: bool = omitted(description="Allow a path outside Bitwig's configured projects folder.")

    @model_validator(mode="after")
    def validate_path(self) -> Self:
        path = validate_local_path(self.path)
        if len(path.parts) < MIN_PROJECT_PATH_PARTS or not path.name.endswith(PROJECT_EXTENSION):
            raise ValueError(f"path must name a {PROJECT_EXTENSION} file inside a folder")
        name = path.name[: -len(PROJECT_EXTENSION)]
        if (
            not name
            or len(name.encode("utf-16-le", errors="surrogatepass")) // UTF16_BYTES_PER_UNIT > MAX_PROJECT_NAME_UNITS
            or name != name.strip()
            or name.startswith(".")
            or name.lower() == "template"
        ):
            raise ValueError(
                f"project name must be 1..{MAX_PROJECT_NAME_UNITS} characters without leading/trailing whitespace or a leading dot, and not 'template'"
            )
        if "auto-backups" in path.parts:
            raise ValueError("Bitwig refuses to save inside an auto-backups folder")
        return self


class HostDiagnosticsArgs(StatusArgs):
    sinceSeconds: int = omitted(
        ge=1, le=86400, description="Look back this many seconds (default 300); exclusive with sinceMarker."
    )
    sinceMarker: str = omitted(
        min_length=1,
        max_length=512,
        description="Opaque marker from a previous host.diagnostics call: only events written after it.",
    )
    maxEvents: int = omitted(ge=1, le=500, description="Keep the newest N deduplicated events (default 50).")

    @model_validator(mode="after")
    def validate_window(self) -> Self:
        if {"sinceSeconds", "sinceMarker"} <= self.model_fields_set:
            raise ValueError("supply at most one of sinceSeconds or sinceMarker")
        return self


STATUS_DESCRIPTION = "Observe project/transport state, not proof of audio or persistence. projectModified: true = unsaved changes, null = unobserved (treat as unsaved)."
CAPABILITIES_DESCRIPTION = "Discover implemented host methods, bounded windows, units and unsupported interfaces. Local CLI list/describe work even without Bitwig; this method queries the running extension."
CONTEXT_GET_DESCRIPTION = f"One read-only orientation call (does not select or move the cursor): projectName, playing, tempoBpm, the three recording/automation safety flags, sceneCount, tracks [{{trackId, index, name, type, selected, launcherSlots [{{slotIndex, name, playing}}] occupied slots only}}], selectedTrack (trackId) and cursorClip {{trackId, slotIndex, name}}: the clip the user has selected in the launcher/editor. Unobserved values are null, never guessed; cursorClip is null when no clip is selected or it lies outside the observed {TRACK_WINDOW} tracks/{SCENE_WINDOW} scenes. Your own notes.read/notes.edit/launcher.create calls move cursorClip."
EXTENSION_RESTART_DESCRIPTION = "Restart this controller extension instance, clearing per-instance write quarantine (replaces the Settings off/on toggle; a Grid device quarantine is NOT cleared, use grid.topology.reconcile). Refused with unresolved_mutation while unresolved create/import/note/settings mutations exist, unless discardUnresolved=true; the refusal and the dispatched result both list their descriptions: inspect those targets afterwards, never assume they applied or failed. Returns dispatched only: the RPC connection drops, so poll status until it answers, then re-discover targets (tracks.list etc.). Observed on 6.1.4: it does NOT load a replaced .bwextension (status.extension.build stays the same); restart Bitwig to run a new build, but first save every open project tab (projectModified covers only the current tab; use project.save) or get user consent: closing Bitwig loses unsaved work."
PROJECT_SAVE_DESCRIPTION = "Save the project via the native Save action; saved=true only when Project.isModified is observed false (alreadySaved if nothing to save). Otherwise dispatched/not_observed, never re-invoked: an untitled project opens a Save As dialog needing GUI/user completion, and an already open modal dialog (e.g. Recover Projects after a crash) blocks the save unseen. Check projectModified before retrying."
PROJECT_SAVE_AS_DESCRIPTION = "Save the current project to an explicit path without a dialog (also an untitled one), through Bitwig's own Save Project pipeline; the open project then lives there (name = file name). path is the final file: <projects>/<Name>/<Name>.bwproject creates the project folder <Name>, or name a .bwproject inside an existing project folder. Refused before dispatch (not_applied): a path outside Bitwig's projects folder unless allowAnyPath, an existing file unless overwrite, an existing non-project folder, auto-backups. saved=true only with Bitwig's completion callback, the file written (nonempty, new or changed), projectModified=false and projectName = Name. save_refused (not_applied) when Bitwig refused (recording, already saving); save_failed and dispatched/not_observed are unknown: inspect the file and status, never retry. Files in the old or another project folder are copied in (the dialog default); other external samples stay referenced. capabilities.projectSaveAs says whether the binding is available. timeoutMs default 30000, up to 60000."
PROJECT_ACTIVATE_ENGINE_DESCRIPTION = 'Activate the audio engine for the current project (native "Activate Engine For Project" action, dispatched once, never retried). After a Bitwig restart the recovered project can have engineActive=false: clip launches then return not_observed and captures are silent. engineActive=true only when hasActiveEngine is observed true within the request deadline (alreadyActive if it already was); otherwise dispatched/not_observed with a hint. launcher.launch, audio.capture and grid.probe refuse with engine_inactive until this succeeds.'
HOST_DIAGNOSTICS_DESCRIPTION = "Client-side: host problems invisible through the API, read from Bitwig's shared log (Linux ~/.BitwigStudio/log; macOS/Windows documented paths, else status unsupported). One status call picks this instance's log section by process start time; attribution.certain=false says when other instances may be mixed in. Events {time, level, category: grid_rejected|engine|extension|exception|dialog|other, message, count}; grid_rejected = a Grid device silently dropped from the audio network (check after Grid topology writes), engine = isEngineReady flips. Modal dialogs (Dashboard, Save?, Recover) are NOT in the log. Returns marker: pass it as sinceMarker next time to see only what happened since. grid_rejected bursts WHILE a build is in progress are expected (the graph is incomplete between inserts and cables): only events after the build count, so use the diagnosticsMarker that grid.apply returns as sinceMarker. Default last 300 s, maxEvents 50."


SPECS: dict[str, ToolSpec] = {
    "status": tool(StatusArgs, STATUS_DESCRIPTION, True),
    "capabilities": tool(StatusArgs, CAPABILITIES_DESCRIPTION, True),
    "context.get": tool(StatusArgs, CONTEXT_GET_DESCRIPTION, True),
    "extension.restart": tool(ExtensionRestartArgs, EXTENSION_RESTART_DESCRIPTION, False, {"discardUnresolved": False}),
    "project.save": tool(StatusArgs, PROJECT_SAVE_DESCRIPTION, False),
    "project.saveAs": tool(
        ProjectSaveAsArgs,
        PROJECT_SAVE_AS_DESCRIPTION,
        False,
        {"path": "/home/me/Bitwig Studio/Projects/Agent Sketch/Agent Sketch.bwproject"},
    ),
    "project.activateEngine": tool(StatusArgs, PROJECT_ACTIVATE_ENGINE_DESCRIPTION, False),
    "host.diagnostics": tool(
        HostDiagnosticsArgs, HOST_DIAGNOSTICS_DESCRIPTION, True, {"sinceSeconds": 300}, host=False
    ),
}
