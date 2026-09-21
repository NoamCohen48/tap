# Tap Project Architecture

Date: 2026-09-19

Status: current implementation overview after Phase 1 (contract and driver), the first
usable cut of Phase 2 (host session service, Kotlin/JUnit 5 and Python clients). The normative future design remains
[`android-e2e-framework-implementation-plan.md`](android-e2e-framework-implementation-plan.md);
the exact wire contract is [`protocol-contract.md`](protocol-contract.md); progress is in
[`phase-1-progress.md`](phase-1-progress.md) and the remaining delta to the plan in
[`framework-gaps.md`](framework-gaps.md).

## 1. Overview

Tap is a host-driven Android E2E framework made of three components: language clients
(the test-facing API), one host service per machine (ADB control plane, sessions, device
pool), and a small dedicated driver on each Android device. An optional library inside the
application under test (AUT) exposes busy state. The repository is laid out along those
lines: `contracts/` (what the components agree on), `device/`, `host/`, `clients/`.

```text
Test process (any language)
+-- clients/kotlin   :clients:kotlin:sdk (Device / App / Element / waits / selectors), :clients:kotlin:junit5 (@TapTest)
+-- clients/python   tap-e2e (same API in Python), pytest plugin
        |
        | gRPC over loopback  (contracts/api/proto/*.proto, package tap.v1)
        v
Host service `tap serve` (one per machine, JVM dist or GraalVM native image)
+-- :host:service    connections (liveness via Attach), device list, sessions, Execute proxy, App lifecycle RPCs
+-- :host:core       Adb, SessionJournal, DriverLifecycle, DeviceSession, DriverClient, AppLifecycle
+-- :host:validation `host` executable: PhaseZeroMain fault/validation flow, ProductProbe (uses :host:core directly)
        |
        | adb forward tcp:<host> tcp:27183   (one TAP1 socket per device session; contracts/protocol)
        v
Android driver instrumentation  (package com.company.tap.driver, own UID/process)  device/driver
+-- TapDriverServer     loopback listener, session config, TAP_READY/TAP_POISONED markers
+-- ClientConnection    handshake, framing, reader lane, heartbeat
+-- CommandPipeline     bounded queue -> single executor -> writer, watchdog   (pure JVM, device/driver/command-engine)
+-- DriverCommandEngine request/selector validation, dispatch
+-- SelectorCompiler    AST -> window-scoped BySelector | traversal predicate
+-- UiObjectAccess      limit-aware resolution, no retained handles
+-- UiAutomationCommands taps, gestures, text, waits, scroll, screenshot
+-- SyncProviderClient  reads AUT busy state over ContentResolver.call
        |                                                  |
        | UiAutomation / accessibility                     | content://<aut>.tap-sync
        v                                                  v
Application under test  (any package)          device/sync-sdk: TapSynchronization + provider
```

ADB is used for setup, lifecycle, forwarding, and recovery. Ordinary UI commands travel over
the persistent RPC connection; Tap never launches an ADB process per action.

## 2. Design principles

These are the invariants the code is organized around (see `CLAUDE.md` for the short list):

| Principle | Where it is enforced |
|---|---|
| Driver is a separate package from the AUT and survives AUT force-stop/clear-data | `device/driver/` manifest; lifecycle checks in `PhaseZeroMain` and `LifecycleTest` |
| No hierarchy dump or XPath on the selector hot path | `SelectorCompiler` + `UiObjectAccess` use `BySelector`/tree walk only; `DUMP_HIERARCHY` is diagnostic |
| No persistent `UiObject2` handles across commands | every command resolves and recycles inside `UiAutomationCommands.gesture/editText` |
| Mutations require exactly one match; `AMBIGUOUS`/`NOT_FOUND` before input | `UiObjectAccess.resolve(EXACTLY_ONE)` fetches two matches; checked before the mutation gate |
| Never replay a transmitted mutation; transport loss after acceptance is `INDETERMINATE` | `DriverClient` state `WRITTEN` + `isMutating`; `CommandTransportException` |
| Request IDs strictly increasing per generation; old generations rejected | `DriverClient` transport mutex; `ClientConnection` watermark; `SESSION_MISMATCH` |
| Every ADB call is serial-specific; never `forward --remove-all` | `Adb` API takes `serial` on every method; `removeExactForward` |
| Elements are lazy selectors; creating one performs no I/O | client `Element` holds a proto `Selector`; every terminal call is one `Execute` that resolves again on the driver |
| One device is never assigned to two tests | the per-serial file lock (`SessionJournalStore.acquireLease`) held by every live `DeviceSession`, across processes; `Open` waits for it or fails; nothing else to acquire (`pool-and-leases.md`) |
| Clients hold no lifecycle logic; the host service never depends on a client | `clients/*` depend on `:contracts:api` only; `:host:service` depends on `:host:core` + `:contracts:api` |
| Bounded everything: payload 1 MiB, text 256 chars, timeout 120 s, selector depth/nodes/strings, artifact 64 MiB, queue 16 | constants in `protocol/Messages.kt`, `Selector.kt`, `Blob.kt`; `CommandPipeline` |
| Both sides validate the same selector rules | `SelectorValidation` is called by `DriverClient` before an ID is allocated and by `DriverCommandEngine` before any lookup |
| Failure taxonomy is closed and typed, with may-have-mutated/retryable flags | `ErrorCode` enum, `ErrorDetail` constants, `Response.failure` |

## 3. Repository layout

```text
tap/
+-- build.gradle.kts             root plugins (AGP 9.0.1, Kotlin 2.3.20), no logic
+-- settings.gradle.kts          includes, grouped: contracts / device / host / clients / samples (list below)
+-- gradle.properties, gradlew*, gradle/wrapper/
+-- CLAUDE.md                    working rules for agents/contributors
+-- README.md                    build/run instructions
+-- THIRD_PARTY_NOTICES.md       copied/adapted upstream code (currently none)
+-- .docs/                       design, plan, contract, progress, audits (internal)
+-- archive/                     code removed from the product but kept for reference; never on a build path (pool-roles/: the service-side role/constraint matcher; pool-leases/: the Acquire/Release lease table)
+-- docs/, mkdocs.yml            public documentation site: guide/ + reference/ (Kotlin via Dokka,
|                                Python via mkdocstrings, gRPC via protoc-gen-doc; generated files are
|                                ignored); scripts/build-docs.sh builds the site and a Markdown bundle
|                                (Dokka GFM + lazydocs), .github/workflows/docs.yml publishes both
|
+-- contracts/                   what the three components agree on
|   +-- protocol/                :contracts:protocol — TAP1 device wire contract, pure Kotlin/JVM, shared by host and driver
|   |   +-- src/main/kotlin/com/company/tap/protocol/
|   |   |   +-- Messages.kt          FrameType, Direction, StabilitySignal, handshake models, ElementSnapshot, DeviceInfo, SyncState, limits, key codes
|   |   |   +-- Commands.kt          Request envelope; sealed Command (one class per op, `op` discriminator), Mutation/Targeted, Returning<R>, CommandHandler + dispatch, RequestDecoder
|   |   |   +-- Results.kt           sealed CommandResult (`kind`), Response.Ok/Error (`type`), CommandFailure
|   |   |   +-- Selector.kt          Selector(node, scope, pick); sealed Node (match/flag/resource/related/all_of/any_of), Scope, Pick; factories, and/or
|   |   |   +-- SelectorValidation.kt shared structural validation -> NATIVE | TRAVERSAL plan kind (regex, any_of, repeated single-valued slot)
|   |   |   +-- ErrorCode.kt         closed error taxonomy + ErrorDetail sub-reasons
|   |   |   +-- Blob.kt              BlobStart/BlobEnd/ArtifactInfo, chunk encoding, SHA-256
|   |   |   +-- FrameCodec.kt        TAP1 header encode/decode, bounds checks
|   |   |   +-- CanonicalJson.kt     canonical handshake JSON + the shared Json codec
|   |   |   +-- Authentication.kt    Hello/Challenge/Negotiation, HMAC domains, transcript
|   |   +-- src/test/kotlin/...      FrameCodecTest, ProtocolContractTest, ErrorCodeTest, SelectorTest, GoldenMessageTest
|   |   +-- src/test/resources/golden/  60 golden request/response JSON fixtures (one per command, per result kind, per error code)
|   +-- api/                     :contracts:api — host service API; protobuf/gRPC Java codegen (java-library)
|       +-- proto/               package tap.v1, one file per concern (selector, command, connection, device, session, app); single source for the Kotlin stubs (Gradle) and the Python stubs (gen_stubs.py)
|       +-- BREAKING_BASELINE     commit before which CI skips `buf breaking` (the deliberate 2.0 break)
|
+-- device/                      what runs on the Android device
|   +-- driver/                  :device:driver — Android; the on-device driver
|   |   +-- src/main/AndroidManifest.xml   empty app shell (package com.company.tap.driver, <queries> for sync/fault providers)
|   |   +-- src/androidTest/kotlin/com/company/tap/driver/
|   |   |   +-- TapDriverServerTest.kt   instrumentation entry point (keeps the process alive)
|   |   |   +-- TapDriverServer.kt       SessionConfig from instrumentation args, listener, markers
|   |   |   +-- ClientConnection.kt      per-connection handshake, frame reader, blob writer
|   |   |   +-- DriverCommandEngine.kt   request validation + dispatch table
|   |   |   +-- SelectorCompiler.kt      AST -> CompiledSelector.Native | .Traversal
|   |   |   +-- UiObjectAccess.kt        resolve/hasObject/count/containerHasObject per MatchLimit
|   |   |   +-- UiAutomationCommands.kt  tap, longTap, pressKey, swipe, scroll, scrollUntil, waitVisible/Gone/AppVisible, set/type/clearText, snapshot, deviceInfo, screenshot, dumpHierarchy
|   |   |   +-- SyncProviderClient.kt    signature-checked ContentProvider reads with timeout
|   |   |   +-- FaultController.kt       test-only fault injection (transport loss, late work, cancel-after-mutation)
|   |   +-- command-engine/      :device:driver:command-engine — pure Kotlin/JVM execution state machine (no Android types)
|   |       +-- src/main/kotlin/com/company/tap/driver/engine/
|   |       |   +-- CommandPipeline.kt   queue, executor, writer, watchdog, poison, heartbeat, blob streaming
|   |       |   +-- CommandContext.kt    deadline, checkpoint/sleep, mutation gate, transferBlob
|   |       |   +-- Command.kt           per-request state (QUEUED/RUNNING/TERMINAL) + single terminal response
|   |       |   +-- BlobTransfer.kt      chunking, checksums, outcome (COMPLETED/CANCELLED/DEADLINE/WRITE_FAILED)
|   |       |   +-- Outbound.kt          writer-lane frames (Response, Pong, BlobStart/Chunk/End)
|   |       |   +-- CommandInterrupted.kt, Clock.kt
|   |       +-- src/test/kotlin/...      CommandPipelineTest (23 tests)
|   +-- sync-sdk/                :device:sync-sdk — Android library an AUT ships in its E2E/debug build
|       +-- src/main/AndroidManifest.xml  signature permission + provider at ${applicationId}.tap-sync
|       +-- src/main/kotlin/com/company/tap/sync/
|           +-- TapSynchronization.kt          busy() handles, generation, identity snapshot
|           +-- TapSynchronizationProvider.kt  ContentResolver.call("state") -> Bundle
|
+-- host/                        what runs on the host machine (no test DSL)
|   +-- core/                    :host:core — Kotlin/JVM library: session infrastructure
|   |   +-- src/main/kotlin/com/company/tap/host/
|   |   |   +-- Adb.kt               every ADB command as a typed method (`open class Adb`, serial-specific, parsing inside); raw `run` is `@RawAdb` opt-in, allowed only in :host:validation
|   |   |   +-- SessionJournal.kt    JournalState, SessionJournal, SessionJournalStore (lease + fsync'd atomic write)
|   |   |   +-- DriverLifecycle.kt   start-with-retry, port range, forward, process observation, journal recovery, cleanup
|   |   |   +-- DeviceSession.kt     DeviceSessionConfig + DeviceSession.open()/close(): lease -> recover -> install -> start -> forward -> connect -> READY; app(pkg): one AppLifecycle per package for the session
|   |   |   +-- DriverClient.kt      handshake, request IDs, reader/heartbeat coroutines, PendingCommand, screenshot()
|   |   |   +-- AppLifecycle.kt      install/uninstall/forceStop/clearData/grantPermission/launch/coldLaunch/process/awaitAppVisible/awaitIdle (ADB + driver waits)
|   |   |   +-- BlobReceiver.kt      verifying blob reassembly
|   |   |   +-- CommandException.kt  RemoteCommandException / CommandTransportException, selector rendering
|   |   +-- src/test/kotlin/...      DriverClientTest (17), SessionJournalTest (6), FakeDriverServer
|   +-- service/                 :host:service — gRPC host session service, `tap` executable (JVM dist + GraalVM native image)
|   |   +-- build.gradle.kts         bundles the driver APKs as resources, native-image config
|   |   +-- src/main/kotlin/com/company/tap/service/
|   |   |   +-- ServiceMain.kt       CLI: serve | status | stop | version; service.json descriptor
|   |   |   +-- TapService.kt        one atomic connection/session lifecycle boundary, device list, bounded teardown, transport-loss-as-data
|   |   |   +-- servicer/
|   |   |   |   +-- ConnectionServicer.kt  coroutine Open/Close/Info + exactly-one Flow Attach liveness
|   |   |   |   +-- DeviceServicer.kt      coroutine device-list adapter
|   |   |   |   +-- SessionServicer.kt     coroutine session/Execute/artifact adapter; gRPC cancel propagates
|   |   |   |   +-- AppServicer.kt         coroutine AppLifecycle adapter
|   |   |   |   +-- common.kt              suspend reply wrapper and unchanged exception/status mapping
|   |   |   +-- Conversions.kt       proto <-> protocol extension functions (toProto / toCommand / toSelector / toResponse)
|   |   |   +-- BundledDriver.kt     extracts the embedded driver APKs per build id
|   |   +-- src/main/resources/META-INF/native-image/  reachability metadata recorded with the tracing agent
|   |   +-- src/test/kotlin/...      EnumMirrorTest, GoldenRoundTripTest, AutResourceTest, TapServiceLifecycleTest
|   +-- validation/              :host:validation — application `host` (exe): the destructive/fault validation flow
|       +-- src/main/kotlin/com/company/tap/host/validation/
|           +-- PhaseZeroMain.kt     multi-device validation flow + fault scenarios, PHASE_* markers
|           +-- ProductProbe.kt      `host --product-probe`: latency/inventory probe for arbitrary apps
|
+-- clients/                     test-facing APIs; every client is a gRPC client of the host service
|   +-- kotlin/
|   |   +-- sdk/                 :clients:kotlin:sdk — public Kotlin API (package com.company.tap.sdk)
|   |   |   +-- src/main/kotlin/com/company/tap/sdk/
|   |   |       +-- TapClient.kt         TapClient (channel, stubs, devices, connect), Connection (attach/availableSerials/openDevice), ServiceDiscovery (descriptor lookup), TapServiceProcess (`tap start`/`tap stop`)
|   |   |       +-- Device.kt            Device.open(run, serial, …), execute/element/await/app/info/pressKey/screenshot/dumpHierarchy/driverLog/awaitUntil, Timeouts, DeviceOptions
|   |   |       +-- App.kt               install/uninstall/forceStop/clearData/grantPermission/launch/coldLaunch/process/awaitIdle over AppService
|   |   |       +-- Element.kt           lazy element: exists/count/snapshot/text, tap/longTap/setText/typeText/clearText/swipe/scroll/scrollUntil, first/at/descendant/child
|   |   |       +-- ElementWait.kt       visible()/gone() (driver-side) and enabled/checked/focused/textEquals/count (host-polled)
|   |   |       +-- Selectors.kt         text/textContains/textMatches/desc/hint/resId/rawRes/className + refinements, relations, infix and/or, over the proto Selector
|   |   |       +-- TapExceptions.kt     TapException, ServiceException, CommandException (proto ErrorCode), WaitTimeoutException, AppLifecycleException
|   |   +-- junit5/              :clients:kotlin:junit5 — JUnit 5 integration (package com.company.tap.junit5)
|   |       +-- src/main/kotlin/com/company/tap/junit5/
|   |           +-- Annotations.kt       @TapTest, @TapDevice(role), @TapDevices(roles), Devices
|   |           +-- TapConfig.kt         tap.* system properties / TAP_* env: serials (optional), autPackage, artifactsDir, acquire timeout, pinned roles
|   |           +-- TapConnection.kt     one TapClient + Connection per JVM (lazy, closed by a shutdown hook)
|   |           +-- TapExtension.kt      BeforeEach/AfterEach/ParameterResolver/ExceptionHandler; roles→serials, opens in sorted serial order; failure artifacts
|   +-- python/                  tap-e2e: Python client + pytest plugin (thin layer over the service)
|       +-- pyproject.toml, README.md
|       +-- scripts/gen_stubs.py     regenerates tap/_gen from contracts/api/proto/*.proto; --check for CI
|       +-- tap/_gen/                committed generated stubs (<file>_pb2, <file>_pb2_grpc, .pyi); the package re-exports them all
|       +-- tap/{service,device,element,app,selectors,errors}.py   Service/Connection, Device, Element/ElementWait, App, selector DSL, typed errors
|       +-- tap/pytest_plugin.py     tap_device / tap_devices fixtures, @pytest.mark.tap_devices, failure artifacts
|       +-- tests/                   the sample suite ported to pytest (conftest = fixture facts)
|
+-- samples/fixture-tests/       JUnit 5 sample suite against the fixture app (real devices, through the service)
|   +-- build.gradle.kts         `test` depends on the fixture APK + service dist, maps -Ptap.serials to system properties, disabled without serials
|   +-- src/test/kotlin/com/company/tap/samples/
|       +-- Fixture.kt           fixture facts + install-once/cold-launch helper
|       +-- MainScreenTest.kt    taps, text input, Compose list scrolling, ambiguity, app-owned sync, wait diagnostics, back key
|       +-- LifecycleTest.kt     cold launch identity, force-stop, clear-data, DEVICE_INFO
|       +-- MultiDeviceTest.kt   @TapDevices("left","right") concurrent two-device journey
|       +-- MotionTest.kt        awaitAnimationEnd / awaitAppSettled: wait out an animation, time out on a ticking screen
|
+-- .github/                     CI (ci.yml) and tag-driven releases (release.yml, scripts/release_version.py); see release-engineering.md
+-- fixture-app/                 Android app used only by the validation flow and the samples
    +-- src/main/AndroidManifest.xml
    +-- src/main/kotlin/com/company/tap/fixture/
    |   +-- MainActivity.kt          View + Compose controls, key-event field, LazyColumn, busy() demo
    |   +-- ViewListActivity.kt      native ListView with end-of-content
    |   +-- AmbiguityActivity.kt     duplicate buttons/fields/scroll views, gesture target, prefilled field
    |   +-- PermissionActivity.kt    real runtime permission dialog
    |   +-- MotionActivity.kt        2 s handler-driven animation and an endless 100 ms ticker (screen-stability waits)
    |   +-- PortOccupierActivity.kt  occupies the driver port for startup-retry faults
    |   +-- FixtureFaultProvider.kt  delayed-mutation hook for the late-work fault (authority ...fixture.fault)
    |   +-- FixtureApplication.kt    Application + FaultTapCounter
    +-- src/main/res/layout/         activity_main, activity_view_list, activity_ambiguity, activity_permission, activity_motion
```

Gradle projects: `:contracts:protocol`, `:contracts:api`, `:device:driver`,
`:device:driver:command-engine`, `:device:sync-sdk`, `:host:core`, `:host:service`,
`:host:validation`, `:clients:kotlin:sdk`, `:clients:kotlin:junit5`, `:fixture-app`,
`:samples:fixture-tests`. `clients/python` is a plain Python package.

Dependency direction:

```text
samples:fixture-tests --> clients:kotlin:junit5 --> clients:kotlin:sdk --> contracts:api   (gRPC at run time)
clients/python (tap-e2e) ---------------------------------------------> contracts:api   (committed stubs; gRPC at run time)
host:service (tap) --> host:core --> contracts:protocol
        \----------> contracts:api
host:validation ---> host:core
device:driver androidTest --> device:driver:command-engine --> contracts:protocol
fixture-app --------------> device:sync-sdk
```

- Nothing under `host/` references `clients/`; the clients know the service only through
  `contracts/api`. `:host:core` is the single implementation of ADB control, journals,
  leases, driver lifecycle and app lifecycle, and only `:host:service` and `:host:validation`
  link it.
- `contracts:protocol` has no Android, host, or coroutine dependency (kotlinx.serialization +
  RE2/J only). `contracts:api` generates Java stubs plus grpc-kotlin coroutine stubs
  (`*CoroutineImplBase`, `*CoroutineStub`) and carries `grpc-kotlin-stub` +
  `kotlinx-coroutines-core` as `api` dependencies.
- `device:driver:command-engine` has no Android types so the state machine is JVM-tested.
- `device:sync-sdk` depends on nothing from Tap; the driver reaches it only through a
  provider call.
- `host:core` and the service are coroutine-based (`suspend` throughout, structured scopes);
  they have no Android API dependency. The Kotlin clients are coroutine-based too (step 4):
  `suspend` over grpc-kotlin stubs with `tapTest`/`tapScope` ownership. Only the validation
  executable fans out per device, as before.
- Product test suites depend on `:clients:kotlin:junit5` (which exposes `:clients:kotlin:sdk`
  and `:contracts:api` as `api`) and never on `:host:*`.

Generated artifacts:

```text
host/validation/build/install/host/bin/host
host/service/build/install/tap/bin/tap                 JVM distribution of the service
host/service/build/native/nativeCompile/tap            GraalVM native image of the service
device/driver/build/outputs/apk/debug/driver-debug.apk
device/driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk
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
| `Request` | envelope: `sessionId`, `generation`, `timeoutMs`, `command` |
| `Command` | sealed, one class per operation with its own fields and range checks (`Health`, `Exists(selector)`, `SetText(selector, text)`, `ScrollUntil(selector, container, …)`, `SyncPoll(…)` …), discriminated by `op`; `Mutation` / `Targeted` markers; `Returning<R>` fixes the result type |
| `CommandHandler` | one typed method per command; `Command.dispatch(handler)` is the exhaustive switch the driver implements |
| `Response` | `Ok(result, durationMs)` or `Error(code, detail?, message?, durationMs)`, discriminated by `type` |
| `CommandResult` | sealed, discriminated by `kind`: `Done`, `BoolResult`, `Moved`, `CountResult`, `TextResult`, `SnapshotResult`, `DeviceInfoResult`, `ArtifactResult`, `SyncResult` |
| `Selector` / `Node` / `Scope` / `Pick` | the AST (below) |
| `ErrorCode` | closed taxonomy; each code has `mayHaveMutated` and `retryable` |
| `BlobStart` / `BlobEnd` / `ArtifactInfo` | binary transfer envelope and checksum |
| `Hello` / `Challenge` / `Negotiation` / `Authentication(Result)` | handshake |

### Selector AST

```text
Selector(node: Node, scope: Scope = Aut, pick: Pick = ExactlyOne)
Scope = Aut | System(packageName)                         `kind`: aut | system
Pick  = ExactlyOne | First | At(index >= 0)               `kind`: exactly_one | first | at
Node  = Match(property: TEXT|CONTENT_DESCRIPTION|HINT|CLASS_NAME, value, mode = EXACT)   `kind`: match
      | Flag(property: ENABLED|CHECKED|…|SELECTED, value = true)                          `kind`: flag
      | Resource(name, packageName?)                                                      `kind`: resource
      | Related(relation: PARENT|ANCESTOR|CHILD|DESCENDANT, node)                         `kind`: related
      | AllOf(nodes >= 2) | AnyOf(nodes >= 2)                                             `kind`: all_of | any_of
```

Factories: `Selector.text(v, mode)`, `.contentDescription(v)`, `.rawResource(name)`,
`.androidResource(pkg, name)`, `.inSystemPackage(pkg)`, `.first()`, `.at(i)`; `Node.text/…/child/descendant`,
`Node.allOf`/`anyOf` and infix `and`/`or` (flattening, single operand returned as is).
`SelectorValidation.validate` returns `NATIVE` (everything expressible in one `BySelector`) or
`TRAVERSAL` (any `REGEX`, any `any_of`, or a conjunction repeating a single-valued `BySelector`
slot), or throws `InvalidSelectorException(detail)`.

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
UiObjectAccess.resolve(pick):
  ExactlyOne -> fetch up to 2 -> 0: NOT_FOUND, 2: AMBIGUOUS
  First      -> first in accessibility order
  At(n)      -> nth or NOT_FOUND
```

Scope rules: `Aut` selectors resolve in the expected AUT package; `System` selectors must name
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
DriverClient.connect(hostPort, sessionId, generation, secret, serial, heartbeatIntervalMs = 5000)
  connect: HELLO -> CHALLENGE -> negotiate -> AUTH -> AUTH_RESULT (HMAC both ways)
  submit(op, selector, ...) : validates selector, allocates ID + writes frame under the transport Mutex
  PendingCommand.await()/cancel()  -> one terminal Response; state NOT_WRITTEN/WRITING/WRITTEN/TERMINAL_RESPONSE
  cancelling await() sends CANCEL but keeps the entry registered until the terminal frame arrives
  reader coroutine: RESPONSE, PONG, BLOB_* demultiplexed by request ID; BlobReceiver verifies artifacts
  heartbeat coroutine: PING after heartbeatIntervalMs idle (0 disables)
  execute()/executeOrThrow()/screenshot()/ping()/close() are suspend; close() is NonCancellable
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

### App lifecycle (`AppLifecycle`)

`AppLifecycle(session, packageName)` is the one implementation of AUT lifecycle for every
client: ADB-side `pm`/`am`/`cmd package resolve-activity` for install, uninstall, launch,
force-stop, clear-data and permission grants, `WAIT_APP_VISIBLE` on the driver after launch,
process observation (`coldLaunch` returns the new `ProcessObservation`; `forceStop`/
`clearData` verify the process is gone), and `awaitIdle` over the sync provider
(`SyncBootstrap` once, then `SyncPoll` until stable; any process identity change is an
`AppLifecycleException`). The service keeps one per (session, package).

### Host session service (`:host:service`)

`tap serve` exposes `:host:core` over loopback gRPC (`contracts/api/proto/*.proto`,
package `tap.v1`) so every client — Kotlin and Python alike — reuses the same ADB control
plane, journals, leases, driver lifecycle and app operations. Its generated grpc-kotlin
servicers use suspend unary methods and a `Flow` for `Attach`; the shared suspend reply wrapper
preserves caller cancellation and keeps the established exception/status mapping. It lists the
devices (`adb devices`, `LEASED` by probing the per-serial lock, quarantine read from the
journal) but leases nothing itself: exclusive use is the lock a live `DeviceSession` holds, and
`Open` can wait for it (`pool-and-leases.md`). It proxies `Execute` to the session's
`DriverClient` (driver failures and transport loss are returned as `CommandResult` data, never
gRPC errors; gRPC cancellation forwards a protocol `CANCEL`).

One short synchronized lifecycle boundary owns connection close state, the sole Attach
registration, in-flight opens, and global/per-connection session registration/removal; no
monitor crosses suspension. If close wins while a device open is suspended, the completed
`DeviceSession` is closed before exposure. Attach drop, explicit Close and service shutdown all
remove each session once before bounded cleanup. The shutdown hook divides one total deadline
across connection/session cleanup and gRPC termination; timed-out core cleanup continues under
its own `DeviceSession.close(timeoutMs)` deadline so quarantine/lease finalization can finish
without preventing later attempts. The driver APKs are embedded. The full contract is
`service-api.md`.

## 7. Clients

Every client is a gRPC client of the service and contains no ADB, journal, lease or driver
lifecycle code; the language-facing shape is the same in Kotlin and Python.

### Kotlin client (`:clients:kotlin:sdk`)

```kotlin
val client = TapClient.create()                    // explicit address or resolved service
val connection = client.connect("checkout")         // Attach stream = liveness (owned scope)
val serial = connection.availableSerials().first()   // the service leases nothing; the session holds the device lock
val device = connection.openDevice(serial, autPackage)
val app = device.app()                        // autPackage by default
app.install(apk); app.coldLaunch(".MainActivity")
device.element(resId(pkg, "view_button")).tap()           // exactly one match or AMBIGUOUS/NOT_FOUND
device.await(text("View tapped")).visible()               // one driver-side wait RPC
device.element(rawRes("composeList")).scrollUntil(rawRes("item-40"))
app.awaitIdle()                                           // sync-sdk busy state, identity-guarded
device.close(); connection.close(); client.close()   // all suspend; try/finally in tapScope/tapTest
```

Device work runs inside `tapScope { ... }` (scripts) or `tapTest { ... }` (JUnit); selector
construction (`text(...)`, `res(...)`) is the only non-suspend part.

- The client is a thin gRPC layer over `contracts/api`: no ADB, journals, leases or driver
  lifecycle. `TapClient` owns the channel and the coroutine stubs (`TapClient.create` resolves
  the service); `Connection` is this process's identity at the service (owned attach scope,
  `availableSerials`, `openDevice`); `Device` wraps one service session and
  `Timeouts(action 10 s, wait 10 s, lifecycle 30 s, poll 100 ms)`, overridable per call.
  Every I/O method is `suspend` with per-call `withDeadlineAfter` plus caller-cancellation;
  `close` is `suspend` (no `AutoCloseable`).
- `Element` is a proto `Selector` plus the device; each terminal call is one `Execute`. A
  `CommandResult` failure becomes `CommandException` (proto `ErrorCode`, detail, selector,
  request identity); transport loss reported by the service is the same exception with
  `ERR_TRANSPORT_LOST`/`ERR_INDETERMINATE`. gRPC-level failures are `ServiceException`.
- `ElementWait.visible()/gone()`, `Device.awaitAppVisible` and `Device.awaitScreenStable`
  (with `awaitAppSettled` = tree signal, `awaitAnimationEnd` = pixel signal) are driver-side (`WAIT_VISIBLE`/`WAIT_GONE`/`WAIT_APP_VISIBLE`/`WAIT_SCREEN_STABLE`); a
  `WAIT_TIMEOUT` becomes `WaitTimeoutException` with elapsed time, the selector and the
  driver detail (`SCREEN_CHANGING`/`APP_NOT_VISIBLE` for stability waits). Settling is
  explicit: no action waits for animations on its own. Property waits (`enabled`,
  `textEquals`, `count(n)`, …) and `Device.awaitUntil` poll from the host via `SNAPSHOT`/
  `COUNT`.
- `App` calls `AppService` (`Install`, `Launch`, `ColdLaunch`, `ForceStop`, `ClearData`,
  `GrantPermission`, `Process`, `AwaitIdle`, …); the implementation is `host/core`
  `AppLifecycle`. `FAILED_PRECONDITION` maps to `AppLifecycleException`.
- `Selectors.kt` builds the proto `Selector`; the service converts it to the protocol AST and
  `SelectorValidation` still runs before an ID is allocated.
- `ServiceDiscovery` resolves `tap.service`/`TAP_SERVICE`, then `<state dir>/service.json`
  (alive check); it never starts a service. `TapServiceProcess.start/stop` run `tap start` /
  `tap stop` from `tap.bin`/`TAP_BIN`/`tap` on `PATH`; the JUnit extension does so around a run
  when `tap.manageService` is set (`TapLauncherSessionListener` stops it).
- The API is `suspend` throughout; multi-device tests fan out with `coroutineScope`/`async`
  inside `tapTest`, with `DeviceBarrier` for genuinely simultaneous phases (see `framework-gaps.md`
  for what was removed with the proving tests).

### JUnit 5 integration (`:clients:kotlin:junit5`)

```kotlin
@TapTest
class CheckoutTest {
    @Test fun buys(device: Device) = tapTest { ... }         // implicit role "device"
    @Test @TapDevices("sender", "receiver") fun sync(devices: Devices) = tapTest { ... }
}
```

`TapExtension` (`BeforeEachCallback` with per-test root job, `AfterEachCallback` with cancel +
bounded non-cancellable teardown, `ParameterResolver`, `TestExecutionExceptionHandler`,
`InvocationInterceptor` binding the `tapTest` context) collects roles from `@TapDevices` (method or class),
`@TapDevice` parameters, and bare `Device` parameters; maps roles to serials itself (pinned
by `tap.device.<role>`, then the `tap.serials` order, otherwise the service's device list, free
devices first); skips the test (assumption) when fewer devices exist than roles; opens the sessions one at
a time in sorted serial order through the JVM-wide `TapConnection` (one `TapClient` + `Connection`, closed
by a shutdown hook), each waiting up to `tap.acquireTimeoutSeconds` for a device another
session holds; stores them
in a per-method namespace; test bodies run only inside `tapTest { ... }` (real-time bridge;
binding/nesting enforced, timeout/sibling cancellation via the root job); on a test failure captures `<artifactsDir>/<class>/<method>/
<role>-<serial>.png|.xml|.device-info.txt|.driver.log` plus `failure.txt` while sessions are
live; then closes the sessions, which frees the devices. Cleanup failures are attached to the
primary failure, or rethrown when the test itself passed. `TapConfig.current` reads
`tap.serials` (optional), `tap.device.<role>` (pinning), `tap.autPackage`,
`tap.artifactsDir`, `tap.acquireTimeoutSeconds`, `tap.service`, `tap.manageService`, `tap.bin` (system property
first, then `TAP_*` environment). Driver APKs come from the service's bundle.

Class-level JUnit parallelism is safe: each device's lock serialises its sessions, and the
sample suite runs its classes concurrently across two devices. Several JVMs (or a JVM and a
pytest run) respect each other because the lock is a file under the shared state dir.

### Python binding (`clients/python/`)

`tap-e2e` is a generated gRPC client plus a thin mirror of the Kotlin SDK: `Service`/`Connection`,
`Device`, `Element`/`ElementWait`, `App`, selector builders over the proto `Selector`, and
typed errors (`CommandError` with `ErrorCode`, `WaitTimeoutError`, `AppLifecycleError`,
`ServiceError`). The pytest plugin mirrors `TapExtension`: per-test sessions, all-or-none
roles via `@pytest.mark.tap_devices`, skip when fewer serials are configured, failure
artifacts (screenshot, hierarchy, device info, driver log). Service discovery: `TAP_SERVICE`,
then `<state dir>/service.json`; never auto-start. `start_service`/`stop_service` run `tap start`/`tap stop` from `TAP_BIN`/`tap` on `PATH` (`tap_manage_service` in the plugin).

## 8. Synchronization SDK

`device/sync-sdk` is what a product app adds to its E2E/debug build:

```kotlin
// build.gradle.kts of the AUT
debugImplementation(project(":device:sync-sdk"))   // never in release

// in app code around asynchronous work tests must wait for
TapSynchronization.busy().use { repository.refresh() }
```

The library manifest declares the signature permission
`com.company.tap.permission.SYNCHRONIZATION` and the exported provider at
`${applicationId}.tap-sync`; the app's test build must be signed with the same certificate
as the driver. The SDK contains no test-fixture logic; the fixture's late-mutation hook is a
separate `FixtureFaultProvider` in `fixture-app`.

## 9. Fixture application

The fixture exists only to exercise the driver. It covers Views and Compose
(`testTagsAsResourceId` so `Modifier.testTag("composeButton")` is `By.res("composeButton")`),
direct and key-event text fields with an `OnKeyListener` proof, a Compose `LazyColumn` and a
native `ListView` with end-of-content, a real runtime permission dialog, a port occupier for
startup-retry faults, `AmbiguityActivity` (duplicate buttons/fields/scroll views, a
long-press-aware gesture target, a prefilled field), and the delayed-mutation fault provider.

## 10. Test strategy

| Layer | Where | Count | What |
|---|---|---:|---|
| Protocol | `contracts/protocol/src/test` | 5 classes | framing bounds, canonical JSON, negotiation/transcript, error taxonomy, selector validation, golden fixtures (every operation and error code) |
| Execution engine | `device/driver/command-engine/src/test` | 23 | ordering, overload, cancel states, mutation gate, deadlines, watchdog, heartbeat, blob streaming, shutdown |
| Host client | `host/core/src/test` | 17 + 6 | real handshake against `FakeDriverServer`: demux, cancel, ping/heartbeat, transport-loss classification, blob corruption; journal atomicity |
| Kotlin client | `:clients:kotlin:sdk:test` | 8 (`TapClientTest`) | in-process grpc-kotlin fakes: attach ownership/lifetime, Close-before-drop ordering, Execute cancellation, sibling-cancellation shape, scope enforcement, quarantine report |
| JUnit extension | `:clients:kotlin:junit5:test` | 15 (`TapTestBridgeTest`, `DeviceBarrierTest`) | binding/nesting, root cancellation (failure + thread interruption), fake-RPC sibling cancellation, sorted opens, teardown preservation; barrier release/reuse/one-shot/cancellation |
| Device | `host --no-reboot <serials> <apks>` | – | every `PHASE_*` marker on API 29 (Samsung SM-J810G) and API 34 (emulator) |
| Device, Kotlin client | `:samples:fixture-tests:test -Ptap.serials=…` | 13 | Kotlin API + JUnit extension through a runner-managed service (`tap.manageService`), structured two-device concurrency incl. sibling cancellation without replay |
| Device, Python client | `TAP_BIN=… TAP_MANAGE_SERVICE=1 TAP_SERIALS=… pytest clients/python/tests` | 9 | the same suite through the pytest plugin |
| Service | `:host:service:test` | 4 classes (`EnumMirrorTest`, `GoldenRoundTripTest`, `AutResourceTest`, `TapServiceLifecycleTest`) | proto mirrors/round trips, AUT-resource conversion, deterministic attach/open/close/shutdown races, and in-process gRPC Execute cancellation |
| Device, destructive | `host <serials> <apks>` | – | adds the late-mutation quarantine + reboot recovery |

Build and JVM tests:

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2
./gradlew :contracts:protocol:test :device:driver:command-engine:test :host:core:test :host:validation:installDist \
  :device:driver:assembleDebug :device:driver:assembleDebugAndroidTest :fixture-app:assembleDebug
./gradlew :host:service:test :samples:fixture-tests:test -Ptap.serials=emulator-5554,85e49002
clients/python/scripts/gen_stubs.py --check
```

## 11. Current maturity and what is not built

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
the selector DSL; per-device locks across processes; the `@TapTest` JUnit 5 extension with failure
artifacts; and a sample suite that passes on two devices concurrently.

Also implemented and device-proven (2026-09-19): the gRPC host session service with a
device list, connection liveness and cancel forwarding; its GraalVM native image;
the Python client and pytest plugin; the sample suite ported to pytest and passing on both
devices through the native service; the repository split into `contracts/`, `device/`,
`host/` and `clients/`, with the Kotlin SDK and JUnit extension rewritten as gRPC clients of
the service (the in-JVM `DevicePool` and `Device.connect` are gone; `AppLifecycle` lives in
`host/core`) and the Kotlin sample suite passing on both devices through a runner-managed
service.

Not yet built — see [`framework-gaps.md`](framework-gaps.md) for the full, per-section list:
`session.shutdown`, `inspector.snapshot`, crash/ANR codes, provider
visibility for non-fixture AUTs, the coroutine `tapTest` façade and `DeviceBarrier`, device
fake-ADB JVM coverage for the host core and in-process service tests for the clients,
logcat/dumpsys/JSONL/HTML artifacts and reports, per-test deadlines and the remaining JUnit
contracts, CI lanes.

`PhaseZeroMain.kt` (≈2 300 lines) remains validation code, not framework code; it should
keep exercising faults the clients cannot inject, and nothing outside `:host:validation` may
depend on it.
