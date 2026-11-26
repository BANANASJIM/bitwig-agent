"""The transport state a Grid write is refused for: value writes may play, structural writes need a stopped transport."""

from __future__ import annotations

from typing import Any

RECORDING_FLAGS = ("arrangerRecordEnabled", "arrangerAutomationWriteEnabled", "clipLauncherAutomationWriteEnabled")


def blocking(state: Any, structural: bool) -> list[str]:
    flags = ("playing", *RECORDING_FLAGS) if structural else RECORDING_FLAGS
    return [k for k in flags if isinstance(state, dict) and state.get(k) is True]
