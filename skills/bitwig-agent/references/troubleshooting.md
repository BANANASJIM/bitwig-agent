# Troubleshooting a production session

Start from the user's symptom; do not rerun a known failing action just to confirm it. Separate observation, hypothesis, authorized correction and new verification. Check the actual running version before diagnosing from a remembered screenshot ([versions](version-and-sources.md)).

## Contents

- Locate the failed layer
- Safe correction loop
- Unknown is not a retry condition
- Escalate with evidence

## Locate the failed layer

| Symptom | Look at first | Avoid |
| --- | --- | --- |
| Bitwig will not open | An existing process or window, the exact launch error and crash-log excerpt, display and graphics backend, audio initialization | Killing all Bitwig processes, deleting the profile, forcing a backend, opening on an unauthorized compositor |
| UI works, agent cannot connect | `bitwig-agent status`, the extension enabled in this host, the loopback endpoint and who owns it, client and extension versions | Treating a pip install as an extension install, exposing the port |
| Wrong project, track or device | Which project is open, a fresh `tracks list` or `context get`, whether the window you read was complete | Treating a project name as full-path identity, reusing old refs, assuming API selection moved the visible device panel |
| MIDI but no sound | Engine active, clip actually playing not queued, note channel and range, device enabled, mute/solo/output routing, meters along the path | Raising master gain, claiming a peak proves audible quality |
| Chords lose notes, pads cut off | Mono or poly mode and voice count, overlapping releases, note lengths, Grid gate and envelope | Assuming four notes mean four voices; more unison or level |
| Parameter snaps back | Automation and overrides, modulation and Remote mappings, controlling sequencer, recording and automation-write flags | Repeated writes, silently disabling automation, mixing normalized, raw and display values |
| Launch or stop ignored or late | Transport, launch quantization, queued versus playing slot, launcher/arranger ownership | Re-sending, treating queued as done |
| Clip edit changed another candidate | Alias or shared-pattern indicators, loop and start/end | Assuming a different slot or name means an independent clip |
| Behavior differs from docs, a fix is missing | `bitwig-agent status`: `extension.build` against the expected build | Assuming the replaced file is running |
| Unsaved work at risk | `projectModified` (current tab only, null is unobserved); `project save` once; an untitled project needs `project saveAs`; Quit and Close need the user's consent via `discardUnsavedChanges`; a Save As or Recover dialog blocks saving and needs the GUI | Calling `project save` again, passing consent the user did not give |
| Writes refused with `pending` or `unresolved` | `status.holds` and the refusal's `next` (which read to inspect, which id to release); inspect, then `ack` that id. `busy` is a job in flight: wait or `jobs get` | Acking without inspecting, restarting Bitwig to clear it |
| Grid access unavailable | `bitwig-agent status --detail=full` (its `grid` section), the running host version | Bypassing a version gate, treating read-only inventory as write support |
| Bounce or export silent, clipped, wrong length | Source selection, routing and mute, the requested range and tails, loop region versus file extent, the output files and `jobs get` | Replacing the original, changing the acceptance range after a failure |

For silence, tell apart no notes, no generated signal, no track output, no master output and no monitored output; each is a different layer. Host faults the API cannot see (a Grid device dropped from the audio network, engine flips, extension errors) appear in `bitwig-agent diagnostics get`; check `attribution.certain` before trusting it. A blocked headless instance (Dashboard, Save?, Recover dialogs) is not in the log: use a screenshot.

## Safe correction loop

1. Record the symptom, version, exact target and evidence. Read only the narrow log or state window you need, leaving out credentials and unrelated project material.
2. Check safety and ownership. If a needed value is unknown or the target changed, stop and resolve that first.
3. Choose one correction for the supported cause. Preserve the original where useful; objective repairs do not need an A/B.
4. Make only the authorized change, then run the user's original scenario and look at the actual result. A healthy connection or green test is not music-production success.
5. Report what changed, what you saw, what stays unsupported and whether audition and persistence were checked. Keep the earlier failure in the record.

## Unknown is not a retry condition

Read every write's `outcome`. An error means nothing changed; `unknown` stops the writer, and a timeout can follow an applied change, so it is `unknown` too. A request id is not an idempotency key. Keep the raw responses, intended targets and partial outputs. Do not retry, Undo, delete, reload, restart or replay through the GUI. Read the state, then `ack` the hold; a long export, bounce or save returns `unknown` with a `job` for `jobs get`. A read does not itself authorize continuing, and an unreleased hold still refuses writes. Meanings: `bitwig-agent help outcomes`, `bitwig-agent help recovery`.

## Escalate with evidence

Give the exact symptom and error, host, client and extension versions, platform, target, the smallest raw response or log excerpt, a screenshot when allowed and the steps already tried. Say whether it is a host or UI problem, a bridge contract bug, an unsupported capability or a musical choice. Do not propose a reinstall or profile reset first, and do not claim the whole interface is controllable because one path worked.
