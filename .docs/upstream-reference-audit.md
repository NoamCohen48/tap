# Appium and Maestro Reference Audit

Date: 2026-09-17

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

All three repositories identify their audited source as Apache-2.0. File history and headers
must still be checked before any future source import.

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
| Settling / animations | Maestro's [`Maestro.waitForAppToSettle`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-client/src/main/java/maestro/Maestro.kt) runs implicitly after every command (`waitForWindowUpdate` loop + hierarchy equality), `waitForAnimationToEnd` diffs screenshots (≤ 0.5 %), and `retryIfNoChange` re-taps when the hierarchy did not change. | **ADAPT** only the observation idea: `WAIT_SCREEN_STABLE` combines an accessibility-tree fingerprint with a downscaled pixel diff, driven by accessibility events. **DO NOT COPY** the implicit per-command settle (it taxes every action and never converges on live screens) or `retryIfNoChange` (replays a mutation). | Settling is opt-in per call (`awaitScreenStable`); no code was copied. Tap also bounds UiAutomator's implicit `waitForIdle` to 1 s so busy screens cannot stall commands past their deadlines. |
| Input | Appium's interaction and element handlers cover key injection, click, gesture, and text edge cases. Maestro's [`AndroidDriver`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt) keeps these behind a typed device API. | **ADAPT** proven Android input compatibility behavior behind Tap operations. | Preserve exact completion, bounded deadlines, unsupported-input rejection before mutation, and no automatic replay. |
| Permissions and system UI | Appium's permission and notification helpers encode Android-version and OEM behavior. | **ADAPT** targeted compatibility paths. | Tap retains explicit `SYSTEM` scope and an instrumentation-supplied package allowlist rather than exposing unrestricted system lookup. |
| Screenshots and artifacts | Appium has Android screenshot fallbacks. Maestro captures command context and screenshots and exposes debug views and suite results. | **ADAPT** screenshot fallback logic and Maestro-style automatic failure bundles. | Artifacts must include screenshot, hierarchy, logcat window, command timeline, session journal, and typed terminal cause without rerunning actions. |
| Reporting | Maestro's [`TestSuiteInteractor`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt) and [`TestSuiteReporter`](https://github.com/mobile-dev-inc/Maestro/blob/01a3e442afba15f80f825492dae2e48fcfd4239c/maestro-cli/src/main/java/maestro/cli/runner/TestSuiteReporter.kt) separate execution events from JUnit/HTML presentation. | **ADOPT** the event-model/report-adapter separation. | Tap will emit one canonical typed result model, then derive JSON, JUnit XML, HTML, and Flowdeck output. |
| Process supervision | Maestro CLI runner code supervises instrumentation startup, liveness, and logs. Appium host code similarly owns server launch and cleanup. | **ADAPT** startup markers, liveness checks, bounded waits, and log capture. | Tap additionally verifies PID/start token, driver instance, boot ID, forward identity, and crash-resumable journals. |
| Multi-device execution | Maestro's suite layer allocates devices and runs flows concurrently; Appium commonly uses one driver session per device. | **ADAPT** independent per-device workers and failure isolation. | Tap keeps interleaved Kotlin orchestration and must prove one disconnected device cannot stop another session. |
| User-facing test model | Appium exposes WebDriver/HTTP and Maestro exposes YAML flows. | **DO NOT COPY** either surface. | Tap remains Kotlin/JUnit 5 first, with no WebDriver compatibility layer and no YAML interpreter. |

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
