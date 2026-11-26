from __future__ import annotations

from pydantic import Field

from bitwig_agent.protocol.base import Nonempty, StatusArgs, ToolSpec, omitted


class MarkerInsertArgs(StatusArgs):
    beat: float = Field(ge=0, le=1e7, description="Quarter-note beats from the project start.")
    name: Nonempty = omitted(description="Marker name; omitted keeps the name the host assigns.")


class MarkerRenameArgs(StatusArgs):
    markerIndex: int = Field(ge=0, le=63, description="Rank in the beat-sorted markers.list result.")
    expectedName: Nonempty
    name: Nonempty
    expectedBeat: float = omitted(ge=0, le=1e7, description="Optional second guard: the marker must be at this beat.")


class MarkerDeleteArgs(StatusArgs):
    markerIndex: int = Field(ge=0, le=63, description="Rank in the beat-sorted markers.list result.")
    expectedName: Nonempty
    expectedBeat: float = omitted(ge=0, le=1e7, description="Optional second guard: the marker must be at this beat.")


MARKERS_LIST_DESCRIPTION = "List arranger cue markers sorted by beat: markerIndex (rank, not a stable id), name, beat in quarter-note beats; window of 64 markers, observationsComplete says whether the list is whole. Read-only (guide 5.5.1 Cue Markers; time-signature change markers, 5.5.2, have no public API)."
MARKERS_INSERT_DESCRIPTION = "Insert a cue marker at a beat. Needs a stopped transport and recording/automation off. Adds a marker via the native add-at-playhead call, then names it and moves it to beat in one step; the playhead is never moved. Refused if a marker already sits at that beat. Verified by comparing the whole observed marker list with the expected one; an unobserved result is outcome unknown (reason not_observed) and blocks further marker writes until you inspect markers.list and ack the hold."
MARKERS_RENAME_DESCRIPTION = "Rename one cue marker from a fresh markers.list: markerIndex plus its exact expectedName (and optionally expectedBeat) as compare-and-set. Verified by whole-list readback: outcome applied, already_matched when the name is unchanged, unknown (reason not_observed) otherwise."
MARKERS_DELETE_DESCRIPTION = "Delete one cue marker from a fresh markers.list: markerIndex plus its exact expectedName (and optionally expectedBeat). Verified by whole-list readback: outcome applied, unknown (reason not_observed) otherwise; a lost marker cannot be undone through this API."


SPECS: dict[str, ToolSpec] = {
    "markers.list": ToolSpec(StatusArgs, MARKERS_LIST_DESCRIPTION, True, records="markers"),
    "markers.insert": ToolSpec(
        MarkerInsertArgs, MARKERS_INSERT_DESCRIPTION, False, examples=({"beat": 16, "name": "Verse"},)
    ),
    "markers.rename": ToolSpec(
        MarkerRenameArgs,
        MARKERS_RENAME_DESCRIPTION,
        False,
        examples=({"markerIndex": 1, "expectedName": "Verse", "name": "Verse 1"},),
    ),
    "markers.delete": ToolSpec(
        MarkerDeleteArgs, MARKERS_DELETE_DESCRIPTION, False, examples=({"markerIndex": 1, "expectedName": "Verse"},)
    ),
}
