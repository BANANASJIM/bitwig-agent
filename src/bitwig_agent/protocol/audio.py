from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import Field, field_validator, model_validator

from bitwig_agent.protocol.annotations import ADD, SET
from bitwig_agent.protocol.base import Nonempty, StatusArgs, ToolSpec, TrackId, omitted, tool, validate_local_path


class AudioRouteArgs(StatusArgs):
    mode: Literal["silent", "restore", "status"]


def wav_path(value: str) -> str:
    validate_local_path(value)
    if not value.lower().endswith(".wav"):
        raise ValueError("path must end in .wav")
    return value


class AudioCaptureArgs(StatusArgs):
    seconds: float = Field(ge=0.5, le=120)
    outPath: Nonempty
    overwrite: bool = omitted(description="Replace an existing file; refused by default.")
    fromStart: bool = omitted(
        description="Default true: stop transport, seek to 0, record, start transport, stop after seconds. false records without touching the transport."
    )
    allowAudible: bool = omitted(
        description="Do not require audio.route silent (the signal stays audible). Capture always records Bitwig's own out1/out2, never the default output monitor, so other applications are not in the file."
    )
    soloTrack: TrackId = omitted(
        description="Really solo this track for the recording: its solo is set and every other soloed track cleared, then every changed solo is restored afterwards (also on failure); the result reports soloed/unsoloed/restored. Refuses (not_isolated) while other tracks have playing launcher clips unless stopOthers."
    )
    stopOthers: bool = omitted(
        description="Stop the launcher clips of every other playing track (launcher.stop) before recording; the result lists them. Without soloTrack, nothing is spared: use it with soloTrack."
    )

    @field_validator("outPath")
    @classmethod
    def validate_out_path(cls, value: str) -> str:
        return wav_path(value)


class AudioAnalyzeArgs(StatusArgs):
    path: Nonempty
    mode: Literal["level", "onsets", "mono_pitch", "probe", "loudness", "bands"]
    bpm: Annotated[float, Field(ge=20, le=666)] = omitted(description="mono_pitch: adds 16th-grid positions.")
    skipSeconds: Annotated[float, Field(ge=0, le=3600)] = omitted(
        description="Ignore audio before this time; reported times stay relative to the file start."
    )
    method: Literal["flux", "envelope"] = omitted(
        description="onsets/mono_pitch: flux (default) = spectral flux, counts overlapping notes with long decays; envelope = rising crossings of a 5 ms peak envelope."
    )
    threshold: Annotated[float, Field(ge=0.01, le=0.9)] = omitted(
        description="onsets/mono_pitch: flux: how far a flux peak must stand above its neighbourhood, as a fraction of the strongest peak; envelope: fraction of the peak envelope that counts as an onset (default 0.15)."
    )
    windowSeconds: Annotated[float, Field(ge=0.01, le=60)] = omitted(
        description="level: window length (default 0.5); bands: window length (default 8)."
    )
    key: str = omitted(
        pattern=r"^[A-Ga-g][#b]?:(ionian|dorian|phrygian|lydian|mixolydian|aeolian|locrian|harmonic_minor)$",
        description="bands: e.g. 'F:aeolian'; adds per-window and overall in-key chroma share (50-1500 Hz peaks folded to pitch classes) and top pitch classes. Drums and noise pollute chroma.",
    )
    spectrogramPath: Nonempty = omitted(
        description="bands: absolute .png path to write a log-frequency spectrogram to (an existing file is replaced)."
    )

    @field_validator("path")
    @classmethod
    def validate_path(cls, value: str) -> str:
        return wav_path(value)

    @field_validator("spectrogramPath")
    @classmethod
    def validate_spectrogram_path(cls, value: str) -> str:
        validate_local_path(value)
        if not value.lower().endswith(".png"):
            raise ValueError("spectrogramPath must end in .png")
        return value

    @model_validator(mode="after")
    def validate_bands_only(self) -> Self:
        if self.mode != "bands" and (self.key or self.spectrogramPath):
            raise ValueError("key and spectrogramPath apply to mode=bands only")
        return self


AUDIO_ROUTE_DESCRIPTION = "Linux/PipeWire only (else unsupported_platform); client-side, no host call. mode=silent moves Bitwig Studio's out1/out2 links to a 'bwtest' null sink (created if absent), remembering the previous targets in a state file; restore re-links exactly those and drops the bwtest links; status lists current links and whether output is silent. Idempotent: silent/restore return outcome applied (already_matched when no link changed), status carries none; a pw-link that fails returns outcome unknown (inspect with mode=status). Never touches other applications. Restore when done, or Bitwig stays silent."
AUDIO_CAPTURE_DESCRIPTION = "Linux/PipeWire only; client-side. Records Bitwig's output into an absolute .wav (existing file refused unless overwrite=true). Records Bitwig's own output ports (out1/out2) through a dedicated pw-record node linked by pw-link; only links the capture created are removed afterwards, the user's links and other applications' audio are never involved. Needs audio.route silent (else not_isolated) unless allowAudible=true, which only waives that requirement (the signal stays audible). fromStart (default true): stop transport, seek to 0, start recording, play, stop after seconds; false leaves the transport alone. Isolation: playing launcher clips are read first (context.get) and the result carries otherTracksPlaying [names] plus a warning when other tracks play, since the recording is then a mix. soloTrack=<trackId> really solos that track: the solo states of all tracks are read (tracks.list + tracks.get), solo is set on the target and cleared on other soloed tracks (tracks.set), and every changed state is restored afterwards, also on failure; the result carries soloed, unsoloed, previousSolo and restored (restoreFailed if a restore write failed). It refuses (not_isolated, nothing recorded) while other tracks have playing launcher clips; stopOthers=true (with soloTrack) first stops those clips (stoppedTracks lists them). Returns outcome (applied; unknown with a reason when a transport stop or solo restore could not be confirmed), path, duration, peak, rms, clipping, silent. Grid writes need a stopped transport; this leaves it stopped."
AUDIO_ANALYZE_DESCRIPTION = "Offline WAV analysis of one absolute .wav (channel 0, 16/24/32-bit PCM or float; no Bitwig needed). mode=level: peak/rms/silenceRatio per window; onsets: onset times and intervals (method=flux default: spectral flux, handles overlapping long decays; method=envelope: old 5 ms envelope threshold); mono_pitch: per-onset MIDI pitch for monophonic material plus summary (pitch classes, intervals, stepwiseRatio, leaps, range) and 16th-grid positions when bpm is given; probe: signed 10 ms means, for reading a logic/CV signal routed straight to Audio Out as a scope. mode=loudness (all channels): ITU-R BS.1770-4 integrated LUFS (K-weighting, 400 ms blocks, -70 LUFS and -10 LU gates), momentary/short-term maxima, EBU 3342 loudness range, sample peak and a 4x true-peak estimate (dBTP, about +-0.1 dB); `limits` in the result lists the approximations; at most 1800 s per call, pure Python (about 2 s per stereo minute). bands: per windowSeconds (default 8) band energy relative to the window total in dB (sub <60, low 60-250, mid 250-2k, high 2-8k, air >8k Hz), spectral centroid and RMS, plus stereoCorrelation (L/R, whole file; the only use of channel 1); optional key 'F:aeolian' (ionian/dorian/phrygian/lydian/mixolydian/aeolian/locrian/harmonic_minor) adds in-key chroma share per window and overall and top pitch classes (drums pollute chroma), optional spectrogramPath writes a log-frequency PNG. Windows sample up to 16 frames each. skipSeconds drops warm-up."


SPECS: dict[str, ToolSpec] = {
    "audio.route": tool(
        AudioRouteArgs, AUDIO_ROUTE_DESCRIPTION, False, {"mode": "silent"}, host=False, annotations=SET
    ),
    "audio.capture": tool(
        AudioCaptureArgs,
        AUDIO_CAPTURE_DESCRIPTION,
        False,
        {"seconds": 8, "outPath": "/tmp/bitwig-capture.wav"},
        host=False,
        annotations=ADD,
    ),
    "audio.analyze": tool(
        AudioAnalyzeArgs,
        AUDIO_ANALYZE_DESCRIPTION,
        True,
        {"path": "/tmp/bitwig-capture.wav", "mode": "level"},
        host=False,
    ),
}
