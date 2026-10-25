# Contributing

Keep changes focused on helping agents complete real Bitwig tasks. Domain operations belong in their existing modules; transport, validation and native bindings remain separate. Do not add automatic retries or GUI fallback for uncertain writes.

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
