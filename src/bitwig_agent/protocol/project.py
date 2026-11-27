from __future__ import annotations

from typing import Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.annotations import EDIT, SET, STEER
from bitwig_agent.protocol.base import (
    NOT_RECORDING,
    UTF16_BYTES_PER_UNIT,
    Nonempty,
    StatusArgs,
    StrictModel,
    ToolSpec,
    long_timeout,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.limits import LONG_DEFAULT_TIMEOUT_MS, SCENE_WINDOW, TRACK_WINDOW

MAX_PROJECT_NAME_UNITS = 128
MIN_PROJECT_PATH_PARTS = 3


PROJECT_EXTENSION = ".bwproject"


class ProjectSaveAsArgs(StrictModel):
    timeoutMs: int = long_timeout(LONG_DEFAULT_TIMEOUT_MS)
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


KEY_CHOICE = "an option label of project.key.get (case-insensitive) or its integer value; Bitwig's own list"


class ProjectKeySetArgs(StatusArgs):
    root: int | Nonempty = omitted(description=f"Root Key: {KEY_CHOICE} (e.g. 'D' or 2).")
    scale: int | Nonempty = omitted(description=f"Scale: {KEY_CHOICE} (e.g. 'Minor' or its pitch-class mask 1453).")

    @model_validator(mode="after")
    def require_change(self) -> Self:
        if not self.model_fields_set - {"timeoutMs"}:
            raise ValueError("project.key.set requires root or scale")
        return self


class StatusDetailArgs(StatusArgs):
    detail: Literal["full"] = omitted(
        description="full adds each domain's binding and coverage section; omitted, status is the brief project state."
    )


STATUS_DESCRIPTION = "Observe project/transport state, not proof of audio or persistence. projectModified: true = unsaved changes, null = unobserved (treat as unsaved). detail=full adds the implemented methods by domain, windows, units and binding availability."
CONTEXT_GET_DESCRIPTION = f"One read-only orientation call (does not select or move the cursor): projectName, playing, tempo, the three recording/automation safety flags, sceneCount, tracks [{{ref (a track ref), index, name, type, selected, launcherSlots [{{ref (clip), playing}}] occupied slots of scenes 0-{SCENE_WINDOW - 1} only}}], launcherSlotsComplete (false when more scenes exist: read them with launcher.list), selectedTrack (track ref) and cursorClip (clip ref): the clip the user has selected in the launcher/editor, at any scene. Unobserved values are null, never guessed; cursorClip is null when no clip is selected or it lies outside the observed {TRACK_WINDOW} tracks. Your own notes.get/notes.set/clips.insert calls move cursorClip. Waits for a running scene-window operation."
PROJECT_SAVE_DESCRIPTION = "Save the project via the native Save action; outcome applied only when Project.isModified is observed false (already_matched if nothing to save). Otherwise outcome unknown, reason not_observed, never re-invoked: an untitled project opens a Save As dialog needing GUI/user completion, and an already open modal dialog (e.g. Recover Projects after a crash) blocks the save unseen. Check projectModified before retrying."
PROJECT_SAVE_AS_DESCRIPTION = (
    "Save the current project to an explicit path without a dialog (also an untitled one), through Bitwig's own Save Project pipeline; the open project then lives there (name = file name). path is the final file: <projects>/<Name>/<Name>.bwproject creates the project folder <Name>, or name a .bwproject inside an existing project folder. Refused before dispatch (an error, nothing changed): a path outside Bitwig's projects folder unless allowAnyPath, an existing file unless overwrite, an existing non-project folder, auto-backups. outcome applied only with Bitwig's completion callback, the file written (nonempty, new or changed), projectModified=false and projectName = Name. host_refused (an error, nothing written) when Bitwig refused (recording, already saving); job_failed is outcome unknown; no confirmation before the deadline is outcome unknown, reason deadline and job: follow it with jobs.get (project saves are busy until Bitwig's callback, or until you ack the job hold after inspecting). Inspect the file and status, never retry. Files in the old or another project folder are copied in (the dialog default); other external samples stay referenced. status detail=full projectSaveAs says whether the binding is available. The call waits up to "
    + str(LONG_DEFAULT_TIMEOUT_MS)
    + " ms."
)
PROJECT_ACTIVATE_ENGINE_DESCRIPTION = 'Activate the audio engine for the current project (native "Activate Engine For Project" action, dispatched once, never retried). After a Bitwig restart the recovered project can have engineActive=false: clip launches then return not_observed and captures are silent. outcome applied only when hasActiveEngine is observed true within the request deadline (already_matched if it already was); otherwise outcome unknown, reason not_observed. clips.launch, audio.capture and grid.probe refuse with engine_inactive until this succeeds.'

PROJECT_KEY_GET_DESCRIPTION = "Read the project Key Signature (the transport bar's Root Key and Scale) from Bitwig's native model: root and scale are {value, label, options:[{value, label}]} with Bitwig's own option lists (root 0..11; scale value is a pitch-class bit mask, e.g. Major 2741, Minor 1453; label null for a mask outside the list), scaleName is Bitwig's own name of the current scale ('minor', or 'custom [...]'). verification native_readback. Needs the native gate (exact supported Bitwig and JAR hash): native_unavailable otherwise, the public API has no key signature."
PROJECT_KEY_SET_DESCRIPTION = "Set the project Key Signature: root and/or scale, each an option label from project.key.get (case-insensitive, e.g. root 'D', scale 'Minor') or its integer value. A choice outside Bitwig's own list is invalid_params before anything is written. Changed fields are written in one native transaction, one Undo step ('Set Key Signature'), then read back from the model: per-field outcome applied/already_matched with verification native_readback, or unknown with readback_mismatch (inspect project.key.get, never retry; further key writes are refused until ack). Recording or automation write blocks it (unsafe_state); playback may run. native_unavailable without the native gate."


SPECS: dict[str, ToolSpec] = {
    "status": ToolSpec(StatusDetailArgs, STATUS_DESCRIPTION, True, examples=({}, {"detail": "full"})),
    "context.get": ToolSpec(StatusArgs, CONTEXT_GET_DESCRIPTION, True),
    "project.save": ToolSpec(StatusArgs, PROJECT_SAVE_DESCRIPTION, False, annotations=SET),
    "project.saveAs": ToolSpec(
        ProjectSaveAsArgs,
        PROJECT_SAVE_AS_DESCRIPTION,
        False,
        examples=({"path": "/home/me/Bitwig Studio/Projects/Agent Sketch/Agent Sketch.bwproject"},),
        annotations=EDIT,
    ),
    "project.activateEngine": ToolSpec(StatusArgs, PROJECT_ACTIVATE_ENGINE_DESCRIPTION, False, annotations=STEER),
    "project.key.get": ToolSpec(StatusArgs, PROJECT_KEY_GET_DESCRIPTION, True),
    "project.key.set": ToolSpec(
        ProjectKeySetArgs,
        PROJECT_KEY_SET_DESCRIPTION,
        False,
        examples=({"root": "D", "scale": "Minor"}, {"scale": 2741}),
        requires=NOT_RECORDING,
    ),
}
