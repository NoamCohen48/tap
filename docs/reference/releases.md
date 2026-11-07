# Releases and versions

Tap ships as six independently versioned artifact families. Each has its own tag prefix; a
push of the tag builds and publishes that family from the tagged commit.

| Family | Tag | Artifacts | Where |
|---|---|---|---|
| **Server** (engine: the `tap` executable + bundled driver + `tap-api`) | `daemon/vX.Y.Z` | `tap-X.Y.Z-linux-x86_64`, `tap-X.Y.Z-macos-aarch64`, `tap-X.Y.Z-jvm.zip`; Maven `io.github.noamcohen48.tap:tap-api:X.Y.Z` | GitHub Release; GitHub Packages |
| **Kotlin client** | `client-kotlin/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-client`, `io.github.noamcohen48.tap:tap-junit5` | GitHub Packages |
| **Python client** | `client-python/vX.Y.Z` | `tap_e2e-X.Y.Z-py3-none-any.whl`, sdist | GitHub Release (PyPI when enabled) |
| **Agent tools** (experimental) | `client-agent/vX.Y.Z` | `tap_agent-X.Y.Z-py3-none-any.whl`, sdist (needs `tap-e2e`) | GitHub Release (PyPI when enabled) |
| **Tap Studio** (experimental, not released yet) | `client-studio/vX.Y.Z` | `tap_studio-X.Y.Z-py3-none-any.whl` (the page built in), sdist (needs `tap-e2e`) | GitHub Release (PyPI when enabled) |
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
  `tap-api` of that engine version; the Python release notes name it). Clients speak `tap.v1`,
  which evolves additively: newer servers accept older clients, and a client that uses a
  field the server does not know gets a clear `UNSUPPORTED`/`INVALID_ARGUMENT` rather than
  silent misbehaviour.
- The device protocol between server and driver is internal to the engine; you never see it.
- The device protocol version is reported by `Info()` next to the server version, for
  diagnostics only.

## Stability

Tap is in **alpha**: the first release was 0.0.1 for every family; 0.0.2 (2026-09-30) is the current one.

- **0.x (now):** any release may change the client API, the `tap.v1` API or the CLI. Release
  notes say what changed. The wire stays additive where it can (`buf breaking` runs in CI), so
  mixing a newer client with an older server usually still works, but it is not promised.
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
    - Tap Studio: `tap-studio`, its page and the `tap-recording/1` format;
    - the watcher: `tap-watcher`, its page, the `tap-watch-steps/2` file and the server's
      `WatchService` (`Watch`, `WatchVideo`).
    - App Explorer (development only, no release tag): `tap-explorer`, its Python graph API,
      the discovery workbench, the offline benchmark, robot drafts (`tap-robots/1`) and the
      `tap-exploration/1` metadata format. See the
      [explorer guide](../guide/explorer.md).
- A published version is never replaced: a fix is a new patch release.

## Where things are published

- Binaries and wheels: <https://github.com/NoamCohen48/tap/releases>
- Bundles (everything for one platform in one zip): the [Download](../download.md) page, files
  on the `bundle/v*` releases there
- Maven: `https://maven.pkg.github.com/NoamCohen48/tap` (GitHub Packages; reading needs a
  token with `read:packages`, see [Kotlin + JUnit 5](../sdk/kotlin.md#install)).

## Checking what you have

```bash
tap version                      # tap daemon 0.0.2
python -c "import tap_e2e; print(tap_e2e.__version__)"
```

`TapClient.create().info()` (Kotlin and Python) returns the server version, the
protocol version and whether a driver is bundled.
