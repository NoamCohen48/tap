# Tap

Host-driven Android E2E framework in three parts: an on-device driver (`device/`), one host
service per machine (`host/`, `tap serve`: ADB, driver lifecycle, sessions, device pool; gRPC
over loopback, JVM or native image) and thin language clients (`clients/`: Kotlin SDK +
JUnit 5, Python + pytest). `contracts/` holds what they agree on (the TAP1 device protocol
and the `tap.v1` service API). Phases 0 and 1 (feasibility, contract, driver) are complete
and the first usable cut of Phase 2 (service + clients) is in; this is not a production
release. What is still missing is listed in [`.docs/framework-gaps.md`](.docs/framework-gaps.md).

User documentation lives in [`docs/`](docs/index.md) (guide + generated Kotlin/Python/gRPC
references); build the site with `scripts/build-docs.sh` (see [Docs](#docs)).

## Writing tests

Add `:clients:kotlin:junit5` to a JUnit 5 test source set and annotate the class with `@TapTest`:

```kotlin
@TapTest
class CheckoutTest {
    @Test
    fun buysAnItem(device: Device) {
        val app = device.app()                    // the configured AUT package
        app.clearData()
        app.launch()                              // resolves the launcher activity, waits for the window
        device.element(resId(app.packageName, "buy_button")).tap()
        device.await(text("Order placed")).visible()
    }

    @Test
    @TapDevices("sender", "receiver")
    fun messagePropagates(devices: Devices) {
        devices["sender"].element(rawRes("sendButton")).tap()
        devices["receiver"].await(text("hello"), timeout = 20.seconds).visible()
    }
}
```

- `device.element(selector)` is lazy; every action resolves the selector again on the device
  and requires exactly one match (`CommandException` with `ERR_AMBIGUOUS`/`ERR_NOT_FOUND`
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
- On failure the extension writes a screenshot, hierarchy XML, device info and the driver log
  under `tap.artifactsDir/<class>/<method>/`.

The extension is a gRPC client of `tap serve`, the per-machine host service that owns ADB,
driver lifecycle (the driver APKs are bundled in it), journals, leases and the device pool
(`.docs/service-api.md`). It discovers a running service or starts one. Configuration is
read from system properties or environment variables:

| Property | Env | Meaning |
|---|---|---|
| `tap.autPackage` | `TAP_AUTPACKAGE` | application under test (required) |
| `tap.serials` | `TAP_SERIALS` | comma-separated serials to pin roles to, in order (default: any free device in the pool) |
| `tap.device.<role>` | `TAP_DEVICE_<ROLE>` | pin a role to a serial |
| `tap.service` | `TAP_SERVICE` | `host:port` of a running service (default: `<state dir>/service.json`, else auto-start) |
| `tap.bin` | `TAP_BIN` | the `tap` executable to auto-start (default: `tap` on `PATH`) |
| `tap.artifactsDir` | `TAP_ARTIFACTSDIR` | failure artifacts (default `build/tap-artifacts`) |
| `tap.acquireTimeoutSeconds` | `TAP_ACQUIRETIMEOUTSECONDS` | device pool wait (default 300) |

`samples/fixture-tests` is a complete example wired through Gradle; run it with

```bash
./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554[,SERIAL]
```

It builds the fixture app and the service distribution, auto-starts the service (which
installs the driver), runs twelve tests (including a two-device test that is skipped with one
serial) and runs test classes concurrently across the pool. The service stays up afterwards
(`host/service/build/install/tap/bin/tap stop`).

## Python tests

The Python client talks to the same service. Build it as a single executable (needs a
GraalVM 21 at `GRAALVM_HOME`; the JVM distribution from `:host:service:installDist` is the
fallback):

```bash
./gradlew :host:service:nativeCompile        # -> host/service/build/native/nativeCompile/tap
pip install -e clients/python                # the tap-e2e package
```

The Python API mirrors the Kotlin one; `tap_device` is a per-test session from the pool:

```python
import pytest
from tap import text, res_id

def test_view_button(tap_device):
    tap_device.app().cold_launch(".MainActivity")
    tap_device.element(res_id("com.company.tap.fixture", "view_button")).tap()
    tap_device.wait(text("View tapped")).visible()

@pytest.mark.tap_devices("left", "right")
def test_two_devices(tap_devices): ...
```

```bash
TAP_BIN=$PWD/host/service/build/native/nativeCompile/tap TAP_SERIALS=emulator-5554[,SERIAL] \
  pytest clients/python/tests
```

The plugin discovers a running service (`TAP_SERVICE`, or `~/.tap/service.json`) or starts
one, which then stays up like the ADB server (`tap stop`). Failure artifacts land in
`tap-artifacts/<nodeid>/`. See `clients/python/README.md`.

## Current slice

- Dedicated driver APK and instrumentation APK, independent from the AUT.
- Length-prefixed authenticated socket protocol over serial-specific ADB forwarding.
- A versioned selector AST (text/description/hint/class with exact, contains, prefix,
  suffix, and RE2 regex modes; resource IDs; boolean properties; parent/ancestor/child/
  descendant relations; `first()`/`at(n)` limits) validated identically on host and driver,
  compiled to window-scoped `BySelector`s or a single hierarchy traversal — never XPath or a
  hierarchy dump on the hot path.
- AUT-confined selectors and explicitly allowlisted system-package selectors.
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
- A per-machine host service (`tap serve`, gRPC, native image) with a reusable
  `DeviceSession` state machine, `AppLifecycle`, and an all-or-none named-role device pool
  with constraints; a Kotlin SDK (`Device`/`App`/`Element`/waits/selectors) and JUnit 5
  extension with failure artifacts, and a Python client and pytest plugin, all gRPC clients of
  the service, proven by sample suites on API 29 and API 34.

The exact implemented wire contract is in [`.docs/protocol-contract.md`](.docs/protocol-contract.md).

## Requirements

- Android SDK with API 36 and build tools 36.0.0.
- JDK 17.
- ADB-visible Android API 26+ devices.

The workstation's default Java 26 is not compatible with the Android JDK image transform.
Set `JAVA_HOME` to a JDK 17 installation before building (Gradle's provisioned toolchain
lives at `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2` on the current workstation).

## Build

```bash
./gradlew :contracts:protocol:test :device:driver:command-engine:test :host:core:test :host:validation:installDist \
  :device:driver:assembleDebug :device:driver:assembleDebugAndroidTest \
  :fixture-app:assembleDebug
```

Modules, by component:

- `contracts/` — `:contracts:protocol` (TAP1 device wire contract), `:contracts:api`
  (`contracts/api/proto/tap.proto`, the `tap.v1` service API + generated Java stubs).
- `device/` — `:device:driver` + `:device:driver:command-engine` (on-device driver),
  `:device:sync-sdk` (optional AUT library).
- `host/` — `:host:core` (ADB, journals, sessions, `DriverClient`, `AppLifecycle`),
  `:host:service` (gRPC host service, `tap` executable), `:host:validation` (the `host`
  validation executable). Nothing here depends on `clients/`.
- `clients/` — `:clients:kotlin:sdk`, `:clients:kotlin:junit5`, `clients/python` (tap-e2e);
  each depends only on `contracts/api`.
- `:fixture-app`, `:samples:fixture-tests`.

Service and Python checks:

```bash
./gradlew :host:service:test                      # proto mirror + golden round-trip tests
clients/python/scripts/gen_stubs.py --check               # committed Python stubs match contracts/api/proto/tap.proto
GRAALVM_HOME=... ./gradlew :host:service:nativeCompile
```

## Run the validation flow

Pass one serial or a comma-separated set of unique serials:

**Warning:** the Phase 0 command below includes destructive late-mutation validation and
intentionally reboots every supplied device. Do not use it for routine development or shared
devices.

```bash
./host/validation/build/install/host/bin/host \
  emulator-5554,DEVICE_SERIAL \
  "$PWD/device/driver/build/outputs/apk/debug/driver-debug.apk" \
  "$PWD/device/driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk" \
  "$PWD/fixture-app/build/outputs/apk/debug/fixture-app-debug.apk"
```

The run prints `PHASE_0_OK` only after the device flow and instrumentation process both
finish successfully. Pass `--no-reboot` (anywhere in the arguments) to skip only the
late-mutation quarantine scenario; every other check, including the Phase 1 proofs
(`PHASE_1_CANCELLATION_OK`, `PHASE_1_CANCEL_AFTER_MUTATION_OK`, `PHASE_1_HEARTBEAT_EXPIRY_OK`,
`PHASE_1_SCREENSHOT_OK`, `PHASE_1_SELECTORS_OK`, `PHASE_1_OBSERVATION_OK`), still runs and no
device is rebooted.

To benchmark and inventory screens in an arbitrary installed product shape without adding
product logic to Tap, use product-probe mode. Each final argument is
`tap text|ready text|screen name`; use `-` for the initial screen:

```bash
./host/validation/build/install/host/bin/host --product-probe \
  emulator-5554 \
  "$PWD/device/driver/build/outputs/apk/debug/driver-debug.apk" \
  "$PWD/device/driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk" \
  /path/to/app.apk com.example.app .MainActivity \
  '-|Home|home' 'Settings|Appearance|settings'
```

The probe reports cold and warm direct-query latency, hierarchy latency, host XML query time,
and an AUT-scoped accessibility inventory. It does not define a second test DSL.
For framework fault validation, prefix a tap step with `!ERROR_CODE:`, for example
`'!AMBIGUOUS:Duplicate label|Unchanged status|ambiguous-tap'`.

## Versioning, CI and releases

Four independently versioned artifact families: the **engine** (`tap` service binary/JVM
dist with the bundled driver, plus the `com.company.tap:tap-api` stubs; `tap.version.engine`
in `gradle.properties`), the **Kotlin client** (`tap-client`, `tap-junit5`;
`tap.version.client.kotlin`), the **Python client** (`tap-e2e`; `clients/python/pyproject.toml`)
and **sync-sdk** (`tap-sync-sdk`; `tap.version.sync-sdk`). Tag `service/vX.Y.Z`,
`client-kotlin/vX.Y.Z`, `client-python/vX.Y.Z` or `sync-sdk/vX.Y.Z` on a commit whose
version matches and `.github/workflows/release.yml` publishes to GitHub Packages (Maven) and
GitHub Releases (binaries, wheel). `ci.yml` lints the proto, checks it for breaking changes
and stub drift, runs the JVM/Python builds and unit tests, the sample suites on an API 34
emulator and the native image. Details and rationale (why the driver is not a separate
package): [`.docs/release-engineering.md`](.docs/release-engineering.md).

## Docs

`docs/` is the public documentation (MkDocs Material, `mkdocs.yml`): a hand-written guide and
three generated references — Kotlin (Dokka, `./gradlew :dokkaGenerate`), Python (mkdocstrings
from the docstrings of `clients/python/tap`) and the `tap.v1` gRPC API (protoc-gen-doc from
`tap.proto`). `scripts/build-docs.sh` runs the generators and `mkdocs build --strict` into
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

The normative design is in
`.docs/android-e2e-framework-implementation-plan.md`. Current status is in
`.docs/phase-1-progress.md`, the remaining delta to the plan in `.docs/framework-gaps.md`,
the module layout in `.docs/project-architecture.md`, the device wire protocol in
`.docs/protocol-contract.md` and the host service API in `.docs/service-api.md`.
