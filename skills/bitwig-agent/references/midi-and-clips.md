# MIDI, instrument parts and clips

Fields, limits and result shapes come from `<path> --help`; this page is the workflow and the reasons. Targets and write results: `bitwig-agent help refs`, `bitwig-agent help outcomes`.

## Contents

- Edit the clip the user just recorded
- Make a new part
- Variants and edits
- Loop + operators
- Build a small sketch
- Keep candidates independent

## Edit the clip the user just recorded

1. `bitwig-agent context get` once: it answers "what is open and selected" and does not move the selection. "The clip I just recorded" is its `cursorClip`.
2. Check playback and the recording and automation-write flags before writing. Unknown is not false. Do not change tempo, start playback or switch safety modes just to write notes.
3. `notes get` the clip: every note, as records `{beat, pitch, velocity, duration, channel?}`. Then write with the same records:
   - Change some notes (velocity, length) or add notes: `notes set` with just those notes and no `range`. Nothing else is touched or removed; `velocity` or `duration` may be left out to keep the current value.
   - Rewrite a passage, delete or move notes: `notes get` the range, edit the list, `notes set` the same `range` (`{start, end, pitches?}`; `pitches:[42,42]` is one drum row). The range becomes exactly your list.
4. Read the result: `summary {inserted, updated, removed, unchanged}` says what changed, so you need not read the clip again to confirm the rest is intact. `already_matched` means it was already so. After `unknown`, `notes get` before anything else; never resend blindly.

**Look at a pattern with the grid view.** `notes get --format=grid` (MCP: `format:"grid"`) prints the same notes as a pitch x step grid: one row per pitch, `X`/`x`/`o` onsets by velocity, `-` held cells, beats numbered from 1 (column `1` is beat 0). Use it to check a drum pattern or a phrase at a glance, or to see what a `notes set` did to a passage. It is read-only: never edit or copy the text into a write. To change notes, read the JSON records (`notes get` without `format`) and write records with `notes set`. Onsets that are not on the shown step are not drawn; the `off-grid` line lists them with their exact beat, velocity and duration, so a humanized take shows up there rather than being rounded. `--format=tsv --fields=beat,pitch,velocity` is the compact table of the records when you need the numbers.

A `range` write deletes what you leave out of it, so give the smallest range that holds the change, and on a clip the user recorded keep the original and edit a copy when the change is creative. If `exact` is false the host has no native note binding. Without the native binding notes are read and written on the 1/128-beat grid (`exact:false`, `onsetResolution: 0.0078125`), one onset per cell. Notes past the loop end are not seen (the read says it is incomplete), a write takes a range inside the loop instead of `"all"`, and these calls select the clip (moving `cursorClip`), so do not run them alongside work that depends on the selection.

## Make a new part

1. **Find a source that fits the job.** A playable synth is not automatically a distorted guitar. Prefer an exact installed preset (`presets search`, then `devices insert` with the hit's `insert` object unchanged; `--params='{"Frequency":{"display":"800 Hz"},"Resonance":0.3}'` on `Filter` sets starting values by `parameters list` name in the same call); native types come from `devices catalog`, and a display name is not an id. If nothing fits the requested sound, deliver a labeled draft and name the gap instead of calling it done. Finish a small instrument-and-notes loop before researching a custom Grid graph.
2. **Insert one device, then configure it.** Read the outcome and the new ref, then read Remote Controls or `parameters list` and change only the few values that differ, with the refs those reads returned.
3. **Audition a short A/B before expanding.** Same notes, same passage, matched level: [controlled A/B](sound-design.md#controlled-ab). Parameter readback is not style, and a reviewer without audio cannot pass the sound.
4. **Write the phrase once.** `launcher list` finds an empty slot; `clips insert` creates the named clip with its `notes` in one call, so compose the phrase off the host first. Then one `notes get` of the affected range.

Research (finding presets, planning the melody) can run in parallel with preparing the track; only one writer owns the host. Missing presets do not authorize installing packages or changing global audio settings. A succeeded operation is not a musical success: a lead built from Polysynth, Distortion and Reverb passed every readback and was still rejected as "not black metal", because the source was never judged against the style.

**Browser checks.** The Browser Panel and the context-sensitive Pop-up Browser are different entry points: check the source, filters, insertion or replacement context and the selected result. A missing hit may be a filter or context issue, not a missing install. Samples, instruments, presets, note clips and audio clips are different material, so do not drop a sample onto a device when you meant an audio clip. Previewing does not prove the item went into the right chain. Installing packages is a separate decision.

## Variants and edits

- Copy, then transform, instead of resending notes: `clips copy`, then `clips transpose`, `clips quantize` or `clips transform` (op doubleContent repeats the content in a doubled loop). The host does each transform; read the result's `compared` before and after counts instead of re-reading notes.
- Prefer a short loop over writing every repetition ([loop + operators](#loop--operators)), and keep `range` writes small. `notes get` with `exact:false` snaps onsets to cells: quantize recorded takes before editing them on that path.
- `clips set` changes one clip's name, color, loop and launch behavior, each field with its own outcome.
- Scenes: `scenes list`, then `scenes set`, `scenes launch`, `scenes duplicate`, `scenes delete`. A scene launch is not a one-clip audition: it plays every track's slot in that row.

## Loop + operators

Write variation as rules on a short loop, not as more notes. Bitwig plays the rule and rolls the dice; the record keeps the rule, so a read shows what you wrote and a replay is `already_matched`. Never pick random notes or velocities yourself to fake variation.

| Want | Field on the note |
|---|---|
| a hat that sometimes drops out | `chance: 0.7` |
| velocity that varies around its value | `velocitySpread: 0.2` |
| a ratchet or roll | `repeat: {count: 3, velocityEnd: -0.4}` (`count` is needed to turn repeats on) |
| a fill only on the last of four passes | `recurrence: {length: 4, mask: [false, false, false, true]}` |
| only on the first pass, only after the previous note played, only with Fill | `occurrence: "first"`, `"prev"`, `"fill"` |
| bend, pressure, pan at the note start (native path only) | `transpose: 12`, `pressure: 0.6`, `pan: -0.5` |

1. Make or find a 1- or 2-bar clip with the basic pattern (`clips insert` with `notes`).
2. Add the operators with `notes set` and no `range`, listing only `beat`, `pitch` (and `channel`) plus the fields: `{"beat":0.5,"pitch":42,"chance":0.7}`. Omitted fields keep their values, so nothing else changes.
3. Turn one off with `null` (`"chance": null`), not by leaving it out. With `range` a listed note is the whole note: operators it does not name are turned off, so `notes get` the range first and edit that list.
4. `notes get` lists only operators that are on, so the read is the full rule set. A 1-bar clip with one `recurrence` fill reads as 4 bars of variation.

Without the native note binding (`exact:false`) operators still work, but expressions are refused and not read (`unreadFields`).

## Build a small sketch

1. Fix tempo, meter, roles and length from the user's request and mark any default you chose. Do not change a project's tempo or key to match a recipe.
2. Instrument tracks for notes, audio tracks for recordings, hybrid when both. Name tracks and clips usefully and keep occupied slots untouched.
3. Notes alone make no sound: put a suitable instrument at the intended point of the chain first. For chords check polyphony and release overlap; a pad preset may be mono.
4. Start with a small rhythm, harmony or melody, then add density. Spray Can follows the current beat grid and Step Input enters notes at that grid (6.0).
5. For silence start at the layers in [troubleshooting](troubleshooting.md): mute and solo, the active sequencer, the note channel, instrument and polyphony, then output routing, before rewriting notes or raising gain.

**Key guidance (6.0).** The project's root note and scale guide Snap to Key (`K`), but Quantize to Key rewrites existing notes, so do it only on purpose. Note FX with Use Global Key read offsets as scale degrees, not semitones. Setting a key does not retune what already exists.

## Keep candidates independent

In 6.0 note, audio and automation clips can share a pattern (the paperclip icon, the Inspector pattern chooser). Editing shared content changes every alias, and name and color are shared too while start, length and loop can differ. Make B with ordinary Duplicate or `clips copy`, not an alias variant. If B shares a pattern, select B alone and Make Unique; a unique pattern shows a fingerprint indicator instead of a usage count. Check related automation patterns too, and do not run Merge Duplicate Patterns during a comparison. A duplicate clip on the same track still shares that track's devices and mixer, so a sound A/B needs independent device or track state. Launcher-to-Arranger capture can create aliases when "Record to Arranger as Alias Clips" is on.
