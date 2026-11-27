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
FREQUENCY = {"kind": "parameter", "owner": DEVICE, "id": "CONTENTS/PID1a", "name": "Frequency"}
CHAIN = {"kind": "chain", "device": DEVICE, "id": "33333333-3333-3333-3333-333333333333", "name": "Layer 1"}
NESTED = {
    "kind": "parameter",
    "owner": {"kind": "device", "chain": CHAIN, "index": 0, "name": "Filter"},
    "id": "CONTENTS/PID1a",
}
SET: dict[str, Any] = {
    "target": VOLUME,
    "expect": {"pointsHash": EMPTY_HASH},
    "points": [
        {"beat": 0, "value": 0.25, "interpolation": "linear"},
        {"beat": 4.5, "value": 1, "interpolation": "hold"},
    ],
}


def test_get_takes_no_range_all():
    with pytest.raises(ValidationError):
        validate_call("automation.points.get", {"target": PAN, "range": "all"})


def test_get_takes_a_track_grid_or_device_parameter_ref_and_a_range():
    for target in (VOLUME, PAN, GRID, FREQUENCY, NESTED):
        args = {"target": target}
        assert validate_call("automation.points.get", args)[0] == args
    ranged = {"target": PAN, "range": {"start": 8, "end": 8}}
    assert validate_call("automation.points.get", ranged)[0] == ranged


@pytest.mark.parametrize(
    "target",
    [
        {**VOLUME, "id": "mute"},
        {
            "kind": "parameter",
            "owner": {"kind": "modulator", "device": DEVICE, "id": MODULE["id"], "name": "LFO"},
            "id": "RATE",
        },
        {**FREQUENCY, "id": ""},
        {**GRID, "id": ""},
        {k: v for k, v in GRID.items() if k != "id"},
        {"kind": "track", "id": TRACK["id"], "name": "Lead"},
    ],
)
def test_get_rejects_other_owners_and_malformed_refs(target):
    with pytest.raises(ValidationError):
        validate_call("automation.points.get", {"target": target})


@pytest.mark.parametrize(
    ("owner", "next_method"),
    [
        ({"kind": "remotePage", "device": DEVICE, "index": 0, "name": "Main"}, "parameters.list"),
        ({"kind": "modulator", "device": DEVICE, "id": MODULE["id"], "name": "LFO"}, None),
    ],
)
def test_remote_control_and_modulator_parameters_are_unsupported_scope(owner, next_method):
    with pytest.raises(ValidationError) as rejected:
        validate_call("automation.points.get", {"target": {"kind": "parameter", "owner": owner, "id": "0"}})
    error = invalid_params("automation.points.get", rejected.value).error
    assert (error["code"], error["reason"], error.get("next")) == ("invalid_params", "unsupported_scope", next_method)


def test_set_takes_display_values_and_curves():
    points = [
        {"beat": 32, "value": {"display": "420 Hz"}},
        {"beat": 60, "value": {"display": "420 Hz"}, "curve": 0.6},
        {"beat": 64, "value": 0.8, "interpolation": "hold", "curve": -1},
    ]
    args = {"target": FREQUENCY, "points": points, "range": {"start": 32, "end": 64}}
    assert validate_call("automation.points.set", args)[0] == args


def test_set_upsert_range_all_and_clear():
    assert validate_call("automation.points.set", SET)[0] == SET
    ranged = {**SET, "target": GRID, "range": {"start": 0, "end": 4.5}}
    assert validate_call("automation.points.set", ranged)[0] == ranged
    clear = {**SET, "points": [], "range": {"start": 2, "end": 3}}
    assert validate_call("automation.points.set", clear)[0]["points"] == []
    unguarded = {key: value for key, value in SET.items() if key != "expect"}
    assert validate_call("automation.points.set", unguarded)[0] == unguarded
    whole = {**SET, "range": "all", "points": [{"beat": 9, "value": 0.5}]}
    assert validate_call("automation.points.set", whole)[0] == whole


@pytest.mark.parametrize(
    "changes",
    [
        {"points": [SET["points"][1], SET["points"][0]]},
        {"points": [{"beat": 1, "value": 0}, {"beat": 1.0000003, "value": 1}, {"beat": 0.9999, "value": 0.5}]},
        {"points": [{**SET["points"][0], "value": 1.5}]},
        {"points": [{**SET["points"][0], "interpolation": "bezier"}]},
        {"points": [{**SET["points"][0], "curve": 1.5}]},
        {"points": [{**SET["points"][0], "curve": -1.01}]},
        {"points": [{**SET["points"][0], "value": {"display": ""}}]},
        {"points": [{**SET["points"][0], "value": {"label": "Off"}}]},
        {"points": [{**SET["points"][0], "value": {"display": "-6 dB", "label": "x"}}]},
        {"range": {"start": 1, "end": 2}},
        {"range": {"start": 5, "end": 4}},
        {"range": "everything"},
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
    assert adapter.validate_python(FREQUENCY).owner.kind == "device"
    assert adapter.validate_python(NESTED).owner.chain.kind == "chain"


def test_a_step_is_two_points_at_one_beat_in_order():
    step = {**SET, "points": [{"beat": 8, "value": 0.2}, {"beat": 8, "value": 0.8, "interpolation": "hold"}]}
    assert validate_call("automation.points.set", step)[0] == step
    snapped = {**SET, "points": [{"beat": 1, "value": 0}, {"beat": 1.0000003, "value": 1}]}
    assert validate_call("automation.points.set", snapped)[0] == snapped


def test_any_number_of_points_at_one_beat_but_never_backwards():
    three = {**SET, "points": [{"beat": 2, "value": v} for v in (0.1, 0.2, 0.3)]}
    assert validate_call("automation.points.set", three)[0] == three
    backwards = {**SET, "points": [{"beat": 2, "value": 0}, {"beat": 1, "value": 0}]}
    with pytest.raises(ValidationError, match="non-decreasing"):
        validate_call("automation.points.set", backwards)
