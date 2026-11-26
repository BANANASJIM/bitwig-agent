from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    MAX_STRING_UNITS,
    TARGET,
    TRACK,
    StatusArgs,
    StrictModel,
    ToolSpec,
    Uuid,
    omitted,
)
from bitwig_agent.protocol.devices import DeviceParent
from bitwig_agent.protocol.grid import GridRawWrite


class ModulatorListArgs(StatusArgs):
    parent: DeviceParent
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=64, ge=1, le=128)


class ModulatorParameterGetArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: Uuid = Field(
        description="Current modulator UUID from modulators.list, not its typeId, name or index; reacquire after reopening or identity changes."
    )
    parameterId: str = Field(
        max_length=MAX_STRING_UNITS,
        description=f"Exact addressable parameterId from modulators.list, not a display name; empty is valid when unique, at most {MAX_STRING_UNITS} UTF-16 code units.",
    )


MODULATOR_ID = "Current modulator UUID from modulators.get (or modulators.list), not its typeId, name or index."
TransferFunction = Literal["linear", "rectify_pos", "rectify_neg", "abs_linear", "inv_abs_linear", "cubic", "inv_cubic"]
ModulationAmount = Annotated[float, Field(ge=-1, le=1)]
MODULATOR_COUNT = 4096


class ModulatorGetArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: Uuid = omitted(
        description="Only this modulator; absent returns every modulator on the device (at most 64)."
    )


class ModulatorInsertArgs(StatusArgs):
    parent: DeviceParent
    expectedCount: int = Field(
        ge=0,
        le=MODULATOR_COUNT,
        description="Current modulator count from modulators.get; a different count rejects not_applied.",
    )
    typeName: str = omitted(
        min_length=1,
        max_length=128,
        description="Modulator type name from modulators.catalog, e.g. 'Random' or 'Beat LFO'; exactly one of typeName or typeId.",
    )
    typeId: Uuid = omitted(
        description="Native modulator type UUID (the typeId of a modulators.get row); exactly one of typeName or typeId."
    )

    @model_validator(mode="after")
    def validate_one_type(self) -> Self:
        if len({"typeName", "typeId"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of typeName or typeId")
        return self


class ModulatorDeleteArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: Uuid = Field(description=MODULATOR_ID)
    expectedCount: int = Field(ge=1, le=MODULATOR_COUNT, description="Current modulator count from modulators.get.")


class ModulatorParameterSetArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: Uuid = Field(description=MODULATOR_ID)
    parameterId: str = Field(
        max_length=MAX_STRING_UNITS,
        description="Exact parameterId of a modulators.get parameters row (not an outputId).",
    )
    expectedKind: Literal["float", "integer", "enum", "boolean"] = Field(description="kind from that row.")
    expectedValue: bool | int | float = Field(
        description="Exact current raw value from that row; stale rejects not_applied."
    )
    value: bool | int | float = omitted(
        description="Requested raw value in the row's minimum..maximum (enum: a listed option value). Exactly one of value or display."
    )
    display: str = omitted(
        min_length=1,
        max_length=64,
        description="Float only, instead of value: text with a unit such as '2 Hz' or '50 %'.",
    )

    @model_validator(mode="after")
    def validate_raw(self) -> Self:
        details = {
            key: getattr(self, key) for key in ("expectedValue", "value", "display") if key in self.model_fields_set
        }
        GridRawWrite.model_validate(
            {
                "moduleId": self.modulatorId,
                "parameterId": self.parameterId,
                "expectedKind": self.expectedKind,
                **details,
            }
        )
        return self


class GridControlTarget(StrictModel):
    moduleId: Uuid = Field(
        description="Grid module instanceId: the id of its grid.export refs entry on this same device."
    )
    parameterId: str = Field(
        max_length=MAX_STRING_UNITS,
        description="Exact parameterId of that module's control, e.g. DECAY (grid.export with includeParams 'all', or grid.describe).",
    )


class ModulatorControlTarget(StrictModel):
    modulatorId: Uuid = Field(description="Another modulator on this same device (modulators.get).")
    parameterId: str = Field(max_length=MAX_STRING_UNITS, description="Exact parameterId of that modulator's control.")


MAP_TARGET = "The control to modulate: {moduleId, parameterId} for a Grid module control, or {modulatorId, parameterId} for another modulator's control, both on this device."


class ModulatorMappingArgs(StatusArgs):
    parent: DeviceParent
    modulatorId: Uuid = Field(description="Source modulator. " + MODULATOR_ID)
    outputId: str = omitted(
        max_length=MAX_STRING_UNITS,
        description="Source output id from modulators.get outputs; may be omitted when the modulator has exactly one output.",
    )
    target: GridControlTarget | ModulatorControlTarget = Field(description=MAP_TARGET)


class ModulatorUnmapArgs(ModulatorMappingArgs):
    expectedAmount: ModulationAmount = Field(description="Exact current amount of the mapping, from modulators.get.")


class ModulatorMapArgs(ModulatorMappingArgs):
    expectedAmount: ModulationAmount | None = Field(
        description="null when this output does not map to target yet (creates it); else the exact current amount (changes it)."
    )
    amount: ModulationAmount = Field(
        description="Modulation amount -1..1 (negative inverts). Not clamped: beyond the target's range rejects."
    )
    transfer: TransferFunction = omitted(
        description="New mappings only (default linear): linear, rectify_pos/rectify_neg (keep one half), abs_linear, inv_abs_linear, cubic, inv_cubic. Polarity itself is a modulator parameter (e.g. LFO bipolar)."
    )
    enabled: bool = omitted(description="New mappings only (default true).")


class ModulatorCatalogArgs(StatusArgs):
    query: str = omitted(
        min_length=1,
        max_length=256,
        description="Keywords ranked against names, categories, descriptions and keywords; absent lists all.",
    )
    lang: Literal["en", "de", "es", "fr", "ja", "ko", "zh"] = omitted(
        description="Documentation language (default en)."
    )
    installationRoot: str = omitted(
        min_length=1,
        max_length=4096,
        description="Bitwig install directory; default $BITWIG_INSTALLATION_ROOT, the install of $BITWIG_API_JAR, then /opt/bitwig-studio.",
    )


class ModulatorDescribeArgs(ModulatorCatalogArgs):
    type: str = omitted(
        min_length=1, max_length=128, description="Modulator type name, e.g. 'Beat LFO'; exactly one of type or query."
    )

    @model_validator(mode="after")
    def validate_one_selector(self) -> Self:
        if len({"type", "query"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or query")
        return self


MODULATORS_CAPABILITIES_DESCRIPTION = "Inspect independent primary-modulator discovery and native string-panel read bindings under the exact Bitwig version/loaded-JAR hash gate; edit reports the modulators.get/insert/delete/parameters.set/map/unmap bindings and writeAvailable mirrors edit.available. nativeVerification is not_established_by_binding. Binding availability does not establish reachable instance, DSP or native acceptance."
MODULATORS_LIST_DESCRIPTION = "Inspect primary modulators on one explicit root device without selection, loading contents or reading values. Discover parent trackId with tracks.list and deviceIndex/exact expectedDeviceName with devices.list. offset 0..100000, limit 1..128 (default 64). Rows distinguish current modulatorId, typeId, raw names and exact parameterId descriptors; unsupported children remain explicit and unavailable contents are metadata-only. No secondary/nested modulators, mappings or complete-family coverage. Pages are independent observations, not a revision-consistent cursor; parent names are checks, not durable identity."
MODULATORS_PARAMETERS_GET_DESCRIPTION = "Read one exact native string panel on a primary modulator of an explicit root device, without selection, mutation or content loading. Use fresh modulatorId and exact addressable parameterId from modulators.list, never names, typeId or index aliases; example UUIDs are placeholders. Reacquire current IDs after reopen or identity changes. Empty parameterId is valid when unique; IDs and raw values are bounded to 1024 UTF-16 units, never truncated. Empty strings, whitespace and Unicode are preserved; kind:string/controlFamily:string_panel with null labels/shortLabel/display. Other families reject unsupported_scope. readContext observes owning-project/context lifecycle, not active-project identity, write authorization or atomic snapshot guarantees."
MODULATORS_CATALOG_DESCRIPTION = "List the device modulators Bitwig can insert (the device Modulators browser), each with name, category and Bitwig's own one-line description, read offline from the local install (client-side; no host call, nothing bundled). query ranks by category/name/keywords/description, e.g. 'random', 'envelope', 'sequence'. Insert one with modulators.insert typeName=<name>."
MODULATORS_DESCRIBE_DESCRIPTION = "Bitwig's own in-app help for one modulator type, read offline (client-side): description, category, keywords and per-control help (docKey, title, description) plus shared modulator help. Live kinds, ranges and values are not in these files: read them with modulators.get after inserting."
MODULATORS_GET_DESCRIPTION = "Read every device modulator on one explicit root device (or one with modulatorId) in a single call: modulatorId, typeId, name, index, x/y, typed parameters [{parameterId, name, kind, value, minimum, maximum, options, display}] and outputs [{outputId, mappings [{target {kind grid_module (moduleId, parameterId) | modulator (modulatorId, parameterId) | device | external, path}, amount, transfer, enabled, polyphonic, scaledByAnotherSource}]}]. Discover parent with tracks.list/devices.list. complete=false lists unsupported controls; contentsAvailable=false means the modulator was never opened. No selection or mutation."
MODULATORS_INSERT_DESCRIPTION = "Add one device modulator (typeName from modulators.catalog, e.g. Random, Steps, Beat LFO, ADSR) below the existing ones, via Bitwig's own modulator-browser insertion. expectedCount = current count from modulators.get. Returns the new row (modulatorId, outputs, parameters) after verifying membership and type. Needs a stopped, nonrecording, nonautomation transport. Never retry an unknown outcome: inspect modulators.get."
MODULATORS_DELETE_DESCRIPTION = "Delete one device modulator (and its mappings) with Bitwig's native undoable delete. expectedCount = current count. Membership is verified afterwards. Stopped transport required; never retry an unknown outcome."
MODULATORS_PARAMETERS_SET_DESCRIPTION = "Set one modulator control in the native RAW domain with compare-and-set: expectedKind/expectedValue from a fresh modulators.get parameters row, then value within minimum..maximum (enum: a listed option value) or display text with a unit for floats. Exact readback; stale rejects not_applied, a lost write is unknown. Needs recording and automation write off; a playing transport is fine."
MODULATORS_MAP_DESCRIPTION = "Route a modulator output to a control on the same device, e.g. a Random modulator to an AD module's DECAY: target {moduleId, parameterId} (grid.export) or {modulatorId, parameterId} (another modulator). expectedAmount null creates the mapping (transfer default linear, enabled default true); a number changes an existing mapping's amount only. amount -1..1, not clamped. outputId may be omitted for single-output modulators. Uses Bitwig's undoable modulation add; verified by exact mapping readback. Use it for Grid parameters without input ports (envelope times, step counts, drive, pulse width). Stopped transport required; never retry an unknown outcome."
MODULATORS_UNMAP_DESCRIPTION = "Remove one modulation mapping (Bitwig's undoable Clear modulation). expectedAmount = its exact current amount from modulators.get. Verified by readback. Stopped transport required."


SPECS: dict[str, ToolSpec] = {
    "modulators.capabilities": ToolSpec(StatusArgs, MODULATORS_CAPABILITIES_DESCRIPTION, True),
    "modulators.list": ToolSpec(
        ModulatorListArgs,
        MODULATORS_LIST_DESCRIPTION,
        True,
        examples=(
            {"parent": {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Polymer"}, "offset": 0, "limit": 64},
        ),
        records="modulators",
    ),
    "modulators.parameters.get": ToolSpec(
        ModulatorParameterGetArgs,
        MODULATORS_PARAMETERS_GET_DESCRIPTION,
        True,
        examples=(
            {
                "parent": {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Polymer"},
                "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
                "parameterId": "LABEL",
            },
        ),
    ),
    "modulators.catalog": ToolSpec(
        ModulatorCatalogArgs,
        MODULATORS_CATALOG_DESCRIPTION,
        True,
        examples=(
            {},
            {"query": "random step sequence"},
        ),
        host=False,
    ),
    "modulators.describe": ToolSpec(
        ModulatorDescribeArgs,
        MODULATORS_DESCRIBE_DESCRIPTION,
        True,
        examples=(
            {"type": "Beat LFO"},
            {"query": "envelope follower"},
        ),
        host=False,
    ),
    "modulators.get": ToolSpec(
        ModulatorGetArgs,
        MODULATORS_GET_DESCRIPTION,
        True,
        examples=(
            {"parent": TARGET},
            {"parent": TARGET, "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab"},
        ),
        summary="Read every device modulator of one root device (or one by modulatorId): parameters, outputs and their mappings (target, amount, enabled). Discover parent with tracks.list and devices.list. complete=false lists unsupported controls.",
    ),
    "modulators.insert": ToolSpec(
        ModulatorInsertArgs,
        MODULATORS_INSERT_DESCRIPTION,
        False,
        examples=(
            {"parent": TARGET, "expectedCount": 0, "typeName": "Random"},
            {"parent": TARGET, "expectedCount": 1, "typeId": "bf29a7b0-91dc-4851-8a94-c63f358f3cda"},
        ),
    ),
    "modulators.delete": ToolSpec(
        ModulatorDeleteArgs,
        MODULATORS_DELETE_DESCRIPTION,
        False,
        examples=({"parent": TARGET, "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab", "expectedCount": 1},),
    ),
    "modulators.parameters.set": ToolSpec(
        ModulatorParameterSetArgs,
        MODULATORS_PARAMETERS_SET_DESCRIPTION,
        False,
        examples=(
            {
                "parent": TARGET,
                "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
                "parameterId": "from-modulators.get",
                "expectedKind": "float",
                "expectedValue": 0.5,
                "value": 0.25,
            },
            {
                "parent": TARGET,
                "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
                "parameterId": "from-modulators.get",
                "expectedKind": "float",
                "expectedValue": 0.5,
                "display": "2 Hz",
            },
        ),
    ),
    "modulators.map": ToolSpec(
        ModulatorMapArgs,
        MODULATORS_MAP_DESCRIPTION,
        False,
        examples=(
            {
                "parent": TARGET,
                "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
                "target": {"moduleId": "abcdef02-2345-6789-abcd-0123456789ab", "parameterId": "DECAY"},
                "expectedAmount": None,
                "amount": 0.5,
            },
            {
                "parent": TARGET,
                "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
                "outputId": "from-modulators.get",
                "target": {"modulatorId": "abcdef03-2345-6789-abcd-0123456789ab", "parameterId": "from-modulators.get"},
                "expectedAmount": 0.5,
                "amount": -0.25,
            },
        ),
        summary="Route a modulator output to a control on the same device: target {moduleId|modulatorId, parameterId}. expectedAmount null creates the mapping, a number changes its amount (-1..1). Needs a stopped transport.",
    ),
    "modulators.unmap": ToolSpec(
        ModulatorUnmapArgs,
        MODULATORS_UNMAP_DESCRIPTION,
        False,
        examples=(
            {
                "parent": TARGET,
                "modulatorId": "abcdef01-2345-6789-abcd-0123456789ab",
                "target": {"moduleId": "abcdef02-2345-6789-abcd-0123456789ab", "parameterId": "DECAY"},
                "expectedAmount": 0.5,
            },
        ),
    ),
}
