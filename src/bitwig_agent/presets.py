"""Offline preset search over the user's Bitwig library and installed packages.

Reads file names, folders and, where a .bwpreset header is plain, its meta block. Package presets are
encrypted, so for them only the path (creator, package, folders, name) is known. Nothing here talks to
the host: the result's `path` goes into devices.insert kind=file.
"""

from __future__ import annotations

import os
import re
import struct
import sys
from pathlib import Path
from typing import Any

DEVICE_TYPES = {"note_to_audio": "instrument", "audio_to_audio": "audio-effect", "note_to_note": "note-effect"}
HEADER_BYTES = 65536
PROBE_BYTES = 128
COMMENT_CHARS = 200
_KEY_RE = re.compile(rb"[A-Za-z0-9_]{1,64}")
_STRING, _BLOB, _STRING_LIST = 0x08, 0x0D, 0x19


def default_roots() -> list[tuple[str, Path]]:
    """(source, root) pairs: BITWIG_PRESET_ROOTS (os.pathsep list) replaces the defaults."""
    if os.environ.get("BITWIG_PRESET_ROOTS"):
        return [("package" if "installed-packages" in Path(r).parts else "user", Path(r))
                for r in os.environ["BITWIG_PRESET_ROOTS"].split(os.pathsep) if r]
    home = Path.home()
    user = [home / "Bitwig Studio" / "Library", home / "Documents" / "Bitwig Studio" / "Library"]
    packages = [home / ".BitwigStudio" / "installed-packages",
                home / "Library" / "Application Support" / "Bitwig" / "Bitwig Studio" / "installed-packages"]
    if sys.platform == "win32" and os.environ.get("LOCALAPPDATA"):
        packages.append(Path(os.environ["LOCALAPPDATA"]) / "Bitwig Studio" / "installed-packages")
    return [("user", p) for p in user] + [("package", p) for p in packages]


def read_meta(data: bytes) -> dict[str, Any] | None:
    """Parse the plain `meta` block of a .bwpreset header; None for encrypted or unrecognized files."""
    if not data.startswith(b"BtWg"):
        return None
    at = data.find(b"meta", 0, 128)
    if at < 0:
        return None
    pos, meta = at + 4, {}
    try:
        while pos + 8 <= len(data):
            marker, klen = struct.unpack_from(">II", data, pos)
            key = data[pos + 8:pos + 8 + klen]
            if marker != 1 or not _KEY_RE.fullmatch(key):
                break
            pos += 8 + klen
            kind = data[pos]
            pos += 1
            if kind == _STRING:
                (n,) = struct.unpack_from(">I", data, pos)
                meta[key.decode()] = data[pos + 4:pos + 4 + n].decode("utf-8", "replace")
                pos += 4 + n
            elif kind == _BLOB:
                (n,) = struct.unpack_from(">I", data, pos)
                pos += 4 + n
            elif kind == _STRING_LIST:
                (count,) = struct.unpack_from(">I", data, pos)
                pos += 4
                items = []
                for _ in range(count):
                    (n,) = struct.unpack_from(">I", data, pos)
                    items.append(data[pos + 4:pos + 4 + n].decode("utf-8", "replace"))
                    pos += 4 + n
                meta[key.decode()] = items
            else:
                break
    except (struct.error, IndexError):
        pass
    return meta or None


def _entry(source: str, root: Path, path: Path, parts: tuple[str, ...]) -> dict[str, Any]:
    name = path.stem
    entry: dict[str, Any] = {"name": name, "path": str(path), "source": source}
    if source == "package":
        entry["creator"] = parts[0] if len(parts) > 1 else None
        entry["package"] = parts[1] if len(parts) > 2 else None
        entry["folders"] = list(parts[2:-1])
    else:
        entry["folders"] = list(parts[:-1])
    entry["metadataReadable"] = False
    try:
        with open(path, "rb") as handle:
            head = handle.read(PROBE_BYTES)
            meta = read_meta(head + handle.read(HEADER_BYTES - len(head))) if b"meta" in head else None
    except OSError:
        meta = None
    if meta:
        entry["metadataReadable"] = True
        if meta.get("device_name"): entry["device"] = meta["device_name"]
        if meta.get("device_type"): entry["deviceType"] = DEVICE_TYPES.get(meta["device_type"], meta["device_type"])
        if meta.get("preset_category"): entry["category"] = meta["preset_category"]
        if meta.get("device_category"): entry["deviceCategory"] = meta["device_category"]
        if meta.get("creator"): entry["creator"] = meta["creator"]
        if meta.get("comment"): entry["comment"] = meta["comment"][:COMMENT_CHARS]
    entry["insert"] = {"kind": "file", "path": str(path)}
    return entry


def scan(roots: list[tuple[str, Path]]) -> tuple[list[dict[str, Any]], list[str]]:
    entries, searched = [], []
    for source, root in roots:
        if not root.is_dir():
            continue
        searched.append(str(root))
        # installed-packages holds one folder per Bitwig version, then creator/package/...
        bases = [d for d in sorted(root.iterdir()) if d.is_dir() and not d.name.startswith(".")] \
            if source == "package" and root.name == "installed-packages" else [root]
        for base in bases:
            for folder, dirs, files in os.walk(base):
                dirs.sort()
                for file in sorted(files):
                    if file.lower().endswith(".bwpreset"):
                        path = Path(folder, file)
                        entries.append(_entry(source, base, path, path.relative_to(base).parts))
    return entries, searched


def _norm(text: str) -> str:
    return re.sub(r"[^0-9a-z]+", "", text.casefold())


def _haystack(entry: dict[str, Any]) -> tuple[str, str]:
    near = " ".join([entry["name"], *entry.get("folders", [])])
    far = " ".join(str(entry.get(k) or "") for k in ("package", "creator", "device", "category", "deviceCategory", "comment"))
    return _norm(near), _norm(far)


def _contains(value: Any, needle: str) -> bool:
    return needle in _norm(" ".join(value) if isinstance(value, list) else str(value or ""))


def search(args: dict[str, Any], roots: list[tuple[str, Path]] | None = None) -> dict[str, Any]:
    entries, searched = scan(roots if roots is not None else default_roots())
    words = [_norm(w) for w in args.get("query", "").split() if _norm(w)]
    device = _norm(args["device"]) if args.get("device") else None
    category = _norm(args["category"]) if args.get("category") else None
    kind = args.get("deviceType")
    hits = []
    for entry in entries:
        if kind and entry.get("deviceType") != kind:
            continue
        # Encrypted package presets carry no device field: their folders are the only hint.
        if device and not (_contains(entry.get("device"), device) or _contains(entry.get("folders"), device)):
            continue
        if category and not any(_contains(entry.get(k), category) for k in ("category", "deviceCategory", "folders", "package")):
            continue
        near, far = _haystack(entry)
        if any(w not in near and w not in far for w in words):
            continue
        name = _norm(entry["name"])
        score = sum(2 if w in near else 1 for w in words) + (5 if words and name == "".join(words) else 0)
        hits.append((-score, entry["source"] != "user", entry["name"].casefold(), entry["path"], entry))
    hits.sort(key=lambda h: h[:4])
    offset, limit = args.get("offset", 0), args.get("limit", 25)
    page = [h[4] for h in hits[offset:offset + limit]]
    return {
        "status": "observed",
        "scope": "offline_preset_files",
        "verification": "file_names_and_plain_headers_not_insertion_or_audio_proof",
        "roots": searched,
        "scanned": {"presets": len(entries), "metadataReadable": sum(e["metadataReadable"] for e in entries),
                    "note": "Package presets are encrypted: only path, package, creator and folders are known for them."},
        "window": {"offset": offset, "limit": limit, "total": len(hits), "complete": offset + len(page) >= len(hits)},
        "presets": page,
    }
