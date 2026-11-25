"""Client-side `grid.apply`: a declarative, re-appliable Grid patch composed from the guarded grid.* host methods.

No host method is added. Every write is an ordinary guarded call with fresh CAS values, one request at a time,
stopping at the first error/unknown/partial outcome and never retrying.
"""

from __future__ import annotations

import difflib
import json
import math
import re
from importlib import resources
from typing import Any

from bitwig_agent.client import BridgeError

BUNDLED_HOST_VERSION = "6.1.4"
INSERT_BATCH, PARAM_BATCH, CONNECTION_BATCH, READ_IDS, MODULE_LIMIT = 16, 64, 32, 32, 128
WRITE_TIMEOUT_MS = 10000
CLEAN = ("applied", "already_matched")
_schema_cache: dict[str, Any] = {}


def bundled_schema() -> dict[str, Any]:
    if not _schema_cache:
        text = resources.files("bitwig_agent").joinpath("data/grid_schema_6_1_4.json").read_text(encoding="utf-8")
        _schema_cache.update(json.loads(text))
    return _schema_cache


def _norm(text: str) -> str:
    return "".join(ch for ch in text.casefold() if ch.isalnum())


def _near(word: str, choices) -> str:
    close = difflib.get_close_matches(word, list(choices), n=4, cutoff=0.5)
    return f" Did you mean: {', '.join(map(repr, close))}?" if close else ""


class SpecError(Exception):
    def __init__(self, problems: list[str]):
        self.problems = problems
        super().__init__("; ".join(problems))


class Stop(Exception):
    """A dispatched call ended unclean; carries the raw response."""

    def __init__(self, method: str, raw: dict[str, Any], outcome: str):
        self.method, self.raw, self.outcome = method, raw, outcome
        super().__init__(method)


def _pos(row: dict[str, Any]) -> tuple[int, int]:
    return row["x"], row["y"]


def _display_parts(text: str):
    match = re.fullmatch(r"\s*(-?\d+(?:\.\d+)?)\s*([^\s\d].*?)?\s*", text)
    if not match:
        return None
    number, unit = float(match.group(1)), (match.group(2) or "").strip().casefold()
    if unit == "khz":
        number, unit = number * 1000, "hz"
    return number, unit, len(re.sub(r"[^0-9]", "", match.group(1)).lstrip("0")) or 1


def display_matches(wanted: str, live: str | None) -> bool:
    """True when the live display already shows the wanted text at the live display's precision."""
    if live is None:
        return False
    if wanted.strip().casefold() == live.strip().casefold():
        return True
    a, b = _display_parts(wanted), _display_parts(live)
    if not a or not b or a[1] != b[1]:
        return False
    digits = b[2]
    scale = b[0] if b[0] else 1.0
    return math.isclose(float(f"{a[0]:.{digits}g}"), b[0], rel_tol=1e-9, abs_tol=abs(scale) * 1e-9)


def check_value(pid: str, raw: Any, p: dict[str, Any]) -> dict[str, Any]:
    """Validate one spec value against a parameter description {kind, options?, minimum?, maximum?}; return the write fields."""
    kind = p.get("kind")
    if isinstance(raw, dict) and "display" in raw:
        if kind != "float":
            raise SpecError([f"{pid}: display needs a float parameter, this is {kind}"])
        return {"display": raw["display"]}
    if isinstance(raw, dict):
        options = p.get("options") or []
        if kind != "enum":
            raise SpecError([f"{pid}: label needs an enum parameter, this is {kind}"])
        labels = {o["label"]: o["value"] for o in options}
        folded = {k.casefold(): v for k, v in labels.items()}
        if raw["label"] in labels:
            return {"value": labels[raw["label"]]}
        if raw["label"].casefold() in folded:
            return {"value": folded[raw["label"].casefold()]}
        raise SpecError([f"{pid}: unknown label {raw['label']!r}.{_near(raw['label'], labels)} Options: {list(labels)}"])
    if kind == "boolean":
        if type(raw) is not bool:
            raise SpecError([f"{pid}: boolean parameter needs true/false, got {raw!r}"])
    elif kind in ("integer", "enum"):
        if type(raw) is not int:
            raise SpecError([f"{pid}: {kind} parameter needs an integer, got {raw!r}"])
        if kind == "enum" and p.get("options") is not None and raw not in {o["value"] for o in p["options"]}:
            raise SpecError([f"{pid}: {raw} is not an option value; options {[(o['value'], o['label']) for o in p['options']]}"])
    elif kind == "float":
        if type(raw) not in (int, float):
            raise SpecError([f"{pid}: float parameter needs a number, got {raw!r}"])
    else:
        raise SpecError([f"{pid}: {kind} parameters cannot be written"])
    if kind in ("integer", "float") and p.get("minimum") is not None and not p["minimum"] <= raw <= p["maximum"]:
        raise SpecError([f"{pid}: {raw} outside {p['minimum']}..{p['maximum']}"])
    return {"value": raw}


def value_matches(kind: str, want: dict[str, Any], row_param: dict[str, Any]) -> bool:
    if "display" in want:
        return display_matches(want["display"], row_param.get("display"))
    live, new = row_param.get("value"), want["value"]
    if kind == "boolean":
        return type(live) is bool and live == new
    if kind == "float":
        return type(live) in (int, float) and math.isclose(live, new, rel_tol=1e-9, abs_tol=1e-12)
    return type(live) is int and live == new


class Apply:
    def __init__(self, client: Any, args: dict[str, Any], timeout_ms: int):
        self.client, self.args, self.timeout_ms = client, args, timeout_ms
        self.target = args["target"]
        self.dry = bool(args.get("dryRun"))
        self.prune = bool(args.get("prune"))
        self.namespace = args.get("namespace")
        self.modules: list[dict[str, Any]] = args["modules"]
        self.names = [m["name"] for m in self.modules]
        self.applied = dict.fromkeys(("inserted", "renamed", "moved", "paramsWritten", "connections", "disconnected", "deleted"), 0)
        self.matched = dict.fromkeys(("modules", "positions", "params", "connections"), 0)
        self.steps: list[dict[str, Any]] = []
        self.ids: dict[str, str] = {}
        self.warnings: list[str] = []
        self.wrote = False
        self.phase = "validate"
        self.live_schema = False

    # ---- transport -------------------------------------------------------------------------------------------
    async def call(self, method: str, params: dict[str, Any], *, write: bool = False) -> dict[str, Any]:
        timeout = max(self.timeout_ms, WRITE_TIMEOUT_MS) if write else self.timeout_ms
        if write:
            self.wrote = True
        try:
            host_params = params if method in ("grid.capabilities", "grid.catalog") else {**self.target, **params}
            result = await self.client.call(method, host_params, timeout)
        except BridgeError as exc:
            raise Stop(method, {"error": exc.error}, exc.error.get("outcome", "unknown")) from exc
        return result

    def verify(self, method: str, result: dict[str, Any], key: str | None = None) -> None:
        entries = result.get(key) if key else None
        bad = result.get("status") not in (None, "observed") or result.get("batchStatus") not in (None, "applied") \
            or result.get("completion") == "unknown" or result.get("matchesRequested") is False and method == "grid.connections.set" \
            or any(e.get("status") not in CLEAN for e in entries or [])
        if bad:
            unknown = result.get("batchStatus") == "unknown" or result.get("completion") == "unknown" \
                or any(e.get("status") == "unknown" for e in entries or []) or result.get("status") == "unknown"
            raise Stop(method, result, "unknown" if unknown else "not_applied")

    # ---- reads -----------------------------------------------------------------------------------------------
    async def read_graph(self, *, params: bool = False, ids: list[str] | None = None) -> tuple[list[dict], list[dict]]:
        rows: list[dict] = []
        conns: list[dict] = []
        offset = 0
        while True:
            req: dict[str, Any] = {"moduleOffset": offset, "moduleLimit": MODULE_LIMIT, "includeParameters": params}
            if ids:
                req["moduleIds"] = ids
            result = await self.call("grid.get", req)
            rows += result.get("modules", [])
            conns += result.get("connections", [])
            window = result.get("window") or {}
            total = window.get("total", len(rows))
            offset += len(result.get("modules", []))
            if ids:
                if result.get("missingModuleIds"):
                    raise Stop("grid.get", result, "not_applied")
                break
            if not result.get("modules") or offset >= total:
                break
        if not ids and len(rows) < total:
            raise Stop("grid.get", {"error": {"code": "graph_incomplete", "message": "grid.get did not return the whole bounded graph"}}, "not_applied")
        return rows, conns

    async def read_params(self, ids: list[str]) -> dict[str, dict]:
        rows: dict[str, dict] = {}
        for i in range(0, len(ids), READ_IDS):
            got, _ = await self.read_graph(params=True, ids=ids[i:i + READ_IDS])
            rows.update({r["instanceId"]: r for r in got})
        return rows

    # ---- validation ------------------------------------------------------------------------------------------
    async def load_types(self) -> None:
        caps = await self.call("grid.capabilities", {})
        schema = bundled_schema()
        self.live_schema = caps.get("hostVersion") != schema["hostVersion"]
        if not self.live_schema:
            self.info = schema["modules"]
            self.by_name = {n: [m["typeId"]] for n, m in self.info.items()}
        else:
            self.info, self.by_name, offset = None, {}, 0
            while True:
                page = await self.call("grid.catalog", {"offset": offset, "limit": 256})
                entries = page.get("entries", [])
                for e in entries:
                    self.by_name.setdefault(e["name"], []).append(e["typeId"])
                offset += len(entries)
                if (page.get("window") or {}).get("complete", True) or not entries:
                    break
        self.by_id = {t.lower(): n for n, ids in self.by_name.items() for t in ids}

    def resolve_type(self, spec: dict[str, Any]) -> tuple[str, str]:
        if "typeId" in spec:
            name = self.by_id.get(spec["typeId"].lower())
            if name is None:
                raise SpecError([f"{spec['name']!r}: typeId {spec['typeId']} is not a catalog module"])
            return spec["typeId"].lower(), name
        wanted = spec["type"]
        exact = [n for n in self.by_name if n == wanted] or [n for n in self.by_name if n.casefold() == wanted.casefold()]
        if not exact and _norm(wanted):
            exact = [n for n in self.by_name if _norm(n) == _norm(wanted)]
        if len(exact) != 1:
            raise SpecError([f"{spec['name']!r}: unknown module type {wanted!r}.{_near(wanted, self.by_name)}"])
        ids = self.by_name[exact[0]]
        if len(ids) != 1:
            raise SpecError([f"{spec['name']!r}: type {wanted!r} is ambiguous; use typeId from {ids}"])
        return ids[0].lower(), exact[0]

    def split_endpoint(self, endpoint: str, problems: list[str]) -> tuple[str, str] | None:
        name, dot, port = endpoint.rpartition(".")
        if not dot or not name or name not in self.by_spec:
            problems.append(f"connection endpoint {endpoint!r}: expected '<declared module name>.<PORT_KEY>'.{_near(name or endpoint, self.names)}")
            return None
        return name, port

    def validate_spec(self) -> None:
        problems: list[str] = []
        self.by_spec = {m["name"]: m for m in self.modules}
        self.resolved: dict[str, dict[str, Any]] = {}
        for m in self.modules:
            try:
                type_id, type_name = self.resolve_type(m)
            except SpecError as exc:
                problems += exc.problems
                continue
            info = self.info[type_name] if self.info else None
            writes: dict[str, dict] = {}
            for pid, raw in (m.get("params") or {}).items():
                if info is None:
                    writes[pid] = {"raw": raw, "want": None}
                    continue
                pinfo = {p["parameterId"]: p for p in info["parameters"]}
                if pid not in pinfo:
                    problems.append(f"{m['name']!r} ({type_name}): unknown parameter {pid!r}.{_near(pid, pinfo)}")
                    continue
                try:
                    writes[pid] = {"raw": raw, "want": check_value(pid, raw, pinfo[pid])}
                except SpecError as exc:
                    problems += [f"{m['name']!r}: {p}" for p in exc.problems]
            self.resolved[m["name"]] = {"typeId": type_id, "typeName": type_name, "info": info, "params": writes}
        self.edges: list[tuple[str, str, str, str]] = []
        for c in self.args.get("connections", []):
            src, dst = self.split_endpoint(c["from"], problems), self.split_endpoint(c["to"], problems)
            if not src or not dst:
                continue
            for (name, port), want, label in ((src, "outputs", "output"), (dst, "inputs", "input")):
                info = self.resolved.get(name, {}).get("info")
                if info is not None and port not in {p["key"] for p in info[want]}:
                    problems.append(f"{name!r} ({self.resolved[name]['typeName']}) has no {label} {port!r}.{_near(port, [p['key'] for p in info[want]])}")
            self.edges.append((src[0], src[1], dst[0], dst[1]))
        if problems:
            raise SpecError(problems)

    # ---- planning --------------------------------------------------------------------------------------------
    def plan_params(self, name: str, row: dict | None) -> tuple[list[dict], int]:
        """Writes for one module against a live row (None: module not inserted yet, everything is a write)."""
        entry = self.resolved[name]
        writes, matched = [], 0
        live = {p["parameterId"]: p for p in (row or {}).get("parameters", []) if p.get("supported") and "parameterId" in p}
        for pid, spec in entry["params"].items():
            if row is None:
                writes.append({"module": name, "parameterId": pid, "to": spec["raw"]})
                continue
            if pid not in live:
                raise SpecError([f"{name!r}: parameter {pid!r} is not a supported live parameter.{_near(pid, live)}"])
            lp = live[pid]
            want = spec["want"] or check_value(pid, spec["raw"], lp)
            if entry["info"] is not None:
                known = next(p for p in entry["info"]["parameters"] if p["parameterId"] == pid)
                if known["kind"] != lp["kind"]:
                    raise SpecError([f"{name!r}: {pid} is {lp['kind']} live but {known['kind']} in the bundled schema"])
            if value_matches(lp["kind"], want, lp):
                matched += 1
                continue
            writes.append({"module": name, "parameterId": pid, "to": spec["raw"],
                           "write": {"moduleId": row["instanceId"], "parameterId": pid, "expectedKind": lp["kind"],
                                     "expectedValue": lp["value"], **want}})
        return writes, matched

    def plan_connections(self, rows: dict[str, dict], conns: list[dict]) -> list[dict]:
        """Edits (as host entries + readable fields) for rows keyed by module name; modules absent from rows are new."""
        incoming = {(c["targetModuleId"], c["targetPortKey"]): (c["sourceModuleId"], c["sourcePortKey"]) for c in conns}
        name_of = {r["instanceId"]: r.get("customName") or r.get("name") for r in rows.values()}
        edits, wanted = [], set()
        for src, sport, dst, dport in self.edges:
            drow, srow = rows.get(dst), rows.get(src)
            if drow is not None:
                wanted.add((drow["instanceId"], dport))
            current = incoming.get((drow["instanceId"], dport)) if drow else None
            desired = (srow["instanceId"], sport) if srow else None
            if drow and srow and current == desired:
                self.matched["connections"] += 1
                continue
            edit = {"from": f"{src}.{sport}", "to": f"{dst}.{dport}",
                    "previous": f"{name_of.get(current[0], current[0])}.{current[1]}" if current else None}
            if drow and srow:
                edit["write"] = {"targetModuleId": drow["instanceId"], "targetPortKey": dport,
                                 "expectedSource": {"moduleId": current[0], "portKey": current[1]} if current else None,
                                 "source": {"moduleId": srow["instanceId"], "portKey": sport}}
            edits.append(edit)
        if self.prune:
            for name, row in rows.items():
                for port in row.get("inputs", []):
                    key = (row["instanceId"], port["key"])
                    if key in incoming and key not in wanted:
                        cur = incoming[key]
                        edits.append({"from": None, "to": f"{name}.{port['key']}",
                                      "previous": f"{name_of.get(cur[0], cur[0])}.{cur[1]}",
                                      "write": {"targetModuleId": row["instanceId"], "targetPortKey": port["key"],
                                                "expectedSource": {"moduleId": cur[0], "portKey": cur[1]}, "source": None}})
        return edits

    def live_checks(self, rows: dict[str, dict]) -> None:
        """Port validation against live rows, needed when no bundled schema covers the host version."""
        problems = []
        for src, sport, dst, dport in self.edges:
            for name, port, want in ((src, sport, "outputs"), (dst, dport, "inputs")):
                if name in rows and port not in {p["key"] for p in rows[name].get(want, [])}:
                    problems.append(f"{name!r} has no {want[:-1]} {port!r}.{_near(port, [p['key'] for p in rows[name].get(want, [])])}")
        if problems:
            raise SpecError(problems)

    # ---- phases ----------------------------------------------------------------------------------------------
    async def run(self) -> dict[str, Any]:
        await self.load_types()
        self.validate_spec()
        self.phase = "read"
        rows, conns = await self.read_graph()
        by_custom: dict[str, list[dict]] = {}
        for r in rows:
            if r.get("customName"):
                by_custom.setdefault(r["customName"], []).append(r)
        problems, existing = [], {}
        for name in self.names:
            found = by_custom.get(name, [])
            if len(found) > 1:
                problems.append(f"{len(found)} modules in the device are already named {name!r}; names must be unique")
            elif found:
                if found[0].get("typeId", "").lower() != self.resolved[name]["typeId"]:
                    problems.append(f"{name!r} exists as {found[0].get('typeName') or found[0].get('typeId')} but the spec wants {self.resolved[name]['typeName']}")
                else:
                    existing[name] = found[0]
        deletes = []
        if self.prune and self.namespace:
            deletes = [r for r in rows if r.get("customName", "").startswith(self.namespace) and r["customName"] not in self.by_spec]
        inserts = [n for n in self.names if n not in existing]
        if len(rows) - len(deletes) + len(inserts) > MODULE_LIMIT:
            problems.append(f"the device would hold {len(rows) - len(deletes) + len(inserts)} modules; the limit is {MODULE_LIMIT}")
        if problems:
            raise SpecError(problems)
        self.ids = {n: r["instanceId"] for n, r in existing.items()}
        self.matched["modules"] = len(existing)
        if self.live_schema:
            self.live_checks(existing)
        pending = {"deleted": len(deletes), "inserted": len(inserts)}
        if self.dry:
            return await self.dry_run(existing, conns, deletes, inserts)
        try:
            await self.execute(deletes, inserts, pending)
        except Stop as stop:
            return self.report_stop(stop, pending)
        return self.report("applied" if self.wrote else "unchanged")

    async def dry_run(self, existing, conns, deletes, inserts) -> dict[str, Any]:
        param_rows = await self.read_params([r["instanceId"] for n, r in existing.items() if self.resolved[n]["params"]])
        param_plan, planned_params = [], 0
        for name in self.names:
            row = param_rows.get(existing[name]["instanceId"]) if name in existing else None
            writes, matched = self.plan_params(name, row)
            self.matched["params"] += matched
            param_plan += [{k: v for k, v in w.items() if k != "write"} for w in writes]
            planned_params += len(writes)
        moves = []
        for name, row in existing.items():
            spec = self.by_spec[name]
            if _pos(row) != (spec["x"], spec["y"]):
                moves.append({"name": name, "from": list(_pos(row)), "to": [spec["x"], spec["y"]]})
            else:
                self.matched["positions"] += 1
        edits = self.plan_connections(existing, conns)
        plan = {"deletes": [r["customName"] for r in deletes],
                "inserts": [{"name": n, "type": self.resolved[n]["typeName"], "x": self.by_spec[n]["x"], "y": self.by_spec[n]["y"]} for n in inserts],
                "renames": list(inserts), "moves": moves, "params": param_plan,
                "connections": [{k: v for k, v in e.items() if k != "write"} for e in edits]}
        planned = {"inserted": len(inserts), "renamed": len(inserts), "moved": len(moves), "paramsWritten": planned_params,
                   "connections": sum(1 for e in edits if e["from"]), "disconnected": sum(1 for e in edits if not e["from"]), "deleted": len(deletes)}
        notes = ["moves of inserted modules depend on native reflow and are decided after insertion"] if inserts else []
        if self.live_schema:
            notes.append("no bundled schema for this host version: ports/parameters of inserted modules are validated only after insertion")
        result = {"status": "dryRun", "planned": planned, "alreadyMatched": self.matched, "plan": plan, "steps": []}
        return result | ({"notes": notes} if notes else {})

    async def execute(self, deletes, inserts, pending) -> None:
        self.phase = "delete"
        for row in deletes:
            result = await self.call("grid.modules.delete", {"moduleId": row["instanceId"]}, write=True)
            self.verify("grid.modules.delete", result)
            self.applied["deleted"] += 1
            pending["deleted"] -= 1
            self.steps.append({"op": "delete", "name": row["customName"]})
        self.phase = "insert"
        added_rows: dict[str, dict] = {}
        for i in range(0, len(inserts), INSERT_BATCH):
            chunk = inserts[i:i + INSERT_BATCH]
            result = await self.call("grid.modules.insert", {"modules": [
                {"typeId": self.resolved[n]["typeId"], "x": self.by_spec[n]["x"], "y": self.by_spec[n]["y"]} for n in chunk]}, write=True)
            entries = result.get("modules", [])
            for n, e in zip(chunk, entries):
                if e.get("status") == "applied" and e.get("moduleId"):
                    self.ids[n] = e["moduleId"]
                    self.applied["inserted"] += 1
                    pending["inserted"] -= 1
            added_rows.update({r["instanceId"]: r for r in result.get("modulesAdded", [])})
            self.steps.append({"op": "insert", "count": len(chunk), "status": result.get("batchStatus")})
            self.verify("grid.modules.insert", result, "modules")
            if len(entries) != len(chunk):
                raise Stop("grid.modules.insert", result, "unknown")
        self.phase = "rename"
        for n in inserts:
            result = await self.call("grid.modules.rename", {
                "moduleId": self.ids[n], "expectedCustomName": added_rows.get(self.ids[n], {}).get("customName", ""), "customName": n}, write=True)
            self.verify("grid.modules.rename", result)
            self.applied["renamed"] += 1
            self.steps.append({"op": "rename", "name": n})
        self.phase = "move"
        rows, _ = await self.read_graph()
        pos = {r["instanceId"]: _pos(r) for r in rows}
        for n in self.names:
            want = (self.by_spec[n]["x"], self.by_spec[n]["y"])
            if self.ids[n] not in pos:
                raise Stop("grid.get", {"error": {"code": "stale_context", "message": f"module {n!r} is missing after insertion"}}, "not_applied")
            if pos[self.ids[n]] == want:
                self.matched["positions"] += 1
                continue
            result = await self.call("grid.modules.move", {"moduleId": self.ids[n], "expectedX": pos[self.ids[n]][0],
                                                          "expectedY": pos[self.ids[n]][1], "x": want[0], "y": want[1]}, write=True)
            self.verify("grid.modules.move", result)
            self.applied["moved"] += 1
            for m in result.get("modulesMoved", []):
                pos[m["moduleId"]] = (m["to"]["x"], m["to"]["y"])
            if "actualPosition" in result:
                pos[self.ids[n]] = (result["actualPosition"]["x"], result["actualPosition"]["y"])
            self.steps.append({"op": "move", "name": n, "to": list(pos[self.ids[n]])})
        self.phase = "params"
        with_params = [n for n in self.names if self.resolved[n]["params"]]
        param_rows = await self.read_params([self.ids[n] for n in with_params])
        writes: list[dict] = []
        try:
            for n in with_params:
                got, matched = self.plan_params(n, param_rows[self.ids[n]])
                self.matched["params"] += matched
                writes += [w["write"] for w in got]
        except SpecError as exc:
            raise Stop("grid.get", {"error": {"code": "invalid_spec", "message": "; ".join(exc.problems)}}, "not_applied") from exc
        for i in range(0, len(writes), PARAM_BATCH):
            chunk = writes[i:i + PARAM_BATCH]
            result = await self.call("grid.parameters.set", {"writes": chunk}, write=True)
            entries = result.get("writes", [])
            self.applied["paramsWritten"] += sum(1 for e in entries if e.get("status") == "applied")
            self.matched["params"] += sum(1 for e in entries if e.get("status") == "already_matched")
            self.warnings += [f"{e['parameterId']}: displayMismatch {e['displayMismatch']}" for e in entries if e.get("displayMismatch")]
            self.steps.append({"op": "params", "count": len(chunk), "status": result.get("batchStatus")})
            self.verify("grid.parameters.set", result, "writes")
        self.phase = "connections"
        rows, conns = await self.read_graph()
        by_id = {r["instanceId"]: r for r in rows}
        by_name = {n: by_id[self.ids[n]] for n in self.names if self.ids[n] in by_id}
        if len(by_name) != len(self.names):
            raise Stop("grid.get", {"error": {"code": "stale_context", "message": "a declared module vanished before wiring"}}, "not_applied")
        try:
            if self.live_schema:
                self.live_checks(by_name)
        except SpecError as exc:
            raise Stop("grid.get", {"error": {"code": "invalid_spec", "message": "; ".join(exc.problems)}}, "not_applied") from exc
        edits = self.plan_connections(by_name, conns)
        for n in self.names:
            want = (self.by_spec[n]["x"], self.by_spec[n]["y"])
            if _pos(by_name[n]) != want:
                self.warnings.append(f"{n}: at {list(_pos(by_name[n]))}, wanted {list(want)} (native reflow or collision)")
        for i in range(0, len(edits), CONNECTION_BATCH):
            chunk = edits[i:i + CONNECTION_BATCH]
            result = await self.call("grid.connections.set", {"connections": [e["write"] for e in chunk]}, write=True)
            entries = result.get("connections", [])
            done = [e for e, r in zip(chunk, entries) if r.get("status") == "applied"]
            self.applied["connections"] += sum(1 for e in done if e["from"])
            self.applied["disconnected"] += sum(1 for e in done if not e["from"])
            self.matched["connections"] += sum(1 for e in entries if e.get("status") == "already_matched")
            self.steps.append({"op": "connections", "count": len(chunk), "status": result.get("batchStatus")})
            self.verify("grid.connections.set", result, "connections")

    # ---- reports ---------------------------------------------------------------------------------------------
    def report(self, status: str) -> dict[str, Any]:
        out = {"status": status, "applied": self.applied, "alreadyMatched": self.matched, "steps": self.steps, "moduleIds": self.ids}
        return out | ({"warnings": self.warnings} if self.warnings else {})

    def report_stop(self, stop: Stop, pending: dict[str, int]) -> dict[str, Any]:
        order = ["delete", "insert", "rename", "move", "params", "connections"]
        status = "unknown" if stop.outcome == "unknown" else ("partial" if any(self.applied.values()) else "failed")
        out = self.report(status)
        out["failure"] = {"phase": self.phase, "method": stop.method, "response": stop.raw}
        out["notDispatched"] = {"phasesNotStarted": order[order.index(self.phase) + 1:] if self.phase in order else order,
                                "failedPhaseIncomplete": self.phase, **{k: v for k, v in pending.items() if v}}
        out["next"] = ("Outcome unknown: inspect with grid.get before any further write." if status == "unknown"
                       else "Fix the cause and re-apply the same spec; applied work is reused.")
        return out


def invalid_spec(problems: list[str]) -> BridgeError:
    return BridgeError({"code": "invalid_spec", "message": f"{len(problems)} problem(s); nothing was written: " + " | ".join(problems[:20]),
                        "outcome": "not_dispatched", "details": {"problems": problems}})


async def apply(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    """Run a validated grid.apply spec (dict from schema.GridApplyArgs) through `client.call(method, params, timeout_ms)`."""
    run = Apply(client, args, timeout_ms)
    try:
        return await run.run()
    except SpecError as exc:
        raise invalid_spec(exc.problems) from exc
    except Stop as stop:
        if run.wrote:
            return run.report_stop(stop, {})
        error = stop.raw.get("error")
        if error is not None:
            raise BridgeError(error) from stop
        raise BridgeError({"code": "not_applied", "message": f"{stop.method} returned an unusable result; nothing was written",
                           "outcome": "not_dispatched", "details": stop.raw}) from stop
