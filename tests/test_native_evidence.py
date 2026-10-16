"""Pure evidence-policy regressions; no native host, desktop or project access."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest


POLICY = Path(__file__).resolve().parents[1] / "scripts/evidence_mix.py"
spec = importlib.util.spec_from_file_location("native_mix_policy", POLICY)
mix_policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mix_policy)


def mix_row():
    return {"trackId": "fixture", "selected": False,
            "readable": {"selected": True, "mute": True},
            "sendObservationsComplete": True, "observationsComplete": True,
            "sends": []}


@pytest.mark.parametrize("changes", [
    {"observationsComplete": False},
    {"observationsComplete": 1},
    {"readable": {"selected": True, "mute": 1}},
    {"selected": None},
])
def test_inconsistent_mix_evidence_is_rejected_with_optimized_python(changes):
    row = mix_row()
    row.update(changes)
    code = """
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location('mix_policy', sys.argv[1])
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
row = json.loads(sys.argv[2])
try:
    policy.compare_mix([row], [row])
except ValueError:
    raise SystemExit(0)
raise SystemExit(7)
"""
    result = subprocess.run([sys.executable, "-O", "-c", code, str(POLICY), json.dumps(row)],
                            capture_output=True, text=True, timeout=10)
    assert result.returncode == 0, result.stdout + result.stderr


def test_unknown_send_values_and_readiness_remain_protected():
    before = mix_row()
    before.update(sendObservationsComplete=False, observationsComplete=False,
                  sends=[{"valueNormalized": None, "readable": False}])
    after = copy.deepcopy(before)
    after["sends"][0]["valueNormalized"] = 0.25
    assert mix_policy.compare_mix([before], [after])["musicalEqual"] is False

    after = copy.deepcopy(before)
    after.update(sendObservationsComplete=True, observationsComplete=True)
    assert mix_policy.compare_mix([before], [after])["musicalEqual"] is False

    after = copy.deepcopy(before)
    after["selected"] = True
    result = mix_policy.compare_mix([before], [after])
    assert result["musicalEqual"] is True
    assert result["selectionAndAggregateEqual"] is False
    assert result["beforeSelectionAndAggregate"][0]["observationsComplete"] is False

@pytest.mark.parametrize("replacement", ["input", "render"])
def test_audio_evidence_binds_hashes_to_consumed_pcm(tmp_path, monkeypatch, replacement):
    """Synthetic PCM only; path replacement must not forge accepted evidence."""
    np = pytest.importorskip("numpy", reason="Requires the native-music dependency group")
    sf = pytest.importorskip("soundfile", reason="Requires the native-music dependency group")
    pytest.importorskip("scipy.signal", reason="Requires the native-music dependency group")
    path = Path(__file__).resolve().parents[1] / "scripts/evidence_audio.py"
    spec = importlib.util.spec_from_file_location("audio_evidence", path)
    audio = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audio)
    time = np.arange(16384) / 48000
    signal = np.column_stack((0.08 * np.sin(2 * np.pi * 261.625565 * time),
                              0.07 * np.sin(2 * np.pi * 196 * time)))
    source, render, alternate = (tmp_path / name for name in ("source.wav", "render.wav", "alternate.wav"))
    sf.write(source, signal, 48000, subtype="PCM_24")
    sf.write(render, signal, 48000, subtype="PCM_24")
    sf.write(alternate, signal * 0.5, 48000, subtype="PCM_24")
    original_hash = hashlib.sha256(render.read_bytes()).hexdigest()
    alternate_bytes = alternate.read_bytes()
    declaration = {
        "inputPath": str(source),
        "inputSha256": hashlib.sha256(alternate_bytes if replacement == "input" else source.read_bytes()).hexdigest(),
        "renderPaths": {"control": str(render)}, "gains": {"control": 1.0}, "sealedTimeNs": 0,
        "limits": {"gainAbsoluteError": 0.001, "correlationMinimum": 0.99999,
                   "residualRelativeMaximum": 0.0001, "clippingAbsolute": 0.9,
                   "minOverlapFrames": 8193, "inputRmsMinimum": 0.01},
    }
    declaration_bytes = json.dumps(declaration).encode()
    (tmp_path / "music-predeclaration.json").write_bytes(declaration_bytes)
    real_read = sf.read
    reads = 0
    def replace_after_decode(file, *args, **kwargs):
        nonlocal reads
        decoded = real_read(file, *args, **kwargs)
        reads += 1
        if reads == (1 if replacement == "input" else 2):
            (source if replacement == "input" else render).write_bytes(alternate_bytes)
        return decoded
    monkeypatch.setattr(sf, "read", replace_after_decode)
    if replacement == "input":
        with pytest.raises(ValueError):
            audio.analyze(tmp_path)
    else:
        result = audio.analyze(tmp_path)
        assert result["passed"] is True
        assert result["cases"]["control"]["sha256"] == original_hash
        assert hashlib.sha256(render.read_bytes()).hexdigest() != original_hash
        assert result["declarationSha256"] == hashlib.sha256(declaration_bytes).hexdigest()
