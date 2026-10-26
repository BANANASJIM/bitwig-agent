# Bitwig Agent

[![CI](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/BANANASJIM/bitwig-agent/actions/workflows/ci.yml)
[![GitHub release](https://img.shields.io/github/v/release/BANANASJIM/bitwig-agent?include_prereleases)](https://github.com/BANANASJIM/bitwig-agent/releases)
[![AUR version](https://img.shields.io/aur/version/bitwig-agent)](https://aur.archlinux.org/packages/bitwig-agent)
[![MIT license](https://img.shields.io/github/license/BANANASJIM/bitwig-agent)](LICENSE)

**Control Bitwig Studio from an MCP client or the command line.** Inspect tracks and devices, edit MIDI clips, and author **The Grid** patches through structured tools. Your agent owns planning; this thin bridge provides bounded controls and observed results, not an autonomous music workflow or automatic GUI fallback.

[Quickstart](#quickstart) · [Documentation](docs/README.md) · [Agent skill](skills/bitwig-agent) · [Contributing](CONTRIBUTING.md)

> [!WARNING]
> Experimental. Native evidence is limited to **Linux / Bitwig Studio 6.1.3 / Controller API 25**; other versions and platforms are unverified. Use a disposable project and keep backups. Read the [safety boundaries](#safety-and-compatibility) before enabling the controller.

## Quickstart

You need your own compatible Bitwig Studio installation. The released extension needs **no JDK**. Choose **one** installation route below; do not leave an older uv-installed command shadowing an Arch installation on `PATH`.

### 1. Install

#### uv

Use [uv](https://docs.astral.sh/uv/getting-started/installation/) with Python 3.11+:

```sh
uv tool install \
  "https://github.com/BANANASJIM/bitwig-agent/releases/download/v0.1.1/bitwig_agent-0.1.1-py3-none-any.whl"
bitwig-agent-install
```

This installs the Python tools, then downloads and verifies the matching extension before copying it into your Bitwig Extensions directory. Changed existing bytes are backed up; identical bytes are left untouched.

**PyPI publication is still pending:** use the versioned GitHub wheel, not `uv tool install bitwig-agent` yet. See [uvx, upgrades and offline installation](INSTALL.md).

#### Arch Linux / AUR

The [AUR package](https://aur.archlinux.org/packages/bitwig-agent) is available as **`bitwig-agent`**. Review its recipe when prompted:

```sh
yay -S bitwig-agent
bitwig-agent-install --extension /usr/share/bitwig-agent/BitwigAgent.bwextension
```

Run the extension-copy command **without sudo**, as your Bitwig user. The system package never writes into user homes. [Install without an AUR helper](INSTALL.md#arch-linux-aur).

### 2. Enable in Bitwig

Open **Settings → Controllers → Add controller**:

- **Vendor:** Bitwig Agent
- **Model:** Local API bridge
- **Controller name:** Bitwig Agent
- **MIDI ports:** none required

Enable it, open a disposable project, then check the connection:

```sh
bitwig-agent-rpc status
```

A successful response proves connectivity, not editing, sound or persistence. If the controller is missing, check the extension destination and restart Bitwig. See [troubleshooting](INSTALL.md#troubleshooting).

### 3. Connect your MCP client

```sh
bitwig-agent-install --mcp-config
```

Copy the printed JSON into your client's MCP configuration and reload its connection. This command **only prints configuration**; it does not modify client settings. It uses an absolute Python path so GUI clients need not inherit your shell's `PATH`.

The client starts the **stdio** server. Running `bitwig-agent` manually just waits for an MCP client; it is not a GUI or a host connectivity check.

## Explore the tools

These commands work offline:

```sh
bitwig-agent-install --version
bitwig-agent-rpc list
bitwig-agent-rpc describe notes.edit
bitwig-agent-rpc tracks.set --help
```

For an agent, copy the complete [portable skill folder](skills/bitwig-agent), including its references and license, into your client's supported skill location. A skill is guidance, **not permission to operate the DAW**.

## Safety and compatibility

- **Local trust only.** The extension listens on unauthenticated `127.0.0.1:61172`. Any local process able to connect can control Bitwig. Never expose or forward it to a network.
- **Do not bypass compatibility gates.** Private Grid/modulator access requires the exact mapped host-JAR SHA-256, in addition to the tested host version.
- **Never blindly retry an uncertain edit.** A timeout may mean the mutation happened. Discover fresh targets, inspect the outcome and reconcile uncertainty before another write.
- **Package checks are not native acceptance.** Model readback, GUI state, audio, Undo and save/reopen are separate evidence. See [targeting safety](USAGE.md#target-safely-then-observe-completion) and [coverage limits](VERIFICATION.md).

## Documentation

| Need | Guide |
|---|---|
| Installation, upgrades or connection help | [Installation](INSTALL.md) |
| Operations, targeting and Grid recipes | [Usage](USAGE.md) · [Protocol](PROTOCOL.md) |
| How the Python and Java bridge works | [Architecture](docs/architecture.md) |
| Reusable agent instructions | [Agent skill](skills/bitwig-agent) |
| Tests and evidence boundaries | [Verification](VERIFICATION.md) |
| Development and releases | [Contributing](CONTRIBUTING.md) · [Packaging](docs/packaging.md) |
| Private vulnerability reports | [Security](SECURITY.md) |

[Full documentation index](docs/README.md)

## License

Project code is [MIT licensed](LICENSE). Gson is Apache-2.0; see [third-party notices](THIRD_PARTY_NOTICE.md). Bitwig Studio and its proprietary host JAR are separate dependencies and are not bundled.
