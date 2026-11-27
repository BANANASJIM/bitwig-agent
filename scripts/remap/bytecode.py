"""Method bodies as comparable token lists, with names rewritten by caller-supplied functions."""

import re
import struct
from collections.abc import Callable
from typing import Any

from .classfile import (
    BRANCHES,
    FIELD_REF,
    INTERFACE_METHOD_REF,
    LENGTH,
    LOOKUPSWITCH,
    METHOD_REF,
    NAME_AND_TYPE,
    TABLESWITCH,
    WIDE_BRANCHES,
    code_body,
    instructions,
)

Rename = Callable[[str], str]
TYPE = re.compile(r"L([^;]+);")
CP_OPERAND_END = 3  # opcode + two-byte index; invokeinterface/invokedynamic/multianewarray carry more bytes


def types_of(desc: str) -> list[str]:
    return TYPE.findall(desc)


def rename_desc(rename: Rename) -> Rename:
    return lambda desc: TYPE.sub(lambda m: "L" + rename(m.group(1)) + ";", desc)


def constant(cp: list[Any], index: int, nc: Rename, nd: Rename, nm: Rename) -> str:
    """A constant-pool entry as text, class/descriptor/member names passed through nc/nd/nm."""
    entry = cp[index]
    match entry[0]:
        case "u":
            text = str(entry[1])
        case "c":
            name = cp[entry[1]][1]
            text = "C:" + (nd(name) if name.startswith("[") else nc(name))
        case "s":
            text = "S:" + cp[entry[1]][1]
        case "n":
            text = "N:" + entry[1].hex()
        case "mt":
            text = "MT:" + nd(cp[entry[1]][1])
        case "r":
            text = reference(cp, entry, nc, nd, nm)
        case kind:
            text = str(kind)
    return text


def reference(cp: list[Any], entry: tuple[str, int, tuple[int, int]], nc: Rename, nd: Rename, nm: Rename) -> str:
    _, tag, (a, b) = entry
    if tag == NAME_AND_TYPE:
        return nm(cp[a][1]) + ":" + nd(cp[b][1])
    if tag in (FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF):
        return f"R{tag}:" + nc(cp[cp[a][1]][1]) + "." + constant(cp, b, nc, nd, nm)
    return f"I{tag}:" + constant(cp, b, nc, nd, nm)


def tokens(code: bytes, cp: list[Any], nc: Rename, nd: Rename, nm: Rename) -> list[str]:
    """One token per instruction; offsets kept only where they are relative (branches)."""
    body = code_body(code)
    out = []
    for start, end, op, index in instructions(body):
        if op == TABLESWITCH:
            q = (start + 4) & ~3
            low, high = struct.unpack_from(">ii", body, q + 4)
            out.append(f"tableswitch {low} {high}")
        elif op == LOOKUPSWITCH:
            q = (start + 4) & ~3
            (pairs,) = struct.unpack_from(">i", body, q + 4)
            keys = [struct.unpack_from(">i", body, q + 8 + 8 * k)[0] for k in range(pairs)]
            out.append(f"lookupswitch {keys}")
        elif index is not None:
            extra = " " + body[start + CP_OPERAND_END : end].hex() if LENGTH[op] > CP_OPERAND_END else ""
            out.append(f"{op:02x} " + constant(cp, index, nc, nd, nm) + extra)
        elif op in BRANCHES:
            out.append(f"{op:02x} {struct.unpack_from('>h', body, start + 1)[0]}")
        elif op in WIDE_BRANCHES:
            out.append(f"{op:02x} {struct.unpack_from('>i', body, start + 1)[0]}")
        else:
            out.append(body[start:end].hex())
    return out


def constant_indexes(code: bytes) -> list[tuple[int, int | None]]:
    """(opcode, constant-pool index or None) per instruction."""
    return [(op, index) for _, _, op, index in instructions(code_body(code))]


def class_refs(code: bytes, cp: list[Any]) -> list[tuple[int, tuple[str, ...]]]:
    """(opcode, class names referenced by its constant, in order) per instruction."""
    out = []
    for op, index in constant_indexes(code):
        names: list[str] = []
        if index is not None:

            def collect(name: str, names: list[str] = names) -> str:
                names.append(name)
                return "#"

            def collect_desc(desc: str, names: list[str] = names) -> str:
                names.extend(types_of(desc))
                return "#"

            constant(cp, index, collect, collect_desc, lambda _: "#")
        out.append((op, tuple(names)))
    return out


def member_ref(cp: list[Any], index: int) -> tuple[str, str, str, str] | None:
    """('f' | 'm', owner, name, descriptor) for a field or method reference constant."""
    entry = cp[index]
    if entry is None or entry[0] != "r" or entry[1] not in (FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF):
        return None
    owner, name_and_type = entry[2]
    name, desc = cp[name_and_type][2]
    return ("f" if entry[1] == FIELD_REF else "m", cp[cp[owner][1]][1], cp[name][1], cp[desc][1])
