#!/usr/bin/env python3
"""Metrics from one `claude -p --output-format stream-json --verbose` transcript, and the cross-run report.

  metrics.py run.jsonl [--labels labels.json] [--out metrics.json]   one run -> JSON
  metrics.py --report OUT_DIR                                        every run under OUT_DIR -> markdown

Counted from the transcript, not from the agent's own account:
  calls            every tool call (tool-use block), by tool and by task
  method calls     every non-help `bitwig-agent ...` command inside a shell call plus every non-help MCP call. A
                   chained shell call counts each command; a loop counts its body once per textual occurrence,
                   not once per iteration (a loop is one tool call and, textually, one command)
  help-only calls  tool calls that only learn: help, Skill, skills/** reads, MCP `help` (no method call in them)
  learning bytes   result bytes of `--help`/`help`/old `list`/`describe`/MCP `help` calls, plus everything read
                   from skills/** (Read, a shell cat/head/sed/grep, the Skill tool and the text it injects). In a
                   shell call that mixes learning with method calls the output cannot be split by command, so only
                   output outside JSON blocks counts (method answers are JSON, help text is not); the whole-call
                   bytes of such calls are kept as learning_bytes_whole_call, the upper bound
  total read bytes result bytes of every tool call
  segments.json    optional per run {"1": first call index of task 1, ...}, when the agent skipped or misplaced markers
  verdicts.json    optional per run {"t3": {"status": "pass"|"fail", "reason": ...}}; replaces a `review` only
  failures         a Bash call with exit != 0, or any call with is_error; each labelled `tool` (the Bitwig tool or
                   host answered with an error) or `agent` (the agent's own shell script, file or command broke)
  self-correction  (original criterion, unchanged) a tool-caused failure followed, within the next
                   CORRECTION_WINDOW calls, by a success of the same method; rate = corrected / tool-caused
                   failures; a method that never succeeds later is abandoned
  followup-corrected  (pre-registered criterion) a refusal whose next later call of the same method succeeds
  evidence         transcript facts a verify cannot read back: the launcher clip was played (a launch call or
                   transport playing=true succeeded) and a loudness analysis ran (audio.analyze succeeded)
  per-task calls   calls are assigned to the task whose `=== TASK n ===` line the agent wrote last (calls before
                   the first marker are `pre`); with no marker at all, to the task whose method family the call
                   uses (see TASK_METHODS), and `task_split` says which rule applied

The failure label is a heuristic (AGENT_SIGNS, BITWIG_ERROR); a labels file maps a 1-based call index to
"tool" or "agent" (or {"label": .., "method": ..} to also fix the failing method of a chained call) and overrides it,
recorded as label_source "override".

Lost requests (a requested sub-goal undone after a tool refusal) and the two verdicts are computed at report time
from verify.json goals; `<run>/lost.json` {"t2.playing": false|true} forces a goal to not-lost|lost.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import statistics
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

TASKS = (1, 2, 3, 4, 5)
CORRECTION_WINDOW = 4
MIN_RUNS = 4  # pre-registered: runs listed per condition
STRONG_TASKS = (1, 4, 5)  # the tasks whose stricter check is a separate `strong` field of verify.json
MAX_WORDS = 3
OLD_BINARY = "bitwig-agent-rpc"
OLD_LEARNING = ("list", "describe")
MARKER = re.compile(r"===\s*TASK\s*([1-5])\s*===", re.IGNORECASE)
EXIT_CODE = re.compile(r"^Exit code (\d+)")
BITWIG_BIN = re.compile(r"(?:^|[\s;&|(])(?:\S*/)?(bitwig-agent-rpc|bitwig-agent)(?=\s|$)(.*)", re.DOTALL)
SEGMENT = re.compile(r";|&&|\|\||\||&|\n|\$\(|`")
PLAY_METHODS = ("clips.launch", "launcher.launch", "scenes.launch")
PLAYING_TRUE = re.compile(r"playing[\"']?\s*[:=]\s*true", re.IGNORECASE)
WORD = re.compile(r"[A-Za-z][A-Za-z0-9_.]*")
SKILL_READ = re.compile(r"\b(?:cat|head|tail|sed|less|bat|grep|rg|awk)\b[^|;&\n]*skills/")
VIOLATION = re.compile(
    r"src/bitwig_agent|site-packages|PROTOCOL\.md|USAGE\.md|VERIFICATION\.md|CONTRIBUTING\.md|/docs/|git\s+(?:log|show|grep)"
)
# A bitwig-agent error answer on its own line: the CLI's {"code":..,"reason":..} or 0.4's {"error":{"code":..}}.
# Agents pipe calls through `| head`, which turns the failing exit code into 0, so the text is checked as well.
ERROR_LINE = re.compile(r'^\{"(?:code"\s*:\s*"[a-z_]+"\s*,\s*"reason"|error"\s*:\s*\{"code")', re.MULTILINE)
AGENT_SIGNS = re.compile(
    r"jq: (?:error|\d+ compile)|parse error|Traceback \(most recent|command not found|syntax error|unexpected (?:EOF|token)"
    r"|No such file or directory|: line \d+:|JSONDecodeError|bad substitution|Permission denied",
    re.IGNORECASE,
)
BITWIG_ERROR = re.compile(r'"code"\s*:\s*"[a-z_]+"|"outcome"\s*:\s*"(?:not_applied|unknown)"|"reason"\s*:')
TASK_METHODS = {
    1: ("tracks.insert", "tracks.create", "devices.insert", "devices.catalog", "grid."),
    2: ("notes.", "clips.", "launcher.", "scenes."),
    3: ("parameters.",),
    4: ("devices.sidechain",),
    5: ("export.", "audio.capture", "audio.analyze"),
}


@dataclass
class Call:
    index: int
    id: str
    tool: str
    input: dict[str, Any]
    task: str
    text: str = ""
    is_error: bool = False
    extra_bytes: int = 0  # text the Skill tool injected after its own result

    @property
    def command(self) -> str:
        return str(self.input.get("command", "")) if self.tool == "Bash" else ""

    @property
    def exit_code(self) -> int:
        found = EXIT_CODE.match(self.text)
        return int(found.group(1)) if found else (1 if self.is_error else 0)

    @property
    def masked_error(self) -> bool:
        """A Bitwig error answer inside a Bash call whose exit status was hidden by a pipe."""
        return self.tool == "Bash" and self.exit_code == 0 and bool(ERROR_LINE.search(self.text))

    @property
    def failed(self) -> bool:
        return self.is_error or self.exit_code != 0 or self.masked_error

    @property
    def bytes(self) -> int:
        return len(self.text.encode()) + self.extra_bytes


def load(path: Path) -> list[dict[str, Any]]:
    events = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{number}: not JSON: {exc.msg}") from exc
    return events


def flatten(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(block.get("text", "") if isinstance(block, dict) else str(block) for block in content)
    return ""


def parse(events: Iterable[dict[str, Any]]) -> tuple[list[Call], bool]:
    """Calls in order with their result, and whether the agent wrote any task marker."""
    calls: list[Call] = []
    by_id: dict[str, Call] = {}
    task, marked, after_skill = "pre", False, None
    for event in events:
        message = event.get("message") or {}
        content = message.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if not isinstance(block, dict):
                continue
            kind = block.get("type")
            if event.get("type") == "assistant" and kind == "text":
                found = MARKER.findall(block.get("text", ""))
                if found:
                    task, marked = found[-1], True
            elif event.get("type") == "assistant" and kind == "tool_use":
                call = Call(len(calls) + 1, block.get("id", ""), block.get("name", ""), block.get("input") or {}, task)
                calls.append(call)
                by_id[call.id] = call
                after_skill = None
            elif event.get("type") == "user" and kind == "tool_result":
                call = by_id.get(block.get("tool_use_id", ""))
                if call:
                    call.text = flatten(block.get("content"))
                    call.is_error = bool(block.get("is_error"))
                    after_skill = call if call.tool == "Skill" else None
            elif event.get("type") == "user" and kind == "text" and after_skill is not None:
                after_skill.extra_bytes += len(block.get("text", "").encode())
    return calls, marked


def invocation(command: str) -> tuple[str, list[str], list[str]] | None:
    """(binary, leading words, all tokens) of the first bitwig-agent[-rpc] call; words stop at a flag, key=value or operand."""
    found = BITWIG_BIN.search(command)
    if not found:
        return None
    tail = re.split(r"[|;&\n]", found.group(2))[0]
    try:
        tokens = shlex.split(tail)
    except ValueError:
        tokens = tail.split()
    words: list[str] = []
    for token in tokens:
        if not WORD.fullmatch(token) or len(words) == MAX_WORDS:
            break
        words.append(token)
    return found.group(1), words, tokens


def is_help(parsed: tuple[str, list[str], list[str]]) -> bool:
    binary, words, tokens = parsed
    first = words[0] if words else ""
    return bool({"--help", "-h"} & set(tokens)) or first == "help" or (binary == OLD_BINARY and first in OLD_LEARNING)


def invocations(command: str) -> list[tuple[str, list[str], list[str]]]:
    """Every bitwig-agent[-rpc] command of a command line, one per shell segment (`;`, `&&`, `|`, `$(`, newline), in order."""
    found = (invocation(segment) for segment in SEGMENT.split(command))
    return [parsed for parsed in found if parsed is not None]


def asks_for_help(call: Call) -> bool:
    return any(is_help(parsed) for parsed in invocations(call.command))


def methods_of(call: Call) -> list[str]:
    """Methods of every non-help bitwig-agent call in the command, in order (an agent often chains several)."""
    if call.tool.startswith("mcp__"):
        if call.tool.endswith("__help"):
            return []
        return [
            call.tool.split("__", 2)[-1].replace("_", ".")
        ]  # MCP tools are named tracks_insert, methods tracks.insert
    return [".".join(parsed[1]) for parsed in invocations(call.command) if parsed[1] and not is_help(parsed)]


def method_count(call: Call) -> int:
    return len(methods_of(call))


def is_help_only(call: Call) -> bool:
    """A learning call with no method call in it."""
    return is_learning(call) and method_count(call) == 0


def block_free_bytes(text: str) -> int:
    """Bytes of the output outside JSON blocks: in a call that mixes help with method calls, what is not an answer."""
    depth, kept = 0, 0
    for line in text.splitlines(keepends=True):
        bare = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
        inside = depth > 0 or line.lstrip().startswith(("{", "["))
        depth = max(0, depth + sum(bare.count(c) for c in "{[") - sum(bare.count(c) for c in "}]"))
        if not inside:
            kept += len(line.encode())
    return kept


def learning_bytes(call: Call) -> int:
    """Result bytes that are learning; for a mixed shell call only the non-JSON output (an approximation)."""
    if not is_learning(call):
        return 0
    if method_count(call) == 0:
        return call.bytes
    return block_free_bytes(call.text)


def method_of(call: Call) -> str | None:
    """The call's method; for a chain, the failing one when the error text names it, else the last."""
    methods = methods_of(call)
    if not methods:
        return None
    named = [m for m in methods if call.failed and re.search(rf"\b{re.escape(m)}\b", error_text(call))]
    return named[-1] if named else methods[-1]


def error_text(call: Call) -> str:
    return "\n".join(line for line in call.text.splitlines() if ERROR_LINE.match(line) or line.startswith("Exit code"))


def is_learning(call: Call) -> bool:
    if call.tool == "Skill":
        return True
    if call.tool == "Read":
        return "/skills/" in str(call.input.get("file_path", ""))
    if call.tool.startswith("mcp__"):
        return call.tool.endswith("__help")
    return bool(SKILL_READ.search(call.command)) or asks_for_help(call)


def played(call: Call) -> bool:
    """A call that starts playback: a launch method, or transport playing=true (CLI flag, JSON or MCP argument)."""
    if call.failed:
        return False
    if any(m in PLAY_METHODS for m in methods_of(call)):
        return True
    return "transport.set" in methods_of(call) and bool(PLAYING_TRUE.search(call.command or json.dumps(call.input)))


def violation(call: Call) -> str | None:
    if call.tool == "Read" and "/skills/" not in str(call.input.get("file_path", "")):
        return f"Read of {call.input.get('file_path')}"
    if call.command and VIOLATION.search(call.command):
        return "Bash command touches repository source or docs"
    return None


def heuristic_label(call: Call) -> str:
    if call.tool.startswith("mcp__"):
        return "tool"
    if call.tool != "Bash" or invocation(call.command) is None:
        return "agent"
    if AGENT_SIGNS.search(call.text) and not BITWIG_ERROR.search(call.text):
        return "agent"
    return "tool" if BITWIG_ERROR.search(call.text) or call.exit_code in (1, 2, 3) else "agent"


def keyword_task(call: Call) -> str:
    method = method_of(call) or ""
    for task, prefixes in TASK_METHODS.items():
        if method.startswith(prefixes):
            return str(task)
    return "pre"


def self_corrections(calls: list[Call], failures: list[dict[str, Any]]) -> tuple[int, list[str]]:
    corrected, abandoned = 0, []
    for failure in failures:
        if failure["label"] != "tool" or not failure["method"]:
            continue
        later = [c for c in calls[failure["index"] :] if not c.failed and failure["method"] in methods_of(c)]
        near = [c for c in calls[failure["index"] : failure["index"] + CORRECTION_WINDOW] if c in later]
        failure["self_corrected"] = bool(near)
        corrected += bool(near)
        if not later:
            abandoned.append(failure["method"])
    return corrected, sorted(set(abandoned))


def followup_corrections(calls: list[Call], failures: list[dict[str, Any]]) -> int:
    """Mark each tool-caused failure `followup_corrected` when the next later call of its method succeeds."""
    done = 0
    for failure in failures:
        later = [c for c in calls[failure["index"] :] if failure["method"] and failure["method"] in methods_of(c)]
        failure["followup_corrected"] = bool(later) and not later[0].failed
        done += failure["followup_corrected"]
    return done


def apply_segments(calls: list[Call], segments: dict[str, int]) -> None:
    """Hand-set task boundaries {"1": first call index of task 1, ...} for a run whose markers are missing or misplaced."""
    starts = sorted((first, task) for task, first in segments.items())
    for call in calls:
        call.task = "pre"
        for first, task in starts:
            if call.index >= first:
                call.task = task


def analyze(
    events: Iterable[dict[str, Any]],
    overrides: dict[str, Any] | None = None,
    segments: dict[str, int] | None = None,
) -> dict[str, Any]:
    events = list(events)
    calls, marked = parse(events)
    if segments:
        apply_segments(calls, segments)
    elif not marked:
        for call in calls:
            call.task = keyword_task(call)
    failures = []
    for call in (c for c in calls if c.failed):
        override = (overrides or {}).get(str(call.index))
        decided = override if isinstance(override, dict) else {"label": override}
        failures.append(
            {
                "index": call.index,
                "tool": call.tool,
                "method": decided.get("method") or method_of(call),
                "task": call.task,
                "exit": call.exit_code,
                "label": decided.get("label") or heuristic_label(call),
                "label_source": "override" if override else "heuristic",
                "message": call.text.strip()[:200],
            }
        )
    tool_failures = [f for f in failures if f["label"] == "tool"]
    corrected, abandoned = self_corrections(calls, tool_failures)
    followup = followup_corrections(calls, tool_failures)
    for failure in tool_failures:
        failure["asked_grid"] = "grid" in json.dumps(calls[failure["index"] - 1].input).lower()
    by_tool: dict[str, int] = {}
    for call in calls:
        by_tool[call.tool] = by_tool.get(call.tool, 0) + 1
    result = next((e for e in events if e.get("type") == "result"), {})
    init = next((e for e in events if e.get("subtype") == "init"), {})
    return {
        "model": init.get("model"),
        "calls": len(calls),
        "method_calls": sum(method_count(c) for c in calls),
        "help_only_calls": sum(is_help_only(c) for c in calls),
        "method_calls_by_task": {
            t: sum(method_count(c) for c in calls if c.task == t) for t in ("pre", *map(str, TASKS))
        },
        "calls_by_tool": by_tool,
        "calls_by_task": {t: sum(1 for c in calls if c.task == t) for t in ("pre", *map(str, TASKS))},
        "task_split": "segments.json" if segments else "markers" if marked else "keywords",
        "skill_injected_bytes": sum(c.extra_bytes for c in calls if c.tool == "Skill"),
        "learning_calls": sum(is_learning(c) for c in calls),
        "learning_bytes": sum(learning_bytes(c) for c in calls),
        "learning_bytes_whole_call": sum(c.bytes for c in calls if is_learning(c)),
        "total_read_bytes": sum(c.bytes for c in calls),
        "failures": failures,
        "tool_failures": len(tool_failures),
        "agent_failures": len(failures) - len(tool_failures),
        "self_corrections": corrected,
        "self_correction_rate": corrected / len(tool_failures) if tool_failures else None,
        "refusals": len(tool_failures),
        "followup_corrected": followup,
        "evidence": {
            "t2.playing": any(played(c) for c in calls),
            "t5.measured": any(not c.failed and "audio.analyze" in methods_of(c) for c in calls),
        },
        "abandoned_methods": abandoned,
        "violations": [{"index": c.index, "what": v} for c in calls if (v := violation(c))],
        "turns": result.get("num_turns"),
        "duration_ms": result.get("duration_ms"),
        "cost_usd": result.get("total_cost_usd"),
    }


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


@dataclass
class Run:
    condition: str
    run: int
    meta: dict[str, Any]
    metrics: dict[str, Any]
    verify: dict[str, Any]
    lost_override: dict[str, bool]


def apply_verdicts(verify: dict[str, Any], decided: dict[str, Any]) -> dict[str, Any]:
    """A human decision for a task verify.sh left at `review`: {"t3": {"status": "pass", "reason": "..."}}."""
    merged = dict(verify)
    for task, decision in decided.items():
        if (merged.get(task) or {}).get("status") == "review":
            merged[task] = {**merged[task], **decision, "decided": True}
    return merged


def collect(out_dir: Path) -> list[Run]:
    runs = []
    for directory in sorted(out_dir.glob("[ABC]/run*")):
        metrics = read_json(directory / "metrics.json")
        if metrics is None:
            continue
        runs.append(
            Run(
                directory.parent.name,
                int(directory.name.removeprefix("run")),
                read_json(directory / "meta.json") or {},
                metrics,
                apply_verdicts(
                    read_json(directory / "verify.json") or {}, read_json(directory / "verdicts.json") or {}
                ),
                read_json(directory / "lost.json") or {},
            )
        )
    return runs


def task_status(run: Run, task: int) -> str:
    return str((run.verify.get(f"t{task}") or {}).get("status", "missing"))


def strong_status(run: Run, task: int) -> str:
    """The stricter verify of a task: its `strong` field where verify.sh wrote one (T1, T4, T5), else its status;
    `no data` for T1/T4/T5 of a verify from before the stricter checks existed."""
    found = run.verify.get(f"t{task}") or {}
    if task in STRONG_TASKS and "strong" not in found:
        return "no data"
    return str(found.get("strong") or found.get("status", "missing"))


def strong_passes(run: Run) -> bool | None:
    """True when every task passes the stricter verify, False when one fails, None while one is `review` or has no data."""
    statuses = [strong_status(run, t) for t in TASKS]
    if any(s not in ("pass", "review", "no data") for s in statuses):
        return False
    return None if set(statuses) & {"review", "no data"} else True


def strong_label(run: Run) -> str:
    if any(strong_status(run, t) == "no data" for t in STRONG_TASKS):
        return "n/a"
    return {True: "pass", False: "fail", None: "review"}[strong_passes(run)]


def goal_states(run: Run) -> dict[str, bool]:
    """Sub-goal -> done, from verify.json goals plus transcript evidence. A task without goals (or decided by a human,
    or a verify from before goals existed) is one goal named `<task>.task`; `review` counts as done, no data is skipped."""
    states: dict[str, bool] = {}
    for task in TASKS:
        found = run.verify.get(f"t{task}") or {}
        goals = found.get("goals")
        if found.get("decided") or not isinstance(goals, dict):
            if found.get("status") in ("pass", "review", "fail"):
                states[f"t{task}.task"] = found["status"] != "fail"
        else:
            states.update({f"t{task}.{name}": bool(done) for name, done in goals.items()})
    for goal, done in (run.metrics.get("evidence") or {}).items():
        states[goal] = bool(done)
    if any(f["label"] == "tool" and f["task"] == "1" and f.get("asked_grid") for f in run.metrics["failures"]):
        states["t1.grid"] = (run.verify.get("t1") or {}).get("grid") is True
    return states


def lost_requests(run: Run) -> list[dict[str, Any]]:
    """Sub-goals undone at the end in a task where a tool refusal happened, minus/plus `lost.json` overrides."""
    refusals: dict[str, list[int]] = {}
    for failure in run.metrics["failures"]:
        if failure["label"] == "tool":
            refusals.setdefault(failure["task"], []).append(failure["index"])
    lost = [
        {"goal": goal, "refusals": refusals[goal[1]], "source": "verify"}
        for goal, done in goal_states(run).items()
        if not done and goal[1] in refusals
    ]
    for goal, forced in run.lost_override.items():
        lost = [entry for entry in lost if entry["goal"] != goal]
        if forced:
            lost.append({"goal": goal, "refusals": refusals.get(goal[1], []), "source": "override"})
    return lost


def used_grid(run: Run) -> bool:
    return (run.verify.get("t1") or {}).get("grid") is True


def t1_device(run: Run) -> str:
    found = run.verify.get("t1") or {}
    names = [str(n) for n in found.get("devices") or []]
    if names:
        return next((n for n in names if "grid" in n.lower()), names[0])
    return {True: "Grid", False: "not Grid"}.get(found.get("grid"), "?")


def counted_method_calls(run: Run, leave_out_t1: bool) -> int:
    by_task = run.metrics["method_calls_by_task"]
    return sum(v for k, v in by_task.items() if not (leave_out_t1 and k == "1"))


def criteria(run: Run, baseline: list[Run]) -> dict[str, bool | None]:
    """The design's original per-run criteria (section 6) for B and C against A's matching run and A's per-task median."""
    match = next((a for a in baseline if a.run == run.run), baseline[-1])
    own, theirs = run.metrics["self_correction_rate"], match.metrics["self_correction_rate"]
    statuses = [task_status(run, t) for t in TASKS]
    medians = [statistics.median(a.metrics["calls_by_task"][str(t)] for a in baseline) for t in TASKS]
    return {
        "t1_used_grid": (run.verify.get("t1") or {}).get("grid"),
        "tool_failures_le_A": run.metrics["tool_failures"] <= match.metrics["tool_failures"],
        "all_tasks_done": None
        if "review" in statuses and "fail" not in statuses and "missing" not in statuses
        else all(s == "pass" for s in statuses),
        "self_correction_ge_A": (own is None or theirs is None or own >= theirs)
        and not run.metrics["abandoned_methods"],
        "calls_le_A_median": all(
            run.metrics["calls_by_task"][str(t)] <= m for t, m in zip(TASKS, medians, strict=True)
        ),
    }


def verdict(values: dict[str, bool | None]) -> str:
    if any(v is False for v in values.values()):
        return "FAIL"
    return "REVIEW" if any(v is None for v in values.values()) else "PASS"


def preregistered(runs: list[Run], condition: str) -> dict[str, bool | None]:
    """The pre-registered criterion (README.md) for one of B and C against A; None = needs a human or has no data."""
    baseline = [r for r in runs if r.condition == "A"]
    mine = [r for r in runs if r.condition == condition]
    if not baseline or not mine:
        return {"runs_ge_4": False}
    leave_out_t1 = not all(used_grid(a) for a in baseline)
    refusals = [r.metrics["refusals"] for r in mine]
    base_refusals = [a.metrics["refusals"] for a in baseline]
    methods = [counted_method_calls(r, leave_out_t1) for r in mine]
    base_methods = [counted_method_calls(a, leave_out_t1) for a in baseline]
    strong = [strong_passes(r) for r in mine]
    return {
        "runs_ge_4": len(baseline) >= MIN_RUNS and len(mine) >= MIN_RUNS,
        "stronger_verify": False
        if any(s is False for s in strong)
        else None
        if any(s is None for s in strong)
        else True,
        "no_lost_requests": not any(lost_requests(r) for r in mine),
        "refusals_le_A": statistics.median(refusals) <= statistics.median(base_refusals)
        and max(refusals) <= max(base_refusals),
        "refusals_corrected": all(
            f.get("followup_corrected") for r in mine for f in r.metrics["failures"] if f["label"] == "tool"
        ),
        "method_calls_le_A": statistics.median(methods) <= statistics.median(base_methods)
        and max(methods) <= max(base_methods),
    }


def mark(value: bool | None) -> str:
    return {True: "ok", False: "FAIL", None: "review"}[value]


def table(headers: list[str], rows: list[list[Any]]) -> list[str]:
    cells = [headers, *[["" if c is None else str(c) for c in row] for row in rows]]
    return ["| " + " | ".join(row) + " |" for row in (cells[0], ["---"] * len(headers), *cells[1:])]


def report(runs: list[Run]) -> str:
    baseline = [r for r in runs if r.condition == "A"]
    lines = ["## Runs", ""]
    lines += table(
        [
            "Cond",
            "Run",
            "Tool calls",
            "Method calls",
            "Help-only calls",
            "Refusals (tool)",
            "Corrected in 1 follow-up",
            "Lost requests",
            "T1 device",
            "T1",
            "T2",
            "T3",
            "T4",
            "T5",
            "Strong verify",
        ],
        [
            [
                r.condition,
                r.run,
                r.metrics["calls"],
                r.metrics["method_calls"],
                r.metrics["help_only_calls"],
                r.metrics["refusals"],
                r.metrics["followup_corrected"],
                ", ".join(entry["goal"] for entry in lost_requests(r)) or 0,
                t1_device(r),
                *(task_status(r, t) for t in TASKS),
                strong_label(r),
            ]
            for r in runs
        ],
    )
    lines += ["", "## Bytes, cost and tool failures (recorded, not judged)", ""]
    lines += table(
        [
            "Cond",
            "Run",
            "Commit",
            "Model",
            "Skill B",
            "Skill injected B",
            "tools/list B",
            "Learning B (help only)",
            "Learning B (whole calls)",
            "Read B",
            "Cost USD",
            "Agent fail",
            "Self-corr (orig.)",
        ],
        [
            [
                r.condition,
                r.run,
                r.meta.get("commit"),
                r.meta.get("model") or r.metrics["model"],
                r.meta.get("skill_bytes"),
                r.metrics.get("skill_injected_bytes"),
                r.meta.get("tools_list_bytes"),
                r.metrics["learning_bytes"],
                r.metrics.get("learning_bytes_whole_call"),
                r.metrics["total_read_bytes"],
                r.metrics.get("cost_usd"),
                r.metrics["agent_failures"],
                "n/a" if r.metrics["self_correction_rate"] is None else f"{r.metrics['self_correction_rate']:.2f}",
            ]
            for r in runs
        ],
    )
    lines += ["", "## Per-task calls (diagnostic only; tool calls / method calls)", ""]
    lines += table(
        ["Cond", "Run", "pre", *(f"T{t}" for t in TASKS), "split"],
        [
            [
                r.condition,
                r.run,
                *(
                    f"{r.metrics['calls_by_task'][k]} / {r.metrics['method_calls_by_task'][k]}"
                    for k in ("pre", *map(str, TASKS))
                ),
                r.metrics["task_split"],
            ]
            for r in runs
        ],
    )
    if baseline:
        lines += [
            "",
            "## Verdict (a): original criteria, as implemented in round 1 (B and C, every run, against A)",
            "",
        ]
        rows = []
        for r in (r for r in runs if r.condition != "A"):
            values = criteria(r, baseline)
            rows.append([r.condition, r.run, *(mark(v) for v in values.values()), verdict(values)])
        lines += table(
            ["Cond", "Run", "T1 Grid", "Tool fail <= A", "All tasks", "Self-corr >= A", "Calls <= A median", "Verdict"],
            rows,
        )
        leave_out_t1 = not all(used_grid(a) for a in baseline)
        lines += [
            "",
            "## Verdict (b): pre-registered criterion (eval/front-ends/README.md)",
            "",
            f"T1 left out of the method-call comparison: {'yes (an A run did not use a Grid)' if leave_out_t1 else 'no'}."
            " Medians and maxima are over the runs listed above.",
            "",
        ]
        rows = []
        for condition in sorted({r.condition for r in runs} - {"A"}):
            values = preregistered(runs, condition)
            rows.append([condition, *(mark(v) for v in values.values()), verdict(values)])
        lines += table(
            [
                "Cond",
                ">= 4 runs",
                "Stronger verify",
                "No lost requests",
                "Refusals <= A (median, max)",
                "Refusals corrected",
                "Method calls <= A (median, max)",
                "Verdict",
            ],
            rows,
        )
    failures = [
        [
            r.condition,
            r.run,
            f["index"],
            f["method"],
            f["task"],
            f["label"],
            f["label_source"],
            f.get("followup_corrected", ""),
            f["message"][:80].replace("|", "/").replace("\n", " "),
        ]
        for r in runs
        for f in r.metrics["failures"]
    ]
    lines += [
        "",
        "## Failed calls",
        "",
        *table(["Cond", "Run", "Call", "Method", "Task", "Cause", "Source", "Corrected in 1", "Message"], failures),
    ]
    notes = [
        f"- {r.condition} run {r.run}: " + "; ".join(v["what"] for v in r.metrics["violations"])
        for r in runs
        if r.metrics["violations"]
    ]
    return "\n".join([*lines, "", "## Rule violations", "", *(notes or ["none"])]) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("transcript", nargs="?", type=Path)
    parser.add_argument(
        "--labels", type=Path, help="JSON {call index: tool|agent}; default labels.json beside the transcript"
    )
    parser.add_argument("--out", type=Path)
    parser.add_argument("--report", type=Path, metavar="OUT_DIR")
    args = parser.parse_args()
    if args.report:
        sys.stdout.write(report(collect(args.report)))
        return 0
    if not args.transcript:
        parser.error("give a transcript or --report")
    labels = args.labels or args.transcript.with_name("labels.json")
    segments = read_json(args.transcript.with_name("segments.json"))
    text = json.dumps(analyze(load(args.transcript), read_json(labels), segments), indent=2)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
