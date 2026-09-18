# Phase 0 Progress

Date: 2026-09-17

Status: Phase 0 exit gates complete on the documented API 29/API 34 qualification matrix.

## Implemented

- Gradle 9.2.1 wrapper and AGP 9.0.1 project.
- Pure Kotlin shared protocol with bounded binary framing.
- HMAC-SHA-256 host/driver authentication with independent domain MACs.
- Dedicated `com.company.tap.driver` APK and AndroidJUnitRunner instrumentation APK.
- Loopback-only device server and dynamic serial-specific host forwarding.
- Invalid authentication rejection without terminating the server.
- Monotonic request IDs and session/generation checks.
- `HEALTH`, `EXISTS`, `TAP`, `SET_TEXT`, key-event `TYPE_TEXT`, bounded
  `SCROLL_UNTIL`, `WAIT_VISIBLE`, and diagnostic `DUMP_HIERARCHY` operations.
- Raw Compose tag, qualified View resource, and text selectors.
- AUT selectors confined to the expected package and explicit system selectors restricted
  to an instrumentation-supplied package allowlist.
- Mixed View/Compose AUT fixture with `testTagsAsResourceId`, a Compose lazy list, a native
  View list, and separate direct/key-event text inputs.
- One Kotlin host process controlling multiple devices concurrently.
- Independent cleanup, socket cleanup after failed authentication, request-relative host
  socket deadlines, and instrumentation-result verification.
- AUT force-stop, clear-data, relaunch, and driver-survival checks.
- Unsupported key-event text is rejected before mutation; View and Compose scrolling detect
  end-of-content from an unchanged visible accessibility subtree.
- A real camera permission dialog is accepted through a SYSTEM-scoped selector while the
  same dialog remains invisible to an AUT-scoped selector.
- A signature-protected cross-UID synchronization provider exposes app-generated process and
  session identities, generation, busy count, transition time, and sticky error state.
- The host brackets every provider call with PID and `/proc` start-token observations,
  verifies package signatures and provider ownership, requires two stable zero samples for
  idle, and rejects stale identity after clear-data/process restart.
- Machine-wide per-device filesystem leases and fsync/atomic-replace journals persist boot,
  session, generation, port, PID/start token, and driver-instance identity.
- Startup recovers both `CREATING` device-port forwards and a real `ACTIVE` orphan driver plus
  exact host-port forward after lease reacquisition while preserving an explicit unrelated
  forward; corrupt or uncertain state is preserved and durably quarantined.
- Fault validation occupies the first reserved device port from the separate fixture UID and
  proves bounded retry on the next port. An isolated changed-boot journal is durably
  quarantined without removing the forward it references.
- Deterministic transport faults drop authenticated connections before acceptance, after
  acceptance, and after a tap mutation. Transmitted actions report `INDETERMINATE`, poison
  the old client, rebuild under increasing generations, preserve the AUT process identity,
  and prove the uncertain action is never replayed with a process-scoped counter.
- A delegated late-mutation fault blocks the driver after transport loss, durably marks the
  device reset-required, refuses same-boot reuse, force-stops the old driver, and requires a
  changed boot identity plus AUT-state reset before quarantine is released. Interrupted
  resets resume from the journal, non-secure keyguard is dismissed after reboot, and a fresh
  generation proves the late mutation cannot affect the replacement test. Validation also
  resumed a real host-interrupted reset after the boot identity had already changed.
- A dedicated protocol-fencing generation proves a duplicate health request is rejected,
  an old-generation request returns `SESSION_MISMATCH`, and the request ID consumed by that
  rejection cannot be reused. Both API 34 and API 29 completed the full flow afterward.
- A bounded multi-device rendezvous abruptly disconnects one authenticated RPC client while
  the other device completes three additional health requests on its existing session. The
  API 34 emulator was the disconnected participant and the API 29 physical device continued;
  both workers then completed independently.

## Validated devices

| Serial | Device | API | Result |
|---|---|---:|---|
| `emulator-5554` | Android emulator | 34 | Pass |
| `85e49002` | Samsung SM-J810G | 29 | Pass |

Both devices passed the complete flow, and concurrent control from one host process was also
validated. The physical-device run exposed that another active UiAutomation instrumentation
session, such as Maestro's driver, can prevent this driver from registering on older Android.
Competing automation drivers must not run simultaneously on one device; startup must detect
and report this distinctly.

## Initial measurements

The current fixture is small. These numbers validate the benchmark path but do not establish
the final performance claim.

| Device | Direct lookup average, 100 runs | Dump and transfer average, 5 runs |
|---|---:|---:|
| API 34 emulator | 100.4 ms | 120.1 ms |
| API 29 physical | 115.8 ms | 156.7 ms |

The initial fixture benchmark did not include representative dense screens, percentiles,
warm/cold separation, or host XML parse/query time. Those requirements were subsequently
exercised on NextPlayer's Library, Settings, and Song gestures screens; see
[`nextplayer-demo-validation.md`](nextplayer-demo-validation.md). The fixture averages above
remain as the original baseline.

## Remaining Phase 0 gates

- [x] Prove late/uninterruptible mutation quarantine and reset behavior.
- [x] Add same-generation duplicate/stale and old-generation rejection tests.
- [x] Disconnect one device while proving the other session continues.
- [x] Benchmark representative dense screens in the NextPlayer complex demo.
- [x] Produce a dynamic and source-backed accessibility inventory for the demo surfaces.
- [x] Verify `/proc` process-start identity support on the Phase 0 API 29/API 34 matrix.

## Upstream references

- Appium UiAutomator2 server commit
  `4a8139161cbb3aad078e36539995eb734297d56e`.
- Appium UiAutomator2 driver commit
  `7a54db007aa8a44f5df21cd0b52afc13c0d277ae`.
- Maestro commit `01a3e442afba15f80f825492dae2e48fcfd4239c`.

The evidence-linked adopt/adapt/do-not-copy matrix is in
[`upstream-reference-audit.md`](upstream-reference-audit.md).

No upstream source has been copied into the current implementation.
