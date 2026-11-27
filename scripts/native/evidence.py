"""The evidence directory of one run: file snapshots, run.json and the proof summary."""

import gzip
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

from bitwig_agent.audio import pipewire

from . import sandbox

MONITOR_CLEAN = re.compile(
    r"monitor stopped .* outside \S+: dump=0 poll=0; pw-dump exited early: no; pw-link poller died: no;"
)
SILENT_OUTCOMES = ("applied", "already_matched")


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def snapshot(roots: list[Path]) -> list[str]:
    """`path<TAB>size<TAB>mtime` for every entry under roots, sorted (the classic `find -printf` listing)."""
    rows = []
    for root in roots:
        for top, dirs, files in os.walk(root):
            for name in [*dirs, *files]:
                stat = os.lstat(os.path.join(top, name))
                rows.append(f"{os.path.join(top, name)}\t{stat.st_size}\t{stat.st_mtime}")
    return sorted(rows)


def write_snapshot(path: Path, rows: list[str]) -> None:
    with gzip.open(path, "wt", encoding="utf-8") as handle:
        handle.writelines(f"{row}\n" for row in rows)


def read_snapshot(path: Path) -> list[str]:
    with gzip.open(path, "rt", encoding="utf-8") as handle:
        return handle.read().splitlines()


def diff(before: list[str], after: list[str]) -> list[str]:
    old, new = set(before), set(after)
    return sorted([f"-{row}" for row in old - new] + [f"+{row}" for row in new - old], key=lambda r: r[1:])


def read_run(run: Path) -> dict[str, Any]:
    path = run / "run.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}


def update_run(run: Path, **fields: Any) -> dict[str, Any]:
    data = {**read_run(run), **fields}
    (run / "run.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return data


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def _silent(path: Path) -> bool:
    try:
        result = json.loads(_text(path))
    except ValueError:
        return False
    return result.get("outcome") in SILENT_OUTCOMES and result.get("silent") is True


def checks(run: Path) -> list[tuple[bool, str]]:
    """Each proof line of a finished run as (passed, text)."""
    data = read_run(run)
    targets = data.get("mounts", [])
    missing = sandbox.missing_mounts(_text(run / "mountinfo.txt"), targets)
    changed = _text(run / "real.diff").splitlines()
    return [
        (data.get("jarSha256") == data.get("expectedJarSha256"), f"host jar sha256 {data.get('jarSha256')}"),
        (bool(targets) and not missing, f"mountinfo has all {len(targets)} sandbox mounts; missing: {missing}"),
        (
            (run / "real-after.tsv.gz").is_file() and not changed,
            f"real directories unchanged: {len(changed)} diff lines",
        ),
        (
            bool(MONITOR_CLEAN.search(_text(run / "monitor.log"))),
            f"audio monitor saw no Bitwig link outside {pipewire.SINK} and pw-dump ran throughout",
        ),
        (data.get("strayLinks") == [], f"links after launch only to {pipewire.SINK}: stray {data.get('strayLinks')}"),
        (_silent(run / "silent-route.json"), "audio route --mode=silent read back silent"),
        (data.get("lockReleased") is True, "lock released after quit"),
        (data.get("quit") in {"clean", "term"}, f"quit: {data.get('quit')}"),
    ]


def summary(run: Path) -> tuple[bool, str]:
    rows = checks(run)
    passed = all(ok for ok, _ in rows)
    data = read_run(run)
    lines = [
        f"run {run.name}: host {data.get('host')} port {data.get('port')} extension sha256 {data.get('extensionSha256')}",
        *(f"{'PASS' if ok else 'FAIL'}  {text}" for ok, text in rows),
        f"{'PASS' if passed else 'FAIL'}  overall",
    ]
    return passed, "\n".join(lines) + "\n"
