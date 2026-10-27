# Production workflows

Use this reference to turn an authorized musical idea into clips, an arrangement and deliverable audio. These are production recommendations and documented **Bitwig UI behaviors**, not a list of supported native methods. Use actual installed schemas and fresh exact targets; retain the [targeting and unknown-outcome rules](targeting-and-evidence.md). A known API gap may use separately authorized GUI control. An unknown native outcome stops the writer; it is never a reason to replay through the GUI.

**Version basis:** the [online manual](https://www.bitwig.com/userguide/latest/) currently identifies itself as **5.3**. The [6.0 release notes](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html) supersede its editor, alias and automation instructions where they differ. Check later installed-version notes for subsequent changes. Shortcuts below are defaults: verify platform, keymap, focused panel and text-entry state before using them. None of this establishes bridge compatibility or authorizes playback, recording, save, render, installation or unrelated project edits.

## 1. Orient before making music

Establish the actual project, selected track/clip/device, group level, focused panel, transport, record-arm and automation state. Use the Inspector to identify the selected object rather than guessing from screen position. Distinguish track selection from clip selection and the device chain from an expanded device editor. Hidden FX/deactivated tracks and collapsed groups are not empty project space.

In **6.0**, the Detail Editor contains note, audio and automation editors; the separate Automation Editor Panel described in the old manual is gone. Its **Clip** context uses clip time and can show events outside the playable bounds; **Track** context uses Arranger time and is unavailable for Launcher clips. With multiple layers selected, the bright target header determines where new notes are inserted; selecting notes can change that target. Select only the intended layer when an edit should not span tracks.

Check the panel's tool, snapping and beat grid before drawing. Adaptive grid changes with zoom. The 6.0 default tool keys are Pointer `1`, Time Selection `2`, Pencil `3`, Spray Can `4`, Knife `5`, Eraser `6`, Audition `7`, Step Input `8`; **old manual advice that `4` selects Eraser is obsolete**. Auditioning can produce sound/start transport, and computer-keyboard MIDI mode can intercept shortcuts.

**Expected observation:** the Inspector and editor header agree on the intended object, timebase and editable layer. If they do not, fix targeting before any content edit.

Sources: [Arrange view and tracks](https://www.bitwig.com/userguide/latest/the_arrange_view_and_tracks), [note recording and keyboard MIDI](https://www.bitwig.com/userguide/latest/recording_clips), [6.0 Editing Improvements and Bringing It All Together](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 2. Build a small, playable sketch

1. Translate the request into a short phrase: tempo, meter, musical roles, length and intended variation. Follow supplied musical choices; make any agent-proposed defaults explicit. Do not change a project's tempo or key merely to match a recipe.
2. Use instrument tracks for playable notes, audio tracks for recorded/imported audio, and hybrid tracks when both are needed. Give new tracks/sections useful musical names. Preserve occupied slots and timeline material.
3. Load a suitable instrument or existing preset into the **intended insertion point**, then create a note clip of explicit start/loop length. Notes alone make no sound. For chord parts, inspect instrument polyphony and release overlap rather than assuming a pad preset is polyphonic.
4. Establish a small rhythm/harmony/melody first. Enter explicit pitches, starts, durations, velocities and channels using the available interface. Keep schema units distinct from UI bars/beats and MIDI conventions; inspect the actual result instead of translating by guesswork.
5. Use the piano roll for pitched material and Drum/Hybrid editor for drum lanes. In 6.0, Spray Can follows the current beat grid for repeated notes; Step Input enters MIDI notes/chords at that grid. Inspect note lengths, rests and loop boundaries before adding density.
6. Inspect the result, then audition only within playback permission. Adjust the intended musical variable, not every sound at once. Preserve A before exploring B.

**Browser checks:** the Browser Panel and context-sensitive Pop-up Browser are different entry points. Check source, filters, insertion/replacement context and selected result. A missing search result may be a filter/context issue, not a missing installation. Samples, instruments, presets, note clips and audio clips are different kinds of material; do not drop a sample onto an existing device when the intent was an audio clip. Previewing is not proof the item was committed to the correct chain. Installing packages or plug-ins is a separate decision.

**6.0 key awareness:** the project's Root Note/Scale can guide **Snap to Key** (`K`), while **Quantize to Key** changes existing notes and must be intentional. Note FX with **Use Global Key** may interpret offsets as scale degrees instead of semitones. Setting a key is not a promise that every existing note or device will be retuned.

**Expected observation:** the selected clip contains the intended phrase within its playable range, feeds the correct instrument, and repeats at the intended length. For silence, inspect mute/solo, active sequencer, note channel, instrument availability/polyphony and output routing before rewriting notes or raising gain. Bounded note reads are not lossless exports of expressions or a whole clip.

Sources: [Browsers](https://www.bitwig.com/userguide/latest/browsers), [recording and track I/O](https://www.bitwig.com/userguide/latest/recording_clips), [note events](https://www.bitwig.com/userguide/latest/working_with_note_events), [6.0 key signature and note editing changes](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 3. Launcher, Arranger and actual playback

The Launcher is for trying combinations; the Arranger is for fixed placement on the song timeline. Each track has one controlling sequencer at a time. Launching a clip, recording a Launcher clip or pressing a Launcher stop button takes that track away from Arranger playback. **Stopping Launcher clips does not return control to the Arranger.** Use the track/global **Return playback to Arranger Timeline** control when that is the intended transition.

Before launching, inspect project defaults and clip/scene overrides for quantization, launch mode, release behavior and Next Actions. A scene can affect many tracks; empty slots can stop playing clips, or record on armed tracks when **Record on scene launch** is enabled. Do not treat scene launch as a single-clip audition.

A launch or stop may be queued for a quantization boundary. A request accepted or queue observed is **not** evidence that playback has begun or stopped. Observe the necessary transition before dependent edits. Launcher stop can leave the global transport running; stopped transport can leave clips active for resume. In 6.0, the per-track Launcher status circle distinguishes an active stopped clip from a moving playback position, with loop count and Next Action timing where applicable. Do not infer silence from one stopped playhead: instruments/effects can have tails.

**Expected observation:** the intended track is controlled by the intended sequencer, the correct clip is active, and its position advances or stops as requested. If an Arranger edit seems inaudible, first check whether that track is still under Launcher control.

Sources: [Launcher triggering and sequencer ownership](https://www.bitwig.com/userguide/latest/triggering_launcher_clips_0), [project launch defaults](https://www.bitwig.com/userguide/latest/the_project_panel), [6.0 Launcher status and phase behavior](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 4. Keep creative candidates genuinely independent

In 6.0, note, audio and automation clips can share a **Pattern**. The paperclip icon and Inspector Pattern chooser reveal aliases. Editing shared pattern content updates every clip using it; name and color are shared too, while clip start/length/loop settings can differ. Two visibly separate clips are therefore not necessarily separate candidates.

- Preserve original **A**. Create **B** with ordinary **Duplicate/Copy/Paste**, not the **as Alias** variants. If B already shares a pattern, select B alone and use **Make Unique** before editing content. Inspect the Pattern chooser; a unique pattern has a fingerprint indicator rather than a usage count.
- Check related automation patterns as well. Do not run **Merge Duplicate Patterns** during comparison: it can reconnect identical candidates.
- A duplicate clip on the same track still uses that track's devices and mixer. For a sound-design or routing A/B, preserve independent device/mixer states too, using an authorized separate track or project copy as appropriate.
- Compare equal musical ranges and playback conditions, account for tails, latency, seeds/randomness and level differences, and avoid unintended simultaneous A+B playback. Show what changed and let the user choose; meters and screenshots do not establish subjective improvement.

**Expected observation:** changing B's authorized content or sound leaves A unchanged. Inspect independence before the change rather than making a destructive test edit. Launcher-to-Arranger capture can create aliases when **Record to Arranger as Alias Clips** is enabled; check this before polishing the captured version.

Source: [6.0 Alias Clips and recording-to-Arranger option](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 5. Record and edit audio deliberately

For recording, identify the exact input, mono/stereo channel choice, monitoring mode and destination first. Check signal at the intended input and use safe monitoring levels; avoid feeding a monitored output back into its input. Inspect all armed tracks and existing destination content before starting. Arranger recording uses track arm, Global Record and transport; a Launcher slot recording can start transport itself or wait for launch quantization. Decide overdub, loop/punch range and comping behavior before capture, not after an unexpected overwrite.

Existing audio matters: the manual distinguishes recording over actively looping Arranger audio from adding takes to non-looping material. Preserve the original and inspect the current version's behavior rather than assuming every recording becomes a harmless take. Launcher comping uses **Record as Comping Takes** and **Take Length**; Arranger cycle recording uses the Arranger loop.

For editing, distinguish the **clip's playback window**, the contained **audio event**, and the **source file**. Moving an event is not the same as sliding its content or moving its parent clip. Trim/split, event gain, short fades/crossfades and appropriate stretch settings solve different problems. In 6.0, the Waveform Editor exposes event fade handles at the top corners, gain near the top and content sliding near the bottom. Check the hover action before dragging. Do not replace a project media file to fix a local clip edit.

**Expected observation:** recording creates the intended waveform/takes only in the authorized destination; editing preserves intended transients, timing and boundaries. A visible waveform is not a listening result. If pitch/stretch edits appear ineffective, inspect the playback/stretch mode; not every mode uses every expression. Inspect and, when authorized, listen at cuts and loop joins for clicks or truncated tails.

Sources: [recording clips](https://www.bitwig.com/userguide/latest/recording_clips), [Launcher recording/comping](https://www.bitwig.com/userguide/latest/recording_launcher_clips), [audio events and expressions](https://www.bitwig.com/userguide/latest/working_with_audio_events), [6.0 audio editing](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 6. Automation: three separate questions

1. **Which lane is targeted?** In 6.0 the flying lane can follow the last touched parameter. Read its device/parameter label before drawing; displaying another lane does not mean the first curve disappeared.
2. **What follows a structural edit?** **Global Automation Behavior** (`0`) switches **Follow** versus **Lock**. Each lane can override this with **Use editor setting**, **Always follow**, **Never follow**, or **Arranger lock**. Inspect selection previews/badges and affected lanes before moving, duplicating or resizing parent clips. **Free Time** lets an automation clip's timing diverge from its parent. These settings govern editing relationships, not whether automation plays or records.
3. **Who currently controls the parameter?** Manually moving an automated parameter can temporarily override playback automation. **Restore Automation Control** (`R` in 6.0) returns control to automation; it is not Undo and the global button affects all overridden parameters. Use it only when that broader restoration is intended, then observe the relevant parameter following its curve.

6.0 uses one **Automation Write** control for both sequencers. Recording note/audio clips can also enable/capture automation through recording preferences, which default on for both Arranger and Launcher. Inspect these before a performance or parameter tweak. Launcher automation clips can launch with their parent, but can also be triggered directly; track stop applies to automation lanes too. Do not assume the audible note loop describes every automation loop.

**Expected observation:** the intended curve belongs to the intended parameter; structural edits include or exclude exactly the intended lanes; playback control and writing state match the task. If a knob will not stay where set, inspect automation/modulation rather than repeatedly sending the same value. If a curve exists but seems inactive, inspect override state and controlling sequencer before deleting/rebuilding it.

Sources: [automation override and restored control](https://www.bitwig.com/userguide/latest/automation#parameter_follow_and_automation_control), [6.0 Automation Clips and Automation Editing](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 7. Route and balance before adding processing

Trace the actual path: source/notes → instrument → device chain → track output/group → master → monitoring output. Inspect sends separately: **Pre**, **Post**, or **Auto** determine their relationship to the fader, and disabled sends do not pass signal. A send adds a parallel route without reducing the main output. Do not assume lowering a track fader removes a pre-fader effect feed.

Start with conservative source/instrument levels, especially layered oscillators, chords and long releases. Balance parts in context before adding compression or limiting. Leave output headroom for summing and peaks; no universal fader position guarantees it. Lowering the master does not fix upstream clipping or an unintended feedback path. Check mute/solo, disabled devices, output destinations, hidden crossfader assignments and monitoring before blaming the composition.

In 6.0, audio clips on a group lane override the group's summed output for their duration. Group meta-clips summarize child material, and editing them can act on children. Treat group selection as a wider target, not another ordinary clip.

**Expected observation:** the intended channels and master show plausible activity without unintended overload, and the monitored destination is correct. Peak/RMS displays are useful observations, not proof of perceived loudness, tonal balance or hearing safety. Gain-match A/B where practical.

Sources: [Mixer, sends and crossfader](https://www.bitwig.com/userguide/latest/the_mix_view), [track I/O](https://www.bitwig.com/userguide/latest/recording_clips#track_io_settings), [6.0 Group Track changes](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).

## 8. Arrange, render and preserve the work

**Arrange:** copy/place chosen clips into a clear song structure, or capture an authorized Launcher performance using Global Record and transport. Check the recording start, existing Arranger material, record arms, automation writing and alias-capture option first. Afterwards inspect the actual Arranger clips/automation and return the relevant tracks to Arranger control before evaluating the linear arrangement. Section markers help make the intended form and export range explicit.

**Bounce:** use it to print a selected clip/range, not as a substitute for saving. Inspect the source tap: **Pre-FX** (primary instrument output), **Pre-Fader** (after device chain), **Post-Fader**, or **Custom**. The dialog's **In-Place** option replaces the selection; preserve editable originals for creative comparisons. Real-time is necessary for live external signal paths. Confirm the actual audio event/file extent, start and loop region separately; a loop length alone does not establish render duration or tail coverage.

**Export Audio:** this exports **Arranger** material, not a directly selected Launcher performance. Inspect **Project Master** versus individual tracks/stems, From/To range, formats, sample rate, bit depth/dither, pre-fader option, real-time requirement, destination and overwrite risk. Existing selections can supply dialog defaults, so inspect rather than accept them blindly. Include required releases/tails deliberately and state whether stems include the processing the recipient expects.

**Preserve:** save only to the authorized project/destination. A BWPROJECT is accompanied by a project folder containing generated media/state; handing over the project file alone may be incomplete. In Project Panel → Files, inspect external and missing media; use **Collect and Save** with the intended external/package options when authorized. Inspect Plug-ins for missing dependencies/version conflicts rather than silently ignoring them or installing replacements. Do not run **Delete Unused** as routine cleanup. 6.0's version-opening backups are useful protection but are not a substitute for a deliberate project/media backup. DAWproject exports generic interchange data; preserve the native project rather than promising lossless translation of every device/feature.

**Expected observations and report:** actual arranged range; observed bounce/export completion and destination files; required duration/tails and format where inspected; project/media save outcome; remaining missing dependencies. Keep GUI completion, audio analysis, listening and save/reopen evidence separate. Do not claim rendered sound quality, persistence or interchange equivalence without that evidence.

Sources: [capturing Launcher to Arranger](https://www.bitwig.com/userguide/latest/recording_launcher_clips#record_to_arranger_timeline), [bounce options](https://www.bitwig.com/userguide/latest/bouncing_to_audio), [Export Audio](https://www.bitwig.com/userguide/latest/exporting_audio), [project folders](https://www.bitwig.com/userguide/latest/working_with_projects_and_exporting), [Files/Plug-ins and Collect and Save](https://www.bitwig.com/userguide/latest/the_project_panel), [DAWproject export](https://www.bitwig.com/userguide/latest/exporting_projects), [6.0 version backups](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).
