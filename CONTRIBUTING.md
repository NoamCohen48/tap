# Contributing to Tap

Thanks for your interest in Tap. Bug reports, ideas, documentation fixes and code are all
welcome. This guide covers how the repository is organised, how to build and test it, and
what a change needs before it can be merged.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security
issues privately as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Ways to contribute

- **Report a bug.** Open an [issue](https://github.com/NoamCohen48/tap/issues/new/choose) with
  the Tap versions, the device (model, API level, emulator or physical), what you ran and what
  happened. The failure artifacts (`build/tap-artifacts/…` or `tap-artifacts/…`) and
  `tap status` output help a lot.
- **Suggest a feature.** Describe the test you are trying to write and what gets in the way.
  If another tool (Maestro, Appium, uiautomator2) already handles it, say how.
- **Improve the docs.** Guide pages live in `docs/guide/`; every page has an edit link on the
  site.
- **Send a pull request.** For anything beyond a small fix, open an issue first so we can agree
  on the approach.

## Repository layout

```
contracts/   the one protobuf schema (tap.v1 server API, tap.wire.v1 device payloads)
             :contracts:schema, :contracts:api (gRPC stubs), :contracts:protocol (TAP1 framing)
device/      :device:driver (instrumentation APKs), :device:driver:core, :device:driver:command-engine,
             :device:sync-sdk (optional library an app can ship; experimental)
host/        :host:core (ADB, sessions, journals, driver client, app lifecycle),
             :host:daemon (the `tap` server and CLI), :host:validation (device fault-injection suite)
clients/     :clients:kotlin:sdk, :clients:kotlin:junit5, clients/python (tap-e2e),
             clients/agent (tap-agent)
fixture-app/ the app the test suites run against
samples/     :samples:fixture-tests, the Kotlin client suite
docs/        the public documentation (MkDocs)
```

Layering rules:

- Clients depend only on `:contracts:api`.
- Nothing under `host/` depends on `clients/`.
- Product code never depends on `:host:validation`.
- ADB, journals, device locks, the driver and app lifecycle all live in `:host:core` and are
  reached through the server.

## Development setup

You need:

- the Android SDK with API 36 and build tools 36.0.0;
- a JDK 17 (Gradle finds one in `~/.gradle/jdks`, an OS install, or setup-java in CI, whatever
  `JAVA_HOME` points at);
- Python 3.10+ for the Python client and `tap-agent`;
- optionally, GraalVM 21 (`GRAALVM_HOME`) for the native `tap` binary;
- for device tests, one or more `adb`-visible devices or emulators with API 26+.

Dependency and plugin versions live in `gradle/libs.versions.toml`. Shared JVM module setup is
in the `build-logic/` convention plugins.

```bash
python -m venv .venv
.venv/bin/pip install -e "clients/python[dev]" -e clients/agent
```

## Building

```bash
./gradlew :host:daemon:installDist         # JVM `tap` in host/daemon/build/install/tap/bin
./gradlew :host:daemon:nativeCompile       # native `tap` in host/daemon/build/native/nativeCompile
./gradlew :device:driver:assembleDebug :device:driver:assembleAndroidTest :fixture-app:assembleDebug
```

## Testing

**Unit tests.** No device needed; run them before every pull request.

```bash
./gradlew :contracts:protocol:test :host:core:test :host:daemon:test \
  :device:driver:command-engine:test :device:driver:core:test
.venv/bin/pytest clients/agent/tests
```

**Client suites against devices.** These start the server, run the fixture tests on every
serial you pass (two-device tests are skipped with one), and stop the server again.

```bash
./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554[,SERIAL]

TAP_BIN=$PWD/host/daemon/build/native/nativeCompile/tap TAP_MANAGE_DAEMON=1 \
  TAP_SERIALS=emulator-5554[,SERIAL] .venv/bin/pytest clients/python/tests
```

**Device validation.** `:host:validation:deviceTest` is the fault-injection suite: recovery,
fencing, transport faults, cancellation, heartbeats, selectors, input, scrolling, permissions
and multi-device isolation, one class per scenario. Without `-Ptap.serials` every test is
skipped.

```bash
./gradlew :host:validation:deviceTest -Ptap.serials=emulator-5554[,SERIAL]
```

It uses the validation-flavor driver and fixture APKs by default; override them with
`-Ptap.driverApk=`, `-Ptap.driverTestApk=` and `-Ptap.fixtureApk=`. Journals and device locks
live in `$TAP_STATE_DIR/sessions` (default `~/.tap/sessions`).

> [!WARNING]
> `-Ptap.reboot=true` adds `LateMutationQuarantineTest`, which **reboots every device you
> pass**. Never use it on shared devices or for routine development. Without it, nothing is
> rebooted.

**Product probe.** `tap-product-probe` measures query latency and lists the accessibility
inventory of screens in any installed app, without adding app-specific logic to Tap. Each
final argument is `tap text|ready text|screen name` (`-` for the first screen):

```bash
./gradlew :host:validation:installDist
host/validation/build/install/tap-product-probe/bin/tap-product-probe emulator-5554 \
  device/driver/build/outputs/apk/product/debug/driver-product-debug.apk \
  device/driver/build/outputs/apk/androidTest/product/debug/driver-product-debug-androidTest.apk \
  /path/to/app.apk com.example.app .MainActivity '-|Home|home' 'Settings|Appearance|settings'
```

## Making changes

- **Invariants.** The driver is a separate package from the app. Selectors never use XPath or
  a hierarchy dump on the hot path. No element handles are kept between commands. Mutations
  need exactly one match. A transmitted mutation is never replayed. Every ADB call names its
  serial (`-s`). A change that weakens any of these will not be merged.
- **The driver assumes nothing about the app.** It performs the action and reports what
  happened; tests assert the effect. Discuss driver behaviour changes in an issue first.
- **Protocol changes.** `contracts/proto/**/*.proto` is the only schema. Only add fields and
  values; never remove, renumber or retype them (`buf breaking` enforces this in CI). After an
  edit, run `:contracts:protocol:test` and `:host:daemon:test`, regenerate the Python stubs
  with `clients/python/scripts/gen_stubs.py` and update the gRPC docs.
- **Public API.** Every public Kotlin and Python symbol needs KDoc or a docstring; the API
  reference is generated from them. Update the guide in `docs/` with any user-visible change,
  and add a line under `## Unreleased` in `CHANGELOG.md`.
- **Borrowed code.** Tap learns from Appium UiAutomator2, Maestro, uiautomator2 and AndroidX.
  Anything copied or closely adapted must be recorded in `THIRD_PARTY_NOTICES.md` with the
  repository, commit, paths, license and what was changed.
- **Native image.** If a new dependency uses reflection, re-record the configuration in
  `host/daemon/src/main/resources/META-INF/native-image` with the GraalVM tracing agent.

## Documentation

`docs/` is the public site (MkDocs Material). `scripts/build-docs.sh` generates the Kotlin
(Dokka), Python and gRPC references, builds the site into `build/site` and a Markdown edition
into `build/docs-md`. It needs JDK 17, `buf`, `protoc-gen-doc` and
`pip install -r docs/requirements.txt -e "clients/python[dev]"`. To preview the guide alone, run
`mkdocs serve`.

## Pull requests

- Keep a pull request to one topic, with tests for the behaviour it adds or fixes.
- Write commit messages in the imperative, with a short subject line and a body that explains
  why.
- CI must pass: proto lint and breaking-change check, stub drift, JVM and Python unit tests,
  the native image, and both client suites on an API 34 emulator.
- By contributing, you agree that your contribution is licensed under the
  [Apache License 2.0](LICENSE).

## Releases

Tap has five independently versioned artifact families: the engine (`tap` server with the
bundled driver, plus `tap-schema`/`tap-api`), the Kotlin client, the Python client, `tap-agent`
and `sync-sdk`. Versions are set in `gradle.properties` and the two `pyproject.toml` files.
Pushing a tag such as `daemon/v0.0.2` on a commit with the matching version runs
`.github/workflows/release.yml`, which publishes to GitHub Packages and GitHub Releases. See
[Releases and versions](docs/reference/releases.md).
