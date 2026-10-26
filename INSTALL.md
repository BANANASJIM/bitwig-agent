# Installation details

Start with the [three-step quickstart](README.md#quickstart). Choose an isolated [uv tool environment](https://docs.astral.sh/uv/guides/tools/) from [PyPI](https://pypi.org/project/bitwig-agent/), or the [Arch/AUR package](#arch-linux-aur). Both supply the same-version prebuilt controller extension without a Java compiler. Native compatibility evidence remains limited to Linux with Bitwig Studio 6.1.3 / API 25; platform-specific installer defaults do not establish macOS or Windows host support.

Bitwig documents its platform extension directories and manual controller registration in its [official controller-extension installation guide](https://www.bitwig.com/support/technical_support/how-do-i-add-a-controller-extension-or-script-17/).

## Persistent uv tools and ephemeral uvx

`uv tool install` creates a persistent isolated environment and exposes the package's entrypoints on your tool `PATH`. Use it for normal MCP configuration and repeated CLI use:

```sh
uv tool install bitwig-agent
bitwig-agent-install
bitwig-agent-install --mcp-config
```

The first installer command changes your user extension directory; run it only when you intend to install/replace that extension. The second only prints MCP configuration. Neither starts Bitwig or enables the controller.

`uvx` (`uv tool run`) instead runs a command in a cached, disposable tool environment without a persistent tool installation. It is useful for a one-off **offline** discovery command:

```sh
uvx --from bitwig-agent==0.1.2 bitwig-agent-rpc list
```

Here `--from` identifies the distribution; `bitwig-agent-rpc` identifies the entrypoint. Do not assume a generated absolute interpreter path inside an ephemeral uv cache is durable MCP configuration. Prefer the persistent installation above, or deliberately configure your client to invoke `uvx --from bitwig-agent==0.1.2 bitwig-agent` itself, with uv on that client's `PATH`. That option may need downloads and makes cache availability part of startup.

For a reproducible installation, pin a reviewed version with `uv tool install bitwig-agent==0.1.2`. The Python tools come from [this PyPI project](https://pypi.org/project/bitwig-agent/); the matching controller extension is downloaded separately from GitHub by the installer.

## Arch Linux (AUR)

[`bitwig-agent`](https://aur.archlinux.org/packages/bitwig-agent) is published on AUR and maintained by `bananasjim`. Review its `PKGBUILD` and `.SRCINFO` before building. With an AUR helper:

```sh
yay -S bitwig-agent
```

Without a helper, install Arch's `base-devel` prerequisites, then build as a regular user:

```sh
git clone https://aur.archlinux.org/bitwig-agent.git
cd bitwig-agent
makepkg -si
```

This builds the Python component from release source and packages the matching prebuilt Java extension. Runtime dependencies come from pacman; there is no runtime uv/private-venv requirement. `makepkg` may request privilege for dependency/package installation through pacman; **never run makepkg itself as root**.

Maintainers can also build the mirrored recipe in the source repository's [`packaging/aur`](https://github.com/BANANASJIM/bitwig-agent/tree/main/packaging/aur) directory. The published AUR repository contains only the recipe, generated metadata and license.

Public guides are installed under `/usr/share/doc/bitwig-agent/` and the portable Agent Skills folder under `/usr/share/bitwig-agent/skills/bitwig-agent/`. Copy the whole skill directory to your client's chosen skill location; installing the package does not register a skill or change client settings.

The package places the extension at `/usr/share/bitwig-agent/BitwigAgent.bwextension`. Package installation does not enable it or write into any user's home directory. As the user who runs Bitwig, explicitly opt in:

```sh
bitwig-agent-install --extension /usr/share/bitwig-agent/BitwigAgent.bwextension
bitwig-agent-install --mcp-config
```

Do not run this user-extension installation with `sudo`. Repeat the explicit copy after upgrading the system package, then reload/restart the controller; the system asset and already-copied user extension are separate files. Avoid leaving a uv-installed command ahead of the Arch package on `PATH`; choose one Python installation and regenerate MCP configuration from it.

Bitwig is an optional host dependency, not a forced host upgrade. The AUR `bitwig-studio` recipe's 6.1.4 version does **not** establish compatibility: native evidence here remains Linux / 6.1.3 / API 25 and exact-JAR-gated private access. Keep your licensed, compatible host under your control. See [package design and publication boundaries](docs/packaging.md).

## Release artifacts and integrity

The [v0.1.2 release](https://github.com/BANANASJIM/bitwig-agent/releases/tag/v0.1.2) supplies:

- `bitwig_agent-0.1.2-py3-none-any.whl`: Python MCP server, diagnostic CLI and installer.
- `bitwig_agent-0.1.2.tar.gz`: Python source distribution.
- `BitwigAgent.bwextension`: prebuilt Bitwig controller extension.
- `SHA256SUMS`: SHA-256 hashes for all three artifacts.

`bitwig-agent-install` fetches the extension and checksum manifest from the installed Python package's versioned release, checks the extension hash before replacing anything, and installs atomically. A checksum checks integrity against that release manifest; it is not an independent signature or a native-compatibility test. Do not mix Python and extension versions.

If you prefer to download the assets manually, verify their hashes against `SHA256SUMS` using your platform's SHA-256 utility before installation. On Linux, from the directory containing all four assets:

```sh
sha256sum --check SHA256SUMS
uv tool install ./bitwig_agent-0.1.2-py3-none-any.whl
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

For a PyPI installation, review the new release's compatibility before upgrading both components:

```sh
uv tool upgrade bitwig-agent
bitwig-agent-install
```

`uv tool upgrade` respects an existing version pin; change that constraint with `uv tool install bitwig-agent==0.1.2` when selecting that release. To switch an older GitHub-wheel installation to PyPI, use `uv tool install --force bitwig-agent`, then run `bitwig-agent-install`. For intentional direct-wheel installations, use `uv tool install --force <new-wheel-URL>` instead.

Do not upgrade only the Python half. A restore similarly requires both matching versions; an extension backup alone is not a full rollback. Regenerate MCP configuration after replacing or moving the environment, then reload the controller.

## Troubleshooting

### `uv` or a tool command is not found

Follow the official [uv installation instructions](https://docs.astral.sh/uv/getting-started/installation/), then open a new shell. If uv is available but `bitwig-agent-install` or `bitwig-agent-rpc` is not:

```sh
uv tool update-shell
```

Restart the shell afterward. See [uv's tool PATH guidance](https://docs.astral.sh/uv/guides/tools/).

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
