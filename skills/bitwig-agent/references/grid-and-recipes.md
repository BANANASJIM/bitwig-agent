# Grid patching judgment and recipes

Spec syntax, value forms, units and limits are in `bitwig-agent help grid.apply` and `bitwig-agent grid describe --help`; do not restate them from memory. This page is how to work, and why.

## Start here

1. **Preset first if one fits.** `presets search`, then the part workflow in [midi-and-clips](midi-and-clips.md#make-a-new-part). Finish a small instrument-and-notes loop before researching a custom Grid graph.
2. **Otherwise build a Grid instrument.** `tracks insert` an instrument track, `devices insert` the Poly Grid with the `insert` object its `devices catalog` row carries, then apply the subtractive bass spec from `bitwig-agent help recipes` (edit its names, values and cables) with `grid apply`; its `pruneAll` replaces the new device's whole graph, so use it only on a device you just inserted.

## Work loop

1. **Read the docs before using an unfamiliar module.** `bitwig-agent grid describe` gives Bitwig's own description of each port and parameter, offline; follow its `--help` for how names are matched.
2. **Write the patch as one spec.** A spec is code: modules, cables and parameters in a file, `bitwig-agent grid apply @patch.json`. A spec that fails validation writes nothing, so apply it directly and read `warnings`; applying again is a diff, so tune the file instead of poking the live device. Use `--dryRun` before `prune`, `pruneAll` or `replace`, which delete, and read its `plan`, `warnings` and `wouldFail` (a certain refusal such as the transport state; a display the host converts itself is only a warning).
3. **Start from the live device.** `bitwig-agent grid export` returns a spec of what is there, with refs. Edit it and apply it back; unchanged parts write nothing.
4. **Look at the layout.** `bitwig-agent grid render` draws the device and summarizes overlaps, unconnected modules and sections. Read the summary; open the image only when you can see it.
5. **Check for silent drops.** After topology writes run `bitwig-agent diagnostics get` and look for `grid_rejected`. A device missing a required input is dropped from the audio network and just goes silent, with no sign in the API. Pass the apply result's `diagnosticsMarker` as `sinceMarker` so you see only what your write caused. Modal dialogs are not in the log: take a screenshot.

## Judgment

- **Change one value with a partial entry** (`name` and `params` only): it never inserts, moves or retypes, which is why it is the safe way to tune a running patch.
- **One spec per Grid device, one namespace per section.** `prune` deletes modules in its namespace that the spec omits, so two specs sharing a namespace delete each other's modules. Keep `prune` out of the first draft.
- **Write values the way the spec allows, never by guess.** Which parameters accept `{display}` and which need a raw value is in `grid apply --help` (per-parameter `rawScale`). Raw numbers are not seconds, hertz or decibels, and enum values are option values, not list positions. Never clamp: an `unsupported_unit` error means write the raw value.
- **Stored cables are not the active graph.** A readable graph does not prove sound; a moving meter does not prove the intended sound.
- **Do not stop at the first working patch.** Build the smallest signal path (see [sound-design](sound-design.md#grid-build-the-smallest-signal-path)) and audition it before adding modules.
- **A failed or `unknown` apply stops.** Inspect with `grid export`, fix the cause, apply again; a topology hold on a device is released with `ack` after inspection.
- **Transport: inserting, deleting, renaming, moving or rewiring modules needs a stopped transport; changing parameter values, step arrays, voicing or partial entries works while playing.** Recording and automation write must be off for both.

## Controls without ports: device modulators

Many controls have no input port (envelope times, step counts, oscillator timbre, drive, pulse width). Vary them with a modulator instead of more modules.

1. `modulators describe` picks a type and explains its controls (offline).
2. One `modulators insert` creates, shapes and maps it: `--to="$DEVICE" --typeName=LFO --params='{"Rate":-0.60206}' --map='[{"target":'"$CUTOFF"',"amount":0.3}]'`. `params` keys are the control names `modulators get` shows (`describe` titles may differ); `map[].target` is a parameter ref from `grid export` on the same device. The result has the modulator `ref` and one entry per step. Write a control in the raw unit `modulators get` reports: LFO `Rate` is log10(Hz) with no display unit (0 is 1 Hz, 0.30103 is 2 Hz, -0.60206 is 0.25 Hz), so write the raw value, not `{display:"0.25 Hz"}`.
3. If a step failed, the modulator exists: finish with what `next` names (`parameters set` on its controls, `modulators map --source=<ref>`), never `modulators insert` again. A misspelt or shared name lists `candidates` and no configure step runs (the inserted modulator still exists).
4. Later changes: `modulators map` again changes the amount (`modulators unmap` removes it; its `--help` says what an omitted field keeps), `parameters set` on a control ref from `modulators list`. Polarity is a parameter of the modulator, not of the mapping.

Modulator topology edits need a stopped, non-recording transport; `parameters set` on a modulator control may run while playing. After `unknown`, inspect `modulators list`; never repeat.

## Macro knobs

`devices remote define` sets a named Remote Controls page whose knobs drive chosen scalar Grid parameters (refs from the export) or the device's own parameters (refs from `parameters list`), on any root device. It is an upsert; `devices remote delete` removes the page. Pages live in the device's preset-page list, which is what the UI's Add New Page creates.

## Debug silent patches

Trace notes, source, envelope and gate, output, track routing, mute and level, and find the first point where the signal vanishes. `bitwig-agent grid probe` listens to one Grid output port and analyzes it, so you can bisect a patch port by port. Typical causes: a missing required input, an envelope never gated, too few voices for chord releases (mono Poly Grid pad), a keytrack filter in a Poly Grid, an ignored phase cable. Deleting a through-processing module can reconnect its neighbours and create a drone. More in [sound-design](sound-design.md).

## Scratch recipe and pad voices

`examples/grid_music.py` in the repository builds two disposable instrument tracks (pluck and pad) from a factory Poly Grid. It is a source-checkout script, not an installed tool: do not invent an executable, and do not run it without permission, a disposable project and its explicit write flag. It stops on `unknown` and never retries.

A pad needs polyphony: four-note chords were missing fundamentals on a mono Poly Grid. Set `voicing` in the spec, for example eight voices, and read it back with `grid export`. Keep per-voice level low for headroom. Neither a corrected case nor a clean readback proves the patch sounds right: audition it.
