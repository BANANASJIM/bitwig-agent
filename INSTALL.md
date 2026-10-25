# Installation details

Start with the [three-step quickstart](README.md#quickstart). Release installation uses an isolated [uv tool environment](https://docs.astral.sh/uv/guides/tools/), a versioned wheel and a prebuilt controller extension. There is no dependency on a checkout or a Java compiler. Native compatibility is established only for Linux with Bitwig Studio 6.1.3 / API 25; platform-specific installer defaults do not establish macOS or Windows host support.

Bitwig documents its platform extension directories and manual controller registration in its [official controller-extension installation guide](https://www.bitwig.com/support/technical_support/how-do-i-add-a-controller-extension-or-script-17/).

## Release artifacts and integrity

The [v0.1.0 release](https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.1.0) supplies:

- `bitwig_agent-0.1.0-py3-none-any.whl`: Python MCP server, diagnostic CLI and installer.
- `BitwigAgent.bwextension`: prebuilt Bitwig controller extension.
- `SHA256SUMS`: SHA-256 hashes for the wheel and extension.

`bitwig-agent-install` fetches the extension and checksum manifest from the installed Python package's versioned release, checks the extension hash before replacing anything, and installs atomically. A checksum checks integrity against that release manifest; it is not an independent signature or a native-compatibility test. Do not mix Python and extension versions.

If you prefer to download the assets manually, verify both hashes against `SHA256SUMS` using your platform's SHA-256 utility before installation. On Linux, from the directory containing all three assets:

```sh
sha256sum --check SHA256SUMS
uv tool install ./bitwig_agent-0.1.0-py3-none-any.whl
bitwig-agent-install --extension ./BitwigAgent.bwextension
```

`--extension FILE` copies a local artifact without a release download; its provenance and checksum verification are your responsibility. Installing the wheel on a fully offline machine also requires its Python dependencies already available locally; the wheel alone is not an offline dependency bundle.

## Custom location and existing installations

Use the installer destination reported for your platform and compare it with Bitwig's configured extension location. Linux defaults to `~/Bitwig Studio/Extensions/`. To use another directory:

```sh
bitwig-agent-install --destination "/absolute/path/to/Extensions"
```

Both options may be combined:

```sh
bitwig-agent-install --extension ./BitwigAgent.bwextension --destination "/absolute/path/to/Extensions"
```

A different existing extension is backed up to `DESTINATION_PARENT/.bitwig-agent-backups/OLD_SHA256/BitwigAgent.bwextension`, outside Bitwig's extension scan directory. Installing identical bytes preserves the existing file. After replacing a loaded extension, restart Bitwig or reload its controller before expecting the new code to run. Keep the Python package and extension matched when upgrading or restoring a backup.

## Troubleshooting

### `uv` or a tool command is not found

Follow the official [uv installation instructions](https://docs.astral.sh/uv/getting-started/installation/), then open a new shell. If uv is available but `bitwig-agent-install` or `bitwig-agent-rpc` is not:

```sh
uv tool update-shell
```

Restart the shell afterward. See [uv's tool PATH guidance](https://docs.astral.sh/uv/guides/tools/). Use the explicit GitHub wheel URL from the quickstart; `uv tool install bitwig-agent` would ask a package index instead and is not this installation path.

### Terminal works, GUI MCP client does not

Generate configuration from the installed environment:

```sh
bitwig-agent-install --mcp-config
```

Use that JSON rather than a bare executable name: GUI clients may not inherit the shell's tool `PATH`. The generated command is an absolute Python interpreter path with `-m bitwig_agent`; keep the virtual-environment path intact rather than replacing it with a resolved system-Python symlink. If you move/reinstall the tool environment, regenerate the configuration. The static [example](examples/mcp-config.json) uses `bitwig-agent` and therefore needs that executable on the client's `PATH` (or an absolute executable path substituted). No configuration is installed automatically. Do not replace an existing client configuration wholesale if it contains other servers.

### Controller missing or connection refused

1. Confirm the extension is installed in the directory Bitwig scans, then restart Bitwig if necessary.
2. Add/enable vendor **Bitwig Agent**, model **Local API bridge** under Settings → Controllers. No MIDI ports are needed.
3. Run `bitwig-agent-rpc status`. Unlike `list`, `describe` and `--help`, this contacts the host.
4. Use the local endpoint `127.0.0.1:61172`; the generated MCP configuration sets `BITWIG_AGENT_PORT=61172`. Changing a client's port does not reconfigure the extension.

A stdio adapter starting successfully is not proof that the extension is reachable. An observed status response is connection evidence, not full tool acceptance. Private Grid operations may still be unavailable because their separate version/hash gates fail closed. Do not bypass those gates or expose the unauthenticated endpoint to a network. See [usage safety](USAGE.md) and [verification limits](VERIFICATION.md).

For source builds and local development, use [CONTRIBUTING.md](CONTRIBUTING.md), not the release quickstart.
