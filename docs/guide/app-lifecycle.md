# App lifecycle and sync

`device.app()` returns an `App` bound to the app under test (`device.app("other.pkg")` for
another package). Every lifecycle operation is executed by the server over ADB and **verified
against a postcondition** — the method returns when the device is provably in the requested
state, or fails with `AppLifecycleException` / `AppLifecycleError`.

| Method | Does | Verified by |
|---|---|---|
| `isInstalled()` | | `pm` query |
| `install(apk)` | uploads the local APK file (streamed in 1 MiB chunks, so the server may run on another machine), then `adb install -r -t` | package visible afterwards |
| `uninstall()` | `pm uninstall` | package gone |
| `launch(activity = null)` | `am start` the given or the launcher activity | the package owns the focused window |
| `coldLaunch(activity = null)` | force-stop, launch | **a new process identity** (PID + start token) is in the foreground; returned as `ProcessIdentity` |
| `forceStop()` | `am force-stop` | no process of the package remains |
| `clearData()` | `pm clear` | data, cache and runtime permissions gone; app left stopped |
| `grantPermission(name)` | `pm grant` | |
| `process()` | | the single current `ProcessIdentity` |
| `isRunning()` | | any process of the package |
| `awaitIdle(stableFor = 200 ms)` | | the app's own busy counter — below |

```kotlin
@Test
fun survivesAKill(device: Device) {
    tapTest {
        val app = device.app()
        val first = app.coldLaunch()
        device.element(text("Add to cart")).tap()
        app.forceStop()
        val second = app.coldLaunch()
        assertNotEquals(first.pid, second.pid)
        device.await(text("1 item")).visible()
    }
}
```

All `App` calls are `suspend` inside `tapTest` (or `tapScope` in scripts).

Because the driver is its own package, none of this disturbs the attached device: you can
force-stop, clear and reinstall the app under test in the middle of a test and keep issuing
commands. The server also owns the driver's lifecycle — if the driver dies, the next command
fails with `DRIVER_UNHEALTHY`; the client must detach and attach the device again to create a
fresh device session under a new generation. It is never rebuilt silently.

## Permissions

Two options, in order of preference:

1. `app.grantPermission("android.permission.CAMERA")` before the flow reaches the prompt. No
   dialog, no timing.
2. Tap the system dialog through the scope opt-in described in
   [Selectors → Scope](selectors.md#scope). Use this only when the dialog itself is what you
   are testing.

`clearData()` revokes runtime permissions, so grant again after it.

## App-owned idle: `sync-sdk`

Waiting on the UI covers most cases, but some work is invisible to the accessibility tree: a
request in flight whose response will *replace* the screen, a database write the next screen
reads. For that the app can tell Tap when it is busy.

Add the library to the **E2E build flavour only** (it ships a `ContentProvider` and a
signature-level permission; never release it):

```kotlin
dependencies {
    e2eImplementation("io.github.noamcohen48.tap:tap-sync-sdk:0.1.0")
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
device.element(text("Place order")).tap()
device.app().awaitIdle()                      // busy count 0 and stable for 200 ms
device.await(text("Order placed")).visible()
```

(inside `tapTest`; the AUT-side `busy()`/`close()` is ordinary app code, not suspend).

The provider (`<applicationId>.tap-sync`) is protected by a signature-level permission, so the
driver must be signed with the same certificate as the E2E build of the app; a mismatch is
`SYNC_PROVIDER_UNAVAILABLE`/`CERTIFICATE_MISMATCH`. The host also checks the process identity
on each read: if the app restarted between two observations you get `AUT_MISMATCH` /
`PROCESS_RESTARTED` instead of a stale "idle".

`awaitIdle` is the equivalent of Espresso idling resources for a host-driven test — precise
when the app cooperates, and completely optional.
