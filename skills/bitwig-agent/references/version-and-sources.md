# Versions and official sources

Keep four versions apart: the running Bitwig and platform (observe the application, not a package on disk), the product documentation, the installed bridge (CLI, extension: `bitwig-agent --version`, `bitwig-agent status`), and what has actually been tested. A newer repository `main` is not the installed release, and a successful operation proves only its own scope. Do not upgrade the host or the bridge just because this page is newer.

## Changes that affect production guidance

Checked 2026-11-26: the [release archive](https://www.bitwig.com/previous_releases/) lists Bitwig Studio 6.1.7 (2026-11-05) as the newest release, after 6.1.4 (2026-10-13), 6.1.5 and 6.1.6. The bridge supports 6.1.4 and 6.1.7 (API 25), each with the exact loaded-JAR gate; 6.1.5 and 6.1.6 are not supported, and the [6.1.7 release notes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1.7/release_notes/) mention no Controller API version change. Recheck the archive before answering a later "latest version" question. The online user guide still identifies as 5.3, so it supplies concepts while newer release notes override changed controls and names.

| Release | Changes | Consequence |
| --- | --- | --- |
| 6.0 | Clip aliases; revised automation editing and automation clips; project key and scale with Snap to Key; new editor tool shortcuts | Check shared versus independent clip content before an A/B. Check Follow and Lock and who controls an automated parameter. Do not apply old tool keys without looking at the editor. |
| 6.1 | Sampler slicing, granular and spectral modes; Tuner; Grid quantizer renames | Find the Sampler's mode before parameter advice. Recheck migrated modulation mappings and use current module names. |
| 6.1.1 to 6.1.3 | Sampler and automation fixes, memory improvements, a Linux X11 knob and slider jump fix | Establish the exact build for these symptoms; notes list known fixes, not every cause. |
| 6.1.4 | Maintenance fixes; rekordbox integration on Windows and macOS | No rekordbox on Linux. A host update alone does not adapt the bridge's private bindings. |
| 6.1.5 to 6.1.7 | Plug-in, Sampler, modulator and PipeWire/Wayland fixes; macOS multitouch (not validated by this bridge) | 6.1.7 is supported with its own exact-JAR gate; on 6.1.5 and 6.1.6 private Grid/modulator access stays unavailable. |

Private Grid and modulator access is gated to an exact host version and loaded JAR. A mismatch means the capability is unavailable, so do not bypass it: guide the user through the GUI for a known gap. Upgrade the Python package and extension together.

## Source order

1. The actual UI and the installed `--help` output decide what target, control, unit and arguments exist. They do not decide intent, safety or audio quality.
2. Version-specific release notes override the older manual for changed workflows; state the version and platform.
3. The official user guide gives concepts and signal flow; check its displayed version.
4. The repository's PROTOCOL.md and VERIFICATION.md describe bridge contracts and tested coverage; prefer the installed release's tag.
5. Tutorials and community examples are hypotheses, not authority on a parameter, a binding or the user's project.

## Entry points

- [Release archive](https://www.bitwig.com/previous_releases/)
- [6.1.4 release notes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1.4/release_notes/)
- [6.1 release notes](https://www.bitwig.com/dl/Bitwig%20Studio/6.1/release_notes)
- [6.0 release notes](https://downloads.bitwig.com/6.0/Release-Notes-6.0.html)
- [User guide](https://www.bitwig.com/userguide/latest/)

Use stable public links, not signed download redirects, local paths or account URLs.
