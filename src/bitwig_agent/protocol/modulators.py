from __future__ import annotations

from typing import Annotated, Any, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import STOPPED, StatusArgs, StrictModel, ToolSpec, Uuid, omitted
from bitwig_agent.protocol.parameters import InitialParams
from bitwig_agent.protocol.refs import DeviceParameterRef, DeviceRef, MappingParameterRef, ModulatorRef
from bitwig_agent.protocol.values import Expect

TransferFunction = Literal["linear", "rectify_pos", "rectify_neg", "abs_linear", "inv_abs_linear", "cubic", "inv_cubic"]
ModulationAmount = Annotated[float, Field(ge=-1, le=1)]
MAX_INITIAL_MAPPINGS = 64
OUTPUT_ID = "Source output id from modulators.list; may be omitted when the modulator has exactly one output."
AMOUNT = "Modulation amount -1..1 (negative inverts). Not clamped: beyond the target's range rejects."
TRANSFER = "linear, rectify_pos/rectify_neg (keep one half), abs_linear, inv_abs_linear, cubic, inv_cubic. Omitted keeps an existing mapping's transfer; a new mapping gets linear."
ENABLED = "Omitted keeps an existing mapping's state; a new mapping is enabled."
TARGET = "A parameter ref on the same device: one of the device itself (parameters.list), of a Grid module (grid.export) or of a modulator (modulators.list)."


def mapped_device(target: Any) -> Any:
    """The device a mapping target is on: a device parameter's own device, else its module's or modulator's."""
    return target.owner if isinstance(target, DeviceParameterRef) else target.owner.device


class ModulatorsListArgs(StatusArgs):
    target: DeviceRef = Field(description="The root device whose modulators to read.")
    offset: int = Field(default=0, ge=0, le=100000)
    limit: int = Field(default=64, ge=1, le=128)


class ModulatorsGetArgs(StatusArgs):
    target: ModulatorRef


class ModulatorsInsertArgs(StatusArgs):
    to: DeviceRef = Field(description="The root device to add a modulator to; it is appended after the others.")
    typeName: str = omitted(
        min_length=1,
        max_length=128,
        description="Modulator type name from modulators.describe, e.g. 'Random' or 'Beat LFO'; exactly one of typeName or typeId.",
    )
    typeId: Uuid = omitted(
        description="Native modulator type UUID (the typeId of a modulators.list row); exactly one of typeName or typeId."
    )

    @model_validator(mode="after")
    def validate_one_type(self) -> Self:
        if len({"typeName", "typeId"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of typeName or typeId")
        return self


class InitialMapping(StrictModel):
    """A mapping from the modulator a modulators.insert creates, set with modulators.map right after the insert."""

    target: MappingParameterRef = Field(description=TARGET)
    amount: ModulationAmount = Field(description=AMOUNT)
    outputId: str = omitted(max_length=1024, description=OUTPUT_ID)
    transfer: TransferFunction = omitted(description=TRANSFER)
    enabled: bool = omitted(description=ENABLED)


class ModulatorsInsertConfigureArgs(ModulatorsInsertArgs):
    params: InitialParams = omitted(
        description="Initial control values by name (as modulators.get names them): the raw value, {display} or {label}, set with parameters.set right after the insert."
    )
    map: list[InitialMapping] = omitted(
        min_length=1, max_length=MAX_INITIAL_MAPPINGS, description="Mappings from the new modulator, made after params."
    )

    @model_validator(mode="after")
    def validate_map_device(self) -> Self:
        for i, mapping in enumerate(self.map or ()):
            if mapped_device(mapping.target) != self.to:
                raise ValueError(f"map[{i}].target must be a parameter on the device `to`")
        return self


class ModulatorsDeleteArgs(StatusArgs):
    target: ModulatorRef


class MappingExpect(Expect):
    amount: ModulationAmount


class ModulatorsUnmapArgs(StatusArgs):
    source: ModulatorRef = Field(description="The modulator whose output is mapped.")
    outputId: str = omitted(max_length=1024, description=OUTPUT_ID)
    target: MappingParameterRef = Field(description=TARGET)
    expect: MappingExpect = omitted(
        description="Compares the current mapping amount first; a different one writes nothing."
    )

    @model_validator(mode="after")
    def validate_same_device(self) -> Self:
        if mapped_device(self.target) != self.source.device:
            raise ValueError("source and target must be on the same device")
        return self


class ModulatorsMapArgs(ModulatorsUnmapArgs):
    amount: ModulationAmount = Field(description=AMOUNT)
    transfer: TransferFunction = omitted(description=TRANSFER)
    enabled: bool = omitted(description=ENABLED)


class ModulatorsDescribeArgs(StatusArgs):
    type: str = omitted(
        min_length=1,
        max_length=128,
        description="Modulator type name, e.g. 'Beat LFO': its help with per-control texts. Omitted lists every type.",
    )
    query: str = omitted(
        min_length=1,
        max_length=256,
        description="Keywords ranked against names, categories, descriptions and keywords, instead of type.",
    )
    lang: Literal["en", "de", "es", "fr", "ja", "ko", "zh"] = omitted(
        description="Documentation language (default en)."
    )
    installationRoot: str = omitted(
        min_length=1,
        max_length=4096,
        description="Bitwig install directory; default $BITWIG_INSTALLATION_ROOT, the install of $BITWIG_API_JAR, then /opt/bitwig-studio.",
    )

    @model_validator(mode="after")
    def validate_one_selector(self) -> Self:
        if {"type", "query"} <= self.model_fields_set:
            raise ValueError("supply at most one of type or query")
        return self


DEVICE_REF = {
    "kind": "device",
    "track": {"kind": "track", "id": "12345678-1234-1234-1234-123456789abc", "name": "Lead"},
    "index": 0,
    "name": "Poly Grid",
}
MODULATOR_REF = {"kind": "modulator", "device": DEVICE_REF, "id": "abcdef01-2345-6789-abcd-0123456789ab", "name": "LFO"}
GRID_PARAMETER_REF = {
    "kind": "parameter",
    "owner": {"kind": "gridModule", "device": DEVICE_REF, "id": "abcdef02-2345-6789-abcd-0123456789ab", "name": "AD"},
    "id": "DECAY",
}
DEVICE_PARAMETER_REF = {"kind": "parameter", "owner": DEVICE_REF, "id": "CONTENTS/PID1a", "name": "Cutoff"}
MODULATOR_PARAMETER_REF = {
    "kind": "parameter",
    "owner": {**MODULATOR_REF, "id": "abcdef03-2345-6789-abcd-0123456789ab", "name": "Random"},
    "id": "from-modulators.list",
}

MODULATORS_LIST_DESCRIPTION = "Read the modulators of one root device (the Modulators section of a device) in a single call, without selection: per modulator its ref, typeId, name, x/y, parameters (parameter ref and value {value, display, kind, minimum, maximum, options?}), read-only string-panel rows, and outputs with their mappings (target parameter ref, amount, transfer, enabled). offset 0..100000 and limit 1..128 (default 64) page the modulators. complete=false lists unsupported controls; contentsAvailable=false means the modulator was never opened. Discover target with devices.list."
MODULATORS_GET_DESCRIPTION = "Read one modulator, in the same row shape as modulators.list: target is a modulator ref from modulators.list or modulators.insert."
MODULATORS_DESCRIBE_DESCRIPTION = "Bitwig's own in-app help for device modulators, read offline from the local install (client-side; no host call). With type, one modulator: description, category, keywords, per-control help (docKey, title, description) and the shared help; without it, every type with name, category and one-line description (query ranks them by name, category, keywords and description, e.g. 'random', 'envelope'). Live kinds, ranges and values are not in these files: read them with modulators.list after inserting."
MODULATORS_INSERT_DESCRIPTION = "Add one device modulator to the device `to` (typeName from modulators.describe, e.g. Random, Steps, Beat LFO, ADSR) after the existing ones, via Bitwig's own modulator-browser insertion. Returns the new row with its ref after verifying membership and type. Needs a stopped, nonrecording, nonautomation transport. Never retry an unknown outcome: read modulators.list."
MODULATORS_INSERT_CONFIGURE_DESCRIPTION = MODULATORS_INSERT_DESCRIPTION.replace(
    " Needs a stopped",
    " params {name: value} sets its controls and map [{target, amount, ...}] maps it in the same call (modulators.get names, parameters.set values, modulators.map fields); entries has one step each, and once the insert applied the result keeps its ref and, if a later step failed, next: finish with parameters.set / modulators.map on that ref, never repeat the insert. Needs a stopped",
)
MODULATORS_DELETE_DESCRIPTION = "Delete one device modulator (and its mappings) with Bitwig's native undoable delete: target is a modulator ref. Membership is verified afterwards. Stopped transport required; never retry an unknown outcome."
MODULATORS_MAP_DESCRIPTION = "Set a modulation mapping from a modulator output to a control on the same device, whether or not one exists: source is a modulator ref, target a parameter ref of the device itself, a Grid module or another modulator (from parameters.list, grid.export refs or modulators.list), amount -1..1 (not clamped). An omitted transfer or enabled keeps an existing mapping's value (a new mapping gets linear, enabled). outputId may be omitted for single-output modulators. expect {amount} compares the current mapping first. Uses Bitwig's undoable modulation add; verified by exact mapping readback. Stopped transport required; never retry an unknown outcome."
MODULATORS_UNMAP_DESCRIPTION = "Remove one modulation mapping (Bitwig's undoable Clear modulation): source is a modulator ref, target the mapped parameter ref. expect {amount} compares the current mapping first. Verified by readback. Stopped transport required."


SPECS: dict[str, ToolSpec] = {
    "modulators.list": ToolSpec(
        ModulatorsListArgs,
        MODULATORS_LIST_DESCRIPTION,
        True,
        examples=({"target": DEVICE_REF, "offset": 0, "limit": 64},),
        records="modulators",
        summary="Read every modulator of one root device: ref, typeId, name, parameters {ref, value {value, display, kind, minimum, maximum, options?}}, string panels and outputs with mappings (target ref, amount, transfer, enabled). offset/limit page; complete=false lists unsupported controls.",
    ),
    "modulators.describe": ToolSpec(
        ModulatorsDescribeArgs,
        MODULATORS_DESCRIBE_DESCRIPTION,
        True,
        examples=({"type": "Beat LFO"}, {"query": "envelope follower"}, {}),
        host=False,
    ),
    "modulators.get": ToolSpec(
        ModulatorsGetArgs, MODULATORS_GET_DESCRIPTION, True, examples=({"target": MODULATOR_REF},)
    ),
    "modulators.insert": ToolSpec(
        ModulatorsInsertConfigureArgs,
        MODULATORS_INSERT_CONFIGURE_DESCRIPTION,
        False,
        examples=(
            {"to": DEVICE_REF, "typeName": "Random"},
            {"to": DEVICE_REF, "typeId": "bf29a7b0-91dc-4851-8a94-c63f358f3cda"},
            {
                "to": DEVICE_REF,
                "typeName": "LFO",
                "params": {"Rate": -0.60206},
                "map": [{"target": GRID_PARAMETER_REF, "amount": 0.3}],
            },
        ),
        summary="Add one device modulator (typeName from modulators.describe, or typeId) to the root device `to`; params? {name: value} sets its controls and map? [{target, amount, outputId?, transfer?, enabled?}] maps it, in one call. On a failed later step finish with parameters.set / modulators.map on the returned ref; never re-insert. Needs a stopped transport.",
        wire=ModulatorsInsertArgs,
        wire_description=MODULATORS_INSERT_DESCRIPTION,
        requires=STOPPED,
    ),
    "modulators.delete": ToolSpec(
        ModulatorsDeleteArgs,
        MODULATORS_DELETE_DESCRIPTION,
        False,
        examples=({"target": MODULATOR_REF},),
        requires=STOPPED,
    ),
    "modulators.map": ToolSpec(
        ModulatorsMapArgs,
        MODULATORS_MAP_DESCRIPTION,
        False,
        examples=(
            {"source": MODULATOR_REF, "target": GRID_PARAMETER_REF, "amount": 0.5},
            {"source": MODULATOR_REF, "target": DEVICE_PARAMETER_REF, "amount": 0.3},
            {
                "source": MODULATOR_REF,
                "outputId": "from-modulators.list",
                "target": MODULATOR_PARAMETER_REF,
                "amount": -0.25,
                "transfer": "cubic",
                "expect": {"amount": 0.5},
            },
        ),
        summary="Set a mapping from a modulator output to a control on the same device (upsert): source modulator ref, target a device, gridModule or modulator parameter ref, amount -1..1; an omitted transfer or enabled keeps an existing mapping's value. expect {amount} guards the current amount. Needs a stopped transport.",
        requires=STOPPED,
    ),
    "modulators.unmap": ToolSpec(
        ModulatorsUnmapArgs,
        MODULATORS_UNMAP_DESCRIPTION,
        False,
        examples=({"source": MODULATOR_REF, "target": GRID_PARAMETER_REF, "expect": {"amount": 0.5}},),
        requires=STOPPED,
    ),
}
