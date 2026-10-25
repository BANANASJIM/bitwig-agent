# Grid and music recipe boundaries

Use this reference before native Grid edits or the public scratch-music recipe. It does not grant permission to operate a DAW. Exact schemas remain in live tool discovery and the version-matched public [protocol](https://github.com/BANANASJIM/bitwig-agent/blob/main/PROTOCOL.md).

## Compatibility and discovery

Native evidence is limited to Linux / Bitwig Studio **6.1.3 / API 25**. Private Grid/modulator access also checks the exact supported **loaded host-JAR** SHA-256. An installed 6.1.4 package, another JAR on disk, successful Python installation or a passing package smoke does not extend compatibility. Do not bypass a mismatch.

Call `grid.capabilities` and inspect separate read/write availability and topology admission. Availability means a binding is present, not that every module/type has native acceptance. Start a structure/layout/wiring/name inspection with a small `grid.get` page, `includeParameters=false`, and an explicit current device target. Inspect `window.complete` and unsupported coverage; top-level completeness has additional requirements. Stored cables are not proof of the active signal graph.

Discover exact module instance IDs, ports and controls; catalog type IDs are not module instance IDs. Module/modulator UUIDs may change on reopen. Unknown native children remain unsupported. Grid-family QEE lacks a real fixture; Macro-modulator observations do not establish that coverage. Modulator inventory and exact string-panel values are read-only, not loading/mapping/editing support.

## Guarded raw changes

For a supported parameter, read `grid.parameters.get` immediately before its compare-and-set write. Use the exact discovered kind and current raw value as preconditions. Numeric raw values are not normalized/display values or automatically seconds, hertz or decibels. Enum values are actual option values, not their list positions. No guessing, clamping or display-string conversion.

String panels are read-only. Boolean-panel changes are a separate guarded operation and may coerce a coupled scalar; do not flatten those relationships into a guessed scalar write. Custom-name CAS uses raw `customName`, not the effective display name. Topology writers have their own lifecycle/quarantine checks; do not assume a readable graph authorizes insert/move/delete/rename/cable edits.

Native context, license/property and undo observations do not constitute user permission, a global lock or engine acknowledgement. Check applicable stopped/nonrecording/nonautomation conditions and current ownership, and stop on any unknown completion. Native undo-aware dispatch does not promise grouping, rollback or saved persistence.

## Scratch recipe: explicit agent workflow

The public [usage guide](https://github.com/BANANASJIM/bitwig-agent/blob/main/USAGE.md#make-grid-instruments-without-preset-files) and [recipe source](https://github.com/BANANASJIM/bitwig-agent/blob/main/examples/grid_music.py) contain the complete source-checkout commands. The wheel does not install the recipe as a tool, and this copied skill does not bundle it. Do not invent a missing executable or download/run it without permission.

The recipe requires an explicitly named disposable project, fresh safety observations, a new evidence directory and explicit `--allow-write`. It creates two muted/disarmed instrument tracks and eight beats of pluck notes/pad chords. In `--from-scratch` mode it discovers the factory Poly Grid/catalog and constructs Union → ADSR → Audio Out without caller-supplied preset files. Fixed Analog ADSR settings are bounded native-observed calibrations, not a general physical-unit conversion. Alternatively omit `--from-scratch` and provide **both** host-authored pluck and pad presets; mixed/incomplete modes reject, and presets must never be fabricated.

Only `report.json`'s `createdTracks` and their slot-0 clips/devices are owned outputs; existing tracks are not edited. A project name is not full-path identity or an atomic lock. Use a complete ungrouped track window. The recipe retains full responses and partial ownership, stops on unknown even with `status: observed`, and does not play, render, save, undo or retry. Do not rerun it after uncertainty. A null note count is unverified, not empty.

### Required Pad voice-count gap

The original factory Pad was **Mono / 1 voice**; its nine exposed direct parameters did not include voice count. Four-note chords need polyphony. After successful authoring, **only with separate GUI permission**, select the actual owned Pad/Poly Grid, open its device inspector and set **VOICES to 8** using the numeric field's **Type value** interaction. Observe the resulting field before rendering. Eight voices permit overlapping releases.

The recipe uses Pad Triangle Level **0.125 per voice** for headroom and Pluck Level **1.0**, and reports the required GUI step in stdout `next`. Do not omit the voice step or invent a structured voice-count API. It is explicit Computer Use, not automatic fallback. The original Mono case lacked three chord fundamentals; a later corrected case does not retroactively pass it, uniquely isolate simultaneous voice/level changes, or establish whole-recipe persistence/listening acceptance.

### Render and completion boundaries

Graph/control/MIDI readback establishes bounded authoring observations, not sound or persistence. Playback, bounce, save and reopen are separate authorized actions. For GUI Bounce, select the actual owned Launcher clip and inspect its context before invoking **Bounce...**; an Action/selection response does not prove the dialog target or result.

For looping bounces inspect the full audio-event/file extent, initial Start and repeating loop region separately: they can differ. An earlier maximum-seven-second criterion failed; observing a sixteen-beat event with an eight-beat loop does not change that failure or prove a universal generation rule. No automatic rendering/saving workflow is supplied. Use the public [verification limits](https://github.com/BANANASJIM/bitwig-agent/blob/main/VERIFICATION.md#coverage-limits-and-unresolved-results) rather than promoting later corrections into blanket native acceptance.
