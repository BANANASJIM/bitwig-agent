"""The guarded call path of grid.apply: host calls with write timeouts, and the stop that ends a run on an unclean result."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from bitwig_agent.errors import BridgeError
from bitwig_agent.grid.read import GridCall
from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.base import SUCCEEDED, Outcome

WRITE_TIMEOUT_MS = 10000
TOPOLOGY_PREFIX = "grid.modules."

T = TypeVar("T")


class Stop(Exception):
    """A dispatched call ended unclean; carries the raw error or result (an error changed nothing)."""

    def __init__(self, method: str, raw: dict[str, Any]):
        self.method, self.raw = method, raw
        self.outcome: str = raw.get("outcome", Outcome.NOT_APPLIED.value)
        error = raw.get("error")
        self.reason: str | None = (error if isinstance(error, dict) else raw).get("reason")
        super().__init__(method)


def verify(method: str, result: dict[str, Any]) -> None:
    if Outcome(result["outcome"]) not in SUCCEEDED:
        raise Stop(method, result)


class Channel:
    def __init__(self, client: Any, target: dict[str, Any], timeout_ms: int):
        self.client, self.target, self.timeout_ms = client, target, timeout_ms
        self.wrote = False
        self.topo_written = False
        self.applied: list[dict[str, Any]] = []

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
            host_params = {**self.target, **params} if "target" in spec.model.model_fields else params
            result: dict[str, Any] = await self.client.call(method, host_params, timeout)
        except BridgeError as exc:
            raise Stop(method, {"error": exc.error}) from exc
        if result.get("outcome") == Outcome.APPLIED:
            self.applied.append(result)
        return result

    async def read(self, reader: Callable[..., Awaitable[T]], *args: Any, **kwargs: Any) -> T:
        """Run a grid.read reader over this channel; its failures end the run like any unclean call."""
        call: GridCall = self.call
        try:
            return await reader(call, *args, **kwargs)
        except BridgeError as exc:
            raise Stop("grid.export", {"error": exc.error}) from exc
