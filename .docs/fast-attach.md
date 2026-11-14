# Fast attach: fresh sessions without repeated startup overhead

Status: approved scope; implementation started. This is the work plan for G41 in the
competitor comparison (PR #61). The comparison is a separate, unmerged report; this record
stands on its own. One branch/PR owns the work; do not split the stages into stacked PRs.

## Goal and boundaries

Reduce the complete attach + detach cost while retaining per-test cleanup and exclusive
ownership. Reuse a proven-clean instrumentation process, not a test session. Every attachment
still takes the serial journal lock, advances the generation, gets fresh credentials, restores
Tap-managed changes on detach, and releases the device. An idle runtime is not a reservation.

Do not obtain speed by sharing attachments implicitly, skipping restoration, trusting cached
APK versions indefinitely, omitting process/boot identity checks, replaying accepted input, or
removing quarantine. App login/data/navigation reset is outside this task, as is synchronization.
Python's existing class/module/session device scope, JUnit's
`@TapTest(deviceLifetime = PER_CLASS)` and held connections remain different: they retain the
entire attachment, including ownership and changed settings.

The owner approved investigating bounded instrumentation reuse and the related optimizations
in this conversation. This deliberately revises the upstream audit's rejection of a persistent
agent only for a daemon-managed, bounded, clean idle runtime. The audit and pool decision record
must be amended with the implemented ownership/recovery contract before runtime reuse ships.
The currently implemented driver remains per-session until those stages are complete.

## Phone baseline (2026-11-14)

Samsung SM-J810G, serial `85e49002`, API 29, USB ADB. Freshly built JVM daemon, Python client,
one observed client connection. Five sequential attach -> device info -> detach cycles, with
default driver compatibility checks. Daemon startup, build time and the info read are excluded
from the lifecycle figures. No app launch, clear-data or reboot.

| Cycle | Attach (s) | Detach (s) | Combined (s) |
|---|---:|---:|---:|
| 1 | 4.868 | 0.462 | 5.330 |
| 2 | 4.361 | 0.482 | 4.844 |
| 3 | 4.383 | 0.446 | 4.829 |
| 4 | 4.351 | 0.441 | 4.792 |
| 5 | 4.405 | 0.433 | 4.838 |
| Median | 4.383 | 0.446 | 4.838 |

A separate traced run measured instrumentation launch -> `TAP_READY` at 2.041–2.067 s in
steady state (median 2.056 s). This is a potentially avoidable interval, not demonstrated warm
reuse savings: reset/reconnect cost remains unknown, and authentication/health follow readiness.
Other traced costs: unconditional wake input 0.76–0.79 s, keyguard dismissal about 0.06 s,
both installed APK digest checks about 0.30 s combined, plus recovery, process identity,
forwarding, authentication/health and registration.

The first traced attach took 21.711 s because the installed test APK differed. The normal
installation path installed both APKs in 16.565 s total. Later cycles did not reinstall. Keep
this deployment cost separate from steady-state lifecycle timings. The tracing wrapper adds
some overhead; use the untraced run for overall totals. Both runs detached successfully,
stopped their daemons and left no driver process/forward. Rotation settings were unchanged.
Raw local evidence: `/tmp/tap-g41-bench-1794639837/` (temporary, not a durable dependency).

At the median, 100 independent tests incur about 8.1 minutes of lifecycle overhead. The
2.06 s launch interval represents about 3.4 minutes before subtracting reset/reconnect cost.
A 2–3 s combined lifecycle is an investigation target, not a promised result or a gate that
justifies weakening correctness.

## Implementation sequence

### 1. Observe and reduce low-risk overhead

- [x] Record monotonic timings for journal recovery, screen preparation, APK check/install,
  instrumentation readiness, forward/identity/authentication/health, and detach cleanup.
  Record durations/outcomes only; never secrets, authentication frames or raw commands.
- [x] Skip the wake key only on positive evidence that the display is already awake. Unknown
  or unfamiliar OEM output falls back to today's wake path. Preserve insecure-keyguard
  dismissal; never bypass a secure lock. Measure the cost of the check too.
- [ ] Reduce read-only ADB round trips where useful without weakening APK digest validation.
  Any grouping must retain per-package errors, split APK behavior and compatibility with
  devices without a usable device-side hash command.
- [x] Add a repeatable benchmark that reports individual cycles, medians/ranges, failures and
  deployment outliers. Report complete attach/detach, not just the improved substage.

The initial implementation logs host-core stages through the daemon's existing log sink;
logging failures cannot change resource ownership or replace cancellation. It does not log
commands, credentials or exception messages. `attach_total` includes the core open stages,
not daemon registration or the entire client RPC. Cleanup stages are separate; benchmark
client-side detach for the complete teardown cost.

Screen preparation uses `dumpsys -t 1 power`: all reported wakefulness states must be `Awake`,
all reported transition flags must be false, and a truncated/timed-out dump is not evidence.
The check is never cached; unknown output and nonzero status send the existing wake key.
Keyguard dismissal, cold-start recovery, APK hashing and driver shutdown are unchanged.

Regression coverage: `ScreenPreparationTest` (awake, asleep/dozing/dreaming, grouped displays,
unknown/partial/failed reports, cancellation and named-serial calls), `SessionLifecycleTimingsTest`
and `DeviceSessionTest` (including a throwing log sink), plus Python unit tests of the benchmark's
cleanup/failure/outlier behavior. `test_attachment_lifecycle.py` exercises reattachment from both
awake and sleeping states on each requested serial.

Reproduce from a checkout with the Python SDK installed in the selected interpreter and an
explicitly started, matching daemon:

```sh
# Build/start the daemon as usual; the benchmark never starts or stops one.
PYTHONPATH=clients/python .venv/bin/python clients/python/scripts/benchmark_attach.py \\
  --serial 85e49002 --cycles 5 --output /tmp/attach-results.json
```

Use an idle device. The script refuses busy, quarantined and offline devices. Normal attach
can update driver APKs and wake/dismiss an insecure keyguard. Every sample is retained, even
an installation outlier; prime a build separately when comparing steady-state cycles. A read
failure still detaches, a cleanup failure fails the run, and no retries mask a failed cycle.

### First implementation evidence (2026-11-14)

A fresh comparison used the same parent (`05f64011`, before these changes), current Python
benchmark/client, JVM daemon, USB phone and default install checks. Each build had a separate
one-cycle deployment/priming run, followed by five measured cycles; all five measured samples
are included. No app launch or reboot. This measures the wake optimization and diagnostics,
not instrumentation reuse (which is still unimplemented).

| Median on Samsung API 29 | Parent | Initial improvement |
|---|---:|---:|
| Attach | 4.394 s | 3.673 s |
| Detach | 0.441 s | 0.431 s |
| Complete lifecycle | 4.819 s | 4.101 s |

The combined median improved by 0.719 s, about 15%, or about 72 seconds per 100 tests.
After-change combined range: 4.085–4.365 s; parent range: 4.752–5.357 s. These are small local
samples, not a native-image or broad device performance claim. The new phone stage logs show
screen preparation at 125 ms median, instrumentation at 2125 ms, recovery at 270 ms, APK checks
at 318 ms, authentication at 229 ms and health at 130 ms. Runtime startup is still the main cost.

Emulator API 34: five after-change cycles completed (combined median 1.763 s, range
1.581–2.218 s); no emulator before/after saving is claimed. The two new device regression cases
passed on **both serials**, testing awake and sleeping reattachment. Settings checked on the
phone stayed at their previous rotation values; all sessions detached, test daemons stopped,
and neither device retained a driver process or forward.

Checks: host core **207 tests**, daemon **118 tests**, Python unit **199 tests**, all passing;
new targeted device cases **2 passed**, each iterating over API 29/API 34. The timings-only
commit also passed core/daemon tests independently. Kotlin compilation used
`-Pkotlin.compiler.execution.strategy=in-process` because this machine's Kotlin daemon startup
receives an invalid `-autoshutdownIdleSeconds=300` JVM option; no project build configuration
was changed to mask it. LSP diagnostics could not confirm Kotlin coverage, so Gradle compilation
and JVM tests are the type/check evidence. Local raw results: `/tmp/tap-fast-attach-comparison/`.
The full device matrix, native-image smoke, grouped APK reads and runtime reuse remain pending.

### 2. Prove same-process isolation before building a cache

- [ ] Prototype clean session end -> UiAutomation disconnect/reset -> restore -> reconnect in
  the same instrumentation. Inspect the pinned AndroidX source for singleton/cached state
  before assuming `UiDevice` survives reconnect correctly.
- [ ] Audit everything currently cleaned by process death: rotation snapshot, mock providers,
  shell permission adoption, listeners, event/toast/notification buffers, pipeline queues,
  background work and cancellation. Do not extend/redesign the deferred sync path.
- [ ] Measure reset/reconnect and compare full lifecycles on API 29/API 34. If safe reuse
  offers no material saving, stop at the low-risk improvements and document that evidence;
  do not mark G41 complete or introduce a cache merely to meet the plan.

An initial API check reinforces why this needs a prototype: the public compileSdk 36
`UiAutomation` surface exposes neither `disconnect()` nor `destroy()`. Android's fixed
`android-9.0.0_r1` and `android-14.0.0_r1` `Instrumentation` sources reconnect when
`getUiAutomation(flags)` changes flags, but that is not an unconnected idle state.
`UiAutomationConnection` stores/restores rotation around connect/disconnect. Do not treat a
flags toggle (including `FLAG_DONT_USE_ACCESSIBILITY`) as proof of safe idle cleanup or use
hidden APIs silently; baseline restoration after final runtime exit still needs device evidence.

### 3. Separate runtime and session ownership

- [ ] A runtime owns instrumentation, process identity, compatible build and authenticated
  lifecycle control. A session owns the serial lock, generation, fresh credentials, pipeline
  and restoration journal. Runtime credentials cannot be session credentials.
- [ ] Add authenticated begin/end-session control. Only clean, acknowledged drain/reset can
  leave an idle runtime. Unexpected transport loss, poison, late mutation or uncertain cleanup
  disqualifies reuse; preserve existing `INDETERMINATE` and quarantine behavior.
- [ ] Keep idle retention bounded. Idle runtimes accept no test commands and hold no device
  lock. Attach, eviction and shutdown acquire the serial lock and revalidate runtime identity
  before changing anything. A stale eviction timer must not terminate a newer session.
- [ ] Journal runtime identity/ownership so another process can safely invalidate it. No
  cross-daemon adoption initially. Verify boot, process start token, instance and APK builds.
  A stale/foreign/incompatible runtime takes the proven cold recovery path.
- [ ] Optimize redundant recovery only after establishing this proof; `CLOSED` alone is not
  permission to skip checking for a driver another process could have started.

### 4. API, integration and evidence

Proposed attachment policy (not implemented): `AUTO` (verified reuse, otherwise cold) and
`COLD` (force fresh instrumentation under the lock). An unspecified gRPC value means the
daemon default. Python: `attach_device(serial, driver_start="cold")`; Kotlin:
`attachDevice(serial, driverStart = DriverStart.COLD)`. The default call remains unchanged.
`skip_driver_install` is separate from runtime policy. No public runtime handles, new lease
RPCs, implicit session resume or mutation retry. Reuse starts opt-in behind an experimental
daemon setting; promote it only after isolation and recovery validation.

- [ ] Specify runtime controls, attach policy, timing diagnostics and cold/reused reason in the
  protocol/server records; do not publish API fields before their behavior exists.
- [ ] Update both SDKs, runner/agent surfaces where applicable, public guides and generated
  Python stubs in the same change as their API. Preserve `clients -> contracts` layering.
- [ ] Exercise stale generations/credentials, concurrent opens, lock handoff, cancelled begin/end,
  eviction races, daemon death, foreign runtime, APK replacement, reboot identity and poison.
- [ ] Exercise per-session state isolation and restoration, including rotation changed between
  sessions; a final runtime exit must not restore an earlier test's rotation snapshot.
- [ ] Run relevant JVM/protocol/client tests and the requested no-reboot API 29/API 34 matrix.
  Re-run the phone benchmark under the same conditions and record before/after results here.
  No reboot/reset-required tests without separate approval.

## References

- Current lifecycle: `host/core/.../DeviceSession.kt`, `DriverLifecycle.kt`, `Adb.kt`;
  `device/driver/core/.../TapDriverServer.kt`; `host/daemon/.../TapDaemon.kt`.
- `.docs/pool-and-leases.md`, `.docs/protocol-contract.md`, `.docs/upstream-reference-audit.md`.
- AOSP fixed tags `android-9.0.0_r1` and `android-14.0.0_r1`,
  `frameworks/base/core/java/android/app/{Instrumentation,UiAutomationConnection}.java`:
  public flags reconnect and platform-owned rotation restoration.
- agent-device, pinned `0ebd2540a33c674194d59357a48287629b8377a9`,
  `android/snapshot-helper/.../SnapshotInstrumentation.java`: long-lived instrumentation
  accepts successive socket commands. Adapt only lifecycle ideas, not its selector model.
- openatx/uiautomator2, pinned `657c5d791075945cc21e78e125b220461c8ae99c`,
  `uiautomator2/core.py`: probe health before starting the service. Do not copy reconnect-and-
  retry for transmitted mutations. No source code copied.
