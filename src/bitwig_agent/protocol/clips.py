from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal
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
    drop_default,
    long_timeout,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.launcher import ARRANGER_CLIP_REF, CLIP_REF, SLOT_REF, TRACK_REF
from bitwig_agent.protocol.limits import (
    BEAT_GRID,
    DURATION_RESOLUTION,
    DURATION_RESOLUTION_DIVISOR,
    LONG_DEFAULT_TIMEOUT_MS,
    MAX_CLIP_BEATS,
)
from bitwig_agent.protocol.refs import ArrangerClipRef, Beats, ClipRef, LauncherClipRef, SlotRef, TrackRef
from bitwig_agent.protocol.values import Expect

MAX_ARRANGER_CLIP_BEATS = 65536
MAX_TRANSFORM_TARGETS = 16
COLOR = r"^#[0-9a-fA-F]{6}$"


NOTE_RESOLUTION = Decimal(1) / DURATION_RESOLUTION_DIVISOR


def canonical(value: float) -> Decimal:
    """A beat or duration as Bitwig stores it: the nearest multiple of 1/1048576 beat, half a step rounding up.

    The exact binary value is rounded, as the extension's Math.round(value * 2^20) does.
    """
    return (Decimal(value) / NOTE_RESOLUTION).quantize(Decimal(1), rounding=ROUND_HALF_UP) * NOTE_RESOLUTION


def plain(value: Decimal) -> str:
    return format(value.normalize(), "f")


Channel = Annotated[int, Field(ge=0, le=15, description="MIDI channel 0..15; omitted means 0.")]
NoteBeat = Annotated[float, Field(ge=0, le=MAX_BEAT, description="Onset in beats from the clip start.")]
Pitch = Annotated[int, Field(ge=0, le=127)]
Velocity = Annotated[int, Field(ge=1, le=127, description="MIDI velocity 1..127.")]
Duration = Annotated[float, Field(ge=DURATION_RESOLUTION, le=MAX_CLIP_BEATS)]


Unit = Annotated[float, Field(ge=0, le=1)]
Bipolar = Annotated[float, Field(ge=-1, le=1)]
Occurrence = Literal[
    "always",
    "first",
    "not_first",
    "prev",
    "not_prev",
    "prev_channel",
    "not_prev_channel",
    "prev_key",
    "not_prev_key",
    "fill",
    "not_fill",
]
EXPRESSIONS = ("timbre", "pressure", "pan", "gain", "transpose", "formant")
FEATURES = (
    "chance",
    "velocitySpread",
    "releaseVelocity",
    "repeat",
    "recurrence",
    "occurrence",
    "muted",
    *EXPRESSIONS,
)


def nullable(description: str) -> Any:
    """Absent keeps the note's value; explicit null turns the feature off (an expression back to neutral)."""
    return Field(default=None, json_schema_extra=drop_default, description=description)


class Repeat(StrictModel):
    """Repeats (ratchets); omitted members keep an enabled repeat's values, turning it on needs count."""

    count: Annotated[int, Field(ge=-127, le=127)] = omitted(
        description="Repeats: positive splits the note into that many; negative is Bitwig's rate-based repeat."
    )
    curve: Bipolar = omitted(description="Timing curve of the repeats -1..1.")
    velocityEnd: Bipolar = omitted(description="Velocity change toward the last repeat -1..1.")
    velocityCurve: Bipolar = omitted(description="Curve of that velocity change -1..1.")


class Recurrence(StrictModel):
    """Plays only on some passes of a cycle of loop passes."""

    length: int = Field(ge=1, le=8, description="Loop passes in the cycle, 1..8.")
    mask: list[bool] = Field(min_length=1, max_length=8, description="Per pass of the cycle, whether the note plays.")

    @model_validator(mode="after")
    def validate_mask(self) -> Self:
        if len(self.mask) != self.length:
            raise ValueError(
                f"recurrence.mask has {len(self.mask)} entries for length {self.length}: give one per pass"
            )
        return self


class NoteFeatures(StrictModel):
    """Operators and expressions of a note: present means on, null means off; notes.get lists only those not at default."""

    chance: Unit | None = nullable("Chance 0..1 that the note plays (Bitwig rolls it per loop pass); null: off.")
    velocitySpread: Unit | None = nullable("Random velocity range 0..1 around velocity; null: 0.")
    releaseVelocity: Annotated[int, Field(ge=0, le=127)] | None = nullable("Note-off velocity 0..127; null: 100.")
    repeat: Repeat | None = nullable("Repeats {count, curve, velocityEnd, velocityCurve}; null: off.")
    recurrence: Recurrence | None = nullable("Recurrence {length 1..8, mask}; null: off.")
    occurrence: Occurrence | None = nullable(
        "Play condition: always, first, not_first, prev, not_prev, prev_channel, not_prev_channel, prev_key, "
        "not_prev_key, fill, not_fill; null: off."
    )
    muted: bool | None = nullable("A muted note does not play; null: false.")
    timbre: Bipolar | None = nullable("Expression at the note start -1..1; native only; null: 0.")
    pressure: Unit | None = nullable("Expression at the note start 0..1; native only; null: 0.")
    pan: Bipolar | None = nullable("Expression at the note start -1..1; native only; null: 0.")
    gain: Unit | None = nullable("Expression at the note start 0..1; native only; null: 0.5.")
    transpose: Annotated[float, Field(ge=-96, le=96)] | None = nullable(
        "Expression at the note start in semitones -96..96; native only; null: 0."
    )
    formant: Bipolar | None = nullable("Expression at the note start -1..1; native only; null: 0.")


class ClipNoteFields(StrictModel):
    beat: NoteBeat
    pitch: Pitch
    velocity: Velocity
    duration: Duration
    channel: Channel = omitted()


class ClipNote(NoteFeatures, ClipNoteFields):
    """One note as notes.get reads it and clips.insert writes it."""


class NotePatchFields(StrictModel):
    beat: NoteBeat
    pitch: Pitch
    velocity: Velocity = omitted(description="Required for a new note; omitted keeps an existing note's velocity.")
    duration: Duration = omitted(description="Required for a new note; omitted keeps an existing note's duration.")
    channel: Channel = omitted()


class NotePatch(NoteFeatures, NotePatchFields):
    """One listed note of notes.set: a note at (channel, pitch, beat) gets the fields given; a new one needs all."""


class Span(NamedTuple):
    position: int
    channel: int
    pitch: int
    beat: Decimal  # as Bitwig stores it: what every rule judges
    shown: Decimal  # as requested: what messages show
    end: Decimal | None  # as Bitwig stores it, None when the duration is omitted
    requested_end: Decimal  # what messages show (the onset when the duration is omitted)


def spans(notes: list[Any]) -> list[Span]:
    out = []
    for index, note in enumerate(notes):
        beat = canonical(note.beat)
        duration = None if note.duration is None else Decimal(str(note.duration))
        out.append(
            Span(
                index,
                note.channel or 0,
                note.pitch,
                beat,
                Decimal(str(note.beat)),
                None if duration is None else beat + canonical(note.duration),
                Decimal(str(note.beat)) + (duration or 0),
            )
        )
    return out


class NoteRange(StrictModel):
    start: NoteBeat
    end: Annotated[float, Field(ge=DURATION_RESOLUTION, le=MAX_BEAT)]
    pitches: Annotated[list[Pitch], Field(min_length=2, max_length=2)] = omitted(
        description="[low, high], both included: only these pitches belong to the range."
    )

    @model_validator(mode="after")
    def validate_bounds(self) -> Self:
        if canonical(self.end) <= canonical(self.start):
            raise ValueError("range.end must be at least 1/1048576 beat after range.start")
        if self.pitches is not None and self.pitches[0] > self.pitches[1]:
            raise ValueError("range.pitches is [low, high] with low <= high")
        return self


RANGE_DESCRIPTION = (
    '"all" (the whole clip, outside the loop too) or {start, end, pitches?}: notes whose onset lies in '
    "[start, end) and whose pitch lies in pitches [low, high] (default all)."
)
Range = Annotated[
    Annotated[NoteRange, Tag("bounds")] | Annotated[Literal["all"], Tag("all")],
    Discriminator(lambda value: "all" if isinstance(value, str) else "bounds"),
    Field(description=RANGE_DESCRIPTION),
]


def range_problem(at: str, span: Span, range_: NoteRange) -> str | None:
    start, end = canonical(range_.start), canonical(range_.end)
    shown_start, shown_end = Decimal(str(range_.start)), Decimal(str(range_.end))
    if span.beat < start:
        return (
            f"{at} beat {plain(span.shown)} is before range.start {plain(shown_start)}: move it inside, or lower "
            "range.start"
        )
    if span.beat >= end:
        return (
            f"{at} beat {plain(span.shown)} is outside range [{plain(shown_start)}, {plain(shown_end)}): move it "
            f"inside, or raise range.end above {plain(span.shown)}"
        )
    if range_.pitches is not None and not range_.pitches[0] <= span.pitch <= range_.pitches[1]:
        low, high = range_.pitches
        return (
            f"{at} pitch {span.pitch} is outside range.pitches [{low}, {high}]: move it inside, or widen range.pitches"
        )
    return None


def length_problem(at: str, span: Span, length: Decimal) -> str | None:
    stored = canonical(float(length))
    if span.end is None or span.end <= stored:
        return None
    if span.beat >= stored:
        return f"{at} starts at beat {plain(span.shown)}, at or past the clip length {plain(length)}"
    return (
        f"{at} ends at beat {plain(span.requested_end)}, past the clip length {plain(length)}: shorten it to duration <= "
        f"{plain(length - span.shown)}"
    )


def collision_problems(listed: list[Span], field: str) -> list[tuple[int, str]]:
    """Repeated notes, then listed notes that start before an earlier one on the same channel and pitch ends."""
    found = []
    cells: dict[tuple[int, int, Decimal], int] = {}
    for span in listed:
        first = cells.setdefault((span.channel, span.pitch, span.beat), span.position)
        if first != span.position:
            found.append(
                (
                    span.position,
                    f"{field}[{span.position}] repeats the channel, beat and pitch of {field}[{first}]: remove one",
                )
            )
    holders: dict[tuple[int, int], Span] = {}  # per channel and pitch, the note seen so far that ends last
    for span in sorted(listed, key=lambda s: (s.beat, s.position)):
        row = (span.channel, span.pitch)
        holder = holders.get(row)
        if holder is not None and holder.end is not None and holder.beat != span.beat and holder.end > span.beat:
            found.append(
                (
                    span.position,
                    f"{field}[{span.position}] starts at beat {plain(span.shown)} before {field}[{holder.position}] on pitch "
                    f"{span.pitch} ends at beat {plain(holder.requested_end)}: shorten {field}[{holder.position}] to "
                    f"duration <= {plain(span.shown - holder.shown)}",
                )
            )
        if span.end is not None and (holder is None or holder.end is None or span.end > holder.end):
            holders[row] = span
    return found


def note_violations(notes: list[Any], field: str, length: Decimal | None = None, range_: Any = None) -> list[str]:
    """Every rule the listed notes break on their own, in request order, with the extension's texts.

    Rules that need the clip (overlap with an unlisted note, a new note without velocity or duration) are the
    extension's.
    """
    listed = spans(notes)
    found: list[tuple[int, str]] = []
    for span in listed:
        at = f"{field}[{span.position}]"
        problems = (
            length_problem(at, span, length) if length is not None else None,
            range_problem(at, span, range_) if isinstance(range_, NoteRange) else None,
        )
        found += [(span.position, text) for text in problems if text]
    found += collision_problems(listed, field)
    return [text for _, text in sorted(found, key=lambda item: item[0])]


def refuse_violations(field: str, found: list[str]) -> None:
    if found:
        noun = "problem" if len(found) == 1 else "problems"
        raise ValueError(f"{field}: {len(found)} {noun}, nothing was written: " + "; ".join(found))


def require_location(target: Any, location: type, method: str, label: str) -> None:
    if not isinstance(target, location):
        raise ReasonError("unsupported_scope", f"{method} takes a {label} clip in this version")


class InsertLoop(StrictModel):
    length: int = Field(ge=1, le=MAX_CLIP_BEATS, description="Loop length and playback stop of the new clip.")


class SetLoop(StrictModel):
    length: float = Field(
        ge=BEAT_GRID, le=MAX_CLIP_BEATS, description="Loop length; the play stop moves to the new loop end."
    )


class ArrangerDestination(StrictModel):
    track: TrackRef
    start: Beats


def _destination_tag(value: Any) -> str:
    return "slot" if (value.get("kind") if isinstance(value, dict) else getattr(value, "kind", None)) else "arranger"


Destination = Annotated[
    Annotated[SlotRef, Tag("slot")] | Annotated[ArrangerDestination, Tag("arranger")], Discriminator(_destination_tag)
]
"""Where a clip lands: a launcher slot, or a track at a time on the arranger timeline."""


class ClipTargetArgs(StatusArgs):
    target: LauncherClipRef


class ClipInsertArgs(StatusArgs):
    # Without the native binding the notes are written window by window: with notes the call waits longer.
    timeoutMs: int = long_timeout()
    to: Destination
    name: str = Field(min_length=1, max_length=128)
    loop: InsertLoop = omitted(description="Launcher slot only: the loop length.")
    length: Annotated[float, Field(ge=BEAT_GRID, le=MAX_ARRANGER_CLIP_BEATS)] = omitted(
        description="Arranger position only: the clip length."
    )
    notes: Annotated[list[ClipNote], Field(min_length=1)] = omitted(
        description="The new clip's notes, in the record notes.get reads; they must fit the length."
    )

    @model_validator(mode="before")
    @classmethod
    def notes_wait_longer(cls, data: Any) -> Any:
        if isinstance(data, dict) and data.get("notes") and "timeoutMs" not in data:
            return {**data, "timeoutMs": LONG_DEFAULT_TIMEOUT_MS}
        return data

    @model_validator(mode="after")
    def validate_shape(self) -> Self:
        launcher = isinstance(self.to, SlotRef)
        supplied = self.model_fields_set
        if launcher and ("loop" not in supplied or "length" in supplied):
            raise ValueError("a launcher slot takes loop:{length}, not length")
        if not launcher and ("length" not in supplied or "loop" in supplied):
            raise ValueError("an arranger position takes length, not loop")
        if "notes" in supplied:
            length = Decimal(str(self.loop.length if launcher else self.length))
            refuse_violations("notes", note_violations(self.notes, "notes", length))
            refuse_violations(
                "notes",
                [
                    f"notes[{i}] repeat is off on this note: give repeat.count to turn it on"
                    for i, note in enumerate(self.notes)
                    if note.repeat is not None and "count" not in note.repeat.model_fields_set
                ],
            )
            if not launcher and any(note.model_fields_set & set(FEATURES) for note in self.notes):
                raise ReasonError(
                    "unsupported_scope",
                    "notes on an arranger clip take beat, pitch, velocity, duration and channel only: operators and "
                    "expressions are written into launcher clips in this version",
                )
        return self


class ClipImportArgs(StatusArgs):
    to: SlotRef
    path: Nonempty

    @model_validator(mode="after")
    def validate_path(self) -> Self:
        validate_local_path(self.path)
        return self


class LoopExpect(StrictModel):
    length: float


class ClipExpect(Expect):
    """The current values readable without selecting the clip: color and the launch fields are not."""

    name: str = omitted()
    length: float = omitted()
    loop: LoopExpect = omitted()
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: int = omitted()
    nextActionDelay: float = omitted()


NEXT_ACTION = (
    "nextAction",
    "nextActionEnabled",
    "nextActionLinkToLoop",
    "nextActionLoopCount",
    "nextActionDelay",
)
LAUNCH = ("launchQuantization", "launchMode", "useLoopStartAsQuantizationReference")
LAUNCHER_ONLY = ("color", "loop", *NEXT_ACTION, *LAUNCH)
SETTINGS = ("name", "length", *LAUNCHER_ONLY)


class ClipSetArgs(StatusArgs):
    target: ClipRef
    name: Annotated[str, Field(min_length=1, max_length=128)] = omitted()
    color: Annotated[str, Field(pattern=COLOR)] = omitted(description='"#rrggbb"; Bitwig may snap it to its palette.')
    length: Annotated[float, Field(ge=BEAT_GRID, le=MAX_ARRANGER_CLIP_BEATS)] = omitted(
        description="Arranger clip only: the timeline length; the start stays."
    )
    loop: SetLoop = omitted(description="Launcher clip only.")
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: Annotated[int, Field(ge=1, le=64)] = omitted()
    nextActionDelay: Annotated[float, Field(ge=0, le=MAX_BEAT)] = omitted()
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
            hint = "an arranger clip takes name and length"
        else:
            wrong = [key for key in ("length",) if key in supplied] + [f"expect.{key}" for key in expected & {"length"}]
            hint = "a launcher clip takes loop:{length}"
        if wrong:
            raise ReasonError("unsupported_scope", f"{', '.join(wrong)} cannot be set on this clip: {hint}")
        return self


class ClipDeleteArgs(StatusArgs):
    target: ClipRef


class ClipCopyArgs(StatusArgs):
    from_: LauncherClipRef = Field(alias="from")
    to: Destination


class ArrangerMove(StrictModel):
    start: Beats


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


class NotesGetArgs(ClipTargetArgs):
    # Without the native binding a read binds one window per beat (about 0.2 s each).
    timeoutMs: int = long_timeout(LONG_DEFAULT_TIMEOUT_MS)
    range: Range = omitted(description="Limits the notes returned; default the whole clip. " + RANGE_DESCRIPTION)


class NotesSetArgs(ClipTargetArgs):
    timeoutMs: int = long_timeout(LONG_DEFAULT_TIMEOUT_MS)
    notes: list[NotePatch] = Field(
        description="Without range: written in place (inserted or updated), nothing removed. With range: the notes "
        "of the range become exactly these."
    )
    range: Range = omitted()

    @model_validator(mode="after")
    def validate_notes(self) -> Self:
        if "range" not in self.model_fields_set and not self.notes:
            raise ValueError('notes is empty: give range (e.g. "all") to clear that range, or list notes to write')
        refuse_violations("notes", note_violations(self.notes, "notes", range_=self.range))
        return self


CLIPS_GET_DESCRIPTION = (
    "Read exact launcher clip timing and Next Action settings of one clip: loop {length}, play and loop starts, "
    "nextAction fields, in the shape clips.set writes. Source-bound native model access; no cursor selection. "
    "launchQuantization, launchMode and useLoopStartAsQuantizationReference are not read back here (they need the clip selected): "
    "the per-field outcomes of clips.set are their evidence. Requires the inspected Bitwig 6.1.4 JAR. Readback is not "
    "playback or persistence proof."
)
NOTE_RULES = (
    "A note is {beat, pitch, velocity 1..127, duration, channel (omitted = 0)}, the record notes.get reads, plus "
    "operators (chance, velocitySpread, releaseVelocity, repeat, recurrence, occurrence, muted) and note expressions "
    "(timbre, pressure, pan, gain, transpose, formant; native path only), listed only when on or not default. Beats and "
    "durations are compared as Bitwig stores them (1/1048576 beat). No two notes on one channel, pitch and beat; no "
    "overlap on one channel and pitch. Every broken rule is refused at once, each with its index and a fix, and "
    "nothing is written. "
)
NOTE_PATHS = (
    "With the native note binding (Bitwig 6.1.4/6.1.7) the write is one Undo step (undo=one_step) and is read back "
    "natively without selecting anything (exact=true, verification=native_readback). Without it the public API "
    'writes note by note: onsets and range bounds on the 1/128-beat grid, a range inside the loop (not "all"), '
    "one onset per cell, one Undo step each (undo=per_note), the clip "
    "selected and read back through its note grid (exact=false, verification=api_readback). "
)
CLIPS_INSERT_DESCRIPTION = (
    "Create a named MIDI clip in an EMPTY launcher slot (to: a slot ref from launcher.list, scene included; loop "
    "{length} sets loop length and playback stop) or on an instrument track's arranger timeline (to {track, "
    "start}, length; refused on overlap, never overwrites). Optional notes are written in the same call and must "
    "fit the length. "
    + NOTE_RULES
    + "Launcher: the note result is noteWrite, in notes.set's shape. "
    + NOTE_PATHS
    + "Returns the new clip's ref. An unobserved create is outcome unknown (reason not_observed): inspect, then ack "
    "the hold; never retry."
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
    "it, then unknown/readback_mismatch), loop {length} (also moves the play stop), Next Action fields "
    "(nextAction play_next|stop, nextActionEnabled, nextActionLinkToLoop, nextActionLoopCount, nextActionDelay; "
    "native, no selection) and launchQuantization, launchMode, useLoopStartAsQuantizationReference (select the clip; "
    "options lists the host's own values; clips.get does not report them, the per-field outcomes are the evidence). Arranger clip: name, length (the start stays; overlap is refused). "
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
    "track's arranger timeline (to {track, start}; Bitwig's copy of launcher clips, any overlap is refused). "
    "Slot to slot works only within 16 scenes of the source (else unsupported_scope: copy via an empty slot within 16 "
    "scenes, or notes.get + clips.insert {notes} for MIDI clips). Returns the new clip's ref; nameChanged is "
    "reported if the host renames a slot copy. Needs a stopped transport, recording/automation-write off; arranger "
    "writes also need a settled project. An unobserved copy is outcome unknown and holds that slot; never retry."
)
CLIPS_MOVE_DESCRIPTION = (
    "Move one arranger clip to to.start on its own track; the length is kept and an overlap with another clip "
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
NOTES_GET_DESCRIPTION = (
    "Read the notes of one launcher clip: every note of every channel, in the record notes.set and clips.insert "
    "take, sorted by beat then pitch; channel is omitted when 0. range ({start, end, pitches?}) only limits the "
    "output. Operators and expressions appear only when on or not default. Result: ref, loop, count (notes in the range), complete (false when the response cap cut the list: "
    "read the rest by range; on the public path also past the loop end or out of time, with incompleteReason), exact "
    "and notes. exact=true: read natively (Bitwig 6.1.4/6.1.7), no selection, onsets "
    "exact. exact=false: the public note grid, read on the 1/128-beat write grid (onsetResolution 0.0078125), one "
    "onset per cell, the clip selected (a failure after that is unknown); expressions are not read (unreadFields)."
)
NOTES_SET_DESCRIPTION = (
    "Write the notes of one launcher clip. Without range: each listed note is written in place (identity: channel, "
    "pitch, beat; inserted when absent, given fields updated when present) and nothing is removed. With range "
    '("all" or {start, end, pitches?}): the notes whose onset lies in it become exactly the listed ones (notes:[] '
    "clears it); every listed note must lie in it. The recipe for removing or moving notes: notes.get the range, "
    "edit the list, notes.set the same range. Omitted fields keep the note's value; null turns an operator off (an "
    "expression back to neutral). A new note needs velocity and duration; a listed note may not "
    "overlap a note left in place, and every written note must end within the clip loop. "
    + NOTE_RULES
    + NOTE_PATHS
    + "Result: outcome (already_matched when nothing differs, so a replay converges), summary {inserted, updated, "
    "removed, unchanged}, undo, exact, count (notes in the clip after the write, native). Never retry unknown: "
    "notes.get first."
)
_NOTE = {"beat": 0, "pitch": 60, "velocity": 90, "duration": 1}


SPECS: dict[str, ToolSpec] = {
    "clips.get": ToolSpec(ClipTargetArgs, CLIPS_GET_DESCRIPTION, True, examples=({"target": CLIP_REF},)),
    "clips.insert": ToolSpec(
        ClipInsertArgs,
        CLIPS_INSERT_DESCRIPTION,
        False,
        examples=(
            {"to": SLOT_REF, "loop": {"length": 4}, "name": "Sketch"},
            {
                "to": SLOT_REF,
                "loop": {"length": 4},
                "name": "Sketch",
                "notes": [{"beat": 0, "pitch": 60, "velocity": 90, "duration": 1}],
            },
            {"to": {"track": TRACK_REF, "start": 16}, "length": 16, "name": "Verse"},
            {
                "to": SLOT_REF,
                "loop": {"length": 1},
                "name": "Hats",
                "notes": [
                    {
                        "beat": 0.5,
                        "pitch": 42,
                        "velocity": 80,
                        "duration": 0.25,
                        "chance": 0.7,
                        "repeat": {"count": 2, "curve": 0, "velocityEnd": -0.3, "velocityCurve": 0},
                        "recurrence": {"length": 4, "mask": [False, False, False, True]},
                    }
                ],
            },
        ),
        requires=PROJECT_READY,
        summary="Create a named MIDI clip in an empty launcher slot (to: slot ref, loop {length}) or on an instrument track's timeline (to {track, start}, length), with optional notes (beats on the 1/128 grid, any duration). Returns the new ref. A launcher create selects it. Unobserved: unknown, ack the hold, never retry.",
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
            {"target": CLIP_REF, "name": "Verse", "loop": {"length": 8}, "color": "#ff8800"},
            {
                "target": CLIP_REF,
                "nextAction": "play_next",
                "nextActionEnabled": True,
                "nextActionLinkToLoop": True,
                "nextActionLoopCount": 1,
                "nextActionDelay": 0,
            },
            {"target": CLIP_REF, "launchQuantization": "1/4", "useLoopStartAsQuantizationReference": True},
            {"target": ARRANGER_CLIP_REF, "length": 8},
        ),
        summary="Set fields of one clip. Launcher: name, color, loop, Next Action, launch fields; arranger: name, length. Runs next action, launch, clip fields in order; the first failure stops the rest. Next Action and launch need a stopped transport.",
    ),
    "clips.delete": ToolSpec(ClipDeleteArgs, CLIPS_DELETE_DESCRIPTION, False, examples=({"target": CLIP_REF},)),
    "clips.copy": ToolSpec(
        ClipCopyArgs,
        CLIPS_COPY_DESCRIPTION,
        False,
        examples=({"from": CLIP_REF, "to": SLOT_REF}, {"from": CLIP_REF, "to": {"track": TRACK_REF, "start": 0}}),
        summary="Copy a launcher clip (from) to an empty slot of the same track or onto its arranger timeline (to). Returns the new clip's ref.",
    ),
    "clips.move": ToolSpec(
        ClipMoveArgs,
        CLIPS_MOVE_DESCRIPTION,
        False,
        examples=({"target": ARRANGER_CLIP_REF, "to": {"start": 32}},),
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
    "notes.get": ToolSpec(
        NotesGetArgs,
        NOTES_GET_DESCRIPTION,
        False,
        examples=({"target": CLIP_REF}, {"target": CLIP_REF, "range": {"start": 0, "end": 4, "pitches": [36, 42]}}),
        annotations=STEER,
        summary="Read every note of a launcher clip, all channels, as records {beat, pitch, velocity, duration, channel?, operators and expressions when set} that notes.set takes; range limits the output. exact=false means the public note grid (onsets snapped, clip selected, expressions unread).",
    ),
    "notes.set": ToolSpec(
        NotesSetArgs,
        NOTES_SET_DESCRIPTION,
        False,
        examples=(
            {"target": CLIP_REF, "notes": [_NOTE]},
            {
                "target": CLIP_REF,
                "range": {"start": 0, "end": 4, "pitches": [42, 42]},
                "notes": [_NOTE | {"pitch": 42}],
            },
            {"target": CLIP_REF, "range": "all", "notes": []},
            {
                "target": CLIP_REF,
                "notes": [
                    {"beat": 0.5, "pitch": 42, "chance": 0.7, "velocitySpread": 0.2},
                    {
                        "beat": 3.5,
                        "pitch": 38,
                        "repeat": {"count": 3},
                        "recurrence": {"length": 4, "mask": [False, False, False, True]},
                    },
                ],
            },
        ),
        summary="Write a launcher clip's notes. No range: insert or update the listed notes (omitted fields kept, null turns an operator off), remove nothing. With range ('all' or {start, end, pitches?}): that region becomes exactly the listed notes. Replays are already_matched. Never retry unknown.",
    ),
}
