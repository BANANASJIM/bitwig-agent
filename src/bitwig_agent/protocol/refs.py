"""Refs: typed JSON objects that name one host object. They come from reads and go back into writes verbatim."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Annotated, Any, ClassVar, Literal, NamedTuple

from pydantic import (
    Discriminator,
    Field,
    Tag,
    TypeAdapter,
    ValidationError,
    ValidatorFunctionWrapHandler,
    WrapValidator,
)

from bitwig_agent.protocol.base import MAX_BEAT, Nonempty, ReasonError, StrictModel, Uuid, omitted
from bitwig_agent.protocol.limits import DEVICE_WINDOW, REMOTE_PAGE_SLOTS, SEND_WINDOW

JAVA_INT_MAX = 2**31 - 1
Name = Annotated[str, Field(max_length=4096)]
Index = Annotated[int, Field(ge=0, le=JAVA_INT_MAX)]
Beats = Annotated[float, Field(ge=0, le=MAX_BEAT)]
CONTROL_ID = "^(" + "|".join(str(slot) for slot in range(REMOTE_PAGE_SLOTS)) + ")$"


class Roles(NamedTuple):
    """How a resolver uses each field: locate finds the object, compared must still match, targetOnly is compared
    only when the ref is the object written (not an ancestor of it)."""

    locate: tuple[str, ...]
    compared: tuple[str, ...] = ()
    targetOnly: tuple[str, ...] = ()

    def fields(self) -> tuple[str, ...]:
        return ("kind", *self.locate, *self.compared, *self.targetOnly)


ROLES: dict[str, Roles] = {
    "track": Roles(("id",), targetOnly=("name",)),
    "scene": Roles(("index",), ("name",)),
    "slot": Roles(("track", "scene")),
    "clip": Roles(("track", "slot", "lane", "startBeats"), ("name",)),
    "device": Roles(("track", "chain", "index"), ("name",)),
    "chain": Roles(("device", "id"), targetOnly=("name",)),
    "parameter": Roles(("owner", "id"), targetOnly=("name",)),
    "remotePage": Roles(("device", "index"), ("name",)),
    "send": Roles(("track", "index"), ("name",)),
    "sidechain": Roles(("device", "index"), ("id", "modulatorId", "moduleId")),
    "modulator": Roles(("device", "id"), targetOnly=("name",)),
    "gridModule": Roles(("device", "id"), targetOnly=("name",)),
    "marker": Roles(("index",), ("name", "beat")),
}


class Ref(StrictModel):
    """One variant of a ref kind; TAG tells it apart from the other variants of its kind."""

    TAG: ClassVar[str]


class TrackRef(Ref):
    TAG = "track"
    kind: Literal["track"]
    id: Uuid
    name: Name


class SceneRef(Ref):
    TAG = "scene"
    kind: Literal["scene"]
    index: Index
    name: Name | None = Field(description="null for an unnamed scene, which then has no rename protection.")


class SlotRef(Ref):
    TAG = "slot"
    kind: Literal["slot"]
    track: TrackRef
    scene: SceneRef


class LauncherSlot(StrictModel):
    scene: SceneRef


class LauncherClipRef(Ref):
    TAG = "clip:launcher"
    kind: Literal["clip"]
    track: TrackRef
    slot: LauncherSlot
    name: Name


class ArrangerClipRef(Ref):
    TAG = "clip:arranger"
    kind: Literal["clip"]
    track: TrackRef
    lane: Literal["main"]
    startBeats: Beats
    name: Name


DeviceIndex = Annotated[int, Field(ge=0, le=DEVICE_WINDOW - 1)]


class TrackDeviceRef(Ref):
    TAG = "device:track"
    kind: Literal["device"]
    track: TrackRef
    index: DeviceIndex
    name: Name


class ChainDeviceRef(Ref):
    TAG = "device:chain"
    kind: Literal["device"]
    chain: ChainRef
    index: DeviceIndex
    name: Name


class ChainRef(Ref):
    TAG = "chain"
    kind: Literal["chain"]
    device: DeviceRef
    id: Uuid
    name: Name


class RemotePageRef(Ref):
    TAG = "remotePage"
    kind: Literal["remotePage"]
    device: DeviceRef
    index: Index
    name: Name


class SendRef(Ref):
    TAG = "send"
    kind: Literal["send"]
    track: TrackRef
    index: Annotated[int, Field(ge=0, le=SEND_WINDOW - 1)]
    name: Name


class SidechainRef(Ref):
    TAG = "sidechain"
    kind: Literal["sidechain"]
    device: DeviceRef
    index: Index
    id: Nonempty
    modulatorId: Uuid = omitted()
    moduleId: Uuid = omitted()


class ModulatorRef(Ref):
    TAG = "modulator"
    kind: Literal["modulator"]
    device: DeviceRef
    id: Uuid
    name: Name


class GridModuleRef(Ref):
    TAG = "gridModule"
    kind: Literal["gridModule"]
    device: DeviceRef
    id: Uuid
    name: Name


class MarkerRef(Ref):
    TAG = "marker"
    kind: Literal["marker"]
    index: Index
    name: Name
    beat: Beats


class DeviceParameterRef(Ref):
    TAG = "parameter:device"
    kind: Literal["parameter"]
    owner: DeviceRef
    id: Nonempty
    name: Name = omitted()


class RemoteParameterRef(Ref):
    TAG = "parameter:remotePage"
    kind: Literal["parameter"]
    owner: RemotePageRef
    id: Annotated[str, Field(pattern=CONTROL_ID)]
    name: Name = Field(description="The control's name, always compared: a remapped control is a different target.")


class ModulatorParameterRef(Ref):
    TAG = "parameter:modulator"
    kind: Literal["parameter"]
    owner: ModulatorRef
    id: Nonempty
    name: Name = omitted()


class GridParameterRef(Ref):
    TAG = "parameter:gridModule"
    kind: Literal["parameter"]
    owner: GridModuleRef
    id: Nonempty = Field(description="The parameter key in the module's grid.export spec.")
    name: Name = omitted()


class TrackParameterRef(Ref):
    TAG = "parameter:track"
    kind: Literal["parameter"]
    owner: TrackRef
    id: Literal["volume", "pan"]
    name: Name = omitted()


def _get(value: Any, key: str) -> Any:
    return value.get(key) if isinstance(value, Mapping) else getattr(value, key, None)


def _tag(value: Any) -> str | None:
    """The variant tag: the kind, refined by the field or owner kind that tells a kind's variants apart."""
    kind = _get(value, "kind")
    if kind == "clip":
        return "clip:launcher" if _get(value, "slot") is not None else "clip:arranger"
    if kind == "device":
        return "device:chain" if _get(value, "chain") is not None else "device:track"
    if kind == "parameter":
        return f"parameter:{_get(_get(value, 'owner'), 'kind')}"
    return kind if isinstance(kind, str) else None


PARAMETER_VARIANTS: dict[str, type[Ref]] = {
    "device": DeviceParameterRef,
    "remotePage": RemoteParameterRef,
    "modulator": ModulatorParameterRef,
    "gridModule": GridParameterRef,
    "track": TrackParameterRef,
}


def _scope(what: str, **next: str) -> WrapValidator:
    """Turns a parameter owner the union does not accept into unsupported_scope, naming the method that takes it."""

    def check(value: Any, handler: ValidatorFunctionWrapHandler) -> Any:
        try:
            return handler(value)
        except ValidationError as error:
            owner = _get(_get(value, "owner"), "kind")
            if owner not in PARAMETER_VARIANTS or error.errors()[0]["type"] != "union_tag_invalid":
                raise
            use = f"; use {next[owner]}" if owner in next else ""
            raise ReasonError(
                "unsupported_scope", f"{what} does not take a parameter owned by a {owner}{use}", next.get(owner)
            ) from error

    return WrapValidator(check)


_TRACK_DEVICE = Annotated[TrackDeviceRef, Tag(TrackDeviceRef.TAG)]
_CHAIN_DEVICE = Annotated[ChainDeviceRef, Tag(ChainDeviceRef.TAG)]
_LAUNCHER_CLIP = Annotated[LauncherClipRef, Tag(LauncherClipRef.TAG)]
_ARRANGER_CLIP = Annotated[ArrangerClipRef, Tag(ArrangerClipRef.TAG)]
_DEVICE_PARAMETER = Annotated[DeviceParameterRef, Tag(DeviceParameterRef.TAG)]
_REMOTE_PARAMETER = Annotated[RemoteParameterRef, Tag(RemoteParameterRef.TAG)]
_MODULATOR_PARAMETER = Annotated[ModulatorParameterRef, Tag(ModulatorParameterRef.TAG)]
_GRID_PARAMETER = Annotated[GridParameterRef, Tag(GridParameterRef.TAG)]
_TRACK_PARAMETER = Annotated[TrackParameterRef, Tag(TrackParameterRef.TAG)]

DeviceRef = Annotated[_TRACK_DEVICE | _CHAIN_DEVICE, Discriminator(_tag)]
ClipRef = Annotated[_LAUNCHER_CLIP | _ARRANGER_CLIP, Discriminator(_tag)]
ParameterRef = Annotated[
    _DEVICE_PARAMETER | _REMOTE_PARAMETER | _MODULATOR_PARAMETER | _GRID_PARAMETER | _TRACK_PARAMETER,
    Discriminator(_tag),
]
AnyRef = Annotated[
    Annotated[TrackRef, Tag(TrackRef.TAG)]
    | Annotated[SceneRef, Tag(SceneRef.TAG)]
    | Annotated[SlotRef, Tag(SlotRef.TAG)]
    | _LAUNCHER_CLIP
    | _ARRANGER_CLIP
    | _TRACK_DEVICE
    | _CHAIN_DEVICE
    | Annotated[ChainRef, Tag(ChainRef.TAG)]
    | _DEVICE_PARAMETER
    | _REMOTE_PARAMETER
    | _MODULATOR_PARAMETER
    | _GRID_PARAMETER
    | _TRACK_PARAMETER
    | Annotated[RemotePageRef, Tag(RemotePageRef.TAG)]
    | Annotated[SendRef, Tag(SendRef.TAG)]
    | Annotated[SidechainRef, Tag(SidechainRef.TAG)]
    | Annotated[ModulatorRef, Tag(ModulatorRef.TAG)]
    | Annotated[GridModuleRef, Tag(GridModuleRef.TAG)]
    | Annotated[MarkerRef, Tag(MarkerRef.TAG)],
    Discriminator(_tag),
]

# The owner matrix: each method's parameter ref accepts only the owners in its union.
SetParameterRef = Annotated[
    _DEVICE_PARAMETER | _REMOTE_PARAMETER | _MODULATOR_PARAMETER,
    Discriminator(_tag),
    _scope("parameters.set", gridModule="grid.apply", track="tracks.set"),
]
AutomationParameterRef = Annotated[_TRACK_PARAMETER | _GRID_PARAMETER, Discriminator(_tag), _scope("automation.points")]
MappingParameterRef = Annotated[
    _GRID_PARAMETER | _MODULATOR_PARAMETER, Discriminator(_tag), _scope("modulators.map/unmap")
]
RemoteSlotParameterRef = Annotated[_GRID_PARAMETER, Discriminator(_tag), _scope("devices.remote.define")]

FORWARD: tuple[type[Ref], ...] = (*PARAMETER_VARIANTS.values(), TrackDeviceRef, ChainDeviceRef, ChainRef, RemotePageRef)
for _model in FORWARD:
    _model.model_rebuild()


def bundle_refs() -> dict[str, Any]:
    """The field roles of every kind, and the JSON Schema of any ref the extension checks its refs against."""
    return {
        "roles": {
            kind: {"locate": list(r.locate), "compared": list(r.compared), "targetOnly": list(r.targetOnly)}
            for kind, r in ROLES.items()
        },
        "schema": TypeAdapter(AnyRef).json_schema(),
    }
