"""Native name tables (`names_<entry>.json`): resolve them on a jar, verify them, and map one to another jar.

A table maps semantic keys to one host binary's obfuscated names: `classes` {key: "pkg.Cls"} and `members`
{key: {"owner": class key, "name": host name, "desc": JVM descriptor in the host's name space}}. The descriptor makes
every member unique inside its owner class, so the previous table and two jars are enough to produce the next table.

  PYTHONPATH=scripts python -m remap.table verify names_6_1_7.json bitwig.jar [--record names_verified.json]

`verify` checks that every class exists and every member's (name, desc) resolves on its owner class, the way the
binding's lookup resolves it; `--record` then stores the table's and the jar's SHA-256 so offline CI can tell a table
edited since its last real-jar check.
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

from .classfile import read_jar, sha256
from .classmap import Jar
from .hierarchy import Hierarchy
from .inventory import Symbol, internal
from .symbols import symbol_key

Table = dict[str, dict[str, Any]]
FIELD = r"\[*(?:[ZBCSIJFD]|L[^;()\[]+;)"
DESCRIPTOR = re.compile(rf"\((?:{FIELD})*\)(?:V|{FIELD})|{FIELD}")
NEW_MEMBER = re.compile(r"(?P<owner>[^(:]+)\.(?P<name>[^.(:]+):?(?P<desc>[(]?.*)")
SOUND = ("exact", "confident")


def render(table: Table) -> str:
    """The checked-in layout: one class per line, one member object per line, keys sorted."""
    classes = ",\n".join(f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in sorted(table["classes"].items()))
    members = ",\n".join(f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in sorted(table["members"].items()))
    return f'{{\n "classes": {{\n{classes}\n }},\n "members": {{\n{members}\n }}\n}}\n'


def shape_errors(table: Table) -> list[str]:
    """Offline shape: owners are class keys, names are set, descriptors are well-formed JVM descriptors."""
    errors = []
    for key, member in table["members"].items():
        if member is None:  # an explicit null: this entry was decided to have no binding for the key
            continue
        if set(member) != {"owner", "name", "desc"}:
            errors.append(f"member {key}: fields {sorted(member)}, expected owner, name, desc")
        elif member["owner"] not in table["classes"] or not member["name"]:
            errors.append(f"member {key}: unknown owner {member['owner']!r} or empty name")
        elif not DESCRIPTOR.fullmatch(member["desc"]):
            errors.append(f"member {key}: malformed descriptor {member['desc']!r}")
    return errors


def symbol(table: Table, key: str, hierarchy: Hierarchy) -> Symbol | None:
    """The lookup a member key stands for on the table's jar, or None when it does not resolve there."""
    member = table["members"][key]
    owner, name, desc = internal(table["classes"][member["owner"]]), member["name"], member["desc"]
    if owner not in hierarchy.jar:
        return None
    if not desc.startswith("("):
        found = hierarchy.declaring(owner, name, desc, field=True)
        return Symbol("CONST", owner, name, desc, "table") if found else None
    declared, method = hierarchy.public_method(owner, name, desc)
    if method is not None and method.desc == desc:
        return Symbol("METHOD", owner, name, desc, "table", declared)
    if any(m.name == name and m.desc == desc for m in hierarchy.jar[owner].methods):
        return Symbol("DECLARED", owner, name, desc, "table")
    return None


def resolve_errors(table: Table, jar: Jar) -> list[str]:
    """Every class of the table is in the jar and every member's (name, desc) resolves on its owner."""
    hierarchy = Hierarchy(jar)
    errors = [f"class {k}: {v} is not in the jar" for k, v in table["classes"].items() if internal(v) not in jar]
    for key, member in table["members"].items():
        if (
            member is not None
            and internal(table["classes"][member["owner"]]) in jar
            and not symbol(table, key, hierarchy)
        ):
            errors.append(f"member {key}: {member['name']}{member['desc']} does not resolve on its owner")
    return errors


def symbols(table: Table, jar: Jar) -> tuple[dict[str, Symbol], dict[str, Symbol]]:
    """The table as remap symbols, by class key and by member key; explicit nulls are not bound."""
    hierarchy = Hierarchy(jar)
    classes = {k: Symbol("TYPE", internal(v), src="table") for k, v in table["classes"].items()}
    members = {}
    for key, member in table["members"].items():
        if member is None:
            continue
        found = symbol(table, key, hierarchy)
        if found is None:
            raise SystemExit(f"member {key} does not resolve on the source jar; run `verify` first")
        members[key] = found
    return classes, members


def generate(
    previous: Table, classes: dict[str, Symbol], members: dict[str, Symbol], mapped: dict[str, dict[str, Any]]
) -> tuple[Table, list[str]]:
    """The next table from the symbol map; a key whose mapping is not exact or confident is an error."""
    out: Table = {"classes": {}, "members": {}}
    errors = []
    for key, s in classes.items():
        record = mapped[symbol_key(s)]
        if record["status"] in SOUND:
            out["classes"][key] = record["new"]
        else:
            errors.append(f"class {key}: {record['status']} ({record['evidence']})")
    for key, member in previous["members"].items():
        if member is None:
            out["members"][key] = None
            continue
        record = mapped[symbol_key(members[key])]
        target = NEW_MEMBER.fullmatch(record["new"] or "")
        if record["status"] not in SOUND or target is None:
            errors.append(f"member {key}: {record['status']} ({record['evidence']})")
            continue
        out["members"][key] = {"owner": member["owner"], "name": target["name"], "desc": target["desc"]}
    return out, errors


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="remap.table", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)
    verify = sub.add_parser("verify", help="check a table against its jar")
    verify.add_argument("table", type=Path)
    verify.add_argument("jar", type=Path)
    verify.add_argument("--record", type=Path, help="JSON record of verified table and jar hashes to update")
    args = parser.parse_args(argv)
    table = json.loads(args.table.read_text(encoding="utf-8"))
    errors = shape_errors(table) or resolve_errors(table, read_jar(args.jar))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if args.record:
        record = json.loads(args.record.read_text(encoding="utf-8")) if args.record.exists() else {}
        record[args.table.stem] = {"jarSha256": sha256(args.jar), "tableSha256": file_sha256(args.table)}
        args.record.write_text(json.dumps(record, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"{args.table.name}: {len(table['classes'])} classes, {len(table['members'])} members resolve", file=sys.stderr
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
