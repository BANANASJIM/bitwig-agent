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


def omitted():
    """Absent is allowed; explicit null is not part of the wire contract."""
    return Field(default=None, json_schema_extra=lambda schema: schema.pop("default", None))


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
                length = len(value.encode("utf-16-le")) // 2
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


class ParameterArgs(DeviceArgs):
    deviceIndex: int = Field(ge=0, le=15)
    expectedDeviceName: Nonempty


class LayerParent(StrictModel):
    trackId: TrackId
    deviceIndex: int = Field(ge=0, le=15)
    expectedDeviceName: Nonempty


class LayerListArgs(StatusArgs):
    parent: LayerParent


class LayerParameterArgs(LayerListArgs):
    layerId: TrackId
    childDeviceIndex: int = Field(ge=0, le=1)
    expectedChildDeviceName: Nonempty
    discoveryToken: Nonempty


class LayerSetArgs(LayerParameterArgs):
    contextToken: Nonempty
    parameterId: Nonempty
    value: UnitValue


class GridCatalogArgs(StatusArgs):
    query: str = Field(default="", max_length=128)
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=64, ge=1, le=256)


class GridGetArgs(ParameterArgs):
    moduleOffset: int = Field(default=0, ge=0, le=100000)
    moduleLimit: int = Field(default=64, ge=1, le=128)
    includeParameters: bool = True


class GridModuleInsertArgs(ParameterArgs):
    typeId: TrackId = Field(description="Canonical MODULE type UUID discovered through grid.catalog, not a module instanceId or file path; the host verifies catalog membership.")
    x: int = Field(ge=-(2**31), le=2**31 - 1, description="Requested absolute native anchor X, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.")
    y: int = Field(ge=-(2**31), le=2**31 - 1, description="Requested absolute native anchor Y, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.")


class GridModuleArgs(ParameterArgs):
    moduleId: TrackId = Field(description="Current module instanceId from grid.get; reacquire after reopening the project.")


class GridConnectionSource(StrictModel):
    moduleId: TrackId = Field(description="Current source module instanceId from grid.get, not a type UUID.")
    portKey: str = Field(max_length=1024, description="Exact output port key from grid.get; empty is valid, at most 1024 UTF-16 code units.")


class GridConnectionSetArgs(ParameterArgs):
    targetModuleId: TrackId = Field(description="Current destination module instanceId from grid.get; reacquire after reopening the project.")
    targetPortKey: str = Field(max_length=1024, description="Exact input port key from grid.get; empty is valid, at most 1024 UTF-16 code units.")
    expectedSource: GridConnectionSource | None = Field(description="Required exact prior source from fresh grid.get, or explicit null for an unconnected input. Checked before even an already-matching no-op; never defaults to null.")
    source: GridConnectionSource | None = Field(description="Required requested output endpoint, or explicit null to disconnect. Must belong to the same current graph; native repair can change the observed result.")


class GridModuleMoveArgs(GridModuleArgs):
    expectedX: int = Field(ge=-(2**31), le=2**31 - 1, description="Fresh native anchor X; exact stale-state check precedes even a no-op. Signed int32 JSON integer token.")
    expectedY: int = Field(ge=-(2**31), le=2**31 - 1, description="Fresh native anchor Y; exact stale-state check precedes even a no-op. Signed int32 JSON integer token.")
    x: int = Field(ge=-(2**31), le=2**31 - 1, description="Requested absolute native anchor X, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.")
    y: int = Field(ge=-(2**31), le=2**31 - 1, description="Requested absolute native anchor Y, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.")


class GridParameterGetArgs(GridModuleArgs):
    parameterId: str = Field(max_length=1024, description="Exact native parameter ID from grid.get, not a name; empty is valid, at most 1024 UTF-16 code units.")


class GridParameterSetArgs(GridParameterGetArgs):
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
            )
        ],
    })

    expectedKind: Literal["float", "integer", "enum", "boolean"] = Field(
        description="Fresh typed kind from grid.parameters.get; checked before any already-matching no-op.")
    expectedValue: bool | int | float = Field(
        description="Exact current native raw value from a fresh grid.parameters.get, not normalized or display units. Must match expectedKind; no tolerance or coercion.")
    value: bool | int | float = Field(
        description="Requested native raw value: boolean JSON bool; integer/enum signed int32 JSON integer token; float finite JSON number. Enum uses an actual listed option value, never an ordinal. Native range/representability are checked before dispatch.")

    @field_validator("expectedValue", "value", mode="before")
    @classmethod
    def validate_raw_number(cls, value):
        if type(value) not in (bool, int, float):
            raise ValueError("raw value must be a JSON boolean or number, never null or a string")
        if type(value) is not bool:
            try:
                finite = math.isfinite(value)
            except OverflowError:
                finite = False
            if not finite:
                raise ValueError("raw numeric value must be finite and representable as a native double")
        return value

    @model_validator(mode="after")
    def validate_raw_kind(self):
        for field in ("expectedValue", "value"):
            value = getattr(self, field)
            if self.expectedKind == "boolean":
                if type(value) is not bool:
                    raise ValueError(f"{field} must be a JSON boolean for boolean kind")
            elif self.expectedKind in ("integer", "enum"):
                if type(value) is not int or not -(2**31) <= value < 2**31:
                    raise ValueError(f"{field} must be a signed int32 JSON integer for {self.expectedKind} kind")
            elif type(value) not in (int, float):
                raise ValueError(f"{field} must be a finite JSON number for float kind")
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


class ClipCreateArgs(ClipArgs):
    lengthBeats: int = Field(ge=1, le=4096)
    name: str = Field(min_length=1, max_length=128)


class ClipImportArgs(ClipArgs):
    path: Nonempty

    @model_validator(mode="after")
    def validate_path(self):
        validate_local_path(self.path)
        return self


class NoteWindowArgs(ClipArgs):
    channel: int = Field(ge=0, le=15)
    stepSizeBeats: float = Field(ge=1 / 128, le=4)
    startStep: int = Field(ge=0, le=1048576)
    steps: int = Field(ge=1, le=128)
    pitchStart: int = Field(ge=0, le=127)
    pitchCount: int = Field(ge=1, le=128)

    @model_validator(mode="after")
    def validate_pitch_window(self):
        if self.pitchStart + self.pitchCount > 128:
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

    @model_validator(mode="after")
    def validate_edits(self):
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
