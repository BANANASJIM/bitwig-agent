import pytest
from pydantic import ValidationError

from bitwig_agent.catalog import EMPTY_HASH, validate_call

TRACK = "11111111-1111-1111-1111-111111111111"
MODULE = "22222222-2222-2222-2222-222222222222"
GRID = {"kind": "grid", "deviceIndex": 0, "expectedDeviceName": "Poly Grid", "moduleId": MODULE, "parameterId": "CUTOFF"}
SET = {"trackId": TRACK, "target": {"kind": "volume"}, "expectedCount": 0, "expectedHash": EMPTY_HASH,
       "points": [{"beat": 0, "value": 0.25, "interpolation": "linear"}, {"beat": 4.5, "value": 1, "interpolation": "hold"}]}


def test_get_targets_and_range():
    for target in ({"kind": "volume"}, {"kind": "pan"}, GRID, {**GRID, "parameterId": ""}):
        args = {"trackId": TRACK, "target": target}
        assert validate_call("automation.points.get", args)[0] == args
    ranged = {"trackId": TRACK, "target": {"kind": "pan"}, "range": {"startBeat": 8, "endBeat": 8}}
    assert validate_call("automation.points.get", ranged)[0] == ranged


@pytest.mark.parametrize("target", [
    {"kind": "mute"}, {"kind": "volume", "deviceIndex": 0}, {"kind": "grid"}, {**GRID, "deviceIndex": 16},
    {**GRID, "moduleId": "x"}, {**GRID, "parameterId": "p" * 1025}, {**GRID, "expectedDeviceName": ""}, {k: v for k, v in GRID.items() if k != "parameterId"}])
def test_get_rejects_bad_targets(target):
    with pytest.raises(ValidationError):
        validate_call("automation.points.get", {"trackId": TRACK, "target": target})


def test_set_whole_lane_range_and_clear():
    assert validate_call("automation.points.set", SET)[0] == SET
    ranged = {**SET, "target": GRID, "range": {"startBeat": 0, "endBeat": 4.5}}
    assert validate_call("automation.points.set", ranged)[0] == ranged
    clear = {**SET, "points": [], "range": {"startBeat": 2, "endBeat": 3}}
    assert validate_call("automation.points.set", clear)[0]["points"] == []


@pytest.mark.parametrize("changes", [
    {"points": [SET["points"][1], SET["points"][0]]},
    {"points": [SET["points"][0], {**SET["points"][0], "value": 0.5}]},
    {"points": [{**SET["points"][0], "value": 1.5}]}, {"points": [{**SET["points"][0], "interpolation": "bezier"}]},
    {"points": [{**SET["points"][0], "curve": 0.5}]}, {"range": {"startBeat": 1, "endBeat": 2}},
    {"range": {"startBeat": 5, "endBeat": 4}}, {"expectedHash": EMPTY_HASH.upper()}, {"expectedHash": "abc"},
    {"expectedCount": -1}, {"expectedCount": 65537},
    {"points": [{"beat": i, "value": 0, "interpolation": "hold"} for i in range(1025)]}])
def test_set_rejects_invalid(changes):
    with pytest.raises(ValidationError):
        validate_call("automation.points.set", {**SET, **changes})
