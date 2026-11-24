#!/usr/bin/env python3
"""Build the extension using the installed Bitwig API and a verified Gson artifact."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
GSON_VERSION = "2.10"
GSON_SHA256 = "0cdd163ce3598a20fc04eee71b140b24f6f2a3b35f0a499dbbdd9852e83fbfaf"
GSON_URL = f"https://repo.maven.apache.org/maven2/com/google/code/gson/gson/{GSON_VERSION}/gson-{GSON_VERSION}.jar"


def gson_jar():
    target = BUILD / "deps" / f"gson-{GSON_VERSION}.jar"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if hashlib.sha256(target.read_bytes()).hexdigest() != GSON_SHA256:
            raise RuntimeError(f"Dependency checksum mismatch: {target}; remove it before rebuilding")
        return target
    with urllib.request.urlopen(GSON_URL, timeout=30) as response:
        data = response.read(1024 * 1024 + 1)
    if hashlib.sha256(data).hexdigest() != GSON_SHA256:
        raise RuntimeError("Downloaded Gson checksum mismatch")
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
        temporary = Path(output.name)
        output.write(data)
    try:
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def classes_digest(classes):
    """Content hash of compiled classes, so two uncommitted builds of one commit stay distinguishable."""
    digest = hashlib.sha256()
    for path in sorted(classes.rglob("*.class")):
        digest.update(path.relative_to(classes).as_posix().encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()[:12]


def build_properties(classes):
    """Package version, git short SHA with dirty flag ("unknown" outside git), and a class-content hash."""
    version = tomllib.loads((ROOT / "pyproject.toml").read_text())["project"]["version"]
    try:
        git = lambda *args: subprocess.run(["git", "-C", str(ROOT), *args], check=True,
                                           capture_output=True, text=True).stdout.strip()
        build = git("rev-parse", "--short", "HEAD")
        if git("status", "--porcelain"):
            build += "-dirty"
    except (OSError, subprocess.CalledProcessError):
        build = "unknown"
    return f"version={version}\nbuild={build}+{classes_digest(classes)}\n"


def compile_java(sources, target, classpath):
    target.mkdir(parents=True, exist_ok=True)
    subprocess.run([os.environ.get("JAVAC", "javac"), "--release", "11", "-encoding", "UTF-8",
                    "-cp", os.pathsep.join(map(str, classpath)), "-d", str(target),
                    *map(str, sources)], check=True)


def build():
    api = Path(os.environ.get("BITWIG_API_JAR", "/opt/bitwig-studio/bin/bitwig.jar"))
    if not api.is_file():
        raise RuntimeError("Set BITWIG_API_JAR to the installed Bitwig API jar")
    dependency = gson_jar()
    classes = BUILD / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    compile_java(sorted((ROOT / "extension/src/main/java").rglob("*.java")), classes, [api, dependency])
    resources = ROOT / "extension/src/main/resources"
    service = resources / "META-INF/services/com.bitwig.extension.ExtensionDefinition"
    if not service.is_file():
        raise RuntimeError("Missing Bitwig ExtensionDefinition service registration")
    definitions = [line.split("#", 1)[0].strip() for line in service.read_text().splitlines()]
    definitions = [name for name in definitions if name]
    if not definitions or any(not (classes / (name.replace(".", "/") + ".class")).is_file()
                              for name in definitions):
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
