from __future__ import annotations

import re
from typing import Any, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    PROJECT_READY,
    TRACK,
    Nonempty,
    StatusArgs,
    StrictModel,
    ToolSpec,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.parameters import InitialParams, check_host_value
from bitwig_agent.protocol.positions import DeviceParent, DevicePosition
from bitwig_agent.protocol.refs import DeviceRef

REMOTE_PAGE_INDEX_MAX = 4095


class DevicesListArgs(StatusArgs):
    target: DeviceParent = Field(description="The track whose root devices, or the chain whose devices, to list.")


class ChainsListArgs(StatusArgs):
    target: DeviceRef = Field(description="A device that has chains (Instrument Layer, FX Layer, FX Selector).")


class DevicesCatalogArgs(StatusArgs):
    query: Nonempty = omitted(description="Case-insensitive substring of a device's name or category.")
    category: Nonempty = omitted(description="Only devices of this category (case-insensitive, exact), e.g. Drum.")


class DeviceTargetArgs(StatusArgs):
    target: DeviceRef


class DeviceSetArgs(DeviceTargetArgs):
    enabled: bool


def validate_insertion(model: Any) -> None:
    if model.kind == "file":
        if "id" in model.model_fields_set or "path" not in model.model_fields_set:
            raise ValueError("file insertion requires path and forbids id")
        validate_local_path(model.path)
    else:
        if "path" in model.model_fields_set or "id" not in model.model_fields_set:
            raise ValueError("plug-in insertion requires id and forbids path")
        if model.kind == "bitwig" and not re.fullmatch(
            r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", model.id
        ):
            raise ValueError("bitwig id must be a canonical UUID")
        if model.kind == "vst2" and (
            not re.fullmatch(r"-?(0|[1-9][0-9]{0,9})", model.id) or not -(2**31) <= int(model.id) < 2**31
        ):
            raise ValueError("vst2 id must be a signed int32 decimal string")
        if model.kind == "vst3" and not re.fullmatch(r"[0-9a-fA-F]{32}", model.id):
            raise ValueError("vst3 id must be 32 hexadecimal characters")
        if model.kind == "clap" and not re.fullmatch(r"[!-~]{1,256}", model.id):
            raise ValueError("clap id must be 1..256 printable non-whitespace ASCII characters")


class Insert(StrictModel):
    """What to insert, exactly as presets.search and devices.catalog report it in insert."""

    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()

    @model_validator(mode="after")
    def validate_identifier(self) -> Self:
        validate_insertion(self)
        return self


POSITION = '"start", "end" (default) or {before: deviceRef} / {after: deviceRef} of a device in the destination.'


class DeviceInsertArgs(StatusArgs):
    to: DeviceParent = Field(description="The track or chain to insert into.")
    position: DevicePosition = omitted(description=POSITION)
    insert: Insert = Field(description="The insert object of a presets.search or devices.catalog row, unchanged.")


PARAMS = 'Initial values by parameter name (as parameters.list names them): the bare normalized 0..1 value or {display: "800 Hz"}, set with parameters.set right after the insert.'


class DeviceInsertParamsArgs(DeviceInsertArgs):
    params: InitialParams = omitted(description=PARAMS)

    @model_validator(mode="after")
    def validate_params(self) -> Self:
        for name, value in (self.params or {}).items():
            try:
                check_host_value(value)
            except ValueError as exc:
                raise ValueError(f"params.{name}: {exc}") from None
        return self


class DeviceMoveArgs(DeviceTargetArgs):
    to: DeviceParent = omitted(description="The device's own track or chain; omitted keeps it there.")
    position: DevicePosition = Field(description=POSITION.replace(" (default)", ""))


class RemoteGetArgs(DeviceTargetArgs):
    page: int = omitted(
        ge=0, le=REMOTE_PAGE_INDEX_MAX, description="Remote Controls page index; default the extension's current page."
    )


DEVICE_REF_EXAMPLE = {
    "kind": "device",
    "track": {"kind": "track", "id": TRACK, "name": "Lead"},
    "index": 0,
    "name": "Poly Grid",
}
DEVICE_REF = DEVICE_REF_EXAMPLE
TRACK_REF = DEVICE_REF_EXAMPLE["track"]
CHAIN_REF = {
    "kind": "chain",
    "device": {**DEVICE_REF, "index": 1, "name": "Instrument Layer"},
    "id": "abcdef01-2345-6789-abcd-0123456789ab",
    "name": "Pad",
}

DEVICES_LIST_DESCRIPTION = "List the devices of a track (root chain, up to 16 observed) or of one chain (all of its devices, read natively), without changing selection. Each row has the device ref that every device method takes back, plus name and type; window says how many there are."
DEVICES_GET_DESCRIPTION = "Read one device without selecting it: ref, name, type, enabled and whether it has Remote Controls. target is a device ref from devices.list (a root device or a device inside a chain)."
CHAINS_LIST_DESCRIPTION = "List every chain of a device that has them (Instrument Layer, FX Layer, the Selectors, Drum Machine pads that hold devices; also a layer device inside a chain): each row has the chain ref, its name and its device count. Chains are read natively on each request, so a device inside a chain stays addressable after inserts, deletes and moves. A chain ref follows its layer's UUID, so it survives reordering and renaming but not a Bitwig restart: Bitwig assigns new layer UUIDs then (an old ref is target_not_found), so re-read chains.list. Loading a preset onto the device keeps the UUIDs, so an older ref then names the new content. List a chain's devices with devices.list {target: chain}. FX slots and empty pads are unsupported."
DEVICES_SET_DESCRIPTION = "Enable or bypass one device: target is a device ref, enabled true or false. Result outcome applied or already_matched with the device ref."
DEVICES_SELECT_DESCRIPTION = (
    "Select this device in the host UI. Unlike reads, this intentionally changes selection. Result outcome sent."
)
DEVICES_CATALOG_DESCRIPTION = "List installed Bitwig factory device files (about 150: name, category, description, path) using the host's installationRoot; query (substring of name or category) and category (exact) narrow the list; texts are read from Bitwig's own resources at runtime. Pass a row's insert {kind: file, path} to devices.insert unchanged. kind=bitwig UUIDs are not discoverable here (bitwigIdAvailable=false); no guessed UUIDs or exhaustive plug-in inventory. File presence is not insertion or musical verification."
INSERT_CAUTION = "Insertion can load plug-ins or produce audio; observe before retrying."
DEVICES_INSERT_DESCRIPTION = "Insert a native device, plug-in or preset into a track's device chain or into one chain of a layer device. to is the track ref or chain ref; position is start, end (default), {before: deviceRef} or {after: deviceRef} of a device in it (indexes are not accepted). insert is {kind, id|path}, the insert object of a presets.search or devices.catalog row passed unchanged: kind=bitwig requires a canonical UUID id; vst2 a signed int32 decimal id; vst3 32 hex digits; clap printable ASCII; kind=file an absolute normalized path instead of id (a .bwdevice or .bwpreset). The result has the new device's ref (inserted) and verification api_readback (the count grew by one, the devices before the point are unchanged and those after it shifted) or, in a chain inside a device, native_readback (the new device object is at the point and everything else kept its order). It does not prove the requested native UUID: inspect requestedIdentifierVerified. Insertion can load plug-ins or produce audio; observe before retrying."
DEVICES_INSERT_PARAMS_DESCRIPTION = (
    DEVICES_INSERT_DESCRIPTION.removesuffix(INSERT_CAUTION)
    + "params {name: value} sets initial values on the new device in the same call (parameters.list names, parameters.set values); entries has one step each, and once the insert applied the result keeps its ref and, if a later step failed, next: finish with parameters.set on that ref, never repeat the insert. "
    + INSERT_CAUTION
)
DEVICES_DELETE_DESCRIPTION = "Delete one device (Device.deleteObject): target is a device ref from devices.list. Needs no recording or automation write. Returns the deleted device, the chain before and after, and verification api_readback (the count dropped by one and the devices after it shifted) or native_readback inside a device; an unknown outcome (not_observed, readback_mismatch) means read devices.list before another edit. One Undo step; never retry."
DEVICES_MOVE_DESCRIPTION = "Move one device within its chain: target is a device ref, position start|end|{before: deviceRef}|{after: deviceRef} (required); to, when given, is the device's own track or chain ref (moving to another parent is not supported yet). A device already at that position is already_matched. Verified by the arrangement of the whole chain: observed names/types in a track's chain, where devices with identical name and type cannot prove a move (outcome unknown, readback_mismatch); device objects in a chain inside a device. Same safety and no-retry rules as devices.delete."
DEVICES_REMOTE_GET_DESCRIPTION = "Read one root device's Remote Controls: the page list (remotePage refs) and the controls of one page, each with the parameter ref, name and bare value. page is the page index; omitted reads the extension's own current page, not the GUI's; a page past the page count is target_not_found. Pass a control's ref to parameters.set; a control remapped to another name is stale."


SPECS: dict[str, ToolSpec] = {
    "devices.list": ToolSpec(
        DevicesListArgs, DEVICES_LIST_DESCRIPTION, True, examples=({"target": TRACK_REF},), records="devices"
    ),
    "devices.get": ToolSpec(DeviceTargetArgs, DEVICES_GET_DESCRIPTION, True, examples=({"target": DEVICE_REF},)),
    "chains.list": ToolSpec(
        ChainsListArgs,
        CHAINS_LIST_DESCRIPTION,
        True,
        examples=({"target": CHAIN_REF["device"]},),
        records="chains",
    ),
    "devices.set": ToolSpec(
        DeviceSetArgs, DEVICES_SET_DESCRIPTION, False, examples=({"target": DEVICE_REF, "enabled": False},)
    ),
    "devices.select": ToolSpec(
        DeviceTargetArgs,
        DEVICES_SELECT_DESCRIPTION,
        False,
        examples=({"target": DEVICE_REF},),
    ),
    "devices.catalog": ToolSpec(
        DevicesCatalogArgs,
        DEVICES_CATALOG_DESCRIPTION,
        True,
        examples=({"query": "delay"}, {"category": "Drum"}),
        records="devices",
    ),
    "devices.insert": ToolSpec(
        DeviceInsertParamsArgs,
        DEVICES_INSERT_PARAMS_DESCRIPTION,
        False,
        examples=(
            {"to": TRACK_REF, "insert": {"kind": "clap", "id": "org.example.synth"}},
            {
                "to": TRACK_REF,
                "position": {"after": {**DEVICE_REF, "index": 1, "name": "EQ-5"}},
                "insert": {"kind": "file", "path": "/presets/Warm Pad.bwpreset"},
            },
            {"to": CHAIN_REF, "position": "start", "insert": {"kind": "clap", "id": "org.example.effect"}},
            {
                "to": TRACK_REF,
                "insert": {"kind": "file", "path": "/devices/Filter+.bwdevice"},
                "params": {"Cutoff": {"display": "800 Hz"}, "Resonance": 0.3},
            },
        ),
        requires=PROJECT_READY,
        summary="Insert a native device, plug-in or preset into a track's chain or a layer chain: to (track or chain ref), position? start|end|{before|after: deviceRef}, insert {kind, id|path} as presets.search or devices.catalog return it; params? {name: value} sets initial values (if a step fails, finish with parameters.set on the new ref, never re-insert). Identity is unproven: check requestedIdentifierVerified.",
        wire=DeviceInsertArgs,
        wire_description=DEVICES_INSERT_DESCRIPTION,
    ),
    "devices.delete": ToolSpec(
        DeviceTargetArgs, DEVICES_DELETE_DESCRIPTION, False, examples=({"target": DEVICE_REF},), requires=PROJECT_READY
    ),
    "devices.move": ToolSpec(
        DeviceMoveArgs,
        DEVICES_MOVE_DESCRIPTION,
        False,
        examples=({"target": {**DEVICE_REF, "index": 3, "name": "Comp"}, "position": "start"},),
        requires=PROJECT_READY,
    ),
    "devices.remote.get": ToolSpec(
        RemoteGetArgs, DEVICES_REMOTE_GET_DESCRIPTION, True, examples=({"target": DEVICE_REF, "page": 0},)
    ),
}
