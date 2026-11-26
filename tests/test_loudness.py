"""audio.analyze mode=loudness against synthetic signals with analytically known BS.1770 values (no Bitwig)."""

import asyncio
import math
import struct

import pytest

from bitwig_agent import audio, loudness
from bitwig_agent.catalog import validate_call
from bitwig_agent.client import BridgeError

RATE = 48000


def write_float_wav(path, channels, rate=RATE):
    """32-bit float WAV, so the file carries the exact synthetic samples (no 16-bit rounding)."""
    n = len(channels[0])
    data = b"".join(struct.pack("<%df" % len(channels), *(ch[i] for ch in channels)) for i in range(n))
    fmt = struct.pack("<HHIIHH", 3, len(channels), rate, rate * 4 * len(channels), 4 * len(channels), 32)
    body = b"WAVEfmt " + struct.pack("<I", 16) + fmt + b"data" + struct.pack("<I", len(data)) + data
    path.write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)


def sine(db, hz, seconds, rate=RATE, phase=0.0):
    amp = 10 ** (db / 20)
    return [amp * math.sin(2 * math.pi * hz * i / rate + phase) for i in range(int(seconds * rate))]


def analyze(path, **args):
    params, _ = validate_call("audio.analyze", {"path": str(path), "mode": "loudness", **args})
    return asyncio.run(audio.analyze(params))


def expected_lufs(amplitude_db, hz, channel_weights=(1.0, 1.0), rate=RATE):
    """BS.1770 by hand for a steady sine: -0.691 + 10 log10(sum_c G_c * (A^2/2) * |H(f)|^2), |H| evaluated analytically."""
    power = 10 ** (amplitude_db / 10) / 2
    h = 10 ** (loudness.gain_db(rate, hz) / 10)
    return -0.691 + 10 * math.log10(sum(w * power * h for w in channel_weights))


def test_k_weighting_matches_the_standard_at_48k():
    c = loudness.coefficients(48000)
    # ITU-R BS.1770-4 Table 1 and 2.
    assert c["shelfB"] == pytest.approx((1.53512485958697, -2.69169618940638, 1.19839281085285), abs=1e-12)
    assert c["shelfA"] == pytest.approx((-1.69065929318241, 0.73248077421585), abs=1e-12)
    assert c["rlbA"] == pytest.approx((-1.99004745483398, 0.99007225036621), abs=1e-12)
    assert loudness.gain_db(48000, 997) == pytest.approx(0.691, abs=0.01)  # why the -0.691 offset exists
    assert loudness.gain_db(48000, 10) < -20 and loudness.gain_db(48000, 10000) > 3


def test_stereo_sine_is_its_level_in_lufs(tmp_path):
    # EBU TECH 3341 style: a 997 Hz sine at -20 dBFS on both channels reads -20.0 LUFS (the 0.691 offset cancels the K gain).
    tone = sine(-20, 997, 6)
    write_float_wav(tmp_path / "a.wav", [tone, tone])
    out = analyze(tmp_path / "a.wav")
    assert out["integratedLufs"] == pytest.approx(-20.0, abs=0.05)
    assert out["integratedLufs"] == pytest.approx(expected_lufs(-20, 997), abs=0.03)
    assert out["momentaryMaxLufs"] == pytest.approx(-20.0, abs=0.1)
    assert out["shortTermMaxLufs"] == pytest.approx(-20.0, abs=0.1)
    assert out["samplePeakDbfs"] == pytest.approx(-20.0, abs=0.01)
    assert out["channels"] == 2 and out["analyzedChannel"] == "all" and out["channelLayout"] == "stereo"


def test_one_kilohertz_uses_the_filter_gain_not_a_guess(tmp_path):
    tone = sine(-20, 1000, 6)
    write_float_wav(tmp_path / "b.wav", [tone, tone])
    out = analyze(tmp_path / "b.wav")
    assert out["integratedLufs"] == pytest.approx(expected_lufs(-20, 1000), abs=0.03)
    assert abs(out["integratedLufs"] + 20) < 0.06


def test_mono_reads_three_lu_below_dual_mono_and_one_channel_of_two_likewise(tmp_path):
    tone = sine(-20, 997, 6)
    write_float_wav(tmp_path / "m.wav", [tone])
    write_float_wav(tmp_path / "l.wav", [tone, [0.0] * len(tone)])
    mono, left = analyze(tmp_path / "m.wav"), analyze(tmp_path / "l.wav")
    assert mono["integratedLufs"] == pytest.approx(-23.0, abs=0.05)
    assert left["integratedLufs"] == pytest.approx(-23.0, abs=0.05)
    assert mono["channelLayout"] == "mono"


def test_relative_gate_ignores_the_quiet_part(tmp_path):
    loud, quiet = (
        sine(-20, 997, 10),
        sine(-45, 997, 10),
    )  # 25 dB apart: the quiet part is below the -10 LU relative gate
    both = loud + quiet
    write_float_wav(tmp_path / "g.wav", [both, both])
    out = analyze(tmp_path / "g.wav")
    assert out["integratedLufs"] == pytest.approx(-20.0, abs=0.1)
    assert out["gating"]["relativeGated"] < out["gating"]["absoluteGated"]
    assert out["gating"]["relativeThresholdLufs"] == pytest.approx(-20 - 3.0 - 10, abs=1.0)


def test_absolute_gate_makes_silence_unmeasurable(tmp_path):
    silent = [0.0] * RATE
    write_float_wav(tmp_path / "s.wav", [silent, silent])
    out = analyze(tmp_path / "s.wav")
    assert out["integratedLufs"] is None and out["truePeakDbtp"] is None and out["gating"]["absoluteGated"] == 0
    quiet = sine(-90, 997, 3)
    write_float_wav(tmp_path / "q.wav", [quiet, quiet])
    assert analyze(tmp_path / "q.wav")["integratedLufs"] is None  # below -70 LUFS


def test_true_peak_finds_the_intersample_peak(tmp_path):
    # fs/4 sine at 45 degrees: every sample is +-0.7071 (-3.01 dBFS) but the waveform peaks at 1.0 between samples.
    tone = sine(0, 12000, 2, phase=math.pi / 4)
    write_float_wav(tmp_path / "t.wav", [tone, tone])
    out = analyze(tmp_path / "t.wav")
    assert out["samplePeakDbfs"] == pytest.approx(-3.01, abs=0.02)
    assert out["truePeakDbtp"] == pytest.approx(0.0, abs=0.1)
    assert out["perChannelTruePeakDbtp"] == [out["truePeakDbtp"]] * 2


def test_true_peak_is_the_sample_peak_for_a_slow_sine(tmp_path):
    tone = sine(-6, 100, 1, phase=math.pi / 2)
    write_float_wav(tmp_path / "u.wav", [tone, tone])
    out = analyze(tmp_path / "u.wav")
    assert out["truePeakDbtp"] == pytest.approx(-6.02, abs=0.05)


def test_loudness_range_of_a_two_level_signal(tmp_path):
    # 30 s at -30 and 30 s at -20 (both above the -20 LU relative gate of the mean): LRA about 10 LU.
    tone = sine(-30, 997, 30) + sine(-20, 997, 30)
    write_float_wav(tmp_path / "r.wav", [tone, tone])
    out = analyze(tmp_path / "r.wav")
    assert out["loudnessRangeLu"] == pytest.approx(10.0, abs=1.0)
    steady = sine(-23, 997, 8)
    write_float_wav(tmp_path / "st.wav", [steady, steady])
    assert analyze(tmp_path / "st.wav")["loudnessRangeLu"] == pytest.approx(0.0, abs=0.2)


def test_short_files_report_what_they_can(tmp_path):
    tone = sine(-20, 997, 1)
    write_float_wav(tmp_path / "short.wav", [tone, tone])
    out = analyze(tmp_path / "short.wav")
    assert out["integratedLufs"] == pytest.approx(-20.0, abs=0.1)
    assert out["shortTermMaxLufs"] is None and out["loudnessRangeLu"] is None
    tiny = sine(-20, 997, 0.2)
    write_float_wav(tmp_path / "tiny.wav", [tiny, tiny])
    assert analyze(tmp_path / "tiny.wav")["integratedLufs"] is None


def test_skip_seconds_drops_the_warm_up(tmp_path):
    tone = sine(-40, 997, 4) + sine(-20, 997, 6)
    write_float_wav(tmp_path / "w.wav", [tone, tone])
    assert analyze(tmp_path / "w.wav", skipSeconds=4)["integratedLufs"] == pytest.approx(-20.0, abs=0.1)
    with pytest.raises(BridgeError):
        analyze(tmp_path / "w.wav", skipSeconds=10)


def test_five_one_weights_and_limits_are_reported(tmp_path):
    tone = sine(-20, 997, 4)
    silent = [0.0] * len(tone)
    write_float_wav(
        tmp_path / "f.wav", [silent, silent, silent, tone, silent, silent]
    )  # LFE only: excluded by the standard
    out = analyze(tmp_path / "f.wav")
    assert out["integratedLufs"] is None and out["channelWeights"] == [1.0, 1.0, 1.0, 0.0, 1.41, 1.41]
    write_float_wav(tmp_path / "f2.wav", [silent, silent, silent, silent, tone, silent])  # Ls: weight 1.41
    surround = analyze(tmp_path / "f2.wav")
    assert surround["integratedLufs"] == pytest.approx(expected_lufs(-20, 997, (1.41,)), abs=0.05)
    assert any("not a certified" in line for line in surround["limits"])


def test_rates_other_than_48k_use_derived_coefficients(tmp_path):
    tone = sine(-20, 997, 6, rate=44100)
    write_float_wav(tmp_path / "r44.wav", [tone, tone], rate=44100)
    out = analyze(tmp_path / "r44.wav")
    assert out["integratedLufs"] == pytest.approx(expected_lufs(-20, 997, rate=44100), abs=0.05)
    assert abs(out["integratedLufs"] + 20) < 0.1


def test_length_cap_names_the_fix(tmp_path, monkeypatch):
    monkeypatch.setattr(loudness, "MAX_SECONDS", 1)
    tone = sine(-20, 997, 2)
    write_float_wav(tmp_path / "long.wav", [tone, tone])
    with pytest.raises(BridgeError) as error:
        analyze(tmp_path / "long.wav")
    assert "skipSeconds" in str(error.value)


def test_existing_modes_still_read_channel_zero(tmp_path):
    tone = sine(-6, 997, 1)
    write_float_wav(tmp_path / "e.wav", [tone, [0.0] * len(tone)])
    params, _ = validate_call("audio.analyze", {"path": str(tmp_path / "e.wav"), "mode": "level"})
    out = asyncio.run(audio.analyze(params))
    assert out["analyzedChannel"] == 0 and out["peakDb"] == pytest.approx(-6.02, abs=0.05)
