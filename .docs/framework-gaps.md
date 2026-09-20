# Framework Gaps

Date: 2026-09-18

This is the honest delta between what `.docs/android-e2e-framework-implementation-plan.md`
specifies and what the repository implements and proves on devices today. It is the
"what is still missing" companion to `.docs/phase-1-progress.md` (what is done) and
`.docs/project-architecture.md` (what exists and where). Items are grouped by plan section
and tagged with the phase the plan assigns them to. Nothing here is ticked in the progress
docs until it is implemented *and* covered by a JVM test, the device validation flow, or the
sample suite.

## What is usable now

A product team can write and run real tests today:

- `:host:service` (`tap serve`) — the one host process: ADB, journals, leases, driver
  lifecycle, `AppLifecycle`, machine-wide constrained device pool; JVM dist or native image.
- `:clients:kotlin:sdk` — `TapClient`/`Run`, `Device`, `App`, `Element`, `ElementWait`,
  selector DSL, typed exceptions; a gRPC client of the service.
- `:clients:kotlin:junit5` — `@TapTest`, `Device`/`Devices` parameter injection, named roles,
  all-or-none acquisition from the service pool, failure artifacts, `tap.*` system-property /
  `TAP_*` env config.
- `clients/python` — the same API and a pytest plugin.
- `:samples:fixture-tests` — twelve tests (single-device journeys, ambiguity, text input,
  Compose list scrolling, app-owned idle sync, explicit screen-stability waits, lifecycle, one
  two-device test) passing on API 29 and API 34 concurrently.

Everything below is what separates that from the production-grade framework the plan
describes.

## Driver operations (plan §11) — Phase 1 leftovers

| Gap | Impact | Notes |
|---|---|---|
| `session.shutdown` | Sessions end by closing the socket and `am force-stop` of the instrumentation. Works, but the driver never gets a graceful close and the journal cannot distinguish "host asked" from "connection died". | Small; add an operation + a driver-side clean exit. |
| `device.wake` / screen state | Tests assume the screen is on and unlocked. | `UiDevice.wakeUp()` + keyguard dismissal; validation on a device with a lock screen. |
| `inspector.snapshot` | No JSON hierarchy with selector suggestions; `DUMP_HIERARCHY` returns raw UiAutomator XML only. | Phase 5 inspector UI depends on it; failure artifacts use the XML for now. |
| `AUT_CRASHED` / `AUT_ANR` / `AUT_NOT_INSTALLED` emission | Crashes surface as `NOT_FOUND`/`WAIT_TIMEOUT` plus a process-identity change, not as a first-class code. | Requires driver-side process observation (`am`/`dumpsys activity` or `ActivityManager` crash/ANR detection) and a fixture that crashes/ANRs on demand. |
| Text selectors vs hints | `SNAPSHOT.text` and the `SET_TEXT`/`TYPE_TEXT`/`CLEAR_TEXT` verification exclude a displayed hint, but `By.text` (and therefore `text(...)` selectors and `textEquals`) still see the hint as text on API 26+. `text("")` never matches an empty hinted field. | Either compile `text` selectors that could hit editable fields through the traversal plan with the same `displayedText` rule, or document `hint(...)` as the way to target empty fields. |
| Multi-touch / pinch, drag, fling | Not exposed. | Plan lists them as demand-driven. |
| Permission dialogs | `App.grantPermission` pre-grants via `pm`; there is no helper to accept/deny a runtime permission dialog that appears mid-test. The driver's `SYSTEM` scope allow-list already includes the permission controller package. | Adopt the Appium UiAutomator2 approach (locate the controller's allow/deny buttons by resource id per API family) behind a fixture (`PermissionActivity` exists). |

## Sync SDK visibility (plan §16)

The driver manifest's `<queries>` lists only the fixture's authorities
(`com.company.tap.fixture.tap-sync`, `com.company.tap.fixture.fault`). On API 30+ a product
AUT's `${applicationId}.tap-sync` provider is invisible to the driver, so `App.awaitIdle`
fails with `SYNC_*` details for any app other than the fixture. Options, in order of
preference: `<queries><intent>` with a Tap-specific provider action declared by
`:device:sync-sdk`; `QUERY_ALL_PACKAGES` on the debug-only driver; or generating the driver APK per
AUT. This blocks "idling-integrated journeys" for real products and must be decided before
the CI pilot.

## Host SDK (plan §12–13) — Phase 2 deviations

| Gap | Impact | Notes |
|---|---|---|
| Synchronous API instead of `tapTest` coroutine scope | Tests are plain blocking JUnit methods. Multi-device fan-out uses threads (see `MultiDeviceTest`) rather than structured concurrency; a failing sibling does not cancel the other device's in-flight command. | Deliberate simplification to reach usability; the `DriverClient` is already async (`submit`/`PendingCommand`), so a `suspend` façade + `tapTest` can be layered without protocol changes. |
| No `DeviceBarrier` / structured fan-out helpers | Cross-device phases are hand-rolled. | Depends on the coroutine façade. |
| `WaitOptions.stableFor` | Host-polled waits (`enabled`, `textEquals`, `count`, …) have `timeout` and `pollInterval` only; no "condition held for N ms". | Small. |
| Wait diagnostics | `WaitTimeoutException` carries description, serial, selector, elapsed, poll count and last observation; it does not attach a bounded hierarchy/screenshot snapshot at timeout. | The JUnit extension captures those on failure, so the information exists per test but not per wait. |
| `Element.getProperty` | Covered by `snapshot()`; there is no single-property accessor beyond `text/isEnabled/isChecked`. | Convenience only. |
| Device constraints | The service leases by serial only (decided 2026-09-20: the service stays simple; roles and constraints are a client concern). Clients map roles to `tap.serials`, pins, or the inventory; no API-range/emulator/model/locale/orientation filtering exists in any client. The earlier service-side matcher is archived under `archive/pool-roles/`. | Client-side selection over `DeviceFacts` from `Inventory`; a `@TapDevice(minApi = …)` style annotation. |
| Fake ADB / fake driver coverage for host core and clients | `DriverClient` has loopback tests; `DeviceSession`, `AppLifecycle`, `TapService`, the Kotlin `Device`/`App`/`Element` and `TapExtension` are exercised only on real devices via the sample suites. Failure paths (install failure, forward conflict, pool timeout, artifact capture failure) have no JVM tests. | Highest-value testing gap; a fake `Adb` + the existing `FakeDriverServer` would cover most of it. |
| Localization / text normalisation | `text(...)` is exact and case-sensitive; Material buttons expose all-caps accessibility text, so `text("Sign in")` misses `SIGN IN`. | Document (done in the samples) or add a case-insensitive match mode. |

## JUnit 5 integration (plan §18) — Phase 3

Implemented: `BeforeEachCallback`, `AfterEachCallback`, `ParameterResolver`,
`TestExecutionExceptionHandler`, all-or-none acquisition with timeout, per-method store,
failure artifacts, cleanup failures preserved as secondary and rethrown only for passing tests,
missing-devices → assumption (skipped, not failed).

Missing:

- `ExecutionCondition` for static prerequisites (API range, physical-only, tags) without
  reserving devices.
- `InvocationInterceptor` per-test deadline; today a hung test relies on JUnit's own
  `@Timeout` and the driver watchdog.
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
(verified), `grantPermission`, `launch` (launcher resolution + `am start -W` +
`WAIT_APP_VISIBLE`), `coldLaunch` returning a new process identity, `process()`,
`isRunning()`, `awaitIdle` with process-identity guards.

Missing: crash/ANR detection as codes (above), backgrounding/foregrounding helpers
(`pressHome` + relaunch works manually), deep-link launch, activity-result assertions,
locale/orientation control, and the plan's "AUT restarted during a command" fault scenario.

## Compose and WebView (plan §15, §17)

- Compose: supported only through `testTagsAsResourceId` → `rawRes(tag)` and standard
  semantics text/description. No semantics-tree access, no `useUnmergedTree`, no Compose
  lazy-list item scrolling by key (only by visible selector via `SCROLL_UNTIL`).
- WebView: nothing. The plan's boundary (no WebDriver surface; accessibility-only within
  WebViews, explicit "not supported" for the rest) is not yet enforced or documented in the
  SDK.

## Lifecycle, ADB control plane, cleanup (plan §6–7)

- The late-mutation quarantine scenario (device reboot recovery) has not been re-run since
  the watchdog/heartbeat changes; `host --no-reboot` skips it. Needs an explicit go-ahead
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
- Session secret is passed as an instrumentation argument (visible in `ps`/`dumpsys` on a
  rooted device for the process lifetime). Acceptable for debug builds; note for the security
  review.

## CI, benchmarks, reliability gates (plan §23–24, Phase 3–4)

Done (2026-09-19, `release-engineering.md`): GitHub Actions CI (API contract lint/breaking +
stub check, JVM unit tests and artifacts, Python build, API 34 emulator sample suites, native
image on `main`) and tag-driven releases per artifact family (service binaries + `tap-api`,
Kotlin client, Python wheel, sync-sdk) with per-family versions. Not yet proven on a GitHub
runner (no push since it was written).

Not started: sharding across devices at the JUnit platform level (the pool handles
concurrency inside one JVM only), a physical-device / API 29 CI lane, soak lane, benchmark
gates (per-command latency, session start time), the confidence-based reliability gate,
upgrade/rollback procedure, run-time client↔service version skew check.

## Second-language binding (design doc §8)

Implemented (2026-09-19): the host session service (`:host:service`, `service-api.md`),
its native image, and the Python client + pytest plugin (`clients/python/`), with the sample suite
passing on API 29 and API 34 through the service. The pool leases by serial (roles are
client-side since 2026-09-20) and releases immediately on client death. The Kotlin
SDK/JUnit extension is a gRPC client of the same service (2026-09-19): one pool serves both
languages and nothing under `host/` depends on `clients/`. Remaining:

| Gap | Impact | Notes |
|---|---|---|
| pytest plugin exposes pinned serials only | `min_api`/`emulator`/`model_contains` are reachable from scripts but not from a marker | Add `@pytest.mark.tap_devices(left={"min_api": 30}, ...)`. |
| Service has no per-run structured event stream | Bindings cannot build reports from service events | Belongs with plan §19 events. |
| Synchronous Python API only | Multi-device tests use threads | `asyncio` façade later, as with the Kotlin coroutine façade. |

## Suggested order

1. Fake-ADB JVM tests for `DeviceSession`/`AppLifecycle`/`TapService` failure paths and
   in-process gRPC tests for the Kotlin client and `TapExtension`.
2. Provider visibility decision + `session.shutdown` (small protocol
   additions; update `protocol-contract.md` and golden fixtures in the same change).
3. Logcat + `dumpsys` in failure artifacts, run layout, JSONL events, then JUnit XML/HTML.
4. Crash/ANR codes with a crashing fixture activity.
5. Coroutine façade (`tapTest`, `DeviceBarrier`) once the synchronous API has settled.
6. Quarantine re-run and the remaining Phase 3 JUnit contracts.
