"""Python/Java contract parity: send schema-derived vectors straight to the Java validator.

Imported by smoke-mcp-java.py and run against its fixture RpcServer. For every
method, walk the pydantic JSON schema; each numeric/length/item bound is sent at
the bound (accept) and just beyond it (reject), every optional field the catalog
examples never include is sent once, and every object gets an unknown field, every
field a wrong-typed and a null value, every required field is dropped and every
pattern gets a trailing newline. The raw TCP request bypasses the Python adapter, so
the JVM's own validation (schema, then module rules) decides; its verdict must equal
pydantic's. No codegen: vectors are derived from the schema at run time.
"""

import asyncio
import copy
import json
import math
import os
import re
from typing import Any

from pydantic import ValidationError
from tooling import host_paths

from bitwig_agent.protocol import CLIENT_SIDE, TOOLS
from bitwig_agent.protocol.base import MAX_BEAT, PROTOCOL
from bitwig_agent.protocol.clips import MIDI_PITCH_COUNT
from bitwig_agent.protocol.refs import CONTROL_ID, Ref

UUID_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
UUID = re.compile(UUID_PATTERN)
SAMPLE_UUID = "12345678-1234-1234-1234-123456789abc"
HASH_PATTERN = "^[0-9a-f]{64}$"
SAMPLE_HASH = "0" * 64
PATTERN_SAMPLES = {UUID_PATTERN: SAMPLE_UUID, CONTROL_ID: "0", HASH_PATTERN: SAMPLE_HASH}
REF_MODELS = {model.__name__ for model in Ref.__subclasses__()}

# (method, field path, vector kind) -> reason. Kind "accept"/"reject"/"optional"
# skips that vector kind for the field; "all" skips every vector for it. Use only
# for cross-field rules the two sides legitimately express differently.
SKIPS: dict[tuple[str, str, str], str] = {
    (
        "devices.insert",
        "insert.id",
        "accept",
    ): "per-kind id caps (clap 256, vst3 32) make the shared 4096 unreachable; rejects still run",
    (
        "clips.insert",
        "notes.0.beat",
        "accept",
    ): "a note must fit the clip length (loop 4096, arranger 65536), so beat 4194304 is unreachable here",
    (
        "notes.edit",
        "edits.0.beat",
        "accept",
    ): "no explicit window: the host derives a grid of <=128 steps (step<=4 beats, startStep<=1048576) and rejects extremes the schema allows; error names the fix",
    (
        "clips.insert",
        "notes.0.durationBeats",
        "accept",
    ): "same derived-grid limit (a 4096-beat note needs >128 steps)",
    (
        "notes.edit",
        "edits.0.durationBeats",
        "accept",
    ): "same derived-grid limit as edits.0.beat (duration 4096 beats needs >128 steps)",
    **{
        (
            m,
            f,
            "accept",
        ): "endBeats must exceed startBeats by at most 65536, so a bound on one side is reachable only with a matching partner; rejects still run"
        for m in ("export.audio", "tracks.bounce")
        for f in ("startBeats", "endBeats")
    },
}


MAX_BMP_CODEPOINT = 0xFFFF
ASTRAL_UTF16_UNITS = 2


ROOT = os.path.abspath(os.sep)


def utf16_units(char):
    return ASTRAL_UTF16_UNITS if ord(char) > MAX_BMP_CODEPOINT else 1


def as_path(value):
    """Make a vector string an absolute path of the same UTF-16 length."""
    skipped = replaced = 0
    while replaced < len(ROOT) and skipped < len(value):
        replaced += utf16_units(value[skipped])
        skipped += 1
    return ROOT + "a" * (replaced - len(ROOT)) + value[skipped:] if value else value


def file_insert(params, value):
    params["insert"] = {"kind": "file", "path": as_path(value)}


def project_path(params, value):
    """A final <dir>/S/S.bwproject path of the vector's UTF-16 length (same astral-character mix)."""
    tail = f"{os.sep}S{os.sep}S.bwproject"
    body = len(value.encode("utf-16-le", errors="surrogatepass")) // 2 - len(tail) - len(ROOT)
    if body > 0:
        filler = (
            "\U0001f600" * (body // 2) + "a" * (body % 2)
            if any(ord(c) > MAX_BMP_CODEPOINT for c in value)
            else "a" * body
        )
        params["path"] = ROOT + filler + tail


def window_fix(field):
    """Keep the explicit note window self-consistent while one window field sweeps."""

    def fix(params, value):
        if field == "pitchStart" and 0 <= value < MIDI_PITCH_COUNT:
            params["pitchCount"] = max(1, min(params["pitchCount"], MIDI_PITCH_COUNT - value))
        if "edits" in params and "stepSizeBeats" in params:
            params["edits"] = [
                {"op": "clear", "beat": params["startStep"] * params["stepSizeBeats"], "pitch": params["pitchStart"]}
            ]

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
                    box["values"] = box["values"][: len(box["expectedValues"])] or [0.0]

    return fix


def automation_fix(field):
    """Keep range ordered and points increasing and inside it while one of them sweeps."""

    def fix(params, value):
        box = params.get("range")
        if box and isinstance(value, (int, float)) and 0 <= value <= MAX_BEAT:
            if field == "startBeat":
                box["endBeat"] = max(box["endBeat"], value)
            elif field == "endBeat":
                box["startBeat"] = min(box["startBeat"], value)
        if "points" not in params:
            return
        low = box["startBeat"] if box else 0.0
        if field == "points":
            span = (box["endBeat"] - low) if box else float(value)
            params["points"] = [
                {"beat": low + span * i / max(value, 1), "value": 0.5, "interpolation": "linear"} for i in range(value)
            ]
        elif box:
            params["points"] = [p for p in params["points"] if box["startBeat"] <= p["beat"] <= box["endBeat"]]

    return fix


def with_partner(key, value):
    """An expected* entry only counts with the entry it guards."""

    def fix(params, _):
        params.setdefault(key, value)

    return fix


FIXUPS = {
    **{
        ("tracks.routing.set", f"expect.input.{f}"): with_partner("input", {"kind": "all_ins"})
        for f in ("name", "nativeType")
    },
    **{
        ("tracks.routing.set", f"expect.output.{f}"): with_partner("output", {"kind": "none"})
        for f in ("name", "nativeType")
    },
    **{
        (m, f"range.{f}"): automation_fix(f)
        for m in ("automation.points.get", "automation.points.set")
        for f in ("startBeat", "endBeat")
    },
    ("automation.points.set", "points"): automation_fix("points"),
    **{
        ("grid.parameters.set", name): steps_fix(name.rsplit(".", 1)[-1])
        for name in ("values", "expectedValues", "writes.0.values", "writes.0.expectedValues")
    },
    ("grid.modules.insert", "typeName"): drop("typeId"),
    ("grid.modules.insert", "typeId"): drop("typeName"),
    ("grid.modules.insert", "modules.0.typeName"): drop("modules.0.typeId"),
    ("grid.parameters.set", "display"): drop("value"),
    ("transport.set", "timeSignature"): lambda p, v: p.__setitem__("timeSignature", "3/4"),
    ("devices.insert", "insert.path"): file_insert,
    ("clips.import", "path"): lambda p, v: p.__setitem__("path", as_path(v)),
    ("clips.set", "expect"): lambda p, v: p.__setitem__("expect", {"name": "Hook"}),
    ("project.saveAs", "path"): project_path,
    ("export.audio", "directory"): lambda p, v: p.__setitem__("directory", as_path(v)),
    **{
        (m, f): window_fix(f)
        for m in ("notes.read", "notes.edit")
        for f in ("stepSizeBeats", "startStep", "steps", "pitchStart", "pitchCount")
    },
}


def resolve(schema, defs):
    while "$ref" in schema:
        schema = defs[schema["$ref"].rsplit("/", 1)[1]]
    return schema


def variants(schema, defs):
    schema = resolve(schema, defs)
    if "anyOf" in schema:
        return [resolve(s, defs) for s in schema["anyOf"] if s.get("type") != "null"]
    if ref_union(schema):
        return [resolve(branch, defs) for branch in schema["oneOf"]]
    return [schema]


def ref_union(schema):
    """Whether the node is a union of the ref kinds of protocol/refs.py, named by their models."""
    branches = schema.get("oneOf") or ()
    return bool(branches) and all(b.get("$ref", "").rsplit("/", 1)[-1] in REF_MODELS for b in branches)


def opaque(schema, defs):
    """A ref (a union of ref kinds): its own parity is the exported ref schema's, so its fields are not walked."""
    return ref_union(resolve(schema, defs))


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


def generate(schema, defs):  # noqa: PLR0911
    """A minimal valid value for a schema node (cross-field rules are not modelled)."""
    schema = variants(schema, defs)[0]
    if "const" in schema:
        return schema["const"]
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
            if schema["pattern"] not in PATTERN_SAMPLES:
                raise AssertionError(f"no generator for pattern {schema['pattern']}")
            return PATTERN_SAMPLES[schema["pattern"]]
        return "a" * max(schema.get("minLength", 0), 1)
    raise AssertionError(f"no generator for {schema}")


def container_form(schema, defs):
    """The object or array form of a schema node, else its first form."""
    forms = variants(schema, defs)
    return next((f for f in forms if f.get("type") in ("object", "array")), forms[0])


def child_schema(node, key, defs):
    """The schema of node's child; of a union, the first form that has it."""
    forms = variants(node, defs)
    if isinstance(key, int):
        return next(f for f in forms if "items" in f)["items"]
    return next(f for f in forms if key in f.get("properties", {}))["properties"][key]


def ensure(root, path, schema, defs):
    """Create missing containers along path, as the form of a union that holds the next key; return (parent, key)."""
    node, node_schema = root, schema
    for i, key in enumerate(path[:-1]):
        node_schema = child_schema(node_schema, key, defs)
        form = form_for(node_schema, path[i + 1], defs)
        if isinstance(key, int):
            if not node:
                node.append(generate(form, defs))
            elif not isinstance(node[key], dict | list) or foreign(node[key], form):
                node[key] = generate(form, defs)
        elif not isinstance(node.get(key), dict | list) or foreign(node[key], form):
            node[key] = generate(form, defs)
        node = node[key]
    return node, path[-1]


def form_for(schema, key, defs):
    """The form of a union that has the key; else its object or array form, which a path needs instead of a scalar form."""
    forms = variants(schema, defs)
    has = (lambda f: "items" in f) if isinstance(key, int) else (lambda f: key in f.get("properties", {}))
    return next((f for f in forms if has(f)), container_form(schema, defs))


def foreign(value, form):
    """Whether a container already holds keys that the chosen form does not have."""
    return isinstance(value, dict) and not set(value) <= set(form.get("properties", {}))


def leaves(schema, defs, path=()):
    """Yield (path, node, required) for every named property, descending objects and array item 0."""
    alternatives = variants(schema, defs)
    for node in alternatives:
        if node.get("type") == "object":
            for name, sub in node.get("properties", {}).items():
                if name == "timeoutMs":
                    continue
                yield (
                    (*path, name),
                    sub,
                    len(alternatives) == 1 and name in node.get("required", ()),
                    node if len(alternatives) > 1 else None,
                )
                if not opaque(sub, defs):
                    yield from leaves(sub, defs, (*path, name))
        elif node.get("type") == "array":
            items = node["items"]
            if not any(v.get("type") in ("object", "array") for v in variants(items, defs)):
                yield (*path, 0), items, False, None
            yield from leaves(items, defs, (*path, 0))


def containers(schema, defs, path=()):
    """Yield (path, node) of every object the schema describes: the root, nested objects and array item 0 objects."""
    for node in variants(schema, defs):
        if node.get("type") == "object" and "properties" in node:
            yield path, node
            for name, sub in node["properties"].items():
                if not opaque(sub, defs):
                    yield from containers(sub, defs, (*path, name))
        elif node.get("type") == "array":
            yield from containers(node["items"], defs, (*path, 0))


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
                yield (
                    f"maxLength={top} utf16",
                    (
                        "\U0001f600" * (top // ASTRAL_UTF16_UNITS - 1)
                        + "a" * (ASTRAL_UTF16_UNITS + top % ASTRAL_UTF16_UNITS)
                    )
                    if top >= ASTRAL_UTF16_UNITS
                    else "a" * top,
                    True,
                )
                yield "maxLength+ utf16", "\U0001f600" * (top // 2 + 1), False
        elif kind == "array":
            if s.get("minItems", 0) > 0:
                yield f"minItems={s['minItems']}", s["minItems"], True
                yield "minItems-", s["minItems"] - 1, False
            if "maxItems" in s:
                yield f"maxItems={s['maxItems']}", s["maxItems"], True
                yield "maxItems+", s["maxItems"] + 1, False


WRONG_TYPES: tuple[tuple[str, Any], ...] = (
    ("boolean", True),
    ("string", "zz"),
    ("integer", 1),
    ("number", 1.5),
    ("array", []),
    ("object", {}),
)
ENUM_STRANGER = "zz-not-a-member"
UNKNOWN_FIELD = "zzUnknown"
INDEXES = frozenset("0123456789")
UTF16_WORDING = r"utf-16|characters|too long|at most"
# values may not outnumber expectedValues, so growing values past its maximum grows its partner past the same one.
PARTNERS = {
    ("grid.parameters.set", "values"): "expectedValues",
    ("grid.parameters.set", "writes.0.values"): "writes.0.expectedValues",
}


def allowed_kinds(node, defs):
    """JSON kinds the schema accepts at node, including null inside anyOf."""
    node = resolve(node, defs)
    kinds = set()
    for branch in node.get("anyOf", [node]):
        kinds.add(resolve(branch, defs).get("type"))
    if "number" in kinds:
        kinds.add("integer")
    return kinds


def type_vectors(node, defs):
    """Yield (label, value, expectation): a wrong-typed value and a null at a field, plus a non-member for enums."""
    kinds = allowed_kinds(node, defs)
    if None in kinds:
        return
    wrong = [(k, v) for k, v in WRONG_TYPES if k not in kinds]
    for label, value in wrong[:2]:
        yield f"wrong type {label}", value, "reject"
    if "integer" in kinds and "number" not in kinds:
        yield "float for integer", 1.0, "reject"
    yield "null", None, "reject" if "null" not in kinds else "same"
    if any("enum" in v for v in variants(node, defs)):
        yield "enum non-member", ENUM_STRANGER, "reject"


def python_verdict(method, params):
    try:
        TOOLS[method].model.model_validate(params)
        return True
    except ValidationError:
        return False


async def java_verdict(port, method, params):
    reader, writer = await asyncio.open_connection("127.0.0.1", port, limit=1 << 21)
    try:
        request = {"id": "parity", "method": method, "params": params, "timeoutMs": 5000, "protocol": PROTOCOL}
        writer.write((json.dumps(request, separators=(",", ":")) + "\n").encode())
        await writer.drain()
        response = json.loads(await asyncio.wait_for(reader.readline(), 10))
    finally:
        writer.close()
    error = response.get("error")
    return error is None, error


def render(field):
    """A dotted field name as the Java validator spells it: .name and [index]."""
    if field == "<root>":
        return ""
    return "".join(f"[{part}]" if part in INDEXES else f".{part}" for part in field.split("."))


BOUND_REJECTS = ("min-", "max+", "maxLength+", "maxLength+ utf16", "minLength-", "maxItems+", "minItems-")


def is_bound_reject(label):
    """Rejects that sweep a bound, so their base must be valid apart from the bound."""
    tail = label.split(" ", 1)[1]
    return tail in BOUND_REJECTS


def names_field(label, field, error):
    """A rejection is only agreement when Java's message points at the mutated field."""
    message = error["message"]
    leaf = field.rsplit(".", 1)[-1]
    word = rf"\b{re.escape(leaf)}\b"
    if label.endswith(" missing"):
        return re.search(rf"missing required fields?[^;]*{word}", message) is not None
    named = [field, PARTNERS.get((label.split(":")[0], field), field)]
    if any(f"params{render(name)}" in message for name in named):
        return True
    parts = field.split(".")
    if any(  # a union names itself when none of its forms takes the value
        f"params{render('.'.join(parts[:depth]))} matches none of the allowed forms" in message
        for depth in range(1, len(parts))
    ):
        return True
    # the module checks own UTF-16 limits and name the bare field with their own wording
    return "utf16" in label and re.search(word, message) and re.search(UTF16_WORDING, message, re.I) is not None


def edit(example, path, doc, owner=None):
    """A copy of the example with the containers of path created; returns (params, parent, key). doc is (schema, defs)."""
    params = copy.deepcopy(example)
    parent, key = ensure(params, path, *doc)
    if owner is not None:  # the field belongs to one anyOf form: build the container as exactly that form
        parent.clear()
        parent.update(generate(owner, doc[1]))
    if isinstance(key, int) and len(parent) <= key:
        parent.append(None)
    return params, parent, key


def fetch(parent, key):
    if isinstance(parent, list):
        return parent[key] if key < len(parent) else None
    return parent.get(key)


def bound_cases(method, example, leaf, doc):
    path, node, _, owner = leaf
    defs = doc[1]
    name = ".".join(map(str, path))
    for label, value, accept in bound_vectors(node, defs):
        params, parent, key = edit(example, path, doc, owner)
        if label.startswith(("minItems", "maxItems")):
            current = fetch(parent, key)
            template = current[0] if current else generate(variants(node, defs)[0]["items"], defs)
            parent[key] = [vary(copy.deepcopy(template), i) for i in range(value)]
        else:
            parent[key] = value
        if (method, name) in FIXUPS:
            FIXUPS[method, name](params, value)
        yield f"{method}:{name} {label}", params, name, "accept" if accept else "reject"


def shape_cases(method, example, leaf, doc):
    """Wrong-typed and null values, a dropped required field and a pattern with a trailing newline."""
    path, node, required, owner = leaf
    defs = doc[1]
    name = ".".join(map(str, path))
    for label, value, expect in type_vectors(node, defs):
        params, parent, key = edit(example, path, doc, owner)
        parent[key] = value
        yield f"{method}:{name} {label}", params, name, expect
    if required:
        params, parent, key = edit(example, path, doc, owner)
        parent.pop(key, None)
        yield f"{method}:{name} missing", params, name, "reject"
    for variant in variants(node, defs):
        params, parent, key = edit(example, path, doc, owner)
        current = fetch(parent, key)
        if "pattern" in variant and isinstance(current, str) and re.search(variant["pattern"], current):
            parent[key] = current + "\n"
            yield f"{method}:{name} pattern trailing newline", params, name, "reject"


def oneof_cases(method, example, container, doc):
    """Exactly-one-of forms: neither and both for required-field forms, a mismatched value for kind-selected forms."""
    path, node = container
    defs = doc[1]
    where = ".".join(map(str, path)) or "<root>"
    branches = node["oneOf"]
    if all(branch.get("required") for branch in branches):
        union = [key for branch in branches for key in branch["required"]]
        params, parent, _ = edit(example, (*path, UNKNOWN_FIELD), doc)
        for key in union:
            parent.pop(key, None)
        yield f"{method}:{where} oneOf neither form", params, where, "reject"
        params, parent, _ = edit(example, (*path, UNKNOWN_FIELD), doc)
        for key in union:
            parent[key] = generate(node["properties"][key], defs)
        yield f"{method}:{where} oneOf both forms", params, where, "reject"
        return
    for branch in branches:
        fields = branch["properties"]
        selector = next((key for key, field in fields.items() if "enum" in field), None)
        typed = {key: field for key, field in fields.items() if "type" in field}
        if selector is None or not typed:
            continue
        params, parent, _ = edit(example, (*path, UNKNOWN_FIELD), doc)
        parent[selector] = fields[selector]["enum"][0]
        for key, field in typed.items():
            wrong = [
                value
                for kind, value in WRONG_TYPES
                if kind in allowed_kinds(node["properties"][key], defs) and kind not in allowed_kinds(field, defs)
            ]
            parent[key] = wrong[0]
        yield f"{method}:{where} oneOf {selector}={parent[selector]} mismatch", params, where, "reject"


def owns(example, path, schema):
    """Whether the example uses the exactly-one-of form that the field at path belongs to."""
    branches = [b["required"] for b in schema.get("oneOf", ()) if b.get("required")]
    owner = next((b for b in branches if path[0] in b), None)
    used = next((b for b in branches if set(b) <= set(example)), None)
    return owner is None or owner is used


def cases(method):
    """Yield (label, params, field, expectation) for one method."""
    spec = TOOLS[method]
    schema = spec.model.model_json_schema()
    defs = schema.get("$defs", {})
    doc = (schema, defs)
    sent = {key for example in spec.examples for key in example}
    for example in map(host_paths, spec.examples):
        for container, node in containers(schema, defs):
            params, parent, key = edit(example, (*container, UNKNOWN_FIELD), doc)
            parent[key] = 1
            where = ".".join(map(str, container)) or "<root>"
            yield f"{method}:{where} unknown field", params, where, "reject"
            if "oneOf" in node:
                yield from oneof_cases(method, example, (container, node), doc)
        for path, node, required, owner in leaves(schema, defs):
            name = ".".join(map(str, path))
            if not owns(example, path, schema):
                continue  # another example uses the form this field belongs to
            top_level_gap = len(path) == 1 and path[0] not in example
            if top_level_gap and path[0] in sent:
                continue  # an example that carries this field covers it
            if top_level_gap:  # optional field the examples never send
                base, parent, key = edit(example, path, doc, owner)
                parent[key] = generate(node, defs)
                if (method, name) in FIXUPS:
                    FIXUPS[method, name](base, parent[key])
                yield f"{method}:{name} optional", base, name, "optional"
            yield from bound_cases(method, example, (path, node, required, owner), doc)
            yield from shape_cases(method, example, (path, node, required, owner), doc)


class Ledger:
    """What the vectors have shown so far: problems and, per vector family, whether any base was valid."""

    def __init__(self):
        self.problems = []
        self.covered = {}  # accept vector -> some base was accepted by pydantic
        self.proven = {}  # bound reject -> some valid base showed Java naming the field
        self.twin = {}  # (method, field, utf16) -> the preceding accept vector was valid

    def judge(self, method, vector, py, java, error):
        label, _, field, expect = vector
        twin_key = (method, field, "utf16" in label)
        if py != java:
            self.problems.append(
                f"MISMATCH {label}: python={'accept' if py else 'reject'} java={'accept' if java else 'reject'} {error}"
            )
        elif expect in ("accept", "optional"):
            self.covered[label] = self.covered.get(label, False) or py
            self.twin[twin_key] = py
        elif expect == "reject" and py:
            self.problems.append(f"{label}: both sides accept a value the schema forbids")
        elif is_bound_reject(label) and self.twin.get(twin_key) is False:
            self.proven.setdefault(label, False)  # the base itself is invalid: this reject proves nothing
        elif expect == "reject" and not names_field(label, field, error):
            self.problems.append(f"VACUOUS {label}: Java rejected without naming params{render(field)}: {error}")
        elif is_bound_reject(label) and twin_key in self.twin:
            self.proven[label] = True

    def vacuous(self):
        return [
            f"VACUOUS {label}: no example base is valid with this vector, so it never exercised the bound"
            for label, ok in {**self.covered, **self.proven}.items()
            if not ok
        ]


async def run(port):
    """Compare both validators on every vector; return the number checked."""
    checked = 0
    ledger = Ledger()
    for method in sorted(set(TOOLS) - CLIENT_SIDE):
        seen = set()
        for vector in cases(method):
            label, params, field, expect = vector
            key = (label, json.dumps(params, sort_keys=True))
            if key in seen or SKIPS.get((method, field, expect)) or SKIPS.get((method, field, "all")):
                continue
            seen.add(key)
            py = python_verdict(method, params)
            java, error = await java_verdict(port, method, params)
            checked += 1
            ledger.judge(method, vector, py, java, error)
    insert = TOOLS["grid.modules.insert"].examples[0]
    for name in ("<", "=", ">", "\u2260", "\u2264", "\u2265", "Slope \u2197", "---", "\u00b2"):
        for params in (
            insert | {"typeName": name},
            {k: v for k, v in insert.items() if k not in ("typeName", "x", "y")}
            | {"modules": [{"typeName": name, "x": 1, "y": 1}]},
        ):
            py = python_verdict("grid.modules.insert", params)
            java, error = await java_verdict(port, "grid.modules.insert", params)
            checked += 1
            if not (py and java):
                ledger.problems.append(f"typeName {name!r} must be accepted by both: python={py} java={java} {error}")
    problems = ledger.problems + ledger.vacuous()
    if problems:
        raise AssertionError(f"vector parity failed ({checked} vectors):\n" + "\n".join(problems))
    return checked
