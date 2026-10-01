# App lifecycle and sync

`device.app(packageName)` returns an `App` for that package; a test can hold several, one per
app it drives. Besides [finding its elements](selectors.md#app-or-screen) (`app.element`,
`app.await`) and [waiting for its window](actions-and-waits.md#waiting-for-the-app-or-the-screen),
an `App` owns the package's lifecycle. Every lifecycle operation is executed by the server over ADB and **verified
against a postcondition** — the method returns when the device is provably in the requested
state, or fails with `AppLifecycleException` / `AppLifecycleError`.

| Method | Does | Verified by |
|---|---|---|
| `isInstalled()` | | `pm` query |
| `install(apk)` | uploads the local APK file (streamed in 1 MiB chunks, so the server may run on another machine), then `adb install -r -t` | package visible afterwards |
| `uninstall()` | `pm uninstall` | package gone |
| `launch(activity = null)` | `am start -W` the given or the launcher activity | Android reports the launch complete (nothing about the UI: wait for it yourself) |
| `coldLaunch(activity = null)` | force-stop, launch | **a new process identity** (PID + start token) is in the foreground; returned as an `AppProcess` |
| `forceStop()` | `am force-stop` | no process of the package remains, and Android has destroyed its activities |
| `clearData()` | `pm clear` | data, cache and runtime permissions gone; app left stopped, as after `forceStop()` |
| `grantPermission(name)` | `pm grant` | `dumpsys package` lists it as granted |
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
2. Tap the system dialog through the permission controller's `App` (or `device.screen`), as in
   [Selectors → App or screen](selectors.md#app-or-screen). Use this only when the dialog
   itself is what you are testing.

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
