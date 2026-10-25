# Contributing

Keep changes focused on helping agents complete real Bitwig tasks. Domain operations belong in their existing modules; transport, validation and native bindings remain separate. Do not add automatic retries or GUI fallback for uncertain writes.

## Source setup

End users should use the [released wheel and extension](README.md#quickstart). Development requires a checkout, Python 3.11+, [uv](https://docs.astral.sh/uv/getting-started/installation/), **JDK 25+**, and your own licensed Bitwig installation with its API 25 host JAR. The Bitwig 6.1.3 loader superclass uses class-file version 69, so JDK 25 is needed to compile/run Java checks; generated extension classes still target Java 11.

From the repository root:

```sh
uv sync --locked
export BITWIG_API_JAR=/path/to/installed/bitwig.jar
export JAVAC=/path/to/jdk25/bin/javac
export JAVA=/path/to/jdk25/bin/java
uv run --locked python scripts/build.py
uv run --locked bitwig-agent-install --extension build/BitwigAgent.bwextension
```

The API JAR defaults to `/opt/bitwig-studio/bin/bitwig.jar`; override it for your installation. The build downloads and verifies pinned Gson on first use. Neither the proprietary host JAR nor JDK is bundled. Installation does not enable the controller: follow [the enable/connect steps](README.md#2-enable-in-bitwig). For a checkout-based MCP configuration, run `uv run --locked bitwig-agent-install --mcp-config`; keep the checkout environment available to the client.

## Development checks

Use Python 3.11+ and uv. Install optional audio-analysis dependencies so the offline suite exercises its PCM checks rather than skipping them:

```sh
uv sync --locked --group native-music
uv run --locked python scripts/check.py --layer offline
```

For the Java build, simulated-host checks and real MCP/Java fixture, install JDK 25+ and supply your own compatible Bitwig API JAR:

```sh
export BITWIG_API_JAR=/path/to/installed/bitwig.jar
export JAVAC=/path/to/jdk25/bin/javac
export JAVA=/path/to/jdk25/bin/java
uv run --locked python scripts/build.py
uv run --locked python scripts/check.py
```

The default checks do not launch Bitwig. See [VERIFICATION.md](VERIFICATION.md) for layer boundaries. CI covers host-free Python checks, Python package consumption and a reachable-history secret scan; it does not claim to build against or test a live proprietary host.

## Changes and bug reports

- Describe the observable behavior, affected methods and checks actually run. Add a deterministic regression test for a consumer-visible bug when practical.
- Keep MCP and CLI schemas consistent with Java validation and [PROTOCOL.md](PROTOCOL.md).
- Distinguish dispatch, model readback, GUI completion, audio output and persistence. Do not report an unknown write as failed-and-safe-to-repeat.
- Native work requires a disposable project and one designated writer. Never use a user's active project as an automatic test fixture.
- Review staged files before committing. Do not submit credentials, private conversations, raw machine/process snapshots, original tool-call identifiers, personal paths, Bitwig host binaries or proprietary factory assets. Use minimal synthetic reproductions and redacted diagnostics.
- Preserve third-party notices. Project contributions are distributed under the [MIT License](LICENSE); third-party material retains its own applicable license.

For security-sensitive issues, use the private route in [SECURITY.md](SECURITY.md), not a public issue containing exploit details or private data.

## Release procedure

Releases are built locally against a maintainer's licensed Bitwig JAR; public CI does not have that dependency. The commands below illustrate **0.1.0**, not evidence that a release has been verified. For a later release, update the package/extension versions and installation links together before building.

1. Use a clean release checkout with the source prerequisites above. Run the [layered checks](VERIFICATION.md), retaining the results and distinguishing host-free checks from native evidence. Record any native checks not performed; do not infer acceptance from compilation.
2. Build the wheel and extension from that same source snapshot:

   ```sh
   uv sync --locked
   uv build --wheel
   uv run --locked python scripts/build.py
   sha256sum dist/bitwig_agent-0.1.0-py3-none-any.whl build/BitwigAgent.bwextension
   ```

3. Create a UTF-8 `SHA256SUMS` file containing the two printed hashes with **bare asset filenames**, not local `dist/` or `build/` prefixes:

   ```text
   <actual wheel SHA-256>  bitwig_agent-0.1.0-py3-none-any.whl
   <actual extension SHA-256>  BitwigAgent.bwextension
   ```

4. Check packaged contents, third-party notices and staged files. **Never upload or bundle `bitwig.jar`, other proprietary host/factory assets, private evidence or machine configuration.** The extension includes Gson with its notices, not the host JAR.
5. Tag the checked source as `v0.1.0` and create its GitHub release with exactly the versioned wheel, `BitwigAgent.bwextension` and `SHA256SUMS` as assets. Include tested host/platform scope and unresolved limitations in the release notes.
6. Before announcing the release, exercise the documented wheel URL in a clean tool environment outside the checkout; verify checksum-protected extension download/installation in an isolated destination, offline CLI discovery and the generated configuration's MCP stdio handshake. A live-host status or editing check is separate and requires a designated writer and disposable project. Record actual results, not just these instructions.

The installation layout follows the user/developer separation in [Ruff installation](https://docs.astral.sh/ruff/installation/), persistent environments in [uv tools](https://docs.astral.sh/uv/guides/tools/), explicit stdio configuration in the [MCP Git server](https://github.com/modelcontextprotocol/servers/tree/main/src/git), and prebuilt-extension/compatibility guidance in [DrivenByMoss](https://www.mossgrabers.de/Software/Bitwig/Bitwig.html). These are workflow references, not bundled code or compatibility endorsements.

## Repository map and architecture

Python MCP stdio and CLI entrypoints share a strict schema/catalog and loopback client. The Java extension dispatches bounded newline-delimited JSON requests through host scheduling into separate domains. Bitwig owns the project/audio engine; the bridge is not a complete project mirror and never automatically retries requests.

| Path | Responsibility |
|---|---|
| `src/bitwig_agent/` | Schemas/catalog, transport, MCP, CLI and release installer |
| `extension/src/main/java/dev/bitwigagent/` | Host services, domains and bounded native adapters |
| `extension/src/test/`, `tests/` | Simulated-host Java checks and host-free Python regressions |
| `scripts/` | Source builds/checks and offline evidence helpers |
| `examples/` | Client configuration and explicit opt-in music recipes |

The default-package `extension/src/main/java/BitwigAgent613GridInsertion.java` is intentional: its native superclass is also in the unnamed package and it is loaded by that exact class name. Historical evidence is not active test infrastructure; use [VERIFICATION.md](VERIFICATION.md) for current checks.
