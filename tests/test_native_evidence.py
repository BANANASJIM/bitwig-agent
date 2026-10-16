"""Pure evidence-policy regressions; no native host, desktop or project access."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import pytest


POLICY = (Path(__file__).resolve().parents[1] / "runs/2026-10-15-toolkit/"
          "panel-layer-native/semantic-mix-v2.py")
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
