"""The public tool protocol: strict argument models and their catalog entries, one module per domain."""

from bitwig_agent.protocol.base import MAX_TIMEOUT_MS, ToolSpec
from bitwig_agent.protocol.bundle import export_bundle
from bitwig_agent.protocol.messages import invalid_params_message, unknown_arguments_message
from bitwig_agent.protocol.registry import CLIENT_SIDE, TOOLS, validate_call

__all__ = [
    "CLIENT_SIDE",
    "MAX_TIMEOUT_MS",
    "TOOLS",
    "ToolSpec",
    "export_bundle",
    "invalid_params_message",
    "unknown_arguments_message",
    "validate_call",
]
