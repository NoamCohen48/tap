# Driver-side count waits: investigation verdict

Status: investigation only; no driver, protocol or SDK change implemented or approved by
this note.

## Verdict

No fundamental technical blocker was found to moving arbitrary-count waits into the driver.
The driver already polls a match count for `wait_visible(exactly_one=true)`. Leaving arbitrary
count waits on the client appears to be a remaining inconsistency, not an Android requirement.
No documented technical reason for that exception was found in the inspected code and history.
This is a source-level assessment, not device validation of a new count-wait operation.

## Current behavior and why history loses the wait

Both Python `ElementWait.count(expected)` and Kotlin `ElementWait.count(expected)` use the
SDK's generic host polling helper. Each iteration sends a `count(selector)` command, then
compares the result with `expected` locally. The overall timeout and comparison are not sent
to the daemon or driver.

For example, Python `element.wait(timeout=5).count(0)` means "wait up to five seconds until
no nodes match." The daemon's raw history contains individual count commands, not the
expected zero or the five-second wait budget. Each count command has its own query timeout;
that is not the overall wait timeout. A client-side wait timeout is likewise not a failed
count command in the server log.

This does not apply to every wait: visible, gone and element-state waits are already single
driver commands. Named agent recordings also retain explicit count-wait arguments today;
`history` is the raw command trail, not the named recording.

The change `ee69c371` ("Device actions: Wait until an element satisfies a condition on the
device") moved property waits into `wait_state`, but left arbitrary-count waits host-polled.
It does not establish a technical prohibition on moving count waits too.

## Constraints a driver-side implementation must address

- **Capped counts.** `UiObjectAccess.count` returns at most `MAX_MATCH_COUNT` (1,000).
  Comparing that result with 1,000 cannot prove an exact count of 1,000: there may be more.
  Values above the cap cannot be reached using the existing counter. This limitation already
  exists in host-side count waits. Define supported expected values or distinguish overflow;
  do not silently advertise exact counts the implementation cannot prove.
- **Stale trees.** The counter returns null for a stale lookup. A wait must retry rather than
  interpret null as zero and incorrectly pass a disappearance wait. Resolve and recycle nodes
  on each poll; retain no element handles across polls.
- **Match selection.** Existing count queries count all matches, ignoring `first()` and
  `at()`. Preserve that behavior rather than accidentally applying action-target selection.
- **Overall deadline.** Driver requests are limited to `MAX_REQUEST_TIMEOUT_MS` (120 seconds),
  including queue residence. A single count-wait command would apply this bound to the whole
  wait, whereas the current host loop sends separately bounded count commands. Decide and
  document the behavior for longer requested waits.
- **Executor occupancy.** A driver wait occupies the single command executor until it ends;
  it does not yield between polls to other queued commands. This is already true of existing
  driver waits, but differs from separate host-polled count commands. A queued action cannot
  be relied on to make an in-flight count wait succeed.
- **Cancellation and polling.** Existing driver waits check cancellation and use bounded,
  cancellation-aware sleeps, normally polling every 50 ms. Host polling uses the SDK's
  configurable interval (100 ms by default). Reuse driver cancellation/deadline machinery
  and document the polling change rather than assuming identical timing.
- **Timeout evidence.** Preserve the expected count in the command and report the last known
  count on timeout, with an explicit unknown observation if every lookup was stale. Keep
  wait failure distinct from transport failure. Both SDKs must expose useful diagnostics.

## Recommended follow-up, not yet implemented

Add an explicit driver-side count-wait operation with selector and expected count, using the
existing request timeout as its wait budget. Update both SDKs, protocol validation, history
rendering and generated bindings together. It is a read-only wait, not a mutation or a replay.

Before implementation, settle count-cap/overflow and timeout compatibility. Regression tests
should cover zero, one and multiple matches, changing counts, stale reads, selector picks,
cap boundaries, cancellation, timeout diagnostics and the recorded command's expected count
and timeout. Device checks are still needed to validate the eventual implementation.

This follow-up does not require code generation from exported flows, inferred assertions or
a new executable recording format. It also does not fix the separate named-export gaps in
structured failure reasons or resolved implicit-settle package scope.

## Source evidence

Paths are relative to the repository root:

- `clients/python/tap_e2e/element.py`: `ElementWait.count` and device-side wait methods.
- `clients/python/tap_e2e/device.py`: `await_until`, SDK timeouts and polling interval.
- `clients/kotlin/sdk/src/main/kotlin/io/github/noamcohen48/tap/sdk/ElementWait.kt`:
  host-polled `count` versus driver-side `one` and `until`.
- `device/driver/core/src/main/kotlin/io/github/noamcohen48/tap/driver/WaitCommands.kt`:
  exactly-one counting, stale handling and the existing wait loop.
- `device/driver/core/src/main/kotlin/io/github/noamcohen48/tap/driver/UiObjectAccess.kt`:
  capped counting, pick-independent lookup and node recycling.
- `device/driver/command-engine/src/main/kotlin/io/github/noamcohen48/tap/driver/engine/CommandContext.kt`:
  deadlines, cancellation and bounded sleeps.
- `device/driver/command-engine/src/main/kotlin/io/github/noamcohen48/tap/driver/engine/CommandPipeline.kt`:
  bounded queue and single command executor.
- `contracts/protocol/src/main/kotlin/io/github/noamcohen48/tap/protocol/Protocol.kt`:
  count and request-timeout limits.
- `contracts/proto/command.proto`: separate count, visibility and state-wait operations.
