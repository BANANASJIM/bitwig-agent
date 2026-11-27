"""Working Grid specs shared by the `recipes` help topic and grid.apply's example; tests plan them against a fake Poly Grid."""

from __future__ import annotations

import json
from typing import Any

# pruneAll makes the spec the whole device: the fresh Poly Grid's default modules are deleted first, so no generated names or
# positions are assumed. Pitch and gate reach the oscillator and envelope by pre-cord, without a cable.
BASS_MODULES: list[dict[str, Any]] = [
    {"name": "B: osc", "type": "Union", "x": 0, "y": 0},
    {
        "name": "B: lp",
        "type": "SVF",
        "x": 4,
        "y": 0,
        "params": {
            "TYPE": {"label": "Low-pass"},
            "KEYTRACK": 0,
            "CUTOFF": {"display": "800 Hz"},
            "RESONANCE": {"display": "20 %"},
        },
    },
    {"name": "B: env", "type": "ADSR", "x": 8, "y": 0, "params": {"RELEASE": {"display": "150 ms"}}},
    {"name": "B: out", "type": "Audio Out", "x": 12, "y": 0},
]
BASS_CONNECTIONS: list[dict[str, str]] = [
    {"from": "B: osc.OUT", "to": "B: lp.IN"},
    {"from": "B: lp.OUT", "to": "B: env.IN"},
    {"from": "B: env.OUT", "to": "B: out.IN"},
]
BASS_SPEC: dict[str, Any] = {"modules": BASS_MODULES, "connections": BASS_CONNECTIONS, "pruneAll": True}
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
    "pruneAll:true makes the spec the device's whole graph: it deletes every module the spec does not declare (a fresh "
    "Poly Grid's default oscillator, envelope and output included), so use it only on a new device; to change an "
    "existing patch run grid.export and edit what it returns. Union is a multi-wave oscillator (sawtooth by default); "
    "pitch and gate reach it and the ADSR by pre-cord, without a cable. KEYTRACK 0 keeps the cutoff fixed, because a "
    "keytracked filter in a Poly Grid darkens low notes. Applying it again is a no-op. "
    f"spec (grid.apply fields besides target, pruneAll included): {_json(BASS_SPEC)}. "
    "Tune it live with a partial apply (works while the transport plays; only module entries with name and params, "
    f"no type, and no pruneAll): {_json(BASS_TWEAK)}. Voicing is the default single true-mono voice, right for a bass; "
    "voicing:{voices:N} changes it (see grid.apply). Then play a clip through the track and check meters.get or "
    "audio.capture; a valid graph is not proof of sound."
)
