#!/usr/bin/env python3
"""Generate docs/capabilities.md, what Bitwig Agent can do, from the capability table and the verification records.

pixi run capabilities                      # rewrite the map and print its headline
pixi run capabilities --to-verify [--json] # the maintainers' queue: available but unverified on the headline host
"""

import argparse
import json
import re
from pathlib import Path

from bitwig_agent.protocol.capabilities import CHAPTERS
from bitwig_agent.protocol.verification import Gap, Record, Standing, Verdicts, headline_host

ROOT = Path(__file__).resolve().parents[1]
MAP = ROOT / "docs" / "capabilities.md"
RECORDS = ROOT / "docs" / "verification"
PROTOCOL = ROOT / "PROTOCOL.md"
BAR = 20
METHOD = r"`([a-zA-Z]+(?:\.[a-zA-Z]+)*)`"
SYMBOLS = {"verified": "●", "available": "◔", "partial": "◑", "none": "○"}
PLATFORMS = {"linux": "Linux", "macos": "macOS", "windows": "Windows"}
PINNED = {
    **dict.fromkeys(
        ("devices.insert", "devices.delete", "devices.move", "devices.duplicate", "devices.replace", "presets.search"),
        "device-chain-edits-and-preset-search",
    ),
    **dict.fromkeys(("devices.remote.define", "devices.remote.delete"), "define-remote-controls-pages"),
    **dict.fromkeys(("meters.get", "tracks.sends.set"), "mixer-levels-sends-meters-markers"),
    **dict.fromkeys(("notes.get", "notes.set"), "notes-one-record-one-range-rule"),
    **dict.fromkeys(
        ("scenes.list", "scenes.insert", "scenes.set", "scenes.launch", "scenes.duplicate", "scenes.delete"), "scenes"
    ),
    "tracks.group": "track-tree-and-grouping",
    **dict.fromkeys(("tracks.convert", "tracks.ungroup"), "convert-and-ungroup-version-gated"),
}
"""Methods whose own PROTOCOL.md section is a dedicated one rather than the first section that documents them."""
LABELS = {
    "verified": "Verified",
    "available": "Available, not yet verified",
    "partial": "Partial",
    "none": "Not supported",
}


def load_records() -> list[Record]:
    return [Record.model_validate_json(path.read_text(encoding="utf-8")) for path in sorted(RECORDS.glob("*.json"))]


def status(standing: Standing, host: str) -> str:
    if standing.coverage == "none":
        return "none"
    if standing.coverage == "partial":
        return "partial"
    return "verified" if host in standing.verified_on else "available"


def slug(heading: str) -> str:
    """GitHub's anchor for a heading."""
    return re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")


def sections(text: str) -> dict[str, tuple[str, list[str]]]:
    """PROTOCOL.md's sections by GitHub anchor: the heading and the lines under it (code blocks left out)."""
    out: dict[str, tuple[str, list[str]]] = {}
    seen: dict[str, int] = {}
    lines: list[str] = []
    fenced = False
    for line in text.splitlines():
        fenced ^= line.startswith("```")
        heading = None if fenced else re.match(r"#+ (.*)", line)
        if heading:
            base = slug(heading.group(1))
            lines = []
            out[base + (f"-{seen[base]}" if base in seen else "")] = (heading.group(1), lines)
            seen[base] = seen.get(base, 0) + 1
        elif not fenced:
            lines.append(line)
    return out


def leads(line: str) -> list[str]:
    """The methods a line documents: those in a table row's first cell, or those opening a paragraph or list item."""
    if line.startswith("|"):
        cell = line.split("|")[1]
    else:
        lead = re.match(r"(?:- )?\**((?:`[^`]+`(?:,? and |, | / |/)?)+)", line)
        cell = lead.group(1) if lead else ""
    return re.findall(METHOD, cell)


def protocol_anchors(text: str) -> dict[str, list[str]]:
    """By method, the sections documenting it, best first: a heading naming it, its reference table rows, then the
    paragraphs it opens."""
    ranked: dict[str, list[tuple[int, str]]] = {}
    for anchor, (heading, lines) in sections(text).items():
        found = [(0, m) for m in re.findall(METHOD, heading)]
        found += [(1 if line.startswith("|") else 2, m) for line in lines for m in leads(line)]
        for rank, method in found:
            if (rank, anchor) not in ranked.setdefault(method, []):
                ranked[method].append((rank, anchor))
    return {method: [anchor for _, anchor in sorted(pairs, key=lambda p: p[0])] for method, pairs in ranked.items()}


def bar(counts: list[int]) -> str:
    total, cells, done = sum(counts), [], 0
    for symbol, cumulative in zip(SYMBOLS.values(), [sum(counts[: i + 1]) for i in range(len(counts))], strict=True):
        width = round(BAR * cumulative / total) - done
        cells.append(symbol * width)
        done += width
    return "".join(cells)


def percent(part: int, whole: int) -> str:
    return f"{round(100 * part / whole)}%"


def shown(host: str) -> str:
    version, platform = host.split()
    return f"{version} {PLATFORMS[platform]}"


def status_cell(standing: Standing, host: str) -> str:
    kind = status(standing, host)
    verified = host in standing.verified_on
    others = [shown(label) for label in standing.verified_on if label != host]
    text = f"{SYMBOLS[kind]} {LABELS[kind]}"
    if kind == "verified":
        text += f" on {shown(host)}"
    if standing.partial:
        text += f", verified on {shown(host)}" if verified else ", not yet verified"
        text += f": {standing.partial.missing}"
    if others:
        text += f" (also verified on {', '.join(others)})"
    return text


def how_cell(standing: Standing, anchors: dict[str, list[str]]) -> str:
    """The methods, each linked to its pinned section, else its section in this row's chapter, else its first."""
    chapter = standing.capability.id.split(".")[0] + "-"

    def link(name: str) -> str:
        candidates = anchors.get(name)
        if not candidates:
            return f"`{name}`"
        anchor = PINNED.get(name) or next((a for a in candidates if a.startswith(chapter)), candidates[0])
        return f"[`{name}`](../PROTOCOL.md#{anchor})"

    return ", ".join(link(name) for name in standing.methods)


def render(standings: list[Standing], host: str, anchors: dict[str, list[str]]) -> str:
    high = [s for s in standings if s.capability.tier == "high"]
    kinds = list(SYMBOLS)

    def count(rows: list[Standing]) -> list[int]:
        return [sum(status(s, host) == kind for s in rows) for kind in kinds]

    totals = count(high)
    version, platform = shown(host).split()
    lines = [
        "# What Bitwig Agent can do",
        "",
        "<!-- Generated by `pixi run capabilities` from src/bitwig_agent/protocol/capabilities.py and "
        "docs/verification/; do not edit by hand. -->",
        "",
        "Each row is a task from the [Bitwig User Guide](https://www.bitwig.com/userguide/latest/) and the methods "
        "that do it: call them as MCP tools by these names, or from the shell as `bitwig-agent <group> <verb>` "
        "(`tracks.duplicate` is `bitwig-agent tracks duplicate`). Each method links to its reference in PROTOCOL.md.",
        "",
        f"**Verified on Bitwig {version} ({platform}): {totals[0]} of {len(high)} common tasks "
        f"({percent(totals[0], len(high))}).**",
        "",
        "| Status | Common tasks |",
        "|---|---:|",
        *(f"| {SYMBOLS[kind]} {LABELS[kind]} | {total} |" for kind, total in zip(kinds, totals, strict=True)),
        "",
        f"- **{LABELS['verified']}**: tested end to end on a real Bitwig {version} on {platform}, "
        "and the methods have not changed since.",
        f"- **{LABELS['available']}**: the methods exist but have not been tried on a real Bitwig yet.",
        f"- **{LABELS['partial']}**: part of the task is available; the row says what is missing and whether the "
        "available part is verified.",
        f"- **{LABELS['none']}**: no method does it yet.",
        "",
        "Tasks marked † are less common and not counted above.",
        "",
        "## By chapter",
        "",
        f"| Chapter | Tasks | {' | '.join(SYMBOLS[kind] for kind in kinds)} | |",
        "|---|---:|---:|---:|---:|---:|---|",
    ]
    chapters = {prefix: [s for s in standings if s.capability.id.split(".")[0] == prefix] for prefix in CHAPTERS}
    for prefix, rows in chapters.items():
        counts = count([s for s in rows if s.capability.tier == "high"])
        lines.append(
            f"| [{CHAPTERS[prefix]}](#{slug(CHAPTERS[prefix])}) | {sum(counts)} | {' | '.join(map(str, counts))} | `{bar(counts)}` |"
        )
    for prefix, rows in chapters.items():
        lines += ["", f"## {CHAPTERS[prefix]}", "", "| Task | Status | How |", "|---|---|---|"]
        for s in rows:
            dagger = "" if s.capability.tier == "high" else " †"
            how = how_cell(s, anchors) or "—"
            lines.append(f"| {s.capability.title}{dagger} | {status_cell(s, host)} | {how} |")
    return "\n".join(lines) + "\n"


def gap_rows(gaps: list[Gap]) -> list[dict[str, object]]:
    return [
        {
            "id": gap.capability.id,
            "title": gap.capability.title,
            "tier": gap.capability.tier,
            "methods": list(gap.methods),
            "reason": gap.reason,
        }
        for gap in gaps
    ]


def summary(standings: list[Standing], host: str, records: int) -> dict[str, object]:
    """The headline counts, also weighted by the weighted task list, for maintainers."""
    high = [s for s in standings if s.capability.tier == "high"]
    return {
        "host": host,
        "records": records,
        "tasks": len(high),
        "weight": sum(s.capability.weight for s in high),
        **{
            kind: {
                "tasks": sum(status(s, host) == kind for s in high),
                "weight": sum(s.capability.weight for s in high if status(s, host) == kind),
            }
            for kind in SYMBOLS
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--to-verify", action="store_true", help="print the to-verify queue instead")
    parser.add_argument("--json", action="store_true", help="with --to-verify: as JSON")
    args = parser.parse_args()
    if args.json and not args.to_verify:
        parser.error("--json needs --to-verify")
    records = load_records()
    verdicts, host = Verdicts(records), headline_host()
    standings, gaps = verdicts.standings(), verdicts.gaps(host)
    counts = summary(standings, host, len(records))
    if args.json:
        print(json.dumps({"summary": counts, "capabilities": gap_rows(gaps)}, indent=2))
        return
    if not args.to_verify:
        MAP.write_text(
            render(standings, host, protocol_anchors(PROTOCOL.read_text(encoding="utf-8"))), encoding="utf-8"
        )
        print(f"Wrote {MAP.relative_to(ROOT)}")
    print(json.dumps(counts))
    print(f"To verify on {shown(host)}: {len(gaps)}")
    if args.to_verify:
        for gap in gaps:
            print(f"{gap.capability.id}\t{gap.capability.tier}\t{', '.join(gap.methods)}\t{gap.reason}")


if __name__ == "__main__":
    main()
