from __future__ import annotations

from typing import Annotated

from pydantic import Field

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


class LauncherStopArgs(StatusArgs):
    target: TrackRef


LAUNCHER_LIST_DESCRIPTION = (
    "List the launcher slots of one track across every scene: rows {ref (slot), clip (clip ref, only when occupied), "
    "hasContent, playing, queued, recording, selected}. selected marks the slot of the UI cursor clip (null if not yet "
    "observed); notes.* calls move that selection. Scenes are read in windows of "
    f"{SCENE_WINDOW}; if the deadline passes first the result has complete=false and next={{sceneOffset}}: call "
    "again with it. Does not select or move the cursor."
)
LAUNCHER_STOP_DESCRIPTION = (
    "Stop launcher playback on one track; does not delete clips or stop the entire transport. A stop observed as "
    "stopped returns outcome applied; a queued stop returns outcome sent: observe launcher.list before dependent edits. "
    "Missing queue/stop observation returns outcome unknown (reason not_observed): stop the writer, never blindly retry."
)


SPECS: dict[str, ToolSpec] = {
    "launcher.list": ToolSpec(
        LauncherListArgs, LAUNCHER_LIST_DESCRIPTION, True, examples=({"target": TRACK_REF},), records="slots"
    ),
    "launcher.stop": ToolSpec(LauncherStopArgs, LAUNCHER_STOP_DESCRIPTION, False, examples=({"target": TRACK_REF},)),
}
