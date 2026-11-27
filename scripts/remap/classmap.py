"""Whole-jar class map: stable names, obfuscation-invariant fingerprints and neighbour propagation."""

import hashlib
import re
from collections import Counter, defaultdict

from .bytecode import Rename, class_refs, rename_desc, types_of
from .classfile import ACC_STATIC, ClassFile, Member

Jar = dict[str, ClassFile]
# The obfuscator emits names of at most three characters and pads Windows-reserved ones (aux -> auxx, con -> conn).
RESERVED_PADDED = re.compile(r"(?i)(aux|con|nul|prn)[a-z]")
OBFUSCATED_LENGTH = 3
PLAIN_SHORT_NAMES = frozenset(
    [
        "get",
        "set",
        "run",
        "add",
        "put",
        "max",
        "min",
        "abs",
        "end",
        "map",
        "key",
        "of",
        "is",
        "has",
        "len",
        "pow",
        "sin",
        "cos",
        "tan",
        "log",
        "exp",
        "val",
        "apply",
        "accept",
        "test",
        "call",
        "size",
    ]
)
LOOSE_LEVEL, STRUCTURE, STRINGS, CODE_SIZES = 0, 1, 2, 3  # each fingerprint level adds to the one below
FINGERPRINT_LEVELS = (CODE_SIZES, STRINGS, STRUCTURE)  # LOOSE_LEVEL is weaker evidence, tried once the rest settled
AGREEMENT = 0.9
MIN_VOTES = 2
SUPER_VOTE, INTERFACE_VOTE, DESCRIPTOR_VOTE, CODE_VOTE = 3, 2, 2, 1
MEMBER_FLAGS = 0x5F
CLASS_FLAGS, FIELD_FLAGS, METHOD_FLAGS = 0x7631, 0x1F, 0x53F


def is_obfuscated_class(name: str) -> bool:
    outer = name.rsplit("/", 1)[-1].split("$", maxsplit=1)[0]
    return len(outer) <= OBFUSCATED_LENGTH or bool(RESERVED_PADDED.fullmatch(outer))


def is_obfuscated_member(name: str) -> bool:
    return len(name) <= OBFUSCATED_LENGTH and name not in PLAIN_SHORT_NAMES


def mask_member(name: str) -> str:
    return "?" if is_obfuscated_member(name) else name


class ClassMap:
    """old class name -> new class name, with the evidence for each pair."""

    def __init__(self, old: Jar, new: Jar) -> None:
        self.old, self.new = old, new
        stable = [n for n in old if n in new and not is_obfuscated_class(n)]
        self.forward = {n: n for n in stable}
        self.backward = dict(self.forward)
        self.evidence = dict.fromkeys(stable, "stable-name")
        self.old_desc = rename_desc(self.old_token)
        self.new_desc = rename_desc(self.new_token)

    def old_token(self, name: str) -> str:
        """Mapped classes compare by their new name, unmapped jar classes are wildcards, JDK classes stay."""
        if name in self.forward:
            return "K:" + self.forward[name]
        return "?" if name in self.old else name

    def new_token(self, name: str) -> str:
        if name in self.backward:
            return "K:" + name
        return "?" if name in self.new else name

    def translate(self, desc: str) -> str:
        """An old descriptor in new names; unmapped jar classes become '!old'."""

        def rename(name: str) -> str:
            if name in self.forward:
                return self.forward[name]
            return "!" + name if name in self.old else name

        return rename_desc(rename)(desc)

    def add(self, old: str, new: str, evidence: str) -> bool:
        if old in self.forward or new in self.backward:
            return False
        self.forward[old], self.backward[new], self.evidence[old] = new, old, evidence
        return True

    def status(self, old: str) -> str:
        evidence = self.evidence.get(old, "")
        if evidence in ("stable-name", "fingerprint-L3", "fingerprint-L2"):
            return "exact"
        if evidence == "fingerprint-L1" or evidence.startswith("propagation"):
            return "confident"
        return "uncertain" if evidence == f"fingerprint-L{LOOSE_LEVEL}" else "unmapped"


def fingerprint(c: ClassFile, nc: Rename, nd: Rename, level: int) -> str:
    parts = [nc(c.super or ""), ",".join(sorted(nc(i) for i in c.interfaces))]
    if level >= STRUCTURE:
        parts.append(str(c.access & CLASS_FLAGS))
        parts.append("|".join(sorted(f"{f.access & FIELD_FLAGS}:{mask_member(f.name)}:{nd(f.desc)}" for f in c.fields)))
    flags = METHOD_FLAGS if level >= STRUCTURE else ACC_STATIC
    parts.append("|".join(sorted(f"{m.access & flags}:{mask_member(m.name)}:{nd(m.desc)}" for m in c.methods)))
    if level >= STRINGS:
        parts.append("|".join(c.strings))
    if level >= CODE_SIZES:
        sizes = ",".join(sorted(str(len(m.code or b"")) for m in c.methods))
        parts.append(f"{len(c.methods)}:{sizes}")
    return hashlib.sha1("\n".join(parts).encode()).hexdigest()


def fingerprint_round(cm: ClassMap, levels: tuple[int, ...]) -> int:
    """Pair classes whose fingerprint is unique on both sides."""
    found = 0
    for level in levels:
        old: defaultdict[str, list[str]] = defaultdict(list)
        new: defaultdict[str, list[str]] = defaultdict(list)
        for n in cm.old:
            if n not in cm.forward:
                old[fingerprint(cm.old[n], cm.old_token, cm.old_desc, level)].append(n)
        for n in cm.new:
            if n not in cm.backward:
                new[fingerprint(cm.new[n], cm.new_token, cm.new_desc, level)].append(n)
        for key, olds in old.items():
            news = new.get(key)
            if len(olds) == 1 and news and len(news) == 1:
                found += cm.add(olds[0], news[0], f"fingerprint-L{level}")
    return found


def neighbour_votes(cm: ClassMap, a: str, b: str, votes: defaultdict[str, Counter[str]]) -> None:
    """Votes for unmapped classes that a mapped pair references in the same position."""
    ca, cb = cm.old[a], cm.new[b]

    def vote(x: str, y: str, weight: int) -> None:
        if x in cm.old and y in cm.new and x not in cm.forward and y not in cm.backward:
            votes[x][y] += weight

    if ca.super and cb.super:
        vote(ca.super, cb.super, SUPER_VOTE)
    if len(ca.interfaces) == len(cb.interfaces):
        for x, y in zip(ca.interfaces, cb.interfaces, strict=True):
            vote(x, y, INTERFACE_VOTE)
    for kind in ("fields", "methods"):
        for ma, mb in unique_member_pairs(cm, getattr(ca, kind), getattr(cb, kind)):
            for x, y in zip(types_of(ma.desc), types_of(mb.desc), strict=False):
                vote(x, y, DESCRIPTOR_VOTE)
            if kind == "methods" and ma.code and mb.code:
                for x, y in aligned_class_refs(class_refs(ma.code, ca.cp), class_refs(mb.code, cb.cp)):
                    vote(x, y, CODE_VOTE)


def aligned_class_refs(
    ra: list[tuple[int, tuple[str, ...]]], rb: list[tuple[int, tuple[str, ...]]]
) -> list[tuple[str, str]]:
    """Class names referenced at the same position of two bodies with the same opcode sequence."""
    if [op for op, _ in ra] != [op for op, _ in rb]:
        return []
    return [pair for (_, xs), (_, ys) in zip(ra, rb, strict=True) for pair in zip(xs, ys, strict=False)]


def unique_member_pairs(cm: ClassMap, old: list[Member], new: list[Member]) -> list[tuple[Member, Member]]:
    """Members whose (flags, plain name, descriptor in known names) is unique on both sides."""
    old_groups: defaultdict[tuple[int, str, str], list[Member]] = defaultdict(list)
    new_groups: defaultdict[tuple[int, str, str], list[Member]] = defaultdict(list)
    for m in old:
        old_groups[m.access & MEMBER_FLAGS, mask_member(m.name), cm.old_desc(m.desc)].append(m)
    for m in new:
        new_groups[m.access & MEMBER_FLAGS, mask_member(m.name), cm.new_desc(m.desc)].append(m)
    return [
        (olds[0], new_groups[key][0])
        for key, olds in old_groups.items()
        if len(olds) == 1 and len(new_groups.get(key, [])) == 1
    ]


def propagate(cm: ClassMap) -> int:
    """Accept x -> y when y gets >= 90% of x's votes and x casts >= 90% of y's."""
    votes: defaultdict[str, Counter[str]] = defaultdict(Counter)
    for a, b in list(cm.forward.items()):
        neighbour_votes(cm, a, b, votes)
    received: defaultdict[str, Counter[str]] = defaultdict(Counter)
    for x, targets in votes.items():
        for y, weight in targets.items():
            received[y][x] += weight
    found = 0
    for x, targets in votes.items():
        y, weight = targets.most_common(1)[0]
        source, source_weight = received[y].most_common(1)[0]
        if source != x or weight < MIN_VOTES:
            continue
        total, received_total = sum(targets.values()), sum(received[y].values())
        if weight / total >= AGREEMENT and source_weight / received_total >= AGREEMENT:
            found += cm.add(x, y, f"propagation(votes={weight}/{total})")
    return found


def settle(cm: ClassMap) -> None:
    while fingerprint_round(cm, FINGERPRINT_LEVELS) + propagate(cm):
        pass


def build(old: Jar, new: Jar) -> ClassMap:
    cm = ClassMap(old, new)
    fingerprint_round(cm, FINGERPRINT_LEVELS)
    settle(cm)
    fingerprint_round(cm, (LOOSE_LEVEL,))
    settle(cm)
    return cm
