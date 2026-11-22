# Notifications and toasts

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`await_toast` → `awaitToast`), with named
    arguments where Python uses keywords. [Kotlin + JUnit 5](../sdk/kotlin.md#notifications-and-toasts)
    shows this page's examples in Kotlin.

## Toasts

`device.await_toast(text=None, mode=EXACT, *, package_name=None, timeout=None)` returns a
`Toast(text, package_name)`. A toast shown in the last 3.5 s counts (the longest one stays up),
so calling it right after the action that raises the toast cannot miss it. It does not consume
the toast: two calls in a row can both match it.

Without `package_name`, a toast from any package matches; `app.await_toast(text, mode)` matches
only that app's (on Android 11+ a text toast is drawn by SystemUI but still reported under the
app that posted it). `mode` is a selector `MatchMode` (`CONTAINS`, `REGEX`, …). Nothing within
the timeout is a `WaitTimeoutError` with reason `NO_TOAST`.

```python
app.element(res("save")).tap()
assert app.await_toast().text == "Saved"
app.await_toast("Saved")   # the same check, as a wait for that text
```

### Retained history (Python and tap-agent)

For longer pauses, `device.toast_history()` reads the driver's last 128 toast observations,
oldest first, without waiting or consuming them. They have no age-based expiry. Each event
has a sequence, device wall timestamp, and toast text/package. History is not current UI state.

```python
page = device.toast_history()
for event in page.events:
    print(event.sequence, event.toast.text)
next_page = device.toast_history(page.next_sequence, stream_id=page.stream_id)
```

Check `gap` for lost observations and `reset` for a lost stream or an ahead-of-stream cursor.
The history is memory-only: driver loss, release/reuse and session termination end it. Text
is capped at 1024 UTF-16 units (`text_truncated` reports it). Accessibility delivery is not a
guarantee that every Android/OEM/custom toast is observable.

```sh
tap-agent toast                                   # retained toasts, oldest first
tap-agent toast saved --package com.example       # part of the text, any case
tap-agent toast 'Card saved' --exact              # the whole text, case included
tap-agent toast --after CURSOR --json             # read on from the `next:` cursor
```

The printed cursor covers all observations, including those excluded by text/package filters.
History reads do not affect the existing toast wait's 3.5-second lookback.

For agents, snapshots and settle responses deliver and acknowledge unread toast observations.
CLI and MCP share that unread cursor. A `toast` read, including one with
`--after CURSOR`, never acknowledges it. See
[catching toasts between actions](../agent/session.md#catch-toasts-between-actions) for limits
and recovery.

Custom-view toasts posted from the background are blocked by Android itself (11+) and never
appear.

## Notifications

Tap reads notifications as data, from a notification listener in its driver app (it gets
notification access for the session and gives it back on detach); the shade stays closed. A
`Notification` has `package_name`, `title`, `text`, `actions` (its button titles), `clearable`
and `posted_at`.

| Call | Does |
|---|---|
| `await_notification(title, text, mode, *, package_name, timeout)` | waits until a matching notification is showing (one already posted counts) and returns the newest; nothing within the timeout is a wait timeout with reason `NO_NOTIFICATION`. `app.await_notification(...)` matches only that app's |
| `notifications()` | every notification showing, newest first |
| `open_notification(title, text, mode, *, package_name, action)` | opens it as a tap in the shade does (its content intent; an auto-cancel notification then goes away), or with `action` presses the button with that title |
| `dismiss_notification(title, text, mode, *, package_name)` | swipes it away; an ongoing one is refused (`ACTION_REJECTED` / `NOT_CLEARABLE`) |

`title` and `text` match under `mode` (`EXACT`, `CONTAINS`, `REGEX`, …); every argument given
must match. Open and dismiss act on exactly one notification: none or several fail with
`NOT_FOUND` / `AMBIGUOUS` before anything happens. Group summaries are not listed. On Android
13+ an app needs the `POST_NOTIFICATIONS` permission to post at all.

```python
app.element(res("send")).tap()
message = app.await_notification("New message")
assert message.actions == ["Mark as read"]
device.open_notification("New message", package_name=app.package_name)
assert device.foreground_activity().class_name == "com.example.chat.ConversationActivity"
```

```python
device.open_notification("New message", package_name=app.package_name, action="Mark as read")
device.dismiss_notification("Syncing", package_name=app.package_name)   # ongoing: NOT_CLEARABLE
```

`device.foreground_activity()` ([Where am I?](device-control.md#where-am-i)) tells you where
opening a notification landed.
