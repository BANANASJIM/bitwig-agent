#!/usr/bin/env python3
"""Build the extension against the host jars host.json names (adapters per version) and a verified Gson artifact."""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

from tooling import dependency

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
SOURCES = ROOT / "extension/src/main/java"
HOST_SOURCES = ROOT / "extension/src/host"
SHARED = ROOT / "src/bitwig_agent/shared"
LINUX_HOST_JAR = "/opt/bitwig-studio/bin/bitwig.jar"


def gson_jar():
    return dependency("gson")


def junit_jar():
    return dependency("junit-console")


def jqwik_jars():
    """Property-based testing for JUnit: the API to compile against and the engine the console launcher discovers."""
    return [dependency("jqwik-api"), dependency("jqwik-engine")]


def api_jar():
    """Installed host jar when BITWIG_API_JAR is set, else the checksum-pinned public extension API."""
    setting = os.environ.get("BITWIG_API_JAR")
    if setting:
        if not Path(setting).is_file():
            raise RuntimeError(f"BITWIG_API_JAR is not a file: {setting}")
        return Path(setting).resolve()
    return dependency("extension-api")


def lint_flag(api):
    """The public API jar carries generics the host jar erases, so casts and raw types only warn against it."""
    return "-Xlint:all,-cast,-rawtypes,-unchecked" if api == dependency("extension-api") else "-Xlint:all"


def verification_sources():
    """Main sources; the per-version host sources need that host's internal classes."""
    return sorted(SOURCES.rglob("*.java"))


def host_jars(release=False):
    """The host jar of each host.json version to compile its adapter against, each verified by SHA-256.

    BITWIG_HOST_JARS names a directory of `<version>.jar` files; otherwise the one BITWIG_API_JAR or installed host jar is
    used for the version whose hash it has. A release build needs every version's jar.
    """
    hosts = {
        host["version"]: host["jarSha256"] for host in json.loads((SHARED / "host.json").read_text(encoding="utf-8"))
    }
    directory = os.environ.get("BITWIG_HOST_JARS")
    if directory:
        jars = {version: Path(directory, f"{version}.jar") for version in hosts}
        jars = {version: jar.resolve() for version, jar in jars.items() if jar.is_file()}
    else:
        default = LINUX_HOST_JAR if sys.platform.startswith("linux") else None
        jar = Path(os.environ.get("BITWIG_API_JAR") or default or "")
        if not jar.is_file():
            raise SystemExit(
                "Set BITWIG_API_JAR to the installed Bitwig API jar (bitwig.jar in the Bitwig Studio install), "
                "or BITWIG_HOST_JARS to a directory of <version>.jar host jars"
            )
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        jars = {version: jar.resolve() for version, sha in hosts.items() if sha == digest}
        if not jars:
            raise SystemExit(f"{jar} (SHA-256 {digest}) is not a supported host jar in host.json")
    for version, jar in jars.items():
        digest = hashlib.sha256(jar.read_bytes()).hexdigest()
        if digest != hosts[version]:
            raise SystemExit(f"{jar} (SHA-256 {digest}) is not the Bitwig {version} jar host.json names")
    missing = [version for version in hosts if version not in jars]
    if not jars or (release and missing):
        raise SystemExit(f"Missing host jars for Bitwig {', '.join(missing)} in BITWIG_HOST_JARS")
    return jars


def classes_digest(classes):
    """Content hash of compiled classes, so two uncommitted builds of one commit stay distinguishable."""
    digest = hashlib.sha256()
    for path in sorted(classes.rglob("*.class")):
        digest.update(path.relative_to(classes).as_posix().encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()[:12]


def git_output(*args):
    return subprocess.run(
        ["git", "-C", str(ROOT), *args], check=True, capture_output=True, text=True, encoding="utf-8"
    ).stdout.strip()


def build_properties(classes):
    """Package version, git short SHA with dirty flag ("unknown" outside git), and a class-content hash."""
    version = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
    try:
        build = git_output("rev-parse", "--short", "HEAD")
        # Untracked .claude/ (agent worktrees) is local tooling, not source: it must not mark the build dirty.
        if git_output("status", "--porcelain", "--", ".", ":(exclude).claude"):
            build += "-dirty"
    except (OSError, subprocess.CalledProcessError):
        build = "unknown"
    return f"version={version}\nbuild={build}+{classes_digest(classes)}\n"


def compile_java(sources, target, classpath, lint="-Xlint:all"):
    target.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "javac",
            "--release",
            "11",
            lint,
            "-Werror",
            "-encoding",
            "UTF-8",
            "-cp",
            os.pathsep.join(map(str, classpath)),
            "-d",
            str(target),
            *map(str, sources),
        ],
        check=True,
    )


def write_resources(directory):
    """The generated protocol bundle and the shared data files, into a classes directory; never committed."""
    subprocess.run(
        [sys.executable, "-m", "bitwig_agent.protocol", "export", "--out", str(directory / "protocol.json")], check=True
    )
    (directory / "shared").mkdir(parents=True, exist_ok=True)
    for path in sorted(SHARED.glob("*.json")):
        shutil.copyfile(path, directory / "shared" / path.name)


def build(release=False):
    jars = host_jars(release)
    dependency = gson_jar()
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java(verification_sources(), classes, [next(iter(jars.values())), dependency])
    for version, jar in jars.items():
        compile_java(sorted((HOST_SOURCES / version).glob("*.java")), classes, [jar, dependency, classes])
    write_resources(classes)
    resources = ROOT / "extension/src/main/resources"
    service = resources / "META-INF/services/com.bitwig.extension.ExtensionDefinition"
    if not service.is_file():
        raise RuntimeError("Missing Bitwig ExtensionDefinition service registration")
    definitions = [line.split("#", 1)[0].strip() for line in service.read_text(encoding="utf-8").splitlines()]
    definitions = [name for name in definitions if name]
    if not definitions or any(not (classes / (name.replace(".", "/") + ".class")).is_file() for name in definitions):
        raise RuntimeError("ExtensionDefinition registration does not name a compiled class")
    output = BUILD / "BitwigAgent.bwextension"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n")
        archive.writestr("bitwig-agent-build.properties", build_properties(classes))
        for directory in [classes, ROOT / "extension/src/main/resources"]:
            if directory.exists():
                for path in sorted(directory.rglob("*")):
                    if path.is_file():
                        archive.write(path, path.relative_to(directory).as_posix())
        # Bundle Gson's runtime classes, never the installed host jar or module descriptor.
        with zipfile.ZipFile(dependency) as source:
            for entry in source.infolist():
                if entry.filename.startswith("com/google/gson/") and not entry.is_dir():
                    archive.writestr(entry.filename, source.read(entry))
        archive.write(ROOT / "LICENSE", "META-INF/LICENSE")
        archive.write(ROOT / "THIRD_PARTY_NOTICE.md", "META-INF/THIRD_PARTY_NOTICE.md")
        archive.write(ROOT / "third-party/GSON-LICENSE.txt", "META-INF/GSON-LICENSE.txt")
    print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", action="store_true", help="require every host.json version's jar")
    build(parser.parse_args().release)
