"""Native verification verdicts and the status they give each capability on each supported host."""

from __future__ import annotations

import datetime
import hashlib
import json
from dataclasses import dataclass
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from bitwig_agent.protocol.bundle import export_bundle
from bitwig_agent.protocol.capabilities import CAPABILITIES, PARTIAL, TAXONOMY, Capability, Partial
from bitwig_agent.protocol.shared import shared

CONTRACT_KEYS = ("host", "inputSchema", "readOnly", "requires")
"""What a verdict depends on: a method's description may change, its contract may not."""
HEADLINE_PLATFORM = "linux"
"""The only platform with a native test instance."""

Platform = Literal["linux", "macos", "windows"]
Sha256 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Opaque = Annotated[str, Field(pattern=r"^[A-Za-z0-9._:+-]+$")]
"""An identifier, never a path or free text."""


def _undescribed(node: Any) -> Any:
    """A JSON schema without its description texts (a property named description is kept)."""
    if isinstance(node, dict):
        return {k: _undescribed(v) for k, v in node.items() if not (k == "description" and isinstance(v, str))}
    if isinstance(node, list):
        return [_undescribed(v) for v in node]
    return node


def contract_hash(entry: dict[str, Any]) -> str:
    """The SHA-256 of a protocol bundle method entry's contract."""
    contract = json.dumps({key: _undescribed(entry[key]) for key in CONTRACT_KEYS}, sort_keys=True)
    return "sha256:" + hashlib.sha256(contract.encode()).hexdigest()


def contract_hashes() -> dict[str, str]:
    """By method, its current contract hash."""
    return {name: contract_hash(entry) for name, entry in export_bundle()["methods"].items()}


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Host(_Strict):
    version: str
    jarSha256: Sha256
    platform: Platform


class Extension(_Strict):
    commit: str = Field(pattern=r"^[0-9a-f]{7,40}$")
    build: Opaque


class Check(_Strict):
    capability: str
    methods: dict[str, str] = Field(min_length=1)
    """Each method the check exercised, with its contract hash at the tested commit."""
    result: Literal["pass", "fail"]
    judgement: Literal["tester", "readback"]
    """Who decided the result: a person, or the recorded readback alone."""

    @field_validator("capability")
    @classmethod
    def _known(cls, capability: str) -> str:
        if capability not in {known.id for known in TAXONOMY}:
            raise ValueError(f"unknown capability {capability}")
        return capability


class Record(_Strict):
    """One native run's verdicts. The evidence itself (commands, outputs) stays private; `evidence` refers to it."""

    format: Literal[1]
    date: datetime.date
    host: Host
    extension: Extension
    checks: list[Check] = Field(min_length=1)
    evidence: Opaque


@dataclass(frozen=True)
class Standing:
    """Where one capability stands: what the tagged methods cover and the hosts a current verdict passes on."""

    capability: Capability
    methods: tuple[str, ...]
    partial: Partial | None
    verified_on: tuple[str, ...]
    """`<version> <platform>` labels, newest version first."""

    @property
    def coverage(self) -> Literal["full", "partial", "none"]:
        if not self.methods:
            return "none"
        return "partial" if self.partial else "full"


@dataclass(frozen=True)
class Gap:
    """A capability with something available but no passing current verdict on a host."""

    capability: Capability
    methods: tuple[str, ...]
    """The tagged methods that lack current evidence."""
    reason: str


def _version(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def host_label(version: str, platform: str) -> str:
    return f"{version} {platform}"


def headline_host() -> str:
    """The newest supported Bitwig version on the headline platform."""
    return host_label(max((entry["version"] for entry in shared("host")), key=_version), HEADLINE_PLATFORM)


def _tagged(capability: str) -> tuple[str, ...]:
    return tuple(name for name, ids in CAPABILITIES.items() if capability in ids)


class Verdicts:
    """The records' checks for each capability, judged against the current contracts and supported hosts."""

    def __init__(self, records: list[Record]) -> None:
        self.hashes = contract_hashes()
        self.jars = {(entry["version"], entry["jarSha256"]) for entry in shared("host")}
        self.checks = [(record, check) for record in records for check in record.checks]

    def stale(self, check: Check) -> tuple[str, ...]:
        """The capability's tagged methods this check gives no current evidence for."""
        return tuple(m for m in _tagged(check.capability) if check.methods.get(m) != self.hashes[m])

    def _on(self, capability: str, host: str) -> list[tuple[Record, Check]]:
        return [
            (record, check)
            for record, check in self.checks
            if check.capability == capability and host_label(record.host.version, record.host.platform) == host
        ]

    def _supported(self, record: Record) -> bool:
        return (record.host.version, record.host.jarSha256) in self.jars

    def newest(self, capability: str, host: str) -> tuple[Record, Check] | None:
        """The newest current check of capability on host (a failure wins a tie), if any."""
        current = [(r, c) for r, c in self._on(capability, host) if self._supported(r) and not self.stale(c)]
        return max(current, key=lambda pair: (pair[0].date, pair[1].result == "fail"), default=None)

    def passes(self, capability: str, host: str) -> bool:
        newest = self.newest(capability, host)
        return newest is not None and newest[1].result == "pass"

    def standings(self) -> list[Standing]:
        hosts = sorted(
            {host_label(record.host.version, record.host.platform) for record, _ in self.checks},
            key=lambda label: _version(label.split()[0]),
            reverse=True,
        )
        return [
            Standing(
                capability,
                _tagged(capability.id),
                PARTIAL.get(capability.id),
                tuple(host for host in hosts if self.passes(capability.id, host)),
            )
            for capability in TAXONOMY
        ]

    def gaps(self, host: str) -> list[Gap]:
        """The to-verify queue for host: available or partial capabilities without a passing current verdict there."""
        out = []
        for standing in self.standings():
            if standing.coverage == "none" or host in standing.verified_on:
                continue
            out.append(Gap(standing.capability, *self._missing(standing, host)))
        return sorted(out, key=lambda gap: ("high", "medium", "low").index(gap.capability.tier))

    def _missing(self, standing: Standing, host: str) -> tuple[tuple[str, ...], str]:
        id_ = standing.capability.id
        if self.newest(id_, host):
            return standing.methods, "failed"
        on_host = self._on(id_, host)
        if not on_host:
            return standing.methods, "never verified"
        record, check = max(on_host, key=lambda pair: pair[0].date)
        if not self._supported(record):
            return standing.methods, "stale: host jar not in host.json"
        stale = self.stale(check)
        return stale, f"stale: contract changed ({', '.join(stale)})"
