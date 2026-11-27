# MIDI, instrument parts and clips

Fields, limits and result shapes come from `<path> --help`; this page is the workflow and the reasons. Targets and write results: `bitwig-agent help refs`, `bitwig-agent help outcomes`.

## Contents

- Edit the clip the user just recorded
- Make a new part
- Variants and edits
- Build a small sketch
- Keep candidates independent

## Edit the clip the user just recorded

1. `bitwig-agent context get` once: it answers "what is open and selected" and does not move the selection. "The clip I just recorded" is its `cursorClip`.
2. Check playback and the recording and automation-write flags before writing. Unknown is not false. Do not change tempo, start playback or switch safety modes just to write notes.
3. `notes read` the clip, then `notes edit` only the cells that change. Read the result's `outcome`; it names the cells it could not verify, so read back only those.

Note calls select the clip in the editor (that is what moves `cursorClip`), so do not run them alongside work that depends on the selection. A notes read is a bounded window, not a backup: keep the original clip and edit a copy when the change is creative, and never clear all notes to make room.

## Make a new part

1. **Find a source that fits the job.** A playable synth is not automatically a distorted guitar. Prefer an exact installed preset (`presets search`, then `devices insert` with the hit's `insert` object unchanged); native types come from `devices catalog`, and a display name is not an id. If nothing fits the requested sound, deliver a labeled draft and name the gap instead of calling it done. Finish a small instrument-and-notes loop before researching a custom Grid graph.
2. **Insert one device, then configure it.** Read the outcome and the new ref, then read Remote Controls or `parameters list` and change only the few values that differ, with the refs those reads returned.
3. **Audition a short A/B before expanding.** Same notes, same passage, matched level: [controlled A/B](sound-design.md#controlled-ab). Parameter readback is not style, and a reviewer without audio cannot pass the sound.
4. **Write the phrase once.** `launcher list` finds an empty slot; `clips insert` creates the named clip with its `notes` in one call, so compose the phrase off the host first. Then one `notes read` of the affected range.

Research (finding presets, planning the melody) can run in parallel with preparing the track; only one writer owns the host. Missing presets do not authorize installing packages or changing global audio settings. A succeeded operation is not a musical success: a lead built from Polysynth, Distortion and Reverb passed every readback and was still rejected as "not black metal", because the source was never judged against the style.

**Browser checks.** The Browser Panel and the context-sensitive Pop-up Browser are different entry points: check the source, filters, insertion or replacement context and the selected result. A missing hit may be a filter or context issue, not a missing install. Samples, instruments, presets, note clips and audio clips are different material, so do not drop a sample onto a device when you meant an audio clip. Previewing does not prove the item went into the right chain. Installing packages is a separate decision.

## Variants and edits

- Copy, then transform, instead of resending notes: `clips copy`, then `clips transpose`, `clips quantize` or `clips transform`.
- Quantize recorded takes before editing; `notes read` snaps onsets to cells.
- `clips set` changes one clip's name, color, loop and launch behavior, each field with its own outcome.
- Scenes: `scenes list`, then `scenes set`, `scenes launch`, `scenes duplicate`, `scenes delete`. A scene launch is not a one-clip audition: it plays every track's slot in that row.

## Build a small sketch

1. Fix tempo, meter, roles and length from the user's request and mark any default you chose. Do not change a project's tempo or key to match a recipe.
2. Instrument tracks for notes, audio tracks for recordings, hybrid when both. Name tracks and clips usefully and keep occupied slots untouched.
3. Notes alone make no sound: put a suitable instrument at the intended point of the chain first. For chords check polyphony and release overlap; a pad preset may be mono.
4. Start with a small rhythm, harmony or melody, then add density. Spray Can follows the current beat grid and Step Input enters notes at that grid (6.0).
5. For silence start at the layers in [troubleshooting](troubleshooting.md): mute and solo, the active sequencer, the note channel, instrument and polyphony, then output routing, before rewriting notes or raising gain.

**Key guidance (6.0).** The project's root note and scale guide Snap to Key (`K`), but Quantize to Key rewrites existing notes, so do it only on purpose. Note FX with Use Global Key read offsets as scale degrees, not semitones. Setting a key does not retune what already exists.

## Keep candidates independent

In 6.0 note, audio and automation clips can share a pattern (the paperclip icon, the Inspector pattern chooser). Editing shared content changes every alias, and name and color are shared too while start, length and loop can differ. Make B with ordinary Duplicate or `clips copy`, not an alias variant. If B shares a pattern, select B alone and Make Unique; a unique pattern shows a fingerprint indicator instead of a usage count. Check related automation patterns too, and do not run Merge Duplicate Patterns during a comparison. A duplicate clip on the same track still shares that track's devices and mixer, so a sound A/B needs independent device or track state. Launcher-to-Arranger capture can create aliases when "Record to Arranger as Alias Clips" is on.
