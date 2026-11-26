"""ITU-R BS.1770-4 / EBU R128 loudness of decoded WAV channels, stdlib only.

Integrated loudness is the standard's: K-weighting (shelf + RLB high-pass), 400 ms blocks with 75 % overlap, per-channel
energy sum with channel weights, -70 LUFS absolute gate, then a -10 LU relative gate. Short-term (3 s) and momentary (400 ms)
maxima and the EBU 3342 loudness range use the same blocks. True peak is an estimate (see true_peak).
"""

from __future__ import annotations

import math
from array import array
from collections.abc import Sequence
from typing import Any

OFFSET = -0.691  # BS.1770 constant: K-weighted energy -> LKFS
ABS_GATE = -70.0
REL_GATE_LU = -10.0
LRA_REL_GATE_LU = -20.0  # EBU TECH 3342
SUB_BLOCK_SECONDS = 0.1  # 400 ms block = 4 sub-blocks (75 % overlap), 3 s window = 30
MOMENTARY_SUB, SHORT_TERM_SUB = 4, 30
TP_CANDIDATE_FRACTION = 0.5  # only intervals touching a sample >= -6 dB below the sample peak can hold the true peak
TP_MAX_INTERVALS = 150_000
TP_HALF_TAPS = 12  # 24 taps per phase
MAX_SECONDS = 1800


STEREO_CHANNELS = 2
SURROUND_5_1_CHANNELS = 6
MIN_PERCENTILE_POINTS = 2


def _shelf(rate: int) -> tuple[tuple[float, float, float], tuple[float, float]]:
    """BS.1770 stage 1 (head-related high shelf), bilinear transform of the analog prototype; exact at 48 kHz."""
    gain, q, fc = 3.999843853973347, 0.7071752369554196, 1681.9744509555319
    k = math.tan(math.pi * fc / rate)
    vh = 10 ** (gain / 20)
    vb = vh**0.4996667741545416
    a0 = 1 + k / q + k * k
    return ((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0), (
        2 * (k * k - 1) / a0,
        (1 - k / q + k * k) / a0,
    )


def _rlb(rate: int) -> tuple[tuple[float, float, float], tuple[float, float]]:
    """BS.1770 stage 2 (revised low-frequency B-curve high-pass)."""
    q, fc = 0.5003270373238773, 38.13547087602444
    k = math.tan(math.pi * fc / rate)
    a0 = 1 + k / q + k * k
    return (1.0, -2.0, 1.0), (2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)


def coefficients(rate: int) -> dict[str, tuple[float, ...]]:
    (b, a), (c, d) = _shelf(rate), _rlb(rate)
    return {"shelfB": b, "shelfA": a, "rlbB": c, "rlbA": d}


def gain_db(rate: int, hz: float) -> float:
    """Magnitude of the cascaded K-weighting filter at hz (analytic, for checks and documentation)."""
    z = complex(math.cos(2 * math.pi * hz / rate), -math.sin(2 * math.pi * hz / rate))  # z^-1
    total = 1.0
    for b, a in (_shelf(rate), _rlb(rate)):
        num = b[0] + b[1] * z + b[2] * z * z
        den = 1 + a[0] * z + a[1] * z * z
        total *= abs(num / den)
    return 20 * math.log10(total)


def sub_block_energies(x: Sequence[float], rate: int) -> list[float]:
    """Mean square of the K-weighted signal per 100 ms sub-block (full sub-blocks only)."""
    (b0, b1, b2), (a1, a2) = _shelf(rate)
    (c0, c1, c2), (d1, d2) = _rlb(rate)
    size = max(1, round(SUB_BLOCK_SECONDS * rate))
    s1 = s2 = t1 = t2 = 0.0
    out = []
    for start in range(0, len(x) - size + 1, size):
        total = 0.0
        for v in x[start : start + size]:
            y = b0 * v + s1  # transposed direct form II
            s1 = b1 * v - a1 * y + s2
            s2 = b2 * v - a2 * y
            w = c0 * y + t1
            t1 = c1 * y - d1 * w + t2
            t2 = c2 * y - d2 * w
            total += w * w
        out.append(total / size)
    return out


def channel_weights(channels: int) -> tuple[list[float], str]:
    if channels == 1:
        return [1.0], "mono"
    if channels == STEREO_CHANNELS:
        return [1.0, 1.0], "stereo"
    if channels == SURROUND_5_1_CHANNELS:
        return [1.0, 1.0, 1.0, 0.0, 1.41, 1.41], "5.1_wav_order_L_R_C_LFE_Ls_Rs"
    return [1.0] * channels, "assumed_all_front_weight_1"


def _lufs(energy: float) -> float:
    return OFFSET + 10 * math.log10(energy) if energy > 0 else -math.inf


def _windows(per_sub: list[float], span: int) -> list[float]:
    """Weighted energy of every span-sub-block window, hop one sub-block."""
    if len(per_sub) < span:
        return []
    run = sum(per_sub[:span])
    out = [run / span]
    for i in range(span, len(per_sub)):
        run += per_sub[i] - per_sub[i - span]
        out.append(max(run, 0.0) / span)
    return out


def _percentile(sorted_values: list[float], q: float) -> float:
    pos = q * (len(sorted_values) - 1)
    lo = math.floor(pos)
    hi = min(lo + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo)


def integrated(block_energies: list[float]) -> tuple[float | None, dict[str, Any]]:
    loud = [_lufs(e) for e in block_energies]
    kept = [e for e, level in zip(block_energies, loud, strict=True) if level > ABS_GATE]
    info: dict[str, Any] = {
        "blocks": len(block_energies),
        "absoluteGated": len(kept),
        "relativeGated": 0,
        "relativeThresholdLufs": None,
    }
    if not kept:
        return None, info
    threshold = _lufs(sum(kept) / len(kept)) + REL_GATE_LU
    final = [e for e, level in zip(block_energies, loud, strict=True) if level > ABS_GATE and level > threshold]
    info.update(relativeGated=len(final), relativeThresholdLufs=round(threshold, 2))
    return _lufs(sum(final) / len(final)), info


def loudness_range(short_term_energies: list[float]) -> float | None:
    loud = [_lufs(e) for e in short_term_energies]
    kept = [(e, level) for e, level in zip(short_term_energies, loud, strict=True) if level > ABS_GATE]
    if not kept:
        return None
    threshold = _lufs(sum(e for e, _ in kept) / len(kept)) + LRA_REL_GATE_LU
    final = sorted(level for _, level in kept if level > threshold)
    if len(final) < MIN_PERCENTILE_POINTS:
        return None
    return _percentile(final, 0.95) - _percentile(final, 0.10)


def _interpolator() -> list[list[float]]:
    """Phases 1..3 of a 4x windowed-sinc interpolator (Blackman-Harris, 2*TP_HALF_TAPS taps, unity DC gain per phase)."""
    k = TP_HALF_TAPS
    phases = []
    for p in (1, 2, 3):
        frac = p / 4
        taps = []
        for n in range(-k + 1, k + 1):  # sample i+n, distance u = n - frac from the interpolation point
            u = n - frac
            sinc = math.sin(math.pi * u) / (math.pi * u)
            r = u / k
            w = (
                0.35875
                + 0.48829 * math.cos(math.pi * r)
                + 0.14128 * math.cos(2 * math.pi * r)
                + 0.01168 * math.cos(3 * math.pi * r)
            )
            taps.append(sinc * w)
        gain = sum(taps)
        phases.append([t / gain for t in taps])
    return phases


_PHASES = _interpolator()


def true_peak(x: Sequence[float]) -> tuple[float, float]:
    """(samplePeak, truePeakEstimate), both linear. 4x oversampling by windowed sinc, evaluated only on intervals that
    touch a sample within 6 dB of the sample peak (at most TP_MAX_INTERVALS, the loudest first). An estimate of the
    BS.1770-4 Annex 2 meter, not a certified one."""
    n = len(x)
    peak = max(map(abs, x), default=0.0)
    if peak == 0 or n < 2 * TP_HALF_TAPS:
        return peak, peak
    floor = TP_CANDIDATE_FRACTION * peak
    candidates = [i for i in range(n - 1) if abs(x[i]) >= floor or abs(x[i + 1]) >= floor]
    if len(candidates) > TP_MAX_INTERVALS:
        candidates.sort(key=lambda i: -max(abs(x[i]), abs(x[i + 1])))
        candidates = candidates[:TP_MAX_INTERVALS]
    best = peak
    lo_pad, hi_pad = TP_HALF_TAPS - 1, TP_HALF_TAPS
    for i in candidates:
        if i - lo_pad < 0 or i + hi_pad >= n:
            continue
        seg = x[i - lo_pad : i + hi_pad + 1]
        for taps in _PHASES:
            v = abs(sum(t * s for t, s in zip(taps, seg, strict=False)))
            best = max(best, v)
    return peak, best


def _db(value: float) -> float | None:
    return round(20 * math.log10(value), 2) if value > 0 else None


def analyze(channels: list[array[Any]], rate: int) -> dict[str, Any]:
    if not channels or not len(channels[0]):
        raise ValueError("no samples to measure")
    seconds = len(channels[0]) / rate
    if seconds > MAX_SECONDS:
        raise ValueError(
            f"loudness is limited to {MAX_SECONDS} s of audio per call (this file has {seconds:.0f} s); use skipSeconds or a shorter capture"
        )
    weights, layout = channel_weights(len(channels))
    per_channel = [sub_block_energies(ch, rate) if w else [] for ch, w in zip(channels, weights, strict=False)]
    count = min((len(e) for e, w in zip(per_channel, weights, strict=False) if w), default=0)
    weighted = [sum(w * e[i] for e, w in zip(per_channel, weights, strict=False) if w) for i in range(count)]
    momentary = _windows(weighted, MOMENTARY_SUB)
    short = _windows(weighted, SHORT_TERM_SUB)
    value, gating = integrated(momentary)
    lra = loudness_range(short)
    peaks = [true_peak(ch) for ch in channels]
    sample_peak = max(p for p, _ in peaks)
    tp = max(t for _, t in peaks)
    return {
        "integratedLufs": None if value is None else round(value, 2),
        "momentaryMaxLufs": round(max(_lufs(e) for e in momentary), 2) if momentary else None,
        "shortTermMaxLufs": round(max(_lufs(e) for e in short), 2) if short else None,
        "loudnessRangeLu": None if lra is None else round(lra, 2),
        "truePeakDbtp": _db(tp),
        "samplePeakDbfs": _db(sample_peak),
        "perChannelTruePeakDbtp": [_db(t) for _, t in peaks],
        "gating": gating,
        "channelWeights": weights,
        "channelLayout": layout,
        "limits": [
            "BS.1770-4 K-weighting and gating; 48 kHz coefficients are the standard's, other rates are bilinear-transform derived.",
            "Mono counts as one front channel (about 3 LU below the same signal played on two speakers). Channel order is assumed to be WAV order; 6 channels use 5.1 weights, other counts weight every channel 1.",
            "integratedLufs is null when no 400 ms block passes the -70 LUFS gate; shortTermMaxLufs and loudnessRangeLu need at least 3 s.",
            "truePeakDbtp is a 4x windowed-sinc estimate over intervals near the sample peak, not a certified BS.1770 true-peak meter; expect about +-0.1 dB.",
            f"Pure Python: about 2 s of CPU per minute of stereo 48 kHz audio (measured on one desktop CPU); at most {MAX_SECONDS} s per call.",
            "The whole file counts (use skipSeconds to drop a warm-up); this is a file measurement, not Bitwig's own meter.",
        ],
    }
