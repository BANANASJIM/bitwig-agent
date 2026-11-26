# Contributing

Keep changes focused on helping agents complete real Bitwig tasks. Domain operations belong in their existing modules; transport, validation and native bindings remain separate. Do not add automatic retries or GUI fallback for uncertain writes.

## Source setup

End users should use the [released wheel and extension](README.md#quickstart) when its host boundary fits. Current source requires a checkout, [pixi](https://pixi.sh/latest/installation/), and your own licensed **Bitwig 6.1.4 / API 25** installation. Pixi supplies Python, JDK 25 and PMD from conda-forge; the extension's loader superclass uses class-file version 69, so JDK 25 is needed to compile/run Java checks, while generated extension classes still target Java 11. This is a clean private-binding cutover, not simultaneous 6.1.3/6.1.4 support; published `0.1.2` remains unchanged.

From the repository root:

```sh
pixi install --locked
pixi run install
```

`install` builds `build/BitwigAgent.bwextension` and copies it into your Bitwig Extensions directory. The extension build compiles against your installed host JAR, `/opt/bitwig-studio/bin/bitwig.jar` unless `BITWIG_API_JAR` names another. The build downloads and verifies pinned Gson on first use. The proprietary host JAR is not bundled. Installation does not enable the controller: follow [the enable/connect steps](README.md#2-enable-in-bitwig). The checkout's CLI and MCP server run from its editable pixi environment (`pixi run bitwig-agent-rpc list`). For a checkout-based MCP configuration, run `pixi run bitwig-agent-install --mcp-config`; keep the checkout's `.pixi` environment available to the client. Pixi cannot install PyPI-only applications globally, so there is no pixi equivalent of a tool install.

## Development checks

Everything runs through pixi tasks; the default environment includes the optional audio-analysis dependencies, so the offline suite exercises its PCM checks rather than skipping them.

| Task | Does |
|---|---|
| `pixi run test` | host-free Python tests |
| `pixi run lint` | static gates below |
| `pixi run fmt` | apply ruff fixes and reformat Python and Java |
| `pixi run cpd-baseline` | rewrite `config/cpd-baseline.json` |
| `pixi run test-java` | simulated-host Java tests |
| `pixi run fixture` | real MCP/Java fixture |
| `pixi run check` | lint, offline, Java and fixture layers (`scripts/check.py` defaults) |
| `pixi run dist` | sdist and wheel in `dist/` |
| `pixi run extension` | `build/BitwigAgent.bwextension` |
| `pixi run build` | `dist` and `extension` |
| `pixi run install` | build and install the extension, see above |
| `pixi run smoke-dist` | install each built distribution into a fresh venv and smoke it (`--mcp VERSION` pins the MCP SDK) |
| `pixi run validate-skills` | Agent Skills format check |

`extension` (and so `build` and `install`) needs your own compatible Bitwig host JAR (default `/opt/bitwig-studio/bin/bitwig.jar`; override with `BITWIG_API_JAR`). `test-java` and `fixture` use the checksum-pinned public extension API unless `BITWIG_API_JAR` is set. Pixi provides JDK 25 and PMD, so no Java toolchain setup is needed. CI's other environments (`py311`, `py314`, `py311-mcp-min`, which pins the minimum supported MCP SDK) run with `pixi run -e ENVIRONMENT test`.

The `lint` task needs neither the API jar nor Bitwig; it downloads only the checksum-pinned palantir-java-format (native binary, or the pinned JVM jars where none exists for the host) into `build/deps`. Run a single layer through `pixi run python scripts/check.py --layer lint`.

`config/cpd-baseline.json` lists existing duplicates; only new ones fail, so update it only when a refactor removes some. Ruff per-file ignores under "refactor debt" in `pyproject.toml` may shrink, never grow. `git config blame.ignoreRevsFile .git-blame-ignore-revs` hides the mechanical format commit from blame.

The default checks do not launch Bitwig. See [VERIFICATION.md](VERIFICATION.md) for layer boundaries. CI covers host-free Python checks, the static `lint` gates, minimum supported MCP, Python package consumption, skill format and a reachable-history secret scan; local Arch packaging is a separate downstream check. None claims to build against or test a live proprietary host. Consult actual workflow output rather than treating configured jobs as passing evidence.

## Code style

The tools decide formatting, types, duplicate code, the `protocol/` import direction and warnings: run `pixi run fmt`, then `pixi run check`. Fix the cause; a suppression needs a reason the tool cannot infer. Everything below is judgment the tools cannot check. When rules conflict, earlier wins:

1. **Correct and fail-closed.**
2. **Optimal design.** Never keep a worse design for temporary or historical reasons; diff size, old wording and existing call sites are not reasons.
3. **Minimal.** The least code that is clear, correct and complete. Not terse, not clever.
4. **One responsibility** per module, class and function.
5. **One source per fact.** No duplicated logic, no hardcoded values.
6. **Decoupled.** Depend inward through narrow interfaces.

- **Structure.** The directory tree is documentation ([layers and ownership](docs/architecture.md)). Put new code where its layer and domain live; if nothing fits, fix the structure instead of adding a misc module. Native bindings return plain data; they do not parse requests, build JSON or apply domain rules.
- **Facts.** Method metadata and request shapes are defined once in `protocol/`; Java consumes the generated `protocol.json`. Derive method lists, timeouts, enums and limits instead of restating them. A number in a message comes from the constant that enforces it. One concept has one name across Python, Java and the wire.
- **Changing code.** Refactor fully to the right design and update every call site: no shims, aliases, re-exports or legacy paths. A behaviour-preserving refactor proves it (for example, exported schemas byte-identical); a behaviour change is explicit and versioned.
- **Comments.** None unless the code cannot say it: reverse-engineered native facts, measured host behaviour, a non-obvious external constraint. Never restate code or narrate reasoning; delete stale comments.
- **Errors and writes.** Writes compare-and-set, read back, and never retry `unknown` (outcomes are defined in [PROTOCOL.md](PROTOCOL.md#core-discovery-and-direct-parameters)). Build errors only through `Errors` / `ErrorCode` (Java) and `bridge_error` (Python). A safety gate must answer a failure that was actually observed; no gates against imagined ones.
- **Tests.** Test every layer. Shared fakes live in one place (`tests/fakes.py`; one strict fake host in Java); never import from another test file. A change to native bindings needs native E2E on an isolated test instance.
- **Language.** English everywhere in this repository. Commit subjects are imperative; no AI attribution in commits or PRs.

## Changes and bug reports

Pull requests follow [the template](.github/pull_request_template.md): why, a before/after diagram, what changes for users, evidence, risks. Write for a human reader; leave exhaustive detail to the code and tests. Evidence must be reproducible: link the CI run or give the command and its key output; never pipe a check through `tail` or similar without preserving its exit status.

- Describe the observable behavior, affected methods and checks actually run. Add a deterministic regression test for a consumer-visible bug when practical.
- Keep MCP and CLI schemas consistent with Java validation and [PROTOCOL.md](PROTOCOL.md).
- Distinguish dispatch, model readback, GUI completion, audio output and persistence. Do not report an unknown write as failed-and-safe-to-repeat.
- Native work requires a disposable project and one designated writer. Never use a user's active project as an automatic test fixture.
- Review staged files before committing. Do not submit credentials, private conversations, raw machine/process snapshots, original tool-call identifiers, personal paths, Bitwig host binaries or proprietary factory assets. Use minimal synthetic reproductions and redacted diagnostics.
- Preserve third-party notices. Project contributions are distributed under the [MIT License](LICENSE); third-party material retains its own applicable license.

For security-sensitive issues, use the private route in [SECURITY.md](SECURITY.md), not a public issue containing exploit details or private data.

## Releases

See the [release procedure](docs/packaging.md#release-procedure).

## Agent skill maintenance

The portable skill is [`skills/bitwig-agent`](https://github.com/BANANASJIM/bitwig-agent/tree/main/skills/bitwig-agent). Keep its main `SKILL.md` short; place focused guidance one directory level down in `references/`, with every reference directly reachable from `SKILL.md`. Bundle its MIT `LICENSE` so the folder can be copied alone. Links outside that folder must use public repository URLs, not checkout-relative paths. Do not embed host identities, private paths, credentials, global client configuration changes or a tool permission grant.

Use the [Agent Skills specification](https://agentskills.io/specification) and the demonstration `agentskills` reference validator (conda-forge `skills-ref`, version-locked in `pixi.lock`):

```sh
pixi run validate-skills
```

The validator is a development dependency, not a production DAW dependency. Format/frontmatter validation does **not** establish correct tool use, native behavior, compatibility, permissions or musical acceptance. Separately review the copied folder's local references and safety instructions: fresh schema/target discovery, explicit permission, unknown-stop discipline and no implicit GUI fallback.

## Architecture

See [architecture and ownership](docs/architecture.md), including the [repository map](docs/architecture.md#repository-map).
