#!/usr/bin/env python3
"""Generate the next host's native name table from the previous table and a host-symbol map.

  python scripts/names_table.py src/bitwig_agent/shared/names_6_1_4.json map_614_617.json \
      src/bitwig_agent/shared/names_6_1_7.json --classes classmap_full.json

The map's `symbols` are keyed by the previous host's identity: a class name, `Owner.member(descriptor)` or
`Owner.field:type`, where Owner is the class the lookup runs on; each `new` value is the same identity in the next host.
Obfuscated member names are not consistent across classes, so every member is resolved on its own owner class. The
code passes parameter types itself, so a member resolves when every mapped overload of its owner and name agrees on
one next-host name. A class the symbol map does not bind (one the code only compares against) comes from the optional
whole-jar class map, `{"map": {"pkg/Old": "pkg/New"}}`. Any key that does not resolve fails the run.
"""

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

MEMBER = re.compile(r"(?P<owner>[^(:]+)\.(?P<name>[^.(:]+)(?P<descriptor>[(:].*)")


def generate(previous, symbols, whole_jar=None):
    classes, errors = {}, []
    jar = {old.replace("/", "."): new.replace("/", ".") for old, new in (whole_jar or {}).items()}
    for key, name in previous["classes"].items():
        mapped = symbols.get(name)
        if mapped and mapped["kind"] == "class":
            classes[key] = mapped["new"]
        elif name in jar:
            classes[key] = jar[name]
        else:
            errors.append(f"class {key}: {name} is not mapped")
    overloads = defaultdict(list)
    for identity, mapped in symbols.items():
        match, target = MEMBER.fullmatch(identity), MEMBER.fullmatch(mapped["new"])
        if match and target and mapped["kind"] != "class":
            overloads[match["owner"], match["name"]].append(target)
    members = {}
    for key, member in previous["members"].items():
        owner = previous["classes"][member["owner"]]
        targets = overloads.get((owner, member["name"]), [])
        names = {target["name"] for target in targets}
        owners = {target["owner"] for target in targets}
        if len(names) != 1:
            errors.append(f"member {key}: {owner}.{member['name']} maps to {sorted(names) or 'nothing'}")
        elif member["owner"] in classes and owners != {classes[member["owner"]]}:
            errors.append(f"member {key}: mapped on {sorted(owners)}, not on {classes[member['owner']]}")
        else:
            members[key] = {"owner": member["owner"], "name": names.pop()}
    if errors:
        raise SystemExit("\n".join(errors))
    return {"classes": classes, "members": members}


def render(table):
    """The checked-in layout: one class per line, one member object per line, keys sorted."""
    classes = ",\n".join(f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in sorted(table["classes"].items()))
    members = ",\n".join(f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in sorted(table["members"].items()))
    return f'{{\n "classes": {{\n{classes}\n }},\n "members": {{\n{members}\n }}\n}}\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("previous", type=Path, help="previous host's names_<version>.json")
    parser.add_argument("symbols", type=Path, help="host-symbol map with a `symbols` object")
    parser.add_argument("out", type=Path, help="names_<version>.json to write")
    parser.add_argument("--classes", type=Path, help="whole-jar class map with a `map` object")
    args = parser.parse_args()
    whole_jar = json.loads(args.classes.read_text())["map"] if args.classes else None
    table = generate(json.loads(args.previous.read_text()), json.loads(args.symbols.read_text())["symbols"], whole_jar)
    args.out.write_text(render(table))
    print(f"{args.out}: {len(table['classes'])} classes, {len(table['members'])} members", file=sys.stderr)


if __name__ == "__main__":
    main()
