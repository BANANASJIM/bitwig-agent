"""The guarded call path of grid.apply: host calls with write timeouts, and the stop that ends a run on an unclean result."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from bitwig_agent.errors import BridgeError
from bitwig_agent.grid.read import GridCall
from bitwig_agent.protocol import TOOLS

WRITE_TIMEOUT_MS = 10000
CLEAN = ("applied", "already_matched")
TOPOLOGY_PREFIX = "grid.modules."

T = TypeVar("T")


class Stop(Exception):
    """A dispatched call ended unclean; carries the raw response."""

    def __init__(self, method: str, raw: dict[str, Any], outcome: str):
        self.method, self.raw, self.outcome = method, raw, outcome
        super().__init__(method)


def verify(method: str, result: dict[str, Any], key: str | None = None) -> None:
    entries = result.get(key) if key else None
    bad = (
        result.get("status") not in (None, "observed")
        or result.get("batchStatus") not in (None, "applied")
        or result.get("completion") == "unknown"
        or (result.get("matchesRequested") is False and method == "grid.connections.set")
        or any(e.get("status") not in CLEAN for e in entries or [])
    )
    if bad:
        unknown = (
            result.get("batchStatus") == "unknown"
            or result.get("completion") == "unknown"
            or any(e.get("status") == "unknown" for e in entries or [])
            or result.get("status") == "unknown"
        )
        raise Stop(method, result, "unknown" if unknown else "not_applied")


class Channel:
    def __init__(self, client: Any, target: dict[str, Any], timeout_ms: int):
        self.client, self.target, self.timeout_ms = client, target, timeout_ms
        self.wrote = False
        self.topo_written = False

    def _timeout(self, method: str) -> int:
        spec = TOOLS[method]
        if spec.max_timeout_ms > WRITE_TIMEOUT_MS:
            return spec.max_timeout_ms
        return max(self.timeout_ms, WRITE_TIMEOUT_MS) if spec.project_write else self.timeout_ms

    async def call(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        spec = TOOLS[method]
        timeout = self._timeout(method)
        if spec.project_write:
            self.wrote = True
            self.topo_written |= method.startswith(TOPOLOGY_PREFIX)
        try:
            host_params = {**self.target, **params} if "trackId" in spec.model.model_fields else params
            result: dict[str, Any] = await self.client.call(method, host_params, timeout)
        except BridgeError as exc:
            raise Stop(method, {"error": exc.error}, exc.error.get("outcome", "unknown")) from exc
        return result

    async def read(self, reader: Callable[..., Awaitable[T]], *args: Any, **kwargs: Any) -> T:
        """Run a grid.read reader over this channel; its failures end the run like any unclean call."""
        call: GridCall = self.call
        try:
            return await reader(call, *args, **kwargs)
        except BridgeError as exc:
            raise Stop("grid.get", {"error": exc.error}, exc.error.get("outcome", "unknown")) from exc
