# Building and testing

## Setup

You need:

- the Android SDK with API 36 and build tools 36.0.0;
- a JDK 17. Gradle finds one in `~/.gradle/jdks`, an OS install or setup-java in CI, whatever
  `JAVA_HOME` points at;
- Python 3.10+ for the Python client, `tap-agent` and `tap-studio`;
- [Bun](https://bun.sh) 1.3 for the `tap-studio` page;
- optionally, GraalVM 21 (`GRAALVM_HOME`) for the native `tap` binary;
- for device tests, one or more devices or emulators with API 26+ visible to `adb`.

Dependency and plugin versions live in `gradle/libs.versions.toml`, and the shared JVM module
setup in the `build-logic/` convention plugins.

```bash
git clone https://github.com/NoamCohen48/tap.git && cd tap
python -m venv .venv
.venv/bin/pip install -e "clients/python[dev]" -e clients/agent -e "clients/studio[dev]"
(cd clients/studio/web && bun install && bun run build)   # the tap-studio page
```

## Building

```bash
./gradlew :host:daemon:installDist     # JVM server: host/daemon/build/install/tap/bin/tap
./gradlew :host:daemon:nativeCompile   # native server: host/daemon/build/native/nativeCompile/tap
./gradlew :device:driver:assembleDebug :device:driver:assembleAndroidTest :fixture-app:assembleDebug
```

The server bundles the driver APKs, so a server you build yourself installs the matching
driver on each device the first time a session opens.

## Unit tests

No device needed. Run them before every pull request:

```bash
./gradlew :contracts:protocol:test :host:core:test :host:daemon:test \
  :device:driver:command-engine:test :device:driver:core:test
.venv/bin/pytest clients/agent/tests clients/studio/tests
(cd clients/studio/web && bun run test)
```

## Client suites

Both suites run against real devices. They start a server, run the fixture tests on every
serial you pass (two-device tests are skipped with one serial), and stop the server again:

```bash
./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554

TAP_BIN=$PWD/host/daemon/build/native/nativeCompile/tap TAP_MANAGE_DAEMON=1 \
  TAP_SERIALS=emulator-5554 .venv/bin/pytest clients/python/tests
```

## Device validation

`:host:validation:deviceTest` is the fault-injection suite, one class per scenario: recovery,
fencing, transport faults, cancellation, heartbeats, selectors, input, scrolling, permissions
and multi-device isolation. Without `-Ptap.serials` every test is skipped.

```bash
./gradlew :host:validation:deviceTest -Ptap.serials=emulator-5554
```

It uses the validation build of the driver (which adds fault injection) and the fixture app by
default; override them with `-Ptap.driverApk=`, `-Ptap.driverTestApk=` and `-Ptap.fixtureApk=`.
Journals and device locks live in `~/.tap/sessions` (or `$TAP_STATE_DIR/sessions`).

!!! danger "Reboots"

    `-Ptap.reboot=true` adds `LateMutationQuarantineTest`, which **reboots every device you
    pass**. Never use it on shared devices or for routine development. Without it, nothing is
    rebooted.

## Product probe

`tap-product-probe` measures query latency and lists the accessibility inventory of screens in
any installed app, without adding app-specific code to Tap. Each final argument is
`tap text|ready text|screen name`, with `-` for the first screen:

```bash
./gradlew :host:validation:installDist
host/validation/build/install/tap-product-probe/bin/tap-product-probe emulator-5554 \
  device/driver/build/outputs/apk/product/debug/driver-product-debug.apk \
  device/driver/build/outputs/apk/androidTest/product/debug/driver-product-debug-androidTest.apk \
  /path/to/app.apk com.example.app .MainActivity '-|Home|home' 'Settings|Appearance|settings'
```

## Changing the protocol

`contracts/proto/**/*.proto` is the only schema. After editing it:

1. run `./gradlew :contracts:protocol:test :host:daemon:test` (the wire golden bytes and the
   server tests);
2. regenerate the committed Python stubs with `clients/python/scripts/gen_stubs.py`;
3. update the guide and reference pages that describe the change.

Only add fields and values. CI runs `buf lint` and `buf breaking` and fails on anything else.

## Native image

`GRAALVM_HOME=/path/to/graalvm-21 ./gradlew :host:daemon:nativeCompile` builds the native
server. If a new dependency uses reflection, re-record the configuration in
`host/daemon/src/main/resources/META-INF/native-image` by running the JVM server with the
GraalVM tracing agent
(`JAVA_OPTS=-agentlib:native-image-agent=config-output-dir=...`) through a smoke flow.

## Documentation

`docs/` is this site (MkDocs Material). `scripts/build-docs.sh` generates the Kotlin (Dokka),
Python and gRPC references, builds the site into `build/site`, and a Markdown edition into
`build/docs-md`. It needs JDK 17, `buf`, `protoc-gen-doc` and
`pip install -r docs/requirements.txt -e "clients/python[dev]"`. `mkdocs serve` previews the
guide alone.

## Continuous integration and releases

CI (`.github/workflows/ci.yml`) runs the proto checks, the JVM and Python unit tests, the
native image, and both client suites plus the `tap-agent` and `tap-studio` smokes on an API 34 emulator. Tap has six independently
versioned artifact families: the engine (the `tap` server with the bundled driver, plus
`tap-schema`/`tap-api`), the Kotlin client, the Python client, `tap-agent`, `tap-studio` and
`sync-sdk`. Versions are set in `gradle.properties` and the clients' `pyproject.toml` files; pushing a tag such
as `daemon/v0.0.2` on a commit with the matching version publishes that family. See
[Releases and versions](../reference/releases.md).
