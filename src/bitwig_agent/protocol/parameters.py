from __future__ import annotations

from pydantic import Field

from bitwig_agent.protocol.base import TARGET, Nonempty, ParameterArgs, ToolSpec, TrackId, UnitValue
from bitwig_agent.protocol.devices import LAYER_TARGET, LayerListArgs


class LayerParameterArgs(LayerListArgs):
    layerId: TrackId
    childDeviceIndex: int = Field(ge=0, le=1)
    expectedChildDeviceName: Nonempty
    discoveryToken: Nonempty


class LayerSetArgs(LayerParameterArgs):
    contextToken: Nonempty
    parameterId: Nonempty
    value: UnitValue


class SetArgs(ParameterArgs):
    contextToken: Nonempty
    parameterId: Nonempty
    value: UnitValue


PARAMETERS_LIST_DESCRIPTION = "Read source-bound direct parameters and contextToken without changing host cursor/selection. Preserve trackId, deviceIndex, exact expectedDeviceName and fresh token for parameters.set. Bank context is not persistent physical-device identity."
PARAMETERS_SET_DESCRIPTION = "Set one normalized direct parameter using the exact target, parameterId and contextToken from parameters.list. Example: value=0.5. Inspect scope and the outcome. Never retry an unknown outcome without inspecting state."
PARAMETERS_LAYER_LIST_DESCRIPTION = "Read one discovered layer child's direct parameters and contextToken without selection. Supply the explicit parent and fresh discoveryToken from devices.layers.list. Structural ancestor changes invalidate tokens; names are metadata checks, not persistent identity. Physically rebound child sources fail closed without a genuine provenance boundary. No nested remotes, deeper layers, pads, slots or Grid-private targeting."
PARAMETERS_LAYER_SET_DESCRIPTION = "Set one normalized layer-child direct parameter in 0..1 using the exact discovered target, discoveryToken and fresh contextToken/parameterId from parameters.layer.list. Inspect readiness, scope and the outcome; never retry an unknown outcome without inspecting state. No selection or GUI fallback."


SPECS: dict[str, ToolSpec] = {
    "parameters.list": ToolSpec(ParameterArgs, PARAMETERS_LIST_DESCRIPTION, True, examples=(TARGET,)),
    "parameters.set": ToolSpec(
        SetArgs,
        PARAMETERS_SET_DESCRIPTION,
        False,
        examples=(
            {**TARGET, "contextToken": "from-parameters.list", "parameterId": "from-parameters.list", "value": 0.5},
        ),
    ),
    "parameters.layer.list": ToolSpec(
        LayerParameterArgs, PARAMETERS_LAYER_LIST_DESCRIPTION, True, examples=(LAYER_TARGET,)
    ),
    "parameters.layer.set": ToolSpec(
        LayerSetArgs,
        PARAMETERS_LAYER_SET_DESCRIPTION,
        False,
        examples=(
            {
                **LAYER_TARGET,
                "contextToken": "from-parameters.layer.list",
                "parameterId": "from-parameters.layer.list",
                "value": 0.5,
            },
        ),
        summary="Set one normalized (0..1) layer-child parameter using the tokens from devices.layers.list and parameters.layer.list.",
    ),
}
