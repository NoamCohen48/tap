<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-lockup-dark.svg">
    <img src="docs/assets/logo-lockup-light.svg" alt="Tap" width="220" height="70">
  </picture>
</p>

# Tap

Host-driven Android E2E framework in three parts: an on-device driver (`device/`), one host
server per machine (`host/`, `tap start`: ADB, driver lifecycle, sessions, device list; gRPC
over loopback, JVM or native image) and thin language clients (`clients/`: Kotlin SDK +
JUnit 5, Python + pytest; `clients/agent`: `tap-agent`, a CLI and MCP server for coding
agents). `contracts/` holds what they agree on (the TAP1 device protocol and the `tap.v1`
server API).

Status: **alpha, 0.0.1** — the first release of every artifact family. Any 0.x release may
change the API; app synchronization (`sync-sdk`, `awaitIdle`) and the agent surface
(`tap-agent`, held connections, snapshots, the event log) are experimental on top of that
(`docs/reference/releases.md`). What is still missing is listed in
[`.docs/framework-gaps.md`](.docs/framework-gaps.md).

User documentation lives in [`docs/`](docs/index.md) (guide + generated Kotlin/Python/gRPC
references); build the site with `scripts/build-docs.sh` (see [Docs](#docs)).

## Writing tests

Add `:clients:kotlin:junit5` to a JUnit 5 test source set and annotate the class with `@TapTest`:

```kotlin
@TapTest
class CheckoutTest {
    @Test
    fun buysAnItem(device: Device) {
        tapTest {
            val app = device.app()                    // the configured AUT package
            app.clearData()
            app.launch()                              // resolves the launcher activity, waits for the window
            device.element(res("buy_button")).tap()  // <aut>:id/buy_button
            device.await(text("Order placed")).visible()
        }
    }

    @Test
    @TapDevices("sender", "receiver")
    fun messagePropagates(devices: Devices) {
        tapTest {
            coroutineScope {
                awaitAll(
                    async {
                        devices["sender"].element(rawRes("sendButton")).tap()
                    },
                    async {
                        devices["receiver"].await(text("hello"), timeout = 20.seconds).visible()
                    },
                )
            }
        }
    }
}
```

(`awaitAll` is `kotlinx.coroutines.awaitAll`; `coroutineScope` + `async` is the same shape
`samples/fixture-tests` compiles and runs — see `MultiDeviceTest`.)

Every test body runs inside `tapTest { ... }` (block- or expression-bodied test methods both
work: `tapTest` returns `Unit`): the real-time `runBlocking`-style bridge onto
the extension-owned per-test coroutine scope (root job + deadline). Suspending calls outside
it — or from a coroutine that does not inherit it (`GlobalScope`) — fail with
`TapUsageException`, so a JUnit timeout and a failing sibling both cancel the other device's
in-flight RPC. `DeviceBarrier(parties)` coordinates genuinely simultaneous phases; backend
propagation still uses the observing device's UI condition, not a barrier.

- `device.element(selector)` is lazy; every action resolves the selector again on the device
  and requires exactly one match (`CommandException` with `AMBIGUOUS`/`NOT_FOUND`
  otherwise). Use `resId`, `rawRes` (Compose `testTag`), `text*`, `desc`, `hint`,
  `className`, refinements (`.clickable()`, `.andText(...)`), relations
  (`.hasDescendant(...)`, `.child(...)`) and `.first()`/`.at(n)`.
- `device.await(selector).visible()/gone()` and `device.awaitAppVisible(pkg)` wait on the
  device; `.enabled()/.textEquals()/.count(n)` poll from the host; timeouts throw
  `WaitTimeoutException` with elapsed time and polls.
- `device.awaitAppSettled()` (accessibility hierarchy quiet, no screenshots — Maestro's
  `waitForAppToSettle`), `device.awaitAnimationEnd()` (window pixels quiet — Maestro's
  `waitForAnimationToEnd`) and `device.awaitScreenStable()` (both) wait, on request only, until
  the AUT's window has stopped changing for `stableFor` (default 500 ms). Nothing waits for a
  quiet screen implicitly, so actions stay fast on busy screens.
- `device.app(pkg)` handles install/launch/coldLaunch/forceStop/clearData/grantPermission and
  `awaitIdle()` for apps that ship `sync-sdk`.
- `device.capture()` takes a screenshot, the hierarchy XML, device info and the driver log at
  once; on failure the extension saves it for every device under
  `tap.artifactsDir/<class>/<method>/` (`tap.capture=off` turns that off).

The extension is a gRPC client of the `tap` server, the per-machine host daemon that owns ADB,
driver lifecycle (the driver APKs are bundled in it), journals, device locks and the device list
(`.docs/server-api.md`). The server is started explicitly — `tap start` — and shared by every
test process on the machine until `tap stop`; a test run can also be told to start and stop it
itself. Configuration is read from system properties or environment variables:

| Property | Env | Meaning |
|---|---|---|
| `tap.autPackage` | `TAP_AUT_PACKAGE` | application under test (required) |
| `tap.serials` | `TAP_SERIALS` | comma-separated serials; roles map to them in order, rotating the start device per test (default: any device the server lists) |
| `tap.device.<role>` | `TAP_DEVICE_<ROLE>` | pin a role to a serial |
| `tap.server` | `TAP_SERVER` | `host:port` of a running server (default: the one in `<state dir>/daemon.json`) |
| `tap.token` | `TAP_TOKEN` | bearer token for an explicit `tap.server` (default: the one in `daemon.json`) |
| `tap.manageDaemon` | `TAP_MANAGE_DAEMON` | `true` = `tap start` before the first test and `tap stop` after the last one if that start created the server (default `false`) |
| `tap.bin` | `TAP_BIN` | the `tap` executable `tap.manageDaemon` runs (default: `tap` on `PATH`) |
| `tap.artifactsDir` | `TAP_ARTIFACTS_DIR` | failure artifacts (default `build/tap-artifacts`) |
| `tap.capture` | `TAP_CAPTURE` | `onFailure` (default) captures every device of a failed test; `off` captures nothing |
| `tap.acquireTimeoutSeconds` | `TAP_ACQUIRE_TIMEOUT_SECONDS` | wait for a device another session holds (default 300) |

`samples/fixture-tests` is a complete example wired through Gradle; run it with

```bash
./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554[,SERIAL]
```

It builds the fixture app and the daemon distribution, starts the server (`tap.manageDaemon`,
which installs the driver), runs the fixture tests concurrently across the devices (the
two-device tests are skipped with one serial), and stops the server again unless one was
already running (`-Ptap.manageDaemon=false` to require a running one). The suite runs on the
API 29/API 34 matrix locally and on an API 34 emulator in CI.

## Python tests

The Python client talks to the same server. Build it as a single executable (needs a
GraalVM 21 at `GRAALVM_HOME`; the JVM distribution from `:host:daemon:installDist` is the
fallback):

```bash
./gradlew :host:daemon:nativeCompile        # -> host/daemon/build/native/nativeCompile/tap
pip install -e clients/python                # the tap-e2e package
```

The Python API mirrors the Kotlin one; `tap_device` is a per-test session:

```python
import pytest
from tap_e2e import text, res

def test_view_button(tap_device):
    tap_device.app().cold_launch(".MainActivity")
    tap_device.element(res("view_button")).tap()
    tap_device.wait(text("View tapped")).visible()

@pytest.mark.tap_devices("left", "right")
def test_two_devices(tap_devices): ...
```

```bash
TAP_BIN=$PWD/host/daemon/build/native/nativeCompile/tap TAP_MANAGE_DAEMON=1 TAP_SERIALS=emulator-5554[,SERIAL] \
  pytest clients/python/tests
```

The plugin connects to a running server (`TAP_SERVER`, or `~/.tap/daemon.json` written by
`tap start`); with `TAP_MANAGE_DAEMON=1` it starts one from `TAP_BIN` before the run and stops
it afterwards if it started it. Failure artifacts land in `tap-artifacts/<nodeid>/`. See
`clients/python/README.md`.

## Coding agents

`tap-agent` (`clients/agent`, experimental) lets a coding agent drive a device through the same
server: `snapshot` prints the screen with refs, actions take a ref or a selector, `--settle`
prints what changed, and `export` writes the session's device calls as JSON (`tap-events/1`)
for turning into a test in any language. The same steps are MCP tools (`tap-agent mcp`).

```bash
uv pip install -e clients/python -e clients/agent    # or pip
tap-agent attach emulator-5554 io.github.noamcohen48.tap.fixture --cold
tap-agent snapshot -i
tap-agent tap @e3 --settle
tap-agent export -o session.json && tap-agent release
```

See `docs/guide/agents.md` and `clients/agent/README.md`.

## Current slice

- Dedicated driver APK and instrumentation APK, independent from the AUT.
- Length-prefixed authenticated socket protocol over serial-specific ADB forwarding.
- A versioned selector expression tree (text/description/hint/class with exact, contains,
  prefix, suffix, and RE2 regex modes; resource IDs; boolean properties; parent/ancestor/child/
  descendant relations; `and`/`or` combinators; `first()`/`at(n)`) validated identically on
  host and driver, compiled to window-scoped `BySelector`s or a single hierarchy traversal —
  never XPath or a hierarchy dump on the hot path.
- AUT-confined selectors by default, widened per selector to another package or to every window.
- Health, device info, key presses, exists, count, element snapshots, tap, long tap, direct
  and key-event text input, clear text, directional swipe and scroll, bounded View/Compose
  list scroll-and-search with end detection, visible/gone/app-visible waits, an explicit
  screen-stability wait (tree fingerprint + pixel grid), PNG screenshots
  streamed as checksummed blobs, and diagnostic hierarchy-dump commands.
- Every mutating command requires exactly one match and returns `AMBIGUOUS`/`NOT_FOUND`
  before any input; a typed error taxonomy with stable detail sub-reasons.
- Driver execution lanes (reader, bounded queue, single executor, writer, watchdog) with
  cooperative cancellation, an atomic mutation gate, heartbeat expiry, and self-poisoning.
- An optional `sync-sdk` Android library apps ship in E2E builds to expose busy state.
- One Kotlin host process controlling multiple devices concurrently.
- Mixed View/Compose fixture with `testTagsAsResourceId`, merged semantics, and a lazy list.
- Driver-survival checks across AUT force-stop, data clearing, and relaunch.
- A real runtime permission dialog handled without allowing AUT selectors into its system
  window.
- Signature-protected cross-UID synchronization with host-verified process identity,
  stable-idle sampling, and restart invalidation.
- Machine-wide per-device leases and atomically durable session journals with exact orphan
  driver/forward recovery and fail-closed quarantine.
- Conservative transport-loss classification with poisoned clients, generation-based driver
  rebuilds, and deterministic pre-acceptance, post-acceptance, and post-mutation fault tests.
- Durable late-mutation quarantine with same-boot refusal, crash-resumable reboot recovery,
  AUT-state reset, and fresh-generation isolation verification.
- Authenticated application-protocol version, operation-version, capability, build, and
  device-contract negotiation.
- A per-machine host daemon (`tap start`, gRPC, native image) with a reusable
  `DeviceSession` state machine, `AppLifecycle`, and per-device locks shared across processes
with constraints; a coroutine Kotlin SDK (`Device`/`App`/`Element`/waits/selectors, all `suspend`
behind `tapTest`/`tapScope`) and JUnit 5 extension (`tapTest` bridge, per-test root job,
`DeviceBarrier`, failure artifacts), and a Python client and pytest plugin, all gRPC clients of
the server. Host-side validation passes the no-reboot run on API 29 and API 34; the
Kotlin and Python fixture suites pass on the same matrix, including the multi-device tests.
- The agent surface: held connections that outlive a process, screen snapshots whose refs
  name selectors the server checked, a per-connection event log, and `tap-agent` on top.

The exact implemented wire contract is in [`.docs/protocol-contract.md`](.docs/protocol-contract.md).

## Requirements

- Android SDK with API 36 and build tools 36.0.0.
- JDK 17.
- ADB-visible Android API 26+ devices.

Gradle itself runs on a JDK 17 whatever `JAVA_HOME` points at
(`gradle/gradle-daemon-jvm.properties`): it must find one locally (an OS install, `~/.gradle/jdks`
or setup-java's `JAVA_HOME_17_*` in CI). Dependency and plugin versions live in
`gradle/libs.versions.toml`; shared JVM module setup in the `build-logic` convention plugins.

## Build

```bash
./gradlew :contracts:protocol:test :device:driver:command-engine:test :device:driver:core:test :host:core:test \
  :host:validation:installDist :device:driver:assembleDebug :device:driver:assembleAndroidTest \
  :fixture-app:assembleDebug
```

Modules, by component:

- `contracts/` — `contracts/proto` is the one protobuf schema (`tap.v1` server API +
  `tap.wire.v1` device payloads): `:contracts:schema` (generated lite messages),
  `:contracts:api` (gRPC stubs), `:contracts:protocol` (TAP1 framing, handshake, validation).
- `device/` — `:device:driver:core` (driver product code, Android library) +
  `:device:driver:command-engine` (pure-JVM pipeline), run by `:device:driver` (instrumentation
  shell; flavor `product` is what the daemon bundles, `validation` adds fault injection for the
  validation flow), `:device:sync-sdk` (optional AUT library).
- `host/` — `:host:core` (ADB, journals, sessions, `DriverClient`, `AppLifecycle`),
  `:host:daemon` (gRPC host daemon, `tap` executable), `:host:validation` (device
  validation suite + `tap-product-probe`). Nothing here depends on `clients/`.
- `clients/` — `:clients:kotlin:sdk`, `:clients:kotlin:junit5`, `clients/python` (tap-e2e);
  each depends only on `contracts/api`.
- `:fixture-app`, `:samples:fixture-tests`.

Server and Python checks:

```bash
./gradlew :contracts:protocol:test :host:daemon:test  # wire golden bytes + server tests
clients/python/scripts/gen_stubs.py --check               # committed Python stubs match contracts/proto/*.proto
GRAALVM_HOME=... ./gradlew :host:daemon:nativeCompile
```

## Run the validation flow

Device validation is a JUnit 5 suite (`:host:validation:deviceTest`, one class per scenario:
recovery, fencing, transport faults, cancellation, heartbeat, selectors, input, scroll,
permissions, multi-device disconnect isolation, ...). Pass one serial or a comma-separated set;
without `-Ptap.serials` every test is skipped:

```bash
./gradlew :host:validation:deviceTest -Ptap.serials=emulator-5554,DEVICE_SERIAL
```

The driver and fixture APKs default to
`device/driver/build/outputs/apk/validation/debug/driver-validation-debug.apk`,
`device/driver/build/outputs/apk/androidTest/validation/debug/driver-validation-debug-androidTest.apk`
and `fixture-app/build/outputs/apk/debug/fixture-app-debug.apk` (build them first); override
with `-Ptap.driverApk=`, `-Ptap.driverTestApk=` and `-Ptap.fixtureApk=`. Journals and leases
live in `$TAP_STATE_DIR/sessions` (default `~/.tap/sessions`). Timings are printed as
`TAP_VALIDATION <scenario> serial=...` lines.

**Warning:** `-Ptap.reboot=true` adds `LateMutationQuarantineTest` (tagged `reboot`), which
intentionally reboots every supplied device. Do not use it for routine development or shared
devices. Without it no device is rebooted.

To benchmark and inventory screens in an arbitrary installed product shape without adding
product logic to Tap, use the product probe. Each final argument is
`tap text|ready text|screen name`; use `-` for the initial screen:

```bash
./gradlew :host:validation:installDist
./host/validation/build/install/tap-product-probe/bin/tap-product-probe \
  emulator-5554 \
  "$PWD/device/driver/build/outputs/apk/product/debug/driver-product-debug.apk" \
  "$PWD/device/driver/build/outputs/apk/androidTest/product/debug/driver-product-debug-androidTest.apk" \
  /path/to/app.apk com.example.app .MainActivity \
  '-|Home|home' 'Settings|Appearance|settings'
```

The probe reports cold and warm direct-query latency, hierarchy latency, host XML query time,
and an AUT-scoped accessibility inventory. It does not define a second test DSL.
For framework fault validation, prefix a tap step with `!ERROR_CODE:`, for example
`'!AMBIGUOUS:Duplicate label|Unchanged status|ambiguous-tap'`.

## Versioning, CI and releases

Five independently versioned artifact families: the **engine** (`tap` daemon binary/JVM
dist with the bundled driver, plus the `io.github.noamcohen48.tap:tap-schema`/`tap-api` stubs;
`tap.version.engine` in `gradle.properties`), the **Kotlin client** (`tap-client`, `tap-junit5`;
`tap.version.client.kotlin`), the **Python client** (`tap-e2e`; `clients/python/pyproject.toml`),
the **agent tools** (`tap-agent`; `clients/agent/pyproject.toml`) and **sync-sdk**
(`tap-sync-sdk`; `tap.version.sync-sdk`). Tag `daemon/vX.Y.Z`, `client-kotlin/vX.Y.Z`,
`client-python/vX.Y.Z`, `client-agent/vX.Y.Z` or `sync-sdk/vX.Y.Z` on a commit whose version
matches and `.github/workflows/release.yml` publishes to GitHub Packages (Maven) and GitHub
Releases (binaries, wheels). `CHANGELOG.md` records each release. `ci.yml` lints the proto, checks it for breaking changes
and stub drift, runs the JVM/Python builds and unit tests, the sample suites on an API 34
emulator and the native image. Details and rationale (why the driver is not a separate
package): [`.docs/release-engineering.md`](.docs/release-engineering.md).

## Docs

`docs/` is the public documentation (MkDocs Material, `mkdocs.yml`): a hand-written guide and
three generated references — Kotlin (Dokka, `./gradlew :dokkaGenerate`), Python (mkdocstrings
from the docstrings of `clients/python/tap_e2e`) and the `tap.v1` gRPC API (protoc-gen-doc from
`contracts/proto`). `scripts/build-docs.sh` runs the generators and `mkdocs build --strict` into
`build/site`, and also assembles `build/docs-md/` (+ `build/tap-docs-md.zip`): the same guide
and references as plain Markdown (Dokka GFM and lazydocs instead of Dokka HTML and
mkdocstrings). It needs JDK 17, `buf`, `protoc-gen-doc` and
`pip install -r docs/requirements.txt -e "clients/python[dev]"`. `mkdocs serve` previews the guide
alone. `.github/workflows/docs.yml` builds both on every change (artifacts `site` and
`docs-md`) and deploys the site to GitHub Pages once the `DEPLOY_DOCS` repository variable is
`true`. Public API additions need a
KDoc/docstring, since that is what the references are generated from. `.docs/` remains the
internal design record.

## Design

The original design is `.docs/android-e2e-framework-implementation-plan.md` (a reference: the
code, the contracts and the decision records win where they differ). Current status is in
`.docs/phase-1-progress.md`, the remaining delta to the plan in `.docs/framework-gaps.md`,
the module layout in `.docs/project-architecture.md`, the device wire protocol in
`.docs/protocol-contract.md` and the host server API in `.docs/server-api.md`.
