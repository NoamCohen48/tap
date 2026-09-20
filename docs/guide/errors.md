# Errors and artifacts

Tap distinguishes four kinds of failure, each its own exception type, and never converts one
into another:

| Kotlin | Python | Raised when |
|---|---|---|
| `CommandException` | `CommandError` | the driver executed (or refused) a UI command and answered with an error code |
| `WaitTimeoutException` | `WaitTimeoutError` | an `await(...)` / `awaitUntil` condition did not hold in time |
| `AppLifecycleException` | `AppLifecycleError` | install / launch / stop / clear did not reach its verified end state |
| `DeviceBusyException` | `DeviceBusyError` | another session holds the device and the open did not (or could not) wait long enough |
| `ServiceException` | `ServiceError` | the service rejected a call (unknown session, bad argument, device offline, run closed) |

All inherit from `TapException` / `TapError`. Ordinary assertion failures in your test are, of
course, yours.

## Command errors

A `CommandException` carries the **code**, an optional stable **detail**, the operation, the
rendered selector, the device serial, the request id, the session generation and the duration,
and formats all of it into one line:

```
AMBIGUOUS during TAP text("Add") on emulator-5554 (request 14, generation 1, 62 ms): 3 matches
NOT_FOUND/END_REACHED during SCROLL_UNTIL text("Wool socks") on 85e49002 (request 9, generation 1, 8210 ms)
WAIT_TIMEOUT/SCREEN_CHANGING during WAIT_SCREEN_STABLE on emulator-5554 (request 3, generation 1, 10004 ms)
```

Branch on `code` (`ErrorCode.AMBIGUOUS`) and `detail` (`"END_REACHED"`); the free-text message
is for humans and may change.

The codes, grouped by what they tell you:

**The selector, before anything happened** — device state unchanged:

| Code | Meaning / details |
|---|---|
| `NOT_FOUND` | zero matches. `END_REACHED`, `MAX_SCROLLS` from `scrollUntil` |
| `AMBIGUOUS` | more than one match; add a constraint or use `first()`/`at(n)` |
| `NOT_INTERACTABLE` | the node exists but cannot take the action (not editable, not scrollable); `FOCUS_TIMEOUT` when a text field never took focus |
| `INVALID_SELECTOR` | rejected before lookup: `SCOPE_DENIED`, `SCOPE_PACKAGE_UNEXPECTED`, `SELECTOR_TOO_DEEP`, `SELECTOR_TOO_LARGE`, `STRING_TOO_LONG`, `INVALID_REGEX`, `EMPTY_NODE`, `INDEX_REQUIRED`, … |
| `INVALID_REQUEST` | out-of-range argument; `UNSUPPORTED_CHARACTERS` when `typeText` has no key mapping for a character |

**The action, after input started** — device state may have changed:

| Code | Meaning / details |
|---|---|
| `STALE_DURING_COMMAND` | the target changed under the action: `TARGET_GONE`, `TARGET_AMBIGUOUS`, `FOCUS_LOST` |
| `ACTION_REJECTED` | input was issued but did not take effect: `TEXT_MISMATCH` (read-back differs), `PARTIAL_INPUT`, `DEADLINE_AFTER_FOCUS` |
| `INDETERMINATE` | the driver accepted a mutation and no definitive result came back (`WATCHDOG`, `KEY_RELEASE_FAILED`, or the transport dropped after acceptance) |

**Waits:**

| Code | Meaning / details |
|---|---|
| `WAIT_TIMEOUT` | the condition stayed false. `SCREEN_CHANGING` / `APP_NOT_VISIBLE` for the stability waits |
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

Each code has two fixed properties in the [protocol contract](../reference/grpc.md): whether
the device **may have mutated** and whether a retry is **safe**. Tap itself never retries; a
test that wants to may consult those flags.

## Wait timeouts

```
Timed out after 10012 ms after 98 polls waiting for text("Order placed") visible on emulator-5554; last observed: 0 matches
Timed out after 5003 ms after 48 polls waiting for resId(com.shop:id/pay) enabled on emulator-5554; last observed: enabled=false
```

`description`, `serial`, `elapsedMs`, `polls` and `lastObservation` are fields on the
exception.

## Failure artifacts

When a test fails, the JUnit extension and the pytest plugin capture — *before* closing the
session, while the screen still shows the failure — for each device of the test:

```
build/tap-artifacts/com.shop.CheckoutTest/buysAnItem/      (pytest: tap-artifacts/<nodeid>/)
├── failure.txt                          the exception and stack trace
├── device-emulator-5554.png             screenshot
├── device-emulator-5554.xml             accessibility hierarchy
├── device-emulator-5554.device-info.txt serial, API, model, display
└── device-emulator-5554.driver.log      the driver's log for the session
```

The file prefix is the role name, so a two-device test yields `sender-…` and `receiver-…`.
Capture never masks the original failure: a file that cannot be produced (the device went
away) is simply missing, and the test still fails with its real error.

The same data is available on demand: `device.screenshot()`, `device.dumpHierarchy()`,
`device.driverLog()`, `device.info()`.

## Reading the hierarchy dump

The XML is UiAutomator's view of the focused window. The attributes that selectors match are
`resource-id`, `text`, `content-desc`, `hint`, `class`, `package`, plus the boolean state
flags. If the element you wanted is missing from the dump it is missing from the accessibility
tree — a Compose node without `testTag` + `testTagsAsResourceId`, a `View` with
`importantForAccessibility="no"`, or content not yet laid out. Fix the app's semantics rather
than reaching for coordinates.
