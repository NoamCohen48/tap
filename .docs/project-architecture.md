# Tap Project Architecture

Date: 2026-09-18

Status: current implementation overview after Phase 1 (contract and driver) and the first
usable cut of Phase 2 (host SDK and JUnit 5 integration). The normative future design remains
[`android-e2e-framework-implementation-plan.md`](android-e2e-framework-implementation-plan.md);
the exact wire contract is [`protocol-contract.md`](protocol-contract.md); progress is in
[`phase-1-progress.md`](phase-1-progress.md) and the remaining delta to the plan in
[`framework-gaps.md`](framework-gaps.md).

## 1. Overview

Tap is a host-driven Android E2E framework. Test coordination runs in a Kotlin/JVM process on
the host computer; a small dedicated driver runs on each Android device; an optional library
inside the application under test (AUT) exposes busy state.

```text
Kotlin host process (one per test run)
+-- :host:junit5   @TapTest extension: roles, all-or-none DevicePool, artifacts, TapConfig
+-- :host:sdk      Device / App / Element / ElementWait, selector DSL, typed exceptions
+-- :host          Adb, SessionJournal, DriverLifecycle, DeviceSession, DriverClient
+-- :host:validation  `host` executable: PhaseZeroMain fault/validation flow, ProductProbe
        |
        | adb forward tcp:<host> tcp:27183   (one socket per device session)
        v
Android driver instrumentation  (package com.company.tap.driver, own UID/process)
+-- TapDriverServer     loopback listener, session config, TAP_READY/TAP_POISONED markers
+-- ClientConnection    handshake, framing, reader lane, heartbeat
+-- CommandPipeline     bounded queue -> single executor -> writer, watchdog   (pure JVM)
+-- DriverCommandEngine request/selector validation, dispatch
+-- SelectorCompiler    AST -> window-scoped BySelector | traversal predicate
+-- UiObjectAccess      limit-aware resolution, no retained handles
+-- UiAutomationCommands taps, gestures, text, waits, scroll, screenshot
+-- SyncProviderClient  reads AUT busy state over ContentResolver.call
        |                                                  |
        | UiAutomation / accessibility                     | content://<aut>.tap-sync
        v                                                  v
Application under test  (any package)          sync-sdk: TapSynchronization + provider
```

ADB is used for setup, lifecycle, forwarding, and recovery. Ordinary UI commands travel over
the persistent RPC connection; Tap never launches an ADB process per action.

## 2. Design principles

These are the invariants the code is organized around (see `CLAUDE.md` for the short list):

| Principle | Where it is enforced |
|---|---|
| Driver is a separate package from the AUT and survives AUT force-stop/clear-data | `driver/` manifest; lifecycle checks in `PhaseZeroMain` and `LifecycleTest` |
| No hierarchy dump or XPath on the selector hot path | `SelectorCompiler` + `UiObjectAccess` use `BySelector`/tree walk only; `DUMP_HIERARCHY` is diagnostic |
| No persistent `UiObject2` handles across commands | every command resolves and recycles inside `UiAutomationCommands.gesture/editText` |
| Mutations require exactly one match; `AMBIGUOUS`/`NOT_FOUND` before input | `UiObjectAccess.resolve(EXACTLY_ONE)` fetches two matches; checked before the mutation gate |
| Never replay a transmitted mutation; transport loss after acceptance is `INDETERMINATE` | `DriverClient` state `WRITTEN` + `isMutating`; `CommandTransportException` |
| Request IDs strictly increasing per generation; old generations rejected | `DriverClient` transport mutex; `ClientConnection` watermark; `SESSION_MISMATCH` |
| Every ADB call is serial-specific; never `forward --remove-all` | `Adb` API takes `serial` on every method; `removeExactForward` |
| Elements are lazy selectors; creating one performs no I/O | `sdk/Element` holds a `Selector`; every terminal call resolves again on the driver |
| One device is never assigned to two tests | `junit5/DevicePool` exclusive, all-or-none acquisition inside the JVM; `SessionJournalStore` lease across processes |
| Bounded everything: payload 1 MiB, text 256 chars, timeout 120 s, selector depth/nodes/strings, artifact 64 MiB, queue 16 | constants in `protocol/Messages.kt`, `Selector.kt`, `Blob.kt`; `CommandPipeline` |
| Both sides validate the same selector rules | `SelectorValidation` is called by `DriverClient` before an ID is allocated and by `DriverCommandEngine` before any lookup |
| Failure taxonomy is closed and typed, with may-have-mutated/retryable flags | `ErrorCode` enum, `ErrorDetail` constants, `Response.failure` |

## 3. Repository layout

```text
tap/
+-- build.gradle.kts             root plugins (AGP 9.0.1, Kotlin 2.3.20), no logic
+-- settings.gradle.kts          includes :protocol :driver :driver:command-engine :host :host:validation :host:sdk :host:junit5 :fixture-app :sync-sdk :samples:fixture-tests
+-- gradle.properties, gradlew*, gradle/wrapper/
+-- CLAUDE.md                    working rules for agents/contributors
+-- README.md                    build/run instructions
+-- THIRD_PARTY_NOTICES.md       copied/adapted upstream code (currently none)
+-- .docs/                       design, plan, contract, progress, audits
|
+-- protocol/                    pure Kotlin/JVM, shared by host and driver
|   +-- src/main/kotlin/com/company/tap/protocol/
|   |   +-- Messages.kt          FrameType, Operation, Direction, Request, Response, ElementSnapshot, DeviceInfo, SyncState, limits, key codes
|   |   +-- Selector.kt          Selector/NodeSelector AST, MatchMode, MatchLimit, factories
|   |   +-- SelectorValidation.kt shared structural validation -> NATIVE | TRAVERSAL plan kind
|   |   +-- ErrorCode.kt         closed error taxonomy + ErrorDetail sub-reasons
|   |   +-- Blob.kt              BlobStart/BlobEnd/ArtifactInfo, chunk encoding, SHA-256
|   |   +-- FrameCodec.kt        TAP1 header encode/decode, bounds checks
|   |   +-- CanonicalJson.kt     canonical handshake JSON + the shared Json codec
|   |   +-- Authentication.kt    Hello/Challenge/Negotiation, HMAC domains, transcript
|   +-- src/test/kotlin/...      FrameCodecTest, ProtocolContractTest, ErrorCodeTest, SelectorTest, GoldenMessageTest
|   +-- src/test/resources/golden/  57 golden request/response JSON fixtures
|
+-- driver/                      Android; the on-device driver
|   +-- src/main/AndroidManifest.xml   empty app shell (package com.company.tap.driver, <queries> for sync/fault providers)
|   +-- src/androidTest/kotlin/com/company/tap/driver/
|   |   +-- TapDriverServerTest.kt   instrumentation entry point (keeps the process alive)
|   |   +-- TapDriverServer.kt       SessionConfig from instrumentation args, listener, markers
|   |   +-- ClientConnection.kt      per-connection handshake, frame reader, blob writer
|   |   +-- DriverCommandEngine.kt   request validation + dispatch table
|   |   +-- SelectorCompiler.kt      AST -> CompiledSelector.Native | .Traversal
|   |   +-- UiObjectAccess.kt        resolve/hasObject/count/containerHasObject per MatchLimit
|   |   +-- UiAutomationCommands.kt  tap, longTap, pressKey, swipe, scroll, scrollUntil, waitVisible/Gone/AppVisible, set/type/clearText, snapshot, deviceInfo, screenshot, dumpHierarchy
|   |   +-- SyncProviderClient.kt    signature-checked ContentProvider reads with timeout
|   |   +-- FaultController.kt       test-only fault injection (transport loss, late work, cancel-after-mutation)
|   +-- command-engine/          pure Kotlin/JVM execution state machine (no Android types)
|       +-- src/main/kotlin/com/company/tap/driver/engine/
|       |   +-- CommandPipeline.kt   queue, executor, writer, watchdog, poison, heartbeat, blob streaming
|       |   +-- CommandContext.kt    deadline, checkpoint/sleep, mutation gate, transferBlob
|       |   +-- Command.kt           per-request state (QUEUED/RUNNING/TERMINAL) + single terminal response
|       |   +-- BlobTransfer.kt      chunking, checksums, outcome (COMPLETED/CANCELLED/DEADLINE/WRITE_FAILED)
|       |   +-- Outbound.kt          writer-lane frames (Response, Pong, BlobStart/Chunk/End)
|       |   +-- CommandInterrupted.kt, Clock.kt
|       +-- src/test/kotlin/...      CommandPipelineTest (23 tests)
|
+-- host/                        Kotlin/JVM library: session infrastructure (no test DSL)
|   +-- src/main/kotlin/com/company/tap/host/
|   |   +-- Adb.kt               ProcessBuilder wrapper (`Adb(executable)`); every call takes a serial
|   |   +-- SessionJournal.kt    JournalState, SessionJournal, SessionJournalStore (lease + fsync'd atomic write)
|   |   +-- DriverLifecycle.kt   start-with-retry, port range, forward, process observation, journal recovery, cleanup
|   |   +-- DeviceSession.kt     DeviceSessionConfig + DeviceSession.open()/close(): lease -> recover -> install -> start -> forward -> connect -> READY
|   |   +-- DriverClient.kt      handshake, request IDs, reader thread, heartbeat thread, PendingCommand, screenshot()
|   |   +-- BlobReceiver.kt      verifying blob reassembly
|   |   +-- CommandException.kt  RemoteCommandException / CommandTransportException, selector rendering
|   +-- src/test/kotlin/...      DriverClientTest (17), SessionJournalTest (6), FakeDriverServer
|   |
|   +-- validation/              application `host` (exe): the destructive/fault validation flow
|   |   +-- src/main/kotlin/com/company/tap/host/validation/
|   |       +-- PhaseZeroMain.kt     multi-device validation flow + fault scenarios, PHASE_* markers
|   |       +-- ProductProbe.kt      `host --product-probe`: latency/inventory probe for arbitrary apps
|   +-- sdk/                     public Kotlin API (package com.company.tap.sdk)
|   |   +-- src/main/kotlin/com/company/tap/sdk/
|   |       +-- Device.kt            Device.connect(config), element/await/app/info/pressKey/screenshot/dumpHierarchy/awaitUntil, Timeouts
|   |       +-- App.kt               install/uninstall/forceStop/clearData/grantPermission/launch/coldLaunch/process/awaitIdle
|   |       +-- Element.kt           lazy element: exists/count/snapshot/text, tap/longTap/setText/typeText/clearText/swipe/scroll/scrollUntil, first/at/descendant/child
|   |       +-- ElementWait.kt       visible()/gone() (driver-side) and enabled/checked/focused/textEquals/count (host-polled)
|   |       +-- Selectors.kt         text/textContains/textMatches/desc/hint/resId/rawRes/className + refinements and relations
|   |       +-- TapExceptions.kt     TapException, WaitTimeoutException, AppLifecycleException
|   +-- junit5/                  JUnit 5 integration (package com.company.tap.junit5)
|       +-- src/main/kotlin/com/company/tap/junit5/
|           +-- Annotations.kt       @TapTest, @TapDevice(role), @TapDevices(roles), Devices
|           +-- TapConfig.kt         tap.* system properties / TAP_* env: serials, autPackage, driver APKs, artifactsDir, timeouts, pinned roles
|           +-- DevicePool.kt        exclusive all-or-none acquisition with timeout; shared per JVM
|           +-- TapExtension.kt      BeforeEach/AfterEach/ParameterResolver/ExceptionHandler; parallel connect; failure artifacts
|
+-- samples/fixture-tests/       JUnit 5 sample suite against the fixture app (real devices)
|   +-- build.gradle.kts         `test` depends on the APK builds, maps -Ptap.serials to system properties, disabled without serials
|   +-- src/test/kotlin/com/company/tap/samples/
|       +-- Fixture.kt           fixture facts + install-once/cold-launch helper
|       +-- MainScreenTest.kt    taps, text input, Compose list scrolling, ambiguity, app-owned sync, wait diagnostics, back key
|       +-- LifecycleTest.kt     cold launch identity, force-stop, clear-data, DEVICE_INFO
|       +-- MultiDeviceTest.kt   @TapDevices("left","right") concurrent two-device journey
|
+-- sync-sdk/                    Android library an AUT ships in its E2E/debug build
|   +-- src/main/AndroidManifest.xml  signature permission + provider at ${applicationId}.tap-sync
|   +-- src/main/kotlin/com/company/tap/sync/
|       +-- TapSynchronization.kt          busy() handles, generation, identity snapshot
|       +-- TapSynchronizationProvider.kt  ContentResolver.call("state") -> Bundle
|
+-- fixture-app/                 Android app used only by the validation flow
    +-- src/main/AndroidManifest.xml
    +-- src/main/kotlin/com/company/tap/fixture/
    |   +-- MainActivity.kt          View + Compose controls, key-event field, LazyColumn, busy() demo
    |   +-- ViewListActivity.kt      native ListView with end-of-content
    |   +-- AmbiguityActivity.kt     duplicate buttons/fields/scroll views, gesture target, prefilled field
    |   +-- PermissionActivity.kt    real runtime permission dialog
    |   +-- PortOccupierActivity.kt  occupies the driver port for startup-retry faults
    |   +-- FixtureFaultProvider.kt  delayed-mutation hook for the late-work fault (authority ...fixture.fault)
    |   +-- FixtureApplication.kt    Application + FaultTapCounter
    +-- src/main/res/layout/         activity_main, activity_view_list, activity_ambiguity, activity_permission
```

Dependency direction:

```text
samples:fixture-tests --> host:junit5 --> host:sdk --> host --> protocol
host:validation ---------------------------------> host
driver androidTest --> driver:command-engine --> protocol
fixture-app ---------> sync-sdk
```

- `protocol` has no Android, host, or coroutine dependency (kotlinx.serialization + RE2/J only).
- `driver:command-engine` has no Android types so the state machine is JVM-tested.
- `sync-sdk` depends on nothing from Tap; the driver reaches it only through a provider call.
- `host`, `host:sdk`, and `host:junit5` have no Android API or coroutine dependency; only the
  validation executable uses kotlinx.coroutines for its per-device fan-out.
- Product test suites depend on `:host:junit5` (which exposes `:host:sdk` and `:host` as
  `api`) and never on `:host:validation`.

Generated artifacts:

```text
host/validation/build/install/host/bin/host
driver/build/outputs/apk/debug/driver-debug.apk
driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk
fixture-app/build/outputs/apk/debug/fixture-app-debug.apk
```

## 4. Protocol module

The protocol is a length-prefixed binary frame (`TAP1` magic, framing version, frame type,
flags, i64 request ID, i32 length) carrying JSON. Handshake payloads are canonical JSON so the
HMAC transcript is byte-stable; request/response payloads are ordinary JSON decoded with
`ignoreUnknownKeys`.

Key types:

| Type | Purpose |
|---|---|
| `FrameType` | HELLO, CHALLENGE, AUTH, AUTH_RESULT, REQUEST, RESPONSE, CLOSE, CANCEL, PING, PONG, BLOB_START, BLOB_CHUNK, BLOB_END |
| `Operation` | HEALTH, EXISTS, TAP, LONG_TAP, WAIT_VISIBLE, DUMP_HIERARCHY, SET_TEXT, TYPE_TEXT, CLEAR_TEXT, SWIPE, SCROLL, SCROLL_UNTIL, SCREENSHOT, SYNC_BOOTSTRAP, SYNC_STATE |
| `Request` | session identity, operation + version, timeout, selector/container, input text, direction, distance percent, max scrolls, observed AUT identity |
| `Response` | `ok`, `value`, `text`, `errorCode` + `detail` + `message`, `durationMs`, `syncState`, `artifact` |
| `Selector` / `NodeSelector` | the AST (below) |
| `ErrorCode` | closed taxonomy; each code has `mayHaveMutated` and `retryable` |
| `BlobStart` / `BlobEnd` / `ArtifactInfo` | binary transfer envelope and checksum |
| `Hello` / `Challenge` / `Negotiation` / `Authentication(Result)` | handshake |

### Selector AST

```text
Selector(node, scope = AUT | SYSTEM, scopePackage?, limit = EXACTLY_ONE | FIRST | AT, index?, acceptAccessibilityOrder)
NodeSelector(text | contentDescription | hint | className : StringMatch(value, mode),
             resource : ResourceId(name, packageName?),
             checkable checked clickable enabled focusable focused longClickable scrollable selected : Boolean?,
             parent | ancestor | child | descendant : NodeSelector?)
MatchMode = EXACT | CONTAINS | STARTS_WITH | ENDS_WITH | REGEX
```

Factories: `Selector.text(v, mode)`, `.contentDescription(v)`, `.rawResource(name)`,
`.androidResource(pkg, name)`, `.first()`, `.at(n)`, `.inSystemPackage(pkg)`.
`SelectorValidation.validate` returns `NATIVE` (everything expressible in `BySelector`) or
`TRAVERSAL` (any `REGEX`), or throws `InvalidSelectorException(detail)`.

## 5. Driver

### Process model

The driver is an instrumentation test (`TapDriverServerTest`) that never finishes: it binds a
loopback socket, prints `TAP_READY` with session/generation/port/instance, and serves one
authenticated connection at a time. Instrumentation arguments (`tapSessionId`,
`tapGeneration`, `tapSecret`, `tapExpectedAut`, `tapSyncAuthority`, `tapFaultAuthority`,
`tapAllowedSystemPackages`, `tapUninterruptibleGraceMs`, `tapHeartbeatTimeoutMs`,
`tapFaultPoint`) become an immutable `SessionConfig`.

### Command lifecycle

```text
socket -> ClientConnection.reader ---enqueue---> CommandPipeline.queue(16)
              |  (watermark, CANCEL, PING,           |
              |   heartbeat())                        v
              |                             single executor thread
              |                                       |  DriverCommandEngine.execute(context, request)
              |                                       |    validate -> validateSelectors -> dispatch
              |                                       |    UiAutomationCommands.* (checkpoint, resolve, gate, act)
              |                                       v
              +<---- writer lane <---- Response / Pong / BlobStart|Chunk|End
                          ^
                     watchdog thread: deadline + grace, heartbeat silence -> poison -> TAP_POISONED -> self-kill
```

- `CommandContext` gives a command its acceptance-relative deadline, `checkpoint()` (throws
  `CommandInterrupted` on cancel/deadline/poison), `sleep()`, `markMutationStarted()` (the
  atomic gate: refuses on cancel/deadline/poison, otherwise makes the command uncancellable),
  and `transferBlob()`.
- Every accepted request produces exactly one terminal `Response`; `Command.complete` is a
  compare-and-set so late work can never emit a second one.
- `FaultController` is the only test-specific code in the driver; each fault is armed by an
  instrumentation argument and targets the fixture's `fault_button`.

### Selector execution

```text
Selector ---SelectorCompiler.compile---> CompiledSelector.Native(scopePackage, BySelector)
                                       | CompiledSelector.Traversal(scopePackage, NodePredicate)
UiObjectAccess.findObjects:
  window = device.findWindow(By.Window.pkg(scopePackage).focused(true))
  Native    -> window.findObjects(by)            (UiAutomator 2.4 window-scoped search)
  Traversal -> walk window.rootObject once, evaluate predicate per node (reads AccessibilityNodeInfo once)
UiObjectAccess.resolve(limit):
  EXACTLY_ONE -> fetch up to 2 -> 0: NOT_FOUND, 2: AMBIGUOUS
  FIRST       -> first in accessibility order
  AT n        -> nth or NOT_FOUND
```

Scope rules: `AUT` selectors resolve in the expected AUT package; `SYSTEM` selectors must name
an allowlisted package (`SCOPE_DENIED` otherwise); a resource with an explicit package must
match the scope; target and container must share a scope.

### Synchronization

`SyncProviderClient` calls `content://<syncAuthority>` method `state` on a daemon thread with
a timeout, verifies the provider's package matches the expected AUT and shares the driver's
signing certificate, and validates every field of the returned bundle. `SYNC_BOOTSTRAP`
records identity; `SYNC_STATE` fails with `AUT_MISMATCH/PROCESS_RESTARTED` when the process
start UUID or session identity changes.

## 6. Host

### ADB control plane

`Adb` runs the installed `adb` binary through `ProcessBuilder`. Every method takes a serial
(`install`, `run`, `runResult`, `forward` (tcp:0 → returns the host port), `removeForward`,
`forwards`, `processIds`). There is no bulk forward removal.

### Session journal and lease

`SessionJournalStore(root=~/.tap/sessions, serial)` takes a machine-wide file lock per serial
and writes an fsync'd, atomically replaced JSON journal with `JournalState`
(`CREATING → ACTIVE → READY → CLOSED`, or `BROKEN`/`QUARANTINED`), boot ID, session ID,
generation, device/host port, driver PID + start token, and driver instance ID. Startup
recovery (`recoverJournal`) removes only exact journal-owned forwards after proving the old
driver identity is gone; anything it cannot prove is quarantined.

### Driver lifecycle and DeviceSession

`DriverLifecycle.kt` holds the start/observe/cleanup procedures that were extracted from the
validation flow: `startDriverWithRetry` (instrumentation launch with the session secret,
port range `27183..27187`, `TAP_READY` marker parsing, port-occupied retry),
`observeProcess`/`processStartToken` (PID + `/proc` start token identity), `connectWithRetry`,
`removeExactForward`, `cleanupInstrumentation`, `forceStopDriverAndVerify`, and
`recoverJournal` (journal-driven orphan cleanup with the `ResetRecoveryAction` decision table
tested in `SessionJournalTest`).

`DeviceSession.open(DeviceSessionConfig)` is the reusable session state machine on top of it:

```text
lease(serial) -> bootId -> recoverJournal -> [install driver APKs] -> journal CREATING
  -> startDriverWithRetry -> forward tcp:0 -> journal ACTIVE(pid, startToken, instanceId)
  -> connectWithRetry -> HEALTH -> journal READY
close(): client.close -> remove exact forward -> cleanupInstrumentation
  -> journal CLOSED, or QUARANTINED(SESSION_CLEANUP_UNCERTAIN) -> release lease
```

Any failure during `open` runs the same cleanup and journals `CLOSED` or `QUARANTINED`
(`SESSION_START_CLEANUP_UNCERTAIN`); `close()` throws after releasing when cleanup could not
be verified, so a test's teardown failure is visible rather than silently leaving state.

### DriverClient

```text
DriverClient(hostPort, sessionId, generation, secret, serial, heartbeatIntervalMs = 5000)
  connect: HELLO -> CHALLENGE -> negotiate -> AUTH -> AUTH_RESULT (HMAC both ways)
  submit(op, selector, ...) : validates selector, allocates ID + writes frame under transportLock
  PendingCommand.await()/cancel()  -> one terminal Response; state NOT_WRITTEN/WRITING/WRITTEN/TERMINAL_RESPONSE
  reader thread: RESPONSE, PONG, BLOB_* demultiplexed by request ID; BlobReceiver verifies artifacts
  heartbeat thread: PING after heartbeatIntervalMs idle (0 disables)
  execute()/executeOrThrow()/screenshot()/ping()/close()
  any read failure or unknown ID poisons the client: mutating in-flight -> INDETERMINATE, queries -> TRANSPORT_LOST
```

`CommandException` is sealed: `RemoteCommandException` (driver error) and
`CommandTransportException` (`TRANSPORT_LOST`/`INDETERMINATE` with the transmission state);
both carry operation, request ID, generation, serial, rendered selector, timeout, and the
code's flags.

### Validation flow (`host` executable, `:host:validation`)

`PhaseZeroMain` is the end-to-end proof of the fault and recovery behaviour the SDK relies
on; it drives `DriverClient` directly so it can inject faults, forge request IDs, and observe
the driver process. Per device, concurrently via `async(Dispatchers.IO)`, it: acquires the lease, recovers the
journal, installs APKs, starts the driver with retry (including a port-occupier fault),
forwards, rejects bad authentication and an unsupported protocol, launches the fixture, and
runs the scenario groups below, each printing a stable marker:

| Marker | Proves |
|---|---|
| `PHASE_0_*` fencing, transport faults, lifecycle, sync, benchmark, disconnect isolation | Phase 0 gates (see `phase-0-progress.md`) |
| `PHASE_1_CANCELLATION_OK` | queued/running cancel, deadline in queue, ping under load, session reusable |
| `PHASE_1_CANCEL_AFTER_MUTATION_OK` | cancel after the mutation gate is ignored; counter advances once |
| `PHASE_1_HEARTBEAT_EXPIRY_OK` | driver poisons and exits when the host stops sending |
| `PHASE_1_SCREENSHOT_OK` | PNG blob reassembled and checksum-verified |
| `PHASE_1_SELECTORS_OK` | `AMBIGUOUS` for every mutating op, limits, relations, regex, scope denial, long tap, clear text (incl. a hinted field) |
| `PHASE_1_OBSERVATION_OK` | `COUNT`, `SNAPSHOT`, `DEVICE_INFO`, `WAIT_APP_VISIBLE`, `PRESS_KEY` validation and back key → `WAIT_GONE` |
| `PHASE_0_LATE_MUTATION_SKIPPED` / quarantine | reboot-based recovery (only without `--no-reboot`) |
| `PHASE_0_OK` | the whole device flow and the instrumentation process ended cleanly |

`--product-probe` reuses the same session infrastructure against an arbitrary installed app
to measure lookup latency and accessibility inventory; it is not a test DSL.

### Public SDK (`:host:sdk`)

```kotlin
val device = Device.connect(DeviceSessionConfig(serial, autPackage, driverApk, driverTestApk))
val app = device.app()                       // autPackage by default
app.install(apk); app.coldLaunch(".MainActivity")
device.element(resId(pkg, "view_button")).tap()           // exactly one match or AMBIGUOUS/NOT_FOUND
device.await(text("View tapped")).visible()               // one driver-side wait RPC
device.element(rawRes("composeList")).scrollUntil(rawRes("item-40"))
app.awaitIdle()                                           // sync-sdk busy state, identity-guarded
device.close()
```

- `Device` wraps one `DeviceSession`; `Timeouts(action 10 s, wait 10 s, lifecycle 30 s,
  poll 100 ms)` are per device and overridable per call.
- `Element` is a `Selector` plus the device; each terminal call is one `executeOrThrow`,
  which turns driver errors into `RemoteCommandException` (code, detail, selector, request
  identity) and transport loss into `CommandTransportException`.
- `ElementWait.visible()/gone()` and `Device.awaitAppVisible` are driver-side
  (`WAIT_VISIBLE`/`WAIT_GONE`/`WAIT_APP_VISIBLE`); a `WAIT_TIMEOUT` becomes
  `WaitTimeoutException` with elapsed time and the selector. Property waits (`enabled`,
  `textEquals`, `count(n)`, …) and `Device.awaitUntil` poll from the host via `SNAPSHOT`/
  `COUNT`.
- `App` is ADB-side lifecycle (`pm`, `am`, `cmd package resolve-activity`) plus
  `WAIT_APP_VISIBLE` after launch; `forceStop`/`clearData` verify the process is gone;
  `coldLaunch` returns the new `ProcessObservation`; `awaitIdle` bootstraps the sync identity
  once (`SYNC_BOOTSTRAP`) and fails on any process identity change (`AppLifecycleException`).
- `Selectors.kt` builds the protocol `Selector` AST; nothing in the SDK bypasses
  `SelectorValidation`.
- The API is synchronous. Multi-device tests fan out with threads; the coroutine `tapTest`
  façade from plan §12 is not built yet (see `framework-gaps.md`).

### JUnit 5 integration (`:host:junit5`)

```kotlin
@TapTest
class CheckoutTest {
    @Test fun buys(device: Device) { ... }                       // implicit role "device"
    @Test @TapDevices("sender", "receiver") fun sync(devices: Devices) { ... }
}
```

`TapExtension` (`BeforeEachCallback`, `AfterEachCallback`, `ParameterResolver`,
`TestExecutionExceptionHandler`) collects roles from `@TapDevices` (method or class),
`@TapDevice` parameters, and bare `Device` parameters; skips the test (assumption) when
`tap.serials` lists fewer devices than roles; acquires all roles from the shared
`DevicePool` atomically with `tap.acquireTimeoutSeconds`; opens every session in parallel
(driver APKs installed once per JVM per serial, un-marked on failure); stores them in a
per-method namespace; on a test failure captures `<artifactsDir>/<class>/<method>/
<role>-<serial>.png|.xml|.device-info.txt|.driver.log` plus `failure.txt` while sessions are
live; then closes sessions and releases the pool. Cleanup failures are attached to the
primary failure, or rethrown when the test itself passed. `TapConfig.current` reads
`tap.serials`, `tap.device.<role>` (pinning), `tap.autPackage`, `tap.driverApk`,
`tap.driverTestApk`, `tap.artifactsDir`, `tap.acquireTimeoutSeconds` (system property first,
then `TAP_*` environment).

Class-level JUnit parallelism is safe: the pool serialises devices, and the sample suite
runs its classes concurrently across two devices.

## 7. Synchronization SDK

`sync-sdk` is what a product app adds to its E2E/debug build:

```kotlin
// build.gradle.kts of the AUT
debugImplementation(project(":sync-sdk"))   // never in release

// in app code around asynchronous work tests must wait for
TapSynchronization.busy().use { repository.refresh() }
```

The library manifest declares the signature permission
`com.company.tap.permission.SYNCHRONIZATION` and the exported provider at
`${applicationId}.tap-sync`; the app's test build must be signed with the same certificate
as the driver. The SDK contains no test-fixture logic; the fixture's late-mutation hook is a
separate `FixtureFaultProvider` in `fixture-app`.

## 8. Fixture application

The fixture exists only to exercise the driver. It covers Views and Compose
(`testTagsAsResourceId` so `Modifier.testTag("composeButton")` is `By.res("composeButton")`),
direct and key-event text fields with an `OnKeyListener` proof, a Compose `LazyColumn` and a
native `ListView` with end-of-content, a real runtime permission dialog, a port occupier for
startup-retry faults, `AmbiguityActivity` (duplicate buttons/fields/scroll views, a
long-press-aware gesture target, a prefilled field), and the delayed-mutation fault provider.

## 9. Test strategy

| Layer | Where | Count | What |
|---|---|---:|---|
| Protocol | `protocol/src/test` | 5 classes | framing bounds, canonical JSON, negotiation/transcript, error taxonomy, selector validation, golden fixtures (every operation and error code) |
| Execution engine | `driver/command-engine/src/test` | 23 | ordering, overload, cancel states, mutation gate, deadlines, watchdog, heartbeat, blob streaming, shutdown |
| Host client | `host/src/test` | 17 + 6 | real handshake against `FakeDriverServer`: demux, cancel, ping/heartbeat, transport-loss classification, blob corruption; journal atomicity |
| Device | `host --no-reboot <serials> <apks>` | – | every `PHASE_*` marker on API 29 (Samsung SM-J810G) and API 34 (emulator) |
| Device, SDK | `:samples:fixture-tests:test -Ptap.serials=…` | 9 | public API + JUnit extension end to end, two-device concurrency |
| Device, destructive | `host <serials> <apks>` | – | adds the late-mutation quarantine + reboot recovery |

Build and JVM tests:

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
./gradlew :protocol:test :driver:command-engine:test :host:test :host:validation:installDist \
  :driver:assembleDebug :driver:assembleDebugAndroidTest :fixture-app:assembleDebug
./gradlew :samples:fixture-tests:test -Ptap.serials=emulator-5554,85e49002
```

## 10. Current maturity and what is not built

Implemented and device-proven: dedicated driver process; authenticated bounded RPC with
negotiated version/capabilities; selector AST with native and traversal plans;
ambiguity-safe mutations; tap/long tap/swipe/scroll/scroll-until/text input/clear/waits;
screenshots as checksummed blobs; execution lanes with cancellation, deadlines, watchdog,
heartbeat expiry, and poisoning; typed error taxonomy; signature-protected synchronization
with restart invalidation; durable journals, leases, orphan recovery, and quarantine;
transport-loss classification without replay; multi-device concurrency and disconnect
isolation.

Also implemented and device-proven (2026-09-18): observation/key operations (`DEVICE_INFO`,
`PRESS_KEY`, `COUNT`, `SNAPSHOT`, `WAIT_GONE`, `WAIT_APP_VISIBLE`); a reusable
`DeviceSession` state machine; the public `Device`/`App`/`Element` API with lazy elements and
the selector DSL; the all-or-none device pool; the `@TapTest` JUnit 5 extension with failure
artifacts; and a sample suite that passes on two devices concurrently.

Not yet built — see [`framework-gaps.md`](framework-gaps.md) for the full, per-section list:
`session.shutdown`, `wait.screenStable`, `inspector.snapshot`, crash/ANR codes, provider
visibility for non-fixture AUTs, the coroutine `tapTest` façade and `DeviceBarrier`, device
constraints in the pool, fake-ADB JVM coverage for the SDK, logcat/dumpsys/JSONL/HTML
artifacts and reports, per-test deadlines and the remaining JUnit contracts, CI lanes.

`PhaseZeroMain.kt` (≈2 300 lines) remains validation code, not framework code; it should
keep exercising faults the SDK cannot inject, and nothing outside `:host:validation` may
depend on it.
