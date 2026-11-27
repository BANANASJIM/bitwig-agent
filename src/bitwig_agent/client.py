"""One newline-delimited JSON request per loopback connection, without retries."""

from __future__ import annotations

import asyncio
import contextlib
import json
import math
import os
import uuid
from importlib.metadata import PackageNotFoundError, version
from typing import Any

from bitwig_agent.errors import BridgeError, bridge_error, unknown_result
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, validate_call
from bitwig_agent.protocol.base import PROTOCOL, REASONS
from bitwig_agent.protocol.limits import (
    DEFAULT_PORT,
    DEFAULT_TIMEOUT_MS,
    MAX_MESSAGE_BYTES,
    MIN_TIMEOUT_MS,
    RESPONSE_GRACE_MS,
    mebibytes,
)
from bitwig_agent.protocol.shared import shared

RESPONSE_TOO_LARGE = f"Response exceeds {mebibytes(MAX_MESSAGE_BYTES)}"
METHODS = frozenset(TOOLS) - CLIENT_SIDE


MAX_PORT = 65535


def package_version() -> str:
    try:
        return version("bitwig-agent")
    except PackageNotFoundError:
        return "unknown"


def _flag_extension_mismatch(result: dict[str, Any]) -> None:
    """A status from another extension build is reported, never an error."""
    extension = result.get("extension")
    loaded = extension.get("version") if isinstance(extension, dict) else None
    local = package_version()
    if loaded != local:
        result["extensionMismatch"] = {
            "extensionVersion": loaded,
            "clientVersion": local,
            "warning": "Loaded extension differs from this client; reinstall/restart the controller before trusting writes.",
        }


def supported_bitwig() -> list[str]:
    """The Bitwig versions this package supports, from `shared/host.json`."""
    return [entry["version"] for entry in shared("host")]


def _reject_constant(value: str) -> None:
    raise ValueError(f"Non-finite JSON number: {value}")


def _finite_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError("JSON number is outside the finite float range")
    return parsed


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def load_json(value: str) -> Any:
    """Decode strict JSON, rejecting duplicate keys and non-finite numbers."""
    return json.loads(
        value, parse_constant=_reject_constant, parse_float=_finite_float, object_pairs_hook=_unique_object
    )


def configured_port() -> int:
    value = int(os.environ.get("BITWIG_AGENT_PORT", str(DEFAULT_PORT)))
    if not 1 <= value <= MAX_PORT:
        raise ValueError(f"BITWIG_AGENT_PORT must be in 1..{MAX_PORT}")
    return value


def _encode_request(method: str, params: dict[str, Any], timeout_ms: int) -> tuple[str, bytes]:
    if method not in METHODS or not isinstance(params, dict):
        raise ValueError("A known method and object params are required")
    limit = TOOLS[method].max_timeout_ms
    if type(timeout_ms) is not int or not MIN_TIMEOUT_MS <= timeout_ms <= limit:
        raise ValueError(f"timeoutMs must be an integer in {MIN_TIMEOUT_MS}..{limit}")
    if "timeoutMs" in params:
        raise ValueError("Pass timeout_ms separately from host params")
    params, _ = validate_call(method, {**params, "timeoutMs": timeout_ms})
    request_id = uuid.uuid4().hex
    request = {"id": request_id, "method": method, "params": params, "timeoutMs": timeout_ms, "protocol": PROTOCOL}
    payload = (json.dumps(request, allow_nan=False, separators=(",", ":")) + "\n").encode("utf-8")
    if len(payload) > MAX_MESSAGE_BYTES:
        raise ValueError(f"Request exceeds {mebibytes(MAX_MESSAGE_BYTES)}")
    return request_id, payload


def _parse_response(raw: bytes, request_id: str, method: str) -> dict[str, Any]:
    if len(raw) > MAX_MESSAGE_BYTES:
        raise ValueError(RESPONSE_TOO_LARGE)
    response = load_json(raw.decode("utf-8"))
    if not isinstance(response, dict) or response.get("id") != request_id:
        raise ValueError("Response id does not match request")
    if ("result" in response) == ("error" in response):
        raise ValueError("Response must contain exactly one of result or error")
    if "error" in response:
        error = response["error"]
        if not isinstance(error, dict) or not all(isinstance(error.get(k), str) for k in ("code", "message")):
            raise ValueError("Malformed host error")
        if REASONS.get(str(error.get("reason"))) != error.get("code"):
            # Only a peer on another protocol answers without a 0.5 reason; it refused the envelope unread.
            raise bridge_error(
                "protocol_mismatch",
                f"The extension answered in another protocol ({error.get('code')}: {error['message']}); "
                f"this client speaks {PROTOCOL}.",
                "Install the matching client and extension, then restart Bitwig.",
            )
        raise BridgeError(error)
    if not isinstance(response["result"], dict):
        raise ValueError("Host result must be an object")
    if method == "status":
        _flag_extension_mismatch(response["result"])
        response["result"]["supportedBitwig"] = supported_bitwig()
    return response["result"]


def _transport_failure(exc: Exception, method: str, dispatched: bool) -> dict[str, Any]:
    """A failed write that may have reached the host is an unknown result; anything else changed nothing."""
    reason = "deadline" if isinstance(exc, TimeoutError) else "transport"
    hint = ""
    if method == "grid.get" and (isinstance(exc, asyncio.LimitOverrunError) or RESPONSE_TOO_LARGE in str(exc)):
        hint = f" The grid.get response exceeded the {mebibytes(MAX_MESSAGE_BYTES)} client cap: lower moduleLimit (64 works with includeParameters) or set includeParameters=false."
    message = f"{type(exc).__name__}: {exc}.{hint}"
    if dispatched and not TOOLS[method].read_only:
        return unknown_result(reason, message + " The request was not retried.", TOOLS[method].inspect)
    raise bridge_error(reason, message) from exc


class BridgeClient:
    def __init__(self, port: int = DEFAULT_PORT, *, response_grace_seconds: float = RESPONSE_GRACE_MS / 1000):
        if type(port) is not int or not 1 <= port <= MAX_PORT:
            raise ValueError(f"port must be in 1..{MAX_PORT}")
        self.port = port
        self.response_grace_seconds = response_grace_seconds

    async def call(self, method: str, params: dict[str, Any], timeout_ms: int = DEFAULT_TIMEOUT_MS) -> dict[str, Any]:
        request_id, payload = _encode_request(method, params, timeout_ms)
        writer = None
        dispatched = False
        try:
            # The host deadline bounds mutations. The extra second only lets its
            # final response reach us; it never extends or replays the request.
            async with asyncio.timeout(timeout_ms / 1000 + self.response_grace_seconds):
                reader, writer = await asyncio.open_connection("127.0.0.1", self.port, limit=MAX_MESSAGE_BYTES)
                dispatched = True
                writer.write(payload)
                await writer.drain()
                raw = await reader.readuntil(b"\n")
                return _parse_response(raw, request_id, method)
        except BridgeError:
            raise
        except (
            TimeoutError,
            OSError,
            ValueError,
            RecursionError,
            asyncio.IncompleteReadError,
            asyncio.LimitOverrunError,
        ) as exc:
            return _transport_failure(exc, method, dispatched)
        finally:
            if writer is not None:
                writer.close()
                with contextlib.suppress(OSError, TimeoutError):
                    await asyncio.wait_for(writer.wait_closed(), timeout=0.2)
