# Framework Gaps

Last updated: 2026-09-21

This is the honest delta between what `.docs/android-e2e-framework-implementation-plan.md`
specifies and what the repository implements and proves on devices today. It is the
"what is still missing" companion to `.docs/phase-1-progress.md` (what is done) and
`.docs/project-architecture.md` (what exists and where). Items are grouped by plan section
and tagged with the phase the plan assigns them to. Nothing here is ticked in the progress
docs until it is implemented *and* covered by a JVM test, the device validation flow, or the
sample suite.

## What is usable now

A product team can write and run real tests today:

- `:host:daemon` (`tap serve`) — the one host process: ADB, journals, per-device locks, driver
  lifecycle, `AppLifecycle`, device list; JVM dist or native image.
- `:clients:kotlin:sdk` — `TapClient`/`TapConnection` (owned attach scope), `Device`, `App`,
  `Element`, `ElementWait` (all `suspend` over grpc-kotlin stubs), `tapScope` for scripts,
  selector DSL, typed exceptions; a gRPC client of the server.
- `:clients:kotlin:junit5` — `@TapTest`, mandatory `tapTest { ... }` bridge with per-test root
  job, `Device`/`Devices` parameter injection, named roles, roles mapped to serials and opened
  in serial order, structured `coroutineScope`/`async` fan-out with sibling cancellation,
  `DeviceBarrier` (reusable/one-shot) for simultaneous phases, failure artifacts, `tap.*`
  system-property / `TAP_*` env config.
- `clients/python` — the same API and a pytest plugin.
- `:samples:fixture-tests` — thirteen device tests (single-device journeys, ambiguity, text
  input, Compose list scrolling, app-owned idle sync, explicit screen-stability waits,
  lifecycle, and two two-device tests including sibling-cancellation without replay), plus a
  device-free JUnit discovery guard. The 0.2.0-client suite passed on API 29 and API 34
  concurrently on 2026-09-21.

Everything below is what separates that from the production-grade framework the plan
describes.

## Driver operations (plan §11) — Phase 1 leftovers

| Gap | Impact | Notes |
|---|---|---|
| `session.shutdown` | Sessions end by closing the socket and `am force-stop` of the instrumentation. Works, but the driver never gets a graceful close and the journal cannot distinguish "host asked" from "connection died". | Small; add an operation + a driver-side clean exit. |
| `device.wake` / screen state | Tests assume the screen is on and unlocked. | `UiDevice.wakeUp()` + keyguard dismissal; validation on a device with a lock screen. |
| `inspector.snapshot` | No JSON hierarchy with selector suggestions; `DUMP_HIERARCHY` returns raw UiAutomator XML only. | Phase 5 inspector UI depends on it; failure artifacts use the XML for now. |
| `AUT_CRASHED` / `AUT_ANR` / `AUT_NOT_INSTALLED` emission | Crashes surface as `NOT_FOUND`/`WAIT_TIMEOUT` plus a process-identity change, not as a first-class code. | Requires driver-side process observation (`am`/`dumpsys activity` or `ActivityManager` crash/ANR detection) and a fixture that crashes/ANRs on demand. |
| Multi-touch / pinch, drag, fling | Not exposed. | Plan lists them as demand-driven. |
| Permission dialogs | `App.grantPermission` pre-grants via `pm`; there is no helper to accept/deny a runtime permission dialog that appears mid-test. Selectors can reach the dialog with `inAnyWindow()` or `inPackage(controller)` (`PermissionTest`). | Adopt the Appium UiAutomator2 approach (locate the controller's allow/deny buttons by resource id per API family) behind a fixture (`PermissionActivity` exists). |

## Sync SDK visibility (plan §16)

The driver manifest's `<queries>` lists only the fixture's authorities
(`io.github.noamcohen48.tap.fixture.tap-sync`, `io.github.noamcohen48.tap.fixture.fault`). On API 30+ a product
AUT's `${applicationId}.tap-sync` provider is invisible to the driver, so `App.awaitIdle`
fails with `SYNC_*` details for any app other than the fixture. Options, in order of
preference: `<queries><intent>` with a Tap-specific provider action declared by
`:device:sync-sdk`; `QUERY_ALL_PACKAGES` on the debug-only driver; or generating the driver APK per
AUT. This blocks "idling-integrated journeys" for real products and must be decided before
the CI pilot.

## Host SDK (plan §12–13) — Phase 2 deviations

Delivered 2026-09-21 (client 0.2.0, breaking): the Kotlin SDK is `suspend` throughout
(`TapClient`/`TapConnection`/`Device`/`App`/`Element`/`ElementWait` over grpc-kotlin
`CoroutineStub`s, `delay`-based polling, `tapScope` for scripts) and multi-device fan-out is
structured (`tapTest` + `coroutineScope`/`async`, `DeviceBarrier` for simultaneous phases).
Proving tests: `TapClientTest` (in-process attach lifetime/close ordering, Execute
cancellation, sibling-cancellation shape, scope enforcement), `DeviceBarrierTest`
(release/reuse/one-shot with waiting reset/cancellation), `TapTestBridgeTest`
(binding/nesting incl. child coroutines, root cancellation, accepted-`Execute` sibling
cancellation without replay, duplicate-role rejection, sorted opens, teardown preservation
incl. interrupted-`tapTest` → `AfterEach` closing every device), `ConnectionMemoTest`
(generations, reopen, shutdown-vs-connection race), and the
device fixture `MultiDeviceTest.siblingFailureCancelsWaitWithoutReplay` (failing sibling
cancels the other's in-flight wait without replaying the pre-scope mutation; the accepted-
`Execute` half is proven in-process, the prompt-cancel/no-replay half on hardware).

| Gap | Impact | Notes |
|---|---|---|
| `WaitOptions.stableFor` | Host-polled waits (`enabled`, `textEquals`, `count`, …) have `timeout` and `pollInterval` only; no "condition held for N ms". | Small. |
| Wait diagnostics | `WaitTimeoutException` carries description, serial, selector, elapsed, poll count and last observation; it does not attach a bounded hierarchy/screenshot snapshot at timeout. | The JUnit extension captures those on failure, so the information exists per test but not per wait. |
| `Element.getProperty` | Covered by `snapshot()`; there is no single-property accessor beyond `text/isEnabled/isChecked`. | Convenience only. |
| Device constraints | The daemon leases nothing (decided 2026-09-20, `pool-and-leases.md`: exclusive use is the per-serial journal lock a device session holds; roles and constraints are a client concern). Clients map roles to `tap.serials`, pins, or the inventory; no API-range/emulator/model/locale/orientation filtering exists in any client. The earlier daemon-side matcher and lease table are archived under `archive/pool-roles/` and `archive/pool-leases/`. | Client-side selection (`getprop` per serial, or `DeviceInfo` after open); a `@TapDevice(minApi = …)` style annotation. |
| Remaining fake-ADB lifecycle coverage | `AdbTest` (17), `DeviceSessionTest` (20), `DriverClientTest` (27), `SessionJournalTest` (6) and `TapDaemonLifecycleTest` (12) now cover typed ADB parsing/process ownership, session open/cleanup/quarantine, transport races and daemon lifecycle with fakes. Dedicated `AppLifecycle` failure/postcondition tests and artifact-capture failure paths remain. | Highest-value remaining JVM testing gap; extend the existing `FakeAdb` rather than introducing another abstraction. |
| Localization / text normalisation | `text(...)` is exact and case-sensitive; Material buttons expose all-caps accessibility text, so `text("Sign in")` misses `SIGN IN`. | Document (done in the samples) or add a case-insensitive match mode. |

## JUnit 5 integration (plan §18) — Phase 3

Implemented: `BeforeEachCallback` (per-test root job + sorted-serial opens), `AfterEachCallback`
(cancel root, capture artifacts while live when possible, bounded non-cancellable teardown),
`ParameterResolver`, `TestExecutionExceptionHandler`, `InvocationInterceptor` (binds the
`tapTest` context; records user lifecycle failures), all-or-none acquisition with timeout,
per-method store, failure artifacts, cleanup failures preserved as secondary and rethrown only
for passing tests, missing-devices → assumption (skipped, not failed). Duplicate serials
across pins/assignment are rejected before any session opens. `tapTest` is mandatory
for suspending calls (nesting rejected, including from child coroutines); `Device` access
outside it fails with `TapUsageException`, so plain
metadata-only tests stay possible. JUnit timeout/interruption cancels the root job and is
consumed so `AfterEach` teardown still closes every device; sibling
failure in `coroutineScope`/`async` cancels the other's in-flight RPC (`DeviceBarrier` for
simultaneous phases).

Missing:

- `ExecutionCondition` for static prerequisites (API range, physical-only, tags) without
  reserving devices.
- Per-test deadline: `tapTest` runs on the root job so JUnit `@Timeout`/interruption cancels
  in-flight RPCs, but there is no extension-owned wall-clock deadline beyond that; a hung
  test still relies on JUnit's own `@Timeout` and the driver watchdog.
- `LifecycleMethodExecutionExceptionHandler` / `TestWatcher` / run-level
  `TestExecutionListener`; failures in user `@BeforeEach`/`@AfterEach` are not captured
  with device artifacts.
- `CloseableResource` fallback when another extension aborts callback execution.
- Quarantine metadata (owner, expiry) surfaced to the test report; the journal marks
  `QUARANTINED` but nothing reads it back into JUnit output.
- Standard `@Tag` vocabulary and composed annotations (smoke, destructive, multi-device,
  physical, WebView).
- Suite-scoped sessions (one driver start per class/suite) — every test currently opens and
  closes its own session (~4–6 s per device per test on the local matrix).
- Rejection of `PER_CLASS` instances that retain live `Device` fields; field injection is not
  offered at all (parameters only), which is the plan's preferred form.

## Artifacts, events, reports (plan §19) — Phase 3

Implemented on failure: `screenshot`, hierarchy XML, `DEVICE_INFO` text, driver
instrumentation log, `failure.txt`, under `<artifactsDir>/<class>/<method>/<role>-<serial>.*`.

Missing:

- Collision-proof run layout (`RUN_ID/tests/TEST_ID/ATTEMPT/ROLE-SERIAL/GENERATION`),
  `run.json`, artifact manifest with checksums and collection errors.
- Logcat capture started before the first AUT command and retained across AUT restarts.
- `dumpsys window/activity`, crash/ANR evidence, redacted protocol event log.
- Incremental JSONL event stream; JUnit XML is whatever the build tool writes; no HTML report;
  no Flowdeck adapter.
- Optional rotating `screenrecord` video.
- Separate deadlines per artifact step; today capture runs sequentially with the action
  timeout.

## AUT lifecycle (plan §14)

Implemented: install, uninstall, `isInstalled`, `forceStop` (verified), `clearData`
(verified), `grantPermission`, `launch` (launcher resolution + `am start -W`; the window wait is the
client's explicit `awaitAppVisible` / `awaitScreenStable`), `coldLaunch` returning a new process identity, `process()`,
`isRunning()`, `awaitIdle` with process-identity guards.

Missing: crash/ANR detection as codes (above), backgrounding/foregrounding helpers
(`pressHome` + relaunch works manually), deep-link launch, activity-result assertions,
locale/orientation control, and the plan's "AUT restarted during a command" fault scenario.

## Compose and WebView (plan §15, §17)

- Compose: supported only through `testTagsAsResourceId` → `rawRes(tag)` and standard
  semantics text/description. No semantics-tree access, no `useUnmergedTree`, no Compose
  lazy-list item scrolling by key (only by visible selector via the clients' `scrollUntil` loop).
- Observed once on emulator-5554 (2026-09-20): `MainScreenTest.scrollsComposeListUntilItemIsVisible`
  failed on `item.exists()` immediately after `scrollUntil` returned, with the item visible in
  the failure screenshot — a Compose semantics-update race between the scroll's match and the
  next resolve. Passed on rerun. Since protocol 4.0 `scrollUntil` is a client loop whose
  exit condition is that same `exists`, so the race no longer applies in that form.
- WebView: nothing. The plan's boundary (no WebDriver surface; accessibility-only within
  WebViews, explicit "not supported" for the rest) is not yet enforced or documented in the
  SDK.

## Lifecycle, ADB control plane, cleanup (plan §6–7)

- The late-mutation quarantine scenario (device reboot recovery) has not been re-run since
  the watchdog/heartbeat changes; the deviceTest suite excludes its `reboot` tag unless `-Ptap.reboot=true`. Needs an explicit go-ahead
  because it reboots both devices.
- Journal recovery runs at session open, but there is no standalone `tap doctor` / cleanup
  command for orphaned forwards, instrumentation, or leases outside a test run.
- ADB server supervision (restart on `adb` daemon death, `ADB_SERVER_SOCKET` pinning per CI
  worker) is not implemented; every call assumes a healthy default server.
- Driver APK install is once-per-JVM per serial; there is no version check against an
  already installed driver, so a stale driver on a device is only detected by the handshake
  failing.

## Observability and security (plan §20–21)

- Driver `Log` output is only captured through the instrumentation stdout; no structured
  driver event log, no metrics (queue depth, command latency percentiles).
- Redaction of typed text in logs/artifacts is not implemented. Exceptions render the
  selector but not `inputText`; the driver log and any future protocol event log would need
  an explicit redaction rule before secrets are typed in tests.
- Session secret is passed as an instrumentation argument (visible in the host's `ps` through
  the `adb shell` argv, and to shell/root on the device). Accepted under the trusted-host
  threat model (`code-review-status.md`, decisions; DR-16), with the stdin alternative noted
  there for shared hosts.

## CI, benchmarks, reliability gates (plan §23–24, Phase 3–4)

Done (2026-09-19, `release-engineering.md`): GitHub Actions CI (API contract lint/breaking +
stub check, JVM unit tests and artifacts, Python build, API 34 emulator sample suites, native
image on `main`) and tag-driven releases per artifact family (daemon binaries + `tap-api`,
Kotlin client, Python wheel, sync-sdk) with per-family versions. Not yet proven on a GitHub
runner (no push since it was written).

Not started: sharding across devices at the JUnit platform level (device locks handle
concurrency; nothing distributes classes across devices), a physical-device / API 29 CI lane, soak lane, benchmark
gates (per-command latency, session start time), the confidence-based reliability gate,
upgrade/rollback procedure, run-time client↔daemon version skew check.

## Second-language binding (design doc §8)

Implemented (2026-09-19): the host daemon (`:host:daemon`, `server-api.md`),
its native image, and the Python client + pytest plugin (`clients/python/`), with the sample suite
passing on API 29 and API 34 through the server. The daemon leases nothing (2026-09-20,
`pool-and-leases.md`): an attached device's `DeviceSession` holds the file lock, which the OS drops on daemon death. The Kotlin
SDK/JUnit extension is a gRPC client of the same server (2026-09-19): one daemon serves both
languages and nothing under `host/` depends on `clients/`. Remaining:

| Gap | Impact | Notes |
|---|---|---|
| pytest plugin exposes pinned serials only | `min_api`/`emulator`/`model_contains` are reachable from scripts but not from a marker | Add `@pytest.mark.tap_devices(left={"min_api": 30}, ...)`. |
| Server has no per-run structured event stream | Bindings cannot build reports from server events | Belongs with plan §19 events. |
| Synchronous Python API only | Multi-device tests use threads | `asyncio` façade later; the Kotlin coroutine façade landed in client 0.2.0. |

## Suggested order

1. Finish fake-ADB JVM coverage for `AppLifecycle` failure/postcondition paths and artifact
   capture; `DeviceSession` and `TapDaemon` lifecycle coverage has landed.
2. Provider visibility decision + `session.shutdown` (small protocol
   additions; update `protocol-contract.md` and golden fixtures in the same change).
3. Logcat + `dumpsys` in failure artifacts, run layout, JSONL events, then JUnit XML/HTML.
4. Crash/ANR codes with a crashing fixture activity.
5. Quarantine re-run and the remaining Phase 3 JUnit contracts. The coroutine façade and its
   13-device-test API 29/API 34 matrix are complete (`coroutines.md`).
