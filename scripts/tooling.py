"""Checksum-verified downloads of pinned build and lint tools into build/deps."""

import hashlib
import tempfile
import urllib.request
from pathlib import Path


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
