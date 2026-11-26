#!/usr/bin/env python3
"""Run the JUnit 5 tests in one JVM; simulated host only, never native Bitwig.

Compiles against BITWIG_API_JAR when set, else the pinned public extension API.
Dependencies come from extension/deps.toml, checksum-verified; requires a JDK.
"""

import importlib.util
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "extension/src/test/java"
CORE_TESTS = (
    "dev/bitwigagent/RpcServerTest.java",
    "dev/bitwigagent/HostBridgeTest.java",
    "dev/bitwigagent/core/MethodSpecsTest.java",
)
TIMEOUT_SECONDS = 600


def main() -> None:
    missing = [name for name in CORE_TESTS if not (TEST_ROOT / name).is_file()]
    if missing:
        raise RuntimeError(f"Required Java tests missing: {', '.join(missing)}")
    spec = importlib.util.spec_from_file_location("extension_build", ROOT / "scripts/build.py")
    assert spec is not None
    assert spec.loader is not None
    build = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build)
    classes = build.BUILD / "test-classes"
    if classes.exists():
        shutil.rmtree(classes)
    api = build.api_jar()
    gson = build.gson_jar()
    junit = build.junit_jar()
    sources = [*build.verification_sources(), *sorted(TEST_ROOT.rglob("*.java"))]
    build.compile_java(sources, classes, [gson, api, junit], build.lint_flag(api))
    build.write_resources(classes)
    classpath = os.pathsep.join(map(str, [classes, gson, api]))
    subprocess.run(
        [
            "java",
            "-ea",
            "-jar",
            str(junit),
            "execute",
            "--disable-banner",
            "--disable-ansi-colors",
            "--fail-if-no-tests",
            "--details=tree",
            "--class-path",
            classpath,
            "--scan-class-path",
            str(classes),
        ],
        check=True,
        timeout=TIMEOUT_SECONDS,
    )


if __name__ == "__main__":
    main()
