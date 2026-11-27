"""Map each bound symbol to the new jar with a status (exact/confident/uncertain/unmapped) and its evidence."""

import difflib
from collections import Counter, defaultdict
from typing import Any

from .classfile import ACC_ABSTRACT, ACC_FINAL, ACC_INTERFACE, ACC_PUBLIC, ACC_STATIC, Member
from .classmap import MEMBER_FLAGS, ClassMap, is_obfuscated_member, mask_member
from .inventory import CLASS_KINDS, Symbol
from .members import Key, Members, keyed, ordered

Record = dict[str, Any]
ALIGNED_MIN, SHAPE_MIN = 0.5, 0.9
COMPARED_FLAGS = 0x1F7F
SOUND_BODIES = ("identical", "renamed-only", "no-body")
MEMBER_KINDS = ("method", "ctor", "override", "declared")
CANDIDATE_LIMIT = 12


def java(name: str) -> str:
    return name.replace("/", ".")


def symbol_key(s: Symbol) -> str:
    if s.kind in CLASS_KINDS:
        return java(s.owner)
    return f"{java(s.owner)}.{s.name}" + (":" + s.desc if s.kind == "CONST" else s.desc)


class Mapper:
    def __init__(self, cm: ClassMap, members: Members) -> None:
        self.cm, self.members = cm, members

    def resolve(self, s: Symbol) -> tuple[str | None, Key | None]:
        """The old declaring class and member a lookup of `s` reaches."""
        old = self.cm.old
        if s.kind == "CONST":
            desc = next((f.desc for f in old[s.owner].fields if f.name == s.name), None)
            return s.owner, ("f", s.name, desc) if desc else None
        if s.kind in ("CTOR", "DECLARED"):
            found = any(m.name == s.name and m.desc == s.desc for m in old[s.owner].methods)
            return s.owner, ("m", s.name, s.desc) if found else None
        decl, m = self.members.old.public_method(s.owner, s.name, s.desc)
        return decl, ("m", s.name, m.desc) if m else None

    def map_all(self, inventory: list[Symbol]) -> dict[str, Record]:
        resolved = {s: self.resolve(s) for s in inventory if s.kind not in CLASS_KINDS and s.owner in self.cm.old}
        relevant = {s.owner for s in inventory if s.kind == "TYPE" and s.owner in self.cm.old}
        for decl, _ in resolved.values():
            if decl:
                relevant |= {decl, *(c for c in self.members.old.supers(decl) if c in self.cm.old)}
        self.members.solve(relevant)
        out = {symbol_key(s): self.member(s, resolved.get(s)) for s in inventory if s.kind not in CLASS_KINDS}
        out |= {symbol_key(s): self.cls(s) for s in inventory if s.kind == "TYPE"}
        return out

    def cls(self, s: Symbol) -> Record:
        o, cm = s.owner, self.cm
        status = cm.status(o) if o in cm.old else "unmapped"
        evidence = cm.evidence.get(o, "no fingerprint/propagation match") if o in cm.old else "not in old jar"
        rec: Record = {"kind": "class", "new": java(cm.forward[o]) if o in cm.forward else None, "src": s.src}
        if o in cm.forward and status != "exact":
            aligned, total = len(self.members.align(o).pairs), len(cm.old[o].methods) + len(cm.old[o].fields)
            shape = self.shape_ratio(o)
            evidence += f"; {aligned}/{total} members align, declaration-shape ratio {shape:.2f}"
            if (total and aligned / total < ALIGNED_MIN) or shape < SHAPE_MIN:
                status = "uncertain"
                evidence += " (<50% align or shape<0.9)"
        return rec | {"evidence": evidence, "status": status}

    def shape_ratio(self, a: str) -> float:
        def shapes(members: list[Member], translate: Any) -> list[str]:
            return [f"{m.access & MEMBER_FLAGS}:{mask_member(m.name)}:{translate(m.desc)}" for m in members]

        ca, cb = self.cm.old[a], self.cm.new[self.cm.forward[a]]
        old = shapes(ca.fields + ca.methods, self.cm.translate)
        new = shapes(cb.fields + cb.methods, lambda d: d)
        return difflib.SequenceMatcher(None, old, new, autojunk=False).ratio()

    def member(self, s: Symbol, resolved: tuple[str | None, Key | None] | None) -> Record:
        rec: Record = {"kind": s.kind.lower(), "new": None, "status": "unmapped", "evidence": "", "src": s.src}
        if resolved is None:
            return rec | {"evidence": "owner not in old jar"}
        decl, ka = resolved
        if decl is None or ka is None:
            return rec | {"evidence": "member not found in old jar"}
        if s.declared_in and s.declared_in != decl:
            rec["evidence"] += f"DECL MISMATCH log={s.declared_in} jar={decl}; "
        rec["old_decl"] = java(decl)
        if decl not in self.cm.forward:
            rec["evidence"] += f"declaring class {java(decl)} unmapped"
            return rec
        bd = self.cm.forward[decl]
        al = self.members.align(decl)
        ma = keyed(self.cm.old[decl], ka[0])[ka]
        rec["old_flags"] = hex(ma.access)
        kb = al.pairs.get(ka)
        if kb is None:  # report candidates, never pick one
            candidates = [
                k
                for k, m in keyed(self.cm.new[bd], ka[0]).items()
                if k[2] == self.cm.translate(ka[2]) and (m.access & ACC_STATIC) == (ma.access & ACC_STATIC)
                if k not in al.inverse
            ]
            rec["status"] = "uncertain" if candidates else "unmapped"
            rec["evidence"] += (
                f"no unique alignment in {java(bd)}; {len(candidates)} unpaired same-descriptor candidates"
            )
            rec["candidates"] = [f"{k[1]}{k[2]}" for k in candidates[:CANDIDATE_LIMIT]]
            return rec
        return self.mapped(s, decl, ka, kb, rec)

    def mapped(self, s: Symbol, decl: str, ka: Key, kb: Key, rec: Record) -> Record:
        bd = self.cm.forward[decl]
        ma, mb = keyed(self.cm.old[decl], ka[0])[ka], keyed(self.cm.new[bd], kb[0])[kb]
        how = self.members.alignments[decl].evidence[ka]
        new_owner = self.cm.forward.get(s.owner, bd)
        if s.kind == "CONST":
            rec.update(new=f"{java(bd)}.{kb[1]}:{kb[2]}", new_flags=hex(mb.access))
        else:
            rec.update(new=f"{java(new_owner)}.{kb[1]}{kb[2]}", new_decl=java(bd), new_flags=hex(mb.access))
            body, note = self.members.compare_bodies(decl, ma, mb)
            rec["body"] = body
            if note:
                rec["body_note"] = note
        flags_ok = (ma.access & COMPARED_FLAGS) == (mb.access & COMPARED_FLAGS)
        if not flags_ok:
            how += f"; ACCESS FLAGS {ma.access:#x}->{mb.access:#x}"
        if s.kind in ("METHOD", "OVERRIDE"):
            hit, _ = self.members.new.public_method(new_owner, kb[1], kb[2])
            if hit != bd:
                how += f"; RESOLUTION via {java(new_owner)} hits {hit}"
                flags_ok = False
        class_status = self.cm.status(decl)
        sound = rec.get("body", "no-body") in SOUND_BODIES and "masked" not in rec.get("body_note", "")
        if how.startswith("unique-descriptor") and flags_ok and class_status == "exact" and sound:
            status = "exact"
        elif flags_ok and class_status in ("exact", "confident"):
            status = "confident"
        else:
            status = "uncertain"
        consistent, order = self.order(decl, ka, kb)
        rec["order"] = order
        if not consistent:
            how += "; DECLARATION ORDER INCONSISTENT"
            status = "uncertain"
        rivals = [
            m
            for m in (self.cm.new[bd].fields if kb[0] == "f" else self.cm.new[bd].methods)
            if m.desc == kb[2] and m.name != kb[1] and (m.access & ACC_STATIC) == (mb.access & ACC_STATIC)
        ]
        if ka[0] == "m" and ma.code and rivals:
            equal = sum(1 for m in rivals if self.members.strict_equal(decl, ma, m))
            rec["rivals"] = f"{len(rivals)} same-descriptor rivals, {equal} also body-equal"
        suffix = "" if class_status == "exact" else f"; declaring class {class_status}"
        return rec | {"status": status, "evidence": rec["evidence"] + how + suffix}

    def order(self, decl: str, ka: Key, kb: Key) -> tuple[bool, str]:
        """Independent cross-check: does the pair lie on the class's order-consistent chain?"""
        chain, total = self.members.order_chain(decl, ka[0])
        ia = ordered(self.cm.old[decl], ka[0]).index(ka)
        ib = ordered(self.cm.new[self.cm.forward[decl]], kb[0]).index(kb)
        consistent = (ia, ib) in set(chain)
        word = "consistent" if consistent else "INCONSISTENT"
        return consistent, f"{word} ({len(chain)}/{total} pairs order-consistent in class)"


def checks(cm: ClassMap, members: Members, inventory: list[Symbol], symbols: dict[str, Record]) -> list[str]:
    """Structural checks over the bound classes and members."""
    out = [
        f"getMethods({java(s.owner)}): old {sorted(members.old.public_methods(s.owner))}; new "
        f"{sorted(members.new.public_methods(cm.forward[s.owner])) if s.owner in cm.forward else []}"
        for s in inventory
        if s.kind == "GETMETHODS"
    ]
    bound = [k.replace(".", "/") for k, v in symbols.items() if v["kind"] == "class"]
    bound = [b for b in bound if b in cm.forward]
    return out + class_checks(cm, members, bound) + member_checks(cm, symbols)


def class_checks(cm: ClassMap, members: Members, bound: list[str]) -> list[str]:
    """Class flags and the subtype relation between every pair of bound classes survive the mapping."""
    out, subtypes = [], 0
    for x in bound:
        ca, cb = cm.old[x], cm.new[cm.forward[x]]
        for bit, flag in (
            (ACC_PUBLIC, "public"),
            (ACC_INTERFACE, "interface"),
            (ACC_ABSTRACT, "abstract"),
            (ACC_FINAL, "final"),
        ):
            if (ca.access & bit) != (cb.access & bit):
                out.append(f"FLAG {flag} differs: {java(x)} {ca.access:#x} -> {java(cm.forward[x])} {cb.access:#x}")
        old_supers, new_supers = set(members.old.supers(x)), set(members.new.supers(cm.forward[x]))
        for y in bound:
            in_old, in_new = y in old_supers, cm.forward[y] in new_supers
            if in_old != in_new:
                out.append(f"SUBTYPE {java(x)} <: {java(y)} is {in_old} in old, {in_new} in new")
            subtypes += in_old
    out.append(f"subtype relations among bound classes checked: {len(bound)}^2 pairs, {subtypes} true in old")
    return out


def member_name(identity: str) -> str:
    return identity.split("(", maxsplit=1)[0].split(":", maxsplit=1)[0].rsplit(".", 1)[1]


def member_checks(cm: ClassMap, symbols: dict[str, Record]) -> list[str]:
    """Descriptor chain, final override targets, old->new name consistency and the body negative control."""
    out, chained = [], 0
    names: defaultdict[str, Counter[str]] = defaultdict(Counter)
    for k, v in symbols.items():
        if v["kind"] in MEMBER_KINDS and v.get("new"):
            old_desc, new_desc = cm.translate(k[k.index("(") :]), v["new"][v["new"].index("(") :]
            chained += old_desc == new_desc
            if old_desc != new_desc:
                out.append(f"DESCRIPTOR-CHAIN {k}: translated {old_desc} != new {new_desc}")
        if v["kind"] == "override" and v.get("new_flags") and int(v["new_flags"], 16) & ACC_FINAL:
            out.append(f"OVERRIDE target is final in new jar: {k} -> {v['new']}")
        if v.get("new") and v["kind"] != "class" and is_obfuscated_member(member_name(k)):
            names[member_name(k)][member_name(v["new"])] += 1
    out.append(f"members whose new descriptor equals class-map translation of old: {chained}")
    multi = {o: dict(c) for o, c in names.items() if len(c) > 1}
    single = sum(1 for c in names.values() if len(c) == 1)
    out.append(f"old->new member-name consistency over bound members: {single} single-valued; multi-valued: {multi}")
    rivals = [v["rivals"] for v in symbols.values() if v.get("rivals")]
    ambiguous = sum(1 for r in rivals if not r.endswith(" 0 also body-equal"))
    out.append(
        f"negative control: {len(rivals)} mapped bound methods have same-descriptor rivals in the new class; "
        f"rivals whose body is also canonically equal: {ambiguous} methods"
    )
    return out
