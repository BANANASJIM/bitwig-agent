# Bitwig Agent

An experimental Bitwig Controller Extension with Python MCP and CLI entrypoints. It lets agents inspect and control tracks, devices and MIDI clips, and author **The Grid** patches through explicit, structured tools.

The agent owns task planning. This project provides domain tools, target checks and observed results—not another planner, a full project mirror or automatic GUI fallback. For controls outside the API, the agent can use Computer Use explicitly.

## Status and compatibility

- Native experience is limited to **Linux with Bitwig Studio 6.1.3 / Controller API 25**. Other operating systems and host versions are not established.
- Private Grid/modulator access requires the exact mapped host version and loaded-JAR SHA-256. A mismatch disables that access; do not bypass the gate.
- There are **45 implemented methods**, with bounded coverage rather than comprehensive native acceptance. Start with a disposable project and retain backups.
- A successful dispatch, model readback, rendered audio and saved persistence are different claims. See [VERIFICATION.md](VERIFICATION.md) for reproducible checks and unresolved coverage limits. No fresh native acceptance is claimed for this publication snapshot.

## Architecture

- **Python:** MCP stdio and the CLI share one tool catalog, strict Pydantic schemas and a loopback client.
- **Java extension:** a bounded JSON transport dispatches work through host scheduling into separate track, transport, device, parameter, launcher, Grid and modulator domains.
- **Bitwig:** owns the actual project and audio engine. The extension keeps bounded observer state, not a second complete project model. Grid-specific native bindings are isolated behind a versioned adapter.

The local protocol is newline-delimited JSON over TCP. Requests are not automatically retried.

## Build and run

Prerequisites: Python 3.11+, `uv`, **JDK 25+ to compile and run Java checks**, and your own installed Bitwig API 25 JAR. The Bitwig 6.1.3 loader superclass uses class-file version 69; generated extension classes still target Java 11. Neither the host JAR nor JDK is bundled. The build downloads and verifies a pinned Gson dependency on first use.

The default API JAR is `/opt/bitwig-studio/bin/bitwig.jar`. Override `BITWIG_API_JAR` for another installation; if PATH selects an older JDK, set `JAVAC=/path/to/jdk25/bin/javac` and `JAVA=/path/to/jdk25/bin/java`.

From the repository root:

```sh
uv sync --locked
uv run --locked python scripts/build.py
uv run --locked python scripts/install-extension.py
```

The build produces `build/BitwigAgent.bwextension`. On Linux the installer copies it to `~/Bitwig Studio/Extensions/`; use `--destination /path/to/Extensions` for a custom location. An existing different build is backed up outside the extension scan directory.

Open Bitwig and enable **Bitwig Agent** in Settings → Controllers. It needs no MIDI ports and listens on IPv4 loopback `127.0.0.1:61172`. Start the MCP stdio adapter:

```sh
uv run --locked bitwig-agent
```

Use [examples/mcp-config.json](examples/mcp-config.json) as a client template, replacing its checkout path. No client configuration is installed automatically. Build, adapter startup and default checks do not launch Bitwig automatically.

The diagnostic CLI uses the same bridge without an MCP session:

```sh
uv run --locked bitwig-agent-rpc list
uv run --locked bitwig-agent-rpc describe notes.edit
uv run --locked bitwig-agent-rpc tracks.set --help
uv run --locked bitwig-agent-rpc status
```

`list`, `describe` and method help work offline. Host-facing connection failures report `outcome: not_dispatched` until the extension is available.

**Local trust boundary:** the extension's TCP endpoint has no authentication. Any local process that can connect can invoke its tools. Use it only in a trusted local environment; do not expose or forward the port to a network. Target checks and context tokens protect against stale edits, not unauthorized clients.

## Control domains and boundaries

| Domain | Implemented surface |
|---|---|
| Discovery | Status, capabilities, tracks, root devices, fixed one-hop layers and host Actions |
| Transport | Play/stop, raw tempo, seek, metronome and arranger loop, with recording/automation safety guards |
| Tracks | UUID-targeted mix/name/mute/solo/arm, selection, audio/instrument/effect creation and send readback |
| Devices and parameters | Bypass, selection, native/plug-in/preset insertion, eight Remote Controls with page/context guards; source-bound normalized direct parameters on roots and fixed layer children |
| Launcher/MIDI | Empty-slot clip creation, local audio/clip-file import, launch/stop, explicit-channel grid note reads and bounded edits |
| Grid | Native catalog, bounded graph/metadata/footprint and parameter-context reads; guarded raw scalar and coupled boolean-panel writes; known-catalog insertion, move/delete, raw custom-name CAS and single-input cable CAS |
| Modulators | Read-only primary inventory on an explicit root device and exact QEE string-panel reads; no loading, mappings or writes |

The registry implements **45 methods**, not 45 comprehensively native-accepted tools. Track, root-device and launcher windows are 16 each; sends are eight per track. Layers are one hop: two layers per root and two children per layer. Deeper hierarchy, named slots/drum pads, project paging, arranger clip editing, automation curves and dedicated master/cue tools are outside the structured surface. Bounded reads are not lossless whole-project exports.

Private Grid/modulator access is gated to the mapped **Bitwig 6.1.3 loaded host JAR** and fails closed otherwise. Read and write availability gates are separate. Grid-QEE source support lacks a real Grid-family fixture; Macro-modulator evidence does not establish that coverage. See [PROTOCOL.md](PROTOCOL.md) for actual schemas, limits and side effects, and [VERIFICATION.md](VERIFICATION.md) for checks and bounded coverage.

## Target safely, then observe completion

1. Discover `capabilities`, `tracks.list`, then `devices.list trackId=...`. Use the returned track UUID, zero-based device index and exact current name—not a remembered GUI selection.
2. For direct writes, obtain the `contextToken` from a fresh `parameters.list`; Remote Controls use their own observed-page token. Layer targets come from `devices.layers.list` and `parameters.layer.list`, including their discovery/context tokens. Switching targets requires a fresh binding.
3. For Grid structure, start with `grid.get moduleOffset=0 moduleLimit=32 includeParameters=false` and the explicit device target. Inspect page completeness and unsupported coverage. Discover exact module/port/control IDs rather than guessing labels; read a control immediately before its compare-and-set write. Raw numeric values are not automatically seconds, hertz or decibels. Rename CAS uses raw `customName`, not effective display name.
4. For `tracks.set`, send only changed fields from a fresh readable snapshot and inspect verification per field. For every write, inspect `verification`, `mutationDispatched`, `outcome` and `completion`, not merely the existence of a result.

Names, context tokens and module UUIDs are not persistent physical identities. Module/modulator UUIDs changed on actual reopening; reacquire them. `window.complete` is page coverage, not proof of whole-Grid completeness. Stored cables are not a proven active signal graph. MIDI settling is explicitly scheduled but unacknowledged.

**Never blindly retry an uncertain write.** Timeout or a dropped response may mean it happened; later matching readback is not a write acknowledgement. Stop and inspect before deciding on another mutation. Readback does not establish audio output, saved persistence or one-call/one-Undo grouping. Actions/selection may be dispatch-only and need separate completion observation.

For New Project, Save As/Bounce or an unexposed control, use explicit Computer Use only after confirming the actual target/dialog, then observe completion. GUI-completed work is not structured API support; do not repeat an uncertain API write through the GUI. Native work requires one designated writer and a disposable fixture, not a stale process/window snapshot.

## Make Grid instruments without preset files

[examples/grid_music.py](examples/grid_music.py) creates two muted, disarmed instrument tracks and eight beats of pluck notes and pad chords. In `--from-scratch` mode it discovers the installed factory Poly Grid and native MODULE catalog, replaces only the new devices' default modules, constructs Union → ADSR → Audio Out, and configures contrasting triangle pluck/pad envelopes through fresh typed raw CAS:

```sh
uv run --locked python examples/grid_music.py \
  --from-scratch \
  --project 'EXACT DISPOSABLE PROJECT NAME' \
  --artifacts /absolute/path/new-evidence-directory \
  --allow-write
```

No caller-supplied `.bwpreset` is needed. Type UUIDs, instance/port/control IDs and current values come from discovery; the fixed Analog ADSR raw settings are native-observed calibrations, not a general seconds-to-raw conversion. The recipe verifies its owned module/cable/control graph, exact note window, enabled eight-beat loop with playback starting at zero, and final muted/disarmed tracks. This is native model readback, not DSP, audio or persistence acknowledgement.

**Complete the scratch Pad with explicit Computer Use:** use its returned owned track ID to select its Poly Grid device, open the device inspector, and set **VOICES to 8** using the numeric field's **Type value** interaction; observe the result before rendering. The factory device in the native case was **Mono / 1 voice**, and its nine exposed direct parameters did not include voice count. Four-note chords need polyphony; eight voices allow overlapping releases. The recipe uses Pad Triangle Level `0.125` per voice for headroom, leaves the Pluck at `1.0`, and reports this required GUI step in stdout `next`. This is an explicit agent workflow, not automatic GUI fallback or a new API. [Bitwig's voice-management guide](https://www.bitwig.com/userguide/latest/on_grid_signals/#voice_management_in_the_grid) explains polyphony and envelope-managed voice lifetime.

To use your own host-authored presets instead, **omit `--from-scratch` and supply both** `--pluck-preset /absolute/pluck.bwpreset --pad-preset /absolute/pad.bwpreset`. Mixed or incomplete modes reject before connecting; presets are never fabricated or bundled.

The recipe checks the named disposable project and explicitly false playback/recording/automation flags before each mutation. The name is an observed label, not full-path identity or an atomic project lock. Use a complete ungrouped track window. Only `report.json`'s `createdTracks` and their slot-0 clips/devices are owned outputs; existing tracks are not edited. Every response and partial ownership is retained. It does not play, render, save, undo or retry. Unknown completion stops even with `status: observed`; inspect partial changes rather than rerunning the recipe.

Standard output is a compact JSON summary with owned track IDs, `reportPath`/`callsPath`, and the next required action on successful authoring. A null note count is unverified, not empty. Full graph/control/note observations remain in those files for on-demand inspection instead of being repeated into the agent context.

For explicit GUI follow-up, select the actual owned Launcher clip and verify its inspector before choosing its context-menu **Bounce...**; selection/Action dispatch alone does not establish that context. Observe the dialog and result separately. For looping bounces, inspect the full audio-event/file extent as well as the repeating loop region: they need not match. See [coverage limits](VERIFICATION.md#coverage-limits-and-unresolved-results); this is not an automatic rendering/fallback promise.

## Checks and project navigation

```sh
BITWIG_API_JAR=/opt/bitwig-studio/bin/bitwig.jar uv run --locked python scripts/check.py --report build/checks.json
```

The default check is host-free and never starts/manipulates Bitwig. Optional audio-analysis dependencies are separate from the production adapter: `uv sync --locked --group native-music`. See [VERIFICATION.md](VERIFICATION.md) for layers, prerequisites and what these checks do not prove.

- [PROTOCOL.md](PROTOCOL.md): executable request/response reference.
- [VERIFICATION.md](VERIFICATION.md): reproducible checks, native preflight boundaries and unresolved coverage limits.
- [CONTRIBUTING.md](CONTRIBUTING.md): development prerequisites, contribution boundaries and CI scope.
- [SECURITY.md](SECURITY.md): private vulnerability reporting and local-endpoint trust boundary.

### Repository map

| Path | Responsibility |
|---|---|
| `src/bitwig_agent/` | Strict schemas and shared catalog; independent transport client, MCP adapter and CLI |
| `extension/src/main/java/dev/bitwigagent/` | Transport/composition, host services, domain modules and bounded native adapters |
| `extension/src/test/`, `tests/` | Simulated-host Java checks and host-free Python regressions |
| `scripts/` | Build/install/check entrypoints and pure offline evidence helpers |
| `examples/` | Explicit opt-in music recipes, not automatic test setup |

The default-package `extension/src/main/java/BitwigAgent613GridInsertion.java` is intentional: its native superclass is also in the unnamed package and it is loaded by that exact class name.

## License and third-party dependencies

Project code is licensed under the [MIT License](LICENSE).

Gson is distributed under Apache-2.0; see [THIRD_PARTY_NOTICE.md](THIRD_PARTY_NOTICE.md) and [its license](third-party/GSON-LICENSE.txt). Bitwig Studio and its installed host JAR are separate proprietary dependencies; the build does not package the host JAR into the extension.
