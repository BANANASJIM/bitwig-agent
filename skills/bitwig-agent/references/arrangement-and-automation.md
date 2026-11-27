# Arrangement, launcher ownership, audio and automation

Fields and limits: `<path> --help`. These are production recommendations and Bitwig UI behaviors (manual 5.3, superseded by the 6.0 and 6.1 release notes, see [version-and-sources](version-and-sources.md)), not a list of supported methods. Shortcuts are defaults: check platform, keymap and the focused panel first.

## Contents

- Orient before editing
- Launcher and Arranger
- Arrange and capture
- Record and edit audio
- Automation: three questions

## Orient before editing

Find the actual project, selected track, clip and device, group level, focused panel, transport, record-arm and automation state; the Inspector says what is selected better than screen position. Hidden FX, deactivated tracks and collapsed groups are not empty space.

In 6.0 the Detail Editor holds the note, audio and automation editors. Its Clip context uses clip time and can show events outside the playable bounds; its Track context uses Arranger time and is unavailable for launcher clips. With several layers selected the bright target header decides where new notes go, so select only the layer you mean. The tool keys are Pointer 1, Time Selection 2, Pencil 3, Spray Can 4, Knife 5, Eraser 6, Audition 7, Step Input 8; older advice that 4 is Eraser is obsolete. Auditioning can start the transport and computer-keyboard MIDI mode can swallow shortcuts.

## Launcher and Arranger

The Launcher is for trying combinations and the Arranger for fixed placement. Each track has one controlling sequencer at a time: launching or stopping a clip takes that track away from the Arranger, and stopping launcher clips does not give it back. Use Return playback to Arranger Timeline. If an Arranger edit is inaudible, check this first.

- Before launching, check project and clip quantization, launch mode, release and Next Actions. A scene touches many tracks, and an empty slot can stop clips or record on armed tracks.
- A launch or stop may queue to a quantization boundary, so `clips launch` and `scenes launch` return `sent`, which is not "playing". Observe the transition before dependent edits. A stopped playhead is not silence while tails ring.
- Quantization defaults live on the project (`transport set`) and can be overridden per clip (`clips set`); `<path> --help` lists the fields.

## Arrange and capture

1. Stop the transport. `clips copy` and `clips insert` take `to` as a slot or a track and start beat; `--help` shows both forms. Read the whole arrangement with `arranger list` (no target: every track's clips by start, with `bar`, markers, tempo and time signature) before placing sections.
2. Overlaps are refused, not overwritten: lay sections end to end and read the lane back.
3. Before capturing a launcher performance check the recording start, existing material, record arms, automation writing and the alias-capture option.
4. Afterwards read the arranger clips and return tracks to Arranger control before judging the linear arrangement. `markers insert` makes form and export range explicit.

## Record and edit audio

Before recording, identify the exact input, channel choice, monitoring mode and destination, and never feed a monitored output back into its input. Check every armed track and the existing content. Decide overdub, loop or punch range and comping before capture. Arranger recording uses track arm, Global Record and the transport (cycle recording uses the Arranger loop); a launcher recording can start the transport or wait for quantization, and launcher comping uses Record as Comping Takes and Take Length. Recording over looping Arranger audio differs from adding takes to non-looping material, so keep the original.

When editing, tell apart a clip's playback window, its audio event and the source file: moving an event is not sliding its content or moving the clip. In 6.0 the Waveform Editor has fade handles at the top corners, gain near the top and content sliding near the bottom, so check the hover action before dragging. Never replace a media file to fix a local edit. If a pitch or stretch edit seems ineffective, check the playback or stretch mode, since not every mode uses every expression. A visible waveform is not a listening result; listen at cuts and loop joins for clicks and truncated tails when you can.

## Automation: three questions

1. **Which lane?** In 6.0 the flying lane can follow the last touched parameter. Read the device and parameter label before drawing; another visible lane does not mean the first curve vanished.
2. **What follows a structural edit?** Global Automation Behavior (`0`) switches Follow and Lock. A lane overrides it with Use editor setting, Always follow, Never follow or Arranger lock. Check which lanes a move, duplicate or resize will carry. Free Time lets an automation clip's timing diverge from its parent. These settings govern editing relationships only, not whether automation plays or records.
3. **Who controls the parameter now?** Moving an automated parameter by hand overrides playback until Restore Automation Control (`R` in 6.0). That is not Undo, and the global button restores every overridden parameter.

6.0 has one Automation Write control for both sequencers, and recording note or audio clips can capture automation (a preference on by default for both), so check it before a performance or tweak. Launcher automation clips launch with their parent but can also be triggered on their own, and a track stop applies to automation lanes too: the audible note loop does not describe every automation loop.

`automation points get` reads a lane and `automation points set` replaces a beat range. Pass `expect.pointsHash` from the read when the user may have edited the lane, so their change is refused-over rather than overwritten (`bitwig-agent help values`).

If a knob will not stay where you set it, look at automation and modulation instead of resending. If a curve exists but seems inactive, check the override state and the controlling sequencer before deleting it.
