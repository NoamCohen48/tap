# Tap

Kotlin host-driven Android E2E framework. Phases 0 and 1 (feasibility, contract, driver) are
complete and the first usable cut of Phase 2 (host SDK + JUnit 5) is in; this is not a
production release. What is still missing is listed in
[`.docs/framework-gaps.md`](.docs/framework-gaps.md).

## Writing tests

Add `:host:junit5` to a JUnit 5 test source set and annotate the class with `@TapTest`:

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
  and requires exactly one match (`RemoteCommandException` with `AMBIGUOUS`/`NOT_FOUND`
  otherwise). Use `resId`, `rawRes` (Compose `testTag`), `text*`, `desc`, `hint`,
  `className`, refinements (`.clickable()`, `.andText(...)`), relations
  (`.hasDescendant(...)`, `.child(...)`) and `.first()`/`.at(n)`.
- `device.await(selector).visible()/gone()` and `device.awaitAppVisible(pkg)` wait on the
  device; `.enabled()/.textEquals()/.count(n)` poll from the host; timeouts throw
  `WaitTimeoutException` with elapsed time and polls.
- `device.app(pkg)` handles install/launch/coldLaunch/forceStop/clearData/grantPermission and
  `awaitIdle()` for apps that ship `sync-sdk`.
- On failure the extension writes a screenshot, hierarchy XML, device info and the driver log
  under `tap.artifactsDir/<class>/<method>/`.

Configuration is read from system properties or environment variables:

| Property | Env | Meaning |
|---|---|---|
| `tap.serials` | `TAP_SERIALS` | comma-separated device pool (required) |
| `tap.autPackage` | `TAP_AUTPACKAGE` | application under test (required) |
| `tap.driverApk`, `tap.driverTestApk` | `TAP_DRIVERAPK`, `TAP_DRIVERTESTAPK` | driver APKs to install once per device (optional if already installed) |
| `tap.device.<role>` | `TAP_DEVICE_<ROLE>` | pin a role to a serial |
| `tap.artifactsDir` | `TAP_ARTIFACTSDIR` | failure artifacts (default `build/tap-artifacts`) |
| `tap.acquireTimeoutSeconds` | `TAP_ACQUIRETIMEOUTSECONDS` | device pool wait (default 300) |

`samples/fixture-tests` is a complete example wired through Gradle; run it with

```bash
./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554[,SERIAL]
```

It builds and installs the fixture app and the driver, runs nine tests (including a two-device
test that is skipped with one serial) and runs test classes concurrently across the pool.

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
  list scroll-and-search with end detection, visible/gone/app-visible waits, PNG screenshots
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
- Public Kotlin SDK (`Device`/`App`/`Element`/waits/selectors), a reusable `DeviceSession`
  state machine, an all-or-none named-role device pool, and a JUnit 5 extension with failure
  artifacts, proven by a sample suite on API 29 and API 34.

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
./gradlew :protocol:test :driver:command-engine:test :host:test :host:validation:installDist \
  :driver:assembleDebug :driver:assembleDebugAndroidTest \
  :fixture-app:assembleDebug
```

Modules: `:protocol` (wire contract), `:driver` + `:driver:command-engine` (on-device
driver), `:host` (ADB, sessions, `DriverClient`), `:host:sdk` (public API),
`:host:junit5` (JUnit 5 extension), `:host:validation` (the `host` validation executable),
`:sync-sdk`, `:fixture-app`, `:samples:fixture-tests`.

## Run the validation flow

Pass one serial or a comma-separated set of unique serials:

**Warning:** the Phase 0 command below includes destructive late-mutation validation and
intentionally reboots every supplied device. Do not use it for routine development or shared
devices.

```bash
./host/validation/build/install/host/bin/host \
  emulator-5554,DEVICE_SERIAL \
  "$PWD/driver/build/outputs/apk/debug/driver-debug.apk" \
  "$PWD/driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk" \
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
  "$PWD/driver/build/outputs/apk/debug/driver-debug.apk" \
  "$PWD/driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk" \
  /path/to/app.apk com.example.app .MainActivity \
  '-|Home|home' 'Settings|Appearance|settings'
```

The probe reports cold and warm direct-query latency, hierarchy latency, host XML query time,
and an AUT-scoped accessibility inventory. It does not define a second test DSL.
For framework fault validation, prefix a tap step with `!ERROR_CODE:`, for example
`'!AMBIGUOUS:Duplicate label|Unchanged status|ambiguous-tap'`.

## Design

The normative design is in
`.docs/android-e2e-framework-implementation-plan.md`. Current status is in
`.docs/phase-1-progress.md`, the remaining delta to the plan in `.docs/framework-gaps.md`,
and the module layout in `.docs/project-architecture.md`.
