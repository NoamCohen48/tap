<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-lockup-dark.svg">
    <img src="docs/assets/logo-lockup-light.svg" alt="Tap" width="220" height="70">
  </picture>
</p>

<p align="center">
  <strong>Reliable end-to-end tests for Android apps, written in Kotlin or Python.</strong>
</p>

<p align="center">
  <a href="https://github.com/NoamCohen48/tap/actions/workflows/ci.yml"><img src="https://github.com/NoamCohen48/tap/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="https://github.com/NoamCohen48/tap/releases"><img src="https://img.shields.io/github/v/release/NoamCohen48/tap?include_prereleases&filter=daemon/*&label=release" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/status-alpha-orange" alt="Status: alpha">
</p>

<p align="center">
  <a href="https://noamcohen48.github.io/tap/guide/getting-started/">Getting started</a> ·
  <a href="https://noamcohen48.github.io/tap/">Documentation</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

---

Tap lets you write UI tests for your Android app with the tools you already use, JUnit 5 or
pytest, and run them on real phones and emulators. You don't need to change your app.

```kotlin
@TapTest
class CheckoutTest {
    @Test
    fun buysAnItem(device: Device) = tapTest {
        device.app().coldLaunch()
        device.element(res("buy_button")).tap()
        device.await(text("Order placed")).visible()
    }
}
```

## Why Tap

- **Stable tests.** Tap finds the element fresh for every action and refuses to guess
  when more than one matches.
- **No hidden waiting.** Tests wait only where you say, so they stay fast and predictable.
- **Clear failures.** Every failure has a specific reason, plus a screenshot and screen
  layout of each device.
- **Several devices in one test.** Test chat, calls or sharing between two phones.
- **Kotlin or Python.** Both work the same way; pick the one your team knows.
- **Works with coding agents.** Claude Code and other agents can drive a device, check a
  change, and hand you the steps as a test.

## Quick start

You need an Android phone or emulator connected through `adb`.

1. Download the Tap server for your system from the
   [latest release](https://github.com/NoamCohen48/tap/releases/tag/daemon/v0.0.1):
   `tap-0.0.1-linux-x86_64` (Linux), `tap-0.0.1-macos-aarch64` (macOS on Apple silicon) or
   `tap-0.0.1-jvm.zip` (any system with Java 17).
2. Start it:

   ```bash
   mv tap-0.0.1-linux-x86_64 tap && chmod +x tap
   ./tap start
   ```

   It keeps running in the background until `./tap stop`.

### Kotlin

Add the JUnit 5 extension to your Gradle build. The package is hosted on GitHub Packages,
which needs a GitHub token with the `read:packages` scope:

```kotlin
repositories {
    maven("https://maven.pkg.github.com/NoamCohen48/tap") {
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    testImplementation("io.github.noamcohen48.tap:tap-junit5:0.0.1")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tap.autPackage", "com.example.shop")   // your app's package
}
```

Write a test like the one above, then run `./gradlew test`.

### Python

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.1/tap_e2e-0.0.1-py3-none-any.whl
```

Tell the pytest plugin which app to test in `pytest.ini`:

```ini
[pytest]
tap_aut = com.example.shop
```

```python
from tap_e2e import res, text

def test_buys_an_item(tap_device):
    tap_device.app().cold_launch()
    tap_device.element(res("buy_button")).tap()
    tap_device.wait(text("Order placed")).visible()
```

Run it with `pytest`.

### Coding agents

`tap-agent` (experimental) gives a coding agent a command line and an MCP server for driving a
device. It needs Python 3.10+ and a running Tap server:

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.1/tap_e2e-0.0.1-py3-none-any.whl \
            https://github.com/NoamCohen48/tap/releases/download/client-agent/v0.0.1/tap_agent-0.0.1-py3-none-any.whl
claude mcp add tap -- tap-agent mcp      # register it with Claude Code
```

Other agents can use the CLI directly: `tap-agent skill` prints the instructions to give them.

### Tap Studio

`tap-studio` (experimental) shows a device's screen in your browser with its elements overlaid.
You act on them to record steps: taps, text, scrolls and checks, each on an element's selector,
never a coordinate. Edit and replay the steps, then export them as a `tap-recording/1` file to
turn into a test. It needs Python 3.10+ and a running Tap server. Until its first release,
install it from a checkout, which needs [Bun](https://bun.sh) to build the page:

```bash
(cd clients/studio/web && bun install && bun run build)
pip install -e clients/python -e clients/studio
tap-studio                                # opens the page; Ctrl-C frees the device
```

## Documentation

| | |
|---|---|
| [Getting started](https://noamcohen48.github.io/tap/guide/getting-started/) | a first test, step by step |
| [Selectors](https://noamcohen48.github.io/tap/guide/selectors/) | finding elements on the screen |
| [Actions and waits](https://noamcohen48.github.io/tap/guide/actions-and-waits/) | tapping, typing, scrolling and waiting |
| [App lifecycle](https://noamcohen48.github.io/tap/guide/app-lifecycle/) | installing, launching and resetting your app |
| [Multi-device tests](https://noamcohen48.github.io/tap/guide/multi-device/) | tests that use several devices |
| [Configuration](https://noamcohen48.github.io/tap/guide/configuration/) | settings and the `tap` command |
| [Errors and artifacts](https://noamcohen48.github.io/tap/guide/errors/) | what failures mean and what to look at |
| [Coding agents](https://noamcohen48.github.io/tap/guide/agents/) | using `tap-agent` |
| [Tap Studio](https://noamcohen48.github.io/tap/guide/studio/) | inspecting a screen and recording steps in the browser |
| [Coming from Maestro or Appium](https://noamcohen48.github.io/tap/guide/coming-from/) | how the concepts map |
| [API reference](https://noamcohen48.github.io/tap/reference/) | every Kotlin, Python and server API |
| [Development](https://noamcohen48.github.io/tap/development/) | how Tap is built, and how to work on it |

## Contributing

Bug reports, ideas and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).
Please follow the [Code of Conduct](CODE_OF_CONDUCT.md) and report security issues privately
as described in [SECURITY.md](SECURITY.md).

## Acknowledgements

Tap stands on the shoulders of projects that solved these problems first, and we are grateful
to their authors:

- [AndroidX UiAutomator](https://developer.android.com/training/testing/other-components/ui-automator),
  which the on-device driver is built on.
- [Appium UiAutomator2](https://github.com/appium/appium-uiautomator2-server), whose years of
  Android edge cases (input, permission dialogs, screenshots) shaped how the driver handles
  them.
- [Maestro](https://github.com/mobile-dev-inc/maestro), for its approach to waiting for an app
  to settle, failure reports and running on many devices.
- [uiautomator2](https://github.com/openatx/uiautomator2), whose Python API guided the feel of
  the Python client.
- [agent-device](https://github.com/callstackincubator/agent-device), which showed what a
  device tool for coding agents should look like and inspired `tap-agent`.

Tap is written independently; no code was copied from these projects.

## License

Tap is licensed under the [Apache License 2.0](LICENSE).
