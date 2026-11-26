from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import BeforeValidator, Field, model_validator

from bitwig_agent.protocol.annotations import EDIT
from bitwig_agent.protocol.base import (
    MAX_BEAT,
    TRACK,
    StrictModel,
    ToolSpec,
    has_control_character,
    long_timeout,
    omitted,
    validate_local_path,
)
from bitwig_agent.protocol.limits import LONG_DEFAULT_TIMEOUT_MS, TRACK_WINDOW
from bitwig_agent.protocol.refs import TrackRef

TRACK_REF = {"kind": "track", "id": TRACK, "name": "Lead"}
FLOAT_BIT_DEPTH = 32
RENDER_MAX_BEATS = MAX_BEAT
RENDER_MAX_LENGTH = 65536


class RenderRangeArgs(StrictModel):
    timeoutMs: int = long_timeout(LONG_DEFAULT_TIMEOUT_MS)
    startBeats: float = Field(
        ge=0, le=RENDER_MAX_BEATS, description="Range start in quarter-note beats from the project start."
    )
    endBeats: float = Field(
        ge=0,
        le=RENDER_MAX_BEATS + RENDER_MAX_LENGTH,
        description="Range end, after startBeats and at most 65536 beats later.",
    )

    @model_validator(mode="after")
    def validate_range(self) -> Self:
        if self.endBeats <= self.startBeats or self.endBeats - self.startBeats > RENDER_MAX_LENGTH:
            raise ValueError("endBeats must be after startBeats, at most 65536 beats later")
        return self


class ExportAudioArgs(RenderRangeArgs):
    sources: Annotated[list[TrackRef | Literal["master"]], Field(min_length=1, max_length=TRACK_WINDOW)] = Field(
        description='"master" and/or track refs from tracks.list (audio, instrument, effect or group tracks); one file each, in this order.'
    )
    directory: str = Field(max_length=4096, description="Existing absolute folder for the files. No ~ expansion.")
    baseName: str = Field(
        min_length=1,
        max_length=128,
        description="File name without extension: <baseName>.<fileType> for one source, <baseName>-01, -02, ... for several.",
    )
    fileType: Literal["wav", "flac"]
    bitDepth: Literal[16, 24, 32] = omitted(description="Default 24; 32 = 32-bit float (wav only).")
    sampleRate: Literal[44100, 48000] = omitted(description="Omitted: the current engine rate.")
    tap: Literal["post_fader", "pre_fader"] = omitted(description="Default post_fader.")
    dither: bool = omitted()
    overwrite: bool = omitted(description="Allow replacing existing files with the same names.")

    @model_validator(mode="after")
    def validate_export(self) -> Self:
        keys = [source if isinstance(source, str) else source.id.lower() for source in self.sources]
        if len(set(keys)) != len(keys):
            raise ValueError("sources must be distinct")
        validate_local_path(self.directory)
        name = self.baseName
        if name != name.strip() or name.startswith(".") or "/" in name or "\\" in name or has_control_character(name):
            raise ValueError(
                "baseName must not contain '/', '\\', control characters, a leading dot or surrounding whitespace"
            )
        if self.fileType == "flac" and self.bitDepth == FLOAT_BIT_DEPTH:
            raise ValueError("flac supports bitDepth 16 or 24")
        return self


def only_true(value: object) -> object:
    if value is not True:
        raise ValueError("must be true")
    return value


class TracksBounceArgs(RenderRangeArgs):
    targets: Annotated[list[TrackRef], Field(min_length=1, max_length=TRACK_WINDOW)] = Field(
        description="Audio/instrument track refs from tracks.list whose main arranger lane gets the bounced audio."
    )
    tap: Literal["pre_fx", "pre_fader", "post_fader"]
    inPlace: Annotated[Literal[True], BeforeValidator(only_true)] = Field(
        default=True, description="Only true: the bounced audio replaces the range in the track's own lane."
    )

    @model_validator(mode="after")
    def validate_distinct(self) -> Self:
        if len({target.id.lower() for target in self.targets}) != len(self.targets):
            raise ValueError("targets must be distinct")
        return self


EXPORT_AUDIO_DESCRIPTION = (
    "Render a beat range of the master and/or tracks to audio files without the Export Audio dialog, through the job the dialog starts (6.1.4-gated). One file per source in directory: <baseName>.<fileType>, or <baseName>-01, -02, ... in sources order for several. Refused before dispatch (an error, nothing changed): an existing file unless overwrite (target_exists), a missing directory, playing transport/recording/automation write, a render already running (busy), Bitwig's own refusals (host_refused: engine not connected, no project directory, recording), missing files/plugins (needs_confirmation: Bitwig would ask in a dialog). outcome applied only after the job's completion latch and every file read back: new or changed, nonempty, a valid WAV/FLAC header with the requested bit depth and sample rate, at least one frame. Completed but not verified: job_failed (an error if nothing was written, else outcome unknown). On success Bitwig may open the folder in the file manager (mayOpenFileManager). The call waits up to "
    + str(LONG_DEFAULT_TIMEOUT_MS)
    + " ms; a longer render returns outcome unknown, reason deadline and job: follow it with jobs.get; further renders are busy until it completes or you ack its job hold after inspecting. Never retry unknown."
)
TRACKS_BOUNCE_DESCRIPTION = (
    "Bounce a beat range of audio/instrument tracks in place (6.1.4-gated; Bitwig's Bounce in Place shows no dialog): each track's main arranger lane gets one audio clip over [startBeats, endBeats) replacing what was there, in one undoable action; the audio lives in the project's managed audio folder. Same refusals as export.audio before dispatch (group tracks: unsupported_scope). outcome applied only after the job's completion latch and arranger readback of every track: exactly one new audio clip at the range and nothing else overlapping it (the new clip is in tracks[].clip, its ref in tracks[].clipRef, the track in tracks[].track). An instrument track becomes Hybrid (tracks[].trackTypeBefore/trackTypeAfter, warning); Undo reverts that, deleting the clip does not. Completed with no lane changed: job_failed (an error, nothing changed); any other readback difference: outcome unknown, reason readback_mismatch, and further bounces are refused until you inspect arranger.list and ack the hold. The call waits up to "
    + str(LONG_DEFAULT_TIMEOUT_MS)
    + " ms; a longer job returns outcome unknown, reason deadline and job: follow it with jobs.get, which also verifies it. Never retry unknown; undo with the Undo action."
)


SPECS: dict[str, ToolSpec] = {
    "export.audio": ToolSpec(
        ExportAudioArgs,
        EXPORT_AUDIO_DESCRIPTION,
        False,
        examples=(
            {
                "sources": ["master"],
                "directory": "/home/me/Music/Exports",
                "baseName": "Agent Sketch",
                "startBeats": 0,
                "endBeats": 64,
                "fileType": "wav",
                "bitDepth": 24,
            },
            {
                "sources": [TRACK_REF],
                "directory": "/home/me/Music/Stems",
                "baseName": "Stem",
                "startBeats": 0,
                "endBeats": 64,
                "fileType": "flac",
                "tap": "pre_fader",
                "overwrite": True,
            },
        ),
        annotations=EDIT,
        summary="Render a beat range of the master and/or track refs to wav/flac files in an existing directory, no dialog: one file per source, <baseName>.<fileType> or <baseName>-01, -02, ... in sources order. Needs a stopped transport; existing files need overwrite. A render longer than the wait returns unknown with job: follow it with jobs.get; never retry unknown.",
    ),
    "tracks.bounce": ToolSpec(
        TracksBounceArgs,
        TRACKS_BOUNCE_DESCRIPTION,
        False,
        examples=({"targets": [TRACK_REF], "startBeats": 0, "endBeats": 16, "tap": "post_fader", "inPlace": True},),
        annotations=EDIT,
        summary="Bounce a beat range of audio/instrument track refs in place: each target's main arranger lane gets one audio clip over the range, one undoable action; an instrument track becomes Hybrid. Needs a stopped transport; group tracks are refused. A job longer than the wait returns unknown with job: follow it with jobs.get; never retry unknown.",
    ),
}
