#!/usr/bin/env python3
"""Run every *Test.java main deterministically; simulated host, never native Bitwig.

Requires BITWIG_API_JAR pointing to the user's installed API jar and a JDK.
Gson is checksum-verified by build.py (downloaded only when not cached).
"""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "extension/src/test/java"
CORE_TESTS = {"dev.bitwigagent.RpcServerTest", "dev.bitwigagent.HostBridgeTest"}


def main() -> None:
    api_setting = os.environ.get("BITWIG_API_JAR")
    if not api_setting or not Path(api_setting).is_file():
        raise RuntimeError("Set BITWIG_API_JAR to your installed Bitwig API jar; no host is downloaded")
    api = Path(api_setting).resolve()
    tests = sorted(path.relative_to(TEST_ROOT).with_suffix("").as_posix().replace("/", ".")
                   for path in TEST_ROOT.rglob("*Test.java"))
    missing = CORE_TESTS - set(tests)
    if missing:
        raise RuntimeError(f"Required Java tests missing: {', '.join(sorted(missing))}")
    # Naming/path convention is intentional: a mispackaged test or missing main
    # fails in Java instead of being silently omitted by a source-text heuristic.
    spec = importlib.util.spec_from_file_location("extension_build", ROOT / "scripts/build.py")
    build = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(build)
    classes = build.BUILD / "test-classes"
    if classes.exists():
        shutil.rmtree(classes)
    gson = build.gson_jar()
    sources = sorted((ROOT / "extension/src/main/java").rglob("*.java"))
    sources += sorted(TEST_ROOT.rglob("*.java"))
    build.compile_java(sources, classes, [gson, api])
    classpath = os.pathsep.join(map(str, [classes, gson, api]))
    print(f"Java simulated-host/transport layer: {len(tests)} test mains", flush=True)
    failures = []
    for test in tests:
        print(f"RUN {test}", flush=True)
        try:
            subprocess.run([os.environ.get("JAVA", "java"), "-ea", "-cp", classpath, test],
                           check=True, timeout=60)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            failures.append(test)
            print(f"FAIL {test}: {error}", flush=True)
    if failures:
        raise RuntimeError(f"{len(failures)} Java test mains failed: {', '.join(failures)}")
    print(f"PASS: all {len(tests)} Java test mains; native host NOT exercised", flush=True)


if __name__ == "__main__":
    main()
