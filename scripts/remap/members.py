"""Member alignment inside mapped class pairs, and method-body comparison.

Obfuscated member names are not consistent across classes, so every member is matched inside its own declaring
class: unique (flags, plain name, translated descriptor), then to a fixpoint bytecode-reference votes, unique
canonical-body equality among same-descriptor overloads, override families and declaration-order anchoring.
"""

import bisect
import difflib
import itertools
import re
import struct
from collections import Counter, defaultdict
from dataclasses import dataclass, field

from .bytecode import constant_indexes, member_ref, rename_desc, tokens
from .classfile import ClassFile, Member
from .classmap import MEMBER_FLAGS, ClassMap, is_obfuscated_member, mask_member
from .hierarchy import Hierarchy

Key = tuple[str, str, str]  # ('f' | 'm', name, descriptor)
MAX_ITERATIONS = 40
ORDER_CONSISTENT = 0.9
MIN_ANCHORS = 3
INVOKE_WITH_EXTRA = (0xB9, 0xBA)  # invokeinterface/invokedynamic tokens keep their trailing operand bytes
OPCODE_PREFIX = re.compile(r"^[0-9a-f]{2} ")


def keyed(c: ClassFile, kind: str) -> dict[Key, Member]:
    return {(kind, m.name, m.desc): m for m in (c.fields if kind == "f" else c.methods)}


def ordered(c: ClassFile, kind: str) -> list[Key]:
    return [(kind, m.name, m.desc) for m in (c.fields if kind == "f" else c.methods)]


def longest_increasing(pairs: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Longest chain of (old index, new index) pairs, sorted by old index, whose new index strictly increases."""
    tails: list[int] = []
    tail_at: list[int] = []
    previous: list[int | None] = [None] * len(pairs)
    for i, (_, new) in enumerate(pairs):
        k = bisect.bisect_left(tails, new)
        if k == len(tails):
            tails.append(new)
            tail_at.append(i)
        else:
            tails[k], tail_at[k] = new, i
        previous[i] = tail_at[k - 1] if k else None
    out = []
    at = tail_at[-1] if tail_at else None
    while at is not None:
        out.append(pairs[at])
        at = previous[at]
    return out[::-1]


@dataclass
class Alignment:
    pairs: dict[Key, Key] = field(default_factory=dict)  # old -> new
    inverse: dict[Key, Key] = field(default_factory=dict)
    evidence: dict[Key, str] = field(default_factory=dict)
    groups: list[tuple[list[Key], list[Key]]] = field(default_factory=list)  # ambiguous same-shape overloads


class Members:
    def __init__(self, cm: ClassMap, use_order: bool = True) -> None:
        self.cm, self.use_order = cm, use_order
        self.old, self.new = Hierarchy(cm.old), Hierarchy(cm.new)
        self.alignments: dict[str, Alignment] = {}
        self.masked_cache: dict[tuple[str, int], tuple[str, ...] | None] = {}

    # ---- alignment state ---------------------------------------------------------------------------------------
    def shape(self, m: Member, desc: str) -> tuple[int, str, str]:
        return m.access & MEMBER_FLAGS, mask_member(m.name), desc

    def align(self, a: str) -> Alignment:
        """The alignment of old class `a`, seeded with members unique by (flags, plain name, descriptor)."""
        if a in self.alignments:
            return self.alignments[a]
        al = self.alignments[a] = Alignment()
        if a not in self.cm.forward or self.cm.forward[a] not in self.cm.new:
            return al
        ca, cb = self.cm.old[a], self.cm.new[self.cm.forward[a]]
        for kind, k in (("fields", "f"), ("methods", "m")):
            old: defaultdict[tuple[int, str, str], list[Key]] = defaultdict(list)
            new: defaultdict[tuple[int, str, str], list[Key]] = defaultdict(list)
            for m in getattr(cb, kind):
                new[self.shape(m, m.desc)].append((k, m.name, m.desc))
            for m in getattr(ca, kind):
                old[self.shape(m, self.cm.translate(m.desc))].append((k, m.name, m.desc))
            for group, olds in old.items():
                news = new.get(group, [])
                if len(olds) == 1 and len(news) == 1:
                    self.put(a, olds[0], news[0], "unique-descriptor")
                elif olds and news:
                    al.groups.append((olds, news))
        return al

    def put(self, a: str, ka: Key, kb: Key, evidence: str) -> bool:
        al = self.alignments[a]
        if ka in al.pairs or kb in al.inverse:
            return False
        al.pairs[ka], al.inverse[kb], al.evidence[ka] = kb, ka, evidence
        return True

    # ---- bodies --------------------------------------------------------------------------------------------------
    def masked(self, c: ClassFile, code: bytes | None, side: str) -> tuple[str, ...] | None:
        """Body tokens with obfuscated member names and unmapped classes masked."""
        if not code:
            return None
        key = (side, id(code))
        if key not in self.masked_cache:
            nc = self.cm.old_token if side == "A" else self.cm.new_token
            try:
                self.masked_cache[key] = tuple(tokens(code, c.cp, nc, rename_desc(nc), mask_member))
            except (IndexError, KeyError, TypeError, ValueError, struct.error):
                self.masked_cache[key] = None
        return self.masked_cache[key]

    def canonical(self, c: ClassFile, code: bytes, side: str) -> list[str]:
        """Body tokens in the old name space; member references the alignment cannot resolve keep a '?'."""
        cm = self.cm
        if side == "A":

            def nc(n: str) -> str:
                return n if n not in cm.old or n in cm.forward else "!A:" + n
        else:

            def nc(n: str) -> str:
                return cm.backward.get(n, "!B:" + n) if n in cm.new else n

        nd = rename_desc(nc)
        out = tokens(code, c.cp, nc, nd, lambda s: s)
        for j, (op, index) in enumerate(constant_indexes(code)):
            if index is None or (ref := member_ref(c.cp, index)) is None:
                continue
            tag, owner, desc = c.cp[index][1], ref[1], ref[3]
            out[j] = f"{op:02x} R{tag}:{nc(owner)}.{self.canonical_member(ref, side)}:{nd(desc)}" + (
                " " + out[j].rsplit(" ", 1)[-1] if op in INVOKE_WITH_EXTRA else ""
            )
        return out

    def canonical_member(self, ref: tuple[str, str, str, str], side: str) -> str:
        kind, owner, name, desc = ref
        if not is_obfuscated_member(name):
            return name
        if side == "A":
            decl = self.old.declaring(owner, name, desc, kind == "f")
            return f"{decl}.{name}" if decl else f"?{name}"
        decl = self.new.declaring(owner, name, desc, kind == "f")
        if decl and decl in self.cm.backward:
            a = self.cm.backward[decl]
            ka = self.align(a).inverse.get((kind, name, desc))
            if ka:
                return f"{a}.{ka[1]}"
        return "?" + name

    def strict_equal(self, a: str, ma: Member, mb: Member) -> bool:
        if ma.code is None or mb.code is None:
            return False
        ta = self.canonical(self.cm.old[a], ma.code, "A")
        tb = self.canonical(self.cm.new[self.cm.forward[a]], mb.code, "B")
        return ta == tb and not any(t.split(":", 1)[-1].split(".")[-1][:1] == "?" for t in ta)

    def compare_bodies(self, a: str, ma: Member, mb: Member) -> tuple[str, str]:
        """identical | renamed-only | no-body | changed, with a note."""
        ca, cb = self.cm.old[a], self.cm.new[self.cm.forward[a]]
        if ma.code is None and mb.code is None:
            return "no-body", "abstract/interface on both sides"
        if ma.code is None or mb.code is None:
            return "changed", "one side abstract/native, other has code"

        def same(n: str) -> str:
            return n

        if tokens(ma.code, ca.cp, same, same, same) == tokens(mb.code, cb.cp, same, same, same):
            return "identical", ""
        ta, tb = self.canonical(ca, ma.code, "A"), self.canonical(cb, mb.code, "B")
        if ta == tb:
            unresolved = sum(1 for t in ta if ".?" in t)
            return "renamed-only", (
                f"{unresolved} member refs not resolvable by alignment but name-equal in shape" if unresolved else ""
            )
        matcher = difflib.SequenceMatcher(None, ta, tb, autojunk=False)
        removed = [t for o, i1, i2, _, _ in matcher.get_opcodes() if o in ("delete", "replace") for t in ta[i1:i2]]
        added = [t for o, _, _, j1, j2 in matcher.get_opcodes() if o in ("insert", "replace") for t in tb[j1:j2]]
        masked_a = self.masked(ca, ma.code, "A")
        if masked_a is not None and masked_a == self.masked(cb, mb.code, "B"):
            return "renamed-only", (
                f"equal with obfuscated member names masked; {len(removed)} refs unresolved: "
                f"-{excerpt(removed, 3)} +{excerpt(added, 3)}"
            )
        return (
            "changed",
            f"len {len(ta)}->{len(tb)}, ratio {matcher.ratio():.2f}; -{excerpt(removed)} +{excerpt(added)}",
        )

    # ---- solving -------------------------------------------------------------------------------------------------
    def solve(self, classes: set[str]) -> None:
        mapped = sorted(a for a in classes if a in self.cm.forward)
        for _ in range(MAX_ITERATIONS):
            for a in sorted(classes):
                self.align(a)
            found = self.reference_votes(mapped)
            found += sum(self.unique_bodies(a) for a in mapped)
            found += sum(self.override_families(a) for a in mapped)
            if self.use_order:
                found += sum(self.order_anchor(a) for a in mapped)
            if not found:
                return

    def reference_votes(self, mapped: list[str]) -> int:
        """Methods whose masked bodies are equal reference the same members at the same positions."""
        votes: defaultdict[tuple[str, Key], Counter[Key]] = defaultdict(Counter)
        for a in mapped:
            ca, cb = self.cm.old[a], self.cm.new[self.cm.forward[a]]
            ma, mb = keyed(ca, "m"), keyed(cb, "m")
            for ka, kb in list(self.alignments[a].pairs.items()):
                if ka[0] != "m":
                    continue
                code_a, code_b = ma[ka].code, mb[kb].code
                if not code_a or not code_b or self.masked(ca, code_a, "A") != self.masked(cb, code_b, "B"):
                    continue
                for (_, ia), (_, ib) in zip(constant_indexes(code_a), constant_indexes(code_b), strict=False):
                    ra = member_ref(ca.cp, ia) if ia is not None else None
                    rb = member_ref(cb.cp, ib) if ib is not None else None
                    if not ra or not rb or not is_obfuscated_member(ra[2]):
                        continue
                    da = self.old.declaring(ra[1], ra[2], ra[3], ra[0] == "f")
                    db = self.new.declaring(rb[1], rb[2], rb[3], rb[0] == "f")
                    if da and db and self.cm.forward.get(da) == db:
                        votes[da, (ra[0], ra[2], ra[3])][rb[0], rb[2], rb[3]] += 1
        found = 0
        for (da, ka), targets in votes.items():
            if ka in self.align(da).pairs or len(targets) != 1:
                continue
            kb, weight = next(iter(targets.items()))
            if kb[2] == self.cm.translate(ka[2]):
                found += self.put(da, ka, kb, f"bytecode-ref votes={weight}")
        return found

    def unique_bodies(self, a: str) -> int:
        ma, mb = keyed(self.cm.old[a], "m"), keyed(self.cm.new[self.cm.forward[a]], "m")
        al, found = self.alignments[a], 0
        for olds, news in al.groups:
            for ka in olds:
                if ka in al.pairs or ka[0] != "m":
                    continue
                hits = [kb for kb in news if kb not in al.inverse and self.strict_equal(a, ma[ka], mb[kb])]
                if len(hits) == 1:
                    found += self.put(a, ka, hits[0], "unique strict-canonical body among same-descriptor overloads")
        return found

    def override_families(self, a: str) -> int:
        """An aligned override or implementation elsewhere in the hierarchy fixes the family's new name."""
        al, found = self.alignments[a], 0
        for olds, news in al.groups:
            for ka in olds:
                if ka in al.pairs or ka[0] != "m" or ka[1] == "<init>":
                    continue
                names: Counter[str] = Counter()
                for relative in set(self.old.supers(a)) | self.old.subs(a):
                    if relative not in self.cm.forward or relative not in self.cm.old:
                        continue
                    if not any(m.name == ka[1] and m.desc == ka[2] for m in self.cm.old[relative].methods):
                        continue
                    kb = self.align(relative).pairs.get(ka)
                    if kb:
                        names[kb[1]] += 1
                if len(names) == 1:
                    kb = ("m", next(iter(names)), self.cm.translate(ka[2]))
                    if kb in news and kb not in al.inverse:
                        found += self.put(a, ka, kb, f"override-family ({sum(names.values())} aligned relatives)")
        return found

    def order_chain(self, a: str, kind: str) -> tuple[list[tuple[int, int]], int]:
        """Order-consistent aligned (old index, new index) pairs of class `a`, and how many pairs there are."""
        la, lb = ordered(self.cm.old[a], kind), ordered(self.cm.new[self.cm.forward[a]], kind)
        position = {key: i for i, key in enumerate(lb)}
        pairs = self.alignments[a].pairs
        anchors = [(i, position[pairs[x]]) for i, x in enumerate(la) if x in pairs and pairs[x] in position]
        return longest_increasing(anchors), len(anchors)

    def order_anchor(self, a: str) -> int:
        """The obfuscator keeps declaration order (measured), so members between two order-consistent anchors
        pair up inside that gap."""
        ca, cb, found = self.cm.old[a], self.cm.new[self.cm.forward[a]], 0
        for kind in ("f", "m"):
            chain, total = self.order_chain(a, kind)
            if total < MIN_ANCHORS or len(chain) / total < ORDER_CONSISTENT:
                continue
            la, lb = ordered(ca, kind), ordered(cb, kind)
            fa, fb = keyed(ca, kind), keyed(cb, kind)
            old_shapes = {x: self.shape(fa[x], self.cm.translate(x[2])) for x in la}
            new_shapes = {y: self.shape(fb[y], y[2]) for y in lb}
            note = f"anchors {len(chain)}/{total}"
            for (ia1, ib1), (ia2, ib2) in itertools.pairwise([(-1, -1), *chain, (len(la), len(lb))]):
                gap = la[ia1 + 1 : ia2], lb[ib1 + 1 : ib2]
                found += self.pair_gap(a, gap, old_shapes, new_shapes, note)
        return found

    def pair_gap(
        self,
        a: str,
        gap: tuple[list[Key], list[Key]],
        old_shapes: dict[Key, tuple[int, str, str]],
        new_shapes: dict[Key, tuple[int, str, str]],
        note: str,
    ) -> int:
        """Pair the unique unaligned member of each shape, then a gap whose shape sequence is equal positionally."""
        al, (xs, ys), found = self.alignments[a], gap, 0
        olds: defaultdict[tuple[int, str, str], list[Key]] = defaultdict(list)
        news: defaultdict[tuple[int, str, str], list[Key]] = defaultdict(list)
        for x in xs:
            if x not in al.pairs:
                olds[old_shapes[x]].append(x)
        for y in ys:
            if y not in al.inverse:
                news[new_shapes[y]].append(y)
        for group, candidates in olds.items():
            if len(candidates) == 1 and len(news.get(group, [])) == 1:
                found += self.put(
                    a, candidates[0], news[group][0], f"declaration-order anchor (order-consistent {note})"
                )
        if xs and len(xs) == len(ys) and [old_shapes[x] for x in xs] == [new_shapes[y] for y in ys]:
            for x, y in zip(xs, ys, strict=True):
                if x not in al.pairs and y not in al.inverse:
                    found += self.put(
                        a, x, y, f"declaration-order gap with identical shape sequence (len {len(xs)}, {note})"
                    )
        return found


def excerpt(items: list[str], limit: int = 6) -> str:
    stripped = [OPCODE_PREFIX.sub("", t) for t in items]
    text = "; ".join(stripped[:limit])
    return "[" + text[:400] + (" ..." if len(stripped) > limit else "") + "]"
