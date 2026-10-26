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

The default checks do not launch Bitwig. See [VERIFICATION.md](VERIFICATION.md) for layer boundaries. CI covers host-free Python checks, minimum supported MCP, Python package consumption, skill format and a reachable-history secret scan; local Arch packaging is a separate downstream check. None claims to build against or test a live proprietary host. Consult actual workflow output rather than treating configured jobs as passing evidence.

## Changes and bug reports

- Describe the observable behavior, affected methods and checks actually run. Add a deterministic regression test for a consumer-visible bug when practical.
- Keep MCP and CLI schemas consistent with Java validation and [PROTOCOL.md](PROTOCOL.md).
- Distinguish dispatch, model readback, GUI completion, audio output and persistence. Do not report an unknown write as failed-and-safe-to-repeat.
- Native work requires a disposable project and one designated writer. Never use a user's active project as an automatic test fixture.
- Review staged files before committing. Do not submit credentials, private conversations, raw machine/process snapshots, original tool-call identifiers, personal paths, Bitwig host binaries or proprietary factory assets. Use minimal synthetic reproductions and redacted diagnostics.
- Preserve third-party notices. Project contributions are distributed under the [MIT License](LICENSE); third-party material retains its own applicable license.

For security-sensitive issues, use the private route in [SECURITY.md](SECURITY.md), not a public issue containing exploit details or private data.

## Release procedure

See [packaging and release promotion](docs/packaging.md) for the complete artifact, Arch and PyPI contract. Releases are built locally against a maintainer's licensed Bitwig JAR; public CI does not have that dependency. The commands below use **0.1.1**; they are instructions, not evidence that any check ran.

1. Start from reviewed source, align Python/MCP/extension versions and installation links, and run the applicable [layered checks](VERIFICATION.md). Keep actual results; list native checks not performed.
2. Build the Python wheel **and sdist**, plus the matching extension:

   ```sh
   uv sync --locked
   uv build
   uv run --locked python scripts/build.py
   sha256sum dist/bitwig_agent-0.1.1-py3-none-any.whl dist/bitwig_agent-0.1.1.tar.gz build/BitwigAgent.bwextension
   ```

3. Create `SHA256SUMS` from those actual hashes with exactly three unique **bare asset filenames**, removing local `dist/` and `build/` prefixes. Inspect artifact contents and third-party notices. Never upload the proprietary host JAR, factory assets or private machine/evidence data.
4. Finalize [the Arch recipe](https://github.com/BANANASJIM/bitwig-agent/tree/main/packaging/aur) from those exact candidate artifacts and regenerate `.SRCINFO` **before tagging**. Validate it in an isolated Arch environment; its source cache may be prefilled with the exact candidate sdist and version-aliased extension. `packaging/` is excluded from the sdist, so updating its hashes does not require rebuilding that sdist.
5. Commit the reviewed source, recipe and metadata, then tag it as `v0.1.1`. Publish the same wheel, sdist, extension and manifest to its GitHub release. Include the actual tested platform/host scope and unresolved limitations. Do not mutate previous release artifacts.
6. Exercise the real GitHub wheel/download path in an isolated environment outside the checkout, using an isolated extension destination, offline CLI and the generated configuration's MCP handshake. Confirm the release-triggered Arch workflow builds the matching version from published downloads. A live status/editing check is separate and requires permission, one writer and a disposable project; recipe verification is not an AUR upload.
7. Publish the matching reviewed recipe, `.SRCINFO` and license through the separate AUR Git repository's `master` branch. Follow [AUR maintenance](docs/packaging.md#maintaining-the-recipe); confirm the public package version and maintainer before announcing helper-based installation.
8. Run `publish-pypi.yml` on the version tag with `publish=false` for promotion validation. Only an explicitly authorized publication with the configured `pypi` environment and Trusted Publisher may use `publish=true`. Promote the same reviewed artifacts, not a separate rebuild. See [external account prerequisites](docs/packaging.md#external-accountenvironment-prerequisites).

Neither a pending PyPI publisher nor an AUR recipe establishes public package availability. Keep the GitHub wheel as the uv installation source until the intended PyPI project/version exists; an AUR publication is a separate distribution path.

## Agent skill maintenance

The portable skill is [`skills/bitwig-agent`](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent). Keep its main `SKILL.md` short; place focused guidance one directory level down in `references/`, with every reference directly reachable from `SKILL.md`. Bundle its MIT `LICENSE` so the folder can be copied alone. Links outside that folder must use public repository URLs, not checkout-relative paths. Do not embed host identities, private paths, credentials, global client configuration changes or a tool permission grant.

Use the [Agent Skills specification](https://agentskills.io/specification) and the demonstration `skills-ref` validator pinned to the reviewed source commit:

```sh
uvx --from "git+https://github.com/agentskills/agentskills.git@69ef37e9424c0a7ea9dd2293b559e43ec8176379#subdirectory=skills-ref" skills-ref validate skills/bitwig-agent
```

This command fetches a validation tool; it is not a production DAW dependency. Format/frontmatter validation does **not** establish correct tool use, native behavior, compatibility, permissions or musical acceptance. Separately review the copied folder's local references and safety instructions: fresh schema/target discovery, explicit permission, unknown-stop discipline and no implicit GUI fallback.

## Repository map and architecture

See [architecture and ownership](docs/architecture.md) for the detailed public-code walkthrough. Python MCP stdio and CLI entrypoints share a strict schema/catalog and loopback client. The Java extension dispatches bounded newline-delimited JSON requests through host scheduling into separate domains. Bitwig owns the project/audio engine; the bridge is not a complete project mirror and never automatically retries requests.

| Path | Responsibility |
|---|---|
| `src/bitwig_agent/` | Schemas/catalog, transport, MCP, CLI and release installer |
| `extension/src/main/java/dev/bitwigagent/` | Host services, domains and bounded native adapters |
| `extension/src/test/`, `tests/` | Simulated-host Java checks and host-free Python regressions |
| `scripts/` | Source builds/checks and offline evidence helpers |
| `examples/` | Client configuration and explicit opt-in music recipes |
| `docs/` | Public architecture, installation navigation and packaging guides |
| `packaging/aur/` | Reviewed AUR recipe and synchronized `.SRCINFO` |
| `skills/bitwig-agent/` | Copyable Agent Skills instructions, focused references and MIT license |

The default-package `extension/src/main/java/BitwigAgent613GridInsertion.java` is intentional: its native superclass is also in the unnamed package and it is loaded by that exact class name. Historical evidence is not active test infrastructure; use [VERIFICATION.md](VERIFICATION.md) for current checks.
