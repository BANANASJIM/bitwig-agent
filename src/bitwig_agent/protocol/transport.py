from __future__ import annotations

import math
import re
from typing import Annotated, Self

from pydantic import Field, field_validator, model_validator

from bitwig_agent.protocol.base import Nonempty, StatusArgs, ToolSpec, UnitValue, omitted, tool

MAX_TIME_SIGNATURE_NUMERATOR = 32


class TransportSetArgs(StatusArgs):
    playing: bool = omitted()
    tempoBpm: Annotated[float, Field(ge=20, le=666)] = omitted()
    positionBeats: Annotated[float, Field(ge=0)] = omitted()
    metronome: bool = omitted()
    loopEnabled: bool = omitted()
    loopStartBeats: Annotated[float, Field(ge=0)] = omitted()
    loopDurationBeats: Annotated[float, Field(gt=0)] = omitted()
    timeSignature: str = omitted(
        description=f"numerator/denominator, e.g. 7/8: numerator 1..{MAX_TIME_SIGNATURE_NUMERATOR}, denominator 1, 2, 4, 8, 16 or 32."
    )
    metronomeVolumeNormalized: UnitValue = omitted(description="Metronome volume 0..1 (not dB); read back as observed.")
    punchInEnabled: bool = omitted(description="Arranger punch-in on/off.")
    punchOutEnabled: bool = omitted(description="Arranger punch-out on/off.")
    preRoll: Nonempty = omitted(
        description="Pre-roll length: one of transport.get options.preRoll (the host's own list)."
    )
    defaultLaunchQuantization: Nonempty = omitted(
        description="Project default clip launch quantization: one of transport.get options.defaultLaunchQuantization."
    )
    recordQuantizationGrid: Nonempty = omitted(
        description="Grid for quantizing recorded notes: one of transport.get options.recordQuantizationGrid."
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
        if {"loopStartBeats", "loopDurationBeats"} <= self.model_fields_set and not math.isfinite(
            self.loopStartBeats + self.loopDurationBeats
        ):
            raise ValueError("loopStartBeats + loopDurationBeats must be finite")
        return self


TRANSPORT_GET_DESCRIPTION = "Read observed projectName, playing, tempoBpm, positionBeats, metronome and loop settings. Recording/automation fields are arrangerRecordEnabled, arrangerAutomationWriteEnabled and clipLauncherAutomationWriteEnabled; require each explicitly false before stopped-fixture edits, not missing/null. One response covers project-label and playback-safety preflight; the name is not full-path identity or an atomic lock. Also reads metronomeVolumeNormalized (0..1), punchInEnabled, punchOutEnabled, preRoll, defaultLaunchQuantization and recordQuantizationGrid, with options listing the host's own values for the three enums (null until observed)."
TRANSPORT_SET_DESCRIPTION = "Change only supplied transport fields; e.g. tempoBpm=120 playing=false. Supported tempo range 20..666 BPM is a toolkit bound. Beat positions are nonnegative; loop duration is positive and loop end finite. An explicit playing=false is observed before other supplied fields are dispatched; this is not an atomic transaction. Inspect per-field verification and stop on unknown completion. Playback can produce audio. Recording or automation-write mode blocks changes except an explicit playing=false stop; modes are never silently disabled. timeSignature (e.g. '7/8', numerator 1..32, denominator 1/2/4/8/16/32) is read back like the other fields; time-signature change markers are not in the public API. metronomeVolumeNormalized (0..1, not dB), punchInEnabled, punchOutEnabled, preRoll, defaultLaunchQuantization (project default clip launch quantization) and recordQuantizationGrid take the same per-field readback; the three enums must be one of transport.get options (the host's own list, refused as invalid_params/not_applied otherwise, before anything is written) and punch/pre-roll/quantization edits are blocked while recording or automation write is on, like the other settings."


SPECS: dict[str, ToolSpec] = {
    "transport.get": tool(StatusArgs, TRANSPORT_GET_DESCRIPTION, True),
    "transport.set": tool(
        TransportSetArgs,
        TRANSPORT_SET_DESCRIPTION,
        False,
        {"tempoBpm": 120.0, "playing": False},
        {"preRoll": "one_bar", "punchInEnabled": True, "metronomeVolumeNormalized": 0.5},
    ),
}
