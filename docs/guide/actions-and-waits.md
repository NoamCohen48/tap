# Actions and waits

Everything on this page is a method of `Element` (built with `app.element(selector)` or
`device.screen.element(selector)`), `ElementWait` (`app.await(selector)` /
`device.screen.await(selector)`; `.wait(...)` in Python), `App` or `Device`. The examples use
`val app = device.app("com.shop")`. Kotlin names are shown; Python uses the same names in
`snake_case` (`setText` → `set_text`, `awaitSettled` → `await_settled`, `app.await(...)` →
`app.wait(...)`).

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
| `typeText(value, awaitFocus = true)` | three client-side steps: `tap()`, then `await().focused()` (skip it with `awaitFocus = false`), then `device.typeText(value)`. Not read back |
| `device.typeText(value)` | type character by character with real key events (IME-free) into whatever has input focus now; no target, no click, no settling. Unsupported characters (outside Android's virtual key map, e.g. emoji) are rejected *before* input with `INVALID_REQUEST`/`UNSUPPORTED_CHARACTERS` |
| `clearText()` | `setText("")` |
| `swipe(direction, distancePercent = 80)` | one swipe gesture across the node, in the direction the finger moves. Returns nothing: whether the screen moved is for the test to assert |
| `scroll(direction, distancePercent = 80)` | one scroll gesture on the node towards `direction`'s content edge (`DOWN` reveals content below). The node need not report itself scrollable, and nothing says whether content moved |
| `scrollUntil(target, direction = DOWN, maxScrolls = 20, distancePercent = 80, timeout)` | a client-side loop: while `target` does not exist inside the container, `scroll` once more; returns `target` as an `Element`. Gives up with `WaitTimeoutException` / `WaitTimeoutError` after `maxScrolls` scrolls or the timeout (default: the wait timeout). A failing scroll step propagates unchanged |
| `doubleTap()` | two taps at the centre of the node's visible bounds, inside Android's double-tap window, as one gesture. Like a tap, this and the gestures below fail with `OBSCURED` when another window covers the touch point (the centre; a fling's start) |
| `dragTo(destination)` | press on the node until it is a long press, move to the centre of `destination` (a selector, exactly one match), hold briefly so drop targets see the finger arrive, release. Both nodes are found before the finger goes down: a missing or ambiguous destination fails with no input |
| `pinchOpen(percent = 80)`, `pinchClose(percent = 80)` | two fingers moving apart (zoom in) or together (zoom out) across `percent` of the node's size |
| `fling(direction)` | one fast swipe across the whole node towards `direction`'s content edge, as `scroll`. It does not wait for the content to stop moving |
| `device.pressBack()`, `device.pressHome()`, `device.pressKey(code)` | key events |
| `device.wake()`, `device.sleep()` | turn the screen on or off (the `WAKEUP` / `SLEEP` keys; nothing happens when it already is). Waking does not dismiss the keyguard |
| `device.dismissKeyguard()` | dismiss a lock screen that has no PIN, pattern or password (none showing: nothing is sent). A secure one fails with `ACTION_REJECTED` / `KEYGUARD_SECURE` before any input: Tap never unlocks it. `device.info()` reports `screenOn`, `keyguardLocked` and `keyguardSecure` |
| `device.openNotifications()`, `device.openQuickSettings()` (Python: `open_notifications()`, `open_quick_settings()`) | open the notification shade or quick settings through the system's accessibility action, as a swipe down from the status bar would. Only whether the system accepted it is reported: wait for what you need in the panel; `pressBack()` closes it (from quick settings, Android 14 first goes back to the notification shade, so press it twice). Elements in the panel belong to `com.android.systemui`: reach them with `device.app("com.android.systemui").element(...)` or `device.screen.element(...)`. While a panel is still sliding open or closed, the system can accept the action and ignore it: `app.awaitAnimationEnd()` before opening another one |
| `element.imeAction()` (Python: `ime_action()`) | run the field's keyboard action key (Search, Go, Send, Done, …: whatever the app configured), as the keyboard's own key does. The field must have input focus, so `tap()` it first; a node that does not offer the action fails with `ACTION_REJECTED` before input. Android 11 (API 30)+; older devices fail with `UNSUPPORTED` before input. Pressing Enter is not the same: apps waiting for "Search" ignore it |
| `performAction(action)`, `performCustomAction(label)` (Python: `perform_action`, `perform_custom_action`) | run one of the node's accessibility actions as a screen reader does, with no touch: a `StandardAction` (`EXPAND`, `COLLAPSE`, `DISMISS`, `SCROLL_FORWARD`, `PAGE_DOWN`, `SELECT`, `COPY`, …) or an app-defined custom action by its label ("Archive", "Mark unread"). An action the node does not offer fails with `ACTION_REJECTED` / `ACTION_NOT_OFFERED` before input; `snapshot().actions` and `customActions` list what it offers. Page actions need Android 10, press-and-hold Android 11 (`UNSUPPORTED` below) |
| `setProgress(value)` (Python: `set_progress`) | set a slider, seek bar or rating bar to `value` in its own units (`snapshot().range` gives the type, min, max and current value). A value outside the range fails with `ACTION_REJECTED` / `OUT_OF_RANGE` before input instead of being clamped |
| `device.keyboardShown()`, `device.hideKeyboard()` (Python: `keyboard_shown()`, `hide_keyboard()`) | whether any soft keyboard is on screen (also `device.info().keyboardShown`); hide it with one Back key. No keyboard: nothing is sent, so Back never reaches the app |
| `device.setClipboard(text)`, `device.clipboard()` | write or read the device clipboard as plain text (empty reads as `""`), without moving focus away from the app. On Android 12+ a read shows the system's "Tap Driver pasted from your clipboard" notice |
| `device.setOrientation(orientation)` | `PORTRAIT` or `LANDSCAPE`: choose the screen geometry and freeze sensor rotation, on portrait-natural phones and landscape-natural tablets alike |
| `device.setDisplayRotation(rotation)` | `NATURAL`, `LEFT`, `UPSIDE_DOWN` or `RIGHT`: an exact clockwise rotation relative to the device's natural orientation, frozen |
| `device.unfreezeRotation()` | release the sensor lock without selecting a new rotation |

Text actions report only what Android said about the input, never what the app did with it:
apps reformat, truncate, reject or copy text elsewhere, and the framework assumes none of
that. Assert the outcome with a selector that still identifies the field after the edit (its
resource id, not its old text):

```kotlin
val email = app.element(res("email"))
email.setText("user@example.com")
email.await().textEquals("user@example.com")
```

Rotation calls fail only when Android refuses the rotation (`ACTION_REJECTED`). They wait up
to two seconds for the display to turn, but an app that locks its own orientation can keep it
where it was without that being an error: assert what you need, for example
`device.info().orientation`; `device.info().autoRotate` says whether the sensor turns the
display (false while a rotation is frozen). Before the session's first rotation call, the host captures the
device's auto-rotate settings; detach restores them. A restoration failure is a visible detach
failure and quarantines the device rather than leaving changed state silently.

```kotlin
try {
    device.setOrientation(Orientation.LANDSCAPE)
    // assertions in landscape
    device.setDisplayRotation(DisplayRotation.RIGHT)
} finally {
    device.unfreezeRotation() // optional during the session; detach still restores initial state
}
```

```python
device.set_orientation(Orientation.LANDSCAPE)
device.set_display_rotation(DisplayRotation.RIGHT)
device.unfreeze_rotation()
```

### Device conditions

Device-wide settings can be changed for the session. Each change is read back (a value the
device did not take fails with `ServerException` / `ServerError`, reason `DEVICE_SETTING`), and
what the device had before the session's first change comes back on detach, as for rotation.
`info()` reports the current values.

| Call (Kotlin / Python) | Changes | Read back |
|---|---|---|
| `setAnimations(enabled)` / `set_animations` | window, transition and animator scales, all 0 or all 1 | `animationsEnabled` |
| `setDarkMode(enabled)` / `set_dark_mode` | `cmd uimode night`; API 29+ (below: reason `UNSUPPORTED_API`). Some devices (Samsung's One UI) lock the day/night mode: there it fails with `DEVICE_SETTING` | `darkMode` |
| `setFontScale(scale)` / `set_font_scale` | system font scale, 0.5 to 2.0 | `fontScale` |
| `setDensity(dpi)` / `set_density` | display density override, 100 to 1000 dpi; `null` / `None` for the physical density | `densityDpi` |
| `setNetwork(airplaneMode, wifi, mobileData)` / `set_network` | the real switches (pass only the ones to change); API 29+. Airplane mode turns Wi-Fi off, as on a phone, unless the call also turns Wi-Fi on. A device reached over ADB on Wi-Fi refuses Wi-Fi off and airplane mode on: Tap would lose it | `airplaneMode`, `wifiEnabled`, `mobileDataEnabled` |
| `setSystemLocales(tags)` / `set_system_locales` | the device's languages (Settings › Languages), BCP-47 tags in preference order; every app that follows the system language sees it. Apps with their own language (`App.setLocales`) keep it | `systemLocales` |
| `setLocation(latitude, longitude, accuracyM, altitudeM)` / `set_location` | a mock location: the GPS and network providers report this fix (re-sent every second, so an app that starts listening later gets it); call again to move it. The Tap driver app becomes the device's mock-location app and location is turned on if it was off; both come back on detach, which ends the mock | the app's own location |

```kotlin
device.setAnimations(false)
device.setDarkMode(true)
device.setFontScale(1.3f)
val app = device.app("com.example.shop")
app.launch()
app.await(text("Large text")).visible()
```

```python
device.set_animations(False)
device.set_density(None)  # back to the display's own density
assert device.info().animations_enabled is False
```

```kotlin
device.setSystemLocales("de-DE", "en-US")
device.setNetwork(wifi = false, mobileData = false) // offline, without airplane mode
device.setLocation(48.8584, 2.2945, accuracyM = 5f)
app.grantPermission("android.permission.ACCESS_FINE_LOCATION")
```

```python
device.set_system_locales(["de-DE", "en-US"])
device.set_network(airplane_mode=True)
device.set_location(48.8584, 2.2945, accuracy_m=5)
```

Android applies dark mode, font scale, density and the languages as configuration changes: a
running app's activities are recreated unless it handles the change itself, so wait for what
the test needs after changing one. Network switches take time to reach the app's connectivity
callbacks; wait for the app's own offline or online state. On some devices turning location on
also shows Google Play services' "improve location accuracy" prompt. The app's own language is on `App`: see
[App lifecycle](app-lifecycle.md).

Gestures are reported the same way: `done` means Android accepted the injected input, not that
the app zoomed, dropped or scrolled. Wait for the effect:

```kotlin
app.element(res("card")).dragTo(res("done_column"))
app.await(text("Moved to Done")).visible()
```

Directions are the `Direction` enum, `UP`/`DOWN`/`LEFT`/`RIGHT`, in both SDKs (Python also
exports them as plain constants).

```kotlin
val list = app.element(res("results"))
list.scrollUntil(text("Wool socks"), timeout = 30.seconds).tap()
```

An action that fails **before** input (`NOT_FOUND`, `AMBIGUOUS`, `INVALID_*`, and
`NOT_INTERACTABLE` — including `OBSCURED`, a gesture whose touch point another window covers)
has changed nothing. An action that fails **after** input says so:
`STALE_DURING_COMMAND` (the target changed mid-action), `ACTION_REJECTED` (input was issued but
did not take effect), `INDETERMINATE` (the transport dropped after the driver accepted the
mutation). Tap never re-sends any of them for you.

### Files and the gallery

`device.pushFile(devicePath, content)` copies bytes, or a file on the test machine, to the
device; `device.pullFile(devicePath)` returns a device file's bytes (or writes it to a local
path). `device.addMedia(fileName, content)` puts a photo or video in the gallery, where gallery
apps and photo pickers list it, and returns where it landed. The bytes are streamed through the
server (at most 512 MiB), so the test machine and the server need not share a disk.

- Paths are absolute: `/data/local/tmp/…`, or shared storage such as `/sdcard/Download/…` for
  an app with storage access to read. The directory must exist.
- Tap never overwrites a file it did not create: pushing over a device file fails with
  `ServerException` / `ServerError`, reason `DEVICE_FILE` (as do a missing directory and a
  pull of something that is not a file). Pushing again to a path this device handle pushed
  replaces it.
- Media names end in a photo (`jpg`, `jpeg`, `png`, `gif`, `webp`, `heic`, `heif`, `bmp`) or
  video (`mp4`, `3gp`, `webm`, `mkv`, `mov`) extension and go to `Pictures/Tap` or
  `Movies/Tap`. The media scanner must index the file, so it has to be a real image or video.
- Every pushed file and added media item is deleted on detach (and the gallery entry with it);
  nothing else on the device is touched.

```kotlin
device.pushFile("/sdcard/Download/invoice.pdf", Path.of("fixtures/invoice.pdf"))
val photo = device.addMedia(Path.of("fixtures/cat.jpg"))  // "/sdcard/Pictures/Tap/cat.jpg"
app.grantPermission("android.permission.READ_MEDIA_IMAGES")
val log = device.pullFile("/sdcard/Android/data/com.example.shop/files/log.txt").decodeToString()
```

```python
device.push_file("/sdcard/Download/invoice.pdf", "fixtures/invoice.pdf")
device.add_media(png_bytes, "cat.png")
log = device.pull_file("/sdcard/Android/data/com.example.shop/files/log.txt")
device.pull_file("/data/local/tmp/trace.txt", "out/trace.txt")
```

## Waiting for elements

`app.await(selector, timeout)` (Python `app.wait(...)`; `device.screen` has the same) returns an `ElementWait`; each
terminal method waits until the condition holds or the timeout elapses, and then returns the
`Element` so you can act on it:

```kotlin
app.await(text("Order placed")).visible()
app.await(res("pay")).enabled().tap()
app.await(res("spinner"), timeout = 30.seconds).gone()
```

| Method | Condition |
|---|---|
| `visible()` | at least one match |
| `one()` | exactly one match (what `tap()` and other actions need) |
| `gone()` | zero matches |
| `enabled()`, `disabled()` | exactly one match with that state |
| `checked()`, `unchecked()`, `focused()` | likewise |
| `textEquals(s)`, `textContains(s)` | text of the single match |
| `count(n)` | exactly `n` matches |

`visible()`, `one()` and `gone()` poll **on the device** in a single round trip; the property
waits take a snapshot from the host every `pollInterval` (100 ms). A miss raises
`WaitTimeoutException` / `WaitTimeoutError`. For the device waits it carries `reason`
(`WaitReason.NO_MATCH`, `AMBIGUOUS` for `one()`, `STILL_PRESENT` for `gone()`) and
`matchCount` / `match_count` from the last poll; for the property waits, the number of polls and
the last observation (e.g. `text='Placing order…' enabled=False …`). `visible()` passes with
several matches, so when the next step is an action, `one()` is the wait that proves it can
run. Note that `visible()` means
*present in the accessibility tree*, which is what UiAutomator can see; an element scrolled
off-screen in a `RecyclerView` is usually absent from the tree, and so is one that another
window (a dialog, the keyboard) covers completely: Android reports it as not visible. One that
another window covers only partly is present, and a gesture whose touch point is under the
other window fails with `OBSCURED`.

`Element.await()` is the same thing starting from an element you already hold.

## Waiting for a toast

`device.awaitToast(text = null, mode = EXACT, packageName = null, timeout)`
(Python: `await_toast(text=None, mode=..., *, package_name=None, timeout=None)`)
returns a `Toast(text, packageName)`. A toast shown in the last 3.5 s counts (the longest one
stays up), so calling it right after the action that raises the toast cannot miss it. It does
not consume the toast: two calls in a row can both match it. Without `packageName` a toast
from any package matches; `app.awaitToast(text, mode)` (Python `app.await_toast`) matches only
that app's (on Android 11+ a text toast is drawn by SystemUI but still reported under the app
that posted it). `mode` is a
selector `MatchMode` (`CONTAINS`, `REGEX`, …). Nothing within the timeout is a
`WaitTimeoutException` / `WaitTimeoutError` with reason `NO_TOAST`.

```kotlin
app.element(res("save")).tap()
assertEquals("Saved", app.awaitToast().text)
```

```python
app.element(res("save")).tap()
app.await_toast("Saved")
```

Custom-view toasts posted from the background are blocked by Android itself (11+) and never
appear.

## Waiting for the app or the screen

These are on `App` (`device.app(packageName)`).

| Method | Waits until |
|---|---|
| `app.awaitVisible()` | the package owns the focused window (a launch or a return from another app is done) |
| `app.awaitSettled(stableFor = 500 ms)` | the accessibility **tree** of the app's focused window has not changed for `stableFor` |
| `app.awaitAnimationEnd(stableFor = 500 ms)` | the **pixels** of the app's window have not changed for `stableFor` |
| `app.awaitScreenStable(stableFor = 500 ms, signal = ALL)` | both (or the signal you pass) |
| `app.awaitIdle(stableFor = 200 ms)` | the app itself reports no busy work — see [App lifecycle and sync](app-lifecycle.md) |

Use `awaitSettled` after navigation, when a list is still being populated or a screen
rebuilt: it is cheap (a fingerprint of the tree, refreshed on window-change events) and ignores
purely visual motion. Use `awaitAnimationEnd` before a screenshot or when a transition animates
without touching the tree. `awaitScreenStable` combines both.

A screen that never goes quiet — a ticking clock, an indeterminate spinner — fails with
`WAIT_TIMEOUT`/`SCREEN_CHANGING` rather than returning "stable enough". If that happens, wait
for the element you actually need instead, or pass `signal = TREE` to ignore the pixels.

!!! info "There is no implicit wait"
    UiAutomator's own idle wait before each interaction is capped at 1 s by the driver, and Tap
    adds none of its own. If a test only passes with a `sleep`, it is telling you which wait
    is missing: usually `app.await(...).visible()` on the thing you are about to use.

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

Each returns a typed value, not a file: keep it in memory, assert on it, attach it to a report
or write it where you like. They share one artifact shape: `bytes` (the serialized form),
`mediaType` / `media_type`, `extension` and `save(path)` (creates parent directories).

- Recordings: `device.startRecording()` / `device.stopRecording()` in Kotlin, or
  `device.start_recording()` / `device.stop_recording()` in Python, return a `Recording`
  artifact (`bytes`, `mediaType` / `media_type`, `extension`, `save(path)`). Nothing is recorded
  unless you ask; this is not a live screen stream or an automatic failure artifact.
    - Tracks: the default is video only (MP4, up to 30 seconds/16 MiB; 1024 px, at most 15 fps).
      Add `audioSource = "output"` (Python: `audio_source="output"`) for video and audio in one
      Matroska file, or `video = false` with an audio source for Opus audio only (up to 60
      seconds/3 MiB). `maxSeconds` / `max_seconds` ends the capture early.
    - Audio sources: `output` (Android 11+) records device playback but redirects it away from
      the device speakers; `playback` (Android 13+) keeps local playback but apps may opt out;
      `mic` records the device microphone. Android 10 and earlier can record video only.
      `output` records *after* the device's media volume, and that route has its own volume
      (at the default 5 of 15 a test tone comes out about 32 dB quieter); `playback` records
      the app's sound before volume. Use `playback` on Android 13+ when levels matter, or set
      the media volume while an `output` recording runs.
    - The daemon host needs [scrcpy](https://github.com/Genymobile/scrcpy)
      (`tap start --scrcpy PATH`, default `scrcpy` on `PATH`). One recording per attached
      device; detach discards an unfinished one.
    - Timing: the start call returns while scrcpy is still starting on the device (about half a
      second), so the first moment can be missing, and a capture the device refuses is reported
      by the stop call. Do not stop a recording immediately after starting it. A static screen
      produces no new frames, so a video file can be shorter than the time it was recording.
- `device.screenshot()` → `Screenshot`: the PNG `bytes` (checked against the server's
  SHA-256), its `format` and its `width` / `height`.
- `device.dumpHierarchy()` → `Hierarchy`: the accessibility tree as `xml` (a string) and as
  UTF-8 `bytes`. Diagnostic only; lookups never use it.
- `device.driverLog()` → `DriverLog`: the driver's recent `lines` for the session, `text`
  joined with newlines.
- `device.info()` → `DeviceInfo`: API level, manufacturer, model, display size and rotation,
  and the package owning the focused window; `bytes` is JSON.

=== "Kotlin"

    ```kotlin
    val shot = device.screenshot()
    println("${shot.width}x${shot.height}")
    shot.save(Path.of("build/shots/login.png"))
    ```

=== "Python"

    ```python
    shot = device.screenshot()
    print(shot.width, shot.height)
    shot.save("build/shots/login.png")
    ```

`device.capture()` takes all four at once, in parallel, each within a timeout (default 30 s),
and returns a `Capture`: the `screenshot`, `hierarchy`, `info` and `driverLog` / `driver_log`
parts, `artifacts` (the produced ones by name) and `failures` (why a missing part is missing).
It never throws for the device, so it is safe in a `catch` / `except`. `saveTo(dir)` /
`save_to(dir)` writes `<prefix>.<part>.<ext>` files (prefix defaults to the serial).

=== "Kotlin"

    ```kotlin
    val capture = device.capture()
    capture.saveTo(Path.of("build/evidence/after-login"))
    capture.failures.forEach { (part, why) -> println("no $part: ${why.message}") }
    ```

=== "Python"

    ```python
    capture = device.capture()
    capture.save_to("build/evidence/after-login")
    for part, why in capture.failures.items():
        print(f"no {part}: {why}")
    ```

The JUnit extension and the pytest plugin call `capture()` for every device of a failed test
([Failure artifacts](errors.md#failure-artifacts)).
