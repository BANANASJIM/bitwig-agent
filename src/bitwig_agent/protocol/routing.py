from __future__ import annotations

from typing import TYPE_CHECKING, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    PROJECT_READY,
    TARGET,
    TRACK,
    DeviceArgs,
    Nonempty,
    ParameterArgs,
    StrictModel,
    ToolSpec,
    TrackId,
    omitted,
    tool,
)
from bitwig_agent.protocol.limits import TRACK_WINDOW

ROUTING_TRACK_KINDS = frozenset({"track_audio_input", "track_note_input", "pre_fader", "post_fader", "track_notes"})
OutputKind = Literal["none", "track_audio_input", "track_note_input"]
SourceKind = Literal[
    "none", "pre_fader", "post_fader", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard"
]


ROUTING_TRACK_ID = f"A track of the current {TRACK_WINDOW}-track window (tracks.list); for a group, its trackId."


class RoutingEntry(StrictModel):
    if TYPE_CHECKING:
        kind: str

    trackId: TrackId = omitted(description=ROUTING_TRACK_ID)

    @model_validator(mode="after")
    def validate_track(self) -> Self:
        if ("trackId" in self.model_fields_set) != (self.kind in ROUTING_TRACK_KINDS):
            raise ValueError(f"trackId is required for {sorted(ROUTING_TRACK_KINDS)} and not allowed otherwise")
        return self


class OutputEntry(RoutingEntry):
    kind: OutputKind = Field(
        description="none, or the audio/note input of another track (a group, an effect track or master by its trackId)."
    )


class SourceEntry(RoutingEntry):
    kind: SourceKind = Field(
        description="none; pre_fader/post_fader (audio) or track_notes of another track; all_ins, computer_keyboard, onscreen_keyboard (notes)."
    )


class ObservedEntry(RoutingEntry):
    """An entry exactly as a fresh get reported it; kind other (hardware, device output, ...) is matched by its exact name and nativeType."""

    name: str = omitted(
        max_length=4096,
        description="Exact (possibly empty) name of a kind other entry from the get; only with kind other.",
    )
    nativeType: Nonempty = omitted(
        description="nativeType of a kind other entry from the get; optional, only with kind other."
    )

    @model_validator(mode="after")
    def validate_name(self) -> Self:
        if ("name" in self.model_fields_set) != (self.kind == "other"):
            raise ValueError("name is required for kind other and not allowed otherwise")
        if "nativeType" in self.model_fields_set and self.kind != "other":
            raise ValueError("nativeType is only allowed with kind other")
        return self


class ExpectedOutputEntry(ObservedEntry):
    kind: Literal["none", "track_audio_input", "track_note_input", "other"]


class ExpectedSourceEntry(ObservedEntry):
    kind: Literal[
        "none", "pre_fader", "post_fader", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard", "other"
    ]


class TrackRoutingGetArgs(DeviceArgs):
    includeChoices: bool = omitted(description="Also list every v1 entry with accepted (Bitwig's own cycle check).")


class TrackRoutingSetArgs(DeviceArgs):
    output: OutputEntry = omitted()
    expectedOutput: ExpectedOutputEntry = omitted(
        description="output.entry from a fresh tracks.routing.get (compare-and-set)."
    )
    input: SourceEntry = omitted()
    expectedInput: ExpectedSourceEntry = omitted(
        description="input.entry from a fresh tracks.routing.get (compare-and-set)."
    )

    @model_validator(mode="after")
    def validate_pairs(self) -> Self:
        given = self.model_fields_set
        if not given & {"output", "input"}:
            raise ValueError("supply output, input or both")
        for field, expected in (("output", "expectedOutput"), ("input", "expectedInput")):
            if (field in given) != (expected in given):
                raise ValueError(f"{field} and {expected} must be supplied together")
        return self


class SidechainGetArgs(ParameterArgs):
    includeChoices: bool = omitted(
        description="Also list every v1 source with accepted (Bitwig's own cycle check) per writable component."
    )


class SidechainSetArgs(ParameterArgs):
    componentIndex: int = omitted(
        ge=0,
        le=255,
        description="componentIndex from a fresh devices.sidechain.get; exactly one of componentIndex or componentId.",
    )
    expectedComponentId: Nonempty = omitted(
        description="That row's componentId; required with componentIndex (guards against shifted components)."
    )
    componentId: Nonempty = omitted(
        description="componentId from devices.sidechain.get when it is unique on the device."
    )
    expectedModulatorId: TrackId = omitted(
        description="Required when the component's host is modulator: its modulatorId from the get."
    )
    expectedModuleId: TrackId = omitted(
        description="Required when the component's host is grid_module: its moduleId from the get."
    )
    source: SourceEntry
    expectedSource: ExpectedSourceEntry = Field(
        description="The component's source from a fresh devices.sidechain.get (compare-and-set)."
    )

    @model_validator(mode="after")
    def validate_component(self) -> Self:
        given = self.model_fields_set
        if ("componentIndex" in given) == ("componentId" in given):
            raise ValueError("supply exactly one of componentIndex or componentId")
        if ("componentIndex" in given) != ("expectedComponentId" in given):
            raise ValueError("componentIndex needs expectedComponentId, and only it")
        return self


TRACKS_ROUTING_GET_DESCRIPTION = "Read one track's output chooser and, for audio/instrument/hybrid tracks, its input chooser (the guide's Track I/O Settings), by trackId from tracks.list. output.entry / input.entry are {kind, trackId?} (copy them verbatim as expectedOutput/expectedInput; the display name is beside them): output none | track_audio_input | track_note_input (another track, group, effect track or master); input none | pre_fader | post_fader | track_notes (another track) | all_ins | computer_keyboard | onscreen_keyboard; anything else (hardware ports, device outputs, master's own hardware output) reads as {kind other, name, nativeType} and cannot be set. input is null with inputUnavailableReason where the track type has no input chooser (group, effect, master). kinds lists what that chooser accepts; writable says whether this version can set it. includeChoices adds choices [{entry, name, accepted}] for every bank track, accepted (Bitwig's own cycle check; rejected entries are listed, not hidden) and unresolvedTracks. nativeTrackKind/nativeRole are diagnostics. Native read (inspected Bitwig 6.1.4 JAR); no selection, no stopped transport needed. Rows naming a track carry its public name beside the entry (trackName; sourceTrackName for a sidechain source), since native bus names are blank or generic (\"Pre-Fader\")."
TRACKS_ROUTING_SET_DESCRIPTION = "Set a track's output and/or input chooser entry with compare-and-set: expectedOutput/expectedInput are the entries from a fresh tracks.routing.get (mismatch: stale_target, nothing changed). Entries name tracks of the current window by trackId. Refused before any write (not_applied): kind_mismatch (an audio track's input takes only pre_fader/post_fader, an instrument track's only note sources; Bitwig's setter does not check this itself), rejected_cycle (Bitwig's own cycle check, a track routed to itself, or output and input naming the same track in one call), unsupported_scope (no input chooser, master output). Both fields change inside one project undo transaction; each is read back by native identity: applied, already_matched, or unknown. Needs stopped transport with recording/automation-write off. An unknown field quarantines routing writes until extension.restart; inspect with tracks.routing.get, never retry."
DEVICES_SIDECHAIN_GET_DESCRIPTION = "Read the sidechain source choosers of one top-level device (trackId, deviceIndex, exact expectedDeviceName from devices.list): the native device's own audio/note sidechain (Compressor, Dynamics, Gate, Transient Control, ...), its modulators' (Audio Sidechain, Note Sidechain, Audio Rate; modulatorId as in modulators.get), its Grid Audio Sidechain modules (moduleId as grid.get instanceId) and a plug-in's audio sidechain. components [{componentIndex, componentId, label, kind audio|note|plugin_audio|audio_in|other, host device|modulator|grid_module, modulatorId?, moduleId?, kinds, writable, reason?, source (an entry), sourceName, choices?}]; source kinds as tracks.routing.get input. Nested devices inside chains/layers are not included. includeChoices adds choices [{entry, name, accepted}] for every bank track, accepted. Native read (inspected Bitwig 6.1.4 JAR); no selection."
DEVICES_SIDECHAIN_SET_DESCRIPTION = "Set one sidechain component's source with compare-and-set expectedSource (its source entry from a fresh devices.sidechain.get). Address it by componentIndex plus expectedComponentId, or by componentId when unique; a modulator's component also needs expectedModulatorId and a Grid module's expectedModuleId (components shift when their owners change). The component's kinds (from the get) are what it accepts, e.g. an audio sidechain pre_fader/post_fader of a track, a note sidechain track_notes; anything else is kind_mismatch (Bitwig's setter does not check it). Same refusals, undo transaction, identity readback, stopped-transport requirement and unknown-quarantine as tracks.routing.set."


SPECS: dict[str, ToolSpec] = {
    "tracks.routing.get": tool(
        TrackRoutingGetArgs,
        TRACKS_ROUTING_GET_DESCRIPTION,
        True,
        {"trackId": TRACK},
        {"trackId": TRACK, "includeChoices": True},
    ),
    "tracks.routing.set": tool(
        TrackRoutingSetArgs,
        TRACKS_ROUTING_SET_DESCRIPTION,
        False,
        {
            "trackId": TRACK,
            "output": {"kind": "track_audio_input", "trackId": "12345678-1234-1234-1234-123456789abd"},
            "expectedOutput": {"kind": "track_audio_input", "trackId": "12345678-1234-1234-1234-123456789abe"},
        },
        {
            "trackId": TRACK,
            "input": {"kind": "post_fader", "trackId": "12345678-1234-1234-1234-123456789abd"},
            "expectedInput": {"kind": "none"},
        },
        {
            "trackId": TRACK,
            "input": {"kind": "all_ins"},
            "expectedInput": {"kind": "other", "name": "exact name from tracks.routing.get"},
        },
        {
            "trackId": TRACK,
            "output": {"kind": "none"},
            "expectedOutput": {
                "kind": "other",
                "name": "exact name from tracks.routing.get",
                "nativeType": "from-tracks.routing.get",
            },
        },
        requires=PROJECT_READY,
    ),
    "devices.sidechain.get": tool(
        SidechainGetArgs, DEVICES_SIDECHAIN_GET_DESCRIPTION, True, {**TARGET, "expectedDeviceName": "Compressor"}
    ),
    "devices.sidechain.set": tool(
        SidechainSetArgs,
        DEVICES_SIDECHAIN_SET_DESCRIPTION,
        False,
        {
            **TARGET,
            "expectedDeviceName": "Compressor",
            "componentIndex": 0,
            "expectedComponentId": "from-devices.sidechain.get",
            "source": {"kind": "pre_fader", "trackId": "12345678-1234-1234-1234-123456789abd"},
            "expectedSource": {"kind": "none"},
        },
        {
            **TARGET,
            "expectedDeviceName": "Dynamics",
            "componentId": "from-devices.sidechain.get",
            "source": {"kind": "none"},
            "expectedSource": {"kind": "post_fader", "trackId": "12345678-1234-1234-1234-123456789abd"},
        },
        {
            **TARGET,
            "expectedDeviceName": "Polysynth",
            "componentIndex": 1,
            "expectedComponentId": "from-devices.sidechain.get",
            "expectedModulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
            "source": {"kind": "track_notes", "trackId": "12345678-1234-1234-1234-123456789abd"},
            "expectedSource": {
                "kind": "other",
                "name": "exact name from devices.sidechain.get",
                "nativeType": "from-devices.sidechain.get",
            },
        },
        requires=PROJECT_READY,
    ),
}
