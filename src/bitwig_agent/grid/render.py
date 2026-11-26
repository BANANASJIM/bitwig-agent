"""Client-side `grid.render`: read one Grid device with grid.get and draw its layout as SVG (or PNG).

Read-only toward Bitwig: only grid.get is called. The only side effect is the file written at outPath.
The returned summary lets an agent judge the layout (overlaps, orphans, sections, density) without the image.
"""

from __future__ import annotations

import asyncio
import html
import math
import os
import re
import shutil
import subprocess
from typing import Any, NamedTuple

from bitwig_agent.errors import bridge_error
from bitwig_agent.grid.read import GridCall, device_target, host_call, read_graph, read_params

CELL = 46  # svg units per grid cell
MAX_SPAN = 2000  # cells per axis; refuse to draw anything larger
LIST_CAP = 50

# The one category table: category -> (colour, exact module type names, name keywords as fallback).
CATEGORIES: dict[str, tuple[str, frozenset[str], tuple[str, ...]]] = {
    "sequencing": (
        "#4f8fd8",
        frozenset(
            {
                "Transport",
                "Clock",
                "Clock Divide",
                "Clock Quantize",
                "Ø Counter",
                "Ø Mirror",
                "Steps",
                "Triggers",
                "Gates",
                "Accents",
                "Probabilities",
                "Pitches",
                "Step Access",
                "Array",
                "Recorder",
                "Shift Register",
                "Trigger",
                "Transport Playing",
                "Select In",
                "Counter",
            }
        ),
        ("clock", "step", "counter", "sequenc", "transport"),
    ),
    "random": (
        "#d8a24f",
        frozenset({"Dice", "Chance", "Noise", "S/H LFO", "Sample / Hold", "Hold", "Latch", "N-Latch"}),
        ("random", "dice", "noise", "latch"),
    ),
    "pitch": (
        "#7cc47c",
        frozenset(
            {
                "Key Quantize",
                "Pitch Quantize",
                "Semitone Quantize",
                "Transpose",
                "Root Key",
                "Pitch Class",
                "Octaver",
                "Pitch",
                "Scale Steps",
                "Pitch Scaler",
                "Quantize",
                "Bend",
            }
        ),
        ("quantize", "pitch", "transpose", "scale", "key"),
    ),
    "oscillator": (
        "#d86a6a",
        frozenset(
            {
                "Sine",
                "Sawtooth",
                "Triangle",
                "Pulse",
                "Union",
                "Wavetable",
                "Phase-1",
                "Swarm",
                "Bite",
                "Sampler",
                "Pluck",
                "LFO",
            }
        ),
        ("osc", "wave"),
    ),
    "envelope": (
        "#b07cd8",
        frozenset({"AD", "ADSR", "AR", "Gate Length", "Gate Repeat", "Lag", "Slopes", "Segments", "Curves"}),
        ("envelope", "slope"),
    ),
    "filter": (
        "#d87cb0",
        frozenset(
            {"SVF", "Low-pass", "High-pass", "Low-pass LD", "Low-pass MG", "Sallen-Key", "Comb", "All-pass", "Vowels"}
        ),
        ("pass", "filter", "formant"),
    ),
    "fx": (
        "#5fb8b8",
        frozenset(
            {
                "All-pass Delay",
                "Mod Delay",
                "Long Delay",
                "Delay",
                "Chorus+",
                "Flanger+",
                "Phaser+",
                "Pitch Shift",
                "Freq Shift+",
                "Distortion",
            }
        ),
        ("delay", "chorus", "flanger", "phaser", "reverb", "shift", "distort", "crush", "fold"),
    ),
    "output": (
        "#e0e0e0",
        frozenset({"Audio Out", "Note Out", "Modulator Out", "Mixer", "Amplify", "Blend", "Pan", "Stereo Width"}),
        (" out", "mix", "pan"),
    ),
    "visualization": (
        "#f0d040",
        frozenset({"VU Meter", "Oscilloscope", "Spectrum", "Value Readout", "Tuner", "XY"}),
        ("meter", "scope", "spectrum", "readout"),
    ),
    "text": ("#888888", frozenset({"Label", "Comment"}), ()),
    "other": ("#9aa0a6", frozenset(), ()),
}
_BY_TYPE = {t: k for k, (_, names, _kw) in CATEGORIES.items() for t in names}


def category(type_name: str) -> str:
    if type_name in _BY_TYPE:
        return _BY_TYPE[type_name]
    low = type_name.casefold()
    for key, (_, _names, words) in CATEGORIES.items():
        if any(w in low for w in words):
            return key
    return "other"


SECTION_PREFIX = re.compile(r"^([^\s:]{1,16}):\s")


def _converter() -> list[str] | None:
    if shutil.which("rsvg-convert"):
        return ["rsvg-convert", "-f", "png"]
    if shutil.which("magick"):
        return ["magick", "svg:-", "png:-"]
    return None


async def _comment_texts(call: GridCall, ids: list[str]) -> dict[str, str]:
    rows = await read_params(call, ids)
    return {
        row["instanceId"]: p["value"]
        for row in rows.values()
        for p in row.get("parameters") or []
        if p.get("parameterId") == "TEXT" and isinstance(p.get("value"), str)
    }


# ---- analysis --------------------------------------------------------------------------------------------
def _box(rows: list[dict[str, Any]]) -> dict[str, int]:
    x0, y0 = min(r["x"] for r in rows), min(r["y"] for r in rows)
    x1, y1 = max(r["x"] + r["width"] for r in rows), max(r["y"] + r["height"] for r in rows)
    return {"x": x0, "y": y0, "width": x1 - x0, "height": y1 - y0}


def _label(row: dict[str, Any]) -> str:
    return (row.get("customName") or "").strip() or row["typeName"]


def _ref(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": _label(row),
        "type": row["typeName"],
        "x": row["x"],
        "y": row["y"],
        "width": row["width"],
        "height": row["height"],
    }


def summarize(rows: list[dict[str, Any]], cables: list[dict[str, Any]], namespace: str | None = None) -> dict[str, Any]:
    if not rows:
        return {
            "moduleCount": 0,
            "connectionCount": 0,
            "boundingBox": None,
            "categories": {},
            "overlaps": [],
            "overlapCount": 0,
            "unconnected": [],
            "unconnectedCount": 0,
            "sections": [],
            "density": 0.0,
        }
    owner: dict[tuple[int, int], dict[str, Any]] = {}
    pairs: dict[tuple[str, str], tuple[dict[str, Any], dict[str, Any]]] = {}
    for r in rows:
        for cx in range(r["x"], r["x"] + r["width"]):
            for cy in range(r["y"], r["y"] + r["height"]):
                other = owner.setdefault((cx, cy), r)
                if other is not r:
                    pairs.setdefault((other["instanceId"], r["instanceId"]), (other, r))
    box = _box(rows)
    linked = {c["sourceModuleId"] for c in cables} | {c["targetModuleId"] for c in cables}
    loose = [r for r in rows if r["instanceId"] not in linked and category(r["typeName"]) != "text"]
    cats: dict[str, int] = {}
    for r in rows:
        key = category(r["typeName"])
        cats[key] = cats.get(key, 0) + 1
    groups: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        m = SECTION_PREFIX.match((r.get("customName") or "").strip())
        if m:
            groups.setdefault(m.group(1) + ": ", []).append(r)
    sections = [{"prefix": p, "moduleCount": len(rs), "boundingBox": _box(rs)} for p, rs in sorted(groups.items())]
    out: dict[str, Any] = {
        "moduleCount": len(rows),
        "connectionCount": len(cables),
        "boundingBox": box,
        "categories": dict(sorted(cats.items(), key=lambda kv: -kv[1])),
        "overlapCount": len(pairs),
        "overlaps": [[_ref(a), _ref(b)] for a, b in list(pairs.values())[:LIST_CAP]],
        "unconnectedCount": len(loose),
        "unconnected": [_ref(r) for r in loose[:LIST_CAP]],
        "sections": sections,
        "density": round(len(owner) / (box["width"] * box["height"]), 3),
    }
    if namespace is not None:
        out["namespace"] = {
            "prefix": namespace,
            "moduleCount": sum(1 for r in rows if (r.get("customName") or "").startswith(namespace)),
        }
    return out


# ---- drawing ---------------------------------------------------------------------------------------------
def _fit(text: str, px: float, size: float) -> str:
    limit = max(2, int((px - 6) / (size * 0.56)))
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _wrap(text: str, px: float, size: float, lines: int = 12) -> list[str]:
    width = max(8, int((px - 8) / (size * 0.56)))
    out: list[str] = []
    for para in text.strip("\n").split("\n"):
        out += [para[i : i + width] for i in range(0, len(para), width)] or [""]
    return out[:lines]


def _port_y(row: dict[str, Any], ports: list[dict[str, Any]], key: str) -> float:
    keys = [p["key"] for p in ports]
    i = keys.index(key) if key in keys else 0
    y: float = row["y"] + row["height"] * (i + 0.5) / max(1, len(keys))
    return y


class View(NamedTuple):
    namespace: str | None
    show_connections: bool
    scale: float


def draw(rows: list[dict[str, Any]], cables: list[dict[str, Any]], texts: dict[str, str], view: View) -> str:
    namespace, show_connections, scale = view
    box = _box(rows)
    pad = 1
    x0, y0 = box["x"] - pad, box["y"] - pad
    W, H = (box["width"] + 2 * pad) * CELL, (box["height"] + 2 * pad) * CELL + 34
    by_id = {r["instanceId"]: r for r in rows}

    def inside(row: dict[str, Any]) -> bool:
        return not namespace or (row.get("customName") or "").startswith(namespace)

    o = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W * scale:.0f}" height="{H * scale:.0f}" viewBox="0 0 {W} {H}" font-family="sans-serif">',
        f'<rect width="{W}" height="{H}" fill="#1e1f22"/>',
    ]
    if show_connections:
        o.append('<g fill="none" stroke-width="1.3">')
        for c in cables:
            a, b = by_id.get(c["sourceModuleId"]), by_id.get(c["targetModuleId"])
            if not a or not b:
                continue
            ax, ay = (
                (a["x"] + a["width"] - x0) * CELL,
                (_port_y(a, a.get("outputs", []), c["sourcePortKey"]) - y0) * CELL,
            )
            bx, by = (b["x"] - x0) * CELL, (_port_y(b, b.get("inputs", []), c["targetPortKey"]) - y0) * CELL
            dx = max(24, min(120, abs(bx - ax) / 2))
            op = 0.55 if inside(a) or inside(b) else 0.15
            color = CATEGORIES[category(a["typeName"])][0]
            o.append(
                f'<path d="M{ax:.0f},{ay:.0f} C{ax + dx:.0f},{ay:.0f} {bx - dx:.0f},{by:.0f} {bx:.0f},{by:.0f}" stroke="{color}" stroke-opacity="{op}"/>'
            )
        o.append("</g>")
    for r in rows:
        kind = category(r["typeName"])
        color = CATEGORIES[kind][0]
        x, y = (r["x"] - x0) * CELL, (r["y"] - y0) * CELL
        w, h = r["width"] * CELL - 3, r["height"] * CELL - 3
        title = _label(r)
        dim = ' opacity="0.3"' if not inside(r) else ""
        o.append(f"<g{dim}>")
        if r["typeName"] == "Label":
            o.append(
                f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{color}" fill-opacity="0.12" stroke="{color}" stroke-opacity="0.5"/>'
            )
            o.append(
                f'<text x="{x + 4}" y="{y + 15}" font-size="13" font-weight="bold" fill="#eee">{html.escape(_fit(title, w, 13))}</text>'
            )
        elif r["typeName"] == "Comment":
            body = _wrap(texts.get(r["instanceId"], ""), w, 9)
            h2 = max(h, 15 + 11 * len(body))
            o.append(
                f'<rect x="{x}" y="{y}" width="{w}" height="{h2}" rx="4" fill="{color}" fill-opacity="0.18" stroke="{color}" stroke-dasharray="4 3"/>'
            )
            o.append(
                f'<text x="{x + 4}" y="{y + 11}" font-size="9" font-weight="bold" fill="#ddd">{html.escape(_fit(title, w, 9))}</text>'
            )
            for i, line in enumerate(body):
                o.append(
                    f'<text x="{x + 4}" y="{y + 22 + 11 * i}" font-size="8" fill="#ccc" xml:space="preserve">{html.escape(line)}</text>'
                )
        else:
            o.append(
                f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{color}" fill-opacity="0.85" stroke="{color}"/>'
            )
            o.append(
                f'<text x="{x + 3}" y="{y + 12}" font-size="9" fill="#111">{html.escape(_fit(title, w, 9))}</text>'
            )
            if (r.get("customName") or "").strip():
                o.append(
                    f'<text x="{x + 3}" y="{y + 23}" font-size="7" fill="#222">{html.escape(_fit(r["typeName"], w, 7))}</text>'
                )
        o.append("</g>")
    lx = 10
    for key, (color, _n, _k) in CATEGORIES.items():
        o.append(
            f'<rect x="{lx}" y="{H - 26}" width="12" height="12" fill="{color}"/><text x="{lx + 16}" y="{H - 16}" font-size="11" fill="#ddd">{key}</text>'
        )
        lx += 22 + 7 * len(key)
    o.append("</svg>")
    return "\n".join(o)


# ---- entry point -----------------------------------------------------------------------------------------
def _write(path: str, data: bytes, overwrite: bool) -> None:
    parent = os.path.dirname(path)
    if not os.path.isdir(parent):
        raise bridge_error("invalid_params", f"Parent directory does not exist: {parent}")
    try:
        with open(path, "wb" if overwrite else "xb") as handle:
            handle.write(data)
    except FileExistsError as exc:
        raise bridge_error("file_exists", f"{path} exists; pass overwrite=true to replace it") from exc
    except OSError as exc:
        raise bridge_error("write_failed", f"Cannot write {path}: {exc}") from exc


async def render(client: Any, args: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:
    path, fmt, overwrite = args["outPath"], args.get("format", "svg"), args.get("overwrite", False)
    if not overwrite and os.path.exists(path):
        raise bridge_error("file_exists", f"{path} exists; pass overwrite=true to replace it")
    if not os.path.isdir(os.path.dirname(path)):
        raise bridge_error("invalid_params", f"Parent directory does not exist: {os.path.dirname(path)}")
    convert = None
    if fmt == "png":
        convert = _converter()
        if convert is None:
            raise bridge_error("invalid_params", "format=png needs rsvg-convert or magick on PATH; use format=svg")
    call = host_call(client, device_target(args), timeout_ms)
    rows, cables = await read_graph(call)
    if not rows:
        raise bridge_error("empty_grid", "The Grid device has no modules to render")
    box = _box(rows)
    if max(box["width"], box["height"]) > MAX_SPAN:
        raise bridge_error(
            "layout_too_large",
            f"Layout spans {box['width']}x{box['height']} cells (limit {MAX_SPAN}); too sparse to draw",
            boundingBox=box,
        )
    ids = [r["instanceId"] for r in rows if r["typeName"] == "Comment"]
    texts = await _comment_texts(call, ids)
    namespace = args.get("namespace")
    svg = draw(rows, cables, texts, View(namespace, args.get("showConnections", True), args.get("scale", 1.0)))
    data = svg.encode("utf-8")
    if convert is not None:
        done = await asyncio.to_thread(subprocess.run, convert, input=data, capture_output=True, timeout=120)
        if done.returncode != 0 or not done.stdout:
            raise bridge_error("convert_failed", f"{convert[0]} failed: {done.stderr.decode('utf-8', 'replace')[:300]}")
        data = done.stdout
    _write(path, data, overwrite)
    return {
        "status": "rendered",
        "outPath": path,
        "format": fmt,
        "bytes": len(data),
        "imageSize": {
            "width": math.ceil(((box["width"] + 2) * CELL) * args.get("scale", 1.0)),
            "height": math.ceil(((box["height"] + 2) * CELL + 34) * args.get("scale", 1.0)),
        },
        **summarize(rows, cables, namespace),
    }
