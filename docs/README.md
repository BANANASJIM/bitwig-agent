# Public documentation

These guides describe the public implementation and supported user workflows. They are not a native acceptance certificate or permission to operate a DAW. The current protocol and installed tool schemas define request shapes; actual observations define what a particular host did.

## Choose a guide

| Goal | Guide |
|---|---|
| Install the released Python tools and matching extension | [Quickstart](../README.md#quickstart), then [installation details](../INSTALL.md) |
| Choose persistent uv, ephemeral uvx or Arch/AUR installation | [uv alternatives](../INSTALL.md#persistent-uv-tools-and-ephemeral-uvx) · [Arch/AUR](../INSTALL.md#arch-linux-aur) |
| Connect an MCP client without changing global settings automatically | [MCP connection](../README.md#3-connect-your-mcp-client) |
| Operate tracks/devices/MIDI/Grid safely | [Usage and safety](../USAGE.md) |
| Give an agent portable, focused operating instructions | [Agent skill](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent) |
| Understand interfaces, state ownership and failure boundaries | [Architecture](architecture.md) |
| Look up exact schemas, bounds and side effects | [Protocol](../PROTOCOL.md) |
| Understand package contents, AUR maintenance and PyPI promotion | [Packaging and releases](packaging.md) |
| Build from source or contribute | [Contributing](../CONTRIBUTING.md) |
| Run checks or interpret native coverage | [Verification](../VERIFICATION.md) |
| Report a vulnerability privately | [Security](../SECURITY.md) |

## Reading claims correctly

- A tool appearing in discovery means the interface exists, not that every host/version supports it.
- A successful package install or MCP handshake does not establish a Bitwig connection.
- A host response may establish dispatch or bounded model readback, not audio, GUI completion or persistence.
- Native evidence is limited to Linux / Bitwig Studio 6.1.3 / API 25. Exact loaded-JAR gates remain mandatory for private Grid/modulator access.
- GitHub release availability, AUR publication and PyPI publication are separate facts. Use the explicit installation source documented for the release.

The architecture guide links implementation ownership rather than reproducing every schema. The packaging guide separates end-user installation from maintainer release/account operations. Native voice-count, recipe and bounce caveats remain in [usage](../USAGE.md#make-grid-instruments-without-preset-files) and [verification](../VERIFICATION.md#coverage-limits-and-unresolved-results), where they can be read in context.
