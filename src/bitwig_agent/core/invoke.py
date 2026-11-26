"""The single call path: validate, dispatch to the host or a composed handler, wrap in an envelope."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from bitwig_agent.client import BridgeClient, configured_port, load_json
from bitwig_agent.core import composed
from bitwig_agent.errors import BridgeError
from bitwig_agent.protocol import invalid_params_message, validate_call


@dataclass(frozen=True)
class Result:
    result: dict[str, Any]

    @property
    def payload(self) -> dict[str, Any]:
        return {"result": self.result}


@dataclass(frozen=True)
class Error:
    error: dict[str, Any]
    usage: bool = False

    @property
    def payload(self) -> dict[str, Any]:
        return {"error": self.error}


Envelope = Result | Error


def decode(text: str) -> Any:
    """Strict JSON: duplicate keys and non-finite numbers are rejected."""
    return load_json(text)


def invalid_params(name: str, exc: Exception) -> Error:
    error = {"code": "invalid_params", "message": invalid_params_message(name, exc), "outcome": "not_dispatched"}
    return Error(error, usage=True)


async def invoke(name: str, args: Mapping[str, Any], client: BridgeClient | None = None) -> Envelope:
    try:
        arguments, timeout_ms = validate_call(name, dict(args))
        bridge = client if client is not None else BridgeClient(configured_port())
        if name in composed.HANDLERS:
            return Result(await composed.run(name, bridge, arguments, timeout_ms))
        return Result(await bridge.call(name, arguments, timeout_ms))
    except BridgeError as exc:
        return Error(exc.error)
    except (ValueError, TypeError, RecursionError) as exc:
        return invalid_params(name, exc)
