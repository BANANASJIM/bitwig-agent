"""The public tool protocol: strict argument models and their catalog entries, one module per domain."""

from bitwig_agent.protocol.base import ToolSpec
from bitwig_agent.protocol.bundle import export_bundle
from bitwig_agent.protocol.limits import MAX_TIMEOUT_MS
from bitwig_agent.protocol.messages import invalid_params_message, invalid_params_reason, unknown_arguments_message
from bitwig_agent.protocol.registry import CLIENT_SIDE, COMPOSED, TOOLS, unknown_method_message, validate_call

__all__ = [
    "CLIENT_SIDE",
    "COMPOSED",
    "MAX_TIMEOUT_MS",
    "TOOLS",
    "ToolSpec",
    "export_bundle",
    "invalid_params_message",
    "invalid_params_reason",
    "unknown_arguments_message",
    "unknown_method_message",
    "validate_call",
]
