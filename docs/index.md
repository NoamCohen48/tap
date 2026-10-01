# Tap

<img src="assets/logo-lockup.svg" alt="Tap" width="200" height="64" style="display:block;margin:0 0 1rem">

**Reliable end-to-end tests for Android apps, written in Kotlin or Python.**

Tap lets you write UI tests for your Android app with the tools you already use, JUnit 5 or
pytest, and run them on real phones and emulators. You don't need to change your app.

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
            val shop = device.app("com.example.shop")
            shop.coldLaunch()
            shop.element(res("buy_button")).tap()
            shop.await(text("Order placed")).visible()
        }
    }
    ```

=== "Python"

    ```python
    from tap_e2e import res, text

    def test_buys_an_item(tap_device):
        shop = tap_device.app("com.example.shop")
        shop.cold_launch()
        shop.element(res("buy_button")).tap()
        shop.wait(text("Order placed")).visible()
    ```

[Get started](guide/getting-started.md){ .md-button .md-button--primary }
[Coding agents](guide/agents.md){ .md-button }

## Why Tap

<div class="grid cards" markdown>

-   **Stable tests**

    ---

    Tap finds the element fresh for every action and refuses to guess when more than one matches.

-   **No hidden waiting**

    ---

    Tests wait only where you say, so they stay fast and predictable.

-   **Clear failures**

    ---

    Every failure has a specific reason, plus a screenshot and screen layout of each device.

-   **Several devices in one test**

    ---

    Test chat, calls or sharing between two phones.

-   **Kotlin or Python**

    ---

    Both work the same way; pick the one your team knows.

-   **Works with coding agents**

    ---

    Claude Code and other agents can drive a device, check a change, and hand you the steps as a test.

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

-   **[Tap Studio](guide/studio.md)**

    A browser inspector and recorder: see the device's screen with its elements, act on them,
    and get the steps as a file you replay or turn into a test.

-   **[API reference](reference/index.md)**

    Kotlin, Python and gRPC references, generated from the source.

-   **[Development](development/index.md)**

    How Tap is built inside, and how to build, test and change it.

</div>

Coming from another tool? [Coming from Maestro or Appium](guide/coming-from.md) maps the
concepts you already know.

## Open source

Tap is open source under the Apache License 2.0. Issues and pull requests are welcome on
[GitHub](https://github.com/NoamCohen48/tap); see the
[contributing guide](https://github.com/NoamCohen48/tap/blob/main/CONTRIBUTING.md).
