# Device events

!!! info "Python only, for now"
    `device.events` is in the Python client. The server call (`WatchDeviceEvents`) is there
    for any client; the Kotlin SDK does not wrap it yet.

Android tells Tap's driver when something happens on the screen: a toast shows, a window
opens or closes, content changes, a view is clicked or scrolled, an editable field's text
changes. `device.events` hands those to your code as they happen, without polling: a callback
for every event of a kind, or a one-shot wait for the first one that matches.

Each kind of event is its own frozen class, and the class is what you subscribe with, so your
callback receives that class with its own fields, and a type checker knows them:

```python
device.events.on_toast(lambda e: print(e.text))                  # e: ToastShownEvent
device.events.on_window_change(lambda e: print(e.class_name))    # e: WindowChangedEvent
```

!!! warning "Events are hints, not proof"
    Android merges and drops accessibility events, and an event holds no element. Use one to
    know *when* to look, then look through a selector:
    `device.screen.element(text("Paid")).wait.visible()`. Waiting on the element is still
    the reliable check that the screen is ready.

## Call back on every event

`on_toast`, `on_window_change`, `on_content_change`, `on_click`, `on_scroll` and
`on_text_change` each take a callback, and an optional `package` to keep only that app's
events. `register(kind, callback)` is the same for any kind: `register(ToastShownEvent, cb)` is
`on_toast(cb)`. Each returns once the server is subscribed, so every event an action started
afterwards causes reaches the callback:

```python
toasts = []
with device.events.on_toast(lambda e: toasts.append(e.text), package="com.shop"):
    app.element(text("Save")).tap()
    app.element(text("Saved")).wait.visible()
assert "Draft saved" in toasts
```

The block decides how long the registration lasts; it does not wait for events. Without
`with`, the registration lasts until you call `close()` on it or the device is detached.
Registrations are independent of each other (at most 64 per device): to follow two kinds,
register twice.

The callback runs on a background thread of the registration's own, once per event, in the
order the events fired. It should be quick: a registration whose callback falls more than
1 024 events behind is ended. A callback that raises does not stop the registration; the
exception is logged and kept in `callback_error`.

At the end of a `with` block, a registration that was ended by a failure, or whose callback
raised, raises that failure (unless the block itself raised), so it cannot go unnoticed:

| What ended it | `registration.error` |
|---|---|
| `close()`, the end of the block, the device's detach | `None` |
| Events were lost: the callback fell behind, or the device raised them faster than they could be sent | `ServerError` with `RESOURCE_EXHAUSTED` |
| The driver connection was lost | `ServerError` with `SESSION_UNUSABLE` |

## Wait for one event

`expect(kind)` subscribes now, and `wait` returns the first event that `where` accepts.
Subscribe before the action, so an event the action causes cannot fire before you start
waiting:

```python
with device.events.expect(ToastShownEvent, where=lambda e: "saved" in e.text) as saved:
    app.element(text("Save")).tap()
    toast = saved.wait(timeout=5)          # toast: ToastShownEvent
```

`wait_for` is `expect` and `wait` in one call, for an event that does not come from your next
action (a push message, a background sync):

```python
window = device.events.wait_for(WindowChangedEvent, package="com.shop", timeout=30)
```

`wait` raises `WaitTimeoutError` when no matching event arrived in time (the default timeout is
`device.timeouts.wait`). The message says how many events of that kind arrived but did not
match. If `where` raises, `wait` raises that exception.

## Event kinds

Every event has `sequence`, `uptime_ms` (Android's uptime when it fired) and `package_name`.

| Class | Android raises it when | Its own fields |
|---|---|---|
| `ToastShownEvent` | a toast is shown | `text` |
| `WindowChangedEvent` | a window appears, goes away or changes: an activity, dialog, popup or pane | `class_name`, `title`, `window_id`, `changes` (`WindowChange`: `STATE` for a new activity, dialog or pane; `ADDED`, `REMOVED`, `FOCUSED`, `TITLE`… on API 28+) |
| `ContentChangedEvent` | content inside a window changes. Frequent: animations raise it on every frame | `class_name`, `window_id`, `changes` (`ContentChange`: `SUBTREE`, `TEXT`, `PANE_APPEARED`…; empty when Android did not say) |
| `ViewClickedEvent` | a view is clicked, by Tap or by anything else | `class_name`, `label`, `window_id` |
| `ViewScrolledEvent` | a view scrolls | `scroll_x`/`scroll_y` and their maximums, `delta_x`/`delta_y` (API 28+), `from_index`/`to_index`/`item_count` for a list |
| `TextChangedEvent` | an editable view's text changes | `text`, `before_text`, `from_index`, `added_count`, `removed_count`, `password` |

A field the view or the Android version does not report is `None`. Text is cut at 512
characters.

What Android reports, as seen on devices:

- **Text: typed or set.** Typing (`type_text`) raises one `TextChangedEvent` per key, with
  `before_text`, `from_index` and the counts. Text set in one step (`set_text`) raises a
  `ContentChangedEvent` with `ContentChange.TEXT` instead, and no `TextChangedEvent`.
- **Labels as shown.** `ViewClickedEvent.label` is the text as displayed: a theme that
  uppercases buttons reports `"TOAST"` for a button whose text is `"Toast"`. Compare case-
  insensitively.
- **Window events are many.** Besides one `STATE` event per new activity, dialog or pane (with
  its class, title and package), Android reports changes to its window list (`ADDED`,
  `REMOVED`, `LAYER`, `BOUNDS`…) that name only a window id, and a launch can show a starting
  window (`android.widget.FrameLayout`) before the activity. Filter on `WindowChange.STATE` and
  `class_name` to follow screens.
- **Lists report items, not pixels.** A `RecyclerView` scroll has `from_index`, `to_index` and
  `item_count`, and no `scroll_x`/`scroll_y` or deltas.

`TextChangedEvent.text` holds what was typed: keep that in mind before you log it (Android
masks a password field's text itself, and sets `password`).
