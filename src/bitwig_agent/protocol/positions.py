"""Positions in an ordered container: "start" | "end" | {before: ref} | {after: ref}; a destination adds a parent."""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import Discriminator, Field, Tag

from bitwig_agent.protocol.base import StrictModel, omitted
from bitwig_agent.protocol.refs import ChainRef, DeviceRef, TrackRef, tag

Edge = Literal["start", "end"]


class BeforeTrack(StrictModel):
    before: TrackRef


class AfterTrack(StrictModel):
    after: TrackRef


class BeforeDevice(StrictModel):
    before: DeviceRef


class AfterDevice(StrictModel):
    after: DeviceRef


TrackPosition = Edge | BeforeTrack | AfterTrack
"""Where in the track list; omitted means "end". The anchor is a track of the same list."""
DevicePosition = Edge | BeforeDevice | AfterDevice
"""Where in a device chain; omitted means "end". The anchor is a device of the same chain."""
DeviceParent = Annotated[Annotated[TrackRef, Tag("track")] | Annotated[ChainRef, Tag("chain")], Discriminator(tag)]


class TrackDestination(StrictModel):
    parent: TrackRef = omitted(description="The group track to move into; omitted keeps the current parent.")
    position: TrackPosition


class DeviceInsertion(StrictModel):
    """Where a new device goes: the container and a position in it; the position defaults to "end"."""

    parent: DeviceParent = Field(description="The track or chain to insert into.")
    position: DevicePosition = omitted(
        description='"start", "end" (default) or {before: deviceRef} / {after: deviceRef} of a device in this parent.'
    )


class DeviceDestination(StrictModel):
    parent: DeviceParent = omitted(description="The track or chain to move into; omitted keeps the current one.")
    position: DevicePosition
