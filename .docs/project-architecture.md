# Tap Project Architecture

Date: 2026-09-15

Status: current Phase 0 implementation overview. The normative future design remains
[`android-e2e-framework-implementation-plan.md`](android-e2e-framework-implementation-plan.md).

## Overview

Tap is a host-driven Android E2E framework. Test coordination runs in a Kotlin/JVM process
on the host computer, while a small dedicated driver runs on each Android device.

```text
Kotlin host process
+-- starts and supervises ADB processes
+-- installs APKs and controls AUT lifecycle
+-- creates one isolated session per device
+-- coordinates devices concurrently
+-- sends authenticated RPC commands
        |
        | ADB-forwarded TCP socket
        v
Android driver instrumentation
+-- authenticates the host
+-- evaluates selectors with UiAutomator
+-- performs input and scrolling
+-- returns bounded structured responses
        |
        v
Application under test
```

ADB is used for setup, lifecycle, forwarding, and recovery. Ordinary UI commands use the
persistent RPC connection after startup; they do not launch one ADB process per action.

## Repository Layout

```text
tap/
+-- protocol/
+-- driver/
+-- host/
+-- fixture-app/
+-- .docs/
+-- build.gradle.kts
+-- settings.gradle.kts
+-- gradlew
```

The dependency direction is intentionally narrow:

```text
host ----------------> protocol
driver androidTest --> protocol
fixture-app            independent
```

The host has no Android API dependency. The protocol has no Android, host, or coroutine
runtime dependency.

## Protocol Module

Location: `protocol/src/main/kotlin/com/company/tap/protocol/`

The pure Kotlin protocol module is shared by the host and Android driver. It defines:

- operations such as `TAP`, `SET_TEXT`, `TYPE_TEXT`, and `SCROLL_UNTIL`;
- text, raw Compose resource-tag, and qualified Android resource selectors;
- request, response, authentication, and framing models;
- HMAC-SHA-256 mutual authentication;
- monotonic request IDs; and
- payload, text-input, and timeout limits.

A request currently resembles:

```kotlin
Request(
    sessionId = "...",
    sessionGeneration = 1,
    operation = Operation.TAP,
    selector = Selector(
        kind = SelectorKind.RAW_RESOURCE,
        value = "composeButton",
    ),
    timeoutMs = 5_000,
)
```

JSON messages are carried inside bounded binary frames:

```text
TAP1 header | frame type | request ID | payload length | JSON payload
```

Relevant files:

- `protocol/src/main/kotlin/com/company/tap/protocol/Messages.kt`
- `protocol/src/main/kotlin/com/company/tap/protocol/FrameCodec.kt`
- `protocol/src/main/kotlin/com/company/tap/protocol/Authentication.kt`

## Driver Module

Location: `driver/`

The device driver uses the dedicated package `com.company.tap.driver`. Its instrumentation
APK targets that package and starts a persistent UiAutomator command server. The driver and
AUT therefore do not share a package, UID, or process.

The server:

1. Binds a TCP socket to device loopback only.
2. Authenticates the host with a per-session random secret.
3. Accepts bounded framed requests.
4. Executes selectors and actions through AndroidX UiAutomator.
5. Returns structured responses.

Because it is independent from the AUT, the driver survives AUT force-stop, clear-data, and
relaunch operations.

Current operations are:

```text
HEALTH
EXISTS
TAP
WAIT_VISIBLE
DUMP_HIERARCHY
SET_TEXT
TYPE_TEXT
SCROLL_UNTIL
SYNC_BOOTSTRAP
SYNC_STATE
```

`SET_TEXT` uses accessibility text replacement. `TYPE_TEXT` is deliberately separate: it
focuses an editable element, converts supported characters into Android key events, injects
complete key sequences, and cleans up pressed keys after failures.

`SCROLL_UNTIL` resolves a requested container, searches only inside that container, performs
bounded scroll segments, and compares visible accessibility state to detect end-of-content.

Most spike logic currently remains in
`driver/src/androidTest/kotlin/com/company/tap/driver/TapDriverServerTest.kt`. It can be split
into server, selector, and command-engine components after Phase 0 behavior stabilizes.

## Host Module

Location: `host/src/main/kotlin/com/company/tap/host/`

The host is a Kotlin/JVM command-line application with three current responsibilities.

### ADB supervision

`Adb.kt` invokes the installed `adb` executable through `ProcessBuilder`. Every operation is
serial-specific:

```text
adb -s SERIAL install ...
adb -s SERIAL shell am start ...
adb -s SERIAL shell pm clear ...
adb -s SERIAL forward tcp:0 tcp:DEVICE_PORT
```

### Driver RPC

`DriverClient.kt` opens the forwarded local TCP port, performs mutual authentication,
allocates monotonically increasing request IDs, applies request-relative socket deadlines,
and serializes requests and responses.

Authentication negotiates application protocol `1.0` separately from framing version 1. The
canonical HELLO/CHALLENGE/NEGOTIATION transcript binds the selected version, capabilities,
Android API, component build IDs, driver instance, and supported operation versions into both
host and driver HMACs. Requests carry operation version 1; unknown versions return
`UNSUPPORTED` after consuming the request ID. See
[`protocol-contract.md`](protocol-contract.md).

Mutating selector resolution is cardinality-safe. The driver collects matches only from the
focused window in the selected package and transfers ownership of exactly one `UiObject2` to
the command. It recycles all candidates and returns `NOT_FOUND` or `AMBIGUOUS` before input
when cardinality is not one. Tap and text paths check their absolute request deadline again
immediately before mutation.

```text
DriverClient
    -> localhost:dynamic-port
    -> adb forward
    -> device 127.0.0.1:27183
    -> UiAutomator driver
```

### Phase 0 orchestration

`PhaseZeroMain.kt` is the current executable validation scenario. For each device it installs
the APKs, starts instrumentation, creates a forward, proves invalid authentication rejection,
launches the fixture, exercises commands, checks AUT lifecycle independence, benchmarks
lookup behavior, and tears the session down independently. Startup fault checks seed both
`CREATING` and `ACTIVE` stale forwards plus a live orphan driver, occupy the first reserved
driver port from the fixture UID, and exercise changed-boot quarantine against an isolated
journal.

A dedicated fencing generation first consumes request ID 1 during the health check, proves
that replaying ID 1 returns `DUPLICATE_OR_STALE`, submits ID 2 with the previous generation and
requires `SESSION_MISMATCH`, then proves that ID 2 cannot be reused with the current generation.
The server advances its request watermark before operation validation, so even a rejected
old-generation request cannot later be repurposed for a mutation.

Transport fault generations arm one test-only driver hook at a time: before request
acceptance, after acceptance, or immediately after a dedicated tap mutation. The host tracks
`NOT_WRITTEN`, `WRITING`, `WRITTEN`, and `TERMINAL_RESPONSE`, reports transmitted mutation
loss as `INDETERMINATE`, permanently poisons that client, cleans the exact session unit, and
rebuilds with a new session ID, generation, secret, forward, and driver instance. A
process-scoped fixture counter and pinned AUT PID/start token prove the action is not replayed.

The late-work fault delegates delayed mutation into the AUT process, closes transport, and
blocks the instrumentation command beyond cleanup grace. The host records a reset-required
quarantine before terminating the driver and refuses same-boot recovery. Reset recovery is
durable across host interruption: it retries reboot while the boot identity is unchanged, or
completes only when a previously recorded reset observes a new boot. It then waits for boot
and package readiness, wakes and dismisses non-secure keyguard, clears AUT state, and starts a
higher-generation verification session.

Devices run concurrently through Kotlin structured concurrency:

```kotlin
serials.map { serial ->
    async(Dispatchers.IO) {
        runDevice(serial, ...)
    }
}.awaitAll()
```

Each device receives its own secret, session ID, forwarding port, socket, driver process, and
serialized command stream.

`--product-probe` is a separate general-purpose validation path for real applications. It
accepts APK/package/activity metadata and a sequence of text navigation/readiness triples,
then measures cold lookup, warm direct-query percentiles, hierarchy percentiles, host XML
parse/query percentiles, and an AUT-package-scoped accessibility inventory. It reuses Tap's
driver, authentication, lease, generation, journal, forwarding, and cleanup infrastructure;
it is not a YAML flow runner or a product-specific test API. NextPlayer is the current complex
demo, documented in [`nextplayer-demo-validation.md`](nextplayer-demo-validation.md).

When two or more devices are supplied, the final validation sessions rendezvous at a bounded
barrier. The designated fault device abruptly closes only its authenticated RPC socket; each
other participant must then complete three health requests on its existing session before the
faulted device may finish. This explicitly proves that transport loss and client poisoning are
contained to one per-device worker rather than cancelling healthy sessions.

Per-device ownership is protected by a machine-wide filesystem lease under
`~/.tap/sessions`. An atomically replaced, fsynced journal records boot identity, session and
generation, forwarding, driver PID/start token, and driver-instance identity. Startup removes
only exact journal-owned forwards after proving the old driver identity is gone; failures and
corrupt state are quarantined instead of guessed through. Reset-required quarantines also
record their originating boot ID so reboot recovery can resume safely after a host crash.

## Fixture Application

Location: `fixture-app/`

The fixture application proves framework behavior against both Android Views and Compose.
It contains:

- a traditional Android button and status view;
- separate direct-text and key-event `EditText` fields;
- an `OnKeyListener` signal proving real key-event delivery;
- a Compose button and state;
- a Compose `LazyColumn`; and
- a separate native `ListView` activity; and
- a loopback-only port-occupier activity used by startup retry fault validation; and
- a signature-protected delayed-mutation hook used only by the late-work isolation gate.

Compose tags are projected into accessibility resource names with:

```kotlin
Modifier.semantics { testTagsAsResourceId = true }
```

A tag such as `Modifier.testTag("composeButton")` can then be selected with
`By.res("composeButton")` from the external driver.

Relevant files:

- `fixture-app/src/main/kotlin/com/company/tap/fixture/MainActivity.kt`
- `fixture-app/src/main/kotlin/com/company/tap/fixture/ViewListActivity.kt`
- `fixture-app/src/main/res/layout/activity_main.xml`
- `fixture-app/src/main/res/layout/activity_view_list.xml`

## Build

The Android build currently requires JDK 17:

```bash
JAVA_HOME="/tmp/opencode/temurin17" ./gradlew \
  :fixture-app:assembleDebug \
  :driver:assembleDebugAndroidTest \
  :host:installDist
```

The resulting artifacts are:

```text
fixture-app/build/outputs/apk/debug/fixture-app-debug.apk
driver/build/outputs/apk/debug/driver-debug.apk
driver/build/outputs/apk/androidTest/debug/driver-debug-androidTest.apk
host/build/install/host/bin/host
```

## Current Maturity

The Phase 0 vertical slice currently proves:

- a dedicated persistent driver process;
- authenticated and bounded host/driver RPC;
- concurrent multi-device execution;
- View and Compose selectors;
- explicit AUT package confinement and allowlisted system-window selectors;
- direct and key-event text input;
- View and Compose list scrolling with no-progress detection;
- AUT lifecycle independence;
- signature-protected cross-UID synchronization;
- host-verified process identity and stable-idle observation;
- synchronization invalidation after AUT process restart;
- durable session journaling and exact orphan recovery;
- conservative transport-loss classification without action replay;
- late/uninterruptible mutation quarantine with mandatory reboot and AUT-state reset;
- same-generation duplicate/stale and old-generation request fencing; and
- cross-device transport-disconnect isolation.

The current device matrix includes an API 34 emulator and an API 29 Samsung device.

Still to be built or proven:

- public Kotlin `Device`, `App`, and `Element` APIs;
- a JUnit 5 extension;
- screenshots and failure artifacts; and
- JUnit XML, JSON event, HTML, and Flowdeck reporting.

The current host intentionally calls protocol operations directly. A polished SDK should be
added only after the device behavior, protocol, lifecycle, and failure semantics are proven.
