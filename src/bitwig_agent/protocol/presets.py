from __future__ import annotations

from typing import Literal

from pydantic import Field

from bitwig_agent.protocol.base import StatusArgs, ToolSpec, omitted, tool


class PresetSearchArgs(StatusArgs):
    query: str = omitted(
        min_length=1,
        max_length=4096,
        description="Words that must all occur in the preset name, folders, package, creator or readable metadata; case, spaces and punctuation ignored.",
    )
    device: str = omitted(
        min_length=1,
        max_length=4096,
        description="Device name (readable metadata) or a folder named for it, e.g. Polymer, Poly Grid, Convolution.",
    )
    deviceType: Literal["instrument", "audio-effect", "note-effect"] = omitted(
        description="Only presets whose readable metadata gives this type; encrypted package presets have none."
    )
    category: str = omitted(
        min_length=1,
        max_length=4096,
        description="Preset/device category from readable metadata, or a folder name (e.g. Synth Bass, Drums).",
    )
    limit: int = Field(default=25, ge=1, le=200)
    offset: int = Field(default=0, ge=0, le=1000000)


PRESETS_SEARCH_DESCRIPTION = "Search the user's preset library and installed packages offline by file name and folders (no host call): query words, device (name or folder, e.g. Polymer, Convolution), deviceType, category, limit/offset. User-library presets expose device, deviceType, category, creator and comment from their plain header; package presets are encrypted, so they carry only path, creator, package and folders. Each hit has insert {kind: file, path}: pass it to devices.insert (or devices.layers.insert). Insertion and the resulting sound are verified separately: a hit is a file, not proof it loads."


SPECS: dict[str, ToolSpec] = {
    "presets.search": tool(
        PresetSearchArgs,
        PRESETS_SEARCH_DESCRIPTION,
        True,
        {"query": "warm pad", "device": "Polymer", "limit": 10},
        host=False,
    ),
}
