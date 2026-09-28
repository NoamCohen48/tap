# Code review — implementation status

Status of every finding in [`code-review.md`](code-review.md), audited item by item against the
code at `679b5e8` (2026-09-26). In the review, ✔ means "re-checked by the reviewer", **not**
"fixed"; this file is the fix status. Update a row in the same change that fixes it.

| Status | Count |
|---|---:|
| Fixed | 136 |
| Obsolete (code removed or redesigned: one schema, driver split, scroll_until removed) | 8 |
| Partial | 8 |
| Open | 10 |
| Won't fix (accepted risk, see decisions) | 2 |
| Deferred (synchronization is WIP, see below) | 7 |

The counts cover all 171 findings; Open, Partial, Deferred and Won't fix are exactly the rows below.

Fixed/obsolete items are not repeated below except where a remark matters. Obsolete: B-5,
P-1, P-2, P-10, DR-11 (`scroll_until` left the driver in protocol 4.0), DM-12 (not a bug: always under `lifecycleLock`), DM-14, and P-12's claim is
moot (protobuf caps decode recursion at 100; depth is re-checked in `CommandValidation`).

## Decisions taken while reviewing the gaps

- **Driver assumes nothing about the app (2026-09-26, user).** The driver reports what it did
  and what Android returned; it does not verify effects. Consequences for text input
  (resolves DR-3, DR-4 and the verification half of DR-6):
  - `set_text` / `clear_text`: resolve exactly one node, perform `ACTION_SET_TEXT` on that
    node, report `ACTION_REJECTED` only when Android refuses the action. No `isEditable`
    pre-check, no read-back of the field's text.
  - `type_text`: inject the key events, report whether each was accepted. No `isEditable`
    pre-check, no read-back (since 2026-09-28 also no click or settle; see below).
  - Nothing is re-resolved after the action, so a selector that matches differently after
    the edit (the edited text, another element that now shows it, several matches) cannot
    affect the result. Tests assert outcomes with a selector that survives the edit
    (`element(resourceId("email")).waitUntil.textEquals(…)`).
  - Any other driver behaviour change is proposed to the user first.
- **Driver-assumption pass (2026-09-28, user; protocol 4.0).** Ten proposals reviewed one by
  one:
  - Selector scope: `system` accepts any package (no allowlist, `allowed_system_packages`
    removed from attach), plus a new `any_window` scope over every window.
  - `tap` / `long_tap`: no `isEnabled` pre-check. `scroll`: no `isScrollable` pre-check.
    `swipe` / `scroll` return `done` (`moved` removed). `NOT_INTERACTABLE` is no longer emitted.
  - `scroll_until` removed from the driver; `scrollUntil` / `scroll_until` are client loops of
    `exists` + `scroll` in both SDKs (resolves DR-11, DM-15, P-15).
  - Key-up events carry their press's `downTime` (DR-6).
  - Snapshots and selectors use Android's raw text; `showing_hint` flags a displayed hint
    (DR-10: observations and selectors now agree).
  - Launch returns after `am start -W`; waiting for the window is the client's explicit
    `awaitAppVisible` / `awaitScreenStable`.
  - `type_text` has no target: it injects key events into the current focus, with no click
    and no settle (`TypeText.selector` reserved, `DEADLINE_AFTER_FOCUS` gone). Element
    `typeText` in both SDKs is `tap` → `await().focused()` (opt out with `awaitFocus = false`)
    → `device.typeText`. `set_text` stays the accessibility action; both are kept.
- **Synchronization is WIP (2026-09-28, user).** The sync SDK/provider path is ignored for
  now; its findings (DR-12, DR-13, DR-14, SY-1, SY-2, SY-4, H-15) are Deferred, not Open.
- **Threat model: the host and the devices belong to the tester (2026-09-28, user).** Tap
  runs in a development or CI environment where the machine and every attached device are the
  user's own. The per-launch secret and HMAC handshake exist to bind a host to *its* driver
  session (fencing stale generations, never talking to the wrong driver), not to defend
  against other local users or hostile apps. Hardening that only matters against those is
  not done; revisit before Tap runs on shared multi-user hosts or device farms.
  - DR-16 (secret visible in host `ps` via `am instrument` argv): accepted. The review's
    0600-file fix also does not work as written: the driver runs as its own app UID and
    cannot read a shell-owned 0600 file in `/data/local/tmp` (SELinux blocks app reads of
    shell data too). If it is ever needed, feed the `am instrument` command through
    `adb shell`'s stdin so the secret never appears in a host argv; on the device only
    shell and root can read another process's argv (Android 7+ `hidepid`).
  - DR-15 (an app on the device can keep the host out of the driver's accept loop by
    connecting and staying silent through the 10 s handshake timeout): accepted. It is a
    denial of service only; without the secret the app cannot authenticate. If it is ever
    needed, the stronger fix is a `localabstract:` socket whose accept checks the peer UID
    (adbd is shell 2000, or root) and closes anything else before reading, making each
    rejection immediate; concurrent handshakes with a short timeout only raise the bar.
- **P-9 was wrongly marked "leave".** CANCELLED before acceptance is safe to retry, but the
  per-code flags cannot express "when"; DR-22 (`PAYLOAD_TOO_LARGE` flagged mutating) is the
  same problem. Needs a per-response `may_have_mutated`.

## Open and partial, by area

Severity from the review (H/M/L/N). Size: S < half a day, M ≈ a day, L = multi-day.

### Driver (device/driver, device/sync-sdk, fixture-app)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| DR-12 | H | Deferred | Driver `<queries>` names only the fixture's sync provider; a real AUT's provider is invisible, so idle sync cannot work outside the fixture | S |
| DR-3 | H | Fixed | Text verification re-resolves the selector after the edit → false `TEXT_MISMATCH`/`FOCUS_TIMEOUT`. Resolved by the decision above | M |
| DR-5 | H | Partial | `NodePredicate` reads node info once per node, but traversal still walks `UiObject2` (unverified cost; measure first) | M–L |
| DR-4 | M | Fixed | Password fields can never match expected text. Resolved by the decision above | S |
| DR-6 | M | Fixed | Verification removed; key-up events carry the press's `downTime` (`KeyInputTest`) | S |
| DR-10 | M | Fixed | Snapshot text is raw like the selectors, plus `showing_hint` (`InputTest`, `MainScreenTest`) | S |
| DR-13 | M | Deferred | Signature permission defined only in sync-sdk; install order can drop the grant (unverified) | S |
| DR-14 | M | Deferred | One provider timeout poisons sync for the session; call thread leaks; `SecurityException` in the generic catch | S |
| DR-15 | M | Won't fix | `ServerSocket(port, 1)`, serialized accept, 10 s handshake timeout: an app on the device can hold the accept loop. Accepted under the trusted-host threat model (decisions above) | — |
| DR-16 | M | Won't fix | Session secret passed as `-e tapSecret` in `am instrument` argv. Accepted under the trusted-host threat model (decisions above) | — |
| DR-21 | L | Fixed | No common version: the driver answers HELLO with `AUTH_RESULT{ok=false, UNSUPPORTED}` before closing; the host reports it (`DriverClientTest`, `FencingTest`) | S |
| DR-22 | L | Open | `dumpHierarchy` → `PAYLOAD_TOO_LARGE`, a may-have-mutated code (see P-9) | S |
| P-15 | L | Fixed | `swipe`/`scroll` return `done`; no fabricated boolean (`ScrollTest`) | S |
| SY-1 | L | Deferred | `processStartUuid` and `sessionIdentity` have the same lifetime | S |
| SY-2 | N | Deferred | `require(method == "state")` throws IAE across binder | S |
| SY-3 | M | Partial | No password, WebView, popup/spinner or text-changing-selector fixtures | M |
| SY-4 | L | Deferred | `FixtureFaultProvider` reuses the sync signature permission | S |
| SY-5 | N | Fixed | Every fixture activity's KDoc names the scenarios it serves (documented rather than renamed: tests and docs launch them by name) | S |
| X-3 | M | Partial | Driver `internal class ClientConnection` collides with the daemon/SDK name; Python `TapServer` vs Kotlin `TapClient` | S |

### Protocol and command engine (contracts/protocol, device/driver/command-engine)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| P-9 | M | Open | Error flags per code (`ErrorCode.kt:51-74`); need per-response `may_have_mutated` | M |
| P-7 | M | Open | `enabledCapabilities` negotiated, MAC'd, stored, never read: gate on it or delete it | S |
| P-8 | M | Fixed | `UIAUTOMATOR_VERSION` is generated from the catalog (`libs.versions.uiautomator`) next to `ENGINE_VERSION` | S |
| P-14 | L | Fixed | The `ok` factory is gone (`Response.ok` is only the property). `CommandFailure` stays in the contract on purpose: contract validation (`CommandValidation.kt`, `Operations.kt`) throws it, so it is not driver-internal | S |
| E-4 | M | Fixed | PONGs go through a pending-pong queue the writer drains before every blob chunk, so a ping is answered mid-blob | M |
| E-8 | M | Fixed | `aPongIsNotQueuedBehindTheRestOfABlob` (`CommandPipelineTest`) | S |
| E-3 | L | Fixed | `CommandPhase` removed | S |
| E-5 | L | Fixed | `sleep` follows the injected clock and waits on the command's cancel signal (`sleepFollowsTheInjectedClockAndEndsOnCancel`) | S |
| E-6 | L | Fixed | `awaitTermination` bounds the writer join by the remaining time | S |
| E-7 | N | Fixed | Engine type renamed `PendingCommand`; `transferBlob` returns `BlobResult` | S |

### Host core (host/core)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| B-4 | H | Fixed | A driver package signed by another build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) is uninstalled and reinstalled once; the AUT never is (`AdbTest`). Installed `versionName` is checked against the bundled build. Release signing with a project key stays a release-engineering option | S–M |
| H-5 | M | Fixed | `BlobReceiver` copies each chunk once into a buffer of the announced size, hashes incrementally and hands the array on uncopied (`BlobReceiverTest`) | S |
| H-15 | M | Deferred | `awaitIdle` runs `process()` before and after every poll (`AppLifecycle.kt:221-229`). Synchronization is WIP | S |
| H-19 | M | Open | `DeviceSession.open` ≈190 lines with a `suspendCancellableCoroutine` handoff | L |
| H-20 | M | Fixed | The lock holder records its PID in the lock file; `isLeased` reads it (advisory, no lock taken; a dead PID reads as free) (`SessionJournalTest`) | S |
| H-22 | M | Partial | Primitives shared; HELLO/CHALLENGE/AUTH state machine still hand-written in driver, client and fake | M |
| H-23 | M | Partial | `BlobReceiverTest`, lease timeout/probe tests and every `recoverJournal` branch (`RecoverJournalTest`) added; AppLifecycle deadline splitting is still only covered on devices | M |
| H-3 | L | Fixed | One shared whitespace `Regex` in `Adb.kt` | S |
| H-4 | L | Fixed | `isPortListening` tolerates a missing `tcp6` when the other table was read, and still fails on a failed read (`AdbTest`) | S |
| H-9 | L | Partial | `lastWriteNanos` is set after a completed write and the ping budget is clamped, not truncated. Still one `async` per write: it is how a cancelled caller tells "not started" from "started" (`INDETERMINATE`); a channel writer needs the same handshake | S |
| H-12 | L | Fixed | The real device port is journaled via `onStarting` (`DEVICE_PORT` is only the placeholder where none is known); the output drain spots the readiness marker line by line instead of rescans. The instrumentation child stays outside `Adb`: its serial lane admits one command at a time, and the attempt reaps its own child (`cleanupAttempt`) | S |
| H-16 | L | Fixed | `grantPermission` verifies the grant through `dumpsys package`; the constructor is internal (only `DeviceSession.app` creates one). The `syncIdentity` part is Deferred with synchronization | S |
| H-24 | L | Fixed | Selector values are JSON-escaped when rendered; an undecodable chunk is `BLOB_MALFORMED` | S |

### Daemon and CLI (host/daemon)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| DM-18 | M | Fixed | `CliTest`: per-command option validation, `status`/`stop` without a daemon and with a stale descriptor (`parseCommandLine`, `status` and `stop` no longer exit the process). `start`/`serve` run in every fixture and Python suite | M |
| DM-15 | L | Fixed | `scroll_until` removed; `DIR_UNSPECIFIED` is rejected uniformly for swipe/scroll | S |
| DM-17 | N | Fixed | Result shaping moved out of `TapDaemon` into `DeviceService` (its only caller) | S |
| API-7 | L | Fixed* | `stop` never compares `Info.pid` with the descriptor pid | S |

### Kotlin client (clients/kotlin)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| K-15 | L→bug | Fixed | `DeviceAdmission` carries every admitted device of the enclosing operations, so detaching A inside B's operation nested in A's fails fast (`TapClientTest`) | M |
| J-5 | L→bug | Fixed | `DeviceBarrier` uses a plain monitor; withdrawal can no longer be cancelled | S |
| K-4 | M | Open | Proto types in the public API (`Device.info(): DeviceInfo`, `execute: CommandResult`); `App.ProcessIdentity` clashes with the proto name. Breaking — ask first | L |
| K-9 | M | Open | `tapScope`/`ensureTapBound` mandatory; no `use {}`/`attach {}` helpers | M |
| K-1 | M | Open | Mutex + 5 atomics; `established` write-only; `register()` unused; events capped at 200 | M |
| K-17 | M | Fixed | `App` unit test: targets, timeouts, activity, chunked APK upload, process identity and a refused grant (`TapClientTest`) | S |
| K-11 | L | Fixed | The internal node combinators are private `conjunction`/`disjunction`; `allOf`/`anyOf` are only the public selector entry points | S |
| K-13 | L | Partial | Device-side waits carry no last observation | S |
| K-14 | L | Fixed | The process starter is injected per call; `tap start`/`stop` wait on `process.onExit()` instead of polling | S |
| K-16 | N | Fixed | The rethrow-only catch in `connect` is gone | S |
| J-6 | L | Fixed | The primary failure is JUnit's `executionException` only; the recording interceptors, the exception handler and `TestState.failure` are gone | S |
| J-7 | L | Partial | Devices are captured concurrently, each within its own 60 s budget. No `ArtifactSink` hook yet (new public API: needs a decision) | S–M |
| J-9 | L | Open | No opt-in class-level device reuse | M |
| S-2 | L | Fixed | `runCatching` + one assertion instead of `fail()` inside `catch (AssertionError)` | S |
| S-3 | L | Fixed | `MultiDeviceTest` asserts its two roles hold distinct serials | S |

### Python client (clients/python)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| PY-9 | L | Fixed | Failure artifacts are captured per device on their own thread within a 60 s per-device budget; a straggler is abandoned, not joined | S |
| PY-12 | N | Open | `TapServer.__init__` probes `Info` when discovering from `daemon.json` | S |

### Build

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| B-3 | M | Open | bundleDriver, fixture-tests and validation deviceTest wire APKs by `dependsOn` + hardcoded path; no consumable configuration | M |
