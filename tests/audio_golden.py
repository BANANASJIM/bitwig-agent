"""Deterministic synthetic WAVs and the audio.analyze calls whose outputs form the golden baseline.

The baseline (tests/fixtures/audio_golden_baseline.json) holds the outputs of the stdlib implementation that predates
the numpy/scipy one; test_audio_golden.py diffs the current outputs against it field by field.

Regenerate (only when an intended behaviour change is documented in test_audio_golden.py):
  python tests/audio_golden.py <out.json>
"""

import json
import math
import random
import struct
import sys
import tempfile
from pathlib import Path

from bitwig_agent.audio import analysis

# (bits, float?) -> (format tag, pack function for one sample)
_ENCODINGS = {
    (16, False): (1, lambda v: struct.pack("<h", max(-32768, min(32767, round(v * 32767))))),
    (24, False): (1, lambda v: max(-8388608, min(8388607, round(v * 8388607))).to_bytes(3, "little", signed=True)),
    (32, True): (3, lambda v: struct.pack("<f", v)),
    (64, True): (3, lambda v: struct.pack("<d", v)),
}


def write(path, channels, rate, bits, is_float=False):
    tag, pack = _ENCODINGS[bits, is_float]
    width = bits // 8
    data = b"".join(pack(ch[i]) for i in range(len(channels[0])) for ch in channels)
    fmt = struct.pack("<HHIIHH", tag, len(channels), rate, rate * width * len(channels), width * len(channels), bits)
    body = b"WAVEfmt " + struct.pack("<I", 16) + fmt + b"data" + struct.pack("<I", len(data)) + data
    path.write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)


def sine(hz, seconds, rate, db=-6.0, phase=0.0):
    amp = 10 ** (db / 20)
    return [amp * math.sin(2 * math.pi * hz * i / rate + phase) for i in range(int(seconds * rate))]


def noise(seconds, rate, db=-12.0, seed=7):
    rng = random.Random(seed)
    amp = 10 ** (db / 20)
    return [amp * max(-1.0, min(1.0, rng.gauss(0, 0.3))) for _ in range(int(seconds * rate))]


def clicks(seconds, rate, bpm=120.0):
    """A decaying 1 kHz burst on every beat."""
    out = [0.0] * int(seconds * rate)
    step = int(60 / bpm * rate)
    for start in range(int(0.1 * rate), len(out), step):
        for k in range(min(int(0.05 * rate), len(out) - start)):
            out[start + k] = 0.8 * math.exp(-k / (0.008 * rate)) * math.sin(2 * math.pi * 1000 * k / rate)
    return out


def melody(rate):
    """Monophonic sine notes (C4 D4 E4 G4 A4 C5) of 0.2 s every 0.25 s."""
    out = [0.0] * int(1.8 * rate)
    for i, midi in enumerate((60, 62, 64, 67, 69, 72)):
        hz = 440 * 2 ** ((midi - 69) / 12)
        start, frames = int((0.1 + 0.25 * i) * rate), int(0.2 * rate)
        for k in range(frames):
            out[start + k] = 0.6 * min(1, k / 48) * (1 - k / frames) * math.sin(2 * math.pi * hz * k / rate)
    return out


def signals():
    """name -> (channels, rate, bits, float?)."""
    r = 48000
    tone = sine(440, 4, r)
    return {
        "sine440_f32": ([tone, sine(440, 4, r, db=-9.0)], r, 32, True),
        "sine2500_s16": ([sine(2500, 4, r)] * 2, r, 16, False),
        "sine3000_s24": ([sine(3000, 4, r)] * 2, r, 24, False),
        "noise_f64": ([noise(4, r), noise(4, r, seed=8)], r, 64, True),
        "two_level_f32": ([sine(997, 6, r, db=-30.0) + sine(997, 6, r, db=-20.0)] * 2, r, 32, True),
        "intersample_f32": ([sine(12000, 2, r, db=0.0, phase=math.pi / 4)] * 2, r, 32, True),
        "clicks_44k_s16": ([clicks(4, 44100)] * 2, 44100, 16, False),
        "melody_s16": ([melody(r)], r, 16, False),
    }


def calls():
    """(signal, audio.analyze arguments) pairs; 'path' is filled in per run."""
    out = []
    for name in signals():
        out += [
            (name, {"mode": "level", "windowSeconds": 1.0}),
            (name, {"mode": "loudness"}),
            (name, {"mode": "bands", "windowSeconds": 2.0, "key": "A:aeolian"}),
            (name, {"mode": "onsets"}),
            (name, {"mode": "onsets", "method": "envelope"}),
        ]
    out += [
        ("melody_s16", {"mode": "mono_pitch", "bpm": 120}),
        ("clicks_44k_s16", {"mode": "mono_pitch", "method": "envelope"}),
        ("sine440_f32", {"mode": "probe"}),
        ("sine440_f32", {"mode": "bands", "windowSeconds": 1.0, "spectrogramPath": "spectrogram.png"}),
    ]
    return out


def key(name, args):
    return name + " " + json.dumps(args, sort_keys=True)


def run_all(folder):
    """Write every signal under folder and return {call key: output without paths}."""
    folder = Path(folder)
    for name, (channels, rate, bits, is_float) in signals().items():
        write(folder / f"{name}.wav", channels, rate, bits, is_float)
    results = {}
    for name, args in calls():
        call = {**args, "path": str(folder / f"{name}.wav")}
        if "spectrogramPath" in call:
            call["spectrogramPath"] = str(folder / call["spectrogramPath"])
        out = analysis.analyze(call)
        out.pop("path", None)
        out.get("spectrogram", {}).pop("path", None)
        results[key(name, args)] = out
    return results


if __name__ == "__main__":
    with tempfile.TemporaryDirectory() as tmp:
        Path(sys.argv[1]).write_text(json.dumps(run_all(tmp), indent=1, sort_keys=True) + "\n")
