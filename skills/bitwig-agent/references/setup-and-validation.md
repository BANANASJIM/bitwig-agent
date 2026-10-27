# Setup and format validation

This skill is portable instructions, not an installer or permission grant. Copy the entire `bitwig-agent` directory, keeping `SKILL.md`, `references/` and `LICENSE` together, into a skill location supported by the chosen client. Do not assume a universal client-specific path. Client skill discovery and MCP configuration are separate tasks; make global/client changes only with explicit permission.

## Missing tools or extension

Use the public [installation guide](https://github.com/BANANASJIM/bitwig-agent/blob/main/INSTALL.md) for current commands and matching release assets. Persistent `uv tool install bitwig-agent` from PyPI is preferred for ongoing MCP use; `uvx --from bitwig-agent==0.2.0 <entrypoint>` is version-pinned ephemeral/cached execution. Do not treat an absolute interpreter path in a temporary uv cache as permanent client configuration.

The installed Python package and enabled Java extension must match. `bitwig-agent-install` installs/downloads the matching extension and can replace user files, so it requires installation permission. `bitwig-agent-install --mcp-config` only prints configuration; it does not edit global settings or install the extension. Review and merge that JSON according to the client's instructions. Offline `list`, `describe` and version commands are not host connectivity evidence; `status` contacts the running extension. Starting `bitwig-agent` by hand starts a stdio server awaiting a client, not a GUI or host test.

The published [Arch/AUR package](https://aur.archlinux.org/packages/bitwig-agent) places the extension under `/usr/share/bitwig-agent/BitwigAgent.bwextension` and the skill under `/usr/share/bitwig-agent/skills/bitwig-agent/`. Each user explicitly installs the extension with `bitwig-agent-install --extension /usr/share/bitwig-agent/BitwigAgent.bwextension`, without sudo, after package installation/upgrades; then reloads/restarts the controller. A system package must not write to user homes. Installing through AUR still requires permission; it does not authorize host operation or establish PyPI availability.

Native evidence remains bounded to exercised Linux / API 25 cases. Version `0.2.0` gates private access to 6.1.4; the older `0.1.2` release targets 6.1.3 instead. Both require the mapped loaded-JAR hash. Check [versions and sources](version-and-sources.md), not package-version equality alone. Do not upgrade the proprietary host or bypass those gates to resolve missing tools. Keep the unauthenticated endpoint local; no port forwarding.

## Standard format validation

The [Agent Skills specification](https://agentskills.io/specification) defines the frontmatter and directory format. To validate a source-checkout skill folder with the pinned reference implementation:

```sh
uvx --from "git+https://github.com/agentskills/agentskills.git@69ef37e9424c0a7ea9dd2293b559e43ec8176379#subdirectory=skills-ref" skills-ref validate skills/bitwig-agent
```

For a copied skill, replace the final argument with that folder's actual path. The command downloads a validation tool and needs appropriate network/tool-execution permission; do not run it automatically as part of a musical task. `skills-ref` is a demonstration validator, not a Bitwig runtime dependency.

Format validation checks naming/frontmatter conventions, **not** link resolution, user permission, safe model behavior, host compatibility, native editing, audio or saved persistence. Separately inspect the standalone folder's local references and license. External documentation uses public URLs so copying the folder does not leave checkout-relative links. Public `main` may change; use the matching release tag and installed schemas for version-specific behavior.
