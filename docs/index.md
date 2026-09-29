# Tap

<img src="assets/logo-lockup.svg" alt="Tap" width="200" height="64" style="display:block;margin:0 0 1rem">

**Host-driven end-to-end testing for Android, in Kotlin or Python.**

Tap runs your UI tests on the host and drives real Android devices and emulators through a
small on-device driver. One `tap` server per machine owns ADB and the devices; tests written
with JUnit 5 or pytest talk to it over gRPC. Your app is never modified: the driver is a
separate package, so tests can force-stop, clear or reinstall the app mid-test and carry on.

=== "Kotlin"

    ```kotlin
    import io.github.noamcohen48.tap.junit5.TapTest
    import io.github.noamcohen48.tap.junit5.tapTest
    import io.github.noamcohen48.tap.sdk.*
    import org.junit.jupiter.api.Test

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

=== "Python"

    ```python
    from tap_e2e import res, text

    def test_buys_an_item(tap_device):
        tap_device.app().cold_launch()
        tap_device.element(res("buy_button")).tap()
        tap_device.wait(text("Order placed")).visible()
    ```

[Get started](guide/getting-started.md){ .md-button .md-button--primary }
[How it works](guide/how-it-works.md){ .md-button }

## Why Tap

<div class="grid cards" markdown>

-   **No stale elements**

    ---

    Every action re-resolves its selector on the device and needs exactly one match.
    `AMBIGUOUS` or `NOT_FOUND` comes back *before* any input is injected.

-   **Waits you can see**

    ---

    Nothing sleeps or settles implicitly. You wait for what you mean: an element visible or
    gone, the app in front, the screen or animations settled.

-   **Honest failures**

    ---

    A closed set of error codes with stable sub-reasons. If the connection drops after a tap was
    accepted, the result is `INDETERMINATE`; Tap never replays it.

-   **Failure artifacts built in**

    ---

    Screenshot, accessibility hierarchy, device info and driver log for every device in a
    failed test, captured while the session is still live.

-   **Multi-device tests**

    ---

    Declare roles such as `@TapDevices("sender", "receiver")` and get every device or none.
    Device locks are shared by every test process on the machine.

-   **One engine, thin clients**

    ---

    ADB, sessions and the driver live in the server. Kotlin and Python are thin gRPC clients
    of the same API, so they behave the same.

</div>

## Explore the docs

<div class="grid cards" markdown>

-   **[Getting started](guide/getting-started.md)**

    Install the server and run your first test in Kotlin or Python.

-   **[Selectors](guide/selectors.md) and [Actions and waits](guide/actions-and-waits.md)**

    The API you use in every test: finding elements, acting on them, waiting for results.

-   **[App lifecycle](guide/app-lifecycle.md) and [Multi-device tests](guide/multi-device.md)**

    Install, launch, clear data, grant permissions; roles, serials and the device list.

-   **[Configuration](guide/configuration.md) and [Errors](guide/errors.md)**

    Properties, environment variables and the `tap` CLI; every error code and what to check.

-   **[Coding agents](guide/agents.md)**

    `tap-agent`, a CLI and MCP server that lets Claude Code or another agent drive a device and
    export what it did as a test.

-   **[API reference](reference/index.md)**

    Kotlin, Python and gRPC references, generated from the source.

</div>

Coming from another tool? [Coming from Maestro or Appium](guide/coming-from.md) maps the
concepts you already know.

## Project status

!!! warning "Alpha"

    Tap is at **0.0.x**. The server, driver and both clients are tested on a physical API 29
    device, an API 34 emulator and an emulator lane in CI, but any 0.x release may change the
    API. App synchronization (`sync-sdk`, `awaitIdle`) and the agent surface (`tap-agent`,
    held connections, snapshots, the event log) are experimental even by that standard. See
    [Releases and versions](reference/releases.md).

Tap is open source under the Apache License 2.0. Issues and pull requests are welcome on
[GitHub](https://github.com/NoamCohen48/tap); see the
[contributing guide](https://github.com/NoamCohen48/tap/blob/main/CONTRIBUTING.md).
