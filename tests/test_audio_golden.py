"""audio.analyze on numpy/scipy/soundfile against the stdlib implementation's outputs, an independent loudness meter
(pyloudnorm) and the reader formats libsndfile adds."""

import asyncio
import json
import math
import sys
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf
from audio_golden import key, run_all, sine, write

import bitwig_agent.audio
from bitwig_agent.audio import analysis, loudness, pipewire
from bitwig_agent.errors import BridgeError
from bitwig_agent.protocol import validate_call

BASELINE = json.loads((Path(__file__).parent / "fixtures" / "audio_golden_baseline.json").read_text(encoding="utf-8"))
RATE = 48000

# Intended differences from the baseline, each explained in the PR that replaced the stdlib DSP:
TEXT_CHANGED = {"limits", "note"}  # wording about the new filters / removed "pure Python" timing
TRUE_PEAK_DB = 0.2  # resample_poly's filter instead of a 24-tap windowed sinc; wideband noise moves most
CHROMA_SHARE = 0.02  # the anti-alias low-pass removes folded energy that small shares carried
NUMERIC_FLOOR_DB = -200.0  # bandsDb this low is FFT round-off of an empty band, not a measurement
# Files without onsets: flux peaks there are relative to round-off-sized flux, so they move with any filter change.
# Bounded instead of compared: (most onsets, latest onset in seconds).
NO_REAL_ONSETS = {"noise_f64": (25, 4.0), "sine2500_s16": (1, 0.01), "sine3000_s24": (1, 0.01)}
ALIASED = {"sine2500_s16", "sine3000_s24"}  # the bug: their chroma was folded energy from 2.5/3 kHz


def _diff(old, new, path, out):
    """Append (path, field, old, new) for every leaf that differs; lists of other lengths differ as a whole."""
    field = path.rsplit(".", 1)[-1].split("[")[0]
    if isinstance(old, dict) and isinstance(new, dict):
        assert old.keys() == new.keys(), path
        for name in old:
            _diff(old[name], new[name], f"{path}.{name}", out)
    elif isinstance(old, list) and isinstance(new, list) and len(old) == len(new):
        for i, (a, b) in enumerate(zip(old, new, strict=True)):
            _diff(a, b, f"{path}[{i}]", out)
    elif old != new:
        out.append((path, field, old, new))


def _allowed(signal, path, field, old, new):
    if field in TEXT_CHANGED:
        return True
    if field in ("truePeakDbtp", "perChannelTruePeakDbtp"):
        return all(abs(a - b) <= TRUE_PEAK_DB for a, b in zip(np.atleast_1d(old), np.atleast_1d(new), strict=True))
    if ".chroma" in path and signal not in ALIASED and field != "name":
        return abs(old - new) <= CHROMA_SHARE
    if ".bandsDb." in path:
        return old < NUMERIC_FLOOR_DB and new < NUMERIC_FLOOR_DB
    # ALIASED chroma is asserted empty and NO_REAL_ONSETS bounded below; near-equal noise shares swap order
    return (".chroma" in path and (signal in ALIASED or signal == "noise_f64")) or (
        '"onsets"' in path and signal in NO_REAL_ONSETS
    )


def test_outputs_match_the_stdlib_baseline_except_the_documented_changes(tmp_path):
    current = run_all(tmp_path)
    assert current.keys() == BASELINE.keys()
    unexplained = []
    for call, old in BASELINE.items():
        signal = call.split(" ", 1)[0]
        diffs: list[tuple[str, str, object, object]] = []
        _diff(old, current[call], call, diffs)
        unexplained += [d for d in diffs if not _allowed(signal, *d)]
    assert unexplained == []
    for signal, (most, latest) in NO_REAL_ONSETS.items():
        found = current[key(signal, {"mode": "onsets"})]["onsets"]
        assert len(found) <= most
        assert all(t <= latest for t in found)
    for signal in ALIASED:
        call = key(signal, {"mode": "bands", "windowSeconds": 2.0, "key": "A:aeolian"})
        assert BASELINE[call]["chroma"]["topPitchClasses"][0]["share"] == 1.0  # the baseline really carried the bug
        assert current[call]["chroma"]["topPitchClasses"] == []
        assert current[call]["chroma"]["inKeyShare"] is None


def run_analyze(path, **args):
    params, _ = validate_call("audio.analyze", {"path": str(path), **args})
    return asyncio.run(pipewire.analyze(params))


@pytest.mark.parametrize("hz", [2500, 3000, 4500])
def test_chroma_does_not_fold_tones_above_its_range(tmp_path, hz):
    write(tmp_path / "t.wav", [sine(hz, 4, RATE)] * 2, RATE, 24)
    out = run_analyze(tmp_path / "t.wav", mode="bands", key="C:ionian")
    assert out["chroma"]["topPitchClasses"] == []
    assert out["chroma"]["inKeyShare"] is None
    assert all(row["chroma"]["topPitchClasses"] == [] for row in out["windows"])


def test_chroma_of_a_tone_with_a_high_partial_names_only_the_tone(tmp_path):
    # 3000 Hz used to fold onto 1000 Hz (B); with the anti-alias filter only the 440 Hz A remains.
    mix = [a + 0.5 * b for a, b in zip(sine(440, 4, RATE, db=-12), sine(3000, 4, RATE), strict=True)]
    write(tmp_path / "m.wav", [mix, mix], RATE, 32, True)
    chroma = run_analyze(tmp_path / "m.wav", mode="bands", key="A:aeolian")["chroma"]
    top = chroma["topPitchClasses"][0]
    assert (top["name"], top["share"]) == ("A", 1.0)


@pytest.mark.parametrize(("level_db", "kept"), [(-50, True), (-70, False)])
def test_chroma_floor_is_relative_to_the_total_power(tmp_path, level_db, kept):
    # A loud 3000 Hz tone sets the window's total power; pitched 440 Hz content 50 dB below it is reported,
    # 70 dB below it is under the 60 dB floor.
    mix = [a + b for a, b in zip(sine(3000, 4, RATE), sine(440, 4, RATE, db=-6 + level_db), strict=True)]
    write(tmp_path / "f.wav", [mix, mix], RATE, 32, True)
    top = run_analyze(tmp_path / "f.wav", mode="bands", key="A:aeolian")["chroma"]["topPitchClasses"]
    assert (top[0]["name"] if top else None) == ("A" if kept else None)


def test_unrelated_import_errors_are_not_reported_as_the_missing_extra(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "soundfile", None)
    monkeypatch.delitem(sys.modules, "bitwig_agent.audio.analysis")
    monkeypatch.delattr(bitwig_agent.audio, "analysis")
    with pytest.raises(BridgeError, match="soundfile is missing"):
        run_analyze(tmp_path / "x.wav", mode="level")
    monkeypatch.setitem(sys.modules, "bitwig_agent.audio.loudness", None)  # a broken install, not the extra
    monkeypatch.delattr(bitwig_agent.audio, "loudness")
    monkeypatch.delitem(sys.modules, "soundfile")
    with pytest.raises(ImportError) as error:
        pipewire.load_analysis()
    assert error.value.name == "bitwig_agent.audio.loudness"


def _program(rate=RATE):
    """10 s of a 220 Hz tone and noise, with a 10 dB step at 5 s."""
    t = np.arange(10 * rate) / rate
    rng = np.random.default_rng(3)
    x = 0.2 * np.sin(2 * np.pi * 220 * t) + 0.05 * rng.standard_normal(len(t))
    x[len(t) // 2 :] *= 10 ** (-10 / 20)
    return np.stack([x, 0.8 * x], axis=1)


@pytest.mark.parametrize("rate", [44100, 48000])
def test_integrated_loudness_agrees_with_pyloudnorm(tmp_path, rate):
    pyloudnorm = pytest.importorskip("pyloudnorm")
    data = _program(rate)
    sf.write(tmp_path / "p.wav", data, rate, subtype="FLOAT")
    out = run_analyze(tmp_path / "p.wav", mode="loudness")
    meter = pyloudnorm.Meter(rate)
    assert out["integratedLufs"] == pytest.approx(meter.integrated_loudness(data), abs=0.1)
    # pyloudnorm's LRA is documented as an attempt; recorded here as a loose check of the same order.
    assert out["loudnessRangeLu"] == pytest.approx(meter.loudness_range(data), abs=1.0)


def test_reader_accepts_flac_8_bit_and_rf64(tmp_path):
    tone = np.array(sine(440, 1, RATE))
    for name, fmt, subtype in (
        ("a.flac", "FLAC", "PCM_24"),
        ("b.wav", "WAV", "PCM_U8"),
        ("c.wav", "RF64", "PCM_16"),
    ):
        sf.write(tmp_path / name, np.stack([tone, tone], axis=1), RATE, format=fmt, subtype=subtype)
        out = run_analyze(tmp_path / name, mode="level")
        assert out["peakDb"] == pytest.approx(-6.02, abs=0.2), name  # 8-bit rounding
        assert out["durationSeconds"] == 1.0


def test_reader_rejects_low_rates_and_large_files(tmp_path, monkeypatch):
    sf.write(tmp_path / "low.wav", np.zeros(4000), 4000)
    with pytest.raises(BridgeError, match="sample rate"):
        run_analyze(tmp_path / "low.wav", mode="level")
    write(tmp_path / "ok.wav", [sine(440, 0.1, RATE)], RATE, 16)
    monkeypatch.setattr(analysis, "MAX_BYTES", 100)
    with pytest.raises(BridgeError, match="256 MiB"):
        run_analyze(tmp_path / "ok.wav", mode="level")


def test_true_peak_covers_every_chunk_of_a_long_signal(monkeypatch):
    monkeypatch.setattr(loudness, "TP_CHUNK", 1000)
    x = np.zeros(5000)
    x[2500:2504] = (0.0, 0.7071, 0.7071, 0.0)  # an intersample peak inside the third chunk
    sample, true = loudness.true_peak(x)
    assert sample == pytest.approx(0.7071)
    assert true > sample * 1.05
    assert loudness.true_peak(np.full(15, 0.5)) == (0.5, 0.5)  # too short to oversample


def test_missing_audio_extra_names_the_install_command(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "numpy", None)
    monkeypatch.delitem(sys.modules, "bitwig_agent.audio.analysis")
    monkeypatch.delattr(bitwig_agent.audio, "analysis")
    with pytest.raises(BridgeError) as error:
        run_analyze(tmp_path / "x.wav", mode="level")
    assert error.value.error["reason"] == "unsupported_platform"
    assert "pip install 'bitwig-agent[audio]'" in error.value.error["message"]
    assert "numpy" in error.value.error["message"]
    monkeypatch.setattr(pipewire, "check_platform", lambda: None)
    capture, _ = validate_call("audio.capture", {"seconds": 1, "outPath": str(tmp_path / "c.wav")})
    with pytest.raises(BridgeError, match=r"bitwig-agent\[audio\]"):
        asyncio.run(pipewire.capture(None, capture, 1000))
    assert not (tmp_path / "c.wav").exists()


def test_spectrogram_colours_follow_the_palette():
    rgb = analysis._colors(np.array([0.0, 1.0, 0.6, -1.0, 2.0]))
    assert rgb.tolist() == [[0, 0, 0], [255, 255, 255], [240, 110, 30], [0, 0, 0], [255, 255, 255]]
    assert math.isclose(analysis._parabolic(1.0, 2.0, 1.0), 0.0)
