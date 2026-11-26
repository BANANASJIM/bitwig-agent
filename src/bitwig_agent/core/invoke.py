"""The single call path: validate, dispatch to the host or a composed handler, wrap in an envelope."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from bitwig_agent.client import BridgeClient, configured_port, load_json
from bitwig_agent.core import composed, views
from bitwig_agent.errors import BridgeError, HostChanged, bridge_error
from bitwig_agent.protocol import invalid_params_message, unknown_method_message, validate_call
from bitwig_agent.protocol.base import SUCCEEDED


@dataclass(frozen=True)
class Result:
    result: dict[str, Any]

    @property
    def payload(self) -> dict[str, Any]:
        return {"result": self.result}

    @property
    def failed(self) -> bool:
        """A write whose outcome is not a success; reads carry no outcome."""
        return "outcome" in self.result and self.result["outcome"] not in SUCCEEDED


@dataclass(frozen=True)
class Error:
    error: dict[str, Any]
    usage: bool = False

    @property
    def payload(self) -> dict[str, Any]:
        return {"error": self.error}

    failed = True


Envelope = Result | Error


def decode(text: str) -> Any:
    """Strict JSON: duplicate keys and non-finite numbers are rejected."""
    return load_json(text)


def invalid_params_error(message: str) -> Error:
    return Error(bridge_error("invalid_params", message).error, usage=True)


def invalid_params(name: str, exc: Exception) -> Error:
    return invalid_params_error(invalid_params_message(name, exc))


def ask_help(arguments: Mapping[str, Any]) -> Envelope:
    topic = arguments.get("topic")
    unknown = sorted(set(arguments) - {"topic"})
    if unknown or not (topic is None or isinstance(topic, str)):
        return invalid_params_error(
            f"help takes only an optional string topic, got {', '.join(unknown) or type(topic).__name__}"
        )
    try:
        return Result(views.help(topic))
    except ValueError as exc:
        return invalid_params_error(str(exc))


def refuse_timeout(name: str) -> Error:
    """For a front end that leaves the wait to the method's default."""
    return invalid_params_error(views.unknown_field_message(name, "timeoutMs"))


async def invoke(name: str, args: Mapping[str, Any], client: BridgeClient | None = None) -> Envelope:
    if not views.exists(name):
        return Error(bridge_error("unknown_method", unknown_method_message(name)).error, usage=True)
    try:
        arguments, timeout_ms = validate_call(name, dict(args))
        bridge = client if client is not None else BridgeClient(configured_port())
        if name in composed.HANDLERS:
            return Result(await composed.run(name, bridge, arguments, timeout_ms))
        return Result(await bridge.call(name, arguments, timeout_ms))
    except BridgeError as exc:
        return Error(exc.error)
    except HostChanged as exc:
        return Result(exc.result)
    except (ValueError, TypeError, RecursionError) as exc:
        return invalid_params(name, exc)
