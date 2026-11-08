# Framework Gaps

Capability inventory reviewed: 2026-11-08 against current `origin/main`.
The plan-section inventory originated with the 2026-09-28 first release; older device-evidence
notes below retain their original dates and are not a fresh matrix run.

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
- `clients/agent` — `tap-agent` (experimental): CLI + MCP server for coding agents over held
  connections, screen snapshots with refs and the per-connection event log.
- `:samples:fixture-tests` — device tests (single-device journeys, ambiguity, text input,
  Compose list scrolling, app-owned idle sync, explicit screen-stability waits, lifecycle,
  opt-in device reuse, and two-device tests including sibling-cancellation without replay),
  plus a device-free JUnit discovery guard. Passes on API 29 and API 34 concurrently.

Everything below is what separates that from the production-grade framework the plan
describes.

## Driver operations (plan §11) — Phase 1 leftovers

| Gap | Impact | Notes |
|---|---|---|
| `session.shutdown` | Sessions end by closing the socket and `am force-stop` of the instrumentation. Works, but the driver never gets a graceful close and the journal cannot distinguish "host asked" from "connection died". | Small; add an operation + a driver-side clean exit. |
| Snapshot follow-ups | Structured screen snapshots, refs and optional selector candidates now exist (`ScreenSnapshot`, `ResolveRef`; `ScreenSnapshotServiceTest`, `SelectorSynthesisTest`). XML dumps remain diagnostic artifacts, not the only inspection surface. | Do not treat the original `inspector.snapshot` proposal as missing; see `agent-surface.md` and `recorder.md` for current inspector scope. |
| `AUT_NOT_INSTALLED` emission | A selector on an app that is not installed is `NOT_FOUND`. `AUT_CRASHED` / `AUT_ANR` are emitted by the server, not the driver (`AppDeaths`; `AppDeathsServiceTest`, `AppDeathTest` on emulator-5554). Their limits: only apps started through the attachment count, the failure comes when the running wait ends and not when the app dies, and a hang with no input waiting is no ANR to Android, so it stays a plain timeout. | Needs a package to check against, which a selector need not name. |
| Occlusion check coverage | `OBSCURED` reads the window list only: a view covering the target inside the same window (a floating bar, an in-window bottom sheet) is not detected, only the default display is checked, and `set_text` / `clear_text` (accessibility actions, not touches) are not checked. | `.docs/app-and-screen.md` Limits. Same-window covering would need a hit test over the node tree. |
| Secondary-display pointer gestures | `double_tap` and `drag` inject on the default display even when their selector resolves on another display, so they can touch an unrelated view. Tap and long tap use `UiObject2` on secondary displays; a refresh failure after the mutation gate is conservatively `STALE_DURING_COMMAND`, even if no touch was sent. | Follow-up: route pointer events with the node's display id (as AndroidX `GestureController` does), validate drag endpoints are on the same display, and add secondary-display device coverage. Until then use these pointer gestures only on the default display. |
| Permission dialogs on API 26–28 and OEM builds | `wait_permission_prompt` / `choose_permission` match the permission controller's (and the older package installer's) button resource ids; proven on API 34 (emulator) and API 29 (Samsung SM-J810G), both showing the Google controller package. API 26–28 and other OEM controllers are not yet exercised on a device. | Add a device of that family to the matrix. |

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
| Device constraints beyond current criteria | pytest role criteria and JUnit `@RequireDevice` now filter by API range, emulator/physical, manufacturer and model (`test_plugin.py`, `DeviceCriteriaTest`). Locale/orientation criteria and a JUnit `ExecutionCondition` remain absent. | Selection stays client-side; the daemon leases nothing (`pool-and-leases.md`). Device choice is not automatic test sharding. |
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

- `@RequireDevice` skips a test no device fits before anything is attached, from `beforeEach`
  (`DeviceCriteriaTest` in the extension and in the samples). What it does not cover: an
  `ExecutionCondition`, which would report the skip without opening a server connection.
- Per-test deadline: `tapTest` runs on the root job so JUnit `@Timeout`/interruption cancels
  in-flight RPCs, but there is no extension-owned wall-clock deadline beyond that; a hung
  test still relies on JUnit's own `@Timeout` and the driver watchdog.
- Run-level `TestExecutionListener`. A `@BeforeEach` / `@AfterEach` failure is captured with
  device artifacts like a test failure (`executionException`; `TapTestBridgeTest`), and those
  methods can use the devices (`LifecycleMethodsTest`). `@BeforeAll` / `@AfterAll` have no devices.
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

Implemented on failure: screenshot, hierarchy XML, device info JSON, driver instrumentation
log, failure stack, logcat and dumpsys. The JUnit listener and pytest plugin now write
client-owned `tap-run/1` metadata, incrementally flushed lifecycle JSONL and an atomically
published manifest with artifact identities, sizes, SHA-256 and collection errors under
`RUN_ID/tests/TEST_ID/ATTEMPT/DEVICE_ID/GENERATION` (`run-records.md`; Kotlin `RunRecordsTest`,
bridge capture test, Python `test_run_records.py`). IDs hash unsafe test/role/serial names.
Reporting-off retains the old artifact layout. Each worker/launcher has its own run ID.

Missing:

- Logcat is read at failure time from the device's ring buffers since the attachment
  (`DeviceLogs`, part of every failure capture; `DeviceEvidenceTest`, Python and Kotlin capture
  tests, checked on emulator-5554 with `am crash`), not streamed: on a busy device the oldest
  lines of a long test can be gone. `dumpsys window/activity` and crash/ANR *evidence* are in
  the same capture, and a command failing after the crash or ANR says so with `AUT_CRASHED` /
  `AUT_ANR`. A redacted protocol event log is not there.
- Per-command JSONL events (v1 records runner lifecycle, device assignments and evidence only),
  cross-worker aggregation and automatic indexing of manually saved artifacts. JUnit XML is
  whatever the build tool writes; no HTML report or Flowdeck adapter.
- Optional rotating **automatic failure-capture** video. Opt-in `Device.startRecording` /
  `start_recording` now records bounded scrcpy MP4/MKV on demand, but does not rotate files,
  start with each test, or attach video to JUnit/pytest failure artifacts.

## AUT lifecycle (plan §14)

Implemented: install, uninstall, `isInstalled`, `forceStop` (verified), `clearData`
(verified), `grantPermission`, `launch` (launcher resolution + `am start -W`; the window wait is the
client's explicit `awaitAppVisible` / `awaitScreenStable`), `coldLaunch` returning a new process identity, `process()`,
`isRunning()`, `awaitIdle` with process-identity guards.

Implemented device rotation: portable portrait/landscape, exact natural-relative rotation,
sensor unlock, a bounded settle wait (the driver reports Android's acceptance, not the app's
reaction), and session teardown restoration (`RotationTest`, `DeviceActionsTest` rotation
hold/restore, host cleanup tests). `foreground` / `background` and `openLink` (deep links),
screen wake/sleep/keyguard, runtime-permission dialogs, and double tap / drag / pinch / fling
(`GestureTest`, `ScreenTest`, `PermissionTest`, `DeviceActionsTest`, Python
`test_device_actions.py`). Launch with typed intent extras, `revokePermission`, keyboard state /
hide / IME action, clipboard set/get and `awaitToast` (`KeyboardTest`, `ClipboardTest`,
`ToastTest`, `DeviceActionsTest` 7–9). Device conditions (animations, dark mode, font scale,
density) and per-app languages (API 33+), each read back and restored on detach or by the next
attach after a crash (`DeviceConditionsTest`, `DeviceActionsTest` 10–11,
`test_conditions_hold_until_detach_then_are_restored`; crash restore in `RecoverJournalTest`).
Group 3 (`device-actions.md`) is implemented with unit and fake-device tests — network
switches, the device-wide locale, mock location, accessibility actions and slider progress,
the location-accuracy choice, push/pull files and gallery media — and its device tests are
written (`SystemLocaleTest`, `MockLocationTest`, `AccessibilityActionTest`, `DeviceFilesTest`,
`DeviceActionsTest` 12–15, Python `test_device_actions.py`). They pass on the Samsung (API 29,
2026-10-02); the emulator (API 34) run is pending, so the device-wide locale stays listed below
until it passes there too.
Group 4 is implemented with unit and fake-device tests — notifications as data (await, list,
open, dismiss through the driver app's notification listener), stay awake, the accessibility
display settings and the foreground activity — and its device tests are written
(`NotificationListenerTest`, `DeviceActionsTest` 16–17, Python `test_device_actions.py`). They
pass on the Samsung (API 29, 2026-10-02); the emulator (API 34) run is pending, so notifications
stay listed below until they pass there too.

Missing: activity-result assertions, the device-wide
locale (implemented, passing on API 29, API 34 run pending; see above), and
the plan's "AUT restarted during a command" fault scenario, and group 4 on API 34
(opening a notification's intent with background-start options, the display settings'
read-back; passing on API 29, emulator run pending).

## Compose and WebView (plan §15, §17)

- Compose: supported only through `testTagsAsResourceId` → `res(tag)` and standard
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
- `tap doctor [--fix]` reports and cleans what ended sessions left (unclosed journal, driver
  process, forwards to the device port range, unrestored settings) on devices nobody holds,
  through the attach recovery (`DeviceDoctorTest`, `CliTest`; on emulator-5554 after killing
  the daemon with SIGKILL mid-session). It never clears a quarantine.
- ADB server supervision (restart on `adb` daemon death, `ADB_SERVER_SOCKET` pinning per CI
  worker) is not implemented; every call assumes a healthy default server.

## Observability and security (plan §20–21)

- Driver `Log` output is only captured through the instrumentation stdout; no structured
  driver event log, no metrics (queue depth, command latency percentiles).
- Redaction of typed text in logs/artifacts is not implemented. Exceptions render the
  selector but not `inputText`; the driver log would need an explicit redaction rule before
  secrets are typed in tests. The per-connection event log (`Events`, `Watch`, `tap-agent
  export`) withholds the text of a `sensitive` `set_text` / `type_text`
  (`EventLogServiceTest`, `test_sensitive_text_is_sent_with_its_flag`); text typed without the
  flag is still logged verbatim, and nothing marks a password field as sensitive on its own.
- Session secret is passed as an instrumentation argument (visible in the host's `ps` through
  the `adb shell` argv, and to shell/root on the device). Accepted under the trusted-host
  threat model (`code-review-status.md`, decisions; DR-16), with the stdin alternative noted
  there for shared hosts.

## CI, benchmarks, reliability gates (plan §23–24, Phase 3–4)

Done (2026-09-19, `release-engineering.md`): GitHub Actions CI (API contract lint/breaking +
stub check, JVM unit tests and artifacts, Python build, API 34 emulator sample suites, native
image on `main`) and tag-driven releases per artifact family (daemon binaries + `tap-api`,
Kotlin client, Python wheel, agent tools, sync-sdk) with per-family versions. First run on
GitHub with the 0.0.1 release (2026-09-28).

Not started: sharding across devices at the JUnit platform level (device locks handle
concurrency; nothing distributes classes across devices), a physical-device / API 29 CI lane, soak lane, benchmark
gates (per-command latency, session start time), the confidence-based reliability gate,
upgrade/rollback procedure. Runtime client↔daemon checks require an equal explicit API
compatibility epoch and a sufficiently new engine (`ServerVersionTest`, Python connection
tests). A deliberate breaking edit must increment `contracts/proto/API_COMPATIBILITY`;
clients predating the epoch check still need a coordinated upgrade.

## Second-language binding (design doc §8)

Implemented (2026-09-19): the host daemon (`:host:daemon`, `server-api.md`),
its native image, and the Python client + pytest plugin (`clients/python/`), with the sample suite
passing on API 29 and API 34 through the server. The daemon leases nothing (2026-09-20,
`pool-and-leases.md`): an attached device's `DeviceSession` holds the file lock, which the OS drops on daemon death. The Kotlin
SDK/JUnit extension is a gRPC client of the same server (2026-09-19): one daemon serves both
languages and nothing under `host/` depends on `clients/`. Remaining:

| Gap | Impact | Notes |
|---|---|---|
| No aggregated per-run command stream | A report cannot yet correlate every command across connections/workers from one run stream | Per-connection `Events` and passive `Watch` streaming exist, and the runners write `tap-run/1` lifecycle records. Cross-worker aggregation and per-command run JSONL remain missing (`run-records.md`). Agent raw event export is now `history`; named step `record`/`export` is a separate feature (`agent-step-recordings.md`). |
| Synchronous Python API only | Multi-device tests use threads | `asyncio` façade later; the Kotlin coroutine façade landed in client 0.2.0. |

## User-facing capability inventory

This inventory adds user needs beyond the original plan. **Missing** means no built-in Tap
API/workflow, not that a test cannot use an external tool. **Partial** means an existing
capability has a specific limit. **Scope decision** means the current boundary is deliberate:
listing it is not approval to change that boundary. Synchronization remains deferred and is
not part of this audit. These are backlog/research items, not promised APIs or a delivery order.

### App delivery and failure diagnosis

- **Missing — split APK / app bundle installation.** Users need to install release-like builds
  containing a base APK plus configuration or dynamic-feature splits. `App.install` and
  `InstallRequest` upload one APK; there is no install-multiple/session workflow, APK-set
  selection or `.aab` conversion. Bundletool integration would be a separate dependency and
  device-targeting decision. A future implementation needs transactional failure/cleanup tests
  and verification of the expected package and split set, not just package presence.
- **Partial — crash/ANR reporting.** This is no longer wholly missing: `AppDeaths` upgrades
  failures to `AUT_CRASHED` / `AUT_ANR` for apps started through the attachment
  (`AppDeathsServiceTest`, sample `AppDeathTest`). It does not interrupt a wait immediately
  when the app dies, monitor arbitrary already-running packages, or classify every application
  hang as an Android ANR. Users still need timely, attributable failures and the supporting
  evidence. `AUT_NOT_INSTALLED` also needs an explicit app target rather than a scope-free
  selector. Do not equate an ordinary timeout with a crash or an ANR.

### Network and system scenarios

- **Missing — packet recording and live network monitoring.** Users need to see which endpoints
  a device contacts, record sent/received traffic and correlate it with UI steps. No Tap
  capture API exists. See the feasibility/options section below; passive packet evidence is
  different from HTTP decoding and from modifying network behavior.
- **Missing — network fault simulation and request-level testing.** Existing airplane-mode,
  Wi-Fi and mobile-data switches cover offline transitions, not bandwidth limits, latency,
  jitter, loss, DNS failures, selected connection drops, HTTP error injection, request assertions
  or response mocking. Those unlock retry, timeout, caching and error-state tests. Emulator
  controls, a controlled gateway and an opt-in application proxy have different coverage;
  neither capture nor a Wi-Fi switch is a substitute. Changes must be scoped and restored,
  and must not silently break Tap's control connection.
- **Missing — biometric and clock controls.** Fingerprint success/failure on supported
  emulators, and controlled date/time/timezone, would exercise login, token expiration,
  scheduling and day-boundary bugs. Fingerprint and time/timezone are already in the
  `device-actions.md` backlog. Real biometric hardware cannot be assumed to accept simulated
  input, and changing a device clock can break TLS and other apps; capability checks and
  restoration are prerequisites, not an unconditional cross-device promise.

### UI coverage and assertions

- **Partial — hybrid/WebView testing.** Nodes exposed through Android accessibility can be
  selected, but Tap has no DOM selectors, JavaScript execution, frame handling or web-context
  switching. Users with embedded checkout/login flows may need these. A full web automation
  surface is outside the current no-WebDriver design; document unsupported content and decide
  on a separate integration rather than quietly adding a second driver model.
- **Partial — Compose-specific semantics.** Exposed semantics text/descriptions and
  `testTagsAsResourceId` work. There is no direct semantics-tree API, `useUnmergedTree`, or
  lazy-list scrolling by item key; selector-driven scrolling only sees exposed nodes. Users
  needing unmerged child state or stable off-screen item identity would need an explicitly
  designed app-side integration, not assumptions about UiAutomator visibility.
- **Scope decision — custom canvas/game UI and image-based targeting.** Content without
  accessibility nodes has no general element-targeting path. OCR/image matching or coordinate
  input would help some users, but coordinate actions are deliberately excluded. Prefer app
  accessibility/test hooks; any broader adapter needs approval and must preserve unambiguous
  mutations. Inspecting a screenshot is not an image locator implementation.
- **Scope decision — visual regression assertions.** Screenshots exist; baseline storage,
  comparison, masks, tolerances and review/approval do not. These would catch layout/theme
  regressions that semantic assertions miss. Visual testing is explicitly a non-goal in
  `docs/development/architecture.md`; evaluate an external integration before expanding Tap.
- **Missing — accessibility auditing.** Accessibility inspection/actions and display-condition
  controls are not a rules engine for unlabeled controls, small touch targets, reading order
  or contrast. Users may want a bounded report attached to each screen/test. Any audit needs
  defined rule coverage and platform limits: a successful UiAutomator action is not proof of
  TalkBack usability or accessibility compliance.
- **Partial — stable property waits and timeout evidence.** Element waits can assert state,
  text and count, but there is no general "condition held for N ms" option for these waits.
  A transient match can therefore pass. Per-wait bounded screenshot/hierarchy evidence would
  also explain timeouts that a later test-level capture cannot reconstruct. See the host SDK
  gaps above; neither requires an implicit global wait.

### Evidence, privacy and execution at scale

- **Partial — automatic failure video.** Explicit SDK and agent audio/video recording exists.
  Automatic per-test capture, bounded rolling pre-failure footage and failure-artifact
  attachment do not. Users want the actions leading to a failure, with bounded disk use and
  clear opt-in/privacy controls. Reuse the recording lifecycle and manifest, and report capture
  failure without hiding the original test failure.
- **Partial — reports and long-running diagnostics.** `tap-run/1` already provides run/test
  identities, manifests, hashes, collection errors and lifecycle JSONL. Remaining needs are a
  browsable HTML report, cross-worker aggregation, per-command run events and continuous or
  bounded rolling logcat so busy-device ring-buffer rollover cannot erase earlier evidence.
  This is not a missing artifact manifest anymore (`run-records.md`).
- **Partial — sensitive-data defaults.** Explicit sensitive typing flags withhold event-log
  text, but password fields are not automatically marked sensitive and screenshots/hierarchies
  may contain secrets regardless. Users need an explicit retention/redaction policy spanning
  logs, media and future network artifacts; no blanket "all artifacts are redacted" guarantee
  is justified by the existing input flag.
- **Missing — automatic test sharding.** Device locks prevent conflicting ownership and
  multi-device tests can coordinate roles, but no scheduler distributes classes/tests across
  available devices or aggregates those workers. Users with large suites need capacity-aware
  scheduling that preserves multi-device role requirements and test isolation.
- **Partial — device selection.** Both pytest and JUnit now support API/device-kind/vendor/model
  criteria; the earlier JUnit-parity gap is resolved (`DeviceCriteriaTest`). Locale/orientation
  criteria and JUnit prerequisite reporting before connecting to the server remain follow-ups.
- **Missing — asynchronous Python facade.** Python's SDK is synchronous; concurrent device
  journeys need threads. Async applications and orchestrators may want native `asyncio`
  cancellation and multi-device concurrency. A facade must preserve connection ownership and
  indeterminate-mutation semantics; automatically replaying an interrupted action is not safe.

### Agent parity: what remains and what is already supported

The previous audit was made against an older checkout. Current `origin/main` already includes
richer state/text/count waits, recording start/stop, bounded `scroll_until`, and boolean,
text-mode and relational/logical JSON selectors. Do **not** re-add these as missing. Evidence:
`agent-api-coverage.md`, `clients/agent/api-coverage.json`, `test_api_coverage.py` and adapter
regression tests. That record includes an API 34 device check, not API 29 verification.

- **Partial — toast/notification match modes.** Agent helpers still expose exact/contains,
  while the SDK supports additional modes such as regex. Dynamic message IDs or variable
  counters may need regex matching. This is a narrow parameter-parity gap, distinct from the
  now-supported richer element selector language.
- **Existing mechanism — snackbars.** There is no snackbar-specific helper or dedicated
  snackbar regression scenario found in this audit. Accessible snackbars use ordinary element
  waits and action selectors; unlike buffered toast events, the wait must catch the snackbar
  while its node exists. A Material/Compose fixture covering message, action and dismissal
  would prove that workflow. A new helper is optional ergonomics, not a missing locator engine.
- **Intentional exclusions.** Passive `Watch`/`WatchVideo` belong to Tap watcher; arbitrary
  Python callbacks remain SDK-only. Synchronization is deferred. Coordinate input, XPath,
  persistent element handles, implicit retry/settling, iOS and a YAML flow runner are not
  accidental gaps; changing those boundaries requires an explicit scope decision.

## Packet capture and monitoring: proposed capability

**Status: VPN implementation requested first (2026-11-08); not implemented yet.** Emulator
capture and privileged device capture remain explicit alternative-backend backlog items.
The VPN forwarding engine, packaging and public API still need design. The goal is
opt-in recording of traffic sent/received by a device, plus bounded live flow/packet summaries
that can be correlated with test steps. Export PCAP/PCAPNG for Wireshark or tshark; decoded
HTTP assertions are a separate layer. No rooting, VPN takeover, CA installation or TLS
interception should happen implicitly.

### Feasible backends and their limits

| Backend | What it could provide | Prerequisites and boundaries |
|---|---|---|
| Emulator host capture | Device traffic without changing the AUT or installing a VPN. | Capture depends on emulator version and network path. Android's console `network capture` covers legacy cellular/WAN through QEMU slirp; Emulator 36.5+ defaults Wi-Fi to netsim, which has separate per-radio PCAP capture (`-netsim-args="--pcap"`). Detect capabilities and identify the captured path rather than promising one command captures every interface. |
| Privileged device `tcpdump`/libpcap | Broad interface-level send/receive evidence, including a physical test phone. | Requires a suitable capture binary and privileges allowed by the build, capabilities and SELinux policy; ordinary production `adb shell` is insufficient. A rooted/userdebug test device may work, but root alone is not a guarantee. Never root/reboot a shared device automatically. Capture selected interfaces or `any` where supported; report filters, truncation and drops. |
| No-root `VpnService` helper | IP traffic routed through a local TUN, either selected apps or the supported profile-wide route set. | Requires explicit VPN permission and a service that forwards traffic, not just reads it. Only one VPN is active per user/profile, so it conflicts with an existing VPN/always-on policy. IPv4/IPv6 routes, UDP/TCP forwarding, DNS, excluded/protected sockets and revocation must be handled. It sees routed IP traffic, not raw Wi-Fi frames, every profile or all bypass paths. |
| Controlled router/gateway or mirrored network | Traffic crossing that network boundary, without changing the phone. | Requires infrastructure access. Wi-Fi capture does not include cellular, loopback or traffic on other paths; an ordinary USB ADB connection does not route all phone internet traffic through the host. VPN/TLS traffic remains encrypted at this point. |
| Explicit HTTP(S) proxy | Readable requests/responses and potential request assertions or fault injection for cooperating apps. | Not an all-packet recorder: apps may ignore proxies and arbitrary TCP/UDP/QUIC needs other handling. HTTPS interception needs deliberate trust configuration; pinning/custom TLS can prevent it. Use an opt-in debug build or instrumentation, never silent certificate/pinning bypass. |

### What "all packets" must not imply

- Coverage must name the interface(s), profile/app scope, capture point and start/stop interval.
  Device-wide IP capture is not modem/baseband, Bluetooth or raw over-the-air Wi-Fi capture.
  Loopback, tethering, other profiles, VPN bypass and hardware offload need separate validation.
- TLS/HTTPS and QUIC remain encrypted in a raw capture, even with root. IP addresses, ports,
  sizes, timing and some handshake/DNS metadata can be useful; encrypted DNS/ECH can hide
  names too. Full URLs, headers and bodies require an additional authorized decryption or
  application-instrumentation strategy. A user-installed CA is not trusted by every Android
  app; debug-only Network Security Configuration is one controlled option for an owned AUT.
- No backend should promise lossless capture under all loads. Record captured/dropped counts
  when available, packet-size limits, filters, byte/time caps and incomplete-stop conditions.
  Interface capture also does not automatically identify the originating Android package;
  per-app attribution needs a supported mechanism or must be marked unknown.

### Proposed Tap integration and acceptance evidence

These are design requirements to evaluate, not new callable method names:

1. **Explicit lifecycle and capabilities.** Probe the selected backend, fail clearly when
   unsupported, then start/stop/query a bounded capture and optionally subscribe to bounded
   summaries. Define the readiness boundary so a test knows when triggering network activity
   is safe. Never retry a transmitted start/stop mutation automatically.
2. **Host-owned supervision.** Keep process/device lifecycle, serial-specific ADB and journal
   cleanup in `host/core`, exposed through contracts/server and both SDKs, then thin CLI/MCP
   adapters. A VPN helper belongs on-device, but clients must not spawn their own ADB/capture
   processes. Account for device exclusivity and cleanup after disconnect, cancellation,
   process death and host restart. Do not silently displace or claim to restore a third-party
   VPN session.
3. **Evidence and monitoring.** Stream/download artifacts without exposing arbitrary server
   paths; include backend/version, serial, interface/scope, timestamps, filters, counts/drops,
   truncation and SHA-256 in the artifact metadata. Relate capture times to run/step IDs with
   known clock offsets. Bound live summary queues and report subscriber gaps/backpressure.
4. **Privacy.** Capture can include unrelated apps, tokens, messages and personal data. Require
   explicit opt-in and scope, private artifact permissions, size/time/retention limits and no
   raw payloads in default logs or MCP responses. Prefer per-app filtering where supported.
   Raw PCAP is not made safe by the existing sensitive-input flag; define any derived redacted
   view separately and disclose what the original artifact still contains.
5. **Proving tests.** Use an owned endpoint to exercise outbound/inbound TCP and UDP, IPv4/IPv6,
   DNS and HTTPS/QUIC where supported. Verify readable cleartext versus encrypted payloads,
   capture readiness, counts/limits, unsupported privileges, VPN refusal/conflict/revocation,
   detach/crash cleanup, control-channel survival and artifact integrity. Prove each advertised
   backend on its actual device/network path; do not infer physical-device support from an
   emulator-only pass. Device tests require explicit user approval.

### Selected first backend and alternative-backend backlog

The user selected **no-root VPN capture first**, for sent/received IP packet evidence on
physical phones. It must forward traffic while recording it; merely reading a TUN interface
would break connectivity. Start with raw encrypted packet evidence, not implicit HTTPS
interception. Forwarding-library/helper packaging still needs an explicit decision.

Keep the other two implementation paths on the backlog behind the same eventual capture API:

- **Emulator-native capture — not implemented.** Prefer this for emulator/CI runs where it
  avoids occupying Android's VPN slot and changing AUT routing. Detect the emulator version
  and actual network path; cover legacy QEMU cellular/WAN and netsim Wi-Fi separately. Record
  capability/coverage metadata and collect private, bounded PCAP artifacts. Prove inbound and
  outbound traffic on each advertised path before claiming support.
- **Privileged device `tcpdump`/libpcap — not implemented.** An optional backend for an already
  privileged physical test phone, useful when VPN conflicts make no-root capture unsuitable.
  Probe binary availability and actual capture permission, select interfaces explicitly,
  supervise the process per serial and collect bounded private artifacts plus drop statistics.
  Never root/reboot automatically or infer all-interface coverage from one successful capture.

A controlled gateway and an explicit HTTP proxy remain research alternatives, not additional
backends promised by this request. Keep API capability reporting honest: an unsupported VPN
must not silently fall back to root capture or change the device's network configuration.

### Primary references

- [Android VPN guide](https://developer.android.com/develop/connectivity/vpn): TUN packets,
  permission, per-app routing, lifecycle and the single-active-VPN constraint.
- [Android Emulator console](https://developer.android.com/studio/run/emulator-console#network):
  capture commands and the legacy cellular/WAN versus netsim Wi-Fi boundary.
- [Advanced emulator networking](https://developer.android.com/studio/run/emulator-networking-advanced#capture-network):
  netsim per-radio PCAP and artifact locations.
- [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config):
  CA trust defaults, debug-only trust anchors and certificate pinning.
- [tcpdump manual](https://man7.org/linux/man-pages/man1/tcpdump.1.html): capture interfaces,
  snapshot length, filters, output files and drop statistics.

These are feasibility references, not borrowed implementations. Before adapting an upstream
capture tool, pin its commit and record the adopt/adapt/license decision in
`upstream-reference-audit.md`.

## Suggested next evaluations

Not an approved implementation sequence:

1. Implement the requested no-root VPN capture after selecting forwarding/packaging; keep
   emulator-native and privileged `tcpdump` capture on the alternative-backend backlog.
   Encrypted packet evidence stays distinct from HTTP decoding and network fault injection.
2. Add split-APK installation and the remaining narrow agent match-mode parity when selected.
3. Evaluate automatic failure video, continuous bounded logs and a report consumer on top of
   the existing `tap-run/1` records, rather than rebuilding manifests or lifecycle JSONL.
4. Improve crash/ANR timeliness, same-window occlusion and secondary-display gesture safety;
   preserve the distinction between missing behavior and matrix coverage still to be run.
5. Address the remaining fake-ADB lifecycle/failure-capture coverage and JUnit contracts.
   Synchronization stays deferred; quarantine/reboot validation requires explicit approval.
