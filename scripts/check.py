#!/usr/bin/env python3
"""Run separated evidence layers; never install/launch/control a native host by default.

From an existing development environment:
  python scripts/check.py --layer offline
  python scripts/check.py --layer coverage  # not default: repeats the offline and java test runs
  python scripts/check.py  # BITWIG_API_JAR=/path/to/bitwig.jar selects the installed host jar
  python scripts/check.py --layer native --allow-native --native-config /path/to/run.json

Default layers: lint, offline Python, Java simulated-host, Java/MCP fixture. Missing
requested prerequisites fail, never skip-to-green. Native is an opt-in read-only
preflight, NOT music/UI/persistence E2E acceptance. See smoke-native.py --help.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
LAYERS = ("lint", "offline", "java", "fixture", "extension", "coverage", "native")
HOST_JAR = Path(os.environ.get("BITWIG_API_JAR") or "/opt/bitwig-studio/bin/bitwig.jar")
DESCRIPTIONS = {
    "lint": "Static gates: ruff, mypy, Java format, duplicate detection; no API jar or native host",
    "offline": "Python unit/adapter fixtures; no JVM or native host",
    "java": "Java transport and simulated-host tests; installed or pinned public API jar",
    "fixture": "Real MCP/Python/Java protocol + registry fixture; NOT native editing",
    "extension": "Builds the extension against the installed host jar; runs by default only when that jar exists",
    "coverage": "Opt-in: re-runs Python and Java tests; changed lines must be at least 90% covered",
    "native": "Opt-in read-only native preflight; NOT music/UI/persistence E2E",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--layer", choices=LAYERS, action="append", help="repeat to select; default lint, offline, java, fixture"
    )
    parser.add_argument("--allow-native", action="store_true", help="explicit permission for read-only native queries")
    parser.add_argument("--native-config", type=Path, help="explicit fixture/PID/window/display/artifacts JSON")
    parser.add_argument("--report", type=Path, help="write machine-readable layer outcomes here")
    args = parser.parse_args()
    if args.native_config:
        args.native_config = args.native_config.resolve()
    child_env = dict(os.environ)
    if child_env.get("BITWIG_API_JAR"):
        child_env["BITWIG_API_JAR"] = str(Path(child_env["BITWIG_API_JAR"]).resolve())
    selected = set(args.layer or ("lint", "offline", "java", "fixture"))
    if not args.layer:
        if HOST_JAR.is_file():
            selected.add("extension")
        else:
            print(f"NOTE: {HOST_JAR} not found; the extension is not compiled against the real host jar", flush=True)
    if (args.allow_native or args.native_config) and "native" not in selected:
        parser.error("native options require --layer native; no native run is implicit")
    outcomes = []
    for layer in LAYERS:
        if layer not in selected:
            print(f"NOT REQUESTED [{layer}]: {DESCRIPTIONS[layer]}", flush=True)
            outcomes.append({"layer": layer, "status": "not_requested", "scope": DESCRIPTIONS[layer]})
            continue
        print(f"RUN [{layer}]: {DESCRIPTIONS[layer]}", flush=True)
        started = time.monotonic()
        result: dict[str, Any] = {"layer": layer, "scope": DESCRIPTIONS[layer]}
        if layer == "native" and not (args.allow_native and args.native_config and args.native_config.is_file()):
            result.update(status="blocked", reason="Native requires --allow-native and an existing --native-config")
        else:
            commands = {
                "lint": [sys.executable, str(ROOT / "scripts/lint.py")],
                "offline": [sys.executable, "-m", "pytest", "-q"],
                "java": [sys.executable, str(ROOT / "scripts/test-java.py")],
                "fixture": [sys.executable, str(ROOT / "scripts/smoke-mcp-java.py")],
                "extension": [sys.executable, str(ROOT / "scripts/build.py")],
                "coverage": [sys.executable, str(ROOT / "scripts/coverage-gate.py")],
                "native": [
                    sys.executable,
                    str(ROOT / "scripts/smoke-native.py"),
                    "--allow-native",
                    "--config",
                    str(args.native_config),
                ],
            }
            command = commands[layer]
            result["command"] = command
            try:
                completed = subprocess.run(
                    command,
                    cwd=ROOT,
                    env=child_env,
                    check=False,
                    timeout={
                        "lint": 600,
                        "offline": 900,
                        "java": 900,
                        "fixture": 240,
                        "extension": 600,
                        "coverage": 1500,
                        "native": 180,
                    }[layer],
                )
                result.update(
                    status="passed" if completed.returncode == 0 else "failed", returncode=completed.returncode
                )
            except (OSError, subprocess.TimeoutExpired) as exc:
                result.update(status="failed", reason=str(exc))
        result["seconds"] = round(time.monotonic() - started, 3)
        print(f"{result['status'].upper()} [{layer}] {result.get('reason', '')}", flush=True)
        outcomes.append(result)
    report = {
        "recordedAt": datetime.now(UTC).isoformat(),
        "layers": outcomes,
        "boundary": "Passing checks are scoped evidence, not host E2E completion or user approval.",
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return int(any(result["status"] in {"failed", "blocked"} for result in outcomes))


if __name__ == "__main__":
    sys.exit(main())
