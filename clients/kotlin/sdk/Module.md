# Module tap-client

The Kotlin client for Tap: connect to the `tap` server, attach devices and drive them.

Start with [TapClient][io.github.noamcohen48.tap.sdk.TapClient] to connect, then work with a
[Device][io.github.noamcohen48.tap.sdk.Device]. `device.app(packageName)` gives an
[App][io.github.noamcohen48.tap.sdk.App]: control its lifecycle and find its elements with
selectors such as `res(...)` and `text(...)` (`app.element(...)`, `app.await(...)`);
`device.screen` ([Screen][io.github.noamcohen48.tap.sdk.Screen]) finds elements of any app or
the system UI. Act on them through [Element][io.github.noamcohen48.tap.sdk.Element].
All device calls are `suspend`. In JUnit 5 tests, use `tap-junit5` instead of connecting by hand.

Guide: <https://noamcohen48.github.io/tap/sdk/kotlin/>

# Package io.github.noamcohen48.tap.sdk

Client, connections, devices, elements, waits, the selector DSL, errors and the value types
the server returns.
