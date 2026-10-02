# App lifecycle and sync

`device.app(packageName)` returns an `App` for that package; a test can hold several, one per
app it drives. Besides [finding its elements](selectors.md#app-or-screen) (`app.element`,
`app.await`) and [waiting for its window](actions-and-waits.md#waiting-for-the-app-or-the-screen),
an `App` owns the package's lifecycle. The one package it refuses is Tap's own driver
(`io.github.noamcohen48.tap.driver`): stopping it would end the session. Every lifecycle operation is executed by the server over ADB and **verified
against a postcondition** — the method returns when the device is provably in the requested
state, or fails with `AppLifecycleException` / `AppLifecycleError`.

| Method | Does | Verified by |
|---|---|---|
| `isInstalled()` | | `pm` query |
| `install(apk)` | uploads the local APK file (streamed in 1 MiB chunks, so the server may run on another machine), then `adb install -r -t` | package visible afterwards |
| `uninstall()` | `pm uninstall` | package gone |
| `launch(activity = null, extras = {})` | `am start -W` the given or the launcher activity, with typed intent extras | Android reports the launch complete (nothing about the UI: wait for it yourself) |
| `coldLaunch(activity = null, extras = {})` | force-stop, launch | **a new process identity** (PID + start token) is in the foreground; returned as an `AppProcess` |
| `background()` | the Home key, as a user would; the process keeps running | nothing: it is a key press |
| `foreground()` | `am start -W` of the launcher intent, as the home screen sends it | Android reports the start complete. An existing task comes back as it was left, on whatever screen the user was; with no task, the launcher activity starts |
| `openLink(uri, anyApp = false)` | `am start -W` of a VIEW intent for a deep link or app link (`myapp://orders/42`, `https://…`), limited to this package so no browser or chooser can take it; `anyApp = true` lets Android resolve it as a tapped link would | Android reports the start; returns the activity it named (`package/.Activity`) or `null`. No handler in the package is an `AppLifecycleException` |
| `forceStop()` | `am force-stop` | no process of the package remains, and Android has destroyed its activities |
| `clearData()` | `pm clear` | data, cache and runtime permissions gone; app left stopped, as after `forceStop()` |
| `grantPermission(name)` | `pm grant` | `dumpsys package` lists it as granted |
| `isPermissionGranted(name)` | | whether `dumpsys package` lists it as granted now (after a grant, a revoke, `clearData()` or a choice in the permission dialog) |
| `setLocales(tags)` | the app's own languages (Android's per-app language, API 33+), BCP-47 tags in preference order; an empty list follows the system again. Restored on detach | read back; `locales()` returns them (canonical tags, empty = the system's) |
| `revokePermission(name)` | `pm revoke` (Android kills the app's process when a runtime permission is revoked) | `dumpsys package` no longer lists it as granted |
| `process()` | | the single current `AppProcess` |
| `isRunning()` | | any process of the package |
| `awaitIdle(stableFor = 200 ms)` | | the app's own busy counter — below |

```kotlin
@Test
fun survivesAKill(device: Device) {
    tapTest {
        val app = device.app("com.shop")
        val first = app.coldLaunch()
        app.element(text("Add to cart")).tap()
        app.forceStop()
        val second = app.coldLaunch()
        assertNotEquals(first.pid, second.pid)
        app.await(text("1 item")).visible()
    }
}
```

Intent extras are typed as the app reads them: `String`, `Boolean`, `Int`, `Long`, `Float` in
Kotlin (anything else is an `IllegalArgumentException` before the call); `str`, `bool`, `int`
(32-bit), `float` and `tap_e2e.Long(n)` (64-bit) in Python. An app reading `getLongExtra` on an
int extra gets its default value, so the type matters. At most 64 extras; keys without
whitespace.

```kotlin
app.coldLaunch(".DetailActivity", extras = mapOf("item_id" to 42L, "preview" to true))
```

```python
app.cold_launch(".DetailActivity", extras={"item_id": Long(42), "preview": True})
```

All `App` calls are `suspend` inside `tapTest` (or `tapScope` in scripts).

Because the driver is its own package, none of this disturbs the attached device: you can
force-stop, clear and reinstall the app in the middle of a test and keep issuing
commands. The server also owns the driver's lifecycle — if the driver dies, the next command
fails with `DRIVER_UNHEALTHY`; the client must detach and attach the device again to create a
fresh device session under a new generation. It is never rebuilt silently.

## Permissions

Two options, in order of preference:

1. `app.grantPermission("android.permission.CAMERA")` before the flow reaches the prompt. No
   dialog, no timing.
2. When the dialog itself is under test, `device.awaitPermissionPrompt()` waits for it and
   returns a `PermissionPrompt` with the choices it offers (`ALLOW`, `ALLOW_FOREGROUND_ONLY`,
   `ALLOW_ONE_TIME`, `DENY`, …: which ones depends on the permission and the Android version),
   and `device.choosePermission(choice)` taps one. Buttons are found by the permission
   controller's resource ids, never by label or position, so this works in any language. A
   choice the dialog does not offer is `NOT_FOUND` before any input; no dialog within the
   timeout is a `WaitTimeoutException` / `WaitTimeoutError` with reason
   `NO_PERMISSION_PROMPT`.

   ```kotlin
   app.element(text("Take photo")).tap()
   val prompt = device.awaitPermissionPrompt()
   val allow = if (PermissionChoice.ALLOW_FOREGROUND_ONLY in prompt.choices) {
       PermissionChoice.ALLOW_FOREGROUND_ONLY // Android 11+: "While using the app"
   } else {
       PermissionChoice.ALLOW
   }
   device.choosePermission(allow)
   app.await(text("Camera ready")).visible()
   ```

   ```python
   prompt = device.await_permission_prompt()
   device.choose_permission(PermissionChoice.DENY)
   ```

   The location dialog of Android 12+ also asks how precise the location may be:
   `prompt.accuracies` lists the `LocationAccuracy` radios it shows (`PRECISE`,
   `APPROXIMATE`; empty on other dialogs), and `device.choosePermission(choice, accuracy)`
   selects one before pressing the button. An accuracy the dialog does not offer is
   `NOT_FOUND` before any input.

   ```kotlin
   device.choosePermission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)
   app.await(text("Approximate location")).visible()
   ```

   ```python
   device.choose_permission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)
   ```

   For anything else in the dialog, its elements belong to `prompt.packageName` (the window's
   package; Google builds name it `com.google.android.permissioncontroller`): reach them with
   `device.app(prompt.packageName).element(...)` as described in
   [Selectors → App or screen](selectors.md#app-or-screen).

`clearData()` revokes runtime permissions, so grant again after it.

## App-owned idle: `sync-sdk`

!!! warning "Experimental, not released yet"
    App synchronization is still being designed. `awaitIdle` / `await_idle` is marked
    experimental (Kotlin: opt in with
    `@OptIn(ExperimentalTapApi::class)`) and may change in any release, and `tap-sync-sdk` has
    no published release yet. On Android 11+ the driver can currently see only the sync
    provider of Tap's own fixture app, so `awaitIdle` against your app fails with a `SYNC_*`
    detail until that is solved.

Waiting on the UI covers most cases, but some work is invisible to the accessibility tree: a
request in flight whose response will *replace* the screen, a database write the next screen
reads. For that the app can tell Tap when it is busy.

Add the library to the **E2E build flavour only** (it ships a `ContentProvider` and a
signature-level permission; never release it):

```kotlin
dependencies {
    e2eImplementation("io.github.noamcohen48.tap:tap-sync-sdk:<version>")
}
```

Wrap the work the tests must wait for:

```kotlin
val busy = TapSynchronization.busy()
scope.launch {
    try { repository.placeOrder(cart) } finally { busy.close() }
}
```

Then in the test:

```kotlin
app.element(text("Place order")).tap()
app.awaitIdle()                               // busy count 0 and stable for 200 ms
app.await(text("Order placed")).visible()
```

(inside `tapTest`; the app-side `busy()`/`close()` is ordinary app code, not suspend).

The provider (`<applicationId>.tap-sync`) is protected by a signature-level permission, so the
driver must be signed with the same certificate as the E2E build of the app; a mismatch is
`SYNC_PROVIDER_UNAVAILABLE`/`CERTIFICATE_MISMATCH`. The host also checks the process identity
on each read: if the app restarted between two observations you get `AUT_MISMATCH` /
`PROCESS_RESTARTED` instead of a stale "idle".

`awaitIdle` is the equivalent of Espresso idling resources for a host-driven test — precise
when the app cooperates, and completely optional.
