"""Checksum-verified downloads of pinned build and lint tools into build/deps."""

import hashlib
import os
import tempfile
import tomllib
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEPS_TABLE = ROOT / "extension/deps.toml"
DEPS_DIR = ROOT / "build" / "deps"


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def download_verified(url: str, sha256: str, target: Path, limit: int) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if sha256_of(target) != sha256:
            raise RuntimeError(f"Dependency checksum mismatch: {target}; remove it before rebuilding")
        return target
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read(limit + 1)
    if len(data) > limit or hashlib.sha256(data).hexdigest() != sha256:
        raise RuntimeError(f"Downloaded checksum mismatch: {url}")
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
        temporary = Path(output.name)
        output.write(data)
    try:
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


def dependency(name: str) -> Path:
    entry = tomllib.loads(DEPS_TABLE.read_text(encoding="utf-8"))[name]
    return download_verified(entry["url"], entry["sha256"], DEPS_DIR / entry["file"], entry["max_bytes"])


def dependency_group(prefix: str) -> list[str]:
    return [name for name in tomllib.loads(DEPS_TABLE.read_text(encoding="utf-8")) if name.startswith(prefix)]


def host_paths(value):
    """Examples spell absolute paths POSIX-style; the host accepts only its own absolute spelling."""
    if isinstance(value, str) and value.startswith("/"):
        return os.path.abspath(value)
    if isinstance(value, dict):
        return {key: host_paths(item) for key, item in value.items()}
    if isinstance(value, list):
        return [host_paths(item) for item in value]
    return value


if __name__ == "__main__":
    import sys

    for name in sys.argv[1:]:
        print(dependency(name).name)
