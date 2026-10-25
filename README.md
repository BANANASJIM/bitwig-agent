# Bitwig Agent

An experimental Bitwig Controller Extension with Python MCP and CLI tools. Agents can inspect and control tracks, devices and MIDI clips, and author **The Grid** patches through structured tools. Your agent owns planning; this bridge provides bounded controls and observed results, not automatic GUI fallback.

**Tested compatibility:** native evidence is limited to **Linux, Bitwig Studio 6.1.3 / Controller API 25**. macOS, Windows and other Bitwig versions are unverified. Private Grid/modulator access additionally requires the exact mapped host-JAR SHA-256 and fails closed on a mismatch; do not bypass that gate. See [verification and coverage limits](VERIFICATION.md).

**Use a disposable project and keep backups.** The extension listens on unauthenticated `127.0.0.1:61172`: any local process able to connect can control Bitwig. Use a trusted local machine; never expose or forward this port to a network. Never blindly retry an uncertain mutation: a timeout may mean the edit happened. [Targeting and completion safety](USAGE.md#target-safely-then-observe-completion) applies to every write.

## Quickstart

You need your own Bitwig Studio installation, Python 3.11+ and [uv](https://docs.astral.sh/uv/getting-started/installation/). The released wheel and extension need **no Git checkout or JDK**. The commands below use GitHub release assets, not a PyPI package.

### 1. Install

```sh
uv tool install "https://github.com/BANANASJIM/bitwig-agent/releases/download/v0.1.1/bitwig_agent-0.1.1-py3-none-any.whl"
bitwig-agent-install
```

The installer downloads the same-version `BitwigAgent.bwextension` and `SHA256SUMS`, verifies the extension checksum, and installs it in your Bitwig Extensions directory. Different existing bytes are backed up outside Bitwig's extension scan; identical bytes are left untouched. [Custom locations, offline installation and troubleshooting](INSTALL.md).

Prefer this persistent install for an MCP client. [Installation alternatives](INSTALL.md#persistent-uv-tools-and-ephemeral-uvx) cover ephemeral `uvx`, offline assets and the local Arch package recipe. PyPI/AUR publication is separate from GitHub releases; the short index-based commands are not the current installation path.

### 2. Enable in Bitwig

Open **Settings → Controllers → Add controller** and choose vendor **Bitwig Agent**, model **Local API bridge** (controller name **Bitwig Agent**). No MIDI ports are needed. Enable it, open a disposable project, then check the connection:

```sh
bitwig-agent-rpc status
```

A successful status response from the running extension establishes connectivity—not successful editing, audio output or persistence. Connection failures report `outcome: not_dispatched` until the endpoint is available. If the controller is absent, check the install destination and restart Bitwig after installing/replacing the extension.

### 3. Connect your MCP client

```sh
bitwig-agent-install --mcp-config
```

Copy the printed JSON into your client's MCP server configuration, following that client's instructions, then reload/restart its MCP connection. This command **only prints configuration**; it does not install/download anything or edit your client settings. The generated configuration launches the installed Python environment by absolute path, avoiding GUI applications' often different `PATH`. The adapter uses **stdio**; the client starts it and connects it to the local extension.

[examples/mcp-config.json](examples/mcp-config.json) is a minimal static alternative using the installed `bitwig-agent` command. Prefer generated configuration, especially for GUI clients. See [connection troubleshooting](INSTALL.md#troubleshooting).

## Useful checks

```sh
bitwig-agent-install --version
bitwig-agent-rpc list
bitwig-agent-rpc describe notes.edit
bitwig-agent-rpc tracks.set --help
bitwig-agent-rpc status
```

Version, catalog, schema and help commands work **offline**; they do not demonstrate a Bitwig connection. Only the last command above contacts the extension. Running `bitwig-agent` manually starts a stdio server waiting for an MCP client, not a graphical application or a connection check.

## Documentation

- [Documentation index](docs/README.md): choose an installation, operation, architecture or packaging guide.
- [Installation](INSTALL.md): persistent uv tools, ephemeral uvx, local Arch package, offline/custom install and troubleshooting.
- [Usage and safety](USAGE.md): control bounds, targeting, unknown outcomes and the explicit Grid music recipe.
- [Architecture](docs/architecture.md): Python/Java interfaces, state ownership and why the bridge does not plan.
- [Protocol](PROTOCOL.md): request/response schemas, limits and side effects.
- [Verification](VERIFICATION.md): reproducible checks and unresolved native coverage; host-free checks are not native acceptance.
- [Contributing](CONTRIBUTING.md) and [packaging](docs/packaging.md): source setup, artifacts and release promotion.
- [Portable agent skill](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent): copyable Agent Skills instructions; not permission to operate your DAW.
- [Security](SECURITY.md): local trust boundary and private vulnerability reporting.

## License

Project code is [MIT licensed](LICENSE). Gson is Apache-2.0; see [third-party notices](THIRD_PARTY_NOTICE.md) and [its license](third-party/GSON-LICENSE.txt). Bitwig Studio and its proprietary host JAR are separate dependencies and are not bundled.
