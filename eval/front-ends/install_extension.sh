#!/usr/bin/env bash
# Build and install the extension a condition needs, then restart the test instance on the pristine project.
#
#   install_extension.sh A|B|C        needs BWA_RESTART_SCRIPT (the restart helper) and the condition's tree
#
# The 0.4 build must see its own sources: without PYTHONPATH the build picks up the installed 0.5 bundle and
# the extension crashes on load.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

cond="${1:?usage: install_extension.sh A|B|C}"
check_condition "$cond"
tree="$(cond_tree "$cond")"
restart="$(need_env BWA_RESTART_SCRIPT 'path of the helper that restarts the test instance on a fresh project')"

if [[ "$DRY_RUN" == 1 ]]; then
  echo "(cd $tree && PYTHONPATH=$tree/src pixi run install)"
  echo "bash $restart"
  exit 0
fi

[[ -d "$tree" ]] || die "$tree is not a directory"
if [[ "$cond" == A ]]; then
  [[ "$(git -C "$tree" rev-parse HEAD)" == "$A_SHA"* ]] || die "$tree is not at $A_SHA (set BWA_A_SHA to override)"
fi
(cd "$tree" && PYTHONPATH="$tree/src" pixi run install)
bash "$restart"
