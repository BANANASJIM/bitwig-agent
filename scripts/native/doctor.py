"""Preconditions of a harness run: system tools, pixi, the host jars and initialised homes."""

import os
import shutil
import subprocess

from bitwig_agent.protocol.shared import shared

from . import evidence, sandbox
from .config import Config, Host

# System packages, not pixi: pw-link and pactl must match the running PipeWire daemon, and conda-forge has no pactl.
TOOLS = ("bwrap", "xdotool", "pw-link", "pw-dump", "pactl", "magick")
PIXI_MIN = (0, 84, 2)  # task arguments verified on this release


def expected_jar(host: Host) -> str | None:
    return next((e["jarSha256"] for e in shared("host") if e["version"] == host.name), None)


def jar_sha(host: Host) -> str | None:
    jar = host.root / "bin/bitwig.jar"
    return evidence.sha256(jar) if jar.is_file() else None


def pixi_version() -> tuple[int, ...] | None:
    exe = os.environ.get("PIXI_EXE") or shutil.which("pixi")
    if not exe:
        return None
    out = subprocess.run([exe, "--version"], capture_output=True, text=True, encoding="utf-8", check=False).stdout
    return tuple(int(part) for part in out.split()[-1].split("."))


def checks(config: Config) -> list[tuple[bool, str]]:
    rows = [(shutil.which(tool) is not None, f"tool {tool}: {shutil.which(tool)}") for tool in TOOLS]
    pixi = pixi_version()
    rows.append((pixi is not None and pixi >= PIXI_MIN, f"pixi {pixi} (need {PIXI_MIN} or newer)"))
    for host in config.hosts.values():
        found, expected = jar_sha(host), expected_jar(host)
        rows.append((found is not None and found == expected, f"{host.name} jar {found} expected {expected}"))
        iso = config.iso(host)
        rows.append(((iso / "BitwigStudio/prefs").is_dir(), f"{host.name} isolated home {iso} (native-init)"))
    rows.append((config.fixture.is_dir(), f"fixture {config.fixture} (native-init)"))
    rows.append((sandbox.INSTALL.is_dir(), f"install mount point {sandbox.INSTALL}"))
    return rows
