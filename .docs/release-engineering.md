# Versioning, CI and releases

Status: implemented 2026-09-19 (`.github/workflows/ci.yml`, `.github/workflows/release.yml`).
The first release is **0.0.1 (alpha)** for every family (2026-09-28); the earlier 0.1.0–0.3.0
numbers were never published and were reset to it. `sync-sdk` is experimental and is not
tagged yet. Stability promises and the experimental list are public in
`docs/reference/releases.md`; `CHANGELOG.md` records each release.

## What is an artifact, and why

The repository builds five artifact families, each on its own version line:

| Family | Tag | Artifacts | Version source |
|---|---|---|---|
| **engine** (`server`) | `daemon/vX.Y.Z` | `tap` native binary (linux-x86_64, macos-aarch64) and JVM dist on a GitHub Release; Maven `io.github.noamcohen48.tap:tap-schema` (generated protobuf-lite messages of `contracts/proto`) and `tap-api` (the `tap.v1` gRPC stubs, depending on `tap-schema`) to GitHub Packages | `gradle.properties` `tap.version.engine` |
| **Kotlin client** | `client-kotlin/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-client`, `io.github.noamcohen48.tap:tap-junit5` | `gradle.properties` `tap.version.client.kotlin` |
| **Python client** | `client-python/vX.Y.Z` | `tap-e2e` wheel + sdist on a GitHub Release (PyPI opt-in) | `clients/python/pyproject.toml` |
| **Agent tools** | `client-agent/vX.Y.Z` | `tap-agent` wheel + sdist (CLI + MCP server, depends on `tap-e2e`) on a GitHub Release (PyPI opt-in, after `tap-e2e`) | `clients/agent/pyproject.toml` |
| **sync-sdk** | `sync-sdk/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-sync-sdk` (AAR) | `gradle.properties` `tap.version.sync-sdk` |

### The host daemon and the driver are one artifact (the engine)

The driver APKs (`device/driver`) are not published on their own. They ride inside the
server (`host/daemon` bundles `driver.apk`/`driver-test.apk` as resources and installs them
per device) and they share one version with it:

- The server ↔ driver contract (TAP1, `contracts/protocol`) is internal and changes with
  both sides at once. A separately versioned driver would create a compatibility matrix that
  nobody needs: no user ever picks a driver version, `tap serve` installs the one it was built
  with, and the handshake's `HOST_BUILD_ID`/`DRIVER_APK_BUILD_ID` (both = `ENGINE_VERSION`)
  already refuse a mismatch.
- The TAP1 protocol version (`4.0`) remains the *wire* compatibility statement inside the
  handshake; the engine version is the *release* identifier. They move independently: many
  engine releases per protocol version.
- `contracts/protocol` is therefore not published to Maven either; it is compiled into the
  server and the driver. Only `contracts/schema` (the lite messages of `contracts/proto`;
  clients use the `tap.v1` ones, `tap.wire.v1` rides along unused) and `contracts/api` (the
  `tap.v1` service stubs) are published, at the engine version, because clients depend on
  them. Both use protobuf-javalite, the one runtime the Android driver can share with the
  host. `contracts/api` also generates the
  grpc-kotlin coroutine stubs, so it carries `grpc-kotlin-stub`, `kotlinx-coroutines-core`
  and `kotlin-stdlib` at compile scope in the published POM: grpc-kotlin 1.5.0 with grpc-java
  1.75.0 (pinned in `contracts/api/build.gradle.kts`; grpc-kotlin releases lag grpc-java, so
  the pair is chosen deliberately, not bumped with the rest). Coroutines are in the artifact's
  ABI — `Attach` returns a `Flow` — so they cannot be an implementation dependency.

The one on-device piece that *is* published separately is `sync-sdk`: it goes into the app
under test's build, is chosen by the app team, changes rarely, and its contract with the
driver is the content-provider protocol, not TAP1. Coupling it to engine releases would force
app rebuilds for every server fix.

### Clients own their versions

`tap-client`/`tap-junit5` and `tap-e2e` each release independently: a client fix should not
require a server rollout and vice versa. Each client release records which engine it was
built against (the `tap-api` version in the Kotlin POM; the Python release notes and the
committed `_gen` stubs), and the runtime check is `Info().daemon_version` /
`protocol_version` from the server. `tap.v1` is the API compatibility contract: within it,
fields are only added (`buf breaking` in CI enforces wire compatibility), so a newer client
against an older server degrades to "unknown field ignored" rather than breaking, and an
older client against a newer server keeps working. A `tap.v2` package would be new
files under `contracts/proto`.

Because `tap-client` depends on `tap-api:<engine>`, a `client-kotlin/v*` release checks that
the `tap-api` of the current engine version is already in GitHub Packages and fails with a
pointer to tag `daemon/v<engine>` first.

History from before the reset to 0.0.1 (none of these versions were published): client 0.2.0
(2026-09-21) was the breaking coroutine release: every `Device`/`App`/`Element`
call is `suspend` behind `tapTest`/`tapScope`, `TapConnection` owns its attach scope, and
`TapClient`/`TapConnection`/`Device` close via `suspend` (no `AutoCloseable`). The wire is
unchanged (`tap.v1` only gains no fields here), so the break is source-only: 0.1.x callers
recompile against `tapTest`/`tapScope`. Engine and Python/sync lines did not move.

## Versions in the build

`gradle.properties` holds the three Gradle-side versions; the root `build.gradle.kts` assigns
each module the version of its family (`clients/kotlin/*` and `samples` → Kotlin client,
`device/sync-sdk` → sync-sdk, everything else → engine) and the group `io.github.noamcohen48.tap`.
From the engine version:

- `:contracts:protocol` generates `EngineVersion.kt` (`ENGINE_VERSION`), which is what
  `HOST_BUILD_ID`, `DRIVER_APK_BUILD_ID`, `DRIVER_TEST_APK_BUILD_ID` and `DAEMON_VERSION`
  (`tap version`, `Info()`, `daemon.json`) are.
- `:device:driver` gets `versionName = ENGINE_VERSION` and `versionCode = major*10000 +
  minor*100 + patch`.
- The JVM distribution is `tap-<engine>.zip`.

The Python packages report `tap_e2e.__version__` / `tap_agent.__version__` from its installed distribution metadata.

Bumping: edit the property (or `pyproject.toml`), commit, then tag `<family>/v<version>` on
that commit. `.github/scripts/release_version.py` refuses a tag whose version differs from
the committed one. Pre-release suffixes (`1.2.0-rc.1`) are accepted.

## CI (`ci.yml`, on push to `main`, pull requests, manual)

| Job | Proves |
|---|---|
| `api-contract` | `buf lint` on `contracts/proto` (STANDARD minus the naming rules the file deliberately breaks, see `contracts/proto/buf.yaml`); `buf breaking` (`WIRE_JSON`) against the PR base / previous push, so removing, renumbering or retyping a field fails; `gen_stubs.py --check` with the pinned `grpcio-tools`, so the committed Python stubs match the proto. |
| `jvm` | Unit tests (`:contracts:protocol`, `:host:core`, `:host:daemon`, `:device:driver:command-engine`, both Kotlin client modules); assembles driver APKs, fixture, daemon dist/zip and validation executable; `publishToMavenLocal` for the five Maven artifacts (POMs resolve); `tap version` equals `tap.version.engine`. Uploads the JVM dist. |
| `python` | On 3.10 and 3.13: installs `tap-e2e` and `tap-agent`, runs their unit tests over the in-process fake daemon (`clients/python/tests/unit`, `clients/agent/tests`), builds both wheels, `tap-agent --help`. Uploads the wheels (`python-dists`). |
| `device-tests` | `reactivecircus/android-emulator-runner` API 34 x86_64 running `.github/scripts/device-tests.sh` (the action runs each `script` line as its own `sh -c`, so the lane is one script): `:samples:fixture-tests:test -Ptap.serials=emulator-5554` and the Python sample suite through a server each suite starts and stops (`tap.manageDaemon` / `TAP_MANAGE_DAEMON`), then `.github/scripts/agent-smoke.sh` — a `tap-agent` session (attach, install, cold launch, snapshot, tap by ref with `--settle`, wait, export) whose JSON export is checked (install and cold launch first, the ref logged as its `view_button` selector, every call ok). One serial, so two-device tests are skipped. Failure artifacts (incl. `build/agent-smoke`) are uploaded. |
| `native-image` | (push to `main` only) GraalVM 21 `nativeCompile`, then `tap start` and `.github/scripts/daemon_smoke.py`: every RPC that needs no device (Info, ListDevices, held Connect, ListConnections, Events, Disconnect, an observed connection) on the native binary, which catches missing reflection metadata before a release. |

Not in CI, still local: `:host:validation:deviceTest` (needs the two-device local matrix; the
`reboot`-tagged scenario reboots), the Samsung API 29 lane, and a native-image run against a
device (the emulator lane uses the JVM dist).

## Docs (`docs.yml`)

Runs on changes to `docs/`, `mkdocs.yml`, the clients or the API proto: `scripts/build-docs.sh`
(Dokka → `docs/reference/kotlin/`, protoc-gen-doc → `docs/reference/grpc.md`, mkdocstrings at
build time, `mkdocs build --strict`; then Dokka GFM + lazydocs into `build/docs-md/`) and
uploads `build/site` as the `site` artifact and `tap-docs-md.zip` as `docs-md`. Deploy to
GitHub Pages is gated on the `DEPLOY_DOCS=true` repository variable (Pages must be enabled with
"GitHub Actions" as the source; on a private repository it needs a plan that allows private
Pages, otherwise the artifact is the deliverable).

## Releases (`release.yml`, on tags)

`resolve` maps the tag to a family and checks the version; then one job set per family (see
the table above). Maven goes to this repository's GitHub Packages registry
(`https://maven.pkg.github.com/NoamCohen48/tap`; readers need a token with `read:packages`
even for public repositories). Binaries and wheels go to a GitHub Release named after the
tag, with `SHA256SUMS` for the server. PyPI publishing is wired (trusted publishing) but off
until the repository variable `PUBLISH_TO_PYPI=true` is set and the project is registered on
PyPI. `tap-agent` depends on `tap-e2e`, so until both are on PyPI its release notes say to
install the `tap-e2e` wheel first.

Consuming:

```kotlin
repositories { maven("https://maven.pkg.github.com/NoamCohen48/tap") { credentials { … } } }
testImplementation("io.github.noamcohen48.tap:tap-junit5:0.0.1")
```

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.1/tap_e2e-0.0.1-py3-none-any.whl
pip install https://github.com/NoamCohen48/tap/releases/download/client-agent/v0.0.1/tap_agent-0.0.1-py3-none-any.whl
curl -L -o tap https://github.com/NoamCohen48/tap/releases/download/daemon/v0.0.1/tap-0.0.1-linux-x86_64 && chmod +x tap
```

## Open points

- Group id and packages are `io.github.noamcohen48.tap` (decided in the 2026-09-26 review, X-4);
  changing it after a release breaks every consumer.
- License: none chosen yet (`pyproject.toml` says Proprietary, there is no `LICENSE` file).
  Decide before the repository or the artifacts go public.
- A published version is never replaced (GitHub Packages refuses a republish, PyPI never
  allows one, and a replaced GitHub Release asset breaks caches and `SHA256SUMS`): a fix is
  a new patch release. Test the pipeline with a pre-release (`X.Y.Z-rc.N`) when in doubt.
- Downgrading the engine on a device that has a newer driver is handled: the driver is
  uninstalled and reinstalled on `INSTALL_FAILED_VERSION_DOWNGRADE` (as on a signature
  mismatch).
- Maven Central / PyPI instead of GitHub Packages / Release assets once the repository is
  public: Central needs a verified namespace and signing; PyPI needs the trusted-publisher
  registration mentioned above. The workflows are structured so only the publish steps change.
- Windows native binary: not built; the JVM dist works there.
- Version skew check at run time (client refuses a server whose engine is older than the
  `tap-api` it was built with): `Info()` has the data; the check is not implemented.
