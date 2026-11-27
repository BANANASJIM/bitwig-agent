"""Map bound host symbols from one re-obfuscated Bitwig jar to another (a new release or another platform).

  PYTHONPATH=scripts python -m remap OLD.jar NEW.jar --harness run.txt [--extra extra.tsv] \\
      --map map.json --report report.md [--classes classmap.json] [--notes notes.md] [--no-order]

Offline: parses class files as data, never loads or runs host code. Steps: (1) whole-jar class map from stable
names, obfuscation-invariant fingerprints and neighbour propagation; (2) bound members aligned inside their mapped
declaring class; (3) body comparison in the old name space; (4) structural checks. `--no-order` disables
declaration-order anchoring for a control run. Symbol inventory formats: see remap/inventory.py.
"""

import argparse
import json
import sys
from pathlib import Path

from . import classmap, inventory, report
from .classfile import read_jar, sha256
from .members import Members
from .symbols import Mapper, checks


def run(old_jar: Path, new_jar: Path, harness: Path, extra: Path | None, use_order: bool = True) -> dict:
    old, new = read_jar(old_jar), read_jar(new_jar)
    cm = classmap.build(old, new)
    symbols = inventory.load(harness, extra, old)
    members = Members(cm, use_order)
    mapped = Mapper(cm, members).map_all(symbols)
    meta = {
        "old_sha256": sha256(old_jar),
        "new_sha256": sha256(new_jar),
        "classes_mapped": len(cm.forward),
        "classes_old": len(old),
        "classes_new": len(new),
    }
    return {"meta": meta, "symbols": mapped, "checks": checks(cm, members, symbols, mapped), "classes": cm}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="remap", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("old_jar", type=Path)
    parser.add_argument("new_jar", type=Path)
    parser.add_argument("--harness", type=Path, required=True, help="harness log of the bound symbols")
    parser.add_argument("--extra", type=Path, help="bound symbols the harness cannot see")
    parser.add_argument("--map", type=Path, required=True, help="symbol map JSON to write")
    parser.add_argument("--report", type=Path, required=True, help="markdown report to write")
    parser.add_argument("--classes", type=Path, help="whole-jar class map JSON to write")
    parser.add_argument("--notes", type=Path, help="markdown inserted into the report after the header")
    parser.add_argument("--no-order", action="store_true", help="control run without declaration-order anchoring")
    args = parser.parse_args(argv)
    result = run(args.old_jar, args.new_jar, args.harness, args.extra, not args.no_order)
    cm = result.pop("classes")
    args.map.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    notes = args.notes.read_text(encoding="utf-8") if args.notes else ""
    args.report.write_text(report.render(result, notes), encoding="utf-8")
    if args.classes:
        args.classes.write_text(json.dumps({"map": cm.forward, "evidence": cm.evidence}) + "\n", encoding="utf-8")
    statuses: dict[str, int] = {}
    for record in result["symbols"].values():
        statuses[record["status"]] = statuses.get(record["status"], 0) + 1
    print(f"{len(result['symbols'])} symbols: {statuses}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
