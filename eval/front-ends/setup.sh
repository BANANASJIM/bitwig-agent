#!/usr/bin/env bash
# Build the fixed start state on the isolated test instance: silent audio, 120 BPM 4/4, stopped transport,
# one drum track ("Drums") with a 4/4 one-bar clip in scene 0. Run on a pristine project (run_all.sh restarts first).
#
#   setup.sh A|B|C [state-file]      A uses the 0.4 dialect, B and C the current CLI.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

cond="${1:?usage: setup.sh A|B|C [state-file]}"
state_file="${2:-}"
check_condition "$cond"
dialect="$(cond_dialect "$cond")"
if [[ "$DRY_RUN" == 1 ]]; then
  echo "setup $cond ($dialect dialect): audio route silent, 120 BPM 4/4 stopped, Drums track with a one-bar clip in scene 0"
  exit 0
fi
PATH="$(cond_bin "$cond"):$PATH"

# One bar of 4/4 (the 0.4 launcher.create wants "op":"set" on each note; it is added below for that dialect): kick on the beat, hat on the off-beat.
NOTES='[
 {"beat":0,"pitch":36,"velocity":110,"durationBeats":0.25},{"beat":1,"pitch":36,"velocity":100,"durationBeats":0.25},
 {"beat":2,"pitch":36,"velocity":110,"durationBeats":0.25},{"beat":3,"pitch":36,"velocity":100,"durationBeats":0.25},
 {"beat":0.5,"pitch":42,"velocity":80,"durationBeats":0.25},{"beat":1.5,"pitch":42,"velocity":80,"durationBeats":0.25},
 {"beat":2.5,"pitch":42,"velocity":80,"durationBeats":0.25},{"beat":3.5,"pitch":42,"velocity":80,"durationBeats":0.25}]'

assert_test_instance "$dialect"

# Fail closed on anything but a pristine project: the start state must be identical every run.
track_names="$(rd "$dialect" tracks.list | jq -r '.tracks[].name')"
if grep -qxE 'Drums|Bass' <<<"$track_names"; then die "project already has a Drums or Bass track: restart the instance from the pristine project first"; fi

write "$dialect" audio.route '{"mode":"silent"}' >/dev/null
write "$dialect" transport.set '{"playing":false,"tempoBpm":120}' >/dev/null
# The 0.4 extension may not know timeSignature; the pristine project is 4/4.
if [[ "$dialect" == old ]]; then
  rd old transport.set '{"timeSignature":"4/4"}' >/dev/null || echo "warning: 0.4 did not accept timeSignature; relying on the pristine project" >&2
else
  write new transport.set '{"timeSignature":"4/4"}' >/dev/null
fi

if [[ "$dialect" == new ]]; then
  drums="$(write new tracks.insert '{"type":"instrument","name":"Drums"}' | jq -c .ref)"
  slot="$(rd new launcher.list "{\"parent\":$drums}" | jq -c '.slots[0].ref // empty')"
  if [[ -z "$slot" ]]; then
    write new scenes.insert '{"count":1}' >/dev/null
    slot="$(rd new launcher.list "{\"parent\":$drums}" | jq -c '.slots[0].ref // empty')"
  fi
  [[ -n "$slot" ]] || die "no launcher slot on the Drums track"
  write new clips.insert "$(jq -nc --argjson to "$slot" --argjson notes "$NOTES" '{to:$to,name:"Beat",loop:{lengthBeats:4},notes:$notes}')" >/dev/null
else
  write old tracks.create '{"type":"instrument","position":-1,"name":"Drums"}' >/dev/null
  id="$(rd old tracks.list | jq -r '.tracks[] | select(.name=="Drums") | .trackId')"
  [[ -n "$id" ]] || die "Drums track not found after creation"
  if [[ "$(rd old launcher.list "{\"trackId\":\"$id\"}" | jq '.slots | length')" == 0 ]]; then
    write old scenes.append '{"count":1}' >/dev/null
  fi
  write old launcher.create "$(jq -nc --arg id "$id" --argjson notes "$NOTES" '{trackId:$id,slotIndex:0,lengthBeats:4,name:"Beat",notes:($notes | map(. + {op:"set"}))}')" >/dev/null
fi

if [[ -n "$state_file" ]]; then rd "$dialect" tracks.list | jq '{tracks: [.tracks[].name]}' >"$state_file"; fi
echo "start state ready ($cond)"
