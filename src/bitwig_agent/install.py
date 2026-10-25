"""Install the matching release extension, or an explicitly supplied local build."""
import argparse
import hashlib
from http.client import HTTPException
from importlib.metadata import version
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.error import URLError
from urllib.request import urlopen


_EXTENSION = "BitwigAgent.bwextension"
_RELEASES = "https://github.com/BANANASJIM/bitwig-agent/releases/download"
_MAX_EXTENSION_BYTES = 32 * 1024 * 1024
_MAX_CHECKSUM_BYTES = 64 * 1024


def _default_destination() -> Path:
    base = Path.home()
    if sys.platform in ("darwin", "win32"):
        base /= "Documents"
    return base / "Bitwig Studio" / "Extensions"


def _download(url: str, limit: int) -> bytes:
    deadline = time.monotonic() + 60
    with urlopen(url, timeout=30) as response:
        chunks = []
        size = 0
        while True:
            if time.monotonic() >= deadline:
                raise ValueError(f"Download timed out: {url}")
            chunk = response.read1(min(64 * 1024, limit + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
            if size > limit:
                raise ValueError(f"Download exceeds {limit} bytes: {url}")
        length = response.headers.get("Content-Length")
        if length is not None and size != int(length):
            raise ValueError(f"Incomplete download: {url}")
    return b"".join(chunks)


def _release_extension(package_version: str) -> bytes:
    base = f"{_RELEASES}/v{package_version}"
    manifest = _download(f"{base}/SHA256SUMS", _MAX_CHECKSUM_BYTES).decode("utf-8")
    checksums = []
    for line in manifest.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([^\r\n]+)", line)
        if match is None:
            raise ValueError("Malformed SHA256SUMS entry")
        digest, filename = match.groups()
        if filename == _EXTENSION:
            checksums.append(digest.lower())
    if len(checksums) != 1:
        raise ValueError(f"SHA256SUMS must contain exactly one entry for {_EXTENSION}")
    data = _download(f"{base}/{_EXTENSION}", _MAX_EXTENSION_BYTES)
    if hashlib.sha256(data).hexdigest() != checksums[0]:
        raise ValueError(f"SHA256 mismatch for {_EXTENSION}; installation unchanged")
    return data


def _atomic_write(target: Path, data: bytes) -> None:
    staging = None
    try:
        with tempfile.NamedTemporaryFile(prefix=f".{target.name}.", suffix=".tmp", dir=target.parent, delete=False) as stream:
            staging = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        staging.replace(target)
    finally:
        if staging is not None:
            staging.unlink(missing_ok=True)


def _install(data: bytes, destination: Path) -> None:
    target = destination / _EXTENSION
    if target.exists():
        old = target.read_bytes()
        if old == data:
            print(f"Already installed: {target}")
            return
        # Bitwig scans recursively: backups must live outside Extensions.
        backup = destination.parent / ".bitwig-agent-backups" / hashlib.sha256(old).hexdigest() / _EXTENSION
        if backup.exists():
            if backup.read_bytes() != old:
                raise ValueError(f"Existing backup has unexpected contents: {backup}")
        else:
            backup.parent.mkdir(parents=True, exist_ok=True)
            _atomic_write(backup, old)
        print(f"Previous build preserved: {backup}")
    destination.mkdir(parents=True, exist_ok=True)
    _atomic_write(target, data)
    print(f"Installed: {target}")
    print("Enable Bitwig Agent under Bitwig Settings > Controllers; no MIDI ports are required.")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="version", version=f"%(prog)s {version('bitwig-agent')}")
    parser.add_argument("--extension", type=Path, help="Install this local extension instead of downloading a release")
    parser.add_argument("--destination", type=Path, default=_default_destination(), help="Bitwig Extensions directory")
    parser.add_argument("--mcp-config", action="store_true", help="Print MCP configuration only; do not download or install")
    args = parser.parse_args(argv)
    if args.mcp_config:
        print(json.dumps({"mcpServers": {"bitwig-agent": {
            "command": sys.executable,
            "args": ["-m", "bitwig_agent"],
            "env": {"BITWIG_AGENT_PORT": "61172"},
        }}}, indent=2))
        return
    try:
        data = args.extension.read_bytes() if args.extension is not None else _release_extension(version("bitwig-agent"))
        _install(data, args.destination)
    except (OSError, ValueError, URLError, HTTPException) as exc:
        parser.exit(1, f"{parser.prog}: error: {exc}\n")


if __name__ == "__main__":
    main()
