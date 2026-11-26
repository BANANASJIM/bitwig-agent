"""Working Grid specs shared by the `recipes` help topic and grid.apply's example; tests plan them against a fake Poly Grid."""

from __future__ import annotations

import json
from typing import Any

# A fresh Poly Grid holds Union (oscillator) -> ADSR (amp, gate by pre-cord) -> Audio Out, unnamed, so apply matches them
# by their generated names; the SVF goes between and the row is respaced. On any other device: grid.export, then edit.
BASS_MODULES: list[dict[str, Any]] = [
    {"name": "_Union_-2_-1", "type": "Union", "x": -2, "y": -1},
    {
        "name": "B: lp",
        "type": "SVF",
        "x": 1,
        "y": 2,
        "params": {
            "TYPE": {"label": "Low-pass"},
            "KEYTRACK": 0,
            "CUTOFF": {"display": "800 Hz"},
            "RESONANCE": {"display": "20 %"},
        },
    },
    {"name": "_ADSR_1_-1", "type": "ADSR", "x": 4, "y": 2, "params": {"RELEASE": {"display": "150 ms"}}},
    {"name": "_Audio Out_4_-1", "type": "Audio Out", "x": 7, "y": 2},
]
BASS_CONNECTIONS: list[dict[str, str]] = [
    {"from": "_Union_-2_-1.OUT", "to": "B: lp.IN"},
    {"from": "B: lp.OUT", "to": "_ADSR_1_-1.IN"},
    {"from": "_ADSR_1_-1.OUT", "to": "_Audio Out_4_-1.IN"},
]
BASS_SPEC: dict[str, Any] = {"modules": BASS_MODULES, "connections": BASS_CONNECTIONS}
# A partial entry (name and params only) tunes the live module; it works while the transport plays.
BASS_TWEAK: dict[str, Any] = {
    "modules": [{"name": "B: lp", "params": {"CUTOFF": {"display": "400 Hz"}, "RESONANCE": {"display": "35 %"}}}]
}


def _json(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


RECIPES = (
    "Poly Grid bass (subtractive): sawtooth oscillator, low-pass filter at a fixed cutoff, envelope as the amplifier, "
    "Audio Out. 1) tracks.insert an instrument track. 2) devices.catalog, then devices.insert kind=file with the path "
    "of Poly Grid. 3) devices.list for its device ref. 4) grid.apply with target = that ref and the spec below. "
    "A fresh Poly Grid already holds Union (a multi-wave oscillator, sawtooth by default), ADSR and Audio Out wired in "
    "that order, unnamed; their pitch and gate need no cable (pre-cords). The spec names them by their generated names "
    "_<type>_<x>_<y> (the positions of a fresh device on Bitwig 6.1.4), adds the SVF between them and respaces the row; "
    "KEYTRACK 0 keeps the cutoff fixed, because a keytracked filter in a Poly Grid darkens low notes. On any other "
    "device run grid.export first and edit what it returns. Apply it directly: dryRun is for prune/replace. "
    f"Spec modules: {_json(BASS_MODULES)}. connections: {_json(BASS_CONNECTIONS)}. "
    "Tune it live with a partial apply (works while the transport plays; only module entries with name and params, "
    f"no type): {_json(BASS_TWEAK)}. Voicing is the default single true-mono voice, right for a bass; "
    "voicing:{voices:N} changes it (see grid.apply). Then play a clip through the track and check meters.get or "
    "audio.capture; a valid graph is not proof of sound."
)
