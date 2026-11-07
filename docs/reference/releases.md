# Releases and versions

Tap uses **one version for every component**, starting with **0.1.0**. A single `vX.Y.Z`
GitHub Release carries the server, clients, tools and complete download bundles, all built
from the same commit. Older releases used component-specific tags; those remain available
but are no longer the release model.

| Component | Artifacts on the shared release | Also published to |
|---|---|---|
| **Server** (`tap` with the bundled Android driver) | `tap-X.Y.Z-linux-x86_64`, `tap-X.Y.Z-macos-aarch64`, `tap-X.Y.Z-server-jvm.zip` | |
| **Kotlin client and JUnit 5** | `tap-X.Y.Z-maven.zip`: `tap-schema`, `tap-api`, `tap-client`, `tap-junit5` | GitHub Packages (Maven) |
| **Python client and pytest** | `tap_e2e-X.Y.Z-py3-none-any.whl`, source distribution | PyPI when enabled |
| **Tap agent** (experimental) | `tap_agent-X.Y.Z-py3-none-any.whl`, source distribution | PyPI when enabled |
| **Tap Studio** (experimental) | `tap_studio-X.Y.Z-py3-none-any.whl`, source distribution | PyPI when enabled |
| **Tap watcher** (experimental) | `tap_watcher-X.Y.Z-py3-none-any.whl`, source distribution | PyPI when enabled |
| **App synchronization SDK** (experimental) | `tap-sync-sdk` AAR in `tap-X.Y.Z-maven.zip` | GitHub Packages (Maven) |

The same release has **one complete bundle per platform**: `tap-X.Y.Z-linux-x86_64.zip`,
`tap-X.Y.Z-macos-aarch64.zip` and `tap-X.Y.Z-jvm.zip`. Each includes its server, all four
Python wheels, a local Maven repository (no GitHub token needed), documentation, `install.sh`,
`INSTALL.md`, `VERSIONS` and per-file checksums. `SHA256SUMS` on the release covers every
asset. Get a bundle from [Download](../download.md).

The server and Android driver remain one artifact: the server installs its bundled APKs;
users never select or mix driver versions. The synchronization SDK is still optional:
sharing a release version does not require an app team to update it for every server fix.

## Compatibility

- Install the server and clients from the same release. Clients speak `tap.v1` and enforce
  a matching API compatibility epoch plus a server no older than their generated baseline.
  Deliberate 0.x API breaks increment the epoch; additive changes within an epoch remain
  wire-compatible. Unified versioning does not remove these runtime safeguards.
- The device protocol between server and driver is internal. Its version is reported by
  `Info()` for diagnostics and changes independently from release versions.

## Stability

Tap is **alpha** software. Every component, including the experimental synchronization SDK,
uses the shared **0.1.0** release version.

- **0.x:** features and breaking changes increment the minor version (0.1.0 → 0.2.0);
  fixes increment the patch (0.1.0 → 0.1.1). Release notes identify affected components.
- **From 1.0:** [semantic versioning](https://semver.org) for Tap as a whole. Client APIs,
  the `tap.v1` API and the CLI change incompatibly only in a new major version. A breaking
  server API would be a new `tap.v2` package served alongside it.
- **Experimental** parts may change in any release, including after 1.0:
    - app synchronization (`App.awaitIdle` / `await_idle`, `tap-sync-sdk`);
    - Tap agent, held connections, snapshots and refs, the event log and its export formats;
    - Tap Studio and the `tap-recording/2` format;
    - Tap watcher, `tap-watch-steps/2` and `WatchService`.
- A published version is never replaced. Fixes ship under a new version.
- Release candidates use tags such as `v0.2.0-rc.1` and are marked as GitHub pre-releases;
  Python normalizes that version to `0.2.0rc1`. A numeric tag such as `v0.1.0` is a regular
  GitHub Release even while the project itself is alpha.

## Publishing

The canonical version is `tap.version` in `gradle.properties`. Python package metadata
mirrors it so source distributions build without the repository; CI rejects mismatches.
After CI passes on `main`, the release workflow tags an unshipped version and builds all
artifacts. Downloads stay on a draft until every build, bundle and registry upload succeeds.
A version bump alone is not proof that a release is available: check its release page.

- Releases and bundles: <https://github.com/NoamCohen48/tap/releases>
- Maven: `https://maven.pkg.github.com/NoamCohen48/tap`; reading requires `read:packages`.
  The downloadable Maven repository and bundles need no GitHub token.
- Python wheels install directly from the shared release. PyPI publication is opt-in.

## Checking what you have

```bash
tap version                      # tap daemon 0.1.0
python -c "import tap_e2e; print(tap_e2e.__version__)"
tap-agent --version
tap-studio --version
tap-watcher --version
```

`TapClient.create().info()` returns the server version, device protocol version and whether
a driver is bundled. See [Building and testing](../development/building-and-testing.md) for
maintainer checks.
