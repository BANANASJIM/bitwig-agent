# Security

## Reporting a vulnerability

Use GitHub's [private vulnerability reporting](https://github.com/BANANASJIM/bitwig-agent/security/advisories/new) for this repository. Include the affected revision, prerequisites, a minimal reproduction and the observed impact. Remove credentials, project contents, personal filesystem paths and unrelated process/network information before submitting diagnostics.

Do not post unpatched exploit details or credentials in a public issue. Do not test reports against another person's Bitwig session or system.

## Trust boundary and support

This project is experimental. Security fixes target the current `main` branch; older revisions do not have a separate support commitment.

The extension accepts unauthenticated requests on `127.0.0.1:61172`. It trusts local processes that can reach that endpoint and is not a multi-user or network service. Do not expose it through port forwarding, reverse proxies or a public interface. Context tokens and target guards prevent some stale operations; they are not authentication credentials or an authorization boundary.

Host operations can modify projects and produce audio. Use disposable projects for testing, keep backups and stop after unknown outcomes rather than retrying. The exact-version/JAR gate for private Grid access is a compatibility safeguard, not a sandbox. See [README.md](README.md) and [PROTOCOL.md](PROTOCOL.md) for operational limits.
