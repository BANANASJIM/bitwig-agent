#!/usr/bin/env bash
# Judge the five tasks from the real project state, never from the agent's account.
#
#   verify.sh A|B|C EXPORT_DIR RAW_DIR > verify.json
#
# Reads back with the condition's own client (0.4 dialect for A, the CLI for B and C). Every read is kept in
# RAW_DIR so a verdict can be rechecked by hand. Each task is pass | fail | review; review means the state was
# read but this script cannot decide it (T3: the host exposes no default to compare the new value with).
# Row shapes are assumed from PROTOCOL.md; confirm them on the first real run by reading RAW_DIR.
set -euo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

cond="${1:?usage: verify.sh A|B|C EXPORT_DIR RAW_DIR}"
export_dir="${2:?export dir}"
raw="${3:?raw dir}"
check_condition "$cond"
dialect="$(cond_dialect "$cond")"
PATH="$(cond_bin "$cond"):$PATH"
mkdir -p "$raw"

CUTOFF='cutoff|frequency|freq'
RESONANCE='reson|\bres\b|\bq\b'
results='{}'

# read NAME METHOD JSON: keep the answer in RAW_DIR/NAME.json and print it; null when the call fails.
read_state() {
  local out
  out="$(rd "$dialect" "$2" "${3:-}" 2>"$raw/$1.err" </dev/null)" || out=null
  printf '%s' "$out" | tee "$raw/$1.json"
}
put() { results="$(jq -c --arg k "$1" --arg s "$2" --argjson d "$3" '.[$k] = ({status: $s} + $d)' <<<"$results")"; }
verdict() { if [[ "$1" == true ]]; then echo pass; else echo fail; fi; }

assert_test_instance "$dialect"

tracks="$(read_state tracks tracks.list)"
# key of a track as the dialect addresses it: a ref (new) or a UUID (old)
track_key() {  # empty when the track does not exist
  if [[ "$dialect" == new ]]; then jq -c --arg n "$1" '[.tracks[]? | select(.name == $n)][0].ref // empty' <<<"$tracks"
  else jq -r --arg n "$1" '[.tracks[]? | select(.name == $n)][0].id // empty' <<<"$tracks"; fi
}
track_id() { if [[ "$dialect" == new ]]; then jq -r '.id' <<<"$1"; else printf '%s' "$1"; fi; }  # UUID of a track key
devices_args() { if [[ "$dialect" == new ]]; then echo "{\"parent\":$1}"; else echo "{\"trackId\":\"$1\"}"; fi; }
device_args() {  # track key, device row JSON -> the target of a device-level read
  if [[ "$dialect" == new ]]; then jq -c '{target: .ref}' <<<"$2"
  else jq -c --arg id "$1" '{trackId: $id, deviceIndex: (.index // .deviceIndex), expectedDeviceName: .name}' <<<"$2"; fi
}
parameter_args() {
  if [[ "$dialect" == new ]]; then jq -c '{parent: .ref}' <<<"$2"; else device_args "$1" "$2"; fi
}

bass="$(track_key Bass)"
drums="$(track_key Drums)"
devices='{"devices":[]}'
if [[ -n "$bass" ]]; then devices="$(read_state bass-devices devices.list "$(devices_args "$bass")")"; fi
device_rows="$(jq -c '.devices[]? | select(.name != null)' <<<"$devices")"

# T1: a Bass track with a sound, and whether it is a Grid
has_grid="$(jq '[.devices[]?.name | test("grid"; "i")] | any' <<<"$devices")"
has_device="$(jq '(.devices // []) | length > 0' <<<"$devices")"
put t1 "$(verdict "$([[ -n "$bass" && "$has_device" == true ]] && echo true || echo false)")" "{\"bass\":$([[ -n "$bass" ]] && echo true || echo false),\"grid\":$has_grid}"

# T2: eight bars of notes in a launcher clip on Bass
notes='null'
if [[ -n "$bass" ]]; then
  if [[ "$dialect" == new ]]; then
    clip="$(read_state context context.get | jq -c --arg n Bass '[.tracks[]? | select(.name == $n)][0].launcherSlots[0].ref // empty')"
    if [[ -n "$clip" ]]; then notes="$(read_state notes notes.read "{\"target\":$clip}")"; fi
  else
    id="$(track_id "$bass")"
    slot="$(read_state launcher launcher.list "{\"trackId\":\"$id\"}" | jq -r '[.slots[]? | select((.clipName // .name // .hasClip // .occupied // null) != null and (.clipName // .name // .hasClip // .occupied) != false)][0] | (.slotIndex // .index) // empty')"
    if [[ -n "$slot" ]]; then notes="$(read_state notes notes.read "{\"trackId\":\"$id\",\"slotIndex\":$slot}")"; fi
  fi
fi
note_stats="$(jq -c '[.. | objects | select(has("beat") and has("pitch"))] | {count: length, first: (map(.beat) | min), last: (map(.beat) | max)}' <<<"$notes")"
eight_bars="$(jq '.count >= 8 and .first < 4 and .last >= 28' <<<"$note_stats")"
put t2 "$(verdict "$eight_bars")" "$note_stats"

# T3: cutoff and resonance readable on the Bass chain, with a value that differs from the default when one is exposed
candidates='[]'
while read -r device; do
  [[ -n "$device" ]] || continue
  name="$(jq -r .name <<<"$device")"
  slug="${name//[^A-Za-z0-9]/_}"
  params="$(read_state "parameters-$slug" parameters.list "$(parameter_args "$bass" "$device")")"
  candidates="$(jq -c --argjson more "$params" '. + [$more | .. | objects | select(has("name") or has("parameterId"))]' <<<"$candidates")"
  if jq -e '.name | test("grid"; "i")' <<<"$device" >/dev/null; then
    grid="$(read_state "grid-$slug" grid.export "$(device_args "$bass" "$device")")"
    candidates="$(jq -c --argjson more "$grid" '. + [$more | .. | objects | select(has("parameterId") or (has("name") and has("value")))]' <<<"$candidates")"
  fi
done <<<"$device_rows"
t3="$(jq -c --arg cut "$CUTOFF" --arg res "$RESONANCE" '
  def pick($re): [.[] | select(((.name // .parameterId // "") | tostring | test($re; "i")) and ((.value.value? // .value?) | type == "number"))];
  def changed: any(.[]; (.default? // .defaultValue? // null) as $d | $d != null and $d != (.value.value? // .value?));
  def unknown: any(.[]; (.default? // .defaultValue? // null) == null);
  pick($cut) as $c | pick($res) as $r
  | {cutoff: ($c | length), resonance: ($r | length), cutoffChanged: ($c | changed), resonanceChanged: ($r | changed),
     status: (if ($c | length) == 0 or ($r | length) == 0 then "fail"
              elif ($c | changed) and ($r | changed) then "pass"
              elif ($c | unknown) or ($r | unknown) then "review" else "fail" end)}' <<<"$candidates")"
put t3 "$(jq -r .status <<<"$t3")" "$(jq -c 'del(.status)' <<<"$t3")"

# T4: a compressor on Bass whose sidechain source is the Drums track
sidechain_ok=false
if [[ -n "$drums" ]]; then
  drums_id="$(track_id "$drums")"
  while read -r device; do
    [[ -n "$device" ]] || continue
    jq -e '.name | test("compressor"; "i")' <<<"$device" >/dev/null || continue
    slug="$(jq -r '.name | gsub("[^A-Za-z0-9]"; "_")' <<<"$device")"
    sc="$(read_state "sidechain-$slug" devices.sidechain.get "$(device_args "$bass" "$device")")"
    if jq -e --arg id "$drums_id" '[.. | strings] | any(. == $id or . == "Drums")' <<<"$sc" >/dev/null; then sidechain_ok=true; fi
  done <<<"$device_rows"
fi
put t4 "$(verdict "$sidechain_ok")" "{\"drums\":$([[ -n "$drums" ]] && echo true || echo false)}"

# T5: a WAV in the export directory whose integrated loudness measures
wav="$(find "$export_dir" -maxdepth 2 -name '*.wav' -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n 1 | cut -d' ' -f2-)"
lufs=null
if [[ -n "$wav" ]]; then
  analysis="$(read_state loudness audio.analyze "$(jq -nc --arg p "$wav" '{path: $p, mode: "loudness"}')")"
  lufs="$(jq -c '[.. | objects | to_entries[] | select(.key | test("integrated|lufs"; "i")) | .value | numbers][0] // null' <<<"$analysis")"
fi
put t5 "$(verdict "$(jq '. != null and . > -70' <<<"$lufs")")" "{\"file\":$(jq -Rn --arg w "$wav" '$w // null'),\"lufs\":$lufs}"

jq -c --arg cond "$cond" '{condition: $cond} + .' <<<"$results"
