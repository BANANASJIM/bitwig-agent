#!/usr/bin/env bash
# Conditions x runs, one at a time (a single tester owns the test instance). Writes under BWA_EVAL_OUT, which must
# be outside the repository, and ends with a markdown report for the PR.
#
#   run_all.sh [--dry-run] [--conditions "A B C"] [--runs N]
#
# Environment (all required unless --dry-run):
#   BWA_EVAL_OUT         results directory, outside the repository
#   BWA_MAIN_TREE        checkout of current main, 'pixi install --locked' done (conditions B and C)
#   BWA_V04_TREE         checkout of $BWA_A_SHA (default 37157f8) with its own pixi env (condition A)
#   BWA_RESTART_SCRIPT   helper that restarts the isolated test instance on the pristine project
# Optional: BWA_EVAL_MODEL, BWA_RUN_BUDGET_USD, BWA_RUN_TIMEOUT, BWA_BIN_A|B|C (override a condition's bin dir).
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

conditions="A B C"
runs=2
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --conditions) conditions="$2"; shift ;;
    --runs) runs="$2"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done
export DRY_RUN
[[ "$runs" =~ ^[1-9][0-9]*$ ]] || die "--runs must be a positive integer"
for cond in $conditions; do check_condition "$cond"; done

out="$(need_env BWA_EVAL_OUT 'results directory outside the repository')"
restart="$(need_env BWA_RESTART_SCRIPT 'helper that restarts the test instance')"
if [[ "$DRY_RUN" != 1 ]]; then
  mkdir -p "$out"
  out="$(cd "$out" && pwd)"
  [[ "$out/" != "$REPO_ROOT/"* ]] || die "BWA_EVAL_OUT is inside the repository"
  exec 9>"$out/.lock"
  flock -n 9 || die "another run_all.sh owns $out"
fi

echo "plan: conditions [$conditions] x $runs runs, model $MODEL, out $out"
if [[ "$DRY_RUN" == 1 ]]; then echo "(dry run: nothing below is executed or contacted)"; fi

loaded=""
for cond in $conditions; do
  extension="$(cond_extension "$cond")"
  fresh=0
  if [[ "$extension" != "$loaded" ]]; then
    echo "== extension $extension for $cond"
    "$EVAL_DIR/install_extension.sh" "$cond"
    loaded="$extension"
    fresh=1
  fi
  for run in $(seq 1 "$runs"); do
    echo "== $cond run $run"
    if [[ "$fresh" == 0 ]]; then
      if [[ "$DRY_RUN" == 1 ]]; then echo "bash $restart"; else bash "$restart"; fi
    fi
    fresh=0
    if [[ "$DRY_RUN" != 1 ]]; then mkdir -p "$out/$cond"; fi
    "$EVAL_DIR/setup.sh" "$cond" "$out/$cond/start-state-run$run.json"
    "$EVAL_DIR/run_one.sh" "$cond" "$run" "$out"
  done
done

if [[ "$DRY_RUN" == 1 ]]; then
  echo "== report: python3 $EVAL_DIR/metrics.py --report $out > $out/report.md"
else
  python3 "$EVAL_DIR/metrics.py" --report "$out" >"$out/report.md"
  echo "report: $out/report.md"
fi
