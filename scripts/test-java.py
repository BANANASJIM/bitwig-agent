#!/usr/bin/env python3
"""Run every *Test.java main deterministically; simulated host, never native Bitwig.

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
CORE_TESTS = {"dev.bitwigagent.RpcServerTest", "dev.bitwigagent.HostBridgeTest", "dev.bitwigagent.core.MethodSpecsTest"}


def main() -> None:
    tests = sorted(
        path.relative_to(TEST_ROOT).with_suffix("").as_posix().replace("/", ".")
        for path in TEST_ROOT.rglob("*Test.java")
    )
    missing = CORE_TESTS - set(tests)
    if missing:
        raise RuntimeError(f"Required Java tests missing: {', '.join(sorted(missing))}")
    # Naming/path convention is intentional: a mispackaged test or missing main
    # fails in Java instead of being silently omitted by a source-text heuristic.
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
    sources = [*build.verification_sources(), *sorted(TEST_ROOT.rglob("*.java"))]
    build.compile_java(sources, classes, [gson, api], build.lint_flag(api))
    build.write_protocol_bundle(classes)
    classpath = os.pathsep.join(map(str, [classes, gson, api]))
    print(f"Java simulated-host/transport layer: {len(tests)} test mains", flush=True)
    failures = []
    for test in tests:
        print(f"RUN {test}", flush=True)
        try:
            subprocess.run(["java", "-ea", "-cp", classpath, test], check=True, timeout=60)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            failures.append(test)
            print(f"FAIL {test}: {error}", flush=True)
    if failures:
        raise RuntimeError(f"{len(failures)} Java test mains failed: {', '.join(failures)}")
    print(f"PASS: all {len(tests)} Java test mains; native host NOT exercised", flush=True)


if __name__ == "__main__":
    main()
