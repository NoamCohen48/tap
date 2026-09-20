# Getting started

You need three things: an ADB-visible Android device (API 26+, emulator or physical), the
`tap` service on the machine, and one of the clients in your test project.

## 1. The service

The service is a single executable that owns ADB and the devices for the whole machine. Pick
one:

=== "Native binary"

    Download `tap-<version>-linux-x86_64` or `tap-<version>-macos-aarch64` from the latest
    `service/v*` [release](https://github.com/NoamCohen48/tap/releases), make it executable and
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
    ./gradlew :host:service:installDist
    export PATH="$PWD/host/service/build/install/tap/bin:$PATH"
    ```

Start it once; like the ADB server it stays up until `tap stop`, shared by every test process on
the machine. It binds loopback only. The on-device driver is bundled inside the executable and
installed on each device the first time a session opens.

```bash
tap start          # prints: started 127.0.0.1:PORT pid=PID (or "running …" if it already is)
tap status
```

Clients never start the service themselves; if none is running they fail with *run `tap start`*.
Test runners can do the starting and stopping for you — `tap.manageService=true` (JUnit) or
`tap_manage_service = true` (pytest) — see [Configuration](configuration.md#how-clients-find-the-service).

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
    testImplementation("com.company.tap:tap-junit5:0.1.0")   // brings tap-client and tap-api
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
import com.company.tap.junit5.TapTest
import com.company.tap.sdk.*
import org.junit.jupiter.api.Test

@TapTest
class SmokeTest {
    @Test
    fun opensTheHomeScreen(device: Device) {
        val app = device.app()                       // the configured AUT package
        app.install(Path.of("build/outputs/apk/debug/shop-debug.apk"))
        app.coldLaunch()                             // resolves the launcher activity, waits for its window
        device.await(text("Welcome")).visible()
        device.element(res("search")).setText("socks")
    }
}
```

```bash
./gradlew test -Ptap.serials=emulator-5554
```

`@TapTest` opens a session on a device before each test, injects it as the `Device`
parameter, closes the session afterwards, and on failure writes a screenshot, hierarchy dump,
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
from tap import text, res

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

`tap_device` is a per-test session; `tap_devices` with `@pytest.mark.tap_devices("a", "b")`
gives several. Failure artifacts land in `tap-artifacts/<nodeid>/`.

## 4. Without a test framework

Both clients can be used from a script. The shape is the same: a *run* attaches to the
service, opens a session per device.

=== "Kotlin"

    ```kotlin
    TapClient().use { client ->
        val connection = client.connect("smoke")
        connection.openDevice("emulator-5554", "com.shop").use { device ->
            device.app().coldLaunch()
            println(device.element(text("Welcome")).exists())
        }
        connection.close()
    }
    ```

=== "Python"

    ```python
    from tap import Service, text

    with Service().connect("smoke") as connection:
        with connection.open_device("emulator-5554", "com.shop") as device:
            device.app().cold_launch()
            print(device.element(text("Welcome")).exists())
    ```

If the process dies, the service notices the connection's stream closing and frees its devices.

## Next

- [How it works](how-it-works.md) explains what a session and a connection are.
- [Selectors](selectors.md) and [Actions and waits](actions-and-waits.md) cover the API you
  will use in every test.
