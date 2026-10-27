# Targeting, uncertainty and evidence

Use the installed MCP tool schemas or `bitwig-agent-rpc describe METHOD` for exact arguments. This reference describes decision rules, not a second schema registry. The public [protocol](https://github.com/BANANASJIM/bitwig-agent/blob/main/PROTOCOL.md) documents method-specific guards and recovery; consult the matching release tag if `main` differs from the installed version.

## Bind from current observations

| Operation | Required discovery/binding practice |
|---|---|
| Track change | Discover current UUID and readable fields; send only actual changed fields and inspect per-field verification |
| Root device/direct parameter | Discover track, zero-based root device index and exact name; call `parameters.list` and use the returned parameter ID/context token |
| One-hop layer parameter | Use `devices.layers.list`, the exact child address/discovery token and `parameters.layer.list` context; do not treat child indices as root indices |
| Remote Control | Use the currently observed page/name/parameter and its own context token; do not reuse a direct-parameter token |
| MIDI | Explicit track/slot/channel/grid/window; inspect occupancy/coverage and understand selection/auto-arm side effects before calling |
| Grid | Use current graph instance/port/control IDs, target ownership and exact typed raw CAS observations; read a control immediately before writing |
| Modulator | Use explicit root parent and current modulator/control IDs; this surface is read-only, not loader/mapping/write support |
| Host Action | Discover the actual host Action ID and confirm its current UI context; dispatch does not establish enablement or completion |

Switching direct-parameter targets invalidates the previous shared binding. Names, tokens and native module/modulator UUIDs are not permanent identity: reacquire after reopening or changing targets. Do not assume project-name equality proves which full path is loaded. Example IDs are placeholders, not targets.

Tracks, root devices and Launcher slots are bounded to windows of 16; sends to eight; layers are one hop with two layers/two children. Inspect completeness and unreadable values. `null` is not zero, false, empty or safe. MIDI reads are grid-window observations, not lossless whole-clip exports; scheduled settling is not a general acknowledgement barrier.

## Before a write

Require explicit user permission covering the change and actual target, one writer, and fresh relevant safety/target observations. Use a disposable fixture for experiments. Check the method's schema and guards rather than inventing an `allowWrite` argument. Recording/playback/automation conditions vary by operation; establish all required values rather than treating unknown as false. Read-only metadata and license/property descriptors never themselves authorize a write.

`notes.read` selects its clip. An operation described as inspection can still change UI context; a failed dispatched selection can have `outcome: unknown`. Do not automatically retry it merely because no notes were meant to change.

## Outcome handling

- **Before dispatch:** validation/connection failure can report `not_dispatched`; correct the actual cause, rediscover as needed and confirm the original permission still applies before a new request.
- **Host rejection before application:** `not_applied` describes that rejection's scope, not permission to remove a safety gate. Resolve stale/unavailable/unsupported prerequisites; do not force a write.
- **Unknown:** either `error.outcome: unknown` or `result.completion: unknown` stops the writer. Preserve the full envelope, intended target, dispatched operation and partial owned objects. A timeout/dropped reply may follow an applied change. The request ID is not an idempotency key.
- **Dispatch-only:** Actions, selection or confirmed queued Launcher playback/stop can report `completion: not_observed`. A queue is not actual playback/stop. Accept only an explicitly expected dispatch-only result; do not advance to dependent mutations until the necessary completion is separately established within permission.
- **Observed/verified:** inspect the stated scope, per-field checks and mutation flags. Exact native model readback is not engine acknowledgement, saved persistence or one-call/one-Undo evidence.

After unknown, do not retry, undo, delete partial objects, restart a controller or switch to GUI as automatic recovery. Use only authorized inspection to reconcile the actual state and report what remains unknown. A fresh plan requires explicitly addressed uncertainty and applicable permission/guards; later matching readback alone does not acknowledge the original write. Launcher/topology quarantines have specific protocol lifetimes. A required full JVM restart may clear a process guard, but does not prove rollback and is itself a separately authorized action.

## Honest handoff to the user

Report:

1. The requested change and the observed target scope—not remembered GUI selection.
2. What was dispatched and what was actually verified, including partial ownership.
3. Any unknown result, failed criterion, bounded/unreadable coverage or unsupported API gap.
4. Whether GUI, audio, listening, Undo/Redo or save/reopen evidence was obtained separately; otherwise say it was not established.
5. The next action that needs explicit permission or prerequisites, without silently performing it.

A known GUI gap requires Computer Use permission and fresh target/dialog observation; that permission may already be included in the delegated task. It is not granted by a missing API. Never replay an uncertain API operation through GUI. A successful correction or later case does not retroactively pass an earlier failed criterion.
