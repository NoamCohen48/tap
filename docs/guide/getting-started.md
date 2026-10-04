# Getting started

This page takes you from nothing to a passing test. The guide's examples are in Python; for
Kotlin, follow [Kotlin + JUnit 5](../sdk/kotlin.md) after step 1.

!!! info "Before you start"

    - An Android device or emulator with **API 26 or newer**, visible to `adb devices`.
    - **Linux x86-64 or macOS on Apple silicon** for the native `tap` binary, or any OS with
      **JDK 17+** for the JVM build.
    - **Python 3.10+** and pytest, or a Gradle project with JUnit 5 for Kotlin.

!!! tip "Everything in one download"

    The [Download](../download.md) page has one zip per platform with the server, both clients,
    `tap-agent`, Tap Studio and these docs, plus an `install.sh` that sets it all up. With it,
    Gradle reads Tap from a local Maven repository and needs no GitHub token. The steps below
    install each part on its own instead.

## 1. The server

The server is a single executable that owns ADB and the devices for the whole machine. Pick
one:

=== "Native binary"

    1. Download `tap-0.0.2-linux-x86_64` (Linux) or `tap-0.0.2-macos-aarch64` (macOS on Apple
       silicon) from the [latest release](https://github.com/NoamCohen48/tap/releases/tag/daemon/v0.0.2).
    2. Rename it to `tap` and make it executable:

        ```bash
        mv tap-0.0.2-linux-x86_64 tap && chmod +x tap
        ./tap version
        ```

    The examples below write `tap`; use `./tap`, or put the file in a directory on your `PATH`.

=== "JVM distribution"

    `tap-<version>-jvm.zip` from the same release; needs a JDK 17+.

    ```bash
    unzip tap-0.0.2-jvm.zip && export PATH="$PWD/tap-0.0.2/bin:$PATH"
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
Test runners can start and stop it for you: see [The tap server](server.md#who-starts-it).

## 2. Install the Python client

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
```

The package `tap-e2e` (imported as `tap_e2e`) registers a pytest plugin that provides attached
devices as fixtures.

## 3. Write a test

```python
from tap_e2e import res, text

def test_opens_the_home_screen(tap_device):
    app = tap_device.app("com.shop")
    app.install("build/outputs/apk/debug/shop-debug.apk")
    app.cold_launch()                         # resolves the launcher activity, waits for its window
    app.wait(text("Welcome")).visible()       # only com.shop's nodes count
    app.element(res("search")).set_text("socks")
```

```bash
TAP_SERIALS=emulator-5554 pytest
```

`tap_device` is a device attached for this test and detached after it. When the test fails, the
plugin saves a screenshot, the hierarchy dump, the device info and the driver log under
`tap-artifacts/<nodeid>/`.

## Next

- [Selectors](selectors.md), [Elements and text](elements.md) and [Waits](waits.md) cover what
  you will use in every test.
- [Python + pytest](../sdk/python.md) has the plugin's options and using the client from a
  script; [Kotlin + JUnit 5](../sdk/kotlin.md) is the same for Kotlin.
- [tap-agent](../agent/index.md) lets Claude Code or another coding agent drive a device through
  the same server, and [Tap Studio](../studio/index.md) records steps from your browser.
