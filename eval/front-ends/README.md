# Front-end evaluation (FE5)

Measures the CLI and MCP front ends against the 0.4 baseline on five fixed tasks, on the isolated test instance
only. Design: the front-ends design, section "Measurement". Nothing here runs in CI except the offline tests
(`tests/test_eval_front_ends.py`).

| Condition | Entry | Skill |
|---|---|---|
| A | 0.4 `bitwig-agent-rpc` (checkout of `37157f8`, its own extension build) | old |
| B | `bitwig-agent` CLI, current main | new |
| C | `bitwig-agent-mcp` only (`--strict-mcp-config`), no shell | none |

```sh
export BWA_EVAL_OUT=...        # results, outside the repo
export BWA_MAIN_TREE=...       # current main, `pixi install --locked` done
export BWA_V04_TREE=...        # checkout of 37157f8, `pixi install --locked` done
export BWA_RESTART_SCRIPT=...  # restarts the test instance on its pristine project
eval/front-ends/run_all.sh --dry-run          # print the plan, touch nothing
eval/front-ends/run_all.sh [--conditions "A B C"] [--runs 4]
```

Per run: restart, `setup.sh` (silent audio, one `Drums` track with a one-bar clip), `claude -p` with the
prompt from `tasks.md`, `metrics.py` and `verify.sh`. `metrics.py --report $BWA_EVAL_OUT` prints the markdown
tables and applies the criteria. Failed calls are labelled `tool` or `agent` by heuristic; put
`{"<call index>": "tool"|"agent"}` in `<run>/labels.json` and rerun `metrics.py <run>/run.jsonl --out
<run>/metrics.json` to override. `pixi run surface` prints the static sizes (no Bitwig).

Overrides, all optional files in `<run>`: `labels.json` (failure cause), `segments.json` (task boundaries),
`verdicts.json` (a human verdict for a `review`), `lost.json` `{"t2.playing": false|true}` (force a sub-goal to
not-lost|lost). `verify.sh` writes per task `status` (the original check), `goals` and, for T1, T4 and T5,
`strong`; for T4 it inserts the same device on a scratch audio track and deletes it afterwards.

The B/C dialect argument names that the FE6-b renames change live in `lib.sh` only: `F_LIST_PARENT` (`.list`
owner argument, `parent` now) and `F_INSERT_TO` (`devices.insert` destination, `parent` now). Flip those two
values, or export `BWA_F_LIST_PARENT` / `BWA_F_INSERT_TO`; no other line depends on them.

## Pre-registered criterion (rerun)

Registered before any rerun. The report prints two verdicts: (a) the original design criteria exactly as
implemented in round 1, for continuity, and (b) this criterion. The human decides which one governs; the
numbers below are fixed now and are not adjusted after seeing results.

Definitions:

- **Tool call**: one tool-use block of the agent (a shell call, a Read, a Skill load, an MCP tool call).
- **Method call**: one `bitwig-agent ...` / `bitwig-agent-rpc ...` command inside a shell call, or one MCP tool call,
  excluding help (`--help`, `help ...`, 0.4 `list`/`describe`). A shell call that chains several commands counts
  each one; a loop counts its body once per textual occurrence, not once per iteration.
- **Refusal**: a failed method call whose cause is the tool or host (an error answer), not the agent's own
  shell or script. Corrected: the next call of the same method after the refusal succeeds (one follow-up
  operation; help reads in between do not count as operations).
- **Lost request**: a requested sub-goal that is undone at the end, per the state verify reads back, in a task
  where a tool refusal happened. Sub-goals: T1 track, a sound device and a filter control on Bass (a Grid
  counts only when a refused T1 call asked for one); T2 notes in a launcher clip and the clip played; T3 cutoff
  and resonance changed; T4 a compressor, its sidechain source and a dynamics parameter moved; T5 an export
  file and a loudness measurement. A refusal followed by the sub-goal ending done is not lost.
- **Stronger verify**: T1 a Grid device on the Bass track; T2 and T3 as before (T3 `review` needs a human
  verdict); T4 the sidechain source is `Drums` and at least one dynamics parameter differs from a fresh insert
  of the same device; T5 the render is not silent and is low-band dominated (see `verify.sh`).

Criterion, for each of B and C against A:

1. At least 4 runs per condition, every run listed individually.
2. Every B/C run passes the stronger verify.
3. No lost requests in any B/C run.
4. Tool refusals: the B/C median is at most the A median and the B/C maximum is at most the A maximum, and every
   B/C refusal is corrected within one follow-up operation.
5. Efficiency in method calls per run: the B/C median is at most the A median and the B/C maximum is at most the
   A maximum. If any A run did not use a Grid device, T1 is left out of the method-call count of every run in
   this comparison (same basis for all conditions).

Per-task numbers are diagnostics only. Bytes and cost are recorded, not judged.
