#!/usr/bin/env bash
# One run of one condition: render the prompt, run the subject agent, then compute metrics and verify.
#
#   run_one.sh A|B|C RUN OUT_DIR       DRY_RUN=1 prints the claude command and touches nothing
#
# The subject agent runs headless in OUT_DIR/work/<cond>-run<N>, a directory outside the repository that holds
# only the Skill (A and B). Tools are restricted so it cannot read source or docs:
#   A, B  --tools Bash,Read,Skill   Bash is open; Read is allowed only under the Skill directory
#   C     --tools ""                only the MCP server's tools (--strict-mcp-config), no Skill, no shell
# --setting-sources project keeps the user's CLAUDE.md, hooks and plugins out; --permission-mode dontAsk denies
# whatever is not in --allowedTools.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

cond="${1:?usage: run_one.sh A|B|C RUN OUT_DIR}"
run="${2:?run number}"
out="${3:?out dir}"
check_condition "$cond"
tree="$(cond_tree "$cond")"
bin="$(cond_bin "$cond")"
run_dir="$out/$cond/run$run"
work="$out/work/$cond-run$run"
exports="$run_dir/exports"
budget="${BWA_RUN_BUDGET_USD:-5}"
timeout_s="${BWA_RUN_TIMEOUT:-2400}"

case "$cond" in
  A) learn=$'- The Skill `bitwig-agent`: load it with the Skill tool; it names further files you may read.\n- `bitwig-agent-rpc list`, `bitwig-agent-rpc describe <method>` and `bitwig-agent-rpc help`.' ;;
  B) learn=$'- The Skill `bitwig-agent`: load it with the Skill tool; it names further files you may read.\n- `bitwig-agent --help`, `bitwig-agent <resource> <verb> --help` and `bitwig-agent help <topic>`.' ;;
  C) learn=$'- The MCP tools you are given, and the `help` tool among them. You have no shell.' ;;
esac
prompt="$(cat "$EVAL_DIR/tasks.md")"
prompt="${prompt//<LEARN>/$learn}"
prompt="${prompt//<OUTDIR>/$exports}"

flags=(-p "$prompt" --output-format stream-json --verbose --model "$MODEL" --no-session-persistence
  --setting-sources project --permission-mode dontAsk --max-budget-usd "$budget")
if [[ "$cond" == C ]]; then
  mcp_config="$run_dir/mcp.json"
  flags+=(--tools "" --mcp-config "$mcp_config" --strict-mcp-config --allowedTools "mcp__bitwig-agent")
else
  flags+=(--tools Bash,Read,Skill --allowedTools "Bash,Skill,Read(/$work/.claude/skills/**)"
    --mcp-config '{"mcpServers":{}}' --strict-mcp-config)
fi

if [[ "$DRY_RUN" == 1 ]]; then
  printf "cd '%s' && PATH='%s':\$PATH timeout %s claude -p '<tasks.md with LEARN and OUTDIR filled>'" "$work" "$bin" "$timeout_s"
  printf " '%s'" "${flags[@]:2}"
  printf " > '%s'\n" "$run_dir/run.jsonl"
  exit 0
fi

command -v claude >/dev/null || die "claude is not on PATH"
[[ ! -e "$run_dir" ]] || die "$run_dir exists: results are never overwritten"
mkdir -p "$run_dir" "$exports" "$work"
if [[ "$cond" == C ]]; then
  jq -n --arg cmd "$bin/bitwig-agent-mcp" '{mcpServers: {"bitwig-agent": {command: $cmd, args: []}}}' >"$mcp_config"
  skill_bytes=0 skill_tree_bytes=0
else
  mkdir -p "$work/.claude/skills"
  cp -a "$tree/skills/bitwig-agent" "$work/.claude/skills/"
  skill_bytes="$(wc -c <"$tree/skills/bitwig-agent/SKILL.md")"
  skill_tree_bytes="$(find "$tree/skills/bitwig-agent" -type f -exec cat {} + | wc -c)"
fi
tools_list_bytes=null
if [[ "$cond" != A ]]; then
  tools_list_bytes="$("$bin/python" "$tree/scripts/surface.py" | jq .tools_list_bytes)"
fi
commit="$(git -C "$tree" rev-parse --short HEAD)$(git -C "$tree" diff --quiet HEAD 2>/dev/null || echo -dirty)"
printf '%s\n' "$prompt" >"$run_dir/prompt.md"
jq -n --arg cond "$cond" --argjson run "$run" --arg commit "$commit" --arg model "$MODEL" \
  --argjson skill "$skill_bytes" --argjson tree "$skill_tree_bytes" --argjson tools "$tools_list_bytes" \
  --arg claude "$(claude --version)" --arg started "$(date -u +%FT%TZ)" \
  '{condition: $cond, run: $run, commit: $commit, model: $model, skill_bytes: $skill, skill_tree_bytes: $tree,
    tools_list_bytes: $tools, claude: $claude, started: $started}' >"$run_dir/meta.json"

exit_code=0
(cd "$work" && PATH="$bin:$PATH" timeout "$timeout_s" claude "${flags[@]}" >"$run_dir/run.jsonl" 2>"$run_dir/stderr.log") || exit_code=$?
jq --argjson code "$exit_code" '.claude_exit = $code' "$run_dir/meta.json" >"$run_dir/meta.tmp" && mv "$run_dir/meta.tmp" "$run_dir/meta.json"

python3 "$EVAL_DIR/metrics.py" "$run_dir/run.jsonl" --out "$run_dir/metrics.json"
"$EVAL_DIR/verify.sh" "$cond" "$exports" "$run_dir/verify-raw" >"$run_dir/verify.json"
echo "run $cond/$run done (claude exit $exit_code)"
