from __future__ import annotations

import re
from typing import Any, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    PROJECT_READY,
    TARGET,
    TRACK,
    DeviceArgs,
    Nonempty,
    ParameterArgs,
    StatusArgs,
    StrictModel,
    ToolSpec,
    UnitValue,
    Uuid,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.limits import DEVICE_WINDOW

ANCHOR_FIELDS = frozenset({"deviceIndex", "expectedDeviceName"})


class DeviceParent(StrictModel):
    trackId: Uuid
    deviceIndex: int = Field(ge=0, le=DEVICE_WINDOW - 1)
    expectedDeviceName: Nonempty


class LayerListArgs(StatusArgs):
    parent: DeviceParent


class DeviceSetArgs(ParameterArgs):
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


class DeviceInsertArgs(DeviceArgs):
    expectedDeviceCount: int = Field(ge=0, le=DEVICE_WINDOW - 1)
    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()
    position: Literal["start", "end", "before", "after"] = omitted(
        description="Where in the chain; default end. before/after need deviceIndex and expectedDeviceName of the anchor device."
    )
    deviceIndex: int = omitted(ge=0, le=DEVICE_WINDOW - 1, description="Anchor device for position before/after.")
    expectedDeviceName: Nonempty = omitted(description="Exact name of the anchor device.")

    @model_validator(mode="after")
    def validate_identifier(self) -> Self:
        anchored = self.position in ("before", "after")
        given = ANCHOR_FIELDS & self.model_fields_set
        if anchored and given != ANCHOR_FIELDS:
            raise ValueError("position before/after requires deviceIndex and expectedDeviceName")
        if not anchored and given:
            raise ValueError("deviceIndex and expectedDeviceName are only for position before/after")
        validate_insertion(self)
        return self


class DeviceMoveArgs(ParameterArgs):
    toIndex: int = Field(ge=0, le=DEVICE_WINDOW - 1)

    @model_validator(mode="after")
    def validate_move(self) -> Self:
        if self.toIndex == self.deviceIndex:
            raise ValueError("toIndex equals deviceIndex; nothing to move")
        return self


class LayerInsertArgs(LayerListArgs):
    layerId: Uuid
    expectedChildCount: int = Field(ge=0, le=4096)
    kind: Literal["bitwig", "vst2", "vst3", "clap", "file"]
    id: Nonempty = omitted()
    path: Nonempty = omitted()
    position: Literal["start", "end"] = omitted(description="Start or end of the layer's chain; default end.")

    @model_validator(mode="after")
    def validate_identifier(self) -> Self:
        validate_insertion(self)
        return self


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


LAYER_PARENT = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Instrument Layer"}
LAYER_TARGET = {
    "parent": LAYER_PARENT,
    "layerId": "abcdef01-2345-6789-abcd-0123456789ab",
    "childDeviceIndex": 0,
    "expectedChildDeviceName": "Polymer",
    "discoveryToken": "from-devices.layers.list",
}


DEVICES_LIST_DESCRIPTION = "Read source-bound top-level device observations without changing host cursor/selection. Discover zero-based deviceIndex and exact expectedDeviceName, within the returned window."
DEVICES_GET_DESCRIPTION = "Read a source-bound device's state and unsupported-interface details without selecting it. Use exact name and zero-based index from devices.list."
DEVICES_LAYERS_LIST_DESCRIPTION = "Discover one layer level beneath an explicit root parent without selection. Fixed zero-offset windows cover at most 2 layers and 2 child devices per layer, not a complete hierarchy. Inspect observed counts, completeness and readiness before using layerId, childDeviceIndex, exact expectedChildDeviceName and discoveryToken. Pads, slots and deeper targets are unsupported."
DEVICES_SET_DESCRIPTION = "Enable or bypass an explicitly identified device using enabled=true/false. Inspect a fresh observation when the outcome is unknown before another write."
DEVICES_SELECT_DESCRIPTION = (
    "Explicitly select this device in the host UI. Unlike source-bound reads, this intentionally changes selection."
)
DEVICES_CATALOG_DESCRIPTION = "List every installed Bitwig factory device file (about 150: name, category, description, path) using the host's installationRoot; texts are read from Bitwig's own resources at runtime. Copy kind=file and path into devices.insert with an observed track/count. kind=bitwig UUIDs are not discoverable here (bitwigIdAvailable=false); no guessed UUIDs or exhaustive plug-in inventory. File presence is not insertion or musical verification."
DEVICES_INSERT_DESCRIPTION = "Insert a native device, plug-in or preset into a track's device chain. Discover expectedDeviceCount with devices.list; truncated windows are rejected. position: end (default, append), start, or before/after an anchor device given by deviceIndex and its exact expectedDeviceName (devices.list). kind=bitwig requires canonical UUID id; vst2 signed int32 decimal id; vst3 32 hex id; clap printable ASCII id. kind=file requires absolute normalized path instead of id (a .bwdevice or a .bwpreset from presets.search). No shell/path expansion. Verification is the observed count increment, unchanged devices before the point and shifted devices after it; inserted names the final slot. A shifted device with layers may report not_ready from parameters.layer.* until you inspect state and ack the hold. It does not prove the requested native UUID: inspect requestedIdentifierVerified. Insertion can load plug-ins or produce audio; observe before retrying."
DEVICES_DELETE_DESCRIPTION = "Delete one top-level device (Device.deleteObject). Needs the exact trackId, deviceIndex and expectedDeviceName from devices.list and no recording/automation write. Returns the deleted device, before/after chains and verification observed_count_decrement_and_shifted_suffix; an unknown outcome (not_observed, readback_mismatch) means inspect devices.list before another edit. One Undo step; never retry. Shifted devices with layers may report not_ready from parameters.layer.* until you inspect state and ack the hold."
DEVICES_MOVE_DESCRIPTION = "Move one top-level device within its track so it ends at toIndex (public InsertionPoint.moveDevices): the device at deviceIndex/expectedDeviceName goes after the device now at toIndex when moving later, before it when moving earlier. Verified by the observed name/type arrangement of the whole chain; devices with identical name and type cannot prove a move (outcome unknown, readback_mismatch). Same safety and no-retry rules as devices.delete."
DEVICES_LAYERS_INSERT_DESCRIPTION = "Insert a device into one layer of a container device that reports layers (Instrument Layer, FX Layer, FX Selector; not the Chain device, whose chain is an uncovered device slot) at its start or end (default end). Factory Instrument Layer/FX Selector presets have zero layers and the API cannot add one: load a preset that has layers. Address the layer as devices.layers.list does (parent + layerId) and give its current child count (expectedChildCount, from that list's layer window total). Only the first two children per layer are observable: with more, the count delta is the evidence (observed_count_increment_beyond_window, inserted null). Parameters of a changed layer's children may report not_ready until the extension restarts. Drum pads and nested layers are unsupported. kind/id/path as devices.insert; never retry an unobserved result."
DEVICES_REMOTE_GET_DESCRIPTION = "Read a source-device-bound remote-control page and fresh contextToken without changing the GUI page. Discover exact page/control names and zero-based indices. Token tracks observed metadata, not unobservable same-name remaps."
DEVICES_REMOTE_PAGE_DESCRIPTION = "Change this extension's independent remote page, not the GUI-selected page. Use current contextToken and target pageIndex/expectedPageName from devices.remote.get; get a fresh token after switching."
DEVICES_REMOTE_SET_DESCRIPTION = "Set one normalized remote control with exact page/control names and fresh contextToken from devices.remote.get. Indices are zero-based; inspect fresh same-source value verification. Unobservable same-name mapping changes are not guaranteed detectable."


SPECS: dict[str, ToolSpec] = {
    "devices.list": ToolSpec(
        DeviceArgs, DEVICES_LIST_DESCRIPTION, True, examples=({"trackId": TRACK},), records="devices"
    ),
    "devices.get": ToolSpec(ParameterArgs, DEVICES_GET_DESCRIPTION, True, examples=(TARGET,)),
    "devices.layers.list": ToolSpec(
        LayerListArgs, DEVICES_LAYERS_LIST_DESCRIPTION, True, examples=({"parent": LAYER_PARENT},), records="layers"
    ),
    "devices.set": ToolSpec(DeviceSetArgs, DEVICES_SET_DESCRIPTION, False, examples=({**TARGET, "enabled": False},)),
    "devices.select": ToolSpec(ParameterArgs, DEVICES_SELECT_DESCRIPTION, False, examples=(TARGET,)),
    "devices.catalog": ToolSpec(StatusArgs, DEVICES_CATALOG_DESCRIPTION, True),
    "devices.insert": ToolSpec(
        DeviceInsertArgs,
        DEVICES_INSERT_DESCRIPTION,
        False,
        examples=(
            {
                "trackId": TRACK,
                "expectedDeviceCount": 2,
                "kind": "clap",
                "id": "org.example.synth",
                "position": "before",
                "deviceIndex": 1,
                "expectedDeviceName": "EQ-5",
            },
        ),
        requires=PROJECT_READY,
        summary="Insert a native device, plug-in or preset into a track's chain: expectedDeviceCount from devices.list, position end|start|before|after (anchor by deviceIndex and exact expectedDeviceName), kind bitwig|vst2|vst3|clap with id, or kind=file with an absolute path. The requested identity is not proven: check requestedIdentifierVerified.",
    ),
    "devices.delete": ToolSpec(
        ParameterArgs, DEVICES_DELETE_DESCRIPTION, False, examples=(TARGET,), requires=PROJECT_READY
    ),
    "devices.move": ToolSpec(
        DeviceMoveArgs,
        DEVICES_MOVE_DESCRIPTION,
        False,
        examples=({**TARGET, "deviceIndex": 3, "toIndex": 1},),
        requires=PROJECT_READY,
    ),
    "devices.layers.insert": ToolSpec(
        LayerInsertArgs,
        DEVICES_LAYERS_INSERT_DESCRIPTION,
        False,
        examples=(
            {
                "parent": LAYER_PARENT,
                "layerId": LAYER_TARGET["layerId"],
                "expectedChildCount": 1,
                "kind": "clap",
                "id": "org.example.effect",
                "position": "end",
            },
        ),
        requires=PROJECT_READY,
    ),
    "devices.remote.get": ToolSpec(ParameterArgs, DEVICES_REMOTE_GET_DESCRIPTION, True, examples=(TARGET,)),
    "devices.remote.page": ToolSpec(
        RemotePageArgs,
        DEVICES_REMOTE_PAGE_DESCRIPTION,
        False,
        examples=({**TARGET, "contextToken": "from-devices.remote.get", "pageIndex": 0, "expectedPageName": "Main"},),
    ),
    "devices.remote.set": ToolSpec(
        RemoteSetArgs,
        DEVICES_REMOTE_SET_DESCRIPTION,
        False,
        examples=(
            {
                **TARGET,
                "contextToken": "from-devices.remote.get",
                "expectedPageName": "Main",
                "parameterIndex": 0,
                "expectedParameterName": "Cutoff",
                "normalizedValue": 0.5,
            },
        ),
    ),
}
