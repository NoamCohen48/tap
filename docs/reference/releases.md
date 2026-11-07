# Releases and versions

Tap ships as seven independently versioned artifact families. Each has its own tag prefix; a
push of the tag builds and publishes that family from the tagged commit.

| Family | Tag | Artifacts | Where |
|---|---|---|---|
| **Server** (engine: the `tap` executable + bundled driver + `tap-api`) | `daemon/vX.Y.Z` | `tap-X.Y.Z-linux-x86_64`, `tap-X.Y.Z-macos-aarch64`, `tap-X.Y.Z-jvm.zip`; Maven `io.github.noamcohen48.tap:tap-api:X.Y.Z` | GitHub Release; GitHub Packages |
| **Kotlin client** | `client-kotlin/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-client`, `io.github.noamcohen48.tap:tap-junit5` | GitHub Packages |
| **Python client** | `client-python/vX.Y.Z` | `tap_e2e-X.Y.Z-py3-none-any.whl`, sdist | GitHub Release (PyPI when enabled) |
| **Agent tools** (experimental) | `client-agent/vX.Y.Z` | `tap_agent-X.Y.Z-py3-none-any.whl`, sdist (needs `tap-e2e`) | GitHub Release (PyPI when enabled) |
| **Tap Studio** (experimental) | `client-studio/vX.Y.Z` | `tap_studio-X.Y.Z-py3-none-any.whl` (the page built in), sdist (needs `tap-e2e`) | GitHub Release (PyPI when enabled) |
| **tap-watcher** (experimental, not released yet) | `client-watcher/vX.Y.Z` | `tap_watcher-X.Y.Z-py3-none-any.whl` (the page built in), sdist (needs `tap-e2e`) | GitHub Release (PyPI when enabled) |
| **sync-sdk** (experimental, not released yet) | `sync-sdk/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-sync-sdk` (AAR) | GitHub Packages |

A release set also ships as **bundles**, one download per platform: the release
`bundle/vX.Y.Z` carries `tap-X.Y.Z-linux-x86_64.zip`, `tap-X.Y.Z-macos-aarch64.zip` and
`tap-X.Y.Z-jvm.zip`. Each holds the server for that platform, the Kotlin artifacts as a local
Maven repository, the Python wheels (`tap-e2e`, `tap-agent`, `tap-studio`, `tap-watcher`), the docs,
`install.sh`, `INSTALL.md`, a `VERSIONS` file naming each family's version, and `SHA256SUMS`.
The files are the released ones, repackaged; the bundle version is the server's. The complete
documentation as Markdown (this guide, the Kotlin, Python and gRPC references, tap-agent and the
changelog) ships only in the bundles, not on each family's release. Get them from the
[Download](../download.md) page.

The server Maven release also carries `tap-schema` (the protobuf messages `tap-api` is built on).

The server and the on-device driver are **one** artifact: the driver APKs are bundled inside
the daemon binary and installed by it, and both report the same *engine version* (`tap
version`, `ENGINE_VERSION`, the driver's `versionName`). They are never mixed.

## Compatibility

- A client release states the engine version it was built against (`tap-client` depends on
  `tap-api` of that engine version; the Python bindings record it). Clients speak `tap.v1`
  and require a matching API compatibility epoch plus a server no older than their engine
  baseline. Deliberate 0.x API breaks increment the epoch, so upgrade the server and clients
  together across those breaks. Additive changes within an epoch keep the wire compatible.
- The device protocol between server and driver is internal to the engine; you never see it.
- The device protocol version is reported by `Info()` next to the server version, for
  diagnostics only.

## Stability

Tap is in **alpha**. The next release set is **0.1.0** for the server, Kotlin client,
Python client, tap-agent, Tap Studio and tap-watcher. Tags and artifacts are published
separately from version preparation. The unchanged, unreleased sync-sdk stays at 0.0.1.
Earlier published sets used 0.0.x versions.

- **0.x (now):** from 0.1.0, new features or breaking changes increment the minor version
  (0.1.0 → 0.2.0); fixes only increment the patch (0.1.0 → 0.1.1). Each family can still
  release independently. Any 0.x minor release may change the client API, the `tap.v1` API
  or the CLI; release notes say what changed. Clients require a matching API compatibility
  epoch and refuse servers older than the engine version they were built against.
- **From 1.0:** [semantic versioning](https://semver.org) per family. The client APIs (Kotlin
  `tap-client`/`tap-junit5`, Python `tap-e2e`), the `tap.v1` gRPC API and the `tap` CLI change
  incompatibly only in a new major version; `tap.v1` only ever gains fields, and a breaking
  server API would be a new `tap.v2` package served alongside it.
- **Experimental** parts are outside that promise and may change in any release:
    - app synchronization: `App.awaitIdle` / `await_idle`
      (Kotlin: `@ExperimentalTapApi`, opt in with `@OptIn(ExperimentalTapApi::class)`) and
      `tap-sync-sdk`;
    - the agent surface: `tap-agent`, held connections (`connect(..., hold=...)`, `resume`),
      screen snapshots and refs (`screen_snapshot`, `resolve_ref`), the event log
      (`event_log`, the `Events` RPC) and the `tap-events/1` export format;
    - Tap Studio: `tap-studio`, its page and the `tap-recording/2` format;
    - the watcher: `tap-watcher`, its page, the `tap-watch-steps/2` file and the server's
      `WatchService` (`Watch`, `WatchVideo`).
- A published version is never replaced: a fix is a new patch release.

## Where things are published

- Binaries and wheels: <https://github.com/NoamCohen48/tap/releases>
- Bundles (everything for one platform in one zip): the [Download](../download.md) page, files
  on the `bundle/v*` releases there
- Maven: `https://maven.pkg.github.com/NoamCohen48/tap` (GitHub Packages; reading needs a
  token with `read:packages`, see [Kotlin + JUnit 5](../sdk/kotlin.md#install)).

## Checking what you have

```bash
tap version                      # tap daemon 0.1.0
python -c "import tap_e2e; print(tap_e2e.__version__)"
```

`TapClient.create().info()` (Kotlin and Python) returns the server version, the
protocol version and whether a driver is bundled.
