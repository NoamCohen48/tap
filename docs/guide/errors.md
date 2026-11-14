# Errors

!!! info "Names are Python's"
    Each Python `…Error` is a Kotlin `…Exception` (`CommandError` → `CommandException`,
    `TapError` → `TapException`), with the same fields in camelCase.

Tap distinguishes these kinds of failure, each its own exception type, and never converts one
into another:

| Exception | Raised when |
|---|---|
| `CommandError` | the driver executed (or refused) a UI command and answered with an error code |
| `WaitTimeoutError` | a `wait(...)` / `await_until` condition did not hold in time (the device reported `WAIT_TIMEOUT`; any other failure during a wait, such as driver unhealthy or transport lost, is a `CommandError`) |
| `AppLifecycleError` | install / launch / stop / clear did not reach its verified end state |
| `DeviceBusyError` | another device session holds the device and attachment did not (or could not) wait long enough |
| `DeviceQuarantinedError` | the device is out of service (a mutation whose outcome could not be proven, a corrupt session journal, a setting that did not read back) until someone checks it and runs `tap unquarantine`; waiting does not help. A quarantine listed `retry_on_attach` is retried by the next attach |
| `ServerError` | the server rejected a call (unknown attached device, bad argument, device offline, driver would not start, client connection closed; `UNAUTHENTICATED` = wrong or missing daemon token; `PERMISSION_DENIED` = the device is attached by another client connection). `reason` is the server's `FailureReason` (for example `FailureReason.DRIVER_START_FAILED`); branch on it, not on the message |

Server `reason`s about the device itself: `UNSUPPORTED_API` (the device's Android version is
too old for the call; the detail is `REQUIRES_API_<n>`), `DEVICE_SETTING` (a device condition
did not read back as written, for example a dark mode the device locks), and `DEVICE_FILE` (a
file push, pull or gallery add was refused: the file exists and Tap did not create it, its
directory is missing, it is not a regular file, or it did not read back).

All inherit from `TapError`. Ordinary assertion failures in your test are, of course, yours.

## Command errors

A `CommandError` carries the **code**, an optional stable **detail**, the operation, the
rendered selector, the device serial, the request id, the session generation and the duration,
and formats all of it into one line:

```
AMBIGUOUS during TAP text("Add") on emulator-5554 (request 14, generation 1, 62 ms): 3 matches
STALE_DURING_COMMAND/TARGET_GONE during SET_TEXT resId(com.shop:id/email) on 85e49002 (request 9, generation 1, 820 ms)
WAIT_TIMEOUT/SCREEN_CHANGING during WAIT_SCREEN_STABLE on emulator-5554 (request 3, generation 1, 10004 ms)
```

Branch on `code` (`ErrorCode.AMBIGUOUS`) and `detail` (`"TARGET_GONE"`); the free-text message
is for humans and may change.

```python
from tap_e2e import CommandError, ErrorCode

try:
    app.element(text("Add")).tap()
except CommandError as error:
    if error.code == ErrorCode.AMBIGUOUS:
        app.element(text("Add").first()).tap()
    else:
        raise
```

The codes, grouped by what they tell you:

**The selector, before anything happened**: device state unchanged.

| Code | Meaning / details |
|---|---|
| `NOT_FOUND` | zero matches, or fewer than `at(n)` needs. For an action, `narrowing` says which part of the selector ruled the nodes out ([below](#why-nothing-matched)) |
| `AMBIGUOUS` | more than one match; add a constraint or use `first()` / `at(n)` |
| `STALE_BEFORE_INPUT` | the one match was re-rendered (its view detached or replaced) between the lookup and the input, or while a query such as `snapshot()` read it, so nothing was sent. Running the action again looks the node up afresh; state waits (`enabled()`, `checked()`, …) keep polling through it on the device |
| `NOT_INTERACTABLE` | the one match cannot take the gesture: `OBSCURED` when another window (a keyboard, a popup, another app's overlay) covers the point the gesture would touch while part of the node still shows (a node covered completely is `NOT_FOUND`), or when the node is under an open navigation drawer (`DrawerLayout`). Nothing was sent; close or move what covers it. `tap_outside_panel` adds `NO_OUTSIDE_POINT` (the node is not in a panel with room around it) and `TOUCH_REACHES_BELOW` (the panel passes touches through; opt in with `allow_reach_below`) |
| `INVALID_SELECTOR` | rejected before lookup: `QUALIFIED_RESOURCE_NAME` (a `res` name with `:id/` in it; use `res_id(pkg, name)`), `SELECTOR_TOO_DEEP`, `SELECTOR_TOO_LARGE`, `STRING_TOO_LONG`, `INVALID_REGEX`, `EMPTY_NODE`, `EMPTY_VALUE` |
| `INVALID_REQUEST` | out-of-range argument; `UNSUPPORTED_CHARACTERS` when `type_text` has no key mapping for a character |

**The action, after input started**: device state may have changed.

| Code | Meaning / details |
|---|---|
| `STALE_DURING_COMMAND` | the target changed under the action: `TARGET_GONE` (also a node re-rendered after a swipe, scroll, fling, pinch or permission choice started), `TARGET_AMBIGUOUS` |
| `ACTION_REJECTED` | Android refused the input: the node refused set-text or an accessibility action, a key event was not injected, `PARTIAL_INPUT` (deadline mid-typing). Three details are refusals *before* any input, so nothing changed: `KEYGUARD_SECURE` (`dismiss_keyguard` on a PIN, pattern or password), `ACTION_NOT_OFFERED` (`perform_action`, `perform_custom_action` or `set_progress` on a node that does not offer that action) and `OUT_OF_RANGE` (`set_progress` outside the node's range) |
| `INDETERMINATE` | the driver accepted a mutation and no definitive result came back (`WATCHDOG`, `KEY_RELEASE_FAILED`, or the transport dropped after acceptance) |

**Waits:**

| Code | Meaning / details |
|---|---|
| `WAIT_TIMEOUT` | the condition stayed false. The detail is the wait's `reason`: `NO_MATCH`, `AMBIGUOUS`, `STILL_PRESENT`, `STATE_NOT_REACHED`, `SCREEN_CHANGING` or `APP_NOT_VISIBLE` |
| `DEADLINE_EXCEEDED` | the command's deadline passed outside a normal wait (`EXPIRED_IN_QUEUE`) |
| `CANCELLED` | stopped before mutation (`CANCELLED_IN_QUEUE`, `TRANSPORT_CLOSED`) |

**The session or the app:**

| Code | Meaning / details |
|---|---|
| `AUT_MISMATCH` | the app's process identity changed between observations (`PROCESS_RESTARTED`, `PROCESS_MISMATCH`) |
| `AUT_CRASHED`, `AUT_ANR` | an app this attachment started crashed or stopped responding, and that explains why the command failed. The message names the app and the exception; the detail is the code it replaces (`NOT_FOUND`, `WAIT_TIMEOUT`, …). Each crash is reported once. See [below](#a-crashed-app) |
| `SYNC_PROVIDER_UNAVAILABLE` | `await_idle` cannot read the app's sync provider: `CERTIFICATE_MISMATCH`, `UNINITIALIZED`, `PROVIDER_TIMEOUT`, … |
| `DRIVER_UNHEALTHY` | the driver's watchdog poisoned the session (`WATCHDOG`, `HEARTBEAT_EXPIRED`); the next open rebuilds it |
| `TRANSPORT_LOST` | no response and *no* mutation risk (the request never got out) |
| `OVERLOADED`, `SESSION_MISMATCH`, `DUPLICATE_OR_STALE`, `UNAUTHENTICATED`, `UNSUPPORTED` | protocol-level; you should not see them from the clients |
| `PAYLOAD_TOO_LARGE`, `ARTIFACT_TRANSFER_FAILED`, `INTERNAL` | the command ran, its result could not be delivered / an unexpected driver failure |

Tap itself never retries. A code that says nothing ran (`NOT_FOUND`, `AMBIGUOUS`, `CANCELLED`,
`TRANSPORT_LOST`, …) is only ever sent before any input reached the device: once a mutation
has started, such a failure is reported as `INDETERMINATE` instead. `INDETERMINATE`,
`ACTION_REJECTED`, `AUT_CRASHED` and `AUT_ANR` mean the screen may have changed, so do not
repeat a mutation after them blindly.

## A crashed app

A wait on an app that has died used to end in a timeout on a node that was never coming. Now
the failure says what happened:

```
AUT_CRASHED/WAIT_TIMEOUT during execute res("pay") on emulator-5554 (request 7, generation 2, 5012 ms): com.shop crashed: [23554,0,com.shop,547,java.lang.IllegalStateException,Out of stock,Cart.kt,41,0]; the command reported WAIT_TIMEOUT: NO_MATCH
```

Only apps started through this attachment (`launch`, `cold_launch`, `foreground`, `open_link`)
count, so another app crashing in the background changes nothing. The check runs only after a
command fails, so the failure arrives when the running wait ends, not at the moment of the crash.
Android reports an ANR only when input goes unanswered for about 5 s, so a hang with no input
waiting stays a plain timeout. The logs of the crash are in the
[failure artifacts](artifacts.md#failure-artifacts).

## Wait timeouts

```
Timed out after 10012ms waiting for text("Order placed") to be visible on emulator-5554; NO_MATCH (0 matches)
Timed out after 10008ms waiting for text("Buy") to match exactly one node on emulator-5554; AMBIGUOUS (3 matches)
Timed out after 5003ms waiting for resId(com.shop:id/pay) to satisfy enabled() on emulator-5554; STATE_NOT_REACHED; last observed: text='Pay' enabled=False …
  enabled(): no (False)
```

`description`, `serial`, `elapsed_ms`, `reason`, `match_count`, `last_state`,
`condition_parts`, `polls` and `last_observation` are fields on the exception. `reason` is a `WaitReason` for the waits the
device runs (`visible()`, `one()`, `gone()`, `until` and its shortcuts, `app.await_visible`,
`app.await_screen_stable`) and `None` for the ones the client polls (`count()`,
`device.await_until`). A state wait that found its node but not the state carries that node's
`ElementSnapshot` as `last_state`, and `condition_parts` says for each part of its condition
whether it held, read from that same node. A `NO_MATCH` from `visible()`, `one()` or a state
wait also carries `narrowing`, as `NOT_FOUND` does.

## Why nothing matched

When a selector finds no node (`NOT_FOUND`, or a wait that times out with `NO_MATCH`), the device
looks at the screen once more and works out which part of the selector is to blame. The error's
`narrowing` field holds the result, and the message shows it on the lines after the first:

```
NOT_FOUND during tap … on emulator-5554 (request 41, generation 1, 12 ms)
no node matches text("Pay") & clickable() & app("com.shop"); each part:
  text("Pay"): 0 alone, 2 without it (seen: "Pay now", "Pay later")
  clickable(): 14 alone, 0 without it
  app("com.shop"): 40 alone, 0 without it
```

Each line is one part of the selector's top-level and, written the way a test builds it. The
`app(...)` part is the package scope that `app.element` adds. *Alone* is how many nodes match that
part by itself. *Without it* is how many match all the other parts. A part with nodes *without
it* is the one ruling them out, and for a text, description, hint, class, package or resource
part, *seen* lists up to three values those nodes really have. Here the button says "Pay now",
not "Pay". When every part shows 0 *without it*, no single part is to blame: the parts disagree
two or more at a time.

A pick that asks for more matches than there are reads `only 2 nodes match text("Row").at(3);
its pick needs 4`. A selector with a single part has nothing to compare, so its line is just
`no node matches text("Pay")`.

The narrowing is read after the failure, from the screen as it is then, so on a screen that is
still changing it can disagree with the failure itself. It is diagnostic only: it never chooses a
node, and nothing runs again because of it.

It is also bounded, because it runs after the failure:

- A selector with more than eight parts gets only the first line.
- The device spends at most about a second on it. On a slow device with a large screen the
  per-part lines or the *seen* values can be missing.
- `element.snapshot()` (and `text()`, `is_enabled()` and the other reads built on it) has no
  narrowing: a loop that polls a node into existence with it would pay for it on every poll.

What a failed test leaves behind to look at (a screenshot, the hierarchy, the driver log) is on
[Screenshots and artifacts](artifacts.md#failure-artifacts).
