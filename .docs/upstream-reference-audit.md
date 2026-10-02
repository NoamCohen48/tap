# Upstream Reference Audit

Date: 2026-09-17; uiautomator2 and agent-device added 2026-09-20.

This audit records the upstream behavior Tap will adopt or adapt and the product surfaces it
will deliberately not copy. It is an engineering decision record, not a statement that source
was imported. The implementation remains independently written.

## Pinned revisions

- [Appium UiAutomator2 server](https://github.com/appium/appium-uiautomator2-server/tree/4a8139161cbb3aad078e36539995eb734297d56e),
  commit `4a8139161cbb3aad078e36539995eb734297d56e`.
- [Appium UiAutomator2 driver](https://github.com/appium/appium-uiautomator2-driver/tree/7a54db007aa8a44f5df21cd0b52afc13c0d277ae),
  commit `7a54db007aa8a44f5df21cd0b52afc13c0d277ae`.
- [Maestro](https://github.com/mobile-dev-inc/Maestro/tree/01a3e442afba15f80f825492dae2e48fcfd4239c),
  commit `01a3e442afba15f80f825492dae2e48fcfd4239c`.
- [openatx/uiautomator2](https://github.com/openatx/uiautomator2/tree/657c5d791075945cc21e78e125b220461c8ae99c),
  commit `657c5d791075945cc21e78e125b220461c8ae99c` (MIT). Python wrapper over an on-device
  UiAutomator HTTP/JSON-RPC agent.
- [callstack/agent-device](https://github.com/callstack/agent-device/tree/0ebd2540a33c674194d59357a48287629b8377a9),
  commit `0ebd2540a33c674194d59357a48287629b8377a9` (MIT). CLI, MCP server and Node API that
  lets coding agents drive and verify apps on iOS/Android/HarmonyOS/web/desktop.

The first three repositories identify their audited source as Apache-2.0, the last two as MIT.
File history and headers must still be checked before any future source import.

## Decision matrix

| Area | Upstream evidence | Tap decision | Rationale and boundary |
|---|---|---|---|
| Device server shape | Appium's [`AppiumServlet`](https://github.com/appium/appium-uiautomator2-server/blob/4a8139161cbb3aad078e36539995eb734297d56e/app/src/main/java/io/appium/uiautomator2/server/AppiumServlet.java) is a thin on-device request service; host driver code owns ADB, installation, forwarding, and cleanup. | **ADOPT** the thin device-agent boundary. | Tap keeps orchestration and ownership on the host and keeps the Android process replaceable. |
| Session lifecycle | Appium's host driver creates and deletes one device session and performs best-effort cleanup. Maestro separates CLI orchestration, a per-device client, and an instrumentation agent. | **ADAPT** into a durable session unit containing lease, generation, secret, forward, socket, process identity, and journal. | Neither upstream provides Tap's crash-safe ownership or generation fencing. |
| Transport loss | Maestro's [`AndroidDriverClient`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-client/src/main/java/maestro/drivers/AndroidDriverClient.kt) distinguishes connection timeout/closure and retries selected transient failures. | **ADAPT** typed failures and narrow retry allowlists. | Tap never retries a transmitted mutation and quarantines indeterminate late work. Broad retry-by-default is rejected. |
| Request identity | Appium has a single active session but no Tap-style generation/request fence. | **RETAIN TAP DESIGN**: authenticated session generation plus strictly increasing request IDs. | This is the protocol boundary that prevents stale clients and duplicate mutations from crossing rebuilt sessions. |
| Selectors | Appium's [`FindElement`](https://github.com/appium/appium-uiautomator2-server/blob/4a8139161cbb3aad078e36539995eb734297d56e/app/src/main/java/io/appium/uiautomator2/handler/FindElement.java) and element-location helpers exercise mature UiAutomator lookup behavior. Maestro centralizes selector/wait behavior in [`Maestro`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-client/src/main/java/maestro/Maestro.kt). | **ADAPT** Android compatibility cases and concise condition semantics. | Tap keeps immutable lazy selectors, package scope, device-side matching, and no persistent node handles. |
| XPath and hierarchy | Appium supports XPath and broad WebDriver element semantics. | **DO NOT COPY** for the hot path. | Hierarchy dump remains diagnostic only; XPath and persistent handles conflict with Tap's performance and staleness model. |
| Accessibility cache | Appium's [`AccessibilityNodeInfoCache`](https://github.com/appium/appium-uiautomator2-server/blob/4a8139161cbb3aad078e36539995eb734297d56e/app/src/main/java/io/appium/uiautomator2/core/AccessibilityNodeInfoCache.java) contains platform-specific cache handling. | **ADAPT** focused invalidation and stale-node recovery where device evidence requires it. | Do not import global state or element-cache architecture. Add each workaround behind a regression test for affected API/device families. |
| Settling / animations | Maestro's [`Maestro.waitForAppToSettle`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-client/src/main/java/maestro/Maestro.kt) runs implicitly after every command (`waitForWindowUpdate` loop + hierarchy equality), `waitForAnimationToEnd` diffs screenshots (≤ 0.5 %), and `retryIfNoChange` re-taps when the hierarchy did not change. | **ADAPT** the two observations as explicit waits: `awaitAppSettled` (accessibility-tree fingerprint, no screenshots) and `awaitAnimationEnd` (downscaled pixel diff), both one `WAIT_SCREEN_STABLE` with a `stableSignal`, driven by accessibility events. **DO NOT COPY** the implicit per-command settle (it taxes every action and never converges on live screens) or `retryIfNoChange` (replays a mutation). | Settling is opt-in per call (`awaitScreenStable`); no code was copied. Tap also bounds UiAutomator's implicit `waitForIdle` to 1 s so busy screens cannot stall commands past their deadlines. |
| System panels | Appium's `openNotifications` and androidx `UiDevice.openNotification` / `openQuickSettings` (uiautomator 2.4.0) run `waitForIdle()`, then `UiAutomation.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS / _QUICK_SETTINGS)`. | **ADAPT** the global action without the idle wait (the driver assumes nothing): `open_system_panel` returns whether the system accepted the action, and the test waits for the panel. | No code was copied. `SystemPanelTest` (Kotlin samples, Python device suite) passes on 85e49002 (SM-J810G, API 29) and emulator-5554 (API 34). On API 34, Back from quick settings returns to the shade first, and on the Samsung a panel action sent while the shade was still closing was accepted and ignored; the test handles both. |
| Input | Appium's interaction and element handlers cover key injection, click, gesture, and text edge cases. Maestro's [`AndroidDriver`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt) keeps these behind a typed device API. | **ADAPT** proven Android input compatibility behavior behind Tap operations. | Preserve exact completion, bounded deadlines, unsupported-input rejection before mutation, and no automatic replay. |
| Permissions and system UI | Appium's permission and notification helpers encode Android-version and OEM behavior. | **ADAPT** targeted compatibility paths. | Tap retains explicit `SYSTEM` scope and an instrumentation-supplied package allowlist rather than exposing unrestricted system lookup. |
| Screenshots and artifacts | Appium has Android screenshot fallbacks. Maestro captures command context and screenshots and exposes debug views and suite results. | **ADAPT** screenshot fallback logic and Maestro-style automatic failure bundles. | Artifacts must include screenshot, hierarchy, logcat window, command timeline, session journal, and typed terminal cause without rerunning actions. |
| Reporting | Maestro's [`TestSuiteInteractor`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt) and [`TestSuiteReporter`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-cli/src/main/java/maestro/cli/runner/TestSuiteReporter.kt) separate execution events from JUnit/HTML presentation. | **ADOPT** the event-model/report-adapter separation. | Tap will emit one canonical typed result model, then derive JSON, JUnit XML, HTML, and Flowdeck output. |
| Process supervision | Maestro CLI runner code supervises instrumentation startup, liveness, and logs. Appium host code similarly owns server launch and cleanup. | **ADAPT** startup markers, liveness checks, bounded waits, and log capture. | Tap additionally verifies PID/start token, driver instance, boot ID, forward identity, and crash-resumable journals. |
| Multi-device execution | Maestro's suite layer allocates devices and runs flows concurrently; Appium commonly uses one driver session per device. | **ADAPT** independent per-device workers and failure isolation. | Tap keeps interleaved Kotlin orchestration and must prove one disconnected device cannot stop another session. |
| User-facing test model | Appium exposes WebDriver/HTTP and Maestro exposes YAML flows. | **DO NOT COPY** either surface. | Tap remains Kotlin/JUnit 5 first, with no WebDriver compatibility layer and no YAML interpreter. |
| Python API ergonomics | uiautomator2's `d(text="Clock", className=...)` builds a lazy selector object with `.click()`, `.exists`, `.wait()`, `.wait_gone()`, child/sibling navigation, plus `app_start(stop=True)`, `app_wait`, `app_current`, key and gesture helpers (`swipe_ext`, `long_click`, `drag`), clipboard, toast and screen-state helpers. | **ADAPT** naming and coverage checklist for `clients/python` (which calls already exist, what a Python tester expects to find). | Tap's selector is a typed AST validated by the service, not kwargs; matching stays exactly-one for mutations. Compare the two surfaces when adding a Python call, not when designing the protocol. |
| Implicit waits and watchers | uiautomator2 has a global `implicitly_wait`/`settings['wait_timeout']` applied to every lookup and **watchers** that auto-dismiss popups in a background thread. | **DO NOT COPY**. | Tap waits are explicit per call with one deadline; nothing runs unattended between commands, and nothing modifies the screen behind the test's back (a watcher is an unlogged mutation). |
| On-device agent lifecycle | uiautomator2 installs an APK pair and a persistent agent, talks HTTP/JSON-RPC over a fixed forwarded port (9008), and re-initialises when the agent dies; XPath is served from a hierarchy dump on the host. | **ADAPT** the catalogue of failure modes (agent killed by the OEM, instrumentation dying with the AUT, uiautomator service already running, accessibility service conflicts) as test cases for the driver. **DO NOT COPY** the persistent agent, fixed port or XPath-over-dump model. | Tap's driver is per-session, port chosen per session and journaled, no dump on the hot path. |
| Agent-facing surface | agent-device exposes `snapshot` (accessibility tree as `@eN [role] "name"` refs, `-i` interactive-only), `press/fill @ref --settle` returning a **diff** of the tree, `screenshot`, `verify`, logs/video/traces as evidence, `.ad` replay scripts, one execution path shared by CLI, MCP and the typed client. | **ADAPT** as the design reference for a future *agent adapter over the Tap server*: snapshot-as-diagnostic with stable serialisation, settle-then-diff as an explicit call, evidence bundle shape, CLI/MCP/typed-client parity. | This is a consumer of a driver, not a driver: Tap would expose it as another client of `tap.v1`, never inside `host/`. |
| Ref-addressed elements | agent-device addresses nodes by refs that are valid only until the next snapshot/diff, and every settle re-dumps the tree. | **DO NOT COPY** into the test SDKs. | Refs are persistent node handles by another name and need a hierarchy dump per step — both invariants Tap keeps out of the hot path. An agent adapter may hold refs *on the adapter side*, translating each ref to a selector before calling the service. |
| Device state actions (group 3) | Maestro sets the device locale through a broadcast receiver in its driver app, mocks location through test providers (`setLocation`/`travel`) and adds media with `addMedia` (push + media scan); Appium toggles network with `svc`/`cmd connectivity` and pushes/pulls files over adb. | **ADAPT** the locale receiver (Tap's driver app, `CHANGE_CONFIGURATION`), test-provider mock location, `svc`/`cmd connectivity` switches and push + `scan_file` media. | Every change is journaled and read back (`DEVICE_SETTING` on a mismatch) and restored on detach; media and files are only ever created, never overwritten, and removed again. Bytes stream through the server, which never gets a host path. No code was copied; details in `.docs/device-actions.md`. |

## Immediate implementation order

1. Complete protocol fencing, disconnect isolation, dense-screen benchmarks, accessibility
   inventory, and process-identity matrix before expanding the public API.
2. Adapt Appium compatibility behavior only when a failing device/API regression demonstrates
   the need, keeping each workaround narrow and tested.
3. Build a typed execution-event and artifact model before JUnit XML, HTML, or Flowdeck
   adapters, following Maestro's separation of execution from presentation.
4. Add failure screenshots, hierarchy, logcat, and journal capture without changing action
   replay semantics.

## Proven Tap-specific behavior

The Phase 0 device matrix now proves that an authenticated connection rejects a duplicate
request ID, rejects an old-generation request, and then rejects reuse of that consumed ID. It
also proves that abruptly disconnecting one device's authenticated transport does not stop a
second device's existing session. These guarantees are intentionally stronger than adopting
either upstream session model unchanged.
