"""Audio file analysis for audio.analyze: level, onsets, monophonic pitch, 10 ms probe, spectral bands.

numpy, scipy and soundfile are the optional [audio] extra; the caller imports this module only on first use.
"""

from __future__ import annotations

import io
import math
import os
import struct
import zlib
from functools import cache
from itertools import pairwise
from statistics import fmean, median
from typing import Any

import numpy as np
import soundfile as sf
from numpy.lib.stride_tricks import sliding_window_view
from numpy.typing import ArrayLike, NDArray
from scipy.ndimage import median_filter
from scipy.signal import find_peaks, firwin, get_window, kaiserord, resample_poly

from bitwig_agent.audio import loudness

Signal = NDArray[np.float64]

MAX_BYTES = 256 * 1024 * 1024
FFT_SIZE = 4096
HOP_SECONDS = 0.005
SILENT_PEAK = 0.01  # below this the whole file is reported silent (about -40 dBFS)
CLIP_LEVEL = 0.999
MAX_ONSETS = 256
MAX_PROBE = 2000
MAX_WINDOWS = 600


MIN_SAMPLE_RATE = 8000
QUIET_RMS = 0.001
LEAP_SEMITONES = 5
STEP_SEMITONES = 2
SEMITONE_CENTS = 100
MIN_ENERGY = 1e-12
STREAMED_SIZE = b"\xff\xff\xff\xff"


class WavError(ValueError):
    pass


def _finish_streamed_header(data: bytearray) -> None:
    """pw-record/parec can leave data size 0 in a WAV header they never finalized. libsndfile reads such a file as
    empty but reads 0xFFFFFFFF as "to the end of the file", so the first is rewritten as the second."""
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return
    pos = 12
    while pos + 8 <= len(data):
        size = int.from_bytes(data[pos + 4 : pos + 8], "little")
        if data[pos : pos + 4] == b"data":
            if size == 0:
                data[pos + 4 : pos + 8] = STREAMED_SIZE
            return
        pos += 8 + size + (size & 1)


def read_wav(path: str, all_channels: bool = False) -> tuple[int, int, Any]:
    """Return (sampleRate, channels, first-channel samples as floats in -1..1), or a list of every channel's samples
    with all_channels=True. Reads every format libsndfile reads (WAV, RF64, FLAC, ...); tolerates streamed headers."""
    try:
        if os.path.getsize(path) > MAX_BYTES:
            raise WavError("Audio file exceeds 256 MiB")
        with open(path, "rb") as handle:
            data = bytearray(handle.read())
    except OSError as exc:
        raise WavError(f"Cannot read {path}: {exc.strerror}") from exc
    _finish_streamed_header(data)
    try:
        samples, rate = sf.read(io.BytesIO(data), dtype="float64", always_2d=True)
    except sf.SoundFileError as exc:
        raise WavError(f"Cannot decode {path}: {exc}") from exc
    channels = samples.shape[1]
    if rate < MIN_SAMPLE_RATE:
        raise WavError("Unsupported sample rate")
    if all_channels:
        return rate, channels, [np.ascontiguousarray(samples[:, c]) for c in range(channels)]
    return rate, channels, np.ascontiguousarray(samples[:, 0])


def _db(value: float) -> float | None:
    return round(20 * math.log10(value), 2) if value > 0 else None


def _peak(x: Signal) -> float:
    return float(np.max(np.abs(x))) if len(x) else 0.0


def _envelope(x: Signal, hop: int) -> Signal:
    """Peak of |x| over each full hop."""
    count = len(x) // hop
    return np.abs(x[: count * hop]).reshape(count, hop).max(axis=1)


def _rms(seg: Signal) -> float:
    return float(np.sqrt(np.mean(seg * seg))) if len(seg) else 0.0


def level(x: Signal, rate: int, window_seconds: float) -> dict[str, Any]:
    peak = _peak(x)
    rms = _rms(x)
    window_seconds = max(window_seconds, len(x) / rate / MAX_WINDOWS)
    size = max(1, int(window_seconds * rate))
    rows: list[dict[str, Any]] = []
    for i in range(0, len(x), size):
        seg = x[i : i + size]
        r = _rms(seg)
        rows.append({"t": round(i / rate, 3), "peak": round(_peak(seg), 4), "rms": round(r, 4), "rmsDb": _db(r)})
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


def onsets(x: Signal, rate: int, threshold: float, skip: float) -> tuple[list[float], dict[str, Any]]:
    """Rising crossings of threshold*max of a 5 ms peak envelope; times are seconds from file start."""
    hop = max(1, int(HOP_SECONDS * rate))
    first = int(skip * rate) // hop
    env = _envelope(x, hop)
    top = float(np.max(env[first:])) if len(env) > first else 0.0
    if top < SILENT_PEAK:
        return [], {"silent": True, "peak": round(top, 4)}
    limit = threshold * top
    start = max(first, 1)
    rising = np.flatnonzero((env[start:] > limit) & (env[start - 1 : -1] <= limit)) + start
    return [round(int(i) * hop / rate, 4) for i in rising], {"silent": False, "peak": round(top, 4)}


def _spectra(x: Signal, starts: Any, size: int) -> NDArray[np.complex128]:
    """rfft of the periodic-Hann-windowed frames x[s : s + size] for every start."""
    frames = sliding_window_view(x, size)[np.asarray(starts, dtype=np.intp)]
    return np.fft.rfft(frames * get_window("hann", size), axis=-1)


def _parabolic(a: float, b: float, c: float) -> float:
    """Offset (-0.5..0.5 bins) of the vertex of the parabola through three equally spaced points."""
    denom = a - 2 * b + c
    return 0.5 * (a - c) / denom if denom else 0.0


def pitch_at(x: Signal, rate: int, start: int) -> float | None:
    """Dominant spectral peak 10 ms after an onset, as fractional MIDI (parabolic interpolation); None if too short."""
    begin = start + int(0.01 * rate)
    if len(x) - begin < FFT_SIZE:
        return None
    mags = np.abs(_spectra(x, [begin], FFT_SIZE)[0, : FFT_SIZE // 2])
    low = max(2, int(30 * FFT_SIZE / rate))
    k = low + int(np.argmax(mags[low:-1]))
    if mags[k] <= 0:
        return None
    a, b, c = np.log(np.maximum(mags[k - 1 : k + 2], 1e-12))
    hz = (k + _parabolic(a, b, c)) * rate / FFT_SIZE
    return 69 + 12 * math.log2(hz / 440)


FLUX_SIZE = 512  # at FLUX_RATE: 21 ms frames
FLUX_HOP = 240  # 10 ms
FLUX_RATE = 24000
FLUX_MAX_HZ = 8000
MIN_SEPARATION = 0.04  # seconds between onsets


def _decimate(x: Signal, rate: int) -> tuple[Signal, int]:
    """Anti-aliased (polyphase FIR) decimation to about FLUX_RATE; onsets need no high band."""
    factor = max(1, round(rate / FLUX_RATE))
    if factor == 1:
        return x, rate
    return resample_poly(x, 1, factor), rate // factor


def spectral_flux(x: Signal, rate: int) -> tuple[Signal, float]:
    """Half-wave rectified log-magnitude flux per 10 ms hop, and the hop length in seconds."""
    y, yrate = _decimate(x, rate)
    hop = max(1, round(FLUX_HOP * yrate / FLUX_RATE))
    bins = min(max(2, int(FLUX_MAX_HZ * FLUX_SIZE / yrate)), FLUX_SIZE // 2)
    hop_s = hop * (rate / yrate) / rate
    if len(y) < FLUX_SIZE:
        return np.zeros(0), hop_s
    mags = np.log1p(200 * np.abs(_spectra(y, range(0, len(y) - FLUX_SIZE + 1, hop), FLUX_SIZE)[:, 1:bins]) / FLUX_SIZE)
    rise = np.maximum(np.diff(mags, axis=0), 0.0).sum(axis=1)
    return np.concatenate(([0.0], rise)), hop_s


def flux_onsets(x: Signal, rate: int, threshold: float, skip: float) -> tuple[list[float], dict[str, Any]]:
    """Peaks of the spectral flux: a new note over a long decaying tail still adds new energy per bin, which an
    amplitude envelope cannot see. A peak is the largest value within 30 ms that beats its 1 s neighbourhood median by
    threshold*max(flux) and sits MIN_SEPARATION after the previous one; its time is refined to the steepest envelope rise."""
    top_peak = _peak(x[int(skip * rate) :])
    info = {"silent": top_peak < SILENT_PEAK, "peak": round(top_peak, 4), "method": "flux"}
    if info["silent"]:
        return [], info
    flux, hop_s = spectral_flux(x, rate)
    first = max(1, int(skip / hop_s))
    top = float(np.max(flux[first:])) if len(flux) > first else 0.0
    found: list[float] = []
    if top <= 0:
        return found, info
    span = max(2, round(0.5 / hop_s))
    near = max(1, round(0.03 / hop_s))
    floor = median_filter(flux, size=2 * span + 1, mode="nearest") + threshold * top
    peaks, _ = find_peaks(flux, height=floor, distance=near + 1)
    env_hop = max(1, int(HOP_SECONDS * rate))
    for i in peaks[peaks >= first]:
        lo = int(max(0.0, i * hop_s - 0.01) * rate)
        env = _envelope(x[lo : lo + int((FLUX_SIZE / FLUX_RATE + 0.02) * rate)], env_hop)
        rise = int(np.argmax(np.diff(env))) + 1 if len(env) > 1 else 0
        t = (lo + rise * env_hop) / rate
        if t < skip or (found and t - found[-1] < MIN_SEPARATION):
            continue
        found.append(round(t, 4))
    return found, info


def find_onsets(x: Signal, rate: int, threshold: float, skip: float, method: str) -> tuple[list[float], dict[str, Any]]:
    if method == "envelope":
        times, info = onsets(x, rate, threshold, skip)
        return times, {**info, "method": "envelope"}
    return flux_onsets(x, rate, threshold, skip)


NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")


def _melody_summary(notes: list[tuple[float, float]], rounded: list[int]) -> dict[str, Any]:
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
    return summary


def _step_grid(notes: list[tuple[float, float]], bpm: float) -> dict[str, Any]:
    step = 60.0 / bpm / 4
    offset = median(t % step for t, _ in notes)
    steps = [round((t - offset) / step) for t, _ in notes]
    return {
        "stepSeconds": round(step, 5),
        "offsetSeconds": round(offset, 4),
        "positions16": [s % 16 for s in steps][:MAX_ONSETS],
        "onBeatRatio": round(sum(s % 4 == 0 for s in steps) / len(steps), 3),
    }


def mono_pitch(x: Signal, rate: int, found: tuple[list[float], dict[str, Any]], bpm: float | None) -> dict[str, Any]:
    times, info = found
    notes = []
    for t in times:
        midi = pitch_at(x, rate, int(t * rate))
        if midi is not None:
            notes.append((t, midi))
    result: dict[str, Any] = {**info, "noteCount": len(notes)}
    if not notes:
        return {**result, "notes": []}
    result["summary"] = _melody_summary(notes, [round(m) for _, m in notes])
    if bpm:
        result["grid"] = _step_grid(notes, bpm)
    result["notes"] = [
        {"t": t, "midi": round(m, 2), "name": f"{NAMES[round(m) % 12]}{round(m) // 12 - 1}"}
        for t, m in notes[:MAX_ONSETS]
    ]
    result["truncated"] = len(notes) > MAX_ONSETS
    return result


def probe(x: Signal, rate: int, skip: float) -> dict[str, Any]:
    """Signed means over 10 ms windows: the file as a scope trace of a logic/CV signal."""
    size = max(1, rate // 100)
    start = int(skip * rate)
    count = (len(x) - start) // size
    means = x[start : start + count * size].reshape(count, size).mean(axis=1)
    return {
        "windowSeconds": 0.01,
        "startSeconds": round(start / rate, 3),
        "count": count,
        "truncated": count > MAX_PROBE,
        "min": round(float(np.min(means)), 4) if count else None,
        "max": round(float(np.max(means)), 4) if count else None,
        "means": [round(float(v), 4) for v in means[:MAX_PROBE]],
    }


# ---- bands ----------------------------------------------------------------------------------------------
BAND_EDGES = (("sub", 0, 60), ("low", 60, 250), ("mid", 250, 2000), ("high", 2000, 8000), ("air", 8000, math.inf))
FRAMES_PER_WINDOW = 16  # frames sampled evenly across a window (FFT_SIZE samples each), not every sample
CHROMA_LOW, CHROMA_HIGH = 50.0, 1500.0
CHROMA_RATE = 4000  # chroma works on audio decimated to about this rate
CHROMA_PASS_HZ, CHROMA_STOP_HZ = 1600.0, 2000.0  # anti-alias low-pass band edges ahead of that decimation
CHROMA_STOP_DB = 80.0
CHROMA_FLOOR = 1e-6  # in-range power 60 dB below the window's mean square counts as no pitched content
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


def _frame_power(x: Signal, starts: Any) -> Signal:
    """Power of bins 0..N/2 of Hann-windowed FFT_SIZE frames, one row per start."""
    spectrum = _spectra(x, starts, FFT_SIZE)
    return spectrum.real**2 + spectrum.imag**2


@cache
def _chroma_lowpass(rate: int) -> Signal:
    """Kaiser FIR passing CHROMA_PASS_HZ and CHROMA_STOP_DB down from CHROMA_STOP_HZ (below the decimated Nyquist)."""
    numtaps, beta = kaiserord(CHROMA_STOP_DB, (CHROMA_STOP_HZ - CHROMA_PASS_HZ) / (rate / 2))
    taps: Signal = firwin(numtaps | 1, (CHROMA_PASS_HZ + CHROMA_STOP_HZ) / 2, window=("kaiser", beta), fs=rate)
    return taps


@cache
def _pitch_class_map(n: int, rate: float) -> tuple[int, NDArray[np.intp]]:
    lo, hi = max(1, math.ceil(CHROMA_LOW * n / rate)), min(n // 2, int(CHROMA_HIGH * n / rate))
    bins = np.arange(lo, hi + 1)
    return lo, np.rint(69 + 12 * np.log2(bins * rate / n / 440)).astype(np.intp) % 12


def _chroma(x: Signal, rate: int) -> Signal:
    """Pitch-class power of one window: anti-alias and decimate to ~CHROMA_RATE, one Hann FFT over the whole window
    (resolution 1/window seconds, enough to separate semitones at 50 Hz), fold every bin's power in CHROMA_LOW..CHROMA_HIGH.
    All zero when that power is below CHROMA_FLOOR of the window's mean square (no pitched content in range)."""
    factor = max(1, rate // CHROMA_RATE)
    y = resample_poly(x, 1, factor, window=_chroma_lowpass(rate)) if factor > 1 else x
    yrate = rate / factor
    out = np.zeros(12)
    in_range = 0.0
    for begin in range(0, len(y) - CHROMA_MIN_SAMPLES + 1, CHROMA_MAX_SAMPLES):
        seg = y[begin : begin + CHROMA_MAX_SAMPLES]
        n = len(seg)
        size = 1 << (n - 1).bit_length()
        w = get_window("hann", n)
        spectrum = np.fft.rfft((seg - seg.mean()) * w, size)
        lo, classes = _pitch_class_map(size, yrate)
        power = np.abs(spectrum[lo : lo + len(classes)]) ** 2
        out += np.bincount(classes, weights=power, minlength=12)
        in_range += 2 * power.sum() / (size * float(np.sum(w * w)))  # Parseval: one-sided bin power -> mean square
    if in_range < CHROMA_FLOOR * float(np.mean(x * x)):
        out[:] = 0.0
    return out


def _rel_db(part: float, total: float) -> float | None:
    return round(10 * math.log10(part / total), 1) if part > 0 and total > 0 else None


def _chroma_report(weights: Signal, scale: set[int]) -> dict[str, Any]:
    total = float(weights.sum())
    if total <= 0:
        return {"inKeyShare": None, "topPitchClasses": []}
    top = sorted(range(12), key=lambda p: -weights[p])[:3]
    return {
        "inKeyShare": round(float(sum(weights[p] for p in scale)) / total, 3),
        "topPitchClasses": [
            {"name": NAMES[p], "share": round(float(weights[p]) / total, 3)} for p in top if weights[p] > 0
        ],
    }


PEAK_COUNT = 8
PEAK_MIN_DB = -40.0  # peaks weaker than this relative to the strongest are ignored
FIRST_PEAK_BIN = 2  # bins 0 and 1 hold DC and its leakage


def spectral_peaks(power: ArrayLike, rate: int) -> list[dict[str, Any]]:
    """Strongest local maxima of a power spectrum (bins 0..N/2 of an FFT_SIZE frame), parabolic-interpolated in dB,
    with note name and cents. Resolution is rate/FFT_SIZE (about 12 Hz at 48 kHz): low notes are only approximate."""
    power = np.asarray(power, dtype=np.float64)
    top = float(np.max(power[FIRST_PEAK_BIN:])) if len(power) > FIRST_PEAK_BIN else 0.0
    if top <= 0:
        return []
    db = np.where(power > 0, 10 * np.log10(np.maximum(power, np.finfo(np.float64).tiny) / top), -200.0)
    peaks, _ = find_peaks(db, height=PEAK_MIN_DB)
    found = []
    for k in peaks[peaks >= FIRST_PEAK_BIN]:
        a, b, c = db[k - 1 : k + 2]
        shift = _parabolic(a, b, c)
        found.append((float(b - 0.25 * (a - c) * shift), float((k + shift) * rate / FFT_SIZE)))
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


def bands(x: Signal, rate: int, window_seconds: float, key: str | None, shift: float) -> dict[str, Any]:
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
    freqs = np.arange(FFT_SIZE // 2 + 1) * rate / FFT_SIZE
    rows, all_chroma = [], np.zeros(12)
    all_power = np.zeros(FFT_SIZE // 2 + 1)
    for begin in range(0, len(x), size):
        end = min(len(x), begin + size)
        if end - begin < FFT_SIZE:
            break
        hop = max(FFT_SIZE, (end - begin - FFT_SIZE) // (FRAMES_PER_WINDOW - 1))
        power = _frame_power(x, range(begin, end - FFT_SIZE + 1, hop)).sum(axis=0)
        all_power += power
        total = float(power[1:].sum())
        row: dict[str, Any] = {"t": round(begin / rate + shift, 3), "rms": round(_rms(x[begin:end]), 4)}
        if total > 0:
            row["bandsDb"] = {n: _rel_db(float(power[max(lo, 1) : hi].sum()), total) for n, lo, hi in edges}
            row["centroidHz"] = round(float(freqs[1:] @ power[1:]) / total)
        else:
            row["bandsDb"], row["centroidHz"] = {n: None for n, _, _ in edges}, None
        if scale is not None:
            chroma = _chroma(x[begin:end], rate)
            all_chroma += chroma
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
            "note": "power of every FFT bin in the range folded to pitch classes (one long Hann FFT per window on audio low-passed and decimated to ~4 kHz); "
            "empty when that power is 60 dB or more below the window's; drums, noise and inharmonic partials pollute it, so use it on pitched stems",
        }
    return out


def stereo_correlation(left: Signal, right: Signal | None, skip: int) -> float | None:
    """Pearson correlation of the two channels over the whole (skipped) file; None for mono or a silent channel."""
    if right is None or len(left) <= skip:
        return None
    a, b = left[skip:] - left[skip:].mean(), right[skip:] - right[skip:].mean()
    saa, sbb = float(a @ a), float(b @ b)
    return round(float(a @ b) / math.sqrt(saa * sbb), 3) if saa > MIN_ENERGY and sbb > MIN_ENERGY else None


# ---- spectrogram ----------------------------------------------------------------------------------------
SPEC_COLUMNS, SPEC_ROWS, SPEC_RANGE_DB, SPEC_LOW_HZ = 800, 256, 80.0, 30.0
_PALETTE = np.array(((0, 0, 0), (40, 0, 90), (160, 20, 120), (240, 110, 30), (255, 230, 120), (255, 255, 255)), float)


def _colors(levels: Signal) -> NDArray[np.uint8]:
    """RGB of each level in 0..1 (clipped), linear between the palette stops."""
    stops = np.linspace(0.0, 1.0, len(_PALETTE))
    clipped = np.clip(levels, 0.0, 1.0)
    rgb = [np.interp(clipped, stops, _PALETTE[:, channel]) for channel in range(3)]
    return np.rint(np.stack(rgb, axis=-1)).astype(np.uint8)


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


def spectrogram(x: Signal, rate: int, path: str) -> dict[str, Any]:
    """Log-frequency PNG (low frequencies at the bottom, time left to right), SPEC_RANGE_DB below the loudest cell."""
    if len(x) < FFT_SIZE:
        raise WavError("Audio is shorter than one analysis frame; no spectrogram")
    columns = min(SPEC_COLUMNS, (len(x) - FFT_SIZE) // (FFT_SIZE // 4) + 1)
    hop = (len(x) - FFT_SIZE) / (columns - 1) if columns > 1 else 0
    top_hz = min(rate / 2, 16000.0)
    ratio = (top_hz / SPEC_LOW_HZ) ** (1 / SPEC_ROWS)
    mags = np.sqrt(_frame_power(x, [int(c * hop) for c in range(columns)]))
    cells = np.empty((SPEC_ROWS, columns))
    for r in range(SPEC_ROWS):
        lo, hi = SPEC_LOW_HZ * ratio**r, SPEC_LOW_HZ * ratio ** (r + 1)
        a, b = int(lo * FFT_SIZE / rate), int(hi * FFT_SIZE / rate)
        cells[r] = mags[:, a : max(b, a) + 1].max(axis=1)
    peak = float(np.max(cells))
    floor = peak * 10 ** (-SPEC_RANGE_DB / 20)
    levels = np.zeros_like(cells)
    loud = cells > floor
    levels[loud] = 1 + 20 * np.log10(cells[loud] / peak) / SPEC_RANGE_DB
    rgb = _colors(levels[::-1])
    write_png(path, columns, SPEC_ROWS, [row.tobytes() for row in rgb])
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
        seconds = max(_window_seconds(args, 0.5), len(body) / rate / MAX_WINDOWS)
        out = level(body, rate, seconds)
        for row in out["windows"]:
            row["t"] = round(row["t"] + shift, 3)
        if "reference" in args:
            _compare(out, args["reference"], skip, len(body) / rate, seconds)
        if "windowBeats" in args:
            _beat_windows(out, args["bpm"])
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
    return {
        **base,
        **mono_pitch(x, rate, find_onsets(x, rate, threshold, skip, args.get("method", "flux")), args.get("bpm")),
    }


def _window_seconds(args: dict[str, Any], default: float) -> float:
    if "windowBeats" in args:
        return float(args["windowBeats"] * 60 / args["bpm"])
    return float(args.get("windowSeconds", default))


def _beat_windows(out: dict[str, Any], bpm: float) -> None:
    out["windowBeats"] = round(out["windowSeconds"] * bpm / 60, 3)
    for row in out["windows"]:
        row["beat"] = round(row["t"] * bpm / 60, 3)


def _compare(out: dict[str, Any], path: str, skip: float, seconds: float, window_seconds: float) -> None:
    """Per-window RMS difference against the same span of a reference WAV, in dB."""
    rate, _, ref = read_wav(path)
    begin = int(skip * rate)
    other = level(ref[begin : begin + int(seconds * rate)], rate, window_seconds)["windows"]
    deltas = []
    for i, row in enumerate(out["windows"]):
        row["referenceRmsDb"] = other[i]["rmsDb"] if i < len(other) else None
        both = row["rmsDb"] is not None and row["referenceRmsDb"] is not None
        row["deltaDb"] = round(row["rmsDb"] - row["referenceRmsDb"], 2) if both else None
        if both:
            deltas.append(row["deltaDb"])
    out["reference"] = path
    out["summary"] = (
        {"meanDeltaDb": round(fmean(deltas), 2), "minDeltaDb": min(deltas), "maxDeltaDb": max(deltas)}
        if deltas
        else None
    )


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
    out.update(bands(body, rate, _window_seconds(args, 8.0), args.get("key"), start / rate))
    if "windowBeats" in args:
        _beat_windows(out, args["bpm"])
    out["stereoCorrelation"] = stereo_correlation(left, right, start)
    if args.get("spectrogramPath"):
        out["spectrogram"] = spectrogram(body, rate, args["spectrogramPath"])
    return out
