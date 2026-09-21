# Coroutines across the host and the Kotlin client

Decision record and work plan. Written 2026-09-20 before any code changed; update it as the
refactor lands and mark each step with the commit that delivered it.

## Status

**In progress: host core and service landed; final review in progress.** On 2026-09-20 the
user decided that all three layers — `:host:core`, `:host:service` and the Kotlin client
(`:clients:kotlin:sdk` + `:clients:kotlin:junit5`) — move to `kotlinx.coroutines` (`suspend`
functions, structured concurrency), replacing the thread + `CompletableFuture` + blocking-stub
design. The host core and coroutine service checkpoints are now in the tree; the Kotlin client
and `tapTest` step has not started. The Python client is out of scope (an `asyncio` façade stays
a later item in `framework-gaps.md`).

Landed checkpoints, still under final review:

- `38358c0` — host core `DriverClient` suspend API, coroutine reader/heartbeat scope and the
  first coroutine conversion of ADB/session lifecycle callers.
- `b88d2e8` — grpc-kotlin coroutine servicers, suspend status wrapper, Flow-based `Attach`, and
  direct cancellation propagation into `PendingCommand.await()`.
- `8c1332f` — host-core follow-up for caller cancellation, bounded socket writes, bounded
  non-cancellable session cleanup, and deterministic cancellation/cleanup tests.
- `1ce2b7b` — service lifecycle checkpoint: one connection/session state boundary,
  exactly-one Attach, close-vs-open handling, bounded shutdown propagation, and service
  lifecycle tests. This checkpoint is being audited before the lane is considered complete.
- `03b00b7` — docs checkpoint for the coroutine documentation sweep (`service-api.md` status,
  `project-architecture.md` threading/test notes, this file's status). The docs sweep is landed;
  the lane itself remains under final review.
- `7e5efbf` — host-core follow-up: bounded ADB drain lifetime and preserved await cancellation.
- `3bfb049` — service follow-up: strict shutdown bound (detach-and-launch on exhaustion) and
  corrected coroutine verification docs.

Verification recorded for this lane is intentionally **non-device only**: host core/service JVM
unit tests plus protocol tests and compilation/install tasks for validation, fixture tests and
the JVM service distribution. No validation executable, fixture/device suite, Python suite or
native image build has been run for these coroutine checkpoints; device-matrix and native-image
verification remain pending.

This reverses a recorded invariant from the pre-migration architecture ("`host:core`, the
Kotlin clients and the service have no Android API or coroutine dependency; only the validation
executable uses kotlinx.coroutines"). The current architecture now records the landed host
state while retaining the synchronous Kotlin-client note. It is *not* a
departure from the implementation plan: plan §12 always specified a coroutine test surface
(`tapTest`, one coroutine per device, `DeviceBarrier`, root job cancelled by JUnit timeout,
bounded non-cancellable teardown); the synchronous API was a deliberate simplification
recorded in `framework-gaps.md` ("Synchronous API instead of `tapTest` coroutine scope").
Plan §7/§9 leave host internals unspecified beyond "transport mutex, so concurrent coroutines
cannot put a lower ID on the socket after a higher one" — i.e. the plan assumed coroutines
on the host too.

## Preparatory refactor already landed (2026-09-20)

Done while discussing this, all verified with `:host:service:test`, `:host:core:test` and
`:samples:fixture-tests:test -Ptap.serials=emulator-5554,85e49002` (12/12 on both devices):

- `5045dc7` — `host/service/.../Conversions.kt` is top-level extension functions
  (`toProto()` on protocol models; `toCommand(autPackage)`, `toSelector(autPackage)`,
  `toResponse()`, `toErrorCode()`, `toDirection()`, `toStabilitySignal()` on the generated
  proto classes) instead of `object Conversions`. `Conversions.TimedCommand` removed; the
  effective timeout (`if (request.command.timeoutMs > 0) … else session.defaultTimeoutMs`)
  is computed inline in `SessionServicer.execute`.
- `78d3ea4` — `TapService.execute(session, command, timeoutMs, onStarted)` removed. It
  existed only to leak the `PendingCommand` to the gRPC cancel handler through a callback
  and return `Pair<Response, Long>` for the request id. `SessionServicer.execute` now calls
  `session.device.client.submit(command, timeoutMs)` itself, stores the handle in
  `pendingRef` for `setOnCancelHandler`, and `TapService.await(pending)` keeps only the
  transport-loss → error-`Response` mapping (`CommandTransportException` →
  `Response.failure(code, detail = transmissionState.name, …)`).

The awkwardness of that callback is what prompted the question "why futures and not
`suspend`?" and this record.

## Pre-migration baseline (historical; host core/service have now been replaced)

### `:host:core`

- `DriverClient.kt` (522 lines). One blocking `java.net.Socket` per session. A daemon
  **reader thread** (`tap-driver-client-reader`, `readFrames`) demultiplexes frames by
  request id into `ConcurrentHashMap<Long, PendingCommand>`; a daemon **heartbeat thread**
  (`tap-driver-client-heartbeat`, `runHeartbeat`) sleeps and `ping()`s after
  `heartbeatIntervalMs` idle. `PendingCommand` wraps a `CompletableFuture<Response>`;
  `await()` is `result.get(budget, ms)` and poisons the client on timeout; `cancel()` writes a
  `CANCEL` frame under `transportLock`. `submit()` allocates the request id and writes the
  frame under `synchronized(transportLock)` (the ordering invariant). `ping()` uses a
  `LinkedBlockingQueue<Long>` of pongs under `pingLock`. `close()` joins the reader.
- `Adb.kt`: `ProcessBuilder` + `CompletableFuture.supplyAsync` to drain stdout while waiting
  with a timeout.
- `DriverLifecycle.kt`: `am instrument` process with an output-drain thread, `Thread.sleep`
  polling for the port line and for process exit.
- `AppLifecycle.kt`: `Thread.sleep` polling loops for app state.
- `SessionJournal.kt` / `DeviceSession.kt`: file locks and blocking cleanup; no threads of
  their own.

### `:host:service`

- grpc-java `NettyServerBuilder` with a cached thread pool (`tap-rpc`) and a single-thread
  `ScheduledExecutorService` (`tap-scheduler`, connection liveness). Servicers implement the
  generated `*ImplBase` with `StreamObserver`; `SessionServicer.execute` hops to
  `commandExecutor` so the call thread stays free for the cancel listener, and forwards a
  gRPC cancel to `PendingCommand.cancel()` via `AtomicReference` + `AtomicBoolean`.
- Native image (`:host:service:nativeCompile`, GraalVM CE 21.0.2) with recorded reflection
  config under `host/service/src/main/resources/META-INF/native-image/`.

### Kotlin client

- `TapClient.kt`: one `ManagedChannel`, four **blocking stubs**
  (`ConnectionServiceGrpc.newBlockingStub` etc.).
- `Device.kt` / `Element.kt` / `ElementWait.kt` / `App.kt`: synchronous methods;
  `Device.awaitUntil` polls with `Thread.sleep(pollInterval)`; `ElementWait.property/count`
  poll through it.
- `TapExtension.kt` (JUnit 5): acquires roles, opens `Device`s, injects `Device`/`Devices`.
  No per-test job, no deadline, no structured teardown beyond `close()`.
- `samples/fixture-tests/MultiDeviceTest.kt` fans out with `Executors.newFixedThreadPool(2)`;
  a failure on one device does not cancel the other's in-flight work.

## The discussion (arguments, so they are not re-derived)

Per layer, because the answer differs:

1. **`host/core`.** The I/O is blocking by nature (sockets to the driver via `adb forward`,
   `ProcessBuilder` for adb, file locks). A reader thread completing per-request promises is
   the standard demultiplexer shape; `CompletableFuture` is just the promise. Coroutines do
   not make any of it non-blocking — they wrap the same calls in `Dispatchers.IO`. Concurrency
   is tiny: one in-flight command per device, a handful of devices. Argument *for*: uniform
   `suspend` API up the stack, `CompletableDeferred` instead of `CompletableFuture`,
   `delay` instead of `Thread.sleep`, cancellation that composes with the layers above.
2. **`host/service`.** The one place the blocking design was visibly awkward (the callback
   that `78d3ea4` removed). grpc-java's server API is callback-based; bridging to blocking
   `await()` on an executor works but cancel wiring is manual. grpc-kotlin gives
   `suspend fun execute(request): CommandResult` where gRPC cancel = coroutine cancel. Costs:
   second protoc plugin (`protoc-gen-grpc-kotlin`) in `contracts/api`, `grpc-kotlin-stub` +
   `kotlinx-coroutines-core` dependencies, native-image config re-recorded.
3. **Kotlin client.** The only layer where coroutines were always the plan and are a
   *feature*: structured fan-out across devices, per-test deadline that cancels everything,
   `async` phases with a `DeviceBarrier`, sibling failure cancels the other device's command.

The assistant's recommendation was 3 only (build `tapTest` on top of the blocking stubs,
leave 1 and 2). The user decided on all three, primarily for uniformity: one concurrency model
end to end rather than threads below and coroutines above. That is the decision; the
recommendation is kept here only as the record of the trade-off.

## Target design

### `:host:core`

- `DriverClient` keeps the single socket and the request-id ordering invariant, but:
  - `submit(command, timeoutMs): PendingCommand` stays synchronous in effect (it must write
    the frame under a mutex so ids stay ordered); it becomes `suspend` and uses a
    `kotlinx.coroutines.sync.Mutex` for `transportLock`, with the actual blocking socket write
    inside `withContext(Dispatchers.IO)`. A cancelled caller that has *not* yet written keeps
    `NOT_WRITTEN`; one cancelled during the write is `INDETERMINATE` for mutations (the
    existing transmission-state rules are unchanged; see `protocol-contract.md`).
  - `PendingCommand.result` becomes `CompletableDeferred<Response>`; `await()` becomes
    `suspend` with `withTimeout(budget)`. **Cancelling the awaiting coroutine must not be
    treated as the command's outcome**: on `CancellationException` the client sends
    `CANCEL` (cooperative, driver may ignore after the mutation gate) and the pending entry
    stays registered until the terminal frame arrives, so a later frame is not an "unknown
    request id" and the mutation gate result is still recorded. Document this in the KDoc;
    it is the main semantic subtlety of the conversion.
  - Reader: stays a dedicated thread *or* a coroutine on `Dispatchers.IO` — either way a
    blocking `InputStream.read` loop, because a blocking socket read is not interruptible;
    cancellation of the client's scope closes the socket to unblock it (that is already what
    `close()` does). Prefer a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by the
    client so `close()` = `scope.cancel()` + `socket.close()`.
  - Heartbeat: a coroutine in the same scope with `delay`, replacing `runHeartbeat`.
  - `ping()`: `Channel<Long>` for pongs instead of `LinkedBlockingQueue`; `withTimeout`.
  - `screenshot()`, `execute*()`: `suspend`.
- `Adb.kt`: `suspend fun run(...)` — process start/wait inside `Dispatchers.IO`,
  `withTimeout` around `process.waitFor`, `ensureActive` between steps; kill the process on
  cancellation (`invokeOnCancellation` / `finally`).
- `DriverLifecycle.kt`, `AppLifecycle.kt`: `Thread.sleep` polling → `delay`; the output-drain
  thread → `launch(Dispatchers.IO)` in a scope tied to the running instrumentation.
- `DeviceSession` / `SessionJournal`: `suspend` where they call the above; teardown paths run
  under `withContext(NonCancellable)` because cleanup must complete (plan §9/§12: bounded
  non-cancellable teardown, quarantine on failure) — cancellation must never skip the journal
  write.
- **Every ADB call stays serial-specific and no journal/lease semantics change.** The
  invariants list in `CLAUDE.md` is unaffected except for the layering note about
  dependencies.

### `:host:service`

- `contracts/api/build.gradle.kts`: add the `grpckt` protoc plugin
  (`io.grpc:protoc-gen-grpc-kotlin:<ver>:jdk8@jar`) and `api("io.grpc:grpc-kotlin-stub")`,
  `api("org.jetbrains.kotlinx:kotlinx-coroutines-core")`. Java stubs stay generated (the
  Python/other consumers are unaffected; `buf` lint/breaking unaffected — no proto edit).
- Servicers extend the generated `*CoroutineImplBase`. `SessionServicer.execute` becomes:
  ```kotlin
  override suspend fun execute(request: ExecuteRequest): CommandResult {
      val session = service.session(request.sessionId)
      val timeoutMs = if (request.command.timeoutMs > 0) request.command.timeoutMs else session.defaultTimeoutMs
      val pending = session.device.client.submit(request.command.toCommand(session.device.config.autPackage), timeoutMs)
      return service.await(pending).toProto(pending.requestId, session.device.generation)
  }
  ```
  with the cancel forwarding done by `PendingCommand.await()`'s cancellation handling (no
  `AtomicReference`/`AtomicBoolean`). `commandExecutor` goes away; the coroutine impl base
  runs on `Dispatchers.Default` by default — pass a context so command coroutines are not
  starved (they suspend, so `Default` is fine, but `Adb` calls must be on `IO`).
- `ConnectionServicer` liveness: the `ScheduledExecutorService` → a coroutine with `delay`
  in a service-owned scope, cancelled in the shutdown hook.
- `ServiceMain`: keep Netty; drop the cached thread pool; `runBlocking` only at the very top.
- `reply { … }` in `servicer/common.kt` is the plain suspend wrapper: it preserves caller
  cancellation and maps other exceptions to the same gRPC statuses recorded in
  `service-api.md`.
- Native image: re-record `META-INF/native-image` with the tracing agent on the JVM dist
  while running the smoke flow (procedure in `CLAUDE.md` build notes). kotlinx.coroutines
  itself is native-image friendly; grpc-kotlin adds no reflection of its own, but verify.
  `kotlinx.coroutines.debug` must stay off.

### Kotlin client

- `TapClient` uses the generated `*CoroutineStub`s; public `Device`/`Element`/`ElementWait`/
  `App` methods become `suspend`; `Device.awaitUntil` uses `delay`. Deadlines: per-call
  `withDeadlineAfter` stays (server-side enforcement) *and* the test's root job deadline
  cancels the RPC client-side.
- JUnit 5 (`TapExtension`), per plan §12: `BeforeEachCallback` creates the per-test root
  `Job` + deadline; `InvocationInterceptor` binds it; `tapTest { }` is the mandatory entry
  point for suspending framework calls (`@Test fun x() = tapTest { … }` — JUnit methods are
  not `suspend`, so `tapTest` is `runBlocking`-style on the extension-owned context; nesting
  or use without `@TapTest` is a usage error). `AfterEachCallback` cancels the job then runs
  bounded `NonCancellable` cleanup, preserving the primary failure. Add `DeviceBarrier` only
  if a fixture test needs simultaneous phases; `MultiDeviceTest` becomes
  `coroutineScope { roles.map { async { … } }.awaitAll() }` and gains an assertion that a
  failing sibling cancels the other device's in-flight wait (that is the behaviour the gap
  table promises).
- Public API is breaking: every method the guide shows becomes `suspend`. Update
  `docs/guide/*` (all Kotlin snippets), KDoc (the reference is generated), `README.md`,
  `samples/fixture-tests`, and `release-engineering.md` (major version line bump for the
  Kotlin artifacts). Python client and its docs unchanged.

## Amendments (2026-09-20, before step 1)

Reading the code against the target design above surfaced six things it did not account for,
and four decisions the user took in response. They override the target design where they
differ; the step list below already reflects them.

1. **`contracts/api` is a `java-library` with no Kotlin plugin** (`contracts/api/build.gradle.kts`).
   The generated `*CoroutineImplBase` / `*CoroutineStub` are Kotlin sources, so step 1 must
   apply `org.jetbrains.kotlin.jvm` there and add `api("io.grpc:grpc-kotlin-stub")` +
   `api("org.jetbrains.kotlinx:kotlinx-coroutines-core")`. The published `tap-api` artifact
   therefore gains a Kotlin/coroutines dependency for every consumer. Step 1 is *not* purely
   additive, and `release-engineering.md` must record the new dependency of the artifact.
2. **Constructors cannot suspend.** `DriverClient.init` connects and runs the whole handshake
   inline. It becomes a private constructor plus a `suspend` factory; `connectWithRetry` is
   the natural home. `DeviceSession.open` becomes a `suspend` factory the same way.
3. **`close()` cannot suspend while it is `AutoCloseable`.** Decision: **drop `AutoCloseable`**
   on `DriverClient`, `DeviceSession` and the client's `Device`; `close()` becomes `suspend`
   with a `withContext(NonCancellable)` body. Callers use `try`/`finally` inside a coroutine.
   The non-coroutine callers that must be adapted are `TapExtension.afterEach`, the service's
   shutdown hook and `PhaseZeroMain`; they get their own `runBlocking(NonCancellable)`
   boundary at the edge, never inside the framework types.
4. **`writeFrame` spawns a thread per frame** (`FutureTask` + `socket.close()` on timeout),
   because a socket write has no timeout. "Blocking write inside `withContext(Dispatchers.IO)`"
   silently drops that write deadline; the converted `writeFrame` must keep an explicit
   deadline whose expiry closes the socket. Related: `ping()` nests `synchronized(pingLock)`
   around `synchronized(transportLock)`, and `synchronized` cannot span a suspension point —
   the two lock scopes are redesigned as `Mutex`es, not translated line by line.
5. **`ConnectionService.Attach` is a streaming RPC.** It becomes `Flow<ConnectionEvent>` on
   both sides. The client's `Connection.attach` (`ClientResponseObserver` + `CountDownLatch`)
   needs a long-lived scope to collect in, which `TapClient` does not own today — the
   "client process dies -> service closes its sessions" invariant hangs off that stream, so
   the scope's lifetime is part of the step-3/4 design, not an afterthought.
6. **`Adb.execResult` is `protected open` and overridden by test fakes** (`AdbTest.kt`).
   Decision: make it `suspend open` and update the fakes (their test bodies move to
   `runTest`); no process-runner seam is introduced.

Staging decision: steps 2 and 3 are **one lane** (`:host:core` then `:host:service`), because
making `DriverClient` suspend breaks the servicers that call it. No temporary `runBlocking`
bridge is written at that boundary — code whose only purpose is to be deleted by the next
commit. The lane may contain two commits, but the tree compiles at the end of the lane.

## Order of work and verification (commit boundaries)

Each step is its own commit with explicit paths, verified before the next starts. The
device matrix is emulator-5554 (API 34) + 85e49002 (Samsung SM-J810G, API 29).

1. **Landed before this lane: `contracts/api` grpc-kotlin generation** (`78cfed3`, pin note
   `875c512`). Java stubs remain; the module also generates coroutine stubs and exports the
   grpc-kotlin/coroutines dependencies.
2. **Landed, under final review: `:host:core` → coroutines, then `:host:service` → coroutine servicers**
   (`38358c0`, `b88d2e8`, follow-ups `8c1332f`, `1ce2b7b`) **in one lane**
   (staging decision above; steps 2 and 3 below are the two commits of that lane).
   `:host:core`: `DriverClient`, `Adb`, `DriverLifecycle`, `AppLifecycle`,
   `DeviceSession`, `SessionJournal`. Update `DriverClientTest` (fake driver) to
   `runTest`/`runBlocking`; keep the fault-injection scenarios in `:host:validation`
   (`PhaseZeroMain`, already coroutine-based) compiling — they become the primary device
   check: `host --no-reboot emulator-5554 85e49002 <apks>` must still print every
   `PHASE_0_OK` / `PHASE_1_*_OK` line. Update `project-architecture.md` invariants text
   here.
3. **`:host:service` → coroutine servicers** (second commit of the step-2 lane).
   `:host:service:test`, fixture-tests via the JVM
   dist, then `nativeCompile` + re-record native-image config + `TAP_BIN=<native>
   TAP_MANAGE_SERVICE=1 TAP_SERIALS=… pytest clients/python/tests` (Python client is the
   unchanged consumer, so it proves wire compatibility).
4. **Pending: Kotlin SDK + JUnit 5 → `suspend` + `tapTest`.** Fixture tests rewritten; docs guide,
   README, KDoc; `framework-gaps.md` rows "Synchronous API instead of `tapTest`" and
   "No `DeviceBarrier`" moved out only with the test that proves cancellation of a sibling.
5. **Landed (`03b00b7`): docs sweep**: `service-api.md` (status mapping unchanged but the servicer description),
   `project-architecture.md` module table and threading notes, `release-engineering.md`
   version lines, `phase-1-progress.md` if any checklist row is touched, this file's status.

## Risks and open questions

- **Cancellation vs. the mutation gate.** The protocol's `INDETERMINATE` / never-replay rules
  are about transport loss after acceptance. Coroutine cancellation must map onto the
  existing `CANCEL` frame and never onto closing the socket mid-command; a cancelled
  `await()` must still let the reader record the terminal response. JVM regressions now cover
  this in `DriverClientTest` and through an in-process grpc-kotlin `Execute` call in
  `TapServiceLifecycleTest` (`CANCEL` written, terminal consumed, no poison). The device
  validation scenario next to `PHASE_1_CANCEL_AFTER_MUTATION_OK` remains pending.
- **Blocking reads are not cancellable.** Socket reads and `Process.waitFor` only unblock by
  closing/destroying. Scope cancellation must close the resource, which is the existing
  `close()` behaviour — keep `close()` idempotent and callable from a `finally`.
- **`Dispatchers.IO` sizing.** Default parallelism 64; the service holds one reader + one
  heartbeat per open session plus adb calls. Fine for the local matrix; revisit
  (`limitedParallelism` / own dispatcher) if a pool of dozens of devices appears.
- **`runBlocking` in JUnit.** `tapTest` blocks the JUnit thread by design (plan §12: real time,
  not virtual). JUnit's `@Timeout` interrupts the thread; `runBlocking` translates the
  interrupt into cancellation of the root job, which is exactly the plan's behaviour — verify
  with a fixture test.
- **Native image.** Re-record config after adding grpc-kotlin; check `nativeCompile` output
  for coroutine `ServiceLoader` warnings (`kotlinx.coroutines.CoroutineExceptionHandler`,
  `MainDispatcherFactory` — both absent-but-harmless on the JVM, must be confirmed harmless in
  the image).
- **grpc-kotlin version coupling.** `protoc-gen-grpc-kotlin` lags grpc-java releases; pin a
  pair known to build together (grpc-java 1.75.0 is current here) and record the pair in
  `release-engineering.md`.
- **Python parity.** The Python client stays synchronous over the same wire; nothing here
  changes `tap.proto`. An `asyncio` client remains a separate gap.
- **Validation executable.** `:host:validation` already uses coroutines for fan-out; it must
  not become a product dependency (rule in `CLAUDE.md`) and its scenarios remain the device
  proof for step 2.
