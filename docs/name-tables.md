# Name tables: a new platform or Bitwig version

Private features (Grid, modulators, routing and sidechains, layer chains, native note writes and the other version-gated methods) are maintained and natively tested on Linux only. Windows and macOS support, and each new Bitwig release, depend on a name table made from that platform's and version's `bitwig.jar`. Contributions are welcome: you need your own licensed Bitwig installation and a checkout of this repository, and you never share Bitwig's files. Open a [name table issue](https://github.com/BANANASJIM/bitwig-agent/issues/new?template=name-table.yml) first so the work is not done twice.

## Start with your coding agent

If you work with a coding agent, paste this prompt into it from the repository root. It is self-contained; it tells the agent what to read, what to run and where to stop.

```text
You are helping me contribute a Bitwig Agent name table for a new platform or Bitwig version.
Repository: https://github.com/BANANASJIM/bitwig-agent (I have a checkout; work from its root).

1. Read AGENTS.md, CONTRIBUTING.md and docs/name-tables.md before doing anything else. Follow them;
   where this prompt and those files disagree, the files win.
2. Find my local bitwig.jar (search the Bitwig Studio installation directory; do not guess a path).
   Ask me to confirm the Bitwig version shown in Bitwig's About dialog, determine the platform
   (Linux, Windows or macOS), and compute the jar's SHA-256. Report the version, platform and hash.
3. Check src/bitwig_agent/shared/host.json. If this exact jar hash is already listed, stop and tell me.
4. Get the Linux bitwig.jar of the source version named in docs/name-tables.md and check its SHA-256
   against host.json. Do not continue with a jar whose hash does not match.
5. Run the remap from the matching Linux table (names_<version>.json), then
   `PYTHONPATH=scripts pixi run python -m remap.table verify ...` and
   `pixi run test-java --host-jar <my jar>`, exactly as docs/name-tables.md describes.
   Read the remap report. Any key that is not exact or confident is a question for me, not for you.
6. Never commit, upload, paste or attach bitwig.jar, any other Bitwig file, decompiled code or other
   proprietary bytes. Commit only the name table, the verification record and the files the guide
   lists. Do not include personal paths, user names, machine snapshots or process listings in files,
   commits, issues or the pull request; write paths as placeholders such as NEW/bitwig.jar.
7. The native smoke runs against a live Bitwig. Run it only if I agree, only on a project I can lose
   (never my active project), with one writer. It can produce sound: ask me to mute or turn down the
   audio output first, and stop on any unknown outcome instead of retrying.
8. Before committing, run `pixi run check` and keep its exit status. Open the pull request with
   .github/pull_request_template.md. Under Evidence, give each command with its key output: the jar
   SHA-256, the remap summary, the verify result, the test-java result, and the native smoke result
   or "not run".
9. When anything is ambiguous (a mapping, a file name, where a new entry belongs, whether to run a
   step), stop and ask me. Do not invent a value to get past it.
```

## What a name table is

Bitwig's `bitwig.jar` is obfuscated: its internal classes and members have short generated names (`Zsf`, `beI`) that change with every release and differ between platforms. Binding code never uses those names. It uses stable semantic keys, and a name table translates them for one jar:

- `classes` maps a key to a host class;
- `members` maps a key to `{owner, name, desc}`: the owner class key, the member's obfuscated name and its JVM descriptor in that jar.

Tables live in `src/bitwig_agent/shared/` as `names_<version>.json`. Each entry in `host.json` names one supported jar by its exact SHA-256 and the table that goes with it. When Bitwig loads a jar whose hash is not listed, private features fail closed and report the native gate as unavailable; the public Controller API keeps working.

## Why each platform and version needs its own

The obfuscator runs separately for every Bitwig release and for every platform build. For 6.1.7 the Linux, Windows and macOS jars differ, and so do their member names. A table made on the Linux jar therefore does not bind on the Windows or macOS jar of the same version, and no table carries over to the next release.

The mapping is mechanical but not blind: `scripts/remap` parses both jars as data (it never loads or runs host code), matches each member inside its own declaring class, and reports how confident each match is.

## Checklist

Use absolute paths for the jars; `NEW/bitwig.jar` is your platform's or version's jar, `SOURCE/bitwig.jar` the Linux jar the existing table was made on. Neither is ever committed.

1. **Identify your jar.** Confirm the Bitwig version (About dialog) and platform, and compute the SHA-256 (`sha256sum`, `shasum -a 256` or `Get-FileHash`). If `host.json` already lists the hash, there is nothing to do.
2. **Get the source jar.** For a new platform, the source is the Linux jar of the same version; for a new release, the latest maintained version's Linux jar. Get it from Bitwig's official downloads and extract `bitwig.jar` from the package without installing it. Its SHA-256 must equal the one in `host.json`.
3. **Remap.** Generate the table from the matching Linux table:

   ```sh
   pixi run remap -- SOURCE/bitwig.jar NEW/bitwig.jar --from-table src/bitwig_agent/shared/names_<version>.json \
       --out src/bitwig_agent/shared/names_<new>.json --report build/remap-<new>.md
   ```

   No environment variable is needed here; `pixi run remap` sets its own. Any key not mapped `exact` or `confident` fails the run and writes no table. Read the report; raise the remaining keys in the issue instead of guessing them.
4. **Verify the table on its jar** and record the check (offline CI compares the table and jar hashes with the record):

   ```sh
   PYTHONPATH=scripts pixi run python -m remap.table verify src/bitwig_agent/shared/names_<new>.json \
       NEW/bitwig.jar --record tests/fixtures/names_verified.json
   ```

   On Windows PowerShell, set the variable first and drop the prefix: `$env:PYTHONPATH="scripts"; pixi run python -m remap.table verify ...`

5. **Bind every native port** on the jar and compare the bound types with each member's descriptor. The jar joins only the test classpath; no host code runs:

   ```sh
   pixi run test-java --host-jar NEW/bitwig.jar
   BITWIG_HOST_JARS=NEW/bitwig.jar pixi run test tests/test_names_tables.py
   ```

   On Windows PowerShell: `$env:BITWIG_HOST_JARS="NEW\bitwig.jar"; pixi run test tests/test_names_tables.py`

6. **Wire the entry.** A new release adds its `host.json` entry and Grid insertion adapter as described in [Mapping a new Bitwig release](../CONTRIBUTING.md#mapping-a-new-bitwig-release). For a new platform, note that `host.json`, the build and the Grid insertion adapter currently hold one jar per Bitwig version. The first contribution for a platform settles with a maintainer how a second jar of the same version is named and wired, so agree that in the issue before writing this part.
7. **Native smoke on your platform.** Build and install the extension against your jar (`BITWIG_API_JAR=NEW/bitwig.jar pixi run install`; on Windows PowerShell `$env:BITWIG_API_JAR="NEW\bitwig.jar"; pixi run install`), enable it in Bitwig, open a project you can lose, and check `bitwig-agent status --detail=full`: the version-gated bindings should be available. Then exercise a few private features, for example a Grid read and a small `grid.apply` on a new Poly Grid. Use one writer, mute or turn down the audio output, and stop on any `unknown` outcome instead of retrying. `scripts/smoke-native.py` needs Linux `/proc` and `xprop`, so on Windows and macOS record the calls and their results yourself.
8. **Run `pixi run check`** and open the pull request with [the template](../.github/pull_request_template.md).

## What to submit

Submit only data you produced from the jar, never the jar:

- the name table `names_<new>.json` (key names, obfuscated names and descriptors);
- the updated `tests/fixtures/names_verified.json` record;
- the `host.json` entry and adapter changes agreed in the issue;
- in the pull request, the evidence: the jar's version, platform and SHA-256, the remap summary (which keys were not `exact`), the verify and `test-java` results, and what the native smoke did.

Never submit `bitwig.jar`, any other Bitwig file, decompiled or disassembled code, factory content, or excerpts of them. The remap report contains symbol names and hashes only; attach it if useful, after checking it holds no local paths. Leave out personal paths, user names and machine or process snapshots.

## How maintainers review

- The table must bind and verify on the jar with the stated hash; CI checks the record offline.
- Every key in the source table is present: mapped, or an explicit `null` decided by a person with a reason. A key is never silently left out.
- Keys that were not `exact` in the report get a second look against the report's evidence.
- The pull request contains no proprietary bytes, personal paths or machine data.
- A maintainer cannot run the native smoke on your platform. Linux is the only platform natively tested by the project, so the native smoke on Windows or macOS is yours, and the pull request states what it covered. A table that binds is not native behaviour.
