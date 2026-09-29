# Tap Project Architecture

Last updated: 2026-09-21

Status: current implementation overview after Phase 1's implemented operation set and the
usable Phase 2 host daemon server, Kotlin/JUnit 5 and Python clients. The normative future design remains
[`android-e2e-framework-implementation-plan.md`](android-e2e-framework-implementation-plan.md);
the exact wire contract is [`protocol-contract.md`](protocol-contract.md); progress is in
[`phase-1-progress.md`](phase-1-progress.md) and the remaining delta to the plan in
[`framework-gaps.md`](framework-gaps.md).

## 1. Overview

Tap is a host-driven Android E2E framework made of three components: language clients
(the test-facing API), one host daemon per machine (ADB control plane, client connections,
attached devices), and a small dedicated driver on each Android device. An optional library inside the
application under test (AUT) exposes busy state. The repository is laid out along those
lines: `contracts/` (what the components agree on), `device/`, `host/`, `clients/`.

```text
Test process (any language)
+-- clients/kotlin   :clients:kotlin:sdk (Device / App / Element / waits / selectors), :clients:kotlin:junit5 (@TapTest)
+-- clients/python   tap-e2e (same API in Python), pytest plugin
+-- clients/agent    tap-agent: CLI + MCP server for coding agents, over tap-e2e (.docs/agent-surface.md)
        |
        | gRPC over loopback  (contracts/proto/*.proto, package tap.v1)
        v
Host server `tap serve` (one per machine, JVM dist or GraalVM native image)
+-- :host:daemon    client connections (liveness via Observe), device list, attached devices, Execute/App RPCs
+-- :host:core       Adb, SessionJournal, DriverLifecycle, DeviceSession, DriverClient/DriverTransport, AppLifecycle
+-- :host:validation deviceTest JUnit suite (faults, fencing, recovery on real devices), tap-product-probe (uses :host:core directly)
        |
        | adb forward tcp:<host> tcp:27183   (one TAP1 socket per device session; contracts/protocol)
        v
Android driver instrumentation  (package io.github.noamcohen48.tap.driver, own UID/process)  device/driver
+-- TapDriverServer     loopback listener, session config, TAP_READY/TAP_POISONED markers
+-- DriverConnection    handshake, framing, reader lane, heartbeat
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
| Driver is a separate package from the AUT and survives AUT force-stop/clear-data | `device/driver/` manifest; lifecycle checks in `AutLifecycleTest` (validation deviceTest) and `LifecycleTest` |
| No hierarchy dump or XPath on the selector hot path | `SelectorCompiler` + `UiObjectAccess` use `BySelector`/tree walk only; `DUMP_HIERARCHY` is diagnostic |
| No persistent `UiObject2` handles across commands | every command resolves and recycles inside `UiAutomationCommands.gesture/editText` |
| Mutations require exactly one match; `AMBIGUOUS`/`NOT_FOUND` before input | `UiObjectAccess.resolve(EXACTLY_ONE)` fetches two matches; checked before the mutation gate |
| Never replay a transmitted mutation; transport loss after acceptance is `INDETERMINATE` | `PendingCommand` transmission state + `DriverTransport` failure routing; `CommandTransportException` |
| Request IDs strictly increasing per generation; old generations rejected | `DriverTransport` admission mutex; `DriverConnection` watermark; `SESSION_MISMATCH` |
| Every ADB call is serial-specific; never `forward --remove-all` | `Adb` API takes `serial` on every method; `removeExactForward` |
| Elements are lazy selectors; creating one performs no I/O | client `Element` holds a proto `Selector`; every terminal call is one `Execute` that resolves again on the driver |
| One device is never assigned to two tests | the per-serial file lock (`SessionJournalStore.acquireLease`) held by every live `DeviceSession`, across processes; `Attach` waits for it or fails; nothing else to acquire (`pool-and-leases.md`) |
| Clients hold no lifecycle logic; the host daemon never depends on a client | `clients/*` depend on `:contracts:api` only; `:host:daemon` depends on `:host:core` + `:contracts:api` |
| Bounded everything: payload 1 MiB, text 256 chars, timeout 120 s, selector depth/nodes/strings, artifact 64 MiB, queue 16 | constants in `protocol/Messages.kt`, `Selector.kt`, `Blob.kt`; `CommandPipeline` |
| Both sides validate the same selector rules | `CommandValidation` dispatches by command type on the host before an ID is allocated; `SelectorCompiler` applies the same selector validation before device lookup |
| Failure taxonomy is closed and typed, with may-have-mutated/retryable flags | `ErrorCode` enum, `ErrorDetail` constants, `Response.failure` |

## 3. Repository layout

```text
tap/
+-- build.gradle.kts             root plugins (AGP 9.0.1, Kotlin 2.3.20), no logic
+-- settings.gradle.kts          includes, grouped: contracts / device / host / clients / samples (list below)
+-- gradle.properties, gradlew*, gradle/wrapper/
+-- CLAUDE.md                    working rules for agents/contributors
+-- README.md                    overview and quick start (build/test: CONTRIBUTING.md, docs/development/)
+-- THIRD_PARTY_NOTICES.md       copied/adapted upstream code (currently none)
+-- .docs/                       design, plan, contract, progress, audits (internal)
+-- archive/                     code removed from the product but kept for reference; never on a build path (pool-roles/: the server-side role/constraint matcher; pool-leases/: the Acquire/Release lease table)
+-- docs/, mkdocs.yml            public documentation site: guide/ + reference/ (Kotlin via Dokka,
|                                Python via mkdocstrings, gRPC via protoc-gen-doc; generated files are
|                                ignored); scripts/build-docs.sh builds the site and a Markdown bundle
|                                (Dokka GFM + lazydocs), .github/workflows/docs.yml publishes both
|
+-- contracts/                   what the three components agree on
|   +-- proto/                   the one protobuf schema; single source for every generated binding
|   |   +-- *.proto              package tap.v1 (public): selector, command, failure, client_connection, device, app
|   |   +-- wire/wire.proto      package tap.wire.v1 (internal): TAP1 payloads — handshake, Request(Command)/Response(CommandResult), host-internal ops, blob metadata
|   |   +-- buf.yaml, BREAKING_BASELINE   lint/breaking config; commit before which CI skips `buf breaking` (the deliberate 3.0 break)
|   +-- conformance/client-conformance.json   defaults + failure-reason → exception table loaded by the daemon, Kotlin and Python unit suites
|   +-- schema/                  :contracts:schema — contracts/proto compiled to protobuf-javalite + Kotlin lite DSL (Android and host share it); published as tap-schema
|   +-- api/                     :contracts:api — gRPC lite Java + grpc-kotlin coroutine stubs for tap.v1 only (messages from :contracts:schema); published as tap-api
|   +-- protocol/                :contracts:protocol — TAP1 framing, handshake, validation and dispatch over the schema; pure Kotlin/JVM, shared by host and driver
|   |   +-- src/main/kotlin/io/github/noamcohen48/tap/protocol/
|   |   |   +-- Protocol.kt          limits, build ids, protocol version 4.0, capabilities, FrameType/Frame, driver-side defaults, key codes
|   |   |   +-- Operations.kt        operation catalogue (public + host-internal), isMutation/targetSelector, Commands/Requests/Responses factories, CommandHandler + exhaustive Request.dispatch
|   |   |   +-- Selectors.kt         Nodes/Selectors builders, and/or, children/conjunction, scope/pick helpers, aut_package resolution, render()
|   |   |   +-- CommandValidation.kt shared argument + selector validation (InvalidCommandException) -> NATIVE | TRAVERSAL plan kind
|   |   |   +-- ErrorCode.kt         mayHaveMutated/retryable/normalized/label over tap.v1.ErrorCode, ErrorDetail sub-reasons, CommandFailure
|   |   |   +-- Authentication.kt    nonces, HMAC domains over the sent bytes, transcript, negotiation over proto Hello/Challenge
|   |   |   +-- Blob.kt              BLOB_CHUNK encoding, SHA-256
|   |   |   +-- Payloads.kt          parseRequest (INVALID_REQUEST), parsePayload
|   |   |   +-- FrameCodec.kt        TAP1 header encode/decode, bounds checks
|   |   +-- src/test/kotlin/...      FrameCodec, ErrorCode, CommandValidation, Operations, Authentication, BlobFrames, Selectors, GoldenWire tests
|   |   +-- src/test/resources/golden/  30 golden wire encodings (.hex; regenerate with -Dtap.golden.update=true)
|
+-- device/                      what runs on the Android device
|   +-- driver/                  :device:driver — Android; the on-device driver
|   |   +-- src/main/AndroidManifest.xml   empty app shell (package io.github.noamcohen48.tap.driver, <queries> for sync/fault providers)
|   |   +-- src/androidTest/kotlin/io/github/noamcohen48/tap/driver/
|   |   |   +-- TapDriverServerTest.kt   instrumentation entry point (keeps the process alive)
|   |   |   +-- TapDriverServer.kt       SessionConfig from instrumentation args, listener, markers
|   |   |   +-- DriverConnection.kt      per-connection handshake, frame reader, blob writer
|   |   |   +-- DriverCommandEngine.kt   CommandHandler: scope policy compile + Request.dispatch; defaults applied here
|   |   |   +-- SelectorCompiler.kt      AST -> CompiledSelector.Native | .Traversal, with a SearchScope (FocusedWindow(pkg) | AllWindows)
|   |   |   +-- UiObjectAccess.kt        resolve/hasObject/count/containerHasObject per MatchLimit
|   |   |   +-- GestureCommands.kt, TextInputCommands.kt, KeyInput.kt, QueryCommands.kt, WaitCommands.kt, ScreenStability.kt, ArtifactCommands.kt
|   |   |   |                        tap, longTap, swipe, scroll / set/type/clearText / pressKey / exists, count, snapshot, deviceInfo / waitVisible/Gone/AppVisible / waitScreenStable / screenshot, dumpHierarchy
|   |   |   +-- SyncProviderClient.kt    signature-checked ContentProvider reads with timeout
|   |   |   +-- FaultController.kt       test-only fault injection (transport loss, late work, cancel-after-mutation)
|   |   +-- command-engine/      :device:driver:command-engine — pure Kotlin/JVM execution state machine (no Android types)
|   |       +-- src/main/kotlin/io/github/noamcohen48/tap/driver/engine/
|   |       |   +-- CommandPipeline.kt   queue, executor, writer, watchdog, poison, heartbeat, blob streaming
|   |       |   +-- CommandContext.kt    deadline, checkpoint/sleep, mutation gate, transferBlob
|   |       |   +-- Command.kt           per-request state (QUEUED/RUNNING/TERMINAL) + single terminal response
|   |       |   +-- BlobTransfer.kt      chunking, checksums, outcome (COMPLETED/CANCELLED/DEADLINE/WRITE_FAILED)
|   |       |   +-- Outbound.kt          writer-lane frames (Response, Pong, BlobStart/Chunk/End)
|   |       |   +-- RequestScreening.kt  reader-lane session/timeout/CommandValidation screening before enqueue
|   |       |   +-- CommandInterrupted.kt, Clock.kt
|   |       +-- src/test/kotlin/...      CommandPipelineTest (30 tests, incl. reader-lane screening)
|   +-- sync-sdk/                :device:sync-sdk — Android library an AUT ships in its E2E/debug build
|       +-- src/main/AndroidManifest.xml  signature permission + provider at ${applicationId}.tap-sync
|       +-- src/main/kotlin/io/github/noamcohen48/tap/sync/
|           +-- TapSynchronization.kt          busy() handles, generation, identity snapshot
|           +-- TapSynchronizationProvider.kt  ContentResolver.call("state") -> Bundle
|
+-- host/                        what runs on the host machine (no test DSL)
|   +-- core/                    :host:core — Kotlin/JVM library: session infrastructure
|   |   +-- src/main/kotlin/io/github/noamcohen48/tap/host/
|   |   |   +-- Adb.kt               every ADB command as a typed method (`open class Adb`, serial-specific, parsing inside); raw `run` is `@RawAdb` opt-in, allowed only in :host:validation
|   |   |   +-- SessionJournal.kt    JournalState, SessionJournal, SessionJournalStore (lease + fsync'd atomic write)
|   |   |   +-- DriverLifecycle.kt   start-with-retry, port range, forward, process observation, journal recovery, cleanup
|   |   |   +-- DeviceSession.kt     DeviceSessionConfig + DeviceSession.open()/close(): lease -> recover -> install -> start -> forward -> connect -> READY; app(pkg): one AppLifecycle per package for the session
|   |   |   +-- DriverClient.kt      authenticated client API, PendingCommand outcome/cancellation semantics, heartbeat policy, screenshot()
|   |   |   +-- DriverTransport.kt   ordered request IDs and writes, pending-call routing, frames, ping, poison/close
|   |   |   +-- AppLifecycle.kt      install/uninstall/forceStop/clearData/grantPermission/launch/coldLaunch/process/awaitAppVisible/awaitIdle (ADB + driver waits; launch returns after `am start -W`)
|   |   |   +-- BlobReceiver.kt      verifying blob reassembly
|   |   |   +-- CommandException.kt  RemoteCommandException / CommandTransportException, selector rendering
|   |   +-- src/test/kotlin/...      DriverClientTest (17), SessionJournalTest (6), FakeDriverServer
|   +-- daemon/                  :host:daemon — gRPC host daemon server, `tap` executable (JVM dist + GraalVM native image)
|   |   +-- build.gradle.kts         bundles the driver APKs as resources, native-image config
|   |   +-- src/main/kotlin/io/github/noamcohen48/tap/
|   |   |   +-- daemon/cli/
|   |   |   |   +-- TapDaemonMain.kt   CLI: start | serve | status | stop | version, per-command option validation
|   |   |   |   +-- DaemonDescriptor.kt  0600 daemon.json (port, pid, token), owner-checked removal, daemon.lock
|   |   |   +-- daemon/core/
|   |   |   |   +-- TapDaemon.kt       ConnectedClient/attached-device registries, device list, bounded teardown
|   |   |   |   +-- DriverApks.kt      embedded driver APKs extracted per build id, or a `--driver-apk` override
|   |   |   |   +-- EventLog.kt        per-connection bounded event log (`Events`, `.docs/agent-surface.md` decision 3)
|   |   |   +-- daemon/snapshot/       screen snapshots with refs (`.docs/agent-surface.md` decision 2); diagnostic only, never on the action path
|   |   |   |   +-- HierarchyParser.kt   hand XML parser for the UiAutomator dump (no DTD/entities: XXE-safe, no JAXP in the native image) → pre-order DumpNodes
|   |   |   |   +-- DumpMatcher.kt       the driver's native-plan selector semantics (scope + `pkg` filter, resources, relations) evaluated over a dump
|   |   |   |   +-- SelectorSynthesis.kt per-node selector: resource/text/desc/pairs/hint → + ancestor → `At(index)` (by_index); uniqueness via DumpMatcher
|   |   |   |   +-- ScreenSnapshots.kt   dump XML → ScreenNodes (flags, interactive, selector)
|   |   |   |   +-- RefAlignment.kt      node signatures (no bounds) and LCS alignment with a greedy fallback above a size budget
|   |   |   |   +-- ScreenSnapshotState.kt per-device latest snapshot, ref counter (`eN`, never reused), ResolveRef; UnknownRef/RefNotAddressable exceptions
|   |   |   +-- daemon/grpc/
|   |   |       +-- ClientConnectionService.kt  Connect/Disconnect/Info/ListConnections/Events + exactly-one Observe (observing/heartbeat/closing)
|   |   |       +-- DeviceService.kt            inventory, owner-checked Attach/Detach/Execute/Screenshot/DriverLog/ScreenSnapshot/ResolveRef
|   |   |       +-- AppService.kt               AppLifecycle adapter, streamed Install spooled to <state-dir>/uploads
|   |   |       +-- EventRecording.kt          records an Execute / app call and its outcome into the owner's EventLog
|   |   |       +-- TokenAuthInterceptor.kt     bearer-token check on every call
|   |   |       +-- common.kt                  Defaults (echoed in Info), suspend reply wrapper, exception → status + `tap-failure-bin` Failure trailer
|   |   +-- src/main/resources/META-INF/native-image/  reachability metadata recorded with the tracing agent
|   |   +-- src/test/kotlin/...      core: TapDaemonLifecycleTest; cli: CliTest, DaemonDescriptorTest; grpc: ClientConnectionServiceTest, AppServiceTest, FailureStatusTest,
|   |   |                            ScreenSnapshotServiceTest; snapshot: HierarchyParserTest, SelectorSynthesisTest, ScreenSnapshotStateTest
|   |   +-- src/test/resources/snapshot/  fixture-app hierarchy dumps recorded on emulator-5554 (API 34) and 85e49002 (API 29)
|   +-- validation/              :host:validation — device validation suite + `tap-product-probe` (exe)
|       +-- src/main/kotlin/io/github/noamcohen48/tap/host/validation/
|       |   +-- ProductProbe.kt, ProductProbeMain.kt   latency/inventory probe for arbitrary apps
|       +-- src/deviceTest/kotlin/io/github/noamcohen48/tap/host/validation/
|           +-- DeviceTestSupport.kt serials, APKs, per-device fault session harness (journal recovery, install, driver start)
|           +-- *Test.kt             SessionLifecycle, Recovery, Fencing, TransportFault, Cancellation, CancelAfterMutation,
|                                    Heartbeat, AutLifecycle, Screenshot, Input, Scroll, Benchmark, Selectors, Observation,
|                                    Permission, DisconnectIsolation (2+ serials), LateMutationQuarantine (@Tag("reboot"))
|
+-- clients/                     test-facing APIs; every client is a gRPC client of the host daemon
|   +-- kotlin/
|   |   +-- sdk/                 :clients:kotlin:sdk — public Kotlin API (package io.github.noamcohen48.tap.sdk)
|   |   |   +-- src/main/kotlin/io/github/noamcohen48/tap/sdk/
|   |   |       +-- TapClient.kt         TapClient (channel, stubs, devices, connect), TapConnection (observe/availableSerials/attachDevice), DaemonDiscovery (descriptor lookup), TapDaemonProcess (`tap start`/`tap stop`)
|   |   |       +-- Device.kt            Device.attach(connection, serial, …), element/await/app/info/pressKey/typeText/screenshot/dumpHierarchy/driverLog/awaitUntil (internal execute), Timeouts, DeviceOptions
|   |   |       +-- App.kt               install/uninstall/forceStop/clearData/grantPermission/launch/coldLaunch/process/awaitIdle over AppService
|   |   |       +-- Element.kt           lazy element: exists/count/snapshot/text, tap/longTap/setText/clearText/swipe/scroll, typeText (tap + await focused + Device.typeText) and scrollUntil (exists + scroll loop) client-side, first/at/descendant/child
|   |   |       +-- ElementWait.kt       visible()/gone() (driver-side) and enabled/checked/focused/textEquals/count (host-polled)
|   |   |       +-- Selectors.kt         text/textContains/textMatches/desc/hint/resId/rawRes/className + refinements, relations, infix and/or, over the (internal) proto Selector
|   |   |       +-- Models.kt            SDK-owned value types: MatchMode, Direction, StabilitySignal, ErrorCode, FailureReason, DeviceState, Bounds, ElementSnapshot, AppProcess, DeviceEntry, ServerInfo/ServerDefaults
|   |   |       +-- Artifacts.kt         Artifact (bytes, mediaType, extension, save) and Screenshot, Hierarchy, DeviceInfo, DriverLog
|   |   |       +-- Capture.kt           Device.capture(): the four artifacts in parallel, bounded, never throws; Capture.saveTo
|   |   |       +-- ProtoMapping.kt      internal proto <-> model mappers (enums by name after the proto prefix)
|   |   |       +-- TapExceptions.kt     TapException, ServerException (+ FailureReason), CommandException (ErrorCode), WaitTimeoutException, AppLifecycleException, DeviceBusyException, DeviceQuarantinedException
|   |   +-- junit5/              :clients:kotlin:junit5 — JUnit 5 integration (package io.github.noamcohen48.tap.junit5)
|   |       +-- src/main/kotlin/io/github/noamcohen48/tap/junit5/
|   |           +-- Annotations.kt       @TapTest(deviceLifetime), DeviceLifetime, @TapDevice(role), @TapDevices(roles), Devices
|   |           +-- TapTest.kt           tapTest bridge: binding/nesting enforcement, root job, interrupt consumed so teardown runs
|   |           +-- DeviceBarrier.kt     reusable/one-shot coroutine barrier, cancellation-safe; one-shot waiting() resets on release
|   |           +-- TapConfig.kt         tap.* system properties / TAP_* env: serials (optional), autPackage, artifactsDir, acquire timeout, pinned roles, capture mode (tap.capture)
|   |           +-- ConnectionMemo.kt    ConnectionMemo + the JVM-wide SharedConnection: one TapClient + TapConnection per JVM generation (managed sequential generations: teardown gate with cancelled-shutdown NonCancellable re-await of captured flights, single-flight shares, transactional hook install before publish with close+stop rollback, creation rollback with suppressed cleanup, NonCancellable ownership with original cancellation rethrown), closed by launcher listener/shutdown hook
|   |           +-- TapExtension.kt      BeforeEach/AfterEach/ParameterResolver/ExceptionHandler; roles→serials, opens in sorted serial order; failure artifacts
|   +-- python/                  tap-e2e: Python client + pytest plugin (thin layer over the daemon)
|       +-- pyproject.toml, README.md
|       +-- scripts/gen_stubs.py     regenerates tap_e2e/_gen from contracts/proto/*.proto; --check for CI
|       +-- tap_e2e/_gen/            committed generated stubs (<file>_pb2, <file>_pb2_grpc, .pyi); private: the public API is models.py
|       +-- tap_e2e/{client,device,element,app,selectors,errors}.py   TapClient/TapConnection, Device, Element/ElementWait, App, selector DSL, typed errors (mapped by failure reason)
|       +-- tap_e2e/models.py        client-owned value types and artifacts (mirrors Models.kt + Artifacts.kt); _proto.py maps proto <-> models
|       +-- tap_e2e/pytest_plugin.py tap_device / tap_devices fixtures, @pytest.mark.tap_devices, tap_device_scope reuse, failure capture
|       +-- tests/                   the sample suite ported to pytest (conftest = fixture facts)
|
+-- samples/fixture-tests/       JUnit 5 sample suite against the fixture app (real devices, through the daemon)
|   +-- build.gradle.kts         `test` depends on the fixture APK + daemon dist, maps -Ptap.serials to system properties, disabled without serials
|   +-- src/test/kotlin/io/github/noamcohen48/tap/samples/
|       +-- Fixture.kt           fixture facts + install-once/cold-launch helper
|       +-- MainScreenTest.kt    taps, text input, Compose list scrolling, ambiguity, app-owned sync, wait diagnostics, back key
|       +-- LifecycleTest.kt     cold launch identity, force-stop, clear-data, DEVICE_INFO
|       +-- MultiDeviceTest.kt   @TapDevices("left","right") concurrent two-device journey
|       +-- MotionTest.kt        awaitAnimationEnd / awaitAppSettled: wait out an animation, time out on a ticking screen
|       +-- DeviceReuseTest.kt   @TapTest(deviceLifetime = PER_CLASS): one device across the class, replaced once detached
|
+-- .github/                     CI (ci.yml) and tag-driven releases (release.yml, scripts/release_version.py); see release-engineering.md
+-- fixture-app/                 Android app used only by the validation flow and the samples
    +-- src/main/AndroidManifest.xml
    +-- src/main/kotlin/io/github/noamcohen48/tap/fixture/
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
`:device:driver:command-engine`, `:device:sync-sdk`, `:host:core`, `:host:daemon`,
`:host:validation`, `:clients:kotlin:sdk`, `:clients:kotlin:junit5`, `:fixture-app`,
`:samples:fixture-tests`. `clients/python` and `clients/agent` are plain Python packages.

Dependency direction:

```text
samples:fixture-tests --> clients:kotlin:junit5 --> clients:kotlin:sdk --> contracts:api   (gRPC at run time)
clients/python (tap-e2e) ---------------------------------------------> contracts:api   (committed stubs; gRPC at run time)
clients/agent (tap-agent) --> clients/python (tap-e2e)                  (never gRPC directly)
host:daemon (tap) --> host:core --> contracts:protocol
        \----------> contracts:api
host:validation ---> host:core
device:driver androidTest --> device:driver:command-engine --> contracts:protocol
fixture-app --------------> device:sync-sdk
```

- Nothing under `host/` references `clients/`; the clients know the server only through
  `contracts/api`. `:host:core` is the single implementation of ADB control, journals,
  leases, driver lifecycle and app lifecycle, and only `:host:daemon` and `:host:validation`
  link it.
- `contracts:protocol` has no Android, host, or coroutine dependency (`contracts:schema` +
  RE2/J only). `contracts:schema` is protobuf-javalite + the Kotlin lite DSL, so the Android
  driver and the host share the generated classes. `contracts:api` generates gRPC lite stubs plus grpc-kotlin coroutine stubs
  (`*CoroutineImplBase`, `*CoroutineStub`) and carries `grpc-kotlin-stub` +
  `kotlinx-coroutines-core` as `api` dependencies.
- `device:driver:command-engine` has no Android types so the state machine is JVM-tested.
- `device:sync-sdk` depends on nothing from Tap; the driver reaches it only through a
  provider call.
- `host:core` and the daemon are coroutine-based (`suspend` throughout, structured scopes);
  they have no Android API dependency. The Kotlin clients are coroutine-based too (step 4):
  `suspend` over grpc-kotlin stubs with `tapTest`/`tapScope` ownership. Only the validation
  executable fans out per device, as before.
- Product test suites depend on `:clients:kotlin:junit5` (which exposes `:clients:kotlin:sdk`
  and `:contracts:api` as `api`) and never on `:host:*`.

Generated artifacts:

```text
host/validation/build/install/host/bin/host
host/daemon/build/install/tap/bin/tap                 JVM distribution of the daemon
host/daemon/build/native/nativeCompile/tap            GraalVM native image of the daemon
device/driver/build/outputs/apk/product/debug/driver-product-debug.apk            bundled by the daemon
device/driver/build/outputs/apk/androidTest/product/debug/driver-product-debug-androidTest.apk
device/driver/build/outputs/apk/validation/debug/driver-validation-debug.apk      fault-validation suite
device/driver/build/outputs/apk/androidTest/validation/debug/driver-validation-debug-androidTest.apk
fixture-app/build/outputs/apk/debug/fixture-app-debug.apk
```

## 4. Protocol module

The protocol is a length-prefixed binary frame (`TAP1` magic, framing version, frame type,
flags, i64 request ID, i32 length) carrying a protobuf `tap.wire.v1` payload
(`contracts/proto/wire/wire.proto`). `Request` wraps the public `tap.v1.Command` and `Response`
the public `tap.v1.CommandResult`, so the daemon forwards both unchanged. The handshake MACs the
payload bytes as sent, so no canonical encoding is needed.

Key types (generated unless noted):

| Type | Purpose |
|---|---|
| `FrameType`, `Frame` (Kotlin) | HELLO, CHALLENGE, AUTH, AUTH_RESULT, REQUEST, RESPONSE, CLOSE, CANCEL, PING, PONG, BLOB_START, BLOB_CHUNK, BLOB_END |
| `wire.v1.Request` | envelope `session_id`, `generation`, `timeout_ms` + `oneof body` (`command` or a host-internal op) |
| `api.v1.Command` | `oneof op`, one message per public command with only its own fields; optional fields defaulted on the driver |
| `CommandHandler` (Kotlin) | one typed method per operation; `Request.dispatch(handler)` is the exhaustive switch the driver implements |
| `wire.v1.Response` | `CommandResult` + `oneof internal` (`artifact`, `sync`) |
| `api.v1.CommandResult` | `duration_ms`, `request_id`, `session_generation`, `oneof outcome` (`done`, `bool`, `count`, `text`, `snapshot`, `device_info`, `error`) |
| `Selector` / `Node` | the AST (below) |
| `ErrorCode` | closed taxonomy; `mayHaveMutated` / `retryable` extensions in `ErrorCode.kt` |
| `BlobStart` / `BlobEnd` / `ArtifactInfo` | binary transfer envelope and checksum |
| `Hello` / `Challenge` / `Negotiation` / `Authentication(Result)` | handshake |

### Selector AST

```text
Selector { node, oneof scope { aut (default) | system{package_name} }, oneof pick { exactly_one (default) | first | at{index} } }
Node.kind = match{property, value, mode (default EXACT)} | flag{property, value}
          | resource{name, package_name? | aut_package} | related{relation, node}
          | all_of{nodes >= 2} | any_of{nodes >= 2}
```

Builders (`Selectors.kt`): `Selectors.text(v, mode)`, `.contentDescription(v)`, `.rawResource(name)`,
`.androidResource(pkg, name)`, `Selector.inPackage(pkg)`, `Selector.inAnyWindow()`, `.pickFirst()`, `.pickAt(i)`;
`Nodes.text/…/child/descendant/autResource`, `Nodes.allOf`/`anyOf` and infix `and`/`or`
(flattening, single operand returned as is). `CommandValidation.validate(command)` checks the
arguments and the selector a command carries. The device uses `CommandValidation.validateSelector(selector)` to get `NATIVE`
(everything expressible in one `BySelector`) or `TRAVERSAL` (any `REGEX`, any `any_of`, or a
conjunction repeating a single-valued `BySelector` slot); failures are
`InvalidCommandException(code, detail)`.

## 5. Driver

### Process model

The driver is an instrumentation test (`TapDriverServerTest`) that never finishes: it binds a
loopback socket, prints `TAP_READY` with session/generation/port/instance, and serves one
authenticated connection at a time. Instrumentation arguments (`tapSessionId`,
`tapGeneration`, `tapSecret`, `tapExpectedAut`, `tapSyncAuthority`, `tapFaultAuthority`,
`tapUninterruptibleGraceMs`, `tapHeartbeatTimeoutMs`,
`tapFaultPoint`) become an immutable `SessionConfig`.

### Command lifecycle

```text
socket -> DriverConnection.reader ---enqueue---> CommandPipeline.queue(16)
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

Scope rules: `aut` selectors resolve in the AUT's focused window and may name only AUT
resources (`SCOPE_DENIED` otherwise); `system` selectors resolve in the focused window of the
package they name, any package; `any_window` selectors search every window
(`UiDevice.findObjects`, or the roots of `findObjects(By.depth(0))` for the traversal plan).

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

### Device validation suite (`:host:validation:deviceTest`)

The JUnit suite under `host/validation/src/deviceTest` is the end-to-end proof of the fault
and recovery behaviour the clients rely on. It drives `DriverClient` directly and reaches the
fault side door through the opt-in `@ValidationApi ValidationTransport` (explicit request IDs,
foreign session identities, raw payloads, simulated transport loss) and the internal
`TransportHooks`/`SessionHooks`/`AdbHooks` seams; product code opts into neither. It runs
against the `validation` driver flavor, whose instrumentation wires the fault controller
(`FaultHooks`), and the fixture app. Each class runs once per serial in `-Ptap.serials`:

| Class | Proves |
|---|---|
| `SessionLifecycleTest` | startup retry past an occupied port, forward, journal ACTIVE → READY → CLOSED, clean instrumentation exit |
| `RecoveryTest` | CREATING-forward and orphaned-ACTIVE recovery; boot-id change quarantines; unrelated forwards survive |
| `FencingTest` | stale generation, unknown op, malformed payload, reused request ID, bad protocol/secret |
| `TransportFaultTest` | loss before/after acceptance and after mutation → `INDETERMINATE`, never replayed |
| `CancellationTest` / `CancelAfterMutationTest` | queued/running cancel; cancel after the mutation gate is ignored, counter advances once |
| `HeartbeatTest` | driver poisons and exits when the host stops sending |
| `AutLifecycleTest` | sync busy/idle; the driver survives AUT force-stop/clear-data; restart detected |
| `ScreenshotTest`, `InputTest`, `ScrollTest`, `SelectorsTest`, `ObservationTest`, `PermissionTest` | blob reassembly, text input, Compose/View scrolling, `AMBIGUOUS`/limits/relations/regex, count/snapshot/keys, permission-dialog scoping |
| `BenchmarkTest` | lookup and dump timings (`TAP_VALIDATION` lines) |
| `DisconnectIsolationTest` | (2+ serials) one device's transport loss leaves the other untouched |
| `LateMutationQuarantineTest` | `@Tag("reboot")`, only with `-Ptap.reboot=true`: late mutation quarantines, reboot recovery |

`tap-product-probe` reuses the same session infrastructure against an arbitrary installed app
to measure lookup latency and accessibility inventory; it is not a test DSL.

### App lifecycle (`AppLifecycle`)

`AppLifecycle(session, packageName)` is the one implementation of AUT lifecycle for every
client: ADB-side `pm`/`am`/`cmd package resolve-activity` for install, uninstall, launch,
force-stop, clear-data and permission grants (launch returns once `am start -W` does; it does
not wait for the window),
process observation (`coldLaunch` returns the new `ProcessObservation`; `forceStop`/
`clearData` verify the process is gone), and `awaitIdle` over the sync provider
(`SyncBootstrap` once, then `SyncPoll` until stable; any process identity change is an
`AppLifecycleException`). The server keeps one per (session, package).

### Host daemon server (`:host:daemon`)

`tap serve` exposes `:host:core` over loopback gRPC (`contracts/proto/*.proto`,
package `tap.v1`) so every client — Kotlin and Python alike — reuses the same ADB control
plane, journals, device locks, driver lifecycle and app operations. Its generated grpc-kotlin
servers use suspend unary methods and a `Flow` for `Observe`; the shared suspend reply wrapper
preserves caller cancellation and keeps the established exception/status mapping. It lists the
devices (`adb devices`, `LEASED` by probing the per-serial lock, quarantine read from the
journal) but leases nothing itself: exclusive use is the lock a live `DeviceSession` holds, and
`Attach` can wait for it (`pool-and-leases.md`). `DeviceService` proxies `Execute` to the attached
device's `DriverClient` (driver failures and transport loss are returned as `CommandResult`
data, never gRPC errors; gRPC cancellation forwards a protocol `CANCEL`).

One short synchronized lifecycle boundary owns client-connection closed state, the sole
Observe registration, in-flight device attachment, and the global attached-device registry; no
monitor crosses suspension. `AttachedDevice.ownerConnectionId` is the only ownership index. If
disconnect wins while attachment is suspended, the completed `DeviceSession` is closed before
exposure. Observe loss, explicit `Disconnect`, explicit `Detach`, and daemon shutdown all
remove each attached device once before bounded cleanup. The shutdown hook divides one total
deadline across connection/device cleanup and gRPC termination; timed-out core cleanup
continues under its own `DeviceSession.close(timeoutMs)` deadline so quarantine/lease
finalization can finish without preventing later attempts. The driver APKs are embedded. The
full contract is `server-api.md`.

## 7. Clients

Every client is a gRPC client of the server and contains no ADB, journal, lease or driver
lifecycle code; the language-facing shape is the same in Kotlin and Python.

### Kotlin client (`:clients:kotlin:sdk`)

```kotlin
val client = TapClient.create()                    // explicit address or resolved server
val connection = client.connect("checkout")         // Observe stream = liveness (owned scope)
val serial = connection.availableSerials().first()   // the server leases nothing; DeviceSession holds the device lock
val device = connection.attachDevice(serial, autPackage)
val app = device.app()                        // autPackage by default
app.install(apk); app.coldLaunch(".MainActivity")
device.element(resId(pkg, "view_button")).tap()           // exactly one match or AMBIGUOUS/NOT_FOUND
device.await(text("View tapped")).visible()               // one driver-side wait RPC
device.element(rawRes("composeList")).scrollUntil(rawRes("item-40"))
app.awaitIdle()                                           // sync-sdk busy state, identity-guarded
device.detach(); connection.close(); client.close()   // all suspend; try/finally in tapScope/tapTest
```

Device work runs inside `tapScope { ... }` (scripts) or `tapTest { ... }` (JUnit); selector
construction (`text(...)`, `res(...)`) is the only non-suspend part.

- The client is a thin gRPC layer over `contracts/api`: no ADB, journals, leases or driver
  lifecycle. `TapClient` owns the channel and the coroutine stubs (`TapClient.create` resolves
  the daemon); `TapConnection` is this process's identity at the server (owned Observe scope,
  `availableSerials`, `attachDevice`); `Device` wraps one attached device and
  `Timeouts(action 10 s, wait 10 s, lifecycle 30 s, poll 100 ms)`, overridable per call.
  Every I/O method is `suspend` with per-call `withDeadlineAfter` plus caller-cancellation;
  `close` is `suspend` (no `AutoCloseable`).
- `Element` is a proto `Selector` plus the device; each terminal call is one `Execute`. A
  `CommandResult` failure becomes `CommandException` (proto `ErrorCode`, detail, selector,
  request identity); transport loss reported by the server is the same exception with
  `ERR_TRANSPORT_LOST`/`ERR_INDETERMINATE`. gRPC-level failures are `ServerException`.
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
- `Selectors.kt` builds the proto `Selector`; the server converts it to the protocol AST and
  `CommandValidation` validates selectors from the typed command before an ID is allocated.
- `DaemonDiscovery` resolves `tap.server`/`TAP_SERVER`, then `<state dir>/daemon.json`
  (alive check); it never starts a server. `TapDaemonProcess.start/stop` run `tap start` /
  `tap stop` from `tap.bin`/`TAP_BIN`/`tap` on `PATH`; the JUnit extension does so around a run
  when `tap.manageDaemon` is set (`TapLauncherSessionListener` stops it).
- The API is `suspend` throughout; multi-device tests fan out with `coroutineScope`/`async`
  inside `tapTest`, with `DeviceBarrier` for genuinely simultaneous phases (see `framework-gaps.md`
  for what was removed with the proving tests).

### JUnit 5 integration (`:clients:kotlin:junit5`)

```kotlin
@TapTest
class CheckoutTest {
    @Test fun buys(device: Device) { tapTest { ... } }       // implicit role "device"
    @Test @TapDevices("sender", "receiver")
    fun sync(devices: Devices) { tapTest { ... } }
}
```

`TapExtension` (`BeforeEachCallback` with per-test root job, `AfterEachCallback` with cancel +
bounded non-cancellable teardown, `ParameterResolver`, `TestExecutionExceptionHandler`,
`InvocationInterceptor` binding the `tapTest` context) collects roles from `@TapDevices` (method or class),
`@TapDevice` parameters, and bare `Device` parameters; maps roles to serials itself (pinned
by `tap.device.<role>`, then the `tap.serials` order, otherwise the server's device list, free
devices first); skips the test (assumption) when fewer devices exist than roles; attaches devices one at
a time in sorted serial order through the JVM-wide `SharedConnection` (one `TapClient` + `TapConnection`
per sequential generation — teardown-gated start/connect with single-flight shares, creation
rollback with suppressed cleanup, and cancellation-safe ownership under `NonCancellable`
with the original cancellation rethrown — closed by the launcher listener/shutdown hook), each
waiting up to `tap.acquireTimeoutSeconds` for a device another
`DeviceSession` holds; stores them
in a per-method namespace; test bodies run only inside `tapTest { ... }` (real-time bridge;
binding/nesting enforced, timeout/sibling cancellation via the root job); on a test failure captures `<artifactsDir>/<class>/<method>/
<role>-<serial>.png|.xml|.device-info.txt|.driver.log` plus `failure.txt` while devices are
attached; then detaches the devices. Cleanup failures are attached to the
primary failure, or rethrown when the test itself passed. `TapConfig.current` reads
`tap.serials` (optional), `tap.device.<role>` (pinning), `tap.autPackage`,
`tap.artifactsDir`, `tap.acquireTimeoutSeconds`, `tap.server`, `tap.manageDaemon`, `tap.bin` (system property
first, then `TAP_*` environment). Driver APKs come from the server's bundle.

Class-level JUnit parallelism is safe: each device's lock serialises its `DeviceSession`s, and the
sample suite runs its classes concurrently across two devices. Several JVMs (or a JVM and a
pytest run) respect each other because the lock is a file under the shared state dir.

### Python binding (`clients/python/`)

`tap-e2e` is a generated gRPC client plus a thin mirror of the Kotlin SDK: `TapClient`/`TapConnection`,
`Device`, `Element`/`ElementWait`, `App`, selector builders over the proto `Selector`, and
typed errors (`CommandError` with `ErrorCode`, `WaitTimeoutError`, `AppLifecycleError`,
`ServerError`). The pytest plugin mirrors `TapExtension`: per-test attached devices, all-or-none
roles via `@pytest.mark.tap_devices`, skip when fewer serials are configured, failure
artifacts (screenshot, hierarchy, device info, driver log). Server discovery: `TAP_SERVER`,
then `<state dir>/daemon.json`; never auto-start. `start_daemon`/`stop_daemon` run `tap start`/`tap stop` from `TAP_BIN`/`tap` on `PATH` (`tap_manage_daemon` in the plugin).

## 8. Synchronization SDK

`device/sync-sdk` is what a product app adds to its E2E/debug build:

```kotlin
// build.gradle.kts of the AUT
debugImplementation(project(":device:sync-sdk"))   // never in release

// in app code around asynchronous work tests must wait for
TapSynchronization.busy().use { repository.refresh() }
```

The library manifest declares the signature permission
`io.github.noamcohen48.tap.permission.SYNCHRONIZATION` and the exported provider at
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
| Protocol | `contracts/protocol/src/test` | 60 in 8 classes | framing bounds, negotiation/transcript/MAC domains, error taxonomy, command + selector validation, operation catalogue and exhaustive dispatch, blob chunks, 30 golden wire encodings |
| Execution engine | `device/driver/command-engine/src/test` | 30 | ordering, overload, cancel states, mutation gate, deadlines, watchdog, heartbeat, blob streaming, shutdown |
| Driver core | `:device:driver:core:testDebugUnitTest` | 38 in 9 classes | selector compiler/pick, text matching and verification, key input, screen stability, session config, bounded output |
| Host core | `host/core/src/test` | 90 (`DriverClientTest` 33, `AdbTest` 27, `DeviceSessionTest` 24, `SessionJournalTest` 6) | typed/fake ADB process ownership and parsing; session open/cleanup/quarantine; real handshake against `FakeDriverServer` for demux, cancellation, heartbeat, transport-loss and blobs; journal atomicity |
| Kotlin client | `:clients:kotlin:sdk:test` | 60 (`TapClientTest` 37, `SelectorsTest` 10, `WireContractTest` 7, `DaemonTokenTest` 4, `ConformanceTest` 2) | in-process grpc-kotlin fakes: Observe ownership/lifetime, Disconnect-before-drop ordering, Execute cancellation, sibling-cancellation shape, scope enforcement, quarantine report, fail-closed drain-timeout teardown |
| JUnit extension | `:clients:kotlin:junit5:test` | 32 (`TapTestBridgeTest` 17, `DeviceBarrierTest` 7, `ConnectionMemoTest` 6, `TapConfigTest` 2) | binding/nesting (incl. child coroutines), root cancellation (failure + thread interruption with AfterEach teardown), accepted-Execute sibling cancellation without replay, duplicate-role rejection, sorted opens, teardown preservation; barrier release/reuse/one-shot (waiting resets)/cancellation; managed connection generations (teardown-gated start/connect, creation rollback with suppressed cleanup, sequential reopen, cancellation-safe ownership with NonCancellable state/rollback/gate transitions and original cancellation rethrown, explicit teardown-park hook with no timing, handshake cancellation for create/connect/shutdown with no leaks, create/connect suppression contents/order with exact-once resources, cancelled-shutdown NonCancellable re-await of captured flights before reclaim/gate completion, transactional hook install before publish with close+stop rollback and safe retry), shutdown-vs-connection race, concurrent shares |
| Device | `:host:validation:deviceTest -Ptap.serials=…` | 45 on two serials (22 per serial + isolation) | the fault/recovery suite above on API 29 (Samsung SM-J810G) and API 34 (emulator), reboot tag excluded |
| Device, Kotlin client | `:samples:fixture-tests:test -Ptap.serials=…` | 13 device | Kotlin API + JUnit extension through a runner-managed server (`tap.manageDaemon`), structured two-device concurrency incl. sibling cancellation without replay; passed on API 29 + API 34 on 2026-09-21 |
| Device, Python client | `TAP_BIN=… TAP_MANAGE_DAEMON=1 TAP_SERIALS=… pytest clients/python/tests` | 12 | the same suite through the pytest plugin |
| Server | `:host:daemon:test` | 35 (`TapDaemonLifecycleTest` 18, `DaemonDescriptorTest` 6, `FailureStatusTest` 4, `ClientConnectionServiceTest` 4, `AppServiceTest` 2, `NativeImageMetadataTest` 1) | Execute pass-through and INVALID_ARGUMENT pre-flight, `tap-failure-bin` reason mapping, defaults vs the conformance table, native reflection metadata completeness, deterministic attach/open/close/shutdown races, and in-process gRPC Execute cancellation |
| Device, destructive | `… deviceTest -Ptap.reboot=true` | +1 | adds the late-mutation quarantine + reboot recovery |

Build and JVM tests:

```bash
./gradlew :contracts:protocol:test :device:driver:command-engine:test :host:core:test :host:validation:installDist \
  :device:driver:assembleDebug :device:driver:assembleAndroidTest :fixture-app:assembleDebug
./gradlew :host:daemon:test :samples:fixture-tests:test -Ptap.serials=emulator-5554,85e49002
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

Also implemented and device-proven (2026-09-19): the gRPC host daemon server with a
device list, connection liveness and cancel forwarding; its GraalVM native image;
the Python client and pytest plugin; the sample suite ported to pytest and passing on both
devices through the native server; the repository split into `contracts/`, `device/`,
`host/` and `clients/`, with the Kotlin SDK and JUnit extension rewritten as gRPC clients of
the server (the in-JVM `DevicePool` and `Device.connect` are gone; `AppLifecycle` lives in
`host/core`) and the Kotlin sample suite passing on both devices through a runner-managed
server.

Not yet built — see [`framework-gaps.md`](framework-gaps.md) for the full, per-section list:
`session.shutdown`, `inspector.snapshot`, crash/ANR codes, provider
visibility for non-fixture AUTs, device fake-ADB JVM coverage for the host core and
in-process server tests for the clients,
logcat/dumpsys/JSONL/HTML artifacts and reports, per-test deadlines and the remaining JUnit
contracts, CI lanes. (The coroutine `tapTest` façade and `DeviceBarrier` are built with
deterministic JVM tests, including in-process accepted-`Execute` cancellation; host no-reboot,
native image, Python smoke and the 13-device-test 0.2.0-client matrix have passed.)

The device validation suite remains validation code, not framework code; it keeps exercising
faults the clients cannot inject, and nothing outside `:host:validation` may depend on it.
