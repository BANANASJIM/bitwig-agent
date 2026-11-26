# Troubleshooting a production session

Start from the user's observed symptom. Do not rerun a known failing action merely to confirm their report. Separate **observation**, **hypothesis**, **authorized correction** and **new verification**. Check current version/platform and the relevant [official changes](version-and-sources.md); do not diagnose from a remembered screenshot or an installed package version alone.

## Locate the failed layer

| Symptom | First useful observations | Avoid |
| --- | --- | --- |
| Bitwig will not open | Whether a process/window already exists; the exact launch error and relevant application/crash-log excerpt; display/session access, graphics backend, package/runtime and audio initialization messages | Killing all Bitwig processes, deleting the profile, inspecting license secrets, forcing a backend change or opening on an unauthorized compositor |
| UI works but agent cannot connect | Correct MCP server and executable, installed extension enabled in this host, configured loopback endpoint, ownership of the listening process, client/extension versions | Treating a pipx installation as extension installation; exposing the unauthenticated port; switching to another integration with different schemas |
| Wrong project, track or device | Controlled project provenance, actual active window/header, fresh UUID/name/index and relevant tokens, completeness of the returned window | Treating project-name equality as full-path identity, reusing old IDs, or assuming API clip selection changes the visible device panel |
| MIDI exists but no sound | Audio engine active, track/clip playback actually active rather than queued, note channel/range, device enabled, instrument chain, mute/solo/output routing and meters along the signal path | Raising master gain first, disabling protection, enabling recording, or claiming a waveform/peak proves audible quality |
| Chords lose notes or pads cut off | Actual mono/poly mode and voice count, overlapping releases, note lengths, Grid gate/envelope and voice-lifetime path | Assuming four MIDI notes guarantee four sounding voices; increasing unison or level instead of checking voice allocation |
| Parameter snaps back or edits fight the song | Selected owner, active automation/overrides, modulation and remote mappings, clip/Arranger control, recording/automation-write flags | Repeated writes, silently disabling automation, or confusing normalized values, raw values and display units |
| Launch/stop seems ignored or late | Global transport, launch quantization, queued versus playing/stopped slot state, Launcher/Arranger ownership and the intended musical boundary | Blindly issuing the command again, treating queued as completed, or assuming stopping one clip stops the global transport |
| Clip edit changes another candidate | Alias/shared-content indicators, actual clip identity, loop/start/end and independent candidate state | Assuming a different slot/name makes an independent A/B; overwriting A to repair B |
| Behavior differs from the docs or a fix is missing | `status.extension.build` against the expected build (`extensionMismatch` from the client); a replaced `.bwextension` is loaded only by a Bitwig restart, not the Settings toggle | Assuming the replaced file is running |
| Unsaved work at risk (restart, Quit/Close) | `projectModified` (current tab only; null = unobserved); `project.save` once; a Save As or crash-recovery dialog blocks it and needs the GUI/user, so save an untitled project with `project.saveAs` instead. `Quit` always, `Close` unless observed saved, need user consent via `discardUnsavedChanges`; save every tab or get consent before restarting Bitwig. After a forced exit Bitwig shows "Recover Projects": in an isolated headless display dismiss it with `xdotool windowactivate` + Escape (keys work, clicks do not) | Calling `project.save` again, passing `discardUnsavedChanges` without the user's consent |
| Writes refused with `pending` / `unresolved` | The holds in `status.holds` and the refusal's `next` (which read to inspect with and which id to release). Inspect that state, then `ack <id>`; a Grid topology hold is per device, inspect with `grid.get` and `ack` re-checks it (`grid.capabilities` `topologyQuarantine.devices`). `busy` is an in-flight job or request: wait or `jobs.get`; `project.save` stays allowed | Acking without inspecting, restarting Bitwig to clear it |
| Grid control/API is unavailable | `grid.capabilities`, running host version, supported loaded-JAR gate, actual control kind/coverage and selected device | Bypassing the gate, inventing a parameter or treating read-only inventory as write support |
| Bounce/export is silent, clipped or wrong length | Actual source selection, routing/mute state, requested range, tails, loop region versus full event/file extent, export format/rate/normalization and output file (`export.audio` files rows, `bounce.inPlace` tracks rows, `jobs.get`) | Replacing the original, changing the acceptance range after seeing a failure, or treating export-dialog closure as successful audio output |

Follow the smallest signal path that explains the symptom. For silence, distinguish no notes, no generated device signal, no track output, no master output and no monitored output. Each is a different layer; visible meters are observations, not proof that the user heard the intended sound. For audio recording, inspect the intended input, monitoring, arm and destination before explicitly authorized recording; do not enable it just to test an unrelated problem.

Host-side faults the API cannot see (Grid device dropped from the audio network, engine flips, extension errors) show in `diagnostics.get`; check `attribution.certain` before trusting it. A blocked headless instance (Dashboard, Save?, Recover dialogs) is not in the log: use a screenshot.

## Safe correction loop

1. Record the symptom, relevant version, exact target and available evidence. Read only the narrow log/state window needed; exclude credentials, license/account data and unrelated project material from shared artifacts.
2. Inspect current safety and ownership. If required values are unknown or the target changed, stop the proposed write and resolve that prerequisite.
3. Choose one correction addressing the supported cause. Preserve original content/state where useful; use [A/B](sound-design.md#controlled-ab) for creative alternatives, not arbitrary alternatives to an objective repair.
4. Perform only the authorized change. Then exercise the original user-visible scenario and inspect its actual result. A passing unit test or healthy connection does not establish music-production success.
5. Report what changed, what was observed, what remains unsupported and whether audition/persistence were actually checked. Keep the earlier failure in the record.

## Unknown is not a normal retry condition

Read the `outcome` of every write. An error means nothing changed; `outcome: unknown` stops the writer. A write's timeout can follow an applied change and is reported as `unknown`, and a request ID is not an idempotency key. Preserve raw responses, intended targets and partial owned outputs. Do not automatically retry, Undo, delete, reload, restart or replay through GUI. A long export, bounce or save returns `unknown` / `deadline` with a `job`: follow it with `jobs.get`.

Authorized read-only inspection may establish the current state, but does not acknowledge the original operation or itself authorize continuation. Release the hold with `ack <id>` once inspected. A subsequent plan must explicitly address the uncertainty, scope and safety; unreleased holds still refuse writes. See [targeting and evidence](targeting-and-evidence.md) and the [reason table](https://github.com/BANANASJIM/bitwig-agent/blob/main/PROTOCOL.md#results-errors-and-recovery).

## Escalate with useful evidence

Invalid-argument errors name the closest valid field; read it before re-checking schemas. Provide the exact symptom and error, running host/client/extension versions, platform, target scope, smallest relevant raw response/log excerpt, actual screenshot when permitted, and the already-observed steps. Distinguish a host/UI problem, a bridge contract bug, an unsupported capability and a musical choice. Do not propose a reinstall/profile reset as the first diagnostic step, silently move the user's session to another display, or claim the entire interface is controllable because one path worked.
