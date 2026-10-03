# Keys, screen and rotation

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`press_back` → `pressBack`, `set_orientation` →
    `setOrientation`). [Kotlin + JUnit 5](../sdk/kotlin.md#keys-screen-and-rotation) shows this
    page's examples in Kotlin.

These are on `Device`: they act on the device as a whole, not on an element.

## Keys

| Method | What happens on the device |
|---|---|
| `press_back()`, `press_home()`, `press_key(code)` | a key event (`press_key` takes an Android key code, such as `66` for Enter) |
| `type_text(value)` | types into whatever has input focus ([Text](elements.md#text)) |
| `keyboard_shown()` | whether any soft keyboard is on screen (also `info().keyboard_shown`) |
| `hide_keyboard()` | hides it with one Back key. No keyboard: nothing is sent, so Back never reaches the app |

## The screen and the lock screen

| Method | What happens on the device |
|---|---|
| `wake()`, `sleep()` | turn the screen on or off (the `WAKEUP` / `SLEEP` keys; nothing happens when it already is). Waking does not dismiss the lock screen |
| `dismiss_keyguard()` | dismisses a lock screen that has no PIN, pattern or password (none showing: nothing is sent). A secure one fails with `ACTION_REJECTED` / `KEYGUARD_SECURE` before any input: Tap never unlocks it |

`info()` reports `screen_on`, `keyguard_locked` and `keyguard_secure`.

## System panels

`open_notifications()` and `open_quick_settings()` open the notification shade or quick
settings through the system's accessibility action, as a swipe down from the status bar would.
Only whether the system accepted it is reported, so wait for what you need in the panel.
`press_back()` closes it; from quick settings, Android 14 first goes back to the notification
shade, so press it twice.

Elements in the panel belong to `com.android.systemui`: reach them with
`device.app("com.android.systemui")` or `device.screen`. While a panel is still sliding open or
closed, the system can accept the action and ignore it: `app.await_animation_end()` before
opening another one.

```python
device.open_quick_settings()
device.screen.wait(desc("Wi-Fi")).visible()
device.press_back()
```

To read notifications, there is no need to open the shade: see
[Notifications and toasts](notifications.md).

## Clipboard

`set_clipboard(text)` and `clipboard()` write and read the device clipboard as plain text (an
empty one reads as `""`), without moving focus away from the app. On Android 12+ a read shows
the system's "Tap Driver pasted from your clipboard" notice.

## Rotation

| Method | What happens on the device |
|---|---|
| `set_orientation(orientation)` | `PORTRAIT` or `LANDSCAPE`: chooses the screen geometry and freezes sensor rotation, on portrait-natural phones and landscape-natural tablets alike |
| `set_display_rotation(rotation)` | `NATURAL`, `LEFT`, `UPSIDE_DOWN` or `RIGHT`: an exact clockwise rotation relative to the device's natural orientation, frozen |
| `unfreeze_rotation()` | releases the sensor lock without selecting a new rotation |

```python
from tap_e2e import DisplayRotation, Orientation

device.set_orientation(Orientation.LANDSCAPE)
app.wait(res("two_pane")).visible()
device.set_display_rotation(DisplayRotation.RIGHT)
device.unfreeze_rotation()   # optional: detach restores the device's own settings anyway
```

Rotation calls fail only when Android refuses the rotation (`ACTION_REJECTED`). They wait up to
two seconds for the display to turn, but an app that locks its own orientation can keep it where
it was without that being an error: assert what you need, for example
`info().display_rotation`; `info().auto_rotate` says whether the sensor turns the display (false
while a rotation is frozen).

Before the session's first rotation call, the server captures the device's auto-rotate
settings, and detach restores them. A failed restore is a visible detach failure and
quarantines the device rather than leaving it changed silently.

## Where am I?

`foreground_activity()` returns the activity on top as `ForegroundActivity(package_name,
class_name)`, or `None` when none is showing (a lock screen): use it to check where a
notification, a deep link or a back press landed. `info()` returns the rest of the device's
state ([Screenshots and artifacts](artifacts.md#screenshots-and-dumps)).
