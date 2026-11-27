"""devices.insert {params} and modulators.insert {params, map}: one call creates and configures, step by step."""

import asyncio
import copy
from typing import Any

import pytest

from bitwig_agent.core.invoke import Error, invoke
from bitwig_agent.errors import bridge_error, unknown_result
from bitwig_agent.protocol import TOOLS, export_bundle, validate_call
from bitwig_agent.protocol.base import leaf_rule_holds
from bitwig_agent.protocol.devices import DEVICE_REF, INSERT_CAUTION, TRACK_REF
from bitwig_agent.protocol.modulators import DEVICE_REF as GRID_DEVICE
from bitwig_agent.protocol.modulators import GRID_PARAMETER_REF

FILTER = {"kind": "file", "path": "/devices/Filter+.bwdevice"}
NEW_DEVICE = {**DEVICE_REF, "index": 1, "name": "Filter+"}
LFO = {"kind": "modulator", "device": GRID_DEVICE, "id": "abcdef01-2345-6789-abcd-0123456789ab", "name": "LFO"}


def parameter(owner, pid, name):
    return {"ref": {"kind": "parameter", "owner": owner, "id": pid, "name": name}, "name": name, "value": {"value": 0}}


DEVICE_ROWS = [
    parameter(NEW_DEVICE, "CUTOFF", "Cutoff"),
    parameter(NEW_DEVICE, "RESONANCE", "Resonance"),
    parameter(NEW_DEVICE, "MIX_A", "Mix"),
    parameter(NEW_DEVICE, "MIX_B", "Mix"),
]
LFO_ROW: dict[str, Any] = {
    "ref": LFO,
    "name": "LFO",
    "parameters": [parameter(LFO, "RATE", "Rate"), {"name": "Unnamed", "value": {"value": 0}}],
    "outputs": [{"outputId": "out", "mappings": []}],
}
APPLIED_NATIVE = {"outcome": "applied", "verification": "native_readback"}


class Host:
    """Answers each method like the extension would, after checking the request is what the extension accepts."""

    def __init__(self, **overrides):
        self.calls = []
        self.answers = {
            "devices.insert": {**APPLIED_NATIVE, "ref": NEW_DEVICE, "inserted": {"index": 1}},
            "parameters.list": {"parameters": DEVICE_ROWS},
            "parameters.set": APPLIED_NATIVE,
            "modulators.insert": {**APPLIED_NATIVE, "ref": LFO, "modulator": LFO_ROW},
            "modulators.map": APPLIED_NATIVE,
            **overrides,
        }

    async def call(self, method, params, timeout_ms=5000):
        validate_call(method, {**params, "timeoutMs": timeout_ms}, host=True)
        self.calls.append((method, copy.deepcopy(params)))
        answer = self.answers[method]
        if isinstance(answer, list):  # one answer per call
            answer = answer.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return {**answer, "ref": params["target"]} if method == "parameters.set" else answer


def call(host, method, args):
    envelope = asyncio.run(invoke(method, args, host))
    assert not isinstance(envelope, Error), envelope
    return envelope.result


def refusal(host, method, args):
    envelope = asyncio.run(invoke(method, args, host))
    assert isinstance(envelope, Error)
    return envelope.error


def steps(result):
    return [(e["step"], e["outcome"], e.get("reason")) for e in result["entries"]]


def test_devices_insert_sets_each_named_parameter_on_the_new_device():
    host = Host()
    args = {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": {"display": "800 Hz"}, "Resonance": 0.3}}
    result = call(host, "devices.insert", args)
    assert leaf_rule_holds(result)
    assert result["outcome"] == "applied"
    assert result["verification"] == "native_readback"
    assert result["ref"] == NEW_DEVICE
    assert "next" not in result
    assert steps(result) == [
        ("insert", "applied", None),
        ("params.Cutoff", "applied", None),
        ("params.Resonance", "applied", None),
    ]
    assert host.calls == [
        ("devices.insert", {"to": TRACK_REF, "insert": FILTER}),
        ("parameters.list", {"target": NEW_DEVICE}),
        ("parameters.set", {"target": DEVICE_ROWS[0]["ref"], "value": {"display": "800 Hz"}}),
        ("parameters.set", {"target": DEVICE_ROWS[1]["ref"], "value": 0.3}),
    ]


def test_without_params_the_insert_is_the_plain_host_call():
    host = Host()
    assert call(host, "devices.insert", {"to": TRACK_REF, "insert": FILTER}) == host.answers["devices.insert"]
    assert [method for method, _ in host.calls] == ["devices.insert"]


def test_a_later_failure_keeps_the_ref_and_says_how_to_finish_without_reinserting():
    refused = bridge_error("unsupported_unit", "Cutoff shows a text display.")
    host = Host(**{"parameters.set": [refused]})
    args = {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": {"display": "LP"}, "Resonance": 0.3}}
    result = call(host, "devices.insert", args)
    assert leaf_rule_holds(result)
    assert result["outcome"] == "not_applied"
    assert result["reason"] == "unsupported_unit"
    assert result["ref"] == NEW_DEVICE
    assert "verification" not in result
    assert "do not repeat devices.insert" in result["next"]
    assert "params.Cutoff, params.Resonance" in result["next"]
    assert "parameters.set" in result["next"]
    assert steps(result) == [
        ("insert", "applied", None),
        ("params.Cutoff", "not_applied", "unsupported_unit"),
        ("params.Resonance", "not_applied", "not_reached"),
    ]


def test_an_unknown_step_stops_the_run_and_points_at_its_read():
    host = Host(**{"parameters.set": [unknown_result("not_observed", "No callback.")]})
    result = call(
        host, "devices.insert", {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": 0.2, "Resonance": 1}}
    )
    assert result["outcome"] == "unknown"
    assert result["ref"] == NEW_DEVICE
    assert "params.Cutoff is unknown: inspect with parameters.list" in result["next"]


@pytest.mark.parametrize(
    ("name", "reason", "candidates"),
    [
        ("Cutof", "unknown_name", ["Cutoff"]),
        ("Mix", "ambiguous_name", [DEVICE_ROWS[2]["ref"], DEVICE_ROWS[3]["ref"]]),
    ],
)
def test_a_missing_or_shared_name_lists_candidates_and_writes_nothing(name, reason, candidates):
    host = Host()
    result = call(host, "devices.insert", {"to": TRACK_REF, "insert": FILTER, "params": {"Resonance": 0.3, name: 0.5}})
    assert result["outcome"] == "not_applied"
    assert result["reason"] == reason
    assert result["ref"] == NEW_DEVICE
    assert steps(result) == [
        ("insert", "applied", None),
        ("params.Resonance", "not_applied", "not_reached"),
        (f"params.{name}", "not_applied", reason),
    ]
    assert result["entries"][2]["candidates"] == candidates
    assert "parameters.set" not in [method for method, _ in host.calls]


def test_an_unreadable_new_device_refuses_every_step():
    host = Host(**{"parameters.list": bridge_error("not_observed", "No parameters yet.")})
    result = call(host, "devices.insert", {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": 0.2}})
    assert steps(result) == [("insert", "applied", None), ("params.Cutoff", "not_applied", "not_observed")]
    assert result["ref"] == NEW_DEVICE


def test_an_unidentified_device_says_where_to_find_it():
    inserted = {"outcome": "applied", "verification": "api_readback", "inserted": None}
    result = call(
        Host(**{"devices.insert": inserted}),
        "devices.insert",
        {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": 0.2}},
    )
    assert "ref" not in result
    assert result["reason"] == "not_reached"
    assert "find it with devices.list" in result["next"]


def test_an_unknown_insert_runs_nothing_else_and_keeps_its_own_next():
    unknown = unknown_result("not_observed", "No count change.", "devices.list")
    host = Host(**{"devices.insert": unknown})
    result = call(host, "devices.insert", {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": 0.2}})
    assert result["outcome"] == "unknown"
    assert result["next"] == "devices.list"
    assert "ref" not in result
    assert [method for method, _ in host.calls] == ["devices.insert"]


def test_an_insert_error_is_the_call_error():
    host = Host(**{"devices.insert": bridge_error("not_ready", "Project settling.")})
    args = {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": 0.2}}
    assert refusal(host, "devices.insert", args)["reason"] == "not_ready"


def test_a_device_param_value_is_checked_before_anything_is_sent():
    host = Host()
    args = {"to": TRACK_REF, "insert": FILTER, "params": {"Cutoff": {"label": "LP"}}}
    assert "params.Cutoff" in refusal(host, "devices.insert", args)["message"]
    assert host.calls == []


def test_modulators_insert_sets_controls_then_maps_from_the_new_modulator():
    host = Host()
    args = {
        "to": GRID_DEVICE,
        "typeName": "LFO",
        "params": {"Rate": {"display": "0.25 Hz"}},
        "map": [{"target": GRID_PARAMETER_REF, "amount": 0.3, "transfer": "cubic"}],
    }
    result = call(host, "modulators.insert", args)
    assert leaf_rule_holds(result)
    assert result["outcome"] == "applied"
    assert result["verification"] == "native_readback"
    assert result["ref"] == LFO
    assert steps(result) == [("insert", "applied", None), ("params.Rate", "applied", None), ("map[0]", "applied", None)]
    assert host.calls == [
        ("modulators.insert", {"to": GRID_DEVICE, "typeName": "LFO"}),
        ("parameters.set", {"target": LFO_ROW["parameters"][0]["ref"], "value": {"display": "0.25 Hz"}}),
        ("modulators.map", {"source": LFO, "target": GRID_PARAMETER_REF, "amount": 0.3, "transfer": "cubic"}),
    ]


def test_a_failed_mapping_names_modulators_map_as_the_finish():
    host = Host(**{"modulators.map": bridge_error("unsupported_scope", "Bitwig refused the pair.")})
    args = {"to": GRID_DEVICE, "typeName": "LFO", "map": [{"target": GRID_PARAMETER_REF, "amount": 0.3}]}
    result = call(host, "modulators.insert", args)
    assert result["ref"] == LFO
    assert result["reason"] == "unsupported_scope"
    assert "do not repeat modulators.insert" in result["next"]
    assert "modulators.map" in result["next"]
    assert "refs from modulators.get" in result["next"]


def test_an_unnamed_control_is_not_a_candidate():
    result = call(Host(), "modulators.insert", {"to": GRID_DEVICE, "typeName": "LFO", "params": {"Unnamed": 1}})
    assert result["entries"][1]["reason"] == "unknown_name"
    assert result["entries"][1]["candidates"] == ["Rate"]


def test_a_mapping_target_on_another_device_is_refused_before_the_insert():
    grid_ref: dict[str, Any] = GRID_PARAMETER_REF
    other = {**grid_ref, "owner": {**grid_ref["owner"], "device": DEVICE_REF | {"index": 3}}}
    host = Host()
    args = {"to": GRID_DEVICE, "typeName": "LFO", "map": [{"target": other, "amount": 0.3}]}
    assert "map[0].target" in refusal(host, "modulators.insert", args)["message"]
    assert host.calls == []


def test_the_host_never_sees_the_composed_fields():
    for method in ("devices.insert", "modulators.insert"):
        spec = TOOLS[method]
        assert spec.host_model is spec.wire
        assert spec.model is not spec.wire
        assert not {"params", "map"} & spec.wire.model_fields.keys()
        bundled = export_bundle()["methods"][method]["description"]
        assert "params" not in bundled
        assert "params" in spec.description
        assert spec.description.startswith(bundled.split(" Needs a stopped")[0].removesuffix(INSERT_CAUTION))
        assert len(spec.host_examples) == len(spec.examples) - 1
        with pytest.raises(ValueError, match="params"):
            validate_call(method, {**spec.examples[-1]}, host=True)


def test_a_host_ref_the_client_refuses_is_a_not_applied_step_not_an_error():
    rows = [{"ref": {"kind": "parameter", "owner": NEW_DEVICE, "id": "", "name": "Drive"}, "name": "Drive"}]
    host = Host(**{"parameters.list": {"parameters": rows}})
    result = call(host, "devices.insert", {"to": TRACK_REF, "insert": FILTER, "params": {"Drive": 0.5}})
    assert steps(result) == [("insert", "applied", None), ("params.Drive", "not_applied", "invalid_params")]
    assert result["ref"] == NEW_DEVICE
