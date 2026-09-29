# Module tap-client

The Kotlin client for Tap: connect to the `tap` server, attach devices and drive them.

Start with [TapClient][io.github.noamcohen48.tap.sdk.TapClient] to connect, then work with a
[Device][io.github.noamcohen48.tap.sdk.Device]: find elements with selectors such as `res(...)` and
`text(...)`, act on them through [Element][io.github.noamcohen48.tap.sdk.Element], wait with
`device.await(...)`, and control the app under test through [App][io.github.noamcohen48.tap.sdk.App].
All device calls are `suspend`. In JUnit 5 tests, use `tap-junit5` instead of connecting by hand.

Guide: <https://noamcohen48.github.io/tap/guide/getting-started/>

# Package io.github.noamcohen48.tap.sdk

Client, connections, devices, elements, waits, the selector DSL, errors and the value types
the server returns.
