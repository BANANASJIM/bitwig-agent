"""Client-side failure type shared by the transport and the composed tools."""

from __future__ import annotations

from typing import Any


class BridgeError(Exception):
    """Keep host error fields, including an unknown outcome, intact."""

    def __init__(self, error: dict[str, Any]):
        self.error = error
        super().__init__(error["message"])


def bridge_error(code: str, message: str, outcome: str = "not_dispatched", **details: Any) -> BridgeError:
    error: dict[str, Any] = {"code": code, "message": message, "outcome": outcome}
    if details:
        error["details"] = details
    return BridgeError(error)
