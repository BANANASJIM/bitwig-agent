from __future__ import annotations

import itertools
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    MAX_BEAT,
    MAX_STRING_UNITS,
    TRACK,
    DeviceArgs,
    Nonempty,
    StrictModel,
    ToolSpec,
    UnitValue,
    Uuid,
    omitted,
)
from bitwig_agent.protocol.limits import DEVICE_WINDOW


class AutomationTarget(StrictModel):
    kind: Literal["volume", "pan", "grid"] = Field(
        description="Track volume, track pan, or a float Grid module parameter on a top-level device of the same track."
    )
    deviceIndex: Annotated[int, Field(ge=0, le=DEVICE_WINDOW - 1)] = omitted()
    expectedDeviceName: Nonempty = omitted()
    moduleId: Uuid = omitted(description="Grid module instanceId from grid.get on that device.")
    parameterId: Annotated[str, Field(max_length=MAX_STRING_UNITS)] = omitted(
        description="Exact parameterId of that module's float control, from grid.get."
    )

    @model_validator(mode="after")
    def validate_kind_fields(self) -> Self:
        grid = {"deviceIndex", "expectedDeviceName", "moduleId", "parameterId"}
        given = self.model_fields_set & grid
        if given != (grid if self.kind == "grid" else set()):
            raise ValueError(
                "kind grid takes exactly deviceIndex, expectedDeviceName, moduleId and parameterId; volume and pan take none"
            )
        return self


class AutomationRange(StrictModel):
    startBeat: float = Field(ge=0, le=MAX_BEAT)
    endBeat: float = Field(ge=0, le=MAX_BEAT)

    @model_validator(mode="after")
    def validate_order(self) -> Self:
        if self.startBeat > self.endBeat:
            raise ValueError("startBeat must not exceed endBeat")
        return self


class AutomationPoint(StrictModel):
    beat: float = Field(ge=0, le=MAX_BEAT, description="Arrangement position in beats (quarter notes).")
    value: UnitValue = Field(description="Normalized 0..1 parameter value.")
    interpolation: Literal["linear", "hold"]


class AutomationPointsGetArgs(DeviceArgs):
    target: AutomationTarget
    range: AutomationRange = omitted(
        description="Only list points with startBeat <= beat <= endBeat; count and pointsHash still cover the whole lane."
    )


class AutomationPointsSetArgs(DeviceArgs):
    target: AutomationTarget
    expectedCount: int = Field(ge=0, le=65536, description="count from a fresh automation.points.get.")
    expectedHash: str = Field(pattern=r"^[0-9a-f]{64}$", description="pointsHash from a fresh automation.points.get.")
    points: Annotated[list[AutomationPoint], Field(max_length=1024)] = Field(
        description="Replacement points, strictly increasing beats, inside range if given."
    )
    range: AutomationRange = omitted(
        description="Replace only points with startBeat <= beat <= endBeat; without it the whole lane is replaced."
    )

    @model_validator(mode="after")
    def validate_points(self) -> Self:
        beats = [point.beat for point in self.points]
        if any(b <= a for a, b in itertools.pairwise(beats)):
            raise ValueError("point beats must be strictly increasing")
        if "range" in self.model_fields_set and any(not self.range.startBeat <= b <= self.range.endBeat for b in beats):
            raise ValueError("every point must lie inside range")
        return self


# pointsHash of an empty or missing automation lane (SHA-256 of no points).
EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
AUTOMATION_POINTS_GET_DESCRIPTION = "Read the arranger automation points of one parameter on an audio/instrument track: target {kind:volume}, {kind:pan}, or {kind:grid, deviceIndex, expectedDeviceName, moduleId, parameterId} for a float Grid module control. Returns laneExists, count and pointsHash for the whole lane (pass both to automation.points.set) and points [{beat, value (normalized 0..1), interpolation linear|hold, curve}] inside the optional range, at most 1024 (truncated says more exist). Beats are quarter notes from the arrangement start. Launcher-clip automation and automation clips are not read. Native read (inspected Bitwig 6.1.4 JAR); no selection."
AUTOMATION_POINTS_SET_DESCRIPTION = "Replace the arranger automation points of one parameter (targets as automation.points.get): every point with startBeat <= beat <= endBeat (the whole lane without range) is replaced by points [{beat, value 0..1, interpolation linear|hold}], strictly increasing, inside range. expectedCount and expectedHash must equal a fresh automation.points.get (else stale_target, nothing changed). Creates the lane when missing; an empty points list clears the range. One native undoable edit, verified by reading the whole lane back; new points get curve 0. Needs stopped transport with recording/automation-write off. An unknown outcome refuses further automation writes until you inspect automation.points.get and ack the hold; never retry."


SPECS: dict[str, ToolSpec] = {
    "automation.points.get": ToolSpec(
        AutomationPointsGetArgs,
        AUTOMATION_POINTS_GET_DESCRIPTION,
        True,
        examples=(
            {"trackId": TRACK, "target": {"kind": "volume"}},
            {
                "trackId": TRACK,
                "target": {
                    "kind": "grid",
                    "deviceIndex": 0,
                    "expectedDeviceName": "Poly Grid",
                    "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                    "parameterId": "CUTOFF",
                },
                "range": {"startBeat": 0, "endBeat": 16},
            },
        ),
    ),
    "automation.points.set": ToolSpec(
        AutomationPointsSetArgs,
        AUTOMATION_POINTS_SET_DESCRIPTION,
        False,
        examples=(
            {
                "trackId": TRACK,
                "target": {"kind": "volume"},
                "expectedCount": 0,
                "expectedHash": EMPTY_HASH,
                "points": [
                    {"beat": 0, "value": 0.2, "interpolation": "linear"},
                    {"beat": 16, "value": 0.8, "interpolation": "linear"},
                ],
                "range": {"startBeat": 0, "endBeat": 16},
            },
            {
                "trackId": TRACK,
                "target": {"kind": "pan"},
                "expectedCount": 0,
                "expectedHash": EMPTY_HASH,
                "points": [{"beat": 0, "value": 0.5, "interpolation": "hold"}],
            },
            {
                "trackId": TRACK,
                "target": {
                    "kind": "grid",
                    "deviceIndex": 0,
                    "expectedDeviceName": "Poly Grid",
                    "moduleId": "abcdef01-2345-6789-abcd-0123456789ab",
                    "parameterId": "CUTOFF",
                },
                "expectedCount": 0,
                "expectedHash": EMPTY_HASH,
                "points": [
                    {"beat": 32, "value": 0.1, "interpolation": "linear"},
                    {"beat": 48, "value": 0.9, "interpolation": "hold"},
                ],
                "range": {"startBeat": 32, "endBeat": 64},
            },
        ),
        summary="Replace the automation points of one parameter within range (the whole lane if omitted); expectedCount and expectedHash must match a fresh automation.points.get. Needs a stopped transport.",
    ),
}
