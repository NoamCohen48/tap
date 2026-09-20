# Releases and versions

Tap ships as four independently versioned artifact families. Each has its own tag prefix; a
push of the tag builds and publishes that family from the tagged commit.

| Family | Tag | Artifacts | Where |
|---|---|---|---|
| **Service** (engine: the `tap` executable + bundled driver + `tap-api`) | `service/vX.Y.Z` | `tap-X.Y.Z-linux-x86_64`, `tap-X.Y.Z-macos-aarch64`, `tap-X.Y.Z-jvm.zip`; Maven `com.company.tap:tap-api:X.Y.Z` | GitHub Release; GitHub Packages |
| **Kotlin client** | `client-kotlin/vX.Y.Z` | Maven `com.company.tap:tap-client`, `com.company.tap:tap-junit5` | GitHub Packages |
| **Python client** | `client-python/vX.Y.Z` | `tap_e2e-X.Y.Z-py3-none-any.whl`, sdist | GitHub Release (PyPI when enabled) |
| **sync-sdk** | `sync-sdk/vX.Y.Z` | Maven `com.company.tap:tap-sync-sdk` (AAR) | GitHub Packages |

The service and the on-device driver are **one** artifact: the driver APKs are bundled inside
the service binary and installed by it, and both report the same *engine version* (`tap
version`, `ENGINE_VERSION`, the driver's `versionName`). They are never mixed.

## Compatibility

- A client release states the engine version it was built against (`tap-client` depends on
  `tap-api` of that engine version; the Python release notes name it). Clients speak `tap.v1`,
  which evolves additively: newer services accept older clients, and a client that uses a
  field the service does not know gets a clear `UNSUPPORTED`/`INVALID_ARGUMENT` rather than
  silent misbehaviour.
- The device protocol between service and driver is internal to the engine; you never see it.
- Before 1.0, minor versions may change the client API; patch versions do not.

## Where things are published

- Binaries and wheels: <https://github.com/NoamCohen48/tap/releases>
- Maven: `https://maven.pkg.github.com/NoamCohen48/tap` (GitHub Packages; reading needs a
  token with `read:packages`, see [Getting started](../guide/getting-started.md#2-kotlin-junit-5)).

## Checking what you have

```bash
tap version                      # tap service 0.1.0
python -c "import tap; print(tap.__version__)"
```

In Kotlin, `TapClient().info()` (Python `Service().info()`) returns the service version, the
protocol version and whether a driver is bundled.
