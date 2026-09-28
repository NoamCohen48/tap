# Actions and waits

Everything on this page is a method of `Element` (built with `device.element(selector)`),
`ElementWait` (`device.await(selector)` / `device.wait(selector)` in Python) or `Device`.
Kotlin names are shown; Python uses the same names in `snake_case` (`setText` → `set_text`,
`awaitAppSettled` → `await_app_settled`, `device.await(...)` → `device.wait(...)`).

!!! info "Kotlin is suspend"
    Every Kotlin call below is `suspend` and runs inside `tapTest { ... }` (JUnit) or
    `tapScope { ... }` (scripts); building selectors (`text(...)`, `res(...)`) is not. The
    fragments omit the wrapper for brevity.

Every method takes an optional `timeout`. Actions default to `timeouts.action` (10 s), waits to
`timeouts.wait` (10 s), app lifecycle to `timeouts.lifecycle` (30 s). The timeout is the
deadline of the *whole* command on the device, including the lookup; nothing else sleeps.

## Observations

| Method | Returns |
|---|---|
| `exists()` | whether at least one node matches right now |
| `count()` | how many nodes match |
| `snapshot()` | text, content description, class, bounds, enabled/checked/focused/… of the single match |
| `text()` | the node's text as Android reports it, or `null`/`None` when it has none. An empty field reports its hint here; `snapshot().showingHint` (`showing_hint`) says so |
| `isEnabled()`, `isChecked()` | shortcuts on `snapshot()` |

`exists()` and `count()` accept any number of matches; `snapshot()` and everything below need
exactly one.

## Actions

| Method | What happens on the device |
|---|---|
| `tap()` | click at the centre of the node's visible bounds, whether or not the node is enabled (assert `isEnabled()` / `await(...).enabled()` first if it matters) |
| `longTap()` | long click, likewise |
| `setText(value)` | accessibility set-text on the node; `ACTION_REJECTED` only if the node refuses it. The field is **not** read back — assert it (see below) |
| `typeText(value)` | click the node, then type character by character with key events (IME-free) wherever focus is; unsupported characters are rejected *before* input with `INVALID_REQUEST`/`UNSUPPORTED_CHARACTERS`. Not read back |
| `clearText()` | `setText("")` |
| `swipe(direction, distancePercent = 80)` | one swipe gesture across the node, in the direction the finger moves. Returns nothing: whether the screen moved is for the test to assert |
| `scroll(direction, distancePercent = 80)` | one scroll gesture on the node towards `direction`'s content edge (`DOWN` reveals content below). The node need not report itself scrollable, and nothing says whether content moved |
| `scrollUntil(target, direction = DOWN, maxScrolls = 20, distancePercent = 80, timeout)` | a client-side loop: while `target` does not exist inside the container, `scroll` once more; returns `target` as an `Element`. Gives up with `WaitTimeoutException` / `WaitTimeoutError` after `maxScrolls` scrolls or the timeout (default: the wait timeout). A failing scroll step propagates unchanged |
| `device.pressBack()`, `device.pressHome()`, `device.pressKey(code)` | key events |

Text actions report only what Android said about the input, never what the app did with it:
apps reformat, truncate, reject or copy text elsewhere, and the framework assumes none of
that. Assert the outcome with a selector that still identifies the field after the edit (its
resource id, not its old text):

```kotlin
val email = device.element(resourceId("email"))
email.setText("user@example.com")
email.waitUntil.textEquals("user@example.com")
```

Directions are `UP`/`DOWN`/`LEFT`/`RIGHT` (`Direction.DIR_*` in the Kotlin proto types, plain
constants exported by both SDKs).

```kotlin
val list = device.element(res("results"))
list.scrollUntil(text("Wool socks"), timeout = 30.seconds).tap()
```

An action that fails **before** input (`NOT_FOUND`, `AMBIGUOUS`, `INVALID_*`) has changed nothing. An action that fails **after** input says so:
`STALE_DURING_COMMAND` (the target changed mid-action), `ACTION_REJECTED` (input was issued but
did not take effect), `INDETERMINATE` (the transport dropped after the driver accepted the
mutation). Tap never re-sends any of them for you.

## Waiting for elements

`device.await(selector, timeout)` (Python `device.wait(...)`) returns an `ElementWait`; each
terminal method waits until the condition holds or the timeout elapses, and then returns the
`Element` so you can act on it:

```kotlin
device.await(text("Order placed")).visible()
device.await(res("pay")).enabled().tap()
device.await(res("spinner"), timeout = 30.seconds).gone()
```

| Method | Condition |
|---|---|
| `visible()` | at least one match |
| `gone()` | zero matches |
| `enabled()`, `disabled()` | exactly one match with that state |
| `checked()`, `unchecked()`, `focused()` | likewise |
| `textEquals(s)`, `textContains(s)` | text of the single match |
| `count(n)` | exactly `n` matches |

`visible()` and `gone()` poll **on the device** in a single round trip; the property waits
take a snapshot from the host every `pollInterval` (100 ms). A miss raises
`WaitTimeoutException` / `WaitTimeoutError` with the elapsed time, the number of polls and the
last observation (e.g. `text='Placing order…' enabled=False …`). Note that `visible()` means
*present in the accessibility tree*, which is what UiAutomator can see; an element scrolled
off-screen in a `RecyclerView` is usually absent from the tree, an element hidden by another
window usually is not.

`Element.await()` is the same thing starting from an element you already hold.

## Waiting for the app or the screen

These are on `Device` and take a `packageName` that defaults to the app under test.

| Method | Waits until |
|---|---|
| `awaitAppVisible()` | the package owns the focused window (a launch or a return from another app is done) |
| `awaitAppSettled(stableFor = 500 ms)` | the accessibility **tree** of the focused window has not changed for `stableFor` |
| `awaitAnimationEnd(stableFor = 500 ms)` | the **pixels** of the screen have not changed for `stableFor` |
| `awaitScreenStable(stableFor = 500 ms, signal = ALL)` | both (or the signal you pass) |
| `app.awaitIdle(stableFor = 200 ms)` | the app itself reports no busy work — see [App lifecycle and sync](app-lifecycle.md) |

Use `awaitAppSettled` after navigation, when a list is still being populated or a screen
rebuilt: it is cheap (a fingerprint of the tree, refreshed on window-change events) and ignores
purely visual motion. Use `awaitAnimationEnd` before a screenshot or when a transition animates
without touching the tree. `awaitScreenStable` combines both.

A screen that never goes quiet — a ticking clock, an indeterminate spinner — fails with
`WAIT_TIMEOUT`/`SCREEN_CHANGING` rather than returning "stable enough". If that happens, wait
for the element you actually need instead, or pass `signal = TREE` to ignore the pixels.

!!! info "There is no implicit wait"
    UiAutomator's own idle wait before each interaction is capped at 1 s by the driver, and Tap
    adds none of its own. If a test only passes with a `sleep`, it is telling you which wait
    is missing: usually `await(...).visible()` on the thing you are about to use.

## Waiting on your own condition

For conditions the driver cannot evaluate in one command — a second device, a backend, a file —
`device.awaitUntil` (Python `device.await_until`) polls a host-side predicate with the same
`WaitTimeout` reporting:

=== "Kotlin"

    ```kotlin
    device.awaitUntil("order visible in the admin API", timeout = 20.seconds,
        observe = { admin.lastOrder()?.status }) {
        admin.lastOrder()?.status == "PLACED"
    }
    ```

=== "Python"

    ```python
    device.await_until(
        "order visible in the admin API",
        lambda: admin.last_order().status == "PLACED",
        timeout=20,
        observe=lambda: admin.last_order().status,
    )
    ```

Prefer `await(selector)` for anything that is a UI condition: it polls on the device without a
round trip per poll.

## Screenshots and dumps

- `device.screenshot()` → PNG bytes, checked against the server's SHA-256. Save them yourself
  (`Path.writeBytes`), or in Python pass `write_to="shot.png"` to have the client write the file.
- `device.dumpHierarchy()` → the accessibility tree as XML. Diagnostic only; lookups never use it.
- `device.driverLog()` → the driver's own log lines for the session.
- `device.info()` → serial, API level, model, display size.

All four are captured automatically into the failure artifacts by the JUnit extension and the
pytest plugin.
