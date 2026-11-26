from __future__ import annotations

from typing import Self

from pydantic import model_validator

from bitwig_agent.protocol.base import StatusArgs, ToolSpec, omitted
from bitwig_agent.protocol.refs import DeviceRef, SetParameterRef
from bitwig_agent.protocol.values import Display, Expect, Label

RawValue = bool | int | float
ParameterValue = RawValue | Display | Label
"""A host parameter takes a normalized 0..1 number or {display}; a modulator control takes its raw value or {label}."""


class ParametersListArgs(StatusArgs):
    parent: DeviceRef


class ParameterExpect(Expect):
    value: RawValue


class ParametersSetArgs(StatusArgs):
    target: SetParameterRef
    value: ParameterValue
    expect: ParameterExpect = omitted(description="Compares the current value first; a different one writes nothing.")

    @model_validator(mode="after")
    def validate_value(self) -> Self:
        if self.target.owner.kind == "modulator":
            return self
        if isinstance(self.value, Label):
            raise ValueError("{label} is only for a modulator control's enum; write the value you read")
        if isinstance(self.value, bool) or (isinstance(self.value, int | float) and not 0 <= self.value <= 1):
            raise ValueError("value must be a number from 0 to 1 or {display}")
        if self.expect is not None and isinstance(self.expect.value, bool):
            raise ValueError("expect.value must be a number from 0 to 1")
        return self


PARAMETER_REF = {
    "kind": "parameter",
    "owner": {
        "kind": "device",
        "track": {"kind": "track", "id": "12345678-1234-1234-1234-123456789abc", "name": "Lead"},
        "index": 0,
        "name": "EQ+",
    },
    "id": "CONTENTS/GAIN",
    "name": "Gain",
}

MODULATOR_PARAMETER_REF = {
    "kind": "parameter",
    "owner": {
        "kind": "modulator",
        "device": PARAMETER_REF["owner"],
        "id": "abcdef01-2345-6789-abcd-0123456789ab",
        "name": "LFO",
    },
    "id": "POLARITY",
}

PARAMETERS_LIST_DESCRIPTION = "List the direct parameters of one device (a root device or a device inside a chain) without changing selection: each row has the parameter ref, its name and value {value, display?} (value is normalized 0..1, display is the host's own text when it is observed). parent is a device ref from devices.list."
PARAMETERS_SET_DESCRIPTION = 'Set one parameter: target is a parameter ref from parameters.list, devices.remote.get or modulators.list/get; value is the bare value the read returned, or for a normalized parameter {display: "800 Hz"} (a numeric display the host shows, found by bisection on its own text and only while the transport is stopped; a text display or an enum is unsupported_unit, write its value), or for a modulator control {display} (float) or {label} (enum). expect {value} compares the current value first (stale if it differs). A Remote Control is written on its own page without touching the GUI page. Grid parameters take grid.apply and track volume/pan tracks.set. Result outcome applied or already_matched with the parameter ref; never retry an unknown outcome.'


SPECS: dict[str, ToolSpec] = {
    "parameters.list": ToolSpec(
        ParametersListArgs,
        PARAMETERS_LIST_DESCRIPTION,
        True,
        examples=({"parent": PARAMETER_REF["owner"]},),
        records="parameters",
    ),
    "parameters.set": ToolSpec(
        ParametersSetArgs,
        PARAMETERS_SET_DESCRIPTION,
        False,
        examples=(
            {"target": PARAMETER_REF, "value": 0.5},
            {"target": PARAMETER_REF, "value": {"display": "800 Hz"}},
            {"target": MODULATOR_PARAMETER_REF, "value": {"label": "Bipolar"}, "expect": {"value": 0}},
        ),
        summary='Set one parameter by ref from parameters.list, devices.remote.get or modulators.list: value is the bare value read (normalized 0..1), {display: "800 Hz"} for a numeric display, or {label} for a modulator enum; expect {value} guards the current value. Display writes need a stopped transport.',
    ),
}
