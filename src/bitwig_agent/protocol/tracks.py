from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.annotations import ADD
from bitwig_agent.protocol.base import (
    PROJECT_READY,
    TRACK,
    DeviceArgs,
    Nonempty,
    StatusArgs,
    ToolSpec,
    TrackId,
    UnitValue,
    omitted,
)
from bitwig_agent.protocol.limits import MAX_TIMEOUT_MS, MIN_TIMEOUT_MS, TRACK_WINDOW


class TrackSetArgs(DeviceArgs):
    name: Nonempty = omitted()
    volumeNormalized: UnitValue = omitted()
    panNormalized: UnitValue = omitted()
    mute: bool = omitted()
    solo: bool = omitted()
    arm: bool = omitted()
    volumeDb: Annotated[float, Field(ge=-150, le=24)] = omitted(
        description="Fader level in dB, reached by matching the host's own displayed value (not a computed curve). Alternative to volumeNormalized/volumeDisplay."
    )
    volumeDisplay: Nonempty = omitted(description='Fader level as the host shows it, e.g. "-6 dB" or "-inf dB".')
    panPercent: Annotated[float, Field(ge=-100, le=100)] = omitted(
        description="Pan in percent, negative = left, reached by matching the host's displayed value. Alternative to panNormalized/panDisplay."
    )
    panDisplay: Nonempty = omitted(description='Pan as the host shows it, e.g. "25% L", "40% R" or "Center".')
    color: Annotated[str, Field(pattern=r"^#[0-9a-fA-F]{6}$")] = omitted(description="Track color as #RRGGBB.")
    activated: bool = omitted(description="Track activated (false = deactivated, no processing).")
    monitorMode: Nonempty = omitted(
        description="Input monitoring: one of tracks.get options.monitorMode (the host's own list)."
    )
    crossFadeMode: Nonempty = omitted(
        description="Crossfader assignment: one of tracks.get options.crossFadeMode (the host's own list)."
    )

    @model_validator(mode="after")
    def require_change(self) -> Self:
        if not self.model_fields_set - {"timeoutMs", "trackId"}:
            raise ValueError("tracks.set requires at least one changed field")
        for group in (("volumeNormalized", "volumeDb", "volumeDisplay"), ("panNormalized", "panPercent", "panDisplay")):
            if len(set(group) & self.model_fields_set) > 1:
                raise ValueError("supply at most one of " + ", ".join(group))
        return self


class TrackSendSetArgs(DeviceArgs):
    sendIndex: int = omitted(
        ge=0, le=127, description="Zero-based send index from tracks.get; exactly one of sendIndex or sendName."
    )
    sendName: Nonempty = omitted(
        description="Exact send name from tracks.get (the FX track it feeds); must be unique on the track."
    )
    expectedSendName: Nonempty = omitted(
        description="Compare-and-set guard: the write is refused with stale_target if the send has another name."
    )
    valueNormalized: UnitValue = omitted(
        description="Send level 0..1 (not dB). Exactly one of valueNormalized or display, unless only enabled is changed."
    )
    display: Nonempty = omitted(description='Send level as the host shows it, e.g. "-12 dB" or "-inf dB".')
    enabled: bool = omitted(
        description="Turn the send on or off. A level write already turns it on (autoEnabled in the result)."
    )

    @model_validator(mode="after")
    def validate_send(self) -> Self:
        fields = self.model_fields_set
        if ("sendIndex" in fields) == ("sendName" in fields):
            raise ValueError("supply exactly one of sendIndex or sendName")
        if {"valueNormalized", "display"} <= fields:
            raise ValueError("supply at most one of valueNormalized, display")
        if not fields & {"valueNormalized", "display", "enabled"}:
            raise ValueError("at least one of valueNormalized, display or enabled is required")
        return self


METERS_MAX_WINDOW_MS = 5000
METERS_SETTLE_MS = 300


class TrackMetersArgs(StatusArgs):
    trackIds: list[TrackId] = Field(min_length=1, max_length=TRACK_WINDOW)
    windowMs: int = Field(ge=50, le=METERS_MAX_WINDOW_MS)
    timeoutMs: int = Field(default=METERS_MAX_WINDOW_MS + METERS_SETTLE_MS, ge=MIN_TIMEOUT_MS, le=MAX_TIMEOUT_MS)

    @model_validator(mode="after")
    def unique_tracks(self) -> Self:
        if len({t.lower() for t in self.trackIds}) != len(self.trackIds):
            raise ValueError("trackIds must be unique")
        return self


class TrackCreateArgs(StatusArgs):
    type: Literal["audio", "instrument", "effect"]
    position: int = Field(ge=-1, le=2147483647)
    name: Nonempty


class TrackGroupArgs(StatusArgs):
    trackIds: Annotated[list[TrackId], Field(min_length=1, max_length=TRACK_WINDOW)] = Field(
        description="Tracks to put into one new group, all with the same parent (top level or one group); not effect or master tracks."
    )
    name: Nonempty = omitted()

    @model_validator(mode="after")
    def validate_distinct(self) -> Self:
        if len({value.lower() for value in self.trackIds}) != len(self.trackIds):
            raise ValueError("trackIds must be distinct")
        return self


class TrackDeleteArgs(DeviceArgs):
    expectedName: Nonempty


TRACKS_LIST_DESCRIPTION = "Discover real track UUIDs in the flat bounded track window (tree pre-order). Each row has type, isGroup, groupExpanded (groups), parentId (null at top level), depth and parentResolved; tree.complete says every parent was observed. All our track/device/clip/channel indices are zero-based. Inspect window completeness before choosing targets; example UUIDs are not real targets."
TRACKS_GET_DESCRIPTION = "Read one track's mix state and bounded sends by UUID from tracks.list. Values explicitly named Normalized are in 0..1, not dB. Fields may be null after target changes; inspect readable, observationsComplete and sendObservationsComplete rather than treating partial observations as current values. Also reads color (#rrggbb), activated, monitorMode and crossFadeMode; options lists the host's own values for the two enums (these four never decide observationsComplete)."
TRACKS_SET_DESCRIPTION = "Set only differing fields from a fresh tracks.get snapshot, by UUID; omitted fields stay unchanged. Returns outcome (the worst field's) and per-field outcome with reason; a field already at the value is already_matched. A redundant same-value write on a rebound proxy may remain unknown without a fresh callback; never automatically retry unknown. Values named Normalized are 0..1, not dB. volumeDb/volumeDisplay (e.g. -6 / '-6 dB') and panPercent/panDisplay (e.g. -25 / '25% L') are alternatives to the normalized fields: the API has no dB setter and no published curve, so the extension bisects the normalized value against the host's own displayed text and verifies the final display (displayWrite carries the probe count and the final observed display). An unreadable or unreachable display restores the original value, applies no other field of the call and reports outcome not_applied (unknown when the restore is not confirmed) with displayWrite.failure (display_not_reachable, display_unparseable, probe_not_observed); several fader moves are written, so expect brief level changes while it searches. color (#RRGGBB), activated (false deactivates the track), monitorMode and crossFadeMode (one of tracks.get options, the host's own list; an unlisted value is refused before any write) use the ordinary per-field readback."
TRACKS_SENDS_SET_DESCRIPTION = "Set one send of a track by exact sendIndex or unique sendName (from tracks.get, whose send rows also name the effect track each send feeds) with optional expectedSendName compare-and-set. valueNormalized is 0..1 (not dB); display writes the level as the host shows it ('-12 dB', '-inf dB') by bisecting against the observed display, verified by display readback. enabled switches the send; a level write already turns a disabled send on (autoEnabled in the result; guide 7.1.6: new projects start with all sends disabled). Written last-wins: level first, then enabled. Inspect the outcome and fields[].outcome/reason; never retry unknown."
TRACKS_METERS_DESCRIPTION = f"Read VU meter peak and RMS (left/right, steps 0..127; the public API publishes no dB mapping) over windowMs for 1..{TRACK_WINDOW} track UUIDs. Meters (guide 7.1.8) only move while audio renders, so start playback first (transport.set playing=true; consider audio.route silent). Returns per track peakMax, rmsMax, rmsMean, callbacks, silent. Not LUFS: use audio.capture + audio.analyze mode=loudness for that."
TRACKS_SELECT_DESCRIPTION = "Select an exact discovered track UUID in the host UI. Returns outcome sent: the command was dispatched and navigation is not observed; a selected snapshot does not acknowledge it. Inspect the actual UI and possible automatic arming before dependent edits; never blindly retry. Reads do not require selection."
TRACKS_CREATE_DESCRIPTION = "Create and name a top-level track. position=-1 appends; otherwise a zero-based index among top-level main tracks (groups count, their children do not) for audio/instrument, among top-level effect tracks for effect. In a project with groups, tracks.list tree.complete must be true. Inspect the creation identity before using the new track. An unresolved creation holds further creates (reason unresolved); inspect actual state, never blindly retry or reset to bypass ambiguity."
TRACKS_GROUP_DESCRIPTION = f"Put existing tracks into one new group track (the guide's select + Ctrl+G), without using the UI selection. trackIds must share one parent (top level or the same group); effect and master tracks are refused. The new group is inserted where the first of them was, their order is kept, and the native operation selects the group. Optional name is applied after the group is observed. Verified by one new Group UUID whose children are exactly trackIds (tracks.list parentId), every other parent unchanged. Needs the inspected Bitwig 6.1.4 JAR, recording/automation-write off, at most {TRACK_WINDOW - 1} tracks in the window and every parent observed (tracks.list tree.complete). Unknown outcomes hold tracks.create/tracks.group until you inspect tracks.list and ack the hold id; never retry."
TRACKS_DELETE_DESCRIPTION = f"Delete one audio/instrument/hybrid/effect track (Track.deleteObject; never master or group). Needs the exact trackId and expectedName (current name, CAS) from tracks.list and observed stopped transport with recording/automation write off. Verified by the UUID leaving the bank and the count dropping by one (outcome applied); outcome unknown (not_observed) means inspect state and never retry. Only Audio/Instrument/Hybrid/Effect tracks (group and master refused; bounce.inPlace can turn an instrument track Hybrid) and only while the whole project fits the {TRACK_WINDOW}-track bank window (offset 0, at most {TRACK_WINDOW} tracks incl. group children and effect tracks), else not_ready/not_applied. Destructive: removes the track's clips and devices; Bitwig's own Undo is the only recovery (one Undo restores the track with the same UUID, so Undo steps also cover deletes). Bitwig user guide, Intro to Tracks, the Edit Functions and Moving Tracks section (bitwig.com/userguide/latest/intro_to_tracks): select the track and press DELETE or BACKSPACE."


SPECS: dict[str, ToolSpec] = {
    "tracks.list": ToolSpec(StatusArgs, TRACKS_LIST_DESCRIPTION, True, records="tracks"),
    "tracks.get": ToolSpec(DeviceArgs, TRACKS_GET_DESCRIPTION, True, examples=({"trackId": TRACK},)),
    "tracks.set": ToolSpec(
        TrackSetArgs,
        TRACKS_SET_DESCRIPTION,
        False,
        examples=(
            {"trackId": TRACK, "mute": True},
            {"trackId": TRACK, "color": "#ff8800", "monitorMode": "ON"},
            {"trackId": TRACK, "volumeDb": -6, "panPercent": -25},
            {"trackId": TRACK, "volumeDisplay": "-6 dB", "panDisplay": "25% L"},
        ),
        summary="Set only the fields that differ, by track UUID from tracks.list; omitted fields stay unchanged. Values named Normalized are 0..1, not dB; volumeDb/volumeDisplay and panPercent/panDisplay are alternatives. Returns the worst field outcome and a per-field outcome with reason; never retry unknown.",
    ),
    "tracks.sends.set": ToolSpec(
        TrackSendSetArgs,
        TRACKS_SENDS_SET_DESCRIPTION,
        False,
        examples=(
            {"trackId": TRACK, "sendIndex": 0, "expectedSendName": "A-Reverb", "valueNormalized": 0.5},
            {"trackId": TRACK, "sendName": "A-Reverb", "display": "-12 dB", "enabled": True},
        ),
        summary="Set one send of a track by sendIndex or sendName (from tracks.get). valueNormalized is 0..1, not dB; display ('-12 dB') also works. A level write turns a disabled send on. expectedSendName guards the target. Check fields[].outcome; never retry unknown.",
    ),
    "tracks.meters": ToolSpec(
        TrackMetersArgs, TRACKS_METERS_DESCRIPTION, True, examples=({"trackIds": [TRACK], "windowMs": 1000},)
    ),
    "tracks.select": ToolSpec(DeviceArgs, TRACKS_SELECT_DESCRIPTION, False, examples=({"trackId": TRACK},)),
    "tracks.create": ToolSpec(
        TrackCreateArgs,
        TRACKS_CREATE_DESCRIPTION,
        False,
        examples=({"type": "instrument", "position": -1, "name": "Agent Sketch"},),
        requires=PROJECT_READY,
    ),
    "tracks.group": ToolSpec(
        TrackGroupArgs,
        TRACKS_GROUP_DESCRIPTION,
        False,
        examples=({"trackIds": [TRACK, "12345678-1234-1234-1234-123456789abd"], "name": "Drums"},),
        requires=PROJECT_READY,
        annotations=ADD,
    ),
    "tracks.delete": ToolSpec(
        TrackDeleteArgs,
        TRACKS_DELETE_DESCRIPTION,
        False,
        examples=({"trackId": TRACK, "expectedName": "Track name"},),
        requires=PROJECT_READY,
    ),
}
