"""Client-side `modulators.catalog` / `modulators.describe`: Bitwig's own modulator help, read at runtime.

Nothing from Bitwig's resource files is copied into this package. Names come from the installed
`<root>/Library/modulators/*.bwmodulator` files, help text from `<root>/resources/localization/`
(`Modulator-descriptions-resources` and `Modulators-resources`, the in-app "Show help" texts).
Live kinds, ranges and values come from the host via modulators.get, never from these files.
"""

from __future__ import annotations

import difflib
import os
import re
from pathlib import Path
from typing import Any

from bitwig_agent.grid_docs import LANGS, DocsUnavailable, _tokens, localization_dir, read_properties

GENERAL = "modulator_general"
# Display name -> extra Modulators-resources prefixes, only where the prefix is not `<id>` or `<id>-modulator`.
PREFIX_ALIASES: dict[str, tuple[str, ...]] = {
    "Relative Keytrack": ("keytrack-modulator",),
    "Stack Spread": ("voice_stack",),
    "Voice Control": ("voice_stack",),
}
LIVE_FACTS = "kind, value, range and options of each control: read them live with modulators.get (parameters rows)"


DOCUMENTED_STATUS = "documented_display_values_unverified_against_raw"
_DOCUMENTED = re.compile(r"['\u2018\u2019]([-+\u2212]?\d+(?:\.\d+)?)['\u2018\u2019]\s*=\s*([^\n•]+)")


def documented_values(text: str) -> list[dict[str, str]]:
    """`'-3.00' = 8 beats` pairs Bitwig's help lists for a control (display text, not verified raw values)."""
    return [
        {"display": value.replace("\u2212", "-"), "meaning": meaning.strip()}
        for value, meaning in _DOCUMENTED.findall(text)
    ]


def resource_id(name: str) -> str:
    return name.casefold().replace(" ", "_")


def category_name(category: str) -> str:
    words = category.replace("_", " ").split(" ")
    return " ".join("LFO" if w == "lfo" else (w[:1].upper() + w[1:] if i == 0 else w) for i, w in enumerate(words))


class ModulatorDocs:
    def __init__(self, directory: Path, lang: str):
        def load(stem: str) -> dict[str, str]:
            base = directory / f"{stem}.properties"
            merged = read_properties(base) if base.is_file() else {}
            localized = directory / f"{stem}_{lang}.properties"
            if lang != "en" and localized.is_file():
                merged = {**merged, **read_properties(localized)}
            return merged

        base = directory / "Modulator-descriptions-resources.properties"
        if not base.is_file():
            raise DocsUnavailable(f"{base} not found; this Bitwig install has no modulator help resources")
        self.descriptions = load("Modulator-descriptions-resources")
        self.entries = load("Modulators-resources")
        # Categories are the `#<category>.` comment headers that precede each group in the English file.
        self.category: dict[str, str] = {}
        current = None
        for line in base.read_text(encoding="utf-8", errors="replace").lstrip("﻿").splitlines():
            header = re.fullmatch(r"#([a-z_\-]+)\.\s*", line.strip())
            if header:
                current = header.group(1)
            elif line.startswith("modulator.") and ".desc=" in line and current:
                self.category[line[len("modulator.") : line.index(".desc=")]] = current
        self.prefixes: dict[str, dict[str, str]] = {}
        for key, value in self.entries.items():
            prefix, _, rest = key.partition(".")
            if rest:
                self.prefixes.setdefault(prefix, {})[rest] = value
        library = directory.parent.parent / "Library" / "modulators"
        names = sorted(p.stem for p in library.glob("*.bwmodulator")) if library.is_dir() else []
        ids = [
            key[len("modulator.") : -len(".desc")]
            for key in self.descriptions
            if key.startswith("modulator.") and key.endswith(".desc")
        ]
        known = {resource_id(n) for n in names}
        # Without the Library folder, fall back to resource ids so search still works (insert needs a real name).
        self.names = names + [rid for rid in ids if rid not in known]

    def entry(self, name: str) -> dict[str, Any]:
        rid = resource_id(name)
        row: dict[str, Any] = {"name": name}
        description = self.descriptions.get(f"modulator.{rid}.desc")
        if description:
            row["description"] = description
        category = self.category.get(rid)
        if category:
            row["category"] = category_name(category)
        keywords = self.descriptions.get(f"modulator.{rid}.keywords", "").split()
        if keywords:
            row["keywords"] = keywords
        return row

    def controls(self, name: str) -> tuple[list[str], list[dict[str, Any]]]:
        rid = resource_id(name)
        prefixes = [p for p in (rid, f"{rid}-modulator", *PREFIX_ALIASES.get(name, ())) if p in self.prefixes]
        merged: dict[str, dict[str, Any]] = {}
        for prefix in prefixes:
            for rest, text in self.prefixes[prefix].items():
                key, _, field = rest.rpartition(".")
                if field in ("title", "desc") and key:
                    row = merged.setdefault(key, {"docKey": key})
                    row["title" if field == "title" else "description"] = text
                    documented = documented_values(text) if field == "desc" else []
                    if documented:
                        row["documentedValues"] = documented
                        row["documentedValuesStatus"] = DOCUMENTED_STATUS
        return prefixes, list(merged.values())


_cache: dict[tuple[str, str], ModulatorDocs] = {}


def get_docs(root: str | os.PathLike[str] | None = None, lang: str = "en") -> ModulatorDocs:
    if lang not in LANGS:
        raise ValueError(f"lang must be one of {', '.join(LANGS)}")
    directory = localization_dir(root)
    key = (str(directory), lang)
    if key not in _cache:
        _cache[key] = ModulatorDocs(directory, lang)
    return _cache[key]


def search(docs: ModulatorDocs, query: str) -> list[dict[str, Any]]:
    terms = _tokens(query)
    if not terms:
        raise ValueError("query needs at least one word")
    ranked = []
    for name in docs.names:
        row = docs.entry(name)
        fields = {
            "category": (6, _tokens(row.get("category", ""))),
            "name": (5, _tokens(name)),
            "keywords": (3, set(map(str.casefold, row.get("keywords", ())))),
            "description": (2, _tokens(row.get("description", ""))),
        }
        score = 0.0
        for term in terms:
            for weight, tokens in fields.values():
                if term in tokens:
                    score += weight
                elif any(t.startswith(term) for t in tokens):
                    score += weight / 2
        if score:
            ranked.append({**row, "score": score})
    ranked.sort(key=lambda r: (-r["score"], r["name"]))
    return ranked


def resolve(docs: ModulatorDocs, value: str) -> str:
    for name in docs.names:
        if name == value or name.casefold() == value.casefold() or resource_id(name) == resource_id(value):
            return name
    close = difflib.get_close_matches(value, docs.names, n=5, cutoff=0.5)
    raise ValueError(
        f"Unknown modulator {value!r}" + (f"; did you mean {close}?" if close else "; use query to search")
    )


def catalog(args: dict[str, Any]) -> dict[str, Any]:
    docs = get_docs(args.get("installationRoot"), args.get("lang", "en"))
    rows = search(docs, args["query"]) if "query" in args else [docs.entry(n) for n in docs.names]
    for row in rows:
        row.pop("keywords", None)
    return {
        "modulators": rows,
        "total": len(rows),
        "next": "modulators.describe type=<name> for controls; modulators.insert typeName=<name> to add one",
    }


def describe(args: dict[str, Any]) -> dict[str, Any]:
    docs = get_docs(args.get("installationRoot"), args.get("lang", "en"))
    if "query" in args:
        return {
            "query": args["query"],
            "matches": search(docs, args["query"])[:10],
            "next": "modulators.describe type=<name> for controls",
        }
    name = resolve(docs, args["type"])
    prefixes, controls = docs.controls(name)
    general = [
        {"docKey": key.rpartition(".")[0], "description": text}
        for key, text in docs.prefixes.get(GENERAL, {}).items()
        if key.endswith(".desc")
    ]
    result = {
        **docs.entry(name),
        "controls": controls,
        "sharedControls": general,
        "liveFacts": LIVE_FACTS,
        "docKeys": "docKey is Bitwig's help key; match it to a live parameterId or outputId case-insensitively (best effort, unverified)",
    }
    if not controls:
        result["note"] = "No per-control help entries map to this modulator."
    result["resourcePrefixes"] = prefixes
    return result
