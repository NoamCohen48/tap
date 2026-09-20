# Versioning, CI and releases

Status: implemented 2026-09-19 (`.github/workflows/ci.yml`, `.github/workflows/release.yml`).
The first tagged release has not been cut yet; CI has not run on GitHub yet either, so
expect the first runs to need small runner-specific fixes (emulator boot flags, macOS native
image).

## What is an artifact, and why

The repository builds four things people install, each on its own version line:

| Family | Tag | Artifacts | Version source |
|---|---|---|---|
| **engine** (`service`) | `service/vX.Y.Z` | `tap` native binary (linux-x86_64, macos-aarch64) and JVM dist on a GitHub Release; Maven `com.company.tap:tap-api` (generated `tap.v1` stubs) to GitHub Packages | `gradle.properties` `tap.version.engine` |
| **Kotlin client** | `client-kotlin/vX.Y.Z` | Maven `com.company.tap:tap-client`, `com.company.tap:tap-junit5` | `gradle.properties` `tap.version.client.kotlin` |
| **Python client** | `client-python/vX.Y.Z` | `tap-e2e` wheel + sdist on a GitHub Release (PyPI opt-in) | `clients/python/pyproject.toml` |
| **sync-sdk** | `sync-sdk/vX.Y.Z` | Maven `com.company.tap:tap-sync-sdk` (AAR) | `gradle.properties` `tap.version.sync-sdk` |

### The host service and the driver are one artifact (the engine)

The driver APKs (`device/driver`) are not published on their own. They ride inside the
service (`host/service` bundles `driver.apk`/`driver-test.apk` as resources and installs them
per device) and they share one version with it:

- The service ↔ driver contract (TAP1, `contracts/protocol`) is internal and changes with
  both sides at once. A separately versioned driver would create a compatibility matrix that
  nobody needs: no user ever picks a driver version, `tap serve` installs the one it was built
  with, and the handshake's `HOST_BUILD_ID`/`DRIVER_APK_BUILD_ID` (both = `ENGINE_VERSION`)
  already refuse a mismatch.
- The TAP1 protocol version (`1.0`) remains the *wire* compatibility statement inside the
  handshake; the engine version is the *release* identifier. They move independently: many
  engine releases per protocol version.
- `contracts/protocol` is therefore not published to Maven either; it is compiled into the
  service and the driver. Only `contracts/api` (`tap.v1`, the surface clients speak) is
  published, at the engine version, because clients depend on it. It also generates the
  grpc-kotlin coroutine stubs, so it carries `grpc-kotlin-stub` and `kotlinx-coroutines-core`
  as `api` dependencies: grpc-kotlin 1.5.0 with grpc-java 1.75.0 (pinned in
  `contracts/api/build.gradle.kts`; grpc-kotlin releases lag grpc-java, so the pair is
  chosen deliberately, not bumped with the rest).

The one on-device piece that *is* published separately is `sync-sdk`: it goes into the app
under test's build, is chosen by the app team, changes rarely, and its contract with the
driver is the content-provider protocol, not TAP1. Coupling it to engine releases would force
app rebuilds for every service fix.

### Clients own their versions

`tap-client`/`tap-junit5` and `tap-e2e` each release independently: a client fix should not
require a service rollout and vice versa. Each client release records which engine it was
built against (the `tap-api` version in the Kotlin POM; the Python release notes and the
committed `_gen` stubs), and the runtime check is `Info().service_version` /
`protocol_version` from the service. `tap.v1` is the API compatibility contract: within it,
fields are only added (`buf breaking` in CI enforces wire compatibility), so a newer client
against an older service degrades to "unknown field ignored" rather than breaking, and an
older client against a newer service keeps working. A `tap.v2` package would be a new
`contracts/api` module.

Because `tap-client` depends on `tap-api:<engine>`, a `client-kotlin/v*` release checks that
the `tap-api` of the current engine version is already in GitHub Packages and fails with a
pointer to tag `service/v<engine>` first.

## Versions in the build

`gradle.properties` holds the three Gradle-side versions; the root `build.gradle.kts` assigns
each module the version of its family (`clients/kotlin/*` and `samples` → Kotlin client,
`device/sync-sdk` → sync-sdk, everything else → engine) and the group `com.company.tap`.
From the engine version:

- `:contracts:protocol` generates `EngineVersion.kt` (`ENGINE_VERSION`), which is what
  `HOST_BUILD_ID`, `DRIVER_APK_BUILD_ID`, `DRIVER_TEST_APK_BUILD_ID` and `SERVICE_VERSION`
  (`tap version`, `Info()`, `service.json`) are.
- `:device:driver` gets `versionName = ENGINE_VERSION` and `versionCode = major*10000 +
  minor*100 + patch`.
- The JVM distribution is `tap-<engine>.zip`.

The Python package reports `tap.__version__` from its installed distribution metadata.

Bumping: edit the property (or `pyproject.toml`), commit, then tag `<family>/v<version>` on
that commit. `.github/scripts/release_version.py` refuses a tag whose version differs from
the committed one. Pre-release suffixes (`1.2.0-rc.1`) are accepted.

## CI (`ci.yml`, on push to `main`, pull requests, manual)

| Job | Proves |
|---|---|
| `api-contract` | `buf lint` on `contracts/api` (STANDARD minus the naming rules the file deliberately breaks, see `contracts/api/buf.yaml`); `buf breaking` (`WIRE_JSON`) against the PR base / previous push, so removing, renumbering or retyping a field fails; `gen_stubs.py --check` with the pinned `grpcio-tools`, so the committed Python stubs match the proto. |
| `jvm` | Unit tests (`:contracts:protocol`, `:host:core`, `:host:service` — enum mirror + golden round trip —, `:device:driver:command-engine`, both Kotlin client modules); assembles driver APKs, fixture, service dist/zip and validation executable; `publishToMavenLocal` for the four Maven artifacts (POMs resolve); `tap version` equals `tap.version.engine`. Uploads the JVM dist. |
| `python` | Install + wheel build on 3.10 and 3.13, `tap.__version__`, pytest collection of the sample suite. Uploads the wheel. |
| `device-tests` | `reactivecircus/android-emulator-runner` API 34 x86_64: `:samples:fixture-tests:test -Ptap.serials=emulator-5554` and the Python sample suite through a service each suite starts and stops (`tap.manageService` / `TAP_MANAGE_SERVICE`) (one serial, so two-device tests are skipped). Failure artifacts are uploaded. |
| `native-image` | (push to `main` only) GraalVM 21 `nativeCompile` + `tap version` smoke; catches missing reflection metadata before a release. |

Not in CI, still local: `host --no-reboot`/full validation flow (needs the two-device local
matrix and reboots), the Samsung API 29 lane.

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
tag, with `SHA256SUMS` for the service. PyPI publishing is wired (trusted publishing) but off
until the repository variable `PUBLISH_TO_PYPI=true` is set and the project is registered on
PyPI.

Consuming:

```kotlin
repositories { maven("https://maven.pkg.github.com/NoamCohen48/tap") { credentials { … } } }
testImplementation("com.company.tap:tap-junit5:0.1.0")
```

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.1.0/tap_e2e-0.1.0-py3-none-any.whl
curl -L -o tap https://github.com/NoamCohen48/tap/releases/download/service/v0.1.0/tap-0.1.0-linux-x86_64 && chmod +x tap
```

## Open points

- Group id `com.company.tap` is the placeholder used by the source packages; rename both
  together before anything external depends on it.
- Maven Central / PyPI instead of GitHub Packages / Release assets once the repository is
  public: Central needs a verified namespace and signing; PyPI needs the trusted-publisher
  registration mentioned above. The workflows are structured so only the publish steps change.
- Windows native binary: not built; the JVM dist works there.
- Version skew check at run time (client refuses a service whose engine is older than the
  `tap-api` it was built with): `Info()` has the data; the check is not implemented.
