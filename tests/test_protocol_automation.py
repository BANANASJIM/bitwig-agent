from typing import Any

import pytest
from pydantic import TypeAdapter, ValidationError

from bitwig_agent.core.invoke import invalid_params
from bitwig_agent.protocol import validate_call
from bitwig_agent.protocol.automation import EMPTY_HASH
from bitwig_agent.protocol.refs import AutomationParameterRef

TRACK = {"kind": "track", "id": "11111111-1111-1111-1111-111111111111", "name": "Lead"}
DEVICE = {"kind": "device", "track": TRACK, "index": 0, "name": "Poly Grid"}
MODULE = {"kind": "gridModule", "device": DEVICE, "id": "22222222-2222-2222-2222-222222222222", "name": "F: lp"}
VOLUME = {"kind": "parameter", "owner": TRACK, "id": "volume"}
PAN = {"kind": "parameter", "owner": TRACK, "id": "pan"}
GRID = {"kind": "parameter", "owner": MODULE, "id": "CUTOFF"}
SET: dict[str, Any] = {
    "target": VOLUME,
    "expect": {"pointsHash": EMPTY_HASH},
    "points": [
        {"beat": 0, "value": 0.25, "interpolation": "linear"},
        {"beat": 4.5, "value": 1, "interpolation": "hold"},
    ],
}


def test_get_takes_a_track_or_grid_parameter_ref_and_a_range():
    for target in (VOLUME, PAN, GRID):
        args = {"target": target}
        assert validate_call("automation.points.get", args)[0] == args
    ranged = {"target": PAN, "range": {"startBeat": 8, "endBeat": 8}}
    assert validate_call("automation.points.get", ranged)[0] == ranged


@pytest.mark.parametrize(
    "target",
    [
        {**VOLUME, "id": "mute"},
        {"kind": "parameter", "owner": DEVICE, "id": "CUTOFF"},
        {
            "kind": "parameter",
            "owner": {"kind": "modulator", "device": DEVICE, "id": MODULE["id"], "name": "LFO"},
            "id": "RATE",
        },
        {**GRID, "id": ""},
        {k: v for k, v in GRID.items() if k != "id"},
        {"kind": "track", "id": TRACK["id"], "name": "Lead"},
    ],
)
def test_get_rejects_other_owners_and_malformed_refs(target):
    with pytest.raises(ValidationError):
        validate_call("automation.points.get", {"target": target})


def test_a_device_owned_parameter_is_unsupported_scope():
    with pytest.raises(ValidationError) as rejected:
        validate_call(
            "automation.points.get", {"target": {"kind": "parameter", "owner": DEVICE, "id": "x", "name": "x"}}
        )
    error = invalid_params("automation.points.get", rejected.value).error
    assert (error["code"], error["reason"]) == ("invalid_params", "unsupported_scope")


def test_set_whole_lane_range_and_clear():
    assert validate_call("automation.points.set", SET)[0] == SET
    ranged = {**SET, "target": GRID, "range": {"startBeat": 0, "endBeat": 4.5}}
    assert validate_call("automation.points.set", ranged)[0] == ranged
    clear = {**SET, "points": [], "range": {"startBeat": 2, "endBeat": 3}}
    assert validate_call("automation.points.set", clear)[0]["points"] == []
    unguarded = {key: value for key, value in SET.items() if key != "expect"}
    assert validate_call("automation.points.set", unguarded)[0] == unguarded


@pytest.mark.parametrize(
    "changes",
    [
        {"points": [SET["points"][1], SET["points"][0]]},
        {"points": [SET["points"][0], {**SET["points"][0], "value": 0.5}]},
        {"points": [{**SET["points"][0], "value": 1.5}]},
        {"points": [{**SET["points"][0], "interpolation": "bezier"}]},
        {"points": [{**SET["points"][0], "curve": 0.5}]},
        {"range": {"startBeat": 1, "endBeat": 2}},
        {"range": {"startBeat": 5, "endBeat": 4}},
        {"expect": {"pointsHash": EMPTY_HASH.upper()}},
        {"expect": {"pointsHash": "abc"}},
        {"expect": {}},
        {"expectedHash": EMPTY_HASH},
        {"trackId": TRACK["id"]},
        {"points": [{"beat": i, "value": 0, "interpolation": "hold"} for i in range(1025)]},
    ],
)
def test_set_rejects_invalid(changes):
    with pytest.raises(ValidationError):
        validate_call("automation.points.set", {**SET, **changes})


def test_the_owner_union_is_the_automation_row_of_the_owner_matrix():
    adapter: TypeAdapter[Any] = TypeAdapter(AutomationParameterRef)
    assert adapter.validate_python(VOLUME).owner.kind == "track"
    assert adapter.validate_python(GRID).owner.kind == "gridModule"
