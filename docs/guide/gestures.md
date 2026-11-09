# Gestures

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`scroll_until` → `scrollUntil`, `drag_to` →
    `dragTo`) and durations as `Duration`. [Kotlin + JUnit 5](../sdk/kotlin.md#gestures) shows
    this page's examples in Kotlin.

Every gesture is relative to a matched element: there are no screen coordinates. Like a tap,
each one needs exactly one match, and fails with `OBSCURED` before any input when another
window covers its touch point (the centre; a swipe's, scroll's or fling's start).

| Method | What happens on the device |
|---|---|
| `tap(count=1, interval=0.1)`, `triple_tap()` | 1..100 taps at the initially resolved centre; `interval` is the release-to-press gap in seconds (0.04..1). Each press lasts 50 ms |
| `long_tap(duration=None)` | default Android long press, or a physical press held for `duration` seconds (0.001..30) |
| `swipe(direction, distance_percent=80)` | one swipe across the node, in the direction the finger moves |
| `scroll(direction, distance_percent=80)` | one scroll gesture on the node towards `direction`'s content edge (`DOWN` reveals content below). The node need not report itself scrollable |
| `fling(direction)` | one fast swipe across the whole node towards `direction`'s content edge, as `scroll`. It does not wait for the content to stop moving |
| `scroll_until(target, direction=DOWN, max_scrolls=20, distance_percent=80, timeout=None)` | a client-side loop: while `target` does not exist inside the container, `scroll` once more; returns `target` as an `Element`. Gives up with `WaitTimeoutError` after `max_scrolls` scrolls or the timeout (default: the wait timeout). A failing scroll step propagates unchanged |
| `drag_to(destination)` | press on the node until it is a long press, move to the centre of `destination` (a selector, exactly one match), hold briefly so drop targets see the finger arrive, release. Both nodes are found before the finger goes down: a missing or ambiguous destination fails with no input |
| `pinch_open(percent=80)`, `pinch_close(percent=80)` | two fingers moving apart (zoom in) or together (zoom out) across `percent` of the node's size |
| `tap_outside_panel(allow_reach_below=False)` | one tap outside the dialog, popup, menu, drawer or bottom sheet that holds the node; see [Outside a dialog, menu or sheet](#outside-a-dialog-menu-or-sheet) |

Directions are the `Direction` enum, `UP`, `DOWN`, `LEFT` and `RIGHT`, also exported as plain
constants.

```python
from tap_e2e import DOWN, UP, res, text

results = app.element(res("results"))
results.scroll_until(text("Wool socks"), timeout=30).tap()
app.element(res("carousel")).swipe(UP)
```

## Counted taps and timed presses

```python
button = app.element(res("hidden_menu"))
button.triple_tap()
button.tap(count=7, interval=0.2)
button.long_tap(duration=2.5, timeout=5)
```

In Kotlin: `button.tripleTap()`, `button.tap(count = 7, interval = 200.milliseconds)`,
and `button.longTap(duration = 2500.milliseconds, timeout = 5.seconds)` (import the
corresponding `Duration.Companion` extensions).

The count is one command, not a client-side retry loop. Tap resolves and checks the target
once, then sends every touch to that point even if the app changes under it. Android's input
acceptance does not promise the app recognizes triple-clicks. A short timed press may not
reach the app's long-press threshold.

Counted sequences are limited to 30 seconds, including each 50 ms press. Explicit-duration
presses and counted sequences must fit the remaining command timeout or fail before input.
They currently require the default display. Durations have millisecond precision. A failed
injection after input started reports `ACTION_REJECTED` with `PARTIAL_INPUT`; never retry the
whole sequence automatically.

Agent CLI/MCP parity: `tap-agent tap 'id=hidden_menu' --count 3 --interval 100ms` and
`tap-agent tap 'id=hidden_menu' --duration 2500ms --timeout 5s`. MCP's `tap` accepts `count`,
`interval`, `duration` and `timeout` in seconds. `--duration` implies a long press; do not
combine count/interval with `--long`, `--double` or `--duration`.

## Outside a dialog, menu or sheet

To test that a touch outside a panel closes it, select any node **inside** the panel, such as its
title, and call `tap_outside_panel()`. There is no node to select outside a dialog: while it is
open, Android hides the screen under it from the accessibility tree.

```python
app.wait(text("Delete photo?")).visible()
app.await_screen_stable()  # the dialog has finished opening
app.element(text("Delete photo?")).tap_outside_panel()
app.wait(text("Delete photo?")).gone()
```

In Kotlin: `app.element(text("Delete photo?")).tapOutsidePanel()`. Wait until the panel has
stopped moving first: the point is placed from where the panel is at that moment, and a sheet or
drawer still sliding in is not there yet. Python returns where it touched (`OutsideTouch`: `x`,
`y` and `panel`, which says how the panel was found and which side was used); Kotlin returns
nothing, so a test that ends with it stays a `Unit` function.

Tap finds the panel from that node, the same way for Views and Compose:

- **Drawers and bottom sheets** mark themselves to accessibility (a dismiss action or a pane
  title). The touch goes in the middle of the free space beside the panel, where its scrim is.
  Other things carry these marks too (a Snackbar offers dismiss), so a point there is used only
  when no control of the app's own would take it: empty space, the scrim, or content the panel
  hides while it is open.
- **Otherwise the node's window**: a dialog, popup, dropdown or menu, or a marked panel with no
  usable side. The touch goes just past the window's edge (Android's window touch slop plus
  8 dp; closer touches some dialogs ignore).

Sides are tried below, above, right, then left, skipping the status bar, the navigation bar and
the gesture area at the bottom, the keyboard and any window above the panel. Whether the panel
closes is the app's choice (`setCanceledOnTouchOutside(false)` keeps a dialog open), so assert
it.

It fails before any input, with `NOT_INTERACTABLE`, when there is nowhere safe to touch:

- `NO_OUTSIDE_POINT`: the node is not in a panel with room around it, for example a node on a
  plain screen, a full-screen dialog, or a bottom sheet that cannot be cancelled (it offers no
  dismiss action, and its window fills the screen).
- `TOUCH_REACHES_BELOW`: the panel lets touches through to the screen under it (an
  autocomplete dropdown, a popup that does not take focus), so the touch would also press
  whatever is there. Pass `allow_reach_below=True` to send it anyway.

Don't tap a scrim node (`desc("Close sheet")`, `id("touch_outside")`) directly instead: a tall
sheet can cover its centre, and the tap then presses a row of the sheet.

Agent CLI/MCP: `tap-agent tap 'text=Delete photo?' --outside-panel [--allow-reach-below]`;
MCP's `tap` takes `outside_panel` and `allow_reach_below`.

## Wait for the effect

A gesture returns when Android accepted the injected input, not when the app zoomed, dropped
or scrolled: nothing says whether content moved. Wait for what the gesture was meant to do:

```python
app.element(res("card")).drag_to(res("done_column"))
app.wait(text("Moved to Done")).visible()
```

For a list that is still moving after a fling, wait for the element you need, or for the screen
to stop changing ([Waits](waits.md#the-app-or-the-screen)).
