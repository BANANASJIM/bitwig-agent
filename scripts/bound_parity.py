"""Python/Java contract parity: send schema-derived bound vectors straight to the Java validator.

Imported by smoke-mcp-java.py and run against its fixture RpcServer. For every
method, walk the pydantic JSON schema; each numeric/length/item bound is sent at
the bound (accept) and just beyond it (reject), and every optional field the
catalog examples never include is sent once. The raw TCP request bypasses the
Python adapter, so the JVM's own validator decides; its verdict must equal
pydantic's. No codegen: vectors are derived from the schema at run time.
"""
import asyncio
import copy
import json
import math
import re

from pydantic import ValidationError

from bitwig_agent.catalog import CLIENT_SIDE, TOOLS

UUID_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
UUID = re.compile(UUID_PATTERN)
SAMPLE_UUID = "12345678-1234-1234-1234-123456789abc"

# (method, field path, vector kind) -> reason. Kind "accept"/"reject"/"optional"
# skips that vector kind for the field; "all" skips every vector for it. Use only
# for cross-field rules the two sides legitimately express differently.
SKIPS: dict[tuple[str, str, str], str] = {
    ("devices.insert", "id", "accept"): "per-kind id caps (clap 256, vst3 32) make the shared 4096 unreachable; rejects still run",
    ("devices.layers.insert", "id", "accept"): "per-kind id caps (clap 256, vst3 32) make the shared 4096 unreachable; rejects still run",
    ("launcher.create", "notes.0.beat", "accept"): "a note must fit lengthBeats (max 4096), so beat 4194304 is unreachable here",
    ("notes.edit", "edits.0.beat", "accept"): "no explicit window: the host derives a grid of <=128 steps (step<=4 beats, startStep<=1048576) and rejects extremes the schema allows; error names the fix",
    ("launcher.create", "notes.0.durationBeats", "accept"): "same derived-grid limit (a 4096-beat note needs >128 steps)",
    ("arranger.clips.create", "notes.0.beat", "accept"): "a note must fit lengthBeats (max 65536), so beat 4194304 is unreachable here",
    ("notes.edit", "edits.0.durationBeats", "accept"): "same derived-grid limit as edits.0.beat (duration 4096 beats needs >128 steps)",
    **{(m, f, "accept"): "endBeats must exceed startBeats by at most 65536, so a bound on one side is reachable only with a matching partner; rejects still run"
       for m in ("export.audio", "bounce.inPlace") for f in ("startBeats", "endBeats")},
}


def as_path(value):
    """Make a vector string an absolute path of the same UTF-16 length."""
    if not value:
        return value
    return "/a" + value[1:] if ord(value[0]) > 0xFFFF else "/" + value[1:]


def file_insert(params, value):
    params["kind"] = "file"
    params.pop("id", None)
    params["path"] = as_path(value)


def project_path(params, value):
    """A final <dir>/S/S.bwproject path of the vector's UTF-16 length (same astral-character mix)."""
    tail = "/S/S.bwproject"
    body = len(value.encode("utf-16-le", errors="surrogatepass")) // 2 - len(tail) - 1
    if body > 0:
        filler = "\U0001F600" * (body // 2) + "a" * (body % 2) if any(ord(c) > 0xFFFF for c in value) else "a" * body
        params["path"] = "/" + filler + tail


def window_fix(field):
    """Keep the explicit note window self-consistent while one window field sweeps."""
    def fix(params, value):
        if field == "pitchStart" and 0 <= value < 128:
            params["pitchCount"] = max(1, min(params["pitchCount"], 128 - value))
        if "edits" in params and "stepSizeBeats" in params:
            params["edits"] = [{"op": "clear", "beat": params["startStep"] * params["stepSizeBeats"], "pitch": params["pitchStart"]}]
    return fix


# (method, field path) -> mutate params after the vector value was set, so the
# vector is valid except for the bound under test.
def drop(*keys):
    """Exactly-one-of alternatives: drop the sibling the example already carries."""
    def fix(params, value, keys=keys):
        for key in keys:
            container, _, leaf = key.rpartition(".")
            target = params
            for part in filter(None, container.split(".")):
                target = target[int(part)] if part.isdigit() else target.get(part, {})
            if isinstance(target, dict):
                target.pop(leaf, None)
    return fix


def steps_fix(field):
    """Keep values no longer than expectedValues while one of the two arrays sweeps its length."""
    def fix(params, value):
        for box in (params, *(params.get("writes") or [])):
            if "expectedValues" in box and "values" in box:
                if field == "values":
                    box["expectedValues"] = [0.0] * max(len(box["expectedValues"]), len(box["values"]))
                else:
                    box["values"] = box["values"][:len(box["expectedValues"])] or [0.0]
    return fix


def automation_fix(field):
    """Keep range ordered and points increasing and inside it while one of them sweeps."""
    def fix(params, value):
        box = params.get("range")
        if box and isinstance(value, (int, float)) and 0 <= value <= 4194304:
            if field == "startBeat":
                box["endBeat"] = max(box["endBeat"], value)
            elif field == "endBeat":
                box["startBeat"] = min(box["startBeat"], value)
        if "points" not in params:
            return
        low = box["startBeat"] if box else 0.0
        if field == "points":
            span = (box["endBeat"] - low) if box else float(value)
            params["points"] = [{"beat": low + span * i / max(value, 1), "value": 0.5, "interpolation": "linear"} for i in range(value)]
        elif box:
            params["points"] = [p for p in params["points"] if box["startBeat"] <= p["beat"] <= box["endBeat"]]
    return fix


FIXUPS = {
    **{(m, f"range.{f}"): automation_fix(f) for m in ("automation.points.get", "automation.points.set") for f in ("startBeat", "endBeat")},
    ("automation.points.set", "points"): automation_fix("points"),
    **{("grid.parameters.set", name): steps_fix(name.rsplit(".", 1)[-1])
       for name in ("values", "expectedValues", "writes.0.values", "writes.0.expectedValues")},
    ("grid.modules.insert", "typeName"): drop("typeId"),
    ("grid.modules.insert", "typeId"): drop("typeName"),
    ("grid.modules.insert", "modules.0.typeName"): drop("modules.0.typeId"),
    ("grid.parameters.set", "display"): drop("value"),
    ("transport.set", "timeSignature"): lambda p, v: p.__setitem__("timeSignature", "3/4"),
    ("devices.insert", "path"): file_insert,
    ("devices.layers.insert", "path"): file_insert,
    ("launcher.import", "path"): lambda p, v: p.__setitem__("path", as_path(v)),
    ("project.saveAs", "path"): project_path,
    ("export.audio", "directory"): lambda p, v: p.__setitem__("directory", as_path(v)),
    **{(m, f): window_fix(f) for m in ("notes.read", "notes.edit")
       for f in ("stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount")},
}


def resolve(schema, defs):
    while "$ref" in schema:
        schema = defs[schema["$ref"].rsplit("/", 1)[1]]
    return schema


def variants(schema, defs):
    schema = resolve(schema, defs)
    if "anyOf" in schema:
        return [resolve(s, defs) for s in schema["anyOf"] if s.get("type") != "null"]
    return [schema]


def nudge(value, up):
    return math.nextafter(value, math.inf if up else -math.inf)


def vary(item, i):
    """Make array item i distinct: unique UUIDs and note cells."""
    if isinstance(item, dict):
        out = {k: vary(v, i) for k, v in item.items()}
        if "pitch" in out and "beat" in out:
            out["pitch"], out["beat"] = i % 128, float(i // 128 * 2)
        return out
    if isinstance(item, str) and UUID.match(item):
        return f"{i:08x}" + item[8:]
    return item


def generate(schema, defs):
    """A minimal valid value for a schema node (cross-field rules are not modelled)."""
    schema = variants(schema, defs)[0]
    if "enum" in schema:
        return schema["enum"][0]
    kind = schema.get("type")
    if kind == "object":
        return {k: generate(schema["properties"][k], defs) for k in schema.get("required", [])}
    if kind == "array":
        return [vary(generate(schema["items"], defs), i) for i in range(schema.get("minItems", 0))]
    if kind == "boolean":
        return True
    if kind in ("integer", "number"):
        low = schema.get("minimum", nudge(schema["exclusiveMinimum"], True) if "exclusiveMinimum" in schema else None)
        high = schema.get("maximum")
        value = 0 if low is None or low <= 0 else low
        if high is not None and value > high:
            value = high
        return int(value) if kind == "integer" else float(value)
    if kind == "string":
        if "pattern" in schema:
            if schema["pattern"] != UUID_PATTERN:
                raise AssertionError(f"no generator for pattern {schema['pattern']}")
            return SAMPLE_UUID
        return "a" * max(schema.get("minLength", 0), 1)
    raise AssertionError(f"no generator for {schema}")


def child_schema(node, key, defs):
    node = variants(node, defs)[0]
    return node["items"] if isinstance(key, int) else node["properties"][key]


def ensure(root, path, schema, defs):
    """Create missing containers along path; return (parent, key) of the last step."""
    node, node_schema = root, schema
    for key in path[:-1]:
        node_schema = child_schema(node_schema, key, defs)
        if isinstance(key, int):
            if not node:
                node.append(generate(node_schema, defs))
            node = node[key]
        else:
            if node.get(key) is None:
                node[key] = generate(node_schema, defs)
            node = node[key]
    return node, path[-1]


def leaves(schema, defs, path=()):
    """Yield (path, node) for every named property, descending objects and array item 0."""
    for node in variants(schema, defs):
        if node.get("type") == "object":
            for name, sub in node.get("properties", {}).items():
                if name == "timeoutMs":
                    continue
                yield path + (name,), sub
                yield from leaves(sub, defs, path + (name,))
        elif node.get("type") == "array":
            yield from leaves(node["items"], defs, path + (0,))


def bound_vectors(node, defs):
    """Yield (label, value, should_accept); an array's value is the item count."""
    for s in variants(node, defs):
        kind = s.get("type")
        if kind in ("integer", "number"):
            step = (lambda v, up: v + (1 if up else -1)) if kind == "integer" else nudge
            if "minimum" in s:
                yield f"min={s['minimum']}", s["minimum"], True
                yield "min-", step(s["minimum"], False), False
            if "exclusiveMinimum" in s:
                yield f"gt={s['exclusiveMinimum']}", s["exclusiveMinimum"], False
                yield "gt+", step(s["exclusiveMinimum"], True), True
            if "maximum" in s:
                yield f"max={s['maximum']}", s["maximum"], True
                yield "max+", step(s["maximum"], True), False
        elif kind == "string":
            if s.get("minLength", 0) > 0:
                yield f"minLength={s['minLength']}", "a" * s["minLength"], True
                yield "minLength-", "a" * (s["minLength"] - 1), False
            if "maxLength" in s:
                top = s["maxLength"]
                yield f"maxLength={top}", "a" * top, True
                yield "maxLength+", "a" * (top + 1), False
                # Java counts UTF-16 units; U+1F600 is two.
                yield f"maxLength={top} utf16", ("\U0001F600" * (top // 2 - 1) + "a" * (2 + top % 2)) if top >= 2 else "a" * top, True
                yield "maxLength+ utf16", "\U0001F600" * (top // 2 + 1), False
        elif kind == "array":
            if s.get("minItems", 0) > 0:
                yield f"minItems={s['minItems']}", s["minItems"], True
                yield "minItems-", s["minItems"] - 1, False
            if "maxItems" in s:
                yield f"maxItems={s['maxItems']}", s["maxItems"], True
                yield "maxItems+", s["maxItems"] + 1, False


def python_verdict(method, params):
    try:
        TOOLS[method].model.model_validate(params)
        return True
    except ValidationError:
        return False


async def java_verdict(port, method, params):
    reader, writer = await asyncio.open_connection("127.0.0.1", port, limit=1 << 21)
    try:
        request = {"id": "parity", "method": method, "params": params, "timeoutMs": 5000}
        writer.write((json.dumps(request, separators=(",", ":")) + "\n").encode())
        await writer.drain()
        response = json.loads(await asyncio.wait_for(reader.readline(), 10))
    finally:
        writer.close()
    error = response.get("error")
    return error is None or error["code"] == "fixture_unknown", error


def cases(method):
    """Yield (label, params, field, expectation) for one method."""
    spec = TOOLS[method]
    schema = spec.model.model_json_schema()
    defs = schema.get("$defs", {})
    sent = {key for example in spec.examples for key in example}
    for example in spec.examples:
        for path, node in leaves(schema, defs):
            name = ".".join(map(str, path))
            top_level_gap = len(path) == 1 and path[0] not in example
            if top_level_gap and path[0] in sent:
                continue  # an example that carries this field covers it
            base = copy.deepcopy(example)
            parent, key = ensure(base, path, schema, defs)
            if top_level_gap:  # optional field the examples never send
                parent[key] = generate(node, defs)
                if (method, name) in FIXUPS:
                    FIXUPS[method, name](base, parent[key])
                yield f"{method}:{name} optional", base, name, "optional"
            for label, value, accept in bound_vectors(node, defs):
                params = copy.deepcopy(example)
                parent, key = ensure(params, path, schema, defs)
                if label.startswith(("minItems", "maxItems")):
                    current = parent.get(key) if isinstance(parent, dict) else parent[key]
                    template = current[0] if current else generate(variants(node, defs)[0]["items"], defs)
                    parent[key] = [vary(copy.deepcopy(template), i) for i in range(value)]
                else:
                    parent[key] = value
                if (method, name) in FIXUPS:
                    FIXUPS[method, name](params, value)
                yield f"{method}:{name} {label}", params, name, "accept" if accept else "reject"


async def run(port):
    """Compare both validators on every vector; return the number checked."""
    checked = 0
    problems = []
    covered = {}  # (method, field, vector) -> some base was accepted by pydantic
    for method in sorted(set(TOOLS) - CLIENT_SIDE):
        seen = set()
        for label, params, field, expect in cases(method):
            key = (label, json.dumps(params, sort_keys=True))
            if key in seen or SKIPS.get((method, field, expect)) or SKIPS.get((method, field, "all")):
                continue
            seen.add(key)
            py = python_verdict(method, params)
            java, error = await java_verdict(port, method, params)
            checked += 1
            if py != java:
                problems.append(f"MISMATCH {label}: python={'accept' if py else 'reject'} java={'accept' if java else 'reject'} {error}")
            elif expect != "reject":
                covered[label] = covered.get(label, False) or py
            elif py:
                problems.append(f"{label}: both sides accept a value beyond the schema bound")
    insert = TOOLS["grid.modules.insert"].examples[0]
    for name in ("<", "=", ">", "\u2260", "\u2264", "\u2265", "Slope \u2197", "---", "\u00b2"):
        for params in (insert | {"typeName": name},
                       {k: v for k, v in insert.items() if k not in ("typeName", "x", "y")} | {"modules": [{"typeName": name, "x": 1, "y": 1}]}):
            py = python_verdict("grid.modules.insert", params)
            java, error = await java_verdict(port, "grid.modules.insert", params)
            checked += 1
            if not (py and java):
                problems.append(f"typeName {name!r} must be accepted by both: python={py} java={java} {error}")
    problems += [f"VACUOUS {label}: no example base is valid with this vector, so it never exercised the bound"
                 for label, ok in covered.items() if not ok]
    if problems:
        raise AssertionError(f"bound parity failed ({checked} vectors):\n" + "\n".join(problems))
    return checked
