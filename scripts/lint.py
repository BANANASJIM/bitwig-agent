#!/usr/bin/env python3
"""Static gates that need no Bitwig API jar: ruff, mypy, Java format, duplicate detection.

python scripts/lint.py                  run every gate
python scripts/lint.py --fix            apply ruff fixes and reformat Python and Java
python scripts/lint.py --update-cpd-baseline
"""

import argparse
import json
import os
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from pathlib import Path

from tooling import download_verified, sha256_of

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / "build" / "deps"
CPD_BASELINE = ROOT / "config" / "cpd-baseline.json"

PALANTIR_VERSION = "2.102.0"
PALANTIR_BASE = (
    f"https://repo1.maven.org/maven2/com/palantir/javaformat/palantir-java-format-native/{PALANTIR_VERSION}"
    f"/palantir-java-format-native-{PALANTIR_VERSION}-nativeImage-"
)
PALANTIR_BINARIES = {
    ("Linux", "x86_64"): ("linux-glibc_x86-64", "0e8e4d55b6d7cd4803279437c364463cff117f136a7750e78f295a7570e54450"),
    ("Linux", "aarch64"): ("linux-glibc_aarch64", "1bfa7a0a983237c539dfcf4078f28468696059b5a2049fcb20181009aa44bf2d"),
    ("Darwin", "arm64"): ("macos_aarch64", "24a984105fe95594bbb360b7799ada7db13dddeb9fce69640df471061040b5a4"),
}
PALANTIR_FLAGS = ["--palantir", "--skip-reflowing-long-strings"]

PMD_VERSION = "7.29.0"
PMD_SHA256 = "d27177c185b837fa57f097e482d1253468aa035379c5eb2671fa0a279109b179"
PMD_URL = f"https://github.com/pmd/pmd/releases/download/pmd_releases%2F{PMD_VERSION}/pmd-dist-{PMD_VERSION}-bin.zip"
CPD_RUNS = {
    "java": (100, ["extension/src"]),
    "python": (50, ["src", "tests", "scripts"]),
}


def java_sources() -> list[str]:
    return sorted(str(path) for path in (ROOT / "extension/src").rglob("*.java"))


def palantir() -> Path:
    key = (platform.system(), platform.machine())
    if key not in PALANTIR_BINARIES:
        raise RuntimeError(f"No palantir-java-format binary pinned for {key}")
    classifier, sha256 = PALANTIR_BINARIES[key]
    binary = download_verified(
        f"{PALANTIR_BASE}{classifier}.bin",
        sha256,
        DEPS / f"palantir-java-format-{PALANTIR_VERSION}-{classifier}.bin",
        200 * 1024 * 1024,
    )
    binary.chmod(0o755)
    return binary


def pmd_script() -> Path:
    home = DEPS / f"pmd-bin-{PMD_VERSION}"
    if not home.exists():
        archive = download_verified(PMD_URL, PMD_SHA256, DEPS / f"pmd-dist-{PMD_VERSION}-bin.zip", 300 * 1024 * 1024)
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(DEPS)
    return home / "bin" / "pmd"


def java_environment() -> dict[str, str]:
    env = dict(os.environ)
    if env.get("JAVA"):
        env["PATH"] = str(Path(env["JAVA"]).resolve().parent) + os.pathsep + env["PATH"]
    return env


def run(command: list[str], env: dict[str, str] | None = None) -> bool:
    return subprocess.run(command, cwd=ROOT, env=env, check=False).returncode == 0


def ruff_check(fix: bool) -> bool:
    return run([sys.executable, "-m", "ruff", "check", *(["--fix"] if fix else []), "."])


def ruff_format(fix: bool) -> bool:
    return run([sys.executable, "-m", "ruff", "format", *([] if fix else ["--check"]), "."])


def mypy(fix: bool) -> bool:
    return run([sys.executable, "-m", "mypy"])


def java_format(fix: bool) -> bool:
    flags = ["--replace"] if fix else ["--dry-run", "--set-exit-if-changed"]
    return run([str(palantir()), *PALANTIR_FLAGS, *flags, *java_sources()])


def cpd_duplicates(language: str) -> Counter[tuple[int, tuple[str, ...]]]:
    minimum_tokens, directories = CPD_RUNS[language]
    command = ["bash", str(pmd_script()), "cpd", "--minimum-tokens", str(minimum_tokens), "--language", language]
    for directory in directories:
        command += ["--dir", str(ROOT / directory)]
    command += ["--format", "xml", "--no-fail-on-violation"]
    result = subprocess.run(command, cwd=ROOT, env=java_environment(), check=True, capture_output=True, text=True)
    namespace = {"c": "https://pmd-code.org/schema/cpd-report"}
    found: Counter[tuple[int, tuple[str, ...]]] = Counter()
    for duplication in ET.fromstring(result.stdout).findall("c:duplication", namespace):
        files = sorted(
            Path(entry.attrib["path"]).relative_to(ROOT).as_posix()
            for entry in duplication.findall("c:file", namespace)
        )
        found[(int(duplication.attrib["tokens"]), tuple(files))] += 1
    return found


def cpd_snapshot() -> dict[str, list[dict[str, object]]]:
    return {
        language: [
            {"tokens": tokens, "files": list(files), "count": count}
            for (tokens, files), count in sorted(cpd_duplicates(language).items())
        ]
        for language in CPD_RUNS
    }


def cpd(fix: bool) -> bool:
    baseline = json.loads(CPD_BASELINE.read_text())
    ok = True
    for language, (minimum_tokens, _) in CPD_RUNS.items():
        allowed = Counter({(entry["tokens"], tuple(entry["files"])): entry["count"] for entry in baseline[language]})
        new = cpd_duplicates(language) - allowed
        for (tokens, files), count in sorted(new.items()):
            print(f"new {language} duplication ({tokens} tokens, >= {minimum_tokens}) x{count}: {', '.join(files)}")
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
    parser.add_argument("--update-cpd-baseline", action="store_true", help="record current duplicates as the baseline")
    args = parser.parse_args()
    if args.update_cpd_baseline:
        CPD_BASELINE.parent.mkdir(exist_ok=True)
        CPD_BASELINE.write_text(json.dumps(cpd_snapshot(), indent=2) + "\n")
        print(f"wrote {CPD_BASELINE.relative_to(ROOT)} ({sha256_of(CPD_BASELINE)[:12]})")
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
