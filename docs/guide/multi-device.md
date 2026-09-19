# Multi-device tests

The service keeps a **machine-wide pool** of every device ADB can see (optionally restricted with
`tap serve --serials a,b`). Test processes do not pick devices; they ask for **roles** and the
pool leases devices to them. Leases live in `~/.tap/sessions`, so two processes — a Gradle test
JVM and a pytest run, say — never share a device, and a crashed process's devices are reclaimed
when its run stream closes.

## Declaring roles

=== "Kotlin"

    ```kotlin
    @TapTest
    class ChatTest {
        @Test
        @TapDevices("sender", "receiver")
        fun deliversAMessage(devices: Devices) {
            val sender = devices["sender"]
            val receiver = devices["receiver"]
            sender.app().coldLaunch()
            receiver.app().coldLaunch()
            sender.element(res("compose")).setText("hi")
            sender.element(text("Send")).tap()
            receiver.await(text("hi"), timeout = 20.seconds).visible()
        }
    }
    ```

    `@TapDevices` goes on the method or the class. With a single default role the parameter is
    just `device: Device`; `@TapDevice("receiver") device: Device` injects one named role.

=== "Python"

    ```python
    @pytest.mark.tap_devices("sender", "receiver")
    def test_delivers_a_message(tap_devices):
        sender, receiver = tap_devices["sender"], tap_devices["receiver"]
        sender.app().cold_launch()
        receiver.app().cold_launch()
        sender.element(res("compose")).set_text("hi")
        sender.element(text("Send")).tap()
        receiver.wait(text("hi"), timeout=20).visible()
    ```

Acquisition is **all-or-none**: the test either gets every role or waits (up to
`tap.acquireTimeoutSeconds` / `tap_acquire_timeout`), so two tests each holding one of two
devices can never deadlock. Requests are queued fairly; a request that cannot be satisfied by
the pool at all (more roles than devices) fails fast in Kotlin and skips the test in pytest.

## Pinning and constraints

- `tap.serials=a,b` / `TAP_SERIALS` limits the process to those devices and pins roles to them
  in declaration order.
- `tap.device.<role>=<serial>` pins one role (JUnit).
- From the SDK, `run.acquire(mapOf("phone" to DeviceConstraints(minApi = 33, emulator = false)))`
  takes per-role constraints: `serial`, `minApi`, `maxApi`, `emulator`, `modelContains`.
  Python: `run.acquire({"phone": {"min_api": 33, "emulator": False}})`.

## Cross-device waits

The receiver's UI is a normal `await(...)`. For conditions that span devices or systems, use
`device.awaitUntil(...)` — see [Actions and waits](actions-and-waits.md#waiting-on-your-own-condition).

## Inspecting the pool

`tap status` prints the running service's address, PID and version. The pool itself is one
call away in either client:

=== "Kotlin"

    ```kotlin
    TapClient().use { client -> client.inventory().forEach(::println) }
    ```

=== "Python"

    ```python
    for d in Service().inventory():
        print(d.facts.serial, pb.DeviceState.Name(d.state), d.leased_by_run, d.quarantine_reason)
    ```

Each device is `FREE`, `LEASED` (with the run and role holding it), `OFFLINE`, or
`QUARANTINED` with a reason. A device is quarantined when a session could not leave it provably
clean — the driver could not be stopped, the journal is corrupt, the boot identity changed
under an active session, or a mutation may still be in flight. It stays out of circulation until
you have checked the device and removed its journal record from `~/.tap/sessions/` (one
`<base64url(serial)>.json` per device). Nothing is retried or reset behind your back.
