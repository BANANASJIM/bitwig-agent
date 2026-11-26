"""The public tool protocol: strict argument models and their catalog entries, one module per domain."""

from bitwig_agent.protocol.base import MAX_TIMEOUT_MS, ToolSpec
from bitwig_agent.protocol.messages import invalid_params_message, unknown_arguments_message
from bitwig_agent.protocol.registry import CLIENT_SIDE, TOOLS, max_timeout_ms, validate_call

__all__ = [
    "CLIENT_SIDE",
    "MAX_TIMEOUT_MS",
    "TOOLS",
    "ToolSpec",
    "invalid_params_message",
    "max_timeout_ms",
    "unknown_arguments_message",
    "validate_call",
]
