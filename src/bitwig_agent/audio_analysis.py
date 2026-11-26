"""Stdlib-only WAV analysis for audio.analyze: level, onsets, monophonic pitch, 10 ms probe, spectral bands."""

from __future__ import annotations

import cmath
import math
import struct
import sys
import zlib
from array import array
from itertools import accumulate, pairwise
from statistics import median
from typing import Any

from bitwig_agent import loudness

MAX_BYTES = 256 * 1024 * 1024
FFT_SIZE = 4096
HOP_SECONDS = 0.005
SILENT_PEAK = 0.01  # below this the whole file is reported silent (about -40 dBFS)
CLIP_LEVEL = 0.999
MAX_ONSETS = 256
MAX_PROBE = 2000
MAX_WINDOWS = 600


WAVE_FORMAT_PCM = 1
WAVE_FORMAT_IEEE_FLOAT = 3
WAVE_FORMAT_EXTENSIBLE = 0xFFFE
EXTENSIBLE_MIN_FMT_SIZE = 26
MIN_SAMPLE_RATE = 8000
QUIET_RMS = 0.001
LEAP_SEMITONES = 5
STEP_SEMITONES = 2
SEMITONE_CENTS = 100
MIN_ENERGY = 1e-12


class WavError(ValueError):
    pass


def read_wav(path: str, all_channels: bool = False) -> tuple[int, int, Any]:  # noqa: C901, PLR0912, PLR0915
    """Return (sampleRate, channels, first-channel samples as floats in -1..1), or a list of every channel's samples
    with all_channels=True. Tolerates streamed headers."""
    try:
        with open(path, "rb") as handle:
            data = handle.read(MAX_BYTES + 1)
    except OSError as exc:
        raise WavError(f"Cannot read {path}: {exc.strerror}") from exc
    if len(data) > MAX_BYTES:
        raise WavError("WAV exceeds 256 MiB")
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise WavError("Not a RIFF/WAVE file")
    fmt = None
    pcm = None
    pos = 12
    while pos + 8 <= len(data):
        tag, size = data[pos : pos + 4], struct.unpack_from("<I", data, pos + 4)[0]
        body = pos + 8
        if tag == b"fmt ":
            fmt = struct.unpack_from("<HHIIHH", data, body)
            # the sub-format GUID starts with the real tag
            if fmt[0] == WAVE_FORMAT_EXTENSIBLE and size >= EXTENSIBLE_MIN_FMT_SIZE:
                fmt = (struct.unpack_from("<H", data, body + 24)[0], *fmt[1:])
        elif tag == b"data":
            # parec/libsndfile may leave 0 or 0xFFFFFFFF in a header it could not finalize.
            end = len(data) if size in (0, 0xFFFFFFFF) or body + size > len(data) else body + size
            pcm = data[body:end]
            break
        pos = body + size + (size & 1)
    if fmt is None or pcm is None:
        raise WavError("WAV has no fmt or data chunk")
    tag, channels, rate, _, _, bits = fmt
    if channels < 1 or rate < MIN_SAMPLE_RATE:
        raise WavError("Unsupported channel count or sample rate")
    width = bits // 8
    frame = width * channels
    pcm = pcm[: len(pcm) // frame * frame]
    samples: array[Any]
    if (tag, bits) == (WAVE_FORMAT_PCM, 16):
        samples = array("h", pcm)
        scale = 1 / 32768
    elif (tag, bits) == (WAVE_FORMAT_PCM, 32):
        samples = array("i", pcm)
        scale = 1 / 2147483648
    elif (tag, bits) == (WAVE_FORMAT_PCM, 24):
        samples = array("i", (int.from_bytes(pcm[i : i + 3], "little", signed=True) for i in range(0, len(pcm), 3)))
        scale = 1 / 8388608
    elif (tag, bits) == (WAVE_FORMAT_IEEE_FLOAT, 32):
        samples, scale = array("f", pcm), 1.0
    elif (tag, bits) == (WAVE_FORMAT_IEEE_FLOAT, 64):
        samples, scale = array("d", pcm), 1.0
    else:
        raise WavError(f"Unsupported WAV encoding (format {tag}, {bits}-bit); use 16/24/32-bit PCM or float")
    if tag == 1 and bits in (16, 32) and sys.byteorder == "big":
        samples.byteswap()
    if all_channels:
        return rate, channels, [array("d", (v * scale for v in samples[c::channels])) for c in range(channels)]
    channel = samples[::channels]
    return rate, channels, array("d", (v * scale for v in channel))


def _db(value: float) -> float | None:
    return round(20 * math.log10(value), 2) if value > 0 else None


def _envelope(x: array[Any], hop: int) -> list[float]:
    return [max(map(abs, x[i : i + hop])) for i in range(0, len(x) - hop + 1, hop)]


def _rms(seg: array[Any]) -> float:
    return math.sqrt(sum(v * v for v in seg) / len(seg)) if len(seg) else 0.0


def level(x: array[Any], rate: int, window_seconds: float) -> dict[str, Any]:
    peak = max(map(abs, x), default=0.0)
    rms = _rms(x)
    window_seconds = max(window_seconds, len(x) / rate / MAX_WINDOWS)
    size = max(1, int(window_seconds * rate))
    rows = []
    for i in range(0, len(x), size):
        seg = x[i : i + size]
        rows.append({"t": round(i / rate, 3), "peak": round(max(map(abs, seg)), 4), "rms": round(_rms(seg), 4)})
    quiet = sum(1 for r in rows if r["rms"] < QUIET_RMS)
    return {
        "peak": round(peak, 4),
        "peakDb": _db(peak),
        "rms": round(rms, 4),
        "rmsDb": _db(rms),
        "clipping": peak >= CLIP_LEVEL,
        "windowSeconds": round(window_seconds, 3),
        "silenceRatio": round(quiet / len(rows), 3) if rows else 1.0,
        "windows": rows,
    }


def onsets(x: array[Any], rate: int, threshold: float, skip: float) -> tuple[list[float], dict[str, Any]]:
    """Rising crossings of threshold*max of a 5 ms peak envelope; times are seconds from file start."""
    hop = max(1, int(HOP_SECONDS * rate))
    first = int(skip * rate) // hop
    env = _envelope(x, hop)
    top = max(env[first:], default=0.0)
    if top < SILENT_PEAK:
        return [], {"silent": True, "peak": round(top, 4)}
    limit = threshold * top
    found = [round(i * hop / rate, 4) for i in range(max(first, 1), len(env)) if env[i] > limit >= env[i - 1]]
    return found, {"silent": False, "peak": round(top, 4)}


_TWIDDLES: dict[int, list[complex]] = {}


def _fft(values: list[complex]) -> list[complex]:
    n = len(values)
    tw = _TWIDDLES.get(n)
    if tw is None:
        tw = _TWIDDLES[n] = [cmath.exp(-2j * math.pi * k / n) for k in range(n // 2)]
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            values[i], values[j] = values[j], values[i]
    size = 2
    while size <= n:
        half, stride = size // 2, n // size
        for start in range(0, n, size):
            for k in range(half):
                a, b = values[start + k], values[start + k + half] * tw[k * stride]
                values[start + k], values[start + k + half] = a + b, a - b
        size <<= 1
    return values


_WINDOW = [0.5 - 0.5 * math.cos(2 * math.pi * i / FFT_SIZE) for i in range(FFT_SIZE)]


def pitch_at(x: array[Any], rate: int, start: int) -> float | None:
    """Dominant spectral peak 10 ms after an onset, as fractional MIDI (parabolic interpolation); None if too short."""
    begin = start + int(0.01 * rate)
    seg = x[begin : begin + FFT_SIZE]
    if len(seg) < FFT_SIZE:
        return None
    spectrum = _fft([complex(v * w) for v, w in zip(seg, _WINDOW, strict=False)])
    low = max(2, int(30 * FFT_SIZE / rate))
    mags = [abs(c) for c in spectrum[: FFT_SIZE // 2]]
    k = max(range(low, len(mags) - 1), key=mags.__getitem__)
    if mags[k] <= 0:
        return None
    a, b, c = (math.log(max(mags[i], 1e-12)) for i in (k - 1, k, k + 1))
    denom = a - 2 * b + c
    delta = 0.5 * (a - c) / denom if denom else 0.0
    hz = (k + delta) * rate / FFT_SIZE
    return 69 + 12 * math.log2(hz / 440)


FLUX_SIZE = 512  # at FLUX_RATE: 21 ms frames
FLUX_HOP = 240  # 10 ms
FLUX_RATE = 24000
FLUX_MAX_HZ = 8000
MIN_SEPARATION = 0.04  # seconds between onsets
_FLUX_WINDOW = [0.5 - 0.5 * math.cos(2 * math.pi * i / FLUX_SIZE) for i in range(FLUX_SIZE)]


def _decimate(x: array[Any], rate: int) -> tuple[array[Any], int]:
    """Average-and-drop to about FLUX_RATE (crude low-pass; onsets need no high band)."""
    factor = max(1, round(rate / FLUX_RATE))
    if factor == 1:
        return x, rate
    return array("d", (sum(x[i : i + factor]) / factor for i in range(0, len(x) - factor + 1, factor))), rate // factor


def spectral_flux(x: array[Any], rate: int) -> tuple[list[float], float]:
    """Half-wave rectified log-magnitude flux per 10 ms hop, and the hop length in seconds."""
    y, yrate = _decimate(x, rate)
    hop = max(1, round(FLUX_HOP * yrate / FLUX_RATE))
    bins = min(max(2, int(FLUX_MAX_HZ * FLUX_SIZE / yrate)), FLUX_SIZE // 2)
    flux, previous = [], None
    for start in range(0, len(y) - FLUX_SIZE + 1, hop):
        spectrum = _fft([complex(v * w) for v, w in zip(y[start : start + FLUX_SIZE], _FLUX_WINDOW, strict=False)])
        mags = [math.log1p(200 * abs(c) / FLUX_SIZE) for c in spectrum[1:bins]]
        flux.append(0.0 if previous is None else sum(max(0.0, a - b) for a, b in zip(mags, previous, strict=False)))
        previous = mags
    return flux, hop * (rate / yrate) / rate


def flux_onsets(x: array[Any], rate: int, threshold: float, skip: float) -> tuple[list[float], dict[str, Any]]:
    """Peaks of the spectral flux: a new note over a long decaying tail still adds new energy per bin, which an
    amplitude envelope cannot see. A peak is a local maximum that beats its neighbourhood median by
    threshold*max(flux) and sits MIN_SEPARATION after the previous one; its time is refined to the steepest envelope rise."""
    top_peak = max(map(abs, x[int(skip * rate) :]), default=0.0)
    info = {"silent": top_peak < SILENT_PEAK, "peak": round(top_peak, 4), "method": "flux"}
    if info["silent"]:
        return [], info
    flux, hop_s = spectral_flux(x, rate)
    first = max(1, int(skip / hop_s))
    top = max(flux[first:], default=0.0)
    found: list[float] = []
    if top > 0:
        span = max(2, round(0.5 / hop_s))
        near = max(1, round(0.03 / hop_s))
        env_hop = max(1, int(HOP_SECONDS * rate))
        for i in range(first, len(flux)):
            v = flux[i]
            if v <= 0 or any(
                flux[j] > v or (flux[j] == v and j < i)
                for j in range(max(0, i - near), min(len(flux), i + near + 1))
                if j != i
            ):
                continue
            window = sorted(flux[max(0, i - span) : i + span + 1])
            if v - window[len(window) // 2] < threshold * top:
                continue
            lo = int(max(0.0, i * hop_s - 0.01) * rate)
            env = _envelope(x[lo : lo + int((FLUX_SIZE / FLUX_RATE + 0.02) * rate)], env_hop)
            rise = max(range(1, len(env)), key=lambda k: env[k] - env[k - 1], default=0)
            t = (lo + rise * env_hop) / rate
            if t < skip or (found and t - found[-1] < MIN_SEPARATION):
                continue
            found.append(round(t, 4))
    return found, info


def find_onsets(
    x: array[Any], rate: int, threshold: float, skip: float, method: str
) -> tuple[list[float], dict[str, Any]]:
    if method == "envelope":
        times, info = onsets(x, rate, threshold, skip)
        return times, {**info, "method": "envelope"}
    return flux_onsets(x, rate, threshold, skip)


NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")


def mono_pitch(  # noqa: PLR0913, PLR0917
    x: array[Any], rate: int, threshold: float, skip: float, bpm: float | None, method: str = "flux"
) -> dict[str, Any]:
    times, info = find_onsets(x, rate, threshold, skip, method)
    notes = []
    for t in times:
        midi = pitch_at(x, rate, int(t * rate))
        if midi is not None:
            notes.append((t, midi))
    result: dict[str, Any] = {**info, "noteCount": len(notes)}
    if not notes:
        return {**result, "notes": []}
    rounded = [round(m) for _, m in notes]
    ivs = [b - a for a, b in pairwise(rounded)]
    leaps = [i for i, v in enumerate(ivs) if abs(v) >= LEAP_SEMITONES]
    recovered = [i for i in leaps if i + 1 < len(ivs) and ivs[i + 1] * ivs[i] < 0 and abs(ivs[i + 1]) <= STEP_SEMITONES]
    pcs = sorted({m % 12 for m in rounded})
    summary: dict[str, Any] = {
        "pitchClasses": pcs,
        "pitchClassNames": [NAMES[p] for p in pcs],
        "range": {"min": min(rounded), "max": max(rounded), "semitones": max(rounded) - min(rounded)},
        "tuneError": round(sum(abs(m - r) for (_, m), r in zip(notes, rounded, strict=False)) / len(notes), 3),
    }
    if ivs:
        summary.update(
            intervals=ivs[:MAX_ONSETS],
            stepwiseRatio=round(sum(abs(v) <= STEP_SEMITONES for v in ivs) / len(ivs), 3),
            repeatRatio=round(sum(v == 0 for v in ivs) / len(ivs), 3),
            leaps=len(leaps),
            leapRecoveryRatio=round(len(recovered) / len(leaps), 3) if leaps else 1.0,
        )
    result["summary"] = summary
    if bpm:
        step = 60.0 / bpm / 4
        offset = median(t % step for t, _ in notes)
        steps = [round((t - offset) / step) for t, _ in notes]
        result["grid"] = {
            "stepSeconds": round(step, 5),
            "offsetSeconds": round(offset, 4),
            "positions16": [s % 16 for s in steps][:MAX_ONSETS],
            "onBeatRatio": round(sum(s % 4 == 0 for s in steps) / len(steps), 3),
        }
    result["notes"] = [
        {"t": t, "midi": round(m, 2), "name": f"{NAMES[round(m) % 12]}{round(m) // 12 - 1}"}
        for t, m in notes[:MAX_ONSETS]
    ]
    result["truncated"] = len(notes) > MAX_ONSETS
    return result


def probe(x: array[Any], rate: int, skip: float) -> dict[str, Any]:
    """Signed means over 10 ms windows: the file as a scope trace of a logic/CV signal."""
    size = max(1, rate // 100)
    start = int(skip * rate)
    means = [sum(x[i : i + size]) / size for i in range(start, len(x) - size + 1, size)]
    shown = means[:MAX_PROBE]
    return {
        "windowSeconds": 0.01,
        "startSeconds": round(start / rate, 3),
        "count": len(means),
        "truncated": len(means) > MAX_PROBE,
        "min": round(min(means), 4) if means else None,
        "max": round(max(means), 4) if means else None,
        "means": [round(v, 4) for v in shown],
    }


# ---- bands ----------------------------------------------------------------------------------------------
BAND_EDGES = (("sub", 0, 60), ("low", 60, 250), ("mid", 250, 2000), ("high", 2000, 8000), ("air", 8000, math.inf))
FRAMES_PER_WINDOW = 16  # frames sampled evenly across a window (FFT_SIZE samples each), not every sample
CHROMA_LOW, CHROMA_HIGH = 50.0, 1500.0
CHROMA_RATE = 4000  # chroma works on audio decimated to about this rate
CHROMA_MAX_SAMPLES = 65536  # longest single chroma FFT (about 16 s at CHROMA_RATE); longer windows are split
CHROMA_MIN_SAMPLES = 4000  # a leftover shorter than this (1 s) is dropped
MODES = {
    "ionian": (0, 2, 4, 5, 7, 9, 11),
    "dorian": (0, 2, 3, 5, 7, 9, 10),
    "phrygian": (0, 1, 3, 5, 7, 8, 10),
    "lydian": (0, 2, 4, 6, 7, 9, 11),
    "mixolydian": (0, 2, 4, 5, 7, 9, 10),
    "aeolian": (0, 2, 3, 5, 7, 8, 10),
    "locrian": (0, 1, 3, 5, 6, 8, 10),
    "harmonic_minor": (0, 2, 3, 5, 7, 8, 11),
}
_TONICS = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def parse_key(key: str) -> tuple[int, str]:
    """'F:aeolian' / 'C#:dorian' / 'Bb:ionian' -> (tonic pitch class, mode)."""
    tonic, _, mode = key.partition(":")
    pc = _TONICS.get(tonic[:1].upper())
    accidental = tonic[1:]
    if pc is None or accidental not in ("", "#", "b") or mode.lower() not in MODES:
        raise WavError(f"key must look like 'F:aeolian' (tonic A-G with optional #/b; mode one of {', '.join(MODES)})")
    return (pc + {"": 0, "#": 1, "b": -1}[accidental]) % 12, mode.lower()


def _frame(x: array[Any], start: int) -> tuple[list[float], list[float]]:
    """Power and magnitude of bins 0..N/2 of one Hann-windowed frame."""
    spectrum = _fft([complex(v * w) for v, w in zip(x[start : start + FFT_SIZE], _WINDOW, strict=False)])
    mags = [abs(c) for c in spectrum[: FFT_SIZE // 2 + 1]]
    return [m * m for m in mags], mags


def _lowpass_decimate(x: array[Any], factor: int) -> array[Any]:
    """Two cascaded boxcar averages (sinc^2 low-pass), then keep every factor-th sample."""
    if factor == 1:
        return x
    acc = list(accumulate(x, initial=0.0))
    once = [(acc[i + factor] - acc[i]) / factor for i in range(len(x) - factor + 1)]
    acc = list(accumulate(once, initial=0.0))
    return array("d", [(acc[i + factor] - acc[i]) / factor for i in range(0, len(once) - factor + 1, factor)])


_PITCH_CLASS_MAPS: dict[tuple[int, float], tuple[int, list[int]]] = {}


def _pitch_class_map(n: int, rate: float) -> tuple[int, list[int]]:
    key = (n, rate)
    if key not in _PITCH_CLASS_MAPS:
        lo, hi = max(1, math.ceil(CHROMA_LOW * n / rate)), min(n // 2, int(CHROMA_HIGH * n / rate))
        _PITCH_CLASS_MAPS[key] = (lo, [round(69 + 12 * math.log2(k * rate / n / 440)) % 12 for k in range(lo, hi + 1)])
    return _PITCH_CLASS_MAPS[key]


def _chroma(x: array[Any], rate: int) -> list[float]:
    """Pitch-class power of one window: decimate to ~CHROMA_RATE, one Hann FFT over the whole window
    (resolution 1/window seconds, enough to separate semitones at 50 Hz), fold every bin's power in CHROMA_LOW..CHROMA_HIGH."""
    factor = max(1, rate // CHROMA_RATE)
    y, yrate = _lowpass_decimate(x, factor), rate / factor
    out = [0.0] * 12
    for begin in range(0, len(y) - CHROMA_MIN_SAMPLES + 1, CHROMA_MAX_SAMPLES):
        seg = y[begin : begin + CHROMA_MAX_SAMPLES]
        n = len(seg)
        size = 1 << (n - 1).bit_length()
        mean = sum(seg) / n
        w = [0.5 - 0.5 * math.cos(2 * math.pi * i / n) for i in range(n)]
        spectrum = _fft([complex((v - mean) * c) for v, c in zip(seg, w, strict=False)] + [0j] * (size - n))
        lo, classes = _pitch_class_map(size, yrate)
        for k, pc in enumerate(classes, lo):
            c = spectrum[k]
            out[pc] += c.real * c.real + c.imag * c.imag
    return out


def _rel_db(part: float, total: float) -> float | None:
    return round(10 * math.log10(part / total), 1) if part > 0 and total > 0 else None


def _chroma_report(weights: list[float], scale: set[int]) -> dict[str, Any]:
    total = sum(weights)
    if total <= 0:
        return {"inKeyShare": None, "topPitchClasses": []}
    top = sorted(range(12), key=lambda p: -weights[p])[:3]
    return {
        "inKeyShare": round(sum(weights[p] for p in scale) / total, 3),
        "topPitchClasses": [{"name": NAMES[p], "share": round(weights[p] / total, 3)} for p in top if weights[p] > 0],
    }


PEAK_COUNT = 8
PEAK_MIN_DB = -40.0  # peaks weaker than this relative to the strongest are ignored


def spectral_peaks(power: list[float], rate: int) -> list[dict[str, Any]]:
    """Strongest local maxima of a power spectrum (bins 0..N/2 of an FFT_SIZE frame), parabolic-interpolated in dB,
    with note name and cents. Resolution is rate/FFT_SIZE (about 12 Hz at 48 kHz): low notes are only approximate."""
    top = max(power[2:], default=0.0)
    if top <= 0:
        return []
    db = [10 * math.log10(v / top) if v > 0 else -200.0 for v in power]
    found = []
    for k in range(2, len(power) - 1):
        if db[k] > PEAK_MIN_DB and db[k] >= db[k - 1] and db[k] > db[k + 1]:
            a, b, c = db[k - 1], db[k], db[k + 1]
            denom = a - 2 * b + c
            shift = 0.5 * (a - c) / denom if denom else 0.0
            found.append((b - 0.25 * (a - c) * shift, (k + shift) * rate / FFT_SIZE))
    out = []
    for level, freq in sorted(found, reverse=True)[:PEAK_COUNT]:
        midi = 69 + 12 * math.log2(freq / 440)
        nearest = round(midi)
        row = {
            "hz": round(freq, 1),
            "db": round(level, 1),
            "midi": round(midi, 2),
            "note": f"{NAMES[nearest % 12]}{nearest // 12 - 1}",
            "cents": round((midi - nearest) * 100),
        }
        if (
            1200 * math.log2((freq + rate / FFT_SIZE) / freq) > SEMITONE_CENTS
        ):  # one bin is wider than a semitone here: the note name is a guess
            row["approximate"] = True
        out.append(row)
    return out


def bands(x: array[Any], rate: int, window_seconds: float, key: str | None, shift: float) -> dict[str, Any]:
    """Per-window band energy relative to the window's total power (dB), spectral centroid and RMS; optional in-key chroma."""
    nyquist = rate / 2
    edges = [
        (
            n,
            math.ceil(lo * FFT_SIZE / rate),
            min(FFT_SIZE // 2 + 1, math.ceil(hi * FFT_SIZE / rate) if hi != math.inf else FFT_SIZE // 2 + 1),
        )
        for n, lo, hi in BAND_EDGES
        if lo < nyquist
    ]
    scale: set[int] | None = None
    if key:
        tonic, mode = parse_key(key)
        scale = {(tonic + i) % 12 for i in MODES[mode]}
    window_seconds = max(window_seconds, len(x) / rate / MAX_WINDOWS)
    size = max(FFT_SIZE, int(window_seconds * rate))
    freqs = [k * rate / FFT_SIZE for k in range(FFT_SIZE // 2 + 1)]
    rows, all_chroma = [], [0.0] * 12
    all_power = [0.0] * (FFT_SIZE // 2 + 1)
    for begin in range(0, len(x), size):
        end = min(len(x), begin + size)
        if end - begin < FFT_SIZE:
            break
        hop = max(FFT_SIZE, (end - begin - FFT_SIZE) // (FRAMES_PER_WINDOW - 1))
        power, weighted = [0.0] * (FFT_SIZE // 2 + 1), 0.0
        for start in range(begin, end - FFT_SIZE + 1, hop):
            p, _mags = _frame(x, start)
            power = [a + b for a, b in zip(power, p, strict=False)]
        all_power = [a + b for a, b in zip(all_power, power, strict=False)]
        total = sum(power[1:])
        row: dict[str, Any] = {"t": round(begin / rate + shift, 3), "rms": round(_rms(x[begin:end]), 4)}
        if total > 0:
            weighted = sum(f * v for f, v in zip(freqs[1:], power[1:], strict=False))
            row["bandsDb"] = {n: _rel_db(sum(power[max(lo, 1) : hi]), total) for n, lo, hi in edges}
            row["centroidHz"] = round(weighted / total)
        else:
            row["bandsDb"], row["centroidHz"] = {n: None for n, _, _ in edges}, None
        if scale is not None:
            chroma = _chroma(x[begin:end], rate)
            all_chroma = [a + b for a, b in zip(all_chroma, chroma, strict=False)]
            row["chroma"] = _chroma_report(chroma, scale)
        rows.append(row)
    out: dict[str, Any] = {
        "windowSeconds": round(window_seconds, 3),
        "windows": rows,
        "bandEdgesHz": {n: [lo, None if hi == math.inf else hi] for n, lo, hi in BAND_EDGES if lo < nyquist},
        "peaks": spectral_peaks(all_power, rate),
        "peakResolutionHz": round(rate / FFT_SIZE, 1),
        "note": f"each window samples up to {FRAMES_PER_WINDOW} Hann frames of {FFT_SIZE} samples; bandsDb are relative to the window's total power; "
        "peaks = strongest spectral maxima of the whole analyzed range (dB re the strongest), the way to pitch steady or noisy sources",
    }
    if scale is not None:
        out["key"] = key
        out["chroma"] = {
            **_chroma_report(all_chroma, scale),
            "rangeHz": [CHROMA_LOW, CHROMA_HIGH],
            "note": "power of every FFT bin in the range folded to pitch classes (one long Hann FFT per window on audio decimated to ~4 kHz); drums, noise and inharmonic partials pollute it, so use it on pitched stems",
        }
    return out


def stereo_correlation(left: array[Any], right: array[Any] | None, skip: int) -> float | None:
    """Pearson correlation of the two channels over the whole (skipped) file; None for mono or a silent channel."""
    if right is None:
        return None
    a, b = left[skip:], right[skip:]
    n = len(a)
    if not n:
        return None
    ma, mb = sum(a) / n, sum(b) / n
    saa = sum(v * v for v in a) - n * ma * ma
    sbb = sum(v * v for v in b) - n * mb * mb
    sab = sum(map(float.__mul__, a, b)) - n * ma * mb
    return round(sab / math.sqrt(saa * sbb), 3) if saa > MIN_ENERGY and sbb > MIN_ENERGY else None


# ---- spectrogram ----------------------------------------------------------------------------------------
SPEC_COLUMNS, SPEC_ROWS, SPEC_RANGE_DB, SPEC_LOW_HZ = 800, 256, 80.0, 30.0
_PALETTE = ((0, 0, 0), (40, 0, 90), (160, 20, 120), (240, 110, 30), (255, 230, 120), (255, 255, 255))


def _color(level: float) -> bytes:
    pos = min(max(level, 0.0), 1.0) * (len(_PALETTE) - 1)
    i = min(int(pos), len(_PALETTE) - 2)
    f = pos - i
    return bytes(round(a + (b - a) * f) for a, b in zip(_PALETTE[i], _PALETTE[i + 1], strict=False))


def write_png(path: str, width: int, height: int, rgb_rows: list[bytes]) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    raw = b"".join(b"\0" + row for row in rgb_rows)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )
    try:
        with open(path, "wb") as handle:
            handle.write(png)
    except OSError as exc:
        raise WavError(f"Cannot write {path}: {exc.strerror}") from exc


def spectrogram(x: array[Any], rate: int, path: str) -> dict[str, Any]:
    """Log-frequency PNG (low frequencies at the bottom, time left to right), SPEC_RANGE_DB below the loudest cell."""
    if len(x) < FFT_SIZE:
        raise WavError("Audio is shorter than one analysis frame; no spectrogram")
    columns = min(SPEC_COLUMNS, (len(x) - FFT_SIZE) // (FFT_SIZE // 4) + 1)
    hop = (len(x) - FFT_SIZE) / (columns - 1) if columns > 1 else 0
    top_hz = min(rate / 2, 16000.0)
    ratio = (top_hz / SPEC_LOW_HZ) ** (1 / SPEC_ROWS)
    ranges = []
    for r in range(SPEC_ROWS):
        lo, hi = SPEC_LOW_HZ * ratio**r, SPEC_LOW_HZ * ratio ** (r + 1)
        a, b = int(lo * FFT_SIZE / rate), int(hi * FFT_SIZE / rate)
        ranges.append((a, max(b, a) + 1))
    cells = []
    for c in range(columns):
        _, mags = _frame(x, int(c * hop))
        cells.append([max(mags[a:b]) for a, b in ranges])
    peak = max((v for col in cells for v in col), default=0.0)
    floor = peak * 10 ** (-SPEC_RANGE_DB / 20)
    rows = [
        b"".join(
            _color(0.0 if cells[c][r] <= floor else 1 + 20 * math.log10(cells[c][r] / peak) / SPEC_RANGE_DB)
            for c in range(columns)
        )
        for r in range(SPEC_ROWS - 1, -1, -1)
    ]
    write_png(path, columns, SPEC_ROWS, rows)
    return {
        "path": path,
        "width": columns,
        "height": SPEC_ROWS,
        "fMinHz": SPEC_LOW_HZ,
        "fMaxHz": round(top_hz),
        "axis": "log frequency, low at the bottom; time left to right",
        "dynamicRangeDb": SPEC_RANGE_DB,
    }


def analyze(args: dict[str, Any]) -> dict[str, Any]:
    if args["mode"] == "loudness":
        return loudness_mode(args)
    if args["mode"] == "bands":
        return analyze_bands(args)
    rate, channels, x = read_wav(args["path"])
    skip = args.get("skipSeconds", 0.0)
    mode = args["mode"]
    if skip >= len(x) / rate:
        raise WavError("skipSeconds is beyond the end of the file")
    base = {
        "path": args["path"],
        "mode": mode,
        "sampleRate": rate,
        "channels": channels,
        "durationSeconds": round(len(x) / rate, 3),
        "analyzedChannel": 0,
    }
    body = x[int(skip * rate) :]
    shift = int(skip * rate) / rate
    if mode == "level":
        out = level(body, rate, args.get("windowSeconds", 0.5))
        for row in out["windows"]:
            row["t"] = round(row["t"] + shift, 3)
        return {**base, **out}
    if mode == "probe":
        return {**base, **probe(x, rate, skip)}
    threshold = args.get("threshold", 0.15)
    if mode == "onsets":
        times, info = find_onsets(x, rate, threshold, skip, args.get("method", "flux"))
        return {
            **base,
            **info,
            "count": len(times),
            "onsets": times[:MAX_ONSETS],
            "truncated": len(times) > MAX_ONSETS,
            "intervals": [round(b - a, 4) for a, b in pairwise(times)][:MAX_ONSETS],
        }
    return {**base, **mono_pitch(x, rate, threshold, skip, args.get("bpm"), args.get("method", "flux"))}


def loudness_mode(args: dict[str, Any]) -> dict[str, Any]:
    """BS.1770 loudness over every channel (the other modes read channel 0 only)."""
    rate, count, data = read_wav(args["path"], all_channels=True)
    skip = args.get("skipSeconds", 0.0)
    frames = len(data[0])
    if skip >= frames / rate:
        raise WavError("skipSeconds is beyond the end of the file")
    begin = int(skip * rate)
    body = [ch[begin:] for ch in data]
    try:
        measured = loudness.analyze(body, rate)
    except ValueError as exc:
        raise WavError(str(exc)) from exc
    return {
        "path": args["path"],
        "mode": "loudness",
        "sampleRate": rate,
        "channels": count,
        "durationSeconds": round(frames / rate, 3),
        "analyzedChannel": "all",
        "startSeconds": round(begin / rate, 3),
        **measured,
    }


def analyze_bands(args: dict[str, Any]) -> dict[str, Any]:
    rate, channels, data = read_wav(args["path"], all_channels=True)
    left, right = data[0], data[1] if channels > 1 else None
    skip = args.get("skipSeconds", 0.0)
    if skip >= len(left) / rate:
        raise WavError("skipSeconds is beyond the end of the file")
    start = int(skip * rate)
    body = left[start:]
    out: dict[str, Any] = {
        "path": args["path"],
        "mode": "bands",
        "sampleRate": rate,
        "channels": channels,
        "durationSeconds": round(len(left) / rate, 3),
        "analyzedChannel": 0,
    }
    out.update(bands(body, rate, args.get("windowSeconds", 8.0), args.get("key"), start / rate))
    out["stereoCorrelation"] = stereo_correlation(left, right, start)
    if args.get("spectrogramPath"):
        out["spectrogram"] = spectrogram(body, rate, args["spectrogramPath"])
    return out
