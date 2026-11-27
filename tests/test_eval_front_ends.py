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
        use("8", "mcp__bitwig-agent__notes_edit", target={}),
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
        "mcp__bitwig-agent__notes_edit": 1,
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
def verify_world(tmp_path: Path) -> tuple[Path, Path, dict[str, Any], dict[str, str]]:
    """A stub bitwig-agent answers from canned rows (state dir, wav, answers, env), so verify.sh runs without a host."""
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
        # live shape: the name is only under ref
        "tracks.list": {"tracks": [{"ref": drums, "index": 0}, {"ref": bass, "index": 1}]},
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
        "devices.sidechain.get": {
            "components": [
                {
                    "source": {"kind": "pre_fader", "trackId": drums["id"]},
                    "sourceName": "Pre-Fader",
                    "sourceTrackName": "Drums",
                }
            ]
        },
        "audio.analyze": {"integratedLufs": -14.2, "durationSeconds": 16.0},
        "transport.get": {"tempoBpm": 120.0, "playing": True},
        "transport.set": {"outcome": "applied"},
        "export.audio": {"outcome": "applied"},
        "devices.catalog": {"devices": [{"name": "Compressor", "path": "/lib/Compressor.bwdevice"}]},
        "tracks.insert": {
            "ref": {"kind": "track", "id": "33333333-3333-3333-3333-333333333333", "name": "FE5 scratch"}
        },
        "devices.insert": {"ref": {"kind": "device", "index": 0, "name": "Compressor"}},
        "tracks.delete": {"outcome": "applied"},
    }
    # parameters.list is read for Poly Grid (T3), the Bass compressor, then the scratch compressor (fresh insert)
    (state / "parameters.list.2").write_text(
        json.dumps({"parameters": [{"name": "Threshold", "value": {"value": 0.3}}]})
    )
    (state / "parameters.list.3").write_text(
        json.dumps({"parameters": [{"name": "Threshold", "value": {"value": 0.5}}]})
    )
    for method, answer in answers.items():
        (state / method).write_text(json.dumps(answer))
    stub = bin_dir / "bitwig-agent"
    stub.write_text(
        f"""#!/usr/bin/env bash
echo "$1 $2" >> "{state}/log"
n="{state}/$1.n"; i=$(( $(cat "$n" 2>/dev/null || echo 0) + 1 )); echo $i > "$n"
if [[ -f "{state}/$1.$i" ]]; then cat "{state}/$1.$i"; else cat "{state}/$1"; fi
"""
    )
    stub.chmod(0o755)
    env = {"BWA_MAIN_TREE": str(tmp_path), "BWA_BIN_B": str(bin_dir)}
    return state, wav, answers, env


def test_verify_reads_state_through_the_condition_client(tmp_path: Path) -> None:
    state, wav, answers, env = verify_world(tmp_path)
    bass_id = "11111111-1111-1111-1111-111111111111"
    done = run_script(str(EVAL / "verify.sh"), "B", str(wav.parent), str(tmp_path / "raw"), env=env)
    assert done.returncode == 0, done.stderr
    verdict = json.loads(done.stdout)
    assert {t: verdict[t]["status"] for t in ("t1", "t2", "t3", "t4", "t5")} == dict.fromkeys(
        ("t1", "t2", "t3", "t4", "t5"), "pass"
    )
    assert verdict["transportPlayingAtEnd"] is True
    assert 'transport.set {"playing":false}' in (state / "log").read_text()
    assert verdict["t1"]["grid"] is True
    assert verdict["t1"]["devices"] == ["Poly Grid", "Compressor"]
    assert verdict["t1"]["strong"] == "pass"
    assert verdict["t1"]["goals"] == {"track": True, "sound": True, "filter": True}
    assert verdict["t2"]["goals"] == {"notes": True}
    assert verdict["t3"]["goals"] == {"cutoff": True, "resonance": True}
    assert verdict["t4"]["strong"] == "pass"
    assert verdict["t4"]["dynamicsMoved"] is True
    assert verdict["t4"]["scratchDeleted"] is True
    assert verdict["t4"]["goals"] == {"compressor": True, "sidechain": True, "dynamics": True}
    assert verdict["t5"]["lufs"] == -14.2
    assert verdict["t5"]["strong"] == "pass"
    assert (verdict["t5"]["bassRender"], verdict["t5"]["bassLufs"]) == ("exported", -14.2)
    log = (state / "log").read_text()
    export_line = log.split("export.audio ")[1].splitlines()[0]
    assert '"endBeats":32' in export_line
    assert '"sources":[{"kind":"track"' in export_line
    assert "bass-only" in export_line
    assert '{"to":{"parent":{"kind":"track"' in log.split("devices.insert ")[1].splitlines()[0]
    assert '{"target":' in log.split("devices.list ")[1].splitlines()[0]
    assert "tracks.delete" in log
    assert (tmp_path / "raw" / "tracks.json").is_file()

    (state / "devices.sidechain.get").write_text(
        json.dumps({"components": [{"source": {"kind": "pre_fader", "trackId": bass_id}, "sourceTrackName": "Bass"}]})
    )
    (state / "status").write_text(json.dumps({"projectName": "BWA Test", "instance": {"display": ":0"}}))
    refused = run_script(str(EVAL / "verify.sh"), "B", str(wav.parent), str(tmp_path / "raw2"), env=env)
    assert refused.returncode != 0
    assert "not :1" in refused.stderr

    # a Grid-only Bass: parameters.list has no filter, grid.export lists changed module params -> a human decides
    (state / "devices.sidechain.get").write_text(json.dumps(answers["devices.sidechain.get"]))
    (state / "status").write_text(json.dumps({"projectName": "BWA Test", "instance": {"display": ":1"}}))
    (state / "parameters.list").write_text(json.dumps({"parameters": [{"name": "Output", "value": {"value": 0.8}}]}))
    spec = {"modules": [{"name": "B: filter", "type": "SVF", "params": {"CUTOFF": 81.4, "RESONANCE": 0.3}}]}
    (state / "grid.export").write_text(json.dumps({"spec": spec}))
    graded = run_script(str(EVAL / "verify.sh"), "B", str(wav.parent), str(tmp_path / "raw3"), env=env)
    assert graded.returncode == 0, graded.stderr
    assert json.loads(graded.stdout)["t3"]["status"] == "review"


def verify_again(tmp_path: Path, state: Path, env: dict[str, str], name: str) -> subprocess.CompletedProcess[str]:
    for counter in state.glob("*.n"):
        counter.unlink()
    wav = tmp_path / "exports"
    return run_script(str(EVAL / "verify.sh"), "B", str(wav), str(tmp_path / name), env=env)


def test_verify_t4_compares_with_a_fresh_insert_and_field_names_flip(tmp_path: Path) -> None:
    state, _wav, answers, env = verify_world(tmp_path)
    # nothing moved from the fresh insert: the sidechain alone no longer passes the stronger check
    (state / "parameters.list.3").write_text((state / "parameters.list.2").read_text())
    unmoved = json.loads(verify_again(tmp_path, state, env, "raw4").stdout)
    assert (unmoved["t4"]["status"], unmoved["t4"]["strong"], unmoved["t4"]["dynamicsMoved"]) == ("pass", "fail", False)
    # a catalog without the device cannot give a baseline: review, not a guess
    (state / "devices.catalog").write_text(json.dumps({"devices": []}))
    assert json.loads(verify_again(tmp_path, state, env, "raw5").stdout)["t4"]["strong"] == "review"
    (state / "devices.catalog").write_text(json.dumps(answers["devices.catalog"]))
    # a master with no bass in the arranger: the Bass-only render is silent, the stricter T5 fails, the old one passes
    (state / "audio.analyze.2").write_text(json.dumps({"integratedLufs": -80.0}))
    silent = json.loads(verify_again(tmp_path, state, env, "raw7").stdout)["t5"]
    assert (silent["status"], silent["strong"], silent["bassLufs"]) == ("pass", "fail", -80.0)
    (state / "audio.analyze.2").unlink()
    (state / "export.audio").write_text(json.dumps({"code": "job_failed"}))
    failed = json.loads(verify_again(tmp_path, state, env, "raw8").stdout)["t5"]
    assert (failed["strong"], failed["bassRender"]) == ("fail", "export_failed")
    (state / "export.audio").write_text(json.dumps(answers["export.audio"]))


def test_chained_help_and_call_resolve_to_the_call() -> None:
    chained = "bitwig-agent clips launch --help | sed -n 1,14p; B='{}'\nbitwig-agent clips launch --clip=\"$B\""
    calls = metrics.parse([use("1", "Bash", command=chained), result("1", "Exit code 2\n" + BAD_ARG, True)])[0]
    assert metrics.method_of(calls[0]) == "clips.launch"
    only_help = metrics.parse([use("1", "Bash", command="bitwig-agent audio route --help")])[0]
    assert metrics.method_of(only_help[0]) is None


def test_human_verdict_replaces_only_review() -> None:
    verify = {"t3": {"status": "review"}, "t4": {"status": "fail"}}
    merged = metrics.apply_verdicts(verify, {"t3": {"status": "pass", "reason": "r"}, "t4": {"status": "pass"}})
    assert merged["t3"]["status"] == "pass"
    assert merged["t4"]["status"] == "fail"


def test_error_answer_behind_a_pipe_is_a_failure() -> None:
    old = '{"error":{"code":"invalid_params","message":"x","outcome":"not_dispatched"}}'
    events = [
        use("1", "Bash", command="bitwig-agent-rpc parameters.set value=0.4 | head -c 300"),
        result("1", old),
        use("2", "Bash", command="bitwig-agent-rpc parameters.set value=0.4 | head -c 300"),
        result("2", '{"result":{"status":"observed"}}'),
        use("3", "Bash", command="bitwig-agent tracks insert --type=x | head -c 300"),
        result("3", BAD_ARG),
    ]
    out = metrics.analyze(events)
    assert [f["index"] for f in out["failures"]] == [1, 3]
    assert out["tool_failures"] == 2
    assert out["self_corrections"] == 1


def test_segments_set_task_boundaries() -> None:
    events = [use(str(i), "Bash", command="bitwig-agent status") for i in range(1, 6)]
    out = metrics.analyze(events, segments={"1": 2, "2": 4})
    assert out["calls_by_task"] == {"pre": 1, "1": 2, "2": 2, "3": 0, "4": 0, "5": 0}
    assert out["task_split"] == "segments.json"


def test_method_calls_count_chains_and_loops_once_per_command() -> None:
    def count(command: str) -> tuple[int, bool]:
        call = metrics.parse([use("1", "Bash", command=command), result("1", "")])[0][0]
        return metrics.method_count(call), metrics.is_help_only(call)

    assert count("bitwig-agent tracks list") == (1, False)
    assert count("bitwig-agent context get; bitwig-agent tracks list && bitwig-agent help audio") == (2, False)
    assert count("for m in a b c; do bitwig-agent parameters set --x=$m; done") == (1, False)
    assert count("T=$(bitwig-agent tracks list | jq -r .x); bitwig-agent devices list --parent=$T") == (2, False)
    assert count("bitwig-agent tracks --help; bitwig-agent devices catalog --help") == (0, True)
    assert count("bitwig-agent-rpc describe tracks.create; bitwig-agent-rpc list") == (0, True)
    assert count("cat .claude/skills/bitwig-agent/references/x.md | grep sidechain") == (0, True)
    assert count("grep -rn x .claude/skills/bitwig-agent/references") == (0, True)
    assert count("python3 -c 'print(1)'") == (0, False)
    mcp = metrics.parse([use("1", "mcp__bitwig-agent__help", topic="x"), result("1", "h")])[0][0]
    assert (metrics.method_count(mcp), metrics.is_help_only(mcp)) == (0, True)
    mcp = metrics.parse([use("1", "mcp__bitwig-agent__tracks_list"), result("1", "{}")])[0][0]
    assert (metrics.method_count(mcp), metrics.is_help_only(mcp)) == (1, False)


def test_learning_bytes_in_a_mixed_call_exclude_method_answers() -> None:
    answer = '{"tracks": [\n  {"name": "Drums"}\n]}\n'
    events = [
        use("1", "Bash", command="bitwig-agent --help; bitwig-agent tracks list"),
        result("1", "usage: bitwig-agent\n" + answer),
        use("2", "Bash", command="bitwig-agent tracks insert --help"),
        result("2", "help text\n"),
        use("3", "Bash", command="bitwig-agent tracks list"),
        result("3", answer),
    ]
    out = metrics.analyze(events)
    assert out["learning_bytes"] == len("usage: bitwig-agent\n") + len("help text\n")
    assert out["learning_bytes_whole_call"] == len("usage: bitwig-agent\n" + answer) + len("help text\n")
    assert (out["calls"], out["method_calls"], out["help_only_calls"]) == (3, 2, 1)


def test_refusal_is_corrected_only_by_the_next_call_of_its_method() -> None:
    events = [
        use("1", "Bash", command="bitwig-agent tracks insert --type=bass"),
        result("1", f"Exit code 2\n{BAD_ARG}", True),
        use("2", "Bash", command="bitwig-agent tracks insert --type=instrument"),
        result("2", "{}"),
        use("3", "Bash", command="bitwig-agent parameters set --x=1"),
        result("3", f"Exit code 2\n{BAD_ARG}", True),
        use("4", "Bash", command="bitwig-agent parameters set --x=2"),
        result("4", f"Exit code 2\n{BAD_ARG}", True),
        use("5", "Bash", command="bitwig-agent parameters set --x=3"),
        result("5", "{}"),
    ]
    out = metrics.analyze(events)
    assert {f["index"]: f["followup_corrected"] for f in out["failures"]} == {1: True, 3: False, 4: True}
    assert (out["refusals"], out["followup_corrected"]) == (3, 2)


def verify_for(**tasks: dict[str, Any]) -> dict[str, Any]:
    base: dict[str, dict[str, Any]] = {f"t{t}": {"status": "pass"} for t in range(1, 6)}
    base["t1"] = {"status": "pass", "grid": True, "devices": ["Poly Grid"], "strong": "pass"}
    base["t4"] = {"status": "pass", "strong": "pass"}
    base["t5"] = {"status": "pass", "strong": "pass"}
    return base | tasks


def synthetic(condition: str, number: int, **spec: Any) -> Any:
    """A run with `methods`, `refusals`, `corrected`, `task` (of the refusals), `evidence`, `override`; other keys are verify tasks."""
    methods, refusals, corrected, task = (
        spec.pop(k, d) for k, d in (("methods", 40), ("refusals", 2), ("corrected", True), ("task", "2"))
    )
    evidence, override = spec.pop("evidence", None), spec.pop("override", None)
    verify: dict[str, dict[str, Any]] = spec
    failures = [
        {
            "index": i + 1,
            "label": "tool",
            "task": task,
            "method": "m",
            "label_source": "heuristic",
            "message": "e",
            "followup_corrected": corrected,
        }
        for i in range(refusals)
    ]
    per_task = {"pre": 0, "1": 5, "2": 5, "3": 5, "4": 5, "5": 5}
    per_task["pre"] = methods - 25
    return metrics.Run(
        condition,
        number,
        {},
        {
            "model": "m",
            "calls": methods,
            "method_calls": methods,
            "help_only_calls": 2,
            "refusals": refusals,
            "followup_corrected": refusals if corrected else 0,
            "failures": failures,
            "tool_failures": refusals,
            "agent_failures": 0,
            "self_correction_rate": 1.0 if refusals else None,
            "abandoned_methods": [],
            "calls_by_task": per_task,
            "method_calls_by_task": per_task,
            "task_split": "markers",
            "learning_bytes": 1,
            "learning_bytes_whole_call": 2,
            "total_read_bytes": 3,
            "violations": [],
            "evidence": {"t2.playing": True, "t5.measured": True} | (evidence or {}),
        },
        verify_for(**verify),
        override or {},
    )


def test_lost_request_is_an_undone_subgoal_after_a_refusal() -> None:
    notes_goals = {"status": "pass", "goals": {"notes": True}}
    # refusal in T2, clip never played: lost
    run = synthetic("B", 1, evidence={"t2.playing": False}, t2=notes_goals)
    assert [entry["goal"] for entry in metrics.lost_requests(run)] == ["t2.playing"]
    # the same sub-goal ending done is not lost
    assert metrics.lost_requests(synthetic("B", 1, t2=notes_goals)) == []
    # an undone sub-goal with no refusal in its task is a verify failure, not a lost request
    quiet = synthetic("B", 1, refusals=0, evidence={"t2.playing": False}, t2=notes_goals)
    assert metrics.lost_requests(quiet) == []
    # T4 goals come from verify
    t4 = {"status": "pass", "strong": "fail", "goals": {"compressor": True, "sidechain": True, "dynamics": False}}
    assert [e["goal"] for e in metrics.lost_requests(synthetic("B", 1, task="4", t4=t4))] == ["t4.dynamics"]
    # a task without goals (older verify) is one goal named after its status
    failed = synthetic("B", 1, task="3", t3={"status": "fail"})
    assert [e["goal"] for e in metrics.lost_requests(failed)] == ["t3.task"]
    # a Grid counts only when a refused T1 call asked for one
    asked = synthetic("B", 1, task="1", t1={"status": "pass", "grid": False, "goals": {"track": True}})
    assert metrics.lost_requests(asked) == []
    asked.metrics["failures"][0]["asked_grid"] = True
    assert [e["goal"] for e in metrics.lost_requests(asked)] == ["t1.grid"]
    # manual override both ways
    forced = synthetic("B", 1, evidence={"t2.playing": False}, t2=notes_goals, override={"t2.playing": False})
    assert metrics.lost_requests(forced) == []
    forced = synthetic("B", 1, refusals=0, override={"t5.measured": True})
    assert [(e["goal"], e["source"]) for e in metrics.lost_requests(forced)] == [("t5.measured", "override")]


def cohort(condition: str, **each: Any) -> list[Any]:
    return [synthetic(condition, n, **each) for n in range(1, 5)]


def test_preregistered_verdict_on_synthetic_runs() -> None:
    a_runs = cohort("A", methods=40, refusals=3, t1={"status": "pass", "grid": True, "strong": "pass"})
    good = cohort("B", methods=38, refusals=2)
    assert metrics.preregistered(a_runs + good, "B") == dict.fromkeys(
        (
            "runs_ge_4",
            "stronger_verify",
            "no_lost_requests",
            "refusals_le_A",
            "refusals_corrected",
            "method_calls_le_A",
        ),
        True,
    )
    text = metrics.report(a_runs + good)
    assert "## Verdict (a)" in text
    assert "## Verdict (b)" in text
    assert "| B | ok | ok | ok | ok | ok | ok | PASS |" in text
    # each criterion fails on its own
    assert metrics.preregistered(a_runs + good[:3], "B")["runs_ge_4"] is False
    weak = [*good[:3], synthetic("B", 4, t4={"status": "pass", "strong": "fail"})]
    assert metrics.preregistered(a_runs + weak, "B")["stronger_verify"] is False
    pending = [*good[:3], synthetic("B", 4, t3={"status": "review"})]
    assert metrics.preregistered(a_runs + pending, "B")["stronger_verify"] is None
    lost = [*good[:3], synthetic("B", 4, evidence={"t2.playing": False})]
    assert metrics.preregistered(a_runs + lost, "B")["no_lost_requests"] is False
    assert metrics.preregistered(a_runs + cohort("B", refusals=4), "B")["refusals_le_A"] is False
    assert metrics.preregistered(a_runs + cohort("B", corrected=False), "B")["refusals_corrected"] is False
    assert metrics.preregistered(a_runs + cohort("B", methods=41), "B")["method_calls_le_A"] is False
    # one slow run beats the median but not the maximum
    spiky = [*cohort("B", methods=30)[:3], synthetic("B", 4, methods=45)]
    assert metrics.preregistered(a_runs + spiky, "B")["method_calls_le_A"] is False


def test_preregistered_method_calls_leave_out_t1_when_an_a_run_has_no_grid() -> None:
    no_grid = {"status": "pass", "grid": False, "strong": "fail"}
    a_runs = cohort("A", methods=30, t1=no_grid)
    # B spends 8 more method calls than A in T1 only; with a non-Grid A that must not count against it
    b_runs = [synthetic("B", n, methods=30) for n in range(1, 5)]
    for run in b_runs:
        run.metrics["method_calls_by_task"] = {**run.metrics["method_calls_by_task"], "1": 13}
        run.metrics["method_calls"] += 8
    assert metrics.preregistered(a_runs + b_runs, "B")["method_calls_le_A"] is True
    all_grid = cohort("A", methods=30)
    assert metrics.preregistered(all_grid + b_runs, "B")["method_calls_le_A"] is False
    assert "T1 left out of the method-call comparison: yes" in metrics.report(a_runs + b_runs)


def test_round_one_style_verify_has_no_strong_data() -> None:
    old = synthetic("B", 1)
    old.verify = {f"t{t}": {"status": "pass"} for t in range(1, 6)}
    assert metrics.strong_label(old) == "n/a"
    assert metrics.strong_passes(old) is None
    assert metrics.lost_requests(old) == []
