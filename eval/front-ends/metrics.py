#!/usr/bin/env python3
"""Metrics from one `claude -p --output-format stream-json --verbose` transcript, and the cross-run report.

  metrics.py run.jsonl [--labels labels.json] [--out metrics.json]   one run -> JSON
  metrics.py --report OUT_DIR                                        every run under OUT_DIR -> markdown

Counted from the transcript, not from the agent's own account:
  calls            every tool call, by tool and by task
  learning bytes   result bytes of `--help`/`help`/old `list`/`describe`/MCP `help` calls, plus everything read
                   from skills/** (Read, a shell cat/head/sed/grep, the Skill tool and the text it injects)
  total read bytes result bytes of every tool call
  segments.json    optional per run {"1": first call index of task 1, ...}, when the agent skipped or misplaced markers
  verdicts.json    optional per run {"t3": {"status": "pass"|"fail", "reason": ...}}; replaces a `review` only
  failures         a Bash call with exit != 0, or any call with is_error; each labelled `tool` (the Bitwig tool or
                   host answered with an error) or `agent` (the agent's own shell script, file or command broke)
  self-correction  a tool-caused failure followed, within the next CORRECTION_WINDOW calls, by a success of the
                   same method; rate = corrected / tool-caused failures; a method that never succeeds later is
                   abandoned
  per-task calls   calls are assigned to the task whose `=== TASK n ===` line the agent wrote last (calls before
                   the first marker are `pre`); with no marker at all, to the task whose method family the call
                   uses (see TASK_METHODS), and `task_split` says which rule applied

The failure label is a heuristic (AGENT_SIGNS, BITWIG_ERROR); a labels file maps a 1-based call index to
"tool" or "agent" (or {"label": .., "method": ..} to also fix the failing method of a chained call) and overrides it,
recorded as label_source "override".
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
MAX_WORDS = 3
OLD_BINARY = "bitwig-agent-rpc"
OLD_LEARNING = ("list", "describe")
MARKER = re.compile(r"===\s*TASK\s*([1-5])\s*===", re.IGNORECASE)
EXIT_CODE = re.compile(r"^Exit code (\d+)")
BITWIG_BIN = re.compile(r"(?:^|[\s;&|(])(?:\S*/)?(bitwig-agent-rpc|bitwig-agent)(?=\s|$)(.*)", re.DOTALL)
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
    """Every bitwig-agent[-rpc] call of a command line, one per `;`, `&&` or newline segment, in order."""
    found = (invocation(segment) for segment in re.split(r";|&&|\n", command))
    return [parsed for parsed in found if parsed is not None]


def asks_for_help(call: Call) -> bool:
    parsed = invocation(call.command)
    return parsed is not None and is_help(parsed)


def methods_of(call: Call) -> list[str]:
    """Methods of every non-help bitwig-agent call in the command, in order (an agent often chains several)."""
    if call.tool.startswith("mcp__"):
        return [
            call.tool.split("__", 2)[-1].replace("_", ".")
        ]  # MCP tools are named tracks_insert, methods tracks.insert
    return [".".join(parsed[1]) for parsed in invocations(call.command) if parsed[1] and not is_help(parsed)]


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
    by_tool: dict[str, int] = {}
    for call in calls:
        by_tool[call.tool] = by_tool.get(call.tool, 0) + 1
    result = next((e for e in events if e.get("type") == "result"), {})
    init = next((e for e in events if e.get("subtype") == "init"), {})
    return {
        "model": init.get("model"),
        "calls": len(calls),
        "calls_by_tool": by_tool,
        "calls_by_task": {t: sum(1 for c in calls if c.task == t) for t in ("pre", *map(str, TASKS))},
        "task_split": "segments.json" if segments else "markers" if marked else "keywords",
        "learning_calls": sum(is_learning(c) for c in calls),
        "learning_bytes": sum(c.bytes for c in calls if is_learning(c)),
        "total_read_bytes": sum(c.bytes for c in calls),
        "failures": failures,
        "tool_failures": len(tool_failures),
        "agent_failures": len(failures) - len(tool_failures),
        "self_corrections": corrected,
        "self_correction_rate": corrected / len(tool_failures) if tool_failures else None,
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
            )
        )
    return runs


def task_status(run: Run, task: int) -> str:
    return str((run.verify.get(f"t{task}") or {}).get("status", "missing"))


def criteria(run: Run, baseline: list[Run]) -> dict[str, bool | None]:
    """The design's per-run criteria for B and C against A's matching run and A's per-task median."""
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
            "Commit",
            "Model",
            "Skill B",
            "tools/list B",
            "Calls",
            "Learning B",
            "Read B",
            "Tool fail",
            "Agent fail",
            "Self-corr",
            "T1",
            "T2",
            "T3",
            "T4",
            "T5",
        ],
        [
            [
                r.condition,
                r.run,
                r.meta.get("commit"),
                r.meta.get("model") or r.metrics["model"],
                r.meta.get("skill_bytes"),
                r.meta.get("tools_list_bytes"),
                r.metrics["calls"],
                r.metrics["learning_bytes"],
                r.metrics["total_read_bytes"],
                r.metrics["tool_failures"],
                r.metrics["agent_failures"],
                "n/a" if r.metrics["self_correction_rate"] is None else f"{r.metrics['self_correction_rate']:.2f}",
                *(task_status(r, t) for t in TASKS),
            ]
            for r in runs
        ],
    )
    lines += ["", "## Calls per task", ""]
    lines += table(
        ["Cond", "Run", "pre", *(f"T{t}" for t in TASKS), "split"],
        [
            [
                r.condition,
                r.run,
                *(r.metrics["calls_by_task"][k] for k in ("pre", *map(str, TASKS))),
                r.metrics["task_split"],
            ]
            for r in runs
        ],
    )
    if baseline:
        lines += ["", "## Criteria (B and C, every run, against A)", ""]
        rows = []
        for r in (r for r in runs if r.condition != "A"):
            values = criteria(r, baseline)
            rows.append([r.condition, r.run, *(mark(v) for v in values.values()), verdict(values)])
        lines += table(
            ["Cond", "Run", "T1 Grid", "Tool fail <= A", "All tasks", "Self-corr >= A", "Calls <= A median", "Verdict"],
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
            f["message"][:80].replace("|", "/").replace("\n", " "),
        ]
        for r in runs
        for f in r.metrics["failures"]
    ]
    lines += [
        "",
        "## Failed calls",
        "",
        *table(["Cond", "Run", "Call", "Method", "Task", "Cause", "Source", "Message"], failures),
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
