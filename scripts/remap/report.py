"""Markdown report of a symbol map: symbol names and jar hashes only, never local paths."""

from collections import Counter
from typing import Any

STATUSES = ("exact", "confident", "uncertain", "unmapped")


def render(result: dict[str, Any], notes: str = "") -> str:
    meta, symbols = result["meta"], result["symbols"]
    out = [
        "# Host-symbol map (generated)\n",
        f"- old jar sha256 `{meta['old_sha256']}`",
        f"- new jar sha256 `{meta['new_sha256']}`",
        f"- whole-jar class map: {meta['classes_mapped']} of {meta['classes_old']} old classes mapped "
        f"({meta['classes_new']} new classes)\n",
    ]
    if notes:
        out.append(notes.rstrip() + "\n")
    counts = Counter((v["kind"], v["status"]) for v in symbols.values())
    kinds = sorted({k for k, _ in counts})
    out += ["## Totals by status\n", "| kind | " + " | ".join(STATUSES) + " | total |", "|---|" + "---|" * 5]
    for k in kinds:
        row = [counts[k, s] for s in STATUSES]
        out.append(f"| {k} | " + " | ".join(map(str, row)) + f" | {sum(row)} |")
    totals = [sum(counts[k, s] for k in kinds) for s in STATUSES]
    out.append("| **all** | " + " | ".join(map(str, totals)) + f" | {len(symbols)} |\n")
    bodies = Counter(str(v.get("body")) for v in symbols.values() if v["kind"] not in ("class", "const"))
    out.append("Body comparison of mapped members: " + ", ".join(f"{k}: {n}" for k, n in sorted(bodies.items())) + "\n")
    out.append("## Non-exact symbols\n")
    for k, v in symbols.items():
        if v["status"] != "exact":
            line = f"- **{v['status']}** `{k}` -> `{v.get('new')}` — {v.get('evidence')}"
            line += f"; candidates {v['candidates']}" if v.get("candidates") else ""
            out.append(line + (f"; body {v['body']}" if v.get("body") else ""))
    out.append("\n## Changed bodies and masked-only matches\n")
    for k, v in symbols.items():
        if v.get("body") == "changed" or "masked" in v.get("body_note", ""):
            out.append(f"- `{k}` -> `{v.get('new')}` [{v.get('body')}]: {v.get('body_note', '')}")
    out.append("\n## Structural checks\n")
    out += [f"- {check}" for check in result["checks"]]
    return "\n".join(out) + "\n"
