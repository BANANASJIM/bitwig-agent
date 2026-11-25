"""grid.apply against a fake Grid host that mirrors the guarded grid.* contracts (CAS, batch statuses)."""

import asyncio
import copy
import json
import math
import uuid

import pytest

from bitwig_agent import grid_apply, raw_scales
from bitwig_agent.catalog import validate_call
from bitwig_agent.cli import parse_arguments
from bitwig_agent.client import BridgeError

TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
WRITES = {"grid.modules.insert", "grid.modules.rename", "grid.modules.move", "grid.modules.delete",
          "grid.parameters.set", "grid.connections.set", "grid.voicing.set"}


def seconds_text(type_name, raw):
    """Host-style display of a seconds parameter (3 significant digits) from the natively measured mappings."""
    seconds = raw ** 3 if type_name in ("AD", "ADSR") else 10 ** raw
    ms = seconds * 1000
    return f"{seconds:.2f} s" if ms >= 1000 else f"{ms:.{max(0, 2 - math.floor(math.log10(ms)))}f} ms"


class FakeGrid:
    def __init__(self, version="6.1.4"):
        self.catalog = grid_apply.bundled_schema()["modules"]
        self.version = version
        self.mods: dict[str, dict] = {}
        self.conns: dict[tuple[str, str], tuple[str, str]] = {}
        self.calls: list[tuple[str, dict]] = []
        self.shift = 0  # insert lands this far right of the request, like native reflow
        self.fail: dict[str, object] = {}
        self.caps_extra: dict = {}
        self.cut_status = "failed"  # status of the entry where a capped insert stops
        self.insert_caps: list[int] = []  # per insert call: apply only this many entries, then report partial
        self.voicing: dict | None = None  # Poly Grid voicing; None = Note/FX Grid
        self.timeouts: list[tuple[str, int]] = []
        self.now = 0.0  # fake monotonic clock (seconds), advanced by insert_seconds per inserted entry
        self.insert_seconds = 0.0

    # state helpers
    def add(self, type_name, custom="", x=0, y=0):
        info = self.catalog[type_name]
        iid = str(uuid.uuid4())
        params = {}
        for p in info["parameters"]:
            params[p["parameterId"]] = {**p, "value": p["default"], "display": p.get("defaultDisplay")}
        if type_name in grid_apply.STEP_TYPES:  # the bundled schema has no step arrays; the live row carries one
            params["DATA"] = {"parameterId": "DATA", "kind": "steps", "value": [0.0] * 64, "display": None}
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

    def row(self, m, params, full=False):
        connected = {p for (t, p) in self.conns if t == m["instanceId"]}
        row = {"instanceId": m["instanceId"], "customName": m["customName"], "name": m["name"], "typeId": m["typeId"],
               "typeName": m["typeName"], "x": m["x"], "y": m["y"], "width": m["info"]["width"], "height": m["info"]["height"],
               "inputs": [{"key": i["key"], "name": i["name"], "connected": i["key"] in connected} for i in m["info"]["inputs"]],
               "outputs": [{"key": o["key"], "name": o["name"]} for o in m["info"]["outputs"]]}
        if params:
            row["parameters"] = [{"supported": True, "kind": p["kind"], "parameterId": pid, "value": p["value"], "display": p["display"],
                                  **{k: p[k] for k in ("minimum", "maximum", "options", "numericUnit") if k in p}}
                                 for pid, p in m["params"].items() if p["kind"] != "steps"]
            for pid, p in m["params"].items():
                if p["kind"] == "steps":
                    count = m["params"]["STEPS"]["value"]
                    row["parameters"].append({"supported": True, "kind": "steps", "parameterId": pid, "arrayLength": 64, "stepCount": count,
                                              "values": list(p["value"] if full else p["value"][:count])})
        return row

    # host
    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, copy.deepcopy(params)))
        self.timeouts.append((method, timeout_ms))
        if method in self.fail:
            outcome = self.fail[method]
            if isinstance(outcome, BridgeError):
                raise outcome
            return outcome
        return getattr(self, "h_" + method.replace("grid.", "").replace(".", "_"))(params)

    def h_capabilities(self, p):
        return {"status": "observed", "hostVersion": self.version, **self.caps_extra}

    def h_catalog(self, p):
        entries = [{"typeId": m["typeId"], "name": n} for n, m in self.catalog.items()]
        return {"status": "observed", "entries": entries[p["offset"]:p["offset"] + p["limit"]],
                "window": {"complete": p["offset"] + p["limit"] >= len(entries)}}

    def h_get(self, p):
        mods = [m for m in self.mods.values() if m["instanceId"] in p.get("moduleIds", self.mods)]
        page = mods[p["moduleOffset"]:p["moduleOffset"] + p["moduleLimit"]]
        conns = [{"targetModuleId": t, "targetPortKey": tp, "sourceModuleId": s, "sourcePortKey": sp} for (t, tp), (s, sp) in self.conns.items()]
        return {"status": "observed", "modules": [self.row(m, p["includeParameters"], p.get("full")) for m in page], "connections": conns,
                "window": {"total": len(mods), "complete": True}}

    def h_modules_insert(self, p):
        entries, added = [], []
        cap = self.insert_caps.pop(0) if self.insert_caps else len(p["modules"])
        for k, spec in enumerate(p["modules"]):
            if k >= cap:
                entries.append({"typeId": spec["typeId"], "status": "not_dispatched" if k > cap else self.cut_status})
                continue
            name = next(n for n, m in self.catalog.items() if m["typeId"] == spec["typeId"])
            iid = self.add(name, "", spec["x"] + self.shift, spec["y"])
            self.now += self.insert_seconds
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
            if w["expectedKind"] == "steps":
                n = len(w["expectedValues"])
                assert len(w["values"]) <= n <= 64
                if [grid_apply.f32(x) for x in par["value"][:n]] != [grid_apply.f32(x) for x in w["expectedValues"]]:
                    raise BridgeError({"code": "stale_context", "message": "stale", "outcome": "not_applied"})
                par["value"][:len(w["values"])] = [grid_apply.f32(x) for x in w["values"]]
                entries.append({"moduleId": w["moduleId"], "parameterId": w["parameterId"], "status": "applied"})
                continue
            if (par["kind"], par["value"]) != (w["expectedKind"], w["expectedValue"]):
                raise BridgeError({"code": "stale_context", "message": "stale", "outcome": "not_applied"})
            if "display" in w:
                par["value"], par["display"] = 99.0, w["display"]
            elif par.get("numericUnit") == "seconds":
                par["value"], par["display"] = w["value"], seconds_text(self.mods[w["moduleId"]]["typeName"], w["value"])
            else:
                par["value"], par["display"] = w["value"], str(w["value"])
            entries.append({"moduleId": w["moduleId"], "parameterId": w["parameterId"], "status": "applied"})
        return {"status": "observed", "batchStatus": "applied", "completion": "complete", "writes": entries}

    def h_transport_get(self, p):
        return {"status": "observed", **getattr(self, "transport", {"playing": False})}

    def h_voicing_get(self, p):
        if self.voicing is None:
            return {"status": "observed", "supported": False, "reason": "no_voicing_model: only Poly Grid has device voicing"}
        return {"status": "observed", "supported": True, "voicing": dict(self.voicing), "mode": "poly" if self.voicing["voices"] > 1 else self.voicing["monoMode"]}

    def h_voicing_set(self, p):
        if any(self.voicing[k] != v for k, v in p["expected"].items()):
            raise BridgeError({"code": "stale_context", "message": "stale voicing", "outcome": "not_applied"})
        self.voicing.update(p["set"])
        return {"status": "observed", "supported": True, "voicing": dict(self.voicing), "changedUnrequested": []}

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


def test_topology_writes_get_the_long_timeout():
    host = FakeGrid()
    host.shift = 1
    run(host, SPEC)
    by_method = {}
    for method, timeout in host.timeouts:
        by_method.setdefault(method, set()).add(timeout)
    for method in ("grid.modules.insert", "grid.modules.rename", "grid.modules.move", "grid.connections.set"):
        assert by_method[method] == {60000}, method
    assert by_method["grid.parameters.set"] == {10000} and by_method["grid.get"] == {5000}


def test_insert_batches_shrink_when_entries_are_slow(monkeypatch):
    host = FakeGrid()
    monkeypatch.setattr(grid_apply, "monotonic", lambda: host.now)
    host.insert_seconds = 2.0  # 16 entries x 2 s = 32 s > 25% of the 60 s timeout
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(30)]}
    assert run(host, spec)["applied"]["inserted"] == 30
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [16, 7, 7]


def test_insert_batches_start_halved_on_a_large_graph():
    host = FakeGrid()
    for i in range(grid_apply.LARGE_GRAPH_MODULES):
        host.add("Add", f"other {i}", x=i * 3, y=40)
    spec = {"target": TARGET, "modules": [{"name": f"M{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    assert run(host, spec)["applied"]["inserted"] == 20
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [8, 12]


def test_next_insert_batch_uses_slowest_entry_and_host_time_stops():
    applied = [{"status": "applied"}] * 4
    assert grid_apply.next_insert_batch(16, 0.04, {"modules": applied}) == 16
    assert grid_apply.next_insert_batch(16, 0.04, {"modules": applied, "slowestEntryMs": 5000}) == 3
    cut = {"modules": applied + [{"status": "not_dispatched"}] * 4, "stopReason": "deadline_margin"}
    assert grid_apply.next_insert_batch(8, 0.04, cut) == 4


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
    {"ATTACK": {"display": "10 hz"}},  # wrong unit for seconds
    {"ATTACK": {"display": "10 s"}},  # raw 2.154 outside 0..2
    {"ATTACK": {"display": "-1 ms"}},
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
    host.row = lambda m, params, full=False: {**real(m, params, full), "customName": None}
    with pytest.raises(BridgeError) as err:
        run(host, SPEC)
    assert err.value.error["code"] == "invalid_spec" and host.writes() == []
    host = FakeGrid()
    host.fail["grid.connections.set"] = {"status": "observed", "batchStatus": "applied", "connections": [None]}
    result = run(host, SPEC)
    assert result["status"] == "unknown" and result["failure"]["method"] == "internal" and result["applied"]["inserted"] == 5


def test_park_spot_is_always_free_and_converges():
    host = FakeGrid()
    host.add("Add", "P", 10, 0)
    host.add("Phasor", "A", 0, 0)  # 3x1
    host.add("Add", "B", 4, 0)
    spec = {"target": TARGET, "modules": [{"name": "P", "type": "Add", "x": 2, "y": 0}, {"name": "A", "type": "Phasor", "x": 4, "y": 0},
                                          {"name": "B", "type": "Add", "x": 0, "y": 0}]}
    result = run(host, spec)  # the fake host rejects any move onto an occupied anchor
    assert result["status"] == "applied" and result["applied"]["moved"] <= 6
    assert [(host.named(n)["x"], host.named(n)["y"]) for n in "PAB"] == [(2, 0), (4, 0), (0, 0)]
    host.calls.clear()
    assert run(host, spec)["status"] == "unchanged" and host.writes() == []


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
        run(host, spec)
    assert "host limit is 3" in err.value.error["message"] and host.writes() == []
    host.caps_extra = {"moduleInsert": {"available": True, "maximumModules": 8, "topologyQuarantined": True}}
    with pytest.raises(BridgeError) as err:
        run(host, spec)
    assert "topologyQuarantined" in err.value.error["message"] and host.writes() == []
    assert run(host, spec, dryRun=True)["status"] == "dryRun"
    host.caps_extra = {"moduleInsert": {"available": False}}
    with pytest.raises(BridgeError):
        run(host, spec)


def test_deadline_cut_batches_continue_with_the_remaining_modules():
    spec = {"target": TARGET, "modules": [{"name": f"D{i}", "type": "Add", "x": i * 3, "y": 0} for i in range(20)]}
    host = FakeGrid()
    host.cut_status = "not_dispatched"  # the host stopped for lack of time, nothing failed
    host.insert_caps = [5, 3]
    result = run(host, spec)
    assert result["status"] == "applied" and result["applied"]["inserted"] == 20 and result["applied"]["renamed"] == 20
    assert [len(c["modules"]) for m, c in host.calls if m == "grid.modules.insert"] == [16, 15, 12]
    assert not [m for m in host.mods.values() if not m["customName"]]


def test_unchanged_spec_reads_only_what_it_needs():
    host = FakeGrid()
    run(host, SPEC)
    host.calls.clear()
    assert run(host, SPEC)["status"] == "unchanged"
    names = [m for m, _ in host.calls]
    assert names.count("grid.get") == 2  # structure once, parameters once; no post-insert or post-param re-read
    assert host.writes() == []


def test_display_grammar_matches_the_host_trim_and_ascii_digits():
    q = grid_apply.parse_quantity
    assert q("decibel", " 6 dB") is None and q("decibel", "٣ dB") is None and q("decibel", " 6 dB\t")[0] == 6


def test_string_panel_params_are_written_with_cas_and_idempotent():
    host = FakeGrid()
    text = "line 1\nline 2 \u00e9\U0001f600"
    spec = {"target": TARGET, "modules": [{"name": "C", "type": "Comment", "x": 0, "y": 0, "params": {"TEXT": text}}]}
    assert run(host, spec)["applied"]["paramsWritten"] == 1
    assert host.param("C", "TEXT") == text
    host.calls.clear()
    assert run(host, spec)["status"] == "unchanged" and host.writes() == []
    run(host, {**spec, "modules": [{**spec["modules"][0], "params": {"TEXT": ""}}]})
    sent = [c for m, c in host.calls if m == "grid.parameters.set"][0]["writes"][0]
    assert sent["expectedKind"] == "string" and sent["expectedValue"] == text and sent["value"] == "" and host.param("C", "TEXT") == ""


def test_string_param_validation():
    host = FakeGrid()
    mod = {"name": "C", "type": "Comment", "x": 0, "y": 0}
    for bad in (5, "x" * 1025, "\U0001f600" * 513):
        with pytest.raises(BridgeError) as err:
            run(host, {"target": TARGET, "modules": [{**mod, "params": {"TEXT": bad}}]})
        assert err.value.error["code"] == "invalid_spec"
    run(host, {"target": TARGET, "modules": [{**mod, "params": {"TEXT": "\U0001f600" * 512}}]}, dryRun=True)
    with pytest.raises(BridgeError):
        run(host, {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0, "params": {"X": "s"}}]})
    assert host.writes() == []


def test_empty_modules_prunes_namespace_only():
    host = populated_namespace_host()
    before = {m["customName"] for m in host.mods.values()}
    result = run(host, {"target": TARGET, "namespace": "H: ", "prune": True, "modules": []})
    assert result["status"] == "applied" and result["applied"]["deleted"] == 6
    left = {m["customName"] for m in host.mods.values()}
    assert left == {"X: keep", ""} and before - left == {n for n in before if n.startswith("H: ")}
    assert run(host, {"target": TARGET, "namespace": "H: ", "prune": True, "modules": []})["status"] == "unchanged"


@pytest.mark.parametrize("extra", [{}, {"prune": True}, {"namespace": "H: "}])
def test_empty_modules_needs_prune_and_namespace(extra):
    host = FakeGrid()
    with pytest.raises(ValueError, match="modules may be empty"):
        run(host, {"target": TARGET, "modules": [], **extra})
    assert host.writes() == []


def replace_host():
    host = FakeGrid()
    host.add("Add", "R: a", 0, 0)
    host.add("Add", "R: src", 6, 0)
    host.conns[(host.named("R: a")["instanceId"], "IN")] = (host.named("R: src")["instanceId"], "OUT")
    return host


REPLACE_SPEC = {"target": TARGET, "modules": [
    {"name": "R: a", "type": "Multiply", "x": 0, "y": 0}, {"name": "R: src", "type": "Add", "x": 6, "y": 0}],
    "connections": [{"from": "R: src.OUT", "to": "R: a.IN"}]}


def test_replace_default_refuses_and_opt_in_swaps_type_and_rewires():
    host = replace_host()
    with pytest.raises(BridgeError) as err:
        run(host, REPLACE_SPEC)
    assert "exists as" in err.value.error["message"] and host.writes() == []
    spec = copy.deepcopy(REPLACE_SPEC)
    other = host.add("Add", "Z: out", 30, 0)
    host.conns[(other, "IN")] = (host.named("R: a")["instanceId"], "OUT")
    plan = run(host, spec, replace=True, dryRun=True)
    assert plan["plan"]["replaces"] == ["R: a"] and host.writes() == []
    assert plan["plan"]["droppedConnections"] == ["R: a.OUT -> Z: out.IN"] and "R: a.OUT -> Z: out.IN" in plan["warnings"][0]
    result = run(host, spec, replace=True)
    assert result["status"] == "applied" and result["applied"]["deleted"] == 1 and result["applied"]["inserted"] == 1
    assert host.named("R: a")["typeName"] == "Multiply" and host.source("R: a", "IN") == ("R: src", "OUT")
    host.calls.clear()
    assert run(host, spec, replace=True)["status"] == "unchanged" and host.writes() == []


def test_replace_per_module_only_affects_that_module():
    host = replace_host()
    host.add("Add", "R: other", 12, 0)
    spec = copy.deepcopy(REPLACE_SPEC)
    spec["modules"][0]["replace"] = True
    spec["modules"].append({"name": "R: other", "type": "Multiply", "x": 12, "y": 0})
    with pytest.raises(BridgeError) as err:
        run(host, spec)
    assert "R: other" in err.value.error["message"] and "'R: a' exists" not in err.value.error["message"]
    assert host.writes() == []
    spec["modules"][1]["replace"] = False
    spec["modules"][2]["replace"] = True
    assert run(host, spec)["applied"]["deleted"] == 2


def test_notes_and_pitch_mod_warning():
    spec = {"target": TARGET, "modules": [
        {"name": "o", "type": "Sine", "x": 0, "y": 0}, {"name": "o2", "type": "Sine", "x": 6, "y": 0, "params": {"PITCH_MOD": 0.5}},
        {"name": "n", "type": "Add", "x": 12, "y": 0}],
        "connections": [{"from": "n.OUT", "to": "o.PITCH_IN"}, {"from": "n.OUT", "to": "o2.PITCH_IN"}]}
    host = FakeGrid()
    result = run(host, spec, dryRun=True)
    assert [w for w in result["warnings"] if "PITCH_MOD" in w] == ["o: PITCH_IN is connected but PITCH_MOD is 0, so it has no effect (raw 1.0 = 120 semitones)"]
    result = run(host, spec)
    assert [w for w in result["warnings"] if "PITCH_MOD" in w] == ["o: PITCH_IN is connected but PITCH_MOD is 0, so it has no effect (raw 1.0 = 120 semitones)"]
    assert any(n.startswith("Sine: PITCH_IN is scaled by PITCH_MOD") for n in result["notes"])
    assert not any(n.startswith("Chance:") for n in result["notes"]) and any("voice lifetime" in n for n in result["notes"])
    assert len(result["notes"]) < 8


def test_notes_file_is_value_free_and_covers_catalog_types():
    notes = grid_apply.measured_notes()
    assert notes["hostVersion"] == "6.1.4"
    assert set(notes["modules"]) <= set(grid_apply.bundled_schema()["modules"])


# --- seconds display writes ----------------------------------------------------------------------------------
def seconds_spec(params_env, params_gate=None):
    return {"target": TARGET, "modules": [{"name": "E", "type": "ADSR", "x": 0, "y": 0, "params": params_env},
                                          {"name": "G", "type": "Gate Length", "x": 0, "y": 8, "params": params_gate or {}}]}


def test_seconds_display_writes_converted_raw_and_is_idempotent():
    host = FakeGrid()
    spec = seconds_spec({"ATTACK": {"display": "3 ms"}, "DECAY": {"display": "0.5 s"}}, {"LENGTH": {"display": "120 ms"}})
    result = run(host, spec)
    assert result["applied"]["paramsWritten"] == 3
    writes = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]]
    assert all("display" not in w and "shown" not in w for w in writes)  # the host only sees raw values
    assert host.param("E", "ATTACK") == pytest.approx(0.003 ** (1 / 3)) and host.param("E", "DECAY") == pytest.approx(0.5 ** (1 / 3))
    assert host.param("G", "LENGTH") == pytest.approx(math.log10(0.12))
    host.calls.clear()
    again = run(host, spec)
    assert again["status"] == "unchanged" and host.writes() == [] and again["alreadyMatched"]["params"] == 3
    host.named("E")["params"]["ATTACK"].update(value=0.144, display="2.99 ms")  # native pair one step off: 3 ms is a change
    assert run(host, spec)["applied"]["paramsWritten"] == 1


def test_seconds_dry_run_shows_the_converted_raw_value():
    result = run(FakeGrid(), seconds_spec({"ATTACK": {"display": "8 ms"}}), dryRun=True)
    entry = next(p for p in result["plan"]["params"] if p["parameterId"] == "ATTACK")
    assert entry["rawValue"] == pytest.approx(0.2) and entry["to"] == {"display": "8 ms"}


@pytest.mark.parametrize("module,param,text", [
    ("Long Delay", "TIME", "100 ms"),  # default 1.0 = "1.00 s" fits cbrt only by coincidence
    ("Audio Out", "HOLD_TIME", "100 ms"),  # linear
    ("ADSR", "ATTACK", "1 kb"),
])
def test_unverified_seconds_display_is_rejected_offline(module, param, text):
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        run(host, {"target": TARGET, "modules": [{"name": "X", "type": module, "x": 0, "y": 0, "params": {param: {"display": text}}}]})
    assert err.value.error["code"] == "invalid_spec" and host.writes() == []


def test_seconds_table_matches_bundled_schema_and_nothing_else_fits():
    schema = grid_apply.bundled_schema()["modules"]
    seen = set()
    for name, info in schema.items():
        for p in info["parameters"]:
            if p.get("numericUnit") != "seconds":
                continue
            key = (name, p["parameterId"])
            shown = grid_apply.parse_quantity("seconds", p["defaultDisplay"])
            fits = [k for k, (_, from_raw) in grid_apply.SECONDS_SCALES.items() if abs(from_raw(p["default"]) - shown[0]) <= shown[1] * 1.000001]
            args = (key[1], p["defaultDisplay"], "seconds", p["default"], p["defaultDisplay"], p["minimum"], p["maximum"], name)
            if key in grid_apply.SECONDS_PARAMS:
                mapping, raw, display = grid_apply.SECONDS_PARAMS[key]
                seen.add(key)
                assert fits == [mapping] and display == p["defaultDisplay"] and abs(raw - p["default"]) < 1e-4
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
    result = run(host, spec)
    assert result["status"] == "applied" and host.param("E", "ATTACK") == pytest.approx(0.003 ** (1 / 3))
    host.calls.clear()
    assert run(host, spec)["status"] == "unchanged" and host.writes() == []


# --- blocks ---------------------------------------------------------------------------------------------------
BLOCKS = {"voice": {
    "params": {"atk": 0.5, "gain": 0.25, "rel": "20 ms"},
    "modules": [{"name": "mul", "type": "Bias", "x": 0, "y": 0, "params": {"VALUE": "${gain}"}},
                {"name": "env", "type": "ADSR", "x": 4, "y": 0, "params": {"ATTACK": "${atk}", "RELEASE": {"display": "${rel}"}}}],
    "connections": [{"from": "mul.OUT", "to": "env.IN"}]}}
BLOCK_SPEC = {
    "target": TARGET, "blocks": BLOCKS, "namespace": "V: ",
    "modules": [{"name": "V: sum", "type": "Add", "x": 20, "y": 0}],
    "instances": [{"block": "voice", "prefix": "V1: "},
                  {"block": "voice", "prefix": "V2: ", "dy": 12, "args": {"atk": 0.1, "gain": 0.75}}],
    "connections": [{"from": "V1: env.OUT", "to": "V: sum.IN"}, {"from": "V2: env.OUT", "to": "V: sum.IN2"}],
}


def test_blocks_expand_with_prefix_offset_and_typed_placeholders():
    host = FakeGrid()
    result = run(host, BLOCK_SPEC, dryRun=True)
    assert [i["modules"] for i in result["instances"]] == [["V1: mul", "V1: env"], ["V2: mul", "V2: env"]]
    assert {(i["name"], i["x"], i["y"]) for i in result["plan"]["inserts"]} >= {("V1: mul", 0, 0), ("V2: env", 4, 12), ("V: sum", 20, 0)}
    assert len(result["plan"]["inserts"]) == 5 and host.writes() == []
    result = run(host, BLOCK_SPEC)
    assert result["status"] == "applied" and result["instances"][1]["prefix"] == "V2: "
    assert result["applied"]["inserted"] == 5 and result["applied"]["connections"] == 4
    assert host.param("V1: env", "ATTACK") == 0.5 and host.param("V2: env", "ATTACK") == 0.1  # default vs arg, still numbers
    assert host.param("V2: mul", "VALUE") == 0.75 and host.param("V1: mul", "VALUE") == 0.25
    assert host.param("V1: env", "RELEASE") == pytest.approx(0.02 ** (1 / 3))  # {display} placeholder reaches the seconds conversion
    assert (host.named("V2: env")["x"], host.named("V2: env")["y"]) == (4, 12)
    assert host.source("V1: env", "IN") == ("V1: mul", "OUT") and host.source("V: sum", "IN2") == ("V2: env", "OUT")
    host.calls.clear()
    assert run(host, BLOCK_SPEC)["status"] == "unchanged" and host.writes() == []


def test_blocks_are_checked_after_expansion():
    host = FakeGrid()

    def fails(spec, text):
        with pytest.raises(BridgeError) as err:
            run(host, spec)
        assert err.value.error["code"] == "invalid_spec" and text in err.value.error["message"]
        assert host.writes() == []

    fails({**BLOCK_SPEC, "instances": [{"block": "voic", "prefix": "V1: "}]}, "unknown block")
    fails({**BLOCK_SPEC, "instances": [{"block": "voice", "prefix": "V1: ", "args": {"atack": 1}}]}, "unknown args")
    fails({**BLOCK_SPEC, "instances": [{"block": "voice", "prefix": "V1: "}, {"block": "voice", "prefix": "V1: ", "dy": 12}]}, "distinct prefixes")
    fails({**BLOCK_SPEC, "instances": [{"block": "voice", "prefix": "V1: "}, {"block": "voice", "prefix": "V2: "}]}, "overlap")  # same dx/dy
    fails({**BLOCK_SPEC, "instances": [{"block": "voice", "prefix": "V1: ", "args": {"atk": 9}}, {"block": "voice", "prefix": "V2: ", "dy": 12}]}, "outside")
    bad = {"voice": {**BLOCKS["voice"], "connections": [{"from": "V1: mul.OUT", "to": "env.IN"}]}}
    fails({**BLOCK_SPEC, "blocks": bad}, "local module name")
    typo = {"voice": {**BLOCKS["voice"], "modules": [{**BLOCKS["voice"]["modules"][0], "params": {"VALUE": "${gian}"}}, BLOCKS["voice"]["modules"][1]]}}
    fails({**BLOCK_SPEC, "blocks": typo}, "placeholder")
    wrong_type = {"voice": {**BLOCKS["voice"], "modules": [{**BLOCKS["voice"]["modules"][0], "params": {"VALUE": "${rel}"}}, BLOCKS["voice"]["modules"][1]]}}
    fails({**BLOCK_SPEC, "blocks": wrong_type}, "needs a number")
    fails({**BLOCK_SPEC, "connections": BLOCK_SPEC["connections"] + [{"from": "V: sum.OUT", "to": "V1: env.IN"}]}, "repeats connection target")


def test_blocks_schema_surface():
    with pytest.raises(ValueError):
        validate_call("grid.apply", {"target": TARGET, "instances": [{"block": "x"}]})  # instances without blocks
    with pytest.raises(ValueError):
        validate_call("grid.apply", {"target": TARGET})  # nothing to apply
    with pytest.raises(ValueError):  # blocks do not nest
        validate_call("grid.apply", {**BLOCK_SPEC, "blocks": {"b": {"modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}], "instances": []}}})
    args, _ = validate_call("grid.apply", {"target": TARGET, "namespace": "V: ", "prune": True})
    assert "modules" not in args


# --- step arrays and voicing ----------------------------------------------------------------------------------
POLY = {"voices": 1, "voiceStacking": 1, "monoMode": "true_mono", "retriggerMode": "on_note_on", "notePriority": "last",
        "legatoGlide": False, "stealSameKey": False, "allowOverlappingNotes": False}


def steps_spec(steps, count=8, type_name="Pitches"):
    return {"target": TARGET, "modules": [{"name": "S", "type": type_name, "x": 0, "y": 0, "params": {"STEPS": count, "DATA": {"steps": steps}}}]}


def test_steps_are_written_with_prefix_cas_and_idempotent():
    host = FakeGrid()
    spec = steps_spec([1, 0, 0.5, 0.1])
    result = run(host, spec)
    assert result["status"] == "applied"
    assert host.param("S", "DATA")[:5] == [1.0, 0.0, 0.5, grid_apply.f32(0.1), 0.0]
    sent = [c for m, c in host.calls if m == "grid.parameters.set"]
    entry = [w for c in sent for w in c["writes"] if w["expectedKind"] == "steps"][0]
    assert entry["expectedValues"] == [0.0] * 4 and entry["values"] == [1, 0, 0.5, 0.1] and "expectedValue" not in entry
    host.calls.clear()
    again = run(host, spec)
    assert again["status"] == "unchanged" and host.writes() == []  # float32 rounding of 0.1 compares equal


def test_steps_change_writes_only_the_differing_array_and_reads_full_for_long_lists():
    host = FakeGrid()
    run(host, steps_spec([1, 1, 1], count=4))
    host.calls.clear()
    result = run(host, steps_spec([1, 1, 0] + [0.25] * 7, count=4))  # 10 entries > STEPS 4
    assert result["applied"]["paramsWritten"] == 1
    entry = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]][0]
    assert entry["full"] is True and entry["expectedValues"] == [1.0, 1.0, 1.0] + [0.0] * 7
    assert host.param("S", "DATA")[:10] == [1, 1, 0] + [0.25] * 7
    assert any(c.get("full") for m, c in host.calls if m == "grid.get")


def test_steps_dry_run_and_offline_validation_write_nothing():
    host = FakeGrid()
    assert run(host, steps_spec([1, 2]), dryRun=True)["status"] == "dryRun" and host.writes() == []
    for spec in (steps_spec([1, 2], type_name="Add"), steps_spec([0] * 257), steps_spec([500]), steps_spec([])):
        with pytest.raises(Exception):
            run(host, spec)
    assert host.writes() == []


def test_steps_longer_than_live_array_rejects_before_writing_on_existing_module():
    host = FakeGrid()
    host.add("Pitches", "S", 0, 0)
    with pytest.raises(BridgeError) as err:
        run(host, steps_spec([0.5] * 65))
    assert "array holds 64" in err.value.error["message"] and host.writes() == []


def test_steps_block_placeholder_is_a_whole_list():
    host = FakeGrid()
    spec = {"target": TARGET,
            "blocks": {"seq": {"params": {"row": [0.0]}, "modules": [{"name": "g", "type": "Gates", "x": 0, "y": 0, "params": {"DATA": {"steps": "${row}"}}}]}},
            "instances": [{"block": "seq", "prefix": "A: ", "args": {"row": [1, 0, 1, 1]}}, {"block": "seq", "prefix": "B: ", "dy": 6}]}
    result = run(host, spec)
    assert result["status"] == "applied"
    assert host.param("A: g", "DATA")[:4] == [1, 0, 1, 1] and host.param("B: g", "DATA")[:2] == [0, 0]
    host.calls.clear()
    assert run(host, spec)["status"] == "unchanged" and host.writes() == []


def test_voicing_is_written_when_different_and_idempotent():
    host = FakeGrid()
    host.voicing = dict(POLY)
    spec = {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}],
            "voicing": {"voices": 8, "voiceStacking": 1, "monoMode": "true_mono"}}
    result = run(host, spec)
    assert result["applied"]["voicingFields"] == 1 and result["alreadyMatched"]["voicingFields"] == 2
    sent = [c for m, c in host.calls if m == "grid.voicing.set"]
    assert sent[0]["expected"] == {"voices": 1} and sent[0]["set"] == {"voices": 8} and host.voicing["voices"] == 8
    host.calls.clear()
    again = run(host, spec)
    assert again["status"] == "unchanged" and host.writes() == []
    plan = run(host, {**spec, "voicing": {"voices": 2}}, dryRun=True)
    assert plan["plan"]["voicing"] == {"set": {"voices": 2}, "from": {"voices": 8}} and host.voicing["voices"] == 8


def test_voicing_only_spec_and_stale_voicing_is_not_retried():
    host = FakeGrid()
    host.voicing = dict(POLY)
    spec = {"target": TARGET, "modules": [], "prune": True, "namespace": "H: ", "voicing": {"monoMode": "digi_mono"}}
    assert run(host, spec)["applied"]["voicingFields"] == 1 and host.voicing["monoMode"] == "digi_mono"


def test_voicing_on_non_poly_grid_is_refused_before_any_write():
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        run(host, {**SPEC, "voicing": {"voices": 4}})
    assert err.value.error["code"] == "invalid_spec" and "no voicing model" in err.value.error["message"] and host.writes() == []


def test_voicing_schema_surface():
    with pytest.raises(Exception):
        validate_call("grid.apply", {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}], "voicing": {}})
    with pytest.raises(Exception):
        validate_call("grid.apply", {"target": TARGET, "modules": [{"name": "A", "type": "Add", "x": 0, "y": 0}], "voicing": {"voices": 65}})


# --- preflight warnings, wouldFail and actionable errors ------------------------------------------------------
def messages(result, text):
    return [w for w in result.get("warnings", []) if text in w]


def test_device_phase_cable_warning_only_while_device_phase_is_on():
    mods = [{"name": "ph", "type": "Phasor", "x": 0, "y": 0}, {"name": "st", "type": "Steps", "x": 8, "y": 0},
            {"name": "st2", "type": "Gates", "x": 16, "y": 0, "params": {"DEVICE_PHASE": False}}]
    conns = [{"from": "ph.OUT", "to": "st.PHASE_IN"}, {"from": "ph.OUT", "to": "st2.PHASE_IN"}]
    result = run(FakeGrid(), {"target": TARGET, "modules": mods, "connections": conns}, dryRun=True)
    assert [w.split(":")[0] for w in messages(result, "DEVICE_PHASE")] == ["st"]
    assert any(n.startswith("Steps: DEVICE_PHASE defaults to On") for n in result["notes"])


def test_keytrack_warning_needs_a_poly_grid_and_nonzero_keytrack():
    mods = [{"name": "f", "type": "SVF", "x": 0, "y": 0}, {"name": "g", "type": "SVF", "x": 8, "y": 0, "params": {"KEYTRACK": 0}}]
    conns = [{"from": "f.OUT", "to": "g.IN"}]
    spec = {"target": TARGET, "modules": mods, "connections": conns}
    note_grid = run(FakeGrid(), spec, dryRun=True)
    assert not messages(note_grid, "KEYTRACK")
    host = FakeGrid()
    host.voicing = {"voices": 8, "voiceStacking": 1, "monoMode": "true_mono"}
    result = run(host, spec, dryRun=True)
    assert [w.split(":")[0] for w in messages(result, "KEYTRACK")] == ["f"]


def test_dangling_modules_are_flagged_but_note_in_is_not():
    mods = [{"name": "n", "type": "Note In", "x": 0, "y": 0}, {"name": "lonely", "type": "Add", "x": 0, "y": 8},
            {"name": "o", "type": "Sine", "x": 8, "y": 0}, {"name": "m", "type": "Add", "x": 16, "y": 0}]
    result = run(FakeGrid(), {"target": TARGET, "modules": mods, "connections": [{"from": "m.OUT", "to": "o.PITCH_IN"}]}, dryRun=True)
    assert messages(result, "lonely: has no connections") and not messages(result, "n: ")
    assert messages(result, "o: has connected inputs but none of its outputs")


def test_dry_run_would_fail_reports_transport_and_stays_read_only():
    host = FakeGrid()
    host.transport = {"playing": True}
    spec = {"target": TARGET, "modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}]}
    result = run(host, spec, dryRun=True)
    assert [f["code"] for f in result["wouldFail"]] == ["unsafe_state"] and host.writes() == []
    host.transport = {"playing": False}
    assert "wouldFail" not in run(host, spec, dryRun=True)


def test_dry_run_would_fail_flags_host_converted_display_and_missing_target():
    spec = {"target": TARGET, "modules": [{"name": "f", "type": "SVF", "x": 0, "y": 0, "params": {"CUTOFF": {"display": "800 Hz"}}}]}
    result = run(FakeGrid(), spec, dryRun=True)
    assert [f["code"] for f in result["wouldFail"]] == ["display_unverified"]
    host = FakeGrid()
    host.fail["grid.get"] = BridgeError({"code": "target_not_found", "message": "no device", "outcome": "not_applied"})
    result = run(host, spec, dryRun=True)
    assert result["status"] == "dryRun" and result["wouldFail"][0]["code"] == "target_not_found" and host.writes() == []
    with pytest.raises(BridgeError):
        run(host, spec)


def error_text(host, spec):
    with pytest.raises(BridgeError) as exc:
        run(host, spec)
    return exc.value.error["message"]


def test_unknown_parameter_and_port_errors_list_valid_ids():
    low = {"target": TARGET, "modules": [{"name": "f", "type": "Amplify", "x": 0, "y": 0, "params": {"GAIN": 0.5}}]}
    text = error_text(FakeGrid(), low)
    assert "unknown parameter 'GAIN'" in text and "Valid: BIPOLAR, STEREOIZE, VALUE" in text
    mods = [{"name": "osc", "type": "Sine", "x": 0, "y": 0}, {"name": "t", "type": "Triggers", "x": 8, "y": 0}]
    text = error_text(FakeGrid(), {"target": TARGET, "modules": mods, "connections": [{"from": "osc.out", "to": "t.PHASE_IN"}]})
    assert "Did you mean: 'OUT'" in text and "Valid: OUT" in text
    assert "Did you mean: 'IN'" in text and "Valid: IN" in text


def test_overlap_error_names_the_blocker():
    host = FakeGrid()
    host.add("Add", "C: bar", 0, 0)
    host.add("Add", "", 10, 0)
    text = error_text(host, {"target": TARGET, "modules": [{"name": "a", "type": "Sine", "x": 0, "y": 0}, {"name": "b", "type": "Sine", "x": 10, "y": 0}]})
    assert "'C: bar' (Add@0,0)" in text and "Add@10,0" in text


def test_real_apply_returns_a_diagnostics_marker(tmp_path, monkeypatch):
    from datetime import datetime
    log = tmp_path / "bitwig.log"
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S.000")
    log.write_text(f"[{now} float-main-app info] Starting application version 6.1.4 revision abc\n")
    monkeypatch.setenv("BITWIG_LOG_PATH", str(log))
    result = run(FakeGrid(), {"target": TARGET, "modules": [{"name": "a", "type": "Add", "x": 0, "y": 0}]})
    assert isinstance(result.get("diagnosticsMarker"), str)
