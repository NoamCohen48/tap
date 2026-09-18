# Custom Android E2E Framework - Feasibility Research

Date: 2026-09-14

Status: technically feasible, conditional go.

This report records research evidence. The normative build sequence, acceptance criteria,
and estimates are in
[`android-e2e-framework-implementation-plan.md`](android-e2e-framework-implementation-plan.md)
and supersede the initial spike size described in this report.

This report validates the architecture proposed in
[`android-e2e-framework-design.md`](android-e2e-framework-design.md) against current Android,
ADB, AndroidX UiAutomator, Jetpack Compose, and WebView behavior.

## Verdict

The framework is technically feasible. The host/device split, ADB port forwarding,
device-side accessibility matching, and concurrent control of multiple devices all use
supported Android mechanisms.

Proceed with a 5-8 day risk-focused spike before committing to the full implementation. The persistent
instrumentation server must run in a dedicated helper package rather than in the
application under test (AUT). This is a required architecture change, not an optional
hardening step.

## Required process layout

Use three separately installed packages:

```text
e2e-driver.apk
  package: com.example.e2e.driver
  permission: android.permission.INTERNET

e2e-driver-test.apk
  instrumentation targetPackage: com.example.e2e.driver
  contains the instrumentation runner and command server

application-under-test.apk
  independent package controlled through UiAutomator and host ADB commands
```

Android loads instrumentation code into the instrumentation target's process and UID. If
the test APK instead targets the AUT, the server shares the AUT lifecycle. `force-stop`,
`pm clear`, process-death testing, application crashes, package replacement, and a genuine
cold start would then terminate or invalidate the server.

The dedicated driver arrangement allows the host to stop, clear, crash, reinstall, and
cold-start the AUT without deliberately killing the automation session.

## Validated capabilities

### Long-running instrumentation server

- A test method or custom `Instrumentation.onStart()` can remain alive in a socket loop.
- `adb shell am instrument -w` waits for instrumentation completion and has no intrinsic
  AOSP deadline.
- The process still needs supervision. Device disconnects, reboot, driver crashes, package
  updates, OEM behavior, or CI-level timeouts can end the session.
- Do not use Android Test Orchestrator for this process. Its test isolation and optional
  data clearing conflict with a persistent server.

### ADB transport and multiple devices

- `adb -s SERIAL forward tcp:HOST_PORT tcp:DEVICE_PORT` creates a forwarding rule for one
  selected device.
- `tcp:0` can allocate an available host port.
- Each device may use the same device-side port, but needs its own host-side forwarding
  rule and connection.
- Forwarding rules must be recreated after `adbd` or device reconnection.
- Bind the device server to loopback rather than all network interfaces, and authenticate
  each session with a random secret. Other applications on the device may otherwise try
  to connect to a broadly exposed or unauthenticated socket.

### Cross-application UiAutomator control

- UiAutomator can interact with the AUT, third-party applications, permission dialogs,
  system UI, the IME, and other accessible windows without running inside the AUT.
- It can only see the accessibility projection. Custom canvas/OpenGL content, video
  surfaces, hidden semantics, inaccessible WebViews, password values, secure content, and
  private displays may be incomplete or unavailable.
- Generic selectors can match the wrong window. Window, package, display, and Z-order
  constraints should be represented where needed.

### Lazy element model

The proposed lazy-selector `Element` is sound. `UiObject2` is tied to an underlying
accessibility node and may become stale when that node is replaced or destroyed. Resolve
the selector for every host command. Keep a `UiObject2` only within one atomic device-side
operation, where staleness must still be handled.

### Multi-device orchestration

One host task or coroutine per device, serial-specific ADB commands, per-command deadlines,
and structured cancellation form a viable orchestration model. Cross-device propagation
must be expressed as a condition wait on the observing device rather than a fixed sleep.

## Corrections to the original assumptions

### Selector execution and performance

`BySelector` matching executes in the instrumentation/driver process over accessibility
node snapshots. It does not execute inside the AUT process. Accessibility node property
getters are not each a separate IPC call; navigation, refreshes, actions, and uncached
queries can involve Binder calls.

The performance strategy remains sound because device-side matching avoids:

- serializing the complete hierarchy to XML;
- transferring that XML through ADB;
- parsing it on the host; and
- evaluating XPath on the host.

`findObject` can stop at the first match, while a dump must walk and serialize the
hierarchy. Actual latency still depends on hierarchy size, accessibility caching, window
retrieval, idle behavior, watchers, and selector complexity. The proposed `50-150 ms`,
`500 ms-3 s`, and `20x` values are benchmark hypotheses, not Android guarantees.

### Selector composition

`BySelector` supports property conjunction, regex values, child, descendant, parent,
ancestor, depth, and display constraints. It does not provide a general index, OR, NOT,
sibling, nearest-element, or nth-match operator. Some nested relational combinations are
also restricted.

Custom accessibility traversal can implement missing relationships, but each operation
must be explicitly designed and versioned in the protocol.

### Scrolling and input

- `BySelector.scrollUntil` does not exist.
- Scrolling is performed through `UiObject2.scrollUntil`, other `UiObject2` gesture APIs,
  modern UiAutomator helpers, or legacy `UiScrollable`.
- Offscreen lazy content may not exist in the accessibility tree. The driver must find a
  scrollable container and repeatedly scroll and search.
- `setText` uses accessibility text replacement and may not exercise actual IME or key
  event behavior. Provide separate direct-text and realistic-keyboard operations if both
  semantics are needed.

### Current UiAutomator baseline

The design should be evaluated against the current AndroidX UiAutomator API rather than
only the older `BySelector`/`UiObject2` surface. Current APIs add predicate-based element
matching, default waits, stability waiting, window-scoped searches, screenshots, app
lifecycle helpers, and permission-dialog handling.

The legacy APIs remain usable, but the protocol should not be finalized before comparing
their capabilities with the newer APIs. Any built-in default wait must also be explicitly
configured if the framework retains no-retry behavior.

## Compose interoperability

`Modifier.testTag` becomes visible to UiAutomator when `testTagsAsResourceId = true` is
enabled on the relevant semantics subtree. Compose writes the tag verbatim to
`AccessibilityNodeInfo.viewIdResourceName`, so the expected selector is normally:

```kotlin
By.res("playButton")
```

This differs from a traditional View resource such as:

```text
com.example:id/playButton
```

The wire protocol should therefore distinguish a raw resource name from a package/id
resource selector instead of normalizing both into one fully qualified form.

UiAutomator sees Compose's accessibility projection, not the Compose testing semantics
tree. Semantics merging, `clearAndSetSemantics`, pruning, clipping, and lazy composition
can remove or relocate properties. A tag-only node can also be considered unimportant for
accessibility, so compressed hierarchy mode should not be enabled when those nodes are
required.

## App-owned synchronization hook

A debug-only `ContentProvider.call()` endpoint is feasible and remains a high-value
addition for applications the team owns.

Recommended constraints:

- Add the component only in a debug/E2E variant manifest; do not merely guard a production
  component with `BuildConfig.DEBUG`.
- A standalone driver has a different UID, so an unexported provider is inaccessible.
- Export the provider and protect it with a custom signature-level permission shared by
  the E2E app and driver signing certificate.
- Declare package/provider visibility in the driver where current Android package
  visibility rules require it.
- Return initialization state, process/session identity, generation, counters by category,
  and the last state transition time.

An OkHttp dispatcher counter alone is not application idleness. It can miss follow-up
coroutine work, streaming responses, WebSockets, additional clients, database operations,
WorkManager, push handling, rendering, and Compose accessibility publication. Long-lived
coroutine collectors must not keep the app permanently busy.

Track explicit UI-affecting operations. Define `awaitIdle` as zero relevant counters for a
short quiet interval with a stable generation. After that, still wait for the actual UI
postcondition. The hook substantially reduces synchronization races but cannot make a
subsequent UiAutomator assertion atomic.

## WebView

Treat WebView automation as a separate DOM backend rather than stretching accessibility
selectors to cover it.

- Enable `WebView.setWebContentsDebuggingEnabled(true)` only in the E2E/debug build.
- Initially delegate DOM automation to ChromeDriver rather than implementing CDP target
  discovery, frames, coordinate conversion, reconnects, and protocol compatibility.
- ChromeDriver must match the installed WebView/Chromium version. Pin the CI WebView or
  resolve a compatible driver dynamically.

## Initial Phase 0 acceptance criteria

The spike should prove all of the following, plus the expanded fault, late-command,
real-product accessibility, system-window, and synchronization criteria in the normative
implementation plan:

1. Install a dedicated driver APK, its instrumentation APK, and an independent AUT.
2. Start the driver with `adb shell am instrument -w`.
3. Bind an authenticated server to device loopback and connect through dynamic,
   serial-specific ADB forwarding.
4. Execute `find`, `tap`, and condition-based `waitFor` commands.
5. Select and interact with both a traditional View and a Compose `testTag`.
6. Execute `force-stop` and `pm clear` against the AUT while proving the driver survives.
7. Connect to and interleave commands across two devices.
8. Benchmark direct device-side matching against hierarchy dump, transfer, host parsing,
   and host querying on representative dense screens.
9. Disconnect and reconnect one device, then prove that session and forwarding recovery
   are deterministic.

The spike is successful only if lifecycle isolation, reconnection, and multi-device
control work. Selector latency should be recorded rather than used as a binary gate unless
it is unexpectedly close to dump-based querying.

## Go/no-go recommendation

**Conditional go.** Build the spike with the dedicated driver architecture. Continue to
the full framework if the spike validates lifecycle isolation and transport recovery.

The architecture is not blocked by Android platform limitations. The primary long-term
risks are maintenance ownership, accessibility edge cases, synchronization coverage, and
the accumulated reliability work that mature tools already contain.

## Primary sources

- [UiAutomator guide](https://developer.android.com/training/testing/other-components/ui-automator)
- [AndroidX UiAutomator releases](https://developer.android.com/jetpack/androidx/releases/test-uiautomator)
- [UiDevice source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/UiDevice.java)
- [ByMatcher source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/ByMatcher.java)
- [BySelector source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/BySelector.java)
- [UiObject2 source](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/test/uiautomator/uiautomator/src/main/java/androidx/test/uiautomator/UiObject2.java)
- [AccessibilityNodeInfo source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/accessibility/AccessibilityNodeInfo.java)
- [AOSP instrumentation command](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/am/src/com/android/commands/am/Instrument.java)
- [AOSP Instrumentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java)
- [ADB manual](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/master/docs/user/adb.1.md)
- [Command-line instrumentation tests](https://developer.android.com/studio/test/command-line)
- [Compose testing interoperability](https://developer.android.com/develop/ui/compose/testing/interoperability)
- [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)
- [Espresso idling resources](https://developer.android.com/training/testing/espresso/idling-resource)
- [Content provider manifest reference](https://developer.android.com/guide/topics/manifest/provider-element)
- [Package visibility declarations](https://developer.android.com/training/package-visibility/declaring)
- [Remote debugging WebViews](https://developer.chrome.com/docs/devtools/remote-debugging/webviews)
- [ChromeDriver on Android](https://developer.chrome.com/docs/chromedriver/get-started/android)
