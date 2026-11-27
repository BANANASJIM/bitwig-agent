"""Java package rules from the target architecture; javac enforces the rest through package-private visibility."""

import re
from pathlib import Path

import pytest

JAVA = Path(__file__).resolve().parents[1] / "extension/src/main/java"
BASE = JAVA / "dev/bitwigagent"
COMPOSITION = {"BitwigAgentExtension", "BitwigAgentExtensionDefinition", "HostBridge"}
HOST = JAVA.parents[1] / "host"
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


def test_root_package_is_composition_and_each_host_has_only_its_loader_class():
    assert {path.stem for path in BASE.glob("*.java")} == COMPOSITION
    assert not list(JAVA.glob("*.java"))
    for version in HOST.iterdir():
        loader = "BitwigAgentGridInsertion_" + version.name.replace(".", "_")
        assert [path.name for path in version.iterdir()] == [loader + ".java"]


@pytest.mark.parametrize("path", sources(), ids=lambda p: p.relative_to(BASE).as_posix())
def test_only_nativehost_reflects_into_the_host(path):
    if package(path) != "nativehost":
        assert not NATIVE.search(path.read_text()), f"{path.name} binds host internals outside nativehost"


@pytest.mark.parametrize("path", sorted((BASE / "rpc").glob("*.java")), ids=lambda p: p.name)
def test_rpc_imports_only_core(path):
    for match in INTERNAL_IMPORT.finditer(path.read_text()):
        assert match.group(1) == "core.", f"{path.name} imports dev.bitwigagent.{match.group(1)}"


@pytest.mark.parametrize("path", sources(), ids=lambda p: p.relative_to(BASE).as_posix())
def test_only_composition_names_native_adapters(path):
    if package(path) != "nativehost" and path.stem not in COMPOSITION:
        assert "dev.bitwigagent.nativehost." not in path.read_text(), f"{path.name} uses nativehost, not its port"
