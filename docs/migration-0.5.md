# Migrating from 0.4 to protocol 0.5

Derived from the `v0.4.0` tag and the current method table (`src/bitwig_agent/protocol/`); [PROTOCOL.md](../PROTOCOL.md) is the contract for the new shapes.

## Entry points (client configuration)

| 0.4 | 0.5 |
|---|---|
| `bitwig-agent` ran the MCP server | `bitwig-agent` is the CLI (`python -m bitwig_agent` is the same command) |
| `bitwig-agent-rpc` was the CLI | removed; use `bitwig-agent <resource> <verb> ...` (`devices.sidechain.get` is `bitwig-agent devices sidechain get`) |
| MCP config `args: ["-m", "bitwig_agent"]` | the MCP server is `bitwig-agent-mcp` or `python -m bitwig_agent.frontends.mcp` |

An old MCP configuration now starts the CLI instead of the server and the client sees no tools. Regenerate it with `bitwig-agent-install --mcp-config` and replace the old entry (keep other servers in the file). Keep the Python package and the extension on the same version: a host built for another protocol answers `incompatible` / `protocol_mismatch`.

## Envelope

| 0.4 | 0.5 |
|---|---|
| `status`, `completion`, `matchesRequested`, `batchStatus`, `not_dispatched` on results | one top-level `outcome`: `already_matched`, `applied`, `sent`, `not_applied`, `unknown` (the worst entry wins); entries are `{requested, observed?, outcome, reason?}` |
| errors with many method-specific reasons, some after the write had started | an error always means nothing changed; ten codes and a closed reason list (`REASONS` in `src/bitwig_agent/protocol/base.py`); a failure after dispatch is `outcome: unknown` |
| `stale_target`, `stale_context` | `target_not_found` (nothing there), `target_changed` (something else there; `current` holds the ref found), `unsafe_state` (transport, recording or automation-write state moved); `stale_target` and `stale_context` are removed from the vocabulary |
| an `unknown` result without a pointer to what to look at | `next` names the read method of the write that was called (`transport.set` → `transport.get`), unless the result names one itself |
| `unresolved_mutation` and `extension.restart` to clear it | `pending` / `unresolved` with `next`; release with `ack <id>` after inspecting |
| `grid_verification_failed`, `grid_bounds_exceeded` | `readback_mismatch`, `out_of_bounds` |
| requests without a protocol field | every request carries `"protocol":"0.5"` |

## Methods

| 0.4 | 0.5 | Change |
|---|---|---|
| `capabilities`, `grid.capabilities`, `modulators.capabilities` | `status` (`detail:"full"` for the per-module sections) | merged |
| `host.diagnostics` | `diagnostics.get` | renamed |
| `extension.restart`, `grid.topology.reconcile` | `ack {id}` | removed; `status.holds` lists the ids |
| `render.status` | `jobs.get {id}` | replaced; long jobs return `unknown` / `deadline` with `job` |
| `tracks.create` | `tracks.insert` | renamed; integer `position` is now `"start"`, `"end"`, `{before}` or `{after}` |
| `tracks.meters` | `meters.get` | renamed |
| `bounce.inPlace` | `tracks.bounce` | renamed; `trackIds` is `targets`; gained `inPlace` |
| `markers.rename` | `markers.set` | renamed |
| `devices.layers.list` | `chains.list` | renamed |
| `devices.layers.insert` | `devices.insert` (`parent` is a chain) | merged |
| `devices.remote.deletePage` | `devices.remote.delete` | renamed |
| `devices.remote.page` | none | removed; Remote Controls pages are addressed by their ref |
| `devices.remote.set`, `parameters.layer.set`, `modulators.parameters.set` | `parameters.set` | merged |
| `parameters.layer.list` | `parameters.list` | merged |
| `modulators.parameters.get` | `modulators.list` / `modulators.get` | merged |
| `modulators.catalog` | `modulators.describe` | merged |
| `grid.graph` | `grid.export` (`refs` and `sizes` beside `spec`) | merged |
| `grid.modules.rename` | `grid.modules.set` | renamed |
| `grid.parameters.get` | none | removed; `grid.get` with `moduleIds` covers it |
| `launcher.get` | `clips.get` | renamed |
| `launcher.set` | `clips.set` | merged |
| `launcher.create`, `arranger.clips.create` | `clips.insert` | merged |
| `launcher.import` | `clips.import` | renamed |
| `launcher.launch` | `clips.launch` | renamed |
| `scenes.append` | `scenes.insert` | renamed; no 16-scene total limit |
| `arranger.clips.list` | `arranger.list` | renamed |
| `arranger.clips.copyFromLauncher` | `clips.copy` | renamed |
| `clips.edit` | `clips.set` (rename, color, resize as `loop:{lengthBeats}`), `clips.delete`, `clips.copy`, `clips.transpose`, `clips.quantize`, `clips.transform` (`op: "doubleContent"` for duplicateContent) | split |
| `arranger.clips.edit` | `clips.move`, `clips.set` (rename, resize), `clips.delete` | split |

New methods: `ack`, `jobs.get`, `clips.copy`, `clips.delete`, `clips.get`, `clips.import`, `clips.insert`, `clips.launch`, `clips.move`, `clips.quantize`, `clips.set`, `clips.transform`, `clips.transpose`, `arranger.list`, `chains.list`, `devices.remote.delete`, `devices.sidechain.get`, `devices.sidechain.set`, `diagnostics.get`, `markers.set`, `meters.get`, `scenes.delete`, `scenes.duplicate`, `scenes.insert`, `scenes.launch`, `scenes.list`, `scenes.set`, `tracks.bounce`, `tracks.insert`, `tracks.routing.get`, `tracks.routing.set`. The list is the difference between the 0.4 and 0.5 registries.

Not agent-callable any more (still used by `grid.apply`): `grid.catalog`, `grid.get`, `grid.parameters.set`, `grid.voicing.get`, `grid.voicing.set`, `grid.modules.insert`, `grid.modules.set`, `grid.modules.move`, `grid.modules.delete`, `grid.connections.set`. Use `grid.describe`, `grid.export` and `grid.apply`. A `grid.apply` failure names the failing step in `failure.method` when a hidden method failed.

## Fields

One concept has one name. Objects are addressed by refs that a read returned (`devices.list`, `tracks.list`, ...), copied back unchanged.

| 0.4 | 0.5 |
|---|---|
| `trackId`, `deviceIndex`, `expectedDeviceName` (devices, parameters, Grid) | `target` (a device ref); lists take `parent` |
| `trackId` on tracks, notes, launcher, automation | `target` (a track or clip ref); `trackIds` is `targets` |
| `slotIndex` on `notes.read`, `notes.edit` | `target` (a clip ref) |
| `markerIndex`, `expectedName`, `expectedBeat` (markers) | `target` (a marker ref) |
| `modulatorId`, `parent`, `expectedCount`, `expectedAmount` (modulators) | `target` / `parent` refs; `expect` and `source` on `modulators.map` / `unmap`; `expectedCount` is gone from `modulators.insert` |
| `parameterId`, `contextToken` on `parameters.set` | `target` (a parameter ref), optional `expect`; tokens are gone |
| `toIndex` on `devices.move` | `to: {parent?, position}` |
| `expectedCount`, `expectedHash` on `automation.points.set` | `expect: {pointsHash}`; the read returns `lane.pointsHash` |
| `volumeNormalized`, `volumeDb`, `volumeDisplay`, `panNormalized`, `panPercent`, `panDisplay` on `tracks.set` | `volume`, `pan`: a bare value or `{display}` |
| `sendIndex`, `sendName`, `expectedSendName`, `valueNormalized`, `display` on `tracks.sends.set` | `target` (a send ref), `value`, optional `expect` |
| `mode: create \| replace`, `pageName` on `devices.remote.define` | upsert by `name`, no `mode` |
| `expectedAmount: null` meaning create, on `modulators.map` | upsert; fields you leave out keep their value |
| `format` on `export.audio`, `grid.render` | `fileType` |
| `outPath`, `overwrite` on `grid.export` | removed; redirect the output (`bitwig-agent grid export ... > file`) |
| `status` takes nothing | `detail: "full"` |
