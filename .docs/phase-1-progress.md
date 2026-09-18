# Phase 1 Progress

Date: 2026-09-18

Status: in progress.

## Completed

- Application protocol version `1.0` independent of bootstrap framing version 1.
- Canonical handshake JSON with recursive key ordering and malformed-canonical rejection.
- Authenticated HELLO/CHALLENGE/NEGOTIATION transcript with separate HMAC domains.
- Highest-common-version selection and authenticated capability subset.
- Driver metadata for Android API, component builds, driver instance, operations, operation
  versions, and capabilities.
- Request operation version with `UNSUPPORTED` rejection before execution.
- Rejected operation versions consume their request ID and cannot be replayed.
- No-common-version rejection before normal authentication.
- Golden canonical payload, malformed JSON, negotiation, downgrade, capability, and
  transcript-binding unit tests.
- Full API 29/API 34 device suite passed after the handshake migration.
- Mutating selectors use shared exact-one resolution. `TAP`, `SET_TEXT`, `TYPE_TEXT`, and
  `SCROLL_UNTIL` return `AMBIGUOUS` rather than choosing an arbitrary action target or scroll
  container.
- Tap and text operations recheck their request deadline before input. Key-event input also
  revalidates exact-one focus immediately before injection and stops injecting after expiry.
- Selector lookup propagates unexpected driver failures as `INTERNAL` and uses explicit node
  recycling instead of broadly converting failures to absence.
- Duplicate-button validation proved that `AMBIGUOUS` is returned before input and both fixture
  counters remain unchanged on API 29 and API 34.
- A milestone review subagent identified exact-one, deadline, and node-ownership bypasses in
  text input and scrolling. Those findings were fixed and the targeted device validation was
  repeated successfully.

- Driver execution split into independent lanes in the pure-JVM `:driver:command-engine`
  module: socket reader, bounded queue (16, `OVERLOADED` on overflow), single serialized
  executor, writer, and watchdog. Deadlines start at acceptance and queue residence consumes
  them. 19 JVM tests cover ordering, overload, queued/running cancellation, the mutation gate,
  post-mutation cancel being ignored, deadline-in-queue, watchdog poisoning before and after
  mutation, late-work suppression after poison, pong bypassing a busy executor, write failure,
  and shutdown.
- `CANCEL`, `PING`, and `PONG` frames. The driver connection thread is reader-only; the
  watchdog poisons the session, emits `TAP_POISONED`, and kills the process after a flush grace
  (disabled only for the late-work fault so host-side forced termination stays testable).
- Host `DriverClient` demultiplexes responses on a reader thread and exposes `submit`/
  `PendingCommand.cancel`/`ping`. 10 JVM tests against a loopback `FakeDriverServer` (real
  handshake) cover out-of-order responses, cancel framing, cancel-after-mutation, ping, transport
  loss classification, unknown-ID poisoning, and concurrent ID ordering.
- Device-proven on API 29 and API 34 (`host --no-reboot`, 2026-09-18):
  `PHASE_1_CANCELLATION_OK` (cancel a running 30 s wait in 136–236 ms, cancel a queued wait
  before the running one finishes, queued `HEALTH` expires with `DEADLINE_EXCEEDED`, `PING`
  answered in ~50 ms while the executor is blocked, session reusable afterwards) and
  `PHASE_1_CANCEL_AFTER_MUTATION_OK` (driver fault `CANCEL_AFTER_MUTATION` holds a fault-button
  tap 3 s after its click; the host's `CANCEL` is ignored, the awaited result is the definitive
  `ok` tap, the counter advances exactly once, and the session stays usable).
- `host --no-reboot` skips only the late-mutation quarantine scenario, whose recovery reboots
  the device; every other Phase 0/1 scenario ran and passed with the new execution model.
- Explicit validation request IDs now advance the host allocator, so a session that ran the
  fencing checks can keep issuing commands (found on device: the first cancellation probe was
  rejected as `DUPLICATE_OR_STALE`).

- Typed error taxonomy: `ErrorCode` enum on the wire (plan list plus `OVERLOADED`,
  `PAYLOAD_TOO_LARGE`, host-only `UNKNOWN` decode fallback) with per-code may-have-mutated and
  retryable properties, plus a stable `detail` sub-reason so former ad-hoc codes (`END_REACHED`,
  `FOCUS_LOST`, `SYNC_*`, ...) no longer grow the enum. Post-mutation cardinality failures now
  report `STALE_DURING_COMMAND` instead of `NOT_FOUND`/`AMBIGUOUS`, and partial key input is
  `ACTION_REJECTED/PARTIAL_INPUT` instead of `DEADLINE_EXCEEDED`, so no retryable code can
  follow a mutation. Host `CommandException` hierarchy (`RemoteCommandException`,
  `CommandTransportException`) carries code, detail, operation, request ID, generation, serial,
  rendered selector, timeout. 7 protocol tests (round-trip of every code, unknown fallback,
  golden JSON, taxonomy list) and 3 client tests; full device flow re-passed on API 29/34.

- Selector AST (`protocol/Selector.kt`): string properties with `EXACT`/`CONTAINS`/
  `STARTS_WITH`/`ENDS_WITH`/`REGEX` modes, resource IDs, nine boolean properties,
  parent/ancestor/child/descendant relations, `AUT`/`SYSTEM` scope, and `EXACTLY_ONE`/
  `FIRST`/`AT` limits with explicit accessibility-order opt-in. One validator
  (`SelectorValidation`) runs on the host before a request ID is allocated and on the driver
  before any lookup: depth ≤ 32, ≤ 256 nodes, ≤ 1024 chars, non-empty nodes/values, RE2-only
  regexes. The driver compiles non-regex selectors to a window-scoped `BySelector`
  (`UiWindow.findObjects` on the scope package's focused window) and regex selectors to a
  single-pass traversal predicate; no hierarchy dump on the hot path. 7 protocol tests plus
  `PHASE_1_SELECTORS_OK` on API 29/34: `AMBIGUOUS` for every mutating operation against the
  duplicate buttons/fields/scroll containers with fixture state unchanged, `.first()`/`.at(1)`
  taps, `.at(2)` → `NOT_FOUND`, ancestor/child relations, regex traversal, host-side rejection
  of an empty node, foreign resource package → `SCOPE_DENIED`.
- `LONG_TAP`, `CLEAR_TEXT`, `SWIPE`, and single-segment `SCROLL` (value = content moved), and
  `SCROLL_UNTIL` takes a `direction`/`distancePercent`; all share one gesture shape
  (checkpoint → exactly-one resolve → interactable check → mutation gate → act). Fixture
  `AmbiguityActivity` gained a long-press-aware gesture target and a prefilled field; the device
  flow proves long press vs tap, clear text, `NOT_INTERACTABLE` for clear on a button, scroll
  end detection (`SCROLL DOWN` at the end → `false`, `UP` → `true`), and `INVALID_REQUEST` for
  a scroll without direction.
- Screenshots (`SCREENSHOT`, capability `artifact.screenshot.v1`): `UiAutomation.takeScreenshot`
  → PNG → `BLOB_START`/`BLOB_CHUNK` (≤ 256 KiB, indexed)/`BLOB_END` on the writer lane before
  the terminal `RESPONSE` with `ArtifactInfo` (blob ID, media type, byte count, SHA-256,
  size). 64 MiB cap; cancel/deadline honored between chunks; host `BlobReceiver` verifies order,
  length, and checksum (`ARTIFACT_TRANSFER_FAILED` with `BLOB_*` details) and keeps the session
  usable. 4 pipeline tests, 4 client tests (including FLIP_BYTE/DROP_CHUNK/REORDER/NO_END
  corruption), `PHASE_1_SCREENSHOT_OK` on API 29/34 (720×1480 and 1080×2400 PNGs).
- Driver-required heartbeat: any inbound frame resets the timer; silence for
  `tapHeartbeatTimeoutMs` (default 30 s) poisons the session with `DRIVER_UNHEALTHY` /
  `HEARTBEAT_EXPIRED`, emits `TAP_POISONED`, and self-kills. The host client pings after 5 s of
  idle on a background thread. `PHASE_1_HEARTBEAT_EXPIRY_OK` on API 29/34 forces the 3 s
  timeout with host pings disabled and observes the poison marker, the terminal code, and the
  driver process exiting — the watchdog poisoning path is now device-proven without a reboot.
- Synchronization extracted to the `:sync-sdk` Android library (`com.company.tap.sync`,
  authority `${applicationId}.tap-sync`, signature permission declared in the library
  manifest). The fixture-only late-mutation hook moved to `FixtureFaultProvider`
  (`com.company.tap.fixture.fault`, driver argument `tapFaultAuthority`), so the SDK contains
  nothing test-fixture specific. Full device flow re-passed through the extracted provider.
- Golden wire fixtures under `protocol/src/test/resources/golden`: one request per operation
  (plus relational and system-scoped selector shapes) and one response per error code and
  success shape; `GoldenMessageTest` fails on decode or encoding drift and checks that no
  operation or code is missing a fixture.

Latest successful generations:

| Device | API | Generation |
|---|---:|---:|
| Android emulator | 34 | 317 |
| Samsung SM-J810G | 29 | 168 |

## Remaining Phase 1 Work

- [x] Split the driver into an independent socket reader, bounded queue, serialized command
  executor, writer, and watchdog.
- [x] Implement safe cancellation and terminal command states without permitting late work.
- [x] Wire the duplicate editable-field and scroll-container fixtures into host validation
  (`PHASE_1_SELECTORS_OK` exercises every mutating operation against them).
- [x] Define selector AST limits, relations, matching modes, and native/fallback boundary.
- [x] Replace free-form remote error strings with the typed taxonomy and host exceptions.
- [x] Add screenshots with bounded binary transfer and checksum validation.
- [x] Add long tap, directional swipe/scroll, clear text, and fixture coverage.
- [x] Extract synchronization into an optional debug/E2E-only SDK module.
- [x] Add golden request/response fixtures and coverage for every operation/error.
- [x] Prove cancellation either leaves a session reusable or explicitly poisons it
  (`PHASE_1_CANCELLATION_OK`, `PHASE_1_CANCEL_AFTER_MUTATION_OK`,
  `PHASE_1_HEARTBEAT_EXPIRY_OK` on API 29/34).
- [x] Add host-driven heartbeat expiry on the driver.
- [ ] Re-run the late-mutation quarantine scenario (reboots the device) against the
  watchdog-aware driver. Needs an explicit go-ahead because it reboots both devices.
- [ ] Emit `AUT_CRASHED`/`AUT_ANR`/`AUT_NOT_INSTALLED` from observed process state (codes are
  defined and fixture-covered, not yet produced).
- [ ] Public Kotlin `Device`/`App`/`Element` API and JUnit 5 extension (Phase 2 per the plan;
  the host still calls protocol operations directly).

Everything in the Phase 1 contract-and-driver list is implemented and device-proven except
the reboot-only quarantine re-run. Next: the quarantine re-run when a reboot is acceptable,
then the Phase 2 public API on top of `DriverClient`.
