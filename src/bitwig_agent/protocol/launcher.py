from __future__ import annotations

from decimal import Decimal
from typing import Annotated, Any, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    MAX_BEAT,
    TRACK,
    DeviceArgs,
    Nonempty,
    StatusArgs,
    StrictModel,
    ToolSpec,
    omitted,
    tool,
    validate_local_path,
)

MIDI_PITCH_COUNT = 128


class ClipArgs(DeviceArgs):
    slotIndex: int = Field(ge=0, le=15)


class ScenesAppendArgs(StatusArgs):
    count: Annotated[int, Field(ge=1, le=16)] = omitted()


class SceneTargetArgs(StatusArgs):
    sceneIndex: int = Field(ge=0, le=15, description="Zero-based scene index from scenes.list.")
    expectedName: str = Field(
        max_length=256,
        description='Compare-and-set guard: the scene\'s exact current name from scenes.list; for an unnamed scene its current positional label ("Scene N"), which shifts after inserts/deletes.',
    )


class SceneRenameArgs(SceneTargetArgs):
    name: str = Field(min_length=1, max_length=128)


class ClipNote(StrictModel):
    op: Literal["set"] = omitted()
    beat: float = Field(ge=0, le=MAX_BEAT)
    pitch: int = Field(ge=0, le=127)
    velocity: int = Field(ge=1, le=127)
    durationBeats: float = Field(ge=1 / 128, le=4096)


def check_note_spans(notes: list[Any]) -> None:
    """Grid-independent checks; the extension derives or validates the grid itself."""
    seen: set[tuple[Decimal, int]] = set()
    spans: dict[int, list[tuple[Decimal, Decimal]]] = {}
    for note in notes:
        beat = Decimal(str(note.beat))
        if beat / Decimal("0.0078125") % 1:
            raise ValueError("beat must be a multiple of 1/128 beat")
        if (beat, note.pitch) in seen:
            raise ValueError("duplicate edit cell")
        seen.add((beat, note.pitch))
        if getattr(note, "op", "set") == "clear":
            continue
        end = beat + Decimal(str(note.durationBeats))
        if Decimal(str(note.durationBeats)) / Decimal("0.0078125") % 1:
            raise ValueError("durationBeats must be a multiple of 1/128 beat")
        if any(beat < old_end and start < end for start, old_end in spans.setdefault(note.pitch, [])):
            raise ValueError("same-pitch set durations overlap")
        spans[note.pitch].append((beat, end))


class ClipCreateArgs(ClipArgs):
    lengthBeats: int = Field(ge=1, le=4096)
    name: str = Field(min_length=1, max_length=128)
    notes: Annotated[list[ClipNote], Field(min_length=1, max_length=256)] = omitted()
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()
    detail: Literal["compact", "full"] = omitted()

    @model_validator(mode="after")
    def validate_notes(self) -> Self:
        if "notes" not in self.model_fields_set:
            if {"channel", "detail"} & self.model_fields_set:
                raise ValueError("channel and detail are only valid together with notes")
            return self
        check_note_spans(self.notes)
        if any(Decimal(str(n.beat)) + Decimal(str(n.durationBeats)) > self.lengthBeats for n in self.notes):
            raise ValueError("notes must fit inside lengthBeats")
        return self


class ClipSettingsArgs(ClipArgs):
    expectedClipName: str = Field(min_length=1, max_length=256)


LAUNCH_SETTINGS = {"launchQuantization", "launchMode", "useLoopStartAsQuantizationReference"}


class ClipSettingsSetArgs(ClipSettingsArgs):
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: Annotated[int, Field(ge=1, le=64)] = omitted()
    nextActionDelayBeats: Annotated[float, Field(ge=0, le=MAX_BEAT)] = omitted()
    launchQuantization: Nonempty = omitted(
        description="Clip launch quantization: one of the host's own options, listed in the result's options.launchQuantization. Selects the clip."
    )
    launchMode: Nonempty = omitted(
        description="Clip launch mode: one of the host's own options, listed in the result's options.launchMode. Selects the clip."
    )
    useLoopStartAsQuantizationReference: bool = omitted(
        description="Quantize the launch to the clip's loop start. Selects the clip."
    )

    @model_validator(mode="after")
    def validate_settings(self) -> Self:
        settings = self.model_fields_set - {"trackId", "slotIndex", "expectedClipName", "timeoutMs"}
        if not settings:
            raise ValueError("supply at least one clip setting")
        launch = settings & LAUNCH_SETTINGS
        if launch and settings - launch:
            raise ValueError(
                "launch fields ("
                + ", ".join(sorted(LAUNCH_SETTINGS))
                + ") use a different path from the Next Action fields: send two calls"
            )
        return self


CLIP_EDIT_OPS = {
    "rename": {"name"},
    "resize": {"lengthBeats"},
    "color": {"rgb"},
    "transpose": {"semitones"},
    "quantize": {"amount"},
    "duplicateContent": set(),
    "delete": set(),
    "copyTo": {"targetSlotIndex"},
}


class ClipEditArgs(ClipSettingsArgs):
    op: Literal["rename", "resize", "color", "transpose", "quantize", "duplicateContent", "delete", "copyTo"]
    name: Annotated[str, Field(min_length=1, max_length=128)] = omitted()
    lengthBeats: Annotated[int, Field(ge=1, le=4096)] = omitted()
    rgb: Annotated[str, Field(pattern=r"^#[0-9a-fA-F]{6}$")] = omitted()
    semitones: Annotated[int, Field(ge=-48, le=48)] = omitted()
    amount: Annotated[float, Field(gt=0, le=1)] = omitted()
    targetSlotIndex: Annotated[int, Field(ge=0, le=15)] = omitted()

    @model_validator(mode="after")
    def validate_op_fields(self) -> Self:
        payload = self.model_fields_set & set().union(*CLIP_EDIT_OPS.values())
        if self.semitones == 0:
            raise ValueError("semitones must be nonzero")
        if payload != CLIP_EDIT_OPS[self.op]:
            raise ValueError(f"op {self.op} takes exactly {sorted(CLIP_EDIT_OPS[self.op]) or 'no extra fields'}")
        return self


class ClipImportArgs(ClipArgs):
    path: Nonempty

    @model_validator(mode="after")
    def validate_path(self) -> Self:
        validate_local_path(self.path)
        return self


GRID_FIELDS = {"stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount"}


class NoteWindowArgs(ClipArgs):
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()
    stepSizeBeats: Annotated[float, Field(ge=1 / 128, le=4)] = omitted()
    startStep: Annotated[int, Field(ge=0, le=1048576)] = omitted()
    steps: Annotated[int, Field(ge=1, le=128)] = omitted()
    pitchStart: Annotated[int, Field(ge=0, le=127)] = omitted()
    pitchCount: Annotated[int, Field(ge=1, le=MIDI_PITCH_COUNT)] = omitted()

    @property
    def explicit_window(self) -> bool:
        return bool(GRID_FIELDS & self.model_fields_set)

    @model_validator(mode="after")
    def validate_pitch_window(self) -> Self:
        supplied = GRID_FIELDS & self.model_fields_set
        if supplied and supplied != GRID_FIELDS:
            raise ValueError(
                "stepSizeBeats, startStep, steps, pitchStart and pitchCount must be supplied together or all omitted"
            )
        if supplied and self.pitchStart + self.pitchCount > MIDI_PITCH_COUNT:
            raise ValueError(f"pitchStart + pitchCount must not exceed {MIDI_PITCH_COUNT}")
        return self


class NoteEdit(StrictModel):
    op: Literal["set", "clear"]
    beat: float = Field(ge=0, le=MAX_BEAT)
    pitch: int = Field(ge=0, le=127)
    velocity: Annotated[int, Field(ge=1, le=127)] = omitted()
    durationBeats: Annotated[float, Field(ge=1 / 128, le=4096)] = omitted()

    @model_validator(mode="after")
    def validate_operation(self) -> Self:
        supplied = self.model_fields_set & {"velocity", "durationBeats"}
        if self.op == "set" and supplied != {"velocity", "durationBeats"}:
            raise ValueError("set requires velocity and durationBeats")
        if self.op == "clear" and supplied:
            raise ValueError("clear forbids velocity and durationBeats")
        return self


class NoteEditArgs(NoteWindowArgs):
    edits: list[NoteEdit] = Field(min_length=1, max_length=256)
    detail: Literal["compact", "full"] = omitted()

    @model_validator(mode="after")
    def validate_edits(self) -> Self:
        if not self.explicit_window:
            check_note_spans(self.edits)
            return self
        cells = set()
        intervals: dict[int, list[tuple[int, int]]] = {}
        grid = Decimal(str(self.stepSizeBeats))
        for edit in self.edits:
            beat = Decimal(str(edit.beat))
            if beat % grid:
                raise ValueError("beat must align exactly with stepSizeBeats")
            step = int(beat / grid)
            if not self.startStep <= step < self.startStep + self.steps:
                raise ValueError("edit beat is outside the requested step window")
            if not self.pitchStart <= edit.pitch < self.pitchStart + self.pitchCount:
                raise ValueError("edit pitch is outside the requested pitch window")
            cell = (step, edit.pitch)
            if cell in cells:
                raise ValueError("duplicate edit cell")
            cells.add(cell)
            if edit.op == "set":
                duration = Decimal(str(edit.durationBeats))
                if duration % grid:
                    raise ValueError("durationBeats must align exactly with stepSizeBeats")
                end = step + int(duration / grid)
                if end > self.startStep + self.steps:
                    raise ValueError("set duration extends beyond the requested step window")
                spans = intervals.setdefault(edit.pitch, [])
                if any(step < old_end and start < end for start, old_end in spans):
                    raise ValueError("same-pitch set durations overlap")
                spans.append((step, end))
        return self


CLIP = {"trackId": TRACK, "slotIndex": 0}
NOTE_WINDOW = {
    **CLIP,
    "channel": 0,
    "stepSizeBeats": 0.25,
    "startStep": 0,
    "steps": 16,
    "pitchStart": 0,
    "pitchCount": 128,
}


LAUNCHER_LIST_DESCRIPTION = "Discover occupied and empty launcher clip slots for one track UUID; slotIndex is zero-based within the bounded window; slots exist only up to the scene count (window.total). selected=true marks the slot of the UI cursor clip (null if not yet observed); notes.* calls move that selection."
LAUNCHER_GET_DESCRIPTION = "Read exact launcher clip timing and Next Action settings by track UUID, zero-based slot and expectedClipName. Source-bound native model access; no cursor selection. Requires the inspected Bitwig 6.1.4 JAR. Readback is not playback or persistence proof."
LAUNCHER_SET_DESCRIPTION = "Set only supplied launcher Next Action fields (timing and loopEnabled are read-only) on the exact occupied slot. Requires stopped transport with record/automation writes disabled and the inspected Bitwig 6.1.4 JAR. nextAction is play_next or stop; link=true, loopCount=1 follows after one clip loop. Returns native model readback; inspect every fields entry. Unknown writes quarantine further settings writes until extension reload; never retry automatically. launchQuantization, launchMode and useLoopStartAsQuantizationReference (guide 6.2.5.2) are a separate path through the public cursor clip: they SELECT the clip (moving the editor cursor, like clips.edit), cannot be combined with Next Action fields in one call, need the same stopped/non-recording state and exact expectedClipName, are written only when they differ and then verified by readback plus a value callback after the write (fields[].verification; options lists the host's own launchQuantization and launchMode values, anything else is refused before the selection moves), and launcher.get does not report them."
LAUNCHER_CREATE_DESCRIPTION = "Create a named MIDI clip only in an empty zero-based slot that exists (slotIndex < scene count; else slot_out_of_range: run scenes.append, then launcher.list), with loop length and playback stop set to lengthBeats. Selects that clip in the host UI to bind and verify the explicit target. Example: trackId=<UUID> slotIndex=0 lengthBeats=4 name=Sketch. Optional notes (op=set entries: beat, pitch, velocity, durationBeats; optional channel, default 0) are written in the same call after the clip is observed, with notes.edit's compact verification under noteWrite (detail=full for the full shape); notes must fit lengthBeats; an unobserved create writes none; never retried. Never overwrites an existing clip."
SCENES_APPEND_DESCRIPTION = 'Append empty scene(s) at the END of the scene list (count 1..16, default 1; total stays <=16), so existing clip slots never move (unlike actions.invoke "Create Scene", which inserts after the selection). Returns observed scene count and firstNewSlotIndex; placement is per API documentation, only the count is read back. Unobserved count is outcome unknown: inspect launcher.list, never blindly repeat. Observed on 6.1.4: appending can move the UI clip/scene selection, so re-read context.get afterwards.'
SCENES_LIST_DESCRIPTION = "List scenes in the launcher: sceneIndex (zero-based), exact name (an unnamed scene reports its positional label 'Scene N', which shifts after inserts/deletes; null until observed) and clipCount (occupied slots on the observed tracks). Use sceneIndex and the exact name as the guard for scenes.rename/launch/duplicate/delete. Does not select or move the cursor."
SCENES_RENAME_DESCRIPTION = "Rename one scene: sceneIndex plus its exact current expectedName (from scenes.list; stale_target/not_applied otherwise). Verified by the name observer after the write (already_at_value when unchanged); an unobserved write stays status=dispatched, completion=unknown: re-read scenes.list, never blindly repeat."
SCENES_LAUNCH_DESCRIPTION = "Launch one scene (every clip in its row). Playback can produce audio: needs the audio engine active and recording/automation write off. Observed when every occupied slot of the scene is playing or queued; a scene without clips has nothing to observe and reports dispatched/not_observed. Quantization applies as in the GUI."
SCENES_DUPLICATE_DESCRIPTION = "Duplicate one scene with its clips; the copy goes directly after the source (Bitwig's Duplicate). Total scenes stay <= 16 (scene_limit). Verified by the scene count and the neighbouring names (earlier names unchanged, later ones shifted by one); otherwise unknown: inspect scenes.list, never blindly repeat. Needs a settled project and no pending/unresolved launcher mutation."
SCENES_DELETE_DESCRIPTION = "Delete one scene AND the clips in its row (Bitwig's Delete; clipsDeleted in the result says how many). Needs a stopped transport, recording/automation off, none of the scene's clips playing, queued or recording, and at least two scenes. sceneIndex + exact expectedName; later scenes shift up. Verified by the count and the remaining names; otherwise unknown: inspect scenes.list, never blindly repeat (indices have shifted)."
LAUNCHER_IMPORT_DESCRIPTION = "Import a local audio or clip file into an EMPTY explicit launcher slot by track UUID and zero-based slotIndex (0..15, must be below the scene count). Occupied slots reject; no overwrite option. path must be absolute and normalized, without URI, shell/path expansion or control characters. The host checks file existence/readability; .mid/.midi files must parse and contain a positive-velocity note-on or another channel event (use launcher.create for silence). The client checks path syntax only. Result reports only observed slot content transition, not file identity or decoded audio verification. Never blindly retry an unknown outcome."
CLIPS_EDIT_DESCRIPTION = "Whole-clip edit of ONE existing launcher clip per call: trackId, slotIndex, expectedClipName (exact current name from launcher.list/context.get; mismatch is stale_target/not_applied before anything is dispatched) and op. Ops: rename{name}; resize{lengthBeats 1..4096: loop length and play stop}; color{rgb #RRGGBB; Bitwig may snap it to its palette, reported as dispatched/readback_mismatch}; transpose{semitones -48..48, nonzero; no observed channel-0 loop note may leave MIDI 0..127; notes on other channels or outside the loop are moved but not checked}; quantize{amount >0..1: morphs note starts between original (0) and the host's own quantize grid (1), a Bitwig setting not settable here}; duplicateContent (doubles the loop); delete (stopped transport, recording/automation-write off, clip idle); copyTo{targetSlotIndex: an existing, observed-empty slot on the same track, else slot_occupied/slot_out_of_range; nameChanged is reported if the host renames the copy}. Except delete and copyTo, it selects the clip in the host UI (moves the cursor clip, see context.get). Each result says what was read back: rename name, resize loop+play stop, color, delete slot empty, copyTo target content+name, transpose channel-0 onset cells shifted with velocity/duration kept, quantize onset count/pitches/loop length unchanged with at least one cell moved (sub-cell positions are not observable), duplicateContent loop doubled and first half unchanged. transpose and quantize read the whole loop in one 1/32-1/16 window, so need a loop <=32 beats with channel-0 notes (else unverifiable_clip/no_notes, nothing changed); duplicateContent has neither check. Other channels are not compared. rename/resize/color are idempotent: an unread value gives status=dispatched/readback_mismatch. quantize amount 1 with no visible cell movement is status=dispatched/no_cell_change (idempotent, no quarantine). delete, copyTo, transpose, quantize below amount 1 and duplicateContent are never retried: an unobserved outcome is unknown and quarantines that slot until extension.restart. Example: op=transpose semitones=-2."
LAUNCHER_LAUNCH_DESCRIPTION = "Launch an existing clip by explicit track UUID and zero-based slot. Can start playback and produce audio. Recording and both automation-write flags must be observed false; modes are never silently disabled. Dispatch does not prove musical/audio completion. Queued playback returns completion=not_observed; observe launcher.list before dependent edits. Missing queue/playback observation returns completion=unknown: stop the writer, never blindly retry."
LAUNCHER_STOP_DESCRIPTION = "Stop launcher playback on one track UUID; does not delete clips or stop the entire transport. A queued stop returns completion=not_observed; observe launcher.list before dependent edits. Missing queue/stop observation returns completion=unknown: stop the writer, never blindly retry."
NOTES_READ_DESCRIPTION = "Read a clip/channel/grid/pitch window. Selects the target clip in the host UI (moves the editor cursor; see context.get). channel is zero-based MIDI 0..15; beat 0 is clip start. stepSizeBeats=0.25,startStep=0,steps=16 covers four beats. Omit all five grid fields (channel defaults to 0) to read the whole clip loop over all pitches at 1/32 (loop <=16 beats) or 1/16 note, 128 steps max; if the loop is longer, windowCoversLoop=false and continuation gives the next explicit window. Rows are {beat, pitch, velocity 1..127, durationBeats}: the unit notes.edit takes; channel is the window's. At most 2048 onsets returned: inspect completeWindow, notesInWindow, notesReturned, continuingAtWindowStart (notes sustaining into the window start) and subdivide steps or pitchCount when incomplete. Grid data is only scheduled-settled (gridReadiness), not a host-data barrier; late callbacks lack window identity. One onset per grid cell: sub-step and multiple onsets per cell are not exposed; the read never covers the whole clip (use the loop window)."
NOTES_EDIT_DESCRIPTION = 'Edit up to 256 onset cells. Selects the target clip in the host UI (moves the editor cursor; see context.get). The window (stepSizeBeats,startStep,steps,pitchStart,pitchCount; all five or none) may be omitted: the finest power-of-two grid (down to 1/128 beat) that aligns every beat/duration within 128 steps, and the pitch range, are derived (window echoed with derived=true); >128 steps is rejected, so split by time or use a coarser grid. channel defaults to 0. Each set requires op=set, absolute beat, MIDI pitch, integer velocity 1..127 and durationBeats; clear requires only op=clear,beat,pitch. Beat/duration must align exactly to stepSizeBeats without rounding; entire note must fit requested window and observed clip loop. No duplicate cells, overlapping same-pitch sets or clear-all; sustains are protected and already-matching cells are not rewritten. Example edits=[{"op":"set","beat":0,"pitch":60,"velocity":90,"durationBeats":1}]. Result is compact: status/verification cover every edit; summary {requested, dispatched, alreadyMatched, verified, unverified}; unverifiedEdits lists only unverified edits with the observed cell. detail=full also returns the window\'s note listing (as notes.read) and every edit. Verification is per-cell readback after scheduled grid settling (gridReadiness), not a host-data barrier. Verify unverified edits with notes.read. No automatic retry.'


SPECS: dict[str, ToolSpec] = {
    "launcher.list": tool(DeviceArgs, LAUNCHER_LIST_DESCRIPTION, True, {"trackId": TRACK}),
    "launcher.get": tool(ClipSettingsArgs, LAUNCHER_GET_DESCRIPTION, True, {**CLIP, "expectedClipName": "Sketch"}),
    "launcher.set": tool(
        ClipSettingsSetArgs,
        LAUNCHER_SET_DESCRIPTION,
        False,
        {
            **CLIP,
            "expectedClipName": "Sketch",
            "nextAction": "play_next",
            "nextActionEnabled": True,
            "nextActionLinkToLoop": True,
            "nextActionLoopCount": 1,
            "nextActionDelayBeats": 0,
        },
        {
            **CLIP,
            "expectedClipName": "Sketch",
            "launchQuantization": "1/4",
            "useLoopStartAsQuantizationReference": True,
        },
    ),
    "launcher.create": tool(
        ClipCreateArgs,
        LAUNCHER_CREATE_DESCRIPTION,
        False,
        {**CLIP, "lengthBeats": 4, "name": "Sketch"},
        {
            **CLIP,
            "lengthBeats": 4,
            "name": "Sketch",
            "notes": [{"beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1}],
        },
    ),
    "scenes.append": tool(ScenesAppendArgs, SCENES_APPEND_DESCRIPTION, False, {}, {"count": 2}),
    "scenes.list": tool(StatusArgs, SCENES_LIST_DESCRIPTION, True),
    "scenes.rename": tool(
        SceneRenameArgs, SCENES_RENAME_DESCRIPTION, False, {"sceneIndex": 0, "expectedName": "", "name": "Intro"}
    ),
    "scenes.launch": tool(
        SceneTargetArgs, SCENES_LAUNCH_DESCRIPTION, False, {"sceneIndex": 0, "expectedName": "Intro"}
    ),
    "scenes.duplicate": tool(
        SceneTargetArgs, SCENES_DUPLICATE_DESCRIPTION, False, {"sceneIndex": 0, "expectedName": "Intro"}
    ),
    "scenes.delete": tool(
        SceneTargetArgs, SCENES_DELETE_DESCRIPTION, False, {"sceneIndex": 0, "expectedName": "Intro"}
    ),
    "launcher.import": tool(
        ClipImportArgs, LAUNCHER_IMPORT_DESCRIPTION, False, {**CLIP, "path": "/absolute/path/audio.wav"}
    ),
    "clips.edit": tool(
        ClipEditArgs,
        CLIPS_EDIT_DESCRIPTION,
        False,
        {**CLIP, "expectedClipName": "Sketch", "op": "rename", "name": "Verse"},
        {**CLIP, "expectedClipName": "Sketch", "op": "resize", "lengthBeats": 8},
        {**CLIP, "expectedClipName": "Sketch", "op": "color", "rgb": "#ff8800"},
        {**CLIP, "expectedClipName": "Sketch", "op": "transpose", "semitones": -2},
        {**CLIP, "expectedClipName": "Sketch", "op": "quantize", "amount": 0.5},
        {**CLIP, "expectedClipName": "Sketch", "op": "duplicateContent"},
        {**CLIP, "expectedClipName": "Sketch", "op": "delete"},
        {**CLIP, "expectedClipName": "Sketch", "op": "copyTo", "targetSlotIndex": 1},
    ),
    "launcher.launch": tool(ClipArgs, LAUNCHER_LAUNCH_DESCRIPTION, False, CLIP),
    "launcher.stop": tool(DeviceArgs, LAUNCHER_STOP_DESCRIPTION, False, {"trackId": TRACK}),
    "notes.read": tool(NoteWindowArgs, NOTES_READ_DESCRIPTION, False, NOTE_WINDOW, CLIP),
    "notes.edit": tool(
        NoteEditArgs,
        NOTES_EDIT_DESCRIPTION,
        False,
        {**NOTE_WINDOW, "edits": [{"op": "set", "beat": 0.0, "pitch": 60, "velocity": 90, "durationBeats": 1.0}]},
        {**CLIP, "edits": [{"op": "set", "beat": 0.0, "pitch": 60, "velocity": 90, "durationBeats": 1.0}]},
    ),
}
