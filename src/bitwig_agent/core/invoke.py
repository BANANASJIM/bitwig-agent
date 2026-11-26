"""The single call path: validate, dispatch to the host or a composed handler, wrap in an envelope."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from bitwig_agent.client import BridgeClient, configured_port, load_json
from bitwig_agent.core import composed, views
from bitwig_agent.errors import BridgeError, HostChanged, bridge_error, unknown_result
from bitwig_agent.protocol import (
    TOOLS,
    invalid_params_message,
    invalid_params_reason,
    unknown_method_message,
    validate_call,
)
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
    reason, next = invalid_params_reason(exc)
    return Error(bridge_error(reason, invalid_params_message(name, exc), next).error, usage=True)


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


def unknown_method(name: str) -> Error:
    return Error(bridge_error("unknown_method", unknown_method_message(name)).error, usage=True)


def stopped(method: str | None, reason: str, message: str) -> Envelope:
    """A call cut short on the caller's side: unknown when it was a write, else a refusal that changed nothing."""
    if method is None:
        return Error(bridge_error(reason, message).error)
    return Result(
        unknown_result(reason, message, next=TOOLS[method].inspect if method in TOOLS else None, method=method)
    )


def prepare(name: str, args: Mapping[str, Any]) -> tuple[dict[str, Any], int] | Error:
    """The validated host arguments and timeout, or the error the call is refused with before dispatch."""
    if not views.exists(name):
        return unknown_method(name)
    try:
        return validate_call(name, dict(args))
    except (ValueError, TypeError, RecursionError) as exc:
        return invalid_params(name, exc)


def check(name: str, args: Mapping[str, Any]) -> Error | None:
    prepared = prepare(name, args)
    return prepared if isinstance(prepared, Error) else None


async def invoke(name: str, args: Mapping[str, Any], client: BridgeClient | None = None) -> Envelope:
    prepared = prepare(name, args)
    if isinstance(prepared, Error):
        return prepared
    arguments, timeout_ms = prepared
    try:
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
