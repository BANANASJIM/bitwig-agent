from __future__ import annotations

import itertools
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import MAX_BEAT, StatusArgs, StrictModel, ToolSpec, UnitValue, omitted
from bitwig_agent.protocol.refs import AutomationParameterRef
from bitwig_agent.protocol.values import Expect


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


class AutomationPointsGetArgs(StatusArgs):
    target: AutomationParameterRef
    range: AutomationRange = omitted(
        description="Only list points with startBeat <= beat <= endBeat; lane.count and lane.pointsHash still cover the whole lane."
    )


class PointsExpect(Expect):
    pointsHash: str = Field(
        pattern=r"^[0-9a-f]{64}$", description="lane.pointsHash from a fresh automation.points.get."
    )


class AutomationPointsSetArgs(StatusArgs):
    target: AutomationParameterRef
    points: Annotated[list[AutomationPoint], Field(max_length=1024)] = Field(
        description="Replacement points, strictly increasing beats, inside range if given."
    )
    range: AutomationRange = omitted(
        description="Replace only points with startBeat <= beat <= endBeat; without it the whole lane is replaced."
    )
    expect: PointsExpect = omitted(description="The lane's current pointsHash; a mismatch writes nothing.")

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
TRACK_VOLUME = {
    "kind": "parameter",
    "owner": {"kind": "track", "id": "12345678-1234-1234-1234-123456789abc", "name": "Lead"},
    "id": "volume",
}
TRACK_PAN = {**TRACK_VOLUME, "id": "pan"}
GRID_CUTOFF = {
    "kind": "parameter",
    "owner": {
        "kind": "gridModule",
        "device": {"kind": "device", "track": TRACK_VOLUME["owner"], "index": 0, "name": "Poly Grid"},
        "id": "abcdef01-2345-6789-abcd-0123456789ab",
        "name": "F: lp",
    },
    "id": "CUTOFF",
}
AUTOMATION_POINTS_GET_DESCRIPTION = "Read the arranger automation points of one parameter on an audio/instrument track: target is the parameter ref tracks.get returned for volume or pan, or a Grid module parameter ref (an owner gridModule ref as grid.export read it, id the parameter key) for a float Grid control; other owners are unsupported_scope. Returns ref, lane {exists, count, pointsHash} for the whole lane (pass pointsHash as expect to automation.points.set) and points [{beat, value (normalized 0..1), interpolation linear|hold, curve}] inside the optional range, at most 1024 (truncated says more exist). Beats are quarter notes from the arrangement start. Launcher-clip automation and automation clips are not read. Native read (inspected Bitwig 6.1.4 JAR); no selection."
AUTOMATION_POINTS_SET_DESCRIPTION = "Replace the arranger automation points of one parameter (targets as automation.points.get): every point with startBeat <= beat <= endBeat (the whole lane without range) is replaced by points [{beat, value 0..1, interpolation linear|hold}], strictly increasing, inside range. expect.pointsHash, when given, must equal a fresh automation.points.get (else stale/target_changed with observed, nothing changed). Creates the lane when missing; an empty points list clears the range, leaving only Bitwig's own base point at beat 0, which counts as cleared. One native undoable edit, verified by reading the whole lane back; new points get curve 0. Needs stopped transport with recording/automation-write off. An unknown outcome refuses further automation writes until you inspect automation.points.get and ack the hold; never retry."


SPECS: dict[str, ToolSpec] = {
    "automation.points.get": ToolSpec(
        AutomationPointsGetArgs,
        AUTOMATION_POINTS_GET_DESCRIPTION,
        True,
        examples=(
            {"target": TRACK_VOLUME},
            {"target": GRID_CUTOFF, "range": {"startBeat": 0, "endBeat": 16}},
        ),
        summary="Read the arranger automation points of a track's volume or pan, or a float Grid module parameter, by parameter ref. Returns lane {exists, count, pointsHash} and the points in the optional range.",
    ),
    "automation.points.set": ToolSpec(
        AutomationPointsSetArgs,
        AUTOMATION_POINTS_SET_DESCRIPTION,
        False,
        examples=(
            {"target": TRACK_PAN, "points": [{"beat": 0, "value": 0.5, "interpolation": "hold"}]},
            {
                "target": TRACK_VOLUME,
                "points": [
                    {"beat": 0, "value": 0.2, "interpolation": "linear"},
                    {"beat": 16, "value": 0.8, "interpolation": "linear"},
                ],
                "range": {"startBeat": 0, "endBeat": 16},
                "expect": {"pointsHash": EMPTY_HASH},
            },
            {
                "target": GRID_CUTOFF,
                "points": [
                    {"beat": 32, "value": 0.1, "interpolation": "linear"},
                    {"beat": 48, "value": 0.9, "interpolation": "hold"},
                ],
                "range": {"startBeat": 32, "endBeat": 64},
            },
        ),
        summary="Replace the points of one parameter ref in range (default the whole lane); optional expect.pointsHash. Stopped transport only.",
    ),
}
