"""ITU-R BS.1770-4 / EBU R128 loudness of decoded audio channels (numpy/scipy: the optional [audio] extra).

Integrated loudness is the standard's: K-weighting (shelf + RLB high-pass), 400 ms blocks with 75 % overlap, per-channel
energy sum with channel weights, -70 LUFS absolute gate, then a -10 LU relative gate. Short-term (3 s) and momentary (400 ms)
maxima and the EBU 3342 loudness range use the same blocks. True peak is an estimate (see true_peak). The filtering and
oversampling are scipy's; the gating arithmetic below follows the standard step by step.
"""

from __future__ import annotations

import math
from typing import Any

import numpy as np
from numpy.typing import NDArray
from scipy.signal import freqz_sos, resample_poly, sosfilt

OFFSET = -0.691  # BS.1770 constant: K-weighted energy -> LKFS
ABS_GATE = -70.0
REL_GATE_LU = -10.0
LRA_REL_GATE_LU = -20.0  # EBU TECH 3342
SUB_BLOCK_SECONDS = 0.1  # 400 ms block = 4 sub-blocks (75 % overlap), 3 s window = 30
MOMENTARY_SUB, SHORT_TERM_SUB = 4, 30
TP_OVERSAMPLE = 4
TP_EDGE = 10  # input samples at each end that resample_poly's default filter (half length 10 per phase) pads with zeros
TP_CHUNK = 1 << 20  # oversample this many input samples at a time to bound memory
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


def _sos(rate: int) -> NDArray[np.float64]:
    """Both K-weighting stages as second-order sections."""
    return np.array([[*b, 1.0, *a] for b, a in (_shelf(rate), _rlb(rate))])


def gain_db(rate: int, hz: float) -> float:
    """Magnitude of the cascaded K-weighting filter at hz (for checks and documentation)."""
    _, response = freqz_sos(_sos(rate), worN=[hz], fs=rate)
    return float(20 * np.log10(np.abs(response[0])))


def sub_block_energies(x: NDArray[np.float64], rate: int) -> list[float]:
    """Mean square of the K-weighted signal per 100 ms sub-block (full sub-blocks only)."""
    size = max(1, round(SUB_BLOCK_SECONDS * rate))
    count = len(x) // size
    weighted = sosfilt(_sos(rate), x[: count * size])
    return [float(v) for v in np.mean((weighted * weighted).reshape(count, size), axis=1)]


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


def true_peak(x: NDArray[np.float64]) -> tuple[float, float]:
    """(samplePeak, truePeakEstimate), both linear: the peak of the signal oversampled 4x with scipy's polyphase FIR
    (the BS.1770-4 Annex 2 method; scipy's Kaiser filter, not the standard's example filter). TP_EDGE samples at each
    end are left out because the filter sees zero padding there. An estimate, not a certified meter."""
    n = len(x)
    peak = float(np.max(np.abs(x))) if n else 0.0
    if peak == 0 or n <= 2 * TP_EDGE:
        return peak, peak
    best = peak
    edge = TP_EDGE * TP_OVERSAMPLE
    for start in range(0, n - 2 * TP_EDGE, TP_CHUNK):
        seg = x[start : start + TP_CHUNK + 2 * TP_EDGE]  # chunks overlap by 2 * TP_EDGE so every interior sample counts
        up = resample_poly(seg, TP_OVERSAMPLE, 1)[edge:-edge]
        best = max(best, float(np.max(np.abs(up))))
    return peak, best


def _db(value: float) -> float | None:
    return round(20 * math.log10(value), 2) if value > 0 else None


def analyze(channels: list[NDArray[np.float64]], rate: int) -> dict[str, Any]:
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
    weighted = [float(sum(w * e[i] for e, w in zip(per_channel, weights, strict=False) if w)) for i in range(count)]
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
            "truePeakDbtp is the peak after 4x polyphase-FIR oversampling (scipy resample_poly), not a certified BS.1770 true-peak meter: tones read within about +0.02 dB; wideband content near Nyquist can read up to about 1 dB low, as with any 4x meter.",
            f"At most {MAX_SECONDS} s per call.",
            "The whole file counts (use skipSeconds to drop a warm-up); this is a file measurement, not Bitwig's own meter.",
        ],
    }
