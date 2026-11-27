# Changelog

All notable user-visible changes are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While the version is `0.y.z`, a minor release may break compatibility; breaking changes are listed in their own subsection.

The Python package and the Java extension are released as a matched pair: update both together. Each version links to its GitHub release, which carries the assets and `SHA256SUMS`.

## [Unreleased]

### Breaking changes

- `notes.set {target, notes, range?}` replaces the window fields, `edits`, `op` and `detail`; notes are one record `{beat, pitch, velocity, duration, channel?}`. Without `range` the listed notes are inserted or updated and nothing is removed; with `range` (`"all"` or `{start, end, pitches?}`) that region becomes exactly the listed notes. `clips.insert` notes use the same record, and its top-level `channel` and `detail` are gone. The old fields are refused (#140).
- `automation.points.set` follows the same range rule; its `removed` and `inserted` result fields are replaced by `summary {inserted, updated, removed, unchanged}`. Under `range`, an omitted `interpolation` is `linear` and an omitted `curve` is 0 (#138, #154).
- `audio.analyze`, and the level report of `audio.capture`, need the optional `audio` extra (`pipx install 'bitwig-agent[audio]'`); without it they answer `unsupported_platform` (#141).
- CLI `batch` refuses read and `ack` lines at validation (exit 2) instead of running them (#144).
- The CLI `--slot` shorthand for `clips.import` is removed; use `--to` (#161).

### Added

- Recording: `transport.set` writes Global Record, overdub, automation write and `automationWriteMode`; new `clips.record` for a launcher slot; track inputs can be hardware audio buses and controller note inputs; each host write declares its transport requirement, shown by `help` as `Requires:` (#156).
- File placement: `tracks.insert {file}` makes a Sampler track from an audio file, `chains.insert` can fill a new chain with `insert`, and `clips.import` places a file on the arranger at `{track, start}` (#161).
- Note operators (`chance`, `repeat`, `recurrence`, `occurrence`, `muted` and more) and note expressions on the note record (#153).
- `automation.points.get/set` on any direct device parameter, with `{display}` values, readable `display` and writable `curve` (#148); several points at one beat (steps) (#150).
- Tracks and devices: `tracks.duplicate`, `tracks.convert`, `tracks.ungroup`, group deletion with its children, `devices.duplicate`, `devices.replace` (#151).
- Chains: mixer strips in `chains.list`, `chains.set`, Selector choice, and `chains.insert` for layers and Drum Machine pads (#145).
- `devices.insert {params}` and `modulators.insert {params, map}` configure in one call (#139).
- `project.key.get` and `project.key.set` for the project key signature (#142).
- `export.audio` writes mp3, ogg and opus, or several types in one pass; `launcher.stop {target:"all"}`; `launcher.returnToArrangement`; send Pre/Post `mode` (#149).
- `batch` is one core method for the CLI and MCP (#144).
- Record-list reads take `format` (`json`, `jsonl`, `csv`, `tsv` or a view) and `fields`; `notes get --format=grid` and `arranger list --format=timeline` (#152).
- `audio.analyze` takes `windowBeats` and a `.wav` `reference` file for A/B level comparison (#133), and accepts `.flac` (#141).
- `arranger.list` without `target` covers the whole arrangement with bars, markers, tempo and time signature; `tracks.list` rows carry mix columns (#132).
- Name tables carry each member's JVM descriptor, and `pixi run remap --from-table` maps an existing table to a new Bitwig release or platform (#158). The remap tool itself moved to `scripts/remap` (#134).

### Changed

- While recording, structural inserts (`tracks.insert`, `clips.insert`, `clips.copy`, `devices.insert`, `chains.insert`, `scenes.insert`, `scenes.duplicate`) are refused; while recording or automation write is on, `transport.set playing:true` is `unsafe_state` (#156).
- When CLI `batch` stops, it prints a `not_reached` line for each input line it never sent (#133).
- `arranger.list` rows are sorted by `start` within a track instead of creation order (#132).
- `markers.insert` moves the playhead to the marker's beat for the add and puts it back (#143).
- One 16 MiB message cap for requests and responses (`maxMessageBytes`; requests were 1 MiB) (#140).
- `audio.analyze` runs on numpy, scipy and soundfile, about 10 to 30 times faster; true peak uses 4x oversampling (#141).
- New runtime dependency `wcwidth` (#152).
- A native binding failure for the loaded jar is kept instead of retried on every request, and its error message has one form (#158).
- A name-table key missing for the running Bitwig version fails only the feature that uses it, with `native_unavailable` (#142).

### Fixed

- Key chroma in `audio.analyze` no longer folds energy from above 2 kHz (#141).
- `markers.insert` works when the playhead sits on an occupied beat (#143).
- Equal `tracks.set` writes after a group or insert, and equal display-form volume, pan and send writes, are `already_matched` (#143, #146).
- `tracks.sends.set` with a level plus `enabled:true` is `applied` (#143).
- A batch stop whose entry has no top-level `next` uses the kept `error.next`, and the MCP batch `timeoutMs` refusal carries `entries`, one per call (#146).

## [0.5.0] - 2026-11-27

Protocol 0.5. No compatibility with 0.4 clients, extensions or field names. Supported hosts: Bitwig Studio 6.1.4 and 6.1.7 (API 25), each with the exact loaded-JAR gate for private access. The public API works on all platforms; the private API is supported on Linux.

### Breaking changes

- `bitwig-agent` is the Unix CLI and `bitwig-agent-mcp` the MCP server; `bitwig-agent-rpc` is removed. Regenerate MCP configs with `bitwig-agent-install --mcp-config` (#103, #104).
- Every request carries `"protocol":"0.5"`; every result has one top-level `outcome`; an error means nothing changed (#102).
- Closed error codes and reasons: `stale_*` is replaced by `target_not_found`, `target_changed` and `unsafe_state`; `extension.restart` and `grid.topology.reconcile` are replaced by `ack` (#102, #115, #118).
- Refs replace `trackId`, `deviceIndex` and `expected*` guards; many methods are renamed, merged or split (#108, #109, #111-#114).
- List methods take `target`; presets take `deviceName` (#124).
- Writes accept what reads return: `devices.insert` takes `to`, optional `position` and the `insert` object from `presets.search` or `devices.catalog`; `devices.move` takes `position` (#130).
- `verification` is a closed enum: `native_readback`, `api_readback`, `file_readback`, `system_readback` (#130, #136).
- `notes.read/edit` are `notes.get/set`; time fields drop `Beats`/`Beat`; `tempoBpm` is `tempo` (#131).
- `grid.apply` steps no longer carry a per-step `outcome` (#136).
- Grid's low-level methods are no longer agent-callable; the last 0.4 field names are removed (#111, #128).

### Added

- Bitwig 6.1.7 support through per-version native name tables and a multi-version build (#129).
- Unix CLI and MCP surface generated from one method table; Agent Skill rewritten for 0.5 (#103, #104, #117).
- Devices inside layer chains (#116), one-call partial Grid change (#111), scrolling launcher scene window (#112), track window of 1024 (#83).
- Every `applied` result, field and batch entry carries `verification` (#136).
- `audio.route` reads its PipeWire links back before reporting `applied` (`system_readback`) (#136).
- `status` reports `supportedBitwig`.

### Fixed

- Ratio displays in `{display}` writes (#122).
- Poly Grid bass path, sidechain hints and export source semantics (#123).
- CLI kind alias, method index and filterable `devices.catalog` (#124).
- Note grid rules applied in `clips.insert` and `notes.set` (#125).
- `export.audio` warns by what each track renders (#126).
- Launcher notes and clip inserts are one Undo step (#127).
- `grid.render` reads its image back (`file_readback`) (#136).

## [0.4.0] - 2026-11-25

Same host boundary as 0.3.0: Bitwig 6.1.4 (API 25) with the exact loaded-JAR gate. 98 MCP tools.

### Added

- Mixer, arranger and project: `tracks.set` volume and pan in dB and percent, `tracks.sends.set`, `tracks.meters`, cue `markers.*`, time signature; arranger timeline clips (`arranger.clips.*`); group tracks (`tracks.group`) and `tracks.delete`; arranger automation points for volume, pan and Grid parameters (`automation.points.get/set`); `export.audio` and `bounce.inPlace` through Bitwig's render jobs, with `render.status`; `project.saveAs` without a dialog, `project.activateEngine` and the `projectReady` write gate.
- Grid and devices: device modulators (`modulators.*`); device chain insert position, delete, move and layer insert; offline `presets.search`; `devices.catalog`; `grid.apply pruneAll` with up to 4096 cables; `grid.export`, `grid.graph`, `grid.probe`; measured raw scales with warnings; user Remote Controls pages (`devices.remote.define`).
- Audio: `audio.capture` records Bitwig's own outputs and solos `soloTrack`; `audio.analyze` modes `loudness` (BS.1770) and `bands`.

## [0.3.0] - 2026-11-25

Same host boundary as 0.2.0: Bitwig 6.1.4 (API 25) with the exact loaded-JAR gate.

### Added

- Grid: exact-first `typeName` lookup and `status.instance` (#16); `grid.apply`, a declarative re-appliable Grid patch with `replace`, string params, pruning, reusable `blocks` and time-unit display writes (#17, #23, #26); string panels such as Comment text (#18); `grid.render` layout images (#20); `grid.describe` module docs from the local install (#22); step arrays and Poly Grid voicing (#24).
- Audio and diagnostics: `audio.route`, `audio.capture` and `audio.analyze` for output verification (#21); `host.diagnostics` classifies the Bitwig log (#25).
- Project saved state and `project.save`, `clips.edit`, `context.get`, scene append, a shared selection lease and a shared root-device target (#8-#15).

### Changed

- The per-device Grid module cap is raised from 128 to 1024 (#19).

## [0.2.0] - 2026-10-27

### Changed

- Private Grid and modulator bindings target Bitwig 6.1.4 (API 25) and its exact host-JAR SHA-256, replacing the 6.1.3 bindings; this is not dual-version support.
- The adapter uses MCP SDK 2.3+ (`mcp>=2.3.0,<3`, `pydantic>=2.12,<3`); there is no SDK 1 compatibility path. Python stays 3.11+.
- Unexpected adapter exceptions surface as MCP JSON-RPC errors; do not treat them as permission to retry.
- `tracks.select` reports `dispatched` or `not_observed` instead of completion.

### Fixed

- Asynchronous combined stop and seek.

## [0.1.2] - 2026-10-26

### Added

- Installation from PyPI (`uv tool install bitwig-agent`), with persistent and version-pinned one-off usage documented.

### Changed

- Python package, MCP server, Java extension, portable skill and AUR recipe aligned at 0.1.2. No runtime control or native safety-gate changes.

### Fixed

- README links use absolute public URLs so they work on PyPI.

Earlier releases (0.1.0, 0.1.1) are described on the [GitHub releases page](https://github.com/BANANASJIM/bitwig-agent/releases).

[Unreleased]: https://github.com/BANANASJIM/bitwig-agent/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.5.0
[0.4.0]: https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.4.0
[0.3.0]: https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.3.0
[0.2.0]: https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.2.0
[0.1.2]: https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.1.2
