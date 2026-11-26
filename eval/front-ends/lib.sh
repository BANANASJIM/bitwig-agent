#!/usr/bin/env bash
# Shared helpers for the FE5 harness. Source it; do not run it.
# Conditions: A = 0.4 bitwig-agent-rpc + old Skill, B = CLI + Skill, C = MCP only.

EVAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$EVAL_DIR/../.." && pwd)"
MODEL="${BWA_EVAL_MODEL:-claude-sonnet-5-5}"
A_SHA="${BWA_A_SHA:-37157f8}"
DRY_RUN="${DRY_RUN:-0}"

die() { echo "error: $*" >&2; exit 1; }

# need_env NAME HINT: value of $NAME, or an error naming it; under DRY_RUN a visible placeholder.
need_env() {
  local value="${!1:-}"
  if [[ -n "$value" ]]; then printf '%s' "$value"; return; fi
  if [[ "$DRY_RUN" == 1 ]]; then printf '<unset:%s>' "$1"; return; fi
  die "$1 is not set ($2)"
}

check_condition() { [[ "$1" =~ ^[ABC]$ ]] || die "condition must be A, B or C, got '$1'"; }

# Checkout whose pixi environment runs the condition: A is the 0.4 tree, B and C the current main tree.
cond_tree() {
  case "$1" in
    A) need_env BWA_V04_TREE "checkout of $A_SHA with 'pixi install --locked' done" ;;
    *) need_env BWA_MAIN_TREE "checkout of current main with 'pixi install --locked' done" ;;
  esac
}

cond_bin() { local override="BWA_BIN_$1"; if [[ -n "${!override:-}" ]]; then printf '%s' "${!override}"; else printf '%s/.pixi/envs/default/bin' "$(cond_tree "$1")"; fi; }

# Extension build a condition needs; A and B/C differ, B and C share one.
cond_extension() { if [[ "$1" == A ]]; then echo 0.4; else echo current; fi; }

# The client used by setup.sh and verify.sh, in the dialect of the condition's extension.
cond_dialect() { if [[ "$1" == A ]]; then echo old; else echo new; fi; }

# rd DIALECT METHOD JSON: one call, stdout unwrapped to the result object. Never retries.
rd() {
  local dialect="$1" method="$2" args="${3:-}"
  [[ -n "$args" ]] || args='{}'
  if [[ "$dialect" == old ]]; then bitwig-agent-rpc "$method" --params "$args" | jq -c '(.result // .)'
  else bitwig-agent "$method" "$args" | jq -c '(.result // .)'
  fi
}

# write DIALECT METHOD JSON: a write that must end applied|already_matched|sent; prints the result.
write() {
  local out
  out="$(rd "$1" "$2" "$3")" || die "$2 failed"
  jq -e '.outcome == "applied" or .outcome == "already_matched" or .outcome == "sent"' <<<"$out" >/dev/null || die "$2 not applied: $out"
  printf '%s' "$out"
}

skill_dir() { printf '%s/skills/bitwig-agent' "$(cond_tree "$1")"; }

# The test instance is the only thing these scripts may touch.
assert_test_instance() {
  local dialect="$1" status
  status="$(rd "$dialect" status '{}')" || die "status failed: is the test Bitwig up on :1?"
  [[ "$(jq -r '.instance.display' <<<"$status")" == ":1" ]] || die "status.instance.display is not :1: refusing to touch this instance"
  [[ "$(jq -r '.projectName' <<<"$status")" == "BWA Test" ]] || die "status.projectName is not 'BWA Test': refusing"
}
