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
app.wait(res("pay")).until(enabled() & text("Pay")).tap()
app.wait(res("spinner"), timeout=30).gone()
```

| Method | Condition |
|---|---|
| `visible()` | at least one match |
| `one()` | exactly one match (what `tap()` and other actions need) |
| `gone()` | zero matches |
| `until(condition)` | the one match satisfies `condition` ([below](#which-node-then-what-it-must-become)) |
| `enabled()`, `disabled()` | shortcuts: `until(enabled())`, `until(enabled(False))` |
| `checked()`, `unchecked()`, `focused()` | `until(checked())`, `until(checked(False))`, `until(focused())` |
| `text_equals(s)`, `text_contains(s)` | `until(text(s))`, `until(text(s, CONTAINS))` |
| `count(n)` | exactly `n` matches |

Every wait but `count(n)` polls **on the device** in a single round trip; `count(n)` polls from
the host every 100 ms. A miss raises `WaitTimeoutError` with a `reason` and what was seen last:

| Wait | `reason` on timeout | Also carries |
|---|---|---|
| `visible()` | `NO_MATCH` | `match_count` |
| `one()` | `NO_MATCH` or `AMBIGUOUS` | `match_count` |
| `gone()` | `STILL_PRESENT` | `match_count` |
| `until` and its shortcuts | `STATE_NOT_REACHED`, or `NO_MATCH` if the node never appeared | `last_state`: the node's `ElementSnapshot` at the end; `condition_parts`: which part of the condition held |
| `count(n)` | none | `polls`, `last_observation` (`count=…`) |

The message says it on its first line, and for a condition adds one line per part:

```
Timed out after 5003 ms waiting for … to satisfy enabled() & text("Pay") on emulator-5554;
STATE_NOT_REACHED; last observed: text='Paying…' enabled=True …
  enabled(): yes
  text("Pay"): no ("Paying…")
```

`visible()` passes with several matches, so when the next step is an action, `one()` is the
wait that proves it can run.

`visible()` means *present in the accessibility tree*, which is what UiAutomator can see. An
element scrolled off-screen in a `RecyclerView` is usually absent from the tree, and so is one
that another window (a dialog, the keyboard) covers completely: Android reports it as not
visible ([Covered elements](app-or-screen.md#covered-elements)).

### Which node, then what it must become

A state wait has two parts, and each goes in its own place:

- **The selector chooses which node.** Combine and refine it as anywhere else
  ([Selectors](selectors.md)), including `first()` / `at(n)` to pick one of several.
- **`until(condition)` says what that one node must become.**

The condition is written with the same builders as a selector (`enabled()`, `checked(False)`,
`focused()`, `selected()`, `text(...)` with any match mode, `desc`, `hint`, `&`, `|`, relations
such as `has_child(...)`), but it never chooses a node. It is checked on the node the selector
picked, every poll, and all of its parts must hold at the same moment:

```python
app.wait(text("Pay").clickable()).until(enabled())                   # the clickable "Pay" button, once enabled
app.wait(class_name("CheckBox").at(1)).until(checked())              # the second checkbox, once checked
app.wait(res("total")).until(text(r"\d+,\d\d €", REGEX))             # once the total is formatted
app.wait(res("status")).until(text("Saved") | text("Up to date"))    # either message, on that node
app.wait(res("pay")).until(enabled() & text("Pay"))                  # both at once
```

`enabled()`, `checked()`, `text_equals(s)` and the other methods in the table are shortcuts for
the commonest conditions. A condition cannot carry its own `first()` / `at(n)` (`ValueError`):
the pick belongs on the selector.

Putting the condition into the selector instead (`app.wait(res("pay") & enabled()).visible()`)
looks similar but means something else: "some node that is already enabled". It never sees the
disabled button. A pick (`at(1)`) then counts only the enabled nodes, and a selector matching
two buttons, one of them enabled, passes. A timeout can only say `NO_MATCH`, never that the
button was there and still disabled. Use `until`.

The node is resolved again on every poll. While it is missing or being re-rendered the wait
keeps polling. If the selector matches several nodes and has no pick, the wait fails at once with
`AMBIGUOUS` (a `CommandError`), since there is no one node to wait for.

A condition is checked the way the device checks a selector predicate on one node, with two
details worth knowing. A property the node does not have never matches, so `text("")` holds only
for a node whose text is empty, not for one with no text. An ancestor relation looks up at most 32
levels. A `has_descendant` condition walks the node's subtree on every poll, so keep it on small
containers.

A `|` in the *selector* describes one node that can look different ways (`text("Allow") |
text("While using the app")`). It is not a way to wait for "either of two things". To wait
until one of two different screens shows up, wait for something they share, or use
`device.await_until` with your own condition.

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
