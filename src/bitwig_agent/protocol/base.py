from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePath, PurePosixPath, PureWindowsPath
from typing import Annotated, Any, Self, get_args

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.config import JsonDict

from bitwig_agent.protocol.annotations import Annotations
from bitwig_agent.protocol.limits import (
    DEFAULT_TIMEOUT_MS,
    DEVICE_WINDOW,
    LONG_TIMEOUT_MS,
    MAX_TIMEOUT_MS,
    MIN_TIMEOUT_MS,
)

PROTOCOL = "0.5"


class Outcome(StrEnum):
    """What a write did, in rising severity: a result's outcome is the worst of its entries."""

    ALREADY_MATCHED = "already_matched"
    APPLIED = "applied"
    SENT = "sent"
    NOT_APPLIED = "not_applied"
    UNKNOWN = "unknown"


class Verification(StrEnum):
    """How a result was confirmed: the `verification` field of a result."""

    NATIVE_READBACK = "native_readback"
    API_READBACK = "api_readback"
    FILE_READBACK = "file_readback"
    SYSTEM_READBACK = "system_readback"


SUCCEEDED = frozenset({Outcome.ALREADY_MATCHED, Outcome.APPLIED, Outcome.SENT})


def _has_outcome(node: Any) -> bool:
    if isinstance(node, dict):
        return "outcome" in node or any(_has_outcome(v) for v in node.values())
    return isinstance(node, list) and any(_has_outcome(v) for v in node)


def leaf_rule_holds(node: Any) -> bool:
    """Every applied object with no outcome-bearing child names its verification (ack's release is the one exception)."""
    if isinstance(node, list):
        return all(leaf_rule_holds(v) for v in node)
    if not isinstance(node, dict):
        return True
    if not all(leaf_rule_holds(v) for v in node.values()):
        return False
    leaf = node.get("outcome") == Outcome.APPLIED and not any(_has_outcome(v) for v in node.values())
    return not leaf or "verification" in node or "released" in node


def worst(outcomes: Iterable[str]) -> Outcome:
    """The outcome of a result composed of entries: the most severe one."""
    return max(map(Outcome, outcomes), key=list(Outcome).index)


def shared_verification(entries: Iterable[Mapping[str, Any]]) -> Verification | None:
    """The leaf rule for a result composed of others: the one verification all its applied entries name, else none."""
    seen = {
        Verification(e["verification"]) for e in entries if e.get("outcome") == Outcome.APPLIED and "verification" in e
    }
    return seen.pop() if len(seen) == 1 else None


class ErrorCode(StrEnum):
    """An error always means nothing changed; the code says what the caller can do about it."""

    INVALID_PARAMS = "invalid_params"
    STALE = "stale"
    CONFLICT = "conflict"
    PENDING = "pending"
    UNSAFE_STATE = "unsafe_state"
    UNAVAILABLE = "unavailable"
    REFUSED = "refused"
    INCOMPATIBLE = "incompatible"
    UNKNOWN_METHOD = "unknown_method"
    INTERNAL = "internal"


_REASONS_BY_CODE: dict[ErrorCode, tuple[str, ...]] = {
    ErrorCode.INVALID_PARAMS: (
        "invalid_params",
        "invalid_request",
        "invalid_target",
        "kind_mismatch",
        "unsupported_scope",
        "unsupported_unit",
        "unknown_name",
        "ambiguous_name",
        "out_of_bounds",
        "invalid_file",
        "no_notes",
        "unverifiable_clip",
    ),
    ErrorCode.STALE: ("target_not_found", "target_changed", "unknown_hold"),
    ErrorCode.CONFLICT: (
        "target_exists",
        "overlap",
        "limit_reached",
        "readback_mismatch",
        "side_effect",
        "not_reached",
    ),
    ErrorCode.PENDING: ("busy", "unresolved"),
    ErrorCode.UNSAFE_STATE: ("unsafe_state", "engine_inactive", "unsaved_changes", "not_isolated"),
    ErrorCode.UNAVAILABLE: (
        "not_ready",
        "native_unavailable",
        "outside_window",
        "deadline",
        "transport",
        "not_observed",
        "unsupported_platform",
    ),
    ErrorCode.REFUSED: (
        "host_refused",
        "routing_cycle",
        "needs_confirmation",
        "path_not_allowed",
        "job_failed",
        "cancelled",
    ),
    ErrorCode.INCOMPATIBLE: ("protocol_mismatch", "host_mismatch"),
    ErrorCode.UNKNOWN_METHOD: ("unknown_method",),
    ErrorCode.INTERNAL: ("internal",),
}
REASONS: dict[str, ErrorCode] = {reason: code for code, names in _REASONS_BY_CODE.items() for reason in names}
"""The closed reason list: each reason belongs to one error code and also explains a result entry."""


class ReasonError(ValueError):
    """A validator's refusal with an invalid_params reason more specific than invalid_params, and maybe a next."""

    def __init__(self, reason: str, message: str, next: str | None = None):
        if REASONS[reason] is not ErrorCode.INVALID_PARAMS:
            raise ValueError(f"{reason} is not an invalid_params reason")
        super().__init__(message)
        self.reason = reason
        self.next = next


def vocabulary() -> dict[str, Any]:
    return {
        "protocol": PROTOCOL,
        "outcomes": [o.value for o in Outcome],
        "errorCodes": [c.value for c in ErrorCode],
        "verifications": [v.value for v in Verification],
        "reasons": {reason: code.value for reason, code in REASONS.items()},
    }


Nonempty = Annotated[str, Field(min_length=1, max_length=4096)]
Uuid = Annotated[str, Field(pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")]


UnitValue = Annotated[float, Field(ge=0, le=1)]
UTF16_BYTES_PER_UNIT = 2
MAX_STRING_UNITS = 1024
MAX_BEAT = 4194304
FIRST_PRINTABLE_ASCII = 32
DELETE_CHARACTER = 127
LAST_C1_CONTROL = 159


def has_control_character(text: str) -> bool:
    return any(ord(c) < FIRST_PRINTABLE_ASCII or DELETE_CHARACTER <= ord(c) <= LAST_C1_CONTROL for c in text)


def drop_default(schema: JsonDict) -> None:
    schema.pop("default", None)


def omitted(**kwargs: Any) -> Any:
    """Absent is allowed; explicit null is not part of the wire contract."""
    return Field(default=None, json_schema_extra=drop_default, **kwargs)


def validate_local_path(value: str) -> PurePath:
    """The parsed path, in the POSIX or Windows spelling it is a normalized absolute path in."""
    if not value.startswith("//") and not has_control_character(value):
        for flavour in (PurePosixPath, PureWindowsPath):
            path = flavour(value)
            if path.is_absolute() and str(path) == value and ".." not in path.parts:
                return path
    raise ValueError("path must be an absolute normalized path without control characters")


def union_metadata(annotation: Any) -> tuple[Any, ...]:
    """Constraints on the members of an optional or union annotation, which pydantic does not hoist into field.metadata."""
    return tuple(
        constraint
        for member in get_args(annotation)
        for annotated in getattr(member, "__metadata__", ())
        for constraint in getattr(annotated, "metadata", ())
    )


class StrictModel(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", allow_inf_nan=False)

    @model_validator(mode="after")
    def validate_host_string_lengths(self) -> Self:
        # Java String.length counts UTF-16 units, unlike JSON Schema maxLength.
        for key, field in type(self).model_fields.items():
            value = getattr(self, key)
            if isinstance(value, str):
                length = len(value.encode("utf-16-le", errors="surrogatepass")) // 2
                for constraint in (*field.metadata, *union_metadata(field.annotation)):
                    limit = getattr(constraint, "max_length", None)
                    if limit is not None and length > limit:
                        raise ValueError(f"{key} exceeds {limit} UTF-16 code units")
        return self


def long_timeout(default: int = DEFAULT_TIMEOUT_MS) -> Any:
    return Field(default=default, ge=MIN_TIMEOUT_MS, le=LONG_TIMEOUT_MS)


class StatusArgs(StrictModel):
    timeoutMs: int = Field(default=DEFAULT_TIMEOUT_MS, ge=MIN_TIMEOUT_MS, le=MAX_TIMEOUT_MS)


class DeviceArgs(StatusArgs):
    trackId: Uuid


class ParameterArgs(DeviceArgs):
    deviceIndex: int = Field(ge=0, le=DEVICE_WINDOW - 1)
    expectedDeviceName: Nonempty


TRACK = "12345678-1234-1234-1234-123456789abc"
TARGET = {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}


PROJECT_READY = ("projectReady",)
ENGINE = ("engine",)


@dataclass(frozen=True)
class ToolSpec:
    model: type[BaseModel]
    description: str
    read_only: bool
    examples: tuple[dict[str, Any], ...] = ({},)
    requires: tuple[str, ...] = ()
    host: bool = True
    expose: bool = True
    annotations: Annotations | None = None
    summary: str = ""
    records: str | None = None
    inspect: str | None = None
    """The read method that shows the state this write acted on; the registry fills it for every host write."""
    wire: type[BaseModel] | None = None
    """The host's own arguments when model adds fields the client composes from other calls (devices.insert params)."""

    @property
    def host_model(self) -> type[BaseModel]:
        """What the extension validates and receives: the bundle schema and every request to the host."""
        return self.wire or self.model

    @property
    def host_examples(self) -> tuple[dict[str, Any], ...]:
        """The examples the host itself accepts: those without the client-composed fields."""
        composed = self.model.model_fields.keys() - self.host_model.model_fields.keys()
        return tuple(example for example in self.examples if not composed & example.keys())

    @property
    def project_write(self) -> bool:
        return PROJECT_READY[0] in self.requires

    @property
    def default_timeout_ms(self) -> int:
        return int(self.model.model_fields["timeoutMs"].default)

    @property
    def max_timeout_ms(self) -> int:
        for constraint in self.model.model_fields["timeoutMs"].metadata:
            if getattr(constraint, "le", None) is not None:
                return int(constraint.le)
        return MAX_TIMEOUT_MS

    def describe(self) -> dict[str, Any]:
        return {
            "description": self.description,
            "readOnly": self.read_only,
            "inputSchema": self.model.model_json_schema(),
            "examples": list(self.examples),
        }
