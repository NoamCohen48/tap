# App lifecycle

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`cold_launch` → `coldLaunch`), with `extras` as a
    `Map`. [Kotlin + JUnit 5](../sdk/kotlin.md#app-lifecycle) shows this page's examples in
    Kotlin.

`device.app(package_name)` returns an `App` for that package; a test can hold several, one per
app it drives. Besides [finding its elements](app-or-screen.md) and
[waiting for its window](waits.md#the-app-or-the-screen), an `App` owns the package's
lifecycle. The one package it refuses is Tap's own driver (`io.github.noamcohen48.tap.driver`):
stopping it would end the session.

Every lifecycle call is run by the server over ADB and **verified against a postcondition**: it
returns when the device is provably in the requested state, or fails with
`AppLifecycleError`.

| Method | Does | Verified by |
|---|---|---|
| `is_installed()` | | `pm` query |
| `install(apk)` | uploads the local APK file (streamed in 1 MiB chunks, so the server may run on another machine), then `adb install -r -t` | package visible afterwards |
| `uninstall()` | `pm uninstall` | package gone |
| `launch(activity=None, *, extras=None)` | `am start -W` the given or the launcher activity, with typed intent extras | Android reports the launch complete (nothing about the UI: wait for it yourself) |
| `cold_launch(activity=None, *, extras=None)` | force-stop, launch | **a new process identity** (PID + start token) is in the foreground; returned as an `AppProcess` |
| `background()` | the Home key, as a user would; the process keeps running | nothing: it is a key press |
| `foreground()` | `am start -W` of the launcher intent, as the home screen sends it | Android reports the start complete. An existing task comes back as it was left, on whatever screen the user was; with no task, the launcher activity starts |
| `open_link(uri, any_app=False)` | `am start -W` of a VIEW intent for a deep link or app link (`myapp://orders/42`, `https://…`), limited to this package so no browser or chooser can take it; `any_app=True` lets Android resolve it as a tapped link would | Android reports the start; returns the activity it named (`package/.Activity`) or `None`. No handler in the package is an `AppLifecycleError` |
| `force_stop()` | `am force-stop` | no process of the package remains, and Android has destroyed its activities |
| `clear_data()` | `pm clear` | data, cache and runtime permissions gone; the app left stopped, as after `force_stop()` |
| `set_locales(tags)` | the app's own languages (Android's per-app language, API 33+), BCP-47 tags in preference order; an empty list follows the system again. Restored on detach | read back; `locales()` returns them (canonical tags, empty = the system's) |
| `process()` | | the single current `AppProcess` |
| `is_running()` | | any process of the package |

Granting, revoking and the permission dialog are on [Permissions](permissions.md).

```python
def test_survives_a_kill(tap_device):
    app = tap_device.app("com.shop")
    first = app.cold_launch()
    app.element(text("Add to cart")).tap()
    app.force_stop()
    second = app.cold_launch()
    assert first.pid != second.pid
    app.wait(text("1 item")).visible()
```

## Intent extras

Extras are typed as the app reads them: `str`, `bool`, `int` (32-bit), `float` and
`tap_e2e.Long(n)` (64-bit). An app reading `getLongExtra` on an int extra gets its default
value, so the type matters. At most 64 extras; keys without whitespace.

```python
from tap_e2e import Long

app.cold_launch(".DetailActivity", extras={"item_id": Long(42), "preview": True})
```

## The driver survives all of it

Because the driver is its own package, none of this disturbs the attached device: you can
force-stop, clear and reinstall the app in the middle of a test and keep issuing commands. The
server also owns the driver's lifecycle: if the driver dies, the next command fails with
`DRIVER_UNHEALTHY`, and the client must detach and attach the device again to get a fresh device
session. It is never rebuilt silently.

## App-owned idle: `sync-sdk`

!!! warning "Experimental, not released yet"
    App synchronization is still being designed. `await_idle` is experimental (Kotlin: opt in
    with `@OptIn(ExperimentalTapApi::class)`) and may change in any release, and `tap-sync-sdk`
    has no published release yet. On Android 11+ the driver can currently see only the sync
    provider of Tap's own fixture app, so `await_idle` against your app fails with a `SYNC_*`
    detail until that is solved.

Waiting on the UI covers most cases, but some work is invisible to the accessibility tree: a
request in flight whose response will *replace* the screen, a database write the next screen
reads. For that the app can tell Tap when it is busy.

Add the library to the **E2E build flavour only** (it ships a `ContentProvider` and a
signature-level permission; never release it), and wrap the work the tests must wait for. This
part is app code, so it is Kotlin:

```kotlin
dependencies {
    e2eImplementation("io.github.noamcohen48.tap:tap-sync-sdk:<version>")
}
```

```kotlin
val busy = TapSynchronization.busy()
scope.launch {
    try { repository.placeOrder(cart) } finally { busy.close() }
}
```

Then in the test:

```python
app.element(text("Place order")).tap()
app.await_idle()                          # busy count 0 and stable for 0.2 s
app.wait(text("Order placed")).visible()
```

The provider (`<applicationId>.tap-sync`) is protected by a signature-level permission, so the
driver must be signed with the same certificate as the E2E build of the app; a mismatch is
`SYNC_PROVIDER_UNAVAILABLE` / `CERTIFICATE_MISMATCH`. The server also checks the process
identity on each read: if the app restarted between two observations you get `AUT_MISMATCH` /
`PROCESS_RESTARTED` instead of a stale "idle".

`await_idle` is the equivalent of Espresso idling resources for a host-driven test: precise
when the app cooperates, and completely optional.
