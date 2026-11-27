"""Type-hierarchy queries over one jar, resolved the way the JVM's reflection lookups resolve them."""

from collections import defaultdict
from functools import cache

from .classfile import ACC_INTERFACE, ACC_PUBLIC, ClassFile, Member
from .classmap import Jar


class Hierarchy:
    def __init__(self, jar: Jar) -> None:
        self.jar = jar
        self.children: defaultdict[str, list[str]] = defaultdict(list)
        for name, c in jar.items():
            for parent in self.parents(c):
                self.children[parent].append(name)
        self.supers = cache(self._supers)
        self.subs = cache(self._subs)

    @staticmethod
    def parents(c: ClassFile) -> list[str]:
        return ([c.super] if c.super else []) + c.interfaces

    def _supers(self, name: str) -> list[str]:
        """All supertypes in depth-first declaration order (superclass before interfaces)."""
        out: list[str] = []

        def visit(n: str) -> None:
            c = self.jar.get(n)
            for parent in self.parents(c) if c else []:
                if parent not in out:
                    out.append(parent)
                    visit(parent)

        visit(name)
        return out

    def _subs(self, name: str) -> set[str]:
        out: set[str] = set()
        pending = [name]
        while pending:
            for child in self.children.get(pending.pop(), ()):
                if child not in out:
                    out.add(child)
                    pending.append(child)
        return out

    def chain(self, name: str) -> list[str]:
        out = []
        while name in self.jar:
            out.append(name)
            name = self.jar[name].super or ""
        return out

    def public_method(self, owner: str, name: str, desc: str) -> tuple[str | None, Member | None]:
        """Class.getMethod: the class chain first, then interfaces; parameters must match, the return type not."""
        params = desc.split(")", maxsplit=1)[0]
        interfaces = [s for s in self.supers(owner) if s in self.jar and self.jar[s].access & ACC_INTERFACE]
        for n in self.chain(owner) + interfaces:
            for m in self.jar[n].methods:
                if m.name == name and m.desc.split(")")[0] == params and m.access & ACC_PUBLIC:
                    return n, m
        return None, None

    def declaring(self, owner: str, name: str, desc: str, field: bool) -> str | None:
        """The class declaring the member a bytecode reference on `owner` resolves to."""
        for n in self.chain(owner) + [s for s in self.supers(owner) if s in self.jar]:
            if any(m.name == name and m.desc == desc for m in self.members(n, field)):
                return n
        return None

    def members(self, name: str, field: bool) -> list[Member]:
        c = self.jar[name]
        return c.fields if field else c.methods

    def public_methods(self, name: str) -> set[tuple[str, str]]:
        return {
            (m.name, m.desc)
            for s in [name, *self.supers(name)]
            if s in self.jar
            for m in self.jar[s].methods
            if m.access & ACC_PUBLIC and m.name not in ("<init>", "<clinit>")
        }
