"""Client-side `grid.describe`: Bitwig's own Grid module documentation, read at runtime from the local install.

Nothing from Bitwig's resource files is copied into this package; they are parsed on demand from
`<installationRoot>/resources/localization/`.
"""

from __future__ import annotations

import difflib
import os
import re
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any, TypeVar

from bitwig_agent.grid import raw_scales
from bitwig_agent.grid.bundled import bundled_schema
from bitwig_agent.grid.display import SECONDS_PARAMS
from bitwig_agent.grid.names import resolve

DEFAULT_ROOTS = ("/opt/bitwig-studio",)
LANGS = ("en", "de", "es", "fr", "ja", "ko", "zh")
_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "f": "\f"}

# Module display name -> resource id, only for names that do not normalize to their id.
ID_OVERRIDES: dict[str, str] = {}
# Module display name -> extra Modules-resources key prefixes holding its port/parameter entries.
PREFIX_ALIASES: dict[str, tuple[str, ...]] = {
    "AD": ("ad_module",),
    "Array": ("array_module",),
    "Curves": ("curves-module",),
    "Key Quantize": ("by_scale",),
    "Semitone Quantize": ("by_semitone",),
    "Logic Delay": ("gate_delay",),
    "Level": ("level_module",),
    "Pitch": ("pitch_module",),
    "Ratio": ("ratio_module",),
    "Segments": ("segments-module",),
    "Slopes": ("slopes-module",),
    "Transfer": ("transfer-module",),
    "Voice Stack Info": ("voice_info",),
    "Voice Stack Mix": ("voice_mix",),
    "Voice Stack Tog": ("voice_tog",),
    "Slope ↘": ("slope_↘\ufe0e",),
    "Sawtooth": ("saw",),
    "Ø Saw": ("saw",),
    "Crossover-2": ("crossover",),
    "Crossover-3": ("crossover",),
    "Pitch → Freq": ("pitch→freq",),
    "Freq → Pitch": ("freq→pitch",),
    "Poly → Mono": ("poly→mono",),
}


MIN_PLURAL_STEM_LENGTH = 3


class DocsUnavailable(ValueError):
    """The Bitwig resource files cannot be found or read."""


def parse_properties(text: str) -> dict[str, str]:
    """Parse Java .properties text: comments, `=`/`:`/space separators, continuations, \\n and \\uXXXX escapes."""
    result: dict[str, str] = {}
    lines = text.lstrip("\ufeff").splitlines()
    i = 0
    while i < len(lines):
        line = lines[i].lstrip(" \t\f")
        i += 1
        if not line or line[0] in "#!":
            continue
        while _odd_trailing_backslashes(line) and i < len(lines):
            line = line[:-1] + lines[i].lstrip(" \t\f")
            i += 1
        if _odd_trailing_backslashes(line):
            line = line[:-1]
        key, value = _split_entry(line)
        result[_unescape(key)] = _unescape(value)
    return result


def _odd_trailing_backslashes(line: str) -> bool:
    return (len(line) - len(line.rstrip("\\"))) % 2 == 1


def _split_entry(line: str) -> tuple[str, str]:
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "\\":
            i += 2
            continue
        if ch in "=: \t\f":
            break
        i += 1
    key, rest = line[:i], line[i:].lstrip(" \t\f")
    if rest[:1] in ("=", ":"):
        rest = rest[1:].lstrip(" \t\f")
    return key, rest


def _unescape(text: str) -> str:
    if "\\" not in text:
        return text
    out: list[str] = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch != "\\" or i + 1 >= len(text):
            out.append(ch)
            i += 1
            continue
        nxt = text[i + 1]
        if nxt == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", text[i + 2 : i + 6]):
            out.append(chr(int(text[i + 2 : i + 6], 16)))
            i += 6
            continue
        out.append(_ESCAPES.get(nxt, nxt))
        i += 2
    joined = "".join(out)
    if any("\ud800" <= c <= "\udfff" for c in joined):  # 😀 surrogate pairs
        joined = joined.encode("utf-16", "surrogatepass").decode("utf-16", "replace")
    return joined


def read_properties(path: Path) -> dict[str, str]:
    data = path.read_bytes()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:  # classic .properties are Latin-1
        text = data.decode("latin-1")
    return parse_properties(text)


def load_localized(directory: Path, stem: str, lang: str) -> dict[str, str]:
    base = directory / f"{stem}.properties"
    merged = read_properties(base) if base.is_file() else {}
    localized = directory / f"{stem}_{lang}.properties"
    if lang != "en" and localized.is_file():
        merged = {**merged, **read_properties(localized)}
    return merged


def localization_dir(root: str | os.PathLike[str] | None = None) -> Path:
    """Explicit root, else BITWIG_INSTALLATION_ROOT, else the install holding BITWIG_API_JAR, else known defaults."""
    candidates: list[Path] = []
    if root:
        candidates.append(Path(root))
    elif os.environ.get("BITWIG_INSTALLATION_ROOT"):
        candidates.append(Path(os.environ["BITWIG_INSTALLATION_ROOT"]))
    else:
        jar = os.environ.get("BITWIG_API_JAR")
        if jar:
            candidates.append(Path(jar).resolve().parent.parent)  # <root>/bin/bitwig.jar
        candidates += [Path(p) for p in DEFAULT_ROOTS]
        if sys.platform == "darwin":
            candidates.append(Path("/Applications/Bitwig Studio.app/Contents/Resources"))
    for path in candidates:
        for directory in (path / "resources" / "localization", path / "localization"):
            if (directory / "Module-descriptions-resources.properties").is_file():
                return directory
    raise DocsUnavailable(
        "Bitwig localization resources not found; pass installationRoot or set BITWIG_INSTALLATION_ROOT "
        "(the Bitwig install directory that contains resources/localization)"
    )


def group_by_prefix(entries: dict[str, str]) -> dict[str, dict[str, str]]:
    grouped: dict[str, dict[str, str]] = {}
    for key, value in entries.items():
        prefix, _, rest = key.partition(".")
        if rest:
            grouped.setdefault(prefix, {})[rest] = value
    return grouped


def norm(text: str) -> str:
    return re.sub(r"[\s_\ufe0e\ufe0f\u200b]", "", text.casefold())


class Docs:
    def __init__(self, directory: Path, lang: str):
        self.descriptions = load_localized(directory, "Module-descriptions-resources", lang)
        self.entries = load_localized(directory, "Modules-resources", lang)
        self.ids: dict[str, str] = {}  # resource id -> its `.desc` key
        self.id_category: dict[str, str | None] = {}
        category = None
        for key in self.descriptions:  # file order: a category header precedes its modules
            if key.startswith("module-category.") and key.endswith(".desc"):
                category = key[len("module-category.") : -len(".desc")]
            elif key.startswith("module.") and key.endswith(".desc"):
                rid = key[len("module.") : -len(".desc")]
                self.ids[rid] = key
                self.id_category[rid] = category
        self.prefixes = group_by_prefix(self.entries)
        self.by_norm = {norm(r): r for r in self.ids}
        self.by_prefix_norm: dict[str, list[str]] = {}
        for prefix in self.prefixes:
            self.by_prefix_norm.setdefault(norm(prefix), []).append(prefix)

    def resource_id(self, name: str) -> str | None:
        rid = ID_OVERRIDES.get(name) or self.by_norm.get(norm(name))
        return rid if rid in self.ids else None

    def prefixes_for(self, candidates: list[str]) -> list[str]:
        found: list[str] = []
        for cand in candidates:
            for prefix in self.by_prefix_norm.get(norm(cand), []):
                if prefix not in found:
                    found.append(prefix)
        return found


_T = TypeVar("_T")
_cache: dict[tuple[str, str], Docs] = {}


def cached_docs(
    cache: dict[tuple[str, str], _T],
    build: Callable[[Path, str], _T],
    root: str | os.PathLike[str] | None,
    lang: str,
) -> _T:
    if lang not in LANGS:
        raise ValueError(f"lang must be one of {', '.join(LANGS)}")
    directory = localization_dir(root)
    key = (str(directory), lang)
    if key not in cache:
        cache[key] = build(directory, lang)
    return cache[key]


def get_docs(root: str | os.PathLike[str] | None = None, lang: str = "en") -> Docs:
    return cached_docs(_cache, Docs, root, lang)


def _tokens(text: str) -> set[str]:
    return set(re.findall(r"[^\W_]+", text.casefold()))


def _lookup(entries: dict[str, str], names: list[str]) -> str | None:
    """The `<rest>` of a `<rest>.desc` entry whose normalized form equals one of the names."""
    for want in (norm(n) for n in names if n):
        for rest in entries:
            if rest.endswith(".desc") and norm(rest[:-5]) == want:
                return rest
    return None


def _candidate_names(key: str, name: str) -> list[str]:
    name = (name or "").replace("\u200b", "")
    words = re.findall(r"[^\W_]+", name.casefold())
    names = [
        key,
        re.sub(r"\d+$", "", key),
        name,
        re.sub(r"\s*\d+$", "", name),
    ]  # `Signal Out 2` -> family entry `signal_out`
    if len(words) > 1 and words[-1] in ("in", "out"):
        names.append("".join(words[:-1]))  # `Trigger In` -> `trigger`
    return names


def _stem(word: str) -> str:
    return word[:-1] if len(word) > MIN_PLURAL_STEM_LENGTH and word.endswith("s") else word


PARAM_FACTS = ("kind", "numericUnit", "minimum", "maximum", "default", "defaultDisplay", "options")


def raw_scale(type_name: str, pid: str) -> dict[str, Any] | None:
    """How a parameter's raw value maps to its display: client-side table, then the seconds scales."""
    block = raw_scales.describe(type_name, pid)
    if block is None and (type_name, pid) in SECONDS_PARAMS:
        mapping, raw, display = SECONDS_PARAMS[(type_name, pid)]
        formula = {"cbrt": "raw = cbrt(seconds)", "log10": "raw = log10(seconds)"}[mapping]
        block = {
            "scale": f"{mapping}_seconds",
            "formula": formula,
            "status": "verified",
            "units": ["ms", "s"],
            "evidence": [{"raw": raw, "display": display}],
            "displayWrites": True,
        }
    return block


def parameter_facts(type_name: str, param: dict[str, Any]) -> dict[str, Any]:
    """Range, default, enum options and raw scale from the bundled schema (absent keys are omitted)."""
    facts = {k: param[k] for k in PARAM_FACTS if param.get(k) is not None}
    scale = raw_scale(type_name, param["parameterId"])
    if scale:
        facts["rawScale"] = scale
    return facts


def _port_and_param_items(
    name: str, info: dict[str, Any]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    items: list[dict[str, Any]] = []  # ports then parameters, each: row, key, name, direction
    for direction, group in (("in", info.get("inputs", ())), ("out", info.get("outputs", ()))):
        for port in group:
            row = {"key": port["key"], "name": port["name"].replace("\u200b", ""), "direction": direction}
            items.append(
                {
                    "row": row,
                    "key": port["key"],
                    "name": port["name"],
                    "direction": direction,
                    "single": len(group) == 1,
                }
            )
    ports = [i["row"] for i in items]
    params = []
    for param in info.get("parameters", ()):
        row = {"id": param["parameterId"], "name": param["name"], **parameter_facts(name, param)}
        items.append(
            {"row": row, "key": param["parameterId"], "name": param["name"], "direction": None, "single": False}
        )
        params.append(row)
    return items, ports, params


def _pair_leftover(rest: str, text: str, items: list[dict[str, Any]]) -> bool:
    stem = rest[:-5]
    wanted_direction: str | None = "in" if stem.endswith("_in") else "out" if stem.endswith("_out") else None
    words = {_stem(w) for w in re.findall(r"[^\W_]+", stem.casefold())} - {"in", "out"}
    fits = [
        i
        for i in items
        if "description" not in i["row"]
        and (wanted_direction is None or i["direction"] in (wanted_direction, None))
        and words
        and words <= {_stem(w) for w in re.findall(r"[^\W_]+", (i["name"] or "").casefold())}
    ]
    if wanted_direction and not fits:  # `logic_in` for the lone undescribed input
        fits = [i for i in items if "description" not in i["row"] and i["direction"] == wanted_direction]
    if len(fits) == 1:
        fits[0]["row"]["description"] = text
        return True
    return False


def _attach_descriptions(items: list[dict[str, Any]], lookup: dict[str, str], own: dict[str, str]) -> None:
    claimed: set[str] = set()
    for item in items:
        rest = _lookup(lookup, _candidate_names(item["key"], item["name"]))
        if rest is None and item["single"]:
            rest = _lookup(lookup, [item["direction"]])
        if rest:
            item["row"]["description"] = lookup[rest]
            claimed.add(rest)
    # Leftover module-specific entries: pair them with an undescribed item when exactly one fits.
    for rest in [r for r in own if r.endswith(".desc") and r not in claimed]:
        if _pair_leftover(rest, own[rest], items):
            claimed.add(rest)


def _identity(docs: Docs, name: str, info: dict[str, Any], rid: str | None, category: str | None) -> dict[str, Any]:
    result: dict[str, Any] = {"name": name, "typeId": info["typeId"], "resourceId": rid}
    if rid:
        result["description"] = docs.descriptions[docs.ids[rid]]
        keywords = docs.descriptions.get(f"module.{rid}.keywords", "").split()
        if keywords:
            result["keywords"] = keywords
        revisions = {k: v for k, v in docs.descriptions.items() if k.startswith(f"module.{rid}.revision")}
        if revisions:
            result["revisions"] = revisions
    if category:
        result["category"] = category
        categoryDescription = docs.descriptions.get(f"module-category.{category}.desc")
        if categoryDescription:
            result["categoryDescription"] = categoryDescription
    if "width" in info:
        result["footprint"] = {"width": info["width"], "height": info["height"]}
    return result


def describe_module(docs: Docs, name: str, info: dict[str, Any]) -> dict[str, Any]:
    rid = docs.resource_id(name)
    prefixes = docs.prefixes_for(([rid] if rid else []) + list(PREFIX_ALIASES.get(name, ())))
    category = docs.id_category.get(rid) if rid else None
    own = {rest: text for prefix in prefixes for rest, text in docs.prefixes[prefix].items()}
    # category-wide entries (e.g. `random.chance.desc`) describe shared items; module entries take precedence
    shared: dict[str, str] = {}
    if category:
        for prefix in docs.prefixes_for([category]):
            if prefix not in prefixes:
                shared.update(docs.prefixes[prefix])
    items, ports, params = _port_and_param_items(name, info)
    _attach_descriptions(items, {**shared, **own}, own)
    result = _identity(docs, name, info, rid, category)
    result["ports"] = ports
    result["parameters"] = params
    result["rawEntries"] = {
        f"{prefix}.{rest}": text for prefix in prefixes for rest, text in docs.prefixes[prefix].items()
    }
    if rid is None:
        result["note"] = "No Bitwig documentation entry maps to this module."
    return result


def resolve_type(modules: dict[str, Any], value: str) -> str:
    by_id = [name for name, info in modules.items() if info["typeId"].casefold() == value.casefold()]
    if by_id:
        return by_id[0]
    matches = resolve(modules, str, value)
    if len(matches) == 1:
        return matches[0]
    if matches:
        raise ValueError(f"Grid module {value!r} is ambiguous: {matches}; use the exact name or typeId")
    close = difflib.get_close_matches(value, list(modules), n=5, cutoff=0.5)
    raise ValueError(
        f"Unknown Grid module {value!r}" + (f"; did you mean {close}?" if close else "; use query to search")
    )


def _score(terms: set[str], name: str, desc: str, keywords: str, category: str | None) -> float:
    name_t, desc_t, kw_t, cat_t = _tokens(name), _tokens(desc), _tokens(keywords), _tokens(category or "")
    score = 0.0
    for term in terms:
        if term in cat_t:
            score += 6
        if term in name_t:
            score += 5
        elif term in norm(name):
            score += 3
        if term in kw_t:
            score += 3
        elif any(t.startswith(term) for t in kw_t):
            score += 1.5
        if term in desc_t:
            score += 2
        elif any(t.startswith(term) for t in desc_t):
            score += 1
    return score


def search(docs: Docs, modules: dict[str, Any], query: str, limit: int) -> list[dict[str, Any]]:
    terms = _tokens(query)
    if not terms:
        raise ValueError("query needs at least one word")
    ranked = []
    for name, info in modules.items():
        rid = docs.resource_id(name)
        desc = docs.descriptions.get(docs.ids[rid], "") if rid else ""
        kw = docs.descriptions.get(f"module.{rid}.keywords", "") if rid else ""
        category = docs.id_category.get(rid) if rid else None
        score = _score(terms, name, desc, kw, category)
        if score:
            row: dict[str, Any] = {"name": name, "typeId": info["typeId"], "score": score}
            if category:
                row["category"] = category
            if desc:
                row["description"] = desc
            ranked.append(row)
    ranked.sort(key=lambda r: (-r["score"], r["name"]))
    return ranked[:limit]


def mapping_report(root: str | os.PathLike[str] | None = None, lang: str = "en") -> dict[str, Any]:
    docs = get_docs(root, lang)
    modules = bundled_schema()["modules"]
    unmapped = [n for n in modules if docs.resource_id(n) is None]
    return {"modules": len(modules), "mapped": len(modules) - len(unmapped), "unmapped": unmapped}


def describe(args: dict[str, Any]) -> dict[str, Any]:
    """Tool entry point; `args` is the validated GridDescribeArgs dump."""
    docs = get_docs(args.get("installationRoot"), args.get("lang", "en"))
    modules = bundled_schema()["modules"]
    if "type" in args:
        name = resolve_type(modules, args["type"])
        return describe_module(docs, name, modules[name])
    return {
        "query": args["query"],
        "matches": search(docs, modules, args["query"], args.get("limit", 10)),
        "next": "grid.describe type=<name> for ports and parameters",
    }
