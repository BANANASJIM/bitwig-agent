# Mixing, routing, silent checks and export

Fields, limits and units come from `<path> --help`; level and pan values from `bitwig-agent help values`.

## Route and balance before adding processing

Trace the real path: notes, instrument, device chain, track output or group, master, monitor output. A send is a parallel route: it does not reduce the main output, and a disabled send passes nothing. Lowering a fader does not remove a pre-fader send. Pre/post send mode is not exposed.

- Start conservative, especially with layered oscillators, chords and long releases. Balance parts in context before compression or limiting, and leave headroom. Turning the master down does not fix upstream clipping or feedback.
- Before blaming the composition check mute, solo, disabled devices, output destinations, crossfader assignment and monitoring.
- Read the mix in one call: `tracks list --format=tsv --fields=name,type,volume.display,pan.display,mute,solo` (rows also carry `arm` and `sends`).
- Levels: `tracks set` and `tracks sends set` take the value a read returned or a `{display}` (`bitwig-agent help values`; pan 0.5 is centre). A send's pre/post-fader tap is `tracks sends set --mode=...`, one of the `mode.options` its `tracks get` row lists; pre-fader keeps feeding a reverb or a cue mix when the fader comes down. Gain-match an A/B by measured loudness, not by fader positions.
- Several tracks at once: one `batch`, one write per track, run in order and stopped at the first that fails. CLI: `bitwig-agent tracks list | jq -c '.tracks[] | {method:"tracks.set",params:{target:.ref,mute:true}}' | bitwig-agent batch`. MCP: the `batch` tool with `calls=[{"method":"tracks.set","params":{"target":<ref>,"mute":true}}, ...]`, not parallel tool calls (those have no order and do not stop).
- Bus processing: `tracks group` groups adjacent tracks of one parent, then put shared processing on the group track. Audio clips on a group lane override the group's summed output while they play, and group meta-clips act on their children, so treat group selection as a wider target.
- Track structure: `tracks duplicate` copies a track (clips, devices, settings) right after itself; `tracks ungroup` dissolves a group in place and `tracks delete` on a group removes it with its children; `tracks convert --to=hybrid` makes an audio or instrument track hold both clip kinds; Bitwig refuses a conversion that would drop clips (`not_applied`, `host_refused`: remove those clips first). `tracks delete`/`tracks ungroup` on a collapsed group is `not_ready` until the user expands it (groups come back collapsed after Undo). Convert and ungroup put the user's track selection back; if a result says `selectionRestored: false`, tell the user their selection changed. Each is one Undo step.
- Resample, bus and sidechain routes: read before writing. Only some stock devices have an audio sidechain input (Compressor+, Dynamics, Gate, Transient Control; the plain Compressor has none): insert one of those first. `tracks routing get --includeChoices` and `devices sidechain get` list the valid choices (and, for sidechain choosers, whether each is `writable`); then pass an entry from that read (`{kind, track: <track ref>}`) unchanged to `tracks routing set` or `devices sidechain set`. A routing write needs a stopped transport; a sidechain write only needs recording and automation write off. Afterwards confirm with `meters get` or a capture that signal actually arrives.
- `expect` on routing and sidechain writes protects a route the user may have just changed by hand.

Meters and peak readings are observations, not loudness, tonal balance or hearing safety. Gain-match A/B with a measured loudness, not fader positions.

## Check the sound without the user hearing it (Linux, PipeWire)

1. `bitwig-agent audio route --mode=silent` moves Bitwig's outputs to a null sink.
2. Build or edit. Module and cable edits need a stopped transport; plain value writes do not.
3. `bitwig-agent audio capture` records only Bitwig's own outputs; `soloTrack` isolates one track.
4. `bitwig-agent audio analyze` the file; `audio analyze --help` lists the modes (silence and clipping, onsets, pitch, loudness, spectrum, a scope for logic or CV signals). Pick the mode that answers your question.
5. `bitwig-agent audio route --mode=restore` when finished, or Bitwig stays muted for the user.

To check an effect over time (does the sidechain duck on every beat?), capture the same span with and without it and compare: `audio analyze --mode=level --bpm=128 --windowBeats=1 --reference=/tmp/dry.wav` gives each beat's `deltaDb` and a `summary`. Skip warm-up with `skipSeconds`. Pitch analysis is for monophonic, fundamental-dominant material only; an empty `silent` result usually means the route or signal path, not the analysis. Analysis is measurement: it cannot say a sound is good, so report "No audio audition performed" unless someone listened.

## Bounce and export

- **Bounce** (`tracks bounce`) prints a range in place and replaces what was there, and an instrument track becomes Hybrid. Choose the tap deliberately and keep editable originals for creative comparisons. It is not a substitute for saving.
- **Export** (`export audio`) renders each track as it is set to play: the Arranger timeline only while the track follows the arranger. A track playing a launcher clip renders that clip instead, and a track stopped from the launcher (`launcher stop`) renders silent whatever the arranger holds; `launcher returnToArrangement --target=all` puts every track back on the arranger (nothing does it for you; `launcher stop --target=all` is the global stop), and `clips copy --to='{"track":...,"start":N}'` puts a launcher clip on the arranger where it is empty in range. The export still writes its files but warns per affected track, naming the state and the fix, with a `next`; fix, then export again with `overwrite`. Include release tails in the range and say whether stems carry the processing the recipient expects. Its `--help` lists the refusals (playing transport, existing files, missing directory). `--fileType` takes `wav`, `flac`, `mp3`, `ogg` or `opus`, or a list (`'["wav","mp3"]'`) rendered in one pass; mp3/ogg/opus are only checked for their container and codec, so export a WAV alongside when the level matters.
- A long job returns `unknown` with a `job`: follow it with `jobs get` instead of retrying. Check the file's real extent against the intended range; a loop length alone does not set render length.
- For numbers, `audio analyze --mode=loudness` on the exported WAV (BS.1770 LUFS, true-peak estimate, LRA). That is a file measurement, not Bitwig's meter.

## Preserve the work

Save only to the authorized project. `project save` once (never re-invoked), or `project saveAs` for an untitled project. A project file comes with a folder of generated media, so the file alone may be incomplete: use Collect and Save when authorized, check Plug-ins for missing dependencies and do not run Delete Unused as routine cleanup. DAWproject export is interchange, not a lossless copy. Keep GUI completion, audio analysis, listening and save/reopen evidence separate in the report.
