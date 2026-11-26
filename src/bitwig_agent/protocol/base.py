from __future__ import annotations

from dataclasses import dataclass
from pathlib import PurePath, PurePosixPath, PureWindowsPath
from typing import Annotated, Any, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.config import JsonDict

from bitwig_agent.protocol.limits import (
    DEFAULT_TIMEOUT_MS,
    DEVICE_WINDOW,
    LONG_TIMEOUT_MS,
    MAX_TIMEOUT_MS,
    MIN_TIMEOUT_MS,
)

Nonempty = Annotated[str, Field(min_length=1, max_length=4096)]
TrackId = Annotated[
    str, Field(pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
]


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


class StrictModel(BaseModel):
    model_config = ConfigDict(strict=True, extra="forbid", allow_inf_nan=False)

    @model_validator(mode="after")
    def validate_host_string_lengths(self) -> Self:
        # Java String.length counts UTF-16 units, unlike JSON Schema maxLength.
        for key, field in type(self).model_fields.items():
            value = getattr(self, key)
            if isinstance(value, str):
                length = len(value.encode("utf-16-le", errors="surrogatepass")) // 2
                for constraint in field.metadata:
                    limit = getattr(constraint, "max_length", None)
                    if limit is not None and length > limit:
                        raise ValueError(f"{key} exceeds {limit} UTF-16 code units")
        return self


def long_timeout(default: int = DEFAULT_TIMEOUT_MS) -> Any:
    return Field(default=default, ge=MIN_TIMEOUT_MS, le=LONG_TIMEOUT_MS)


class StatusArgs(StrictModel):
    timeoutMs: int = Field(default=DEFAULT_TIMEOUT_MS, ge=MIN_TIMEOUT_MS, le=MAX_TIMEOUT_MS)


class DeviceArgs(StatusArgs):
    trackId: TrackId


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
    examples: tuple[dict[str, Any], ...]
    requires: tuple[str, ...] = ()
    host: bool = True

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


def tool(
    model: type[BaseModel],
    description: str,
    read_only: bool,
    *examples: dict[str, Any],
    requires: tuple[str, ...] = (),
    host: bool = True,
) -> ToolSpec:
    return ToolSpec(model, description, read_only, examples or ({},), requires, host)
