from __future__ import annotations

import math
import re
from typing import Annotated, Self

from pydantic import Field, field_validator, model_validator

from bitwig_agent.protocol.base import Nonempty, StatusArgs, StrictModel, ToolSpec, omitted
from bitwig_agent.protocol.values import Normalized

MAX_TIME_SIGNATURE_NUMERATOR = 32


class TransportLoop(StrictModel):
    start: Annotated[float, Field(ge=0)] = omitted()
    length: Annotated[float, Field(gt=0)] = omitted()

    @model_validator(mode="after")
    def require_field(self) -> Self:
        if not self.model_fields_set:
            raise ValueError("loop needs start or length")
        if {"start", "length"} <= self.model_fields_set and not math.isfinite(self.start + self.length):
            raise ValueError("loop.start + loop.length must be finite")
        return self


class TransportSetArgs(StatusArgs):
    playing: bool = omitted()
    # The installed Maschine JAM/MainKnobControl.js uses raw BPM 20..666; RangedValue leaves raw limits undefined.
    tempo: Annotated[float, Field(ge=20, le=666)] = omitted()
    position: Annotated[float, Field(ge=0)] = omitted()
    metronome: bool = omitted()
    loopEnabled: bool = omitted()
    loop: TransportLoop = omitted(description="Arranger loop in beats; supply only the fields that change.")
    timeSignature: str = omitted(
        description=f"numerator/denominator, e.g. 7/8: numerator 1..{MAX_TIME_SIGNATURE_NUMERATOR}, denominator 1, 2, 4, 8, 16 or 32."
    )
    metronomeVolume: Normalized = omitted(
        description="Metronome volume: the normalized value 0..1 that transport.get returned, or {display}."
    )
    punchInEnabled: bool = omitted(description="Arranger punch-in on/off.")
    punchOutEnabled: bool = omitted(description="Arranger punch-out on/off.")
    preRoll: Nonempty = omitted(
        description="Pre-roll length: the value or an option of transport.get preRoll (the host's own list)."
    )
    defaultLaunchQuantization: Nonempty = omitted(
        description="Project default clip launch quantization: an option of transport.get defaultLaunchQuantization."
    )
    recordQuantizationGrid: Nonempty = omitted(
        description="Grid for quantizing recorded notes: an option of transport.get recordQuantizationGrid."
    )
    arrangerRecordEnabled: bool = omitted(
        description="Global Record (the arranger record button). Turned on while playing (or with playing=true in the same call) it records into armed tracks."
    )
    arrangerOverdubEnabled: bool = omitted(description="Arranger overdub on/off.")
    clipLauncherOverdubEnabled: bool = omitted(description="Clip launcher overdub on/off.")
    arrangerAutomationWriteEnabled: bool = omitted(description="Automation write (the arranger write button) on/off.")
    clipLauncherAutomationWriteEnabled: bool = omitted(
        description="Refused (unsupported_scope): it has no effect in Bitwig 6, which has one automation write button; use arrangerAutomationWriteEnabled. Read by transport.get."
    )
    automationWriteMode: Nonempty = omitted(
        description="Automation write mode: an option of transport.get automationWriteMode (latch, touch, write)."
    )

    @field_validator("timeSignature")
    @classmethod
    def validate_signature(cls, value: str) -> str:
        if (
            not re.fullmatch(r"[1-9][0-9]?/(1|2|4|8|16|32)", value, re.ASCII)
            or int(value.split("/", maxsplit=1)[0]) > MAX_TIME_SIGNATURE_NUMERATOR
        ):
            raise ValueError(
                f"timeSignature must be numerator/denominator: numerator 1..{MAX_TIME_SIGNATURE_NUMERATOR}, denominator 1, 2, 4, 8, 16 or 32"
            )
        return value

    @model_validator(mode="after")
    def require_change(self) -> Self:
        if not self.model_fields_set - {"timeoutMs"}:
            raise ValueError("transport.set requires at least one changed field")
        return self


TRANSPORT_GET_DESCRIPTION = "Read observed projectName, playing, tempo, position, metronome and loop settings. Recording/automation fields are arrangerRecordEnabled (Global Record), arrangerOverdubEnabled, clipLauncherOverdubEnabled, arrangerAutomationWriteEnabled, clipLauncherAutomationWriteEnabled and automationWriteMode {value, options}; a write that needs recording off (help shows Requires: notRecording or stopped) needs the three record/automation-write booleans explicitly false, not missing/null. One response covers project-label and playback-safety preflight; the name is not full-path identity or an atomic lock. metronomeVolume is {value 0..1, display}; preRoll, defaultLaunchQuantization and recordQuantizationGrid are {value, options} with the host's own option list (value null until observed). punchInEnabled and punchOutEnabled are booleans."
TRANSPORT_SET_DESCRIPTION = "Change only supplied transport fields; e.g. tempo=120 playing=false. Supported tempo range 20..666 BPM is a toolkit bound. Beat positions are nonnegative; loop.length is positive and the loop end finite. An explicit playing=false is observed before other supplied fields are dispatched; this is not an atomic transaction. Inspect the outcome and per-field outcome/reason; stop on unknown. Playback can produce audio. The recording fields (arrangerRecordEnabled, the two overdubs, arrangerAutomationWriteEnabled, automationWriteMode) can be changed while recording or automation write is on, so recording can always be turned off, and playing=false always stops; every other field, playing=true included (as for launches), is refused (unsafe_state) while recording or automation write is on, and modes are never silently disabled. playing is written before the recording fields, so playing=true with arrangerRecordEnabled=true in one call explicitly starts recording into armed tracks, as does arrangerRecordEnabled=true while playing. clipLauncherAutomationWriteEnabled is refused up front (unsupported_scope; no effect in Bitwig 6, use arrangerAutomationWriteEnabled). Recording state Bitwig changes along with a requested field (Global Record also switches arrangerAutomationWriteEnabled) is reported in observedSideEffects {field: {before, after}}. timeSignature (e.g. '7/8', numerator 1..32, denominator 1/2/4/8/16/32) is read back like the other fields; time-signature change markers are not in the public API. metronomeVolume takes the value transport.get returned (0..1, not dB); {display} is refused as invalid_params/unsupported_unit until the host's metronome display is measured. punchInEnabled, punchOutEnabled, preRoll, defaultLaunchQuantization (project default clip launch quantization) and recordQuantizationGrid take the same per-field readback; the three enums take the bare option value from transport.get (the host's own list, refused as invalid_params/not_applied otherwise, before anything is written) and punch/pre-roll/quantization edits are blocked while recording or automation write is on, like the other settings. automationWriteMode takes an option of transport.get automationWriteMode the same way."


SPECS: dict[str, ToolSpec] = {
    "transport.get": ToolSpec(StatusArgs, TRANSPORT_GET_DESCRIPTION, True),
    "transport.set": ToolSpec(
        TransportSetArgs,
        TRANSPORT_SET_DESCRIPTION,
        False,
        examples=(
            {"tempo": 120.0, "playing": False},
            {"preRoll": "one_bar", "punchInEnabled": True, "metronomeVolume": 0.5},
            {"loopEnabled": True, "loop": {"start": 0, "length": 16}},
            {"arrangerRecordEnabled": False, "arrangerAutomationWriteEnabled": False},
            {"automationWriteMode": "latch", "clipLauncherOverdubEnabled": True},
        ),
    ),
}
