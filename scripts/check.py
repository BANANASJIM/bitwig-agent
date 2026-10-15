#!/usr/bin/env python3
"""Run separated evidence layers; never install/launch/control a native host by default.

From an existing development environment:
  python scripts/check.py --layer offline
  BITWIG_API_JAR=/path/to/installed/bitwig.jar python scripts/check.py
  python scripts/check.py --layer native --allow-native --native-config /path/to/run.json

Default layers: offline Python, Java simulated-host, Java/MCP fixture. Missing
requested prerequisites fail, never skip-to-green. Native is an opt-in read-only
preflight, NOT music/UI/persistence E2E acceptance. See smoke-native.py --help.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
LAYERS = ("offline", "java", "fixture", "native")
DESCRIPTIONS = {
    "offline": "Python unit/adapter fixtures; no JVM or native host",
    "java": "Java transport and simulated-host tests; installed API jar required",
    "fixture": "Real MCP/Python/Java protocol + registry fixture; NOT native editing",
    "native": "Opt-in read-only native preflight; NOT music/UI/persistence E2E",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--layer", choices=LAYERS, action="append", help="repeat to select; default offline, java, fixture")
    parser.add_argument("--allow-native", action="store_true", help="explicit permission for read-only native queries")
    parser.add_argument("--native-config", type=Path, help="explicit fixture/PID/window/display/artifacts JSON")
    parser.add_argument("--report", type=Path, help="write machine-readable layer outcomes here")
    args = parser.parse_args()
    if args.native_config:
        args.native_config = args.native_config.resolve()
    child_env = dict(os.environ)
    if child_env.get("BITWIG_API_JAR"):
        child_env["BITWIG_API_JAR"] = str(Path(child_env["BITWIG_API_JAR"]).resolve())
    selected = set(args.layer or ("offline", "java", "fixture"))
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
        result = {"layer": layer, "scope": DESCRIPTIONS[layer]}
        if layer in {"java", "fixture"} and not (os.environ.get("BITWIG_API_JAR") and Path(os.environ["BITWIG_API_JAR"]).is_file()):
            result.update(status="blocked", reason="BITWIG_API_JAR must point to the user's installed API jar")
        elif layer == "native" and not (args.allow_native and args.native_config and args.native_config.is_file()):
            result.update(status="blocked", reason="Native requires --allow-native and an existing --native-config")
        else:
            commands = {
                "offline": [sys.executable, "-m", "pytest", "-q"],
                "java": [sys.executable, str(ROOT / "scripts/test-java.py")],
                "fixture": [sys.executable, str(ROOT / "scripts/smoke-mcp-java.py")],
                "native": [sys.executable, str(ROOT / "scripts/smoke-native.py"), "--allow-native", "--config", str(args.native_config)],
            }
            command = commands[layer]
            result["command"] = command
            try:
                completed = subprocess.run(command, cwd=ROOT, env=child_env, check=False,
                                           timeout={"offline": 180, "java": 900, "fixture": 240, "native": 180}[layer])
                result.update(status="passed" if completed.returncode == 0 else "failed", returncode=completed.returncode)
            except (OSError, subprocess.TimeoutExpired) as exc:
                result.update(status="failed", reason=str(exc))
        result["seconds"] = round(time.monotonic() - started, 3)
        print(f"{result['status'].upper()} [{layer}] {result.get('reason', '')}", flush=True)
        outcomes.append(result)
    report = {"recordedAt": datetime.now(timezone.utc).isoformat(), "layers": outcomes,
              "boundary": "Passing checks are scoped evidence, not host E2E completion or user approval."}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return int(any(result["status"] in {"failed", "blocked"} for result in outcomes))


if __name__ == "__main__":
    sys.exit(main())
