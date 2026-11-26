"""The FE5 harness: metrics from a synthetic transcript, the report, and the offline shell scripts."""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parent.parent
EVAL = ROOT / "eval" / "front-ends"
spec = importlib.util.spec_from_file_location("fe5_metrics", EVAL / "metrics.py")
assert spec is not None
assert spec.loader is not None
metrics = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = metrics
spec.loader.exec_module(metrics)

BAD_ARG = '{"code":"invalid_params","reason":"invalid_params","message":"type: expected audio|instrument|effect"}'


def say(text: str) -> dict[str, Any]:
    return {"type": "assistant", "message": {"content": [{"type": "text", "text": text}]}}


def use(call_id: str, tool: str, **tool_input: Any) -> dict[str, Any]:
    return {
        "type": "assistant",
        "message": {"content": [{"type": "tool_use", "id": call_id, "name": tool, "input": tool_input}]},
    }


def result(call_id: str, text: str, error: bool = False) -> dict[str, Any]:
    block = {"type": "tool_result", "tool_use_id": call_id, "content": text, "is_error": error}
    return {"type": "user", "message": {"content": [block]}}


def transcript() -> list[dict[str, Any]]:
    return [
        {"type": "system", "subtype": "init", "model": "claude-sonnet-5-5"},
        say("=== TASK 1 ==="),
        use("1", "Skill", skill="bitwig-agent"),
        result("1", "Launching skill"),
        {"type": "user", "message": {"content": [{"type": "text", "text": "S" * 100}]}},
        use("2", "Bash", command="bitwig-agent --help"),
        result("2", "h" * 50),
        use("3", "Bash", command="bitwig-agent tracks insert --type=bass --name=Bass"),
        result("3", f"Exit code 2\n{BAD_ARG}", True),
        use("4", "Bash", command="bitwig-agent tracks insert --type=instrument --name=Bass | jq -c .ref"),
        result("4", "{}"),
        use("5", "Bash", command="bitwig-agent tracks list | jq '.tracks[] | .nope['"),
        result("5", "Exit code 3\njq: error: syntax error, unexpected end of file", True),
        use("6", "Read", file_path="/repo/src/bitwig_agent/core/views.py"),
        result("6", "x" * 10),
        say("=== TASK 2 ==="),
        use("7", "mcp__bitwig-agent__help", topic="refs"),
        result("7", "r" * 30),
        use("8", "mcp__bitwig-agent__notes.edit", target={}),
        result("8", '{"outcome":"not_applied"}', True),
        use("9", "Read", file_path="/work/.claude/skills/bitwig-agent/SKILL.md"),
        result("9", "m" * 20),
        {"type": "result", "num_turns": 9, "duration_ms": 1000, "total_cost_usd": 0.5},
    ]


def test_calls_bytes_and_tasks() -> None:
    out = metrics.analyze(transcript())
    assert out["model"] == "claude-sonnet-5-5"
    assert out["calls"] == 9
    assert out["calls_by_tool"] == {
        "Skill": 1,
        "Bash": 4,
        "Read": 2,
        "mcp__bitwig-agent__help": 1,
        "mcp__bitwig-agent__notes.edit": 1,
    }
    assert out["task_split"] == "markers"
    assert out["calls_by_task"] == {"pre": 0, "1": 6, "2": 3, "3": 0, "4": 0, "5": 0}
    assert out["learning_calls"] == 4
    assert out["learning_bytes"] == len("Launching skill") + 100 + 50 + 30 + 20
    assert out["total_read_bytes"] > out["learning_bytes"]
    assert out["turns"] == 9
    assert [v["index"] for v in out["violations"]] == [6]


def test_failures_are_labelled_and_self_correction_counted() -> None:
    out = metrics.analyze(transcript())
    labels = {f["index"]: (f["label"], f["method"]) for f in out["failures"]}
    assert labels == {3: ("tool", "tracks.insert"), 5: ("agent", "tracks.list"), 8: ("tool", "notes.edit")}
    assert out["tool_failures"] == 2
    assert out["agent_failures"] == 1
    assert out["self_corrections"] == 1
    assert out["self_correction_rate"] == 0.5
    assert out["abandoned_methods"] == ["notes.edit"]
    assert {f["index"]: f["self_corrected"] for f in out["failures"] if "self_corrected" in f} == {3: True, 8: False}


def test_label_override_wins() -> None:
    out = metrics.analyze(transcript(), {"5": "tool", "3": "agent"})
    by_index = {f["index"]: f for f in out["failures"]}
    assert by_index[5]["label"] == "tool"
    assert by_index[5]["label_source"] == "override"
    assert by_index[3]["label"] == "agent"
    assert by_index[8]["label_source"] == "heuristic"


def test_tasks_fall_back_to_method_families_without_markers() -> None:
    events = [
        e for e in transcript() if not (e["type"] == "assistant" and e["message"]["content"][0]["type"] == "text")
    ]
    out = metrics.analyze(events)
    assert out["task_split"] == "keywords"
    assert out["calls_by_task"]["1"] == 2
    assert out["calls_by_task"]["2"] == 1


def test_old_dialect_learning_calls() -> None:
    events = [
        use("1", "Bash", command="bitwig-agent-rpc list"),
        result("1", "l" * 7),
        use("2", "Bash", command="bitwig-agent-rpc describe grid.apply"),
        result("2", "d" * 5),
        use("3", "Bash", command="bitwig-agent-rpc tracks.list | jq ."),
        result("3", "{}"),
        use("4", "Bash", command="cat skills/bitwig-agent/SKILL.md"),
        result("4", "s" * 3),
    ]
    out = metrics.analyze(events)
    assert (out["learning_calls"], out["learning_bytes"]) == (3, 15)
    calls = metrics.parse(events)[0]
    assert metrics.method_of(calls[1]) is None
    assert (
        metrics.method_of(metrics.parse([use("1", "Bash", command="bitwig-agent-rpc grid.apply spec=@p.json")])[0][0])
        == "grid.apply"
    )


def run_files(tmp_path: Path, condition: str, number: int, **overrides: Any) -> None:
    directory = tmp_path / condition / f"run{number}"
    directory.mkdir(parents=True)
    analysed = metrics.analyze(transcript())
    analysed.update(overrides.pop("metrics", {}))
    (directory / "metrics.json").write_text(json.dumps(analysed))
    (directory / "meta.json").write_text(
        json.dumps({"commit": "abc1234", "model": "m", "skill_bytes": 1, "tools_list_bytes": None})
    )
    verify = {f"t{t}": {"status": "pass"} for t in range(1, 6)} | {"t1": {"status": "pass", "grid": True}}
    (directory / "verify.json").write_text(json.dumps(verify | overrides.pop("verify", {})))


def test_report_applies_the_criteria_per_run(tmp_path: Path) -> None:
    run_files(tmp_path, "A", 1)
    run_files(tmp_path, "A", 2)
    run_files(tmp_path, "B", 1, metrics={"tool_failures": 0, "abandoned_methods": []})
    run_files(tmp_path, "B", 2, verify={"t4": {"status": "fail"}})
    run_files(tmp_path, "C", 1, verify={"t3": {"status": "review"}}, metrics={"abandoned_methods": []})
    text = metrics.report(metrics.collect(tmp_path))
    rows = {
        line.split("|")[1].strip() + line.split("|")[2].strip(): line
        for line in text.splitlines()
        if line.startswith("| ") and "ok" in line + "FAIL" + "review"
    }
    assert "PASS" in rows["B1"]
    assert "FAIL" in rows["B2"]
    assert "REVIEW" in rows["C1"]
    assert "## Failed calls" in text
    assert "Read of /repo/src/bitwig_agent/core/views.py" in text


def test_cli_prints_metrics_and_reads_sibling_labels(tmp_path: Path) -> None:
    path = tmp_path / "run.jsonl"
    path.write_text("\n".join(json.dumps(e) for e in transcript()) + "\n")
    (tmp_path / "labels.json").write_text(json.dumps({"5": "tool"}))
    out = tmp_path / "metrics.json"
    assert metrics.main.__module__ == "fe5_metrics"
    subprocess.run(["python3", str(EVAL / "metrics.py"), str(path), "--out", str(out)], check=True)
    assert json.loads(out.read_text())["tool_failures"] == 3
    bad = tmp_path / "bad.jsonl"
    bad.write_text("{nope\n")
    with pytest.raises(ValueError, match="not JSON"):
        metrics.load(bad)


def run_script(
    *argv: str, env: dict[str, str] | None = None, path: str | None = None
) -> subprocess.CompletedProcess[str]:
    base = {k: v for k, v in os.environ.items() if not k.startswith("BWA_")}
    base.update(env or {})
    if path is not None:
        base["PATH"] = path
    return subprocess.run(["bash", *argv], capture_output=True, text=True, env=base, check=False)


@pytest.mark.skipif(shutil.which("bash") is None, reason="needs bash")
def test_dry_run_prints_the_plan_and_runs_nothing() -> None:
    done = run_script(
        str(EVAL / "run_all.sh"),
        "--dry-run",
        "--conditions",
        "A C",
        "--runs",
        "2",
        env={"BWA_EVAL_OUT": "/eval-out"},
        path="/usr/bin:/bin",
    )
    assert done.returncode == 0, done.stderr
    plan = done.stdout
    assert plan.count("== A run") == 2
    assert "== extension 0.4 for A" in plan
    assert "== extension current for" in plan
    assert "PYTHONPATH=<unset:BWA_V04_TREE>/src pixi run install" in plan
    assert "'--tools' '' '--mcp-config'" in plan
    assert "'--strict-mcp-config' '--allowedTools' 'mcp__bitwig-agent'" in plan
    assert "'--model' 'claude-sonnet-5-5'" in plan
    assert "Read(//eval-out/work/A-run1/.claude/skills/**)" in plan


@pytest.mark.skipif(shutil.which("bash") is None, reason="needs bash")
def test_real_run_refuses_without_its_environment_and_inside_the_repo() -> None:
    assert "BWA_EVAL_OUT is not set" in run_script(str(EVAL / "run_all.sh")).stderr
    env = {"BWA_EVAL_OUT": str(ROOT / "build" / "eval"), "BWA_RESTART_SCRIPT": "/bin/true"}
    assert "inside the repository" in run_script(str(EVAL / "run_all.sh"), env=env).stderr


@pytest.mark.skipif(shutil.which("bash") is None or shutil.which("jq") is None, reason="needs bash and jq")
def test_verify_reads_state_through_the_condition_client(tmp_path: Path) -> None:
    """A stub bitwig-agent answers from canned rows, so the judging logic runs without a host."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    state = tmp_path / "answers"
    state.mkdir()
    bass = {"kind": "track", "id": "11111111-1111-1111-1111-111111111111", "name": "Bass"}
    drums = {"kind": "track", "id": "22222222-2222-2222-2222-222222222222", "name": "Drums"}
    device = {"kind": "device", "track": bass, "index": 0, "name": "Poly Grid"}
    comp = {"kind": "device", "track": bass, "index": 1, "name": "Compressor"}
    notes = [{"beat": b / 2, "pitch": 40, "velocity": 90, "durationBeats": 0.5} for b in range(64)]
    wav = tmp_path / "exports" / "out.wav"
    wav.parent.mkdir()
    wav.write_bytes(b"RIFF" + b"0" * 100)
    answers = {
        "status": {"projectName": "BWA Test", "instance": {"display": ":1"}},
        "tracks.list": {"tracks": [{"name": "Drums", "ref": drums}, {"name": "Bass", "ref": bass}]},
        "devices.list": {
            "devices": [
                {"name": "Poly Grid", "ref": device, "index": 0},
                {"name": "Compressor", "ref": comp, "index": 1},
            ]
        },
        "context.get": {"tracks": [{"name": "Bass", "launcherSlots": [{"ref": {"kind": "clip"}}]}]},
        "notes.read": {"notes": notes},
        "parameters.list": {
            "parameters": [
                {"name": "Filter Cutoff", "value": {"value": 0.3}, "default": 0.5},
                {"name": "Resonance", "value": {"value": 0.4}, "default": 0.1},
            ]
        },
        "grid.export": {"modules": []},
        "devices.sidechain.get": {"components": [{"source": {"track": drums}}]},
        "audio.analyze": {"loudness": {"integratedLufs": -14.2}},
    }
    for method, answer in answers.items():
        (state / method).write_text(json.dumps(answer))
    stub = bin_dir / "bitwig-agent"
    stub.write_text(f'#!/usr/bin/env bash\ncat "{state}/$1"\n')
    stub.chmod(0o755)
    env = {"BWA_MAIN_TREE": str(tmp_path), "BWA_BIN_B": str(bin_dir)}
    done = run_script(str(EVAL / "verify.sh"), "B", str(wav.parent), str(tmp_path / "raw"), env=env)
    assert done.returncode == 0, done.stderr
    verdict = json.loads(done.stdout)
    assert {t: verdict[t]["status"] for t in ("t1", "t2", "t3", "t4", "t5")} == dict.fromkeys(
        ("t1", "t2", "t3", "t4", "t5"), "pass"
    )
    assert verdict["t1"]["grid"] is True
    assert verdict["t5"]["lufs"] == -14.2
    assert (tmp_path / "raw" / "tracks.json").is_file()

    (state / "devices.sidechain.get").write_text(json.dumps({"components": [{"source": {"track": bass}}]}))
    (state / "status").write_text(json.dumps({"projectName": "BWA Test", "instance": {"display": ":0"}}))
    refused = run_script(str(EVAL / "verify.sh"), "B", str(wav.parent), str(tmp_path / "raw2"), env=env)
    assert refused.returncode != 0
    assert "not :1" in refused.stderr
