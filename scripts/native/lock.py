"""One harness run at a time: a flock whose descriptor the launched Bitwig inherits, plus owner.json.

The lock lives as long as any process of the sandbox holds the descriptor, so a crash releases it.
"""

import fcntl
import json
import os
from pathlib import Path
from typing import Any


def runtime() -> Path:
    return Path(os.environ["XDG_RUNTIME_DIR"])


def lock_path() -> Path:
    return runtime() / "bitwig-agent-native.lock"


def owner_path() -> Path:
    return runtime() / "bitwig-agent-native-owner.json"


def acquire() -> int | None:
    """An open descriptor holding the lock, or None when another run holds it."""
    fd = os.open(lock_path(), os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        return None
    return fd


def held() -> bool:
    fd = acquire()
    if fd is None:
        return True
    os.close(fd)
    return False


def owner() -> dict[str, Any] | None:
    path = owner_path()
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def write_owner(data: dict[str, Any]) -> None:
    owner_path().write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def clear_owner() -> None:
    owner_path().unlink(missing_ok=True)
