<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo-lockup-dark.svg">
    <img src="docs/assets/logo-lockup-light.svg" alt="Tap" width="220" height="70">
  </picture>
</p>

<p align="center">
  <strong>Host-driven end-to-end testing for Android, in Kotlin or Python.</strong>
</p>

<p align="center">
  <a href="https://github.com/NoamCohen48/tap/actions/workflows/ci.yml"><img src="https://github.com/NoamCohen48/tap/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"></a>
  <a href="https://github.com/NoamCohen48/tap/releases"><img src="https://img.shields.io/github/v/release/NoamCohen48/tap?include_prereleases&filter=daemon/*&label=release" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
  <img src="https://img.shields.io/badge/status-alpha-orange" alt="Status: alpha">
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android API 26+">
</p>

<p align="center">
  <a href="docs/guide/getting-started.md">Getting started</a> ·
  <a href="docs/index.md">Documentation</a> ·
  <a href="docs/reference/index.md">API reference</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

---

Tap runs your UI tests on the host and drives real Android devices and emulators through a
small on-device driver. One `tap` server per machine owns ADB and the devices; tests written
with JUnit 5 or pytest talk to it over gRPC. Your app is never modified: the driver is a
separate package, so tests can force-stop, clear or reinstall the app mid-test and carry on.

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

```python
def test_buys_an_item(tap_device):
    tap_device.app().cold_launch()
    tap_device.element(res("buy_button")).tap()
    tap_device.wait(text("Order placed")).visible()
```

## Why Tap

- **No stale elements.** Every action re-resolves its selector on the device and needs exactly
  one match. `AMBIGUOUS` or `NOT_FOUND` comes back before any input is injected.
- **Waits you can see.** Nothing sleeps or settles implicitly. You wait for what you mean:
  an element visible or gone, the app in front, the screen or animations settled.
- **Honest failures.** A closed set of error codes with stable sub-reasons. If the connection
  drops after a tap was accepted, the result is `INDETERMINATE`; Tap never replays it.
- **Failure artifacts built in.** Screenshot, accessibility hierarchy, device info and driver
  log for every device in a failed test.
- **Multi-device tests.** Declare roles such as `@TapDevices("sender", "receiver")` and get
  every device or none. Device locks are shared by all test processes on the machine.
- **Same behaviour in both languages.** All device logic lives in the server. The Kotlin and
  Python clients are thin gRPC clients of one API.
- **Built for coding agents too.** `tap-agent` (experimental) gives Claude Code and other
  agents a CLI and an MCP server: screen snapshots with refs, actions, and an export of the
  session to turn into a test.

## Quick start

**Requirements:** an Android device or emulator with API 26 or newer visible to `adb`, and
Linux x86-64 or macOS on Apple silicon (or any OS with JDK 17 for the JVM build).

**1. Install and start the server.** Download `tap` from the latest
[`daemon/v*` release](https://github.com/NoamCohen48/tap/releases), then:

```bash
chmod +x tap-0.0.1-linux-x86_64 && sudo mv tap-0.0.1-linux-x86_64 /usr/local/bin/tap
tap start       # one server per machine, loopback only; stays up until `tap stop`
```

**2. Add a client to your test project.**

<table>
<tr><th>Kotlin + JUnit 5</th><th>Python + pytest</th></tr>
<tr><td>

```kotlin
// build.gradle.kts (GitHub Packages:
// a token with read:packages)
repositories {
    maven("https://maven.pkg.github.com/NoamCohen48/tap") {
        credentials { /* gpr.user / gpr.token */ }
    }
}
dependencies {
    testImplementation(
        "io.github.noamcohen48.tap:tap-junit5:0.0.1")
}
tasks.test {
    useJUnitPlatform()
    systemProperty("tap.autPackage", "com.shop")
}
```

</td><td>

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.1/tap_e2e-0.0.1-py3-none-any.whl
```

```ini
# pytest.ini
[pytest]
tap_aut = com.shop
```

</td></tr>
</table>

**3. Run the tests** with `./gradlew test -Ptap.serials=emulator-5554` or
`TAP_SERIALS=emulator-5554 pytest`.

The [getting started guide](docs/guide/getting-started.md) walks through each step, including
the JVM build, repository credentials and using the clients without a test framework.

## How it works

```
 test processes             one per machine               each device
┌──────────────────┐ gRPC  ┌─────────────────────┐  adb   ┌─────────────────────────┐
│ Kotlin · JUnit 5 │◄─────►│ tap server          │◄──────►│ Tap driver (UiAutomator)│
│ Python · pytest  │       │ devices, sessions,  │        │   ┌─────────────────┐   │
│ tap-agent (MCP)  │       │ driver lifecycle    │        │   │ app under test  │   │
└──────────────────┘       └─────────────────────┘        └───┴─────────────────┴───┘
```

The server installs the bundled driver, forwards a port per device, checks the driver's
identity on every connection and journals its work, so a crashed host can recover a device
instead of leaving it half-used. Read more in [How it works](docs/guide/how-it-works.md).

## Documentation

| | |
|---|---|
| [Getting started](docs/guide/getting-started.md) | install the server and run a first test |
| [Selectors](docs/guide/selectors.md) · [Actions and waits](docs/guide/actions-and-waits.md) | the API used in every test |
| [App lifecycle](docs/guide/app-lifecycle.md) · [Multi-device tests](docs/guide/multi-device.md) | launching, clearing, permissions; roles and serials |
| [Configuration](docs/guide/configuration.md) · [Errors and artifacts](docs/guide/errors.md) | properties, environment, the `tap` CLI; error codes |
| [Coding agents](docs/guide/agents.md) | `tap-agent`, the CLI and MCP server |
| [Coming from Maestro or Appium](docs/guide/coming-from.md) | concept mapping |
| [API reference](docs/reference/index.md) | Kotlin, Python and gRPC references, generated from the source |

Every [release](https://github.com/NoamCohen48/tap/releases) also carries the complete
documentation as Markdown (`tap-docs-<version>.zip`).

## Project status

Tap is in **alpha** (0.0.x). The server, driver and both clients are tested on a physical
API 29 device, an API 34 emulator, and an emulator lane in CI. Any 0.x release may change the
API. App synchronization (`sync-sdk`, `awaitIdle`) and the agent surface are experimental even
by that standard; see [Releases and versions](docs/reference/releases.md).

## Contributing

Bug reports, ideas and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers
the repository layout, building, the test suites and the release process. Please follow the
[Code of Conduct](CODE_OF_CONDUCT.md), and report security issues privately as described in
[SECURITY.md](SECURITY.md).

## License

Tap is licensed under the [Apache License 2.0](LICENSE).
