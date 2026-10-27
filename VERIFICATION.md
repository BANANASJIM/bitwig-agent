# Verification

Checks prove only their exercised layer. The toolkit implements 45 methods; neither that count nor passing fixtures establishes comprehensive native acceptance. [PROTOCOL.md](PROTOCOL.md) defines requests, units, side effects and bounded semantics. This guide is not approval to operate a live DAW; use the actual check output for the revision being evaluated.

## Reproducible host-free checks

Run from the repository root. Prerequisites are Python 3.11+, `uv`, **JDK 25+**, and your own installed API 25 Bitwig JAR. The Bitwig 6.1.3 loader superclass requires JDK 25 to read; generated extension classes target Java 11. Neither the host JAR nor the JDK is redistributed. The build downloads and verifies pinned Gson on first use, so that first build requires network access.

```sh
uv sync --locked
export BITWIG_API_JAR=/opt/bitwig-studio/bin/bitwig.jar
uv run --locked python scripts/build.py
uv run --locked python scripts/check.py --report build/checks.json
```

Set `BITWIG_API_JAR` to your installation if different. If the default Java tools are older, set `JAVA=/path/to/jdk25/bin/java` and `JAVAC=/path/to/jdk25/bin/javac`. Building creates `build/BitwigAgent.bwextension`; it does not install or activate it. The default check runs the offline Python, simulated-host Java and MCP/Java fixture layers. It never starts or manipulates Bitwig. Missing prerequisites for a requested layer are failures, not a skipped success.

Run layers individually when diagnosing a failure:

```sh
uv run --locked pytest -q
uv run --locked python scripts/test-java.py
uv run --locked python scripts/smoke-mcp-java.py
```

Or select layers with `scripts/check.py --layer offline`, `--layer java`, or `--layer fixture`; repeat `--layer` to combine them. Keep the generated report with the exact source snapshot and prerequisites used. These instructions require no historical test artifacts.

| Layer | What it exercises | What it does not prove |
|---|---|---|
| Build/package | Java compilation and extension packaging | Activation, native observer ordering or host behavior |
| Python tests | Schemas, catalog, client/CLI/MCP envelopes and regression controls | Actual Bitwig mutations or sound |
| Java tests | Simulated host/domain semantics and actual loopback transport | Native private-model compatibility or DAW acceptance |
| MCP/Java fixture | Real Python MCP adapter and Java transport with a fixture handler | A live Bitwig project or native completion |
| Installed wheel/sdist | Distribution metadata, real offline entrypoints and MCP stdio discovery outside checkout | Extension activation, host connection or actual edits |
| Local Arch package | System dependency/build layout and installed Python consumer behavior | Availability in AUR, compatibility of the separately installed Bitwig host |
| Skill format | Agent Skills naming/frontmatter conventions | Safe model behavior, authorization or any DAW outcome |

Offline interface discovery is also available without the extension:

```sh
uv run --locked bitwig-agent-rpc list
uv run --locked bitwig-agent-rpc describe notes.edit
uv run --locked bitwig-agent-rpc tracks.set --help
```

These commands describe the implemented surface, not a successful native operation. `status` and other host-facing commands require the running extension.

### Optional music-analysis dependencies

```sh
uv sync --locked --group native-music
uv run --locked pytest -q
```

NumPy, SciPy and SoundFile are optional numeric/file-decoding dependencies, not production adapter requirements. Install this group to exercise optional audio-analysis tests; inspect the test report rather than interpreting omitted dependency-dependent checks as coverage.

The offline policies in [scripts/evidence_mix.py](scripts/evidence_mix.py) and [scripts/evidence_audio.py](scripts/evidence_audio.py) have synthetic controls in [tests/test_native_evidence.py](tests/test_native_evidence.py). `evidence_audio.analyze(out)` reads `out/music-predeclaration.json` and its declared audio paths and returns metrics only. A native caller must separately guard execution, persist results and establish render provenance. Synthetic PCM tests do not grant host access or prove native rendering. Authenticate and decode the same immutable bytes; a matching path or file presence alone is insufficient.

## Continuous integration

[CI](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/ci.yml) is configured to run host-free Python checks on Python 3.11 and 3.14 with the optional audio-analysis dependencies installed, plus Python 3.11 with the minimum supported MCP SDK 1.29.1. The minimum-SDK job uses `--no-sync` after substitution so a test command cannot silently restore the locked SDK. Package jobs build the Python source/wheel distributions and exercise **both** installed consumers' offline CLI and generated configuration's MCP stdio handshake outside the checkout, including the minimum-SDK combination. The package smoke is shared in [scripts/smoke-package.py](scripts/smoke-package.py).

The workflow also validates the portable skill with a pinned reference validator and scans reachable Git history for credentials with checksum-pinned Gitleaks. The handshake tests Python packaging and MCP startup, not a connection to Bitwig; consult actual workflow results rather than treating this configuration as passing evidence.

The separate [Arch workflow](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/aur.yml) runs on `release: published` or manual dispatch, not main-branch pushes, because its release assets must already exist. In a digest-pinned disposable official Arch container it checks `.SRCINFO`, builds/checks the real recipe as an unprivileged user, runs namcap, installs the package, exercises the installed MCP consumer and verifies an explicit extension copy into the container builder's home. This does not touch a native host/user installation or upload to AUR. See [packaging](docs/packaging.md#archaur-recipe) for the build/layout contract and actual workflow output for evidence.

Checkout is pinned to the `v7.0.1` commit, uses Node 24, and does not persist Git credentials. Standard `pull_request` events are used; untrusted fork code is not run under privileged `pull_request_target` or `workflow_run` triggers.

The workflow has read-only repository permissions and does not require repository secrets. GitHub-hosted runners are not supplied with the proprietary Bitwig host JAR: Java compilation, simulated-host tests and the MCP/Java fixture remain separate local checks. CI does not launch Bitwig or establish native, GUI, audio or persistence acceptance. A secret scanner is one check, not a guarantee that all private information has been identified.

### Release promotion checks

The manual [PyPI workflow](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/publish-pypi.yml) defaults to validation-only with `publish=false` and requires a `vX.Y.Z` tag. It verifies the published GitHub wheel, sdist and extension against the release manifest, checks Python package/tag versions and smokes installed wheel/sdist consumers. Only explicitly setting `publish=true` enables the separate `pypi` environment/OIDC publication job. These checks do not install the extension, contact Bitwig, publish to AUR or prove native compatibility. See [promotion and account prerequisites](docs/packaging.md#pypi-trusted-publishing).

To smoke a separately installed Python consumer, invoke the shared script with **that environment's Python**, not the checkout's editable installation:

```sh
/absolute/path/consumer/bin/python -I scripts/smoke-package.py --expected-version 0.1.2
```

The script runs its CLI/MCP consumers from a temporary directory outside the checkout. It performs discovery only, with no extension download, controller installation or native-host calls. A clean sdist-consumption check must actually build the sdist rather than reuse a cached wheel; the CI/promotion workflows install with `--no-cache`.

### Skill validation

Use the [pinned skills-ref command](CONTRIBUTING.md#agent-skill-maintenance) to validate `skills/bitwig-agent`, and inspect a standalone copy with its bundled references and license. `skills-ref` is a demonstration format validator, not a production dependency or an execution harness. A pass does not establish that an agent respects user permission, discovers fresh bindings, stops after unknown outcomes or handles actual Bitwig safely. Those require separate behavioral review and, only when authorized, bounded native evidence.

## Native preflight boundaries

Native testing is explicitly opt-in. Read the preflight configuration documentation without contacting Bitwig:

```sh
uv run --locked python scripts/smoke-native.py --help
```

The preflight requires Linux `/proc`, `xprop`, the Python development environment, an already running extension and a deliberately prepared disposable project. Its explicit JSON configuration identifies the fixture's absolute path, current process, actual display/window, endpoint, a new artifact directory and confirmed display isolation. Populate these from fresh local observation; never reuse another run's identities. After independently confirming them, invoke:

```sh
uv run --locked python scripts/smoke-native.py \
  --allow-native --config /absolute/path/native-preflight.json
```

This is a read-only MCP preflight, not music, GUI, write or persistence E2E. It performs no launch, installation, GUI input, screenshot or automatic retry. It excludes `notes.read` because that call selects a clip. It checks loopback socket ownership, including Linux IPv4-mapped TCP6 entries; a matching title or project name is **not** proof of the loaded full project path. Exact file identity and actual reopen behavior need independent evidence. The local endpoint is unauthenticated: keep it on trusted loopback and never forward it to a network.

Before any native operation, designate one exclusive writer and freshly identify the disposable project, process lifetime, actual window, endpoint and extension build. Preserve user projects; display isolation does not imply profile/audio isolation. Private Grid/modulator access requires Bitwig **6.1.3** and loaded host JAR SHA-256 `4245a38ccddaa800421935216351e6dda8384b34a128cbc85c21e2e6760cfefc`. Inspect each independent read/write gate; do not bypass mismatches or substitute an unrelated installed JAR's hash.

Native helpers can use `NativeOutcomeGuard` from [scripts/smoke-native.py](scripts/smoke-native.py): wrap RPC/GUI/parsing/evidence work in `guard.attempt()`, persist complete responses, then call `guard.observe()` before another operation. Both `error.outcome` and `result.completion` can be `unknown`. Dispatch-only Actions/selection and confirmed queued Launcher operations need explicit expectations and separate completion observation; a queue is not actual playback/stop. External image-tool failure must also stop the writer; Python cannot latch an error it never sees. A later healthy read establishes neither reset, cancellation, cleanup nor acknowledgement of an earlier write. **Never blindly retry an unknown outcome**, including through the GUI.

GUI receipts, independent renders, isolated Undo/Redo and save/reopen comparisons are separate evidence layers. Observe clip Fade and other experiment state before sealing audio predictions, retain full-tail tolerances, and compare authenticated immutable bytes. Reacquire module/modulator UUIDs after reopening and declare every normalization/exclusion. Native undo-aware dispatch does not promise one-call/one-Undo grouping. Model readback does not establish DSP or saved persistence.

Launcher regressions in `LauncherModuleTest` cover queued and immediately observed launch/stop, plus missing observations without re-dispatch. For a native queued case, inspect the slot separately from transport: a saved Launcher clip can remain engaged while transport is stopped. Declare any preparatory stop before writing and separately observe disengagement. Then verify queued launch and actual playing, stop the Launcher while transport still runs, and finally stop transport, retaining full receipts. Static GUI frames alone do not establish queue timing.

### Native 6.1.4 production check — unreleased source

An isolated Linux / Bitwig **6.1.4 / API 25** session exercised the public clip, note and transport path. Creation and exact eight-note window readback were observed; the saved eight-beat phrase was then reopened and independently verified with the final transport candidate. The temporary installed Python consumer came from commit `8d5137a`; the private candidate extension SHA-256 was `5488139de5fcef455a6f1057ab109d6afc327e881bc8ff6d8e8ca29c7d16f95f`.

The original combined stop/seek case **failed**: stop was observed, position remained nonzero, and completion was `unknown`. Its writer stopped and the failure was retained. The fix observes the asynchronous stop before dispatching remaining fields, under one bounded observation window and existing cancellation/safety guards. Regression tests fail against the earlier code and cover delayed stop, cancellation, a changed safety mode and an already-expired observation window.

The transport candidate's independent native case observed quantized launch → playing, quantized clip stop → stopped while global transport kept running, then exactly one `transport.set` with `playing:false, positionBeats:0`. Both fields reported `observer_after_write`; independent reads remained stopped at zero, including one 2.57 seconds later. The actual GUI showed `1.1.1.00 / 0:00.000`; all three recording/automation flags remained false and the target mix was unchanged.

This is a muted, bounded production-path observation, **not audio audition, universal timing proof or 6.1.4 private Grid support**. The private gate still rejects 6.1.4. Raw fixture/project/screenshots remain private rather than being redistributed. The staged transport fix is unreleased source; the published `0.1.2` extension does not include it merely because the development build retains that version label.

#### GUI-assisted A/B and reopen navigation

In the same isolated 6.1.4 fixture, GUI operation preserved the original Poly Grid pluck as A and duplicated an independent B, with the same eight-note phrase and eight-beat loop. The deliberately changed ADSR Decay was **180 ms on A / 360 ms on B**; actual canvas titles and hovered values were inspected independently. This used desktop input/capture outside the bridge, not a private-API gate bypass or a shipped automatic GUI fallback.

Separate non-destructive Pre-Fader bounces produced stereo 48 kHz, 32-bit-float files of eight seconds each. The observed repeating loop occupied seconds **[4, 8)**, distinct from the full file/event. File-only comparison cropped that same passage and applied constant attenuation, without compression, limiting or dynamic normalization:

| Measurement | A | B |
|---|---:|---:|
| Raw loop integrated loudness | −19.38 LUFS | −17.66 LUFS |
| Constant preview gain | −3.64 dB | −5.36 dB |
| Measured preview integrated loudness | −23.04 LUFS | −23.02 LUFS |
| Preview true peak | −9.78 dBTP | −11.48 dBTP |

The previews differed by **0.02 LU** and source files remained unchanged. **No audio audition or preference decision was performed.** Uncontrolled oscillator phase/voice state means these renders do not establish decay-only causality. A temporary desktop driver's same-position synchronized mouse move had timed out in the original GUI case; that failure remains retained. A separately planned case used explicit pointer-position observation before clicking. This helper correction is not a bridge feature or proof of a general desktop-automation solution.

Save, actual close/reopen, bounded note readback and inspection preserved the A/B tracks, renders and phrase. A subsequent owning-track selection exposed a separate failure: the legacy selected-state callback could remain unreadable after rebind, so the old setter-style wait returned `unknown` even though the UI navigated and auto-armed the track. Writes stopped; the original failed receipt remains failed.

The corrected `tracks.select` explicitly requests navigation once and returns **`dispatched` / `not_observed`**, not a fabricated observer acknowledgement; see [the selection contract](PROTOCOL.md#domain-controls). A new private candidate, SHA-256 `a25af7abda2e4b9a03c23c58cd4135deb65fb33136b8eb9d1539ef8dd6db7938`, independently exercised both intentional reselection and missing callbacks after another actual close/reopen. Actual GUI inspection established navigation; observed auto-arm was cleared, and saved A/B decay values remained 180/360 ms. The expanded Grid canvas could retain A while the lower device panel showed B: the canvas's own title, followed by closing the old editor and opening B's preview, established the correct target before any prospective edit.

That candidate also repeated quantized launch/stop and exactly one combined stop/seek. Both fields reported `observer_after_write`; independent reads more than two seconds apart and the actual GUI remained stopped at zero. All four source/render tracks ended muted, source arms and global record/write flags were false, mix values were preserved, and the saved project file was unchanged. These are bounded native observations, not universal selection/timing/persistence guarantees. **Neither fix is included in published `0.1.2`.**

## Coverage limits and unresolved results

This source snapshot supplies reproducible host-free checks, not a redistributable native acceptance corpus. The limitations below summarize prior bounded native observations; they are not a fresh native rerun or independently reproducible native pass claim from this checkout. Previous failures remain failures even where a separate correction or later case succeeded.

- **Host and scope:** native experience covers Linux / Bitwig 6.1.3 / API 25 plus the narrow 6.1.4 production cases above; other versions/platforms and wider 6.1.4 coverage remain unverified. Track, root-device and launcher windows are 16, sends eight; layers are one hop with two layers and two children per layer. Deeper hierarchy, project paging, named slots/drum pads, arranger clip edits, automation curves and dedicated master/cue controls are not supported. No lossless project export or all-module acceptance is established.
- **Identity and safety:** public index/name/context observations are not persistent physical-device identities or locks. Same-metadata replacement, native ABA, general races, atomic transport exclusion and project-wide routing preservation are not guaranteed. Module/modulator UUIDs can change on reopen. Topology quarantine can survive extension reload; full JVM restart does not prove rollback. Follow the exact recovery boundaries in [PROTOCOL.md](PROTOCOL.md).
- **MIDI:** grid settling is scheduled, not acknowledged. A connection case failed full MIDI preservation when one sentinel was observed at beat 1 initially versus beat 2 finally. A later first-read correction does not retroactively pass that case or prove a general callback barrier. Sub-step/multiple-onset cell data and whole-clip completeness are not established.
- **Grid reads and modulators:** stored cables need not be active signal paths. Unknown native children remain unsupported. Real Grid-family QEE coverage is absent; materialized Macro QEE observations do not establish Grid ownership or native unmaterialized-content behavior. Nonempty FX numeric Remote Controls, the native track-alias branch and forced conflict/unavailable-context cases lack comprehensive coverage. Selection and sends can remain partial or unreadable after reopen.
- **Insertion:** prior protected-return equality, wrong FX bounce tap, then-unsupported VALUE eligibility and continued writes after unknown outcomes were failed criteria. Subsequent model/audio observations do not excuse the continued writes or retroactively pass them.
- **Move/delete and audio:** six of 48 near-silent envelope windows failed the original relative criterion. Later absolute-error descriptions are not passage, waveform equivalence or a changed threshold. Disabled Note Grid DSP, rejected-cycle repair, general Undo grouping and injected-failure/race behavior remain unproved.
- **Boolean panels and layers:** earlier selection-derived aggregate and audio-alignment criteria failed. Earlier layer residual limits also failed; a separate zero-Fade case does not repair the earlier result or uniquely establish causality. Layer cold-start/index failures likewise are not erased by later corrected observations.
- **Naming:** bounded exact-name reopen observations do not supply missing BEFORE direct-device controls, controlled-source naming audio acceptance, or general Undo/persistence guarantees. Earlier pending-save failure remains distinct.
- **Scratch recipe:** authoring graph/control/MIDI readback is not audio acceptance. The original Mono Pad case lacked three chord fundamentals. Complete the explicit GUI **VOICES 8** step and use the recipe's Triangle Level `0.125` headroom setting; there is no voice-count API or automatic GUI fallback. A separate corrected Pad case does not retroactively pass Mono behavior, uniquely isolate the two simultaneous corrections, or establish whole-recipe persistence or physical listening.
- **Bounce extent:** full audio-event/file duration can differ from the repeating loop region. An original maximum-seven-second duration criterion failed; later inspection of a sixteen-beat event with an eight-beat loop does not change that failure or establish a universal bounce-generation rule. Inspect initial Start, full event and loop separately; no automatic render/save workflow is provided.

Passing build, Python, Java or MCP fixtures must not be promoted to native music/UI/persistence acceptance. Reviews are scoped analysis, not fresh execution or human approval.
