# Getting started

You need three things: an ADB-visible Android device (API 26+, emulator or physical), the
`tap` server on the machine, and one of the clients in your test project.

## 1. The server

The server is a single executable that owns ADB and the devices for the whole machine. Pick
one:

=== "Native binary"

    Download `tap-<version>-linux-x86_64` or `tap-<version>-macos-aarch64` from the latest
    `daemon/v*` [release](https://github.com/NoamCohen48/tap/releases), make it executable and
    put it on your `PATH` as `tap`.

    ```bash
    chmod +x tap-0.1.0-linux-x86_64 && sudo mv tap-0.1.0-linux-x86_64 /usr/local/bin/tap
    tap version
    ```

=== "JVM distribution"

    `tap-<version>-jvm.zip` from the same release; needs a JDK 17+.

    ```bash
    unzip tap-0.1.0-jvm.zip && export PATH="$PWD/tap-0.1.0/bin:$PATH"
    tap version
    ```

=== "From source"

    ```bash
    ./gradlew :host:daemon:installDist
    export PATH="$PWD/host/daemon/build/install/tap/bin:$PATH"
    ```

Start it once; like the ADB server it stays up until `tap stop`, shared by every test process on
the machine. It binds loopback only. The on-device driver is bundled inside the executable and
installed on each device the first time a session opens.

```bash
tap start          # prints: started 127.0.0.1:PORT pid=PID (or "running …" if it already is)
tap status
```

Clients never start the server themselves; if none is running they fail with *run `tap start`*.
Test runners can do the starting and stopping for you — `tap.manageDaemon=true` (JUnit) or
`tap_manage_daemon = true` (pytest) — see [Configuration](configuration.md#how-clients-find-the-server).

## 2. Kotlin + JUnit 5

Add the repository and the JUnit 5 extension (the Maven artifacts live in this repository's
GitHub Packages registry; reads need a token with `read:packages`):

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
    testImplementation("io.github.noamcohen48.tap:tap-junit5:0.2.0")   // brings tap-client and tap-api
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tap.autPackage", "com.shop")                                  // required
    providers.gradleProperty("tap.serials").orNull?.let { systemProperty("tap.serials", it) }
}
```

Write a test:

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
            val app = device.app()                       // the configured AUT package
            app.install(Path.of("build/outputs/apk/debug/shop-debug.apk"))
            app.coldLaunch()                             // resolves the launcher activity, waits for its window
            device.await(text("Welcome")).visible()
            device.element(res("search")).setText("socks")
        }
    }
}
```

Every test body runs inside `tapTest { ... }` (`fun x(device: Device) = tapTest { ... }` works
too, since `tapTest` returns `Unit`), the real-time bridge onto the extension-owned per-test coroutine scope.
All `Device`/`App`/`Element` calls are `suspend`; building selectors
(`text(...)`, `res(...)`) is not. Calls outside `tapTest` — or from `GlobalScope` — fail with
`TapUsageException`, so timeouts and failing siblings cancel in-flight RPCs.

```bash
./gradlew test -Ptap.serials=emulator-5554
```

`@TapTest` attaches a device before each test, injects it as the `Device`
parameter, detaches it afterwards, and on failure writes a screenshot, hierarchy dump,
device info and driver log under `build/tap-artifacts/<class>/<method>/`.

## 3. Python + pytest

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.1.0/tap_e2e-0.1.0-py3-none-any.whl
```

The package registers a pytest plugin. Configure the app under test in `pytest.ini` or the
environment:

```ini
[pytest]
tap_aut = com.shop
```

```python
from tap_e2e import text, res

def test_opens_the_home_screen(tap_device):
    app = tap_device.app()
    app.install("build/outputs/apk/debug/shop-debug.apk")
    app.cold_launch()
    tap_device.wait(text("Welcome")).visible()
    tap_device.element(res("search")).set_text("socks")
```

```bash
TAP_SERIALS=emulator-5554 pytest
```

`tap_device` is a per-test attached device; `tap_devices` with `@pytest.mark.tap_devices("a", "b")`
gives several. Failure artifacts land in `tap-artifacts/<nodeid>/`.

## 4. Without a test framework

Both clients can be used from a script. The shape is the same: a client connects to the
server, then attaches each device it needs.

=== "Kotlin"

    ```kotlin
    runBlocking {
        val client = TapClient.create()              // resolves tap.server / daemon.json
        try {
            val connection = client.connect("smoke")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.shop")
                    try {
                        device.app().coldLaunch()
                        println(device.element(text("Welcome")).exists())
                    } finally {
                        device.detach()
                    }
                }
            } finally {
                connection.close()
            }
        } finally {
            client.close()
        }
    }
    ```

    Device work (attach, use, detach) lives inside `tapScope { ... }`; detaching is `suspend`
    (no `AutoCloseable`), so callers use `try`/`finally` inside the scope.

=== "Python"

    ```python
    from tap_e2e import TapServer, text

    with TapServer().connect("smoke") as connection:
        with connection.attach_device("emulator-5554", "com.shop") as device:
            device.app().cold_launch()
            print(device.element(text("Welcome")).exists())
    ```

`connect` opens the connection's liveness stream before it returns (in both clients), so if the
process dies the server notices the stream closing and frees its devices. If the stream ends
while the process lives (the daemon restarted), the connection becomes unusable: further
attaches and device calls fail at once, and a new `connect` is needed. The JUnit extension does
that for you on the next test.

## Next

- [How it works](how-it-works.md) explains client connections and attached devices.
- [Selectors](selectors.md) and [Actions and waits](actions-and-waits.md) cover the API you
  will use in every test.
