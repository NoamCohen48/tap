# Kotlin + JUnit 5

The Kotlin client (`tap-client`) and its JUnit 5 extension (`tap-junit5`). Every call that
talks to a device is `suspend`; building selectors is not. The [Guide](../guide/selectors.md)
shows each feature in Python; [The guide in Kotlin](#the-guide-in-kotlin) below has the same
examples in Kotlin, and the [Kotlin API reference](../reference/kotlin.md) has every signature.

## Install

The Maven artifacts live in this repository's GitHub Packages registry; reads need a token with
`read:packages`. With the [all-in-one download](../download.md), Gradle reads them from a local
Maven repository instead and needs no token.

```kotlin
repositories {
    maven("https://maven.pkg.github.com/NoamCohen48/tap") {
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    testImplementation("io.github.noamcohen48.tap:tap-junit5:0.0.2")   // brings tap-client
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test {
    useJUnitPlatform()
    providers.gradleProperty("tap.serials").orNull?.let { systemProperty("tap.serials", it) }
}
```

The [server](../guide/server.md) must be running (`tap start`), or let the test run start it
with `tap.manageDaemon=true`.

## A first test

```kotlin
import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TapTest
class SmokeTest {
    @Test
    fun opensTheHomeScreen(device: Device) {
        tapTest {
            val app = device.app("com.shop")
            app.install(Path.of("build/outputs/apk/debug/shop-debug.apk"))
            app.coldLaunch()                             // resolves the launcher activity, waits for its window
            app.await(text("Welcome")).visible()         // only com.shop's nodes count
            app.element(res("search")).setText("socks")
        }
    }
}
```

```bash
./gradlew test -Ptap.serials=emulator-5554
```

`@TapTest` attaches a device before each test, injects it as the `Device` parameter, detaches
it afterwards, and on failure writes a screenshot, hierarchy dump, device info and driver log
under `build/tap-artifacts/<class>/<method>/`.

!!! note "Why `tapTest { ... }`"

    Every test body runs inside `tapTest { ... }`, the bridge onto the per-test coroutine
    scope the extension owns. `Device`, `App` and `Element` calls are `suspend`; building
    selectors (`text(...)`, `res(...)`) is not. Calls outside `tapTest`, or from `GlobalScope`,
    fail with `TapUsageException`, so a JUnit timeout or a failing sibling cancels in-flight
    calls. `fun x(device: Device) = tapTest { ... }` works too, since `tapTest` returns `Unit`.

## Configuration

Read once per JVM from system properties, falling back to environment variables of the same
name with camelCase and dots turned into underscores, upper-cased (`tap.artifactsDir` →
`TAP_ARTIFACTS_DIR`, `tap.device.sender` → `TAP_DEVICE_SENDER`):

| Property | Meaning | Default |
|---|---|---|
| `tap.serials` | comma-separated serials to use; roles map to them in order, starting one device further on per test ([details](../guide/multi-device.md#which-serial-plays-which-role)) | any device the server lists |
| `tap.device.<role>` | pin one role to a serial (must be in `tap.serials` when that is set) | — |
| `tap.artifactsDir` | failure artifacts root | `build/tap-artifacts` |
| `tap.capture` | `onFailure` = `device.capture()` every device of a failed test into the artifacts root; `off` = capture nothing | `onFailure` |
| `tap.acquireTimeoutSeconds` | how long to wait for a device another session holds | `300` |
| `tap.server` | `host:port` of a running server | the one in `daemon.json` |
| `tap.token` | bearer token for an explicit `tap.server` | the one in `daemon.json` |
| `tap.manageDaemon` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the server | `false` |
| `tap.bin` | the `tap` executable `tap.manageDaemon` runs | `TAP_BIN`, then `PATH` |

Gradle passes them with `systemProperty(...)` on the test task; a common pattern forwards `-P`
properties:

```kotlin
tasks.test {
    useJUnitPlatform()
    listOf("tap.serials", "tap.server", "tap.manageDaemon", "tap.bin").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
```

## Reusing devices

By default every test attaches its devices and detaches them afterwards, so each test starts
with a fresh driver session. Starting the driver is most of an attach's cost: about 1.2 s on an
emulator and 3.8 s on a mid-range phone (measured on an API 34 emulator and an API 29 Samsung),
per test. A class can keep its devices instead:

```kotlin
@TapTest(deviceLifetime = DeviceLifetime.PER_CLASS)
class CheckoutTest { ... }
```

Consecutive tests of the class that declare the same roles then share the attached devices,
which are detached after the last of them. Nothing is reset between tests: the app keeps
whatever state the previous test left, so each test brings it where it needs it
(`coldLaunch()`, `clearData()`). Before each test a reused device is probed (`info()`, a few
milliseconds); one that stopped working (quarantined, driver lost, detached by the test) is
detached and a fresh one attached. Failure artifacts are still captured per test.

## Without JUnit

The client works from any coroutine. A client connects to the server, then attaches each device
it needs:

```kotlin
runBlocking {
    TapClient.create().use { client ->           // resolves tap.server / daemon.json
        client.connect("smoke").use { connection ->
            connection.attach("emulator-5554") { device ->
                val app = device.app("com.shop")
                app.coldLaunch()
                println(app.element(text("Welcome")).exists())
            }
        }
    }
}
```

`use { }` closes the client and the connection after the block; `attach(serial) { }` attaches,
runs the block and always detaches. A failure in the block wins, and a close or detach failure
after it is added as suppressed. `attach` provides the `tapScope` device calls need. For
handles that outlive one block, `connection.attachDevice(...)` inside `tapScope { ... }` with
`device.detach()` in a `finally` does the same by hand.

`connect` opens the connection's liveness stream before it returns, so if the process dies the
server notices the stream closing and frees its devices. If the stream ends while the process
lives (the server restarted), the connection becomes unusable: further attaches and device calls
fail at once, and a new `connect` is needed. The JUnit extension does that for you on the next
test.

`TapDaemonProcess.start()` / `stop()` start and stop the server from code, and report whether
the call started it ([Who starts it](../guide/server.md#who-starts-it)).

## Timeouts and session options

Timeouts are per device, not global: `Timeouts(action = 10.seconds, wait = 10.seconds,
lifecycle = 30.seconds, pollInterval = 100.milliseconds)` is the default, and every method also
takes an explicit `timeout`. With the client directly, pass `Timeouts` and `DeviceOptions` to
`connection.attach(serial, timeouts, options) { device -> ... }` or
`connection.attachDevice(...)`:

| `DeviceOptions` | Meaning |
|---|---|
| `skipDriverInstall` | assume the server's driver is already installed (CI images with a pre-provisioned driver) |
| `waitForDevice` | how long to wait for a device another session holds; zero (the default) fails at once with `DeviceBusyException` |

The JUnit extension uses the defaults. The driver itself is the server's: clients cannot pick
one per attach.

## Multi-device tests

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@TapTest
class ChatTest {
    @Test
    @TapDevices("sender", "receiver")
    fun deliversAMessage(devices: Devices) {
        tapTest {
            val sender = devices["sender"].app("com.example.chat")
            val receiver = devices["receiver"].app("com.example.chat")
            coroutineScope {
                awaitAll(
                    async { sender.coldLaunch() },
                    async { receiver.coldLaunch() },
                )
            }
            sender.element(res("compose")).setText("hi")
            sender.element(text("Send")).tap()
            receiver.await(text("hi"), timeout = 20.seconds).visible()
        }
    }
}
```

`@TapDevices` goes on the method or the class. With a single default role the parameter is just
`device: Device`; `@TapDevice("receiver") device: Device` injects one named role. Use
`coroutineScope` + `async`/`awaitAll` for concurrent phases; a failing sibling cancels the
other's in-flight call. `DeviceBarrier(2)` is only for genuinely simultaneous phases (neither
side proceeds until both arrive). `awaitAll` takes the deferreds:
`coroutineScope { async { } async { } }.awaitAll()` does not compile, because the block's value
is only the last `Deferred`.

From the client by hand: `connection.availableSerials()` to look, then
`connection.attachDevice(serial, options = DeviceOptions(waitForDevice = 60.seconds))` to open.
How roles map to serials and why sessions cannot deadlock is on
[Multi-device tests](../guide/multi-device.md).

## The guide in Kotlin

Each section below is one [Guide](../guide/selectors.md) page's examples, in Kotlin. Names are
the Python ones in camelCase, `app.wait(...)` is `app.await(...)`, timeouts are `Duration`s
(`20.seconds`), paths are `java.nio.file.Path`, and each Python `…Error` is a Kotlin
`…Exception`.

### Selectors

From [Selectors](../guide/selectors.md). Kotlin combines selectors with the infix `or` and `and`
where Python uses `|` and `&`.

```kotlin
import io.github.noamcohen48.tap.sdk.*

val app = device.app("com.shop")
app.element(res("buy_button")).tap()             // com.shop:id/buy_button
app.element(text("Add to cart").clickable()).tap()
app.element(className("android.widget.EditText").andHint("Search"))
    .setText("socks")

// an unchecked checkbox labelled "Remember me"
text("Remember me").checkable().checked(false)

// the permission button, whichever wording this Android version uses
val allow = text("Allow") or
    text("Allow only while using the app") or
    text("While using the app")
device.screen.element(allow).tap()
// both on the same node: the same as text("Add").clickable()
app.element(text("Add") and clickable()).tap()

// the "Delete" button inside the row whose title is "Socks"
val row = className("android.view.ViewGroup").hasDescendant(text("Socks"))
app.element(row.descendant(text("Delete"))).tap()

app.element(text("Add").first()).tap()           // first in accessibility order
app.element(className("Button").at(2)).tap()     // third in accessibility order
```

### App or screen

From [App or screen](../guide/app-or-screen.md).

```kotlin
val shop = device.app("com.shop")
shop.element(res("search")).setText("socks")      // com.shop's search field only
device.screen.await(text("Allow")).visible()      // whoever owns it

device.app("com.google.android.permissioncontroller")
    .element(resId("com.android.permissioncontroller", "permission_allow_button"))
    .tap()
```

### Elements and text

From [Elements and text](../guide/elements.md).

```kotlin
val email = app.element(res("email"))
email.setText("user@example.com")
email.await().textEquals("user@example.com")

app.element(text("Shipping")).performAction(StandardAction.EXPAND)
app.element(res("message_row").first()).performCustomAction("Archive")
app.element(res("volume")).setProgress(7f)
```

### Gestures

From [Gestures](../guide/gestures.md).

```kotlin
val results = app.element(res("results"))
results.scrollUntil(text("Wool socks"), timeout = 30.seconds).tap()
app.element(res("carousel")).swipe(Direction.UP)

app.element(res("card")).dragTo(res("done_column"))
app.await(text("Moved to Done")).visible()
```

### Waits

From [Waits](../guide/waits.md). `awaitUntil` takes the condition as a trailing lambda; both lambdas
are `suspend`.

```kotlin
app.await(text("Order placed")).visible()
app.await(res("pay")).enabled().tap()
app.await(res("spinner"), timeout = 30.seconds).gone()

device.awaitUntil(
    "order visible in the admin API",
    timeout = 20.seconds,
    observe = { admin.lastOrder()?.status },   // shown in the timeout message
) {
    admin.lastOrder()?.status == "PLACED"
}
```

### Keys, screen and rotation

From [Keys, screen and rotation](../guide/device-control.md).

```kotlin
device.openQuickSettings()
device.screen.await(desc("Wi-Fi")).visible()
device.pressBack()

device.setOrientation(Orientation.LANDSCAPE)
app.await(res("two_pane")).visible()
device.setDisplayRotation(DisplayRotation.RIGHT)
device.unfreezeRotation()   // optional: detach restores the device's own settings anyway
```

### App lifecycle

From [App lifecycle](../guide/app-lifecycle.md). Intent extras are a `Map<String, Any>`; a Kotlin
`Long` is sent as a long, so no wrapper is needed.

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

app.coldLaunch(".DetailActivity", extras = mapOf("item_id" to 42L, "preview" to true))
```

### Permissions

From [Permissions](../guide/permissions.md).

```kotlin
app.grantPermission("android.permission.CAMERA")

app.element(text("Take photo")).tap()
val prompt = device.awaitPermissionPrompt()
val allow = if (PermissionChoice.ALLOW_FOREGROUND_ONLY in prompt.choices) {
    PermissionChoice.ALLOW_FOREGROUND_ONLY // Android 11+: "While using the app"
} else {
    PermissionChoice.ALLOW
}
device.choosePermission(allow)
app.await(text("Camera ready")).visible()

device.choosePermission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)
```

### Device conditions

From [Device conditions](../guide/device-conditions.md). `setSystemLocales` takes a `List` or the
locales as arguments.

```kotlin
device.setAnimations(false)
device.setDarkMode(true)
device.setFontScale(1.3f)
app.launch()
app.await(text("Large text")).visible()
assertFalse(device.info().animationsEnabled)

device.setSystemLocales("de-DE", "en-US")
device.setNetwork(wifi = false, mobileData = false) // offline, without airplane mode
device.setLocation(48.8584, 2.2945, accuracyM = 5f)
```

### Notifications and toasts

From [Notifications and toasts](../guide/notifications.md). The match arguments are named:
`title = …`, `text = …`.

```kotlin
app.element(res("save")).tap()
assertEquals("Saved", app.awaitToast().text)

app.element(res("send")).tap()
val message = app.awaitNotification(title = "New message")
assertEquals(listOf("Mark as read"), message.actions)
device.openNotification(title = "New message", packageName = app.packageName)
assertEquals("com.example.chat.ConversationActivity", device.foregroundActivity()?.className)
device.dismissNotification(title = "Syncing", packageName = app.packageName)
```

### Files and the gallery

From [Files and the gallery](../guide/files.md). Sources are a `Path` or a `ByteArray`; `pullFile`
returns a `ByteArray`, or writes a `Path` you pass.

```kotlin
device.pushFile("/sdcard/Download/invoice.pdf", Path.of("fixtures/invoice.pdf"))
val photo = device.addMedia(Path.of("fixtures/cat.jpg"))  // "/sdcard/Pictures/Tap/cat.jpg"
device.addMedia("chart.png", pngBytes)                     // bytes need a file name
app.grantPermission("android.permission.READ_MEDIA_IMAGES")

val log = device.pullFile("/sdcard/Android/data/com.example.shop/files/log.txt").decodeToString()
device.pullFile("/data/local/tmp/trace.txt", Path.of("out/trace.txt"))
```

### Screenshots and artifacts

From [Screenshots and artifacts](../guide/artifacts.md).

```kotlin
val shot = device.screenshot()
println("${shot.width}x${shot.height}")
shot.save(Path.of("build/shots/login.png"))

val capture = device.capture()
capture.saveTo(Path.of("build/evidence/after-login"))
capture.failures.forEach { (part, why) -> println("no $part: ${why.message}") }

device.startRecording(audioSource = "output")
app.element(res("play")).tap()
device.stopRecording().save(Path.of("build/recordings/playback.mkv"))
```
