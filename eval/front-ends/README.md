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
eval/front-ends/run_all.sh [--conditions "A B C"] [--runs 2]
```

Per run: restart, `setup.sh` (silent audio, one `Drums` track with a one-bar clip), `claude -p` with the
prompt from `tasks.md`, `metrics.py` and `verify.sh`. `metrics.py --report $BWA_EVAL_OUT` prints the markdown
tables and applies the criteria. Failed calls are labelled `tool` or `agent` by heuristic; put
`{"<call index>": "tool"|"agent"}` in `<run>/labels.json` and rerun `metrics.py <run>/run.jsonl --out
<run>/metrics.json` to override. `pixi run surface` prints the static sizes (no Bitwig).
