# Device actions: roadmap and decisions

Status: phases A–D, group 1 and group 2 implemented (branch `feat/device-actions`); group 1
proven on emulator-5554 (API 34) and the Samsung (API 29); group 2 device runs pending.
Started from the pi session report (`.docs/pi-session-…html`, not committed) and continued on
2026-09-30.

## Rule for every action here: report, don't judge

The driver fails only on what Android returns (a refused injection, a missing node, an action the
node does not offer), never because the app did not react. A bounded settle wait is fine when
Android itself needs a moment (rotation); the test asserts the effect. Mutations still resolve
their target to exactly one match before any input (`AMBIGUOUS` / `NOT_FOUND` first).

## Rule: every change has a read-back

Because the driver reports only Android's acceptance, a test can assert an effect only if it can
read the state back. Every action that changes device or app state ships with a way to read
that state (decided 2026-10-01). Cheap, per-command state goes in `DeviceInfo` (`info()` is
polled often, so nothing slow goes there); slower or rarer reads get their own call.

| Changes | Read back |
|---|---|
| rotation | `DeviceInfo.displayRotation`, `orientation`, `autoRotate` |
| wake / sleep / dismiss keyguard | `screenOn`, `keyguardLocked`, `keyguardSecure` |
| hide keyboard | `keyboardShown` |
| set clipboard | `clipboard()` |
| grant / revoke / clear data / permission dialog | `App.isPermissionGranted(name)` |
| app stop / launch / foreground | `App.isRunning()`, `process()`, `DeviceInfo.currentPackage` |
| animations / dark mode / font scale / density | `DeviceInfo.animationsEnabled`, `darkMode`, `fontScale`, `densityDpi` |
| app languages | `App.locales()` |

`autoRotate` came from the Samsung: with auto-rotate on, the restored display follows the
sensor, so the rotation-restore tests could only be written once the setting was readable.

## Done (phases A–D)

| Phase | Actions | Proven by |
|---|---|---|
| A | `setOrientation`, `setDisplayRotation`, `unfreezeRotation`; detach restores the device's auto-rotate settings (quarantine if it cannot) | `RotationTest`, samples `DeviceActionsTest` hold/restore, Python `test_device_actions.py` |
| B | `wake`/`sleep` (keys 224/223), `dismissKeyguard` (never a secure one: `KEYGUARD_SECURE`), `DeviceInfo.screenOn/keyguardLocked/keyguardSecure`; `App.foreground` (launcher intent: the task as it was left), `App.background` (Home), `App.openLink(uri, anyApp)` | `ScreenTest` (swipe keyguard dismissed on the Samsung), `DeviceActionsTest` |
| C | `awaitPermissionPrompt` → `PermissionPrompt{packageName, choices}`, `choosePermission(choice)`, by controller resource id | `PermissionTest` (API 34 and API 29) |
| D | `doubleTap`, `dragTo(destination)`, `pinchOpen/Close(percent)`, `fling(direction)` | `GestureTest`, `DeviceActionsTest` |

Also: `device.app(pkg)` works on any package (stop, clear, launch, uninstall) except Tap's own
driver packages, which the server refuses (`INVALID_ARGUMENT`) because stopping them ends the
session.

Decided and left alone: `App.launch()` keeps `am start -n` (starts that activity, on top of the
task if one exists); `foreground()` is the "tap the icon" path.

## Group 1 (implemented)

Chosen 2026-10-01 as the actions real app tests hit most. Upstream references are the pinned
checkouts in `upstream-reference-audit.md`.

### Keyboard: `keyboardShown`, `hideKeyboard`

- **State**: `DeviceInfo.keyboard_shown` = an `AccessibilityWindowInfo.TYPE_INPUT_METHOD` window
  is on screen (`UiAutomation.getWindows()`; UiDevice already sets
  `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`). Works for any IME. Maestro instead searches the hierarchy
  for Gboard's `com.google.android.inputmethod.latin:id` (one keyboard only); Appium reads
  `dumpsys input_method` (`mInputShown`), a host shell parse.
- **Hide** (`hide_keyboard`, mutation): no IME window → `done`, nothing sent. Otherwise one Back
  key, which the IME consumes to hide itself. Maestro presses Back unconditionally (navigates
  back when no keyboard is up); checking first avoids that. Residual race: a keyboard closing on
  its own between the check and the key lets Back reach the app. Only the key is reported: assert
  `keyboardShown == false` if it matters.
- SDK: `device.keyboardShown()` (from `info()`), `device.hideKeyboard()`.

### Keyboard action key: `Element.imeAction()`

- `perform_ime_action` (mutation, one match): `AccessibilityAction.ACTION_IME_ENTER` on the node,
  which runs the field's `onEditorAction` with its configured action (Search, Go, Send, Done, …),
  exactly as the keyboard's action key does. API 30+ only (the action does not exist before).
- A node that does not offer it → `ACTION_REJECTED` before input. Found on device: `TextView`
  answers `true` to `ACTION_IME_ENTER` on any view (a `Button` too) and does nothing, so the
  driver checks the node's `actionList` first. The action is listed only for an editable field
  with input focus: tap the field first. Below API 30: refused before input (decision 2).
- Pressing Enter (`KEYCODE_ENTER`) is not the same thing: `TextView` passes `IME_NULL` as the
  action id, so an app checking for `IME_ACTION_SEARCH` ignores it.

### Launch with intent extras

- `LaunchRequest` / `ColdLaunchRequest` gain `repeated IntentExtra extras`:
  `IntentExtra{key, oneof value {string, bool, int32, int64, float}}` → `am start --es/--ez/--ei/--el/--ef`.
  Those five exist on every supported API; `--ed` (double) is missing on API 29 (checked on
  85e49002), arrays and URIs are left for later.
- Keys: non-empty, no whitespace/control characters; values shell-quoted (like `openLink`).
- Kotlin: `launch(activity, extras = mapOf("flag" to true, "id" to 42L))`; the map's value types
  are `String`, `Boolean`, `Int`, `Long`, `Float` (anything else: `IllegalArgumentException`
  before the call). Python: `str`, `bool`, `int` (→ int32), `float` (→ float); `tap_e2e.Long(n)`
  for an int64 extra, so a mismatch is never silent (an app reading `getLongExtra` on an int
  extra gets its default).

### Revoke a permission

- `AppService.RevokePermission` → `pm revoke`, verified like `grantPermission` (`dumpsys
  package` no longer lists it as granted). Android kills the app's process when a runtime
  permission is revoked; documented, not hidden.
- SDK: `app.revokePermission(name)`.

### Clipboard: `setClipboard(text)`, `clipboard()`

- Write: `ClipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))` from the driver on
  the main thread (Appium's `SetClipboard`). Background writes are allowed on every API.
- Read: API 29+ restricts reads to the focused app or the default IME. Appium reads through its
  separate Settings app there; uiautomator2 (openatx) has it disabled. Tap: the driver adopts the
  shell's `READ_CLIPBOARD_IN_BACKGROUND` (`UiAutomation.adoptShellPermissionIdentity`, API 29+);
  the shell holds it on both local devices (checked with `dumpsys package com.android.shell`).
  No focus change, no extra app. On API 34 Android shows "Tap Driver pasted from your clipboard"
  for each read (the shell does not hold `SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION`);
  `ClipboardTest` records it. Still to verify on device: that `ClipboardService` honours the
  adopted permission on API 29.
- Plain text only (`coerceToText`); an empty clipboard reads as `""`.
- Maestro's `setClipboard`/`pasteText` never touch the device clipboard (host memory, typed as
  text); not what an app's paste button needs.

### Toasts: `awaitToast`

- The driver installs an accessibility-event listener at session start and keeps a small ring
  buffer of `TYPE_NOTIFICATION_STATE_CHANGED` events whose class is a `Toast` (notifications raise
  the same event type with a `Notification` parcelable; they are skipped): text, package, time.
  Appium does the same (`NotificationListener`) but keeps only the last toast for 3.5 s.
- UiAutomation has one listener. androidx UiAutomator's `QueryController` installs one that only
  feeds the legacy `UiObject`/`UiScrollable` API (last activity name, traversed text), which the
  driver never uses; `executeAndWaitForEvent` (screen stability) uses UiAutomation's own queue
  and still works with a listener set. The driver's listener replaces it without chaining:
  chaining needs the hidden `getOnAccessibilityEventListener` (Appium reflects on it), and
  nothing the driver calls reads what that listener records.
- `await_toast{text?, mode, package_name?}` (query, no input): `done` with
  `Toast{text, package_name}` once a matching toast is in the buffer within the lookback window
  or arrives before the timeout; `WAIT_TIMEOUT` / `NO_TOAST` otherwise. Without `package_name`
  a toast of any package matches; `app(pkg).awaitToast` passes the app's (protocol 5.0: an
  attached device names no app, so the earlier "the AUT's toasts unless `any_package`" default
  and the `any_package` flag were removed in the rebase onto it). Text uses the selector
  `MatchMode`s (exact, contains, regex, …).
- Android 11+ renders text toasts in SystemUI and still sends the event; custom-view toasts from
  the background are blocked by Android itself and never appear.

### Decided for group 1 (2026-10-01, with the user)

1. Toast window: `awaitToast` matches a toast from the last 3.5 s (the longest a toast stays up,
   `LENGTH_LONG`) or one arriving before the timeout. Not consuming: the same toast can satisfy
   two calls in a row.
2. Keyboard action below API 30: refused before input (`UNSUPPORTED`, the message names API 30),
   no fallback to Enter.

## Group 2: device conditions (implemented)

Chosen 2026-10-01 (after group 1): animations off, per-app language, dark mode, font scale,
display density. All are restored on detach, which needed one generic mechanism.

### Saved device state (generic restore, rotation moved onto it)

- Before the session's first change of a value, the server reads it over ADB and appends
  `SavedState{key, value}` to the journal (`SessionJournal.savedState`, omitted when empty so
  older engines read the file unchanged). Later changes of the same value capture nothing: detach
  restores what the device had before the session, whatever happened in between.
- Keys: `setting:<namespace>/<name>`, `uimode:night`, `wm:density`, `locale:<package>`
  (`StateKey`). Rotation's two settings are now just two keys; the rotation hook calls the same
  capture.
- Detach writes back newest first (rotation: auto-rotate locked first, so the frozen rotation
  is written while the sensor cannot move it), reads every value back, and quarantines on a
  mismatch. This is the journal fix for rotation: before, a server that died mid-session left
  the rotation changed with no record; now the next attach restores it from the journal
  (`restorePriorState`, quarantine `DEVICE_STATE_RESTORE_FAILED` when it does not take).

### The conditions

| Call | Device | Read back (driver) | Notes |
|---|---|---|---|
| `setAnimations(enabled)` | the three `Settings.Global` scales, all `0` or all `1` | any scale ≠ 0 (unset = 1) | Restore writes the exact strings captured (`1.0` on the Samsung; an unset scale is deleted again) |
| `setDarkMode(enabled)` | `cmd uimode night yes/no` | night bit of `Configuration.uiMode` | API 29+: `UNSUPPORTED_API` below, before anything is captured. The Samsung (One UI, API 29) locks the day/night mode (`dumpsys uimode`: `mNightModeLocked=true`): the shell command is accepted and ignored, so the read-back fails with `DEVICE_SETTING` and the message names the lock (found 2026-10-01) |
| `setFontScale(scale)` | `settings put system font_scale` | `Configuration.fontScale` | 0.5..2.0 (the Settings app offers about 0.85..1.3; 2.0 is Android's own accessibility maximum) |
| `setDensity(dpi?)` | `wm density N` / `wm density reset` | `Configuration.densityDpi` | 100..1000; `null` = the physical density |
| `App.setLocales(tags)` | `cmd locale set-app-locales <pkg> --user current [--locales a,b]` | `get-app-locales` (host) | API 33+. Android accepts ill-formed tags, so the server validates and canonicalizes (`fr-fr` → `fr-FR`), max 16, no repeats |

- The host reads every change back over ADB (the same parse the capture uses); a value the device
  did not take is `FAILED_PRECONDITION` / `DEVICE_SETTING`. The driver's `DeviceInfo` fields are
  what an app sees (its own resources' `Configuration`), which is the read-back a test asserts.
- Configuration changes recreate a running app's activities (unless it handles them); the call
  does not wait for the app — "report, don't judge".
- Upstream (pinned commits): Appium's `disableWindowAnimation` starts its instrumentation with
  `am instrument --no-window-animation` on API 26+ (Android puts the scales back when the
  instrumentation ends); below 26 its settings app writes the scales, which "could remain if
  the session ends unexpectedly". Tap restores the scales itself (on every API) and journals
  them, so a dead server is covered too; animations stay switchable during the session. Maestro's `AndroidDriver` has no
  animation control; it changes the device-wide locale (`setDeviceLocale`: a broadcast to its
  on-device app, retried until `persist.sys.locale` reads back). Tap does only the API 33
  per-app language; the device-wide locale stays in `framework-gaps.md`.
- Not done: a suite-wide "animations off" attach option in the clients (each test calls
  `setAnimations(false)` for now).

## Later (backlog, rough priority)

**Device conditions** (each with its read-back, per the rule above; restore through the saved
device state of group 2)
- Network: airplane mode, Wi-Fi and mobile data on/off (`cmd connectivity airplane-mode`, `svc`).
- Location: mock GPS (Maestro `setLocation`); the driver would register as the mock-location app.
- Device-wide locale (`framework-gaps.md`).

**Element extras**
- Slider value (`ACTION_SET_PROGRESS`).
- Named accessibility actions (expand, collapse, dismiss, custom) on a node.

**Evidence and media**
- Screen recording for failure videos (`screen-streaming.md`: later).
- Push/pull files; add a photo or video to the gallery (Maestro `addMedia`).

**Out, or separate projects**
- Taps at coordinates: element-only by design.
- WebView content (`framework-gaps.md`, plan §17).
- Crash/ANR as error codes (`framework-gaps.md`).
- Fingerprint (emulator only), time/timezone.
