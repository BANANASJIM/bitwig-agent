"""The shipped native name tables: shape and last real-jar verification offline, the real-jar check where a jar is.

The real-jar check runs for each host.json entry whose jar is one of `BITWIG_HOST_JARS` (os.pathsep-separated) or the
installed `/opt/bitwig-studio/bin/bitwig.jar`; CI has no proprietary jar, so there it only checks the recorded hashes.
After editing a table, re-verify it on its jar: `PYTHONPATH=scripts python -m remap.table verify <table> <jar>
--record tests/fixtures/names_verified.json`.
"""

import json
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from remap import classfile, table

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / "src/bitwig_agent/shared"
HOSTS = json.loads((SHARED / "host.json").read_text(encoding="utf-8"))
RECORD = json.loads((ROOT / "tests/fixtures/names_verified.json").read_text(encoding="utf-8"))
JARS = [Path(p) for p in os.environ.get("BITWIG_HOST_JARS", "/opt/bitwig-studio/bin/bitwig.jar").split(os.pathsep)]


def names(entry: dict) -> dict:
    return json.loads((SHARED / f"{entry['names']}.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("entry", HOSTS, ids=lambda e: e["names"])
def test_every_member_has_a_descriptor_and_its_table_was_verified_on_the_entry_jar(entry):
    assert table.shape_errors(names(entry)) == []
    path = SHARED / f"{entry['names']}.json"
    assert RECORD[entry["names"]] == {"jarSha256": entry["jarSha256"], "tableSha256": table.file_sha256(path)}, (
        "the table changed since its last real-jar verify, or was verified on another jar"
    )
    assert path.read_text(encoding="utf-8") == table.render(names(entry)), "tables keep the generated layout"


@pytest.mark.parametrize("entry", HOSTS, ids=lambda e: e["names"])
def test_every_member_resolves_on_the_entry_jar(entry):
    jar = next((p for p in JARS if p.is_file() and classfile.sha256(p) == entry["jarSha256"]), None)
    if jar is None:
        pytest.skip(f"no local jar with SHA-256 {entry['jarSha256']} (set BITWIG_HOST_JARS)")
    assert table.resolve_errors(names(entry), classfile.read_jar(jar)) == []
