"""Java package rules from the target architecture; javac enforces the rest through package-private visibility."""

import re
from pathlib import Path

import pytest

JAVA = Path(__file__).resolve().parents[1] / "extension/src/main/java"
BASE = JAVA / "dev/bitwigagent"
COMPOSITION = {"BitwigAgentExtension", "BitwigAgentExtensionDefinition", "HostBridge"}
LOADER_CLASSES = {"BitwigAgent614GridInsertion"}  # the native loader expects this exact unnamed-package name
NATIVE = re.compile(
    r"^import (static )?(java\.lang\.reflect|com\.bitwig\.(flt|base))\.|Class\.forName|\.getDeclared\w*\(|\.getMethod\(|setAccessible|MethodHandles",
    re.M,
)
INTERNAL_IMPORT = re.compile(r"^import (?:static )?dev\.bitwigagent\.((?:[a-z]\w*\.)*)[A-Z]", re.M)


def package(path: Path) -> str:
    return ".".join(path.parent.relative_to(BASE).parts)


def sources() -> list[Path]:
    return sorted(BASE.rglob("*.java"))


@pytest.mark.parametrize("path", sources(), ids=lambda p: p.relative_to(BASE).as_posix())
def test_package_declaration_matches_directory(path):
    declared = re.search(r"^package ([\w.]+);", path.read_text(), re.M)
    assert declared
    assert declared.group(1) == ".".join(("dev", "bitwigagent", *path.parent.relative_to(BASE).parts))


def test_root_package_is_composition_and_unnamed_package_is_the_loader_class():
    assert {path.stem for path in BASE.glob("*.java")} == COMPOSITION
    assert {path.stem for path in JAVA.glob("*.java")} == LOADER_CLASSES


@pytest.mark.parametrize("path", sources(), ids=lambda p: p.relative_to(BASE).as_posix())
def test_only_bitwig614_reflects_into_the_host(path):
    if package(path) != "bitwig614":
        assert not NATIVE.search(path.read_text()), f"{path.name} binds host internals outside bitwig614"


@pytest.mark.parametrize("path", sorted((BASE / "rpc").glob("*.java")), ids=lambda p: p.name)
def test_rpc_imports_only_core(path):
    for match in INTERNAL_IMPORT.finditer(path.read_text()):
        assert match.group(1) == "core.", f"{path.name} imports dev.bitwigagent.{match.group(1)}"
