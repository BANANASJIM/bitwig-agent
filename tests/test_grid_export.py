"""grid.export / grid.graph and the external / outside-namespace prune rules of grid.apply, against the fake Grid host."""

import asyncio
import copy
import json
from pathlib import Path

import pytest
from fakes import SPEC, TARGET, FakeGrid, apply_grid

from bitwig_agent import grid_export
from bitwig_agent.catalog import validate_call
from bitwig_agent.client import BridgeError


def export(host, **extra):
    args, timeout = validate_call("grid.export", {**TARGET, **extra})
    return asyncio.run(grid_export.export(host, args, timeout))


def graph(host, **extra):
    args, timeout = validate_call("grid.graph", {**TARGET, **extra})
    return asyncio.run(grid_export.graph(host, args, timeout))


def built():
    host = FakeGrid()
    host.voicing = {
        "voices": 4,
        "voiceStacking": 1,
        "monoMode": "true_mono",
        "retriggerMode": "on_note_on",
        "notePriority": "last",
        "legatoGlide": False,
        "stealSameKey": False,
        "allowOverlappingNotes": True,
    }
    spec = copy.deepcopy(SPEC)
    spec["modules"][3]["params"]["DATA"] = {"steps": [1, 0, 0.5, 0.25]}
    apply_grid(host, spec)
    return host


def test_round_trip_is_unchanged_and_rebuilds_an_empty_device():
    host = built()
    out = export(host)
    spec = out["spec"]
    assert out["status"] == "exported"
    assert spec["voicing"]["voices"] == 4
    assert {m["name"] for m in spec["modules"]} == {m["name"] for m in SPEC["modules"]}
    env = next(m for m in spec["modules"] if m["name"] == "H: env")
    assert env["params"] == {"ATTACK": 0.5, "GATE": False} or env["params"]["ATTACK"] == 0.5
    acc = next(m for m in spec["modules"] if m["name"] == "H: acc")
    assert acc["params"]["MODE"] == {"label": "Trigger"}
    assert acc["params"]["DATA"] == {"steps": [1, 0, 0.5, 0.25]}
    host.calls.clear()
    again = apply_grid(host, spec)
    assert again["status"] == "unchanged"
    assert host.writes() == []
    fresh = FakeGrid()
    fresh.voicing = dict(host.voicing, voices=1)
    assert apply_grid(fresh, spec)["status"] == "applied"
    assert export(fresh)["spec"] == spec


def test_include_params_modes_and_out_path(tmp_path):
    host = built()
    none = export(host, includeParams="none")["spec"]
    assert all("params" not in m for m in none["modules"])
    changed = {m["name"]: m.get("params", {}) for m in export(host)["spec"]["modules"]}
    everything = {m["name"]: m.get("params", {}) for m in export(host, includeParams="all")["spec"]["modules"]}
    assert "ATTACK" not in changed["H: mul"]
    assert len(everything["H: env"]) > len(changed["H: env"])
    path = str(tmp_path / "spec.json")
    out = export(host, outPath=path)
    assert "spec" not in out
    assert json.loads(Path(path).read_text())["modules"]
    with pytest.raises(BridgeError) as err:
        export(host, outPath=path)
    assert err.value.error["code"] == "file_exists"
    export(host, outPath=path, overwrite=True)


def test_unnamed_modules_get_stable_names_that_apply_matches():
    host = built()
    host.add("Add", "", 20, 8)
    out = export(host)
    assert out["unnamed"] == ["_Add_20_8"]
    assert any("generated name" in w for w in out["warnings"])
    host.calls.clear()
    assert apply_grid(host, out["spec"])["status"] == "unchanged"
    assert host.writes() == []
    assert not any(m["customName"] == "_Add_20_8" for m in host.mods.values())


def test_namespace_export_references_outside_modules_as_external():
    host = built()
    keep = host.add("Add", "X: sum", 20, 12)
    host.conns[(keep, "IN")] = (host.named("H: add")["instanceId"], "OUT")
    host.conns[(host.named("H: add")["instanceId"], "IN2")] = (keep, "OUT")
    out = export(host, namespace="H: ")["spec"]
    assert out["external"] == ["X: sum"]
    assert out["namespace"] == "H: "
    assert "voicing" not in out
    assert all(m["name"].startswith("H: ") for m in out["modules"])
    assert {"from": "X: sum.OUT", "to": "H: add.IN2"} in out["connections"]
    assert {
        "from": "H: add.OUT",
        "to": "X: sum.IN",
    } in out["connections"]
    host.calls.clear()
    assert apply_grid(host, out)["status"] == "unchanged"
    assert host.writes() == []
    g = graph(host, namespace="H: ")
    assert g["external"] == ["X: sum"]
    assert g["modules"][0]["size"]


def test_graph_by_names():
    host = built()
    g = graph(host)
    assert g["moduleCount"] == 5
    assert {"from": "H: mul.OUT", "to": "H: add.IN"} in g["connections"]
    assert all("params" not in m for m in g["modules"])
    assert "params" in next(m for m in graph(host, includeParams="changed")["modules"] if m["name"] == "H: env")


def test_external_modules_wire_without_redeclaring_and_are_never_touched():
    host = built()
    host.add("Add", "X: sum", 20, 12)
    spec = {
        "target": TARGET,
        "namespace": "H: ",
        "external": ["X: sum"],
        "modules": [{"name": "H: new", "type": "Multiply", "x": 0, "y": 8}],
        "connections": [{"from": "H: new.OUT", "to": "X: sum.IN2"}],
    }
    host.calls.clear()
    result = apply_grid(host, spec, prune=True)
    assert result["applied"]["connections"] == 1
    assert host.source("X: sum", "IN2") == ("H: new", "OUT")
    assert "grid.modules.move" not in [m for m, _ in host.calls]
    # the external's own namespace-like name is never pruned
    host.calls.clear()
    assert apply_grid(host, {**spec, "namespace": "X: "}, prune=True, dryRun=True)["plan"]["deletes"] == []
    sx = {**spec, "external": ["X: sum", "X: sum"]}
    with pytest.raises(ValueError, match="unique"):
        apply_grid(host, sx)


def test_missing_external_and_double_declaration_fail_before_writing():
    host = built()
    host.calls.clear()
    spec = {
        "target": TARGET,
        "external": ["X: smu"],
        "modules": [{"name": "H: new", "type": "Multiply", "x": 0, "y": 8}],
        "connections": [{"from": "H: new.OUT", "to": "X: smu.IN"}],
    }
    with pytest.raises(BridgeError) as err:
        apply_grid(host, spec)
    assert "external module 'X: smu' is not in the device" in err.value.error["message"]
    assert host.writes() == []
    both = {**spec, "external": ["H: mul"], "connections": []}
    both["modules"] = [{"name": "H: mul", "type": "Multiply", "x": 0, "y": 0}]
    with pytest.raises(BridgeError, match="declared in modules"):
        apply_grid(host, both)
    bad = {**spec, "external": [], "connections": [{"from": "H: new.OUT", "to": "X: smu.IN"}]}
    with pytest.raises(BridgeError, match="declared or external"):
        apply_grid(host, bad)


def outside_host():
    host = built()
    s = host.add("Add", "X: sum", 20, 12)
    host.conns[(s, "IN")] = (host.named("H: mul")["instanceId"], "OUT")
    host.conns[(s, "IN2")] = (host.named("H: add")["instanceId"], "OUT")
    host.calls.clear()
    return host, s


def test_prune_keeps_inputs_of_modules_outside_the_namespace_and_names_both_ends():
    host, s = outside_host()
    spec = {
        "target": TARGET,
        "namespace": "Z: ",
        "prune": True,
        "modules": [{"name": "X: sum", "type": "Add", "x": 20, "y": 12}],
        "connections": [],
    }
    plan = apply_grid(host, spec, dryRun=True)
    assert plan["planned"]["disconnected"] == 0
    assert "X: sum.IN (from H: mul.OUT)" in plan["warnings"][0]
    assert "X: sum.IN2 (from H: add.OUT)" in plan["warnings"][0]
    assert "allowOutsideDisconnect" in plan["warnings"][0]
    result = apply_grid(host, spec)
    assert host.writes() == []
    assert result["status"] == "unchanged"
    assert "X: sum.IN2" in result["warnings"][0]
    assert host.conns[(s, "IN")]
    assert host.conns[(s, "IN2")]


def test_outside_disconnect_needs_opt_in_and_is_listed():
    host, s = outside_host()
    spec = {
        "target": TARGET,
        "namespace": "Z: ",
        "prune": True,
        "allowOutsideDisconnect": True,
        "external": ["H: mul"],
        "modules": [{"name": "X: sum", "type": "Add", "x": 20, "y": 12}],
        "connections": [{"from": "H: mul.OUT", "to": "X: sum.IN"}],
    }
    plan = apply_grid(host, spec, dryRun=True)
    cut = [c for c in plan["plan"]["connections"] if c["from"] is None]
    assert cut == [{"from": None, "to": "X: sum.IN2", "previous": "H: add.OUT"}]
    assert "X: sum.IN2 (from H: add.OUT)" in plan["warnings"][0]
    result = apply_grid(host, spec)
    assert result["applied"]["disconnected"] == 1
    assert (s, "IN2") not in host.conns
    assert (s, "IN") in host.conns


def test_external_inputs_are_never_pruned_even_with_opt_in():
    host, s = outside_host()
    spec = {
        "target": TARGET,
        "namespace": "X: ",
        "prune": True,
        "allowOutsideDisconnect": True,
        "external": ["X: sum"],
        "modules": [{"name": "H: mul", "type": "Multiply", "x": 0, "y": 0}],
        "connections": [],
    }
    result = apply_grid(host, spec)
    assert result["status"] == "unchanged"
    assert host.writes() == []
    assert (s, "IN2") in host.conns


def test_external_port_and_default_module_names_are_validated_before_writing():
    host = built()
    host.add("Audio Out", "", 30, 0)
    host.calls.clear()
    base = {"target": TARGET, "modules": [{"name": "H: new", "type": "Multiply", "x": 0, "y": 8}]}
    bad_port = {
        **base,
        "external": ["_Audio Out_30_0"],
        "connections": [{"from": "H: new.OUT", "to": "_Audio Out_30_0.IN_L"}],
    }
    with pytest.raises(BridgeError) as err:
        apply_grid(host, bad_port)
    message = err.value.error["message"]
    assert "no input port 'IN_L'" in message
    assert "valid inputs: IN" in message
    assert host.writes() == []
    by_type = {**base, "external": ["Audio Out"], "connections": [{"from": "H: new.OUT", "to": "Audio Out.IN"}]}
    with pytest.raises(BridgeError) as err:
        apply_grid(host, by_type)
    assert "'_Audio Out_30_0'" in err.value.error["message"]
    assert "generated name" in err.value.error["message"]
    assert host.writes() == []
    ok = {**bad_port, "connections": [{"from": "H: new.OUT", "to": "_Audio Out_30_0.IN"}]}
    assert apply_grid(host, ok)["applied"]["connections"] == 1
