# Waits

!!! info "Examples are in Python"
    Kotlin has the same waits, named `await` instead of `wait` (`app.await(sel).visible()`,
    `app.awaitSettled()`). [Kotlin + JUnit 5](../sdk/kotlin.md#waits) shows this page's examples
    in Kotlin.

Tap never waits on its own: a test waits where it says so, for a condition it names.

!!! info "There is no implicit wait"
    UiAutomator's own idle wait before each interaction is capped at 1 s by the driver, and Tap
    adds none of its own. If a test only passes with a `sleep`, it is telling you which wait
    is missing: usually `app.wait(...).visible()` on the thing you are about to use.

## Elements

`app.wait(selector, timeout=None)` (and `device.screen.wait(...)`) returns an `ElementWait`.
Each of its methods waits until the condition holds or the timeout elapses, then returns the
`Element` so you can act on it:

```python
app.wait(text("Order placed")).visible()
app.wait(res("pay")).enabled().tap()
app.wait(res("spinner"), timeout=30).gone()
```

| Method | Condition |
|---|---|
| `visible()` | at least one match |
| `one()` | exactly one match (what `tap()` and other actions need) |
| `gone()` | zero matches |
| `enabled()`, `disabled()` | exactly one match with that state |
| `checked()`, `unchecked()`, `focused()` | likewise |
| `text_equals(s)`, `text_contains(s)` | text of the single match |
| `count(n)` | exactly `n` matches |

`visible()`, `one()` and `gone()` poll **on the device** in a single round trip; the property
waits take a snapshot from the host every poll interval (100 ms). A miss raises
`WaitTimeoutError`. For the device waits it carries `reason` (`WaitReason.NO_MATCH`, `AMBIGUOUS`
for `one()`, `STILL_PRESENT` for `gone()`) and `match_count` from the last poll; for the
property waits, the number of polls and the last observation (for example
`text='Placing order…' enabled=False …`).

`visible()` passes with several matches, so when the next step is an action, `one()` is the
wait that proves it can run.

`visible()` means *present in the accessibility tree*, which is what UiAutomator can see. An
element scrolled off-screen in a `RecyclerView` is usually absent from the tree, and so is one
that another window (a dialog, the keyboard) covers completely: Android reports it as not
visible ([Covered elements](app-or-screen.md#covered-elements)).

`element.wait()` is the same thing, starting from an element you already hold.

## The app or the screen

These are on `App` (`device.app(package)`).

| Method | Waits until |
|---|---|
| `app.await_visible()` | the package owns the focused window (a launch or a return from another app is done) |
| `app.await_settled(stable_for=0.5)` | the accessibility **tree** of the app's focused window has not changed for `stable_for` seconds |
| `app.await_animation_end(stable_for=0.5)` | the **pixels** of the app's window have not changed for `stable_for` |
| `app.await_screen_stable(stable_for=0.5, signal=ALL)` | both (or the `StabilitySignal` you pass) |
| `app.await_idle(stable_for=0.2)` | the app itself reports no busy work (experimental: [App lifecycle](app-lifecycle.md#app-owned-idle-sync-sdk)) |

Use `await_settled` after navigation, when a list is still being populated or a screen
rebuilt: it is cheap (a fingerprint of the tree, refreshed on window-change events) and ignores
purely visual motion. Use `await_animation_end` before a screenshot or when a transition
animates without touching the tree. `await_screen_stable` combines both.

A screen that never goes quiet, such as a ticking clock or an indeterminate spinner, fails with
`WAIT_TIMEOUT` / `SCREEN_CHANGING` rather than returning "stable enough". If that happens, wait
for the element you actually need instead, or pass `signal=StabilitySignal.TREE` to ignore the
pixels.

## Your own condition

For conditions the driver cannot evaluate in one command, such as a second device, a backend
or a file, `device.await_until` polls a host-side function with the same timeout reporting:

```python
device.await_until(
    "order visible in the admin API",
    lambda: admin.last_order().status == "PLACED",
    timeout=20,
    observe=lambda: admin.last_order().status,   # shown in the timeout message
)
```

Prefer `wait(selector)` for anything that is a UI condition: it polls on the device without a
round trip per poll.

Toasts, notifications and the permission dialog have waits of their own:
[Notifications and toasts](notifications.md), [Permissions](permissions.md).
