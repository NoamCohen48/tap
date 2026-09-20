# Multi-device tests

The service keeps a **machine-wide pool** of every device ADB can see (optionally restricted with
`tap serve --serials a,b`). The service knows nothing but **serials**: a run leases some,
all at once or not at all. Naming devices — *roles* such as `sender` and `receiver` — is the
client's job; the JUnit extension and the pytest plugin map roles to serials before asking. Leases live in `~/.tap/sessions`, so two processes — a Gradle test
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

Acquisition is **all-or-none**: the test either leases every serial it asked for or waits (up
to `tap.acquireTimeoutSeconds` / `tap_acquire_timeout`) until all of them are free at the same
time, so two tests each holding one of two devices can never deadlock. A test that needs more
devices than there are is skipped (a JUnit assumption failure in Kotlin, `pytest.skip` in
Python).

## Which serial plays which role

Decided by the client, in this order:

- `tap.device.<role>=<serial>` pins one role (JUnit).
- `tap.serials=a,b` / `TAP_SERIALS` limits the process to those devices; roles take them in
  declaration order.
- With nothing configured, the client asks the service for its inventory and takes devices that
  are online and not quarantined — free ones first, then ones another run holds (the lease
  call then waits for them).

From the SDK you do the same by hand: `run.freeSerials()` / `run.free_serials()` to look, then
`run.acquire(listOf("a", "b"))` / `run.acquire(["a", "b"], timeout=60)` to lease. `DeviceFacts`
in the inventory (`api_level`, `model`, `emulator`, …) is there so a client can choose; the
service never filters on it.

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
