from __future__ import annotations

import re
from typing import Any, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    PROJECT_READY,
    TARGET,
    TRACK,
    Nonempty,
    ParameterArgs,
    StatusArgs,
    StrictModel,
    ToolSpec,
    Uuid,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.limits import REMOTE_PAGE_SLOTS
from bitwig_agent.protocol.positions import DeviceDestination, DeviceParent, DevicePosition
from bitwig_agent.protocol.refs import DeviceRef

REMOTE_PAGE_INDEX_MAX = 4095


class DevicesListArgs(StatusArgs):
    parent: DeviceParent = Field(description="The track whose root devices, or the chain whose devices, to list.")


class ChainsListArgs(StatusArgs):
    parent: DeviceRef = Field(description="A device that has chains (Instrument Layer, FX Layer, FX Selector).")


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


class DeviceInsertArgs(StatusArgs):
    parent: DeviceParent = Field(description="The track or chain to insert into.")
    position: DevicePosition = omitted(
        description='"start", "end" (default) or {before: deviceRef} / {after: deviceRef} of a device in this parent.'
    )
    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()

    @model_validator(mode="after")
    def validate_identifier(self) -> Self:
        validate_insertion(self)
        return self


class DeviceMoveArgs(DeviceTargetArgs):
    to: DeviceDestination = Field(description="Where the device ends up; parent is omitted to stay in its chain.")


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

DEVICES_LIST_DESCRIPTION = "List the devices of a track (root chain, up to 16 observed) or of one chain (the first 2 of its devices), without changing selection. Each row has the device ref that every device method takes back, plus name and type; window says how many there are."
DEVICES_GET_DESCRIPTION = "Read one device without selecting it: ref, name, type, enabled and whether it has Remote Controls. target is a device ref from devices.list (a root device or a device inside a chain)."
CHAINS_LIST_DESCRIPTION = "List the chains of a device that has them (Instrument Layer, FX Layer, FX Selector; at most the first 2): each row has the chain ref, its name and its device count. A chain ref follows its layer's UUID, so it survives reordering and renaming. List a chain's devices with devices.list {parent: chain}. Pads, slots and nested chains are unsupported."
DEVICES_SET_DESCRIPTION = "Enable or bypass one device: target is a device ref, enabled true or false. Result outcome applied or already_matched with the device ref."
DEVICES_SELECT_DESCRIPTION = (
    "Select this device in the host UI. Unlike reads, this intentionally changes selection. Result outcome sent."
)
DEVICES_CATALOG_DESCRIPTION = "List every installed Bitwig factory device file (about 150: name, category, description, path) using the host's installationRoot; texts are read from Bitwig's own resources at runtime. Copy kind=file and path into devices.insert. kind=bitwig UUIDs are not discoverable here (bitwigIdAvailable=false); no guessed UUIDs or exhaustive plug-in inventory. File presence is not insertion or musical verification."
DEVICES_INSERT_DESCRIPTION = "Insert a native device, plug-in or preset into a track's device chain or into one chain of a layer device. parent is a track ref or a chain ref; position is start, end (default), {before: deviceRef} or {after: deviceRef} of a device in that parent. kind=bitwig requires a canonical UUID id; vst2 a signed int32 decimal id; vst3 32 hex digits; clap printable ASCII; kind=file an absolute normalized path instead of id (a .bwdevice or a .bwpreset from presets.search). The result has the new device's ref (inserted) and the verification: the count grew by one, the devices before the point are unchanged and those after it shifted; a chain shows only its first two devices, then the count delta is the evidence and inserted is null. It does not prove the requested native UUID: inspect requestedIdentifierVerified. Insertion can load plug-ins or produce audio; observe before retrying."
DEVICES_DELETE_DESCRIPTION = "Delete one device (Device.deleteObject): target is a device ref from devices.list. Needs no recording or automation write. Returns the deleted device, the chain before and after, and the verification (the count dropped by one and the devices after it shifted); an unknown outcome (not_observed, readback_mismatch) means read devices.list before another edit. One Undo step; never retry."
DEVICES_MOVE_DESCRIPTION = "Move one device within its chain: target is a device ref, to {position: start|end|{before: deviceRef}|{after: deviceRef}}; parent stays the device's own track or chain (moving to another parent is not supported yet). A device already at that position is already_matched. Verified by the observed name/type arrangement of the whole chain; devices with identical name and type cannot prove a move (outcome unknown, readback_mismatch). Same safety and no-retry rules as devices.delete."
DEVICES_REMOTE_GET_DESCRIPTION = "Read one root device's Remote Controls: the page list (remotePage refs) and the controls of one page, each with the parameter ref, name and bare value. page is the page index; omitted reads the extension's own current page, never the page selected in the GUI. Pass a control's ref to parameters.set; a control remapped to another name is stale."


SPECS: dict[str, ToolSpec] = {
    "devices.list": ToolSpec(
        DevicesListArgs, DEVICES_LIST_DESCRIPTION, True, examples=({"parent": TRACK_REF},), records="devices"
    ),
    "devices.get": ToolSpec(DeviceTargetArgs, DEVICES_GET_DESCRIPTION, True, examples=({"target": DEVICE_REF},)),
    "chains.list": ToolSpec(
        ChainsListArgs,
        CHAINS_LIST_DESCRIPTION,
        True,
        examples=({"parent": CHAIN_REF["device"]},),
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
    "devices.catalog": ToolSpec(StatusArgs, DEVICES_CATALOG_DESCRIPTION, True),
    "devices.insert": ToolSpec(
        DeviceInsertArgs,
        DEVICES_INSERT_DESCRIPTION,
        False,
        examples=(
            {"parent": TRACK_REF, "kind": "clap", "id": "org.example.synth"},
            {
                "parent": TRACK_REF,
                "position": {"after": {**DEVICE_REF, "index": 1, "name": "EQ-5"}},
                "kind": "clap",
                "id": "org.example.synth",
            },
            {"parent": CHAIN_REF, "position": "start", "kind": "clap", "id": "org.example.effect"},
        ),
        requires=PROJECT_READY,
        summary="Insert a native device, plug-in or preset into a track's chain or a layer chain: parent (track or chain ref), position start|end|{before|after: deviceRef}, kind bitwig|vst2|vst3|clap with id, or kind=file with an absolute path. The requested identity is not proven: check requestedIdentifierVerified.",
    ),
    "devices.delete": ToolSpec(
        DeviceTargetArgs, DEVICES_DELETE_DESCRIPTION, False, examples=({"target": DEVICE_REF},), requires=PROJECT_READY
    ),
    "devices.move": ToolSpec(
        DeviceMoveArgs,
        DEVICES_MOVE_DESCRIPTION,
        False,
        examples=({"target": {**DEVICE_REF, "index": 3, "name": "Comp"}, "to": {"position": "start"}},),
        requires=PROJECT_READY,
    ),
    "devices.remote.get": ToolSpec(
        RemoteGetArgs, DEVICES_REMOTE_GET_DESCRIPTION, True, examples=({"target": DEVICE_REF, "page": 0},)
    ),
}
