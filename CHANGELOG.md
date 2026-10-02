# Changelog

Each artifact family is versioned on its own (`docs/reference/releases.md`); entries name the
families they cover. Tap is in alpha: any 0.x release may change the API, and parts marked
experimental may change in any release.

## Unreleased

Packaging:

- **One-download bundles.** Each release set is also published as `bundle/vX.Y.Z` with one zip
  per platform (linux-x86_64, macos-aarch64, jvm): server, Kotlin artifacts as a local Maven
  repository (no GitHub token), Python wheels, docs, `install.sh` and `INSTALL.md`. The site has
  a Download page for them.
- **The Markdown docs moved into the bundles.** The per-family releases no longer attach
  `tap-docs-<version>.zip` / `.tar.gz`.

Breaking, in every family (protocol 5.0; `.docs/app-and-screen.md`):

- **An attached device names no app.** Kotlin `connection.attachDevice(serial)` /
  `attach(serial) { }`, Python `connection.attach_device(serial)`; `tap.autPackage` /
  `tap_aut` / `TAP_AUT` and `DeviceOptions.syncAuthority` are gone. `device.app(pkg)` takes a
  package.
- **App or screen.** `app.element(…)` / `app.await(…)` (Python `app.wait(…)`) match only that
  app's nodes; `device.screen.element(…)` / `.await(…)` match anywhere. Both search every window.
  `device.element` / `device.await`, `inPackage`, `inAnyWindow`, `rawRes` and `SCOPE_DENIED` are
  gone. App waits moved to `App`: `awaitVisible`, `awaitSettled`, `awaitScreenStable`,
  `awaitAnimationEnd` (snake_case in Python).
- **Resource ids.** `res(name)` matches the id in any package (or a Compose testTag),
  `resId(pkg, name)` exactly that package's; a name containing `:id/` fails with
  `QUALIFIED_RESOURCE_NAME`.
- **Covered elements.** A tap, long tap, swipe or scroll on an element partly under another
  window (keyboard, dialog, shade, overlay), with its touch point underneath, fails with
  `NOT_INTERACTABLE` / `OBSCURED` before any input, instead of touching the covering window.
  An element covered completely is, as before, not found.
- `tap-agent`: `attach <serial>` (no package, `--launch`, `--cold`); `app <action> <package>
  [argument]`; `pkg=` restricts a target to one app, otherwise it matches the whole screen.
  The event log and `tap-recording/1` lose `aut_package`. Tap Studio attaches a device
  alone (`--serial S`, no `--package`; `AttachRequest` / `AttachedDevice` lose `app_package`):
  the package the App menu acts on is entered in that menu, and its code shows `app("pkg").…` or
  `screen.…` per step.

Added:

- **Recordings** (daemon, Kotlin and Python clients): `device.startRecording()` /
  `stopRecording()` (`start_recording()` / `stop_recording()`) record the device with scrcpy on
  the daemon host: video (MP4), audio (Opus) or both (Matroska), bounded to 30 s with video and
  60 s audio only, checksummed, and discarded on detach. Audio needs Android 11+. scrcpy is an
  external dependency: `tap start --scrcpy PATH` (or `TAP_SCRCPY`), default `scrcpy` on `PATH`;
  it runs with Tap's own ADB. Guide: `docs/guide/actions-and-waits.md`.

Device actions (engine, Kotlin and Python clients, `tap-agent`), new in protocol 5.0; the engine
and the clients must be updated together to use them:


- **Rotation**: `setOrientation(PORTRAIT|LANDSCAPE)` (the right geometry on phones and
  tablets), `setDisplayRotation(NATURAL|LEFT|UPSIDE_DOWN|RIGHT)` and `unfreezeRotation()`.
  They fail only when Android refuses the rotation; an app that locks its orientation is for
  the test to observe. Detach restores the device's own auto-rotate settings; a failed restore
  quarantines the device.
- **Screen and lock screen**: `wake()`, `sleep()`, `dismissKeyguard()` (never unlocks a PIN,
  pattern or password: `ACTION_REJECTED` / `KEYGUARD_SECURE`); `DeviceInfo` reports
  `screenOn`, `keyguardLocked`, `keyguardSecure`.
- **App**: `foreground()` returns to the app as the home screen does (its task as it was
  left), `background()` presses Home, `openLink(uri, anyApp = false)` opens a deep link or app
  link in the app and returns the activity Android started (`AppService.Foreground` /
  `OpenLink`).
- **Permission dialogs**: `awaitPermissionPrompt()` returns the choices the runtime-permission
  dialog offers, `choosePermission(choice)` presses one; found by resource id, not by label.
- `device.app(pkg)` refuses Tap's driver packages (`INVALID_ARGUMENT`): force-stopping or
  clearing the driver would end the session.
- **Gestures**: `doubleTap()`, `dragTo(destination)`, `pinchOpen()` / `pinchClose()`,
  `fling(direction)`; like a tap, each fails with `OBSCURED` when another window covers its
  touch point (the centre; a fling's start).
- `tap-agent`: `tap --double`, `fling`, `drag`, `pinch`, `rotate`, `screen`, `permission`, and
  `app foreground|background|open-link` (CLI and MCP).
- **Keyboard**: `keyboardShown()` (`DeviceInfo.keyboardShown`, any IME), `hideKeyboard()`
  (Back only when a keyboard is up), `Element.imeAction()` runs a focused field's action key
  (Search, Go, Send, Done; API 30+, `UNSUPPORTED` below).
- **Clipboard**: `setClipboard(text)`, `clipboard()`; reading does not move focus (on API 31+
  Android shows its "pasted from your clipboard" notice).
- **Toasts**: `device.awaitToast(text?, mode, packageName?)` returns `Toast{text,
  packageName}` seen in the last 3.5 s or arriving before the timeout (`WAIT_TIMEOUT` /
  `NO_TOAST`), from any package unless one is named; `app.awaitToast(text?, mode)` names the
  app's.
- **App**: `launch` / `coldLaunch` take typed intent extras (string, boolean, int, long,
  float; Python `tap_e2e.Long` for 64-bit), `revokePermission(name)` (`AppService.RevokePermission`), and
  `isPermissionGranted(name)` to read it back (`AppService.IsPermissionGranted`).
- `tap-agent`: `submit`, `keyboard`, `clipboard`, `toast [--package]`, `app revoke` (CLI and MCP).
- `DeviceInfo.autoRotate` / `auto_rotate`: whether the sensor turns the display (false while a
  rotation is frozen), so a test can tell what detach restored.
- **Device conditions** for the session: `setAnimations(enabled)` (the three animation
  scales), `setDarkMode(enabled)` (API 29+), `setFontScale(scale)` (0.5..2.0), `setDensity(dpi)`
  (100..1000, `null` = physical); Python `set_animations`, `set_dark_mode`, `set_font_scale`,
  `set_density` (`DeviceService.SetAnimations/SetDarkMode/SetFontScale/SetDensity`). Each change
  is read back, and `DeviceInfo` reports `animationsEnabled`, `darkMode`, `fontScale`,
  `densityDpi`.
- **App languages** (API 33+): `App.setLocales(tags)` / `locales()` (Python `set_locales` /
  `locales`; `AppService.SetLocales/GetLocales`), BCP-47 tags checked and canonicalized by the
  server.
- Rotation, the device conditions and app languages are captured before the session's first
  change, journaled, and restored on detach; when the server died first, the next attach
  restores them (a restore that does not read back quarantines the device; a setting
  Android writes back at its default once deleted, `font_scale` 1.0, counts as restored). New failure
  reasons `UNSUPPORTED_API` (detail `REQUIRES_API_<n>`) and `DEVICE_SETTING`.
- `tap-agent`: `condition [animations|dark-mode|font-scale|density] [value]` and
  `app locale <package> [tags|system]` (CLI and MCP).
- **Network switches** (API 29+): `setNetwork(airplaneMode?, wifi?, mobileData?)` (Python
  `set_network`; `DeviceService.SetNetwork`) turns the real switches on or off, nothing mocked;
  read back, `DeviceInfo.airplaneMode` / `wifiEnabled` / `mobileDataEnabled`. A device reached
  over ADB on the network refuses Wi-Fi off or airplane mode on (`DEVICE_SETTING`).
- **Device languages**: `setSystemLocales(tags)` (Python `set_system_locales`;
  `DeviceService.SetSystemLocales`) sets the device-wide locale list through the Tap driver app,
  read back in `DeviceInfo.systemLocales`.
- **Mock location**: `setLocation(latitude, longitude, accuracyM?, altitudeM?)` (Python
  `set_location`; `DeviceService.SetLocation`, driver command `set_location`): the gps and
  network providers (and fused, API 31+) report the fix, re-sent every second; the driver
  becomes the mock-location app and location is turned on if it was off. Detach removes the
  test providers again (they outlive the driver and its app-op).
- Network, languages and location are restored on detach like the other conditions.
- **Accessibility actions**: `Element.performAction(StandardAction)` and
  `performCustomAction(label)` (Python `perform_action` / `perform_custom_action`) run a node's
  action as a screen reader does; one the node does not offer fails before input
  (`ACTION_REJECTED` / `ACTION_NOT_OFFERED`). `Element.setProgress(value)` (`set_progress`)
  sets a slider in its own units (`OUT_OF_RANGE` outside them). `ElementSnapshot` gains
  `actions`, `customActions` and `range`.
- **Location accuracy**: `choosePermission(choice, accuracy)` picks Precise or Approximate on
  the Android 12+ location dialog; `PermissionPrompt.accuracies` lists what it offers.
- **Files and gallery**: `pushFile(devicePath, bytes | path)`, `pullFile(devicePath[, path])`,
  `addMedia(fileName, bytes)` / `addMedia(path)` (Python `push_file`, `pull_file`, `add_media`;
  `DeviceService.PushFile/PullFile/AddMedia`, streamed, at most 512 MiB). Tap never overwrites a
  device file it did not create, reads every write back, and removes what it created on detach;
  media goes to `Pictures/Tap` or `Movies/Tap` and is indexed by the media scanner. New failure
  reason `DEVICE_FILE`.
- `tap-agent`: `condition airplane-mode|wifi|mobile-data|locale`, `location`, `action`,
  `progress`, `permission --accuracy`, `push`, `pull`, `media` (CLI and MCP: `set_location`,
  `accessibility_action`, `set_progress`, `push_file`, `pull_file`, `add_media`).
- **Notifications as data**: `awaitNotification`, `notifications()`, `openNotification(…,
  action?)` and `dismissNotification` (Python `await_notification`, `notifications`,
  `open_notification`, `dismiss_notification`; `App.awaitNotification` for one app's; driver
  commands `await_notification`, `list_notifications`, `open_notification`,
  `dismiss_notification`) read the notifications through a notification listener in the Tap
  driver app, which gets notification access for the session and loses it on detach. Open and
  dismiss act on exactly one match; an ongoing notification is not dismissed (`NOT_CLEARABLE`).
  New `Notification` model and wait reason `NO_NOTIFICATION`.
- **Stay awake and accessibility display**: `setStayAwake(enabled)` and
  `setAccessibilityDisplay(highContrastText?, colorInversion?, boldText?)` (bold text API 31+;
  Python `set_stay_awake`, `set_accessibility_display`; `DeviceService.SetStayAwake`,
  `SetAccessibilityDisplay`), restored on detach; `DeviceInfo` reports `stayAwake`,
  `highContrastText`, `colorInversion`, `boldText`.
- `foregroundActivity()` (Python `foreground_activity()`; `DeviceService.GetForegroundActivity`):
  the resumed activity on top, or null.
- Fixed: a setting the device reported absent after a change or a restore was taken as read
  back; it is now a `DEVICE_SETTING` failure.
- `tap-agent`: `notification [list|await|open|dismiss]`, `activity`, `condition
  stay-awake|high-contrast-text|color-inversion|bold-text` (CLI and MCP: `notification`,
  `foreground_activity`).
- **Breaking** (Kotlin, Python positional): `DeviceInfo` gains `keyboardShown`, `autoRotate`,
  `animationsEnabled`, `darkMode`, `fontScale`, `densityDpi`, `airplaneMode`, `wifiEnabled` and
  `mobileDataEnabled` constructor parameters (and `systemLocales`, defaulted).
- **Breaking** (Kotlin, Python): `DeviceInfo.displayRotation` / `display_rotation` is now a
  `DisplayRotation` instead of an int (the wire field is unchanged); `DeviceInfo.orientation`
  derives portrait or landscape from the size.
- Tap Studio records every device action: on an element, double tap, drag to, pinch, fling,
  submit (IME action), its accessibility and custom actions and a range's value; in the App tab,
  foreground, background, open link, revoke, the app's languages and launch extras; and a new
  **Device** tab (key 5) that reads the device back and records rotation, wake/sleep/unlock, the
  keyboard and clipboard, the permission dialog, notifications and toasts, the conditions and a
  mock location, and device assertions (foreground activity, keyboard, clipboard).
  `tap-recording/1` gains the `device_wait`, `device` and `device_assertion` steps; the studio
  API gains `DescribeElement`, `GetDeviceStatus` and `ListNotifications`.
- The event log's `set_location` call records the `accuracy_m` and `altitude_m` it gave.
- `tap-agent`: `app launch|cold-launch --extra KEY[:TYPE]=VALUE` (MCP `extras`) and
  `location --altitude` (MCP `altitude_m`).

Fixed:

- Server: `forceStop` and `clearData` return only once the app's task is gone too, not just its
  process and activities. Android removes the emptied task about a second later and kills the
  app's running process with it: a launch right after `clearData` lost its new process and
  waited ~40 s for Android to start the app again (the launch timed out).

## 0.0.2 — 2026-09-30 (alpha)

`daemon/v0.0.2`, `client-kotlin/v0.0.2`, `client-python/v0.0.2`, `client-agent/v0.0.2`, and the
first `client-studio/v0.0.1`. `tap-agent` and `tap-studio` need `tap-e2e` 0.0.2.

- Licensed under Apache-2.0 (`LICENSE`; the wheels and POMs carry it).
- Every GitHub Release carries the complete documentation as Markdown
  (`tap-docs-<version>.zip` / `.tar.gz`).
- **Tap Studio** (experimental, new family `client-studio`: `tap-studio`): a browser inspector
  and action recorder on a running server. It shows the live screen with its elements. Act,
  Assert and Inspect modes record element steps (taps, text and secrets, scrolls, swipes, keys,
  app calls, checks), never coordinates. Steps can be edited (another selector candidate, or
  one typed in the Kotlin DSL with a live match count), reordered and replayed. Recordings are
  exported and reopened as `tap-recording/1` JSON. Guide: `docs/guide/studio.md`.
- **Notifications and quick settings** (engine, Kotlin and Python clients, Tap Studio): the
  `open_system_panel` command (`tap.v1.OpenSystemPanel`) opens the notification shade or quick
  settings with the system's accessibility action. The clients expose it as
  `device.openNotifications()` / `openQuickSettings()` (`open_notifications()` /
  `open_quick_settings()`). Tap Studio records it from the device rail beside the screen, which
  also has Back, Home and Recent apps. `tap-agent` has `panel notifications|quick-settings`
  (MCP `open_panel`) and the `recents` key.
- **Selector candidates** (engine, Python client): `ScreenSnapshot(selector_candidates=true)`
  gives each node every selector that matched only it, best first
  (`device.screen_snapshot(selector_candidates=True)`), for inspectors such as Tap Studio.
- **Fix** (engine): `forceStop` and `clearData` (so also `coldLaunch`) now return only once
  Android has destroyed the app's activities, not just its process. On API 34 a launch right
  after `clearData` could otherwise be killed with the old task and hang on its splash screen
  until `am start -W` timed out.

## 0.0.1 — 2026-09-29 (alpha)

First release of the engine (`daemon/v0.0.1`: the `tap` server with the bundled driver, plus
`tap-schema` / `tap-api`), the Kotlin client (`client-kotlin/v0.0.1`: `tap-client`,
`tap-junit5`), the Python client (`client-python/v0.0.1`: `tap-e2e`) and the agent tools
(`client-agent/v0.0.1`: `tap-agent`). `tap-sync-sdk` is not released yet.

- **Server** (`tap start` / `stop` / `status`, JVM or native binary for Linux x86-64 and macOS
  arm64): one per machine, loopback gRPC with a bearer token, per-device locks shared across
  processes, driver install and session lifecycle, app lifecycle (install, launch, cold launch,
  force-stop, clear data, grant permission, process identity), failure capture.
- **Driver**: a separate package from the app under test; selectors re-resolved on every
  command with exactly one match required before any input; explicit waits (visible, gone,
  exactly one, app visible, screen stable, animation end); taps, text input, keys, swipes,
  scrolls, screenshots, hierarchy dumps; transport loss after acceptance reported as
  `INDETERMINATE`, never replayed.
- **Kotlin client**: coroutine SDK (`Device`, `App`, `Element`, selector DSL, typed errors
  and models) and the JUnit 5 extension (`@TapTest`, `tapTest { }`, named roles for several
  devices, `DeviceBarrier`, failure artifacts, opt-in per-class device reuse).
- **Python client**: the same API, synchronous, with a pytest plugin (`tap_device`,
  `tap_devices`, failure artifacts, device reuse by scope).
- **Agent tools** (experimental): `tap-agent`, a CLI and MCP server for coding agents — held
  sessions, screen snapshots with refs, `--settle` diffs, and `export` of the session's device
  calls as JSON (`tap-events/1`).
- **Experimental**: app synchronization (`awaitIdle` / `await_idle`, `syncAuthority`; Kotlin
  `@ExperimentalTapApi`) and the agent surface (held connections, `resume`, screen snapshots,
  `resolve_ref`, `event_log`, the `Events` RPC, `tap-events/1`).

Known limits: see `.docs/framework-gaps.md` — no crash/ANR error codes yet, no helper for
runtime permission dialogs, no WebView support, app synchronization only works with apps the
driver can see (the fixture app on Android 11+), and typed text (passwords included) is
recorded verbatim in the event log.
