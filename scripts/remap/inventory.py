"""The bound host symbols to map, read from a harness log and a hand-written extra-symbol list.

Harness log (tab-separated; other lines ignored), names in Java form (`pkg.Cls`, `int`, `[Ljava.lang.String;`):
  LOG TYPE   owner
  LOG METHOD owner name return static params      params comma-separated; may be followed by
  LOG DECL   owner name declaring-class            the class the lookup resolved to
  LOG CTOR   owner params
  LOG CONST  owner field type
Extra symbols (tab-separated, `#` comments): kind owner name return static params why, where kind is TYPE,
GETMETHODS (check the public method set), CTOR, METHOD, OVERRIDE (a compile-time override) or DECLARED
(a getDeclaredMethod lookup).
"""

from dataclasses import dataclass
from pathlib import Path

from .bytecode import types_of
from .classmap import Jar

PRIMITIVES = {
    "void": "V",
    "boolean": "Z",
    "byte": "B",
    "char": "C",
    "short": "S",
    "int": "I",
    "long": "J",
    "float": "F",
    "double": "D",
}
CLASS_KINDS = ("TYPE", "GETMETHODS")


@dataclass(frozen=True)
class Symbol:
    kind: str
    owner: str  # internal name
    name: str = ""
    desc: str = ""
    src: str = ""
    declared_in: str | None = None  # logged declaring class of a METHOD lookup

    @property
    def key(self) -> tuple[str, str, str, str]:
        return self.kind, self.owner, self.name, self.desc


def internal(name: str) -> str:
    return name.replace(".", "/")


def field_desc(java: str) -> str:
    if not java:
        return ""
    if java.startswith("["):
        return internal(java)
    return PRIMITIVES.get(java) or f"L{internal(java)};"


def method_desc(ret: str, params: str) -> str:
    return "(" + "".join(field_desc(p) for p in params.split(",") if p) + ")" + field_desc(ret)


def column(row: list[str], index: int) -> str:
    return row[index] if len(row) > index else ""


def harness_symbols(lines: list[list[str]]) -> list[Symbol]:
    out = []
    for i, row in enumerate(lines):
        if row[0] != "LOG":
            continue
        kind, owner = row[1], internal(column(row, 2))
        if kind == "TYPE":
            out.append(Symbol("TYPE", owner, src="harness"))
        elif kind == "METHOD":
            decl = lines[i + 1] if i + 1 < len(lines) and lines[i + 1][1:2] == ["DECL"] else None
            desc = method_desc(row[4], column(row, 6))
            out.append(Symbol("METHOD", owner, row[3], desc, "harness", internal(decl[4]) if decl else None))
        elif kind == "CTOR":
            out.append(Symbol("CTOR", owner, "<init>", method_desc("void", column(row, 3)), "harness"))
        elif kind == "CONST":
            out.append(Symbol("CONST", owner, row[3], field_desc(row[4]), "harness"))
    return out


def extra_symbols(lines: list[list[str]]) -> list[Symbol]:
    out = []
    for row in lines:
        if row[0].startswith("#") or not "".join(row).strip():
            continue
        kind, owner, src = row[0], internal(row[1]), "source:" + column(row, 6)
        if kind in CLASS_KINDS:
            out.append(Symbol(kind, owner, src=src))
        elif kind == "CTOR":
            out.append(Symbol(kind, owner, "<init>", method_desc("void", row[5]), src))
        else:
            out.append(Symbol(kind, owner, row[2], method_desc(row[3], row[5]), src))
    return out


def rows(path: Path) -> list[list[str]]:
    return [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines()]


def load(harness_log: Path, extra: Path | None, old: Jar) -> list[Symbol]:
    """Deduplicated symbols; every jar class in a bound descriptor or owning a bound member is bound too."""
    seen: set[tuple[str, str, str, str]] = set()
    out: list[Symbol] = []

    def keep(symbol: Symbol) -> None:
        if symbol.key not in seen:
            out.append(symbol)
            seen.add(symbol.key)

    for symbol in harness_symbols(rows(harness_log)) + (extra_symbols(rows(extra)) if extra else []):
        keep(symbol)
    for symbol in out:
        for name in types_of(symbol.desc):
            if name in old:
                keep(Symbol("TYPE", name, src="descriptor"))
        if symbol.owner in old:
            keep(Symbol("TYPE", symbol.owner, src="owner"))
    return out
