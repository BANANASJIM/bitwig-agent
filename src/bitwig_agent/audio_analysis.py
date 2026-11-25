"""Stdlib-only WAV analysis for audio.analyze: level, onsets, monophonic pitch, 10 ms probe."""

from __future__ import annotations

import cmath
import math
import struct
import sys
from array import array
from statistics import median
from typing import Any

MAX_BYTES = 256 * 1024 * 1024
FFT_SIZE = 4096
HOP_SECONDS = 0.005
SILENT_PEAK = 0.01          # below this the whole file is reported silent (about -40 dBFS)
CLIP_LEVEL = 0.999
MAX_ONSETS = 256
MAX_PROBE = 2000
MAX_WINDOWS = 600


class WavError(ValueError):
    pass


def read_wav(path: str) -> tuple[int, int, array]:
    """Return (sampleRate, channels, first-channel samples as floats in -1..1). Tolerates streamed headers."""
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
        tag, size = data[pos:pos + 4], struct.unpack_from("<I", data, pos + 4)[0]
        body = pos + 8
        if tag == b"fmt ":
            fmt = struct.unpack_from("<HHIIHH", data, body)
            if fmt[0] == 0xFFFE and size >= 26:  # WAVE_FORMAT_EXTENSIBLE: the sub-format GUID starts with the real tag
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
    if channels < 1 or rate < 8000:
        raise WavError("Unsupported channel count or sample rate")
    width = bits // 8
    frame = width * channels
    pcm = pcm[:len(pcm) // frame * frame]
    if tag == 1 and bits == 16:
        samples = array("h", pcm)
        scale = 1 / 32768
    elif tag == 1 and bits == 32:
        samples = array("i", pcm)
        scale = 1 / 2147483648
    elif tag == 1 and bits == 24:
        samples = array("i", (int.from_bytes(pcm[i:i + 3], "little", signed=True) for i in range(0, len(pcm), 3)))
        scale = 1 / 8388608
    elif tag == 3 and bits == 32:
        samples, scale = array("f", pcm), 1.0
    elif tag == 3 and bits == 64:
        samples, scale = array("d", pcm), 1.0
    else:
        raise WavError(f"Unsupported WAV encoding (format {tag}, {bits}-bit); use 16/24/32-bit PCM or float")
    if tag == 1 and bits in (16, 32) and sys.byteorder == "big":
        samples.byteswap()
    channel = samples[::channels]
    return rate, channels, array("d", (v * scale for v in channel))


def _db(value: float) -> float | None:
    return round(20 * math.log10(value), 2) if value > 0 else None


def _envelope(x: array, hop: int) -> list[float]:
    return [max(map(abs, x[i:i + hop])) for i in range(0, len(x) - hop + 1, hop)]


def _rms(seg: array) -> float:
    return math.sqrt(sum(v * v for v in seg) / len(seg)) if len(seg) else 0.0


def level(x: array, rate: int, window_seconds: float) -> dict[str, Any]:
    peak = max(map(abs, x), default=0.0)
    rms = _rms(x)
    window_seconds = max(window_seconds, len(x) / rate / MAX_WINDOWS)
    size = max(1, int(window_seconds * rate))
    rows = []
    for i in range(0, len(x), size):
        seg = x[i:i + size]
        rows.append({"t": round(i / rate, 3), "peak": round(max(map(abs, seg)), 4), "rms": round(_rms(seg), 4)})
    quiet = sum(1 for r in rows if r["rms"] < 0.001)
    return {"peak": round(peak, 4), "peakDb": _db(peak), "rms": round(rms, 4), "rmsDb": _db(rms),
            "clipping": peak >= CLIP_LEVEL, "windowSeconds": round(window_seconds, 3),
            "silenceRatio": round(quiet / len(rows), 3) if rows else 1.0, "windows": rows}


def onsets(x: array, rate: int, threshold: float, skip: float) -> tuple[list[float], dict[str, Any]]:
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


def pitch_at(x: array, rate: int, start: int) -> float | None:
    """Dominant spectral peak 10 ms after an onset, as fractional MIDI (parabolic interpolation); None if too short."""
    begin = start + int(0.01 * rate)
    seg = x[begin:begin + FFT_SIZE]
    if len(seg) < FFT_SIZE:
        return None
    spectrum = _fft([complex(v * w) for v, w in zip(seg, _WINDOW)])
    low = max(2, int(30 * FFT_SIZE / rate))
    mags = [abs(c) for c in spectrum[:FFT_SIZE // 2]]
    k = max(range(low, len(mags) - 1), key=mags.__getitem__)
    if mags[k] <= 0:
        return None
    a, b, c = (math.log(max(mags[i], 1e-12)) for i in (k - 1, k, k + 1))
    denom = a - 2 * b + c
    delta = 0.5 * (a - c) / denom if denom else 0.0
    hz = (k + delta) * rate / FFT_SIZE
    return 69 + 12 * math.log2(hz / 440)


FLUX_SIZE = 512             # at FLUX_RATE: 21 ms frames
FLUX_HOP = 240              # 10 ms
FLUX_RATE = 24000
FLUX_MAX_HZ = 8000
MIN_SEPARATION = 0.04       # seconds between onsets
_FLUX_WINDOW = [0.5 - 0.5 * math.cos(2 * math.pi * i / FLUX_SIZE) for i in range(FLUX_SIZE)]


def _decimate(x: array, rate: int) -> tuple[array, int]:
    """Average-and-drop to about FLUX_RATE (crude low-pass; onsets need no high band)."""
    factor = max(1, round(rate / FLUX_RATE))
    if factor == 1:
        return x, rate
    return array("d", (sum(x[i:i + factor]) / factor for i in range(0, len(x) - factor + 1, factor))), rate // factor


def spectral_flux(x: array, rate: int) -> tuple[list[float], float]:
    """Half-wave rectified log-magnitude flux per 10 ms hop, and the hop length in seconds."""
    y, yrate = _decimate(x, rate)
    hop = max(1, round(FLUX_HOP * yrate / FLUX_RATE))
    bins = min(max(2, int(FLUX_MAX_HZ * FLUX_SIZE / yrate)), FLUX_SIZE // 2)
    flux, previous = [], None
    for start in range(0, len(y) - FLUX_SIZE + 1, hop):
        spectrum = _fft([complex(v * w) for v, w in zip(y[start:start + FLUX_SIZE], _FLUX_WINDOW)])
        mags = [math.log1p(200 * abs(c) / FLUX_SIZE) for c in spectrum[1:bins]]
        flux.append(0.0 if previous is None else sum(max(0.0, a - b) for a, b in zip(mags, previous)))
        previous = mags
    return flux, hop * (rate / yrate) / rate


def flux_onsets(x: array, rate: int, threshold: float, skip: float) -> tuple[list[float], dict[str, Any]]:
    """Peaks of the spectral flux: a new note over a long decaying tail still adds new energy per bin, which an
    amplitude envelope cannot see. A peak is a local maximum that beats its neighbourhood median by
    threshold*max(flux) and sits MIN_SEPARATION after the previous one; its time is refined to the steepest envelope rise."""
    top_peak = max(map(abs, x[int(skip * rate):]), default=0.0)
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
            if v <= 0 or any(flux[j] > v or (flux[j] == v and j < i) for j in range(max(0, i - near), min(len(flux), i + near + 1)) if j != i):
                continue
            window = sorted(flux[max(0, i - span):i + span + 1])
            if v - window[len(window) // 2] < threshold * top:
                continue
            lo = int(max(0.0, i * hop_s - 0.01) * rate)
            env = _envelope(x[lo:lo + int((FLUX_SIZE / FLUX_RATE + 0.02) * rate)], env_hop)
            rise = max(range(1, len(env)), key=lambda k: env[k] - env[k - 1], default=0)
            t = (lo + rise * env_hop) / rate
            if t < skip or (found and t - found[-1] < MIN_SEPARATION):
                continue
            found.append(round(t, 4))
    return found, info


def find_onsets(x: array, rate: int, threshold: float, skip: float, method: str) -> tuple[list[float], dict[str, Any]]:
    if method == "envelope":
        times, info = onsets(x, rate, threshold, skip)
        return times, {**info, "method": "envelope"}
    return flux_onsets(x, rate, threshold, skip)


NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")


def mono_pitch(x: array, rate: int, threshold: float, skip: float, bpm: float | None, method: str = "flux") -> dict[str, Any]:
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
    ivs = [b - a for a, b in zip(rounded, rounded[1:])]
    leaps = [i for i, v in enumerate(ivs) if abs(v) >= 5]
    recovered = [i for i in leaps if i + 1 < len(ivs) and ivs[i + 1] * ivs[i] < 0 and abs(ivs[i + 1]) <= 2]
    pcs = sorted({m % 12 for m in rounded})
    summary: dict[str, Any] = {
        "pitchClasses": pcs, "pitchClassNames": [NAMES[p] for p in pcs],
        "range": {"min": min(rounded), "max": max(rounded), "semitones": max(rounded) - min(rounded)},
        "tuneError": round(sum(abs(m - r) for (_, m), r in zip(notes, rounded)) / len(notes), 3)}
    if ivs:
        summary.update(
            intervals=ivs[:MAX_ONSETS], stepwiseRatio=round(sum(abs(v) <= 2 for v in ivs) / len(ivs), 3),
            repeatRatio=round(sum(v == 0 for v in ivs) / len(ivs), 3), leaps=len(leaps),
            leapRecoveryRatio=round(len(recovered) / len(leaps), 3) if leaps else 1.0)
    result["summary"] = summary
    if bpm:
        step = 60.0 / bpm / 4
        offset = median(t % step for t, _ in notes)
        steps = [round((t - offset) / step) for t, _ in notes]
        result["grid"] = {"stepSeconds": round(step, 5), "offsetSeconds": round(offset, 4),
                          "positions16": [s % 16 for s in steps][:MAX_ONSETS],
                          "onBeatRatio": round(sum(s % 4 == 0 for s in steps) / len(steps), 3)}
    result["notes"] = [{"t": t, "midi": round(m, 2), "name": f"{NAMES[round(m) % 12]}{round(m) // 12 - 1}"}
                       for t, m in notes[:MAX_ONSETS]]
    result["truncated"] = len(notes) > MAX_ONSETS
    return result


def probe(x: array, rate: int, skip: float) -> dict[str, Any]:
    """Signed means over 10 ms windows: the file as a scope trace of a logic/CV signal."""
    size = max(1, rate // 100)
    start = int(skip * rate)
    means = [sum(x[i:i + size]) / size for i in range(start, len(x) - size + 1, size)]
    shown = means[:MAX_PROBE]
    return {"windowSeconds": 0.01, "startSeconds": round(start / rate, 3), "count": len(means),
            "truncated": len(means) > MAX_PROBE, "min": round(min(means), 4) if means else None,
            "max": round(max(means), 4) if means else None, "means": [round(v, 4) for v in shown]}


def analyze(args: dict[str, Any]) -> dict[str, Any]:
    rate, channels, x = read_wav(args["path"])
    skip = args.get("skipSeconds", 0.0)
    mode = args["mode"]
    if skip >= len(x) / rate:
        raise WavError("skipSeconds is beyond the end of the file")
    base = {"path": args["path"], "mode": mode, "sampleRate": rate, "channels": channels,
            "durationSeconds": round(len(x) / rate, 3), "analyzedChannel": 0}
    body = x[int(skip * rate):]
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
        return {**base, **info, "count": len(times), "onsets": times[:MAX_ONSETS], "truncated": len(times) > MAX_ONSETS,
                "intervals": [round(b - a, 4) for a, b in zip(times, times[1:])][:MAX_ONSETS]}
    return {**base, **mono_pitch(x, rate, threshold, skip, args.get("bpm"), args.get("method", "flux"))}
