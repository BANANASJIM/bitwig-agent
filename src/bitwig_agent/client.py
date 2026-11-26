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

from bitwig_agent.errors import BridgeError, bridge_error
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, validate_call

MAX_BYTES = 1024 * 1024
METHODS = frozenset(TOOLS) - CLIENT_SIDE


MAX_PORT = 65535
MIN_TIMEOUT_MS = 100


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
    value = int(os.environ.get("BITWIG_AGENT_PORT", "61172"))
    if not 1 <= value <= MAX_PORT:
        raise ValueError(f"BITWIG_AGENT_PORT must be in 1..{MAX_PORT}")
    return value


class BridgeClient:
    def __init__(self, port: int = 61172, *, response_grace_seconds: float = 1.0):
        if type(port) is not int or not 1 <= port <= MAX_PORT:
            raise ValueError(f"port must be in 1..{MAX_PORT}")
        self.port = port
        self.response_grace_seconds = response_grace_seconds

    async def call(self, method: str, params: dict[str, Any], timeout_ms: int = 5000) -> dict[str, Any]:  # noqa: C901, PLR0912, PLR0915
        if method not in METHODS or not isinstance(params, dict):
            raise ValueError("A known method and object params are required")
        limit = TOOLS[method].max_timeout_ms
        if type(timeout_ms) is not int or not MIN_TIMEOUT_MS <= timeout_ms <= limit:
            raise ValueError(f"timeoutMs must be an integer in {MIN_TIMEOUT_MS}..{limit}")
        if "timeoutMs" in params:
            raise ValueError("Pass timeout_ms separately from host params")
        params, _ = validate_call(method, {**params, "timeoutMs": timeout_ms})
        request_id = uuid.uuid4().hex
        request = {"id": request_id, "method": method, "params": params, "timeoutMs": timeout_ms}
        payload = (json.dumps(request, allow_nan=False, separators=(",", ":")) + "\n").encode("utf-8")
        if len(payload) > MAX_BYTES:
            raise ValueError("Request exceeds 1 MiB")

        writer = None
        dispatched = False
        try:
            # The host deadline bounds mutations. The extra second only lets its
            # final response reach us; it never extends or replays the request.
            async with asyncio.timeout(timeout_ms / 1000 + self.response_grace_seconds):
                reader, writer = await asyncio.open_connection("127.0.0.1", self.port, limit=MAX_BYTES)
                dispatched = True
                writer.write(payload)
                await writer.drain()
                raw = await reader.readuntil(b"\n")
                if len(raw) > MAX_BYTES:
                    raise ValueError("Response exceeds 1 MiB")
                response = load_json(raw.decode("utf-8"))
                if not isinstance(response, dict) or response.get("id") != request_id:
                    raise ValueError("Response id does not match request")
                if ("result" in response) == ("error" in response):
                    raise ValueError("Response must contain exactly one of result or error")
                if "error" in response:
                    error = response["error"]
                    if (
                        not isinstance(error, dict)
                        or not isinstance(error.get("code"), str)
                        or not isinstance(error.get("message"), str)
                        or error.get("outcome") not in ("not_dispatched", "not_applied", "unknown")
                    ):
                        raise ValueError("Malformed host error")
                    raise BridgeError(error)
                if not isinstance(response["result"], dict):
                    raise ValueError("Host result must be an object")
                if method == "status":
                    _flag_extension_mismatch(response["result"])
                return response["result"]
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
            outcome = "unknown" if dispatched else "not_dispatched"
            code = "deadline_exceeded" if isinstance(exc, TimeoutError) else "transport_error"
            hint = ""
            if method == "grid.get" and (isinstance(exc, asyncio.LimitOverrunError) or "exceeds 1 MiB" in str(exc)):
                hint = " The grid.get response exceeded the 1 MiB client cap: lower moduleLimit (64 works with includeParameters) or set includeParameters=false."
            raise bridge_error(
                code,
                f"{type(exc).__name__}: {exc}.{hint} Inspect host state before another write; request was not retried.",
                outcome,
            ) from exc
        finally:
            if writer is not None:
                writer.close()
                with contextlib.suppress(OSError, TimeoutError):
                    await asyncio.wait_for(writer.wait_closed(), timeout=0.2)
