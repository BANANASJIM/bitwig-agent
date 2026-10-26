# Packaging and release promotion

This guide separates **building/installing a package**, **publishing reviewed release artifacts**, and **operating Bitwig**. None implies the others. For ordinary installation use [INSTALL.md](../INSTALL.md); source prerequisites and contributor checks are in [CONTRIBUTING.md](../CONTRIBUTING.md).

## Distribution status and version contract

The current release version is **0.1.1**. The project remains experimental/Alpha, and the GitHub release retains its prerelease badge; packaging work does not promote native maturity.

GitHub release assets and the [AUR package `bitwig-agent` 0.1.1-1](https://aur.archlinux.org/packages/bitwig-agent), maintained by `bananasjim`, are available. **PyPI publication is still pending**: use the versioned GitHub wheel for uv until the intended project/version is actually published there.

Python and Java are a matched release pair. The installer derives the GitHub release tag from its installed Python package version. Keep package metadata, MCP server version, extension definition and release links aligned. Never replace the contents of an existing release with a different build under the same version; make a new version for changed artifacts.

| Asset | Role |
|---|---|
| `bitwig_agent-0.1.1-py3-none-any.whl` | Installable Python MCP adapter, CLI and extension installer |
| `bitwig_agent-0.1.1.tar.gz` | Python source distribution for independent package building/installing |
| `BitwigAgent.bwextension` | Same-version prebuilt Java extension |
| `SHA256SUMS` | Exactly three unique SHA-256 entries using the three bare asset filenames above |

The Python wheel is not a bundle of the Java extension, Bitwig, a JDK or all transitive Python dependencies. The installer downloads the extension and manifest separately, or copies an explicitly supplied local extension. Manual `--extension` installation trusts the caller's provenance/checksum check. A hash in the same release manifest checks integrity against that manifest, not an independent signing authority or native compatibility.

## Python tools: uv, uvx and the lockfile

`uv tool install <versioned-wheel-URL>` is the persistent user installation: isolated dependencies, exposed entrypoints and a stable environment from which to generate MCP configuration. `uvx --from <versioned-wheel-URL> <entrypoint>` is ephemeral/cached execution, not persistent configuration. Both can fetch dependencies and neither installs/enables a Bitwig controller merely by resolving the Python package. See [working commands](../INSTALL.md#persistent-uv-tools-and-ephemeral-uvx).

The package declares Python 3.11+, `mcp>=1.29.1,<2` and `pydantic>=2.11,<3`. [`uv.lock`](https://github.com/BANANASJIM/bitwig-agent/blob/main/uv.lock) pins the source/development environment; it does not force every downstream wheel or Arch installation onto those exact versions. `uv sync --locked` is the reproducible contributor workflow. Minimum-MCP coverage is a separate compatibility check; using `--no-sync` after substituting that dependency prevents uv from silently restoring the lockfile's SDK before the check.

PyPI short commands are conditional on actual publication. For example, **only after `bitwig-agent` 0.1.1 is published** may `uv tool install bitwig-agent==0.1.1` replace the GitHub wheel URL. Users should not confuse a prepared Trusted Publisher configuration with a successful release.

## Arch/AUR recipe

The reviewed recipe lives in [`packaging/aur`](https://github.com/BANANASJIM/bitwig-agent/tree/main/packaging/aur) and is published through the separate [AUR package repository](https://aur.archlinux.org/packages/bitwig-agent). Review it before executing `makepkg`; build as a normal user. [INSTALL.md](../INSTALL.md#arch-linux-aur) gives helper/manual installation and the explicit user-extension copy.

### Package layout and dependencies

The package is named `bitwig-agent`, not `bitwig-agent-bin`: its Python component is built from the versioned Python source distribution. The same-version prebuilt Java extension uses the Arch Java packaging exception for Java artifacts. This avoids requiring a user's proprietary Bitwig host JAR merely to package the public Python tools.

- Build Python with the standard `python-build --no-isolation` / `python-installer --destdir` path and declared build dependencies.
- Use Arch's system `python-mcp` and `python-pydantic` at runtime. No runtime uv, embedded private virtualenv or pip-driven package-manager hook is needed.
- Put the prebuilt extension at `/usr/share/bitwig-agent/BitwigAgent.bwextension`, with license/notices in the package's standard shared locations.
- Install public guides under `/usr/share/doc/bitwig-agent/`, retaining their `docs/` subdirectory, and the copyable skill under `/usr/share/bitwig-agent/skills/bitwig-agent/`. A skill consumer copies the whole skill directory rather than only `SKILL.md`.
- Do not run network package resolution from `package()` or a post-install hook. Source retrieval and hash verification belong to `makepkg`'s source phase.
- Never write into user homes from a privileged package install. Each user explicitly copies the packaged extension with `bitwig-agent-install --extension /usr/share/bitwig-agent/BitwigAgent.bwextension` and enables it in Bitwig.
- Keep Bitwig as an optional host dependency. Do not force installation or upgrade of the proprietary host, or imply that its AUR version is natively accepted here.

The Python sdist excludes downstream `packaging/` so that the AUR recipe can record the sdist's real checksum without recursively changing that same sdist. The public repository remains the source of the downstream recipe and `.SRCINFO`.

### Maintaining the recipe

Before creating the release tag, set the recipe's version and source hashes from the **exact built candidate artifacts** and commit the final recipe with the release source. Prefill `makepkg`'s source cache with that sdist and version-aliased extension when testing before publication. Build/check and rebuild without `--cleanbuild` in an isolated official Arch environment, inspect installed file ownership/dependencies/notices, exercise offline entrypoints and smoke the real installed Python package outside the checkout. Generate `.SRCINFO` from the final `PKGBUILD`:

```sh
cd packaging/aur
makepkg --printsrcinfo > .SRCINFO
```

Keep `PKGBUILD` and `.SRCINFO` synchronized. After the matching GitHub release and Arch checks pass, copy only `PKGBUILD`, `.SRCINFO` and `LICENSE` into a separate checkout of `ssh://aur@aur.archlinux.org/bitwig-agent.git`. Review and commit that update, then push its `master` branch; AUR does not accept another branch. Never push build outputs, the upstream code history or private evidence into the AUR repository. Confirm the public package's version and maintainer after pushing; a local recipe or successful build alone does not establish publication.

Changes to the system package do not update an already-copied user extension. Users repeat the opt-in copy and reload the controller while keeping versions matched. Registry-status documentation may advance on `main`; do not rebuild or replace immutable 0.1.1 release assets just to update availability wording.

The separate [`aur.yml` workflow](https://github.com/BANANASJIM/bitwig-agent/blob/main/.github/workflows/aur.yml) runs on a published GitHub release or explicit manual dispatch, **not** on each main-branch push: its immutable release sources must already exist. It checks tag/Python/recipe version agreement, then builds and rebuilds as an unprivileged user inside a digest-pinned disposable official Arch container. It checks `.SRCINFO`, rejects namcap errors, installs with documentation extraction enabled, verifies owned files and exercises the matching installed Python/MCP consumer plus the explicit user-extension copy. The release run verifies real published downloads; updating main afterward cannot repair a stale recipe in an existing tag. It never uploads to AUR, installs Bitwig or changes a real user's extension directory. Consult actual workflow output; configured checks are not proof of success.

The currently observed AUR `bitwig-studio` version is 6.1.4; the project's native evidence remains Linux / 6.1.3 / API 25 with an exact loaded-JAR gate for private operations. Packaging must not silently relax that safety boundary.

## Build once, promote the same artifacts

The maintainer builds the Python wheel, Python sdist and Java extension from one reviewed source/version snapshot. Java building requires the maintainer's own licensed host JAR and JDK 25+; public CI is not supplied with the proprietary JAR. The extension targets Java 11 bytecode but its Bitwig 6.1.3 loader superclass requires JDK 25 to read during building/checking.

1. Update matching versions and installation links, resolve the lockfile, and run the applicable [verification layers](../VERIFICATION.md). Record which layers actually ran.
2. Build wheel and sdist with `uv build`, then the extension with `uv run --locked python scripts/build.py`.
3. Inspect packaged contents and licenses. Do not include the host JAR, proprietary factory assets, credentials, private diagnostics or machine configuration.
4. Record real SHA-256 values in `SHA256SUMS` using exactly the three bare asset filenames. Finalize and validate the Arch recipe and `.SRCINFO` from those same bytes before committing/tagging; its exclusion from the sdist avoids checksum recursion.
5. Tag the reviewed source and recipe, then publish all four unchanged assets on the matching `v0.1.1` GitHub release.
6. Consume the real downloaded artifacts in isolated environments outside the checkout and confirm the release-triggered Arch workflow checks that version. Extension download/copy checks must use an isolated destination, not a user's native controller directory. Package/MCP startup checks do not authorize or establish native DAW behavior.
7. If PyPI publication is authorized and configured, promote the same wheel/sdist through the workflow below. Do not rebuild a different wheel under the same version for a different registry.

## PyPI Trusted Publishing

[`publish-pypi.yml`](https://github.com/BANANASJIM/bitwig-agent/blob/main/.github/workflows/publish-pypi.yml) is **manual**, not triggered by ordinary pushes. Its `publish` boolean defaults to **false**. It requires an exact `refs/tags/vX.Y.Z` ref and matching package versions. The default validation run downloads the published GitHub wheel, sdist, extension and manifest, verifies the three unambiguous checksum entries, and exercises installed wheel and sdist consumption outside the checkout. Only the Python artifacts are staged for PyPI.

A published non-draft GitHub release may retain its prerelease badge. The workflow still requires a stable-form `vX.Y.Z` tag and matching Python distribution metadata; a GitHub UI badge does not change the Python package version or the separate explicit publication approval.

Run validation-only from the release tag:

```sh
gh workflow run publish-pypi.yml --ref v0.1.1 -f publish=false
```

The separate publish job runs only for explicit `publish=true`, uses GitHub environment **`pypi`**, and receives `id-token: write` there for short-lived OIDC credentials. The validation job has read-only permissions. No long-lived PyPI API token should be introduced. The publication job promotes the validated Python artifacts instead of checking out and rebuilding project source.

### External account/environment prerequisites

An authorized maintainer must configure and confirm these separately; the workflow file does not create registry ownership:

| PyPI Trusted Publisher field | Value |
|---|---|
| Project name | `bitwig-agent` |
| Owner | `BANANASJIM` |
| Repository | `bitwig-agent` |
| Workflow filename | `publish-pypi.yml` |
| Environment name | `pypi` |

For a first release, use PyPI's **pending publisher** setup under the intended account; a pending publisher does not reserve or publish a project name. If the project already exists by the time of setup, use the authorized project's publisher configuration instead and confirm ownership. This PyPI account-side setup remains an external prerequisite.

The repository's GitHub `pypi` environment has been configured with required reviewer `BANANASJIM` and deployment tag pattern `v*`, not branches. Self-review is permitted so the sole maintainer can explicitly approve their own run; this is a human approval boundary, not independent two-person review. The workflow itself additionally enforces the stricter `vX.Y.Z` tag syntax. Reconfirm environment protections before publishing because account settings are mutable and are not created by the workflow file.

Only after validation passes, prerequisites are confirmed and publication is explicitly authorized:

```sh
gh workflow run publish-pypi.yml --ref v0.1.1 -f publish=true
```

Approve the environment deployment deliberately, observe the workflow result, then check the actual PyPI project/version and consume its artifacts before announcing index-based installation. OIDC setup, environment approval and a successful upload are separate evidence. GitHub attestation/provenance does not establish native DAW acceptance.

## Packaging verification is not host acceptance

The public CI and local checks cover package content, dependencies, CLI discovery, MCP handshake, checksum handling, Arch packaging and skill format in their own scopes. Read the current workflow output for the revision being released; configuration alone is not passing evidence. Matching Python/extension versions also do not establish all-method host support. Native testing requires explicit permission, one writer and a disposable project; audio, GUI and save/reopen remain separate evidence layers.

## Primary references

- [uv tools: persistent installation and ephemeral execution](https://docs.astral.sh/uv/guides/tools/)
- [Arch Python package guidelines](https://wiki.archlinux.org/title/Python_package_guidelines)
- [Arch Java package guidelines](https://wiki.archlinux.org/title/Java_package_guidelines)
- [AUR submission guidelines](https://wiki.archlinux.org/title/AUR_submission_guidelines)
- [PyPI Trusted Publishers](https://docs.pypi.org/trusted-publishers/)
- [Creating a PyPI project through a pending publisher](https://docs.pypi.org/trusted-publishers/creating-a-project-through-oidc/)
- [GitHub deployment environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
