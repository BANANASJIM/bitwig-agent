from __future__ import annotations

from typing import TYPE_CHECKING, Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    NOT_RECORDING,
    PROJECT_READY,
    STOPPED,
    Nonempty,
    StatusArgs,
    StrictModel,
    ToolSpec,
    omitted,
)
from bitwig_agent.protocol.limits import TRACK_WINDOW
from bitwig_agent.protocol.refs import SidechainRef, TrackDeviceRef, TrackRef
from bitwig_agent.protocol.shared import shared
from bitwig_agent.protocol.tracks import EXAMPLE_OTHER, EXAMPLE_TRACK
from bitwig_agent.protocol.values import Expect

ROUTING_TRACK_KINDS = frozenset({"track_audio_input", "track_note_input", "pre_fader", "post_fader", "track_notes"})
HARDWARE_KINDS = frozenset({"hardware_audio_input", "hardware_note_input"})
OutputKind = Literal["none", "track_audio_input", "track_note_input"]
SidechainSourceKind = Literal[
    "none", "pre_fader", "post_fader", "track_notes", "all_ins", "computer_keyboard", "onscreen_keyboard"
]
InputKind = Literal[
    "none",
    "pre_fader",
    "post_fader",
    "track_notes",
    "all_ins",
    "computer_keyboard",
    "onscreen_keyboard",
    "hardware_audio_input",
    "hardware_note_input",
]


ROUTING_TRACK = f"A track ref in the current {TRACK_WINDOW}-track window (tracks.list), a group included."
HARDWARE_ID = (
    "The stable id of a hardware entry from tracks.routing.get includeChoices: an audio input bus UUID, or"
    " controllerUUID/instance/noteInputIndex for a controller's note input; only with a hardware kind."
)


class RoutingEntry(StrictModel):
    if TYPE_CHECKING:
        kind: str

    track: TrackRef = omitted(description=ROUTING_TRACK)
    id: Annotated[str, Field(min_length=1, max_length=256)] = omitted(description=HARDWARE_ID)

    @model_validator(mode="after")
    def validate_track(self) -> Self:
        if ("track" in self.model_fields_set) != (self.kind in ROUTING_TRACK_KINDS):
            raise ValueError(f"track is required for {sorted(ROUTING_TRACK_KINDS)} and not allowed otherwise")
        if ("id" in self.model_fields_set) != (self.kind in HARDWARE_KINDS):
            raise ValueError(f"id is required for {sorted(HARDWARE_KINDS)} and not allowed otherwise")
        return self


class OutputEntry(RoutingEntry):
    kind: OutputKind = Field(
        description="none, or the audio/note input of another track (a group, an effect track or master by its track ref)."
    )


class NamedEntry(RoutingEntry):
    """An entry that may carry the display name a get reported beside a hardware id, so it can be copied verbatim."""

    name: str = omitted(
        max_length=4096,
        description="Display name of a hardware entry as the get reported it (informational, not compared).",
    )

    @model_validator(mode="after")
    def validate_name(self) -> Self:
        if "name" in self.model_fields_set and self.kind not in HARDWARE_KINDS:
            raise ValueError("name is only allowed with a hardware kind")
        return self


class InputEntry(NamedEntry):
    kind: InputKind = Field(
        description="none; pre_fader/post_fader (audio) or track_notes of another track; all_ins, computer_keyboard, onscreen_keyboard (notes); hardware_audio_input (an audio input bus) or hardware_note_input (a controller's note input) by id."
    )


class SourceEntry(RoutingEntry):
    kind: SidechainSourceKind = Field(
        description="none; pre_fader/post_fader (audio) or track_notes of another track; all_ins, computer_keyboard, onscreen_keyboard (notes)."
    )


class ObservedEntry(RoutingEntry):
    """An entry exactly as a fresh get reported it; kind other (device output, ...) is matched by its exact name and nativeType, a hardware entry by its id."""

    name: str = omitted(
        max_length=4096,
        description="Exact (possibly empty) name of a kind other entry from the get (compared), or a hardware entry's display name (not compared).",
    )
    nativeType: Nonempty = omitted(
        description="nativeType of a kind other entry from the get; optional, only with kind other."
    )

    @model_validator(mode="after")
    def validate_name(self) -> Self:
        if "name" in self.model_fields_set and self.kind not in HARDWARE_KINDS | {"other"}:
            raise ValueError("name is only allowed with kind other or a hardware kind")
        if "name" not in self.model_fields_set and self.kind == "other":
            raise ValueError("name is required for kind other")
        if "nativeType" in self.model_fields_set and self.kind != "other":
            raise ValueError("nativeType is only allowed with kind other")
        return self


class ExpectedOutputEntry(ObservedEntry):
    kind: Literal["none", "track_audio_input", "track_note_input", "other"]


class ExpectedInputEntry(ObservedEntry):
    kind: Literal[
        "none",
        "pre_fader",
        "post_fader",
        "track_notes",
        "all_ins",
        "computer_keyboard",
        "onscreen_keyboard",
        "hardware_audio_input",
        "hardware_note_input",
        "other",
    ]


class RoutingExpect(Expect):
    output: ExpectedOutputEntry = omitted()
    input: ExpectedInputEntry = omitted()


class SidechainExpect(Expect):
    source: ExpectedInputEntry


class TrackRoutingGetArgs(StatusArgs):
    target: TrackRef
    includeChoices: bool = omitted(
        description="Also list every settable entry with accepted (Bitwig's own cycle check), including the hardware audio input buses and controller note inputs."
    )


class TrackRoutingSetArgs(StatusArgs):
    target: TrackRef
    output: OutputEntry = omitted()
    input: InputEntry = omitted()
    expect: RoutingExpect = omitted(
        description="Keyed like the write: expect.output / expect.input is the entry object of output / input from a fresh tracks.routing.get, copied verbatim; a mismatch writes nothing."
    )

    @model_validator(mode="after")
    def validate_fields(self) -> Self:
        given = self.model_fields_set
        if not given & {"output", "input"}:
            raise ValueError("supply output, input or both")
        if "expect" in given and self.expect.model_fields_set - given:
            raise ValueError("expect keys must also be written")
        return self


class SidechainGetArgs(StatusArgs):
    target: TrackDeviceRef
    includeChoices: bool = omitted(
        description="Also list every v1 source with accepted (Bitwig's own cycle check) per writable component."
    )


class SidechainSetArgs(StatusArgs):
    target: SidechainRef
    source: SourceEntry
    expect: SidechainExpect = omitted(
        description="The component's source from a fresh devices.sidechain.get; a mismatch writes nothing."
    )


_SIDECHAIN = shared("sidechain")  # the Java hint for an empty components list reads the same file
WITH_SIDECHAIN = ", ".join(_SIDECHAIN["audioSidechainDevices"])
WITHOUT_SIDECHAIN = ", ".join(_SIDECHAIN["withoutSidechain"])
EXAMPLE_BUS = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
EXAMPLE_NOTE_INPUT = "a1b2c3d4-e5f6-4789-abcd-ef0123456789/0/0"
EXAMPLE_DEVICE = {"kind": "device", "track": EXAMPLE_TRACK, "index": 0, "name": _SIDECHAIN["audioSidechainDevices"][0]}
EXAMPLE_SIDECHAIN = {"kind": "sidechain", "device": EXAMPLE_DEVICE, "index": 0, "id": "from-devices.sidechain.get"}

TRACKS_ROUTING_GET_DESCRIPTION = "Read one track's output chooser and, for audio/instrument/hybrid tracks, its input chooser (the guide's Track I/O Settings), by track ref. output.entry / input.entry are {kind, track?} with track a track ref, or {kind, id, name} for hardware (copy an entry verbatim into the write or into expect.output / expect.input; the display name is beside them): output none | track_audio_input | track_note_input (another track, group, effect track or master); input none | pre_fader | post_fader | track_notes (another track) | all_ins | computer_keyboard | onscreen_keyboard | hardware_audio_input (an audio input bus of the audio settings, id = its UUID) | hardware_note_input (a controller's note input, id = controllerUUID/instance/noteInputIndex); anything else (device outputs, master's own hardware output, a hardware input this version cannot name) reads as {kind other, name, nativeType} and cannot be set. input is null with inputUnavailableReason where the track type has no input chooser (group, effect, master). kinds lists what that chooser accepts; writable says whether this version can set it. includeChoices adds choices [{entry, name, accepted}] for every bank track and every hardware input (hardware rows are not cycle-checked: accepted true), unresolvedTracks, and hardwareInputs {audio, notes}: null when listed, else why not (audio buses are listed only for a backend with configured buses, PipeWire or JACK). nativeTrackKind/nativeRole are diagnostics. Native read (version-gated); no selection, no stopped transport needed. A track in an entry is a track ref with its public name, since native bus names are blank or generic (\"Pre-Fader\")."
TRACKS_ROUTING_SET_DESCRIPTION = "Set a track's output and/or input chooser entry, optionally with expect (expect.output / expect.input: the entry object of output / input from a fresh tracks.routing.get, copied verbatim; mismatch: stale/target_changed with observed, nothing changed). Entries name tracks of the current window by track ref (entry.track), hardware inputs by the id from includeChoices (a hardware_audio_input on an audio or hybrid track, a hardware_note_input on an instrument or hybrid track; an id no longer listed is target_not_found). Refused before any write (not_applied): kind_mismatch (an audio track's input takes only pre_fader/post_fader and audio inputs, an instrument track's only note sources; Bitwig's setter does not check this itself), routing_cycle (Bitwig's own cycle check, a track routed to itself, or output and input naming the same track in one call), unsupported_scope (no input chooser, master output, no listable hardware input). Both fields change inside one project undo transaction; each is read back by native identity (a hardware input by its bus or controller identity): applied, already_matched, or unknown. Returns ref. Needs stopped transport with recording/automation-write off. An unknown field holds routing writes until you inspect with tracks.routing.get and ack routing.quarantine; never retry. Selecting an input does not arm the track or record."
DEVICES_SIDECHAIN_GET_DESCRIPTION = (
    "Read the sidechain source choosers of one top-level device (a device ref from devices.list): the native device's own audio/note sidechain ("
    + WITH_SIDECHAIN
    + ", ...; the plain "
    + WITHOUT_SIDECHAIN
    + " has none, and an empty components list says so), its modulators' (Audio Sidechain, Note Sidechain, Audio Rate), its Grid Audio Sidechain modules and a plug-in's audio sidechain. components [{ref (the sidechain ref devices.sidechain.set takes), label, kind audio|note|plugin_audio|audio_in|other, host device|modulator|grid_module, kinds, writable, reason?, source (an entry), sourceName, choices?}]; source kinds as tracks.routing.get input, without the hardware kinds (a hardware source reads as an entry but cannot be set here). Nested devices inside chains/layers are not included. includeChoices adds choices [{entry, name, accepted}] for every bank track, accepted. Native read (version-gated); no selection."
)
DEVICES_SIDECHAIN_SET_DESCRIPTION = "Set one sidechain component's source, addressed by the sidechain ref devices.sidechain.get returned (its index, componentId and owning modulator or Grid module are compared, so a shifted component is stale), optionally with expect.source (its source entry from a fresh get). The component's kinds are what it accepts, e.g. an audio sidechain pre_fader/post_fader of a track, a note sidechain track_notes; anything else is kind_mismatch (Bitwig's setter does not check it). Returns ref. Same refusals, undo transaction, identity readback and unknown hold as tracks.routing.set; recording and automation write must be off, the transport may play (pending native verification)."


SPECS: dict[str, ToolSpec] = {
    "tracks.routing.get": ToolSpec(
        TrackRoutingGetArgs,
        TRACKS_ROUTING_GET_DESCRIPTION,
        True,
        examples=(
            {"target": EXAMPLE_TRACK},
            {"target": EXAMPLE_TRACK, "includeChoices": True},
        ),
        summary="Read one track's output chooser and, for audio/instrument/hybrid tracks, its input chooser by track ref. the entry of output and of input is copied verbatim into expect.output / expect.input of tracks.routing.set; kinds lists what a chooser accepts, writable whether it can be set. includeChoices lists every track's accepted entries and the hardware audio inputs and controller note inputs by stable id.",
    ),
    "tracks.routing.set": ToolSpec(
        TrackRoutingSetArgs,
        TRACKS_ROUTING_SET_DESCRIPTION,
        False,
        examples=(
            {
                "target": EXAMPLE_TRACK,
                "output": {"kind": "track_audio_input", "track": EXAMPLE_OTHER},
                "expect": {"output": {"kind": "none"}},
            },
            {
                "target": EXAMPLE_TRACK,
                "input": {"kind": "post_fader", "track": EXAMPLE_OTHER},
                "expect": {"input": {"kind": "none"}},
            },
            {
                "target": EXAMPLE_TRACK,
                "output": {"kind": "none"},
                "input": {"kind": "none"},
                "expect": {
                    "output": {"kind": "track_audio_input", "track": EXAMPLE_OTHER},
                    "input": {"kind": "post_fader", "track": EXAMPLE_OTHER},
                },
            },
            {
                "target": EXAMPLE_TRACK,
                "input": {"kind": "hardware_audio_input", "id": EXAMPLE_BUS, "name": "Stereo 1"},
                "expect": {"input": {"kind": "none"}},
            },
            {
                "target": EXAMPLE_TRACK,
                "input": {"kind": "hardware_note_input", "id": EXAMPLE_NOTE_INPUT},
                "expect": {"input": {"kind": "all_ins"}},
            },
            {
                "target": EXAMPLE_TRACK,
                "input": {"kind": "all_ins"},
                "expect": {
                    "input": {
                        "kind": "other",
                        "name": "exact name from tracks.routing.get",
                        "nativeType": "from-tracks.routing.get",
                    }
                },
            },
            {
                "target": EXAMPLE_TRACK,
                "output": {"kind": "none"},
                "expect": {
                    "output": {
                        "kind": "other",
                        "name": "exact name from tracks.routing.get",
                        "nativeType": "from-tracks.routing.get",
                    }
                },
            },
        ),
        requires=PROJECT_READY + STOPPED,
    ),
    "devices.sidechain.get": ToolSpec(
        SidechainGetArgs,
        DEVICES_SIDECHAIN_GET_DESCRIPTION,
        True,
        examples=({"target": EXAMPLE_DEVICE},),
        summary="Read the sidechain source choosers of one top-level device (a device ref from devices.list): the device's own, its modulators', Grid Audio Sidechain modules' and a plug-in's. Each component carries the sidechain ref devices.sidechain.set takes. "
        + ", ".join(_SIDECHAIN["audioSidechainDevices"][:3])
        + " have an audio sidechain; plain "
        + WITHOUT_SIDECHAIN
        + " has none. includeChoices lists the accepted tracks.",
    ),
    "devices.sidechain.set": ToolSpec(
        SidechainSetArgs,
        DEVICES_SIDECHAIN_SET_DESCRIPTION,
        False,
        examples=(
            {
                "target": EXAMPLE_SIDECHAIN,
                "source": {"kind": "none"},
                "expect": {"source": {"kind": "pre_fader", "track": EXAMPLE_OTHER}},
            },
            {
                "target": EXAMPLE_SIDECHAIN,
                "source": {"kind": "pre_fader", "track": EXAMPLE_OTHER},
                "expect": {"source": {"kind": "none"}},
            },
            {
                "target": {**EXAMPLE_SIDECHAIN, "index": 1, "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab"},
                "source": {"kind": "track_notes", "track": EXAMPLE_OTHER},
                "expect": {
                    "source": {
                        "kind": "other",
                        "name": "exact name from devices.sidechain.get",
                        "nativeType": "from-devices.sidechain.get",
                    }
                },
            },
        ),
        requires=PROJECT_READY + NOT_RECORDING,
        summary="Set one sidechain component's source by the sidechain ref devices.sidechain.get returned; optional expect.source. An unaccepted kind is kind_mismatch.",
    ),
}
