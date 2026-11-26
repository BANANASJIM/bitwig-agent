# Grid patching judgment and recipes

Spec syntax, value forms, units and limits are in `bitwig-agent help grid.apply` and `bitwig-agent grid describe --help`; do not restate them from memory. This page is how to work, and why.

## Work loop

1. **Read the docs before using an unfamiliar module.** `bitwig-agent grid describe --type="<module>"` (or `--query`) gives Bitwig's own description of each port and parameter, offline. Search words, not exact names: "low pass", not "Filter".
2. **Write the patch as one spec, dry run first.** A spec is code: modules, cables and parameters in a file, `bitwig-agent grid apply @patch.json --dryRun`, read `warnings` and `wouldFail`, then apply without `--dryRun`. Applying again is a diff, so tune the file instead of poking the live device.
3. **Start from the live device.** `bitwig-agent grid export` returns a spec of what is there, with refs. Edit it and apply it back; unchanged parts write nothing.
4. **Look at the layout.** `bitwig-agent grid render` draws the device and summarizes overlaps, unconnected modules and sections. Read the summary; open the image only when you can see it.
5. **Check for silent drops.** After topology writes run `bitwig-agent diagnostics get` and look for `grid_rejected`. A device missing a required input is dropped from the audio network and just goes silent, with no sign in the API. Pass the apply result's `diagnosticsMarker` as `sinceMarker` so you see only what your write caused. Modal dialogs are not in the log: take a screenshot.

## Judgment

- **Change one value with a partial entry** (`name` and `params` only): it never inserts, moves or retypes, which is why it is the safe way to tune a running patch.
- **One spec per Grid device, one namespace per section.** `prune` deletes modules in its namespace that the spec omits, so two specs sharing a namespace delete each other's modules. Keep `prune` out of the first draft.
- **Use `{display}` only where the unit is real.** For Hz, percent, decibel and time parameters it is converted for you; elsewhere write the raw value or an enum `{label}`. Raw numbers are not seconds, hertz or decibels, and enum values are option values, not list positions. Never guess or clamp: an `unsupported_unit` error means write the raw value.
- **Stored cables are not the active graph.** A readable graph does not prove sound; a moving meter does not prove the intended sound.
- **Do not stop at the first working patch.** Build the smallest signal path (see [sound-design](sound-design.md#grid-build-the-smallest-signal-path)) and audition it before adding modules.
- **A failed or `unknown` apply stops.** Inspect with `grid export`, fix the cause, apply again; a topology hold on a device is released with `ack` after inspection.
- **Topology edits need a stopped, non-recording state.** Value writes may run while playing.

## Controls without ports: device modulators

Many controls have no input port (envelope times, step counts, oscillator timbre, drive, pulse width). Vary them with a modulator instead of more modules.

1. `bitwig-agent modulators describe --query=random` picks a type; `modulators describe --type=Random` explains its controls.
2. `modulators insert` on the device ref returns the modulator ref with its outputs and parameters.
3. `modulators map` takes the modulator ref as `source`, a parameter ref from `grid export` as `target` and an `amount`. A later `modulators map` with a new amount keeps the mapping's other settings; `modulators unmap` removes it.
4. Shape the source with `parameters set` on a parameter ref from `modulators list`. Polarity is a parameter of the modulator, not of the mapping.

Insert, delete, map and unmap need a stopped transport and no recording or automation write. `parameters set` on a modulator control works while playing. After `unknown`, inspect `modulators list`; never repeat.

## Macro knobs

`devices remote define` sets a named Remote Controls page whose knobs drive chosen scalar Grid parameters (up to eight, parameter refs from the export). It is an upsert; `devices remote delete` removes the page. Pages live in the device's preset-page list, which is what the UI's Add New Page creates.

## Debug silent patches

Trace notes, source, envelope and gate, output, track routing, mute and level, and find the first point where the signal vanishes. Typical causes: a missing required input, an envelope never gated, too few voices for chord releases (mono Poly Grid pad), a keytrack filter in a Poly Grid, an ignored phase cable. Deleting a through-processing module can reconnect its neighbours and create a drone. More in [sound-design](sound-design.md).

## Scratch recipe and pad voices

`examples/grid_music.py` in the repository builds two disposable instrument tracks (pluck and pad) from a factory Poly Grid. It is a source-checkout script, not an installed tool: do not invent an executable, and do not run it without permission, a disposable project and its explicit write flag. It stops on `unknown` and never retries.

A pad needs polyphony: four-note chords were missing fundamentals on a mono Poly Grid. Set `voicing` in the spec, for example eight voices, and read it back with `grid export`. Keep per-voice level low for headroom. Neither a corrected case nor a clean readback proves the patch sounds right: audition it.
