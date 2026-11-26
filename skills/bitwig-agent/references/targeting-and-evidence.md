# Targeting, uncertainty and evidence

Use the installed MCP tool schemas or `bitwig-agent help METHOD` for exact arguments. This reference describes decision rules, not a second schema registry. The public [protocol](https://github.com/BANANASJIM/bitwig-agent/blob/main/PROTOCOL.md) documents method-specific guards and recovery; consult the matching release tag if `main` differs from the installed version.

## Bind from current observations

| Operation | Required discovery/binding practice |
|---|---|
| Track change | Discover current UUID and readable fields; send only actual changed fields and read each field's `outcome` |
| Root device/direct parameter | Discover track, zero-based root device index and exact name; call `parameters.list` and use the returned parameter ID/context token |
| One-hop layer parameter | Use `devices.layers.list`, the exact child address/discovery token and `parameters.layer.list` context; do not treat child indices as root indices |
| Remote Control | Use the currently observed page/name/parameter and its own context token; do not reuse a direct-parameter token |
| MIDI | A clip ref from `context.get` (`cursorClip`, occupied `launcherSlots`) or `launcher.list` (slot refs need an existing scene); channel and window default sensibly. Inspect coverage and selection/auto-arm side effects before calling |
| Grid | Read with `grid.export`; its device ref is the `target` and its module refs name parameters; stale refs are refused as an error |
| Modulator | Use explicit root parent and current modulator/output/control IDs from `modulators.get`; edits need `expectedCount`/`expectedAmount`/`expectedValue` from a fresh read |
| Host Action | Discover the actual host Action ID and confirm its current UI context; `sent` does not establish enablement or effect |

Switching direct-parameter targets invalidates the previous shared binding. Names, tokens and native module/modulator UUIDs are not permanent identity: reacquire after reopening or changing targets. Do not assume project-name equality proves which full path is loaded. Example IDs are placeholders, not targets.

Tracks, root devices and Launcher slots are bounded to windows of 16; sends to eight; layers are one hop with two layers/two children. Inspect completeness and unreadable values. `null` is not zero, false, empty or safe. MIDI reads are grid-window observations, not lossless whole-clip exports; scheduled settling is not a general acknowledgement barrier.

## Before a write

Require explicit user permission covering the change and actual target, one writer, and fresh relevant safety/target observations. Use a disposable fixture for experiments. Check the method's schema and guards rather than inventing an `allowWrite` argument. Recording/playback/automation conditions vary by operation; establish all required values rather than treating unknown as false. Read-only metadata and license/property descriptors never themselves authorize a write.

Agent `notes.*`, `clips.insert`, `clips.set` launch fields (`launchQuantization`, `launchMode`, `useLoopStartAsQuantizationReference`) and `scenes.insert` move selection; `context.get` does not. An operation described as inspection can still change UI context; a refusal after the selection was dispatched is an `unknown` result. Do not automatically retry it merely because no notes were meant to change.

## Outcome handling

The loop is: read, write, read the result's `outcome`. Words and the reason table: [protocol](https://github.com/BANANASJIM/bitwig-agent/blob/main/PROTOCOL.md#results-errors-and-recovery).

- **Error** (`{code, reason, message, next?}`): nothing changed. Correct the actual cause, rediscover as needed and confirm the original permission still applies before a new request. An error is not permission to remove a safety gate; do not force a write.
- **`applied` / `already_matched`:** done within the stated scope. Exact native model readback is not engine acknowledgement, saved persistence or one-call/one-Undo evidence.
- **`sent`:** dispatched once, nothing observed (Actions, selection, a Launcher queue). A queue is not actual playback/stop. Accept only an explicitly expected `sent`; do not advance to dependent mutations until the effect is separately established within permission.
- **`not_applied`:** an entry was not written (for example `not_reached` after an earlier entry stopped a batch); read its `reason`.
- **`unknown`:** stops the writer. Preserve the full result, intended target, dispatched operation and partial owned objects. A timeout or dropped reply of a write is reported this way too, since it may follow an applied change. The request ID is not an idempotency key. Never retry it.

After `unknown`, do not retry, undo, delete partial objects, reload the extension or switch to GUI as automatic recovery. Inspect with the read method the refusal's `next` names, report what remains unknown, then `ack` the hold (named in `next` or `status.holds`): `ack` records that you looked and undoes nothing, and an in-flight hold (a running job) is released by its owner, so follow it with `jobs.get <job>` instead. A fresh plan requires explicitly addressed uncertainty and applicable permission/guards; later matching readback alone does not acknowledge the original write. Restarting Bitwig does not prove rollback and is itself a separately authorized action.

`actions.invoke` is dispatch-only; its unsaved-work guard needs user consent for `Quit` (always) and `Close` (unless the current tab is observed saved). The agent never supplies that consent. `actions.list` takes `query`/`category`/`limit`; check `total`/`complete`.

## Honest handoff to the user

Report:

1. The requested change and the observed target scope—not remembered GUI selection.
2. What was dispatched and each outcome (applied, sent, unknown), including partial ownership.
3. Any unknown result, failed criterion, bounded/unreadable coverage or unsupported API gap.
4. Whether GUI, audio, listening, Undo/Redo or save/reopen evidence was obtained separately; otherwise say it was not established.
5. The next action that needs explicit permission or prerequisites, without silently performing it.

A known GUI gap requires Computer Use permission and fresh target/dialog observation; that permission may already be included in the delegated task. It is not granted by a missing API. Never replay an uncertain API operation through GUI. A successful correction or later case does not retroactively pass an earlier failed criterion.
