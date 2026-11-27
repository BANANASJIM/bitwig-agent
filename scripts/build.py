#!/usr/bin/env python3
"""Build the extension using the installed Bitwig API and a verified Gson artifact."""

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


def host_sources(jar):
    """The sources compiled against this host jar, which must be one host.json names by SHA-256."""
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    hosts = json.loads((SHARED / "host.json").read_text())
    versions = [host["version"] for host in hosts if host["jarSha256"] == digest]
    if not versions:
        raise SystemExit(f"{jar} (SHA-256 {digest}) is not a supported host jar in host.json")
    return sorted((HOST_SOURCES / versions[0]).glob("*.java"))


def classes_digest(classes):
    """Content hash of compiled classes, so two uncommitted builds of one commit stay distinguishable."""
    digest = hashlib.sha256()
    for path in sorted(classes.rglob("*.class")):
        digest.update(path.relative_to(classes).as_posix().encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()[:12]


def git_output(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], check=True, capture_output=True, text=True).stdout.strip()


def build_properties(classes):
    """Package version, git short SHA with dirty flag ("unknown" outside git), and a class-content hash."""
    version = tomllib.loads((ROOT / "pyproject.toml").read_text())["project"]["version"]
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


def build():
    default = LINUX_HOST_JAR if sys.platform.startswith("linux") else None
    api = Path(os.environ.get("BITWIG_API_JAR") or default or "")
    if not api.is_file():
        raise SystemExit("Set BITWIG_API_JAR to the installed Bitwig API jar (bitwig.jar in the Bitwig Studio install)")
    dependency = gson_jar()
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java([*verification_sources(), *host_sources(api)], classes, [api, dependency])
    write_resources(classes)
    resources = ROOT / "extension/src/main/resources"
    service = resources / "META-INF/services/com.bitwig.extension.ExtensionDefinition"
    if not service.is_file():
        raise RuntimeError("Missing Bitwig ExtensionDefinition service registration")
    definitions = [line.split("#", 1)[0].strip() for line in service.read_text().splitlines()]
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
    build()
