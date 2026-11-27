"""Class-file reader: parses bytes only, never loads or runs a class (jars are untrusted input)."""

import hashlib
import struct
import zipfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

UTF8, INT, FLOAT, LONG, DOUBLE, CLASS, STRING = 1, 3, 4, 5, 6, 7, 8
FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF, NAME_AND_TYPE = 9, 10, 11, 12
METHOD_HANDLE, METHOD_TYPE, DYNAMIC, INVOKE_DYNAMIC, MODULE, PACKAGE = 15, 16, 17, 18, 19, 20
REFS = (FIELD_REF, METHOD_REF, INTERFACE_METHOD_REF, NAME_AND_TYPE, DYNAMIC, INVOKE_DYNAMIC)

ACC_PUBLIC, ACC_STATIC, ACC_FINAL, ACC_INTERFACE, ACC_ABSTRACT = 0x1, 0x8, 0x10, 0x200, 0x400

TABLESWITCH, LOOKUPSWITCH, WIDE, IINC = 0xAA, 0xAB, 0xC4, 0x84
WIDE_IINC_LENGTH, WIDE_LENGTH = 6, 4
# Instruction length in bytes (opcode included); every opcode not listed here is one byte long.
LENGTH = dict.fromkeys(range(256), 1)
LENGTH.update(dict.fromkeys((0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC), 2))
LENGTH.update(dict.fromkeys((0x11, 0x13, 0x14, 0x84, *range(0x99, 0xA9), *range(0xB2, 0xB9), 0xBB, 0xBD), 3))
LENGTH.update(dict.fromkeys((0xC0, 0xC1, 0xC6, 0xC7), 3))
LENGTH.update({0xB9: 5, 0xBA: 5, 0xC5: 4, 0xC8: 5, 0xC9: 5})
# Opcodes with a constant-pool operand -> width of that operand (ldc has a one-byte index).
CP_OPERAND = {0x12: 1} | dict.fromkeys((0x13, 0x14, *range(0xB2, 0xBC), 0xBD, 0xC0, 0xC1, 0xC5), 2)
BRANCHES = (*range(0x99, 0xA9), 0xC6, 0xC7)
WIDE_BRANCHES = (0xC8, 0xC9)


@dataclass(frozen=True)
class Member:
    access: int
    name: str
    desc: str
    code: bytes | None


@dataclass(frozen=True)
class ClassFile:
    access: int
    name: str
    super: str | None
    interfaces: list[str]
    fields: list[Member]
    methods: list[Member]
    strings: list[str]
    cp: list[Any]  # ('u', text) | ('n', bytes) | ('c', idx) | ('s', idx) | ('r', tag, (i, j)) | ('mt', idx) | (kind,)


class Reader:
    def __init__(self, data: bytes, pos: int = 0) -> None:
        self.data, self.pos = data, pos

    def take(self, fmt: str) -> tuple[Any, ...]:
        values = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += struct.calcsize(fmt)
        return values

    def u2(self) -> int:
        return int(self.take(">H")[0])

    def raw(self, size: int) -> bytes:
        if self.pos + size > len(self.data):
            raise ValueError("truncated class file")
        self.pos += size
        return self.data[self.pos - size : self.pos]


def constant_pool(r: Reader) -> list[Any]:
    count = r.u2()
    cp: list[Any] = [None] * count
    i = 1
    while i < count:
        tag = r.take(">B")[0]
        if tag == UTF8:
            cp[i] = ("u", r.raw(r.u2()).decode("utf-8", "replace"))
        elif tag in (INT, FLOAT):
            cp[i] = ("n", r.raw(4))
        elif tag in (LONG, DOUBLE):
            cp[i] = ("n", r.raw(8))
            i += 1
        elif tag in (CLASS, STRING, METHOD_TYPE):
            cp[i] = ({CLASS: "c", STRING: "s", METHOD_TYPE: "mt"}[tag], r.u2())
        elif tag in REFS:
            cp[i] = ("r", tag, r.take(">HH"))
        elif tag == METHOD_HANDLE:
            cp[i] = ("mh",)
            r.raw(3)
        elif tag in (MODULE, PACKAGE):
            cp[i] = ("mod",)
            r.raw(2)
        else:
            raise ValueError(f"unknown constant-pool tag {tag}")
        i += 1
    return cp


def members(r: Reader, utf8: Any) -> list[Member]:
    out = []
    for _ in range(r.u2()):
        access, name, desc, attributes = r.take(">HHHH")
        code = None
        for _ in range(attributes):
            attribute, size = r.take(">HI")
            payload = r.raw(size)
            if utf8(attribute) == "Code":
                code = payload
        out.append(Member(access, utf8(name), utf8(desc), code))
    return out


def parse(data: bytes) -> ClassFile:
    r = Reader(data, 8)
    cp = constant_pool(r)

    def utf8(index: int) -> str:
        return str(cp[index][1])

    access, this, sup = r.take(">HHH")
    interfaces = [utf8(cp[r.u2()][1]) for _ in range(r.u2())]
    fields = members(r, utf8)
    methods = members(r, utf8)
    strings = sorted({utf8(e[1]) for e in cp if e and e[0] == "s"})
    return ClassFile(
        access, utf8(cp[this][1]), utf8(cp[sup][1]) if sup else None, interfaces, fields, methods, strings, cp
    )


def read_jar(path: Path) -> dict[str, ClassFile]:
    """Every class in the jar by internal name, in archive order."""
    with zipfile.ZipFile(path) as jar:
        return {
            entry[:-6]: parse(jar.read(entry))
            for entry in jar.namelist()
            if entry.endswith(".class") and not entry.startswith("META-INF")
        }


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def code_body(code: bytes) -> bytes:
    """The bytecode of a Code attribute (after max_stack, max_locals and code_length)."""
    (length,) = struct.unpack_from(">I", code, 4)
    return code[8 : 8 + length]


def instructions(body: bytes) -> Iterator[tuple[int, int, int, int | None]]:
    """(start, end, opcode, constant-pool index or None) for each instruction of a method body."""
    p = 0
    while p < len(body):
        op, start, index = body[p], p, None
        if op == TABLESWITCH:
            q = (p + 4) & ~3
            low, high = struct.unpack_from(">ii", body, q + 4)
            p = q + 12 + 4 * (high - low + 1)
        elif op == LOOKUPSWITCH:
            q = (p + 4) & ~3
            (pairs,) = struct.unpack_from(">i", body, q + 4)
            p = q + 8 + 8 * pairs
        elif op == WIDE:
            p += WIDE_IINC_LENGTH if body[p + 1] == IINC else WIDE_LENGTH
        else:
            if op in CP_OPERAND:
                index = body[p + 1] if CP_OPERAND[op] == 1 else struct.unpack_from(">H", body, p + 1)[0]
            p += LENGTH[op]
        yield start, p, op, index
