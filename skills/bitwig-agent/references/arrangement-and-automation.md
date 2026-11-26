# Arrangement, launcher ownership, audio and automation

Fields and limits: `<path> --help`. These are production recommendations and Bitwig UI behaviors (manual 5.3, superseded by the 6.0 and 6.1 release notes, see [version-and-sources](version-and-sources.md)), not a list of supported methods. Shortcuts are defaults: check platform, keymap and the focused panel first.

## Orient before editing

Find the actual project, selected track, clip and device, group level, focused panel, transport, record-arm and automation state. The Inspector tells you what is selected better than screen position does. Hidden FX, deactivated tracks and collapsed groups are not empty space. In 6.0 the Detail Editor holds note, audio and automation editors; with several layers selected the bright target header decides where new notes go. The 6.0 tool keys are Pointer 1, Time Selection 2, Pencil 3, Spray Can 4, Knife 5, Eraser 6, Audition 7, Step Input 8; older advice that 4 is Eraser is obsolete. Auditioning can start the transport.

## Launcher and Arranger

The Launcher is for trying combinations and the Arranger for fixed placement. Each track has one controlling sequencer at a time: launching or stopping a clip takes that track away from the Arranger, and stopping launcher clips does not give it back. Use Return playback to Arranger Timeline. If an Arranger edit is inaudible, check this first.

- Before launching, check the project and clip quantization, launch mode, release and Next Actions. A scene touches many tracks, and an empty slot can stop clips or record on armed tracks.
- A launch or stop may be queued to a quantization boundary. `clips launch` and `scenes launch` return `sent`: dispatched, not playing. Observe the transition before dependent edits. Launcher stop can leave the global transport running, and tails mean one stopped playhead is not silence.
- `transport set defaultLaunchQuantization` sets the project default; `clips set` `launchQuantization`, `launchMode` and `useLoopStartAsQuantizationReference` override it per clip.

## Arrange and capture

1. Stop the transport. `clips copy` with `to` `{track, startBeats}` places a launcher clip on the same track's timeline as an independent copy; `clips insert` with the same `to` writes a new MIDI clip with notes directly. `clips move`, `clips set` and `clips delete` act on one arranger clip ref from a fresh `arranger list` (creation order, not start order).
2. Overlapping placements are refused, not overwritten: lay sections end to end and read the lane back.
3. Check the recording start, existing material, record arms, automation writing and the alias-capture option before capturing a launcher performance.
4. Afterwards read the arranger clips and return tracks to Arranger control before judging the linear arrangement. Markers (`markers insert`) make form and export range explicit.

## Record and edit audio

Before recording, identify the exact input, channel choice, monitoring mode and destination, and avoid feeding a monitored output back to its input. Check all armed tracks and existing content. Decide overdub, loop or punch range and comping before capture. Recording over looping Arranger audio differs from adding takes to non-looping material, so keep the original. Distinguish a clip's playback window, its audio event and the source file: moving an event is not sliding its content or moving the clip. Do not replace a media file to fix a local edit. A visible waveform is not a listening result; check cuts and loop joins for clicks and truncated tails when you can listen.

## Automation: three questions

1. **Which lane?** In 6.0 the flying lane can follow the last touched parameter. Read the device and parameter label before drawing.
2. **What follows a structural edit?** Global Automation Behavior (`0`) switches Follow and Lock, and each lane can override it. Check which lanes a move, duplicate or resize will carry. Free Time lets an automation clip's timing diverge from its parent.
3. **Who controls the parameter now?** Moving an automated parameter by hand overrides playback until Restore Automation Control (`R` in 6.0). That is not Undo and the global button restores every overridden parameter.

API: `automation points get` reads a track's volume, pan or Grid float-control lane (values 0..1, `linear` or `hold`). `automation points set` replaces the points in a beat range, needs a stopped transport with automation write off, and takes `expect.pointsHash` from the read. Pass it when the user may have edited the lane, so their change is refused-over rather than overwritten.

If a knob will not stay where you set it, look at automation and modulation instead of resending. If a curve exists but seems inactive, check the override state and the controlling sequencer before deleting it. Recording note or audio clips can also capture automation (a preference, on by default for both sequencers), so check it before a performance or tweak.
