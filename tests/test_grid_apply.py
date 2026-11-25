"""grid.apply against a fake Grid host that mirrors the guarded grid.* contracts (CAS, batch statuses)."""

import asyncio
import copy
import json
import uuid

import pytest

from bitwig_agent import grid_apply
from bitwig_agent.catalog import validate_call
from bitwig_agent.cli import parse_arguments
from bitwig_agent.client import BridgeError

TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
WRITES = {"grid.modules.insert", "grid.modules.rename", "grid.modules.move", "grid.modules.delete",
          "grid.parameters.set", "grid.connections.set"}


class FakeGrid:
    def __init__(self, version="6.1.4"):
        self.catalog = grid_apply.bundled_schema()["modules"]
        self.version = version
        self.mods: dict[str, dict] = {}
        self.conns: dict[tuple[str, str], tuple[str, str]] = {}
        self.calls: list[tuple[str, dict]] = []
        self.shift = 0  # insert lands this far right of the request, like native reflow
        self.fail: dict[str, object] = {}
        self.insert_caps: list[int] = []  # per insert call: apply only this many entries, then report partial

    # state helpers
    def add(self, type_name, custom="", x=0, y=0):
        info = self.catalog[type_name]
        iid = str(uuid.uuid4())
        params = {}
        for p in info["parameters"]:
            if p["kind"] != "string":
                params[p["parameterId"]] = {**p, "value": p["default"], "display": p.get("defaultDisplay")}
        self.mods[iid] = {"instanceId": iid, "customName": custom, "name": custom or type_name, "typeId": info["typeId"],
                          "typeName": type_name, "x": x, "y": y, "info": info, "params": params}
        return iid

    def named(self, custom):
        return next(m for m in self.mods.values() if m["customName"] == custom)

    def param(self, custom, pid):
        return self.named(custom)["params"][pid]["value"]

    def source(self, custom, port):
        c = self.conns.get((self.named(custom)["instanceId"], port))
        return (self.mods[c[0]]["customName"], c[1]) if c else None

    def writes(self):
        return [m for m, _ in self.calls if m in WRITES]

    def row(self, m, params):
        connected = {p for (t, p) in self.conns if t == m["instanceId"]}
        row = {"instanceId": m["instanceId"], "customName": m["customName"], "name": m["name"], "typeId": m["typeId"],
               "typeName": m["typeName"], "x": m["x"], "y": m["y"], "width": m["info"]["width"], "height": m["info"]["height"],
               "inputs": [{"key": i["key"], "name": i["name"], "connected": i["key"] in connected} for i in m["info"]["inputs"]],
               "outputs": [{"key": o["key"], "name": o["name"]} for o in m["info"]["outputs"]]}
        if params:
            row["parameters"] = [{"supported": True, "kind": p["kind"], "parameterId": pid, "value": p["value"], "display": p["display"],
                                  **{k: p[k] for k in ("minimum", "maximum", "options", "numericUnit") if k in p}} for pid, p in m["params"].items()]
        return row

    # host
    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, copy.deepcopy(params)))
        if method in self.fail:
            outcome = self.fail[method]
            if isinstance(outcome, BridgeError):
                raise outcome
            return outcome
        return getattr(self, "h_" + method.replace("grid.", "").replace(".", "_"))(params)

    def h_capabilities(self, p):
        return {"status": "observed", "hostVersion": self.version}

    def h_catalog(self, p):
        entries = [{"typeId": m["typeId"], "name": n} for n, m in self.catalog.items()]
        return {"status": "observed", "entries": entries[p["offset"]:p["offset"] + p["limit"]],
                "window": {"complete": p["offset"] + p["limit"] >= len(entries)}}

    def h_get(self, p):
        mods = [m for m in self.mods.values() if m["instanceId"] in p.get("moduleIds", self.mods)]
        page = mods[p["moduleOffset"]:p["moduleOffset"] + p["moduleLimit"]]
        conns = [{"targetModuleId": t, "targetPortKey": tp, "sourceModuleId": s, "sourcePortKey": sp} for (t, tp), (s, sp) in self.conns.items()]
        return {"status": "observed", "modules": [self.row(m, p["includeParameters"]) for m in page], "connections": conns,
                "window": {"total": len(mods), "complete": True}}

    def h_modules_insert(self, p):
        entries, added = [], []
        cap = self.insert_caps.pop(0) if self.insert_caps else len(p["modules"])
        for k, spec in enumerate(p["modules"]):
            if k >= cap:
                entries.append({"typeId": spec["typeId"], "status": "not_dispatched" if k > cap else "failed"})
                continue
            name = next(n for n, m in self.catalog.items() if m["typeId"] == spec["typeId"])
            iid = self.add(name, "", spec["x"] + self.shift, spec["y"])
            entries.append({"typeId": spec["typeId"], "status": "applied", "moduleId": iid, "actualPosition": {"x": spec["x"] + self.shift, "y": spec["y"]}})
            added.append(self.row(self.mods[iid], False))
        batch = "applied" if cap >= len(p["modules"]) else "partial"
        return {"status": "observed", "batchStatus": batch, "completion": "complete", "modules": entries, "modulesAdded": added}

    def h_modules_rename(self, p):
        m = self.mods[p["moduleId"]]
        if m["customName"] != p["expectedCustomName"]:
            raise BridgeError({"code": "stale_context", "message": "name changed", "outcome": "not_applied"})
        m["customName"] = m["name"] = p["customName"]
        return {"status": "observed", "operation": "rename"}

    def h_modules_move(self, p):
        m = self.mods[p["moduleId"]]
        if (m["x"], m["y"]) != (p["expectedX"], p["expectedY"]):
            raise BridgeError({"code": "stale_context", "message": "moved", "outcome": "not_applied"})
        for o in self.mods.values():  # host rule: the anchor may not lie inside another module's footprint
            if o is not m and o["x"] <= p["x"] < o["x"] + o["info"]["width"] and o["y"] <= p["y"] < o["y"] + o["info"]["height"]:
                raise BridgeError({"code": "invalid_params", "message": "occupied anchor", "outcome": "not_applied"})
        m["x"], m["y"] = p["x"], p["y"]
        return {"status": "observed", "operation": "move", "actualPosition": {"x": p["x"], "y": p["y"]}, "matchesRequested": True}

    def h_modules_delete(self, p):
        self.mods.pop(p["moduleId"])
        self.conns = {k: v for k, v in self.conns.items() if p["moduleId"] not in (k[0], v[0])}
        return {"status": "observed", "operation": "delete", "removed": True}

    def h_parameters_set(self, p):
        entries = []
        for w in p["writes"]:
            par = self.mods[w["moduleId"]]["params"][w["parameterId"]]
            if (par["kind"], par["value"]) != (w["expectedKind"], w["expectedValue"]):
                raise BridgeError({"code": "stale_context", "message": "stale", "outcome": "not_applied"})
            if "display" in w:
                par["value"], par["display"] = 99.0, w["display"]
            else:
                par["value"], par["display"] = w["value"], str(w["value"])
            entries.append({"moduleId": w["moduleId"], "parameterId": w["parameterId"], "status": "applied"})
        return {"status": "observed", "batchStatus": "applied", "completion": "complete", "writes": entries}

    def h_connections_set(self, p):
        entries = []
        for c in p["connections"]:
            key = (c["targetModuleId"], c["targetPortKey"])
            cur = self.conns.get(key)
            if (cur and {"moduleId": cur[0], "portKey": cur[1]}) != c["expectedSource"]:
                raise BridgeError({"code": "stale_context", "message": "stale", "outcome": "not_applied"})
            if c["source"]:
                self.conns[key] = (c["source"]["moduleId"], c["source"]["portKey"])
            else:
                self.conns.pop(key, None)
            entries.append({"status": "applied", "matchesRequested": True})
        return {"status": "observed", "batchStatus": "applied", "completion": "complete", "connections": entries}


SPEC = {
    "target": TARGET, "namespace": "H: ",
    "modules": [
        {"name": "H: mul", "type": "Multiply", "x": 0, "y": 0},
        {"name": "H: add", "type": "Add", "x": 3, "y": 0},
        {"name": "H: env", "type": "ADSR", "x": 6, "y": 0, "params": {"ATTACK": 0.5, "GATE": False}},
        {"name": "H: acc", "type": "Accents", "x": 0, "y": 4, "params": {"STEPS": 4, "MODE": {"label": "Trigger"}}},
        {"name": "H: lp", "type": "Low-pass", "x": 3, "y": 4, "params": {"CUTOFF": {"display": "1.2 kHz"}}},
    ],
    "connections": [{"from": "H: mul.OUT", "to": "H: add.IN"}, {"from": "H: add.OUT", "to": "H: env.IN"},
                    {"from": "H: acc.OUT", "to": "H: lp.IN"}],
}


def run(host, spec, **extra):
    args, timeout = validate_call("grid.apply", {**copy.deepcopy(spec), **extra})
    return asyncio.run(grid_apply.apply(host, args, timeout))


def test_fresh_build_then_idempotent_reapply_writes_nothing():
    host = FakeGrid()
    host.shift = 1  # native reflow: every insert lands one column right, forcing moves
    result = run(host, SPEC)
    assert result["status"] == "applied"
    assert result["applied"] == {"inserted": 5, "renamed": 5, "moved": 5, "paramsWritten": 5, "connections": 3, "disconnected": 0, "deleted": 0}
    assert set(result["moduleIds"]) == {m["name"] for m in SPEC["modules"]}
    assert [(m["x"], m["y"]) for m in (host.named(s["name"]) for s in SPEC["modules"])] == [(s["x"], s["y"]) for s in SPEC["modules"]]
    assert host.param("H: env", "ATTACK") == 0.5 and host.param("H: acc", "STEPS") == 4
    assert host.param("H: acc", "MODE") == next(o["value"] for o in host.catalog["Accents"]["parameters"][3]["options"] if o["label"] == "Trigger")
    assert host.source("H: add", "IN") == ("H: mul", "OUT") and host.source("H: lp", "IN") == ("H: acc", "OUT")
    inserts = [c for m, c in host.calls if m == "grid.modules.insert"]
    assert len(inserts) == 1 and all("typeId" in e and "typeName" not in e for e in inserts[0]["modules"])
    host.calls.clear()
    again = run(host, SPEC)
    assert again["status"] == "unchanged"
    assert host.writes() == []
    assert again["applied"] == dict.fromkeys(again["applied"], 0)
    assert again["alreadyMatched"] == {"modules": 5, "positions": 5, "params": 5, "connections": 3}


def test_insert_batches_of_sixteen():
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    host = FakeGrid()
    assert run(host, spec)["applied"]["inserted"] == 20
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [16, 4]


def test_param_change_only():
    host = FakeGrid()
    run(host, SPEC)
    host.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["modules"][2]["params"]["ATTACK"] = 0.75
    result = run(host, spec)
    assert host.writes() == ["grid.parameters.set"]
    assert result["applied"]["paramsWritten"] == 1 and result["alreadyMatched"]["params"] == 4
    assert host.param("H: env", "ATTACK") == 0.75
    sent = next(c for m, c in host.calls if m == "grid.parameters.set")["writes"][0]
    assert sent["expectedKind"] == "float" and sent["expectedValue"] == 0.5 and sent["value"] == 0.75


def test_connection_change_only():
    host = FakeGrid()
    run(host, SPEC)
    host.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["connections"][0] = {"from": "H: mul.OUT", "to": "H: add.IN2"}
    result = run(host, spec)
    assert host.writes() == ["grid.connections.set"]
    assert result["applied"]["connections"] == 1 and result["alreadyMatched"]["connections"] == 2
    assert host.source("H: add", "IN2") == ("H: mul", "OUT") and host.source("H: add", "IN") == ("H: mul", "OUT")
    # replacing a cable sends the live source as the CAS
    host.calls.clear()
    run(host, {**spec, "connections": [{"from": "H: env.OUT", "to": "H: add.IN"}]})
    sent = next(c for m, c in host.calls if m == "grid.connections.set")["connections"][0]
    assert sent["expectedSource"] is not None and host.source("H: add", "IN") == ("H: env", "OUT")
    assert host.source("H: add", "IN2") == ("H: mul", "OUT")  # unlisted input left alone without prune


def test_dry_run_plans_without_writing():
    host = FakeGrid()
    result = run(host, SPEC, dryRun=True)
    assert result["status"] == "dryRun" and host.writes() == []
    assert result["planned"]["inserted"] == 5 and result["planned"]["connections"] == 3 and result["planned"]["paramsWritten"] == 5
    assert len(result["plan"]["inserts"]) == 5 and result["plan"]["connections"][0] == {"from": "H: mul.OUT", "to": "H: add.IN", "previous": None}
    host2 = FakeGrid()
    run(host2, SPEC)
    host2.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["modules"][2]["params"]["ATTACK"] = 1.0
    spec["modules"][0]["x"] = 40
    plan = run(host2, spec, dryRun=True)
    assert host2.writes() == []
    assert plan["planned"]["paramsWritten"] == 1 and plan["planned"]["moved"] == 1 and plan["planned"]["inserted"] == 0
    assert plan["plan"]["moves"][0]["name"] == "H: mul"


def test_validation_failure_writes_nothing_and_suggests():
    host = FakeGrid()
    spec = copy.deepcopy(SPEC)
    spec["modules"][0]["type"] = "Multiplyy"
    spec["modules"][2]["params"] = {"ATAK": 1, "GATE": 1, "SUSTAIN": 7.0}
    spec["connections"] = [{"from": "H: add.OUTT", "to": "H: env.IN"}, {"from": "H: nope.OUT", "to": "H: env.GATE_IN"}]
    with pytest.raises(BridgeError) as err:
        run(host, spec)
    error = err.value.error
    assert error["code"] == "invalid_spec" and error["outcome"] == "not_dispatched"
    text = " ".join(error["details"]["problems"])
    for needle in ("Multiplyy", "Multiply'", "ATAK", "ATTACK", "boolean parameter needs", "outside 0.0..1.0", "OUTT", "'OUT'", "nope"):
        assert needle in text
    assert host.writes() == []


def test_existing_module_of_wrong_type_is_rejected_before_any_write():
    host = FakeGrid()
    host.add("Add", "H: mul")
    with pytest.raises(BridgeError) as err:
        run(host, SPEC)
    assert "H: mul" in err.value.error["message"] and host.writes() == []


def test_stop_on_unknown_never_retries_and_reports():
    host = FakeGrid()
    raw = {"status": "observed", "batchStatus": "unknown", "completion": "unknown",
           "writes": [{"parameterId": "ATTACK", "status": "unknown"}]}
    host.fail["grid.parameters.set"] = raw
    result = run(host, SPEC)
    assert result["status"] == "unknown"
    assert result["failure"] == {"phase": "params", "method": "grid.parameters.set", "response": raw}
    assert result["notDispatched"]["phasesNotStarted"] == ["connections"]
    assert result["applied"]["inserted"] == 5 and result["applied"]["connections"] == 0
    assert [m for m, _ in host.calls].count("grid.parameters.set") == 1
    assert "grid.connections.set" not in [m for m, _ in host.calls]


def test_stop_on_partial_and_on_host_error():
    host = FakeGrid()
    host.fail["grid.connections.set"] = {"status": "observed", "batchStatus": "partial", "completion": "complete",
                                         "connections": [{"status": "applied"}, {"status": "mismatch"}, {"status": "not_dispatched"}]}
    result = run(host, SPEC)
    assert result["status"] == "partial" and result["failure"]["response"]["batchStatus"] == "partial"
    assert result["applied"]["paramsWritten"] == 5

    host = FakeGrid()
    host.fail["grid.modules.rename"] = BridgeError({"code": "stale_context", "message": "no", "outcome": "not_applied"})
    result = run(host, SPEC)
    assert result["status"] == "partial"  # inserts already landed
    assert result["failure"]["response"]["error"]["code"] == "stale_context"
    assert result["notDispatched"]["phasesNotStarted"] == ["move", "params", "connections"]
    assert [m for m, _ in host.calls].count("grid.modules.rename") == 1

    host = FakeGrid()
    host.fail["grid.modules.insert"] = BridgeError({"code": "x", "message": "m", "outcome": "unknown"})
    assert run(host, SPEC)["status"] == "unknown"


def test_read_error_before_any_write_is_a_plain_error():
    host = FakeGrid()
    host.fail["grid.get"] = BridgeError({"code": "stale_context", "message": "wrong device", "outcome": "not_applied"})
    with pytest.raises(BridgeError) as err:
        run(host, SPEC)
    assert err.value.error["code"] == "stale_context" and host.writes() == []


def populated_namespace_host():
    host = FakeGrid()
    run(host, SPEC)
    old = host.add("Add", "H: old", 20, 8)
    keep = host.add("Add", "X: keep", 20, 12)
    host.add("Add", "", 20, 16)  # unnamed
    host.conns[(host.named("H: add")["instanceId"], "IN2")] = (keep, "OUT")  # unlisted input of a spec module
    host.conns[(old, "IN")] = (host.named("H: mul")["instanceId"], "OUT")
    host.calls.clear()
    return host


def test_prune_deletes_only_inside_namespace_and_disconnects_unlisted_inputs():
    host = populated_namespace_host()
    result = run(host, SPEC, prune=True)
    assert result["applied"]["deleted"] == 1 and result["applied"]["disconnected"] == 1
    names = sorted(m["customName"] for m in host.mods.values())
    assert "H: old" not in names and "X: keep" in names and names.count("") == 1
    assert host.source("H: add", "IN2") is None
    deleted = [c for m, c in host.calls if m == "grid.modules.delete"]
    assert len(deleted) == 1


def test_without_prune_nothing_extra_is_touched():
    host = populated_namespace_host()
    result = run(host, SPEC)
    assert result["status"] == "unchanged" and host.writes() == []
    plan = run(host, SPEC, prune=True, dryRun=True)
    assert plan["plan"]["deletes"] == ["H: old"] and host.writes() == []
    assert run(host, SPEC, prune=True, namespace="Z: ", dryRun=True)["plan"]["deletes"] == []


def test_other_host_version_uses_live_catalog_and_checks_ports_after_insert():
    host = FakeGrid(version="9.9.9")
    assert run(host, SPEC)["status"] == "applied"
    assert any(m == "grid.catalog" for m, _ in host.calls) and host.source("H: lp", "IN") == ("H: acc", "OUT")
    host = FakeGrid(version="9.9.9")
    bad = copy.deepcopy(SPEC)
    bad["connections"][0]["to"] = "H: add.NOPE"
    result = run(host, bad)
    assert result["status"] == "partial" and result["failure"]["phase"] == "connections"
    assert "NOPE" in result["failure"]["response"]["error"]["message"]
    with pytest.raises(BridgeError):
        run(FakeGrid(version="9.9.9"), {**SPEC, "modules": [{"name": "A", "type": "Nonsuch", "x": 0, "y": 0}], "connections": []})


def test_schema_and_cli_surface(tmp_path):
    with pytest.raises(ValueError):
        validate_call("grid.apply", {**SPEC, "modules": SPEC["modules"] + [SPEC["modules"][0]]})
    with pytest.raises(ValueError):
        validate_call("grid.apply", {**SPEC, "modules": [{"name": "A", "x": 0, "y": 0}], "connections": []})
    with pytest.raises(ValueError):
        validate_call("grid.apply", {**SPEC, "connections": SPEC["connections"] + [SPEC["connections"][0]]})
    path = tmp_path / "patch.json"
    path.write_text(json.dumps(SPEC))
    params, _ = parse_arguments("grid.apply", [f"spec=@{path}", "dryRun=true"], None, None)
    assert params["dryRun"] is True and params["connections"][0]["from"] == "H: mul.OUT"
    params, _ = parse_arguments("grid.apply", [], f"@{path}", None)
    assert params["modules"][0]["name"] == "H: mul"
    with pytest.raises(ValueError):  # a field may not come from both the file and key=value
        parse_arguments("grid.apply", [f"spec=@{path}", "namespace=Q"], None, None)


def test_display_comparison_uses_live_precision():
    assert grid_apply.display_matches("1.2 kHz", "1.20 kHz", "hertz") and grid_apply.display_matches("1200 Hz", "1.20 kHz", "hertz")
    assert not grid_apply.display_matches("1.2 kHz", "1.19 kHz", "hertz") and not grid_apply.display_matches("50 %", None, "percent")


def test_partial_insert_batch_still_names_what_landed_and_reapply_converges():
    spec = {"target": TARGET, "namespace": "G: ", "modules": [{"name": f"G: m{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    host = FakeGrid()
    host.insert_caps = [16, 2]
    result = run(host, spec)
    assert result["status"] == "partial" and result["applied"]["inserted"] == 18 and result["applied"]["renamed"] == 18
    assert sorted(m["customName"] for m in host.mods.values() if m["customName"]) == sorted(f"G: m{i}" for i in range(18))
    assert not [m for m in host.mods.values() if not m["customName"]] and "unnamedModules" not in result
    assert result["notDispatched"]["inserted"] == 2
    again = run(host, spec, prune=True)
    assert again["status"] == "applied" and again["applied"]["inserted"] == 2 and again["applied"]["deleted"] == 0
    assert len(host.mods) == 20


def test_unsettled_insert_reports_unnamed_modules():
    host = FakeGrid()
    host.fail["grid.modules.insert"] = {"status": "observed", "batchStatus": "unknown", "completion": "unknown",
                                        "modules": [{"status": "applied", "moduleId": host.add("Add", "", 50, 50)}, {"status": "unknown"}]}
    result = run(host, {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}, {"name": "B", "type": "Add", "x": 3, "y": 0}]})
    assert result["status"] == "unknown" and list(result["unnamedModules"].values()) == ["A"]
    assert "grid.modules.rename" not in [m for m, _ in host.calls]


@pytest.mark.parametrize("params", [
    {"ATTACK": {"display": "10 ms"}},  # seconds: no verified conversion
    {"SUSTAIN": {"display": "banana"}},
    {"SUSTAIN": {"display": "500 %"}},  # raw outside range
])
def test_unwritable_display_is_rejected_offline(params):
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        run(host, {"target": TARGET, "modules": [{"name": "E", "type": "ADSR", "x": 0, "y": 0, "params": params}]})
    assert err.value.error["code"] == "invalid_spec" and host.writes() == []


def test_zero_default_display_is_rejected_offline_but_raw_value_works():
    host = FakeGrid()
    bad = {"target": TARGET, "modules": [{"name": "B", "type": "Bias", "x": 0, "y": 0, "params": {"VALUE": {"display": "50 %"}}}]}
    with pytest.raises(BridgeError) as err:
        run(host, bad)
    assert "zero" in err.value.error["message"] and host.writes() == []
    bad["modules"][0]["params"] = {"VALUE": 0.5}
    assert run(host, bad)["applied"]["paramsWritten"] == 1


def test_display_parser_accepts_sign_unicode_minus_and_leading_dot():
    q = grid_apply.parse_quantity
    assert q("decibel", "+6.2 dB")[0] == 6.2 and q("decibel", "−6 dB")[0] == -6
    assert q("percent", ".5 %")[0] == 0.5 and q("hertz", "1.2 kHz")[0] == 1200
    assert grid_apply.display_matches("+6.2 dB", "6.20 dB", "decibel")


def test_overlapping_spec_layout_is_rejected_offline():
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        run(host, {"target": TARGET, "modules": [{"name": "A", "type": "ADSR", "x": 0, "y": 0}, {"name": "B", "type": "Add", "x": 1, "y": 1}]})
    assert "overlap" in err.value.error["message"] and host.writes() == []
    host.add("Add", "other", 0, 0)
    with pytest.raises(BridgeError) as err:
        run(host, {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}]})
    assert "other" in err.value.error["message"] and host.writes() == []


def test_swap_is_ordered_through_a_free_anchor_and_terminates():
    host = FakeGrid()
    spec = {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}, {"name": "B", "type": "Add", "x": 3, "y": 0},
                                          {"name": "C", "type": "Add", "x": 6, "y": 0}]}
    run(host, spec)
    swapped = {"target": TARGET, "modules": [{**spec["modules"][0], "x": 3}, {**spec["modules"][1], "x": 0}, spec["modules"][2]]}
    result = run(host, swapped)  # the fake host rejects any move onto an occupied anchor
    assert result["status"] == "applied" and result["applied"]["moved"] == 3
    assert (host.named("A")["x"], host.named("B")["x"], host.named("C")["x"]) == (3, 0, 6)
    chain = {"target": TARGET, "modules": [{**spec["modules"][2], "x": 9}, {**spec["modules"][1], "x": 6}, {**spec["modules"][0], "x": 3}]}
    assert run(host, chain)["applied"]["moved"] == 2  # A already sits at 3; C and B vacate first, no parking


def test_already_matched_writes_are_reported_unchanged():
    host = FakeGrid()
    run(host, SPEC)
    host.calls.clear()
    spec = copy.deepcopy(SPEC)
    spec["modules"][2]["params"]["ATTACK"] = 0.9
    host.fail["grid.parameters.set"] = {"status": "observed", "batchStatus": "applied", "completion": "complete",
                                        "writes": [{"parameterId": "ATTACK", "status": "already_matched"}]}
    result = run(host, spec)
    assert result["status"] == "unchanged" and result["applied"]["paramsWritten"] == 0


def test_null_names_fail_closed_and_unexpected_errors_keep_the_report():
    host = FakeGrid()
    run(host, SPEC)
    host.calls.clear()
    real = host.row
    host.row = lambda m, params: {**real(m, params), "customName": None}
    with pytest.raises(BridgeError) as err:
        run(host, SPEC)
    assert err.value.error["code"] == "invalid_spec" and host.writes() == []
    host = FakeGrid()
    host.fail["grid.connections.set"] = {"status": "observed", "batchStatus": "applied", "connections": [None]}
    result = run(host, SPEC)
    assert result["status"] == "unknown" and result["failure"]["method"] == "internal" and result["applied"]["inserted"] == 5
