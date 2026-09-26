# Tap Code Review

_2026-09-26 · working tree incl. uncommitted daemon rename · CI, release and docs excluded_

| Critical | High | Medium | Low | Nit | Total | Confirmed by lead |
|---|---|---|---|---|---|---|
| 1 | 30 | 77 | 55 | 8 | 171 | 38 |

## Verdict

Tap is built on sound instincts: a separate driver package, lazy selectors, a mutation gate, no replay after acceptance, per-serial locks, a journal and recovery. The command engine and the daemon's registry logic are the strongest code in the repo, and the invariants listed in CLAUDE.md are mostly honoured.

It is not close to "perfect". **The working tree does not build**: `:host:daemon` fails to compile. Python `import tap` fails on the 3.10 floor it declares. The device driver can corrupt its own output stream. Several paths report "nothing happened, retryable" after the device has already mutated, which undermines the framework's main correctness claim.

The architecture carries weight it does not need. It has **two schemas for one command model**: TAP1 JSON and tap.v1 protobuf, joined by a 784-line converter plus mirror tests. Test hooks and fault injection are baked into product classes. Several 500–700-line god classes on both host and client hide the good core. The Kotlin client needs 700 lines to memoize a connection.

The findings below are ranked. Fix the blockers first, then collapse the duplicate schema. Most of the Medium items disappear once the big classes are split along the seams proposed at the end.

## Priority order

1. **Make the tree build again.** `ClientConnectionServer.kt:60` calls `observeRelease`, which does not exist. Wire it to `disconnectObservedClient(id, token)` in the Observe `finally`, and add an in-process servicer test so this cannot recur. _(DM-1, DM-2)_
2. **Stop the driver corrupting its own stream.** The reader thread writes rejections straight to the socket while the pipeline writer is streaming. Route every write through the outbound queue. _(DR-1)_
3. **Make "may have mutated" true by construction.** Short-circuit the gate once mutated. Have the engine rewrite any post-gate failure to a mutating outcome. Gate `scrollUntil` once. _(E-1, E-2, DR-2)_
4. **Lock down the daemon.** Unauthenticated loopback TCP combined with `write_to` / `apk_path` / `driver_apk` gives any local user file read, file write and APK install as you. Use a Unix socket (0600) or a token, and stream bytes instead of passing paths. _(X-2, API-3, CLI-1)_
5. **Only `WAIT_TIMEOUT` is a timeout.** Host core, the Kotlin SDK and the Python client all convert every failure into a wait timeout. That hides transport loss and driver death. _(X-6, H-18, K-2, PY-5)_
6. **Structured errors over gRPC.** Attach `google.rpc.ErrorInfo{reason=ErrorCode}`, delete message-string matching in both clients, and map unknown exceptions to INTERNAL. _(X-8, DM-6, K-7, PY-4)_
7. **One schema for commands.** Make TAP1 payloads protobuf (javalite on the device) and delete `Conversions.kt`, the enum mirror test, `CanonicalJson` and the default-divergence risk. _(X-1, P-1)_
8. **Fix connection liveness and reaping.** Python `connect()` never observes, the daemon never reaps a connection that stopped observing, a detach timeout closes the shared connection, and junit5 reuses dead connections. _(DM-3, PY-2, K-3, J-2)_
9. **Traversal selectors on raw `AccessibilityNodeInfo`.** Walking `UiObject2` probably costs an idle-wait per node access. Confirm this at the pinned AndroidX commit, then evaluate on node infos and materialize only the final match. _(DR-5)_
10. **Selector composition must not silently drop parts.** `and`/`or`/`descendant` discard scope and pick in both clients, so the wrong element is targeted and the one-match check still passes. _(K-5, K-6, PY-7)_
11. **Per-serial ADB admission.** `ADB_RUNNER_PERMITS = 1` serializes all ADB traffic for every device, and one wedged child gates them all. _(H-1)_
12. **Enforce the driver contract at connect.** Build IDs, supported operations and capabilities are negotiated and MAC'd, then never checked. A stale driver shows up mid-test as weird behaviour. _(H-6, P-7, P-8, DM-9)_

## Architecture

### One command model, two schemas

TAP1 (kotlinx-serialization JSON in `contracts/protocol`) and tap.v1 (protobuf in `contracts/api`) describe the same commands, selectors, results and error codes one-to-one. Holding them together costs `Conversions.kt` (784 lines, about half of it test-only reverse mapping), `EnumMirrorTest`, `GoldenRoundTripTest`, `CanonicalJson`, the `CommandNames` descriptor hack, and a class of bugs where host and driver defaults drift (`encodeDefaults=false`). **Recommendation:** make the TAP1 frame payload protobuf, either the same messages or an internal `tap.wire` package generated for JVM and javalite. The frame codec, HMAC transcript and mutation semantics stay as they are. The daemon then forwards `Command` with at most an envelope change, and one golden suite covers both hops. Keep a separate internal package only if you want the freedom to diverge, but generate both sides from `.proto`.

### Security model is "same user", but the transport is not

`127.0.0.1` TCP is reachable by every local account and by containers sharing the network namespace. Combined with API fields that take host paths (`write_to`, `apk_path`, `driver_apk`), any local user can make the daemon read and write files and install APKs as you. On the device, the driver's port is reachable by any app, and the session secret travels in `am instrument` argv. **Recommendation:** bind a Unix domain socket in `~/.tap` (0700 directory, 0600 socket; grpc-java epoll/kqueue and grpc-python both support `unix:`), or require a bearer token read from a 0600 `daemon.json`. Stream bytes instead of passing paths. Deliver the secret through a 0600 file, or use `localabstract:` forwarding on the device.

### Vocabulary: one word, four meanings

`ClientConnection` is the driver's TAP1 connection handler, the daemon's per-client registry entry, the Kotlin SDK's connection object, and (as `TapClientConnection`) the JUnit memo. `TapServer` is the gRPC server in the design doc but the daemon-process wrapper in Python. One Gradle module holds both `com.company.tap.daemon` and `com.company.tap.server`. The CLI speaks of `serve`, `start`, `daemon` and `server`. **Proposed map:** device side `DriverSession`; daemon side `ClientRegistry` / `ClientEntry`; SDK `TapConnection`; JUnit `ConnectionMemo`; Python `TapDaemon`; one package root per module (`…daemon.core`, `…daemon.grpc`, `…daemon.cli`).

### Placeholder namespace everywhere

`com.company.tap` is the Maven group, every package, the driver and fixture `applicationId`s, and the sync-sdk's permission and authority names. Permission names end up inside customer APKs, so renaming later is expensive. Pick the real namespace now, while there are no users.

### Test scaffolding lives in product code

`admissionWaitProbeForTest`, `holdAdmissionForTest`, `closeDrainProbeForTest` and `beforeOwnershipTransfer` sit on production classes. `DeviceSessionConfig` is a data class with `internal var` hooks, which breaks `equals`/`copy`. `DriverTransport.submitValidation` / `submitRawValidation` exist only for `:host:validation`. The driver APK ships `FaultController` (including a 30 s executor sleep), enabled by an instrumentation argument. **Recommendation:** inject small hook interfaces with no-op defaults, put raw submission behind an opt-in `ValidationTransport`, and move fault injection into a separate driver flavor or source set.

### The same bug in three layers

"Any failure during a wait becomes a timeout" appears in `AppLifecycle.awaitAppVisible` (host), `Device.awaitAppVisible` / `awaitScreenStable` / scroll (Kotlin), and `device.py` `await_*` (Python). Timeout defaults also differ: acquire is 300 s in Kotlin and 120 s in Python, and the host pads 5 s while the driver allows 10 s of grace. Defaults and error mapping should live in one place (`contracts`) and be surfaced through `Info`, so clients read them instead of copying them.

### Parity by convention

The Kotlin and Python clients share the same DSL and the same bugs (selector composition, wait mapping, serials[0] role assignment). A shared conformance table (selector → expected proto, status → exception, config key → env name) loaded by both unit suites would make parity something CI checks.

### Where the driver code lives

All of the driver except the pure-JVM command engine sits in the `androidTest` source set of an application module. Android lint, unit tests and normal visibility rules therefore do not apply to about 2,000 lines of the riskiest code. Move it to an Android library module (`device:driver:core`) and keep only a thin instrumentation entry point in `androidTest`, which is how the Appium UiAutomator2 server is structured in practice.

## Findings

Fix status per item: [`code-review-status.md`](code-review-status.md).

Severity: **C**ritical · **H**igh · **M**edium · **L**ow · **N**it. ✔ = re-checked against source by the lead reviewer. "(unverified)" = reasoned, not reproduced.

### Cross-cutting

Issues that span modules. Details for each are in the architecture section above.

- **X-1** · High · architecture — **Two hand-synchronized schemas for one command model**  
  `contracts/protocol ↔ contracts/api ↔ host/daemon/Conversions.kt`  
  784-line converter, mirror and golden tests, canonical JSON and default drift exist only to keep TAP1 JSON and tap.v1 proto aligned.  
  **Fix:** Generate TAP1 payloads from `.proto` (javalite on device) and forward `Command` through the daemon. Delete `Conversions`, `EnumMirrorTest` and `CanonicalJson`.
- **X-2** · High · security · ✔ — **Unauthenticated loopback daemon plus host-path fields**  
  `TapDaemonMain.kt:110 (bind), device.proto, app.proto`  
  Any local user can call `Screenshot(write_to=…)` to write files as you, `Install(apk_path=…)` to read them, and `Attach(driver_apk=…)` to install arbitrary APKs.  
  **Fix:** Unix socket at 0600, or a token in a 0600 `daemon.json` checked by a `ServerInterceptor`. Stream bytes instead of paths.
- **X-3** · Medium · naming · ✔ — **`ClientConnection` / `TapServer` / daemon-vs-server overload**  
  `driver, daemon, sdk, junit5, python`  
  Four unrelated classes share one name, and the two packages in one module make grep, stack traces and onboarding worse.  
  **Fix:** Adopt the naming map in the architecture section, with one package root per module.
- **X-4** · Medium · naming · ✔ — **`com.company` placeholder namespace**  
  `all modules, AndroidManifest, sync-sdk`  
  Permission and authority names ship inside customer APKs and are expensive to rename later.  
  **Fix:** Rename to the real reverse domain now.
- **X-5** · Medium · design · ✔ — **Test hooks and fault injection in product classes**  
  `host/core (Adb, DriverTransport, DriverClient, DeviceSession), device/driver/FaultController`  
  This widens the API, breaks data-class semantics, and ships a 30 s sleep path in the driver APK.  
  **Fix:** Use constructor-injected hook interfaces, an opt-in `ValidationTransport`, and a fault-injection driver flavor.
- **X-6** · High · bug — **Every failure during a wait is reported as a timeout, in three layers**  
  `AppLifecycle.kt:125, Device.kt:298/357/413, device.py:200/235/291`  
  `DRIVER_UNHEALTHY`, `TRANSPORT_LOST` and `INDETERMINATE` surface as flaky-looking timeouts, and the host variant also swallows `CancellationException` via `runCatching`.  
  **Fix:** Convert only `WAIT_TIMEOUT`. Pass everything else through the normal mapper. Rethrow cancellation.
- **X-7** · Medium · redundancy — **Timeout defaults duplicated and inconsistent**  
  `daemon common.kt, sdk, python, DriverClient.kt:593, driver watchdog`  
  Acquire is 300 s in Kotlin and 120 s in Python. The host pads 5 s but the driver's grace is 10 s, so healthy slow commands get declared INDETERMINATE.  
  **Fix:** Keep one `Defaults` object in contracts, echoed in `InfoResponse`. Derive host padding from driver grace plus slack.
- **X-8** · High · api-design · ✔ — **Errors cross gRPC as message strings**  
  `server/common.kt, TapScope.kt mapStatus, python _map_rpc_error`  
  Clients match "Timed out" and a DEVICE_BUSY marker in the text. Every `FAILED_PRECONDITION` becomes an `AppLifecycleException`.  
  **Fix:** Attach `google.rpc.ErrorInfo{reason, metadata}` to every non-OK status and switch on `reason` in clients.
- **X-9** · Medium · packaging — **Python import name `tap` and plugin name `tap` are likely taken (unverified)**  
  `clients/python/pyproject.toml, tap/`  
  The PyPI `tap.py` (TAP protocol) distribution also installs a top-level `tap` package, and `pytest-tap` depends on it, so the two cannot coexist in one environment.  
  **Fix:** Rename the import package to `tap_e2e` (matching the distribution) and the pytest11 entry point to `tap_e2e`.
- **X-10** · Medium · test · ✔ — **The validation harness is a single `main()` of chained scenarios**  
  `host/validation/PhaseZeroMain.kt (2,284 lines)`  
  Each scenario feeds its journal generation into the next, so one failure hides everything after it. Results are println markers grepped by humans. It uses `Thread.sleep` inside suspend code and hardcodes `~/.tap/sessions`, ignoring `TAP_STATE_DIR`. `--product-probe` is a second program hidden behind a flag.  
  **Fix:** Turn it into a JUnit device-test source set (for example `:host:core:deviceTest`), one class per scenario with its own setup, tag `@Tag("reboot")` for the destructive one, and move ProductProbe to its own entry point.
- **X-11** · Medium · architecture · ✔ — **The driver's product code lives in `androidTest`**  
  `device/driver/src/androidTest/**`  
  No lint and no unit tests, and 2,000 lines can only be exercised through the instrumentation.  
  **Fix:** Create an Android library `device:driver:core` and keep a thin `androidTest` entry point.
- **X-12** · Low · redundancy · ✔ — **Uncompiled "archived" code in the tree**  
  `archive/pool-roles, archive/pool-leases`  
  It rots silently and shows up in grep. Git history already preserves it.  
  **Fix:** Delete it, and reference the commits in the decision record instead.
- **X-13** · Low · test — **Parity between the clients is not tested**  
  `clients/*, samples`  
  The same bugs ship in both clients.  
  **Fix:** Shared conformance tables (YAML) loaded by the Kotlin and Python unit suites.

### Gradle build logic

The build works, but it is hand-rolled per module. There is no version catalog and no convention plugins, and outputs are wired across projects by file path.

- **B-1** · Medium · redundancy · ✔ — **No version catalog**  
  `*/build.gradle.kts`  
  grpc `1.75.0` appears five times, coroutines `1.10.2` about seven times, serialization `1.10.0` four times and JUnit `5.13.4` four times, so one bump means touching a dozen places.  
  **Fix:** Use `gradle/libs.versions.toml`.
- **B-2** · Medium · redundancy · ✔ — **No convention plugins**  
  `*/build.gradle.kts`  
  `jvmToolchain(17)`, `useJUnitPlatform()`, the publishing block and the Dokka GFM block are all copied between modules.  
  **Fix:** Add a `build-logic` included build with `tap.kotlin-jvm`, `tap.published` and `tap.dokka` plugins.
- **B-3** · Medium · build · ✔ — **Cross-project wiring through output file paths**  
  `host/daemon/build.gradle.kts (bundleDriver), samples/fixture-tests`  
  `dependsOn(":device:driver:assembleDebug")` plus hardcoded APK paths breaks with configuration-cache and isolated projects, and silently picks up stale files.  
  **Fix:** Expose the APKs through a consumable configuration (or AGP artifacts API) and resolve them in the consumer.
- **B-4** · High · build · ✔ — **The daemon bundles the **debug** driver**  
  `host/daemon/build.gradle.kts:43-51`  
  Each build signs with whatever debug keystore that machine has, so a new daemon cannot upgrade a driver installed by another build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and there is no uninstall fallback in `host/core`.  
  **Fix:** Build a release variant signed with a project key (or handle a signature mismatch by uninstall and reinstall), and check the installed versionCode.
- **B-5** · Low · build · ✔ — **`kotlinx-serialization-json` is `implementation` while the public types are `@Serializable`**  
  `contracts/protocol/build.gradle.kts:11`  
  Every consumer re-declares it.  
  **Fix:** Make it `api`, or hide serialization behind the codec.
- **B-6** · Low · build · ✔ — **The daemon JVM toolchain is 25, but the workflow requires `JAVA_HOME` to be JDK 17**  
  `gradle/gradle-daemon-jvm.properties`  
  Two contradictory statements about which JDK runs Gradle.  
  **Fix:** Pick one, and let the toolchain provisioning replace the manual `JAVA_HOME`.
- **B-7** · Nit · naming · ✔ — **The generated `ENGINE_VERSION` KDoc still says "service"**  
  `contracts/protocol/build.gradle.kts:20,35`  
  Leftover from the rename.  
  **Fix:** Change it to "daemon".

### contracts/protocol (TAP1)

A disciplined wire model: a sealed Command/Result/Response, a domain-separated HMAC transcript, golden files and bounded selectors. The weak spots are defaults omitted on the wire, negotiated fields that are never used, a chatty frame codec, and missing negative tests.

- **P-1** · High · bug · ✔ — **Defaults are not encoded, so the receiver's defaults win**  
  `CanonicalJson.kt:12, DriverClient.kt:67 (Json config)`  
  `encodeDefaults=false`, so `Scroll.distancePercent`, `WaitScreenStable.signal` and others are absent on the wire. A host and driver built with different defaults silently disagree.  
  **Fix:** Set `encodeDefaults=true` for requests, or make the fields required with no Kotlin default. A golden test should fail when a default changes.
- **P-2** · Medium · redundancy · ✔ — **Canonical JSON exists only because `negotiation` is re-encoded inside `Authentication`**  
  `CanonicalJson.kt, Authentication.kt, DriverClient.kt:522-561`  
  The transcript already takes raw payload bytes. Canonicalization is needed only because the negotiation is embedded and re-serialized.  
  **Fix:** Send the negotiation as its own frame (or as raw bytes) and MAC exactly what was sent. Then drop `CanonicalJson`.
- **P-3** · Medium · perf — **About 13 small writes per frame and Nagle left on**  
  `FrameCodec.kt:13-18,41; no tcpNoDelay anywhere`  
  A new `DataOutputStream` over an unbuffered socket issues one write per int, long or short. Header-then-payload writes trigger the Nagle plus delayed-ACK stall (up to about 40 ms) over adb forwarding.  
  **Fix:** Encode each frame into one `ByteArray` and make one `write`. Set `tcpNoDelay=true` on both ends. Buffer reads.
- **P-4** · Nit · style — **`catch (e: EOFException) { throw e }` does nothing**  
  `FrameCodec.kt:14-18`  
  Noise.  
  **Fix:** Delete it.
- **P-5** · Medium · test — **No malformed-frame tests**  
  `FrameCodecTest`  
  Bad magic, unknown type, negative or oversized length and truncation are untested on the parser that faces an attacker.  
  **Fix:** A table-driven negative suite.
- **P-6** · Low · perf — **`"%02x".format` once per byte**  
  `Blob.kt:74`  
  A 64 MB blob means 64 M format calls.  
  **Fix:** Use a lookup-table hex encoder, or `HexFormat` on the JVM.
- **P-7** · Medium · design — **Capabilities are negotiated and MAC'd but gate nothing**  
  `Messages.kt (capabilities), Authentication.kt`  
  The promise that additive features require an authenticated capability is false.  
  **Fix:** Either gate features on capabilities or delete them (`supportedOperations` already exists).
- **P-8** · Medium · design — **Build IDs are never compared, and `UIAUTOMATOR_BUILD_ID` is hand-copied**  
  `Messages.kt:9-12`  
  Skew between the host and the driver goes undetected, and the UiAutomator ID will drift from Gradle.  
  **Fix:** Generate it from the catalog, and have the host require a matching driver build (see H-6).
- **P-9** · Medium · design — **Error flags are per code, but correctness depends on when the error happened**  
  `ErrorCode.kt:41,55`  
  `PAYLOAD_TOO_LARGE` is marked mutating, yet the read-only `dumpHierarchy` returns it. `CANCELLED` is marked retryable.  
  **Fix:** Add a per-response `mayHaveMutated`, computed from `mutationStarted`. Make `CANCELLED` non-retryable.
- **P-10** · Low · design — **`CommandNames` depends on sealed-descriptor internals and serializes a whole command to read `op`**  
  `Commands.kt:322,326 (CommandNames)`  
  It is fragile across kotlinx upgrades and wasteful.  
  **Fix:** Declare an explicit `op` per subclass.
- **P-11** · Low · design — **`timeoutMs` is validated in three inconsistent places, one of which clamps silently**  
  `Commands.kt:25, DriverClient.submit, ClientConnection.kt:115, DriverCommandEngine`  
  Out-of-range values mean different things on different paths.  
  **Fix:** Validate once in `RequestDecoder` and return `INVALID_ARGUMENT`.
- **P-12** · Medium · robustness — **Deep nesting is checked after decoding (unverified)**  
  `RequestDecoder + recursive Node`  
  Deeply nested `allOf` can overflow the stack in the serializer before the limits apply.  
  **Fix:** Pre-scan bracket depth, or catch `StackOverflowError`.
- **P-13** · Medium · bug — **An empty `Match.value` is accepted**  
  `CommandValidation.kt:129-134`  
  `CONTAINS ""` matches everything. With `First`, it taps an arbitrary node.  
  **Fix:** Reject empty values except for `EQUALS`.
- **P-14** · Low · design — **Misplaced types**  
  `Results.kt, Messages.kt`  
  `CommandFailure` is driver-internal but lives in the contract. `Response.ok` is both a property and a factory.  
  **Fix:** Move `CommandFailure` to the engine and rename the factory `success()`.
- **P-15** · Low · design — **Swipe always returns `Moved(true)`**  
  `Results.kt Moved, UiAutomationCommands.kt:82`  
  Callers may branch on it.  
  **Fix:** Return `Done`, or compute it the way scroll does.
- **P-16** · Low · design — **The session and generation are re-sent on every request and checked after queueing**  
  `Commands.kt Request envelope`  
  Authentication already binds the connection, so the check adds bytes for little value, and it runs late.  
  **Fix:** Check in the reader before queueing, or drop the fields.

### device/driver/command-engine

The strongest module. Reader → queue → executor → writer + watchdog is correct in principle. The mutation gate is taken under a lock and responses are terminal exactly once via CAS. The holes are about what happens after the gate has opened.

- **E-1** · High · bug · ✔ — **The gate does not short-circuit once mutated**  
  `CommandPipeline.kt:245-253`  
  A second `markMutationStarted()` after the deadline or a cancel throws `DEADLINE_EXCEEDED` / `CANCELLED` (`mayHaveMutated=false`) for a command that already scrolled, so the host may replay a mutation.  
  **Fix:** `if (command.mutationStarted) return` first thing inside the lock. Add a test.
- **E-2** · High · bug — **Failures after the gate are not upgraded**  
  `CommandPipeline.kt:282-293, DriverCommandEngine.kt:95`  
  `WAIT_TIMEOUT`, `NOT_FOUND` and similar are passed through after input was sent, so the "nothing happened" claim depends on each handler getting it right.  
  **Fix:** In `execute`, rewrite non-mutating codes after the gate to `INDETERMINATE` (keeping the original in detail), or set a per-response `mutated` flag.
- **E-3** · Low · redundancy — **`CommandPhase` is written and never read**  
  `CommandPipeline.kt:131,152,226,262,271`  
  Dead state.  
  **Fix:** Delete it, or use it in snapshots and watchdog decisions.
- **E-4** · Medium · perf — **PONG queues behind up to 256 blob chunks**  
  `CommandPipeline writer, BlobTransfer`  
  A large screenshot on a slow link can starve the heartbeat, and the host then poisons a healthy session.  
  **Fix:** A priority lane for control frames.
- **E-5** · Low · test — **`sleep()` bypasses the injected `Clock`**  
  `CommandContext.kt:78-82`  
  Fake-clock tests cannot drive it, and it polls every 10 ms.  
  **Fix:** Use `clock`, and wait on a condition signalled by cancel.
- **E-6** · Low · robustness — **`writerDone.await()` has no timeout**  
  `CommandPipeline.kt:188`  
  It can hang on shutdown (currently mitigated by closing the socket).  
  **Fix:** `await(timeout)` and log.
- **E-7** · Nit · naming — **Engine `Command` clashes with `protocol.Command`, and `transferBlob` returns a `Pair`**  
  `Command.kt, CommandContext.kt`  
  Files import both.  
  **Fix:** Rename it `PendingCommand` and return a data class.
- **E-8** · Medium · test — **Post-mutation paths are untested**  
  `CommandPipelineTest`  
  E-1, E-2 and E-4 would all have been caught.  
  **Fix:** Add those cases.

### device/driver (instrumentation)

Functionally rich but the riskiest module. It has one genuine stream-corruption bug, error codes that break the taxonomy in `scrollUntil`, text verification that re-resolves a selector the input may have invalidated, and a likely O(n) idle-wait traversal path. `UiAutomationCommands` has become a god object.

- **DR-1** · High · concurrency · ✔ — **The reader thread writes to the socket concurrently with the writer thread**  
  `ClientConnection.kt:91,102 vs 147-158`  
  `DUPLICATE_OR_STALE` and decoder rejections call `FrameCodec.write` on the reader thread while the pipeline writer streams responses and blobs. There is no lock, and each frame is many `write` calls, so frames interleave, the host loses the transport, and an in-flight mutation becomes INDETERMINATE.  
  **Fix:** Send every outbound frame through the pipeline's outbound queue (`Outbound.ImmediateResponse`).
- **DR-2** · High · bug — **`scrollUntil` gates on every iteration and reports non-mutating errors after scrolling**  
  `UiAutomationCommands.kt:493-546 (scrollUntil)`  
  It returns `WAIT_TIMEOUT`, `NOT_FOUND/END_REACHED` and `MAX_SCROLLS` with `mayHaveMutated=false` after real scrolls. From iteration 2, container loss throws a raw `NOT_FOUND` instead of `staleTarget`.  
  **Fix:** Gate once, before the first scroll, and send every later exit through one mutated-aware helper (E-2 fixes this generically).
- **DR-3** · High · bug — **Text verification re-resolves the selector that the input just invalidated**  
  `UiAutomationCommands.kt:381-401, 406-486, 554-566`  
  `element(text("old")).setText("new")` stops matching after the edit, which produces a false `TEXT_MISMATCH` / `FOCUS_TIMEOUT`.  
  **Fix:** Verify against the resolved node identity within the command (allowed by the invariant) or the focused input node.
- **DR-4** · Medium · bug — **Password fields cannot be verified (unverified)**  
  `UiAutomationCommands.kt:399,483`  
  Masked text never equals the expected value, so login flows fail.  
  **Fix:** When `isPassword`, verify the length or skip verification. Add a fixture.
- **DR-5** · High · perf — **Traversal walks `UiObject2`, likely with an idle-wait per access (unverified)**  
  `SelectorCompiler.kt:280-450 (TRAVERSAL)`  
  `getAccessibilityNodeInfo()` waits for idle (up to 1 s) and refreshes. Relations make it O(n²) on animating screens, exactly where regex and relation selectors are needed.  
  **Fix:** Confirm at the pinned AndroidX commit, then evaluate on raw `AccessibilityNodeInfo` roots and materialize only the final match.
- **DR-6** · Medium · bug — **`typeText` inserts at the tap point but verifies an append, and waits up to the full deadline**  
  `UiAutomationCommands.kt:406-486 (typeText)`  
  The centre tap places the cursor mid-text. Verification can spin for 120 s. Key-up events have `downTime=0`, and the clocks are mixed.  
  **Fix:** Move the selection to the end before typing, cap verification at about 1 s, and build key events properly.
- **DR-7** · Medium · robustness — **A malformed control frame throws out of `serve`**  
  `ClientConnection.kt:75,80,86`  
  The session is torn down without a CLOSE reason and looks like a crash.  
  **Fix:** Send a protocol-error CLOSE through the outbound queue, then close.
- **DR-8** · Medium · bug · ✔ — **`DUPLICATE_OR_STALE` reuses the original request ID**  
  `ClientConnection.kt:89-95`  
  The host may complete the real pending command with the rejection.  
  **Fix:** Treat it as a protocol violation and close the connection.
- **DR-9** · Medium · bug — **A stale object is reported as absent or 0**  
  `UiObjectAccess.kt:53-67`  
  `waitGone` succeeds spuriously during a re-render.  
  **Fix:** On stale, re-poll within the deadline.
- **DR-10** · Medium · design — **Text matching includes hint text, but snapshots strip it**  
  `SelectorCompiler.kt:136-143,304`  
  `text("Email")` matches an empty field, then stops matching after typing (which feeds DR-3).  
  **Fix:** Exclude the hint when `isShowingHintText` and keep a separate `hint` predicate.
- **DR-11** · Medium · perf — **Fingerprinting walks `UiObject2`, and `root` is fetched twice per window**  
  `UiAutomationCommands.kt:196-199, 601`  
  Each is an extra binder call plus the idle-wait cost.  
  **Fix:** Fingerprint on node infos and fetch `root` once.
- **DR-12** · High · bug — **Sync provider visibility is limited to the fixture (unverified)**  
  `device/driver/src/main/AndroidManifest.xml (<queries>)`  
  With `targetSdk 36` package visibility, a real AUT's provider is probably invisible, so idle sync works only in this repo.  
  **Fix:** Add a `<queries>` intent the sync-sdk provider exports, or `QUERY_ALL_PACKAGES` on the driver. Test with a non-fixture AUT.
- **DR-13** · Medium · bug — **Install order can drop the signature permission (unverified)**  
  `sync-sdk signature permission`  
  A driver installed before the AUT does not get the grant.  
  **Fix:** Define the permission in the driver too, or reinstall the driver after the AUT.
- **DR-14** · Medium · robustness — **One provider timeout disables sync for the rest of the session**  
  `SyncProviderClient.kt:23,41,54-73`  
  It sets a permanent `poisoned` flag, leaks a thread, and gives no mapping for `SecurityException`.  
  **Fix:** Poison with an expiry, or reset it on an AUT restart. Map errors explicitly.
- **DR-15** · Medium · security — **The accept loop can be held by any app on the device**  
  `TapDriverServer.kt:56-66`  
  Backlog 1 plus a 10 s authentication timeout, repeated, blocks the host indefinitely.  
  **Fix:** Accept in a loop, time out handshakes quickly, and prefer a `localabstract:` socket.
- **DR-16** · Medium · security — **The session secret travels in `am instrument` argv**  
  `TapDriverServer.kt:201, DriverLifecycle.kt:136-149`  
  It is visible in host and device `ps`.  
  **Fix:** Pass a path to a 0600 file and delete it after reading.
- **DR-17** · Low · design — **No `NOT_INTERACTABLE` check before tapping a disabled element**  
  `UiAutomationCommands.kt tap`  
  The taxonomy has the code, but the driver never returns it.  
  **Fix:** Check `isEnabled` before the gate.
- **DR-18** · Low · design — **A `Socket` is threaded through the handlers for fault injection**  
  `DriverCommandEngine, UiAutomationCommands.tap(socket, generation)`  
  Product signatures are shaped by tests.  
  **Fix:** Inject a `FaultHooks` interface.
- **DR-19** · Low · perf — **Selectors are compiled per poll, and validation is duplicated**  
  `UiObjectAccess, DriverCommandEngine.kt:100-110`  
  Wasted work on every tick.  
  **Fix:** Compile once per request and pass the `CompiledSelector`.
- **DR-20** · Low · concurrency — **`triggered` is a plain `var` shared across threads**  
  `FaultController.kt:25`  
  A data race.  
  **Fix:** Use `AtomicBoolean.compareAndSet`.
- **DR-21** · Low · robustness — **`runCatching` swallows `Error`s, and a version mismatch closes with no reason**  
  `TapDriverServer.kt:61,120,163`  
  The host sees a bare EOF.  
  **Fix:** Catch `Exception`, and send `AUTH_RESULT{reason}`.
- **DR-22** · Low · design — **`dumpHierarchy` returns `PAYLOAD_TOO_LARGE`, marked mutating**  
  `UiAutomationCommands.kt:331-338`  
  A misleading flag on a read-only command.  
  **Fix:** Stream it as a blob.
- **DR-23** · Low · bug — **The native path may count the container itself (unverified)**  
  `UiObjectAccess.kt containerHasObject`  
  It disagrees with the traversal path.  
  **Fix:** Exclude the container on both paths, and add a test.

### device/sync-sdk + fixture-app

Sync-sdk is small and fine. The fixture app covers the happy paths but none of the failure modes found in this review.

- **SY-1** · Low · redundancy — **`processStartUuid` and `sessionIdentity` have the same lifetime**  
  `TapSynchronization.kt`  
  Redundant identity.  
  **Fix:** Keep one.
- **SY-2** · Nit · design — **`require` throws `IllegalArgumentException` across binder**  
  `TapSynchronizationProvider.kt`  
  The driver sees a generic failure.  
  **Fix:** Return an error bundle.
- **SY-3** · Medium · test — **No fixtures for the known failure modes**  
  `fixture-app/`  
  Missing: password field, pre-filled EditText, text-changing selector, popup or spinner window, WebView, busy animating screen.  
  **Fix:** One screen per case, each with a fixture test.
- **SY-4** · Low · design — **The fault channel reuses the sync signature permission**  
  `FixtureFaultProvider`  
  It teaches the wrong trust pattern.  
  **Fix:** Give it a separate permission.
- **SY-5** · Nit · build — **Compose plugin plus XML layouts in one fixture**  
  `fixture-app/build.gradle.kts`  
  That is intentional for mixed coverage, but the purpose of each screen is undocumented.  
  **Fix:** Name the activities after the scenario each one covers.

### host/core

Careful about cancellation and reaping, and the transmission-state model is what makes "never replay" enforceable. But it is over-engineered in places, carries test scaffolding, serializes all ADB traffic globally, never checks the driver it talks to, and `DeviceSession.open` / `DriverLifecycle` are very hard to reason about.

- **H-1** · High · perf · ✔ — **`ADB_RUNNER_PERMITS = 1` across all devices**  
  `Adb.kt:643, 177-187`  
  One `Adb` per daemon serializes every device's ADB traffic, and admission busy-polls every 10 ms. A reap residual on one device gates all of them.  
  **Fix:** Per-serial admission plus a small global cap, a per-serial reap gate, and a `Semaphore` instead of polling.
- **H-2** · Medium · design — **The operational exceptions extend `IllegalStateException`, and `Throwable` is caught**  
  `Adb.kt:43,58,328,355,365,605-615`  
  Callers cannot tell gating from bugs, and cancellation gets swallowed.  
  **Fix:** A sealed `AdbException`. Catch `Exception` and rethrow `CancellationException`.
- **H-3** · Low · perf — **Regexes are compiled per call**  
  `Adb.kt:288,445,473,567,585; CommandException.kt:167`  
  Needless allocation.  
  **Fix:** Hoist them into `private val`s.
- **H-4** · Low · robustness — **`cat /proc/net/tcp /proc/net/tcp6` fails without IPv6, and the "best effort" keyguard dismissal throws**  
  `Adb.kt:443,427`  
  False probe failures.  
  **Fix:** Tolerant probes, and catch-and-log.
- **H-5** · Medium · perf — **Three or four full copies of a 64 MB blob**  
  `BlobReceiver.kt:24,54`  
  `toByteArray()` runs for the hash, twice in the getter, and again in `artifact().copyOf`.  
  **Fix:** Incremental `MessageDigest`, a pre-sized buffer, and a single hand-off.
- **H-6** · High · design — **`driverContract` is stored and never read**  
  `DriverClient.kt:127,573`  
  A stale driver or a missing op is discovered mid-test.  
  **Fix:** Require a matching build at connect (reinstall on mismatch), and reject unsupported ops locally.
- **H-7** · Medium · bug — **The connect deadline lives for the client's lifetime, and the heartbeat then exits silently**  
  `DriverClient.kt:60,136-147,449,577`  
  A footgun: any caller passing a deadline gets a client whose heartbeat dies unnoticed.  
  **Fix:** Scope the deadline to the handshake, and make the heartbeat's exit poison the client.
- **H-8** · Medium · bug — **Host padding (5 s) is shorter than the driver's grace (10 s)**  
  `DriverClient.kt:593 vs driver watchdog grace`  
  Spurious INDETERMINATE and poisoned sessions on slow devices.  
  **Fix:** Derive the padding from a shared constant greater than the grace.
- **H-9** · Low · design — **`lastWriteNanos` is set before the write, a `Long` timeout is truncated to `Int`, and each write launches its own `async`**  
  `DriverTransport.kt:57,183-189,295-298`  
  Heartbeat accounting is off, and the writer is churned.  
  **Fix:** Set the timestamp after the write, keep `Long`, and use one writer coroutine fed by a `Channel`.
- **H-10** · Medium · bug — **`connectWithRetry` retries on any `Throwable` for 20 s**  
  `DriverLifecycle.kt:458-476`  
  It retries authentication and version failures, and delays cancellation.  
  **Fix:** Retry only connect or EOF before HELLO.
- **H-11** · Medium · security — **Instrumentation arguments are passed to `adb shell` unescaped**  
  `DriverLifecycle.kt:136-149`  
  Device `sh` re-parses the joined string, so a value containing `;` or `$` runs as a command.  
  **Fix:** Single-quote each token, or validate against `[A-Za-z0-9_.:-]+`.
- **H-12** · Low · design — **Leftovers**  
  `DriverLifecycle.kt:21-22,62,136,336`  
  `DEVICE_PORT` is used for recovery even though the real port may differ. The long-running child is started outside Adb reaping. Output is rescanned from the start on every tick.  
  **Fix:** Record the real port, add `Adb.startLongRunning`, and scan incrementally.
- **H-13** · Medium · bug · ✔ — **Launch failure is detected by `"Error" in output || "Exception" in output`**  
  `AppLifecycle.kt:101`  
  `.ErrorActivity` or `com.x.exceptions` produce false failures.  
  **Fix:** Use `am start -W` and parse `Status:` / `Error:` prefixes.
- **H-14** · Medium · bug — **The caller's timeout is spent two or three times**  
  `AppLifecycle.kt:96-116`  
  Launch plus visibility (plus observation) can exceed `MAX_REQUEST_TIMEOUT_MS`.  
  **Fix:** One deadline, with the remainder passed to each step.
- **H-15** · Medium · perf — **`awaitIdle` runs four extra ADB calls per poll**  
  `AppLifecycle.kt:149-155`  
  Amplified by H-1.  
  **Fix:** Check process identity at the start and end only, or have the driver report it.
- **H-16** · Low · bug — **`grantPermission` is documented as verified but is not, `syncIdentity` is not reset on launch, and the public constructor bypasses the cache**  
  `AppLifecycle.kt:52,86,96-104`  
  Contract drift.  
  **Fix:** Verify the grant, reset on launch, and make the constructor internal.
- **H-17** · Medium · bug — **`checkUsable` and `close` ignore a poisoned client**  
  `DeviceSession.kt:98`  
  A clean `CLOSED` is journaled after a possibly half-applied mutation, and the next session skips recovery.  
  **Fix:** Consult `client.isPoisoned`, and journal `BROKEN` / `QUARANTINED`.
- **H-18** · Medium · bug — **`awaitAppVisible` maps every error to a timeout and swallows cancellation**  
  `AppLifecycle.kt:125-135`  
  See X-6.  
  **Fix:** Convert only `WAIT_TIMEOUT`, and replace `runCatching` with try/catch.
- **H-19** · Medium · design — **A 180-line `open` with nested try/catch and a `suspendCancellableCoroutine` handoff**  
  `DeviceSession.kt:275-455 (open)`  
  Hard to review, hard to change safely.  
  **Fix:** A step pipeline with a rollback stack and an explicit `Handoff` object (see redesign).
- **H-20** · Medium · concurrency — **The lease probe takes the lock to test it**  
  `SessionJournal.kt:98 (isLeased)`  
  A concurrent acquirer can fail with a spurious busy, and the answer is stale anyway.  
  **Fix:** Read the holder metadata without locking, labelled advisory.
- **H-21** · Low · redundancy — **`BROKEN` is used only by validation, and the unsupported-version branch is unreachable**  
  `SessionJournal.kt:39`  
  Dead code (but see H-17 for where `BROKEN` belongs).  
  **Fix:** Use `BROKEN` for real and drop the duplicate check.
- **H-22** · Medium · redundancy · ✔ — **The handshake is implemented three times (driver, client, fake)**  
  `testFixtures/FakeDriverServer.kt`  
  The fake can drift, so tests pass against a protocol that is no longer spoken.  
  **Fix:** `HandshakeInitiator` / `HandshakeResponder` in `contracts/protocol`, used by all three.
- **H-23** · Medium · test — **Untested areas**  
  `host/core/src/test`  
  AppLifecycle parsing and timeouts, `BlobReceiver`, lease timeout, `recoverJournal` branches, retry classification.  
  **Fix:** `FakeAdb` makes these cheap to add.
- **H-24** · Low · diagnostics — **Selector rendering does not escape its values, and a decode failure is reported as `BLOB_OUT_OF_ORDER`**  
  `CommandException.kt render; BlobReceiver.kt:35`  
  Confusing diagnostics.  
  **Fix:** JSON-escape the values, and add a `BLOB_MALFORMED` detail.

### contracts/api (tap.v1)

A reasonable service split with enums mirrored and tested. It needs consistent naming and default conventions, fields that are actually reachable, no host-path fields, and structured error details.

- **API-1** · Medium · naming · ✔ — **Three spellings of one ID**  
  `client_connection.proto, device.proto`  
  `owner_connection_id`, `client_connection_id`, `held_by_client_connection_id`.  
  **Fix:** Use `client_connection_id` everywhere. There is no compatibility to keep, so rename now.
- **API-2** · Low · api-design · ✔ — **Two "use the default" conventions**  
  `device.proto AttachDeviceRequest`  
  `lease_timeout_ms` uses 0 to mean the default, while `optional default_timeout_ms` uses absence.  
  **Fix:** Use `optional` everywhere.
- **API-3** · High · security · ✔ — **Host paths in the public API**  
  `ScreenshotRequest.write_to, AppInstallRequest.apk_path, AttachDeviceRequest.driver_apk`  
  See X-2.  
  **Fix:** Return bytes and stream APK uploads. Keep the driver override as a daemon flag, not an RPC field.
- **API-4** · Medium · api-design — **`DEVICE_OFFLINE` can never be produced**  
  `device.proto DeviceState`  
  `Adb.devices()` drops non-`device` rows.  
  **Fix:** Report `offline` / `unauthorized` too, or remove the value.
- **API-5** · Medium · api-design — **The `Execute(screenshot)` blob cannot be fetched, and duplicates `DeviceServer.Screenshot`**  
  `command.proto Screenshot, ArtifactInfo.blob_id`  
  A dead path.  
  **Fix:** Remove the command from tap.v1.
- **API-6** · Medium · api-design — **Internal TAP1 operations leak into the public `Command`**  
  `command.proto SyncBootstrap/SyncPoll/Health/DumpHierarchy`  
  Clients can drive sync bootstrap, which is the host's job.  
  **Fix:** Expose only user-level commands. Keep internals in the wire schema.
- **API-7** · Low · api-design — **No pid or instance token**  
  `InfoResponse`  
  `tap stop` / `tap status` cannot verify which process they are talking to.  
  **Fix:** Add `pid` and `instance_token`.
- **API-8** · Low · api-design — **Free-text heartbeat messages**  
  `ClientConnectionEvent`  
  Nobody consumes them.  
  **Fix:** A typed `oneof` (Heartbeat, DeviceReleased, Closing).
- **API-9** · Low · api-design · ✔ — **Shared request and response messages**  
  `app.proto AppRequest/AppEmpty/AppBool`  
  Every App RPC is locked into the same shape.  
  **Fix:** Per-RPC messages (buf default).
- **API-10** · Low · naming · ✔ — **The `*Server` suffix instead of `*Service`**  
  `*.proto services`  
  It fights buf STANDARD lint and gRPC convention, and "server" is already overloaded (X-3).  
  **Fix:** `ClientConnectionService` / `DeviceService` / `AppService`.

### host/daemon

**It does not compile.** The rename was left half-done. Behind that, the registry logic is careful (exactly-once cleanup, orphan attach, sorted lock order) and its race tests are the best tests here. `TapDaemon` is a 647-line god class, `Conversions` is half test-only code, and error mapping is too eager.

- **DM-1** · Critical · build · ✔ — **Calls the nonexistent `daemon.observeRelease`**  
  `server/ClientConnectionServer.kt:60`  
  `:host:daemon:compileKotlin` fails, which breaks the daemon, the native image and every client suite. `TapDaemon.disconnectObservedClient` exists but nothing calls it.  
  **Fix:** Wire it in, and add an in-process test of the servicer.
- **DM-2** · High · bug — **Observe teardown ignores the observe token**  
  `ClientConnectionServer.kt Observe finally`  
  A stale or replaced stream tears down the live one, and an explicit Disconnect ends Observe as CANCELLED.  
  **Fix:** `disconnectObservedClient(id, token)`. Complete the flow normally on Disconnect.
- **DM-3** · High · leak — **Connections that never observe, or have stopped observing, are never reaped**  
  `TapDaemon connect/disconnect`  
  Per-serial locks are held until the daemon restarts (and Python `connect()` never observes).  
  **Fix:** Track `lastObservedAt` and reap after a grace period.
- **DM-4** · Medium · security — **No ownership check on Execute or Detach**  
  `TapDaemon.attachedDevice(id)`  
  Any client holding the ID can drive another client's device.  
  **Fix:** Require the `client_connection_id`, and return `PERMISSION_DENIED`.
- **DM-5** · Medium · bug — **The bundled driver is installed once per daemon per serial, and the device's copy is never checked**  
  `TapDaemon.kt:456-470`  
  Uninstalled drivers, other versions and reused emulators all fail at attach.  
  **Fix:** Compare the installed `versionCode` on every attach.
- **DM-6** · High · bug — **Any IAE or ISE is mapped to INVALID_ARGUMENT or FAILED_PRECONDITION**  
  `server/common.kt toStatus`  
  Internal `require` / `check` bugs surface as client mistakes.  
  **Fix:** Throw domain exceptions and map only those. Anything else is INTERNAL and gets logged.
- **DM-7** · Medium · bug — **A journal read failure is reported as Free**  
  `TapDaemon.devices()`  
  The client then fails at attach with a confusing error.  
  **Fix:** Report UNKNOWN with the reason.
- **DM-8** · Medium · bug — **`clean=null` for a quarantined session, and the test asserts the null**  
  `TapDaemon.detachDevice`  
  A misleading response.  
  **Fix:** Return `clean=false` plus the reason.
- **DM-9** · Medium · perf — **Device teardown is sequential, up to 60 s per device**  
  `TapDaemon disconnect`  
  Disconnecting N devices can take N×60 s.  
  **Fix:** Close them concurrently under `supervisorScope`.
- **DM-10** · Low · concurrency — **The inner and outer timeouts are equal**  
  `TapDaemon.closeDeviceBounded`  
  The outer timeout fires first and falsely reports `SESSION_CLEANUP_TIMEOUT`.  
  **Fix:** Outer = inner + margin.
- **DM-11** · Low · redundancy — **`close()` reimplements disconnect, and failures are logged two or three times**  
  `TapDaemon.kt:585-596, 335-336`  
  Duplication.  
  **Fix:** One `teardownConnection()`, logging only at the boundary.
- **DM-12** · Low · concurrency — **A non-volatile `var` touched by concurrent attaches (unverified)**  
  `installedBundled`  
  A possible race.  
  **Fix:** `ConcurrentHashMap.newKeySet()`.
- **DM-13** · Low · design — **Test seams are mutable private vars set through a secondary constructor**  
  `TapDaemon.kt:181-194`  
  The pattern differs from the SDK's `DiscoveryDeps`.  
  **Fix:** A constructor-injected `DaemonDeps`.
- **DM-14** · Medium · redundancy — **About half is reverse mapping used only by `GoldenRoundTripTest`**  
  `daemon/Conversions.kt (784 lines)`  
  Test code ships in the product.  
  **Fix:** Move it to test fixtures, and split the rest by concern (or delete it via X-1).
- **DM-15** · Low · design — **`DIRECTION_UNSPECIFIED` is rejected for swipe but silently becomes DOWN for `scroll_until`, and `maxScrolls=20` is hardcoded**  
  `Conversions.kt`  
  Inconsistent behaviour.  
  **Fix:** Reject it consistently, and put the default in `Defaults`.
- **DM-16** · Medium · robustness — **Unbounded `pm` calls and a magic number**  
  `AppServer Uninstall/Grant, awaitIdle 200`  
  A stuck `pm` hangs the RPC.  
  **Fix:** Bound every ADB call, and name the constant.
- **DM-17** · Nit · style — **Misplaced KDoc and a utility function inside the domain class**  
  `TapDaemon.kt:29, await(pending)`  
  Noise.  
  **Fix:** Move them.
- **DM-18** · Medium · test · ✔ — **No servicer, CLI, `toStatus` or AppServer tests**  
  `host/daemon/src/test`  
  Any servicer test would have caught DM-1.  
  **Fix:** In-process gRPC tests per service.
- **DM-19** · Low · test — **A reflection walk over private fields**  
  `TapDaemonLifecycleTest "no object cycle"`  
  It breaks on any refactor.  
  **Fix:** Assert that a `WeakReference` is collected.

### host/daemon CLI (TapDaemonMain)

The explicit `tap start` design is right. The implementation is fragile around process identity and concurrent starts.

- **CLI-1** · High · safety — **`tap stop` kills whatever pid `daemon.json` names**  
  `TapDaemonMain.kt:273-279`  
  After a reboot or pid reuse it kills an unrelated process.  
  **Fix:** Check `Info` and an instance token, send a `Shutdown` RPC, and signal only if the command line matches.
- **CLI-2** · High · concurrency — **No lock against concurrent start or serve**  
  `start / serve`  
  The second process overwrites `daemon.json`, the first is orphaned, and the shutdown hook deletes the survivor's descriptor.  
  **Fix:** An exclusive `~/.tap/daemon.lock` held for the daemon's lifetime, an atomic write, and delete-only-if-ours.
- **CLI-3** · Medium · bug — **`daemon.json` is built by string concatenation**  
  `TapDaemonMain.kt:122`  
  Backslashes or quotes in the adb path produce invalid JSON.  
  **Fix:** Use the JSON library already on the classpath.
- **CLI-4** · Medium · security — **Default umask**  
  `daemon.json`  
  The descriptor (and a future token) is world-readable.  
  **Fix:** Create it with 0600.
- **CLI-5** · Low · ux — **`status` does not probe liveness, `toInt()` throws a raw exception, options are global rather than per command, and re-exec drops JVM flags**  
  `status, --port parsing, relaunch`  
  Rough edges.  
  **Fix:** Probe `Info`, validate options per command, and forward `inputArguments`.

### clients/kotlin/sdk

The API surface is good and the taxonomy is clear. Internally it is over-built (six atomics plus a mutex, five copies of Execute construction, mandatory `tapScope`), leaks proto types, and has real bugs in waits, detach and selector composition.

- **K-1** · Medium · design — **Six atomics plus a mutex for one lifecycle**  
  `TapClient.kt ClientConnection`  
  `established` is write-only, `register()` is unused, `unusable` and `unusableCause` are redundant, and the event list is silently capped at 200.  
  **Fix:** One sealed `State` in an `AtomicReference`, and events as a `SharedFlow`.
- **K-2** · High · bug — **Waits convert every error into `WaitTimeoutException`**  
  `Device.kt:298,357,413`  
  See X-6.  
  **Fix:** Map only `WAIT_TIMEOUT`.
- **K-3** · High · bug · ✔ — **A detach drain timeout closes the whole shared connection**  
  `Device.kt:605-625 (launchFailClosed)`  
  Every other attached device in the process is torn down. In JUnit, later tests get the dead cached connection.  
  **Fix:** Poison only this device and send a best-effort Detach.
- **K-4** · Medium · api-design — **Proto types are public API**  
  `Device.kt, TapClient.kt public signatures`  
  SDK versions are chained to proto evolution, and `App.ProcessIdentity` clashes with `api.v1.ProcessIdentity`.  
  **Fix:** SDK-owned models with internal mappers. Rename it `AppProcess`.
- **K-5** · High · bug · ✔ — **`and` / `or` drop the right operand's scope and pick**  
  `Selectors.kt:113,120 (and/or)`  
  `a and list.at(2)` loses `at(2)`, so the wrong element is targeted while the one-match check still passes.  
  **Fix:** Reject operands that carry scope or pick, or compose them properly. Add unit tests.
- **K-6** · High · bug — **The receiver's pick is dropped**  
  `Selectors.kt:137,147 (descendant/child)`  
  `list.at(2).descendant(x)` searches under every list.  
  **Fix:** Carry the pick into the ancestor node, or reject it.
- **K-7** · Medium · bug — **String matching, and every `FAILED_PRECONDITION` becomes `AppLifecycleException`**  
  `TapScope.kt mapStatus`  
  Quarantine and daemon-closing errors are misreported.  
  **Fix:** ErrorInfo (X-8), plus `DeviceQuarantinedException`.
- **K-8** · Medium · redundancy — **Five copies of Execute construction, plus a magic `+60_000` deadline**  
  `Device.kt execute/executeOrThrow/await*/infoInner`  
  Drift risk.  
  **Fix:** One `rpcExecute(cmd, timeout)` and a named deadline policy.
- **K-9** · Medium · design · ✔ — **Mandatory `tapScope`, `ensureTapBound` and nested try/finally**  
  `TapScope.kt, docs getting-started`  
  The script example is four levels deep, versus two `with` blocks in Python.  
  **Fix:** `TapClient.use {}` / `connection.attach(...) { }` suspend helpers, and a lazy context marker.
- **K-10** · Medium · bug — **Claims calls are serialized on the device, but they are not**  
  `Device.kt:81 KDoc`  
  Callers rely on a guarantee that does not exist.  
  **Fix:** Serialize mutations with a per-device `Mutex`, or fix the doc.
- **K-11** · Low · design — **An incomplete predicate set, and the internal `allOf(vararg Node)` shares a name with the public one**  
  `Selectors.kt`  
  No checked, enabled, focused or selected predicates.  
  **Fix:** Complete the set and rename the internal overload.
- **K-12** · Low · bug — **The returned target is not scoped to the container, and `WAIT_TIMEOUT` arrives as `CommandException`**  
  `Element.scrollUntil`  
  Later actions can hit a duplicate outside the container.  
  **Fix:** Return `container.descendant(target)`.
- **K-13** · Low · ux — **Proto debug strings in error messages, and a wait timeout drops what was last seen**  
  `Selector.render(), ElementWait`  
  Poor diagnostics.  
  **Fix:** A pretty-printer, and include the last observation.
- **K-14** · Low · design — **A mutable global test seam, and it polls for process exit**  
  `TapDaemonProcess.processStarter`  
  Contradicts the per-call deps pattern.  
  **Fix:** Inject it, and use `onExit().await()`.
- **K-15** · Low · bug — **Nested cross-device admission replaces the context element (unverified)**  
  `Device DeviceAdmission`  
  Detaching A from inside B can wait on itself.  
  **Fix:** Use a set of admitted devices.
- **K-16** · Nit · style — **Unused imports, a catch that only rethrows, and a detached KDoc**  
  `TapExceptions.kt; TapClient.connect; TapClient.kt:46-58`  
  Noise, and Dokka loses the class doc.  
  **Fix:** Clean up.
- **K-17** · Medium · test — **No tests for selectors, `mapStatus`, Element or App**  
  `clients/kotlin/sdk/src/test`  
  K-5 and K-6 slipped through.  
  **Fix:** Pure JVM tests.

### clients/kotlin/junit5

Works, but `TapClientConnection` (706 lines plus 889 test lines) is a state machine for what is a lazy memoized value, and it never re-validates. Role assignment defeats parallelism. The generic `tapTest` breaks discovery.

- **J-1** · Medium · over-engineering · ✔ — **706 lines for create-once, reuse, recreate-if-dead**  
  `TapClientConnection.kt`  
  Generation and flight machinery, write-only `closed`, and tests that pin implementation details.  
  **Fix:** About 40 lines: `Mutex` plus `var current`, validated on `get()`. Three behavioural tests.
- **J-2** · High · bug — **A dead cached connection is never re-validated**  
  `TapClientConnection`  
  After K-3 or a daemon restart, every later test in the JVM fails.  
  **Fix:** Validate and recreate on `get()`.
- **J-3** · Medium · bug · ✔ — **Generic return type means expression-bodied tests are silently not discovered**  
  `TapTest.kt tapTest<T>`  
  `TestDiscoveryTest` exists as a band-aid.  
  **Fix:** `fun tapTest(block: suspend TapTestScope.() -> Unit): Unit`, then delete the guard test.
- **J-4** · Medium · perf — **Every single-role test takes `serials[0]`**  
  `TapExtension role assignment`  
  Parallel classes queue on one device, and client-side free-device selection is a TOCTOU race.  
  **Fix:** Rotate and retry on DEVICE_BUSY. Long term, an additive `AttachAny(serials)` RPC.
- **J-5** · Medium · concurrency — **`withLock` on the cancellation path is not NonCancellable (unverified)**  
  `DeviceBarrier`  
  The count gets corrupted and later users hang. It is also a generic utility living in junit5.  
  **Fix:** Decrement under `withContext(NonCancellable)`, and move it to the SDK or drop it.
- **J-6** · Low · redundancy — **Failure is recorded in two places**  
  `TapExtension`  
  Duplication.  
  **Fix:** Record it only in the interceptor.
- **J-7** · Low · design — **A single 60 s artifact budget shared by all devices, and no hook**  
  `TapExtension artifacts`  
  One slow device starves the others.  
  **Fix:** A per-device budget and an `ArtifactSink`.
- **J-8** · Low · design — **The env mapping yields `TAP_AUTPACKAGE`, with special cases and missing keys**  
  `TapConfig.kt`  
  An inconsistent configuration surface.  
  **Fix:** A table-driven camelCase-to-SNAKE_CASE mapping and one set of defaults.
- **J-9** · Low · design — **Attaches on every test, with no class-level reuse**  
  `TapExtension`  
  Slow suites.  
  **Fix:** Opt-in `PER_CLASS` reuse with an app reset.

### clients/python

Clean and idiomatic, but it fails to import on its declared minimum Python version, plain `connect()` has no liveness, it has no unit tests, and it mirrors every Kotlin semantic bug.

- **PY-1** · High · bug · ✔ — **`from typing import Self` with `requires-python >=3.10`**  
  `pyproject.toml:10 vs tap/device.py:9, server.py:22`  
  `import tap` raises ImportError on 3.10.  
  **Fix:** `>=3.11`, or `typing_extensions.Self`.
- **PY-2** · High · bug — **The connection is not observed unless used in `with` or `observe()` is called**  
  `server.py:212 connect()`  
  No liveness, so combined with DM-3 devices leak.  
  **Fix:** Observe inside `connect()` on a background thread.
- **PY-3** · Medium · robustness — **`next(stream)` has no timeout, `_events` is unbounded, and a dropped stream does not invalidate the connection**  
  `server.py:236-247`  
  It can hang forever.  
  **Fix:** A first-event deadline, a bounded `deque`, and invalidation when the stream ends.
- **PY-4** · Medium · bug — **String matching, `from None` drops the cause, and `__exit__` suppresses `TapError`**  
  `server.py _map_rpc_error, __exit__:298`  
  Errors are hidden.  
  **Fix:** ErrorInfo, `from err`, and never return True from `__exit__`.
- **PY-5** · High · bug — **Every wait error becomes `WaitTimeoutError`**  
  `device.py:200,235,291`  
  See X-6.  
  **Fix:** Map only `WAIT_TIMEOUT`.
- **PY-6** · Low · bug — **`timeout or default` turns 0 into the default, and `_detached` is set before the RPC**  
  `device.py timeouts, detach`  
  Wrong local state when the call fails.  
  **Fix:** Use `is None` checks, and set the flag after success.
- **PY-7** · High · bug — **The same scope and pick dropping as K-5/K-6, and `hash(selector)` raises `TypeError`**  
  `selectors.py`  
  Wrong-element targeting.  
  **Fix:** The same rules as Kotlin, and `__hash__` over the deterministic serialization.
- **PY-8** · Medium · perf — **`zip(roles, available, strict=False)` always picks serials[0] and silently under-assigns**  
  `pytest_plugin.py:192`  
  Same as J-4.  
  **Fix:** Rotate and retry, and fail clearly when there are fewer devices than roles.
- **PY-9** · Low · design — **`request.node._tap_state`, no artifact time bound, bare `RuntimeError`**  
  `pytest_plugin.py`  
  Non-idiomatic, and quarantine gets no typed error.  
  **Fix:** `pytest.StashKey`, a bound, and `DeviceQuarantinedError`.
- **PY-10** · Low · tooling — **`--check` misses stale extra files and only warns on version skew**  
  `scripts/gen_stubs.py`  
  The deleted `connection_pb2*` files would have slipped through.  
  **Fix:** Compare directory listings and fail on skew.
- **PY-11** · Medium · test · ✔ — **Device integration tests only**  
  `clients/python/tests`  
  No offline coverage of selectors, error mapping, config or assignment.  
  **Fix:** A `tests/unit` directory with an in-process fake gRPC server.
- **PY-12** · Nit · design — **Network I/O in the constructor**  
  `TapServer.__init__`  
  Surprising.  
  **Fix:** A factory or lazy probe.

### samples/fixture-tests

Useful real-flow coverage. The guard test and the parallel configuration mask problems rather than prove behaviour.

- **S-1** · Medium · test · ✔ — **A band-aid for J-3**  
  `TestDiscoveryTest`  
  It asserts discovery instead of fixing the cause.  
  **Fix:** Remove it after J-3.
- **S-2** · Low · test — **`catch (AssertionError)` also catches its own `fail()`**  
  `MultiDeviceTest`  
  The control flow is misleading.  
  **Fix:** `assertThrows`.
- **S-3** · Low · test — **Parallel classes are serialized by J-4**  
  `build.gradle.kts parallel config`  
  The config claims parallelism that does not happen.  
  **Fix:** Fix J-4, then assert that distinct serials were used.

## Redesign proposals

### Collapse the two schemas

```text
contracts/
  wire/proto/tap/wire/v1/*.proto   # TAP1 payloads (internal): Command, Selector, Result, ErrorCode
  api/proto/tap/v1/*.proto         # public: imports wire types it re-exposes, adds connection/device/app
device/driver  -> protobuf-javalite for wire/*
host/core      -> protobuf-java for wire/*
host/daemon    -> forwards tap.wire.v1.Command; no Conversions.kt, no EnumMirrorTest
```

### host/daemon

```text
daemon/core/   ConnectionRegistry  DeviceRegistry  DeviceLifecycle
               DriverProvisioner   DaemonDeps      Defaults  Errors
daemon/grpc/   ClientConnectionService DeviceService AppService
               StatusMapping (domain exc -> Status + ErrorInfo; else INTERNAL)
               AuthInterceptor
daemon/cli/    Main  Start  Stop (Info+token -> Shutdown RPC)  Serve
               DaemonDescriptor (atomic, 0600, delete-if-ours)  DaemonLock
TapDaemon      ~150-line facade composing the registries
```

### host/core

```text
Adb (643)        -> ProcessRunner | AdbAdmission (per-serial) | AdbDevice(serial) typed cmds
DriverClient     -> shared Handshake (contracts) | DriverClient (send/execute/cancel) | Heartbeat
DeviceSession    -> SessionOpener (OpenStep list + rollback stack) | OperationLease | SessionCloser
DriverLifecycle  -> InstrumentationLauncher (escaped args, secret file) | JournalRecovery | DriverConnector
test hooks       -> constructor-injected observer interfaces; ValidationTransport opt-in
```

### device/driver

```text
device/driver/core (Android library, lint + unit tests)
  GestureCommands   TextInputCommands (+TextVerifier)   WaitCommands
  ScreenStability   ScrollUntil (explicit pre/post gate) ArtifactCommands
  NativeSelectorBuilder   TraversalEvaluator (AccessibilityNodeInfo)   CompiledSelectorCache
  FaultHooks interface (no-op in product; impl in 'validation' flavor)
device/driver/androidTest -> thin TapDriverServer entry point
```

### Kotlin SDK + JUnit

```text
sdk/discovery/  DaemonDiscovery  TapDaemonProcess
sdk/            TapClient (~100, use {})  TapConnection (sealed State, SharedFlow)
sdk/errors/     StatusMapper (ErrorInfo)
sdk/model/      SDK-owned DeviceInfo, ElementSnapshot, Direction, AppProcess
sdk/device/     Device  DeviceRpc (single rpcExecute)  Waits  Diagnostics  Admission
sdk/selector/   Selector  Composition (validated)  Render
junit5/         TapExtension  ConnectionMemo (~40)  RoleAssignment  ArtifactCollector  TapConfig
                tapTest(): Unit
```

### Python client

```text
tap_e2e/discovery.py   daemon.json / TAP_SERVER, no I/O in constructors
tap_e2e/daemon.py      TapDaemon: channel + stubs, connect()
tap_e2e/connection.py  auto-observe thread, bounded deque, invalidation
tap_e2e/_rpc.py        single execute helper, WAIT_TIMEOUT-only waits
tap_e2e/errors.py      ErrorInfo-based, `from err`
tap_e2e/selectors.py   Kotlin-identical composition rules, hashable
tap_e2e/pytest_plugin.py  item.stash, rotation, artifact bound
```

### host/validation

```text
host/core/src/deviceTest/   (JUnit 5, tagged, per-scenario setup)
  RecoveryTest  FencingTest  TransportFaultTest  HeartbeatTest
  CancelAfterMutationTest  @Tag("reboot") LateMutationQuarantineTest
host/validation/ProductProbeMain.kt   separate entry point
```

## What is genuinely good

- The mutation gate under a lock, CAS-terminal responses, and watchdog poisoning that picks INDETERMINATE vs DRIVER_UNHEALTHY from `mutationStarted`, all testable on the JVM.
- Host `PendingCommand` transmission states (never-sent / maybe-sent / accepted) make "never replay a mutation" enforceable.
- A domain-separated, length-prefixed HMAC transcript with 32-byte nonces and constant-time comparison.
- A sealed Command/Result model with exhaustive dispatch: adding an op fails to compile until it is handled.
- Bounded selector ASTs and an explicit NATIVE/TRAVERSAL plan. There is no XPath or dump on the hot path.
- The daemon registry: exactly-once teardown, orphan-attach cleanup, and sorted-serial lock ordering, all with real interleaving tests.
- Cancellation becomes a protocol CANCEL, never a replay, across the gRPC hop.
- Every ADB call is serial-specific, children are reaped and proven dead, and the journal uses atomic write plus fsync.
- Layering holds: clients depend only on `:contracts:api`, and nothing in `host/` references `clients/`.
- `res()` is AUT-relative, and the Kotlin and Python DSLs read almost identically.

## To confirm on a device

- DR-5: does `UiObject2.getAccessibilityNodeInfo()` call `waitForIdle` on every access at the pinned AndroidX commit?
- DR-12 / DR-13: is a real (non-fixture) AUT's sync provider visible under targetSdk 36, and is the signature permission granted when the driver is installed first?
- DR-4: does password masking break `setText` / `typeText` verification?
- X-9: does the PyPI `tap.py` package install a top-level `tap` module in current releases?
- P-12, DM-12, J-5, K-15, DR-23: concurrency and stack claims that were reasoned through but not reproduced.

## Method

Read-only review of the working tree on 2026-09-26, including the uncommitted service→daemon rename. Two review agents each read one half of the code in full: (1) protocol, driver, sync-sdk, host/core and fixture; (2) daemon, API, Kotlin and Python clients, and samples. The lead covered build logic, validation and cross-cutting architecture, and re-checked the most severe claims against the source; those are marked **confirmed**. JVM unit suites for protocol, host/core and command-engine pass. `:host:daemon:compileKotlin` fails. CI, release and documentation were excluded at the owner's request. No devices were used.
