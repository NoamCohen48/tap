# Phase 1 Progress

Last updated: 2026-09-21

Status: in progress.

## Completed

- Application protocol version `3.0` independent of bootstrap framing version 1. 3.0 (2026-09-26)
  made every payload protobuf (`tap.wire.v1` wrapping the public `tap.v1.Command`/`CommandResult`,
  one schema in `contracts/proto`); 2.0 replaced 1.0's flat operation/optional-field model with
  per-command and per-result shapes. Full API 29/API 34 `--no-reboot` flow, Kotlin fixture tests
  and Python suite (JVM and native daemon) re-passed after the 3.0 migration.
- The handshake MACs the payload bytes as sent (negotiation carried as serialized bytes), so no
  canonical encoding is needed; a payload that does not parse fails the handshake.
- Authenticated HELLO/CHALLENGE/NEGOTIATION transcript with separate HMAC domains.
- Highest-common-version selection and authenticated capability subset.
- Driver metadata for Android API, component builds, driver instance, operations, operation
  versions, and capabilities.
- One message per command (`oneof op`); an unset or unknown `op` is `UNSUPPORTED` before execution.
- Rejected payloads (unknown `op`, malformed command) consume their request ID and cannot be replayed.
- No-common-version rejection before normal authentication.
- Golden wire encoding, malformed payload, negotiation, downgrade, capability, and
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

- Driver execution split into independent lanes in the pure-JVM `:device:driver:command-engine`
  module: socket reader, bounded queue (16, `OVERLOADED` on overflow), single serialized
  executor, writer, and watchdog. Deadlines start at acceptance and queue residence consumes
  them. 23 JVM tests cover ordering, overload, queued/running cancellation, the mutation gate,
  post-mutation cancel being ignored, deadline-in-queue, watchdog poisoning before and after
  mutation, late-work suppression after poison, pong bypassing a busy executor, write failure,
  and shutdown.
- `CANCEL`, `PING`, and `PONG` frames. The driver connection thread is reader-only; the
  watchdog poisons the session, emits `TAP_POISONED`, and kills the process after a flush grace
  (disabled only for the late-work fault so host-side forced termination stays testable).
- Host `DriverClient` demultiplexes responses and exposes `submit`/
  `PendingCommand.cancel`/`ping`. 27 JVM tests against a loopback `FakeDriverServer` (real
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

- Selector AST (`protocol/Selector.kt`), a sum-type expression tree since 2026-09-20: `Node`
  is `match` (text/contentDescription/hint/className with `EXACT`/`CONTAINS`/`STARTS_WITH`/
  `ENDS_WITH`/`REGEX`), `flag` (nine booleans), `resource`, `related` (parent/ancestor/child/
  descendant), `all_of`, `any_of`; `Scope` is `aut` | `system(packageName)`; `Pick` is
  `exactly_one` | `first` | `at(index)` (choosing `first`/`at` is the accessibility-order
  opt-in). `CommandValidation.validate(command)` dispatches over `ScrollUntil`, other
  `Targeted` commands and non-targeted commands on the host before a request ID is allocated;
  `validateSelector` applies the same structural rules during device compilation before lookup:
  depth ≤ 32, ≤ 256 nodes, ≤ 1024 chars,
  ≥ 2 combinator operands, non-empty resource values, RE2-only regexes. The driver compiles to
  a window-scoped `BySelector` (`UiWindow.findObjects` on the scope package's focused window)
  unless the tree holds a regex, an `any_of` or a repeated single-valued `BySelector` slot,
  which take a single-pass traversal predicate; no hierarchy dump on the hot path. 8 protocol
  tests plus `PHASE_1_SELECTORS_OK` on API 29/34: `AMBIGUOUS` for every mutating operation
  against the duplicate buttons/fields/scroll containers with fixture state unchanged,
  `.first()`/`.at(1)` taps, `.at(2)` → `NOT_FOUND`, ancestor/child relations, regex traversal,
  `any_of` and repeated-text conjunctions agreeing with native counts and tapping through a
  relation, host-side rejection of an empty conjunction, foreign resource package →
  `SCOPE_DENIED`.
- `LONG_TAP`, `CLEAR_TEXT`, `SWIPE`, and single-segment `SCROLL` (value = more content remains), and
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
- Synchronization extracted to the `:device:sync-sdk` Android library (`io.github.noamcohen48.tap.sync`,
  authority `${applicationId}.tap-sync`, signature permission declared in the library
  manifest). The fixture-only late-mutation hook moved to `FixtureFaultProvider`
  (`io.github.noamcohen48.tap.fixture.fault`, driver argument `tapFaultAuthority`), so the SDK contains
  nothing test-fixture specific. Full device flow re-passed through the extracted provider.
- Golden wire encodings under `contracts/protocol/src/test/resources/golden` (30 `.hex`:
  handshake messages, representative requests incl. relational/system-scoped/AUT-resource
  selectors and default-omitting optionals, one response per outcome case plus error shapes);
  `GoldenWireTest` fails on encoding drift. Per-operation coverage is structural: the catalogue
  and the driver's dispatch are exhaustive `when`s over the generated case enums
  (`OperationsTest`).

- Observation and key operations (`DEVICE_INFO`, `PRESS_KEY`, `COUNT`, `SNAPSHOT`,
  `WAIT_GONE`, `WAIT_APP_VISIBLE`) with typed `DeviceInfo`/`ElementSnapshot`/`Bounds`
  payloads and golden fixtures. `PHASE_1_OBSERVATION_OK` on API 29/34: `COUNT` = 2 for the
  duplicate buttons and 0 for an absent selector, `SNAPSHOT` state flags and resource name,
  `AMBIGUOUS` snapshot of duplicates, `DEVICE_INFO` API level and focused package,
  `WAIT_APP_VISIBLE`, `PRESS_KEY -1` → `INVALID_REQUEST`, back key → `WAIT_GONE` of the
  ambiguity screen.
- Hint-aware text observation: an empty `EditText` reports its hint as accessibility text on
  API 26+, which made `CLEAR_TEXT` on a hinted field fail as `TEXT_MISMATCH` and leaked hints
  into `SNAPSHOT.text` (found by the SDK sample suite). One `displayedText()` helper now backs
  `SNAPSHOT`, the text-verification loop, and key-event input; the validation flow clears the
  hinted keyboard field and checks the snapshot reads empty text plus the hint.
- Host split into a reusable library (`:host:core`: `Adb`, `DriverClient`, `DriverLifecycle`,
  `DeviceSession`, `SessionJournal`) and the validation executable (`:host:validation`), so
  product code never depends on `PhaseZeroMain`.
- Phase 2 public API delivered on top (`:clients:kotlin:sdk`, `:clients:kotlin:junit5`,
  `:samples:fixture-tests`; see `.docs/project-architecture.md` §5–7): coroutine
  `Device`/`App`/`Element`/`ElementWait`, selector DSL, client-side role assignment,
  `@TapTest` JUnit 5 extension with failure artifacts, and 13 device tests plus one discovery
  guard passing against the fixture app on API 29 + API 34 concurrently (2026-09-21).

Latest successful generations (`host --no-reboot` run 8 plus the sample suite, 2026-09-18):

| Device | API | Generation |
|---|---:|---:|
| Android emulator | 34 | 353 |
| Samsung SM-J810G | 29 | 190 |

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
- [x] Add golden request/response fixtures and coverage for every operation/error (3.0: golden
  encodings per message shape + exhaustive dispatch over every operation, `ErrorCodeTest` over
  every code).
- [x] Prove cancellation either leaves a session reusable or explicitly poisons it
  (`PHASE_1_CANCELLATION_OK`, `PHASE_1_CANCEL_AFTER_MUTATION_OK`,
  `PHASE_1_HEARTBEAT_EXPIRY_OK` on API 29/34).
- [x] Add host-driven heartbeat expiry on the driver.
- [ ] Re-run the late-mutation quarantine scenario (reboots the device) against the
  watchdog-aware driver. Needs an explicit go-ahead because it reboots both devices.
- [x] Plan §11 operations: `device.pressKey/pressBack/pressHome`, `device.info`,
  `element.count`, `element.snapshot` (covers `getProperty`), `wait.appVisible`, `wait.gone`;
  `sync.awaitIdle` is host-side (`App.awaitIdle` over `SyncBootstrap`/`SyncPoll`).
- [x] `wait.screenStable` as the explicit `WAIT_SCREEN_STABLE` operation (`Device.awaitScreenStable`
  / `await_screen_stable`, with `awaitAppSettled` and `awaitAnimationEnd` selecting one
  signal): tree fingerprint and/or downscaled pixel grid, accessibility-event
  driven, `SCREEN_CHANGING`/`APP_NOT_VISIBLE` timeout details; proven by `MotionTest` and
  `test_motion.py` against the fixture's `MotionActivity` on API 29 and API 34. No command
  waits for a quiet screen implicitly.
- [ ] Plan §11 operations still missing: `device.wake`, `session.shutdown` (sessions end by
  closing the socket and force-stopping the instrumentation), `inspector.snapshot`.
- [ ] Emit `AUT_CRASHED`/`AUT_ANR`/`AUT_NOT_INSTALLED` from observed process state (codes are
  defined and fixture-covered, not yet produced).
- [ ] Driver `<queries>` lists only the fixture's provider authorities; a product AUT's
  `<pkg>.tap-sync` is invisible to the driver on API 30+ until visibility is solved.
- [x] Public Kotlin `Device`/`App`/`Element` API and JUnit 5 extension (Phase 2; see above).

The Phase 1 exit criteria (contract + fixture coverage for every implemented operation, no
hierarchy dump on the hot path, cancellation reusable-or-poison, ambiguous-by-default,
implementable protocol documentation) are met for the implemented operation set, and the
Phase 2 public API makes the framework usable for writing tests today (`README.md`,
"Writing tests"). What is still missing before it is production-grade is tracked in
`.docs/framework-gaps.md`.
