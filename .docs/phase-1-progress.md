# Phase 1 Progress

Date: 2026-09-17

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

Latest successful generations:

| Device | API | Generation |
|---|---:|---:|
| Android emulator | 34 | 243 |
| Samsung SM-J810G | 29 | 107 |

## Remaining Phase 1 Work

- [ ] Split the driver into an independent socket reader, bounded queue, serialized command
  executor, writer, and watchdog.
- [ ] Implement safe cancellation and terminal command states without permitting late work.
- [ ] Add duplicate editable-field and scroll-container fixtures to cover the implemented
  exact-one behavior beyond the validated duplicate-tap case.
- [ ] Define selector AST limits, relations, matching modes, and native/fallback boundary.
- [ ] Replace free-form remote error strings with the typed taxonomy and host exceptions.
- [ ] Add screenshots with bounded binary transfer and checksum validation.
- [ ] Add long tap, directional swipe/scroll, clear text, and fixture coverage.
- [ ] Extract synchronization into an optional debug/E2E-only SDK module.
- [ ] Add golden request/response fixtures and coverage for every operation/error.
- [ ] Prove cancellation either leaves a session reusable or explicitly poisons it.

The next safety-critical vertical slice is ambiguity-safe tap plus the command execution state
machine. Ambiguity-safe tap is complete and reviewed. Cancellation will be added only after
the reader can observe it independently of a blocked UI operation.
