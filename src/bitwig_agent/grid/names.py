"""The one module type-name matching rule; the Java extension's GridNames.resolve is the same rule (shared vectors)."""

from __future__ import annotations

import unicodedata
from collections.abc import Callable, Iterable
from typing import TypeVar

T = TypeVar("T")


def norm(text: str) -> str:
    """Letters and digits only, lowercased: case, spaces, hyphens and punctuation are ignored."""
    return "".join(
        ch.lower() for ch in text if unicodedata.category(ch).startswith("L") or unicodedata.category(ch) == "Nd"
    )


def resolve(items: Iterable[T], name: Callable[[T], str], query: str) -> list[T]:
    """Exact, then exact ignoring case, then the normalized form (skipped for symbol-only queries, which only match
    exactly). The first step with any hit decides; several hits are ambiguous."""
    pool = list(items)
    hits = [item for item in pool if name(item) == query]
    if not hits:
        hits = [item for item in pool if name(item).lower() == query.lower()]
    if not hits and norm(query):
        hits = [item for item in pool if norm(name(item)) == norm(query)]
    return hits
