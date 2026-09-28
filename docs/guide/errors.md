# Errors and artifacts

Tap distinguishes these kinds of failure, each its own exception type, and never converts one
into another:

| Kotlin | Python | Raised when |
|---|---|---|
| `CommandException` | `CommandError` | the driver executed (or refused) a UI command and answered with an error code |
| `WaitTimeoutException` | `WaitTimeoutError` | an `await(...)` / `awaitUntil` condition did not hold in time (the device reported `WAIT_TIMEOUT`; any other failure during a wait — driver unhealthy, transport lost — is a `CommandException` / `CommandError`) |
| `AppLifecycleException` | `AppLifecycleError` | install / launch / stop / clear did not reach its verified end state |
| `DeviceBusyException` | `DeviceBusyError` | another device session holds the device and attachment did not (or could not) wait long enough |
| `DeviceQuarantinedException` | `DeviceQuarantinedError` | the device is out of service until an explicit reset (a mutation whose outcome could not be proven, a corrupt session journal); waiting or retrying does not help |
| `ServerException` | `ServerError` | the server rejected a call (unknown attached device, bad argument, device offline, driver would not start, client connection closed; `UNAUTHENTICATED` = wrong or missing daemon token; `PERMISSION_DENIED` = the device is attached by another client connection). `reason` is the server's `FailureReason` (for example `FailureReason.DRIVER_START_FAILED`); branch on it, not on the message |

All inherit from `TapException` / `TapError`. Ordinary assertion failures in your test are, of
course, yours.

## Command errors

A `CommandException` carries the **code**, an optional stable **detail**, the operation, the
rendered selector, the device serial, the request id, the session generation and the duration,
and formats all of it into one line:

```
AMBIGUOUS during TAP text("Add") on emulator-5554 (request 14, generation 1, 62 ms): 3 matches
STALE_DURING_COMMAND/TARGET_GONE during SET_TEXT resId(com.shop:id/email) on 85e49002 (request 9, generation 1, 820 ms)
WAIT_TIMEOUT/SCREEN_CHANGING during WAIT_SCREEN_STABLE on emulator-5554 (request 3, generation 1, 10004 ms)
```

Branch on `code` (`ErrorCode.AMBIGUOUS`) and `detail` (`"TARGET_GONE"`); the free-text message
is for humans and may change.

The codes, grouped by what they tell you:

**The selector, before anything happened** — device state unchanged:

| Code | Meaning / details |
|---|---|
| `NOT_FOUND` | zero matches |
| `AMBIGUOUS` | more than one match; add a constraint or use `first()`/`at(n)` |
| `INVALID_SELECTOR` | rejected before lookup: `SCOPE_DENIED`, `SELECTOR_TOO_DEEP`, `SELECTOR_TOO_LARGE`, `STRING_TOO_LONG`, `INVALID_REGEX`, `EMPTY_NODE`, `EMPTY_VALUE` |
| `INVALID_REQUEST` | out-of-range argument; `UNSUPPORTED_CHARACTERS` when `typeText` has no key mapping for a character |

**The action, after input started** — device state may have changed:

| Code | Meaning / details |
|---|---|
| `STALE_DURING_COMMAND` | the target changed under the action: `TARGET_GONE`, `TARGET_AMBIGUOUS` |
| `ACTION_REJECTED` | Android refused the input: the node refused set-text, a key event was not injected, `PARTIAL_INPUT` (deadline mid-typing) |
| `INDETERMINATE` | the driver accepted a mutation and no definitive result came back (`WATCHDOG`, `KEY_RELEASE_FAILED`, or the transport dropped after acceptance)) |

**Waits:**

| Code | Meaning / details |
|---|---|
| `WAIT_TIMEOUT` | the condition stayed false. The detail is the wait's `reason`: `NO_MATCH`, `AMBIGUOUS`, `STILL_PRESENT`, `SCREEN_CHANGING` or `APP_NOT_VISIBLE` |
| `DEADLINE_EXCEEDED` | the command's deadline passed outside a normal wait (`EXPIRED_IN_QUEUE`) |
| `CANCELLED` | stopped before mutation (`CANCELLED_IN_QUEUE`, `TRANSPORT_CLOSED`) |

**The session or the app:**

| Code | Meaning / details |
|---|---|
| `AUT_MISMATCH` | the app's process identity changed between observations (`PROCESS_RESTARTED`, `PROCESS_MISMATCH`) |
| `SYNC_PROVIDER_UNAVAILABLE` | `awaitIdle` cannot read the app's sync provider: `CERTIFICATE_MISMATCH`, `UNINITIALIZED`, `PROVIDER_TIMEOUT`, … |
| `DRIVER_UNHEALTHY` | the driver's watchdog poisoned the session (`WATCHDOG`, `HEARTBEAT_EXPIRED`); the next open rebuilds it |
| `TRANSPORT_LOST` | no response and *no* mutation risk (the request never got out) |
| `OVERLOADED`, `SESSION_MISMATCH`, `DUPLICATE_OR_STALE`, `UNAUTHENTICATED`, `UNSUPPORTED` | protocol-level; you should not see them from the clients |
| `PAYLOAD_TOO_LARGE`, `ARTIFACT_TRANSFER_FAILED`, `INTERNAL` | the command ran, its result could not be delivered / an unexpected driver failure |

Tap itself never retries. A code that says nothing ran (`NOT_FOUND`, `AMBIGUOUS`, `CANCELLED`,
`TRANSPORT_LOST`, …) is only ever sent before any input reached the device: once a mutation
has started, such a failure is reported as `INDETERMINATE` instead. `INDETERMINATE`,
`ACTION_REJECTED`, `AUT_CRASHED` and `AUT_ANR` mean the screen may have changed, so do not
repeat a mutation after them blindly.

## Wait timeouts

```
Timed out after 10012ms waiting for text("Order placed") to be visible on emulator-5554; NO_MATCH (0 matches)
Timed out after 10008ms waiting for text("Buy") to match exactly one node on emulator-5554; AMBIGUOUS (3 matches)
Timed out after 5003ms waiting for resId(com.shop:id/pay) to be enabled on emulator-5554 (48 polls); last observed: text=Pay enabled=false …
```

`description`, `serial`, `elapsedMs`, `reason`, `matchCount`, `polls` and `lastObservation`
are fields on the exception (Python: `elapsed_ms`, `match_count`, `last_observation`).
`reason` is a `WaitReason` for the waits the device runs (`visible()`, `one()`, `gone()`,
`awaitAppVisible`, `awaitScreenStable`) and null / None for the ones the client polls.

## Failure artifacts

When a test fails, the JUnit extension and the pytest plugin call `device.capture()` for each
device of the test — *before* detaching it, while the screen still shows the failure — and save
the result:

```
build/tap-artifacts/com.shop.CheckoutTest/buysAnItem/      (pytest: tap-artifacts/<nodeid>/)
├── failure.txt                                 the exception and stack trace
├── device-emulator-5554.screenshot.png         screenshot
├── device-emulator-5554.hierarchy.xml          accessibility hierarchy
├── device-emulator-5554.device-info.json       API, model, display, focused package
└── device-emulator-5554.driver-log.txt         the driver's log for the session
```

The file prefix is `<role>-<serial>`, so a two-device test yields `sender-…` and `receiver-…`.
Devices are captured in parallel, and so are the four parts of each device, each within 30 s, so
one slow or hung device or part does not cost the others their artifacts. Capture never masks
the original failure: a file that cannot be produced (the device went away, the time ran out) is
simply missing, and the test still fails with its real error. `tap.capture=off` (pytest:
`tap_capture = off` / `TAP_CAPTURE=off`) turns this off; see
[Configuration](configuration.md).

`device.capture()` is an ordinary call, so a test can take the same evidence whenever it wants
(after a step, in a `catch`), and each part is also available on its own as a typed value; see
[Screenshots and dumps](actions-and-waits.md#screenshots-and-dumps).

## Reading the hierarchy dump

The XML is UiAutomator's view of the focused window. The attributes that selectors match are
`resource-id`, `text`, `content-desc`, `hint`, `class`, `package`, plus the boolean state
flags. If the element you wanted is missing from the dump it is missing from the accessibility
tree — a Compose node without `testTag` + `testTagsAsResourceId`, a `View` with
`importantForAccessibility="no"`, or content not yet laid out. Fix the app's semantics rather
than reaching for coordinates.
