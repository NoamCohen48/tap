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
| `swipe(direction, distance_percent=80)` | one swipe across the node, in the direction the finger moves |
| `scroll(direction, distance_percent=80)` | one scroll gesture on the node towards `direction`'s content edge (`DOWN` reveals content below). The node need not report itself scrollable |
| `fling(direction)` | one fast swipe across the whole node towards `direction`'s content edge, as `scroll`. It does not wait for the content to stop moving |
| `scroll_until(target, direction=DOWN, max_scrolls=20, distance_percent=80, timeout=None)` | a client-side loop: while `target` does not exist inside the container, `scroll` once more; returns `target` as an `Element`. Gives up with `WaitTimeoutError` after `max_scrolls` scrolls or the timeout (default: the wait timeout). A failing scroll step propagates unchanged |
| `drag_to(destination)` | press on the node until it is a long press, move to the centre of `destination` (a selector, exactly one match), hold briefly so drop targets see the finger arrive, release. Both nodes are found before the finger goes down: a missing or ambiguous destination fails with no input |
| `pinch_open(percent=80)`, `pinch_close(percent=80)` | two fingers moving apart (zoom in) or together (zoom out) across `percent` of the node's size |

Directions are the `Direction` enum, `UP`, `DOWN`, `LEFT` and `RIGHT`, also exported as plain
constants.

```python
from tap_e2e import DOWN, UP, res, text

results = app.element(res("results"))
results.scroll_until(text("Wool socks"), timeout=30).tap()
app.element(res("carousel")).swipe(UP)
```

## Wait for the effect

A gesture returns when Android accepted the injected input, not when the app zoomed, dropped
or scrolled: nothing says whether content moved. Wait for what the gesture was meant to do:

```python
app.element(res("card")).drag_to(res("done_column"))
app.wait(text("Moved to Done")).visible()
```

For a list that is still moving after a fling, wait for the element you need, or for the screen
to stop changing ([Waits](waits.md#the-app-or-the-screen)).
