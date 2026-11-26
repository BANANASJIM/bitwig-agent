"""Shared test fakes: Grid hosts, PipeWire, recorder processes and bridge clients."""

import asyncio
import copy
import math
import struct
import uuid
import wave
from pathlib import Path
from typing import Any, ClassVar

from bitwig_agent import grid_apply
from bitwig_agent.catalog import validate_call
from bitwig_agent.errors import BridgeError

WRITES = {
    "grid.modules.insert",
    "grid.modules.rename",
    "grid.modules.move",
    "grid.modules.delete",
    "grid.parameters.set",
    "grid.connections.set",
    "grid.voicing.set",
}


def seconds_text(type_name, raw):
    """Host-style display of a seconds parameter (3 significant digits) from the natively measured mappings."""
    seconds = raw**3 if type_name in ("AD", "ADSR") else 10**raw
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
        self.voicing: dict[str, Any] | None = None  # Poly Grid voicing; None = Note/FX Grid
        self.timeouts: list[tuple[str, int]] = []
        self.transport: dict[str, Any] = {"playing": False}
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
        self.mods[iid] = {
            "instanceId": iid,
            "customName": custom,
            "name": custom or type_name,
            "typeId": info["typeId"],
            "typeName": type_name,
            "x": x,
            "y": y,
            "info": info,
            "params": params,
        }
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
        row = {
            "instanceId": m["instanceId"],
            "customName": m["customName"],
            "name": m["name"],
            "typeId": m["typeId"],
            "typeName": m["typeName"],
            "x": m["x"],
            "y": m["y"],
            "width": m["info"]["width"],
            "height": m["info"]["height"],
            "inputs": [
                {"key": i["key"], "name": i["name"], "connected": i["key"] in connected} for i in m["info"]["inputs"]
            ],
            "outputs": [{"key": o["key"], "name": o["name"]} for o in m["info"]["outputs"]],
        }
        if params:
            row["parameters"] = [
                {
                    "supported": True,
                    "kind": p["kind"],
                    "parameterId": pid,
                    "value": p["value"],
                    "display": p["display"],
                    **{k: p[k] for k in ("minimum", "maximum", "options", "numericUnit") if k in p},
                }
                for pid, p in m["params"].items()
                if p["kind"] != "steps"
            ]
            for pid, p in m["params"].items():
                if p["kind"] == "steps":
                    count = m["params"]["STEPS"]["value"]
                    row["parameters"].append(
                        {
                            "supported": True,
                            "kind": "steps",
                            "parameterId": pid,
                            "arrayLength": 64,
                            "stepCount": count,
                            "values": list(p["value"] if full else p["value"][:count]),
                        }
                    )
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
        return {
            "status": "observed",
            "entries": entries[p["offset"] : p["offset"] + p["limit"]],
            "window": {"complete": p["offset"] + p["limit"] >= len(entries)},
        }

    def h_get(self, p):
        mods = [m for m in self.mods.values() if m["instanceId"] in p.get("moduleIds", self.mods)]
        page = mods[p["moduleOffset"] : p["moduleOffset"] + p["moduleLimit"]]
        conns = [
            {"targetModuleId": t, "targetPortKey": tp, "sourceModuleId": s, "sourcePortKey": sp}
            for (t, tp), (s, sp) in self.conns.items()
        ]
        return {
            "status": "observed",
            "modules": [self.row(m, p["includeParameters"], p.get("full")) for m in page],
            "connections": conns,
            "window": {"total": len(mods), "complete": True},
        }

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
            entries.append(
                {
                    "typeId": spec["typeId"],
                    "status": "applied",
                    "moduleId": iid,
                    "actualPosition": {"x": spec["x"] + self.shift, "y": spec["y"]},
                }
            )
            added.append(self.row(self.mods[iid], False))
        batch = "applied" if cap >= len(p["modules"]) else "partial"
        return {
            "status": "observed",
            "batchStatus": batch,
            "completion": "complete",
            "modules": entries,
            "modulesAdded": added,
        }

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
            if (
                o is not m
                and o["x"] <= p["x"] < o["x"] + o["info"]["width"]
                and o["y"] <= p["y"] < o["y"] + o["info"]["height"]
            ):
                raise BridgeError({"code": "invalid_params", "message": "occupied anchor", "outcome": "not_applied"})
        m["x"], m["y"] = p["x"], p["y"]
        return {
            "status": "observed",
            "operation": "move",
            "actualPosition": {"x": p["x"], "y": p["y"]},
            "matchesRequested": True,
        }

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
                par["value"][: len(w["values"])] = [grid_apply.f32(x) for x in w["values"]]
                entries.append({"moduleId": w["moduleId"], "parameterId": w["parameterId"], "status": "applied"})
                continue
            if (par["kind"], par["value"]) != (w["expectedKind"], w["expectedValue"]):
                raise BridgeError({"code": "stale_context", "message": "stale", "outcome": "not_applied"})
            if "display" in w:
                par["value"], par["display"] = 99.0, w["display"]
            elif par.get("numericUnit") == "seconds":
                par["value"], par["display"] = (
                    w["value"],
                    seconds_text(self.mods[w["moduleId"]]["typeName"], w["value"]),
                )
            else:
                par["value"], par["display"] = w["value"], str(w["value"])
            entries.append({"moduleId": w["moduleId"], "parameterId": w["parameterId"], "status": "applied"})
        return {"status": "observed", "batchStatus": "applied", "completion": "complete", "writes": entries}

    def h_transport_get(self, p):
        return {"status": "observed", **self.transport}

    def h_voicing_get(self, p):
        if self.voicing is None:
            return {
                "status": "observed",
                "supported": False,
                "reason": "no_voicing_model: only Poly Grid has device voicing",
            }
        return {
            "status": "observed",
            "supported": True,
            "voicing": dict(self.voicing),
            "mode": "poly" if self.voicing["voices"] > 1 else self.voicing["monoMode"],
        }

    def h_voicing_set(self, p):
        assert self.voicing is not None
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


TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET: dict[str, Any] = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}


SPEC: dict[str, Any] = {
    "target": TARGET,
    "namespace": "H: ",
    "modules": [
        {"name": "H: mul", "type": "Multiply", "x": 0, "y": 0},
        {"name": "H: add", "type": "Add", "x": 3, "y": 0},
        {"name": "H: env", "type": "ADSR", "x": 6, "y": 0, "params": {"ATTACK": 0.5, "GATE": False}},
        {"name": "H: acc", "type": "Accents", "x": 0, "y": 4, "params": {"STEPS": 4, "MODE": {"label": "Trigger"}}},
        {"name": "H: lp", "type": "Low-pass", "x": 3, "y": 4, "params": {"CUTOFF": {"display": "1.2 kHz"}}},
    ],
    "connections": [
        {"from": "H: mul.OUT", "to": "H: add.IN"},
        {"from": "H: add.OUT", "to": "H: env.IN"},
        {"from": "H: acc.OUT", "to": "H: lp.IN"},
    ],
}


def apply_grid(host, spec, **extra):
    args, timeout = validate_call("grid.apply", {**copy.deepcopy(spec), **extra})
    return asyncio.run(grid_apply.apply(host, args, timeout))


class LayoutGrid:
    def __init__(self, modules, connections, cap=64):
        self.modules = copy.deepcopy(modules)
        self.connections = copy.deepcopy(connections)
        self.cap = cap  # the real host returns fewer rows than asked
        self.calls = []

    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, copy.deepcopy(params)))
        assert method == "grid.get"
        rows = [
            m
            for m in self.modules
            if m["instanceId"] in params.get("moduleIds", [m["instanceId"] for m in self.modules])
        ]
        page = rows[params["moduleOffset"] : params["moduleOffset"] + min(params["moduleLimit"], self.cap)]
        page = [m if params["includeParameters"] else {k: v for k, v in m.items() if k != "parameters"} for m in page]
        return {
            "status": "observed",
            "modules": page,
            "connections": self.connections,
            "window": {
                "offset": params["moduleOffset"],
                "total": len(rows),
                "complete": params["moduleOffset"] + len(page) >= len(rows),
            },
        }


RECIPE_TRACK = "11111111-1111-1111-1111-111111111111"
RECIPE_TARGET = {"trackId": RECIPE_TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}


class RecipeGrid:
    """Just enough native behavior for the diff/batch protocol; counts every call."""

    TYPES: ClassVar[dict[str, str]] = {"Union": "type-union", "ADSR": "type-adsr", "Audio Out": "type-out"}

    def __init__(self):
        self.calls = []
        self.modules: dict[str, Any] = {}
        self.cables: dict[tuple[str, str], Any] = {}  # (targetModuleId, targetPortKey) -> source endpoint
        for number in range(2):
            self.add(f"default-{number}", "type-default", [], [])
        self.cables[("default-1", "IN")] = {"moduleId": "default-0", "portKey": "OUT"}
        self.modules["default-0"]["outputs"].append({"key": "OUT", "name": "Out"})
        self.modules["default-1"]["inputs"].append({"key": "IN", "name": "In", "connected": True})

    def add(self, module_id, type_id, inputs, outputs, parameters=()):
        self.modules[module_id] = {
            "instanceId": module_id,
            "typeId": type_id,
            "name": type_id,
            "customName": "",
            "x": 0,
            "y": 0,
            "inputs": inputs,
            "outputs": outputs,
            "parameters": list(parameters),
        }

    def rows(self, ids=None, parameters=True):
        rows = []
        for module_id, module in self.modules.items():
            if ids is not None and module_id not in ids:
                continue
            row = {key: value for key, value in module.items() if key != "parameters"}
            row["inputs"] = [
                {**port, "connected": (module_id, port["key"]) in self.cables} for port in module["inputs"]
            ]
            row["parameters"] = [dict(item) for item in module["parameters"]] if parameters else []
            rows.append(row)
        return rows

    def connections(self):
        return [
            {
                "sourceModuleId": source["moduleId"],
                "sourcePortKey": source["portKey"],
                "targetModuleId": target[0],
                "targetPortKey": target[1],
            }
            for target, source in self.cables.items()
        ]

    def parameter(self, module_id, parameter_id):
        return next(item for item in self.modules[module_id]["parameters"] if item["parameterId"] == parameter_id)

    async def __call__(self, method, params):
        self.calls.append((method, params))
        if method == "grid.get":
            ids = set(params["moduleIds"]) if "moduleIds" in params else None
            rows = self.rows(ids, params.get("includeParameters", True))
            return {
                "status": "observed",
                "target": {**RECIPE_TARGET, "nativeOwningTrack": "corroborated_before_and_after_capture"},
                "window": {"offset": 0, "total": len(rows), "complete": True},
                "modules": rows,
                "connections": [c for c in self.connections() if ids is None or c["targetModuleId"] in ids],
            }
        if method == "grid.modules.delete":
            module_id = params["moduleId"]
            removed = self.modules.pop(module_id)
            self.cables = {
                key: value
                for key, value in self.cables.items()
                if key[0] != module_id and value["moduleId"] != module_id
            }
            return {
                "status": "observed",
                "modulesRemoved": [{"moduleId": module_id, "typeId": removed["typeId"]}],
                "moduleCount": len(self.modules),
            }
        if method == "grid.modules.insert":
            module_id = f"module-{len(self.modules)}-{params['typeId']}"
            name = next(key for key, value in self.TYPES.items() if value == params["typeId"])
            ports = {
                "Union": ([], [{"key": "OUT", "name": "Signal Out"}]),
                "ADSR": ([{"key": "IN", "name": "Signal In"}], [{"key": "OUT", "name": "Signal Out"}]),
                "Audio Out": ([{"key": "IN", "name": "Audio In"}], []),
            }[name]

            def floats(*names):
                return [
                    {
                        "supported": True,
                        "parameterId": f"{name}.{item}",
                        "name": item,
                        "kind": "float",
                        "value": 0.5,
                        "minimum": 0.0,
                        "maximum": 2.0,
                    }
                    for item in names
                ]

            def booleans(*names):
                return [
                    {
                        "supported": True,
                        "parameterId": f"{name}.{item}",
                        "name": item,
                        "kind": "boolean",
                        "value": False,
                    }
                    for item in names
                ]

            parameters = {
                "Union": booleans("Key Tracking")
                + floats("Pitch Offset", "Saw Level", "Triangle Level", "Pulse Level"),
                "ADSR": booleans("Gate on Notes", "Affect Voice Lifetime")
                + floats("Attack", "Decay", "Sustain", "Release")
                + [
                    {
                        "supported": True,
                        "parameterId": "ADSR.Model",
                        "name": "Model",
                        "kind": "enum",
                        "value": 0,
                        "options": [{"value": 0, "label": "Digital"}, {"value": 3, "label": "Analog"}],
                    }
                ],
                "Audio Out": [],
            }[name]
            self.add(module_id, params["typeId"], *ports, parameters=parameters)
            row = self.rows({module_id}, parameters=False)[0]
            row.pop("parameters")
            return {
                "status": "observed",
                "moduleId": module_id,
                "modulesAdded": [row],
                "moduleCount": len(self.modules),
            }
        if method == "grid.parameters.set":
            entries = params["writes"]
            for entry in entries:
                current = self.parameter(entry["moduleId"], entry["parameterId"])
                if current["kind"] != entry["expectedKind"] or current["value"] != entry["expectedValue"]:
                    raise BridgeError({"code": "stale_context", "outcome": "not_applied", "message": "stale"})
            results = []
            for entry in entries:
                current = self.parameter(entry["moduleId"], entry["parameterId"])
                changed = current["value"] != entry["value"]
                results.append(
                    {
                        "moduleId": entry["moduleId"],
                        "parameterId": entry["parameterId"],
                        "status": "applied" if changed else "already_matched",
                        "previousValue": current["value"],
                        "value": entry["value"],
                    }
                )
                current["value"] = entry["value"]
            return {
                "status": "observed",
                "batchStatus": "applied",
                "completion": "observed",
                "written": len(results),
                "writes": results,
            }
        if method == "grid.connections.set":
            edits = params["connections"]
            assert all(
                edit["expectedSource"] == self.cables.get((edit["targetModuleId"], edit["targetPortKey"]))
                for edit in edits
            )
            for edit in edits:
                self.cables[edit["targetModuleId"], edit["targetPortKey"]] = edit["source"]
            return {
                "status": "observed",
                "batchStatus": "applied",
                "completion": "observed",
                "written": len(edits),
                "connections": [
                    {
                        "targetModuleId": edit["targetModuleId"],
                        "targetPortKey": edit["targetPortKey"],
                        "status": "applied",
                        "matchesRequested": True,
                    }
                    for edit in edits
                ],
                "connectionsChanged": [
                    {
                        "target": {"moduleId": edit["targetModuleId"], "portKey": edit["targetPortKey"]},
                        "from": None,
                        "to": edit["source"],
                    }
                    for edit in edits
                ],
                "moduleCount": len(self.modules),
            }
        raise AssertionError(method)


RATE = 48000


def write_wav(path, samples, rate=RATE, header_zero=False):
    frames = b"".join(struct.pack("<hh", int(s * 32767), int(s * 32767)) for s in samples)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)
    if header_zero:  # streamed header as parec leaves it
        raw = bytearray(path.read_bytes())
        raw[40:44] = b"\0\0\0\0"
        path.write_bytes(bytes(raw))


def notes(midis, step=0.125, length=0.1, total=None):
    """Sine notes with fast attack/decay at each step."""
    n = int((total or step * len(midis) + 0.3) * RATE)
    out = [0.0] * n
    for i, m in enumerate(midis):
        hz = 440 * 2 ** ((m - 69) / 12)
        start = int((0.1 + i * step) * RATE)
        for k in range(int(length * RATE)):
            env = min(1, k / 48) * (1 - k / (length * RATE))
            if start + k < n:
                out[start + k] = 0.8 * env * math.sin(2 * math.pi * hz * k / RATE)
    return out


def overlapping(midis, step=0.1, decay=0.26, total=None):
    """Plucks whose exponential tail (about 260 ms) runs under the next several notes, summed."""
    n = int((total or 0.1 + step * len(midis) + 0.6) * RATE)
    out = [0.0] * n
    for i, m in enumerate(midis):
        hz = 440 * 2 ** ((m - 69) / 12)
        start = int((0.1 + i * step) * RATE)
        for k in range(min(int(1.0 * RATE), n - start)):
            t = k / RATE
            out[start + k] += (
                0.25
                * min(1, k / 48)
                * math.exp(-t / (decay / 4))
                * (math.sin(2 * math.pi * hz * t) + 0.4 * math.sin(4 * math.pi * hz * t))
            )
    return out


class FakePW:
    """Mimics pw-link/pactl output and state; records every call."""

    def __init__(self, links, sink=False):
        self.links = set(links)
        self.sink = sink
        self.calls = []

    def run(self, args, timeout=5.0):  # noqa: PLR0911
        self.calls.append(args)
        if args[:2] == ["pw-link", "-l"]:
            text = "alsa_output.pci:playback_FL\n  |<- Bitwig Studio:out1\nalsa_output.pci:playback_FR\n  |<- Bitwig Studio:out2\n"
            ports: dict[str, list[str]] = {}
            for a, b in sorted(self.links):
                ports.setdefault(a, []).append(b)
            for a, targets in ports.items():
                text += a + "\n" + "".join(f"  |-> {t}\n" for t in targets)
            return 0, text, ""
        if args[:2] == ["pw-link", "-o"]:
            return 0, "Bitwig Studio:out1\nBitwig Studio:out2\nFirefox:output_FL\nFirefox:output_FR\n", ""
        if args[:2] == ["pw-link", "-i"]:
            return (
                0,
                "".join(f"{n}:input_{c}\n" for n in NODES for c in ("FL", "FR")) + "alsa_output.pci:playback_FL\n",
                "",
            )
        if args[:2] == ["pw-link", "-d"]:
            self.links.discard((args[2], args[3]))
            return 0, "", ""
        if args[0] == "pw-link":
            if (args[1], args[2]) in self.links:
                return 1, "", "failed to link ports: File exists"
            self.links.add((args[1], args[2]))
            return 0, "", ""
        if args[:3] == ["pactl", "list", "short"]:
            return 0, ("7\tbwtest\tmodule\tfmt\tRUNNING\n" if self.sink else "1\tother\tm\tf\tIDLE\n"), ""
        if args[:2] == ["pactl", "load-module"]:
            self.sink = True
            return 0, "42\n", ""
        raise AssertionError(args)


NODES: list[str] = []  # capture nodes the fake pw-record has "created"
SINK_LINKS = {("Bitwig Studio:out1", "bwtest:playback_FL"), ("Bitwig Studio:out2", "bwtest:playback_FR")}


class FakeProc:
    def __init__(self, path):
        self.path, self.returncode, self.stderr, self.signals = path, None, None, []

    def poll(self):
        return self.returncode

    def send_signal(self, sig):
        self.signals.append(sig)

        write_wav(Path(self.path), notes([60, 62], total=0.6))
        self.returncode = 0

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.returncode = -9


class FakeBridge:
    def __init__(self, playing=(), engine=True, solo=None):
        self.calls = []
        self.playing = playing
        self.engine = engine
        self.solo = dict(solo or {})
        self.fail_set = None

    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, params))
        if method == "status":
            return {"engineActive": self.engine}
        if method == "context.get":
            return {
                "tracks": [
                    {"trackId": tid, "name": name, "launcherSlots": [{"slotIndex": 0, "playing": True}]}
                    for tid, name in self.playing
                ]
                + [{"trackId": "idle", "name": "Idle", "launcherSlots": [{"slotIndex": 0, "playing": False}]}]
            }
        if method == "tracks.list":
            return {"tracks": [{"trackId": t, "index": i, "name": t} for i, t in enumerate(self.solo)]}
        if method == "tracks.get":
            return {"trackId": params["trackId"], "solo": self.solo[params["trackId"]]}
        if method == "tracks.set":
            if params["trackId"] == self.fail_set and params["solo"] is False:
                raise BridgeError({"code": "host_error", "message": "boom", "outcome": "unknown"})
            self.solo[params["trackId"]] = params["solo"]
            return {}
        return {}
