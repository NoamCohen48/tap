# Custom Android E2E Framework - Technical Design and Implementation Plan

Date: 2026-09-14

Status: approved by independent architecture review after revision 3.

This is the normative implementation document. Where it conflicts with phase definitions,
estimates, or implementation details in the design notes or feasibility report, this plan
takes precedence. The design notes preserve decision history; the feasibility report
preserves research evidence.

Related documents:

- [`android-e2e-framework-design.md`](android-e2e-framework-design.md)
- [`android-e2e-framework-feasibility.md`](android-e2e-framework-feasibility.md)
- [`upstream-reference-audit.md`](upstream-reference-audit.md)
- [`nextplayer-demo-validation.md`](nextplayer-demo-validation.md)
- [`protocol-contract.md`](protocol-contract.md)
- [`phase-1-progress.md`](phase-1-progress.md)

## 1. Scope and decisions

Build a host-driven Android E2E framework with Kotlin/JVM as the only initial client and
test DSL. Use JUnit 5 as the test runner. Support interleaved multi-device tests as a core
capability, not an extension.

The implementation includes:

- a dedicated Android driver APK and instrumentation APK;
- a versioned RPC protocol over serial-specific ADB forwarding;
- device-side UiAutomator selector evaluation and actions;
- a Kotlin host SDK, DSL, device pool, and JUnit 5 extension;
- an optional app-owned synchronization SDK;
- failure artifacts, JSON event output, JUnit XML, an HTML report, and a Flowdeck adapter;
- an optional inspector after the execution path is stable; and
- a separately scoped ChromeDriver-backed WebView module only when product tests require
  it.

Decisions:

| Area | Decision |
|---|---|
| Initial host language | Kotlin/JVM only |
| Test runner | JUnit 5 extension; do not build a custom test engine |
| Device process | Dedicated driver package targeted by a separate instrumentation APK |
| Transport | Length-prefixed JSON control messages over an authenticated loopback socket and ADB forward |
| Device concurrency | One serialized UiAutomator command lane per device |
| Cross-device concurrency | Kotlin structured concurrency on the host |
| Elements | Immutable lazy selectors; no persistent device handles |
| Waits | Immediate lookup by default; explicit condition waits are first-class and concise |
| AUT lifecycle | Host-owned, typed, serial-specific ADB operations |
| Selector hot path | Device-side matching; no hierarchy dump or XPath |
| Compose | Accessibility projection with raw test-tag resource names |
| WebView | Separate DOM backend, deferred |
| Inspector | On-demand diagnostic path, deferred until requested |
| Minimum Android API | Decide from product/device data during Phase 0; API 26 is the provisional baseline |

## 2. Goals and non-goals

### Goals

- Express ordinary and interleaved multi-device tests as readable Kotlin.
- Keep AUT lifecycle independent from the driver lifecycle.
- Give every operation a finite deadline and actionable failure.
- Never silently replay an action whose outcome is uncertain.
- Make failure artifacts sufficient for CI diagnosis without an immediate rerun.
- Keep the device server small and replaceable; make the protocol the durable contract.
- Integrate with Flowdeck's result shape without merging the products.

### Non-goals

- No test logic, branching, or multi-device coordination on the device.
- No instrumentation targeting the AUT.
- No XPath or hierarchy dumps in normal selector execution.
- No persistent `UiObject2` or accessibility-node handles.
- No arbitrary shell-command RPC.
- No automatic replay of taps, gestures, text input, or lifecycle commands.
- No universal abstraction pretending native UI and the WebView DOM are identical.
- No access to Compose's in-process testing semantics tree.
- No synchronization provider in production application variants.
- No Python or TypeScript client until the Kotlin API and protocol are proven.
- No iOS, OCR, image matching, or custom CDP implementation in the initial scope.

## 3. System architecture

```text
Kotlin/JVM test process
  +-- JUnit 5 extension
  +-- Kotlin SDK and selector DSL
  +-- device pool and multi-device orchestration
  +-- ADB/process/session supervision
  +-- artifacts and reports
          |
          | authenticated framed RPC over adb forward
          v
e2e-driver.apk + e2e-driver-test.apk
  +-- persistent instrumentation process
  +-- command server
  +-- selector compiler/evaluator
  +-- serialized UiAutomator command executor
          |
          | accessibility and input
          v
application-under-test.apk
```

Runtime packages:

```text
com.company.tap.driver
com.company.tap.driver.test
com.company.product
```

The instrumentation declaration targets `com.company.tap.driver`. The driver and AUT must
not share a package, UID, or process. Android Test Orchestrator is not used because its
process isolation conflicts with the persistent command server.

### Upstream source reuse

Appium's UiAutomator2 driver/server and Maestro are approved reference implementations.
Both repositories were verified as Apache License 2.0 on 2026-09-15, which permits copying
and modification subject to its notice, attribution, license-distribution, and modified-file
requirements.

Reuse policy:

- Prefer understanding and adapting a focused implementation over copying large subsystems.
- Use Appium primarily for proven Android/UiAutomator edge-case handling, input behavior,
  permission dialogs, screenshots, server lifecycle, and compatibility workarounds.
- Use Maestro primarily for Kotlin host ergonomics, condition waits, artifact/reporting
  patterns, selector usability, and process supervision.
- Do not copy Appium's WebDriver/HTTP surface, XPath hot path, persistent element-handle
  semantics, or broad multi-language compatibility layers.
- Do not copy Maestro's YAML flow model or single-flow execution constraints.
- Pin every examined/copied source to an immutable upstream commit, never only a branch.
- Record copied or substantially adapted code in `THIRD_PARTY_NOTICES.md` with upstream
  repository, commit SHA, source path, destination path, license, copyright header, and a
  short modification summary.
- Preserve applicable upstream copyright, patent, trademark, and attribution notices.
- Mark modified copied files prominently as changed and include the Apache 2.0 license in
  distributed source/object forms as required.
- Recheck the exact file's history and license before copying; repository-level licensing
  does not excuse importing files carrying incompatible additional notices.
- Keep independently written protocol/session/security code separate from borrowed utility
  code so provenance and future replacement remain clear.

Reference repositories:

- [Appium UiAutomator2 server](https://github.com/appium/appium-uiautomator2-server)
- [Appium UiAutomator2 driver](https://github.com/appium/appium-uiautomator2-driver)
- [Maestro](https://github.com/mobile-dev-inc/Maestro)

The evidence-linked adopt/adapt/do-not-copy decisions are recorded in
[`upstream-reference-audit.md`](upstream-reference-audit.md). Those decisions preserve Tap's
authenticated generations, durable ownership journals, process identity checks, and no-replay
transport semantics rather than treating either upstream architecture as a drop-in design.

This policy is an engineering record, not a substitute for the organization's legal review
before external distribution.

## 4. Repository and modules

Use one Gradle repository with narrow modules:

```text
build-logic/
protocol/
  model/
  codec/
  compatibility-tests/
driver/
  app/
  instrumentation/
  server/
  selector-engine/
  command-engine/
host/
  adb/
  transport/
  sdk/
  orchestration/
  junit5/
  artifacts/
  report-html/
synchronization/
  api/
  provider/
inspector/
  host-api/
  desktop-ui/                 # deferred
webview/                      # deferred
  chromedriver/
samples/
  views-aut/
  compose-aut/
  framework-tests/
test-fixtures/
  fake-adb/
  fake-driver/
  protocol-golden/
benchmarks/
```

Module boundaries:

- `protocol:model` contains pure Kotlin wire DTOs with no Android or coroutine runtime
  types. Wire DTOs remain separate from the public DSL types.
- `protocol:codec` owns framing, serialization, limits, authentication, and checksums.
- `driver:*` owns Android-specific execution. AndroidX types never cross its public
  boundary.
- `host:adb` is the only module allowed to start `adb` processes.
- `host:transport` knows the protocol but not JUnit or the public selector DSL.
- `host:sdk` exposes `Device`, `App`, `Element`, selectors, waits, and exceptions.
- `host:orchestration` owns device leases, roles, barriers, and concurrent fan-out.
- `host:junit5` adapts JUnit lifecycle to the SDK; it does not implement discovery.
- `synchronization:*` is optional application code included only in E2E/debug variants.

## 5. Ownership and concurrency invariants

- Exactly one host `DeviceSession` owns a device lease.
- Every ADB operation includes the exact device serial with `-s`.
- One device-side executor serializes all UiAutomator operations for that device.
- Commands for different devices may run concurrently.
- Host code never holds one device's command lock while waiting for another device.
- A device lease is keyed by ADB serial plus observed boot identity, not by forwarded port.
- Instrumentation process, authentication secret, ADB forward, socket, pending commands,
  logs, and session generation form one disposable session unit.
- Reconnection creates a new generation. Old commands never cross the generation boundary.

`sessionGeneration` is an unsigned 64-bit monotonic value allocated by the host per device
lease and persisted in the durable serial journal before driver launch. The driver receives
and echoes it but cannot choose it. Journal loss requires a fresh session UUID and verified
old-driver death; generation is routing defense within that session/lease, while the random
session UUID prevents identity reuse across journals.

## 6. Driver startup and session lifecycle

### Host startup sequence

1. Acquire an exclusive filesystem/device-pool lease for the serial.
2. Read device state, boot identity, Android API, user/profile, and ADB version.
3. Install or update the driver APKs when their build IDs differ.
4. Read the durable host session journal for this serial. Remove only exact forwarding rules
   recorded as framework-owned, then package-force-stop any prior driver and verify its old
   PID is dead. A missing/corrupt journal never permits global forward cleanup.
5. Generate a session UUID and 256-bit random secret.
6. Allocate and durably persist the next `sessionGeneration`, select a device-loopback port
   from the configured framework range, and start
   `adb -s SERIAL shell am instrument -w -r` as a supervised child process.
7. Pass the session ID, session generation, secret, and selected port as instrumentation
   arguments.
8. Drain instrumentation stdout and stderr continuously to artifacts and require a bounded
   bind/readiness signal containing the new session ID, session generation, and driver
   instance ID.
9. If binding fails because the port remains occupied after verified driver death, retry a
   bounded number of fresh ports. Exhaustion quarantines the device.
10. Before creating the forward, durably write a `CREATING` journal record containing
    serial, boot identity, session ID/generation, and selected device port. Create
    `adb -s SERIAL forward tcp:0 tcp:DEVICE_PORT`, add the returned host port, and atomically
    transition the record to `ACTIVE`.
11. Connect, authenticate, negotiate protocol/capabilities, and verify the exact new driver
    and session identities. Authentication can never adopt an old listener.
12. Mark the session `READY` only after a health request succeeds.

The server binds only to `127.0.0.1`. It permits one authenticated controlling connection.
It exits after a bounded lease/heartbeat timeout if the host disappears.

### Host session state machine

```text
NEW -> PREPARING -> STARTING_DRIVER -> FORWARDING -> AUTHENTICATING -> READY
 |         |              |               |              |             |  |
 +---------+--------------+---------------+--------------+-------------+  +-> DRAINING
                                                                        +----> BROKEN
DRAINING -> CLEANING -> CLOSED
BROKEN   -> CLEANING -> CLOSED | QUARANTINED
```

Failure or lease/boot-identity change in any non-terminal state enters `BROKEN` and then
`CLEANING`. `DRAINING` and `BROKEN` reject new requests, fail queued work, and give active
work a bounded cancellation grace period. A watchdog independent of the UI command executor
then self-terminates the driver process if work has not ended.

The device watchdog self-terminates its process after the grace period. Host cleanup then
performs package-scoped force-stop and verifies that the old PID and driver-instance
identity are gone. The first implementation rebuilds a broken session rather than attempting
in-place socket resurrection. A replacement cannot become `READY` until the old instrumentation PID is
confirmed dead, its socket is closed, and its exact forwarding rule is removed. If any of
those postconditions cannot be proved, the device becomes `QUARANTINED` and is not returned
to the pool. The test receives the original command failure. Recovery prepares the device
for a subsequent command or test; it never hides an uncertain result.

A mutating request has internal phases `QUEUED`, `ACCEPTED`, `PRE_MUTATION`,
`MUTATION_STARTED`, and `TERMINAL`. Loss after `ACCEPTED` is reported `INDETERMINATE` unless
the driver returns a definitive terminal response or proves that mutation was never
started. This conservative rule permits a false-uncertain result but never a false claim
that input did not occur.

### Cleanup

Cleanup runs in a non-cancellable host context with its own finite deadline:

1. Fail queued requests and request cancellation of active work.
2. Request driver shutdown when the connection is healthy.
3. Close the socket.
4. Remove only the forwarding rule created by this session.
5. Terminate the supervised instrumentation child and package-scoped driver process if
   still alive, then verify the old PID no longer exists.
6. Stop and finalize logcat/video children.
7. Flush artifact manifests and mark journal entries closed through atomic replacement.
8. Release the device lease only after all required cleanup postconditions pass; otherwise
   quarantine it.

Never use `adb forward --remove-all` or global process termination. On host crash, the next
startup reconciles `CREATING` and `ACTIVE` journal records against `adb forward --list`.
After acquiring the serial lease, matching boot identity, and proving old driver death, it
removes exact serial/device-port rules for a `CREATING` record and exact host-port rules for
an `ACTIVE` record. It then force-stops the driver package and verifies death before
selecting a new port. The configured device-port range is reserved exclusively for this
framework on leased devices; an unowned forward outside a matching journal/range is never
removed.

## 7. ADB control plane

Expose typed operations instead of shell strings:

```kotlin
interface Adb {
    suspend fun devices(): List<AdbDevice>
    suspend fun install(serial: String, apk: Path, replace: Boolean = true)
    suspend fun uninstall(serial: String, packageName: String)
    suspend fun forwardDynamic(serial: String, devicePort: Int): Forwarding
    suspend fun removeForward(serial: String, hostPort: Int)
    suspend fun forceStop(serial: String, packageName: String)
    suspend fun clearData(serial: String, packageName: String)
    suspend fun startActivity(serial: String, request: StartActivityRequest)
    suspend fun grantPermission(serial: String, packageName: String, permission: String)
}
```

Implementation rules:

- Invoke `ProcessBuilder` with an argument list; never concatenate a shell command.
- Give every subprocess a deadline and kill its descendants on timeout.
- Capture stdout and stderr separately and redact instrumentation secrets.
- Distinguish missing, offline, unauthorized, and changed-boot devices.
- Use `adb track-devices` for notifications plus periodic reconciliation.
- Validate destructive package targets against the AUT allowlist and reject the driver
  packages.
- Verify exit status and operation postconditions; successful ADB exit alone is not enough.

## 8. Wire protocol

### Framing

Use a fixed big-endian header followed by a bounded payload:

```text
magic       4 bytes  "TAP1"
framing     1 byte   bootstrap framing version, initially 1
frameType   1 byte
flags       2 bytes  zero in v1
requestId   8 bytes
length      4 bytes
payload     length bytes
```

Frame types:

```text
HELLO CHALLENGE AUTH AUTH_RESULT REQUEST RESPONSE CANCEL EVENT
BLOB_START BLOB_CHUNK BLOB_END PING PONG CLOSE
```

The framing version is independent of the negotiated application-protocol version and is
fixed for every handshake and authenticated frame on the connection. An unsupported
framing version or nonzero/unknown v1 flag closes the connection before JSON decoding.
Future mandatory flags require a new framing version. The header `requestId` is the
sole request identifier; request/response JSON does not repeat
it. Connection-level frames use request ID zero. Control payloads are canonical UTF-8 JSON
encoded with `kotlinx.serialization`. Binary screenshots and inspector snapshots use
bounded blob frames rather than Base64. Initial limits:

- control payload: 1 MiB;
- blob chunk: 256 KiB;
- artifact: 64 MiB, configurable;
- selector depth: 32;
- selector nodes: 256;
- queued requests: bounded and reported as a structured overload error.

Malformed, unauthenticated, deeply nested, or oversized input closes the connection after
a diagnostic event where safe. Duplicate JSON object keys are malformed.

### Authentication

All nonces are 32 random bytes represented as unpadded Base64URL. Handshake JSON uses
lexicographically sorted object keys, UTF-8, no insignificant whitespace, decimal integers,
and no floats. Authentication uses HMAC-SHA-256 keyed by the raw 32-byte session secret.
Each HMAC output is exactly 32 bytes encoded in JSON as unpadded Base64URL.

1. Host sends `HELLO { sessionId, hostNonce, hostBuildId, supportedVersions[] }`.
2. Driver verifies that the instrumentation-provided session ID matches and sends
   `CHALLENGE { sessionId, hostNonce, driverNonce, driverInstanceId, sessionGeneration,
   driverBuildIds, supportedVersions[], capabilities[] }`.
3. The host selects the highest common major/minor and the enabled subset of offered
   capabilities. It constructs canonical
   `NEGOTIATION { selectedVersion, enabledCapabilities[] }`. Absence of a common version
   closes the connection without accepting normal frames.
4. The authenticated transcript is the exact byte sequence
   `u32be(len(HELLO)) || HELLO || u32be(len(CHALLENGE)) || CHALLENGE ||
   u32be(len(NEGOTIATION)) || NEGOTIATION`, using canonical payload bytes.
5. Host sends `AUTH { negotiation, transcriptHmac }`, where the HMAC input is the ASCII
   bytes `TAP1-HOST-AUTH`, one zero byte, then the transcript.
6. Driver verifies selection is exactly the highest common version and capabilities are an
   offered subset, then sends `AUTH_RESULT { selectedVersion, enabledCapabilities,
   transcriptHmac }`. Its HMAC input is ASCII `TAP1-DRIVER-AUTH`, one zero byte, then the
   same transcript. Its selected version and enabled capabilities must exactly equal the
   authenticated `NEGOTIATION` record.

Any nonce, session, selected-version, or transcript mismatch fails authentication. Neither
side may select a lower version than the highest common version, preventing silent
downgrade. The challenge generation must equal the instrumentation startup argument, and
every request must carry that exact authenticated value. Authentication has a finite
deadline and only one attempt per connection.

Use one random secret per driver launch. Never persist or log it. TLS is not required in v1
because transport is loopback plus the local ADB tunnel, but authentication is required to
exclude other local device or host processes.

### Versioning

- Major incompatibility fails the handshake.
- Negotiate the highest mutually supported minor version.
- Additive behavior is capability-gated; it is not inferred only from a version number.
- Unknown optional fields are ignored.
- Unknown operations, operation versions, and enum values return `UNSUPPORTED`.
- Existing field meaning never changes within a major version.
- Golden fixtures cover every supported minor version.

The handshake reports Android API, UiAutomator version, host build, both APK build IDs,
supported operations, and optional features.

### Connection protocol state machine

```text
CONNECTED -> HELLO_RECEIVED -> CHALLENGE_SENT -> AUTHENTICATED -> CLOSING -> CLOSED
     |              |                |                 |
     +--------------+----------------+-----------------+-> FAILED -> CLOSED
```

Before `AUTHENTICATED`, only the next expected handshake frame or `CLOSE` is legal. After
authentication, `REQUEST`, `CANCEL`, `PING`, `PONG`, blob frames associated with an active
request, and `CLOSE` are legal. A frame with an illegal type/state, nonzero connection-frame
request ID, zero request-frame ID, unsupported version, or invalid ordering fails the
connection. Exactly one terminal `RESPONSE` is emitted for every accepted request unless
the transport is already lost.

### Request contract

Every request payload contains:

```text
sessionId
sessionGeneration
operation
operationVersion
timeoutMs
targetScope
expectedAut { packageName, userId, packageVersionCode?, processStartUuid? }?
arguments
```

`timeoutMs` is a duration interpreted against `SystemClock.elapsedRealtime()` when the
driver accepts and enqueues the request. Queue residence consumes the same deadline; work
that reaches the executor after expiry returns `DEADLINE_EXCEEDED` without running. Host
and device wall-clock skew is irrelevant.

Actions default to exactly-one cardinality. Before injecting input, the driver revalidates
the selected node's package/window constraints to reduce overlay races.

### Request identity and uncertain outcomes

V1 prohibits request retransmission. Request IDs are monotonically increasing unsigned
64-bit integers within a session generation. The driver stores the highest accepted ID;
any ID at or below that watermark returns `DUPLICATE_OR_STALE` and is never executed,
whether or not an older response remains available. A session closes before request-ID
overflow and has a configurable maximum command count. A new connection after transport
loss always uses a new session generation and rejects all old-generation requests.
The host allocates each request ID and writes its complete frame under the same per-session
transport mutex, so concurrent coroutines cannot put a lower ID on the socket after a
higher one.

If transport is lost after a mutating request is accepted, report `INDETERMINATE` unless
the framework has a definitive terminal response or proof that mutation did not start.
Queries may be retried only after the caller or an explicit higher-level policy decides.

The host tracks transmission as `NOT_WRITTEN`, `WRITING`, `WRITTEN`, and
`TERMINAL_RESPONSE`. Cancellation in `NOT_WRITTEN` is local and safe. Once any bytes of a
mutating request may have reached the socket, write timeout, partial write, connection loss,
or local cancellation without an ordered definitive driver response produces
`INDETERMINATE`. A partially written frame poisons and closes the connection. `CANCELLED`
for a transmitted mutation is allowed only when the driver's terminal response proves that
mutation never started.

### Blob transfer

`BLOB_START` uses the owning request ID and a canonical JSON payload
`{ blobId, mediaType, totalLength, sha256 }`. `blobId` is a UUID encoded as 16 raw bytes in
each `BLOB_CHUNK`; it is followed by a 4-byte unsigned big-endian chunk index and then the
remaining payload bytes. Exactly one blob may be active per request and at most one blob is
transferred on the connection at a time in v1. Artifact blobs flow driver-to-host, begin
after request acceptance, and complete before the request's terminal `RESPONSE`. Chunks are
contiguous and ordered.
`BLOB_END` repeats `blobId`, byte count, and SHA-256. Length/checksum mismatch, interruption,
or an unexpected chunk aborts that blob and terminates the request with
`ARTIFACT_TRANSFER_FAILED`;
it does not replace an already established primary test failure. `CANCEL` aborts an active
blob at the next chunk boundary.

### Cancellation and terminal result selection

| Cancellation point | Result |
|---|---|
| Before any request bytes are written | No accepted request; host-side cancellation |
| Bytes written but acceptance unknown | `INDETERMINATE` for mutation; poison connection on partial frame |
| Accepted but still queued | Remove from queue and return `CANCELLED` |
| Running before mutation | Stop cooperatively and return `CANCELLED` |
| Mutation started | Return definitive action result if known, otherwise `INDETERMINATE` |
| Request already terminal | Ignore `CANCEL`; never send a second response |

`WAIT_TIMEOUT` means the requested condition remained false until its operation-specific
timeout. `DEADLINE_EXCEEDED` means the overall request deadline expired outside a normal
condition result. `CANCELLED` requires proof that mutation did not start. `TRANSPORT_LOST`
describes a query/session transport failure with no mutation risk. `INDETERMINATE` is used
whenever mutation may have occurred without a definitive response.

The socket reader and watchdog own heartbeat state independently of the UI executor. If
heartbeats expire while the executor is blocked, the watchdog marks the session poisoned,
closes the listener, and invokes `Process.killProcess(Process.myPid())` after the bounded
grace period. Host-side cleanup then package-force-stops and verifies driver death.

### Error taxonomy

```text
INVALID_REQUEST INVALID_SELECTOR UNSUPPORTED UNAUTHENTICATED
SESSION_MISMATCH DUPLICATE_OR_STALE AUT_MISMATCH NOT_FOUND AMBIGUOUS NOT_INTERACTABLE
ACTION_REJECTED WAIT_TIMEOUT CANCELLED DEADLINE_EXCEEDED
STALE_DURING_COMMAND AUT_NOT_INSTALLED AUT_CRASHED AUT_ANR
SYNC_PROVIDER_UNAVAILABLE DRIVER_UNHEALTHY TRANSPORT_LOST INDETERMINATE
ARTIFACT_TRANSFER_FAILED INTERNAL
```

Host exceptions retain the code, device role, serial, request ID, session generation,
selector rendering, timeout, retryability, and redacted remote details.

## 9. Command execution and cancellation

The driver has three logical lanes:

```text
socket reader -> bounded request queue -> single UI command executor -> socket writer
```

The reader continues to process `CANCEL`, `PING`, and connection closure while a command is
running. The command executor never runs on Android's main thread.

Cancellation/deadline checks occur:

- before selector compilation and resolution;
- between every wait poll and scroll attempt;
- before input injection;
- after a blocking UiAutomator call returns; and
- between artifact chunks.

Some platform calls cannot be interrupted. If one exceeds its deadline, the session becomes
`DRIVER_UNHEALTHY`; queued work fails and the host rebuilds the session. If input may have
been injected, the result is `INDETERMINATE`. Killing instrumentation does not by itself
prove that already-issued Binder or input-system work has quiesced. Phase 0 must establish
a bounded reset/quiescence procedure on supported devices. Until a weaker procedure is
proven, an uninterruptible timed-out mutating operation quarantines the device and requires
the configured reset, defaulting to reboot, boot-identity change, and AUT-state reset, before
reuse. A timed-out late action must never run during the next test.

No command has an infinite timeout. Startup, normal commands, waits, socket operations,
artifacts, cancellation, and teardown have separate bounded defaults.

## 10. Selector model

Do not expose AndroidX classes on the wire or in the public API. Define a versioned selector
AST with:

- string properties: text, content description, hint, class, package;
- resource identity: `AndroidId(package, id)` or `RawName(value)`;
- boolean properties: enabled, checked, checkable, clickable, focused, focusable,
  long-clickable, scrollable, selected;
- relations: parent, ancestor, child, descendant;
- scope: package, foreground, display, window type, and bounds where supported; and
- string matching: exact, contains, starts-with, ends-with, and safe regex.

Exact, contains, starts-with, and ends-with matching are case-sensitive Unicode code-point
operations in v1. Regex is full-string matching using RE2/J's supported grammar and
Unicode behavior. Unsupported constructs such as backreferences and lookbehind are rejected.
User regex is never passed to an AndroidX overload backed by Java `Pattern`; it uses the
fallback evaluator. Raw resource names are literal values. If an AndroidX raw-resource API
accepts a regex internally, the driver applies `Pattern.quote` before passing the value.

Do not include sibling, nearest, OR, NOT, or arbitrary nth-match in the initial protocol.
Add them only for demonstrated test cases. Each addition receives an operation/selector
version and deterministic semantics.

### Cardinality and order

- Actions require exactly one match by default.
- `first()` and `at(index)` are explicit opt-ins.
- Preferred deterministic result order is display ID, descending window Z-order, then
  accessibility traversal order. If an API level cannot expose display or layer data, the
  capability handshake reports the reduced ordering and `first()`/`at(index)` are rejected
  unless the caller explicitly accepts `ACCESSIBILITY_ROOT_ORDER` for that request.
- Exactly-one lookup may stop after a second match to report ambiguity.

### Query plans

The selector engine compiles each AST into one of two internal plans:

- `NativeByPlan` for combinations directly supported by current `BySelector` APIs.
- `AccessibilityTraversalPlan` for explicit window scoping or future relationships not
  supported by `BySelector`.

Unsupported combinations fail with `INVALID_SELECTOR`; compilation never silently weakens
a selector. Current predicate/window APIs must be evaluated during Phase 0 before this
boundary is finalized. Built-in UiAutomator wait defaults are explicitly disabled or
overridden so protocol timing remains authoritative.

`UiObject2` and `AccessibilityNodeInfo` live only inside one command. Before mutation begins,
a compound command may re-resolve once after staleness. After mutation begins, it never
retries automatically.

### Presence, visibility, and interactability

- `present`: at least one matching node exists in the selected accessibility roots.
- `visible`: present, marked visible-to-user where the API provides that state, with
  non-empty bounds intersecting the selected display's visible region. This is an
  accessibility/geometric definition, not proof that every pixel is unobscured.
- `enabled`: the accessibility node reports enabled.
- `interactable`: visible and enabled, supports the requested accessibility action or has
  non-empty tappable bounds for an input gesture, and still satisfies scope immediately
  before mutation.
- `exists`: alias for `present`, not `visible`.

Clipping is represented by visible bounds where Android supplies them. Occlusion by an
unrelated overlay is handled by package/window scope and pre-action revalidation; the
framework does not promise pixel-level occlusion detection.

### Target scope and windows

Every selector/action declares one target scope:

```text
AUT(package, user)       only roots/nodes belonging to the expected AUT
SYSTEM(allowlist)        explicitly allowlisted packages such as permission controller or IME
CROSS_PACKAGE(allowlist) explicit third-party package allowlist
```

There is no implicit cross-package fallback. `expectedAut` is required for AUT-scoped
element commands and synchronization, optional for package-agnostic device commands, and
not applied to explicitly scoped system commands. The CI configuration controls allowed
system and third-party packages.

Foreground AUT means an AUT window is focused or top-resumed on the selected display as
reported by current window/activity state. Split-screen may have multiple visible apps but
only the focused/top-resumed one satisfies foreground. Overlays, IME, permission dialogs,
and picture-in-picture are separate windows and do not silently become AUT roots. A
selector can scope a selected display where supported; unsupported display/window fields
return `UNSUPPORTED`, particularly on lower API levels, rather than degrading.

## 11. Commands

Initial native operations:

```text
session.health session.shutdown device.info
device.pressKey device.pressBack device.pressHome device.wake
element.exists element.count element.snapshot element.getProperty
element.tap element.longTap element.setText element.typeText element.clearText
element.swipe element.scroll element.scrollUntil
wait.element wait.appVisible wait.screenStable
sync.state sync.awaitIdle
artifact.screenshot inspector.snapshot
```

`setText` means accessibility `ACTION_SET_TEXT`. `typeText` means focus plus realistic key
input and reports unsupported characters rather than silently changing them.

`scrollUntil` is one compound device command:

1. Resolve exactly one scroll container.
2. Search for the target.
3. Scroll one bounded segment.
4. Detect progress or end-of-content.
5. Repeat until found, cancelled, no progress, maximum scrolls, or deadline.

The result is still a lazy host `Element`; it is resolved again by the next terminal action.

## 12. Kotlin host API

Creating an `Element` performs no I/O:

```kotlin
val play = device.element(rawRes("playButton"))
play.tap()
```

Each terminal operation resolves the selector again. Example single-device test:

```kotlin
@ExtendWith(TapExtension::class)
class PlaybackTest {
    @field:TapDevice
    lateinit var device: Device

    @Test
    fun startsPlayback() {
        tapTest {
            val app = device.app("com.company.streaming")

            app.clearData()
            app.launch()
            device.await(rawRes("homeScreen")).visible()

            device.element(
                rawRes("heroCard").hasDescendant(text("Featured"))
            ).descendant(text("Play")).tap()

            device.await(text("Now Playing")).visible()
        }
    }
}
```

`tapTest` provides a real-time structured coroutine scope; it does not use virtual test time
because ADB, devices, and command deadlines are external real-time systems.

View and Compose resources remain distinct:

```kotlin
device.element(resId("com.company.streaming", "play_button"))
device.element(rawRes("playButton"))
```

Multi-device tests bind named roles rather than relying on discovery order:

```kotlin
@TapDevices("sender", "receiver")
@Test
fun messagePropagates(devices: Devices) {
    tapTest {
        val sender = devices["sender"]
        val receiver = devices["receiver"]

        sender.element(rawRes("messageField")).setText("hello")
        sender.element(rawRes("sendButton")).tap()

        receiver.await(text("hello"), timeout = 20.seconds).visible()
    }
}
```

Use `coroutineScope`, `async`, and a framework `DeviceBarrier` only when simultaneous phases
are required. Backend propagation uses the observing device's UI condition, not a barrier.
If one child fails, sibling work is cancelled and bounded teardown begins.

## 13. Wait semantics

Immediate lookup remains the default for direct assertions and actions. Explicit waits are
shorter than sleeping:

```kotlin
device.await(text("Connected")).visible()
device.await(rawRes("spinner")).gone()
device.element(text("Continue")).await().enabled().tap()
```

Wait options:

```kotlin
data class WaitOptions(
    val timeout: Duration = 10.seconds,
    val pollInterval: Duration = 100.milliseconds,
    val stableFor: Duration = Duration.ZERO,
)
```

Built-in conditions include existence, absence, count, property equality/match, enabled,
checked, package visibility, and screen stability. Device-side waits use one RPC and poll
on the device. Arbitrary host-side `awaitUntil` is available for cross-device/backend
conditions but is less efficient.

Every timeout includes poll count, elapsed time, last observed state, and a bounded final
diagnostic snapshot. The framework does not call `UiDevice.waitForIdle()` before each
query and exposes no synchronization API implemented as a fixed sleep.

## 14. AUT lifecycle

Bind lifecycle operations to an explicit package and Android user:

```kotlin
val app = device.app("com.company.product")

app.install(apk)
app.forceStop()
app.clearData()
app.launch()
app.coldLaunch()
app.uninstall()
```

Definitions:

- `launch`: start the configured intent without first asserting prior process/task state.
- `coldLaunch`: verify force-stop, launch, then verify a new process identity and expected
  foreground package/window.
- `clearData`: clear package state, invalidate provider/process observations, and leave the
  app stopped.
- `reset`: project-configured clear/install/permission/launch sequence, never an ambiguous
  hard-coded behavior.

Install and reinstall verify package name, version code, signing identity where available,
and postcondition. `clearData` accounts for runtime permission reset. Launch supports
activity, action, data URI, extras, flags, task policy, user, and display through typed
arguments.

Crashes, ANRs, process restarts, unexpected foreground packages, and system dialogs are
distinct observations and error categories.

V1 automates only the current foreground Android user in which instrumentation was launched.
The host records that user at session startup. Cross-user UI commands are rejected.
Secondary users and managed profiles require an explicit future capability and a new driver
session launched in that user; passing an arbitrary user ID does not imply support.

Process identity is an app-generated process-start UUID from the synchronization provider
when available, otherwise `(pid, /proc start time, observed package version)`; PID alone is
never identity. If no prior process exists, cold launch records the absence and requires a
new identity after launch. The wire contract does not use an inferred `installGeneration`;
host-observed package version and signing/build identity are sent where relevant.

Lifecycle postconditions:

- `install`: package manager reports the expected package/version/signing identity.
- `forceStop`: package stopped state is observed and no prior AUT process remains.
- `clearData`: package manager reports success, prior process identity is gone, provider
  state is invalidated, and permissions/data assumptions are reset.
- `launch`: the start command succeeds and the expected AUT window/package becomes visible
  within its deadline.
- `coldLaunch`: `forceStop` postconditions pass, launch passes, and a new process identity
  plus expected foreground AUT window is observed.

Crash/ANR observation combines instrumentation-era logcat markers, activity/process state,
and current system crash/ANR windows. Each session records log start cursor/time and AUT
process identity so historical events are excluded. If multiple outcomes occur, driver or
transport loss takes infrastructure precedence; otherwise current AUT crash/ANR takes
precedence over a generic wait timeout while the original assertion remains attached as
context.

AUT-scoped element, wait, and synchronization operations require `expectedAut`. Device
health, Home/Back/key operations, and explicitly scoped system-dialog operations do not.

## 15. Compose support

Compose remains an accessibility-backed native surface. Owned AUTs enable:

```kotlin
Modifier.semantics {
    testTagsAsResourceId = true
}
```

The host uses `rawRes("tag")`; it never automatically package-qualifies Compose tags.
Compressed accessibility hierarchy mode is disabled by default.

Documented limitations:

- merged or cleared semantics can hide descendants;
- lazy items may not exist until composed by scrolling;
- clipping and accessibility pruning affect visibility;
- tag-only nodes may be omitted by compressed hierarchy mode; and
- custom drawing and video surfaces need explicit accessibility semantics or another test
  strategy.

## 16. App-owned synchronization SDK

Ship an optional E2E/debug-only SDK with a small scoped API:

```kotlin
TapSynchronization.busy(BusyCategory.NETWORK, "load-home").use {
    repository.refreshHome()
    renderedState.awaitApplied()
}
```

The AUT E2E manifest declares
`com.company.tap.permission.SYNCHRONIZATION` with protection level `signature` and contains
an exported `ContentProvider` requiring that permission. The driver declares
`uses-permission` and package visibility. Driver and AUT E2E builds must be signed by the
same approved E2E certificate; installation verifies certificate compatibility before the
provider is used. Install/update the permission-declaring AUT before starting a driver that
needs the permission, and restart/reinstall the driver after incompatible permission-owner
changes. The production manifest contains neither the provider nor WebView debugging.

Provider state includes:

```text
initialized
processId and app-generated processStartUuid
sessionIdentity
generation
counters by category
lastTransitionElapsedMs
underflow/error state
```

The AUT creates `processStartUuid` and a random `sessionIdentity` in `Application.onCreate`;
they are not supplied by the host. Identity bootstrap is explicit:

1. Complete `launch` and the app-visible postcondition.
2. Observe the running AUT's PID plus `/proc` process-start token without calling the
   provider.
3. Call the provider once.
4. Re-observe PID/start token and require it to match the pre-call observation.
5. Only then adopt the returned `processStartUuid` and `sessionIdentity`.

If the first provider call starts or replaces the process, reject the response, invalidate
the bootstrap, and require another explicit launch/visibility cycle. Subsequent calls must
match the adopted UUID/session identity and stable external PID/start token immediately
before and after the call. An unexpected process is `SYNC_PROVIDER_UNAVAILABLE`, never idle.

Every busy/idle transition increments generation. `awaitIdle` requires two or more zero
samples separated by a quiet interval with unchanged generation and process identity.
Unavailable, malformed, uninitialized, restarted, or stale provider state means unknown,
never idle.

Calling the provider can start the AUT process. `awaitIdle` therefore requires an already
launched, expected AUT process identity and must not be used as a launch probe.

Track explicit UI-affecting work. Do not count all coroutines or long-lived streams.
After `awaitIdle`, tests still wait for the actual visible UI postcondition.

Busy-token `close()` is idempotent. A counter can never become negative; attempted
underflow sets a sticky provider error, increments generation, and causes all idle queries
to fail until process restart. Clear-data, reinstall, process restart, provider exception,
malformed response, or certificate mismatch invalidates all host-cached synchronization
identity. Only a later explicit launch/visibility handshake establishes a new identity.

V1 supports the AUT's primary process only. Multi-process aggregation is deferred.

## 17. WebView boundary

When required, WebView uses a separate host API and supervised ChromeDriver process:

```kotlin
val web = app.webView()
web.locator(css = "[data-test=checkout]").click()
```

Requirements:

- enable WebView debugging only in E2E/debug builds;
- identify the device serial, application process, and WebView target explicitly;
- pin WebView in CI or resolve a compatible ChromeDriver dynamically;
- give ChromeDriver startup, commands, reconnect, and teardown finite deadlines; and
- keep DOM selectors, frames, waits, and artifacts separate from native `Element`.

Coordinate conversion between DOM and native space and a custom CDP client are not v1
features.

## 18. JUnit 5 integration and device pool

The extension uses these JUnit 5 contracts:

- `ExecutionCondition`: check static capability/configuration prerequisites that do not
  require reserving devices.
- `BeforeEachCallback`: atomically acquire all named roles with a finite acquisition
  timeout, start sessions, create the per-test coroutine root job/deadline, and register
  resources in `ExtensionContext.Store`.
- `ParameterResolver`: inject `Devices`, `Device`, configuration, and artifact context.
- `InvocationInterceptor`: run the test invocation under the per-test deadline, bind
  `tapTest` to the extension-owned coroutine context, intercept invocation failure, and
  trigger device-backed artifacts while sessions are still live. It also intercepts user
  `@BeforeEach` and `@AfterEach` lifecycle methods so their failures are recorded before
  framework cleanup.
- `AfterTestExecutionCallback`: capture device-backed timeout/failure diagnostics not
  already collected by the interceptor before any session cleanup.
- `LifecycleMethodExecutionExceptionHandler` and `TestExecutionExceptionHandler`: record
  user lifecycle/test exceptions when normal interception is bypassed by extension order,
  then rethrow the original exception unchanged.
- `TestWatcher`: record only the final JUnit outcome for run-level reporting.
- `AfterEachCallback`: inspect the context's recorded setup, user-lifecycle, and invocation
  failures; perform any still-missing device-backed capture while sessions are live; then
  cancel the test job, perform bounded non-cancellable cleanup, preserve primary failures,
  and release or quarantine leases.
- A JUnit Platform `TestExecutionListener`: own run-level JSONL/JUnit/HTML finalization and
  recovery when class-level or launcher failures prevent normal extension callbacks.

`tapTest` is mandatory for suspending framework operations in v1. It looks up the
extension-owned context; calling it without `TapExtension`, nesting it, or invoking
framework suspension from an unbound coroutine fails immediately with a usage error.
Plain synchronous tests may use metadata only but cannot access a live `Device`.

JUnit timeout/interruption cancels the root coroutine job. The interceptor then enters
bounded non-cancellable teardown. If a platform call does not stop, the session follows the
poison/quarantine rules in sections 6 and 9. Each setup callback catches its own failure,
captures diagnostics from any partially live session before cleanup, and registers each
resource in `ExtensionContext.Store` as soon as it exists. Parameter resolution, test
construction, invocation, artifact collection, and teardown failures all flow through the
run listener; secondary failures are attached without replacing the primary one.
The stored resource implements `CloseableResource` as a final fallback. If another extension
aborts callback execution and normal `AfterEachCallback` is skipped, it captures available
bounded diagnostics before cleanup or quarantine when the context store closes.

Field injection, if retained, requires `lateinit var` with Kotlin `@field:TapDevice` and a
per-method test instance. Parameter injection is preferred. `PER_CLASS` test instances may
not retain live `Device` objects between methods and are rejected if annotated fields would
do so. Atomic multi-role acquisition is cancellable, all-or-none, and bounded; cancellation
releases reservations acquired during the attempt.

The extension supports:

- role-based device requirements and exclusive leases;
- capability/API assumptions before test execution;
- suite/test session scopes;
- per-test deadlines and structured cancellation;
- documented standard JUnit `@Tag` values and optional composed annotations for smoke,
  regression, destructive, multi-device, physical-device, and WebView;
- automatic failure artifacts and cleanup;
- visible quarantine metadata with owner and expiry; and
- standard JUnit XML plus framework reports.

JUnit parallelism cannot assign one device to two tests. Device constraints include API
range, model, emulator/physical, locale, orientation, capabilities, and serial allowlist.
The pool either acquires all roles atomically or acquires none, preventing partial
multi-device deadlocks.

## 19. Artifacts, events, and reports

Write artifacts incrementally under collision-proof paths:

```text
artifacts/RUN_ID/tests/TEST_ID/ATTEMPT/ROLE-SERIAL/SESSION_GENERATION/
  screenshot-failure.png
  hierarchy-failure.json
  logcat.txt
  driver-stdout.txt
  driver-events.jsonl
  dumpsys-window.txt
  dumpsys-activity.txt
  recording-000.mp4
```

The run root also contains `run.json`, `events.jsonl`, JUnit XML, `report.html`, and an
artifact manifest with identities, versions, checksums, sizes, and collection errors.

Capture logcat before the first AUT command and retain it across AUT process changes. Record
host wall/monotonic time and device wall/elapsed time for correlation. Append and flush
JSONL events incrementally with crash-resilient record boundaries rather than retaining the
only copy in memory; publish the final manifest through an atomic rename.

On failure, independently and with separate deadlines, attempt:

1. screenshot;
2. inspector hierarchy snapshot;
3. package/window/activity state;
4. recent logcat and driver output;
5. crash/ANR evidence; and
6. recent redacted protocol events.

Artifact failure never replaces the primary test failure. Partial artifacts survive
disconnects where possible. Video uses rotating `screenrecord` chunks, is optional, and
cannot fail a successful test.

The HTML report shows a cross-device timeline, commands by role, failure screenshots, wait
diagnostics, relevant logs, versions, the primary failure, and separate teardown failures.
Flowdeck consumes a stable result adapter, not internal driver data structures.

## 20. Security and privacy

- Bind only to IPv4 loopback; do not bind wildcard interfaces.
- Use a fresh session secret, nonce-based HMAC authentication, constant-time comparison,
  authentication deadline, and one controlling connection.
- Authenticate before parsing normal commands.
- Enforce frame, queue, selector, string, timeout, and artifact limits.
- Reject stale session IDs/generations and every duplicate or non-monotonic request ID.
- Expose typed operations only; no arbitrary device shell endpoint.
- Protect and E2E-scope the synchronization provider.
- Validate package/activity/permission inputs and prohibit destructive driver targeting.
- Redact authentication data, typed secrets, tokens, intent extras, and sensitive provider
  state from default logs.
- Store host artifacts with restrictive permissions and support disabling sensitive
  screenshots/hierarchies.

Rooted devices and a compromised CI user are outside the v1 threat model.

## 21. Observability

Every command records request ID, trace ID, device role, hashed serial, session generation,
operation, selector-plan type, queue time, execution time, poll count, cardinality class,
and error code. Text and input values are omitted by default.

Core metrics:

```text
command duration and queue duration
selector resolution duration
wait poll count
transport reconnects and driver restarts
errors by stable code
ADB command duration
artifact bytes and collection failures
```

The driver's health response includes uptime, queue depth, active request, last completed
request, Android API, UiAutomator/build versions, memory state, and session generation.

## 22. Compatibility policy

Initial proposal:

```text
Host: JDK 17+
Android: API 26+ pending Phase 0/product confirmation
UiAutomator: one pinned current AndroidX release
ADB: one pinned current platform-tools release plus one previous qualified release
```

Commit CI uses one reference emulator. Nightly and release lanes expand to minimum,
primary, and latest supported APIs; Views and Compose fixtures; selected orientations,
locales, font/display scales, navigation modes, and physical/OEM devices based on product
usage. Unsupported capabilities fail explicitly rather than silently degrading.

UiAutomator upgrades require protocol/selector golden tests, the device compatibility
suite, scrolling/staleness tests, and benchmark comparison before release.

## 23. Test strategy

### JVM unit tests

- framing, codecs, authentication, schema limits, version negotiation;
- selector builders, rendering, validation, and plan selection;
- session, command, cancellation, and AUT state machines;
- ADB parsing, deadlines, redaction, artifact paths, and event schemas; and
- JUnit/device-pool lease behavior.

### Contract tests

- host/driver major and minor compatibility;
- golden messages for every operation and error;
- malformed, truncated, oversized, duplicated, stale-generation, and unauthorized input;
- monotonic request IDs, duplicate/stale rejection, and generation boundaries; and
- binary artifact truncation and checksum failure.

### Driver fixture tests

- View and Compose resource forms;
- text/content description, relations, windows, package scoping, and ambiguity;
- recycled/stale nodes and recomposition;
- direct and keyboard text input;
- lazy View/Compose list scrolling and no-progress detection;
- permission/system dialogs, IME, rotation, screenshots, and secure content behavior; and
- every command's deadline and cancellation path.

### End-to-end and fault tests

- AUT force-stop, clear-data, crash, ANR, upgrade, reinstall, and cold start while the
  driver survives;
- driver death, instrumentation death, stale forwarding, half-open sockets, device
  disconnect/reconnect, `adbd` restart, and reboot;
- lost response before/after a tap, proving no blind replay and `INDETERMINATE` behavior;
- cancellation during lookup, scroll, gesture, provider call, screenshot, and lifecycle;
- one-device serialization and independent cross-device progress;
- barrier peer failure without deadlock;
- crossed host clients and identical device-side ports without command leakage;
- provider underflow, process restart, generation change, false quiet interval, and
  unavailable provider;
- disk-full, screenshot/video/log failure, report flushing after runner failure; and
- randomized soak runs with delays, disconnects, process death, and UI churn.

Every fault must either recover within a bounded period with a new session generation or
fail with a stable actionable error and preserve available artifacts. No scenario may
outlive the containing test deadline.

## 24. Benchmarks and reliability gates

Phase 0 records at least 100 warm samples on simple fixtures and representative dense View
and Compose product screens for:

- no-op transport round trip;
- first-match and missing-element queries;
- relational selectors;
- hierarchy dump, transfer, parse, and host query;
- wait polling; and
- two-device independent execution.

Production benchmarks add scrolling, screenshots, launch/clear-data, artifact overhead,
memory growth, and idle CPU. Record median, p90, p95, p99, errors, API/device, hierarchy
size, and warm/cold state.

Do not invent an absolute speed target before the spike. After baselining, fail performance
CI for a reproducible p95 regression above 20%. Before production candidate:

- run each framework smoke flow 100 times as a smoke/zero-hang gate, not as proof of 99%
  reliability;
- run the primary multi-device flow 100 times under the same smoke gate;
- collect enough attempts that the one-sided 95% Clopper-Pearson lower confidence bound for
  framework control-plane success is at least 99% (299 zero-failure attempts is the minimum
  illustrative case; any failures require more attempts); and
- complete a two-hour driver soak without continuous memory growth or leaked processes.

The denominator is every scheduled attempt for which device acquisition began, including
initial attempts later retried, setup failures, quarantined-device capacity failures, and
unknown outcomes. A valid product/backend assertion failure with complete framework result
and artifacts is a framework-control success. Framework failures and unknowns count against
framework control-plane success. Device-lab failures are reported separately and also count
against end-to-end infrastructure success. Publish per-device, per-test, and run-level
rates; never replace first-attempt data with retry results.

Classify failures as product, backend, framework, device lab, authoring, or unknown, with
unknown counting against reliability until adjudicated.

## 25. Delivery plan

Build vertical slices so each merged operation includes protocol, driver, host API, tests,
events, and diagnostics.

### Phase 0 - feasibility spike, 5-8 days

Implement only the minimum protocol/driver/host path needed to test the risky boundaries:
driver startup/authentication, `exists`, `tap`, `setText`, `scrollUntil`, `wait`, explicit
AUT/system scope, lifecycle isolation, two-device supervision, cleanup/reconnect, uncertain
outcomes, a cross-UID synchronization-provider call, and the benchmark harness.

Test surfaces include a fixture with Compose merged semantics and a lazy list, a traditional
View list, text input, a permission/system dialog, and at least one representative dense
screen from every real target product. Produce an accessibility inventory for the top
planned product journeys, identifying critical nodes that are absent, ambiguous, merged,
custom-drawn, WebView-backed, or otherwise unsuitable.

Evaluate the pinned current AndroidX UiAutomator APIs on the provisional minimum API and
the primary CI API. Exercise startup with an orphan driver, occupied device port, stale
forward, and changed boot identity. Drop transport before request acceptance, after
acceptance, and after input injection. Deliberately block or delay a platform operation and
prove that old instrumentation cannot affect the next test. Verify duplicate/stale request
rejection in the same generation and rejection across generations. Disconnect one of two
devices while proving the other continues independently. Verify `/proc` process-start token
availability on the supported device matrix; if unavailable, synchronization identity that
depends on it is capability-gated off until an equally strong process identity is defined.

Exit criteria:

- driver survives AUT force-stop, clear-data, crash, and cold launch;
- raw Compose and qualified View resources both work;
- two devices remain independently addressable;
- system dialogs require explicit system scope and cannot be matched as AUT content;
- authentication failure is rejected and the socket is loopback-only;
- disconnect is bounded and a subsequent session rebuild is deterministic;
- orphan/port/forward cleanup is deterministic or the device is quarantined;
- old or late driver work cannot affect a subsequent test;
- no action is replayed after an uncertain response;
- cross-UID synchronization access and process-restart invalidation work; and
- measurements show a meaningful direct-query advantage or explain any exception.

No-go/redesign criteria are inaccessible UI required by critical journeys, inability to
prevent late commands from crossing test boundaries, nondeterministic cleanup without safe
quarantine, failed lifecycle isolation, or unsafe fine-grained multi-device routing. A
Maestro-per-device orchestrator is acceptable only if stakeholders explicitly relax the
core requirement from fine-grained interleaving to coarse-grained handoffs.

### Phase 1 - contract and driver, 10-15 days

- Gradle/module foundation and protocol contract.
- Session authentication, framing, versions, capabilities, limits, and errors.
- Selector AST and native/fallback plan boundary.
- Serialized command executor, deadlines, cancellation, and poisoned-session handling.
- Core input, gestures, container scrolling, waits, screenshots, and driver tests.
- Synchronization SDK and first owned-app integration.

Exit criteria:

- every driver operation has contract and fixture coverage;
- no normal operation dumps the hierarchy;
- cancellation either leaves the session reusable or explicitly poisons it;
- ambiguous actions fail by default; and
- protocol documentation is enough to implement another client without driver source.

### Phase 2 - host SDK and orchestration, 8-12 days

- Typed ADB layer and process supervision.
- Session state machine, leases, forwarding, health, and cleanup.
- Kotlin selectors, lazy `Element`, `App`, waits, and exceptions.
- Named-role device pool, all-or-none acquisition, barriers, and structured fan-out.
- Fake ADB/fake driver coverage and public examples.

Exit criteria:

- one-device order and cross-device concurrency are demonstrated;
- a failed device cancels related sibling work and bounded teardown completes;
- old generations and cross-device messages are rejected; and
- a second engineer can write a multi-device fixture test from documentation.

### Phase 3 - JUnit, artifacts, and CI pilot, 13-21 days

- JUnit 5 extension and timeout/quarantine/tag behavior.
- Incremental JSONL events, JUnit XML, failure collection, and HTML report.
- Flowdeck result adapter.
- Clean-worker emulator provisioning, signing, device health, sharding, cleanup, and
  artifact upload.
- Benchmark, soak, and core fault-injection lanes.

Exit criteria:

- five to ten real product journeys run non-blocking in CI;
- at least one real multi-device journey and one idling-integrated journey run;
- every failure has a self-contained artifact directory;
- three consecutive nightly runs require no manual lab recovery; and
- production APKs contain neither the synchronization provider nor WebView debugging.

### Phase 4 - production qualification, 15-25 days

- Supported Android/device matrix qualification.
- Full fault-injection set and performance regression gates.
- Security review, upgrade/rollback procedure, release automation, and ownership rotation.
- Gradual promotion of stable smoke tests to blocking status.

Exit criteria:

- the confidence-based reference reliability gate in section 24 and all bounded-failure
  requirements pass;
- two maintainers can diagnose and release host and driver components;
- two independent test authors can author/debug tests without the original author;
- four weeks of pilot reliability data exist; and
- no critical issue remains in lifecycle isolation, device assignment, cancellation,
  artifact privacy, or protocol compatibility.

### Phase 5 - optional demand-driven work

- Inspector desktop UI: 5-8 days after selectors stabilize.
- ChromeDriver-backed WebView module: 5-10+ days after a concrete test requires it.
- Additional language binding: only after Kotlin/protocol stability.

## 26. Effort and staffing

| Work | Engineering days |
|---|---:|
| Phase 0 spike | 5-8 |
| Contract and production driver | 10-15 |
| Kotlin host SDK and orchestration | 8-12 |
| Synchronization SDK and first integration | 3-5, overlaps Phase 1 |
| JUnit, artifacts, reports, CI pilot | 13-21 |
| Compatibility, benchmarks, fault injection, rollout | 15-25 |
| Documentation and adoption work | 6-10, distributed |

Core production candidate: approximately 60-80 engineering days. Two focused engineers
plus part-time QA/device-lab support should plan for at least 8-12 implementation weeks,
followed by the four-week pilot observation gate; those periods may overlap only where the
pilot feature set is already frozen. One part-time engineer should plan for 3-5 months and
carries significantly greater continuity risk.
Expect intermittent reliability work for at least six months.

Minimum ownership:

- two engineers able to modify both host and driver;
- one QA automation lead for test conventions and adoption;
- named device-lab/CI and Flowdeck integration owners; and
- weekly reliability triage for the first three months.

## 27. Rollout

1. Shadow 3-5 existing stable tests without replacing release gates.
2. Run 5-10 product tests as a non-blocking nightly pilot and classify every failure.
3. Promote only tests with seven days of unexplained-framework-failure-free execution.
4. Expand gradually; do not bulk migrate tests that gain no concrete benefit.

Keep existing tooling available during adoption, especially for unsupported WebViews and
third-party accessibility edge cases.

## 28. Principal risks

| Risk | Mitigation |
|---|---|
| Accessibility does not represent important UI | Fixture coverage, owned-app semantics conventions, inspector |
| No-retry default creates sleeps | Make waits shorter, report fixed sleeps, review default after pilot data |
| ADB recovery is unreliable | Phase 0 gate, session generations, fault injection, device quarantine |
| Uncertain actions are duplicated | No automatic mutation replay; explicit `INDETERMINATE` outcome |
| Idling integration is delayed or omitted | Make first-app integration a Phase 3 exit criterion |
| Protocol freezes around one AndroidX API | Stable AST, adapter boundary, current-API spike evaluation |
| CI leaves devices/processes dirty | Exclusive leases, bounded cleanup, child supervision, soak tests |
| Reporting is postponed | Include event/artifact work in every vertical slice |
| WebView doubles scope | Separate deferred backend |
| One permanent expert owns everything | Two maintainers from Phase 1 and second author by Phase 2 |

## 29. Approval checklist

The design is ready to implement only when independent review confirms:

- process isolation and all session state transitions are coherent;
- protocol framing, authentication, versioning, duplicate handling, and uncertain outcomes
  are implementable without ambiguity;
- cancellation cannot permit a late command to affect a later test;
- selector cardinality, ordering, Compose resources, waits, and scrolling are precise;
- AUT lifecycle and synchronization state cannot be mistaken across process generations;
- multi-device leases and failures cannot deadlock or cross-route commands;
- artifacts preserve the primary failure and respect privacy limits;
- Phase 0 can falsify the highest-risk assumptions; and
- estimates distinguish a thin prototype from a production framework.
