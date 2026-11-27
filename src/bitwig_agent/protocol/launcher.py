from __future__ import annotations

from typing import Annotated, Literal

from pydantic import Field

from bitwig_agent.protocol.annotations import STEER
from bitwig_agent.protocol.base import StatusArgs, ToolSpec, omitted
from bitwig_agent.protocol.limits import SCENE_WINDOW
from bitwig_agent.protocol.refs import Index, TrackRef

TRACK_REF = {"kind": "track", "id": "12345678-1234-1234-1234-123456789abc", "name": "Lead"}
SCENE_REF = {"kind": "scene", "index": 33, "name": "Drop"}
SLOT_REF = {"kind": "slot", "track": TRACK_REF, "scene": SCENE_REF}
CLIP_REF = {"kind": "clip", "track": TRACK_REF, "slot": {"scene": SCENE_REF}, "name": "Hook"}
ARRANGER_CLIP_REF = {"kind": "clip", "track": TRACK_REF, "lane": "main", "start": 64, "name": "Hook"}

SceneOffset = Annotated[
    Index,
    Field(description=f"First scene to read, from a previous result's next; scenes are read {SCENE_WINDOW} at a time."),
]


class LauncherListArgs(StatusArgs):
    target: TrackRef
    sceneOffset: SceneOffset = omitted()


class LauncherTrackArgs(StatusArgs):
    target: TrackRef | Literal["all"] = Field(
        description='A track ref from tracks.list, or "all" for every track of the project.'
    )


LAUNCHER_LIST_DESCRIPTION = (
    "List the launcher slots of one track across every scene: rows {ref (slot), clip (clip ref, only when occupied), "
    "hasContent, playing, queued, recording, selected}. selected marks the slot of the UI cursor clip (null if not yet "
    "observed); notes.* calls move that selection. Scenes are read in windows of "
    f"{SCENE_WINDOW}; if the deadline passes first the result has complete=false and next={{sceneOffset}}: call "
    "again with it. Does not select or move the cursor."
)
LAUNCHER_STOP_DESCRIPTION = (
    'Stop launcher playback on one track, or with target "all" on every track (Bitwig\'s Stop Playback of All Tracks); '
    "does not delete clips or stop the transport. A stopped track renders silent until launcher.returnToArrangement. "
    "Bitwig queues a stop first; the call waits for it. Every track observed stopped: outcome applied; still queued "
    'when the observation window ends: outcome sent, observe again before dependent edits. "all" reads back the tracks of the track window (tracks[], complete=false when the project '
    "has more, then at most sent). No stop or queue observed: outcome unknown (reason not_observed); never blindly retry."
)
LAUNCHER_RETURN_DESCRIPTION = (
    'Return one track, or with target "all" every track, from the launcher to Arranger playback (Bitwig\'s Switch '
    "Playback to Arranger; immediate, no launch quantization): playing launcher clips stop and a track stopped from the "
    "launcher follows the arranger again, so export.audio renders the arranger. outcome applied when every track reads "
    "back playbackState arranger (not stopped, no launcher clip playing or queued); complete=false (launcher clips "
    "beyond the observed scene or track window) gives at most sent. Not observed: unknown (not_observed); never retry."
)


SPECS: dict[str, ToolSpec] = {
    "launcher.list": ToolSpec(
        LauncherListArgs, LAUNCHER_LIST_DESCRIPTION, True, examples=({"target": TRACK_REF},), records="slots"
    ),
    "launcher.stop": ToolSpec(
        LauncherTrackArgs, LAUNCHER_STOP_DESCRIPTION, False, examples=({"target": TRACK_REF}, {"target": "all"})
    ),
    "launcher.returnToArrangement": ToolSpec(
        LauncherTrackArgs,
        LAUNCHER_RETURN_DESCRIPTION,
        False,
        examples=({"target": "all"}, {"target": TRACK_REF}),
        annotations=STEER,
    ),
}
