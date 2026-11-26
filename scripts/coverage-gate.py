#!/usr/bin/env python3
"""Fail when lines changed against the base branch are covered below THRESHOLD percent.

Runs the offline Python tests and the Java simulated-host tests under coverage, then
diff-cover reports changed executable lines (Python and Java together) that no test ran.
No global percentage; native bindings are excluded because only native E2E can run them.

  python scripts/coverage-gate.py [--compare-branch origin/main]
"""

import argparse
import subprocess
import sys

from tooling import ROOT, dependency

THRESHOLD = 90
EXCLUDE = ("**/Bitwig614*.java", "**/BitwigAgent614GridInsertion.java", "tests/**", "extension/src/test/**")
BUILD = ROOT / "build"
OUT = BUILD / "coverage"


def run(*command: object) -> None:
    subprocess.run([str(part) for part in command], cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--compare-branch", default="origin/main")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    python_xml, java_xml, java_exec = OUT / "python.xml", OUT / "java.xml", OUT / "java.exec"
    java_exec.unlink(missing_ok=True)
    run(sys.executable, "-m", "pytest", "-q", f"--cov-report=xml:{python_xml}", "--cov")
    run(sys.executable, "scripts/test-java.py", "--jacoco", java_exec)
    run(
        "java", "-jar", dependency("jacoco-cli"), "report", java_exec,
        "--classfiles", BUILD / "test-classes", "--sourcefiles", ROOT / "extension/src/main/java",
        "--xml", java_xml, "--quiet",
    )  # fmt: skip
    return subprocess.run(
        [
            "diff-cover", str(python_xml), str(java_xml),
            "--compare-branch", args.compare_branch,
            "--fail-under", str(THRESHOLD),
            "--exclude", *EXCLUDE,
            "--src-roots", str(ROOT / "extension/src/main/java"),
        ],
        cwd=ROOT, check=False,
    ).returncode  # fmt: skip


if __name__ == "__main__":
    raise SystemExit(main())
