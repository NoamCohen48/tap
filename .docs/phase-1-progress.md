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

Latest successful generations:

| Device | API | Generation |
|---|---:|---:|
| Android emulator | 34 | 261 |
| Samsung SM-J810G | 29 | 121 |

## Remaining Phase 1 Work

- [x] Split the driver into an independent socket reader, bounded queue, serialized command
  executor, writer, and watchdog.
- [x] Implement safe cancellation and terminal command states without permitting late work.
- [ ] Wire the duplicate editable-field and scroll-container fixtures into host validation.
  `AmbiguityActivity` (`activity_ambiguity.xml`) already contains duplicate `EditText`s and
  duplicate `ScrollView`s, but nothing in `PhaseZeroMain` or the product probe exercises it
  yet. The duplicate-button case referenced above was validated through the probe's
  `!AMBIGUOUS:` step; the current fixture layout no longer contains that duplicate button.
- [ ] Define selector AST limits, relations, matching modes, and native/fallback boundary.
- [ ] Replace free-form remote error strings with the typed taxonomy and host exceptions.
- [ ] Add screenshots with bounded binary transfer and checksum validation.
- [ ] Add long tap, directional swipe/scroll, clear text, and fixture coverage.
- [ ] Extract synchronization into an optional debug/E2E-only SDK module.
- [ ] Add golden request/response fixtures and coverage for every operation/error.
- [x] Prove cancellation either leaves a session reusable or explicitly poisons it
  (`PHASE_1_CANCELLATION_OK`, `PHASE_1_CANCEL_AFTER_MUTATION_OK` on API 29/34; watchdog
  poisoning is JVM-tested, not yet forced on a device).
- [ ] Re-run the late-mutation quarantine scenario (reboots the device) against the watchdog-
  aware driver, and add a device fault that forces watchdog poisoning to observe
  `TAP_POISONED` plus self-kill end to end.
- [ ] Add host-driven heartbeat expiry on the driver (currently `PING` is answered but never
  required).

Ambiguity-safe tap, the command execution state machine, and cancellation are implemented and
device-proven. Next: the typed error taxonomy (folding in the pipeline's `CANCELLED`,
`DEADLINE_EXCEEDED`, `OVERLOADED`, `DRIVER_UNHEALTHY`, `INDETERMINATE`), then the selector AST.
