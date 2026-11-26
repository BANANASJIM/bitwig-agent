# Agent guide

Bitwig Agent lets an MCP client or CLI inspect and edit a running Bitwig Studio project through a local Java controller extension. Python (`src/bitwig_agent`) validates requests; Java (`extension/`) executes them against the host. [README.md](README.md) covers use; this file covers working on the code.

## Commands

```sh
pixi install --locked
pixi run fmt      # apply ruff and Java formatting
pixi run check    # lint, host-free Python, Java and fixture layers; must exit 0
pixi run validate-skills
```

Run a single layer with `pixi run python scripts/check.py --layer lint`. Other tasks are listed in [CONTRIBUTING.md](CONTRIBUTING.md#development-checks). When piping a check, keep its exit status (`set -o pipefail`).

## Constraints that are not obvious from the code

- Native Bitwig bindings (`bitwig614`) can only be proven on a real host: use native E2E on an isolated test instance with a disposable project, never the user's active project.
- Writes fail closed. A write whose outcome is `unknown` is never retried, by API or GUI; stop, inspect state, then `ack` its hold.
- Never commit Bitwig host JARs, factory assets, credentials or personal paths.
- Java compiles with JDK 25 but generated classes target Java 11.
- Method metadata lives only in `src/bitwig_agent/protocol/`; Java reads the generated `protocol.json`.

## Where things are documented

| Need | Read |
|---|---|
| Code style and PR/evidence rules | [CONTRIBUTING.md](CONTRIBUTING.md#code-style) |
| Layers, ownership, repository map | [docs/architecture.md](docs/architecture.md) |
| Exact methods, limits, outcomes | [PROTOCOL.md](PROTOCOL.md) |
| What each check proves | [VERIFICATION.md](VERIFICATION.md) |
| Packaging and releases | [docs/packaging.md](docs/packaging.md) |

Add a rule here only after it prevented, or would have prevented, a real failure; anything a tool can check belongs in the tool.
