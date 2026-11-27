from __future__ import annotations

from pydantic import Field

from bitwig_agent.protocol.base import MAX_BEAT, Nonempty, StatusArgs, ToolSpec, omitted
from bitwig_agent.protocol.refs import MarkerRef


class MarkerInsertArgs(StatusArgs):
    beat: float = Field(ge=0, le=MAX_BEAT, description="Quarter-note beats from the project start.")
    name: Nonempty = omitted(description="Marker name; omitted keeps the name the host assigns.")


class MarkerSetArgs(StatusArgs):
    target: MarkerRef = Field(description="A ref from markers.list.")
    name: Nonempty


class MarkerDeleteArgs(StatusArgs):
    target: MarkerRef = Field(description="A ref from markers.list.")


MARKERS_LIST_DESCRIPTION = "List arranger cue markers sorted by beat: ref (kind marker: index, name, beat), name, beat in quarter-note beats; window of 64 markers, observationsComplete says whether the list is whole. A marker has no stable id: its ref is its rank in the beat-sorted list plus its name and beat, so a marker that was moved, renamed or reordered makes the old ref stale. Read-only (guide 5.5.1 Cue Markers; time-signature change markers, 5.5.2, have no public API)."
MARKERS_INSERT_DESCRIPTION = "Insert a cue marker at a beat. Needs a stopped transport and recording/automation off. Moves the playhead to beat, adds a marker there via the native add-at-playhead call (which adds nothing where a marker already sits), names it and puts the playhead back where it was. Refused if a marker already sits at that beat. Verified by comparing the whole observed marker list with the expected one; the result carries the new marker's ref. An unobserved result is outcome unknown (reason not_observed) and blocks further marker writes until you inspect markers.list and ack the hold."
MARKERS_SET_DESCRIPTION = "Rename one cue marker: target is its ref from markers.list. A marker at that rank with another name or beat is stale/target_changed with the ref now there as current; no marker at that rank is stale/target_not_found. Verified by whole-list readback: outcome applied, already_matched when the name is unchanged, unknown (reason not_observed) otherwise; the result carries the marker's new ref."
MARKERS_DELETE_DESCRIPTION = "Delete one cue marker: target is its ref from markers.list, checked as for markers.set. Verified by whole-list readback: outcome applied, unknown (reason not_observed) otherwise; a lost marker cannot be undone through this API."

_MARKER = {
    "kind": "marker",
    "index": 1,
    "name": "Verse",
    "beat": 16,
}

SPECS: dict[str, ToolSpec] = {
    "markers.list": ToolSpec(StatusArgs, MARKERS_LIST_DESCRIPTION, True, records="markers"),
    "markers.insert": ToolSpec(
        MarkerInsertArgs, MARKERS_INSERT_DESCRIPTION, False, examples=({"beat": 16, "name": "Verse"},)
    ),
    "markers.set": ToolSpec(
        MarkerSetArgs,
        MARKERS_SET_DESCRIPTION,
        False,
        examples=({"target": _MARKER, "name": "Verse 1"},),
    ),
    "markers.delete": ToolSpec(MarkerDeleteArgs, MARKERS_DELETE_DESCRIPTION, False, examples=({"target": _MARKER},)),
}
