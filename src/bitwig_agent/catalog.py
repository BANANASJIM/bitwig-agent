"""The public domain catalog; no transport, MCP runtime or host initialization."""

from dataclasses import dataclass
from typing import Any

from bitwig_agent import schema


@dataclass(frozen=True)
class ToolSpec:
    model: type[schema.StatusArgs]
    description: str
    read_only: bool
    examples: tuple[dict[str, Any], ...]

    def describe(self) -> dict[str, Any]:
        return {"description": self.description, "readOnly": self.read_only,
                "inputSchema": self.model.model_json_schema(), "examples": list(self.examples)}


# Example UUIDs are placeholders: discover real targets with tracks.list first.
TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
CLIP = {"trackId": TRACK, "slotIndex": 0}
NOTE_WINDOW = {**CLIP, "channel": 0, "stepSizeBeats": 0.25, "startStep": 0,
               "steps": 16, "pitchStart": 0, "pitchCount": 128}


def tool(model, description, read_only, *examples):
    return ToolSpec(model, description, read_only, examples or ({},))


TOOLS: dict[str, ToolSpec] = {
    "status": tool(schema.StatusArgs, "Observe project/transport state, not proof of audio or persistence.", True),
    "capabilities": tool(schema.StatusArgs, "Discover implemented host methods, bounded windows, units and unsupported interfaces. Local CLI list/describe work even without Bitwig; this method queries the running extension.", True),
    "tracks.list": tool(schema.StatusArgs, "Discover real track UUIDs in the flat bounded track window. All our track/device/clip/channel indices are zero-based. Inspect window completeness before choosing targets; example UUIDs are not real targets.", True),
    "tracks.get": tool(schema.DeviceArgs, "Read one track's mix state and bounded sends by UUID from tracks.list. Values explicitly named Normalized are in 0..1, not dB. Fields may be null after target changes; inspect readable, observationsComplete and sendObservationsComplete rather than treating partial observations as current values.", True, {"trackId": TRACK}),
    "tracks.set": tool(schema.TrackSetArgs, "Change only supplied track fields by UUID. Example: trackId=<UUID> mute=true volumeNormalized=0.5. Read observed verification; absent fields are unchanged.", False, {"trackId": TRACK, "mute": True}),
    "tracks.select": tool(schema.DeviceArgs, "Explicitly select a track in the host UI by discovered UUID. Selection is a UI side effect; reads do not require it.", False, {"trackId": TRACK}),
    "tracks.create": tool(schema.TrackCreateArgs, "Create and name a track. position=-1 appends; otherwise a zero-based main-track index for audio/instrument, effect-list index for effect. Inspect creation identity/verification before using the new track. An unresolved creation quarantines further creates for this extension instance (unresolved_creation); inspect actual state, never blindly retry or reset to bypass ambiguity.", False, {"type": "instrument", "position": -1, "name": "Agent Sketch"}),
    "transport.get": tool(schema.StatusArgs, "Read transport playback, tempo in BPM, beat position, metronome and loop settings.", True),
    "transport.set": tool(schema.TransportSetArgs, "Change only supplied transport fields; e.g. tempoBpm=120 playing=false. Supported tempo range 20..666 BPM is a toolkit bound. Beat positions are nonnegative; loop duration is positive and loop end finite. Playback can produce audio. Recording or automation-write mode blocks changes except an explicit playing=false stop; modes are never silently disabled.", False, {"tempoBpm": 120.0, "playing": False}),
    "devices.list": tool(schema.DeviceArgs, "Read source-bound top-level device observations without changing host cursor/selection. Discover zero-based deviceIndex and exact expectedDeviceName, within the returned window.", True, {"trackId": TRACK}),
    "devices.get": tool(schema.ParameterArgs, "Read a source-bound device's state and unsupported-interface details without selecting it. Use exact name and zero-based index from devices.list.", True, TARGET),
    "devices.set": tool(schema.DeviceSetArgs, "Enable or bypass an explicitly identified device using enabled=true/false. Inspect fresh observation or unknown outcome before another write.", False, {**TARGET, "enabled": False}),
    "devices.select": tool(schema.ParameterArgs, "Explicitly select this device in the host UI. Unlike source-bound reads, this intentionally changes selection.", False, TARGET),
    "devices.catalog": tool(schema.StatusArgs, "Discover installed Poly/FX/Note Grid factory device files using the host's installationRoot. Copy kind=file and path into devices.insert with an observed track/count. Only existing files are listed; no guessed community UUIDs or exhaustive plug-in inventory. File presence is not insertion or musical verification.", True),
    "devices.insert": tool(schema.DeviceInsertArgs, "Append a native device, plug-in or preset to the track's end chain. Discover expectedDeviceCount with devices.list; truncated windows are rejected. kind=bitwig requires canonical UUID id; vst2 signed int32 decimal id; vst3 32 hex id; clap printable ASCII id. kind=file requires absolute normalized path instead of id. No shell/path expansion. An observed added device does not prove its requested native UUID: inspect requestedIdentifierVerified. Insertion can load plug-ins or produce audio; observe before retrying.", False, {"trackId": TRACK, "expectedDeviceCount": 0, "kind": "clap", "id": "org.example.synth"}),
    "devices.remote.get": tool(schema.ParameterArgs, "Read a source-device-bound remote-control page and fresh contextToken without changing the GUI page. Discover exact page/control names and zero-based indices. Token tracks observed metadata, not unobservable same-name remaps.", True, TARGET),
    "devices.remote.page": tool(schema.RemotePageArgs, "Change this extension's independent remote page, not the GUI-selected page. Use current contextToken and target pageIndex/expectedPageName from devices.remote.get; get a fresh token after switching.", False, {**TARGET, "contextToken": "from-devices.remote.get", "pageIndex": 0, "expectedPageName": "Main"}),
    "devices.remote.set": tool(schema.RemoteSetArgs, "Set one normalized remote control with exact page/control names and fresh contextToken from devices.remote.get. Indices are zero-based; inspect fresh same-source value verification. Unobservable same-name mapping changes are not guaranteed detectable.", False, {**TARGET, "contextToken": "from-devices.remote.get", "expectedPageName": "Main", "parameterIndex": 0, "expectedParameterName": "Cutoff", "normalizedValue": 0.5}),
    "parameters.list": tool(schema.ParameterArgs, "Read source-bound direct parameters and contextToken without changing host cursor/selection. Preserve trackId, deviceIndex, exact expectedDeviceName and fresh token for parameters.set. Bank context is not persistent physical-device identity.", True, TARGET),
    "parameters.set": tool(schema.SetArgs, "Set one normalized direct parameter using the exact target, parameterId and contextToken from parameters.list. Example: value=0.5. Inspect scope and verification. Never retry an unknown outcome without inspecting state.", False, {**TARGET, "contextToken": "from-parameters.list", "parameterId": "from-parameters.list", "value": 0.5}),
    "grid.capabilities": tool(schema.StatusArgs, "Inspect the read-only Grid binding and exact Bitwig 6.1.3/loaded-JAR hash gate. Reports catalog, structure and typed-parameter coverage separately; no graph writes or native undo. Availability is not native runtime verification.", True),
    "grid.catalog": tool(schema.GridCatalogArgs, "Read native Grid MODULE type UUIDs and names, not devices or module instances. Version/hash gated. query is a case-insensitive name substring (max 128 UTF-16 units); offset 0..100000, limit 1..256 (default 64), deterministic ordering. Inspect window completeness; catalog presence does not prove graph or parameter coverage.", True, {}, {"query": "osc", "offset": 0, "limit": 32}),
    "grid.get": tool(schema.GridGetArgs, "Read a version/hash-gated Grid graph without selecting or changing the device. Discover trackId with tracks.list, then deviceIndex and exact expectedDeviceName with devices.list. moduleOffset 0..100000, moduleLimit 1..128 (default 64), includeParameters defaults true. Inspect coverage and completeness: instance UUID, type UUID and list-local key are different identities; no native revision or full snapshot guarantee across pages. No graph writes or native undo.", True, TARGET, {**TARGET, "moduleOffset": 0, "moduleLimit": 32, "includeParameters": False}),
    "grid.parameters.get": tool(schema.GridParameterGetArgs, "Read one version/hash-gated Grid module parameter and native edit-context observations without selection or mutation. Use explicit trackId/deviceIndex/expectedDeviceName and a fresh grid.get module instanceId as moduleId (reacquire after project reopen), plus its exact parameterId, never a display name or type UUID. parameterId may be empty and is bounded to 1024 UTF-16 units. editContext reports observations only, not write authorization, readiness, DSP or undo proof. No mutation support, native undo, automatic retries or GUI fallback.", True, {**TARGET, "moduleId": "abcdef01-2345-6789-abcd-0123456789ab", "parameterId": "from-grid.get"}),
    "launcher.list": tool(schema.DeviceArgs, "Discover occupied and empty launcher clip slots for one track UUID; slotIndex is zero-based within the bounded window.", True, {"trackId": TRACK}),
    "launcher.create": tool(schema.ClipCreateArgs, "Create a named MIDI clip only in an empty zero-based slot. Selects that clip in the host UI to bind and verify the explicit target. Example: trackId=<UUID> slotIndex=0 lengthBeats=4 name=Sketch. Then use notes.edit with an explicit grid/window; never overwrites an existing clip.", False, {**CLIP, "lengthBeats": 4, "name": "Sketch"}),
    "launcher.import": tool(schema.ClipImportArgs, "Import a local audio or clip file into an EMPTY explicit launcher slot by track UUID and zero-based slotIndex (0..15). Occupied slots reject; no overwrite option. path must be absolute and normalized, without URI, shell/path expansion or control characters. The host checks file existence/readability; the client checks syntax only. Result reports only observed slot content transition, not file identity or decoded audio verification. Never blindly retry an unknown outcome.", False, {**CLIP, "path": "/absolute/path/audio.wav"}),
    "launcher.launch": tool(schema.ClipArgs, "Launch an existing clip by explicit track UUID and zero-based slot. Can start playback and produce audio. Recording and both automation-write flags must be observed false; modes are never silently disabled. Dispatch does not prove musical/audio completion.", False, CLIP),
    "launcher.stop": tool(schema.DeviceArgs, "Stop launcher playback on one track UUID; does not delete clips or stop the entire transport.", False, {"trackId": TRACK}),
    "notes.read": tool(schema.NoteWindowArgs, "Read an explicit clip/channel/grid/pitch window; selects the target clip in the host UI. channel is zero-based MIDI 0..15; pitch is MIDI 0..127; beat 0 is clip start. stepSizeBeats=0.25,startStep=0,steps=16 covers four beats. Inspect gridReadinessLimitation: scheduled settling is not a host-data barrier; late callbacks lack window identity. Inspect continuingAtWindowStart, notesInWindow, notesReturned and completeWindow: at most 2048 onsets returned; subdivide steps or pitchCount when incomplete. completeClip is always false; onsetPrecision is grid_cell, not arbitrary sub-grid/multiple-onset fidelity.", False, NOTE_WINDOW),
    "notes.edit": tool(schema.NoteEditArgs, "Edit up to 256 onset cells; selects the target clip in the host UI. Each set requires op=set, absolute beat, MIDI pitch, velocity 1..127 and durationBeats; clear requires only op=clear,beat,pitch. Beat/duration must align exactly to stepSizeBeats without rounding; entire note must fit requested window and observed clip loop. No duplicate cells, overlapping same-pitch sets or clear-all; sustains are protected and already-matching cells are not rewritten. Example edits=[{\"op\":\"set\",\"beat\":0,\"pitch\":60,\"velocity\":90,\"durationBeats\":1}]. Inspect gridReadinessLimitation: scheduled settling is not a host-data barrier, and late callbacks lack window identity. Per-edit readbacks are separate from the capped 2048-onset listing. Inspect per-edit verification and completeWindow, then notes.read. No automatic retry.", False, {**NOTE_WINDOW, "edits": [{"op": "set", "beat": 0.0, "pitch": 60, "velocity": 90, "durationBeats": 1.0}]}),
    "actions.list": tool(schema.StatusArgs, "List native host Action IDs, names and categories. Availability does not prove completion in the current UI context.", True),
    "actions.invoke": tool(schema.ActionArgs, "Invoke one exact ID from actions.list in the current host/UI context. May edit the project or open a dialog. Returns dispatched only, never completion; inspect state before another write and do not blindly retry.", False, {"actionId": "from-actions.list"}),
}


def validate_call(method: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], int]:
    if method not in TOOLS:
        raise ValueError(f"Unknown method: {method}. Use list or describe <method> offline.")
    args = TOOLS[method].model.model_validate(arguments)
    return args.model_dump(exclude={"timeoutMs"}, exclude_unset=True), args.timeoutMs
