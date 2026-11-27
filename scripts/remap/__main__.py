"""Map bound host symbols from one re-obfuscated Bitwig jar to another (a new release or another platform).

  PYTHONPATH=scripts python -m remap OLD.jar NEW.jar --from-table names_old.json --out names_new.json \\
      [--report report.md] [--notes notes.md] [--no-order]
  PYTHONPATH=scripts python -m remap OLD.jar NEW.jar --harness run.txt [--extra extra.tsv] \\
      --map map.json --report report.md [--classes classmap.json] [--notes notes.md] [--no-order]

Offline: parses class files as data, never loads or runs host code. Steps: (1) whole-jar class map from stable
names, obfuscation-invariant fingerprints and neighbour propagation; (2) bound members aligned inside their mapped
declaring class; (3) body comparison in the old name space; (4) structural checks. `--no-order` disables
declaration-order anchoring for a control run. The bound symbols are either a name table bound on OLD.jar (its
members carry descriptors; writes the table for NEW.jar, failing on any key not mapped exact or confident; see
remap/table.py) or a harness log (see remap/inventory.py).
"""

import argparse
import json
import sys
from collections.abc import Callable
from pathlib import Path

from . import classmap, inventory, report, table
from .classfile import read_jar, sha256
from .classmap import Jar
from .inventory import Symbol
from .members import Members
from .symbols import Mapper, checks


def mapped(old_jar: Path, new_jar: Path, bound: Callable[[Jar], list[Symbol]], use_order: bool) -> dict:
    """The symbol map of the symbols `bound` reads against the old jar."""
    old, new = read_jar(old_jar), read_jar(new_jar)
    cm = classmap.build(old, new)
    symbols = bound(old)
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


def run(old_jar: Path, new_jar: Path, harness: Path, extra: Path | None, use_order: bool = True) -> dict:
    return mapped(old_jar, new_jar, lambda old: inventory.load(harness, extra, old), use_order)


def run_table(old_jar: Path, new_jar: Path, names: table.Table, use_order: bool = True) -> dict:
    """The symbol map of a name table, with the table it gives for the new jar and the keys that failed."""
    keyed: list[tuple[dict[str, Symbol], dict[str, Symbol]]] = []

    def bound(old: Jar) -> list[Symbol]:
        keyed.append(table.symbols(names, old))
        return list(dict.fromkeys([*keyed[0][0].values(), *keyed[0][1].values()]))

    result = mapped(old_jar, new_jar, bound, use_order)
    result["table"], result["errors"] = table.generate(names, *keyed[0], result["symbols"])
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="remap", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("old_jar", type=Path)
    parser.add_argument("new_jar", type=Path)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--from-table", type=Path, help="name table bound on OLD.jar")
    source.add_argument("--harness", type=Path, help="harness log of the bound symbols")
    parser.add_argument("--out", type=Path, help="with --from-table: the name table for NEW.jar to write")
    parser.add_argument("--extra", type=Path, help="bound symbols the harness cannot see")
    parser.add_argument("--map", type=Path, help="symbol map JSON to write (required with --harness)")
    parser.add_argument("--report", type=Path, help="markdown report to write")
    parser.add_argument("--classes", type=Path, help="whole-jar class map JSON to write")
    parser.add_argument("--notes", type=Path, help="markdown inserted into the report after the header")
    parser.add_argument("--no-order", action="store_true", help="control run without declaration-order anchoring")
    args = parser.parse_args(argv)
    if args.from_table and not args.out:
        parser.error("--from-table requires --out")
    if args.harness and not (args.map and args.report):
        parser.error("--harness requires --map and --report")
    if args.from_table:
        names = json.loads(args.from_table.read_text(encoding="utf-8"))
        result = run_table(args.old_jar, args.new_jar, names, not args.no_order)
    else:
        result = run(args.old_jar, args.new_jar, args.harness, args.extra, not args.no_order)
    cm = result.pop("classes")
    generated, errors = result.pop("table", None), result.pop("errors", [])
    if args.map:
        args.map.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    if args.report:
        notes = args.notes.read_text(encoding="utf-8") if args.notes else ""
        args.report.write_text(report.render(result, notes), encoding="utf-8")
    if args.classes:
        args.classes.write_text(json.dumps({"map": cm.forward, "evidence": cm.evidence}) + "\n", encoding="utf-8")
    statuses: dict[str, int] = {}
    for record in result["symbols"].values():
        statuses[record["status"]] = statuses.get(record["status"], 0) + 1
    print(f"{len(result['symbols'])} symbols: {statuses}", file=sys.stderr)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if generated is not None:
        args.out.write_text(table.render(generated), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
