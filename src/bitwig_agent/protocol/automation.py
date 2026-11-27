from __future__ import annotations

import itertools
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import MAX_BEAT, StatusArgs, StrictModel, ToolSpec, omitted
from bitwig_agent.protocol.limits import MAX_CURVE, POINT_BEAT_DIVISOR
from bitwig_agent.protocol.refs import AutomationParameterRef
from bitwig_agent.protocol.values import Expect, Normalized


def snap(beat: float) -> float:
    """The beat on the grid automation points are identified on, as the extension snaps it."""
    return round(beat * POINT_BEAT_DIVISOR) / POINT_BEAT_DIVISOR


class AutomationRange(StrictModel):
    start: float = Field(ge=0, le=MAX_BEAT)
    end: float = Field(ge=0, le=MAX_BEAT)

    @model_validator(mode="after")
    def validate_order(self) -> Self:
        if self.start > self.end:
            raise ValueError("start must not exceed end")
        return self


class AutomationPoint(StrictModel):
    beat: float = Field(ge=0, le=MAX_BEAT, description="Arrangement position in beats (quarter notes).")
    value: Normalized = Field(
        description='The normalized 0..1 value a read returned, or {display: "800 Hz"} in the form the parameter displays (a bare number reads in the unit it shows).'
    )
    interpolation: Literal["linear", "hold"] = omitted(
        description="Omitted: linear for a new point and with range; without range an updated point keeps its interpolation."
    )
    curve: float = omitted(
        ge=-MAX_CURVE,
        le=MAX_CURVE,
        description="Bend of the segment from this point to the next, -1..1 (0 straight). Omitted: 0 for a new point and with range; without range an updated point keeps its curve.",
    )


class AutomationPointsGetArgs(StatusArgs):
    target: AutomationParameterRef
    range: AutomationRange = omitted(
        description="Only list points with start <= beat <= end; lane.count and lane.pointsHash still cover the whole lane."
    )


class PointsExpect(Expect):
    pointsHash: str = Field(
        pattern=r"^[0-9a-f]{64}$", description="lane.pointsHash from a fresh automation.points.get."
    )


class AutomationPointsSetArgs(StatusArgs):
    target: AutomationParameterRef
    points: Annotated[list[AutomationPoint], Field(max_length=1024)] = Field(
        description="Points to write, beats non-decreasing (points at one beat, such as a step, in order), inside range if given. Points are identified by beat and order at that beat; a listed point updates the one in the same place."
    )
    range: AutomationRange | Literal["all"] = omitted(
        description='Without range only the listed points are written and nothing is removed. {start, end}: the points with start <= beat <= end become exactly the listed ones. "all": the whole lane does.'
    )
    expect: PointsExpect = omitted(description="The lane's current pointsHash; a mismatch writes nothing.")

    @model_validator(mode="after")
    def validate_points(self) -> Self:
        beats = [snap(point.beat) for point in self.points]
        if any(b < a for a, b in itertools.pairwise(beats)):
            raise ValueError("point beats must be non-decreasing")
        if isinstance(self.range, AutomationRange) and any(not self.range.start <= b <= self.range.end for b in beats):
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
FILTER_FREQUENCY = {
    "kind": "parameter",
    "owner": {"kind": "device", "track": TRACK_VOLUME["owner"], "index": 1, "name": "Filter"},
    "id": "CONTENTS/PID1a",
    "name": "Frequency",
}
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
AUTOMATION_POINTS_GET_DESCRIPTION = "Read the arranger automation points of one parameter on an audio/instrument track. target is a parameter ref as read: a track's volume or pan (tracks.get), a direct parameter of a device on the track or inside one of its chains (parameters.list), or a float Grid module parameter (an owner gridModule ref as grid.export read it, id the parameter key); Remote Control and modulator refs are unsupported_scope. Returns ref, lane {exists, count, pointsHash} for the whole lane (pass pointsHash as expect to automation.points.set) and points [{beat, value (normalized 0..1), display (the parameter's own text for value), interpolation linear|hold, curve -1..1}] in lane order (a step is several points at one beat) inside the optional range, at most 1024 (truncated says more exist). Beats are quarter notes from the arrangement start. Only continuous (decimal) parameters have lanes here; others are unsupported_scope. Launcher-clip automation and automation clips are not read. Native read (version-gated); no selection."
AUTOMATION_POINTS_SET_DESCRIPTION = 'Write the arranger automation points of one parameter (targets as automation.points.get). points [{beat, value, interpolation linear|hold, curve -1..1}] have non-decreasing beats (a step is points at one beat in order, the last carrying the outgoing segment shape); value is the normalized 0..1 value a read returned or {display: "800 Hz"}, which Bitwig\'s own text parser reads on that parameter, a bare number in the unit it shows (text it cannot read, or a number in another unit than the parameter shows, is unsupported_unit; past either end of its range is out_of_bounds; nothing is written). Points are identified by beat and order at that beat: omitted interpolation/curve: without range a listed point keeps the value of the point in the same place (a new point: linear, 0); with range they are linear and 0. Without range only the listed points are written and nothing is removed (at a beat holding several points list all of them, else an invalid_target error); range {start, end} makes the points with start <= beat <= end exactly the listed ones (they must lie inside it); range "all" does that for the whole lane; of two adjacent range writes the later owns the boundary beat. Points read by automation.points.get can be written back as {beat, value, interpolation, curve}: the same request again is already_matched. expect.pointsHash, when given, must equal a fresh automation.points.get (else stale/target_changed with observed, nothing changed). Creates the lane when missing; an empty points list with a range clears it, leaving only Bitwig\'s own base point at beat 0, which counts as cleared. Returns summary {inserted, updated, removed, unchanged}. One native undoable edit, verified by reading the whole lane back; untouched points stay the same native objects. Needs stopped transport with recording/automation-write off. An unknown outcome refuses further automation writes until you inspect automation.points.get and ack the hold; never retry.'


SPECS: dict[str, ToolSpec] = {
    "automation.points.get": ToolSpec(
        AutomationPointsGetArgs,
        AUTOMATION_POINTS_GET_DESCRIPTION,
        True,
        examples=(
            {"target": TRACK_VOLUME},
            {"target": GRID_CUTOFF, "range": {"start": 0, "end": 16}},
            {"target": FILTER_FREQUENCY},
        ),
        summary="Read the arranger automation points of any continuous parameter by ref (track volume/pan, device parameter, Grid module parameter). Returns lane {exists, count, pointsHash} and the points with value, display and curve in the optional range.",
    ),
    "automation.points.set": ToolSpec(
        AutomationPointsSetArgs,
        AUTOMATION_POINTS_SET_DESCRIPTION,
        False,
        examples=(
            {"target": TRACK_PAN, "points": [{"beat": 0, "value": 0.5, "interpolation": "hold"}], "range": "all"},
            {"target": TRACK_VOLUME, "points": [{"beat": 8, "value": 0.7}, {"beat": 12, "value": 0.4}]},
            {
                "target": TRACK_VOLUME,
                "points": [
                    {"beat": 0, "value": 0.2, "interpolation": "linear"},
                    {"beat": 16, "value": 0.8, "interpolation": "linear"},
                ],
                "range": {"start": 0, "end": 16},
                "expect": {"pointsHash": EMPTY_HASH},
            },
            {
                "target": GRID_CUTOFF,
                "points": [
                    {"beat": 32, "value": 0.1, "interpolation": "linear"},
                    {"beat": 48, "value": 0.9, "interpolation": "hold"},
                ],
                "range": {"start": 32, "end": 64},
            },
            {
                "target": FILTER_FREQUENCY,
                "points": [
                    {"beat": 32, "value": {"display": "420 Hz"}},
                    {"beat": 60, "value": {"display": "420 Hz"}, "curve": 0.6},
                    {"beat": 64, "value": {"display": "6.80 kHz"}, "interpolation": "hold"},
                ],
                "range": {"start": 32, "end": 64},
            },
        ),
        summary='Write points of one parameter ref, value normalized or {display}, optional curve: without range only the listed points (nothing removed); range {start,end} or "all" becomes exactly the listed points. Optional expect.pointsHash. Stopped transport only.',
    ),
}
