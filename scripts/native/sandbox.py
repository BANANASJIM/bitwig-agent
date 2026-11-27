"""The bwrap sandbox of one host: its mount table, the launch command and environment, and the mount proof.

Java takes `user.home` from passwd, not $HOME, so the isolated directories are bound over the real
paths instead of changing HOME. Audio guard: hiding audio-host-api-plugins leaves Bitwig only JACK
(served by pipewire-jack), and PIPEWIRE_PROPS makes pipewire-jack drop every connect request Bitwig
makes (self/other connect modes) and keeps the session manager from linking it (node.autoconnect).
"""

import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

INSTALL = Path("/opt/bitwig-studio")
PIPEWIRE_PROPS = "{ jack.self-connect-mode=ignore-all jack.other-connect-mode=ignore node.autoconnect=false }"


@dataclass(frozen=True)
class Mount:
    kind: str  # bwrap option without dashes: bind, ro-bind or tmpfs
    target: Path
    source: Path | None = None
    snapshot: bool = False  # a real directory the run must leave unchanged


def mounts(root: Path, iso: Path, home: Path, tmp: Path) -> list[Mount]:
    config = home / ".BitwigStudio"
    table = [] if root == INSTALL else [Mount("bind", INSTALL, root)]
    return [
        *table,
        Mount("tmpfs", INSTALL / "lib/audio-host-api-plugins"),
        Mount("bind", config, iso / "BitwigStudio", snapshot=True),
        Mount("ro-bind", config / "installed-packages", config / "installed-packages"),
        Mount("bind", home / "Bitwig Studio", iso / "BWS", snapshot=True),
        Mount("bind", home / ".cache", iso / "cache"),  # every desktop application writes here: no snapshot
        Mount("bind", home / ".java", iso / "java", snapshot=True),
        Mount("bind", tmp, iso / "tmp", snapshot=True),
    ]


def iso_dirs(table: list[Mount], iso: Path) -> list[Path]:
    return [m.source for m in table if m.source is not None and m.source.is_relative_to(iso)]


def argv(table: list[Mount], project: Path) -> list[str]:
    args = ["bwrap", "--dev-bind", "/", "/"]
    for m in table:
        args += [f"--{m.kind}", *([str(m.source)] if m.source else []), str(m.target)]
    return [*args, str(INSTALL / "bitwig-studio"), str(project)]


def environment(base: Mapping[str, str], display: str, port: int) -> dict[str, str]:
    env = {k: v for k, v in base.items() if k != "WAYLAND_DISPLAY"}
    return {**env, "DISPLAY": display, "PIPEWIRE_PROPS": PIPEWIRE_PROPS, "BITWIG_AGENT_PORT": str(port)}


def mount_points(mountinfo: str) -> set[str]:
    """Mount points from /proc/<pid>/mountinfo (fifth field, octal escapes such as \\040 decoded)."""
    return {
        re.sub(r"\\([0-7]{3})", lambda m: chr(int(m[1], 8)), line.split()[4]) for line in mountinfo.splitlines() if line
    }


def missing_mounts(mountinfo: str, targets: list[str]) -> list[str]:
    present = mount_points(mountinfo)
    return [t for t in targets if t not in present]
