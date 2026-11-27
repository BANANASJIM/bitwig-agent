"""The harness configuration: machine paths from a git-ignored TOML file outside the code."""

import os
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / "native-hosts.local.toml"


@dataclass(frozen=True)
class Host:
    name: str
    root: Path
    prefs_seed: Path


@dataclass(frozen=True)
class Config:
    state_root: Path
    display: str
    port: int
    fixture_seed: Path
    hosts: dict[str, Host]

    def host(self, name: str) -> Host:
        if name not in self.hosts:
            raise SystemExit(f"Unknown host {name!r}; configured: {', '.join(self.hosts)}")
        return self.hosts[name]

    def iso(self, host: Host) -> Path:
        return self.state_root / "iso" / host.name

    @property
    def fixture(self) -> Path:
        return self.state_root / "fixtures" / self.fixture_seed.name

    @property
    def runs(self) -> Path:
        return self.state_root / "runs"


def _path(value: str) -> Path:
    return Path(value).expanduser()


def parse(data: dict[str, Any]) -> Config:
    return Config(
        state_root=_path(data["state_root"]),
        display=data["display"],
        port=int(data["port"]),
        fixture_seed=_path(data["fixture_seed"]),
        hosts={
            name: Host(name, _path(host["root"]), _path(host["prefs_seed"])) for name, host in data["hosts"].items()
        },
    )


def load() -> Config:
    path = Path(os.environ.get("BWA_NATIVE_CONFIG") or LOCAL)
    if not path.is_file():
        raise SystemExit(f"No harness config at {path}: copy native-hosts.example.toml there and edit it")
    with path.open("rb") as handle:
        return parse(tomllib.load(handle))
