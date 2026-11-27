from __future__ import annotations

import math
from typing import Annotated, Any, Literal, Self, cast

from pydantic import ConfigDict, Field, field_validator, model_validator
from pydantic.config import JsonDict

from bitwig_agent.protocol.annotations import SET
from bitwig_agent.protocol.base import (
    MAX_STRING_UNITS,
    PROJECT_READY,
    TRACK,
    UTF16_BYTES_PER_UNIT,
    StatusArgs,
    StrictModel,
    ToolSpec,
    Uuid,
    long_timeout,
    omitted,
)
from bitwig_agent.protocol.limits import LONG_TIMEOUT_MS, REMOTE_PAGE_SLOTS
from bitwig_agent.protocol.refs import DeviceRef, RemotePageRef, RemoteSlotParameterRef

DEVICE = {"kind": "device", "track": {"kind": "track", "id": TRACK, "name": "G"}, "index": 0, "name": "Grid"}
TARGET = {"target": DEVICE}


class GridDeviceArgs(StatusArgs):
    target: DeviceRef = Field(description="The Grid device: a device ref from devices.list.")


class GridCatalogArgs(StatusArgs):
    query: str = Field(
        default="",
        max_length=128,
        description="Case-, space-, hyphen- and punctuation-insensitive; every word must match inside the module name, so 'lowpass' and 'low pass' both find 'Low-pass'. A miss returns up to five nearest names as suggestions.",
    )
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=64, ge=1, le=256)


class GridGetArgs(GridDeviceArgs):
    moduleOffset: int = Field(default=0, ge=0, le=100000)
    moduleLimit: int = Field(default=64, ge=1, le=256)
    includeParameters: bool = True
    diagnostics: bool = Field(
        default=False,
        description="Add obfuscated native modelClass/path and constant coverage notes; no write needs them.",
    )
    moduleIds: Annotated[list[Uuid], Field(min_length=1, max_length=256)] = omitted(
        description="Return only these module instanceIds (e.g. one module's parameters without paging); absent ids are listed in missingModuleIds and the read is never complete."
    )
    full: bool = omitted(
        description="Step-array parameters (kind steps) return all 256 entries instead of the first STEPS ones."
    )


class GridWriteArgs(GridDeviceArgs):
    timeoutMs: int = long_timeout()
    graph: Literal["diff", "full"] = omitted(
        description="diff (default): what changed, computed from the before/after graphs. full: the whole bounded graph after the edit."
    )


GRID_INSERT_FIELDS = {"x", "y"}
TYPE_ID_DESCRIPTION = "Canonical MODULE type UUID discovered with grid.describe, not a module instanceId or file path; the host verifies catalog membership."
TYPE_NAME_DESCRIPTION = "Alternative to typeId: a catalog module name. Matching order: exact string, then exact ignoring case, then loose (ignoring case, spaces, hyphens and punctuation: 'low pass' = 'Low-pass'); the first step with a match decides. Symbol-only names ('<', '=', '≠', '≤') match exactly, and 'Slope ↗' vs 'Slope ↘' are told apart by exact match. Rejected (invalid_params) with candidates unless exactly one entry matches at that step; if ambiguous, use typeId."
INT32_DESCRIPTION = "Requested absolute native anchor, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host."


class GridModuleSpec(StrictModel):
    typeId: Uuid = omitted(description=TYPE_ID_DESCRIPTION)
    typeName: str = omitted(min_length=1, max_length=128, description=TYPE_NAME_DESCRIPTION)
    x: int = Field(ge=-(2**31), le=2**31 - 1, description=INT32_DESCRIPTION)
    y: int = Field(ge=-(2**31), le=2**31 - 1, description=INT32_DESCRIPTION)

    @model_validator(mode="after")
    def validate_one_type(self) -> Self:
        if len({"typeId", "typeName"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of typeId or typeName")
        return self


class GridModuleInsertArgs(GridWriteArgs):
    """One insertion (typeId|typeName, x, y) or a same-device batch (modules), never both."""

    model_config = ConfigDict(
        json_schema_extra={
            "oneOf": [{"required": ["x", "y"]}, {"required": ["modules"]}],
        }
    )

    typeId: Uuid = omitted(description="Single form: " + TYPE_ID_DESCRIPTION)
    typeName: str = omitted(min_length=1, max_length=128, description="Single form: " + TYPE_NAME_DESCRIPTION)
    x: int = omitted(ge=-(2**31), le=2**31 - 1, description="Single form: " + INT32_DESCRIPTION)
    y: int = omitted(ge=-(2**31), le=2**31 - 1, description="Single form: " + INT32_DESCRIPTION)
    modules: Annotated[list[GridModuleSpec], Field(min_length=1)] = omitted(
        description="Batch form: one or more insertions on this device, each {typeId|typeName, x, y}. All types and limits are checked first (any failure inserts nothing); then entries insert in order, each settling before the next, and stop at the first failure."
    )

    @model_validator(mode="after")
    def validate_one_form(self) -> Self:
        single = {"typeId", "typeName", "x", "y"} & self.model_fields_set
        if "modules" in self.model_fields_set:
            if single:
                raise ValueError("supply either modules or the single-insertion fields, not both")
        elif not (single >= GRID_INSERT_FIELDS and len(single & {"typeId", "typeName"}) == 1):
            raise ValueError("supply modules, or x, y and exactly one of typeId or typeName")
        return self


class GridVoicingGetArgs(GridDeviceArgs):
    detail: Literal["summary", "full"] = omitted(
        description="summary (default) omits the editContext observation; full keeps it."
    )


class GridModuleEditArgs(GridWriteArgs):
    moduleId: Uuid = Field(
        description="Current module instanceId from grid.get; reacquire after reopening the project."
    )


class GridConnectionSource(StrictModel):
    moduleId: Uuid = Field(description="Current source module instanceId from grid.get, not a type UUID.")
    portKey: str = Field(
        max_length=MAX_STRING_UNITS,
        description=f"Exact output port key from grid.get; empty is valid, at most {MAX_STRING_UNITS} UTF-16 code units.",
    )


GRID_CONNECTION_FIELDS = {"targetModuleId", "targetPortKey", "expectedSource", "source"}


class GridConnectionEdit(StrictModel):
    targetModuleId: Uuid = Field(
        description="Current destination module instanceId from grid.get; reacquire after reopening the project."
    )
    targetPortKey: str = Field(
        max_length=MAX_STRING_UNITS,
        description=f"Exact input port key from grid.get; empty is valid, at most {MAX_STRING_UNITS} UTF-16 code units.",
    )
    expectedSource: GridConnectionSource | None = Field(
        description="Required exact prior source from fresh grid.get, or explicit null for an unconnected input. Checked before even an already-matching no-op; never defaults to null."
    )
    source: GridConnectionSource | None = Field(
        description="Required requested output endpoint, or explicit null to disconnect. Must belong to the same current graph; native repair can change the observed result."
    )


class GridConnectionSetArgs(GridWriteArgs):
    """One connection edit (the four fields) or a same-device batch (connections), never both."""

    model_config = ConfigDict(
        json_schema_extra={
            "oneOf": [{"required": [*sorted(GRID_CONNECTION_FIELDS)]}, {"required": ["connections"]}],
        }
    )

    targetModuleId: Uuid = omitted(
        description="Single form: current destination module instanceId from grid.get; reacquire after reopening the project."
    )
    targetPortKey: str = omitted(
        max_length=MAX_STRING_UNITS,
        description=f"Single form: exact input port key from grid.get; empty is valid, at most {MAX_STRING_UNITS} UTF-16 code units.",
    )
    expectedSource: GridConnectionSource | None = omitted(
        description="Single form: required exact prior source from fresh grid.get, or explicit null for an unconnected input. Checked before even an already-matching no-op; never defaults to null."
    )
    source: GridConnectionSource | None = omitted(
        description="Single form: required requested output endpoint, or explicit null to disconnect. Must belong to the same current graph; native repair can change the observed result."
    )
    connections: Annotated[list[GridConnectionEdit], Field(min_length=1, max_length=32)] = omitted(
        description="Batch form: 1..32 connection edits on this device, each a full single edit on a distinct input. All expectedSource checks run first; any failure writes nothing. Then edits apply in order and stop at the first failure."
    )

    @model_validator(mode="after")
    def validate_one_form(self) -> Self:
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


class GridModuleSetArgs(GridModuleEditArgs):
    expectedCustomName: str = Field(
        max_length=MAX_STRING_UNITS,
        description=f"Exact raw customName from a fresh grid.get, not effective name or typeName. Required even for no-op; empty is valid. At most {MAX_STRING_UNITS} UTF-16 code units; no trimming or coercion.",
    )
    customName: str = Field(
        max_length=MAX_STRING_UNITS,
        description=f"Requested raw custom name; empty resets the override through native semantics. Unicode and whitespace are preserved, at most {MAX_STRING_UNITS} UTF-16 code units.",
    )


class GridModuleMoveArgs(GridModuleEditArgs):
    expectedX: int = Field(
        ge=-(2**31),
        le=2**31 - 1,
        description="Fresh native anchor X; exact stale-state check precedes even a no-op. Signed int32 JSON integer token.",
    )
    expectedY: int = Field(
        ge=-(2**31),
        le=2**31 - 1,
        description="Fresh native anchor Y; exact stale-state check precedes even a no-op. Signed int32 JSON integer token.",
    )
    x: int = Field(
        ge=-(2**31),
        le=2**31 - 1,
        description="Requested absolute native anchor X, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.",
    )
    y: int = Field(
        ge=-(2**31),
        le=2**31 - 1,
        description="Requested absolute native anchor Y, not a relative delta or pixels. Signed int32 JSON integer token; native layout bounds checked by host.",
    )


DISPLAY_DESCRIPTION = (
    "Instead of value, for float parameters whose numericUnit has a verified conversion (hertz: raw is a MIDI note, e.g. '1.2 kHz', '440 Hz'; percent: '50 %'; decibel: '-12 dB'). "
    "The text needs an explicit unit; the host confirms the unit scale against the current raw/display pair, else rejects with unsupported_unit. "
    "The result reports displayMismatch from the post-write display; it never writes again to correct it."
)


StringValue = Annotated[str, Field(max_length=MAX_STRING_UNITS)]
STEP_MIN, STEP_MAX, STEP_ENTRIES = -128.0, 128.0, 256
FULL_DESCRIPTION = "steps parameters only: expose (and, on a write, compare-and-set) up to 256 step-array entries instead of the first STEPS ones. Default: the STEPS prefix."
STEPS_EXPECTED_DESCRIPTION = (
    "kind steps: the exact current entries from step 0 as read by grid.get (normally the first STEPS, or all with full:true); its length is the CAS window, "
    "numbers as read (32-bit floats); compared after rounding to float."
)


STEPS_VALUES_DESCRIPTION = (
    "kind steps: the entries to write from step 0, 1..256 numbers within -128..128 (a finite sanity bound, not a per-module range: Pitches take Grid pitch units, Gates 1/0, Accents 0/0.5/1, Probabilities 0..1 as observed natively), "
    "stored as 32-bit floats. Not longer than expectedValues; later entries and everything beyond the prefix stay unchanged. Nothing is clamped."
)


KIND_VALUES: tuple[tuple[list[str], dict[str, Any]], ...] = (
    (["boolean"], {"type": "boolean"}),
    (["integer", "enum"], {"type": "integer", "minimum": -(2**31), "maximum": 2**31 - 1}),
    (["float"], {"type": "number"}),
    (["string"], {"type": "string", "maxLength": MAX_STRING_UNITS}),
)
SINGLE_REQUIRED = ["moduleId", "parameterId", "expectedKind"]
ANY: dict[str, Any] = {}


def _closed(required: list[str], properties: dict[str, Any]) -> dict[str, Any]:
    """One allowed shape of the request: exactly these fields (each is typed by the schema's own properties as well)."""
    return {
        "required": required,
        "properties": {"target": ANY, "timeoutMs": ANY, **properties},
        "additionalProperties": False,
    }


def _single_form(kinds: list[str], value_schema: dict[str, Any], written: str = "value") -> dict[str, Any]:
    """The single write of one parameter kind: expectedKind picks the type of expectedValue and of what is written."""
    return _closed(
        [*SINGLE_REQUIRED, "expectedValue", written],
        {
            "moduleId": ANY,
            "parameterId": ANY,
            "expectedKind": {"enum": kinds},
            "expectedValue": value_schema,
            written: value_schema if written == "value" else ANY,
        },
    )


STEPS_FORM = _closed(
    [*SINGLE_REQUIRED, "expectedValues", "values"],
    {
        "moduleId": ANY,
        "parameterId": ANY,
        "expectedKind": {"enum": ["steps"]},
        "expectedValues": ANY,
        "values": ANY,
        "full": ANY,
    },
)
WRITES_FORM = _closed(["writes"], {"writes": ANY})
SINGLE_FORMS = [
    *(_single_form(kinds, value_schema) for kinds, value_schema in KIND_VALUES),
    _single_form(["float"], {"type": "number"}, "display"),
    STEPS_FORM,
]


class GridRawWrite(StrictModel):
    moduleId: Uuid = Field(
        description="Current module instanceId from grid.get; reacquire after reopening the project."
    )
    parameterId: str = Field(
        max_length=MAX_STRING_UNITS,
        description=f"Exact native parameter ID from grid.get, not a name; empty is valid, at most {MAX_STRING_UNITS} UTF-16 code units.",
    )
    model_config = ConfigDict(
        json_schema_extra=cast(
            "JsonDict",
            {
                "oneOf": [
                    {
                        "properties": {
                            "expectedKind": {"enum": kinds},
                            "expectedValue": value_schema,
                            "value": value_schema,
                        }
                    }
                    for kinds, value_schema in KIND_VALUES
                ]
                + [{"properties": {"expectedKind": {"enum": ["steps"]}}}],
            },
        )
    )

    expectedKind: Literal["float", "integer", "enum", "boolean", "string", "steps"] = Field(
        description="Typed kind from a recent grid.get row; checked before any already-matching no-op."
    )
    expectedValue: bool | int | float | StringValue = omitted(
        description="Exact current native raw value from a recent grid.get row, not normalized or display units; stale is rejected (target_changed). Must match expectedKind; no tolerance or coercion. Required except for kind steps."
    )
    expectedValues: Annotated[list[float], Field(min_length=1, max_length=STEP_ENTRIES)] = omitted(
        description=STEPS_EXPECTED_DESCRIPTION
    )
    values: Annotated[
        list[Annotated[float, Field(ge=STEP_MIN, le=STEP_MAX)]], Field(min_length=1, max_length=STEP_ENTRIES)
    ] = omitted(description=STEPS_VALUES_DESCRIPTION)
    full: bool = omitted(description=FULL_DESCRIPTION)
    value: bool | int | float | StringValue = omitted(
        description=f"Requested native raw value: boolean JSON bool; integer/enum signed int32 JSON integer token; float finite JSON number; string (string panels, e.g. Comment text) any text of at most {MAX_STRING_UNITS} UTF-16 units, preserved verbatim, empty allowed. Enum uses an actual listed option value, never an ordinal. Native range/representability are checked before dispatch. Exactly one of value or display."
    )
    display: str = omitted(min_length=1, max_length=64, description=DISPLAY_DESCRIPTION)

    @field_validator("expectedValue", "value", "expectedValues", "values", mode="before")
    @classmethod
    def validate_raw_number(cls, value: Any) -> Any:
        if type(value) is list:
            return value  # steps arrays: items are validated as finite floats by the field type
        if type(value) not in (bool, int, float, str):
            raise ValueError("raw value must be a JSON boolean, number or string, never null")
        if type(value) is str:
            if len(value.encode("utf-16-le", errors="surrogatepass")) > MAX_STRING_UNITS * UTF16_BYTES_PER_UNIT:
                raise ValueError(f"string value must be at most {MAX_STRING_UNITS} UTF-16 code units")
        elif type(value) is not bool:
            try:
                finite = math.isfinite(value)
            except OverflowError:
                finite = False
            if not finite:
                raise ValueError("raw numeric value must be finite and representable as a native double")
        return value

    @model_validator(mode="after")
    def validate_raw_kind(self) -> Self:
        given = self.model_fields_set
        if self.expectedKind == "steps":
            self.check_steps(given)
            return self
        if {"expectedValues", "values", "full"} & given:
            raise ValueError("expectedValues, values and full need expectedKind steps")
        if "expectedValue" not in given:
            raise ValueError("expectedValue is required")
        if len({"value", "display"} & given) != 1:
            raise ValueError("supply exactly one of value (raw) or display (text such as '1.2 kHz')")
        if "display" in given and self.expectedKind != "float":
            raise ValueError("display needs expectedKind float")
        for field in ("expectedValue", "value"):
            if field in given:
                check_value_kind(self.expectedKind, field, getattr(self, field))
        return self

    def check_steps(self, given: set[str]) -> None:
        if {"expectedValue", "value", "display"} & given or not {"expectedValues", "values"} <= given:
            raise ValueError(
                "kind steps takes expectedValues and values (and optional full), not expectedValue, value or display"
            )
        if len(self.values) > len(self.expectedValues):
            raise ValueError("values must not be longer than expectedValues")


def check_value_kind(kind: str, field: str, value: Any) -> None:
    if kind == "string":
        if type(value) is not str:
            raise ValueError(f"{field} must be a JSON string for string kind")
    elif type(value) is str:
        raise ValueError(f"{field} must not be a string for {kind} kind")
    elif kind == "boolean":
        if type(value) is not bool:
            raise ValueError(f"{field} must be a JSON boolean for boolean kind")
    elif kind in ("integer", "enum"):
        if type(value) is not int or not -(2**31) <= value < 2**31:
            raise ValueError(f"{field} must be a signed int32 JSON integer for {kind} kind")
    elif type(value) not in (int, float):
        raise ValueError(f"{field} must be a finite JSON number for float kind")


GRID_WRITE_FIELDS = {"moduleId", "parameterId", "expectedKind"}
WRITE_DETAILS = {"expectedValue", "value", "display", "expectedValues", "values", "full"}


class GridParameterSetArgs(GridDeviceArgs):
    """One write (the five fields) or a same-device batch (writes), never both."""

    model_config = ConfigDict(
        json_schema_extra=cast(
            "JsonDict",
            {
                "oneOf": [
                    *SINGLE_FORMS,
                    WRITES_FORM,
                ]
            },
        )
    )

    moduleId: Uuid = omitted(description="Single form: current module instanceId from grid.get.")
    parameterId: str = omitted(
        max_length=MAX_STRING_UNITS, description="Single form: exact parameterId from grid.get; empty is valid."
    )
    expectedKind: Literal["float", "integer", "enum", "boolean", "string", "steps"] = omitted(
        description="Single form: kind from a recent grid.get row."
    )
    expectedValue: bool | int | float | StringValue = omitted(
        description="Single form: exact current raw value from a recent grid.get row (not for kind steps)."
    )
    expectedValues: Annotated[list[float], Field(min_length=1, max_length=STEP_ENTRIES)] = omitted(
        description="Single form, kind steps: " + STEPS_EXPECTED_DESCRIPTION
    )
    values: Annotated[
        list[Annotated[float, Field(ge=STEP_MIN, le=STEP_MAX)]], Field(min_length=1, max_length=STEP_ENTRIES)
    ] = omitted(description="Single form, kind steps: " + STEPS_VALUES_DESCRIPTION)
    full: bool = omitted(description="Single form, kind steps: " + FULL_DESCRIPTION)
    value: bool | int | float | StringValue = omitted(
        description="Single form: requested native raw value (or give display instead)."
    )
    display: str = omitted(min_length=1, max_length=64, description="Single form: " + DISPLAY_DESCRIPTION)
    writes: Annotated[list[GridRawWrite], Field(min_length=1, max_length=64)] = omitted(
        description="Batch form: 1..64 writes on this device, each a full single write. All CAS checks run first; any failure writes nothing. Then writes dispatch in order and stop at the first failure."
    )

    @model_validator(mode="after")
    def validate_one_form(self) -> Self:
        supplied = GRID_WRITE_FIELDS & self.model_fields_set
        if "writes" in self.model_fields_set:
            if supplied or WRITE_DETAILS & self.model_fields_set:
                raise ValueError("supply either writes or the single-write fields, not both")
            keys = [(item.moduleId.lower(), item.parameterId) for item in self.writes]
            if len(set(keys)) != len(keys):
                raise ValueError("writes must not repeat a moduleId/parameterId pair")
        elif supplied != GRID_WRITE_FIELDS:
            raise ValueError(
                "supply writes, or moduleId, parameterId, expectedKind, expectedValue and one of value or display (kind steps: expectedValues and values)"
            )
        else:
            GridRawWrite.model_validate(
                {key: getattr(self, key) for key in GRID_WRITE_FIELDS | WRITE_DETAILS & self.model_fields_set}
            )
        return self


class GridVoicingFields(StrictModel):
    """Poly Grid device voicing fields; every one optional."""

    voices: int = omitted(ge=1, le=64, description="Voice count 1..64.")
    voiceStacking: int = omitted(ge=1, le=16, description="Voice stacking 1..16.")
    monoMode: Literal["true_mono", "digi_mono"] = omitted(
        description="Stored mono mode: true_mono, or digi_mono (alternating voices). It is what plays when voices is 1."
    )
    retriggerMode: Literal["never", "on_note_on", "always"] = omitted(
        description="Retrigger: never, on_note_on or always."
    )
    notePriority: Literal["last", "high", "low"] = omitted(description="Note priority: last, high or low.")
    legatoGlide: bool = omitted(description="Legato glide.")
    stealSameKey: bool = omitted(description="Steal voices on the same key.")
    allowOverlappingNotes: bool = omitted(description="Allow overlapping notes.")


class GridVoicingSetArgs(GridDeviceArgs):
    expected: GridVoicingFields = Field(
        description="Exact current values from a recent grid.voicing.get, at least for every field in set; stale is rejected (target_changed). Extra fields are additional preconditions."
    )
    set: GridVoicingFields = Field(
        description="New values; at least one field. Unnamed fields stay unchanged (a native change may still move them: see changedUnrequested)."
    )

    @model_validator(mode="after")
    def validate_voicing(self) -> Self:
        want = self.set.model_fields_set
        if not want:
            raise ValueError("set must name at least one voicing field")
        if not want <= self.expected.model_fields_set:
            raise ValueError("expected must also give the current value of every field in set")
        return self


class RemoteSlot(StrictModel):
    parameter: RemoteSlotParameterRef = Field(
        description="A parameter ref owned by a gridModule of the target device (grid.export refs[name] with the parameter id)."
    )
    label: str = omitted(
        max_length=256,
        description="Knob label shown instead of the parameter's own name. Left out: Bitwig's default for a new mapping, an existing label is kept.",
    )


class RemoteDefineArgs(GridDeviceArgs):
    name: str = Field(
        min_length=1,
        max_length=256,
        description="Page name shown in the device's Remote Controls page list; kept verbatim. The page of that name is changed, or created when there is none.",
    )
    slots: Annotated[list[RemoteSlot | None], Field(min_length=1, max_length=REMOTE_PAGE_SLOTS)] = Field(
        description=f"Slot k is entry k (0..{REMOTE_PAGE_SLOTS - 1}): {{parameter, label?}} or null for an empty slot; later slots are left empty."
    )

    @model_validator(mode="after")
    def validate_distinct(self) -> Self:
        pairs = [(s.parameter.owner.id.lower(), s.parameter.id) for s in self.slots if s is not None]
        if len(pairs) != len(set(pairs)):
            raise ValueError("slots must not repeat a module/parameter pair")
        return self


class RemoteDeleteArgs(StatusArgs):
    target: RemotePageRef = Field(
        description="The preset Remote Controls page to delete: its ref from devices.remote.define or devices.remote.get."
    )


DIFF = 'Result is a diff, absent when empty: modulesAdded (full rows with ports, wire them at once), modulesRemoved, modulesMoved, modulesRenamed, connectionsChanged (any cable changed anywhere, including native repair), moduleCount; graphComplete=false with graphUnsupported if coverage was partial. graph="full" returns the whole bounded graph instead.'
LONG = f"timeoutMs up to {LONG_TIMEOUT_MS} (verification reads the whole graph). "
SAFE = "Needs a stopped, nonrecording, nonautomation state, a complete bounded graph of at most 1024 modules, and a device ref target from devices.list. "
VALUE_SAFE = "Needs recording and automation write off (a playing transport is fine), a complete bounded graph of at most 1024 modules, and a device ref target from devices.list. "
REMOTE_DEFINE_DESCRIPTION = (
    "Set one named user Remote Controls page on a Grid device (e.g. a Macros page whose knobs drive Value modules): the page of that name is changed to these slots, or created when there is none. slots: up to "
    + str(REMOTE_PAGE_SLOTS)
    + " entries, slot k = entry k, each {parameter: a parameter ref owned by a gridModule of the target device (grid.export refs, scalar parameters only), label?: knob label} or null for an empty slot. A label left out is Bitwig's default for a new mapping and keeps an existing label. Slots left out are cleared. Defining a page that already holds exactly these slots is already_matched; changing a slot is applied. Pages go in the device's preset-page list, the one the UI's Add New Page uses (stored with the device and its preset; device pages cannot hold Grid module parameters); a same-named device, module or plugin page is target_exists. Each native step (create page, name, map/clear slot) is its own undo step, ungrouped; the result carries the page's ref and reads back every slot. Needs recording and automation write off (a playing transport is fine) and a licensed device. Inspect an unknown outcome with devices.remote.get; never retry it. Version-gated (status detail full, grid.remotePages)."
)
REMOTE_DELETE_DESCRIPTION = "Delete one preset Remote Controls page by its ref (one devices.remote.define made, or one added by hand with Add New Page). Device, module and plugin pages are refused (unsupported_scope); a page that is gone is target_not_found, another page at that index target_changed. One native undo step; the result verifies the page is gone and lists the remaining page names. Same safety and version gate as devices.remote.define."
UNK = "Never retry an unknown outcome: inspect with grid.export. A native setter/callback failure, or an insertion whose later graph check disagreed, holds topology edits on that one device only (other devices and project.save keep working): its writes are refused unresolved until you inspect with grid.export and ack its id grid:<device> (status holds list it; ack re-checks the graph first); no Bitwig restart."
GRID_CATALOG_DESCRIPTION = "Read native Grid MODULE type UUIDs and names, not devices or module instances. Version/hash gated. query (max 128 UTF-16 units) ignores case, spaces, hyphens and punctuation and every word must match inside the name: 'lowpass' and 'low pass' find 'Low-pass', 'Low-pass LD', 'Low-pass MG'. When nothing matches, suggestions lists up to five nearest names. offset 0..100000, limit 1..256 (default 64), deterministic ordering. The native catalog has no category or tag, so query matches names only (e.g. 'Low-pass', not 'Filter'). Inspect window completeness; catalog presence does not prove graph or parameter coverage."
GRID_GET_DESCRIPTION = "Read a Grid graph without selecting or changing the device. Take the target device ref from devices.list. Use includeParameters=false for layout, wiring and naming. moduleIds (1..256 instanceIds) returns only those modules with their parameters, so one module's parameterId/kind/value/range needs no paging; absent ids are listed in missingModuleIds. moduleOffset 0..100000, moduleLimit 1..256 (default 64). Rows have instanceId (current UUID, changes on project reopen), typeId, localKey, x/y, width/height, ports (key, name, connected) and parameters. A parameter row's kind and value are a valid expectedKind/expectedValue for grid.parameters.set while still current. Each module row carries its gridModule ref. diagnostics=true adds native modelClass/path and coverage notes. A kind:string row is a string panel (e.g. Comment text); write it with grid.parameters.set expectedKind string. A kind:steps row is a step table (Steps, Pitches, Gates, Probabilities, Accents): values is the first stepCount entries of an arrayLength-entry array (full:true returns up to 256), minimum/maximum -128..128 is only a sanity bound (observed natively: Pitches in Grid pitch units, Gates 1/0, Accents 0/0.5/1, Probabilities 0..1); write it with expectedKind steps. Child models that carry no user data (engine feeds, indicators) are not listed: the module row has internalChildren and they never make the read incomplete. Inspect window, complete (false for structure-only or filtered reads) and unsupported; pages are independent observations, with no native revision."
GRID_PARAMETERS_SET_DESCRIPTION = 'Set Grid parameters in the native RAW domain (not normalized): one write, or a batch on one device. Single: moduleId, parameterId, expectedKind, expectedValue, and value. Batch: writes=[{moduleId, parameterId, expectedKind, expectedValue, value}], 1..64, no repeated pair. Pitch-unit parameters are raw MIDI notes (Low-pass CUTOFF 84 shows "1.05 kHz"). Instead of value, a float write may give display ("1.2 kHz", "440 Hz", "50 %", "-12 dB") for numericUnit hertz, percent or decibel only: the host converts it after confirming the unit scale against the row\'s current raw/display pair, any other unit is rejected with unsupported_unit, and the result\'s displayMismatch compares the post-write display with your text (never rewritten). expectedKind/expectedValue stay required. Take the ids, kind and value from a recent grid.get; a stale expectation is rejected (target_changed), even for a no-op. Batch: every CAS runs first and any failure writes nothing (error names each failing entry); then writes dispatch in order and stop at the first entry that is neither applied nor already_matched; later entries are not_applied with reason not_reached. Single results and each batch entry carry the post-write display string (e.g. "2.09 kHz") when observable, so a raw value\'s meaning needs no extra read. Batch result: writes[] with the outcome of every entry (applied, already_matched, not_applied, unknown) and a reason when it did not succeed, written, and outcome = the worst entry; on unknown inspect state, never retry. Boolean takes a JSON bool; integer/enum an int32 token (enum is a listed option value, not an ordinal); float a finite number; range is checked, nothing is clamped. String (string panels such as Comment text): expectedKind string, JSON strings of at most 1024 UTF-16 units, kept verbatim (empty and newlines allowed), compare-and-set against the current text. A Label module shows the module name, so rename the module through grid.apply. Needs recording and automation write off (a playing transport is fine). Boolean panels also move their linked bipolar value, which the result reports. Step tables (kind steps, Steps/Pitches/Gates/Probabilities/Accents): expectedKind steps, expectedValues = the live entries from step 0 as read by grid.get (normally the first stepCount, or all 256 with full:true; the CAS window is its own length), values = 1..256 entries within -128..128 written from step 0 (never longer than expectedValues; shorter leaves the rest; nothing beyond the prefix changes; 32-bit floats; nothing clamped). Whole-array write with exact readback (previousValues, requestedValues in the result); valid in batch entries. A Probabilities table is one write per row, e.g. a transition matrix as one batch.'
GRID_VOICING_GET_DESCRIPTION = "Read a Grid device's voicing (Poly Grid only): voices 1..64, voiceStacking 1..16, monoMode true_mono|digi_mono (the stored mode, what plays when voices is 1), retriggerMode never|on_note_on|always, notePriority last|high|low, legatoGlide, stealSameKey, allowOverlappingNotes, plus mode (poly when voices > 1, else the stored monoMode). Note Grid and FX Grid have no voicing model: supported:false with a reason. Same device target as grid.get. The editContext observation is omitted unless detail=full."
GRID_VOICING_SET_DESCRIPTION = (
    "Change a Poly Grid device's voicing: expected = exact current values from grid.voicing.get (at least every field in set; extra fields are more preconditions), set = new values (at least one field). Stale expectations are rejected (target_changed) before dispatch, even for a no-op; nothing is clamped. Fields are written in the order voices, voiceStacking, monoMode, retriggerMode, notePriority, legatoGlide, stealSameKey, allowOverlappingNotes, then everything read back exactly; changedUnrequested lists fields the native change moved besides those asked for. Digi mono is monoMode digi_mono with voices 1. Note Grid / FX Grid reject unsupported_scope. "
    + VALUE_SAFE
    + UNK
)
GRID_MODULES_INSERT_DESCRIPTION = (
    "Insert one MODULE type at absolute native anchor x/y (signed int32 tokens; layout may reflow other modules), or a batch on one device. Identify the type by typeId (from grid.catalog) or typeName, a catalog name matched exact string first, then exact ignoring case, then loosely ignoring case, spaces and hyphens ('low pass' = 'Low-pass'); symbol-only names ('<', '≠') and arrows ('Slope ↗') match exactly; a name matching zero or several entries is rejected (invalid_params) with candidates (use typeId when ambiguous). Single: typeId|typeName, x, y; returns moduleId, requestedPosition/actualPosition (native reflow may land it further right on the same row) and a diff. Batch: modules=[{typeId|typeName, x, y}]: every type and limit is resolved first (any failure inserts nothing); entries then insert in order, each settling before the next (a later insert can push earlier modules right; per-entry actualPosition is taken from the final graph), stopping at the first entry that is not applied (a footprint-dependent rejection after loading is unknown); later entries are not_applied with reason not_reached. When too little of the timeout remains for another load, the next entry is not_applied with reason deadline and the rest not_reached: send those again in a new call. Batch result: modules[] (outcome, reason when not applied, typeId, moduleId, requested/actualPosition), written, outcome = the worst entry, and one diff for the whole batch (none after unknown: inspect with grid.get). "
    + DIFF
    + " Asynchronous: loaderDispatched and placementDispatched are separate and loading is not placement; a timeout is not cancellation and native loading may outlive it. "
    + SAFE
    + LONG
    + UNK
)
GRID_MODULES_SET_DESCRIPTION = (
    "Set one module's custom name, the module taken by its moduleId from grid.get. The expectedCustomName is the exact raw customName from grid.get, not the effective name or typeName, and must match even for a no-op; both names are strings of at most 1024 UTF-16 units, empty allowed, kept verbatim; customName='' resets the override. Returns previous/actual raw and effective names and a diff. "
    + DIFF
    + " "
    + SAFE
    + LONG
    + UNK
)
GRID_MODULES_MOVE_DESCRIPTION = (
    "Move one module (moduleId from grid.get) to absolute native anchor x/y (signed int32 tokens, not deltas or pixels). expectedX/expectedY must match its current anchor even for a no-op. Native collision handling and reflow may push it or other modules right on their rows: inspect actualPosition and the diff; a row change or leftward move is unknown. "
    + DIFF
    + " Occupied y-span at most 65536. "
    + SAFE
    + LONG
    + UNK
)
GRID_MODULES_DELETE_DESCRIPTION = (
    "Delete one module (moduleId from grid.get), not clear-all and not missing-is-success. Native bypass/reconnection may rewire cables anywhere: inspect the diff. "
    + DIFF
    + " "
    + SAFE
    + LONG
    + UNK
)
GRID_CONNECTIONS_SET_DESCRIPTION = (
    "Set one input's source, or a batch on one device. Single: targetModuleId, targetPortKey, expectedSource, source. Batch: connections=[{targetModuleId, targetPortKey, expectedSource, source}], 1..32, distinct inputs (e.g. insert a module into a chain in one call). Ids and port keys are instanceIds and keys from grid.get (or a modulesAdded row), not names, indices or type UUIDs. expectedSource and source are both required: each is null or exactly {moduleId,portKey}; expectedSource must match the current source even for a no-op; source=null disconnects. Keys may be empty (max 1024 UTF-16 units). Native repair may reject the link or alter other cables: inspect actualSource and connectionsChanged; when actualSource differs from the requested source the outcome is unknown with reason readback_mismatch. Batch: every expectedSource and port is checked first and any failure writes nothing (error names each failing entry); then entries apply in order and stop at the first entry that is neither applied nor already_matched, later entries are not_applied with reason not_reached. Batch result: connections[] (outcome, reason when it did not succeed, previousSource, actualSource), written, outcome = the worst entry, and one diff for the whole batch (none after a failed dispatch: inspect with grid.get). "
    + DIFF
    + " "
    + SAFE
    + LONG
    + UNK
)


SPECS: dict[str, ToolSpec] = {
    "grid.catalog": ToolSpec(
        GridCatalogArgs,
        GRID_CATALOG_DESCRIPTION,
        True,
        expose=False,
        examples=(
            {},
            {"query": "osc", "offset": 0, "limit": 32},
        ),
    ),
    "grid.get": ToolSpec(
        GridGetArgs,
        GRID_GET_DESCRIPTION,
        True,
        expose=False,
        examples=(
            {**TARGET, "moduleOffset": 0, "moduleLimit": 32, "includeParameters": False},
            {**TARGET, "moduleIds": ["abcdef01-2345-6789-abcd-0123456789ab"]},
        ),
        records="modules",
    ),
    "grid.parameters.set": ToolSpec(
        GridParameterSetArgs,
        GRID_PARAMETERS_SET_DESCRIPTION,
        False,
        examples=(
            {
                **TARGET,
                "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "parameterId": "from-grid.get",
                "expectedKind": "float",
                "expectedValue": 0.25,
                "value": 0.5,
            },
            {
                **TARGET,
                "writes": [
                    {
                        "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                        "parameterId": "from-grid.get",
                        "expectedKind": "float",
                        "expectedValue": 84,
                        "display": "1.2 kHz",
                    },
                    {
                        "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                        "parameterId": "from-grid.get-2",
                        "expectedKind": "boolean",
                        "expectedValue": False,
                        "value": True,
                    },
                    {
                        "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                        "parameterId": "from-grid.get-3",
                        "expectedKind": "string",
                        "expectedValue": "",
                        "value": "Pad section",
                    },
                ],
            },
            {
                **TARGET,
                "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "parameterId": "from-grid.get",
                "expectedKind": "string",
                "expectedValue": "",
                "value": "Pad section",
            },
            {
                **TARGET,
                "writes": [
                    {
                        "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                        "parameterId": "from-grid.get",
                        "expectedKind": "string",
                        "expectedValue": "",
                        "value": "Pad section",
                    }
                ],
            },
            {
                **TARGET,
                "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "parameterId": "from-grid.get",
                "expectedKind": "steps",
                "expectedValues": [0.0, 0.0, 0.0, 0.0],
                "values": [0.5, 0.25],
            },
            {
                **TARGET,
                "writes": [
                    {
                        "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                        "parameterId": "from-grid.get",
                        "expectedKind": "steps",
                        "expectedValues": [0.0, 0.0, 0.0, 0.0],
                        "values": [1.0, 0.0, 0.5, 0.0],
                    }
                ],
            },
        ),
        requires=PROJECT_READY,
        expose=False,
    ),
    "devices.remote.define": ToolSpec(
        RemoteDefineArgs,
        REMOTE_DEFINE_DESCRIPTION,
        False,
        examples=(
            {
                **TARGET,
                "name": "Macros",
                "slots": [
                    {
                        "parameter": {
                            "kind": "parameter",
                            "owner": {
                                "kind": "gridModule",
                                "device": DEVICE,
                                "id": "abcdef01-2345-6789-abcd-0123456789ab",
                                "name": "M",
                            },
                            "id": "VALUE",
                        }
                    },
                    None,
                ],
            },
        ),
        annotations=SET,
        summary="Set a Remote Controls page of a Grid device from parameter refs.",
    ),
    "devices.remote.delete": ToolSpec(
        RemoteDeleteArgs,
        REMOTE_DELETE_DESCRIPTION,
        False,
        examples=({"target": {"kind": "remotePage", "device": DEVICE, "index": 1, "name": "Macros"}},),
    ),
    "grid.voicing.get": ToolSpec(
        GridVoicingGetArgs, GRID_VOICING_GET_DESCRIPTION, True, examples=(TARGET,), expose=False
    ),
    "grid.voicing.set": ToolSpec(
        GridVoicingSetArgs,
        GRID_VOICING_SET_DESCRIPTION,
        False,
        examples=(
            {**TARGET, "expected": {"voices": 1, "voiceStacking": 1}, "set": {"voices": 8, "voiceStacking": 2}},
            {**TARGET, "expected": {"voices": 1, "monoMode": "true_mono"}, "set": {"monoMode": "digi_mono"}},
        ),
        requires=PROJECT_READY,
        expose=False,
    ),
    "grid.modules.insert": ToolSpec(
        GridModuleInsertArgs,
        GRID_MODULES_INSERT_DESCRIPTION,
        False,
        examples=(
            {**TARGET, "typeName": "Low-pass", "x": 4, "y": 2},
            {
                **TARGET,
                "modules": [
                    {"typeId": "abcdef01-2345-6789-abcd-0123456789ab", "x": 4, "y": 2},
                    {"typeName": "Low-pass", "x": 8, "y": 2},
                ],
            },
        ),
        requires=PROJECT_READY,
        expose=False,
    ),
    "grid.modules.set": ToolSpec(
        GridModuleSetArgs,
        GRID_MODULES_SET_DESCRIPTION,
        False,
        examples=(
            {
                **TARGET,
                "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "expectedCustomName": "",
                "customName": "Lead = \u03b1",
            },
            {
                **TARGET,
                "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "expectedCustomName": "Lead = \u03b1",
                "customName": "",
            },
        ),
        requires=PROJECT_READY,
        expose=False,
    ),
    "grid.modules.move": ToolSpec(
        GridModuleMoveArgs,
        GRID_MODULES_MOVE_DESCRIPTION,
        False,
        examples=(
            {
                **TARGET,
                "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "expectedX": 0,
                "expectedY": 0,
                "x": 4,
                "y": 2,
            },
        ),
        requires=PROJECT_READY,
        expose=False,
    ),
    "grid.modules.delete": ToolSpec(
        GridModuleEditArgs,
        GRID_MODULES_DELETE_DESCRIPTION,
        False,
        examples=({**TARGET, "moduleId": "abcdef01-2345-6789-abcd-0123456789ab"},),
        requires=PROJECT_READY,
        expose=False,
    ),
    "grid.connections.set": ToolSpec(
        GridConnectionSetArgs,
        GRID_CONNECTIONS_SET_DESCRIPTION,
        False,
        examples=(
            {
                **TARGET,
                "targetModuleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "targetPortKey": "from-grid.get-input",
                "expectedSource": None,
                "source": {"moduleId": "abcdef02-2345-6789-abcd-0123456789ab", "portKey": "from-grid.get-output"},
            },
            {
                **TARGET,
                "targetModuleId": "abcdef01-2345-6789-abcd-0123456789ab",
                "targetPortKey": "from-grid.get-input",
                "expectedSource": {
                    "moduleId": "abcdef02-2345-6789-abcd-0123456789ab",
                    "portKey": "from-grid.get-output",
                },
                "source": None,
            },
            {
                **TARGET,
                "connections": [
                    {
                        "targetModuleId": "abcdef01-2345-6789-abcd-0123456789ab",
                        "targetPortKey": "from-grid.get-input",
                        "expectedSource": {
                            "moduleId": "abcdef02-2345-6789-abcd-0123456789ab",
                            "portKey": "from-grid.get-output",
                        },
                        "source": {
                            "moduleId": "abcdef03-2345-6789-abcd-0123456789ab",
                            "portKey": "from-grid.get-output",
                        },
                    },
                    {
                        "targetModuleId": "abcdef03-2345-6789-abcd-0123456789ab",
                        "targetPortKey": "from-grid.get-input",
                        "expectedSource": None,
                        "source": {
                            "moduleId": "abcdef02-2345-6789-abcd-0123456789ab",
                            "portKey": "from-grid.get-output",
                        },
                    },
                ],
            },
        ),
        requires=PROJECT_READY,
        expose=False,
    ),
}
