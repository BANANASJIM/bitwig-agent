from __future__ import annotations

from typing import Self

from pydantic import model_validator

from bitwig_agent.protocol.base import StatusArgs, ToolSpec, omitted


class DiagnosticsGetArgs(StatusArgs):
    sinceSeconds: int = omitted(
        ge=1, le=86400, description="Look back this many seconds (default 300); exclusive with sinceMarker."
    )
    sinceMarker: str = omitted(
        min_length=1,
        max_length=512,
        description="Opaque marker from a previous diagnostics.get call: only events written after it.",
    )
    maxEvents: int = omitted(ge=1, le=500, description="Keep the newest N deduplicated events (default 50).")

    @model_validator(mode="after")
    def validate_window(self) -> Self:
        if {"sinceSeconds", "sinceMarker"} <= self.model_fields_set:
            raise ValueError("supply at most one of sinceSeconds or sinceMarker")
        return self


DIAGNOSTICS_GET_DESCRIPTION = "Client-side: host problems invisible through the API, read from Bitwig's shared log (Linux ~/.BitwigStudio/log; macOS/Windows documented paths, else status unsupported). One status call picks this instance's log section by process start time; attribution.certain=false says when other instances may be mixed in. Events {time, level, category: grid_rejected|engine|extension|exception|dialog|other, message, count}; grid_rejected = a Grid device silently dropped from the audio network (check after Grid topology writes), engine = isEngineReady flips. Modal dialogs (Dashboard, Save?, Recover) are NOT in the log. Returns marker: pass it as sinceMarker next time to see only what happened since. grid_rejected bursts WHILE a build is in progress are expected (the graph is incomplete between inserts and cables): only events after the build count, so use the diagnosticsMarker that grid.apply returns as sinceMarker. Default last 300 s, maxEvents 50."


SPECS: dict[str, ToolSpec] = {
    "diagnostics.get": ToolSpec(
        DiagnosticsGetArgs,
        DIAGNOSTICS_GET_DESCRIPTION,
        True,
        examples=({"sinceSeconds": 300},),
        host=False,
        records="events",
    ),
}
