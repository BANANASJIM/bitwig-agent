# MIDI, instrument parts and clips

Method fields, limits and result shapes come from `<path> --help`; this page is the workflow and the reasons. Targets and write results: `bitwig-agent help refs`, `bitwig-agent help outcomes`.

## Edit the clip the user just recorded

1. `bitwig-agent context get` once. It shows the project label, transport and safety flags, tracks with occupied launcher slots, `selectedTrack` and `cursorClip`, and it does not move the selection. "The clip I just recorded" is `cursorClip`.
2. Check playback and the recording and automation-write flags before writing. Unknown is not false. Do not change tempo, start playback or switch safety modes just to write notes.
3. `notes read` the clip, then `notes edit` only the cells that change. Echoing rows you read counts as `already_matched`. Read the result's `outcome`; it names the cells it could not verify, so read back only those.

Note calls select the clip in the editor (that is what moves `cursorClip`), so do not run them alongside other work that depends on the selection. A notes read is a bounded window, not a backup: keep the original clip and edit a copy when the change is creative, and never clear all notes to make room.

## Make a new part

1. **Find a source that fits the job.** A playable synth is not automatically a distorted guitar. Prefer an exact installed preset: `presets search`, then `devices insert` with `kind` `file` and the hit's insert path. Native types come from `devices catalog`; a display name is not an id. If nothing fits the requested sound, deliver a labeled draft and name the gap instead of calling it done.
2. **Insert one device, then configure it.** `devices insert` on the track (position defaults to the end), read the outcome and the new ref, then read Remote Controls or `parameters list` and change only the few values that differ. Pass the refs the reads returned.
3. **Audition a short A/B before expanding.** Same notes, same passage, matched level, see [controlled A/B](sound-design.md#controlled-ab). Parameter readback is not style; a reviewer without audio cannot pass the sound.
4. **Write the phrase once.** `launcher list` finds an empty slot; `clips insert` creates the named clip with its `notes` in the same call. Compose the phrase off the host and send it in one call. A slot ref needs an existing scene (`scenes insert` adds at the end). Then one `notes read` of the affected range.

Research (finding preset files, planning the melody) can run in parallel with preparing the track. Only one writer owns the host. Missing presets do not authorize installing packages or changing global audio settings.

An operation that succeeded is not a musical success. A lead built from Polysynth, Distortion and Reverb passed every readback and was still rejected as "not black metal", because the source was never judged against the style.

## Variants and edits

- Copy, then transform, instead of resending notes: `clips copy` to an empty slot, then `clips transpose`, `clips quantize` or `clips transform`.
- Quantize recorded takes before editing; `notes read` snaps onsets to cells. `clips quantize` `amount` morphs toward Bitwig's grid.
- `clips set` changes name, color, loop and launch behavior of one clip in one call, each field with its own outcome.
- Scene work: `scenes list`, then `scenes set`, `scenes launch`, `scenes duplicate`, `scenes delete` on scene refs. A scene launch is not a one-clip audition: it plays every track's slot in that row.

## Building a small sketch

1. Fix tempo, meter, roles and length from the user's request; mark any default you chose. Do not change a project's tempo or key to match a recipe.
2. Instrument tracks for notes, audio tracks for recordings, hybrid when both. Name tracks and clips usefully and keep occupied slots untouched.
3. Notes alone make no sound: put a suitable instrument at the intended point of the chain first. For chords check polyphony and release overlap; a pad preset may be mono.
4. Start with a small rhythm, harmony or melody, then add density. In Bitwig 6.0 Spray Can follows the current beat grid; Step Input enters notes at that grid.
5. For silence check mute and solo, the active sequencer, the note channel, instrument availability and polyphony, and output routing before rewriting notes or raising gain.

## Keep candidates independent

In Bitwig 6.0 clips can share a pattern (the paperclip icon, the Inspector pattern chooser). Edit shared content and every alias changes. Make B with ordinary Duplicate or `clips copy`, not an alias variant, and use Make Unique if B already shares a pattern. Do not run Merge Duplicate Patterns during a comparison. A duplicate clip on the same track still shares that track's devices and mixer, so a sound A/B needs independent device or track state too. Launcher-to-Arranger capture can create aliases when "Record to Arranger as Alias Clips" is on.
