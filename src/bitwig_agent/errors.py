"""Client-side failures: an error changed nothing; an unknown outcome is a result, never an error."""

from __future__ import annotations

from typing import Any

from bitwig_agent.protocol.base import REASONS, Outcome


class BridgeError(Exception):
    """Nothing changed on the host; keep the host's error fields intact."""

    def __init__(self, error: dict[str, Any]):
        self.error = error
        super().__init__(error["message"])


class HostChanged(Exception):
    """A composed step after which the host has, or may have, changed: the call returns this result, never an error."""

    def __init__(self, result: dict[str, Any]):
        self.result = result
        super().__init__(result["message"])


def bridge_error(reason: str, message: str, next: str | None = None, **details: Any) -> BridgeError:
    error: dict[str, Any] = {"code": REASONS[reason].value, "reason": reason, "message": message}
    if next:
        error["next"] = next
    if details:
        error["details"] = details
    return BridgeError(error)


def changed_before(error: BridgeError, **changed: Any) -> HostChanged:
    """A refusal that came after this call already changed host state: not applied, plus what did change."""
    refusal = error.error
    result = {"outcome": Outcome.NOT_APPLIED.value, "reason": refusal["reason"], "message": refusal["message"]}
    if "next" in refusal:
        result["next"] = refusal["next"]
    return HostChanged({**result, **changed})


def unknown_result(reason: str, message: str, next: str | None = None, **fields: Any) -> dict[str, Any]:
    if reason not in REASONS:
        raise KeyError(reason)
    result: dict[str, Any] = {"outcome": Outcome.UNKNOWN.value, "reason": reason, "message": message, **fields}
    if next:
        result["next"] = next
    return result
