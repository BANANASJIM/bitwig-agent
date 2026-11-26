from __future__ import annotations

from decimal import ROUND_FLOOR, ROUND_HALF_UP, Decimal
from typing import Annotated, Any, Literal, NamedTuple, Self

from pydantic import Discriminator, Field, Tag, model_validator

from bitwig_agent.protocol.annotations import EDIT, RETRIGGER, STEER
from bitwig_agent.protocol.base import (
    ENGINE,
    MAX_BEAT,
    PROJECT_READY,
    Nonempty,
    ReasonError,
    StatusArgs,
    StrictModel,
    ToolSpec,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.launcher import ARRANGER_CLIP_REF, CLIP_REF, SLOT_REF, TRACK_REF
from bitwig_agent.protocol.limits import (
    BEAT_GRID,
    BEAT_GRID_DIVISOR,
    BEAT_GRID_LABEL,
    DURATION_RESOLUTION_DIVISOR,
    MAX_CLIP_BEATS,
)
from bitwig_agent.protocol.refs import ArrangerClipRef, Beats, ClipRef, LauncherClipRef, SlotRef, TrackRef
from bitwig_agent.protocol.values import Expect

MIDI_PITCH_COUNT = 128
MAX_ARRANGER_CLIP_BEATS = 65536
MAX_TRANSFORM_TARGETS = 16
COLOR = r"^#[0-9a-fA-F]{6}$"


class ClipNote(StrictModel):
    beat: float = Field(ge=0, le=MAX_BEAT, description="Onset in beats from the clip start, on a grid of 1/128 beat.")
    pitch: int = Field(ge=0, le=127)
    velocity: int = Field(ge=1, le=127)
    durationBeats: float = Field(ge=BEAT_GRID, le=MAX_CLIP_BEATS)


ONSET_GRID = Decimal(1) / BEAT_GRID_DIVISOR


def plain(value: Decimal) -> str:
    return format(value.normalize(), "f")


DURATION_STEP = Decimal(1) / DURATION_RESOLUTION_DIVISOR


def stored_duration(duration: Decimal) -> Decimal:
    """The duration Bitwig stores for a requested one: the nearest multiple of its duration resolution."""
    return (duration / DURATION_STEP).quantize(Decimal(1), rounding=ROUND_HALF_UP) * DURATION_STEP


class NoteWindow(NamedTuple):
    grid: Decimal
    start: int
    steps: int
    pitch: int
    pitches: int


class Span(NamedTuple):
    position: int
    pitch: int
    is_set: bool
    beat: Decimal
    end: Decimal  # as Bitwig stores it: what every rule judges
    requested_end: Decimal  # what messages show


def window_problem(at: str, span: Span, window: NoteWindow) -> str | None:
    step, rest = divmod(span.beat, window.grid)
    window_end = (window.start + window.steps) * window.grid
    if rest:
        return (
            f"{at} beat {plain(span.beat)} does not align with stepSizeBeats {plain(window.grid)}: use a multiple of it"
        )
    if not (
        window.start <= step < window.start + window.steps
        and window.pitch <= span.pitch < window.pitch + window.pitches
    ):
        return (
            f"{at} (beat {plain(span.beat)}, pitch {span.pitch}) is outside the window: beats "
            f"{plain(window.start * window.grid)} to {plain(window_end)}, pitches {window.pitch} to "
            f"{window.pitch + window.pitches - 1}"
        )
    if span.end > window_end:
        return (
            f"{at} ends at beat {plain(span.requested_end)}, past the window end {plain(window_end)}: shorten it to "
            f"durationBeats <= {plain(window_end - span.beat)}"
        )
    return None


def onset_problem(at: str, span: Span) -> str | None:
    if not span.beat % ONSET_GRID:
        return None
    low = (span.beat / ONSET_GRID).to_integral_value(rounding=ROUND_FLOOR) * ONSET_GRID
    return f"{at} beat {plain(span.beat)} is not on the {BEAT_GRID_LABEL}-beat grid: use {plain(low)} or {plain(low + ONSET_GRID)}"


def length_problem(at: str, span: Span, length: Decimal) -> str | None:
    if not span.is_set or span.end <= length:
        return None
    if span.beat >= length:
        return f"{at} starts at beat {plain(span.beat)}, at or past the clip length {plain(length)}"
    return (
        f"{at} ends at beat {plain(span.requested_end)}, past the clip length {plain(length)}: shorten it to durationBeats <= "
        f"{plain(length - span.beat)}"
    )


def collision_problems(spans: list[Span], field: str) -> list[tuple[int, str]]:
    """Repeated cells, then sets that start before an earlier same-pitch set ends."""
    found = []
    cells: dict[tuple[str, int], int] = {}
    for span in spans:
        first = cells.setdefault((plain(span.beat), span.pitch), span.position)
        if first != span.position:
            found.append(
                (span.position, f"{field}[{span.position}] repeats the beat and pitch of {field}[{first}]: remove one")
            )
    holders: dict[int, Span] = {}  # per pitch, the set seen so far that ends last
    for span in sorted(spans, key=lambda s: (s.beat, s.position)):
        if not span.is_set:
            continue
        holder = holders.get(span.pitch)
        if holder is not None and holder.beat != span.beat and holder.end > span.beat:
            found.append(
                (
                    span.position,
                    f"{field}[{span.position}] starts at beat {plain(span.beat)} before {field}[{holder.position}] on pitch "
                    f"{span.pitch} ends at beat {plain(holder.requested_end)}: shorten {field}[{holder.position}] to durationBeats <= "
                    f"{plain(span.beat - holder.beat)}",
                )
            )
        if holder is None or span.end > holder.end:
            holders[span.pitch] = span
    return found


def note_violations(
    notes: list[Any], field: str, length: Decimal | None = None, window: NoteWindow | None = None
) -> list[str]:
    """Every grid and clip rule the entries break, in request order, with the extension's texts.

    Note ends are judged as Bitwig stores them; the extension derives or checks the window itself.
    """
    spans = []
    for index, note in enumerate(notes):
        beat = Decimal(str(note.beat))
        is_set = getattr(note, "op", "set") == "set"
        duration = Decimal(str(note.durationBeats)) if is_set else Decimal(0)
        spans.append(Span(index, note.pitch, is_set, beat, beat + stored_duration(duration), beat + duration))
    found: list[tuple[int, str]] = []
    for span in spans:
        at = f"{field}[{span.position}]"
        problems = (
            window_problem(at, span, window) if window is not None else onset_problem(at, span),
            length_problem(at, span, length) if length is not None else None,
        )
        found += [(span.position, text) for text in problems if text]
    found += collision_problems(spans, field)
    return [text for _, text in sorted(found, key=lambda item: item[0])]


def refuse_violations(field: str, found: list[str]) -> None:
    if found:
        noun = "problem" if len(found) == 1 else "problems"
        raise ValueError(f"{field}: {len(found)} {noun}, nothing was written: " + "; ".join(found))


def require_location(target: Any, location: type, method: str, label: str) -> None:
    if not isinstance(target, location):
        raise ReasonError("unsupported_scope", f"{method} takes a {label} clip in this version")


class InsertLoop(StrictModel):
    lengthBeats: int = Field(ge=1, le=MAX_CLIP_BEATS, description="Loop length and playback stop of the new clip.")


class SetLoop(StrictModel):
    lengthBeats: float = Field(
        ge=BEAT_GRID, le=MAX_CLIP_BEATS, description="Loop length; the play stop moves to the new loop end."
    )


class ArrangerDestination(StrictModel):
    track: TrackRef
    startBeats: Beats


def _destination_tag(value: Any) -> str:
    return "slot" if (value.get("kind") if isinstance(value, dict) else getattr(value, "kind", None)) else "arranger"


Destination = Annotated[
    Annotated[SlotRef, Tag("slot")] | Annotated[ArrangerDestination, Tag("arranger")], Discriminator(_destination_tag)
]
"""Where a clip lands: a launcher slot, or a track at a time on the arranger timeline."""


class ClipTargetArgs(StatusArgs):
    target: LauncherClipRef


class ClipInsertArgs(StatusArgs):
    to: Destination
    name: str = Field(min_length=1, max_length=128)
    loop: InsertLoop = omitted(description="Launcher slot only: the loop length.")
    lengthBeats: Annotated[float, Field(ge=BEAT_GRID, le=MAX_ARRANGER_CLIP_BEATS)] = omitted(
        description="Arranger position only: the clip length."
    )
    notes: Annotated[list[ClipNote], Field(min_length=1, max_length=256)] = omitted()
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()
    detail: Literal["compact", "full"] = omitted()

    @model_validator(mode="after")
    def validate_shape(self) -> Self:
        launcher = isinstance(self.to, SlotRef)
        supplied = self.model_fields_set
        if launcher and ("loop" not in supplied or "lengthBeats" in supplied):
            raise ValueError("a launcher slot takes loop:{lengthBeats}, not lengthBeats")
        if not launcher and ("lengthBeats" not in supplied or "loop" in supplied):
            raise ValueError("an arranger position takes lengthBeats, not loop")
        if "notes" not in supplied:
            if {"channel", "detail"} & supplied:
                raise ValueError("channel and detail are only valid together with notes")
            return self
        if not launcher and "detail" in supplied:
            raise ValueError("detail applies to launcher slots only")
        length = Decimal(str(self.loop.lengthBeats if launcher else self.lengthBeats))
        refuse_violations("notes", note_violations(self.notes, "notes", length))
        return self


class ClipImportArgs(StatusArgs):
    to: SlotRef
    path: Nonempty

    @model_validator(mode="after")
    def validate_path(self) -> Self:
        validate_local_path(self.path)
        return self


class LoopExpect(StrictModel):
    lengthBeats: float


class ClipExpect(Expect):
    """The current values readable without selecting the clip: color and the launch fields are not."""

    name: str = omitted()
    lengthBeats: float = omitted()
    loop: LoopExpect = omitted()
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: int = omitted()
    nextActionDelayBeats: float = omitted()


NEXT_ACTION = (
    "nextAction",
    "nextActionEnabled",
    "nextActionLinkToLoop",
    "nextActionLoopCount",
    "nextActionDelayBeats",
)
LAUNCH = ("launchQuantization", "launchMode", "useLoopStartAsQuantizationReference")
LAUNCHER_ONLY = ("color", "loop", *NEXT_ACTION, *LAUNCH)
SETTINGS = ("name", "lengthBeats", *LAUNCHER_ONLY)


class ClipSetArgs(StatusArgs):
    target: ClipRef
    name: Annotated[str, Field(min_length=1, max_length=128)] = omitted()
    color: Annotated[str, Field(pattern=COLOR)] = omitted(description='"#rrggbb"; Bitwig may snap it to its palette.')
    lengthBeats: Annotated[float, Field(ge=BEAT_GRID, le=MAX_ARRANGER_CLIP_BEATS)] = omitted(
        description="Arranger clip only: the timeline length; the start stays."
    )
    loop: SetLoop = omitted(description="Launcher clip only.")
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: Annotated[int, Field(ge=1, le=64)] = omitted()
    nextActionDelayBeats: Annotated[float, Field(ge=0, le=MAX_BEAT)] = omitted()
    launchQuantization: Nonempty = omitted(
        description="One of the host's own options, listed in the result's options.launchQuantization. Selects the clip."
    )
    launchMode: Nonempty = omitted(
        description="One of the host's own options, listed in the result's options.launchMode. Selects the clip."
    )
    useLoopStartAsQuantizationReference: bool = omitted(
        description="Quantize the launch to the clip's loop start. Selects the clip."
    )
    expect: ClipExpect = omitted()

    @model_validator(mode="after")
    def validate_settings(self) -> Self:
        supplied = self.model_fields_set
        if not supplied & set(SETTINGS):
            raise ValueError("supply at least one clip setting")
        expected = self.expect.model_fields_set if self.expect is not None else set()
        if isinstance(self.target, ArrangerClipRef):
            wrong = [key for key in LAUNCHER_ONLY if key in supplied] + [
                f"expect.{key}" for key in expected & set(LAUNCHER_ONLY)
            ]
            hint = "an arranger clip takes name and lengthBeats"
        else:
            wrong = [key for key in ("lengthBeats",) if key in supplied] + [
                f"expect.{key}" for key in expected & {"lengthBeats"}
            ]
            hint = "a launcher clip takes loop:{lengthBeats}"
        if wrong:
            raise ReasonError("unsupported_scope", f"{', '.join(wrong)} cannot be set on this clip: {hint}")
        return self


class ClipDeleteArgs(StatusArgs):
    target: ClipRef


class ClipCopyArgs(StatusArgs):
    from_: LauncherClipRef = Field(alias="from")
    to: Destination


class ArrangerMove(StrictModel):
    startBeats: Beats


class ClipMoveArgs(StatusArgs):
    target: ClipRef
    to: ArrangerMove

    @model_validator(mode="after")
    def validate_location(self) -> Self:
        require_location(self.target, ArrangerClipRef, "clips.move", "arranger")
        return self


class ClipTransposeArgs(ClipTargetArgs):
    semitones: int = Field(ge=-48, le=48)

    @model_validator(mode="after")
    def validate_semitones(self) -> Self:
        if self.semitones == 0:
            raise ValueError("semitones must be nonzero")
        return self


class ClipQuantizeArgs(ClipTargetArgs):
    amount: float = Field(gt=0, le=1)


class ClipTransformArgs(StatusArgs):
    targets: Annotated[list[LauncherClipRef], Field(min_length=1, max_length=MAX_TRANSFORM_TARGETS)]
    op: Literal["doubleContent"]


GRID_FIELDS = {"stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount"}


class NoteWindowArgs(ClipTargetArgs):
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()
    stepSizeBeats: Annotated[float, Field(ge=BEAT_GRID, le=4)] = omitted()
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
    durationBeats: Annotated[float, Field(ge=BEAT_GRID, le=MAX_CLIP_BEATS)] = omitted()

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
        window = (
            NoteWindow(Decimal(str(self.stepSizeBeats)), self.startStep, self.steps, self.pitchStart, self.pitchCount)
            if self.explicit_window
            else None
        )
        refuse_violations("edits", note_violations(self.edits, "edits", window=window))
        return self


NOTE_WINDOW = {
    "target": CLIP_REF,
    "channel": 0,
    "stepSizeBeats": 0.25,
    "startStep": 0,
    "steps": 16,
    "pitchStart": 0,
    "pitchCount": 128,
}


CLIPS_GET_DESCRIPTION = (
    "Read exact launcher clip timing and Next Action settings of one clip: loop {lengthBeats}, play and loop starts, "
    "nextAction fields, in the shape clips.set writes. Source-bound native model access; no cursor selection. "
    "launchQuantization, launchMode and useLoopStartAsQuantizationReference are not read back here (they need the clip selected): "
    "the per-field outcomes of clips.set are their evidence. Requires the inspected Bitwig 6.1.4 JAR. Readback is not "
    "playback or persistence proof."
)
NOTE_RULES = (
    f"Note rules: beats on a {BEAT_GRID_LABEL}-beat grid; any duration (Bitwig stores it to 1/1048576 beat, and "
    "readback is compared at that step); no two notes on one beat and pitch, no same-pitch overlap. Every broken rule "
    "is refused at once (each with its index and a fix) and nothing is written. "
)
NOTE_WINDOWS = (
    "Notes are written through 128-step windows of the finest power-of-two grid that aligns every onset; a span "
    "too long for one runs as several windows of the coarsest aligning grid in the same call: windows[] has each "
    "window's result, outcome is the worst, and after a window that does not succeed the rest are not_reached, "
    "their indices in notReached. "
)
CLIPS_INSERT_DESCRIPTION = (
    "Create a named MIDI clip in an EMPTY launcher slot (to: a slot ref from launcher.list, scene included; loop "
    "{lengthBeats} sets loop length and playback stop) or on an instrument track's arranger timeline (to {track, "
    "startBeats}, lengthBeats; refused on overlap, never overwrites). Optional notes (beat, pitch, velocity, "
    "durationBeats; optional channel, default 0) are written in the same call and must fit the length. "
    + NOTE_RULES
    + "Launcher: the note result is noteWrite, in notes.edit's shape (detail=full for the full one). "
    + NOTE_WINDOWS
    + "A launcher create selects the clip in the host UI. Returns the new clip's ref. An unobserved create is "
    "outcome unknown (reason not_observed): inspect, then ack the hold; never retry."
)
CLIPS_IMPORT_DESCRIPTION = (
    "Import a local audio or clip file into an EMPTY launcher slot (to: a slot ref). Occupied slots reject; no "
    "overwrite option. path must be absolute and normalized, without URI, shell/path expansion or control "
    "characters. The host checks file existence/readability; .mid/.midi files must parse and contain a "
    "positive-velocity note-on or another channel event (use clips.insert for silence). Outcome applied reports only "
    "an observed slot content transition, not file identity or decoded audio verification. An unobserved insertion is "
    "outcome unknown and holds the slot until you inspect it and ack the hold; never retry it."
)
CLIPS_SET_DESCRIPTION = (
    "Set the supplied fields of ONE existing clip; each fields entry has its own outcome and expect compares bare "
    "current values first (stale/target_changed with observed). Launcher clip: name, color (#rrggbb; Bitwig may snap "
    "it, then unknown/readback_mismatch), loop {lengthBeats} (also moves the play stop), Next Action fields "
    "(nextAction play_next|stop, nextActionEnabled, nextActionLinkToLoop, nextActionLoopCount, nextActionDelayBeats; "
    "native, no selection) and launchQuantization, launchMode, useLoopStartAsQuantizationReference (select the clip; "
    "options lists the host's own values; clips.get does not report them, the per-field outcomes are the evidence). Arranger clip: name, lengthBeats (the start stays; overlap is refused). "
    "Next Action and launch fields need a stopped, non-recording transport. Paths run in the order next action, launch, clip; the first that does "
    "not succeed stops the rest (not_applied, not_reached). An arranger set needs a settled project and a stopped transport. Returns the new ref. Never retry unknown."
)
CLIPS_DELETE_DESCRIPTION = (
    "Delete one clip (launcher or arranger). Needs a stopped transport, recording/automation-write off and the clip "
    "idle; an arranger delete also needs a settled project. Verified by the slot being empty (launcher) or the clip absent from the lane (arranger). An unobserved "
    "delete is outcome unknown and holds that slot until you inspect it and ack the hold; never retry."
)
CLIPS_COPY_DESCRIPTION = (
    "Copy an occupied launcher clip (from) to another empty slot of the same track (to: a slot ref) or onto the same "
    "track's arranger timeline (to {track, startBeats}; Bitwig's copy of launcher clips, any overlap is refused). "
    "Slot to slot works only within 16 scenes of the source (else unsupported_scope: copy via an empty slot within 16 "
    "scenes, or notes.read + clips.insert {notes} for MIDI clips). Returns the new clip's ref; nameChanged is "
    "reported if the host renames a slot copy. Needs a stopped transport, recording/automation-write off; arranger "
    "writes also need a settled project. An unobserved copy is outcome unknown and holds that slot; never retry."
)
CLIPS_MOVE_DESCRIPTION = (
    "Move one arranger clip to to.startBeats on its own track; the length is kept and an overlap with another clip "
    "is refused. One native undoable edit verified by native readback; already_matched when it is already there. "
    "Arranger writes need a settled project and a stopped transport with recording/automation-write off; an unknown "
    "outcome refuses further arranger writes until you inspect arranger.list and ack the hold."
)
CLIPS_LAUNCH_DESCRIPTION = (
    "Launch an existing launcher clip. Can start playback and produce audio. Recording and both automation-write "
    "flags must be observed false; modes are never silently disabled. Outcome applied means the clip was observed "
    "playing, not that audio is heard. Queued playback returns outcome sent; observe launcher.list before dependent "
    "edits. Missing queue/playback observation returns outcome unknown (reason not_observed): stop the writer, "
    "never blindly retry."
)
CLIPS_TRANSPOSE_DESCRIPTION = (
    "Transpose the channel-0 notes of one launcher clip by semitones (-48..48, nonzero; no observed loop note may "
    "leave MIDI 0..127). Selects the clip in the host UI. Reads the whole loop in one 1/32-1/16 window, so needs a "
    "loop <=32 beats with channel-0 notes (else unverifiable_clip/no_notes, nothing changed). Verified by every "
    "onset cell having moved with velocity and duration kept. Never retried: an unobserved outcome is unknown and "
    "holds that slot until you inspect it and ack the hold."
)
CLIPS_QUANTIZE_DESCRIPTION = (
    "Quantize the channel-0 notes of one launcher clip: amount >0..1 morphs note starts between original (0) and the "
    "host's own quantize grid (1), a Bitwig setting not settable here. Selects the clip. Same loop limits as "
    "clips.transpose. Verified by unchanged onset count, pitches and loop length with at least one cell moved "
    "(sub-cell positions are not observable); amount 1 with no visible movement is unknown/not_observed (idempotent, "
    "no hold). Below amount 1 an unobserved outcome holds the slot; never retry."
)
CLIPS_TRANSFORM_DESCRIPTION = (
    "Apply op to each launcher clip of targets in order: doubleContent doubles the loop and repeats the content "
    "(selects the clip; verified by the loop length doubled and the first half unchanged). Every target is checked "
    "before any is written; one stale target refuses the whole batch (entries). Then it writes in order and stops at "
    "the first entry that is not applied, already_matched or sent (later entries not_applied, not_reached)."
)
NOTES_READ_DESCRIPTION = (
    "Read a clip/channel/grid/pitch window. Selects the target clip in the host UI (moves the editor cursor; see "
    "context.get). channel is zero-based MIDI 0..15; beat 0 is clip start. stepSizeBeats=0.25,startStep=0,steps=16 "
    "covers four beats. Omit all five grid fields (channel defaults to 0) to read the whole clip loop over all "
    "pitches at 1/32 (loop <=16 beats) or 1/16 note, 128 steps max; if the loop is longer, windowCoversLoop=false "
    "and continuation gives the next explicit window. Rows are {beat, pitch, velocity 1..127, durationBeats}: the "
    "unit notes.edit takes; channel is the window's. At most 2048 onsets returned: inspect completeWindow, "
    "notesInWindow, notesReturned, continuingAtWindowStart (notes sustaining into the window start) and subdivide "
    "steps or pitchCount when incomplete. Grid data is only scheduled-settled (gridReadiness), not a host-data "
    "barrier; late callbacks lack window identity. One onset per grid cell: sub-step and multiple onsets per cell "
    "are not exposed; the read never covers the whole clip (use the loop window)."
)
NOTES_EDIT_DESCRIPTION = (
    "Edit up to 256 onset cells. Selects the target clip in the host UI (moves the editor cursor; see context.get). "
    "The window (stepSizeBeats,startStep,steps,pitchStart,pitchCount; all five or none) may be omitted: it is then "
    "derived from the onsets and the pitch range (echoed with derived=true). "
    + NOTE_WINDOWS
    + "channel defaults to 0. Each set requires op=set, absolute beat, MIDI pitch, integer velocity 1..127 and "
    "durationBeats; clear requires only op=clear,beat,pitch. "
    + NOTE_RULES
    + "With an explicit window, beats align exactly to stepSizeBeats instead and every note must end inside it. "
    "Notes must fit the observed clip loop; sustains are protected, no clear-all, and already-matching cells are not "
    "rewritten. Result is compact: outcome is the worst over every edit (applied, already_matched, or unknown with "
    "a reason); summary {requested, dispatched, alreadyMatched, verified, unverified}; unverifiedEdits lists only "
    "unverified edits (index, outcome, reason, observed cell). detail=full also returns the window's note listing "
    "(as notes.read) and every edit. Verification is per-cell readback after scheduled grid settling "
    "(gridReadiness), not a host-data barrier. Verify unverified edits with notes.read. No automatic retry."
)
_EDIT = {"op": "set", "beat": 0.0, "pitch": 60, "velocity": 90, "durationBeats": 1.0}


SPECS: dict[str, ToolSpec] = {
    "clips.get": ToolSpec(ClipTargetArgs, CLIPS_GET_DESCRIPTION, True, examples=({"target": CLIP_REF},)),
    "clips.insert": ToolSpec(
        ClipInsertArgs,
        CLIPS_INSERT_DESCRIPTION,
        False,
        examples=(
            {"to": SLOT_REF, "loop": {"lengthBeats": 4}, "name": "Sketch"},
            {
                "to": SLOT_REF,
                "loop": {"lengthBeats": 4},
                "name": "Sketch",
                "notes": [{"beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1}],
            },
            {"to": {"track": TRACK_REF, "startBeats": 16}, "lengthBeats": 16, "name": "Verse"},
        ),
        requires=PROJECT_READY,
        summary="Create a named MIDI clip in an empty launcher slot (to: slot ref, loop {lengthBeats}) or on an instrument track's timeline (to {track, startBeats}, lengthBeats), with optional notes (beats on the 1/128 grid, any duration). Returns the new ref. A launcher create selects it. Unobserved: unknown, ack the hold, never retry.",
    ),
    "clips.import": ToolSpec(
        ClipImportArgs,
        CLIPS_IMPORT_DESCRIPTION,
        False,
        examples=({"to": SLOT_REF, "path": "/absolute/path/audio.wav"},),
        requires=PROJECT_READY,
        summary="Import a local audio or .mid file into an empty launcher slot (to: slot ref); path is absolute and normalized. Never overwrites; applied means a slot content transition was observed, not the file's content. An unobserved insertion is unknown and holds the slot until you inspect it and ack; never retry.",
    ),
    "clips.set": ToolSpec(
        ClipSetArgs,
        CLIPS_SET_DESCRIPTION,
        False,
        examples=(
            {"target": CLIP_REF, "name": "Verse", "loop": {"lengthBeats": 8}, "color": "#ff8800"},
            {
                "target": CLIP_REF,
                "nextAction": "play_next",
                "nextActionEnabled": True,
                "nextActionLinkToLoop": True,
                "nextActionLoopCount": 1,
                "nextActionDelayBeats": 0,
            },
            {"target": CLIP_REF, "launchQuantization": "1/4", "useLoopStartAsQuantizationReference": True},
            {"target": ARRANGER_CLIP_REF, "lengthBeats": 8},
        ),
        summary="Set fields of one clip. Launcher: name, color, loop, Next Action, launch fields; arranger: name, lengthBeats. Runs next action, launch, clip fields in order; the first failure stops the rest. Next Action and launch need a stopped transport.",
    ),
    "clips.delete": ToolSpec(ClipDeleteArgs, CLIPS_DELETE_DESCRIPTION, False, examples=({"target": CLIP_REF},)),
    "clips.copy": ToolSpec(
        ClipCopyArgs,
        CLIPS_COPY_DESCRIPTION,
        False,
        examples=({"from": CLIP_REF, "to": SLOT_REF}, {"from": CLIP_REF, "to": {"track": TRACK_REF, "startBeats": 0}}),
        summary="Copy a launcher clip (from) to an empty slot of the same track or onto its arranger timeline (to). Returns the new clip's ref.",
    ),
    "clips.move": ToolSpec(
        ClipMoveArgs,
        CLIPS_MOVE_DESCRIPTION,
        False,
        examples=({"target": ARRANGER_CLIP_REF, "to": {"startBeats": 32}},),
    ),
    "clips.launch": ToolSpec(
        ClipTargetArgs,
        CLIPS_LAUNCH_DESCRIPTION,
        False,
        examples=({"target": CLIP_REF},),
        requires=ENGINE,
        annotations=RETRIGGER,
        summary="Launch a launcher clip; can start playback. Needs the engine active and recording/automation write off. applied: observed playing; sent: queued; unknown: not observed, stop the writer and never retry blindly.",
    ),
    "clips.transpose": ToolSpec(
        ClipTransposeArgs,
        CLIPS_TRANSPOSE_DESCRIPTION,
        False,
        examples=({"target": CLIP_REF, "semitones": -2},),
        annotations=EDIT,
        summary="Transpose the channel-0 notes of a launcher clip by semitones (-48..48, nonzero) and read them back. Selects the clip. Needs a loop of at most 32 beats with channel-0 notes (else unverifiable_clip or no_notes, nothing changed). An unobserved result is unknown and holds the slot; never retry.",
    ),
    "clips.quantize": ToolSpec(
        ClipQuantizeArgs,
        CLIPS_QUANTIZE_DESCRIPTION,
        False,
        examples=({"target": CLIP_REF, "amount": 0.5},),
        annotations=EDIT,
        summary="Quantize the channel-0 notes of a launcher clip: amount 0..1 morphs note starts toward the host's quantize grid. Selects the clip. Same loop limits as clips.transpose. Needs one moved cell with count, pitches and loop unchanged. Below amount 1 an unobserved result holds the slot; never retry.",
    ),
    "clips.transform": ToolSpec(
        ClipTransformArgs,
        CLIPS_TRANSFORM_DESCRIPTION,
        False,
        examples=({"targets": [CLIP_REF], "op": "doubleContent"},),
        annotations=EDIT,
        summary="Apply op (doubleContent: double the loop and repeat its content) to each launcher clip in targets. Every target is checked before any is written; one stale target refuses the batch. Then it writes in order and stops at the first entry that is not applied, already_matched or sent. Selects each clip.",
    ),
    "notes.read": ToolSpec(
        NoteWindowArgs,
        NOTES_READ_DESCRIPTION,
        False,
        examples=({"target": CLIP_REF}, NOTE_WINDOW),
        annotations=STEER,
        summary="Read a clip's notes in a channel, grid and pitch window; selects the clip in the host UI. Omit the window to read the whole loop. Rows {beat, pitch, velocity, durationBeats} are what notes.edit takes. At most 2048 onsets: if completeWindow is false, subdivide.",
    ),
    "notes.edit": ToolSpec(
        NoteEditArgs,
        NOTES_EDIT_DESCRIPTION,
        False,
        examples=(
            {"target": CLIP_REF, "edits": [_EDIT]},
            {**NOTE_WINDOW, "edits": [_EDIT]},
        ),
        summary="Edit up to 256 onset cells of a clip; selects the clip. set: op, beat, pitch, velocity 1..127, durationBeats (any length); clear: op, beat, pitch. Beats on the 1/128 grid or on stepSizeBeats of an explicit window (omit it to derive). Never retry unknown.",
    ),
}
