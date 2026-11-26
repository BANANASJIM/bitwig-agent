# Mixing, routing, silent checks and export

Fields and limits: `<path> --help`. Level and pan values: `bitwig-agent help values`.

## Route and balance before adding processing

Trace the real path: notes, instrument, device chain, track output or group, master, monitor output. A send is a parallel route: it does not reduce the main output, and a disabled send passes nothing. Lowering a fader does not remove a pre-fader send. Pre/post send mode is not exposed.

- Start conservative, especially with layered oscillators, chords and long releases. Balance parts in context before compression or limiting, and leave headroom. Turning the master down does not fix upstream clipping or feedback.
- Before blaming the composition check mute, solo, disabled devices, output destinations, crossfader assignment and monitoring.
- Levels: `tracks set` takes `volume` and `pan` as the read's bare 0..1 value or `{display}` (`"-6 dB"`, pan `"C"`); `tracks sends set` takes `value` the same way. A level write turns a disabled send on and reports it. Pan 0.5 is centre.
- Bus processing: `tracks group` groups adjacent tracks of one parent, then put shared processing on the group track. Audio clips on a group lane override the group's summed output while they play, and group meta-clips act on their children, so treat group selection as a wider target.
- Resample, bus and sidechain routes: read before writing. `tracks routing get --includeChoices` lists the track's output and input entries and which ones Bitwig's own cycle check accepts; `tracks routing set` then takes the new `output` or `input`. For ducking, `devices sidechain get` lists a device's sidechain choosers and `devices sidechain set` points one at a track tap. Hardware ports and the Grid Audio Sidechain module are not settable. Afterwards confirm with `meters get` or a capture that signal actually arrives.
- `expect` on routing and sidechain writes protects a route the user may have just changed by hand.

Meters and peak readings are observations, not loudness, tonal balance or hearing safety. Gain-match A/B with a measured loudness, not fader positions.

## Check the sound without the user hearing it (Linux, PipeWire)

1. `bitwig-agent audio route --mode=silent` moves Bitwig's outputs to a null sink.
2. Build or edit. Module and cable edits need a stopped transport; plain value writes do not.
3. `bitwig-agent audio capture` records only Bitwig's own outputs to an absolute `outPath` (`soloTrack` solos one track and restores the solos).
4. `bitwig-agent audio analyze` the file: `level` for silence and clipping, `onsets` for timing, `mono_pitch` with `bpm` for notes and intervals, `loudness` for LUFS and true peak, `bands` for spectrum, `probe` to scope a logic or CV signal sent to Audio Out.
5. `bitwig-agent audio route --mode=restore` when finished, or Bitwig stays muted for the user.

Skip warm-up with `skipSeconds`. Pitch analysis is for monophonic, fundamental-dominant material only; an empty `silent` result usually means the route or signal path, not the analysis. Analysis is measurement: it cannot say a sound is good, so report "No audio audition performed" unless someone listened.

## Bounce and export

- **Bounce** (`tracks bounce`) prints a range of audio or instrument tracks in place and replaces what was there. Choose the tap deliberately (`pre_fx`, `pre_fader`, `post_fader`). An instrument track becomes Hybrid. Keep editable originals for creative comparisons; it is not a substitute for saving.
- **Export** (`export audio`) renders Arranger material, not a selected launcher performance: the master and/or tracks over a beat range, one file per source. It needs a stopped transport and an existing directory, and refuses to overwrite unless told. Include release tails in the range, and say whether stems carry the processing the recipient expects.
- A long job returns `unknown` with a `job`: follow it with `jobs get` instead of retrying. Check the file's real extent against the intended range; a loop length alone does not set render length.
- For numbers, `audio analyze --mode=loudness` on the exported file (BS.1770 LUFS, true-peak estimate, LRA). That is a file measurement, not Bitwig's meter.

## Preserve the work

Save only to the authorized project. `project save` once (never re-invoked), or `project saveAs` for an untitled project. A project file comes with a folder of generated media, so the file alone may be incomplete: use Collect and Save when authorized, check Plug-ins for missing dependencies and do not run Delete Unused as routine cleanup. DAWproject export is interchange, not a lossless copy. Keep GUI completion, audio analysis, listening and save/reopen evidence separate in the report.
