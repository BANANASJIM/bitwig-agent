"""host.diagnostics: classify the Bitwig log for host problems the API cannot show.

Reads only the log file (plus one `status` call for attribution). The log is shared by every
Bitwig instance of the user, so the section of the connected instance is chosen by matching its
process start time to the `Starting application` headers.
"""

from __future__ import annotations

import base64
import contextlib
import json
import os
import re
import sys
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

MAX_READ = 64 * 1024 * 1024
CHUNK = 1024 * 1024
START_TOLERANCE = timedelta(seconds=120)
LINE = re.compile(r"\[(\d{4})-(\d\d)-(\d\d) (\d\d):(\d\d):(\d{1,2})\.(\d{1,3}) ([^\]]*)\] ?(.*)")
HEADER = re.compile(rb"^\[[^\]\n]*\] ?Starting application version[^\n]*", re.MULTILINE)
GRID = re.compile(r"component (\S+) should be rejected from the network because")
ENGINE = re.compile(r"isEngineReadyChanged\((?:true|false)->(true|false)\)")
EXCEPTION = re.compile(r"\b(?:[\w$]+\.)*[\w$]*(?:Exception|Error)\b|^Caused by:|^Exception in thread")
EXTENSION = re.compile(r"extension|controller|script", re.I)
BENIGN = ("Cannot use realtime priority", "Analyzing audio")
PROBLEM_LEVELS = ("warn", "warning", "error", "severe", "fatal")
CATEGORIES = ("grid_rejected", "engine", "extension", "exception", "dialog", "other")
DIALOG_NOTE = (
    "Modal dialogs (Dashboard, Save Project?, Recover Projects) leave no log line and are not detectable here; "
    "only logged notifications (e.g. 'Update Available') are reported as dialog. Check the screen."
)


def candidates() -> list[Path]:
    if os.environ.get("BITWIG_LOG_PATH"):
        return [Path(os.environ["BITWIG_LOG_PATH"])]
    if sys.platform == "darwin":  # documented location; not verified natively
        return [Path.home() / "Library/Logs/Bitwig/BitwigStudio.log"]
    if sys.platform.startswith("win"):  # documented location; not verified natively
        base = os.environ.get("LOCALAPPDATA")
        return [Path(base) / "Bitwig Studio/log/BitwigStudio.log"] if base else []
    return [Path.home() / ".BitwigStudio/log/BitwigStudio.log"]


def parse_line(line: str) -> tuple[datetime, str, str, str] | None:
    m = LINE.match(line)
    if not m:
        return None
    y, mo, d, h, mi, s, frac, head, msg = m.groups()
    try:
        ts = datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), int(float("0." + frac) * 1_000_000))
    except ValueError:
        return None
    tokens = head.split()
    return ts, " ".join(tokens[:-1]), (tokens[-1].lower() if tokens else ""), msg


def read_back(path: Path, size: int, done: Callable[[bytes], bool]) -> tuple[int, bytes, bool]:
    """Read chunks from the end until done(view) or MAX_READ. Returns (offset of view, view, hit cap)."""
    start, buf = size, b""
    with path.open("rb") as f:
        while start > 0 and size - start < MAX_READ:
            step = min(CHUNK, start)
            start -= step
            f.seek(start)
            buf = f.read(step) + buf
            cut = buf.find(b"\n") + 1 if start > 0 else 0  # drop the partial first line
            if done(buf[cut:]):
                return start + cut, buf[cut:], False
    cut = buf.find(b"\n") + 1 if start > 0 else 0
    return start + cut, buf[cut:], start > 0


def process_start(pid: Any) -> datetime | None:
    """Local start time of a Linux process; None elsewhere or when unavailable."""
    if type(pid) is not int or not sys.platform.startswith("linux"):
        return None
    try:
        stat = Path(f"/proc/{pid}/stat").read_text()
        ticks = int(stat[stat.rindex(")") + 2 :].split()[19])
        btime = next(int(x.split()[1]) for x in Path("/proc/stat").read_text().splitlines() if x.startswith("btime"))
        return datetime.fromtimestamp(btime + ticks / os.sysconf("SC_CLK_TCK"))
    except (OSError, ValueError, StopIteration, IndexError):
        return None


def bitwig_processes() -> int | None:
    if not sys.platform.startswith("linux"):
        return None
    count = 0
    for p in Path("/proc").glob("[0-9]*/comm"):
        with contextlib.suppress(OSError):
            count += p.read_text().strip() == "BitwigStudio"
    return count


def header_time(raw: bytes) -> datetime | None:
    parsed = parse_line(raw.decode("utf-8", "replace"))
    return parsed[0] if parsed else None


def encode_marker(head_offset: int, head_line: str, end: int) -> str:
    return base64.urlsafe_b64encode(
        json.dumps({"h": head_offset, "l": head_line[:60], "o": end}, separators=(",", ":")).encode()
    ).decode()


def decode_marker(marker: str) -> dict[str, Any] | None:
    try:
        value = json.loads(base64.urlsafe_b64decode(marker.encode()))
    except (ValueError, TypeError):
        return None
    if isinstance(value, dict) and all(type(value.get(k)) is t for k, t in (("h", int), ("l", str), ("o", int))):
        return value
    return None


def classify(ts: datetime, component: str, level: str, msg: str) -> dict[str, Any] | None:  # noqa: PLR0911
    msg = msg.strip()
    if any(b in msg for b in BENIGN):
        return None
    event: dict[str, Any] = {
        "time": ts.isoformat(timespec="milliseconds"),
        "level": level,
        "component": component,
        "message": msg[:300],
    }
    if m := GRID.search(msg):
        return {**event, "category": "grid_rejected", "gridComponent": m.group(1)}
    if m := ENGINE.search(msg):
        return {**event, "category": "engine", "engineReady": m.group(1) == "true"}
    if component == "notifications":
        return {**event, "category": "dialog"}
    if level not in PROBLEM_LEVELS and not EXCEPTION.search(msg):
        return None
    if EXTENSION.search(component) or (EXTENSION.search(msg) and level in ("error", "severe", "fatal")):
        return {**event, "category": "extension"}
    return {**event, "category": "exception" if EXCEPTION.search(msg) else "other"}


def scan(text: str, cutoff: datetime | None) -> list[dict[str, Any]]:
    """Classify lines into events; identical consecutive ones merge (count), stack lines fold into detailLines."""
    events: list[dict[str, Any]] = []
    last_key, folding = None, False
    for line in text.splitlines():
        parsed = parse_line(line)
        if parsed is None:
            if folding and line.strip():
                events[-1]["detailLines"] = events[-1].get("detailLines", 0) + 1
            continue
        ts, component, level, msg = parsed
        event = None if cutoff and ts < cutoff else classify(ts, component, level, msg)
        folding = event is not None
        if event is None:
            continue
        key = (event["category"], level, component, event["message"])
        if key == last_key:
            events[-1]["count"] += 1
            events[-1]["lastTime"] = event["time"]
        else:
            events.append({**event, "count": 1})
            last_key = key
    return events


async def diagnostics(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:  # noqa: C901, PLR0912, PLR0915
    now = datetime.now()
    path = next((p for p in candidates() if p.is_file()), None)
    if path is None:
        return {
            "status": "unsupported",
            "platform": sys.platform,
            "searched": [str(p) for p in candidates()],
            "reason": "Bitwig log file not found (BITWIG_LOG_PATH overrides)",
            "dialogs": DIALOG_NOTE,
        }

    instance, instance_error = None, None
    try:
        status = await client.call("status", {}, timeout_ms)
        instance = status.get("instance") if isinstance(status, dict) else None
    except Exception as exc:  # noqa: BLE001 - attribution degrades; the log is still useful
        instance_error = f"status unavailable ({type(exc).__name__})"
    started = process_start(instance.get("pid") if isinstance(instance, dict) else None)

    size = path.stat().st_size
    target = started + START_TOLERANCE if started else None

    def done(view: bytes) -> bool:
        times = [t for t in (header_time(m.group(0)) for m in HEADER.finditer(view)) if t]
        return bool(times) and (target is None or min(times) <= target)

    base, data, capped = read_back(path, size, done)
    end = base + data.rfind(b"\n") + 1  # ignore a half-written last line
    data = data[: end - base]
    headers = [
        (base + m.start(), m.group(0).decode("utf-8", "replace"), header_time(m.group(0)))
        for m in HEADER.finditer(data)
    ]
    sections = [(offset, text, when) for offset, text, when in headers if when]

    reasons: list[str] = []
    chosen: tuple[int, str, datetime] | None = None
    method = "latest_section"
    if started:
        near = [h for h in sections if abs(h[2] - started) <= START_TOLERANCE]
        if near:
            chosen, method = min(near, key=lambda h: abs(h[2] - started)), "pid_start_time"
        else:
            reasons.append("no log start header within 120 s of the instance's process start")
    else:
        reasons.append(instance_error or "instance start time unavailable (non-Linux or no pid)")
    if chosen is None and sections:
        chosen = sections[-1]
    if chosen is None:
        reasons.append("no 'Starting application' header found; the whole readable tail was used")
    idx = sections.index(chosen) if chosen else -1
    later = sections[idx + 1 :] if chosen else []
    if later:
        reasons.append(
            f"{len(later)} later Bitwig start(s) in the same log; their lines may interleave with this instance's"
        )
    procs = bitwig_processes()
    if procs and procs > 1:
        reasons.append(f"{procs} Bitwig processes running")
    if capped:
        reasons.append("log larger than the read cap; older sections were not searched")

    sec_start = chosen[0] if chosen else base
    sec_end = later[0][0] if later else end
    read_from, cutoff, warnings = sec_start, None, []
    seconds = args.get("sinceSeconds", 300)
    window: dict[str, Any] = {"sinceSeconds": seconds}
    if args.get("sinceMarker") is not None:
        m = decode_marker(args["sinceMarker"])
        valid = False
        if m and chosen and m["h"] == chosen[0] and sec_start <= m["o"] <= sec_end:
            with path.open("rb") as f:
                f.seek(m["h"])
                valid = f.read(len(m["l"].encode())).decode("utf-8", "replace") == m["l"]
        if m and valid:
            read_from, window = m["o"], {"sinceMarker": True}
        else:
            warnings.append("sinceMarker is stale or from another instance/log; used the sinceSeconds window instead")
    if "sinceMarker" not in window:
        cutoff = now - timedelta(seconds=seconds)
        window["from"] = cutoff.isoformat(timespec="seconds")

    events = scan(data[read_from - base : sec_end - base].decode("utf-8", "replace"), cutoff)
    counts = {c: sum(e["count"] for e in events if e["category"] == c) for c in CATEGORIES}
    summary: dict[str, Any] = {"counts": counts, "totalEvents": sum(counts.values())}
    ready = [e["engineReady"] for e in events if e["category"] == "engine"]
    if ready:
        summary["engineReady"] = ready[-1]
    rejected = sorted({e["gridComponent"] for e in events if e["category"] == "grid_rejected"})
    if rejected:
        summary["gridRejectedComponents"] = rejected[:50]
    max_events = args.get("maxEvents", 50)

    result: dict[str, Any] = {
        "status": "observed",
        "platform": sys.platform,
        "logPath": str(path),
        "window": window,
        "events": events[-max_events:],
        "summary": summary,
        "marker": encode_marker(chosen[0], chosen[1], end) if chosen else None,
        "dialogs": DIALOG_NOTE,
        "attribution": {
            "method": method,
            "certain": method == "pid_start_time" and not reasons,
            "instance": instance,
            "sectionStart": chosen[2].isoformat(timespec="seconds") if chosen else None,
            "sectionsSeen": len(sections),
        },
    }
    if reasons:
        result["attribution"]["uncertainty"] = reasons
    if len(events) > max_events:
        result["droppedOlderEvents"] = len(events) - max_events
    if warnings:
        result["warnings"] = warnings
    return result
