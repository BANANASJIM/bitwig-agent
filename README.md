# Bitwig Agent

[![CI](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/BANANASJIM/bitwig-agent?include_prereleases)](https://github.com/BANANASJIM/bitwig-agent/releases)
[![PyPI version](https://img.shields.io/pypi/v/bitwig-agent)](https://pypi.org/project/bitwig-agent/)
[![AUR version](https://img.shields.io/aur/version/bitwig-agent)](https://aur.archlinux.org/packages/bitwig-agent)
[![Bitwig 6.1.4 | 6.1.7](https://img.shields.io/badge/Bitwig-6.1.4%20%7C%206.1.7-orange)](#supported-bitwig-versions)
[![MIT license](https://img.shields.io/github/license/BANANASJIM/bitwig-agent)](https://github.com/BANANASJIM/bitwig-agent/blob/main/LICENSE)

**Control Bitwig Studio from an MCP client or the command line.** Inspect tracks and devices, edit MIDI clips, and author **The Grid** patches through structured tools. Your agent owns planning; this thin bridge provides bounded controls and observed results, not an autonomous music workflow or automatic GUI fallback.

[Quickstart](#quickstart) · [Documentation](https://github.com/BANANASJIM/bitwig-agent/blob/main/docs/README.md) · [Agent skill](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent) · [Contributing](https://github.com/BANANASJIM/bitwig-agent/blob/main/CONTRIBUTING.md)

> **Experimental — use a disposable project and keep backups.**
> **`0.5.0` supports Bitwig 6.1.4 and 6.1.7 for private Grid/modulator access**, each with an exact loaded host-JAR check; other versions fail closed. Older releases keep their own boundary ([supported versions](#supported-bitwig-versions)). `0.5.0` speaks protocol 0.5 with no 0.4 compatibility: read the [migration guide](https://github.com/BANANASJIM/bitwig-agent/blob/main/docs/migration-0.5.md). Update Python and the extension together. Read the [bounded native evidence](https://github.com/BANANASJIM/bitwig-agent/blob/main/VERIFICATION.md) and [safety boundaries](#safety-and-compatibility).

## Quickstart

You need your own compatible Bitwig Studio installation. The released extension needs **no JDK**. Choose **one** installation route below; do not leave an older pipx-installed command shadowing an Arch installation on `PATH`.

### 1. Install

#### pipx

Use [pipx](https://pipx.pypa.io/stable/installation/) with Python 3.11+:

```sh
pipx install bitwig-agent
bitwig-agent-install
```

This installs the Python tools, then downloads and verifies the matching extension before copying it into your Bitwig Extensions directory. Changed existing bytes are backed up; identical bytes are left untouched.

See [one-off runs, upgrades and offline installation](https://github.com/BANANASJIM/bitwig-agent/blob/main/INSTALL.md).

#### Arch Linux / AUR

The [AUR package](https://aur.archlinux.org/packages/bitwig-agent) is available as **`bitwig-agent`**. Review its recipe when prompted:

```sh
yay -S bitwig-agent
bitwig-agent-install --extension /usr/share/bitwig-agent/BitwigAgent.bwextension
```

Run the extension-copy command **without sudo**, as your Bitwig user. The system package never writes into user homes. [Install without an AUR helper](https://github.com/BANANASJIM/bitwig-agent/blob/main/INSTALL.md#arch-linux-aur).

### 2. Enable in Bitwig

Open **Settings → Controllers → Add controller**:

- **Vendor:** Bitwig Agent
- **Model:** Local API bridge
- **Controller name:** Bitwig Agent
- **MIDI ports:** none required

Enable it, open a disposable project, then check the connection:

```sh
bitwig-agent status
```

A successful response proves connectivity, not editing, sound or persistence. If the controller is missing, check the extension destination and restart Bitwig. See [troubleshooting](https://github.com/BANANASJIM/bitwig-agent/blob/main/INSTALL.md#troubleshooting).

### 3. Connect your MCP client

```sh
bitwig-agent-install --mcp-config
```

Copy the printed JSON into your client's MCP configuration and reload its connection. This command **only prints configuration**; it does not modify client settings. It uses an absolute Python path so GUI clients need not inherit your shell's `PATH`.

The client starts the **stdio** server. Running `bitwig-agent-mcp` manually just waits for an MCP client; it is not a GUI or a host connectivity check.

## Explore the tools

These commands work offline:

```sh
bitwig-agent-install --version
bitwig-agent --help
bitwig-agent tracks set --help
bitwig-agent help notes.set
```

`bitwig-agent <resource> <verb> --field=value` is the shell front end: JSON on stdout, errors on stderr, stable exit codes, so it composes with `jq`, `cut` and `wc -l`. See [USAGE](USAGE.md#the-command-line).

For an agent, copy the complete [portable skill folder](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent), including its references and license, into your client's supported skill location. It covers production workflows, troubleshooting, screenshot-guided next steps and **preserve-A / compare-B creative edits**. Live screenshots and GUI input need your client's desktop tools; the bridge does not control the entire interface. A skill is guidance, **not permission to operate the DAW**.

## Safety and compatibility

- **Local trust only.** The extension listens on unauthenticated `127.0.0.1:61172`. Any local process able to connect can control Bitwig. Never expose or forward it to a network.
- **Do not bypass compatibility gates.** Private Grid/modulator access requires the exact mapped host-JAR SHA-256, in addition to the tested host version.
- **Never blindly retry an uncertain edit.** A timeout may mean the mutation happened and is reported as `outcome: unknown`. Discover fresh targets, inspect, then `ack` the hold before another write; an error means nothing changed.
- **Package checks are not native acceptance.** Model readback, GUI state, audio, Undo and save/reopen are separate evidence. See [targeting safety](https://github.com/BANANASJIM/bitwig-agent/blob/main/USAGE.md#target-safely-then-read-the-outcome) and [coverage limits](https://github.com/BANANASJIM/bitwig-agent/blob/main/VERIFICATION.md).

## Supported Bitwig versions

| bitwig-agent | Bitwig Studio |
|---|---|
| 0.5.0 | 6.1.4, 6.1.7 |
| 0.2.0 – 0.4.0 | 6.1.4 |
| 0.1.0 – 0.1.2 | 6.1.3 |

Each release gates private access to the exact host JARs it maps; `bitwig-agent status` lists the versions as `supportedBitwig`.

## Documentation

| Need | Guide |
|---|---|
| Installation, upgrades or connection help | [Installation](https://github.com/BANANASJIM/bitwig-agent/blob/main/INSTALL.md) |
| Operations, targeting and Grid recipes | [Usage](https://github.com/BANANASJIM/bitwig-agent/blob/main/USAGE.md) · [Protocol](https://github.com/BANANASJIM/bitwig-agent/blob/main/PROTOCOL.md) |
| How the Python and Java bridge works | [Architecture](https://github.com/BANANASJIM/bitwig-agent/blob/main/docs/architecture.md) |
| Upgrading from 0.4 (renamed methods, fields, entry points) | [Migration to 0.5](https://github.com/BANANASJIM/bitwig-agent/blob/main/docs/migration-0.5.md) |
| Production guidance, screenshots and A/B workflow | [Agent skill](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent) |
| Tests and evidence boundaries | [Verification](https://github.com/BANANASJIM/bitwig-agent/blob/main/VERIFICATION.md) |
| Development and releases | [Contributing](https://github.com/BANANASJIM/bitwig-agent/blob/main/CONTRIBUTING.md) · [Packaging](https://github.com/BANANASJIM/bitwig-agent/blob/main/docs/packaging.md) |
| Private vulnerability reports | [Security](https://github.com/BANANASJIM/bitwig-agent/blob/main/SECURITY.md) |

[Full documentation index](https://github.com/BANANASJIM/bitwig-agent/blob/main/docs/README.md)

## Contributing

Issues and pull requests are welcome. Report bugs, missing workflows or Bitwig versions you need in [Issues](https://github.com/BANANASJIM/bitwig-agent/issues); see [Contributing](https://github.com/BANANASJIM/bitwig-agent/blob/main/CONTRIBUTING.md) for checks to run before opening a PR.

## License

Project code is [MIT licensed](https://github.com/BANANASJIM/bitwig-agent/blob/main/LICENSE). Gson is Apache-2.0; see [third-party notices](https://github.com/BANANASJIM/bitwig-agent/blob/main/THIRD_PARTY_NOTICE.md). Bitwig Studio and its proprietary host JAR are separate dependencies and are not bundled.
