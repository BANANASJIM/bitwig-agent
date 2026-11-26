from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from bitwig_agent.protocol.base import (
    MAX_BEAT,
    TRACK,
    StrictModel,
    ToolSpec,
    TrackId,
    has_control_character,
    long_timeout,
    omitted,
    tool,
    validate_local_path,
)
from bitwig_agent.protocol.limits import LONG_DEFAULT_TIMEOUT_MS, LONG_TIMEOUT_MS, TRACK_WINDOW

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
    sources: Annotated[list[TrackId | Literal["master"]], Field(min_length=1, max_length=TRACK_WINDOW)] = Field(
        description='"master" and/or trackIds from tracks.list (audio, instrument, effect or group tracks); one file each, in this order.'
    )
    directory: str = Field(max_length=4096, description="Existing absolute folder for the files. No ~ expansion.")
    baseName: str = Field(
        min_length=1,
        max_length=128,
        description="File name without extension: <baseName>.<format> for one source, <baseName>-01, -02, ... for several.",
    )
    format: Literal["wav", "flac"]
    bitDepth: Literal[16, 24, 32] = omitted(description="Default 24; 32 = 32-bit float (wav only).")
    sampleRate: Literal[44100, 48000] = omitted(description="Omitted: the current engine rate.")
    tap: Literal["post_fader", "pre_fader"] = omitted(description="Default post_fader.")
    dither: bool = omitted()
    overwrite: bool = omitted(description="Allow replacing existing files with the same names.")

    @model_validator(mode="after")
    def validate_export(self) -> Self:
        if len({value.lower() for value in self.sources}) != len(self.sources):
            raise ValueError("sources must be distinct")
        validate_local_path(self.directory)
        name = self.baseName
        if name != name.strip() or name.startswith(".") or "/" in name or "\\" in name or has_control_character(name):
            raise ValueError(
                "baseName must not contain '/', '\\', control characters, a leading dot or surrounding whitespace"
            )
        if self.format == "flac" and self.bitDepth == FLOAT_BIT_DEPTH:
            raise ValueError("flac supports bitDepth 16 or 24")
        return self


class BounceInPlaceArgs(RenderRangeArgs):
    trackIds: Annotated[list[TrackId], Field(min_length=1, max_length=TRACK_WINDOW)] = Field(
        description="Audio/instrument tracks (trackIds from tracks.list) whose main arranger lane gets the bounced audio."
    )
    tap: Literal["pre_fx", "pre_fader", "post_fader"]

    @model_validator(mode="after")
    def validate_distinct(self) -> Self:
        if len({value.lower() for value in self.trackIds}) != len(self.trackIds):
            raise ValueError("trackIds must be distinct")
        return self


EXPORT_AUDIO_DESCRIPTION = (
    "Render a beat range of the master and/or tracks to audio files without the Export Audio dialog, through the job the dialog starts (6.1.4-gated). One file per source in directory: <baseName>.<format>, or <baseName>-01, -02, ... in sources order for several. Refused before dispatch (an error, nothing changed): an existing file unless overwrite (target_exists), a missing directory, playing transport/recording/automation write, a render already running (busy), Bitwig's own refusals (host_refused: engine not connected, no project directory, recording), missing files/plugins (needs_confirmation: Bitwig would ask in a dialog). outcome applied only after the job's completion latch and every file read back: new or changed, nonempty, a valid WAV/FLAC header with the requested bit depth and sample rate, at least one frame. Completed but not verified: job_failed (an error if nothing was written, else outcome unknown). On success Bitwig may open the folder in the file manager (mayOpenFileManager). timeoutMs default "
    + str(LONG_DEFAULT_TIMEOUT_MS)
    + ", up to "
    + str(LONG_TIMEOUT_MS)
    + "; a longer render returns outcome unknown, reason deadline and job: follow it with jobs.get; further renders are busy until it completes. Never retry unknown."
)
BOUNCE_IN_PLACE_DESCRIPTION = (
    "Bounce a beat range of audio/instrument tracks in place (6.1.4-gated; Bitwig's Bounce in Place shows no dialog): each track's main arranger lane gets one audio clip over [startBeats, endBeats) replacing what was there, in one undoable action; the audio lives in the project's managed audio folder. Same refusals as export.audio before dispatch (group tracks: unsupported_scope). outcome applied only after the job's completion latch and arranger readback of every track: exactly one new audio clip at the range and nothing else overlapping it (the new clips are in tracks[].clip). An instrument track becomes Hybrid (tracks[].trackTypeBefore/trackTypeAfter, warning); Undo reverts that, deleting the clip does not. Completed with no lane changed: job_failed (an error, nothing changed); any other readback difference: outcome unknown, reason readback_mismatch, and further bounces are refused until you inspect arranger.clips.list and ack the hold. timeoutMs default "
    + str(LONG_DEFAULT_TIMEOUT_MS)
    + ", up to "
    + str(LONG_TIMEOUT_MS)
    + "; a longer job returns outcome unknown, reason deadline and job: follow it with jobs.get, which also verifies it. Never retry unknown; undo with the Undo action."
)


SPECS: dict[str, ToolSpec] = {
    "export.audio": tool(
        ExportAudioArgs,
        EXPORT_AUDIO_DESCRIPTION,
        False,
        {
            "sources": ["master"],
            "directory": "/home/me/Music/Exports",
            "baseName": "Agent Sketch",
            "startBeats": 0,
            "endBeats": 64,
            "format": "wav",
            "bitDepth": 24,
        },
        {
            "sources": [TRACK],
            "directory": "/home/me/Music/Stems",
            "baseName": "Stem",
            "startBeats": 0,
            "endBeats": 64,
            "format": "flac",
            "tap": "pre_fader",
            "overwrite": True,
        },
    ),
    "bounce.inPlace": tool(
        BounceInPlaceArgs,
        BOUNCE_IN_PLACE_DESCRIPTION,
        False,
        {"trackIds": [TRACK], "startBeats": 0, "endBeats": 16, "tap": "post_fader"},
    ),
}
