"""Spec problems and the did-you-mean hints that explain them."""

from __future__ import annotations

import difflib
from collections.abc import Iterable, Sequence

from bitwig_agent.errors import BridgeError, bridge_error

PREVIEW_ITEMS = 10


class SpecError(Exception):
    def __init__(self, problems: list[str]):
        self.problems = problems
        super().__init__("; ".join(problems))


def invalid_spec(problems: list[str]) -> BridgeError:
    return bridge_error(
        "invalid_spec",
        f"{len(problems)} problem(s); nothing was written: " + " | ".join(problems[:20]),
        problems=problems,
    )


def norm(text: str) -> str:
    return "".join(ch for ch in text.casefold() if ch.isalnum())


def near(word: str, choices: Iterable[str]) -> str:
    close = difflib.get_close_matches(word, list(choices), n=4, cutoff=0.5)
    return f" Did you mean: {', '.join(map(repr, close))}?" if close else ""


def _valid(choices: Iterable[str], limit: int = 40) -> str:
    keys = sorted(choices)
    return f" Valid: {', '.join(keys[:limit])}{' ...' if len(keys) > limit else ''}." if keys else " It has none."


def hint(word: str, choices: Iterable[str]) -> str:
    """Did-you-mean (case-insensitive and substring matches first) plus the full list of valid ids."""
    keys = list(choices)
    folded = {k.casefold(): k for k in keys}
    first = [folded[word.casefold()]] if word.casefold() in folded else []
    first += [
        k
        for k in keys
        if k not in first and word and (word.casefold() in k.casefold() or k.casefold() in word.casefold())
    ]
    close = [k for k in difflib.get_close_matches(word, keys, n=4, cutoff=0.5) if k not in first]
    near_words = (first + close)[:4]
    return (f" Did you mean: {', '.join(map(repr, near_words))}?" if near_words else "") + _valid(keys)


def preview(items: Sequence[str], separator: str = "; ") -> str:
    """The first PREVIEW_ITEMS items, marked when more were cut."""
    return separator.join(items[:PREVIEW_ITEMS]) + (" ..." if len(items) > PREVIEW_ITEMS else "")
