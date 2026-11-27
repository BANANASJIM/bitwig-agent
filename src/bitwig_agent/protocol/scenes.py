from __future__ import annotations

from typing import Annotated

from pydantic import Field

from bitwig_agent.protocol.annotations import RETRIGGER
from bitwig_agent.protocol.base import ENGINE, NOT_RECORDING, PROJECT_READY, STOPPED, StatusArgs, ToolSpec, omitted
from bitwig_agent.protocol.launcher import SCENE_REF, SceneOffset
from bitwig_agent.protocol.refs import SceneRef

MAX_SCENES_PER_INSERT = 128


class ScenesListArgs(StatusArgs):
    sceneOffset: SceneOffset = omitted()


class ScenesInsertArgs(StatusArgs):
    count: Annotated[int, Field(ge=1, le=MAX_SCENES_PER_INSERT)] = omitted()


class SceneTargetArgs(StatusArgs):
    target: SceneRef


class ScenesSetArgs(SceneTargetArgs):
    name: str = Field(min_length=1, max_length=128)


SCENES_LIST_DESCRIPTION = (
    "List every scene of the launcher: rows {ref, clipCount (occupied slots on the observed tracks)}. An unnamed "
    "scene has name null in its ref, so it has no rename protection: name scenes you address repeatedly. Scenes are "
    "read in windows; if the deadline passes first the result has complete=false and next={sceneOffset}: call again "
    "with it. Does not select or move the cursor."
)
SCENES_INSERT_DESCRIPTION = (
    f"Append empty scene(s) at the END of the scene list (count 1..{MAX_SCENES_PER_INSERT}, default 1), so existing "
    'clip slots never move (unlike actions.invoke "Create Scene", which inserts after the selection). Returns ref of '
    "the first new scene and refs of all of them; placement is per API documentation, only the count is read back. "
    "Unobserved count is outcome unknown: inspect scenes.list, never blindly repeat. Appending can move the UI "
    "clip/scene selection: re-read context.get afterwards."
)
SCENES_SET_DESCRIPTION = (
    "Rename one scene. Verified by the name observer after the write: outcome applied, already_matched when "
    "unchanged; the result's ref carries the new name. An unobserved write is outcome unknown (reason not_observed): "
    "re-read scenes.list, never blindly repeat."
)
SCENES_LAUNCH_DESCRIPTION = (
    "Launch one scene (every clip in its row). Playback can produce audio: needs the audio engine active and "
    "recording/automation write off. Outcome applied when every occupied slot of the scene is observed playing; sent "
    "when the clips are only queued or the scene has no clips (nothing to observe); unknown (reason not_observed) "
    "when neither playing nor queued was observed. Quantization applies as in the GUI."
)
SCENES_DUPLICATE_DESCRIPTION = (
    "Duplicate one scene with its clips; the copy goes directly after the source (Bitwig's Duplicate) and its ref is "
    "returned. Verified by the scene count and the neighbouring names inside the scene window: outcome applied; "
    "otherwise unknown (reason not_observed): inspect scenes.list, never blindly repeat. Needs a settled project and "
    "no pending launcher operation; an unresolved launcher mutation refuses it until you inspect and ack the hold."
)
SCENES_DELETE_DESCRIPTION = (
    "Delete one scene AND the clips in its row (Bitwig's Delete; clipsDeleted in the result says how many). Needs a "
    "stopped transport, recording/automation off, none of the scene's clips playing, queued or recording, and at "
    "least two scenes. Later scenes shift up, so refs of them change. Verified by the count and the remaining names "
    "inside the scene window: outcome applied; otherwise unknown (reason not_observed): inspect scenes.list, never "
    "blindly repeat."
)


SPECS: dict[str, ToolSpec] = {
    "scenes.list": ToolSpec(ScenesListArgs, SCENES_LIST_DESCRIPTION, True, records="scenes"),
    "scenes.insert": ToolSpec(
        ScenesInsertArgs,
        SCENES_INSERT_DESCRIPTION,
        False,
        examples=({}, {"count": 40}),
        requires=PROJECT_READY + NOT_RECORDING,
    ),
    "scenes.set": ToolSpec(
        ScenesSetArgs, SCENES_SET_DESCRIPTION, False, examples=({"target": SCENE_REF, "name": "Intro"},)
    ),
    "scenes.launch": ToolSpec(
        SceneTargetArgs,
        SCENES_LAUNCH_DESCRIPTION,
        False,
        examples=({"target": SCENE_REF},),
        requires=ENGINE + NOT_RECORDING,
        annotations=RETRIGGER,
    ),
    "scenes.duplicate": ToolSpec(
        SceneTargetArgs,
        SCENES_DUPLICATE_DESCRIPTION,
        False,
        examples=({"target": SCENE_REF},),
        requires=PROJECT_READY + NOT_RECORDING,
    ),
    "scenes.delete": ToolSpec(
        SceneTargetArgs,
        SCENES_DELETE_DESCRIPTION,
        False,
        examples=({"target": SCENE_REF},),
        requires=PROJECT_READY + STOPPED,
    ),
}
