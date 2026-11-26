"""grid.apply against a fake Grid host that mirrors the guarded grid.* contracts (CAS, batch statuses)."""

import copy
import json
import math
import re
from datetime import datetime
from typing import Any

import pytest
from fakes import SPEC, TARGET, FakeGrid, apply_grid
from pydantic import ValidationError

from bitwig_agent import grid_apply, raw_scales
from bitwig_agent.cli import parse_arguments
from bitwig_agent.errors import BridgeError
from bitwig_agent.protocol import validate_call


def test_fresh_build_then_idempotent_reapply_writes_nothing():
    host = FakeGrid()
    host.shift = 1  # native reflow: every insert lands one column right, forcing moves
    result = apply_grid(host, SPEC)
    assert result["status"] == "applied"
    assert result["applied"] == {
        "inserted": 5,
        "renamed": 5,
        "moved": 5,
        "paramsWritten": 5,
        "connections": 3,
        "disconnected": 0,
        "deleted": 0,
    }
    assert set(result["moduleIds"]) == {m["name"] for m in SPEC["modules"]}
    assert [(m["x"], m["y"]) for m in (host.named(s["name"]) for s in SPEC["modules"])] == [
        (s["x"], s["y"]) for s in SPEC["modules"]
    ]
    assert host.param("H: env", "ATTACK") == 0.5
    assert host.param("H: acc", "STEPS") == 4
    assert host.param("H: acc", "MODE") == next(
        o["value"] for o in host.catalog["Accents"]["parameters"][3]["options"] if o["label"] == "Trigger"
    )
    assert host.source("H: add", "IN") == ("H: mul", "OUT")
    assert host.source("H: lp", "IN") == ("H: acc", "OUT")
    inserts = [c for m, c in host.calls if m == "grid.modules.insert"]
    assert len(inserts) == 1
    assert all("typeId" in e and "typeName" not in e for e in inserts[0]["modules"])
    host.calls.clear()
    again = apply_grid(host, SPEC)
    assert again["status"] == "unchanged"
    assert host.writes() == []
    assert again["applied"] == dict.fromkeys(again["applied"], 0)
    assert again["alreadyMatched"] == {"modules": 5, "positions": 5, "params": 5, "connections": 3}


def test_insert_batches_of_sixteen():
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    host = FakeGrid()
    assert apply_grid(host, spec)["applied"]["inserted"] == 20
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [16, 4]


def test_topology_writes_get_the_long_timeout():
    host = FakeGrid()
    host.shift = 1
    apply_grid(host, SPEC)
    by_method: dict[str, set[int]] = {}
    for method, timeout in host.timeouts:
        by_method.setdefault(method, set()).add(timeout)
    for method in ("grid.modules.insert", "grid.modules.rename", "grid.modules.move", "grid.connections.set"):
        assert by_method[method] == {60000}, method
    assert by_method["grid.parameters.set"] == {10000}
    assert by_method["grid.get"] == {5000}


def test_insert_batches_shrink_when_entries_are_slow(monkeypatch):
    host = FakeGrid()
    monkeypatch.setattr(grid_apply, "monotonic", lambda: host.now)
    host.insert_seconds = 2.0  # 16 entries x 2 s = 32 s > 25% of the 60 s timeout
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(30)]}
    assert apply_grid(host, spec)["applied"]["inserted"] == 30
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [16, 7, 7]


def test_insert_batches_start_halved_on_a_large_graph():
    host = FakeGrid()
    for i in range(grid_apply.LARGE_GRAPH_MODULES):
        host.add("Add", f"other {i}", x=i * 3, y=40)
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    assert apply_grid(host, spec)["applied"]["inserted"] == 20
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [8, 12]


def test_next_insert_batch_uses_slowest_entry_and_host_time_stops():
    applied = [{"status": "applied"}] * 4
    assert grid_apply.next_insert_batch(16, 0.04, {"modules": applied}) == 16
    assert grid_apply.next_insert_batch(16, 0.04, {"modules": applied, "slowestEntryMs": 5000}) == 3
    cut = {"modules": applied + [{"status": "not_dispatched"}] * 4, "stopReason": "deadline_margin"}
    assert grid_apply.next_insert_batch(8, 0.04, cut) == 4


def test_param_change_only():
    host = FakeGrid()
    apply_grid(host, SPEC)
    host.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["modules"][2]["params"]["ATTACK"] = 0.75
    result = apply_grid(host, spec)
    assert host.writes() == ["grid.parameters.set"]
    assert result["applied"]["paramsWritten"] == 1
    assert result["alreadyMatched"]["params"] == 4
    assert host.param("H: env", "ATTACK") == 0.75
    sent = next(c for m, c in host.calls if m == "grid.parameters.set")["writes"][0]
    assert sent["expectedKind"] == "float"
    assert sent["expectedValue"] == 0.5
    assert sent["value"] == 0.75


def test_connection_change_only():
    host = FakeGrid()
    apply_grid(host, SPEC)
    host.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["connections"][0] = {"from": "H: mul.OUT", "to": "H: add.IN2"}
    result = apply_grid(host, spec)
    assert host.writes() == ["grid.connections.set"]
    assert result["applied"]["connections"] == 1
    assert result["alreadyMatched"]["connections"] == 2
    assert host.source("H: add", "IN2") == ("H: mul", "OUT")
    assert host.source("H: add", "IN") == ("H: mul", "OUT")
    # replacing a cable sends the live source as the CAS
    host.calls.clear()
    apply_grid(host, {**spec, "connections": [{"from": "H: env.OUT", "to": "H: add.IN"}]})
    sent = next(c for m, c in host.calls if m == "grid.connections.set")["connections"][0]
    assert sent["expectedSource"] is not None
    assert host.source("H: add", "IN") == ("H: env", "OUT")
    assert host.source("H: add", "IN2") == ("H: mul", "OUT")  # unlisted input left alone without prune


def test_dry_run_plans_without_writing():
    host = FakeGrid()
    result = apply_grid(host, SPEC, dryRun=True)
    assert result["status"] == "dryRun"
    assert host.writes() == []
    assert result["planned"]["inserted"] == 5
    assert result["planned"]["connections"] == 3
    assert result["planned"]["paramsWritten"] == 5
    assert len(result["plan"]["inserts"]) == 5
    assert result["plan"]["connections"][0] == {
        "from": "H: mul.OUT",
        "to": "H: add.IN",
        "previous": None,
    }
    host2 = FakeGrid()
    apply_grid(host2, SPEC)
    host2.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["modules"][2]["params"]["ATTACK"] = 1.0
    spec["modules"][0]["x"] = 40
    plan = apply_grid(host2, spec, dryRun=True)
    assert host2.writes() == []
    assert plan["planned"]["paramsWritten"] == 1
    assert plan["planned"]["moved"] == 1
    assert plan["planned"]["inserted"] == 0
    assert plan["plan"]["moves"][0]["name"] == "H: mul"


def test_validation_failure_writes_nothing_and_suggests():
    host = FakeGrid()
    spec = copy.deepcopy(SPEC)
    spec["modules"][0]["type"] = "Multiplyy"
    spec["modules"][2]["params"] = {"ATAK": 1, "GATE": 1, "SUSTAIN": 7.0}
    spec["connections"] = [{"from": "H: add.OUTT", "to": "H: env.IN"}, {"from": "H: nope.OUT", "to": "H: env.GATE_IN"}]
    with pytest.raises(BridgeError) as err:
        apply_grid(host, spec)
    error = err.value.error
    assert error["code"] == "invalid_spec"
    assert error["outcome"] == "not_dispatched"
    text = " ".join(error["details"]["problems"])
    for needle in (
        "Multiplyy",
        "Multiply'",
        "ATAK",
        "ATTACK",
        "boolean parameter needs",
        "outside 0.0..1.0",
        "OUTT",
        "'OUT'",
        "nope",
    ):
        assert needle in text
    assert host.writes() == []


def test_existing_module_of_wrong_type_is_rejected_before_any_write():
    host = FakeGrid()
    host.add("Add", "H: mul")
    with pytest.raises(BridgeError) as err:
        apply_grid(host, SPEC)
    assert "H: mul" in err.value.error["message"]
    assert host.writes() == []


def test_stop_on_unknown_never_retries_and_reports():
    host = FakeGrid()
    raw = {
        "status": "observed",
        "batchStatus": "unknown",
        "completion": "unknown",
        "writes": [{"parameterId": "ATTACK", "status": "unknown"}],
    }
    host.fail["grid.parameters.set"] = raw
    result = apply_grid(host, SPEC)
    assert result["status"] == "unknown"
    assert result["failure"] == {"phase": "params", "method": "grid.parameters.set", "response": raw}
    assert result["notDispatched"]["phasesNotStarted"] == ["connections"]
    assert result["applied"]["inserted"] == 5
    assert result["applied"]["connections"] == 0
    assert [m for m, _ in host.calls].count("grid.parameters.set") == 1
    assert "grid.connections.set" not in [m for m, _ in host.calls]


def test_stop_on_partial_and_on_host_error():
    host = FakeGrid()
    host.fail["grid.connections.set"] = {
        "status": "observed",
        "batchStatus": "partial",
        "completion": "complete",
        "connections": [{"status": "applied"}, {"status": "mismatch"}, {"status": "not_dispatched"}],
    }
    result = apply_grid(host, SPEC)
    assert result["status"] == "partial"
    assert result["failure"]["response"]["batchStatus"] == "partial"
    assert result["applied"]["paramsWritten"] == 5

    host = FakeGrid()
    host.fail["grid.modules.rename"] = BridgeError({"code": "stale_context", "message": "no", "outcome": "not_applied"})
    result = apply_grid(host, SPEC)
    assert result["status"] == "partial"  # inserts already landed
    assert result["failure"]["response"]["error"]["code"] == "stale_context"
    assert result["notDispatched"]["phasesNotStarted"] == ["move", "params", "connections"]
    assert [m for m, _ in host.calls].count("grid.modules.rename") == 1

    host = FakeGrid()
    host.fail["grid.modules.insert"] = BridgeError({"code": "x", "message": "m", "outcome": "unknown"})
    assert apply_grid(host, SPEC)["status"] == "unknown"


def test_read_error_before_any_write_is_a_plain_error():
    host = FakeGrid()
    host.fail["grid.get"] = BridgeError({"code": "stale_context", "message": "wrong device", "outcome": "not_applied"})
    with pytest.raises(BridgeError) as err:
        apply_grid(host, SPEC)
    assert err.value.error["code"] == "stale_context"
    assert host.writes() == []


def populated_namespace_host():
    host = FakeGrid()
    apply_grid(host, SPEC)
    old = host.add("Add", "H: old", 20, 8)
    keep = host.add("Add", "X: keep", 20, 12)
    host.add("Add", "", 20, 16)  # unnamed
    host.conns[(host.named("H: add")["instanceId"], "IN2")] = (keep, "OUT")  # unlisted input of a spec module
    host.conns[(old, "IN")] = (host.named("H: mul")["instanceId"], "OUT")
    host.calls.clear()
    return host


def test_prune_deletes_only_inside_namespace_and_disconnects_unlisted_inputs():
    host = populated_namespace_host()
    result = apply_grid(host, SPEC, prune=True)
    assert result["applied"]["deleted"] == 1
    assert result["applied"]["disconnected"] == 1
    names = sorted(m["customName"] for m in host.mods.values())
    assert "H: old" not in names
    assert "X: keep" in names
    assert names.count("") == 1
    assert host.source("H: add", "IN2") is None
    deleted = [c for m, c in host.calls if m == "grid.modules.delete"]
    assert len(deleted) == 1


def test_without_prune_nothing_extra_is_touched():
    host = populated_namespace_host()
    result = apply_grid(host, SPEC)
    assert result["status"] == "unchanged"
    assert host.writes() == []
    plan = apply_grid(host, SPEC, prune=True, dryRun=True)
    assert plan["plan"]["deletes"] == ["H: old"]
    assert host.writes() == []
    assert apply_grid(host, SPEC, prune=True, namespace="Z: ", dryRun=True)["plan"]["deletes"] == []


def test_other_host_version_uses_live_catalog_and_checks_ports_after_insert():
    host = FakeGrid(version="9.9.9")
    assert apply_grid(host, SPEC)["status"] == "applied"
    assert any(m == "grid.catalog" for m, _ in host.calls)
    assert host.source("H: lp", "IN") == ("H: acc", "OUT")
    host = FakeGrid(version="9.9.9")
    bad = copy.deepcopy(SPEC)
    bad["connections"][0]["to"] = "H: add.NOPE"
    result = apply_grid(host, bad)
    assert result["status"] == "partial"
    assert result["failure"]["phase"] == "connections"
    assert "NOPE" in result["failure"]["response"]["error"]["message"]
    with pytest.raises(BridgeError):
        apply_grid(
            FakeGrid(version="9.9.9"),
            {**SPEC, "modules": [{"name": "A", "type": "Nonsuch", "x": 0, "y": 0}], "connections": []},
        )


def test_schema_and_cli_surface(tmp_path):
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call("grid.apply", {**SPEC, "modules": SPEC["modules"] + [SPEC["modules"][0]]})
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call("grid.apply", {**SPEC, "modules": [{"name": "A", "x": 0, "y": 0}], "connections": []})
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call("grid.apply", {**SPEC, "connections": SPEC["connections"] + [SPEC["connections"][0]]})
    path = tmp_path / "patch.json"
    path.write_text(json.dumps(SPEC))
    params, _ = parse_arguments("grid.apply", [f"spec=@{path}", "dryRun=true"], None, None)
    assert params["dryRun"] is True
    assert params["connections"][0]["from"] == "H: mul.OUT"
    params, _ = parse_arguments("grid.apply", [], f"@{path}", None)
    assert params["modules"][0]["name"] == "H: mul"
    with pytest.raises(
        ValueError, match=re.escape("Duplicate argument: namespace")
    ):  # a field may not come from both the file and key=value
        parse_arguments("grid.apply", [f"spec=@{path}", "namespace=Q"], None, None)


def test_display_comparison_uses_live_precision():
    assert grid_apply.display_matches("1.2 kHz", "1.20 kHz", "hertz")
    assert grid_apply.display_matches("1200 Hz", "1.20 kHz", "hertz")
    assert not grid_apply.display_matches("1.2 kHz", "1.19 kHz", "hertz")
    assert not grid_apply.display_matches("50 %", None, "percent")


def test_partial_insert_batch_still_names_what_landed_and_reapply_converges():
    spec = {
        "target": TARGET,
        "namespace": "G: ",
        "modules": [{"name": f"G: m{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)],
    }
    host = FakeGrid()
    host.insert_caps = [16, 2]
    result = apply_grid(host, spec)
    assert result["status"] == "partial"
    assert result["applied"]["inserted"] == 18
    assert result["applied"]["renamed"] == 18
    assert sorted(m["customName"] for m in host.mods.values() if m["customName"]) == sorted(
        f"G: m{i}" for i in range(18)
    )
    assert not [m for m in host.mods.values() if not m["customName"]]
    assert "unnamedModules" not in result
    assert result["notDispatched"]["inserted"] == 2
    again = apply_grid(host, spec, prune=True)
    assert again["status"] == "applied"
    assert again["applied"]["inserted"] == 2
    assert again["applied"]["deleted"] == 0
    assert len(host.mods) == 20


def test_unsettled_insert_reports_unnamed_modules():
    host = FakeGrid()
    host.fail["grid.modules.insert"] = {
        "status": "observed",
        "batchStatus": "unknown",
        "completion": "unknown",
        "modules": [{"status": "applied", "moduleId": host.add("Add", "", 50, 50)}, {"status": "unknown"}],
    }
    result = apply_grid(
        host,
        {
            "target": TARGET,
            "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}, {"name": "B", "type": "Add", "x": 3, "y": 0}],
        },
    )
    assert result["status"] == "unknown"
    assert list(result["unnamedModules"].values()) == ["A"]
    assert "grid.modules.rename" not in [m for m, _ in host.calls]


@pytest.mark.parametrize(
    "params",
    [
        {"ATTACK": {"display": "10 hz"}},  # wrong unit for seconds
        {"ATTACK": {"display": "10 s"}},  # raw 2.154 outside 0..2
        {"ATTACK": {"display": "-1 ms"}},
        {"SUSTAIN": {"display": "banana"}},
        {"SUSTAIN": {"display": "500 %"}},  # raw outside range
    ],
)
def test_unwritable_display_is_rejected_offline(params):
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        apply_grid(
            host, {"target": TARGET, "modules": [{"name": "E", "type": "ADSR", "x": 0, "y": 0, "params": params}]}
        )
    assert err.value.error["code"] == "invalid_spec"
    assert host.writes() == []


def test_zero_default_display_is_rejected_offline_but_raw_value_works():
    host = FakeGrid()
    bad: dict[str, Any] = {
        "target": TARGET,
        "modules": [{"name": "B", "type": "Bias", "x": 0, "y": 0, "params": {"VALUE": {"display": "50 %"}}}],
    }
    with pytest.raises(BridgeError) as err:
        apply_grid(host, bad)
    assert "zero" in err.value.error["message"]
    assert host.writes() == []
    bad["modules"][0]["params"] = {"VALUE": 0.5}
    assert apply_grid(host, bad)["applied"]["paramsWritten"] == 1


def parsed_quantity(unit, text):
    result = grid_apply.parse_quantity(unit, text)
    assert result is not None
    return result


def test_display_parser_accepts_sign_unicode_minus_and_leading_dot():
    q = parsed_quantity
    assert q("decibel", "+6.2 dB")[0] == 6.2
    assert q("decibel", "\u22126 dB")[0] == -6
    assert q("percent", ".5 %")[0] == 0.5
    assert q("hertz", "1.2 kHz")[0] == 1200
    assert grid_apply.display_matches("+6.2 dB", "6.20 dB", "decibel")


def test_overlapping_spec_layout_is_rejected_offline():
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        apply_grid(
            host,
            {
                "target": TARGET,
                "modules": [
                    {"name": "A", "type": "ADSR", "x": 0, "y": 0},
                    {"name": "B", "type": "Add", "x": 1, "y": 1},
                ],
            },
        )
    assert "overlap" in err.value.error["message"]
    assert host.writes() == []
    host.add("Add", "other", 0, 0)
    with pytest.raises(BridgeError) as err:
        apply_grid(host, {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}]})
    assert "other" in err.value.error["message"]
    assert host.writes() == []


def test_swap_is_ordered_through_a_free_anchor_and_terminates():
    host = FakeGrid()
    spec: dict[str, Any] = {
        "target": TARGET,
        "modules": [
            {"name": "A", "type": "Add", "x": 0, "y": 0},
            {"name": "B", "type": "Add", "x": 3, "y": 0},
            {"name": "C", "type": "Add", "x": 6, "y": 0},
        ],
    }
    apply_grid(host, spec)
    swapped = {
        "target": TARGET,
        "modules": [{**spec["modules"][0], "x": 3}, {**spec["modules"][1], "x": 0}, spec["modules"][2]],
    }
    result = apply_grid(host, swapped)  # the fake host rejects any move onto an occupied anchor
    assert result["status"] == "applied"
    assert result["applied"]["moved"] == 3
    assert (host.named("A")["x"], host.named("B")["x"], host.named("C")["x"]) == (3, 0, 6)
    chain = {
        "target": TARGET,
        "modules": [{**spec["modules"][2], "x": 9}, {**spec["modules"][1], "x": 6}, {**spec["modules"][0], "x": 3}],
    }
    assert apply_grid(host, chain)["applied"]["moved"] == 2  # A already sits at 3; C and B vacate first, no parking


def test_already_matched_writes_are_reported_unchanged():
    host = FakeGrid()
    apply_grid(host, SPEC)
    host.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["modules"][2]["params"]["ATTACK"] = 0.9
    host.fail["grid.parameters.set"] = {
        "status": "observed",
        "batchStatus": "applied",
        "completion": "complete",
        "writes": [{"parameterId": "ATTACK", "status": "already_matched"}],
    }
    result = apply_grid(host, spec)
    assert result["status"] == "unchanged"
    assert result["applied"]["paramsWritten"] == 0


def test_null_names_fail_closed_and_unexpected_errors_keep_the_report():
    host = FakeGrid()
    apply_grid(host, SPEC)
    host.calls.clear()
    real = host.row
    host.row = lambda m, params, full=False: {**real(m, params, full), "customName": None}  # type: ignore[method-assign]
    with pytest.raises(BridgeError) as err:
        apply_grid(host, SPEC)
    assert err.value.error["code"] == "invalid_spec"
    assert host.writes() == []
    host = FakeGrid()
    host.fail["grid.connections.set"] = {"status": "observed", "batchStatus": "applied", "connections": [None]}
    result = apply_grid(host, SPEC)
    assert result["status"] == "unknown"
    assert result["failure"]["method"] == "internal"
    assert result["applied"]["inserted"] == 5


def test_park_spot_is_always_free_and_converges():
    host = FakeGrid()
    host.add("Add", "P", 10, 0)
    host.add("Phasor", "A", 0, 0)  # 3x1
    host.add("Add", "B", 4, 0)
    spec = {
        "target": TARGET,
        "modules": [
            {"name": "P", "type": "Add", "x": 2, "y": 0},
            {"name": "A", "type": "Phasor", "x": 4, "y": 0},
            {"name": "B", "type": "Add", "x": 0, "y": 0},
        ],
    }
    result = apply_grid(host, spec)  # the fake host rejects any move onto an occupied anchor
    assert result["status"] == "applied"
    assert result["applied"]["moved"] <= 6
    assert [(host.named(n)["x"], host.named(n)["y"]) for n in "PAB"] == [(2, 0), (4, 0), (0, 0)]
    host.calls.clear()
    assert apply_grid(host, spec)["status"] == "unchanged"
    assert host.writes() == []


def test_order_moves_is_bounded_on_impossible_layouts():
    size = {"A": (3, 1), "B": (3, 1)}
    plan = grid_apply.order_moves({"A": (0, 0), "B": (3, 0)}, {"A": (3, 0), "B": (0, 0)}, size, [])
    assert len(plan) <= 4
    with pytest.raises(grid_apply.SpecError):  # the target is blocked by an unmovable module
        grid_apply.order_moves({"A": (0, 0)}, {"A": (5, 0)}, {"A": (1, 1)}, [(5, 0, 1, 1)])


def test_host_capabilities_bound_the_spec_and_block_quarantined_topology():
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(4)]}
    host = FakeGrid()
    host.caps_extra = {"moduleInsert": {"available": True, "maximumModules": 3}, "moduleMove": {"maximumModules": 5}}
    with pytest.raises(BridgeError) as err:
        apply_grid(host, spec)
    assert "host limit is 3" in err.value.error["message"]
    assert host.writes() == []
    host.caps_extra = {"moduleInsert": {"available": True, "maximumModules": 8, "topologyQuarantined": True}}
    with pytest.raises(BridgeError) as err:
        apply_grid(host, spec)
    assert "topologyQuarantined" in err.value.error["message"]
    assert host.writes() == []
    assert apply_grid(host, spec, dryRun=True)["status"] == "dryRun"
    host.caps_extra = {"moduleInsert": {"available": False}}
    with pytest.raises(BridgeError):
        apply_grid(host, spec)


def test_deadline_cut_batches_continue_with_the_remaining_modules():
    spec = {"target": TARGET, "modules": [{"name": f"D{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    host = FakeGrid()
    host.cut_status = "not_dispatched"  # the host stopped for lack of time, nothing failed
    host.insert_caps = [5, 3]
    result = apply_grid(host, spec)
    assert result["status"] == "applied"
    assert result["applied"]["inserted"] == 20
    assert result["applied"]["renamed"] == 20
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [16, 15, 12]
    assert not [m for m in host.mods.values() if not m["customName"]]


def test_unchanged_spec_reads_only_what_it_needs():
    host = FakeGrid()
    apply_grid(host, SPEC)
    host.calls.clear()
    assert apply_grid(host, SPEC)["status"] == "unchanged"
    names = [m for m, _ in host.calls]
    assert names.count("grid.get") == 2  # structure once, parameters once; no post-insert or post-param re-read
    assert host.writes() == []


def test_display_grammar_matches_the_host_trim_and_ascii_digits():
    q = grid_apply.parse_quantity
    assert q("decibel", "\u00a06 dB") is None
    assert q("decibel", "٣ dB") is None
    assert parsed_quantity("decibel", " 6 dB\t")[0] == 6


def test_string_panel_params_are_written_with_cas_and_idempotent():
    host = FakeGrid()
    text = "line 1\nline 2 \u00e9\U0001f600"
    spec: dict[str, Any] = {
        "target": TARGET,
        "modules": [{"name": "C", "type": "Comment", "x": 0, "y": 0, "params": {"TEXT": text}}],
    }
    assert apply_grid(host, spec)["applied"]["paramsWritten"] == 1
    assert host.param("C", "TEXT") == text
    host.calls.clear()
    assert apply_grid(host, spec)["status"] == "unchanged"
    assert host.writes() == []
    apply_grid(host, {**spec, "modules": [{**spec["modules"][0], "params": {"TEXT": ""}}]})
    sent = next(c for m, c in host.calls if m == "grid.parameters.set")["writes"][0]
    assert sent["expectedKind"] == "string"
    assert sent["expectedValue"] == text
    assert sent["value"] == ""
    assert host.param("C", "TEXT") == ""


def test_string_param_validation():
    host = FakeGrid()
    mod = {"name": "C", "type": "Comment", "x": 0, "y": 0}
    for bad in (5, "x" * 1025, "\U0001f600" * 513):
        with pytest.raises(BridgeError) as err:
            apply_grid(host, {"target": TARGET, "modules": [{**mod, "params": {"TEXT": bad}}]})
        assert err.value.error["code"] == "invalid_spec"
    apply_grid(host, {"target": TARGET, "modules": [{**mod, "params": {"TEXT": "\U0001f600" * 512}}]}, dryRun=True)
    with pytest.raises(BridgeError):
        apply_grid(
            host, {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0, "params": {"X": "s"}}]}
        )
    assert host.writes() == []


def test_empty_modules_prunes_namespace_only():
    host = populated_namespace_host()
    before = {m["customName"] for m in host.mods.values()}
    result = apply_grid(host, {"target": TARGET, "namespace": "H: ", "prune": True, "modules": []})
    assert result["status"] == "applied"
    assert result["applied"]["deleted"] == 6
    left = {m["customName"] for m in host.mods.values()}
    assert left == {"X: keep", ""}
    assert before - left == {n for n in before if n.startswith("H: ")}
    assert (
        apply_grid(host, {"target": TARGET, "namespace": "H: ", "prune": True, "modules": []})["status"] == "unchanged"
    )


@pytest.mark.parametrize("extra", [{}, {"prune": True}, {"namespace": "H: "}])
def test_empty_modules_needs_prune_and_namespace(extra):
    host = FakeGrid()
    with pytest.raises(ValueError, match="modules may be empty"):
        apply_grid(host, {"target": TARGET, "modules": [], **extra})
    assert host.writes() == []


def replace_host():
    host = FakeGrid()
    host.add("Add", "R: a", 0, 0)
    host.add("Add", "R: src", 6, 0)
    host.conns[(host.named("R: a")["instanceId"], "IN")] = (host.named("R: src")["instanceId"], "OUT")
    return host


REPLACE_SPEC: dict[str, Any] = {
    "target": TARGET,
    "modules": [
        {"name": "R: a", "type": "Multiply", "x": 0, "y": 0},
        {"name": "R: src", "type": "Add", "x": 6, "y": 0},
    ],
    "connections": [{"from": "R: src.OUT", "to": "R: a.IN"}],
}


def test_replace_default_refuses_and_opt_in_swaps_type_and_rewires():
    host = replace_host()
    with pytest.raises(BridgeError) as err:
        apply_grid(host, REPLACE_SPEC)
    assert "exists as" in err.value.error["message"]
    assert host.writes() == []
    spec = copy.deepcopy(REPLACE_SPEC)
    other = host.add("Add", "Z: out", 30, 0)
    host.conns[(other, "IN")] = (host.named("R: a")["instanceId"], "OUT")
    plan = apply_grid(host, spec, replace=True, dryRun=True)
    assert plan["plan"]["replaces"] == ["R: a"]
    assert host.writes() == []
    assert plan["plan"]["droppedConnections"] == ["R: a.OUT -> Z: out.IN"]
    assert "R: a.OUT -> Z: out.IN" in plan["warnings"][0]
    result = apply_grid(host, spec, replace=True)
    assert result["status"] == "applied"
    assert result["applied"]["deleted"] == 1
    assert result["applied"]["inserted"] == 1
    assert host.named("R: a")["typeName"] == "Multiply"
    assert host.source("R: a", "IN") == ("R: src", "OUT")
    host.calls.clear()
    assert apply_grid(host, spec, replace=True)["status"] == "unchanged"
    assert host.writes() == []


def test_replace_per_module_only_affects_that_module():
    host = replace_host()
    host.add("Add", "R: other", 12, 0)
    spec = copy.deepcopy(REPLACE_SPEC)
    spec["modules"][0]["replace"] = True
    spec["modules"].append({"name": "R: other", "type": "Multiply", "x": 12, "y": 0})
    with pytest.raises(BridgeError) as err:
        apply_grid(host, spec)
    assert "R: other" in err.value.error["message"]
    assert "'R: a' exists" not in err.value.error["message"]
    assert host.writes() == []
    spec["modules"][1]["replace"] = False
    spec["modules"][2]["replace"] = True
    assert apply_grid(host, spec)["applied"]["deleted"] == 2


def test_notes_and_pitch_mod_warning():
    spec = {
        "target": TARGET,
        "modules": [
            {"name": "o", "type": "Sine", "x": 0, "y": 0},
            {"name": "o2", "type": "Sine", "x": 6, "y": 0, "params": {"PITCH_MOD": 0.5}},
            {"name": "n", "type": "Add", "x": 12, "y": 0},
        ],
        "connections": [{"from": "n.OUT", "to": "o.PITCH_IN"}, {"from": "n.OUT", "to": "o2.PITCH_IN"}],
    }
    host = FakeGrid()
    result = apply_grid(host, spec, dryRun=True)
    assert [w for w in result["warnings"] if "PITCH_MOD" in w] == [
        "o: PITCH_IN is connected but PITCH_MOD is 0, so it has no effect (raw 1.0 = 120 semitones)"
    ]
    result = apply_grid(host, spec)
    assert [w for w in result["warnings"] if "PITCH_MOD" in w] == [
        "o: PITCH_IN is connected but PITCH_MOD is 0, so it has no effect (raw 1.0 = 120 semitones)"
    ]
    assert any(n.startswith("Sine: PITCH_IN is scaled by PITCH_MOD") for n in result["notes"])
    assert not any(n.startswith("Chance:") for n in result["notes"])
    assert any("voice lifetime" in n for n in result["notes"])
    assert len(result["notes"]) < 8


def test_notes_file_is_value_free_and_covers_catalog_types():
    notes = grid_apply.measured_notes()
    assert notes["hostVersion"] == "6.1.4"
    assert set(notes["modules"]) <= set(grid_apply.bundled_schema()["modules"])


# --- seconds display writes ----------------------------------------------------------------------------------
def seconds_spec(params_env, params_gate=None):
    return {
        "target": TARGET,
        "modules": [
            {"name": "E", "type": "ADSR", "x": 0, "y": 0, "params": params_env},
            {"name": "G", "type": "Gate Length", "x": 0, "y": 8, "params": params_gate or {}},
        ],
    }


def test_seconds_display_writes_converted_raw_and_is_idempotent():
    host = FakeGrid()
    spec = seconds_spec(
        {"ATTACK": {"display": "3 ms"}, "DECAY": {"display": "0.5 s"}}, {"LENGTH": {"display": "120 ms"}}
    )
    result = apply_grid(host, spec)
    assert result["applied"]["paramsWritten"] == 3
    writes = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]]
    assert all("display" not in w and "shown" not in w for w in writes)  # the host only sees raw values
    assert host.param("E", "ATTACK") == pytest.approx(0.003 ** (1 / 3))
    assert host.param("E", "DECAY") == pytest.approx(0.5 ** (1 / 3))
    assert host.param("G", "LENGTH") == pytest.approx(math.log10(0.12))
    host.calls.clear()
    again = apply_grid(host, spec)
    assert again["status"] == "unchanged"
    assert host.writes() == []
    assert again["alreadyMatched"]["params"] == 3
    host.named("E")["params"]["ATTACK"].update(
        value=0.144, display="2.99 ms"
    )  # native pair one step off: 3 ms is a change
    assert apply_grid(host, spec)["applied"]["paramsWritten"] == 1


def test_seconds_dry_run_shows_the_converted_raw_value():
    result = apply_grid(FakeGrid(), seconds_spec({"ATTACK": {"display": "8 ms"}}), dryRun=True)
    entry = next(p for p in result["plan"]["params"] if p["parameterId"] == "ATTACK")
    assert entry["rawValue"] == pytest.approx(0.2)
    assert entry["to"] == {"display": "8 ms"}


@pytest.mark.parametrize(
    ("module", "param", "text"),
    [
        ("ADSR", "ATTACK", "1 kb"),
    ],
)
def test_unverified_seconds_display_is_rejected_offline(module, param, text):
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        apply_grid(
            host,
            {
                "target": TARGET,
                "modules": [{"name": "X", "type": module, "x": 0, "y": 0, "params": {param: {"display": text}}}],
            },
        )
    assert err.value.error["code"] == "invalid_spec"
    assert host.writes() == []


def test_seconds_table_matches_bundled_schema_and_nothing_else_fits():
    schema = grid_apply.bundled_schema()["modules"]
    seen = set()
    for name, info in schema.items():
        for p in info["parameters"]:
            if p.get("numericUnit") != "seconds":
                continue
            key = (name, p["parameterId"])
            shown = parsed_quantity("seconds", p["defaultDisplay"])
            fits = [
                k
                for k, (_, from_raw) in grid_apply.SECONDS_SCALES.items()
                if abs(from_raw(p["default"]) - shown[0]) <= shown[1] * 1.000001
            ]
            args = (
                key[1],
                p["defaultDisplay"],
                "seconds",
                p["default"],
                p["defaultDisplay"],
                p["minimum"],
                p["maximum"],
                name,
            )
            if key in grid_apply.SECONDS_PARAMS:
                mapping, raw, display = grid_apply.SECONDS_PARAMS[key]
                seen.add(key)
                assert fits == [mapping]
                assert display == p["defaultDisplay"]
                assert abs(raw - p["default"]) < 1e-4
                assert grid_apply.check_display(*args) == pytest.approx(p["default"], abs=0.01)
            elif key in raw_scales.RAW_SCALES:  # linear seconds: covered by test_raw_scales
                continue
            else:  # not writable by display: no mapping fits, or the only fit is the ambiguous raw 1.0
                assert fits == [] or p["default"] == 1.0, key
                with pytest.raises(grid_apply.SpecError):
                    grid_apply.check_display(*args)
    assert seen == set(grid_apply.SECONDS_PARAMS)
    assert {"AD", "ADSR", "Logic Delay", "Gate Length"} <= {n for n, _ in seen}


def test_seconds_live_schema_converts_against_the_live_pair():
    host = FakeGrid(version="9.9.9")
    spec = seconds_spec({"ATTACK": {"display": "3 ms"}})
    result = apply_grid(host, spec)
    assert result["status"] == "applied"
    assert host.param("E", "ATTACK") == pytest.approx(0.003 ** (1 / 3))
    host.calls.clear()
    assert apply_grid(host, spec)["status"] == "unchanged"
    assert host.writes() == []


# --- blocks ---------------------------------------------------------------------------------------------------
BLOCKS: dict[str, Any] = {
    "voice": {
        "params": {"atk": 0.5, "gain": 0.25, "rel": "20 ms"},
        "modules": [
            {"name": "mul", "type": "Bias", "x": 0, "y": 0, "params": {"VALUE": "${gain}"}},
            {
                "name": "env",
                "type": "ADSR",
                "x": 4,
                "y": 0,
                "params": {"ATTACK": "${atk}", "RELEASE": {"display": "${rel}"}},
            },
        ],
        "connections": [{"from": "mul.OUT", "to": "env.IN"}],
    }
}
BLOCK_SPEC: dict[str, Any] = {
    "target": TARGET,
    "blocks": BLOCKS,
    "namespace": "V: ",
    "modules": [{"name": "V: sum", "type": "Add", "x": 20, "y": 0}],
    "instances": [
        {"block": "voice", "prefix": "V1: "},
        {"block": "voice", "prefix": "V2: ", "dy": 12, "args": {"atk": 0.1, "gain": 0.75}},
    ],
    "connections": [{"from": "V1: env.OUT", "to": "V: sum.IN"}, {"from": "V2: env.OUT", "to": "V: sum.IN2"}],
}


def test_blocks_expand_with_prefix_offset_and_typed_placeholders():
    host = FakeGrid()
    result = apply_grid(host, BLOCK_SPEC, dryRun=True)
    assert [i["modules"] for i in result["instances"]] == [["V1: mul", "V1: env"], ["V2: mul", "V2: env"]]
    assert {(i["name"], i["x"], i["y"]) for i in result["plan"]["inserts"]} >= {
        ("V1: mul", 0, 0),
        ("V2: env", 4, 12),
        ("V: sum", 20, 0),
    }
    assert len(result["plan"]["inserts"]) == 5
    assert host.writes() == []
    result = apply_grid(host, BLOCK_SPEC)
    assert result["status"] == "applied"
    assert result["instances"][1]["prefix"] == "V2: "
    assert result["applied"]["inserted"] == 5
    assert result["applied"]["connections"] == 4
    assert host.param("V1: env", "ATTACK") == 0.5
    assert host.param("V2: env", "ATTACK") == 0.1
    assert host.param("V2: mul", "VALUE") == 0.75
    assert host.param("V1: mul", "VALUE") == 0.25
    assert host.param("V1: env", "RELEASE") == pytest.approx(
        0.02 ** (1 / 3)
    )  # {display} placeholder reaches the seconds conversion
    assert (host.named("V2: env")["x"], host.named("V2: env")["y"]) == (4, 12)
    assert host.source("V1: env", "IN") == ("V1: mul", "OUT")
    assert host.source("V: sum", "IN2") == ("V2: env", "OUT")
    host.calls.clear()
    assert apply_grid(host, BLOCK_SPEC)["status"] == "unchanged"
    assert host.writes() == []


def test_blocks_are_checked_after_expansion():
    host = FakeGrid()

    def fails(spec, text):
        with pytest.raises(BridgeError) as err:
            apply_grid(host, spec)
        assert err.value.error["code"] == "invalid_spec"
        assert text in err.value.error["message"]
        assert host.writes() == []

    fails({**BLOCK_SPEC, "instances": [{"block": "voic", "prefix": "V1: "}]}, "unknown block")
    fails({**BLOCK_SPEC, "instances": [{"block": "voice", "prefix": "V1: ", "args": {"atack": 1}}]}, "unknown args")
    fails(
        {
            **BLOCK_SPEC,
            "instances": [{"block": "voice", "prefix": "V1: "}, {"block": "voice", "prefix": "V1: ", "dy": 12}],
        },
        "distinct prefixes",
    )
    fails(
        {**BLOCK_SPEC, "instances": [{"block": "voice", "prefix": "V1: "}, {"block": "voice", "prefix": "V2: "}]},
        "overlap",
    )  # same dx/dy
    fails(
        {
            **BLOCK_SPEC,
            "instances": [
                {"block": "voice", "prefix": "V1: ", "args": {"atk": 9}},
                {"block": "voice", "prefix": "V2: ", "dy": 12},
            ],
        },
        "outside",
    )
    bad = {"voice": {**BLOCKS["voice"], "connections": [{"from": "V1: mul.OUT", "to": "env.IN"}]}}
    fails({**BLOCK_SPEC, "blocks": bad}, "local module name")
    typo = {
        "voice": {
            **BLOCKS["voice"],
            "modules": [
                {**BLOCKS["voice"]["modules"][0], "params": {"VALUE": "${gian}"}},
                BLOCKS["voice"]["modules"][1],
            ],
        }
    }
    fails({**BLOCK_SPEC, "blocks": typo}, "placeholder")
    wrong_type = {
        "voice": {
            **BLOCKS["voice"],
            "modules": [
                {**BLOCKS["voice"]["modules"][0], "params": {"VALUE": "${rel}"}},
                BLOCKS["voice"]["modules"][1],
            ],
        }
    }
    fails({**BLOCK_SPEC, "blocks": wrong_type}, "needs a number")
    fails(
        {**BLOCK_SPEC, "connections": BLOCK_SPEC["connections"] + [{"from": "V: sum.OUT", "to": "V1: env.IN"}]},
        "repeats connection target",
    )


def test_blocks_schema_surface():
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call("grid.apply", {"target": TARGET, "instances": [{"block": "x"}]})  # instances without blocks
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call("grid.apply", {"target": TARGET})  # nothing to apply
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridApplyArgs")):  # blocks do not nest
        validate_call(
            "grid.apply",
            {
                **BLOCK_SPEC,
                "blocks": {"b": {"modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}], "instances": []}},
            },
        )
    args, _ = validate_call("grid.apply", {"target": TARGET, "namespace": "V: ", "prune": True})
    assert "modules" not in args


# --- step arrays and voicing ----------------------------------------------------------------------------------
POLY = {
    "voices": 1,
    "voiceStacking": 1,
    "monoMode": "true_mono",
    "retriggerMode": "on_note_on",
    "notePriority": "last",
    "legatoGlide": False,
    "stealSameKey": False,
    "allowOverlappingNotes": False,
}


def steps_spec(steps, count=8, type_name="Pitches"):
    return {
        "target": TARGET,
        "modules": [
            {"name": "S", "type": type_name, "x": 0, "y": 0, "params": {"STEPS": count, "DATA": {"steps": steps}}}
        ],
    }


def test_steps_are_written_with_prefix_cas_and_idempotent():
    host = FakeGrid()
    spec = steps_spec([1, 0, 0.5, 0.1])
    result = apply_grid(host, spec)
    assert result["status"] == "applied"
    assert host.param("S", "DATA")[:5] == [1.0, 0.0, 0.5, grid_apply.f32(0.1), 0.0]
    sent = [c for m, c in host.calls if m == "grid.parameters.set"]
    entry = next(w for c in sent for w in c["writes"] if w["expectedKind"] == "steps")
    assert entry["expectedValues"] == [0.0] * 4
    assert entry["values"] == [1, 0, 0.5, 0.1]
    assert "expectedValue" not in entry
    host.calls.clear()
    again = apply_grid(host, spec)
    assert again["status"] == "unchanged"
    assert host.writes() == []


def test_steps_change_writes_only_the_differing_array_and_reads_full_for_long_lists():
    host = FakeGrid()
    apply_grid(host, steps_spec([1, 1, 1], count=4))
    host.calls.clear()
    result = apply_grid(host, steps_spec([1, 1, 0] + [0.25] * 7, count=4))  # 10 entries > STEPS 4
    assert result["applied"]["paramsWritten"] == 1
    entry = next(w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"])
    assert entry["full"] is True
    assert entry["expectedValues"] == [1.0, 1.0, 1.0] + [0.0] * 7
    assert host.param("S", "DATA")[:10] == [1, 1, 0] + [0.25] * 7
    assert any(c.get("full") for m, c in host.calls if m == "grid.get")


def test_steps_dry_run_and_offline_validation_write_nothing():
    host = FakeGrid()
    assert apply_grid(host, steps_spec([1, 2]), dryRun=True)["status"] == "dryRun"
    assert host.writes() == []
    for spec in (steps_spec([1, 2], type_name="Add"), steps_spec([0] * 257), steps_spec([500]), steps_spec([])):
        with pytest.raises(BridgeError, match="nothing was written"):
            apply_grid(host, spec)
    assert host.writes() == []


def test_steps_longer_than_live_array_rejects_before_writing_on_existing_module():
    host = FakeGrid()
    host.add("Pitches", "S", 0, 0)
    with pytest.raises(BridgeError) as err:
        apply_grid(host, steps_spec([0.5] * 65))
    assert "array holds 64" in err.value.error["message"]
    assert host.writes() == []


def test_steps_block_placeholder_is_a_whole_list():
    host = FakeGrid()
    spec = {
        "target": TARGET,
        "blocks": {
            "seq": {
                "params": {"row": [0.0]},
                "modules": [{"name": "g", "type": "Gates", "x": 0, "y": 0, "params": {"DATA": {"steps": "${row}"}}}],
            }
        },
        "instances": [
            {"block": "seq", "prefix": "A: ", "args": {"row": [1, 0, 1, 1]}},
            {"block": "seq", "prefix": "B: ", "dy": 6},
        ],
    }
    result = apply_grid(host, spec)
    assert result["status"] == "applied"
    assert host.param("A: g", "DATA")[:4] == [1, 0, 1, 1]
    assert host.param("B: g", "DATA")[:2] == [0, 0]
    host.calls.clear()
    assert apply_grid(host, spec)["status"] == "unchanged"
    assert host.writes() == []


def test_voicing_is_written_when_different_and_idempotent():
    host = FakeGrid()
    host.voicing = dict(POLY)
    spec = {
        "target": TARGET,
        "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}],
        "voicing": {"voices": 8, "voiceStacking": 1, "monoMode": "true_mono"},
    }
    result = apply_grid(host, spec)
    assert result["applied"]["voicingFields"] == 1
    assert result["alreadyMatched"]["voicingFields"] == 2
    sent = [c for m, c in host.calls if m == "grid.voicing.set"]
    assert sent[0]["expected"] == {"voices": 1}
    assert sent[0]["set"] == {"voices": 8}
    assert host.voicing["voices"] == 8
    host.calls.clear()
    again = apply_grid(host, spec)
    assert again["status"] == "unchanged"
    assert host.writes() == []
    plan = apply_grid(host, {**spec, "voicing": {"voices": 2}}, dryRun=True)
    assert plan["plan"]["voicing"] == {"set": {"voices": 2}, "from": {"voices": 8}}
    assert host.voicing["voices"] == 8


def test_voicing_only_spec_and_stale_voicing_is_not_retried():
    host = FakeGrid()
    host.voicing = dict(POLY)
    spec = {"target": TARGET, "modules": [], "prune": True, "namespace": "H: ", "voicing": {"monoMode": "digi_mono"}}
    assert apply_grid(host, spec)["applied"]["voicingFields"] == 1
    assert host.voicing["monoMode"] == "digi_mono"


def test_voicing_on_non_poly_grid_is_refused_before_any_write():
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        apply_grid(host, {**SPEC, "voicing": {"voices": 4}})
    assert err.value.error["code"] == "invalid_spec"
    assert "no voicing model" in err.value.error["message"]
    assert host.writes() == []


def test_voicing_schema_surface():
    with pytest.raises(ValidationError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call(
            "grid.apply", {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}], "voicing": {}}
        )
    with pytest.raises(ValidationError, match=re.escape("1 validation error for GridApplyArgs")):
        validate_call(
            "grid.apply",
            {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}], "voicing": {"voices": 65}},
        )


# --- preflight warnings, wouldFail and actionable errors ------------------------------------------------------
def messages(result, text):
    return [w for w in result.get("warnings", []) if text in w]


def test_device_phase_cable_warning_only_while_device_phase_is_on():
    mods = [
        {"name": "ph", "type": "Phasor", "x": 0, "y": 0},
        {"name": "st", "type": "Steps", "x": 8, "y": 0},
        {"name": "st2", "type": "Gates", "x": 16, "y": 0, "params": {"DEVICE_PHASE": False}},
    ]
    conns = [{"from": "ph.OUT", "to": "st.PHASE_IN"}, {"from": "ph.OUT", "to": "st2.PHASE_IN"}]
    result = apply_grid(FakeGrid(), {"target": TARGET, "modules": mods, "connections": conns}, dryRun=True)
    assert [w.split(":")[0] for w in messages(result, "DEVICE_PHASE")] == ["st"]
    assert any(n.startswith("Steps: DEVICE_PHASE defaults to On") for n in result["notes"])


def test_keytrack_warning_needs_a_poly_grid_and_nonzero_keytrack():
    mods = [
        {"name": "f", "type": "SVF", "x": 0, "y": 0},
        {"name": "g", "type": "SVF", "x": 8, "y": 0, "params": {"KEYTRACK": 0}},
    ]
    conns = [{"from": "f.OUT", "to": "g.IN"}]
    spec = {"target": TARGET, "modules": mods, "connections": conns}
    note_grid = apply_grid(FakeGrid(), spec, dryRun=True)
    assert not messages(note_grid, "KEYTRACK")
    host = FakeGrid()
    host.voicing = {"voices": 8, "voiceStacking": 1, "monoMode": "true_mono"}
    result = apply_grid(host, spec, dryRun=True)
    assert [w.split(":")[0] for w in messages(result, "KEYTRACK")] == ["f"]


def test_dangling_modules_are_flagged_but_note_in_is_not():
    mods = [
        {"name": "n", "type": "Note In", "x": 0, "y": 0},
        {"name": "lonely", "type": "Add", "x": 0, "y": 8},
        {"name": "o", "type": "Sine", "x": 8, "y": 0},
        {"name": "m", "type": "Add", "x": 16, "y": 0},
    ]
    result = apply_grid(
        FakeGrid(),
        {"target": TARGET, "modules": mods, "connections": [{"from": "m.OUT", "to": "o.PITCH_IN"}]},
        dryRun=True,
    )
    assert messages(result, "lonely: has no connections")
    assert not messages(result, "n: ")
    assert messages(result, "o: has connected inputs but none of its outputs")


def one(module_type, params, extra=None, spec=None, **kw):
    mods = [{"name": "X", "type": module_type, "x": 0, "y": 0, "params": params}] + (extra or [])
    return apply_grid(FakeGrid(), {"target": TARGET, "modules": mods, **(spec or {})}, **kw)


@pytest.mark.parametrize("dry", [True, False])
def test_raw_write_to_nonlinear_scale_warns_with_effective_display(dry):
    result = one("Amplify", {"VALUE": 0.5}, dryRun=dry)
    [w] = messages(result, "VALUE raw 0.5")
    assert "12.5 % (-18.1 dB)" in w
    assert '{"display": "-6 dB"}' in w
    assert not messages(one("Amplify", {"VALUE": {"display": "-6 dB"}}, dryRun=dry), "raw")
    assert not messages(one("Amplify", {"VALUE": 1.0}, dryRun=dry), "raw")  # the default
    assert not messages(one("Attenuate", {"VALUE": 0.5}, dryRun=dry), "raw")  # linear scale
    assert messages(one("Mixer", {"LEVEL_1": 0.5}, dryRun=dry), "LEVEL_1 raw 0.5")


@pytest.mark.parametrize(
    ("params", "warns"),
    [
        ({"TIME": {"display": "300 ms"}}, True),
        ({"TIME": 0.5}, True),
        ({"TIME": 0.5, "UNIT": {"label": "Seconds"}}, False),
        ({"UNIT": 1}, False),
    ],
)
@pytest.mark.parametrize("module", ["Mod Delay", "Long Delay"])
def test_time_is_ignored_while_unit_is_a_note_value(module, params, warns):
    result = one(module, params, dryRun=True)
    found = messages(result, "TIME is ignored")
    assert bool(found) == warns
    if warns:
        assert '"label":"Seconds"' in found[0]
        assert "16th" in found[0]
        assert not messages(result, "TIME raw")  # one warning, not two


def test_oscillator_with_gate_in_but_retrigger_off_warns():
    extra = [{"name": "g", "type": "Note In", "x": 0, "y": 8}]
    spec = {"connections": [{"from": "g.GATE_OUT", "to": "X.GATE_IN"}]}
    assert messages(one("Sine", {}, extra, spec, dryRun=True), "RETRIGGER is off")
    assert not messages(one("Sine", {"RETRIGGER": True}, extra, spec, dryRun=True), "RETRIGGER")
    assert not messages(one("Sine", {}, dryRun=True), "RETRIGGER")


def test_dry_run_would_fail_reports_transport_and_stays_read_only():
    host = FakeGrid()
    host.transport = {"playing": True}
    spec = {"target": TARGET, "modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}]}
    result = apply_grid(host, spec, dryRun=True)
    assert [f["code"] for f in result["wouldFail"]] == ["unsafe_state"]
    assert host.writes() == []
    host.transport = {"playing": False}
    assert "wouldFail" not in apply_grid(host, spec, dryRun=True)


def test_dry_run_would_fail_flags_host_converted_display_and_missing_target():
    spec = {
        "target": TARGET,
        "modules": [{"name": "f", "type": "SVF", "x": 0, "y": 0, "params": {"CUTOFF": {"display": "800 Hz"}}}],
    }
    result = apply_grid(FakeGrid(), spec, dryRun=True)
    assert [f["code"] for f in result["wouldFail"]] == ["display_unverified"]
    host = FakeGrid()
    host.fail["grid.get"] = BridgeError({"code": "target_not_found", "message": "no device", "outcome": "not_applied"})
    result = apply_grid(host, spec, dryRun=True)
    assert result["status"] == "dryRun"
    assert result["wouldFail"][0]["code"] == "target_not_found"
    assert host.writes() == []
    with pytest.raises(BridgeError):
        apply_grid(host, spec)


def error_text(host, spec):
    with pytest.raises(BridgeError) as exc:
        apply_grid(host, spec)
    return exc.value.error["message"]


def test_unknown_parameter_and_port_errors_list_valid_ids():
    low = {"target": TARGET, "modules": [{"name": "f", "type": "Amplify", "x": 0, "y": 0, "params": {"GAIN": 0.5}}]}
    text = error_text(FakeGrid(), low)
    assert "unknown parameter 'GAIN'" in text
    assert "Valid: BIPOLAR, STEREOIZE, VALUE" in text
    mods = [{"name": "osc", "type": "Sine", "x": 0, "y": 0}, {"name": "t", "type": "Triggers", "x": 8, "y": 0}]
    text = error_text(
        FakeGrid(), {"target": TARGET, "modules": mods, "connections": [{"from": "osc.out", "to": "t.PHASE_IN"}]}
    )
    assert "Did you mean: 'OUT'" in text
    assert "Valid: OUT" in text
    assert "Did you mean: 'IN'" in text
    assert "Valid: IN" in text


def test_overlap_error_names_the_blocker():
    host = FakeGrid()
    host.add("Add", "C: bar", 0, 0)
    host.add("Add", "", 10, 0)
    text = error_text(
        host,
        {
            "target": TARGET,
            "modules": [{"name": "a", "type": "Sine", "x": 0, "y": 0}, {"name": "b", "type": "Sine", "x": 10, "y": 0}],
        },
    )
    assert "'C: bar' (Add@0,0)" in text
    assert "Add@10,0" in text


def test_real_apply_returns_a_diagnostics_marker(tmp_path, monkeypatch):

    log = tmp_path / "bitwig.log"
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S.000")
    log.write_text(f"[{now} float-main-app info] Starting application version 6.1.4 revision abc\n")
    monkeypatch.setenv("BITWIG_LOG_PATH", str(log))
    result = apply_grid(FakeGrid(), {"target": TARGET, "modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}]})
    assert isinstance(result.get("diagnosticsMarker"), str)


def test_prune_all_converges_whole_device_across_prefixes():
    host = populated_namespace_host()  # H: spec modules, H: old, X: keep, one unnamed, two undeclared cables
    spec = {**SPEC}
    spec.pop("namespace")
    spec["modules"] = [
        *SPEC["modules"][:1],
        {**SPEC["modules"][1], "type": "Multiply"},
        *SPEC["modules"][2:],
    ]  # H: add retyped
    spec["modules"].append({"name": "K: new", "type": "Add", "x": 20, "y": 12})  # lands on X: keep's footprint
    plan = apply_grid(host, spec, pruneAll=True, dryRun=True)
    assert plan["plan"]["replaces"] == ["H: add"]
    assert host.writes() == []
    assert {"H: add", "H: old", "X: keep"} <= set(plan["plan"]["deletes"])
    assert len(plan["plan"]["deletes"]) == 4
    assert any("pruneAll: deleting" in w for w in plan["warnings"])
    assert any("pruneAll: replacing" in w for w in plan["warnings"])
    result = apply_grid(host, spec, pruneAll=True)
    assert result["status"] == "applied"
    assert result["applied"]["deleted"] == 4
    assert sorted(m["customName"] for m in host.mods.values()) == sorted(m["name"] for m in spec["modules"])
    methods = [m for m, _ in host.calls]
    assert methods.index("grid.modules.delete") < methods.index("grid.modules.insert")
    host.calls.clear()
    assert apply_grid(host, spec, pruneAll=True)["status"] == "unchanged"
    assert host.writes() == []


@pytest.mark.parametrize("extra", [{"namespace": "H: "}, {"allowOutsideDisconnect": True}])
def test_prune_all_excludes_namespace(extra):
    with pytest.raises(ValueError, match="pruneAll"):
        apply_grid(FakeGrid(), {**SPEC, **extra, "pruneAll": True})


def test_tiny_raw_detune_warns_but_display_and_larger_raw_do_not():
    assert messages(one("Sawtooth", {"DETUNE": 0.25}, dryRun=True), "DETUNE raw 0.25")
    assert messages(one("Sine", {"DETUNE": -0.3}, dryRun=True), "no detune")
    assert not messages(one("Sine", {"DETUNE": 1.1}, dryRun=True), "DETUNE")
    assert not messages(one("Sine", {"DETUNE": 0}, dryRun=True), "DETUNE")
    assert not messages(one("Sine", {"DETUNE": {"display": "0.5 Hz"}}, dryRun=True), "DETUNE")
    assert raw_scales.convert("Sine", "DETUNE", "1.331 Hz", -3, 3) == pytest.approx(1.1)


def test_unnamed_module_declared_by_generated_name_keeps_identity_across_a_move():
    host = FakeGrid()
    iid = host.add("Add", "", 20, 16)
    spec = {"target": TARGET, "modules": [{"name": "_Add_20_16", "type": "Add", "x": 24, "y": 16}]}
    first = apply_grid(host, spec, pruneAll=True)
    assert first["status"] == "applied"
    assert first["applied"]["renamed"] == 1
    assert first["applied"]["deleted"] == 0
    assert host.mods[iid]["customName"] == "_Add_20_16"
    assert (host.mods[iid]["x"], host.mods[iid]["y"]) == (24, 16)
    host.calls.clear()
    second = apply_grid(host, spec, pruneAll=True)
    assert second["status"] == "unchanged"
    assert host.writes() == []
    assert list(host.mods) == [iid]
