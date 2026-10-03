# SDKs

Tap has two test clients, and they are the same API in two languages. Both are thin gRPC
clients of the [tap server](../guide/server.md): the server owns ADB, the devices and the
driver, so a test in either language sees the same behaviour, the same errors and the same
device locks.

<div class="grid cards" markdown>

-   **[Python + pytest](python.md)**

    `tap-e2e`: a blocking client and a pytest plugin with `tap_device` / `tap_devices`
    fixtures. The [Guide](../guide/selectors.md)'s examples are in Python.

-   **[Kotlin + JUnit 5](kotlin.md)**

    `tap-client` + `tap-junit5`: a coroutine client (every device call is `suspend`) and a
    JUnit 5 extension that injects `Device` parameters. Has the Guide's examples in Kotlin.

</div>

## One API, two spellings

| | Python | Kotlin |
|---|---|---|
| Method names | `cold_launch()`, `set_text(v)` | `coldLaunch()`, `setText(v)` |
| Wait for an element | `app.wait(sel).visible()` | `app.await(sel).visible()` |
| Combine selectors | `text("A") \| text("B")`, `a & b` | `text("A") or text("B")`, `a and b` |
| Timeouts | seconds: `timeout=20` | `Duration`: `timeout = 20.seconds` |
| Files | a path (`str` / `Path`) or `bytes` | a `java.nio.file.Path` or a `ByteArray` |
| Errors | `CommandError`, `WaitTimeoutError`, … | `CommandException`, `WaitTimeoutException`, … |
| Where calls run | anywhere | inside `tapTest { }` (JUnit) or `tapScope { }` / `attach { }` |
| A test's device | the `tap_device` fixture | a `device: Device` parameter of a `@TapTest` class |
| Several devices | `@pytest.mark.tap_devices("a", "b")` + `tap_devices` | `@TapDevices("a", "b")` + `devices: Devices` |

Only the Python client has [held connections and screen snapshots](python.md#held-connections-and-screen-snapshots),
the multi-process surface [tap-agent](../agent/index.md) and [Tap Studio](../studio/index.md)
are built on; tests do not need them.

## API reference

Generated from the clients' docstrings and KDoc:
[Python](../reference/python/index.md) · [Kotlin](../reference/kotlin.md).
