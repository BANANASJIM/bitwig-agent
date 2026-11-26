from __future__ import annotations

from decimal import Decimal
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import MAX_BEAT, PROJECT_READY, TRACK, DeviceArgs, StrictModel, ToolSpec, omitted, tool
from bitwig_agent.protocol.launcher import CLIP, check_note_spans


class ArrangerNote(StrictModel):
    beat: float = Field(
        ge=0, le=MAX_BEAT, description="Onset in beats from the clip start (not the arrangement), on a 1/128-beat grid."
    )
    pitch: int = Field(ge=0, le=127)
    velocity: int = Field(ge=1, le=127)
    durationBeats: float = Field(ge=1 / 128, le=4096)


class ArrangerClipListArgs(DeviceArgs):
    pass


class ArrangerClipCreateArgs(DeviceArgs):
    startBeats: float = Field(ge=0, le=MAX_BEAT, description="Arrangement position in beats.")
    lengthBeats: float = Field(ge=1 / 128, le=65536)
    name: str = Field(min_length=1, max_length=128)
    notes: Annotated[list[ArrangerNote], Field(min_length=1, max_length=256)] = omitted()
    channel: Annotated[int, Field(ge=0, le=15)] = omitted()

    @model_validator(mode="after")
    def validate_notes(self) -> Self:
        if "notes" not in self.model_fields_set:
            if "channel" in self.model_fields_set:
                raise ValueError("channel is only valid together with notes")
            return self
        check_note_spans(self.notes)
        if any(
            Decimal(str(n.beat)) + Decimal(str(n.durationBeats)) > Decimal(str(self.lengthBeats)) for n in self.notes
        ):
            raise ValueError("notes must fit inside lengthBeats")
        return self


class ArrangerCopyArgs(DeviceArgs):
    slotIndex: int = Field(ge=0, le=15)
    expectedClipName: str = Field(min_length=1, max_length=256)
    startBeats: float = Field(ge=0, le=MAX_BEAT)


ARRANGER_EDIT_OPS = {"move": {"startBeats"}, "resize": {"lengthBeats"}, "rename": {"name"}, "delete": set()}


class ArrangerClipEditArgs(DeviceArgs):
    clipIndex: int = Field(
        ge=0,
        le=4095,
        description="Index from a fresh arranger.clips.list (event order: creation order on 6.1.4, not start order).",
    )
    expectedStartBeats: float = Field(
        ge=0, le=MAX_BEAT, description="Exact startBeats from arranger.clips.list (guard, compared within 1e-6)."
    )
    expectedName: str = Field(max_length=256, description="Exact current name from arranger.clips.list; may be empty.")
    op: Literal["move", "resize", "rename", "delete"]
    startBeats: Annotated[float, Field(ge=0, le=MAX_BEAT)] = omitted()
    lengthBeats: Annotated[float, Field(ge=1 / 128, le=65536)] = omitted()
    name: Annotated[str, Field(min_length=1, max_length=128)] = omitted()

    @model_validator(mode="after")
    def validate_op_fields(self) -> Self:
        payload = self.model_fields_set & set().union(*ARRANGER_EDIT_OPS.values())
        if payload != ARRANGER_EDIT_OPS[self.op]:
            raise ValueError(f"op {self.op} takes exactly {sorted(ARRANGER_EDIT_OPS[self.op]) or 'no extra fields'}")
        return self


ARRANGER_CLIPS_LIST_DESCRIPTION = "Read the arranger clips on one audio/instrument track's main clip lane, in the lane's event order (creation order on 6.1.4, not start order; a moved clip keeps its clipIndex): clipIndex, startBeats, lengthBeats, endBeats, name, kind (instrument|audio|other), loopEnabled, loopStartBeats, loopLengthBeats, playStartBeats, playStopBeats. Beats are quarter notes from the arrangement start. Automation clips and take/comp lanes are not listed. Native read (inspected Bitwig 6.1.4 JAR); no selection."
ARRANGER_CLIPS_CREATE_DESCRIPTION = "Create one MIDI clip on an instrument track's arranger timeline at startBeats with lengthBeats, a name and optional notes (beats relative to the clip start, 1/128 grid, same rules as launcher.create). Refused (arranger_overlap, not_applied) if any existing clip overlaps the range; never overwrites. Needs stopped transport with recording/automation-write off. One native undoable edit; result reads back the clip and every note. Unknown outcomes quarantine arranger writes until extension.restart; inspect with arranger.clips.list."
ARRANGER_CLIPS_COPY_FROM_LAUNCHER_DESCRIPTION = "Copy an occupied launcher clip of the same track onto its arranger timeline at startBeats (the guide's drag of a launcher clip into the arranger): Bitwig's own copy-launcher-clips-to-arranger operation in copy mode (unique, not alias). The copy's length is the clip's arrangement length; any overlap with existing clips is refused. Needs expectedClipName from launcher.list, stopped transport, recording/automation-write off. Result lists the new clip."
ARRANGER_CLIPS_EDIT_DESCRIPTION = "Edit one arranger clip found by clipIndex from a fresh arranger.clips.list, guarded by expectedStartBeats and expectedName (mismatch: stale_target, nothing changed). Ops: move{startBeats} keeps the length; resize{lengthBeats} keeps the start; rename{name}; delete. move/resize into another clip are refused (arranger_overlap). Each op is one native undoable edit verified by native readback; already-matching values are not rewritten. Needs stopped transport with recording/automation-write off; unknown outcomes quarantine arranger writes until extension.restart."


SPECS: dict[str, ToolSpec] = {
    "arranger.clips.list": tool(ArrangerClipListArgs, ARRANGER_CLIPS_LIST_DESCRIPTION, True, {"trackId": TRACK}),
    "arranger.clips.create": tool(
        ArrangerClipCreateArgs,
        ARRANGER_CLIPS_CREATE_DESCRIPTION,
        False,
        {
            "trackId": TRACK,
            "startBeats": 16,
            "lengthBeats": 16,
            "name": "Verse",
            "notes": [{"beat": 0, "pitch": 60, "velocity": 100, "durationBeats": 1}],
        },
        {"trackId": TRACK, "startBeats": 0, "lengthBeats": 4, "name": "Intro"},
        {
            "trackId": TRACK,
            "startBeats": 64,
            "lengthBeats": 4096,
            "name": "Pad",
            "notes": [{"beat": 0, "pitch": 48, "velocity": 80, "durationBeats": 16}],
            "channel": 1,
        },
        requires=PROJECT_READY,
    ),
    "arranger.clips.copyFromLauncher": tool(
        ArrangerCopyArgs,
        ARRANGER_CLIPS_COPY_FROM_LAUNCHER_DESCRIPTION,
        False,
        {**CLIP, "expectedClipName": "Sketch", "startBeats": 0},
        requires=PROJECT_READY,
    ),
    "arranger.clips.edit": tool(
        ArrangerClipEditArgs,
        ARRANGER_CLIPS_EDIT_DESCRIPTION,
        False,
        {
            "trackId": TRACK,
            "clipIndex": 0,
            "expectedStartBeats": 16,
            "expectedName": "Verse",
            "op": "move",
            "startBeats": 32,
        },
        {
            "trackId": TRACK,
            "clipIndex": 0,
            "expectedStartBeats": 16,
            "expectedName": "Verse",
            "op": "resize",
            "lengthBeats": 8,
        },
        {
            "trackId": TRACK,
            "clipIndex": 1,
            "expectedStartBeats": 32,
            "expectedName": "",
            "op": "rename",
            "name": "Chorus",
        },
        {"trackId": TRACK, "clipIndex": 0, "expectedStartBeats": 16, "expectedName": "Verse", "op": "delete"},
        requires=PROJECT_READY,
    ),
}
