# Setup and format validation

This skill is portable instructions, not an installer or a permission grant. Copy the whole `bitwig-agent` folder (`SKILL.md`, `references/`, `LICENSE`) into the skill location your client supports. Client skill discovery and MCP configuration are separate tasks; change global or client settings only with explicit permission.

## Missing tools or extension

The public [installation guide](https://github.com/BANANASJIM/bitwig-agent/blob/main/INSTALL.md) has current commands and release assets. Do not copy versions or interpreter paths from this skill.

- The Python package and the enabled Java extension must match as a pair. `bitwig-agent --version` and `bitwig-agent status` (`extension.build`; the client flags a mismatch) tell you which you have. `--help` and `help` work offline and prove nothing about the host; `status` contacts the running extension.
- `bitwig-agent-install` installs the matching extension and can replace user files, so it needs installation permission. With `--mcp-config` it only prints client configuration; review and merge it yourself.
- A replaced `.bwextension` is loaded only by restarting Bitwig, not by the Settings toggle. Save every open tab or get consent before restarting.
- Starting `bitwig-agent-mcp` by hand starts a stdio server waiting for a client, not a host test.
- The Arch package puts the extension and this skill under `/usr/share/bitwig-agent/`; each user then installs the extension themselves, without sudo, with `bitwig-agent-install --extension /usr/share/bitwig-agent/BitwigAgent.bwextension`, and restarts Bitwig; check `status.extension.build` afterwards.
- Keep the unauthenticated endpoint on loopback; never forward the port. Do not upgrade the proprietary host or bypass a version gate to work around missing tools.

## Isolated native runs

Native tests can run on a headless display (`weston --backend=headless --xwayland`, `DISPLAY=:1`) so the user's desktop is untouched. Check with `ps` that no other Bitwig is running first: the extension port is shared, so the test instance may talk to the user's desktop instance, or lose the port. `status.instance` (`pid`, `parentPid`, `display`, `waylandDisplay`) says which process answered; the launcher forks the child that hosts the extension, so match on `display` or `parentPid` and stop if it is not yours. Display isolation is not audio or profile isolation.

## Format validation

The [Agent Skills specification](https://agentskills.io/specification) defines the frontmatter and layout. In a source checkout run `pixi run validate-skills`. It checks naming and frontmatter, not links, permissions, safe behavior or host compatibility. The test suite separately checks that every `bitwig-agent` command in this skill exists. Neither runs as part of a musical task.
