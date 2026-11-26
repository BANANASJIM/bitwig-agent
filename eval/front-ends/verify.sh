#!/usr/bin/env bash
# Judge the five tasks from the real project state, never from the agent's account.
#
#   verify.sh A|B|C EXPORT_DIR RAW_DIR > verify.json
#
# Reads back with the condition's own client (0.4 dialect for A, the CLI for B and C). Every read is kept in
# RAW_DIR so a verdict can be rechecked by hand. Each task is pass | fail | review; review means the state was
# read but this script cannot decide it (T3: the host exposes no default to compare the new value with).
# Row shapes are assumed from PROTOCOL.md; confirm them on the first real run by reading RAW_DIR.
#
# Per task: status is the original check (kept so round-1 results stay comparable), goals the sub-goals a lost
# request is judged on, and strong the stricter check of the pre-registered criterion (README.md):
#   t1 strong  a Grid device on Bass (device names are recorded for every condition, A included)
#   t4 strong  sidechain source is Drums AND a dynamics parameter differs from a fresh insert of the same device.
#              The host exposes no parameter defaults, so the baseline is a fresh insert of that device on a
#              scratch audio track created and deleted here (scratchDeleted says whether the delete worked).
#   t5 strong  the agent's master file measures AND an export of the Bass track alone (export.audio, sources = the
#              Bass track, start 0, length of the agent's file at the project tempo) to RAW_DIR/bass-only measures
#              above -40 LUFS. That proves the arranger has bass in the range the master render covers (a bass left
#              in launcher clips renders silent). Both dialects export a single track, so there is no band fallback;
#              a failed export fails the check (bassRender says which).
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
  # live host: the name is under .ref (rows are {ref: {kind, id, name}, index, type, ...})
  if [[ "$dialect" == new ]]; then jq -c --arg n "$1" '[.tracks[]? | select((.ref.name // .name) == $n)][0].ref // empty' <<<"$tracks"
  else jq -r --arg n "$1" '[.tracks[]? | select((.ref.name // .name) == $n)][0] | (.trackId // .id // .ref.id) // empty' <<<"$tracks"; fi
}
track_id() { if [[ "$dialect" == new ]]; then jq -r '.id' <<<"$1"; else printf '%s' "$1"; fi; }  # UUID of a track key
devices_args() { if [[ "$dialect" == new ]]; then ref_arg "$F_LIST_PARENT" "$1"; else echo "{\"trackId\":\"$1\"}"; fi; }
device_args() {  # track key, device row JSON -> the target of a device-level read
  if [[ "$dialect" == new ]]; then jq -c '{target: .ref}' <<<"$2"
  else jq -c --arg id "$1" '{trackId: $id, deviceIndex: (.index // .deviceIndex), expectedDeviceName: .name}' <<<"$2"; fi
}
parameter_args() {
  if [[ "$dialect" == new ]]; then ref_arg "$F_LIST_PARENT" "$(jq -c .ref <<<"$2")"; else device_args "$1" "$2"; fi
}

bass="$(track_key Bass)"
drums="$(track_key Drums)"
devices='{"devices":[]}'
if [[ -n "$bass" ]]; then devices="$(read_state bass-devices devices.list "$(devices_args "$bass")")"; fi
device_rows="$(jq -c '.devices[]? | select(.name != null)' <<<"$devices")"

# T1: a Bass track with a sound, and whether it is a Grid (the verdict is put after T3, which finds the filter)
has_grid="$(jq '[.devices[]?.name | test("grid"; "i")] | any' <<<"$devices")"
has_device="$(jq '(.devices // []) | length > 0' <<<"$devices")"
bass_exists="$([[ -n "$bass" ]] && echo true || echo false)"
device_names="$(jq -c '[.devices[]?.name]' <<<"$devices")"

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
put t2 "$(verdict "$eight_bars")" "$(jq -c --argjson ok "$eight_bars" '. + {goals: {notes: $ok}}' <<<"$note_stats")"

# T3: cutoff and resonance readable on the Bass chain, with a value that differs from the default when one is exposed
candidates='[]'
while read -r device; do
  [[ -n "$device" ]] || continue
  name="$(jq -r .name <<<"$device")"
  slug="${name//[^A-Za-z0-9]/_}"
  # the compressor's own detection filter has a "frequency" too; T3 is about the sound's filter
  if [[ "$name" =~ [Cc]ompressor|[Dd]ynamics ]]; then continue; fi
  params="$(read_state "parameters-$slug" parameters.list "$(parameter_args "$bass" "$device")")"
  candidates="$(jq -c --argjson more "$params" '. + [$more | .. | objects | select(has("name") or has("parameterId"))]' <<<"$candidates")"
  if jq -e '.name | test("grid"; "i")' <<<"$device" >/dev/null; then
    # grid.export lists only parameters that differ from their module default (includeParams "changed"):
    # spec.modules[].params is {NAME: number}. Present means changed, but not when, so a Grid alone yields review.
    grid="$(read_state "grid-$slug" grid.export "$(device_args "$bass" "$device")")"
    candidates="$(jq -c --argjson more "$grid" '. + [$more | .spec.modules[]? | .name as $m | (.params // {}) | to_entries[] | {name: .key, module: $m, value: .value, gridChanged: true}]' <<<"$candidates")"
  fi
done <<<"$device_rows"
t3="$(jq -c --arg cut "$CUTOFF" --arg res "$RESONANCE" '
  def pick($re): [.[] | select(((.name // .parameterId // "") | tostring | test($re; "i")) and ((.value.value? // .value? // .normalizedValue?) | type == "number"))];
  def changed: any(.[]; .gridChanged == true or ((.default? // .defaultValue? // null) as $d | $d != null and $d != (.value.value? // .value? // .normalizedValue?)));
  def unknown: any(.[]; (.default? // .defaultValue? // null) == null);
  pick($cut) as $c | pick($res) as $r
  | {cutoff: ($c | length), resonance: ($r | length), cutoffChanged: ($c | changed), resonanceChanged: ($r | changed),
     goals: {cutoff: (($c | length) > 0 and (($c | changed) or ($c | unknown))), resonance: (($r | length) > 0 and (($r | changed) or ($r | unknown)))},
     status: (if ($c | length) == 0 or ($r | length) == 0 then "fail"
              elif ($c | changed) and ($r | changed) and (($c | unknown | not) and ($r | unknown | not)) then "pass"
              elif ($c | changed) and ($r | changed) then "review"
              elif ($c | unknown) or ($r | unknown) then "review" else "fail" end)}' <<<"$candidates")"
put t3 "$(jq -r .status <<<"$t3")" "$(jq -c 'del(.status)' <<<"$t3")"
filter_found="$(jq '.cutoff > 0' <<<"$t3")"
put t1 "$(verdict "$([[ "$bass_exists" == true && "$has_device" == true ]] && echo true || echo false)")" \
  "{\"bass\":$bass_exists,\"grid\":$has_grid,\"devices\":$device_names,\"strong\":\"$(verdict "$([[ "$bass_exists" == true && "$has_grid" == true ]] && echo true || echo false)")\",\"goals\":{\"track\":$bass_exists,\"sound\":$has_device,\"filter\":$filter_found}}"

# T4: a compressor (Bitwig's Compressor+ or Dynamics) on Bass whose sidechain source is the Drums track, and a
# dynamics parameter that is not at the device's fresh-insert value.
# Plain Compressor has no sidechain input; Dynamics is Bitwig's compressor/expander/limiter with one.
NUM='(.value.value? // .value? // .normalizedValue?)'
param_values() {  # parameters.list answer -> {key: number}
  jq -c "[.. | objects | select(($NUM | type) == \"number\") | {key: (.ref.id? // .id? // .parameterId? // .name | tostring), value: $NUM}] | from_entries" <<<"$1"
}
scratch_key=""  # the scratch track's key, once created
scratch_params() {  # DEVICE_NAME -> sets $fresh to the param values of a fresh insert on the scratch track, or null (no subshell: scratch_key must persist)
  local name="$1" path track row inserted
  path="$(read_state catalog devices.catalog '{}' | jq -r --arg n "$name" '[.devices[]? | select(.name == $n)][0] | (.path // .file) // empty')"
  [[ -n "$path" ]] || { fresh=null; return; }
  if [[ -z "$scratch_key" ]]; then
    if [[ "$dialect" == new ]]; then
      scratch_key="$(rd new tracks.insert '{"type":"audio","name":"FE5 scratch"}' 2>/dev/null </dev/null | jq -c '.ref // empty')"
    else
      rd old tracks.create '{"type":"audio","position":-1,"name":"FE5 scratch"}' >/dev/null 2>&1 </dev/null || true
      scratch_key="$(rd old tracks.list 2>/dev/null </dev/null | jq -r '[.tracks[]? | select((.ref.name // .name) == "FE5 scratch")][0] | (.trackId // .id // .ref.id) // empty')"
    fi
  fi
  [[ -n "$scratch_key" ]] || { fresh=null; return; }
  if [[ "$dialect" == new ]]; then
    inserted="$(rd new devices.insert "$(jq -nc --arg f "$F_INSERT_TO" --argjson t "$scratch_key" --arg p "$path" '{($f): $t, kind: "file", path: $p}')" 2>/dev/null </dev/null)" || { fresh=null; return; }
    row="$(jq -c --arg n "$name" '{ref: .ref, name: $n}' <<<"$inserted")"
  else
    rd old devices.insert "$(jq -nc --arg t "$scratch_key" --arg p "$path" '{trackId: $t, expectedDeviceCount: 0, kind: "file", path: $p}')" >/dev/null 2>&1 </dev/null || { fresh=null; return; }
    row="$(jq -nc --arg n "$name" '{index: 0, name: $n}')"
  fi
  fresh="$(param_values "$(read_state "scratch-$slug" parameters.list "$(parameter_args "$scratch_key" "$row")")")"
}

compressor_found=false
sidechain_ok=false
moved=false
moved_known=true
if [[ -n "$drums" ]]; then
  drums_id="$(track_id "$drums")"
  while read -r device; do
    [[ -n "$device" ]] || continue
    jq -e '.name | test("compressor|dynamics"; "i")' <<<"$device" >/dev/null || continue
    compressor_found=true
    name="$(jq -r .name <<<"$device")"
    slug="$(jq -r '.name | gsub("[^A-Za-z0-9]"; "_")' <<<"$device")"
    sc="$(read_state "sidechain-$slug" devices.sidechain.get "$(device_args "$bass" "$device")")"
    if jq -e --arg id "$drums_id" '[.. | strings] | any(. == $id or . == "Drums")' <<<"$sc" >/dev/null; then sidechain_ok=true; fi
    mine="$(param_values "$(read_state "dynamics-$slug" parameters.list "$(parameter_args "$bass" "$device")")")"
    scratch_params "$name"
    if [[ "$fresh" == null ]]; then moved_known=false
    elif jq -e --argjson f "$fresh" '[to_entries[] | select(($f[.key] // null) != null and ((.value - $f[.key]) | fabs) > 0.000001)] | length > 0' <<<"$mine" >/dev/null; then moved=true; fi
  done <<<"$device_rows"
fi
scratch_deleted=null
if [[ -n "$scratch_key" ]]; then
  if [[ "$dialect" == new ]]; then target="$(jq -nc --argjson t "$scratch_key" '{target: $t}')"; else target="$(jq -nc --arg t "$scratch_key" '{trackId: $t}')"; fi
  if rd "$dialect" tracks.delete "$target" >"$raw/scratch-delete.json" 2>"$raw/scratch-delete.err" </dev/null; then scratch_deleted=true; else scratch_deleted=false; fi
fi
t4_strong=fail
if [[ "$sidechain_ok" == true && "$moved" == true ]]; then t4_strong=pass
elif [[ "$sidechain_ok" == true && "$moved_known" == false ]]; then t4_strong=review; fi
put t4 "$(verdict "$sidechain_ok")" "{\"drums\":$([[ -n "$drums" ]] && echo true || echo false),\"strong\":\"$t4_strong\",\"dynamicsMoved\":$([[ "$moved_known" == true ]] && echo "$moved" || echo null),\"scratchDeleted\":$scratch_deleted,\"goals\":{\"compressor\":$compressor_found,\"sidechain\":$sidechain_ok,\"dynamics\":$moved}}"

# T5: a WAV in the export directory whose integrated loudness measures; strong adds a Bass-only render
wav="$(find "$export_dir" -maxdepth 2 -name '*.wav' -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -n 1 | cut -d' ' -f2-)"
lufs=null
bass_lufs=null
bass_render=none
if [[ -n "$wav" ]]; then
  analysis="$(read_state loudness audio.analyze "$(jq -nc --arg p "$wav" '{path: $p, mode: "loudness"}')")"
  lufs="$(jq -c '[.. | objects | to_entries[] | select(.key | test("integrated|lufs"; "i")) | .value | numbers][0] // null' <<<"$analysis")"
  duration="$(jq -r '[.. | objects | .durationSeconds? | numbers][0] // 16' <<<"$analysis")"
  tempo="$(rd "$dialect" transport.get '{}' 2>/dev/null </dev/null | jq -r '.tempoBpm // 120' 2>/dev/null || echo 120)"
  if [[ -n "$bass" ]]; then
    bass_dir="$(cd "$raw" && pwd)/bass-only"
    mkdir -p "$bass_dir"
    end_beats="$(jq -nr --argjson d "$duration" --argjson t "${tempo:-120}" '($d * $t / 60 * 1000 | round) / 1000')"
    if [[ "$dialect" == new ]]; then
      export_args="$(jq -nc --argjson t "$bass" --arg d "$bass_dir" --argjson e "$end_beats" '{sources: [$t], directory: $d, baseName: "bass-only", startBeats: 0, endBeats: $e, fileType: "wav", overwrite: true}')"
      ok='.outcome == "applied"'
    else
      export_args="$(jq -nc --arg t "$(track_id "$bass")" --arg d "$bass_dir" --argjson e "$end_beats" '{sources: [$t], directory: $d, baseName: "bass-only", startBeats: 0, endBeats: $e, format: "wav", overwrite: true, timeoutMs: 60000}')"
      ok='.exported == true'
    fi
    bass_render=export_failed
    if read_state bass-export export.audio "$export_args" | jq -e "$ok" >/dev/null 2>&1; then
      bass_render=exported
      bass_lufs="$(read_state bass-loudness audio.analyze "$(jq -nc --arg p "$bass_dir/bass-only.wav" '{path: $p, mode: "loudness"}')" | jq -c '[.. | objects | to_entries[] | select(.key | test("integrated|lufs"; "i")) | .value | numbers][0] // null')"
    fi
  fi
fi
measured="$(jq '. != null and . > -70' <<<"$lufs")"
t5_strong="$(verdict "$(jq -n --argjson l "$lufs" --argjson b "$bass_lufs" '$l != null and $l > -70 and $b != null and $b > -40')")"
put t5 "$(verdict "$measured")" "{\"file\":$(jq -Rn --arg w "$wav" '$w // null'),\"lufs\":$lufs,\"bassRender\":\"$bass_render\",\"bassLufs\":$bass_lufs,\"strong\":\"$t5_strong\",\"goals\":{\"export\":$([[ -n "$wav" ]] && echo true || echo false)}}"

jq -c --arg cond "$cond" '{condition: $cond} + .' <<<"$results"
