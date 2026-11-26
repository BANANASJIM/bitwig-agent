#!/usr/bin/env python3
"""Static gates that need no Bitwig API jar: ruff, mypy, Java format, duplicate detection.

Inputs are tracked plus untracked-but-not-ignored files, so new files are linted before they are committed.

python scripts/lint.py                  run every gate
python scripts/lint.py --fix            apply ruff fixes and reformat Python and Java
"""

import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

from tooling import dependency, dependency_group

ROOT = Path(__file__).resolve().parents[1]

JAVAC_PACKAGES = ["api", "code", "file", "main", "model", "parser", "processing", "tree", "util"]
PALANTIR_FLAGS = ["--palantir", "--skip-reflowing-long-strings"]

CPD_RUNS = {
    "java": (100, ".java", ("extension/src",)),
    "python": (50, ".py", ("src", "tests", "scripts", "examples")),
}


def tracked(*prefixes: str, suffix: str) -> list[str]:
    listing = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *prefixes],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return sorted(name for name in listing.split("\0") if name.endswith(suffix))


def python_sources() -> list[str]:
    return tracked(suffix=".py")


def java_sources() -> list[str]:
    return tracked("extension/src", suffix=".java")


def formatter_command() -> list[str]:
    native = f"palantir-native-{platform.system().lower()}-{platform.machine().lower()}"
    try:
        binary = dependency(native)
    except KeyError:
        jars = [str(dependency(name)) for name in dependency_group("palantir-jar")]
        exports = [f"--add-exports=jdk.compiler/com.sun.tools.javac.{name}=ALL-UNNAMED" for name in JAVAC_PACKAGES]
        return ["java", *exports, "-cp", os.pathsep.join(jars), "com.palantir.javaformat.java.Main"]
    binary.chmod(0o755)
    return [str(binary)]


def run(command: list[str]) -> bool:
    return subprocess.run(command, cwd=ROOT, check=False).returncode == 0


def ruff_check(fix: bool) -> bool:
    return run(["ruff", "check", *(["--fix"] if fix else []), *python_sources()])


def ruff_format(fix: bool) -> bool:
    return run(["ruff", "format", *([] if fix else ["--check"]), *python_sources()])


def mypy(fix: bool) -> bool:
    strict = run([sys.executable, "-m", "mypy", *tracked("src/bitwig_agent", suffix=".py")])
    loose = run(
        [
            sys.executable,
            "-m",
            "mypy",
            "--config-file",
            "config/mypy-loose.toml",
            *tracked("scripts", "tests", "examples", suffix=".py"),
        ]
    )
    return strict and loose


def java_format(fix: bool) -> bool:
    flags = ["--replace"] if fix else ["--dry-run", "--set-exit-if-changed"]
    return run([*formatter_command(), *PALANTIR_FLAGS, *flags, *java_sources()])


def pmd_launcher() -> list[str]:
    launcher = shutil.which("pmd")
    if launcher is None:
        raise SystemExit("pmd is not on PATH; run through the pixi environment")
    return [launcher] if sys.platform == "win32" else ["bash", launcher]  # the conda wrapper has no shebang


def cpd_duplicates(language: str) -> dict[tuple[str, ...], tuple[int, int]]:
    minimum_tokens, suffix, prefixes = CPD_RUNS[language]
    files = tracked(*prefixes, suffix=suffix)
    with tempfile.TemporaryDirectory() as scratch:
        listing = Path(scratch) / "files.txt"
        report_file = Path(scratch) / "report.xml"
        listing.write_text("\n".join(str(ROOT / name) for name in files) + "\n")
        command = [
            *pmd_launcher(),
            "cpd",
            "--minimum-tokens",
            str(minimum_tokens),
            "--language",
            language,
            "--file-list",
            str(listing),
            "--format",
            "xml",
            "--report-file",
            str(report_file),
            "--no-fail-on-violation",
        ]
        subprocess.run(command, cwd=ROOT, check=True)
        report = ET.fromstring(report_file.read_bytes())
    namespace = {"c": "https://pmd-code.org/schema/cpd-report"}
    if len(report.findall("c:file", namespace)) != len(files):
        raise RuntimeError(f"CPD did not report on all {len(files)} {language} files")
    found: dict[tuple[str, ...], list[int]] = defaultdict(list)
    for duplication in report.findall("c:duplication", namespace):
        names = tuple(
            sorted(
                Path(entry.attrib["path"]).relative_to(ROOT).as_posix()
                for entry in duplication.findall("c:file", namespace)
            )
        )
        found[names].append(int(duplication.attrib["tokens"]))
    return {names: (len(sizes), max(sizes)) for names, sizes in found.items()}


def cpd(fix: bool) -> bool:
    ok = True
    for language in CPD_RUNS:
        for names, (count, tokens) in sorted(cpd_duplicates(language).items()):
            print(f"{language} duplication x{count} ({tokens} tokens):")
            print(f"  {', '.join(names)}")
            ok = False
    return ok


GATES = {
    "ruff check": ruff_check,
    "ruff format": ruff_format,
    "mypy": mypy,
    "java format": java_format,
    "cpd": cpd,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fix", action="store_true", help="apply ruff fixes and reformat instead of checking")
    args = parser.parse_args()
    failed = []
    for name, gate in GATES.items():
        print(f"RUN [{name}]", flush=True)
        if not gate(args.fix):
            failed.append(name)
            print(f"FAILED [{name}]", flush=True)
    if failed:
        print(f"lint failed: {', '.join(failed)}")
        return 1
    print("lint passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
