# Code review — implementation status

Status of every finding in [`code-review.md`](code-review.md), audited item by item against the
code at `679b5e8` (2026-09-26). In the review, ✔ means "re-checked by the reviewer", **not**
"fixed"; this file is the fix status. Update a row in the same change that fixes it.

| Status | Count |
|---|---:|
| Fixed | 104 |
| Obsolete (code removed or redesigned: one schema, driver split, scroll_until removed) | 8 |
| Partial | 24 |
| Open | 20 |
| Deferred (synchronization is WIP, see below) | 6 |

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
  now; its findings (DR-12, DR-13, DR-14, SY-1, SY-2, SY-4) are Deferred, not Open.
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
| DR-15 | M | Open | `ServerSocket(port, 1)`, serialized accept, 10 s handshake timeout: any app can hold the accept loop | S–M |
| DR-16 | M | Open | Session secret passed as `-e tapSecret` in `am instrument` argv | M |
| DR-21 | L | Partial | Version mismatch returns false with no `AUTH_RESULT` reason | S |
| DR-22 | L | Open | `dumpHierarchy` → `PAYLOAD_TOO_LARGE`, a may-have-mutated code (see P-9) | S |
| P-15 | L | Fixed | `swipe`/`scroll` return `done`; no fabricated boolean (`ScrollTest`) | S |
| SY-1 | L | Deferred | `processStartUuid` and `sessionIdentity` have the same lifetime | S |
| SY-2 | N | Deferred | `require(method == "state")` throws IAE across binder | S |
| SY-3 | M | Partial | No password, WebView, popup/spinner or text-changing-selector fixtures | M |
| SY-4 | L | Deferred | `FixtureFaultProvider` reuses the sync signature permission | S |
| SY-5 | N | Partial | Fixture screens' purposes undocumented | S |
| X-3 | M | Partial | Driver `internal class ClientConnection` collides with the daemon/SDK name; Python `TapServer` vs Kotlin `TapClient` | S |

### Protocol and command engine (contracts/protocol, device/driver/command-engine)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| P-9 | M | Open | Error flags per code (`ErrorCode.kt:51-74`); need per-response `may_have_mutated` | M |
| P-7 | M | Open | `enabledCapabilities` negotiated, MAC'd, stored, never read: gate on it or delete it | S |
| P-8 | M | Partial | `UIAUTOMATOR_BUILD_ID = "2.4.0"` hand-copied (`Protocol.kt:26`), not generated from the catalog | S |
| P-14 | L | Partial | `CommandFailure` lives in the contract (`ErrorCode.kt:155`) | S |
| E-4 | M | Open | PONG queues behind a whole blob on the single writer; no priority lane | M |
| E-8 | M | Partial | No test for E-4 | S |
| E-3 | L | Open | `CommandPhase` written, never read | S |
| E-5 | L | Open | `CommandContext.sleep` uses `System.nanoTime` + `Thread.sleep(10)`, bypassing the clock | S |
| E-6 | L | Open | `writerDone.await()` has no timeout (`CommandPipeline.kt:215`) | S |
| E-7 | N | Open | Engine `Command` clashes with the proto name; `transferBlob` returns a `Pair` | S |

### Host core (host/core)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| B-4 | H | Partial | Bundled driver is debug-signed; no uninstall+reinstall on `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | S–M |
| H-5 | M | Open | `BlobReceiver` copies the buffer for the hash and on every `bytes` access; no incremental digest | S |
| H-15 | M | Open | `awaitIdle` runs `process()` before and after every poll (`AppLifecycle.kt:221-229`) | S |
| H-19 | M | Open | `DeviceSession.open` ≈190 lines with a `suspendCancellableCoroutine` handoff | L |
| H-20 | M | Open | `isLeased` acquires the lock to test it (`SessionJournal.kt:98-103`) | S |
| H-22 | M | Partial | Primitives shared; HELLO/CHALLENGE/AUTH state machine still hand-written in driver, client and fake | M |
| H-23 | M | Partial | Thin: lease timeout, `recoverJournal` branches, AppLifecycle timeouts | M |
| H-3 | L | Partial | `Regex("\\s+")` compiled per call (`Adb.kt:444,481,603,628,725`) | S |
| H-4 | L | Partial | `cat /proc/net/tcp /proc/net/tcp6` via throwing `exec` fails without IPv6 (`Adb.kt:442`) | S |
| H-9 | L | Open | `lastWriteNanos` set before the write; `timeoutMs.toInt()`; one `async` per write (`DriverTransport.kt`) | S |
| H-12 | L | Partial | Instrumentation child started outside Adb reaping; output rescanned | S |
| H-16 | L | Open | `grantPermission` documented as verified but isn't; `launch()` keeps `syncIdentity`; public constructor bypasses the cache | S |
| H-24 | L | Partial | Blob decode failure reported as `BLOB_OUT_OF_ORDER` | S |

### Daemon and CLI (host/daemon)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| DM-18 | M | Partial | No CLI tests (option parsing, start/stop/status) | M |
| DM-15 | L | Fixed | `scroll_until` removed; `DIR_UNSPECIFIED` is rejected uniformly for swipe/scroll | S |
| DM-17 | N | Partial | `await(pending)` result shaping still inside `TapDaemon` (`:624`) | S |
| API-7 | L | Fixed* | `stop` never compares `Info.pid` with the descriptor pid | S |

### Kotlin client (clients/kotlin)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| K-15 | L→bug | Open | `DeviceAdmission` holds one device; detaching A inside B's admitted block waits on its own drain | M |
| J-5 | L→bug | Fixed | `DeviceBarrier` uses a plain monitor; withdrawal can no longer be cancelled | S |
| K-4 | M | Open | Proto types in the public API (`Device.info(): DeviceInfo`, `execute: CommandResult`); `App.ProcessIdentity` clashes with the proto name. Breaking — ask first | L |
| K-9 | M | Open | `tapScope`/`ensureTapBound` mandatory; no `use {}`/`attach {}` helpers | M |
| K-1 | M | Open | Mutex + 5 atomics; `established` write-only; `register()` unused; events capped at 200 | M |
| K-17 | M | Partial | No `App` unit tests | S |
| K-11 | L | Partial | Internal `allOf(vararg Node)` shares the public name | S |
| K-13 | L | Partial | Device-side waits carry no last observation | S |
| K-14 | L | Partial | Polls `process.isAlive` instead of `onExit()` (`TapClient.kt:802,809,828`) | S |
| K-16 | N | Partial | `connect` has a catch that only rethrows (`TapClient.kt:135-139`) | S |
| J-6 | L | Open | Failure recorded in three interceptors + handler + fallback (`TapExtension.kt:103-150`) | S |
| J-7 | L | Open | One 60 s artifact budget for all devices; no sink hook | S–M |
| J-9 | L | Open | No opt-in class-level device reuse | M |
| S-2 | L | Open | `fail()` inside `try … catch (AssertionError)` (`MultiDeviceTest.kt:92-94`) | S |
| S-3 | L | Partial | No test asserts distinct serials are used | S |

### Python client (clients/python)

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| PY-9 | L | Partial | `_capture_artifacts` has no overall time bound | S |
| PY-12 | N | Open | `TapServer.__init__` probes `Info` when discovering from `daemon.json` | S |

### Build

| ID | Sev | Status | What remains | Size |
|---|---|---|---|---|
| B-3 | M | Open | bundleDriver, fixture-tests and validation deviceTest wire APKs by `dependsOn` + hardcoded path; no consumable configuration | M |
