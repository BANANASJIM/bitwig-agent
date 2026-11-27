from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.annotations import ADD
from bitwig_agent.protocol.base import PROJECT_READY, Nonempty, StatusArgs, ToolSpec, UnitValue, omitted
from bitwig_agent.protocol.limits import MAX_TIMEOUT_MS, MIN_TIMEOUT_MS, TRACK_WINDOW
from bitwig_agent.protocol.positions import TrackPosition
from bitwig_agent.protocol.refs import SendRef, TrackRef
from bitwig_agent.protocol.values import Expect, Normalized

Color = Annotated[str, Field(pattern=r"^#[0-9a-fA-F]{6}$")]
EXAMPLE_TRACK = {"kind": "track", "id": "12345678-1234-1234-1234-123456789abc", "name": "Lead"}
EXAMPLE_OTHER = {"kind": "track", "id": "12345678-1234-1234-1234-123456789abd", "name": "Bass"}
EXAMPLE_SEND = {"kind": "send", "track": EXAMPLE_TRACK, "index": 0, "name": "A-Reverb"}


class TrackArgs(StatusArgs):
    target: TrackRef


class TrackExpect(Expect):
    name: Nonempty = omitted()
    volume: UnitValue = omitted()
    pan: UnitValue = omitted()
    mute: bool = omitted()
    solo: bool = omitted()
    arm: bool = omitted()
    color: Color = omitted()
    activated: bool = omitted()
    monitorMode: Nonempty = omitted()
    crossFadeMode: Nonempty = omitted()


class TrackSetArgs(TrackArgs):
    name: Nonempty = omitted()
    volume: Normalized = omitted(
        description='Fader level: the normalized 0..1 value tracks.get returns, or {display: "-6 dB"} / {display: "-inf dB"}.'
    )
    pan: Normalized = omitted(
        description='Pan: normalized 0..1 where 0.5 is centre (the host shows "0.00 %") and 0 is hard left ("-100 %"); or {display: "-25 %"}.'
    )
    mute: bool = omitted()
    solo: bool = omitted()
    arm: bool = omitted()
    color: Color = omitted(description="Track color as #RRGGBB.")
    activated: bool = omitted(description="Track activated (false = deactivated, no processing).")
    monitorMode: Nonempty = omitted(description="Input monitoring: one of the options tracks.get returns.")
    crossFadeMode: Nonempty = omitted(description="Crossfader assignment: one of the options tracks.get returns.")
    expect: TrackExpect = omitted(description="Current bare values a write requires; a mismatch writes nothing.")

    @model_validator(mode="after")
    def require_change(self) -> Self:
        if not self.model_fields_set - {"timeoutMs", "target", "expect"}:
            raise ValueError("tracks.set requires at least one changed field")
        return self


class SendExpect(Expect):
    value: UnitValue = omitted()
    enabled: bool = omitted()
    mode: Nonempty = omitted()


class TrackSendSetArgs(StatusArgs):
    target: SendRef
    value: Normalized = omitted(description='Send level, normalized 0..1 or {display: "-12 dB"}.')
    enabled: bool = omitted(
        description="Turn the send on or off. A level write already turns it on (autoEnabled in the result)."
    )
    mode: Nonempty = omitted(description="Pre/post-fader tap: one of the mode options tracks.get returns for the send.")
    expect: SendExpect = omitted(description="Current bare values a write requires; a mismatch writes nothing.")

    @model_validator(mode="after")
    def validate_send(self) -> Self:
        if not self.model_fields_set & {"value", "enabled", "mode"}:
            raise ValueError("at least one of value, enabled or mode is required")
        return self


METERS_MAX_WINDOW_MS = 5000
METERS_SETTLE_MS = 300


class MetersGetArgs(StatusArgs):
    targets: list[TrackRef] = Field(min_length=1, max_length=TRACK_WINDOW)
    windowMs: int = Field(ge=50, le=METERS_MAX_WINDOW_MS)
    timeoutMs: int = Field(default=METERS_MAX_WINDOW_MS + METERS_SETTLE_MS, ge=MIN_TIMEOUT_MS, le=MAX_TIMEOUT_MS)

    @model_validator(mode="after")
    def unique_tracks(self) -> Self:
        if len({track.id.lower() for track in self.targets}) != len(self.targets):
            raise ValueError("targets must be unique")
        return self


class TrackInsertArgs(StatusArgs):
    type: Literal["audio", "instrument", "effect"]
    name: Nonempty
    position: TrackPosition = omitted(
        description='"start", "end" (default) or {before: trackRef} / {after: trackRef}. The anchor must be a top-level track of the same list: a main track for audio and instrument, an effect track for effect.'
    )


class TrackGroupArgs(StatusArgs):
    targets: Annotated[list[TrackRef], Field(min_length=1, max_length=TRACK_WINDOW)] = Field(
        description="Tracks to put into one new group, all with the same parent (top level or one group); not effect or master tracks."
    )
    name: Nonempty = omitted(description="Name of the new group; naming it is part of the same single Undo step.")

    @model_validator(mode="after")
    def validate_distinct(self) -> Self:
        if len({track.id.lower() for track in self.targets}) != len(self.targets):
            raise ValueError("targets must be distinct")
        return self


TRACKS_LIST_DESCRIPTION = "Discover tracks in the flat bounded track window (tree pre-order). Each row has ref (a track ref to pass back), index, name, type, isGroup, groupExpanded (groups), parent (the group's track ref, null at top level), depth and parentResolved, plus the mix columns volume and pan {value 0..1, display}, mute, solo, arm and sends [{name, display, enabled}] (null while unobserved; tracks.get has refs and completeness); tree.complete says every parent was observed. A mix table is --format=tsv --fields=name,volume.display,pan.display,mute,solo. All indices are zero-based. Inspect window completeness before choosing targets."
TRACKS_GET_DESCRIPTION = "Read one track's mix state and bounded sends. ref is the track ref; volume and pan are {value 0..1, display, ref} (ref is a parameter ref for automation.points.*), pan 0.5 is centre; monitorMode and crossFadeMode are {value, options} with the host's own options; send rows carry their send ref, value {value, display}, enabled, mode {value, options} (pre/post-fader tap), preFader (the effective tap) and effectTrack (a track ref). Fields may be null after target changes; inspect observationsComplete and sendObservationsComplete rather than treating partial observations as current."
TRACKS_SET_DESCRIPTION = "Set only the given fields of one track by its ref; omitted fields stay unchanged. Returns ref (the track after the write), outcome (the worst field's) and a per-field outcome with reason; a field already at the value is already_matched. volume and pan take the value tracks.get returned or {display}: the API has no dB setter, so the extension bisects the normalized value against the host's own displayed text and verifies the final display (displayWrite carries the probe count and the final observed display); several fader moves are written while it searches. An unreadable display restores the original value and reports not_applied (unknown when the restore is not confirmed). expect compares bare current values first. Never retry unknown."
TRACKS_SENDS_SET_DESCRIPTION = 'Set one send, addressed by the send ref tracks.get returned (a send\'s name is compared, so a reordered send is stale). value is normalized 0..1 or {display: "-12 dB"}, verified by display readback; enabled switches the send; mode sets the pre/post-fader tap (one of the mode options tracks.get lists); a level write already turns a disabled send on (autoEnabled in the result). Written in order, each read back: level, then enabled, mode last. expect compares bare current values first. Inspect outcome and fields[].outcome/reason; never retry unknown.'
METERS_GET_DESCRIPTION = f"Read VU meter peak and RMS (left/right, steps 0..127; the public API publishes no dB mapping) over windowMs for 1..{TRACK_WINDOW} track refs. Meters only move while audio renders, so start playback first (transport.set playing=true; consider audio.route silent). Returns per track ref, peakMax, rmsMax, rmsMean, callbacks, silent. Not LUFS: use audio.capture + audio.analyze mode=loudness for that."
TRACKS_SELECT_DESCRIPTION = "Select a track in the host UI by its ref. Returns outcome sent: the command was dispatched and navigation is not observed; a selected snapshot does not acknowledge it. Inspect the actual UI and possible automatic arming before dependent edits; never blindly retry. Reads do not require selection."
TRACKS_INSERT_DESCRIPTION = 'Insert and name a top-level track. position is "start", "end" (default) or {before|after: trackRef} of a top-level track of the same list (main tracks for audio/instrument, effect tracks for effect; groups count, their children do not). In a project with groups, tracks.list tree.complete must be true. Returns ref of the new track. An unresolved insertion holds further inserts (reason unresolved); inspect actual state, never blindly retry or reset to bypass ambiguity.'
TRACKS_GROUP_DESCRIPTION = f"Put existing tracks into one new group track (the guide's select + Ctrl+G), without using the UI selection. targets must be adjacent tracks of one parent, in order (top level or the same group); effect and master tracks are refused. The new group is inserted where the first of them was, their order is kept, and the native operation selects the group. The targets must be adjacent siblings of one parent (moving tracks comes later). A named group is created and named inside one native transaction, so a single Undo (Group Tracks) restores the tree; after Redo the group comes back collapsed and its children are not listed until it is expanded (a bank limitation). Verified by one new group whose children are exactly targets, every other parent unchanged; returns ref of the group. Version-gated; needs recording/automation-write off, at most {TRACK_WINDOW - 1} tracks in the window and every parent observed. Unknown outcomes hold tracks.insert/tracks.group until you inspect tracks.list and ack the hold id; never retry."
TRACKS_DELETE_DESCRIPTION = f"Delete one audio/instrument/hybrid/effect track by its ref (Track.deleteObject; never master or group); the ref's name must still match. Needs observed stopped transport with recording/automation write off. Verified by the track leaving the bank and the count dropping by one (outcome applied); outcome unknown (not_observed) means inspect state and never retry. Only while the whole project fits the {TRACK_WINDOW}-track bank window (offset 0, at most {TRACK_WINDOW} tracks incl. group children and effect tracks), else not_ready/not_applied. Destructive: removes the track's clips and devices; Bitwig's own Undo is the only recovery (one Undo restores the track with the same UUID)."


SPECS: dict[str, ToolSpec] = {
    "tracks.list": ToolSpec(StatusArgs, TRACKS_LIST_DESCRIPTION, True, records="tracks"),
    "tracks.get": ToolSpec(TrackArgs, TRACKS_GET_DESCRIPTION, True, examples=({"target": EXAMPLE_TRACK},)),
    "tracks.set": ToolSpec(
        TrackSetArgs,
        TRACKS_SET_DESCRIPTION,
        False,
        examples=(
            {"target": EXAMPLE_TRACK, "mute": True},
            {"target": EXAMPLE_TRACK, "color": "#ff8800", "monitorMode": "ON"},
            {"target": EXAMPLE_TRACK, "volume": {"display": "-6 dB"}, "pan": 0.5},
            {"target": EXAMPLE_TRACK, "pan": {"display": "-25 %"}, "expect": {"pan": 0.5}},
        ),
        summary='Set only the given fields of a track by its ref; omitted fields stay unchanged. volume and pan take the 0..1 value tracks.get returned (pan 0.5 is centre, 0 hard left) or {display: "-6 dB"} / {display: "-25 %"}. expect compares bare current values first. Returns ref, the worst field outcome and a per-field outcome with reason; never retry unknown.',
    ),
    "tracks.sends.set": ToolSpec(
        TrackSendSetArgs,
        TRACKS_SENDS_SET_DESCRIPTION,
        False,
        examples=(
            {"target": EXAMPLE_SEND, "value": 0.5},
            {"target": EXAMPLE_SEND, "value": {"display": "-12 dB"}, "enabled": True, "expect": {"value": 0.25}},
            {"target": EXAMPLE_SEND, "mode": "PRE"},
        ),
        summary='Set one send by the send ref tracks.get returned. value is normalized 0..1 or {display: "-12 dB"}; a level write turns a disabled send on; mode sets the pre/post-fader tap. expect compares bare current values first. Check fields[].outcome; never retry unknown.',
    ),
    "meters.get": ToolSpec(
        MetersGetArgs, METERS_GET_DESCRIPTION, True, examples=({"targets": [EXAMPLE_TRACK], "windowMs": 1000},)
    ),
    "tracks.select": ToolSpec(TrackArgs, TRACKS_SELECT_DESCRIPTION, False, examples=({"target": EXAMPLE_TRACK},)),
    "tracks.insert": ToolSpec(
        TrackInsertArgs,
        TRACKS_INSERT_DESCRIPTION,
        False,
        examples=(
            {"type": "instrument", "name": "Agent Sketch"},
            {"type": "audio", "name": "Bass", "position": {"after": EXAMPLE_TRACK}},
            {"type": "effect", "name": "Verb", "position": {"before": EXAMPLE_OTHER}},
        ),
        requires=PROJECT_READY,
    ),
    "tracks.group": ToolSpec(
        TrackGroupArgs,
        TRACKS_GROUP_DESCRIPTION,
        False,
        examples=({"targets": [EXAMPLE_TRACK, EXAMPLE_OTHER], "name": "Drums"},),
        requires=PROJECT_READY,
        annotations=ADD,
    ),
    "tracks.delete": ToolSpec(
        TrackArgs,
        TRACKS_DELETE_DESCRIPTION,
        False,
        examples=({"target": EXAMPLE_TRACK},),
        requires=PROJECT_READY,
    ),
}
