# Sound design and controlled comparison

Use this reference to translate a musical goal into a small, inspectable change. The recipes below are **starting hypotheses**, not claims that a sound has been heard or improved. Confirm the target instrument, musical passage, intended result and permission before editing; prefer [Controlled A/B](#controlled-ab) for creative changes.

## Knowledge is not native capability

The official [6.1.4 release notes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1.4/release_notes/) include the 6.1 Sampler/Tuner changes below. The online base manual identifies as 5.3; use it for underlying concepts, with newer release notes taking precedence. Public product features do not establish bridge support.

Private Grid/modulator evidence is tied to **Linux / Bitwig Studio 6.1.3 / API 25** and the exact supported loaded-JAR hash; the current gate rejects 6.1.4. Public SDK operations have a different compatibility boundary and need their own native verification. Follow [versions and sources](version-and-sources.md), [Grid and recipe boundaries](grid-and-recipes.md), current capabilities and fresh guarded targets. Modulator inventory is not mapping/edit support. A known API gap can use GUI work within the user's authorized scope, never to replay an uncertain native operation. Product knowledge alone grants no permission to play, render, save or upgrade.

## Start with the audible job

Identify what should change: attack, body, release, brightness, pitch stability, motion or spatial placement. Keep the same notes/velocities first, choose one control or a tightly related set, then compare in context. These are suggested directions rather than universal settings:

| Goal | First controls to investigate | What to observe or audition |
| --- | --- | --- |
| Pluck | Short amplitude attack, low sustain, decay fitted to note spacing; a separate short positive filter envelope for a bright onset. Start with restrained resonance and output. | Observe both envelope settings and note lengths. With authorized audio, check that the onset is defined without an unwanted click, and that releases do not smear the next note. A tiny nonzero attack can soften a discontinuity. |
| Pad | Slower amplitude attack/release; modest low-pass filtering; shallow, slow timbral modulation. Enable enough voices for the chord plus overlapping releases before adding unison/stacking. | Observe actual voice mode/count. Audition chord transitions, not only one held chord; check voice stealing, tails, low-mid buildup and output headroom. |
| Bass | Establish octave/root tuning and waveform first; shape amplitude release around the groove, then add filter movement or saturation. Choose mono/legato only when intended; keep low-frequency stereo spread restrained. | Use a repeated-note and interval passage. Observe pitch, retrigger/legato behavior and level; audition against the kick and in mono, rather than assuming more sub level means more definition. |
| Texture | Choose a sample region, granular mode or noise source; shape its envelope, then introduce bounded slow/random movement. Filter before increasing ambience. | Observe region, grain/motion controls and modulation range. Audition several cycles for stray transients, excessive wash or unstable peaks; report randomness rather than attributing one lucky pass to the edit. |
| Transient | Inspect amplitude attack, sample start/onset, gate/envelope mode and any dynamics processing. Reduce an excessive attack or lengthen it to soften; adjust decay/body separately. | Inspect the waveform/envelope at the actual onset. Audition whether timing and impact remain right; peak height alone is not perceived punch. Check whether a compressor is shaping the attack or simply changing level. |
| Tone | Adjust source waveform or sample character, filter cutoff/keytracking and resonance before broad EQ boosts. Try modest saturation only with gain compensation. | Compare low and high notes and the full mix. A spectrum can show frequency redistribution, not whether the result is warmer, clearer or preferable. |

Source basis: [advanced device concepts](https://www.bitwig.com/userguide/latest/advanced_device_concepts/), [modulation and voice controls](https://www.bitwig.com/userguide/latest/the_unified_modulation_system/), and [Grid envelopes/voice lifetime](https://www.bitwig.com/userguide/latest/on_grid_signals/). Exact control availability depends on the selected device and mode.

## Chain order and modulation

A useful initial serial order is **note processing → instrument → corrective/timbral processing → spatial effects → final gain comparison**. It is a proposal, not a mandated chain:

- EQ/filter before distortion changes what drives the nonlinear stage; after distortion it shapes the generated harmonics. Compare these as different candidates, not interchangeable moves.
- Dynamics before ambience shapes the source; dynamics after ambience also reacts to the tail. Keep the musical reason explicit.
- Confirm nesting: an effect inside one Drum Machine cell processes that cell; after Drum Machine it processes the combined output. Instrument **FX/Post FX** processes the instrument's combined audio output and travels with its preset. **Wet FX** affects only the wet branch; **FB FX** lies inside feedback and can strongly alter accumulating level. Do not add feedback casually.
- Layers run in parallel; selectors select a chain. Summing two candidate chains is not A/B. Equal wet/dry or branch settings need not produce equal loudness, and parallel paths can interact in phase.

For each modulation, record **source → target, base value, amount/sign, rate/time unit, trigger/reset behavior and voice scope**:

1. Set the unmodulated base sound first. Start with a small depth and observe the actual parameter excursion; mapping amounts are relative and are not automatically destination units. Do not translate a raw bridge number into milliseconds, hertz or decibels without supported semantics.
2. Choose unipolar movement for an excursion from a base (for example, an envelope opening a filter); bipolar movement for movement around it (for example, vibrato). A negative mapping reverses direction. Inspect source polarity and both endpoints rather than assuming the sign alone describes the result.
3. Use tempo-synced rates for a repeated rhythmic relation and free-time rates for independent motion. Observe whether the source retriggers per note, follows transport or free-runs; these make otherwise identical values behave differently.
4. Choose shared/monophonic modulation for a common sweep; **Per-Voice**, where supported, for independent note movement. Device-level polyphonic modulation does not guarantee polyphonic control of a nested effect or plug-in: it is often summed to mono there. CLAP support is plug-in-specific. Voice stacking is multiple voices per note, not a substitute for chord polyphony.
5. Inspect existing automation and other mappings before changing the base value. When motion is missing, check source activity, destination eligibility, depth, base/range limits and trigger source before increasing depth.

Source: [nested chains](https://www.bitwig.com/userguide/latest/advanced_device_concepts/) and [Unified Modulation System](https://www.bitwig.com/userguide/latest/the_unified_modulation_system/). GUI mapping instructions in the manual are not promises of structured automation APIs.

## Sampler in 6.1: choose the behavior first

Source for this section: [6.1 features and subsequent fixes in the 6.1.4 notes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1.4/release_notes/). Inspect the live device/module: old tutorials and the base manual may describe earlier controls.

- **Repitch** couples pitch and speed. Start with Clean when diagnosing pitch/timing; Analog and Digital are alternative coloration choices, not automatically better quality.
- **Spectral** separates stretching work from traditional repitch behavior. Start with Pure for a stretching comparison; Reharm deliberately changes harmonic relationships. Inspect Preserve Onsets for transient material. Quality/Cost and Live Performance Mode affect processor/latency tradeoffs, so record them in a comparison.
- **Fragments** is for granular clouds and microloops: begin with grain size, density (or uncoupled rate), envelope and modest Motion. **Textures** remains another granular choice; **Cycles** extracts waveforms for synthesis-oriented treatment. Compare one mode before adding several modulations. Shared parameter values across modes do not guarantee identical sound or loudness.
- **Analyzed Root Key** uses a detected pitch track at the current sample position rather than one fixed root. Check analysis on the actual material, especially ambiguous/polyphonic passages. If using a fixed root instead, verify root/fine tuning before compensating with unrelated pitch controls.

For slices:

1. Set the blue **Slicing Start/End** region. Choose Divisions for equal pieces, Beats for tempo-based spacing, Onsets for attacks, Pitch for detected pitch changes, or Manual. Verify detected BPM or pitch instead of trusting analysis blindly.
2. Inspect boundaries and audition attack/release behavior when authorized. Manual adjustments can refine computed slices. Changing slicing mode/parameters recreates deleted computed slices; convert slices to manual when that is the intended preservation method.
3. Choose how slices are triggered. All/White/Black keys allocate slices to keys and disable Key Tracking; Velocity selects by strength; **"Select" parameter** selects at note-on while leaving pitch and velocity available. Velocity sensitivity can still affect output level.
4. Distinguish **Play Mode** (Repitch/Spectral/etc.) from sliced **Playback Mode** (to Slice end, to Sample end, One-way looping, Ping-pong looping). Non-looping modes can use One-shot or Gated envelopes; looping modes require Gated. Observe note-off behavior and intentional choking/polyphony. The drag-created sliced configuration uses One-shot and Digi Mono, so a new slice can choke the previous one.
5. A generated slice-note clip reflects sample parameters, not all device playback settings such as Speed. Inspect its timing against the actual playback rather than assuming reconstruction is exact.

**Removed modulation capabilities:** in 6.1, Sampler **Play Mode, Freeze Playhead and Envelope Mode** are no longer modulatable/automatable. Old set values load, but their modulation/automation is disregarded; 6.1.1 also removes obsolete remote-control/modulation mappings properly. Some other mode switches are intentionally non-modulatable and kill active notes when changed. Do not invent a mapping workaround or silently downgrade a project. If preserving an old performance depends on those mappings, report the limitation and agree on preservation before migration.

**Tuner:** the new Analysis device and Display Grid module show note, cents and frequency; inspect Silence Threshold and Reference Frequency (A3, default 440 Hz). Use a stable single-note passage to check tuning. A tuner display is not proof of all chord fundamentals, clean audio or subjective tone quality.

## Grid: build the smallest signal path

Use Poly Grid for an instrument, FX Grid for audio processing, and Note Grid for note processing/generation. A practical initial instrument is **oscillator audio → envelope's audio input/output → Audio Out**, with note pitch to oscillator pitch and note gate to the envelope. This is a conceptual patch, not a guaranteed callable recipe; the supported scratch recipe and its voice-count gap remain in [Grid and recipes](grid-and-recipes.md).

- **Pitch is not audio or MIDI note number.** The Grid pitch signal uses 0 for C3 and 0.1 per octave. Confirm pitch input attenuation and tracking. Do not apply this signal convention to raw parameter values.
- **Gate is not pitch.** A gate keeps an envelope held; a trigger is a low-to-high transition. Logic inputs treat values at/above 0.5 as high. Inspect actual gate changes when repeated notes fail to retrigger.
- **Inspect pre-cords.** They are wireless connections: note pitch tracking, envelope note gate and oscillator retrigger can be connected without visible cables. Before adding explicit Pitch In/Gate In, decide whether to replace or combine with the existing pre-cord; avoid unintended doubled pitch/control contributions. For manual oscillator pitch tracking, check the pitch input attenuator as well.
- **Follow actual ports.** An envelope's control output is not its shaped audio output. Check oscillator → envelope → output signals in module Inspector scopes. One cable can carry stereo; some ports intentionally collapse to mono. Module help, not color alone, defines signal semantics.
- **Envelope and voice lifetime are separate from topology.** Inspect Affect Voice Lifetime and voice count/mode. Envelope release can keep a voice alive; Audio Out can also keep it alive by its threshold/hold conditions when enabled. Too few voices can truncate chord releases even with a correct cable graph. More voices/stacking can increase level and load.
- **FX Grid silence can be voicing.** True Mono runs continuously; polyphonic or Digi Mono FX Grid requires note triggers. Inspect Note Source and Auto-gate rather than blindly rebuilding its audio path. For Note Grid, Audio Thru suspends its Audio Out modules; inspect thru options when expected generated audio or notes are missing.
- **Current names:** 6.1 renamed **by Scale → Key Quantize** and **by Semitone → Semitone Quantize**. **Pitch Quantize** remains a separate module. Discover current catalog/instance IDs; never substitute display names for IDs.

Sources: [Grid editing/help/scopes](https://www.bitwig.com/userguide/latest/welcome_to_the_grid/), [pre-cords and thru behavior](https://www.bitwig.com/userguide/latest/special_connections/), [signals and voicing](https://www.bitwig.com/userguide/latest/on_grid_signals/), [6.1 naming changes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1.4/release_notes/).

### Debug by observation, not patch size

For silence, trace **input notes/audio → source → envelope/gate → output → track routing/mute/level**. Inspect the first point where the expected signal disappears. For wrong pitch, inspect pitch pre-cord, explicit cable, attenuator, oscillator offset and sample root. For clicks or missing tails, inspect gate edges, envelope stages, sample/loop boundaries and voice stealing. For unexpected drones, inspect an envelope bypass or direct oscillator-to-output connection: deleting a through-processing module can reconnect its neighbors automatically.

Use the selected module's real Inspector scopes and F1/help view to explain what is present. A stored cable graph alone does not prove an active signal graph; a moving meter does not prove the intended sound. Report observed values/signals separately from suspected causes. Direct feedback is rejected by The Grid; the documented Long Delay feedback path is a deliberate synthesis choice, not a generic fix for a missing cable.

## Controlled A/B

Prefer this for a creative timbre, arrangement or mix change. Mechanical work such as naming or moving an already-approved object does **not** require an A/B experiment.

1. **Preserve independent A and B before editing.** A is the original; B is the candidate. Choose a proportional method: an independent device/preset copy for a contained patch change, an independent track/chain and necessary clip material for routing or performance changes, or a separately authorized project copy for wider state. Capture all state that the proposed change can affect, including automation, mappings and routing. Labels or a screenshot alone do not preserve a patch. Do not create project copies or save presets outside permission.
2. **Check independence.** A/B chains must not share the control being changed. For clip-content edits, 6.0 aliases share a pattern: use **Make Unique** or a verified independent duplicate before editing B. Do not rely on different clip names as proof. Keep A untouched and select only one audible candidate at a time; do not sum both. Source: [6.0 release notes](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html).
3. **Define one intentional difference.** State the hypothesis, target and before/after controls. Keep the same notes, velocities, musical range, tempo, automation and surrounding mix unless one is the explicit variable. Include attacks, sustained notes, transitions and releases relevant to the goal. Compare in the mix as well as solo when appropriate and authorized.
4. **Reduce loudness bias.** Equal fader settings are not proof of loudness matching. Use available measured loudness over the same passage and an explicit comparison trim, then user audition at comparable perceived level. Peak matching alone does not establish equal perceived loudness. Record the metric/window and trim actually used; if only peaks or meters are available, say the comparison remains approximate. Do not present the louder candidate as inherently better.
5. **Control time and variation.** Allow equal preroll and tails; do not switch while A's delay/reverb masks B. Account for latency/compensation, plugin quality modes, oscillator phase, free-running LFOs, round-robin samples and randomness. Use repeatable conditions when the device permits; otherwise disclose variation and compare multiple authorized passes rather than selecting a favorable take. Do not claim deterministic output without evidence.
6. **Show actual evidence.** Present real before/after screenshots with the target visible and a short value/routing diff; never substitute a mockup for the current patch. Where authorized and supported, provide clearly labeled A/B renders of the same passage with comparable export settings and full relevant tails. Check actual render/event extent rather than assuming loop length equals file length. Dispatch is not proof that a render exists or contains the intended signal.
7. **Let the user decide.** Summarize observed changes, remaining uncertainty and the next listening choice. If only numbers/images were inspected, explicitly report **“No audio audition performed.”** An audio file's existence or numerical analysis is not an audition. Do not claim B sounds better, warmer or cleaner without the corresponding listening evidence; user preference determines which version to retain. Preserve A until that decision and any authorized cleanup.

If any operation has unknown completion, **stop and retain the evidence**. Do not blind Undo, retry, restart or replay through GUI to recover the comparison. A later matching value does not acknowledge the earlier write. Report what is known and the uncertain boundary for coordinator recovery.
