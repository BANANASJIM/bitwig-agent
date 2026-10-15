#!/usr/bin/env python3
"""Copy the locally built extension; preserve an existing different build."""
import argparse
import hashlib
from pathlib import Path
import shutil
import sys


def default_destination() -> Path:
    base = Path.home()
    if sys.platform in ("darwin", "win32"):
        base /= "Documents"
    return base / "Bitwig Studio" / "Extensions"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=default_destination())
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1] / "build" / "BitwigAgent.bwextension"
    if not source.is_file():
        parser.error("build/BitwigAgent.bwextension is missing; run python scripts/build.py first")
    args.destination.mkdir(parents=True, exist_ok=True)
    target = args.destination / source.name
    if target.exists():
        old = target.read_bytes()
        if old == source.read_bytes():
            print(f"Already installed: {target}")
            return
        # Bitwig can discover extensions recursively, including hidden folders.
        # Keep recoverable builds outside the extension scan directory.
        backup = args.destination.parent / ".bitwig-agent-backups" / hashlib.sha256(old).hexdigest()
        backup.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, backup / target.name)
        print(f"Previous build preserved: {backup / target.name}")
    staging = target.with_suffix(".bwextension.tmp")
    shutil.copy2(source, staging)
    staging.replace(target)
    print(f"Installed: {target}")
    print("Enable Bitwig Agent under Bitwig Settings > Controllers; no MIDI ports are required.")


if __name__ == "__main__":
    main()
