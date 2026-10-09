# Elements and text

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`set_text` → `setText`, `long_tap` → `longTap`),
    called inside `tapTest { }`. [Kotlin + JUnit 5](../sdk/kotlin.md#elements-and-text) shows
    this page's examples in Kotlin.

An `Element` is a selector plus a device: `app.element(selector)` for one app's nodes,
`device.screen.element(selector)` for the whole screen ([App or screen](app-or-screen.md)).
Building one does nothing on the device; every call on it finds the node again. The examples
use `app = device.app("com.shop")`.

Every call takes an optional `timeout` in seconds. Actions default to the device's action
timeout (10 s), waits to its wait timeout (10 s), app lifecycle calls to 30 s. The timeout is
the deadline of the *whole* command on the device, including the lookup; nothing else sleeps.

## Reading an element

| Method | Returns |
|---|---|
| `exists()` | whether at least one node matches right now |
| `count()` | how many nodes match |
| `snapshot()` | text, content description, class, bounds, enabled / checked / focused / … of the single match |
| `text()` | the node's text as Android reports it, or `None` when it has none. An empty field reports its hint here; `snapshot().showing_hint` says so |
| `is_enabled()`, `is_checked()` | shortcuts on `snapshot()` |

`exists()` and `count()` accept any number of matches; `snapshot()` and every action below need
exactly one ([Selectors](selectors.md#exactly-one-or-say-otherwise)).

## Taps

| Method | What happens on the device |
|---|---|
| `tap()` | a click at the centre of the node's visible bounds, whether or not the node is enabled (wait for `enabled()` first if it matters) |
| `long_tap()` | a long click, likewise |
| `double_tap()` | two taps inside Android's double-tap window, as one gesture |

A tap whose point another window covers fails with `OBSCURED` before any input
([Covered elements](app-or-screen.md#covered-elements)). For swipes, scrolls, drags and pinches,
see [Gestures](gestures.md).

## Text

| Method | What happens on the device |
|---|---|
| `set_text(value)` | accessibility set-text on the node; `ACTION_REJECTED` only if the node refuses it |
| `type_text(value)` | three client-side steps: `tap()`, then `wait().focused()` (skip it with `await_focus=False`), then `device.type_text(value)` |
| `clear_text()` | `set_text("")` |
| `device.type_text(value)` | types character by character with real key events (no IME) into whatever has input focus now: no target, no click, no settling. Characters outside Android's virtual key map, such as emoji, are rejected *before* input with `INVALID_REQUEST` / `UNSUPPORTED_CHARACTERS` |
| `ime_action()` | runs the field's keyboard action key (Search, Go, Send, Done, …: whatever the app configured), as the keyboard's own key does. The field must have input focus, so `tap()` it first; a node that does not offer the action fails with `ACTION_REJECTED` before input. Android 11+ (`UNSUPPORTED` below). Pressing Enter is not the same: apps waiting for "Search" ignore it |

Text actions report only what Android said about the input, never what the app did with it:
apps reformat, truncate, reject or copy text elsewhere, and Tap assumes none of that. The field
is not read back. Assert the outcome with a selector that still identifies the field after the
edit (its resource id, not its old text):

```python
email = app.element(res("email"))
email.set_text("user@example.com")
email.wait().text_equals("user@example.com")
```

## Accessibility actions and sliders

| Method | What happens on the device |
|---|---|
| `perform_action(action)` | runs one of the node's accessibility actions as a screen reader does, with no touch: a `StandardAction` (`EXPAND`, `COLLAPSE`, `DISMISS`, `SCROLL_FORWARD`, `PAGE_DOWN`, `SELECT`, `COPY`, …) |
| `perform_custom_action(label)` | an app-defined custom action by its label ("Archive", "Mark unread") |
| `set_progress(value)` | sets a slider, seek bar or rating bar to `value` in its own units (`snapshot().range` gives the type, min, max and current value). A value outside the range fails with `ACTION_REJECTED` / `OUT_OF_RANGE` instead of being clamped |

An action the node does not offer fails with `ACTION_REJECTED` / `ACTION_NOT_OFFERED` before
input; `snapshot().actions` and `custom_actions` list what it offers. Page actions need Android
10, press-and-hold Android 11 (`UNSUPPORTED` below).

```python
from tap_e2e import StandardAction

app.element(text("Shipping")).perform_action(StandardAction.EXPAND)
app.element(res("message_row").first()).perform_custom_action("Archive")
app.element(res("volume")).set_progress(7)
```

## Before or after input

An action that fails **before** input (`NOT_FOUND`, `AMBIGUOUS`, `INVALID_*`,
`STALE_BEFORE_INPUT`, and `NOT_INTERACTABLE`, including `OBSCURED`) has changed nothing. An action that fails **after**
input says so: `STALE_DURING_COMMAND` (the target changed mid-action), `ACTION_REJECTED` (input
was issued but did not take effect), `INDETERMINATE` (the transport dropped after the driver
accepted the mutation). Tap never re-sends any of them for you. [Errors](errors.md) lists every
code.
