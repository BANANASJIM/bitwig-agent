"""Strict public arguments shared by MCP, the diagnostic CLI and TCP callers."""

from __future__ import annotations

from decimal import Decimal
import math
import re
from pathlib import PurePosixPath
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Nonempty = Annotated[str, Field(min_length=1, max_length=4096)]
TrackId = Annotated[str, Field(pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")]
UnitValue = Annotated[float, Field(ge=0, le=1)]


def omitted(**kwargs):
    """Absent is allowed; explicit null is not part of the wire contract."""
    return Field(default=None, json_schema_extra=lambda schema: schema.pop("default", None), **kwargs)


def validate_local_path(value: str) -> None:
    path = PurePosixPath(value)
    if not path.is_absolute() or value.startswith("//") or str(path) != value or ".." in path.parts or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
        raise ValueError("path must be an absolute normalized path without control characters")


class StrictModel(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", allow_inf_nan=False)

    @model_validator(mode="after")
    def validate_host_string_lengths(self):
        # Java String.length counts UTF-16 units, unlike JSON Schema maxLength.
        for key, field in type(self).model_fields.items():
            value = getattr(self, key)
            if isinstance(value, str):
                length = len(value.encode("utf-16-le", errors="surrogatepass")) // 2
                for constraint in field.metadata:
                    limit = getattr(constraint, "max_length", None)
                    if limit is not None and length > limit:
                        raise ValueError(f"{key} exceeds {limit} UTF-16 code units")
        return self


class StatusArgs(StrictModel):
    timeoutMs: int = Field(default=5000, ge=100, le=10000)


class DeviceArgs(StatusArgs):
    trackId: TrackId


class ActionArgs(StatusArgs):
    actionId: Nonempty
    discardUnsavedChanges: bool = omitted(description="Only with explicit user consent: allow Quit/Close/New/Open when the project is not observed saved.")


class ActionListArgs(StatusArgs):
    query: str = omitted(min_length=1, max_length=4096, description="Case-insensitive substring of actionId or name.")
    category: str = omitted(min_length=1, max_length=4096, description="Exact category id or name, case-insensitive.")
    limit: int = Field(default=100, ge=1, le=1000)


class ParameterArgs(DeviceArgs):
    deviceIndex: int = Field(ge=0, le=15)
    expectedDeviceName: Nonempty


class DeviceParent(StrictModel):
    trackId: TrackId
    deviceIndex: int = Field(ge=0, le=15)
    expectedDeviceName: Nonempty


class LayerListArgs(StatusArgs):
    parent: DeviceParent


class LayerParameterArgs(LayerListArgs):
    layerId: TrackId
    childDeviceIndex: int = Field(ge=0, le=1)
    expectedChildDeviceName: Nonempty
    discoveryToken: Nonempty


class LayerSetArgs(LayerParameterArgs):
    contextToken: Nonempty
    parameterId: Nonempty
    value: UnitValue


class ModulatorListArgs(StatusArgs):
    parent: DeviceParent
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=64, ge=1, le=128)


class ModulatorParameterGetArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: TrackId = Field(description="Current modulator UUID from modulators.list, not its typeId, name or index; reacquire after reopening or identity changes.")
    parameterId: str = Field(max_length=1024, description="Exact addressable parameterId from modulators.list, not a display name; empty is valid when unique, at most 1024 UTF-16 code units.")


class GridCapabilitiesArgs(StatusArgs):
    detail: Literal["summary", "full"] = omitted(
        description="summary (default): availability booleans only. full: every binding's coverage, operation and limits.")


class GridCatalogArgs(StatusArgs):
    query: str = Field(default="", max_length=128, description="Case-, space-, hyphen- and punctuation-insensitive; every word must match inside the module name, so 'lowpass' and 'low pass' both find 'Low-pass'. A miss returns up to five nearest names as suggestions.")
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=64, ge=1, le=256)


class GridGetArgs(ParameterArgs):
    moduleOffset: int = Field(default=0, ge=0, le=100000)
    moduleLimit: int = Field(default=64, ge=1, le=256)
    includeParameters: bool = True
    diagnostics: bool = Field(default=False, description="Add obfuscated native modelClass/path and constant coverage notes; no write needs them.")
    moduleIds: Annotated[list[TrackId], Field(min_length=1, max_length=256)] = omitted(
        description="Return only these module instanceIds (e.g. one module's parameters without paging); absent ids are listed in missingModuleIds and the read is never complete.")


class GridWriteArgs(ParameterArgs):
    graph: Literal["diff", "full"] = omitted(
        description="diff (default): what changed, computed from the before/after graphs. full: the whole bounded graph after the edit.")


GRID_INSERT_FIELDS = {"x", "y"}
TYPE_ID_DESCRIPTION = "Canonical MODULE type UUID discovered through grid.catalog, not a module instanceId or file path; the host verifies catalog membership."
TYPE_NAME_DESCRIPTION = "Alternative to typeId: a catalog module name. Matching order: exact string, then exact ignoring case, then loose (ignoring case, spaces, hyphens and punctuation: 'low pass' = 'Low-pass'); the first step with a match decides. Symbol-only names ('<', '=', '≠', '≤') match exactly, and 'Slope ↗' vs 'Slope ↘' are told apart by exact match. Rejected not_applied with candidates unless exactly one entry matches at that step; if ambiguous, use typeId."
INT32_DESCRIPTION = "Requested absolute native anchor, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host."


class GridModuleSpec(StrictModel):
    typeId: TrackId = omitted(description=TYPE_ID_DESCRIPTION)
    typeName: str = omitted(min_length=1, max_length=128, description=TYPE_NAME_DESCRIPTION)
    x: int = Field(ge=-(2**31), le=2**31 - 1, description=INT32_DESCRIPTION)
    y: int = Field(ge=-(2**31), le=2**31 - 1, description=INT32_DESCRIPTION)

    @model_validator(mode="after")
    def validate_one_type(self):
        if len({"typeId", "typeName"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of typeId or typeName")
        return self


class GridModuleInsertArgs(GridWriteArgs):
    """One insertion (typeId|typeName, x, y) or a same-device batch (modules), never both."""
    model_config = ConfigDict(json_schema_extra={
        "oneOf": [{"required": ["x", "y"]}, {"required": ["modules"]}],
    })

    typeId: TrackId = omitted(description="Single form: " + TYPE_ID_DESCRIPTION)
    typeName: str = omitted(min_length=1, max_length=128, description="Single form: " + TYPE_NAME_DESCRIPTION)
    x: int = omitted(ge=-(2**31), le=2**31 - 1, description="Single form: " + INT32_DESCRIPTION)
    y: int = omitted(ge=-(2**31), le=2**31 - 1, description="Single form: " + INT32_DESCRIPTION)
    modules: Annotated[list[GridModuleSpec], Field(min_length=1, max_length=16)] = omitted(
        description="Batch form: 1..16 insertions on this device, each {typeId|typeName, x, y}. All types and limits are checked first (any failure inserts nothing); then entries insert in order, each settling before the next, and stop at the first failure.")

    @model_validator(mode="after")
    def validate_one_form(self):
        single = {"typeId", "typeName", "x", "y"} & self.model_fields_set
        if "modules" in self.model_fields_set:
            if single:
                raise ValueError("supply either modules or the single-insertion fields, not both")
        elif not (GRID_INSERT_FIELDS <= single and len(single & {"typeId", "typeName"}) == 1):
            raise ValueError("supply modules, or x, y and exactly one of typeId or typeName")
        return self


class GridModuleArgs(ParameterArgs):
    moduleId: TrackId = Field(description="Current module instanceId from grid.get; reacquire after reopening the project.")


class GridModuleEditArgs(GridWriteArgs):
    moduleId: TrackId = Field(description="Current module instanceId from grid.get; reacquire after reopening the project.")


class GridConnectionSource(StrictModel):
    moduleId: TrackId = Field(description="Current source module instanceId from grid.get, not a type UUID.")
    portKey: str = Field(max_length=1024, description="Exact output port key from grid.get; empty is valid, at most 1024 UTF-16 code units.")


GRID_CONNECTION_FIELDS = {"targetModuleId", "targetPortKey", "expectedSource", "source"}


class GridConnectionEdit(StrictModel):
    targetModuleId: TrackId = Field(description="Current destination module instanceId from grid.get; reacquire after reopening the project.")
    targetPortKey: str = Field(max_length=1024, description="Exact input port key from grid.get; empty is valid, at most 1024 UTF-16 code units.")
    expectedSource: GridConnectionSource | None = Field(description="Required exact prior source from fresh grid.get, or explicit null for an unconnected input. Checked before even an already-matching no-op; never defaults to null.")
    source: GridConnectionSource | None = Field(description="Required requested output endpoint, or explicit null to disconnect. Must belong to the same current graph; native repair can change the observed result.")


class GridConnectionSetArgs(GridWriteArgs):
    """One connection edit (the four fields) or a same-device batch (connections), never both."""
    model_config = ConfigDict(json_schema_extra={
        "oneOf": [{"required": sorted(GRID_CONNECTION_FIELDS)}, {"required": ["connections"]}],
    })

    targetModuleId: TrackId = omitted(description="Single form: current destination module instanceId from grid.get; reacquire after reopening the project.")
    targetPortKey: str = omitted(max_length=1024, description="Single form: exact input port key from grid.get; empty is valid, at most 1024 UTF-16 code units.")
    expectedSource: GridConnectionSource | None = omitted(description="Single form: required exact prior source from fresh grid.get, or explicit null for an unconnected input. Checked before even an already-matching no-op; never defaults to null.")
    source: GridConnectionSource | None = omitted(description="Single form: required requested output endpoint, or explicit null to disconnect. Must belong to the same current graph; native repair can change the observed result.")
    connections: Annotated[list[GridConnectionEdit], Field(min_length=1, max_length=32)] = omitted(
        description="Batch form: 1..32 connection edits on this device, each a full single edit on a distinct input. All expectedSource checks run first; any failure writes nothing. Then edits apply in order and stop at the first failure.")

    @model_validator(mode="after")
    def validate_one_form(self):
        supplied = GRID_CONNECTION_FIELDS & self.model_fields_set
        if "connections" in self.model_fields_set:
            if supplied:
                raise ValueError("supply either connections or the single-connection fields, not both")
            keys = [(item.targetModuleId.lower(), item.targetPortKey) for item in self.connections]
            if len(set(keys)) != len(keys):
                raise ValueError("connections must not repeat a targetModuleId/targetPortKey pair")
        elif supplied != GRID_CONNECTION_FIELDS:
            raise ValueError("supply connections, or all of targetModuleId, targetPortKey, expectedSource and source")
        return self


class GridModuleRenameArgs(GridModuleEditArgs):
    expectedCustomName: str = Field(max_length=1024, description="Exact raw customName from a fresh grid.get, not effective name or typeName. Required even for no-op; empty is valid. At most 1024 UTF-16 code units; no trimming or coercion.")
    customName: str = Field(max_length=1024, description="Requested raw custom name; empty resets the override through native semantics. Unicode and whitespace are preserved, at most 1024 UTF-16 code units.")


class GridModuleMoveArgs(GridModuleEditArgs):
    expectedX: int = Field(ge=-(2**31), le=2**31 - 1, description="Fresh native anchor X; exact stale-state check precedes even a no-op. Signed int32 JSON integer token.")
    expectedY: int = Field(ge=-(2**31), le=2**31 - 1, description="Fresh native anchor Y; exact stale-state check precedes even a no-op. Signed int32 JSON integer token.")
    x: int = Field(ge=-(2**31), le=2**31 - 1, description="Requested absolute native anchor X, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.")
    y: int = Field(ge=-(2**31), le=2**31 - 1, description="Requested absolute native anchor Y, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.")


class GridParameterGetArgs(GridModuleArgs):
    parameterId: str = Field(max_length=1024, description="Exact native parameter ID from grid.get, not a name; empty is valid, at most 1024 UTF-16 code units.")


DISPLAY_DESCRIPTION = ("Instead of value, for float parameters whose numericUnit has a verified conversion (hertz: raw is a MIDI note, e.g. '1.2 kHz', '440 Hz'; percent: '50 %'; decibel: '-12 dB'). "
                       "The text needs an explicit unit; the host confirms the unit scale against the current raw/display pair, else rejects not_applied unsupported_unit. "
                       "The result reports displayMismatch from the post-write display; it never writes again to correct it.")


StringValue = Annotated[str, Field(max_length=1024)]


class GridRawWrite(StrictModel):
    moduleId: TrackId = Field(description="Current module instanceId from grid.get; reacquire after reopening the project.")
    parameterId: str = Field(max_length=1024, description="Exact native parameter ID from grid.get, not a name; empty is valid, at most 1024 UTF-16 code units.")
    model_config = ConfigDict(json_schema_extra={
        "oneOf": [
            {"properties": {
                "expectedKind": {"enum": kinds},
                "expectedValue": value_schema,
                "value": value_schema,
            }}
            for kinds, value_schema in (
                (["boolean"], {"type": "boolean"}),
                (["integer", "enum"], {"type": "integer", "minimum": -(2**31), "maximum": 2**31 - 1}),
                (["float"], {"type": "number"}),
                (["string"], {"type": "string", "maxLength": 1024}),
            )
        ],
    })

    expectedKind: Literal["float", "integer", "enum", "boolean", "string"] = Field(
        description="Typed kind from a recent grid.get row (or grid.parameters.get); checked before any already-matching no-op.")
    expectedValue: bool | int | float | StringValue = Field(
        description="Exact current native raw value from a recent grid.get row (or grid.parameters.get), not normalized or display units; stale is rejected not_applied. Must match expectedKind; no tolerance or coercion.")
    value: bool | int | float | StringValue = omitted(
        description="Requested native raw value: boolean JSON bool; integer/enum signed int32 JSON integer token; float finite JSON number; string (string panels, e.g. Comment text) any text of at most 1024 UTF-16 units, preserved verbatim, empty allowed. Enum uses an actual listed option value, never an ordinal. Native range/representability are checked before dispatch. Exactly one of value or display.")
    display: str = omitted(min_length=1, max_length=64, description=DISPLAY_DESCRIPTION)

    @field_validator("expectedValue", "value", mode="before")
    @classmethod
    def validate_raw_number(cls, value):
        if type(value) not in (bool, int, float, str):
            raise ValueError("raw value must be a JSON boolean, number or string, never null")
        if type(value) is str:
            if len(value.encode("utf-16-le", errors="surrogatepass")) > 2048:
                raise ValueError("string value must be at most 1024 UTF-16 code units")
        elif type(value) is not bool:
            try:
                finite = math.isfinite(value)
            except OverflowError:
                finite = False
            if not finite:
                raise ValueError("raw numeric value must be finite and representable as a native double")
        return value

    @model_validator(mode="after")
    def validate_raw_kind(self):
        if len({"value", "display"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of value (raw) or display (text such as '1.2 kHz')")
        if "display" in self.model_fields_set and self.expectedKind != "float":
            raise ValueError("display needs expectedKind float")
        for field in ("expectedValue", "value"):
            if field not in self.model_fields_set:
                continue
            value = getattr(self, field)
            if self.expectedKind == "string":
                if type(value) is not str:
                    raise ValueError(f"{field} must be a JSON string for string kind")
            elif type(value) is str:
                raise ValueError(f"{field} must not be a string for {self.expectedKind} kind")
            elif self.expectedKind == "boolean":
                if type(value) is not bool:
                    raise ValueError(f"{field} must be a JSON boolean for boolean kind")
            elif self.expectedKind in ("integer", "enum"):
                if type(value) is not int or not -(2**31) <= value < 2**31:
                    raise ValueError(f"{field} must be a signed int32 JSON integer for {self.expectedKind} kind")
            elif type(value) not in (int, float):
                raise ValueError(f"{field} must be a finite JSON number for float kind")
        return self


GRID_WRITE_FIELDS = {"moduleId", "parameterId", "expectedKind", "expectedValue"}


class GridParameterSetArgs(ParameterArgs):
    """One write (the five fields) or a same-device batch (writes), never both."""
    model_config = ConfigDict(json_schema_extra={
        "oneOf": [{"required": sorted(GRID_WRITE_FIELDS)}, {"required": ["writes"]}],
    })

    moduleId: TrackId = omitted(description="Single form: current module instanceId from grid.get.")
    parameterId: str = omitted(max_length=1024, description="Single form: exact parameterId from grid.get; empty is valid.")
    expectedKind: Literal["float", "integer", "enum", "boolean", "string"] = omitted(description="Single form: kind from a recent grid.get row.")
    expectedValue: bool | int | float | StringValue = omitted(description="Single form: exact current raw value from a recent grid.get row.")
    value: bool | int | float | StringValue = omitted(description="Single form: requested native raw value (or give display instead).")
    display: str = omitted(min_length=1, max_length=64, description="Single form: " + DISPLAY_DESCRIPTION)
    writes: Annotated[list[GridRawWrite], Field(min_length=1, max_length=64)] = omitted(
        description="Batch form: 1..64 writes on this device, each a full single write. All CAS checks run first; any failure writes nothing. Then writes dispatch in order and stop at the first failure.")

    @model_validator(mode="after")
    def validate_one_form(self):
        supplied = GRID_WRITE_FIELDS & self.model_fields_set
        if "writes" in self.model_fields_set:
            if supplied or {"value", "display"} & self.model_fields_set:
                raise ValueError("supply either writes or the single-write fields, not both")
            keys = [(item.moduleId.lower(), item.parameterId) for item in self.writes]
            if len(set(keys)) != len(keys):
                raise ValueError("writes must not repeat a moduleId/parameterId pair")
        elif supplied != GRID_WRITE_FIELDS:
            raise ValueError("supply writes, or moduleId, parameterId, expectedKind, expectedValue and one of value or display")
        else:
            GridRawWrite.model_validate({key: getattr(self, key) for key in GRID_WRITE_FIELDS | {"value", "display"} & self.model_fields_set})
        return self


class SetArgs(ParameterArgs):
    contextToken: Nonempty
    parameterId: Nonempty
    value: UnitValue


class DeviceSetArgs(ParameterArgs):
    enabled: bool


class DeviceInsertArgs(DeviceArgs):
    expectedDeviceCount: int = Field(ge=0, le=15)
    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()

    @model_validator(mode="after")
    def validate_identifier(self):
        if self.kind == "file":
            if "id" in self.model_fields_set or "path" not in self.model_fields_set:
                raise ValueError("file insertion requires path and forbids id")
            validate_local_path(self.path)
        else:
            if "path" in self.model_fields_set or "id" not in self.model_fields_set:
                raise ValueError("plug-in insertion requires id and forbids path")
            if self.kind == "bitwig" and not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", self.id):
                raise ValueError("bitwig id must be a canonical UUID")
            if self.kind == "vst2" and (not re.fullmatch(r"-?(0|[1-9][0-9]{0,9})", self.id) or not -(2**31) <= int(self.id) < 2**31):
                raise ValueError("vst2 id must be a signed int32 decimal string")
            if self.kind == "vst3" and not re.fullmatch(r"[0-9a-fA-F]{32}", self.id):
                raise ValueError("vst3 id must be 32 hexadecimal characters")
            if self.kind == "clap" and not re.fullmatch(r"[!-~]{1,256}", self.id):
                raise ValueError("clap id must be 1..256 printable non-whitespace ASCII characters")
        return self


class ClipArgs(DeviceArgs):
    slotIndex: int = Field(ge=0, le=15)


class ScenesAppendArgs(StatusArgs):
    count: Annotated[int, Field(ge=1, le=16)] = omitted()


class ClipNote(StrictModel):
    op: Literal["set"] = omitted()
    beat: float = Field(ge=0, le=4194304)
    pitch: int = Field(ge=0, le=127)
    velocity: int = Field(ge=1, le=127)
    durationBeats: float = Field(ge=1 / 128, le=4096)


def check_note_spans(notes) -> None:
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
        if note.op == "clear":
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
    def validate_notes(self):
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


class ClipSettingsSetArgs(ClipSettingsArgs):
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: Annotated[int, Field(ge=1, le=64)] = omitted()
    nextActionDelayBeats: Annotated[float, Field(ge=0, le=4194304)] = omitted()

    @model_validator(mode="after")
    def validate_settings(self):
        settings = self.model_fields_set - {"trackId", "slotIndex", "expectedClipName", "timeoutMs"}
        if not settings:
            raise ValueError("supply at least one clip setting")
        return self


CLIP_EDIT_OPS = {"rename": {"name"}, "resize": {"lengthBeats"}, "color": {"rgb"}, "transpose": {"semitones"},
                 "quantize": {"amount"}, "duplicateContent": set(), "delete": set(), "copyTo": {"targetSlotIndex"}}


class ClipEditArgs(ClipSettingsArgs):
    op: Literal["rename", "resize", "color", "transpose", "quantize", "duplicateContent", "delete", "copyTo"]
    name: Annotated[str, Field(min_length=1, max_length=128)] = omitted()
    lengthBeats: Annotated[int, Field(ge=1, le=4096)] = omitted()
    rgb: Annotated[str, Field(pattern=r"^#[0-9a-fA-F]{6}$")] = omitted()
    semitones: Annotated[int, Field(ge=-48, le=48)] = omitted()
    amount: Annotated[float, Field(gt=0, le=1)] = omitted()
    targetSlotIndex: Annotated[int, Field(ge=0, le=15)] = omitted()

    @model_validator(mode="after")
    def validate_op_fields(self):
        payload = self.model_fields_set & set().union(*CLIP_EDIT_OPS.values())
        if self.semitones == 0:
            raise ValueError("semitones must be nonzero")
        if payload != CLIP_EDIT_OPS[self.op]:
            raise ValueError(f"op {self.op} takes exactly {sorted(CLIP_EDIT_OPS[self.op]) or 'no extra fields'}")
        return self


class ClipImportArgs(ClipArgs):
    path: Nonempty

    @model_validator(mode="after")
    def validate_path(self):
        validate_local_path(self.path)
        return self


GRID_FIELDS = {"stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount"}


class NoteWindowArgs(ClipArgs):
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()
    stepSizeBeats: Annotated[float, Field(ge=1 / 128, le=4)] = omitted()
    startStep: Annotated[int, Field(ge=0, le=1048576)] = omitted()
    steps: Annotated[int, Field(ge=1, le=128)] = omitted()
    pitchStart: Annotated[int, Field(ge=0, le=127)] = omitted()
    pitchCount: Annotated[int, Field(ge=1, le=128)] = omitted()

    @property
    def explicit_window(self) -> bool:
        return bool(GRID_FIELDS & self.model_fields_set)

    @model_validator(mode="after")
    def validate_pitch_window(self):
        supplied = GRID_FIELDS & self.model_fields_set
        if supplied and supplied != GRID_FIELDS:
            raise ValueError("stepSizeBeats, startStep, steps, pitchStart and pitchCount must be supplied together or all omitted")
        if supplied and self.pitchStart + self.pitchCount > 128:
            raise ValueError("pitchStart + pitchCount must not exceed 128")
        return self


class NoteEdit(StrictModel):
    op: Literal["set", "clear"]
    beat: float = Field(ge=0, le=4194304)
    pitch: int = Field(ge=0, le=127)
    velocity: Annotated[int, Field(ge=1, le=127)] = omitted()
    durationBeats: Annotated[float, Field(ge=1 / 128, le=4096)] = omitted()

    @model_validator(mode="after")
    def validate_operation(self):
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
    def validate_edits(self):
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


class TransportSetArgs(StatusArgs):
    playing: bool = omitted()
    tempoBpm: Annotated[float, Field(ge=20, le=666)] = omitted()
    positionBeats: Annotated[float, Field(ge=0)] = omitted()
    metronome: bool = omitted()
    loopEnabled: bool = omitted()
    loopStartBeats: Annotated[float, Field(ge=0)] = omitted()
    loopDurationBeats: Annotated[float, Field(gt=0)] = omitted()

    @model_validator(mode="after")
    def require_change(self):
        if not self.model_fields_set - {"timeoutMs"}:
            raise ValueError("transport.set requires at least one changed field")
        if {"loopStartBeats", "loopDurationBeats"} <= self.model_fields_set:
            if not math.isfinite(self.loopStartBeats + self.loopDurationBeats):
                raise ValueError("loopStartBeats + loopDurationBeats must be finite")
        return self


class TrackSetArgs(DeviceArgs):
    name: Nonempty = omitted()
    volumeNormalized: UnitValue = omitted()
    panNormalized: UnitValue = omitted()
    mute: bool = omitted()
    solo: bool = omitted()
    arm: bool = omitted()

    @model_validator(mode="after")
    def require_change(self):
        if not self.model_fields_set - {"timeoutMs", "trackId"}:
            raise ValueError("tracks.set requires at least one changed field")
        return self


class ExtensionRestartArgs(StatusArgs):
    discardUnresolved: bool = omitted()


class TrackCreateArgs(StatusArgs):
    type: Literal["audio", "instrument", "effect"]
    position: int = Field(ge=-1, le=2147483647)
    name: Nonempty


class RemotePageArgs(ParameterArgs):
    contextToken: Nonempty
    pageIndex: int = Field(ge=0, le=4095)
    expectedPageName: Nonempty


class RemoteSetArgs(ParameterArgs):
    contextToken: Nonempty
    expectedPageName: Nonempty
    parameterIndex: int = Field(ge=0, le=7)
    expectedParameterName: Nonempty
    normalizedValue: UnitValue


class GridApplyTarget(StrictModel):
    trackId: TrackId
    deviceIndex: int = Field(ge=0, le=15)
    expectedDeviceName: Nonempty


class GridApplyDisplay(StrictModel):
    display: str = Field(min_length=1, max_length=64, description="Text with explicit unit, e.g. '1.2 kHz'; float parameters only.")


class GridApplyLabel(StrictModel):
    label: str = Field(min_length=1, max_length=1024, description="Enum option label, e.g. '16th'.")


class GridApplyModule(StrictModel):
    name: str = Field(min_length=1, max_length=1024, description="Identity: the module's customName, unique and non-empty within the device.")
    type: str = omitted(min_length=1, max_length=128, description="Catalog module name (e.g. 'Multiply'); exactly one of type or typeId.")
    typeId: TrackId = omitted(description=TYPE_ID_DESCRIPTION)
    x: int = Field(ge=-(2**31), le=2**31 - 1)
    y: int = Field(ge=-(2**31), le=2**31 - 1)
    params: dict[str, bool | int | float | str | GridApplyDisplay | GridApplyLabel] = omitted(
        description="parameterId -> raw value, {display:'1.2 kHz'}, {label:'16th'} or a string (string panels, e.g. Comment TEXT; at most 1024 UTF-16 units, multi-line ok).")
    replace: bool = omitted(description="If a module with this name exists with another type, delete it and insert this type under the same name (default: refuse).")

    @model_validator(mode="after")
    def validate_one_type(self):
        if len({"type", "typeId"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or typeId")
        return self


class GridApplyConnection(StrictModel):
    from_: str = Field(alias="from", min_length=3, max_length=2048, description="Source output 'module name.PORT_KEY' (split on the last '.').")
    to: str = Field(min_length=3, max_length=2048, description="Target input 'module name.PORT_KEY'.")


class GridRenderArgs(ParameterArgs):
    outPath: Nonempty = Field(description="Absolute normalized path of the image to write; refused if it exists unless overwrite=true.")
    format: Literal["svg", "png"] = omitted(description="svg (default) or png (needs rsvg-convert or magick on PATH).")
    overwrite: bool = omitted(description="Replace an existing file at outPath.")
    namespace: str = omitted(min_length=1, max_length=1024, description="customName prefix to highlight; other modules are dimmed.")
    showConnections: bool = omitted(description="Draw cables (default true).")
    scale: float = omitted(ge=0.25, le=4, description="Image scale factor (default 1).")

    @model_validator(mode="after")
    def validate_path(self):
        validate_local_path(self.outPath)
        return self


class GridApplyArgs(StatusArgs):
    target: GridApplyTarget
    modules: Annotated[list[GridApplyModule], Field(max_length=1024, description="Declared modules; [] only with prune=true and a namespace (empties the namespace).")]
    connections: Annotated[list[GridApplyConnection], Field(max_length=1024)] = omitted(
        description="Cables between declared modules; one source per target input.")
    namespace: str = omitted(min_length=1, max_length=1024, description="customName prefix (e.g. 'H: ') that prune may delete from.")
    prune: bool = omitted(description="Also disconnect unlisted inputs of declared modules and delete namespace modules absent from the spec.")
    dryRun: bool = omitted(description="Return the plan without writing anything.")
    replace: bool = omitted(description="Default for every module: replace an existing same-named module of another type instead of refusing.")

    @model_validator(mode="after")
    def validate_spec(self):
        if not self.modules and not (self.prune and self.namespace):
            raise ValueError("modules may be empty only with prune=true and a non-empty namespace")
        names = [m.name for m in self.modules]
        dupes = sorted({n for n in names if names.count(n) > 1})
        if dupes:
            raise ValueError(f"module names must be unique: {dupes}")
        targets = [c.to for c in self.connections or []]
        dupes = sorted({t for t in targets if targets.count(t) > 1})
        if dupes:
            raise ValueError(f"an input takes one source; repeated targets: {dupes}")
        return self


class AudioRouteArgs(StatusArgs):
    mode: Literal["silent", "restore", "status"]


def _wav_path(value: str) -> str:
    validate_local_path(value)
    if not value.lower().endswith(".wav"):
        raise ValueError("path must end in .wav")
    return value


class AudioCaptureArgs(StatusArgs):
    seconds: float = Field(ge=0.5, le=120)
    outPath: Nonempty
    overwrite: bool = omitted(description="Replace an existing file; refused by default.")
    fromStart: bool = omitted(description="Default true: stop transport, seek to 0, record, start transport, stop after seconds. false records without touching the transport.")
    allowAudible: bool = omitted(description="Record the default output monitor without silent routing (audible).")

    @field_validator("outPath")
    @classmethod
    def validate_out_path(cls, value: str) -> str:
        return _wav_path(value)


class AudioAnalyzeArgs(StatusArgs):
    path: Nonempty
    mode: Literal["level", "onsets", "mono_pitch", "probe"]
    bpm: Annotated[float, Field(ge=20, le=666)] = omitted(description="mono_pitch: adds 16th-grid positions.")
    skipSeconds: Annotated[float, Field(ge=0, le=3600)] = omitted(description="Ignore audio before this time; reported times stay relative to the file start.")
    threshold: Annotated[float, Field(ge=0.01, le=0.9)] = omitted(description="onsets/mono_pitch: fraction of the peak envelope that counts as an onset (default 0.15).")
    windowSeconds: Annotated[float, Field(ge=0.01, le=60)] = omitted(description="level: window length (default 0.5).")

    @field_validator("path")
    @classmethod
    def validate_path(cls, value: str) -> str:
        return _wav_path(value)

class GridDescribeArgs(StatusArgs):
    type: str = omitted(min_length=1, max_length=128, description="Module display name (e.g. 'Select In') or typeId; exactly one of type or query.")
    query: str = omitted(min_length=1, max_length=256, description="Keywords ranked against module names, descriptions and keywords.")
    lang: Literal["en", "de", "es", "fr", "ja", "ko", "zh"] = omitted(description="Documentation language (default en).")
    limit: int = omitted(ge=1, le=50, description="Maximum query matches (default 10).")
    installationRoot: str = omitted(min_length=1, max_length=4096, description="Bitwig install directory containing resources/localization; default $BITWIG_INSTALLATION_ROOT, the install of $BITWIG_API_JAR, then /opt/bitwig-studio.")

    @model_validator(mode="after")
    def validate_one_selector(self):
        if len({"type", "query"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or query")
        if "limit" in self.model_fields_set and "query" not in self.model_fields_set:
            raise ValueError("limit applies only to query")
        return self


class HostDiagnosticsArgs(StatusArgs):
    sinceSeconds: int = omitted(ge=1, le=86400, description="Look back this many seconds (default 300); exclusive with sinceMarker.")
    sinceMarker: str = omitted(min_length=1, max_length=512, description="Opaque marker from a previous host.diagnostics call: only events written after it.")
    maxEvents: int = omitted(ge=1, le=500, description="Keep the newest N deduplicated events (default 50).")

    @model_validator(mode="after")
    def validate_window(self):
        if {"sinceSeconds", "sinceMarker"} <= self.model_fields_set:
            raise ValueError("supply at most one of sinceSeconds or sinceMarker")
        return self
