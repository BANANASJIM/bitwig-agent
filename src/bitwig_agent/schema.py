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


MAX_TIMEOUT_MS = 10000
# Topology edits verify against a full graph read per entry; large graphs need longer (host enforces the same split).
TOPOLOGY_TIMEOUT_MS = 60000


class StatusArgs(StrictModel):
    timeoutMs: int = Field(default=5000, ge=100, le=MAX_TIMEOUT_MS)


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


MODULATOR_ID = "Current modulator UUID from modulators.get (or modulators.list), not its typeId, name or index."
TransferFunction = Literal["linear", "rectify_pos", "rectify_neg", "abs_linear", "inv_abs_linear", "cubic", "inv_cubic"]
ModulationAmount = Annotated[float, Field(ge=-1, le=1)]
MODULATOR_COUNT = 4096
MAX_APPLY_CONNECTIONS = 4096  # a full 1024-module Grid typically needs more cables than modules


class ModulatorGetArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: TrackId = omitted(description="Only this modulator; absent returns every modulator on the device (at most 64).")


class ModulatorInsertArgs(StatusArgs):
    parent: DeviceParent
    expectedCount: int = Field(ge=0, le=MODULATOR_COUNT, description="Current modulator count from modulators.get; a different count rejects not_applied.")
    typeName: str = omitted(min_length=1, max_length=128, description="Modulator type name from modulators.catalog, e.g. 'Random' or 'Beat LFO'; exactly one of typeName or typeId.")
    typeId: TrackId = omitted(description="Native modulator type UUID (the typeId of a modulators.get row); exactly one of typeName or typeId.")

    @model_validator(mode="after")
    def validate_one_type(self):
        if len({"typeName", "typeId"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of typeName or typeId")
        return self


class ModulatorDeleteArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: TrackId = Field(description=MODULATOR_ID)
    expectedCount: int = Field(ge=1, le=MODULATOR_COUNT, description="Current modulator count from modulators.get.")


class ModulatorParameterSetArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: TrackId = Field(description=MODULATOR_ID)
    parameterId: str = Field(max_length=1024, description="Exact parameterId of a modulators.get parameters row (not an outputId).")
    expectedKind: Literal["float", "integer", "enum", "boolean"] = Field(description="kind from that row.")
    expectedValue: bool | int | float = Field(description="Exact current raw value from that row; stale rejects not_applied.")
    value: bool | int | float = omitted(description="Requested raw value in the row's minimum..maximum (enum: a listed option value). Exactly one of value or display.")
    display: str = omitted(min_length=1, max_length=64, description="Float only, instead of value: text with a unit such as '2 Hz' or '50 %'.")

    @model_validator(mode="after")
    def validate_raw(self):
        details = {key: getattr(self, key) for key in ("expectedValue", "value", "display") if key in self.model_fields_set}
        GridRawWrite.model_validate({"moduleId": self.modulatorId, "parameterId": self.parameterId,
                                     "expectedKind": self.expectedKind, **details})
        return self


class GridControlTarget(StrictModel):
    moduleId: TrackId = Field(description="Grid module instanceId from grid.get on this same device.")
    parameterId: str = Field(max_length=1024, description="Exact parameterId of that module's control (e.g. DECAY), from grid.get.")


class ModulatorControlTarget(StrictModel):
    modulatorId: TrackId = Field(description="Another modulator on this same device (modulators.get).")
    parameterId: str = Field(max_length=1024, description="Exact parameterId of that modulator's control.")


MAP_TARGET = "The control to modulate: {moduleId, parameterId} for a Grid module control, or {modulatorId, parameterId} for another modulator's control, both on this device."


class ModulatorUnmapArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: TrackId = Field(description="Source modulator. " + MODULATOR_ID)
    outputId: str = omitted(max_length=1024, description="Source output id from modulators.get outputs; may be omitted when the modulator has exactly one output.")
    target: GridControlTarget | ModulatorControlTarget = Field(description=MAP_TARGET)
    expectedAmount: ModulationAmount = Field(description="Exact current amount of the mapping, from modulators.get.")


class ModulatorMapArgs(ModulatorUnmapArgs):
    expectedAmount: ModulationAmount | None = Field(description="null when this output does not map to target yet (creates it); else the exact current amount (changes it).")
    amount: ModulationAmount = Field(description="Modulation amount -1..1 (negative inverts). Not clamped: beyond the target's range rejects.")
    transfer: TransferFunction = omitted(description="New mappings only (default linear): linear, rectify_pos/rectify_neg (keep one half), abs_linear, inv_abs_linear, cubic, inv_cubic. Polarity itself is a modulator parameter (e.g. LFO bipolar).")
    enabled: bool = omitted(description="New mappings only (default true).")


class ModulatorCatalogArgs(StatusArgs):
    query: str = omitted(min_length=1, max_length=256, description="Keywords ranked against names, categories, descriptions and keywords; absent lists all.")
    lang: Literal["en", "de", "es", "fr", "ja", "ko", "zh"] = omitted(description="Documentation language (default en).")
    installationRoot: str = omitted(min_length=1, max_length=4096, description="Bitwig install directory; default $BITWIG_INSTALLATION_ROOT, the install of $BITWIG_API_JAR, then /opt/bitwig-studio.")


class ModulatorDescribeArgs(ModulatorCatalogArgs):
    type: str = omitted(min_length=1, max_length=128, description="Modulator type name, e.g. 'Beat LFO'; exactly one of type or query.")

    @model_validator(mode="after")
    def validate_one_selector(self):
        if len({"type", "query"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or query")
        return self


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
    full: bool = omitted(description="Step-array parameters (kind steps) return all 256 entries instead of the first STEPS ones.")


class GridTopologyReconcileArgs(ParameterArgs):
    timeoutMs: int = Field(default=5000, ge=100, le=TOPOLOGY_TIMEOUT_MS)


class GridWriteArgs(ParameterArgs):
    timeoutMs: int = Field(default=5000, ge=100, le=TOPOLOGY_TIMEOUT_MS)
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


class GridVoicingGetArgs(ParameterArgs):
    detail: Literal["summary", "full"] = omitted(description="summary (default) omits the editContext observation; full keeps it.")


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
    full: bool = omitted(description="A step-array parameter (kind steps) returns all 256 entries instead of the first STEPS ones.")


DISPLAY_DESCRIPTION = ("Instead of value, for float parameters whose numericUnit has a verified conversion (hertz: raw is a MIDI note, e.g. '1.2 kHz', '440 Hz'; percent: '50 %'; decibel: '-12 dB'). "
                       "The text needs an explicit unit; the host confirms the unit scale against the current raw/display pair, else rejects not_applied unsupported_unit. "
                       "The result reports displayMismatch from the post-write display; it never writes again to correct it.")


StringValue = Annotated[str, Field(max_length=1024)]
STEP_MIN, STEP_MAX, STEP_ENTRIES = -128.0, 128.0, 256


FULL_DESCRIPTION = "steps parameters only: expose (and, on a write, compare-and-set) up to 256 step-array entries instead of the first STEPS ones. Default: the STEPS prefix."
STEPS_EXPECTED_DESCRIPTION = ("kind steps: the exact current entries from step 0 as read by grid.get (normally the first STEPS, or all with full:true); its length is the CAS window, "
                              "numbers as read (32-bit floats); compared after rounding to float.")
STEPS_VALUES_DESCRIPTION = ("kind steps: the entries to write from step 0, 1..256 numbers within -128..128 (a finite sanity bound, not a per-module range: Pitches take Grid pitch units, Gates 1/0, Accents 0/0.5/1, Probabilities 0..1 as observed natively), "
                            "stored as 32-bit floats. Not longer than expectedValues; later entries and everything beyond the prefix stay unchanged. Nothing is clamped.")


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
        ] + [{"properties": {"expectedKind": {"enum": ["steps"]}}}],
    })

    expectedKind: Literal["float", "integer", "enum", "boolean", "string", "steps"] = Field(
        description="Typed kind from a recent grid.get row (or grid.parameters.get); checked before any already-matching no-op.")
    expectedValue: bool | int | float | StringValue = omitted(
        description="Exact current native raw value from a recent grid.get row (or grid.parameters.get), not normalized or display units; stale is rejected not_applied. Must match expectedKind; no tolerance or coercion. Required except for kind steps.")
    expectedValues: Annotated[list[float], Field(min_length=1, max_length=STEP_ENTRIES)] = omitted(description=STEPS_EXPECTED_DESCRIPTION)
    values: Annotated[list[Annotated[float, Field(ge=STEP_MIN, le=STEP_MAX)]], Field(min_length=1, max_length=STEP_ENTRIES)] = omitted(description=STEPS_VALUES_DESCRIPTION)
    full: bool = omitted(description=FULL_DESCRIPTION)
    value: bool | int | float | StringValue = omitted(
        description="Requested native raw value: boolean JSON bool; integer/enum signed int32 JSON integer token; float finite JSON number; string (string panels, e.g. Comment text) any text of at most 1024 UTF-16 units, preserved verbatim, empty allowed. Enum uses an actual listed option value, never an ordinal. Native range/representability are checked before dispatch. Exactly one of value or display.")
    display: str = omitted(min_length=1, max_length=64, description=DISPLAY_DESCRIPTION)

    @field_validator("expectedValue", "value", "expectedValues", "values", mode="before")
    @classmethod
    def validate_raw_number(cls, value):
        if type(value) is list:
            return value  # steps arrays: items are validated as finite floats by the field type
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
        given = self.model_fields_set
        if self.expectedKind == "steps":
            if {"expectedValue", "value", "display"} & given or not {"expectedValues", "values"} <= given:
                raise ValueError("kind steps takes expectedValues and values (and optional full), not expectedValue, value or display")
            if len(self.values) > len(self.expectedValues):
                raise ValueError("values must not be longer than expectedValues")
            return self
        if {"expectedValues", "values", "full"} & given:
            raise ValueError("expectedValues, values and full need expectedKind steps")
        if "expectedValue" not in given:
            raise ValueError("expectedValue is required")
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


GRID_WRITE_FIELDS = {"moduleId", "parameterId", "expectedKind"}


WRITE_DETAILS = {"expectedValue", "value", "display", "expectedValues", "values", "full"}


class GridParameterSetArgs(ParameterArgs):
    """One write (the five fields) or a same-device batch (writes), never both."""
    model_config = ConfigDict(json_schema_extra={
        "oneOf": [{"required": sorted(GRID_WRITE_FIELDS)}, {"required": ["writes"]}],
    })

    moduleId: TrackId = omitted(description="Single form: current module instanceId from grid.get.")
    parameterId: str = omitted(max_length=1024, description="Single form: exact parameterId from grid.get; empty is valid.")
    expectedKind: Literal["float", "integer", "enum", "boolean", "string", "steps"] = omitted(description="Single form: kind from a recent grid.get row.")
    expectedValue: bool | int | float | StringValue = omitted(description="Single form: exact current raw value from a recent grid.get row (not for kind steps).")
    expectedValues: Annotated[list[float], Field(min_length=1, max_length=STEP_ENTRIES)] = omitted(description="Single form, kind steps: " + STEPS_EXPECTED_DESCRIPTION)
    values: Annotated[list[Annotated[float, Field(ge=STEP_MIN, le=STEP_MAX)]], Field(min_length=1, max_length=STEP_ENTRIES)] = omitted(description="Single form, kind steps: " + STEPS_VALUES_DESCRIPTION)
    full: bool = omitted(description="Single form, kind steps: " + FULL_DESCRIPTION)
    value: bool | int | float | StringValue = omitted(description="Single form: requested native raw value (or give display instead).")
    display: str = omitted(min_length=1, max_length=64, description="Single form: " + DISPLAY_DESCRIPTION)
    writes: Annotated[list[GridRawWrite], Field(min_length=1, max_length=64)] = omitted(
        description="Batch form: 1..64 writes on this device, each a full single write. All CAS checks run first; any failure writes nothing. Then writes dispatch in order and stop at the first failure.")

    @model_validator(mode="after")
    def validate_one_form(self):
        supplied = GRID_WRITE_FIELDS & self.model_fields_set
        if "writes" in self.model_fields_set:
            if supplied or WRITE_DETAILS & self.model_fields_set:
                raise ValueError("supply either writes or the single-write fields, not both")
            keys = [(item.moduleId.lower(), item.parameterId) for item in self.writes]
            if len(set(keys)) != len(keys):
                raise ValueError("writes must not repeat a moduleId/parameterId pair")
        elif supplied != GRID_WRITE_FIELDS:
            raise ValueError("supply writes, or moduleId, parameterId, expectedKind, expectedValue and one of value or display (kind steps: expectedValues and values)")
        else:
            GridRawWrite.model_validate({key: getattr(self, key) for key in GRID_WRITE_FIELDS | WRITE_DETAILS & self.model_fields_set})
        return self


class GridVoicingFields(StrictModel):
    """Poly Grid device voicing fields; every one optional."""
    voices: int = omitted(ge=1, le=64, description="Voice count 1..64.")
    voiceStacking: int = omitted(ge=1, le=16, description="Voice stacking 1..16.")
    monoMode: Literal["true_mono", "digi_mono"] = omitted(description="Stored mono mode: true_mono, or digi_mono (alternating voices). It is what plays when voices is 1.")
    retriggerMode: Literal["never", "on_note_on", "always"] = omitted(description="Retrigger: never, on_note_on or always.")
    notePriority: Literal["last", "high", "low"] = omitted(description="Note priority: last, high or low.")
    legatoGlide: bool = omitted(description="Legato glide.")
    stealSameKey: bool = omitted(description="Steal voices on the same key.")
    allowOverlappingNotes: bool = omitted(description="Allow overlapping notes.")


class GridVoicingSetArgs(ParameterArgs):
    expected: GridVoicingFields = Field(description="Exact current values from a recent grid.voicing.get, at least for every field in set; stale is rejected not_applied. Extra fields are additional preconditions.")
    set: GridVoicingFields = Field(description="New values; at least one field. Unnamed fields stay unchanged (a native change may still move them: see changedUnrequested).")

    @model_validator(mode="after")
    def validate_voicing(self):
        want = self.set.model_fields_set
        if not want:
            raise ValueError("set must name at least one voicing field")
        if not want <= self.expected.model_fields_set:
            raise ValueError("expected must also give the current value of every field in set")
        return self


class SetArgs(ParameterArgs):
    contextToken: Nonempty
    parameterId: Nonempty
    value: UnitValue


class DeviceSetArgs(ParameterArgs):
    enabled: bool


def validate_insertion(self):
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


class DeviceInsertArgs(DeviceArgs):
    expectedDeviceCount: int = Field(ge=0, le=15)
    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()
    position: Literal["start", "end", "before", "after"] = omitted(description="Where in the chain; default end. before/after need deviceIndex and expectedDeviceName of the anchor device.")
    deviceIndex: int = omitted(ge=0, le=15, description="Anchor device for position before/after.")
    expectedDeviceName: Nonempty = omitted(description="Exact name of the anchor device.")

    @model_validator(mode="after")
    def validate_identifier(self):
        anchored = self.position in ("before", "after")
        given = {"deviceIndex", "expectedDeviceName"} & self.model_fields_set
        if anchored and len(given) != 2:
            raise ValueError("position before/after requires deviceIndex and expectedDeviceName")
        if not anchored and given:
            raise ValueError("deviceIndex and expectedDeviceName are only for position before/after")
        return validate_insertion(self)


class DeviceMoveArgs(ParameterArgs):
    toIndex: int = Field(ge=0, le=15)

    @model_validator(mode="after")
    def validate_move(self):
        if self.toIndex == self.deviceIndex:
            raise ValueError("toIndex equals deviceIndex; nothing to move")
        return self


class LayerInsertArgs(LayerListArgs):
    layerId: TrackId
    expectedChildCount: int = Field(ge=0, le=4096)
    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()
    position: Literal["start", "end"] = omitted(description="Start or end of the layer's chain; default end.")

    @model_validator(mode="after")
    def validate_identifier(self):
        return validate_insertion(self)


class PresetSearchArgs(StatusArgs):
    query: str = omitted(min_length=1, max_length=4096, description="Words that must all occur in the preset name, folders, package, creator or readable metadata; case, spaces and punctuation ignored.")
    device: str = omitted(min_length=1, max_length=4096, description="Device name (readable metadata) or a folder named for it, e.g. Polymer, Poly Grid, Convolution.")
    deviceType: Literal["instrument", "audio-effect", "note-effect"] = omitted(description="Only presets whose readable metadata gives this type; encrypted package presets have none.")
    category: str = omitted(min_length=1, max_length=4096, description="Preset/device category from readable metadata, or a folder name (e.g. Synth Bass, Drums).")
    limit: int = Field(default=25, ge=1, le=200)
    offset: int = Field(default=0, ge=0, le=1000000)


class ClipArgs(DeviceArgs):
    slotIndex: int = Field(ge=0, le=15)


class ScenesAppendArgs(StatusArgs):
    count: Annotated[int, Field(ge=1, le=16)] = omitted()


class SceneTargetArgs(StatusArgs):
    sceneIndex: int = Field(ge=0, le=15, description="Zero-based scene index from scenes.list.")
    expectedName: str = Field(max_length=256, description="Compare-and-set guard: the scene's exact current name from scenes.list; for an unnamed scene its current positional label (\"Scene N\"), which shifts after inserts/deletes.")


class SceneRenameArgs(SceneTargetArgs):
    name: str = Field(min_length=1, max_length=128)


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


LAUNCH_SETTINGS = {"launchQuantization", "launchMode", "useLoopStartAsQuantizationReference"}


class ClipSettingsSetArgs(ClipSettingsArgs):
    nextAction: Literal["play_next", "stop"] = omitted()
    nextActionEnabled: bool = omitted()
    nextActionLinkToLoop: bool = omitted()
    nextActionLoopCount: Annotated[int, Field(ge=1, le=64)] = omitted()
    nextActionDelayBeats: Annotated[float, Field(ge=0, le=4194304)] = omitted()
    launchQuantization: Nonempty = omitted(description="Clip launch quantization: one of the host's own options, listed in the result's options.launchQuantization. Selects the clip.")
    launchMode: Nonempty = omitted(description="Clip launch mode: one of the host's own options, listed in the result's options.launchMode. Selects the clip.")
    useLoopStartAsQuantizationReference: bool = omitted(description="Quantize the launch to the clip's loop start. Selects the clip.")

    @model_validator(mode="after")
    def validate_settings(self):
        settings = self.model_fields_set - {"trackId", "slotIndex", "expectedClipName", "timeoutMs"}
        if not settings:
            raise ValueError("supply at least one clip setting")
        launch = settings & LAUNCH_SETTINGS
        if launch and settings - launch:
            raise ValueError("launch fields (" + ", ".join(sorted(LAUNCH_SETTINGS)) + ") use a different path from the Next Action fields: send two calls")
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


class ArrangerNote(StrictModel):
    beat: float = Field(ge=0, le=4194304, description="Onset in beats from the clip start (not the arrangement), on a 1/128-beat grid.")
    pitch: int = Field(ge=0, le=127)
    velocity: int = Field(ge=1, le=127)
    durationBeats: float = Field(ge=1 / 128, le=4096)


class ArrangerClipListArgs(DeviceArgs):
    pass


class ArrangerClipCreateArgs(DeviceArgs):
    startBeats: float = Field(ge=0, le=4194304, description="Arrangement position in beats.")
    lengthBeats: float = Field(ge=1 / 128, le=65536)
    name: str = Field(min_length=1, max_length=128)
    notes: Annotated[list[ArrangerNote], Field(min_length=1, max_length=256)] = omitted()
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()

    @model_validator(mode="after")
    def validate_notes(self):
        if "notes" not in self.model_fields_set:
            if "channel" in self.model_fields_set:
                raise ValueError("channel is only valid together with notes")
            return self
        check_note_spans(self.notes)
        if any(Decimal(str(n.beat)) + Decimal(str(n.durationBeats)) > Decimal(str(self.lengthBeats)) for n in self.notes):
            raise ValueError("notes must fit inside lengthBeats")
        return self


class ArrangerCopyArgs(DeviceArgs):
    slotIndex: int = Field(ge=0, le=15)
    expectedClipName: str = Field(min_length=1, max_length=256)
    startBeats: float = Field(ge=0, le=4194304)


ARRANGER_EDIT_OPS = {"move": {"startBeats"}, "resize": {"lengthBeats"}, "rename": {"name"}, "delete": set()}


class ArrangerClipEditArgs(DeviceArgs):
    clipIndex: int = Field(ge=0, le=4095, description="Index from a fresh arranger.clips.list (event order: creation order on 6.1.4, not start order).")
    expectedStartBeats: float = Field(ge=0, le=4194304, description="Exact startBeats from arranger.clips.list (guard, compared within 1e-6).")
    expectedName: str = Field(max_length=256, description="Exact current name from arranger.clips.list; may be empty.")
    op: Literal["move", "resize", "rename", "delete"]
    startBeats: Annotated[float, Field(ge=0, le=4194304)] = omitted()
    lengthBeats: Annotated[float, Field(ge=1 / 128, le=65536)] = omitted()
    name: Annotated[str, Field(min_length=1, max_length=128)] = omitted()

    @model_validator(mode="after")
    def validate_op_fields(self):
        payload = self.model_fields_set & set().union(*ARRANGER_EDIT_OPS.values())
        if payload != ARRANGER_EDIT_OPS[self.op]:
            raise ValueError(f"op {self.op} takes exactly {sorted(ARRANGER_EDIT_OPS[self.op]) or 'no extra fields'}")
        return self


class AutomationTarget(StrictModel):
    kind: Literal["volume", "pan", "grid"] = Field(description="Track volume, track pan, or a float Grid module parameter on a top-level device of the same track.")
    deviceIndex: Annotated[int, Field(ge=0, le=15)] = omitted()
    expectedDeviceName: Nonempty = omitted()
    moduleId: TrackId = omitted(description="Grid module instanceId from grid.get on that device.")
    parameterId: Annotated[str, Field(max_length=1024)] = omitted(description="Exact parameterId of that module's float control, from grid.get.")

    @model_validator(mode="after")
    def validate_kind_fields(self):
        grid = {"deviceIndex", "expectedDeviceName", "moduleId", "parameterId"}
        given = self.model_fields_set & grid
        if given != (grid if self.kind == "grid" else set()):
            raise ValueError("kind grid takes exactly deviceIndex, expectedDeviceName, moduleId and parameterId; volume and pan take none")
        return self


class AutomationRange(StrictModel):
    startBeat: float = Field(ge=0, le=4194304)
    endBeat: float = Field(ge=0, le=4194304)

    @model_validator(mode="after")
    def validate_order(self):
        if self.startBeat > self.endBeat:
            raise ValueError("startBeat must not exceed endBeat")
        return self


class AutomationPoint(StrictModel):
    beat: float = Field(ge=0, le=4194304, description="Arrangement position in beats (quarter notes).")
    value: UnitValue = Field(description="Normalized 0..1 parameter value.")
    interpolation: Literal["linear", "hold"]


class AutomationPointsGetArgs(DeviceArgs):
    target: AutomationTarget
    range: AutomationRange = omitted(description="Only list points with startBeat <= beat <= endBeat; count and pointsHash still cover the whole lane.")


class AutomationPointsSetArgs(DeviceArgs):
    target: AutomationTarget
    expectedCount: int = Field(ge=0, le=65536, description="count from a fresh automation.points.get.")
    expectedHash: str = Field(pattern=r"^[0-9a-f]{64}$", description="pointsHash from a fresh automation.points.get.")
    points: Annotated[list[AutomationPoint], Field(max_length=1024)] = Field(description="Replacement points, strictly increasing beats, inside range if given.")
    range: AutomationRange = omitted(description="Replace only points with startBeat <= beat <= endBeat; without it the whole lane is replaced.")

    @model_validator(mode="after")
    def validate_points(self):
        beats = [point.beat for point in self.points]
        if any(b <= a for a, b in zip(beats, beats[1:])):
            raise ValueError("point beats must be strictly increasing")
        if "range" in self.model_fields_set and any(not self.range.startBeat <= b <= self.range.endBeat for b in beats):
            raise ValueError("every point must lie inside range")
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
    timeSignature: str = omitted(description="numerator/denominator, e.g. 7/8: numerator 1..32, denominator 1, 2, 4, 8, 16 or 32.")
    metronomeVolumeNormalized: UnitValue = omitted(description="Metronome volume 0..1 (not dB); read back as observed.")
    punchInEnabled: bool = omitted(description="Arranger punch-in on/off.")
    punchOutEnabled: bool = omitted(description="Arranger punch-out on/off.")
    preRoll: Nonempty = omitted(description="Pre-roll length: one of transport.get options.preRoll (the host's own list).")
    defaultLaunchQuantization: Nonempty = omitted(description="Project default clip launch quantization: one of transport.get options.defaultLaunchQuantization.")
    recordQuantizationGrid: Nonempty = omitted(description="Grid for quantizing recorded notes: one of transport.get options.recordQuantizationGrid.")

    @field_validator("timeSignature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        if not re.fullmatch(r"[1-9][0-9]?/(1|2|4|8|16|32)", value, re.ASCII) or int(value.split("/")[0]) > 32:
            raise ValueError("timeSignature must be numerator/denominator: numerator 1..32, denominator 1, 2, 4, 8, 16 or 32")
        return value

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
    volumeDb: Annotated[float, Field(ge=-150, le=24)] = omitted(description="Fader level in dB, reached by matching the host's own displayed value (not a computed curve). Alternative to volumeNormalized/volumeDisplay.")
    volumeDisplay: Nonempty = omitted(description='Fader level as the host shows it, e.g. "-6 dB" or "-inf dB".')
    panPercent: Annotated[float, Field(ge=-100, le=100)] = omitted(description="Pan in percent, negative = left, reached by matching the host's displayed value. Alternative to panNormalized/panDisplay.")
    panDisplay: Nonempty = omitted(description='Pan as the host shows it, e.g. "25% L", "40% R" or "Center".')
    color: Annotated[str, Field(pattern=r"^#[0-9a-fA-F]{6}$")] = omitted(description="Track color as #RRGGBB.")
    activated: bool = omitted(description="Track activated (false = deactivated, no processing).")
    monitorMode: Nonempty = omitted(description="Input monitoring: one of tracks.get options.monitorMode (the host's own list).")
    crossFadeMode: Nonempty = omitted(description="Crossfader assignment: one of tracks.get options.crossFadeMode (the host's own list).")

    @model_validator(mode="after")
    def require_change(self):
        if not self.model_fields_set - {"timeoutMs", "trackId"}:
            raise ValueError("tracks.set requires at least one changed field")
        for group in (("volumeNormalized", "volumeDb", "volumeDisplay"), ("panNormalized", "panPercent", "panDisplay")):
            if len(set(group) & self.model_fields_set) > 1:
                raise ValueError("supply at most one of " + ", ".join(group))
        return self


class TrackSendSetArgs(DeviceArgs):
    sendIndex: int = omitted(ge=0, le=127, description="Zero-based send index from tracks.get; exactly one of sendIndex or sendName.")
    sendName: Nonempty = omitted(description="Exact send name from tracks.get (the FX track it feeds); must be unique on the track.")
    expectedSendName: Nonempty = omitted(description="Compare-and-set guard: the write is refused with stale_target if the send has another name.")
    valueNormalized: UnitValue = omitted(description="Send level 0..1 (not dB). Exactly one of valueNormalized or display, unless only enabled is changed.")
    display: Nonempty = omitted(description='Send level as the host shows it, e.g. "-12 dB" or "-inf dB".')
    enabled: bool = omitted(description="Turn the send on or off. A level write already turns it on (autoEnabled in the result).")

    @model_validator(mode="after")
    def validate_send(self):
        fields = self.model_fields_set
        if ("sendIndex" in fields) == ("sendName" in fields):
            raise ValueError("supply exactly one of sendIndex or sendName")
        if {"valueNormalized", "display"} <= fields:
            raise ValueError("supply at most one of valueNormalized, display")
        if not fields & {"valueNormalized", "display", "enabled"}:
            raise ValueError("at least one of valueNormalized, display or enabled is required")
        return self


class TrackMetersArgs(StatusArgs):
    trackIds: list[TrackId] = Field(min_length=1, max_length=16)
    windowMs: int = Field(ge=50, le=5000)

    @model_validator(mode="after")
    def unique_tracks(self):
        if len({t.lower() for t in self.trackIds}) != len(self.trackIds):
            raise ValueError("trackIds must be unique")
        return self


class MarkerInsertArgs(StatusArgs):
    beat: float = Field(ge=0, le=1e7, description="Quarter-note beats from the project start.")
    name: Nonempty = omitted(description="Marker name; omitted keeps the name the host assigns.")


class MarkerRenameArgs(StatusArgs):
    markerIndex: int = Field(ge=0, le=63, description="Rank in the beat-sorted markers.list result.")
    expectedName: Nonempty
    name: Nonempty
    expectedBeat: float = omitted(ge=0, le=1e7, description="Optional second guard: the marker must be at this beat.")


class MarkerDeleteArgs(StatusArgs):
    markerIndex: int = Field(ge=0, le=63, description="Rank in the beat-sorted markers.list result.")
    expectedName: Nonempty
    expectedBeat: float = omitted(ge=0, le=1e7, description="Optional second guard: the marker must be at this beat.")


class ExtensionRestartArgs(StatusArgs):
    discardUnresolved: bool = omitted()


PROJECT_EXTENSION = ".bwproject"


class ProjectSaveAsArgs(StrictModel):
    timeoutMs: int = Field(default=30000, ge=100, le=TOPOLOGY_TIMEOUT_MS)
    path: str = Field(max_length=4096, description="Final project file: <dir>/<Name>/<Name>.bwproject for a new project folder (Bitwig creates <Name>/), or any .bwproject inside an existing project folder. Absolute; no ~ expansion.")
    overwrite: bool = omitted(description="Allow replacing an existing file at path (Bitwig keeps the old one in the project's auto-backups folder).")
    allowAnyPath: bool = omitted(description="Allow a path outside Bitwig's configured projects folder.")

    @model_validator(mode="after")
    def validate_path(self):
        validate_local_path(self.path)
        path = PurePosixPath(self.path)
        if len(path.parts) < 3 or not path.name.endswith(PROJECT_EXTENSION):
            raise ValueError(f"path must name a {PROJECT_EXTENSION} file inside a folder")
        name = path.name[: -len(PROJECT_EXTENSION)]
        if not name or len(name.encode("utf-16-le", errors="surrogatepass")) // 2 > 128 or name != name.strip() or name.startswith(".") or name.lower() == "template":
            raise ValueError("project name must be 1..128 characters without leading/trailing whitespace or a leading dot, and not 'template'")
        if "auto-backups" in path.parts:
            raise ValueError("Bitwig refuses to save inside an auto-backups folder")
        return self


RENDER_MAX_BEATS = 4194304
RENDER_MAX_LENGTH = 65536


class RenderRangeArgs(StrictModel):
    timeoutMs: int = Field(default=30000, ge=100, le=TOPOLOGY_TIMEOUT_MS)
    startBeats: float = Field(ge=0, le=RENDER_MAX_BEATS, description="Range start in quarter-note beats from the project start.")
    endBeats: float = Field(ge=0, le=RENDER_MAX_BEATS + RENDER_MAX_LENGTH, description="Range end, after startBeats and at most 65536 beats later.")

    @model_validator(mode="after")
    def validate_range(self):
        if self.endBeats <= self.startBeats or self.endBeats - self.startBeats > RENDER_MAX_LENGTH:
            raise ValueError("endBeats must be after startBeats, at most 65536 beats later")
        return self


class ExportAudioArgs(RenderRangeArgs):
    sources: Annotated[list[TrackId | Literal["master"]], Field(min_length=1, max_length=16)] = Field(
        description="\"master\" and/or trackIds from tracks.list (audio, instrument, effect or group tracks); one file each, in this order.")
    directory: str = Field(max_length=4096, description="Existing absolute folder for the files. No ~ expansion.")
    baseName: str = Field(min_length=1, max_length=128, description="File name without extension: <baseName>.<format> for one source, <baseName>-01, -02, ... for several.")
    format: Literal["wav", "flac"]
    bitDepth: Literal[16, 24, 32] = omitted(description="Default 24; 32 = 32-bit float (wav only).")
    sampleRate: Literal[44100, 48000] = omitted(description="Omitted: the current engine rate.")
    tap: Literal["post_fader", "pre_fader"] = omitted(description="Default post_fader.")
    dither: bool = omitted()
    overwrite: bool = omitted(description="Allow replacing existing files with the same names.")

    @model_validator(mode="after")
    def validate_export(self):
        if len({value.lower() for value in self.sources}) != len(self.sources):
            raise ValueError("sources must be distinct")
        validate_local_path(self.directory)
        name = self.baseName
        if name != name.strip() or name.startswith(".") or "/" in name or "\\" in name or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name):
            raise ValueError("baseName must not contain '/', '\\', control characters, a leading dot or surrounding whitespace")
        if self.format == "flac" and self.bitDepth == 32:
            raise ValueError("flac supports bitDepth 16 or 24")
        return self


class BounceInPlaceArgs(RenderRangeArgs):
    trackIds: Annotated[list[TrackId], Field(min_length=1, max_length=16)] = Field(
        description="Audio/instrument tracks (trackIds from tracks.list) whose main arranger lane gets the bounced audio.")
    tap: Literal["pre_fx", "pre_fader", "post_fader"]

    @model_validator(mode="after")
    def validate_distinct(self):
        if len({value.lower() for value in self.trackIds}) != len(self.trackIds):
            raise ValueError("trackIds must be distinct")
        return self


class TrackCreateArgs(StatusArgs):
    type: Literal["audio", "instrument", "effect"]
    position: int = Field(ge=-1, le=2147483647)
    name: Nonempty


class TrackGroupArgs(StatusArgs):
    trackIds: Annotated[list[TrackId], Field(min_length=1, max_length=16)] = Field(
        description="Tracks to put into one new group, all with the same parent (top level or one group); not effect or master tracks.")
    name: Nonempty = omitted()

    @model_validator(mode="after")
    def validate_distinct(self):
        if len({value.lower() for value in self.trackIds}) != len(self.trackIds):
            raise ValueError("trackIds must be distinct")
        return self


class TrackDeleteArgs(DeviceArgs):
    expectedName: Nonempty


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


class RemoteSlotSpec(StrictModel):
    """One Remote Control slot: a scalar Grid parameter by module (exactly one of moduleId or moduleName) and parameterId."""
    moduleId: TrackId = omitted(description="Current module instanceId from grid.get.")
    moduleName: str = omitted(min_length=1, max_length=1024, description="Exact effective module name from grid.get; must be unique in the device.")
    parameterId: str = Field(max_length=1024, description="Exact parameterId from grid.get (e.g. VALUE of a Value module); empty is valid.")
    label: str = omitted(max_length=256, description="Knob label shown instead of the parameter's own name; empty or absent keeps Bitwig's default.")

    @model_validator(mode="after")
    def validate_module(self):
        if len({"moduleId", "moduleName"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of moduleId or moduleName")
        return self


class RemoteDefineArgs(ParameterArgs):
    pageName: str = Field(min_length=1, max_length=256, description="Page name shown in the device's Remote Controls page list; kept verbatim.")
    slots: Annotated[list[RemoteSlotSpec | None], Field(min_length=1, max_length=8)] = Field(
        description="Slot k is entry k (0..7); null or absent trailing entries leave that slot empty (replace clears it).")
    mode: Literal["create", "replace"] = omitted(description="create (default) refuses an existing page of that name; replace redefines the one preset page of that name, or creates it.")

    @model_validator(mode="after")
    def validate_distinct(self):
        pairs = [((s.moduleId or "").lower(), s.moduleName or "", s.parameterId) for s in self.slots if s is not None]
        if len(pairs) != len(set(pairs)):
            raise ValueError("slots must not repeat a module/parameter pair")
        return self


class RemoteDeletePageArgs(ParameterArgs):
    pageName: str = Field(min_length=1, max_length=256, description="Exact name of one preset Remote Controls page (made by devices.remote.define or Add New Page); device, module and plugin pages are refused.")


class GridApplyTarget(StrictModel):
    trackId: TrackId
    deviceIndex: int = Field(ge=0, le=15)
    expectedDeviceName: Nonempty


class GridApplyDisplay(StrictModel):
    display: str = Field(min_length=1, max_length=64, description="Text with explicit unit, e.g. '1.2 kHz'; float parameters only.")


class GridApplySteps(StrictModel):
    steps: list[float] | Annotated[str, Field(pattern=r"^\$\{[A-Za-z0-9_]+\}$")] = Field(
        description="Step-array parameter (Steps/Pitches/Gates/Probabilities/Accents): entries from step 0, 1..256 finite numbers within -128..128, at most the live arrayLength; or a whole-value '${name}' block placeholder for such a list.")


class GridApplyLabel(StrictModel):
    label: str = Field(min_length=1, max_length=1024, description="Enum option label, e.g. '16th'.")


class GridApplyModule(StrictModel):
    name: str = Field(min_length=1, max_length=1024, description="Identity: the module's customName, unique and non-empty within the device.")
    type: str = omitted(min_length=1, max_length=128, description="Catalog module name (e.g. 'Multiply'); exactly one of type or typeId.")
    typeId: TrackId = omitted(description=TYPE_ID_DESCRIPTION)
    x: int = Field(ge=-(2**31), le=2**31 - 1)
    y: int = Field(ge=-(2**31), le=2**31 - 1)
    params: dict[str, bool | int | float | str | GridApplyDisplay | GridApplyLabel | GridApplySteps] = omitted(
        description="parameterId -> raw value, {display:'1.2 kHz'}, {label:'16th'}, {steps:[1,0,0.5]} (step arrays: first entries written, rest unchanged) or a string (string panels, e.g. Comment TEXT; at most 1024 UTF-16 units, multi-line ok).")
    replace: bool = omitted(description="If a module with this name exists with another type, delete it and insert this type under the same name (default: refuse).")

    @model_validator(mode="after")
    def validate_one_type(self):
        if len({"type", "typeId"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or typeId")
        return self


class GridApplyConnection(StrictModel):
    from_: str = Field(alias="from", min_length=3, max_length=2048, description="Source output 'module name.PORT_KEY' (split on the last '.').")
    to: str = Field(min_length=3, max_length=2048, description="Target input 'module name.PORT_KEY'.")


class GridApplyBlock(StrictModel):
    params: dict[str, bool | int | float | str | list[float]] = omitted(description="Parameter name -> default (a list for step arrays); params values of the form '${name}' (whole value) take it, or the instance's args value.")
    modules: Annotated[list[GridApplyModule], Field(min_length=1, max_length=1024, description="Modules with local names and positions relative to the instance (no nesting).")]
    connections: Annotated[list[GridApplyConnection], Field(max_length=MAX_APPLY_CONNECTIONS)] = omitted(description="Cables between the block's own modules, by local names.")


class GridApplyInstance(StrictModel):
    block: str = Field(min_length=1, max_length=1024, description="Key of a block in blocks.")
    prefix: str = omitted(max_length=1024, description="Prepended to every local module name (e.g. 'D1: '); instances need distinct prefixes.")
    dx: int = omitted(ge=-(2**31), le=2**31 - 1, description="Offset added to every module x (default 0).")
    dy: int = omitted(ge=-(2**31), le=2**31 - 1, description="Offset added to every module y (default 0).")
    args: dict[str, bool | int | float | str | list[float]] = omitted(description="Values for the block's params.")


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


class GridExportArgs(ParameterArgs):
    namespace: str = omitted(min_length=1, max_length=1024, description="Export only modules whose customName starts with this; cables to/from other modules become `external` names.")
    includeParams: Literal["changed", "all", "none"] = omitted(description="changed (default): params differing from the bundled 6.1.4 defaults; all; none.")
    outPath: Nonempty = omitted(description="Absolute path to write the spec JSON to (then the result omits the spec); refused if it exists unless overwrite=true.")
    overwrite: bool = omitted(description="Replace an existing file at outPath.")

    @model_validator(mode="after")
    def validate_path(self):
        if self.outPath is not None:
            validate_local_path(self.outPath)
        return self


class GridGraphArgs(ParameterArgs):
    namespace: str = omitted(min_length=1, max_length=1024, description="Only modules whose customName starts with this (plus cables touching them).")
    includeParams: Literal["changed", "all", "none"] = omitted(description="none (default), changed (differing from bundled 6.1.4 defaults) or all.")


class GridProbeArgs(ParameterArgs):
    timeoutMs: int = Field(default=5000, ge=100, le=TOPOLOGY_TIMEOUT_MS)
    port: Annotated[str, Field(min_length=3, max_length=1024)] = Field(description="'<module name>.<OUT port key>' of a module in this device, e.g. 'H: lead.OUT'; the name is the customName (or generated name) from grid.graph.")
    seconds: float = Field(ge=0.2, le=20, description="Recording length.")
    mode: Literal["probe", "mono_pitch", "onsets", "level"] = Field(description="audio.analyze mode run on the recording.")
    allowAudible: bool = omitted(description="Record without audio.route silent (the probed signal is audible).")
    bpm: Annotated[float, Field(ge=20, le=666)] = omitted(description="mono_pitch: adds 16th-grid positions.")
    skipSeconds: Annotated[float, Field(ge=0, le=3600)] = omitted(description="Ignore audio before this time.")
    method: Literal["flux", "envelope"] = omitted(description="onsets/mono_pitch onset detector (default flux).")
    threshold: Annotated[float, Field(ge=0.01, le=0.9)] = omitted(description="onsets/mono_pitch sensitivity.")
    windowSeconds: Annotated[float, Field(ge=0.01, le=60)] = omitted(description="level: window length.")


class GridApplyArgs(StatusArgs):
    target: GridApplyTarget
    modules: Annotated[list[GridApplyModule], Field(max_length=1024, description="Declared modules; [] only with prune=true and a namespace (empties the namespace), or with instances.")] = omitted()
    blocks: dict[str, GridApplyBlock] = omitted(description="Reusable sub-patches: {name: {params?, modules, connections?}}; expanded by instances before validation.")
    instances: Annotated[list[GridApplyInstance], Field(max_length=1024)] = omitted(
        description="Copies of blocks: {block, prefix?, dx?, dy?, args?}. Module names become prefix+local name, positions shift by dx/dy; cables from/to an instance use the expanded names.")
    connections: Annotated[list[GridApplyConnection], Field(max_length=MAX_APPLY_CONNECTIONS)] = omitted(
        description="Cables between declared modules; one source per target input.")
    namespace: str = omitted(min_length=1, max_length=1024, description="customName prefix (e.g. 'H: ') that prune may delete from.")
    prune: bool = omitted(description="Also disconnect unlisted inputs of declared modules inside the namespace and delete namespace modules absent from the spec. Inputs of declared modules outside the namespace are kept (warning names both ends) unless allowOutsideDisconnect.")
    pruneAll: bool = omitted(description="Opt in to managing the WHOLE device (mutually exclusive with namespace): delete every module the spec does not declare (unnamed ones too; `external` excepted), replace same-named modules of another type (as replace:true), and disconnect every undeclared input. Deletes run first; all of it is listed in the dryRun plan and warnings. Use dryRun first.")
    allowOutsideDisconnect: bool = omitted(description="With prune and a namespace: also disconnect unlisted inputs of declared modules outside the namespace (listed by name in warnings and the dryRun plan).")
    external: Annotated[list[Annotated[str, Field(min_length=1, max_length=1024)]], Field(max_length=1024)] = omitted(
        description="Names of live modules this spec only wires to or from (e.g. 'X: sum'); they must exist, are used in connections like declared modules, and their type, position and params are never touched; never pruned.")
    dryRun: bool = omitted(description="Return the plan without writing anything.")
    replace: bool = omitted(description="Default for every module: replace an existing same-named module of another type instead of refusing.")
    voicing: GridVoicingFields = omitted(description="Poly Grid device voicing to converge to (voices, voiceStacking, monoMode, ...); only differing fields are written; refused before any write on Note/FX Grid.")

    @model_validator(mode="after")
    def validate_spec(self):
        if "voicing" in self.model_fields_set and not self.voicing.model_fields_set:
            raise ValueError("voicing must name at least one field")
        if self.pruneAll and (self.namespace or self.allowOutsideDisconnect):
            raise ValueError("pruneAll manages the whole device: do not combine it with namespace or allowOutsideDisconnect")
        if not self.modules and not self.instances and not (self.prune and self.namespace) and not self.pruneAll:
            raise ValueError("modules may be empty only with prune=true and a non-empty namespace, pruneAll=true, or with instances")
        if self.instances and not self.blocks:
            raise ValueError("instances need blocks")
        names = [m.name for m in self.modules or []]
        dupes = sorted({n for n in names if names.count(n) > 1})
        if dupes:
            raise ValueError(f"module names must be unique: {dupes}")
        ext = self.external or []
        dupes = sorted({n for n in ext if ext.count(n) > 1})
        if dupes:
            raise ValueError(f"external names must be unique: {dupes}")
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
    allowAudible: bool = omitted(description="Do not require audio.route silent (the signal stays audible). Capture always records Bitwig's own out1/out2, never the default output monitor, so other applications are not in the file.")
    soloTrack: TrackId = omitted(description="Really solo this track for the recording: its solo is set and every other soloed track cleared, then every changed solo is restored afterwards (also on failure); the result reports soloed/unsoloed/restored. Refuses (not_applied) while other tracks have playing launcher clips unless stopOthers.")
    stopOthers: bool = omitted(description="Stop the launcher clips of every other playing track (launcher.stop) before recording; the result lists them. Without soloTrack, nothing is spared: use it with soloTrack.")

    @field_validator("outPath")
    @classmethod
    def validate_out_path(cls, value: str) -> str:
        return _wav_path(value)


class AudioAnalyzeArgs(StatusArgs):
    path: Nonempty
    mode: Literal["level", "onsets", "mono_pitch", "probe", "loudness", "bands"]
    bpm: Annotated[float, Field(ge=20, le=666)] = omitted(description="mono_pitch: adds 16th-grid positions.")
    skipSeconds: Annotated[float, Field(ge=0, le=3600)] = omitted(description="Ignore audio before this time; reported times stay relative to the file start.")
    method: Literal["flux", "envelope"] = omitted(description="onsets/mono_pitch: flux (default) = spectral flux, counts overlapping notes with long decays; envelope = rising crossings of a 5 ms peak envelope.")
    threshold: Annotated[float, Field(ge=0.01, le=0.9)] = omitted(description="onsets/mono_pitch: flux: how far a flux peak must stand above its neighbourhood, as a fraction of the strongest peak; envelope: fraction of the peak envelope that counts as an onset (default 0.15).")
    windowSeconds: Annotated[float, Field(ge=0.01, le=60)] = omitted(description="level: window length (default 0.5); bands: window length (default 8).")
    key: str = omitted(pattern=r"^[A-Ga-g][#b]?:(ionian|dorian|phrygian|lydian|mixolydian|aeolian|locrian|harmonic_minor)$",
                       description="bands: e.g. 'F:aeolian'; adds per-window and overall in-key chroma share (50-1500 Hz peaks folded to pitch classes) and top pitch classes. Drums and noise pollute chroma.")
    spectrogramPath: Nonempty = omitted(description="bands: absolute .png path to write a log-frequency spectrogram to (an existing file is replaced).")

    @field_validator("path")
    @classmethod
    def validate_path(cls, value: str) -> str:
        return _wav_path(value)

    @field_validator("spectrogramPath")
    @classmethod
    def validate_spectrogram_path(cls, value: str) -> str:
        validate_local_path(value)
        if not value.lower().endswith(".png"):
            raise ValueError("spectrogramPath must end in .png")
        return value

    @model_validator(mode="after")
    def validate_bands_only(self):
        if self.mode != "bands" and (self.key or self.spectrogramPath):
            raise ValueError("key and spectrogramPath apply to mode=bands only")
        return self

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
