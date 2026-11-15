# Fast attach: fresh sessions without repeated startup overhead

Status: implemented and verified on the targeted isolation/native/client checks; PR stays draft
with pre-existing full-matrix failures documented, as the owner requested. This is the record for G41 in the competitor comparison (PR #61). The comparison
is a separate, unmerged report; this record stands on its own. One branch/PR owns the work.

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
now include this lifecycle exception. Reuse remains opt-in: the default still starts/stops
instrumentation per attachment. Retaining a process never retains the attachment or its lock.

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
steady state (median 2.056 s). At this baseline checkpoint the reset/reconnect cost was unknown;
this interval alone was not demonstrated warm savings. Authentication/health follow readiness.
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
- [x] Reduce read-only ADB round trips where useful without weakening APK digest validation.
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
This first stage leaves keyguard dismissal, cold recovery, APK hashing and shutdown unchanged.

Regression coverage: `ScreenPreparationTest` (awake, asleep/dozing/dreaming, grouped displays,
unknown/partial/failed reports, cancellation and named-serial calls), `SessionLifecycleTimingsTest`
and `DeviceSessionTest` (including a throwing log sink), plus Python unit tests of the benchmark's
cleanup/failure/outlier behavior. `test_attachment_lifecycle.py` exercises reattachment from both
awake and sleeping states on each requested serial.

Reproduce from a checkout with the Python SDK installed in the selected interpreter and an
explicitly started, matching daemon:

```sh
# Use a matching daemon explicitly started with reuse enabled; do not replace a shared one.
tap start --driver-idle-timeout-ms 60000
PYTHONPATH=clients/python .venv/bin/python clients/python/scripts/benchmark_attach.py --serial 85e49002 --driver-start cold --cycles 5 --output /tmp/attach-cold.json
PYTHONPATH=clients/python .venv/bin/python clients/python/scripts/benchmark_attach.py --serial 85e49002 --driver-start auto --cycles 1 --output /tmp/attach-prime.json
PYTHONPATH=clients/python .venv/bin/python clients/python/scripts/benchmark_attach.py --serial 85e49002 --driver-start auto --cycles 5 --output /tmp/attach-warm.json
```

Use an idle device. The script refuses busy, quarantined and offline devices. Normal attach
can update driver APKs and wake/dismiss an insecure keyguard. Every sample is retained, even
an installation outlier; prime a build separately when comparing steady-state cycles. Every row
records the generation, requested policy and actual `driver_reused`; AUTO alone is not evidence
of reuse. `start` cannot reconfigure an already-running daemon. Stop only a daemon you started.
A read failure still detaches, cleanup failure fails the run, and no retries mask failed cycles.

### First implementation evidence (2026-11-14)

A fresh comparison used the same parent (`05f64011`, before these changes), current Python
benchmark/client, JVM daemon, USB phone and default install checks. Each build had a separate
one-cycle deployment/priming run, followed by five measured cycles; all five measured samples
are included. No app launch or reboot. This measures the wake optimization and diagnostics,
not instrumentation reuse (which was not implemented at that checkpoint).

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
At that checkpoint the full matrix, native smoke, grouped APK reads and runtime reuse were pending.

### 2. Prove same-process isolation before building a cache

- [x] Prototype clean session end -> UiAutomation disconnect/reset -> restore -> reconnect in
  the same instrumentation. Inspect the pinned AndroidX source for singleton/cached state
  before assuming `UiDevice` survives reconnect correctly.
- [x] Audit everything currently cleaned by process death: rotation snapshot, mock providers,
  shell permission adoption, listeners, event/toast/notification buffers, pipeline queues,
  background work and cancellation. Do not extend/redesign the deferred sync path.
- [x] Measure reset/reconnect and compare full lifecycles on API 29/API 34. If safe reuse
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

- [x] Separate instrumentation/process identity from the session's lock, generation, fresh
  credentials, pipeline and restoration journal. There is no persistent management credential:
  the authenticated current session may provision exactly one fresh next session at clean end.
- [x] Add authenticated session handoff. Only clean, acknowledged drain/reset can
  leave an idle runtime. Unexpected transport loss, poison, late mutation or uncertain cleanup
  disqualifies reuse; preserve existing `INDETERMINATE` and quarantine behavior.
- [x] Keep idle retention bounded. Idle runtimes accept no test commands and hold no device
  lock. Attach, eviction and shutdown acquire the serial lock and revalidate runtime identity
  before changing anything. A stale eviction timer must not terminate a newer session.
- [x] Journal runtime identity/ownership so another process can safely invalidate it. No
  cross-daemon adoption initially. Verify boot, process start token, instance and APK builds.
  A stale/foreign/incompatible runtime takes the proven cold recovery path.
- [x] Optimize redundant recovery only after establishing this proof; `CLOSED` alone is not
  permission to skip checking for a driver another process could have started.

### 4. API, integration and evidence

Implemented attachment policy: `AUTO` (verified reuse, otherwise cold) and `COLD` (fresh
instrumentation under the lock, stopped on detach). An unspecified gRPC value means the
daemon default. Python: `attach_device(serial, driver_start="cold")`; Kotlin:
`attachDevice(serial, DeviceOptions(driverStart = DriverStart.COLD))`. Default calls are unchanged.
`skip_driver_install` is separate from runtime policy. No public runtime handles, new lease
RPCs, implicit session resume or mutation retry. Reuse starts opt-in behind an experimental
daemon setting; promote it only after isolation and recovery validation.

- [x] Specify runtime controls, attach policy, timing diagnostics and cold/reused provenance in the
  protocol/server records; do not publish API fields before their behavior exists.
- [x] Update both SDKs, runner/agent surfaces where applicable, public guides and generated
  Python stubs in the same change as their API. Preserve `clients -> contracts` layering.
- [x] Exercise stale generations/credentials, concurrent opens, lock handoff, cancelled begin/end,
  eviction races, daemon death, foreign runtime, APK replacement, reboot identity and poison.
- [x] Exercise per-session state isolation and restoration, including rotation changed between
  sessions; a final runtime exit must not restore an earlier test's rotation snapshot.
- [ ] Obtain a green complete no-reboot API 29/API 34 matrix. JVM/protocol/client checks,
  targeted isolation and benchmarks passed; the full run and parent comparison below identify
  two pre-existing emulator failures. The owner chose to document them and finish the draft,
  not expand this change to fix them. No reboot/reset-required tests were run.

## Implemented lifecycle and safety boundary

- Daemon setting `--driver-idle-timeout-ms`: default 0/off, or 1000..300000 ms. AUTO reuses
  only this daemon's cache. COLD, inspection, startup/fault overrides and any synchronization
  use do not retain a process. Installation policy remains independent.
- Protocol 5.2 negotiates `runtime.park.v1` (5.1 still works cold). Authenticated ID-zero
  `PARK_RUNTIME` provisions a different session id, generation + 1 and different 32-byte
  secret. `RUNTIME_PARKED` acknowledges that exact identity only after command/watchdog
  threads and mock-location emission stop, listeners/shell identity drop, UiAutomation is
  destroyed and pinned AndroidX 2.4.0's `UiDevice.sInstance` is cleared. No hidden-API
  enforcement is disabled. If the narrow reflected bridge is unavailable, cleanup is cold.
- Fresh connection/pipeline/engine/event buffers are constructed for the next authenticated
  session. Old transport is permanently closed, old credentials fail, and request ids restart
  inside the new generation. No persistent node handles, app reset or mutation replay is added.
- The host restores settings under its existing serial lock before recording an empty
  `CLOSED(runtimeIdle=true)` journal and releasing ownership. Only a complete, unchanged,
  explicitly unfrozen rotation baseline with no rotation command can use readback alone.
  Frozen/unknown/touched baselines use full stable restoration: the platform's frozen physical
  snapshot can differ from `USER_ROTATION` under an app orientation request.
- Idle credentials stay in memory; the journal stores exact boot, PID/start token, instance and
  forward identity, not secrets or ownership reservations. A changed rotation baseline, build,
  boot, PID, journal or forward refuses reuse. Warm pre-exposure authentication/health failure
  may cold-open once in a new generation; it never repeats client input.
- The device enforces an absolute parked deadline even when an unauthenticated peer trickles
  bytes. Host timers use the remaining lifetime, not a new interval after restoration. Eviction,
  doctor and shutdown use the serial lock and exact journal/process evidence; a foreign active
  owner wins. If cleanup cannot acquire the lock, it does not interfere; device expiry and
  ordinary identity-checked journal recovery remain the fallback. Unproven cleanup quarantines.
- Failed/cancelled startup restores its captured rotation baseline after verified termination,
  before releasing the lease. Failed readback keeps that baseline quarantined, never a clean
  idle entry. A native cancelled warm RPC exercised this pre-exposure rollback and reattachment.
- Native images install exit handlers: SIGTERM must execute the bounded shutdown hook, not just
  exit. CI/release smoke checks owned-descriptor removal; the device probe checks both an active
  session's changed font scale and a second device's idle process/forward cleanup.
- Deferred synchronization was not redesigned. Its session-used gate refuses parking.

Initial JVM phone smoke: after one retained deployment outlier, four genuinely reused cycles
(generations 294..297) took 2.117, 2.239, 2.215 and 2.143 s combined (median 2.179 s), about 55%
below the 4.819 s parent median. Attach was 1.719..1.740 s and detach 0.382..0.520 s. Explicit
COLD passed too. This is preliminary local evidence, not the final native comparison.

### Final native comparison and verification (2026-11-15)

Same native image/client and enabled 60 s cache, separate explicit one-cycle primes, then five
COLD and five AUTO samples per device. All five samples in each measured set are retained;
AUTO samples all report `driver_reused=true` and distinct increasing generations. These are
client-observed attach + detach times, excluding the intervening info read and daemon startup.
Both devices had auto-rotate enabled; frozen baselines still pay full stable restoration.

| Native median | Phone API 29 COLD | Phone reused | Emulator API 34 COLD | Emulator reused |
|---|---:|---:|---:|---:|
| Attach | 3.793 s | 1.734 s | 1.428 s | 1.083 s |
| Detach | 0.466 s | 0.411 s | 0.233 s | 0.244 s |
| Complete lifecycle | 4.266 s | 2.163 s | 1.639 s | 1.360 s |

Paired native savings: **49% on the phone**, **17% on the emulator**. Combined ranges: phone
COLD 4.235..4.306 s / reused 2.062..2.165 s; emulator COLD 1.611..2.193 s / reused
1.298..1.436 s. Medians are computed per cycle, not by adding separate attach/detach medians.
The phone's 2.163 s is about 55% below the original 4.819 s JVM parent measurement; that last
comparison spans JVM/native and is not a controlled native-parent experiment. These are small
local samples, not a broad device performance guarantee.

Passing checks:

- JVM: host core **223**, daemon **119**, protocol **63**, command engine **32**, Kotlin SDK
  **111**, JUnit adapter **49** (597 total). Grouped-APK commit also passed core/daemon tests
  independently. Failed reusable startup tests cover both successful baseline restoration and
  failed readback retaining quarantine.
- Direct reuse matrix: **8/8** (four cases on each API), including old authentication/generation
  rejection, request-id reset, font/rotation restoration, no surviving mock emitter, COLD,
  foreign-active-owner eviction, poison, baseline changes and final exit under a new lock.
  The trickled-handshake test holds the host lease so host eviction cannot mask a missing
  device-owned absolute deadline.
- Native: exact bundled APK hashes on both devices; replacement APK forces cold then permits
  verified warm reuse again; SIGKILL of an owned, idle-only daemon forces cold recovery after
  restart, then warm reuse. A cancelled warm native attachment restores an empty CLOSED journal
  and permits fresh reattachment. Final SIGTERM restores an attached phone's changed font
  scale, stops the emulator's idle runtime, leaves neither driver/forward and removes the
  descriptor. The no-device CI RPC smoke and descriptor cleanup also pass.
- Live clients on the native daemon with reuse enabled: Python **237 passed, 1 skipped**;
  Kotlin/JUnit fixture suite **42 passed, 2 skipped**. The initial outer cleanup probe exposed
  the missing native signal handler; it was fixed and the final active/idle cleanup probe passed.
- Python/agent regressions **444 passed, 39 skipped** without named-device execution; Studio
  backend **614 passed**, page **181 passed**, page build passed. Both Python and Studio
  generated-code checks, buf lint/breaking, configured agent Pyright and strict public docs
  (HTML and Markdown bundle) passed. Kotlin LSP remains inconclusive; compilation is its check.

Full-matrix caveat (not waived into a passing result): the first run's emulator was stopped;
the owner approved restarting that existing AVD without wiping it or touching the other VM.
The next broad run passed all phone cases but had two emulator UI assertions plus a transient
startup failure and its quarantine-dependent failures. Startup/toast/transport cases passed
isolated retries. `InsetWindowTest` (corner tap) and `NotificationListenerTest` (notification
not seen) **both reproduce on untouched parent `05f64011`**, using isolated journal directories;
the inset case also passes an isolated current-branch retry. The owner chose to finish the
**draft** with those baseline failures documented instead of fixing unrelated behavior here.
The full-matrix checkbox remains open. No device reboot or reset-required test was run.

Use matching binaries and stop the current daemon cleanly before switching versions. Older
hosts that do not understand an idle journal's `runtimeIdle` field fail closed rather than
adopt it; the parent comparison therefore uses its own journal directory.

Raw local evidence (temporary, not a durable dependency): `/tmp/tap-runtime-probe/`, especially
`final-shutdown-jvm-native.log`, `isolation-and-affected-matrix.log`, `native-final-report.json`,
`native-*-final-{cold,warm}.json`, `native-python-device-matrix.log`,
`native-kotlin-fixture-matrix.log`, `original-parent-emulator-comparison.log` and
`public-docs-build-final.log`. Final teardown verified original rotation/font values and no
Tap driver process or owned forward on either device; the explicitly restarted AVD stays online.

## Attachment-tail investigation (2026-11-15)

Duration-only daemon stages now cover `daemon_attach_clock`, `daemon_attach_register`,
`daemon_attach_initial_info` and `daemon_attach_total` (the service body, excluding transport).
`AttachmentTimingsTest` proves return/exception/cancellation preservation and throwing-log
isolation. After rebasing on main's device-log work, `daemon_attach_log_capture` also covers
capture startup. These supplement, rather than nest into the sum of, host-core `attach_total`.

Five native warm cycles per device, with a separate prime and actual reuse verified:

| Median stage | Phone API 29 | Emulator API 34 |
|---|---:|---:|
| Core open | 1221 ms | 618 ms |
| Device clock (`adb shell date +%s`) | 53 ms | 23 ms |
| Registration | <1 ms | <1 ms |
| Initial full `DeviceInfo` | 459 ms | 430 ms |
| Complete daemon attach body | 1734 ms | 1072 ms |
| Client elapsed minus daemon body | 1.7 ms | 1.6 ms |

The missing half-second is **not gRPC overhead**. Temporary per-getter instrumentation on
both devices located most of the first info query in **`UiDevice.getDisplayRotation()`**:
phone median 395 ms (range 171..404), emulator median 398 ms (366..424). The immediately
following info query's rotation getter took 1 ms / 0 ms. Other getter costs were small;
current-package lookup was 49 ms / 2 ms during those first warm queries.

Pinned AndroidX 2.4.0 source explains it: `UiDevice.getDisplayRotation(int)` calls
`waitForIdle()` before `Display.getRotation()`. `QueryController.waitForIdle()` requests a
**500 ms accessibility-quiet window**, with Tap's configured 1000 ms overall cap. Recent
accessibility events after reconnect make the first metadata read wait out the remaining
quiet interval. `UiDevice.getCurrentPackageName()` also calls this wait; rotation normally
pays it first, leaving the package read already quiet. Removing only the rotation wrapper
would therefore risk moving the delay into package lookup rather than removing it.

That investigation proposed fresh, non-idling platform metadata reads specifically for
`DeviceInfo`, retaining nullable package semantics and recycling any temporary node. It did
not change query behavior. The separately approved implementation and A/B follow below;
selector idle behavior, explicit rotation settling/restoration and deferred synchronization
remain unchanged.

The per-getter device logger was temporary and removed after profiling. Only safe daemon
stage diagnostics and their tests remain. The tables above were measured before main's new
device-log APIs landed. A clean-driver probe after the rebase (with default log capture enabled)
confirmed the same diagnosis: phone/emulator initial info 414/387 ms, clock 54/37 ms,
registration and log-capture startup <1 ms, client overhead 1.5/1.4 ms. All five warm samples
per device genuinely reused instrumentation. Post-rebase checks: 652 JVM tests, 474
Python/agent passes (39 unnamed-device skips), 614 Studio backend tests, 181 page tests,
page build, generated bindings and buf lint/breaking passed. The existing field 10 remains
`capture_logs`; `driver_start` uses field 11. New bindings were regenerated, not hand-merged. Evidence: `/tmp/tap-runtime-probe/tail-coarse/`,
`tail-detail/`, `device-info-phone.log`, `device-info-emulator.log` and the final clean-driver
profiling run. Every owned daemon was stopped; state and process/forward cleanup were checked.

## Non-idling device metadata (2026-11-15)

The owner approved removing the diagnosed wait and testing both performance and regressions.
`DeviceMetadataReader` now reads default-display rotation through `DisplayManager` and current
package from one fresh `UiAutomation.rootInActiveWindow`. Each node is recycled in `finally`,
and only the copied package string escapes. Missing root/package means unknown, not an idle
wait or root retry. `QueryCommands.deviceInfo` alone uses this reader; global UiAutomator
configuration, selectors, mutations, explicit rotation settle/restore and sync are untouched.
Public artifact/model documentation now states the snapshot/nullable-package behavior.

Native A/B used the same phone and emulator, enabled 60 s reuse and default device-log capture.
Each version had a separate prime then five measured warm cycles, all retained and genuinely
reused. No fixture launch or reset was included in the benchmark. Each owned daemon was stopped
and original rotation/font values and absence of driver processes/forwards were verified.

| Median | Phone before | Phone after | Emulator before | Emulator after |
|---|---:|---:|---:|---:|
| Client attach | 1.735 s | 1.265 s | 1.148 s | 0.707 s |
| Client detach | 0.408 s | 0.403 s | 0.245 s | 0.248 s |
| Complete lifecycle | 2.142 s | 1.683 s | 1.404 s | 0.956 s |
| Daemon initial info | 459 ms | 34 ms | 431 ms | 4 ms |

Measured complete-lifecycle saving: **21% phone**, **32% emulator**, approximately 450 ms per
cycle. As before, combined medians are per-cycle sums, not sums of component medians; these
are small local samples, not broad performance guarantees.

Verification:

- After rebasing onto `4104e840` (app-death reporting and foreign-journal handling), **735 JVM
  checks pass** across host core/daemon, protocol, command engine, Kotlin SDK/JUnit and Android
  driver core. The incoming foreign-journal tests now use `futureEngineField` rather than
  `runtimeIdle`, which is a known, validated field in this branch. No production journal
  compatibility rule was weakened. Python/agent unit suites: **493 passed, 4 skipped**;
  protobuf regeneration checks, buf lint/breaking and HTML/Markdown documentation builds pass.
- Driver-core JVM suite **66/66**, including six new cases for fresh rotation/package reads,
  absent root/package, exactly-once recycling on success/failure, and copying mutable package
  text. Driver-core lint passes with three existing unrelated
  warnings; its intentional optional reset bridge now has a narrow static `BlockedPrivateApi`
  exception with a cold-fallback explanation, not a runtime hidden-API exemption.
- Dedicated metadata device cases **4/4**: the fixture's continuous 100 ms text ticker does
  not block five metadata reads under a 750 ms command budget (emulator 2..22 ms,
  phone 38..64 ms), and metadata tracks fixture → Home → fixture without retaining an old root.
  Repeating all four cases after the final main rebase also passed (emulator 2..10 ms,
  phone 41..81 ms).
  The first test setup incorrectly used a selector while the ticker ran; that unchanged
  selector path stalled before any metadata query. The corrected harness starts the ticker
  and stops only the test fixture without using a busy-screen selector.
- Observation, all four explicit rotations, screen/keyguard reads and runtime-reuse isolation
  passed both APIs in the targeted run. The complete no-reboot run is **88 passed, 7 failed,
  1 skipped (96 total)**: the same parent-reproduced emulator inset/notification assertions
  and the earlier startup-after-locale/quarantine-dependent group. No new metadata assertion
  failed. The startup-only quarantine was inspected (no reset required, PID or saved changes)
  and cleared through `tap unquarantine`, not by editing the journal or rebooting.
- Broader native SDK rerun: Python **248 passed, 1 failed, 1 skipped** (the lifecycle case
  reached the emulator before its existing quarantine was cleared); Kotlin **36 passed,
  7 failed, 1 skipped**, with emulator locale/media restoration and cascading quarantines.
  These are not a green SDK matrix. Normal unquarantine retried restoration before any later
  focused checks; no journal file was edited and no reset was requested.
- The post-rebase native build is blocked by the unchanged new main `AppDeaths.follow`:
  GraalVM CE 21.0.2 reports incompatible `rethrowException` frame states. The same error is
  proven on main's own [CI run 43686978922](https://github.com/NoamCohen48/tap/actions/runs/43686978922).
  `AppDeaths.kt` is identical to main here. The successful native A/B above predates this
  rebase; do not present it as a successful native build of the latest merged main. Agent
  pyright also reports 26 errors in unchanged log argument/test typing, outside the metadata
  and cold-policy edits. Keep the PR draft and do not silently expand into these fixes.
- Final focused live SDK checks use the successfully built JVM daemon because of that native
  compiler blocker: Python attachment lifecycle/main-screen **9/9 passed**; Kotlin main-screen,
  lifecycle and device-replacement/reuse **12/12 passed**. Owned daemon termination, original
  rotation/font values and absence of driver processes/owned forwards were verified after both
  suites. This focused success does not erase the broader failures above.

Evidence: `/tmp/tap-info-no-idle/before/`, `after/`, `build-tests-rerun.log`,
`metadata-device-tests.log`, `full-device-matrix.log` and the live-client logs. No reboot or
reset-required test was run. Pre-existing formatting-only edits are preserved.

## Consolidated attachment probes (2026-11-15)

The next approved optimization consolidates adjacent read-only device probes, not session
ownership or safety checks. Warm verification reads the three rotation baseline settings and
expected PID's kernel stat in one serial-specific shell instead of four. After APK verification
and screen preparation, a second fresh shell reads package PIDs and the expected process's
stat together instead of two calls. It must still name exactly the parked PID with the same
start token before authentication; the authenticated driver instance and health checks remain.
The forward is still checked separately through ADB's host-side forwarding API. Boot identity,
installed APK bytes on every attach, lock ownership, generation/credentials, restoration and
no-mutation-replay rules are unchanged. Cold-start PID observation keeps its existing path.

`AttachmentProbes.kt` gives every part an independent exit status and a fresh random frame
prefix. Truncated/reordered/duplicated frames, invalid statuses, permission errors and malformed
process data cannot masquerade as absence or cached identity. Package arguments remain quoted.
The original single-call PID/stat methods use the same parsers. Cancellation and runner/reap
failures propagate without hidden retry. Regression tests cover these cases, actual generated
shell execution, fake frame text in returned values, and rotation-baseline changes.

The first batching experiment was **slower**: `type printf` on both devices proved it resolves
to `/system/bin/printf`, while `echo` is a shell builtin. Marker emission now uses only builtin
`echo` with fixed safe characters, and the script goes directly to `adb shell` rather than
starting a redundant `sh -c`. The six fixed status/boundary markers in the installed-APK
check also use builtin `echo`; arbitrary path data still uses `printf '%s\\n'` so escaping and
hash verification are unchanged. Existing absence/split/hash-fallback tests keep their original
semantics. The printf-based timing experiment is rejected, not counted as a gain. This is why fewer ADB calls alone is not sufficient performance evidence.

Measurement uses the **JVM** distribution on the same approved API 29/API 34 devices: one
separate prime and five measured genuinely reused cycles per version, with normal device-log
capture. It is a separate paired comparison, not interchangeable with the earlier native
metadata measurements. Early attempts collided with other worktrees' test workers and were
excluded; their locks/processes/forwards were not disturbed. The valid baseline waited for the
existing workers to exit. Each valid run checks original rotation/font and owned cleanup.
The valid baseline and final builtin-marker version retained all five genuinely reused samples:

| Median | Phone before | Phone after | Emulator before | Emulator after |
|---|---:|---:|---:|---:|
| Client attach | 1.272 s | 1.029 s | 0.624 s | 0.541 s |
| Client detach | 0.392 s | 0.405 s | 1.517 s | 1.545 s |
| Complete lifecycle | 1.664 s | 1.440 s | 2.136 s | 2.099 s |
| Runtime verification | 271 ms | 187 ms | 128 ms | 74 ms |
| Installed APK verification | 378 ms | 256 ms | 133 ms | 104 ms |

Phone attach saves **243 ms (19%)**, emulator attach **83 ms (13%)**. Complete lifecycle saves
**224 ms (13.5%)** on the phone and **38 ms (1.8%)** on the emulator. Combined medians remain
per-cycle sums, not sums of component medians. The current emulator JVM detach costs about
1.5 s and is not optimized here; do not compare this lifecycle directly with the older native
measurements or imply detach sped up. Small samples and modest emulator lifecycle differences
are not universal guarantees.

Core/daemon regression suites pass **250 + 161** checks, including 16 added cases. The earlier
batch-only device pass had **13 passed, 1 failed**: the API 29 rotation read immediately after
`DISPLAY_ROTATION_LEFT` reported 0 rather than 1. The final targeted rerun passed **14/14**
(metadata, all explicit rotations, credential/generation fences, mock-emitter reset, COLD mode,
absolute idle timeout and changed rotation baseline, both APIs). This transient is not erased
or silently fixed by changing rotation/metadata/selector behavior.
After rebasing onto `795f668d` (outside-panel taps and in-window coverage), **764 JVM checks**
pass, including driver-core unit tests/lint and both Kotlin modules; Python/agent units pass
**498 + 4 skipped**. Generated Python/Studio checks and buf lint/breaking pass. The measured host
probe code is byte-for-byte unchanged by that rebase; its only driver-engine conflict retained
both main's outside-panel wiring and this branch's existing lifecycle/metadata setup.
Pre-existing formatter-only edits are preserved separately, not folded into this optimization.
Evidence stays under `/tmp/tap-adb-probes/{before,after-final-builtin}/`, unit-build logs and device
regression logs. The existing broad-matrix and main native-build caveats remain.

## Batched rotation restoration readbacks (2026-11-15)

The next approved change batches only rotation-setting observations during cleanup. The
untouched, complete, explicitly unfrozen fast path reads its baseline in one shell; every
full-restore settle poll does the same. A distinct fresh batch verifies rotation again after
settling, rather than trusting the successful poll. Non-rotation readbacks keep their typed
methods. All write ordering, retries of idempotent restore writes, serial locking and saved
state/quarantine handling remain intact.

The stability budget is **unchanged**: 1000 ms stable, 200 ms poll delay, 5000 ms deadline.
Frozen/unknown/touched baselines still require full restore; this does not make them eligible
for the read-only path. Every part of the batch keeps its own status and fresh random framing.
A missing map key, failed read or malformed frame cannot become an absent setting or proof
that restoration succeeded. No synchronization or selector behavior changes.

Nine additional regression cases exercise nullable/partial/fresh readbacks, per-part errors,
invalid inputs and cancellation, the full stable interval, late-writer reapplication, a fresh
final read that catches post-settle drift, and unchanged non-rotation cleanup. Existing
stateful fake restore tests retain their lock/write ordering and failure assertions. The
API 29/API 34 metadata/rotation/reuse device group passes **14/14** without reboot. Final JVM
verification passes **773/773** across host core/daemon, protocol, command engine, driver core,
Kotlin SDK and JUnit; the branch is rebased onto current main.

Initial five-cycle JVM stage medians improved **238 → 188 ms** on the phone and
**1427 → 1318 ms** on the emulator. The first after-run had slower unchanged attach stages
and noisy overall lifecycle timing, so those component savings alone do **not** establish an
end-to-end speedup. A quiet confirmation and a control comparison using prebuilt baseline and
changed JVM distributions (no rebuild between the control runs) also succeeded. Across those
runs, rotation medians were **229..238 ms before vs 185..195 ms after** on the phone and
**1427..1440 ms before vs 1211..1318 ms after** on the emulator. Each run used a separate prime
and five genuinely reused samples, restored original rotation/font, and verified owned cleanup.

For transparency, the final prebuilt control's full client medians were:

| Median | Phone before | Phone after | Emulator before | Emulator after |
|---|---:|---:|---:|---:|
| Attach | 1.418 s | 1.344 s | 0.565 s | 0.619 s |
| Detach | 0.382 s | 0.557 s | 1.589 s | 1.415 s |
| Complete lifecycle | 1.960 s | 1.950 s | 2.245 s | 1.950 s |
| Rotation cleanup | 229 ms | 195 ms | 1440 ms | 1211 ms |

Other cleanup/attach costs varied substantially. The earlier full lifecycles were phone
1.414 → 1.741 s (quiet confirmation 1.525 s), emulator 2.078 → 2.195 s (confirmation 2.184 s).
Therefore this change establishes cheaper rotation observations, **not a reliable overall
lifecycle percentage improvement**. Per-cycle lifecycle medians are not sums of component
medians; no sample was discarded to manufacture a gain. Keep the one-second safety floor.
Evidence: `/tmp/tap-rotation-batch/{before,after,confirmation,control-before,control-after}/`,
`unit-build.log` and `device-tests.log`. Existing broad-matrix/native-build caveats and
pre-existing formatter edits remain separate from this optimization.

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
