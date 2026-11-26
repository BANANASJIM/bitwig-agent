#!/usr/bin/env python3
"""Static gates that need no Bitwig API jar: ruff, mypy, Java format, duplicate detection.

Inputs are the git-tracked files only, so local untracked scripts never reach a gate or the baseline.

python scripts/lint.py                  run every gate
python scripts/lint.py --fix            apply ruff fixes and reformat Python and Java
python scripts/lint.py --update-cpd-baseline
"""

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

from tooling import download_verified

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / "build" / "deps"
CPD_BASELINE = ROOT / "config" / "cpd-baseline.json"

PALANTIR_VERSION = "2.102.0"
MAVEN = "https://repo1.maven.org/maven2"
PALANTIR_NATIVE_BASE = (
    f"{MAVEN}/com/palantir/javaformat/palantir-java-format-native/{PALANTIR_VERSION}"
    f"/palantir-java-format-native-{PALANTIR_VERSION}-nativeImage-"
)
PALANTIR_BINARIES = {
    ("Linux", "x86_64"): ("linux-glibc_x86-64", "0e8e4d55b6d7cd4803279437c364463cff117f136a7750e78f295a7570e54450"),
    ("Linux", "aarch64"): ("linux-glibc_aarch64", "1bfa7a0a983237c539dfcf4078f28468696059b5a2049fcb20181009aa44bf2d"),
    ("Darwin", "arm64"): ("macos_aarch64", "24a984105fe95594bbb360b7799ada7db13dddeb9fce69640df471061040b5a4"),
}
PALANTIR_JARS = [
    (
        "com/palantir/javaformat/palantir-java-format/2.102.0/palantir-java-format-2.102.0.jar",
        "8074d7e21832edcad422f8dbf6002d296551963bec410daad97317210a55b5c1",
    ),
    (
        "com/palantir/javaformat/palantir-java-format-spi/2.102.0/palantir-java-format-spi-2.102.0.jar",
        "ac4b8e44d90e09ee4ceafb6ad3083097871a161cc88feab6bd87378f4e4859bc",
    ),
    (
        "com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar",
        "b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c",
    ),
    (
        "com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.jar",
        "8a8f81cf9b359e3f6dfa691a1e776985c061ef2f223c9b2c80753e1b458e8064",
    ),
    (
        "org/functionaljava/functionaljava/5.0/functionaljava-5.0.jar",
        "377ad140e7d26ba04fadf219b09d7e1c74bc0232fa4010b20c1c79db11f9670e",
    ),
    (
        "com/fasterxml/jackson/core/jackson-core/2.22.3/jackson-core-2.22.3.jar",
        "8a501126a385b25841915d839508f8a66e2a0dbc8a6709d055ef3b3e852b094c",
    ),
    (
        "com/fasterxml/jackson/core/jackson-databind/2.22.3/jackson-databind-2.22.3.jar",
        "556db5439e206114346043f68d200497dc96a0bca62a360a81784092ebd0e0a9",
    ),
    (
        "com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar",
        "21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0",
    ),
    (
        "com/fasterxml/jackson/datatype/jackson-datatype-guava/2.22.3/jackson-datatype-guava-2.22.3.jar",
        "30d44f810bd73264f2b8e46c799f5a5dc933acf827493d73bfba5525c647d6c0",
    ),
    (
        "com/fasterxml/jackson/datatype/jackson-datatype-jdk8/2.22.3/jackson-datatype-jdk8-2.22.3.jar",
        "f223061a5aaecdc9506458143807c73bcd1a744d6b3bbb8da4703a0abc408f7d",
    ),
    (
        "com/fasterxml/jackson/module/jackson-module-parameter-names/2.22.3/jackson-module-parameter-names-2.22.3.jar",
        "3f085667cd7b2dd9794a837b6e4a5c57779132bfc37b49b5c457870eee9d60ba",
    ),
    (
        "com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar",
        "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7",
    ),
]
JAVAC_PACKAGES = ["api", "code", "file", "main", "model", "parser", "processing", "tree", "util"]
PALANTIR_FLAGS = ["--palantir", "--skip-reflowing-long-strings"]

CPD_RUNS = {
    "java": (100, ".java", ("extension/src",)),
    "python": (50, ".py", ("src", "tests", "scripts", "examples")),
}


def tracked(*prefixes: str, suffix: str) -> list[str]:
    listing = subprocess.run(
        ["git", "ls-files", "-z", "--", *prefixes], cwd=ROOT, check=True, capture_output=True, text=True
    ).stdout
    return sorted(name for name in listing.split("\0") if name.endswith(suffix))


def python_sources() -> list[str]:
    return tracked(suffix=".py")


def java_sources() -> list[str]:
    return tracked("extension/src", suffix=".java")


def formatter_command() -> list[str]:
    key = (platform.system(), platform.machine())
    if key in PALANTIR_BINARIES:
        classifier, sha256 = PALANTIR_BINARIES[key]
        binary = download_verified(
            f"{PALANTIR_NATIVE_BASE}{classifier}.bin",
            sha256,
            DEPS / f"palantir-java-format-{PALANTIR_VERSION}-{classifier}.bin",
            200 * 1024 * 1024,
        )
        binary.chmod(0o755)
        return [str(binary)]
    jars = [
        str(download_verified(f"{MAVEN}/{path}", sha256, DEPS / "palantir" / Path(path).name, 50 * 1024 * 1024))
        for path, sha256 in PALANTIR_JARS
    ]
    exports = [f"--add-exports=jdk.compiler/com.sun.tools.javac.{name}=ALL-UNNAMED" for name in JAVAC_PACKAGES]
    return ["java", *exports, "-cp", os.pathsep.join(jars), "com.palantir.javaformat.java.Main"]


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
            "--no-fail-on-violation",
        ]
        result = subprocess.run(command, cwd=ROOT, check=True, stdout=subprocess.PIPE)
    namespace = {"c": "https://pmd-code.org/schema/cpd-report"}
    report = ET.fromstring(result.stdout)
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


def cpd_snapshot() -> dict[str, list[dict[str, object]]]:
    return {
        language: [
            {"files": list(names), "count": count, "tokens": tokens}
            for names, (count, tokens) in sorted(cpd_duplicates(language).items())
        ]
        for language in CPD_RUNS
    }


def cpd(fix: bool) -> bool:
    baseline = json.loads(CPD_BASELINE.read_text())
    ok = True
    for language in CPD_RUNS:
        allowed = {tuple(entry["files"]): (entry["count"], entry["tokens"]) for entry in baseline[language]}
        current = cpd_duplicates(language)
        for names, (count, tokens) in sorted(current.items()):
            base_count, base_tokens = allowed.get(names, (0, 0))
            if count > base_count or tokens > base_tokens:
                print(f"new {language} duplication x{count} ({tokens} tokens; baseline x{base_count}, {base_tokens}):")
                print(f"  {', '.join(names)}")
                ok = False
        for names in sorted(set(allowed) - set(current)):
            print(f"stale {language} baseline entry (fixed or unparsed): {', '.join(names)}")
            ok = False
    if not ok:
        print("run scripts/lint.py --update-cpd-baseline only after removing duplication, never to admit it")
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
    parser.add_argument("--update-cpd-baseline", action="store_true", help="record current duplicates as the baseline")
    args = parser.parse_args()
    if args.update_cpd_baseline:
        CPD_BASELINE.parent.mkdir(exist_ok=True)
        CPD_BASELINE.write_text(json.dumps(cpd_snapshot(), indent=2) + "\n")
        print(f"wrote {CPD_BASELINE.relative_to(ROOT)}")
        return 0
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
