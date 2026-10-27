# Versions and official sources

## Product knowledge and automation are different

Reviewed **2026-10-26**: the official release archive lists **Bitwig Studio 6.1.4** as the latest stable release. Recheck the archive before answering a later “latest version” question or recommending an upgrade. Do not install an upgrade merely because this knowledge reference is newer than the user's host.

Keep these four versions separate:

1. **Running Bitwig version and platform:** observe the actual application, not only a package installed on disk.
2. **Product documentation:** the online `latest` user guide still identifies as **5.3** at this review. Use it for underlying concepts; newer release notes override changed controls, names and workflows.
3. **Installed bridge/client/extension:** discover actual tools and capabilities. A newer repository `main` does not mean a released wheel or installed extension contains those changes.
4. **Native evidence:** a successful operation proves its stated scope, not every feature on that host. Public SDK operations, private native bindings, GUI interaction, audio and saved persistence need separate evidence.

Version **`0.2.0`** maps private Grid/modulator access to **6.1.4 / API 25 and its exact loaded host-JAR hash**. The older **`0.1.2`** release retains its 6.1.3 gate and lacks the stop/seek and track-selection fixes. Upgrade Python and the extension as a matched pair; discover the running extension's capabilities and build origin, not just its version label. Respect mismatches and use authorized GUI guidance only for a known unsupported surface, never to replay an uncertain write. See [Grid boundaries](grid-and-recipes.md) and the release-matched public [verification record](https://github.com/BANANASJIM/bitwig-agent/blob/main/VERIFICATION.md).

## Changes that affect production guidance

| Release | Important changes | Practical consequence for the agent |
| --- | --- | --- |
| 6.0 | Clip aliases; revised automation editing and automation clips; project key/scale and Snap to Key; changed editor tool shortcuts | Check independent versus shared clip content before A/B. Inspect Follow/Lock and automation ownership. Do not apply old tool-key instructions without checking the actual editor. |
| 6.1 | Expanded Sampler slicing, granular/spectral modes and related controls; new Tuner; Grid quantizer renames | Discover the selected Sampler mode before giving parameter advice. Recheck migrated modulation mappings. Prefer current visible module/control names rather than old screenshots. |
| 6.1.1–6.1.3 | Sampler/automation fixes, memory improvements, and a Linux X11 knob/slider jump fix | Establish the exact build when diagnosing these symptoms; release notes identify known fixes, not the cause of every similar failure. |
| 6.1.4 | Further maintenance fixes and rekordbox integration on Windows/macOS | Do not promise rekordbox integration on Linux. A host-package update alone does not adapt the installed bridge's private bindings. |

For the actual workflow, load [production](production-workflows.md) or [sound design](sound-design.md), not every release note. Keep the official notes available for exact mode names, removed mappings and platform qualifications.

## Source order

- **Actual current UI and installed schemas:** authoritative for the available target, control label, units, mode and tool arguments. They do not alone establish intent, safety or audio quality.
- **Version-specific official release notes:** authoritative for changes to the older manual. State the applicable version/platform when advising a changed workflow.
- **Official user guide:** underlying concepts, signal flow and general interactions; check its displayed version.
- **Repository protocol and verification:** bridge contracts and exercised coverage, preferably from the installed release tag.
- **Tutorials/community examples:** supplementary hypotheses, not authority for an exact current parameter, native binding or user's project state.

## Official entry points

- [Release archive and current stable version](https://www.bitwig.com/previous_releases/)
- [6.1.4 release notes, including preceding 6.1 maintenance changes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1.4/release_notes/)
- [6.1 feature release notes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1/release_notes)
- [6.0 feature release notes](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html)
- [Official user guide and chapter index](https://www.bitwig.com/userguide/latest/)

Use stable public links, not signed download redirects, local host paths or account/session URLs. These references summarize the relevant concepts; they do not redistribute the proprietary manual, host JAR, factory library or licensed project media.
