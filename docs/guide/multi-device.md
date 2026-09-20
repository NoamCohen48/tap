# Multi-device tests

The service sees every device ADB can see (optionally restricted with `tap serve --serials a,b`)
and knows nothing but **serials**. A device is in use exactly while a session holds its
per-serial lock (`~/.tap/sessions/<serial>.lock`), taken when the session opens and released
when it closes or its process dies — so two processes, a Gradle test JVM and a pytest run say,
never share a device, and a crashed process's devices come back on their own. Naming devices —
*roles* such as `sender` and `receiver` — is the client's job; the JUnit extension and the
pytest plugin map roles to serials and then open one session per role.

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

Sessions are opened **one at a time in sorted serial order**, waiting up to
`tap.acquireTimeoutSeconds` / `tap_acquire_timeout` for a device another session holds. Every
process takes device locks in the same order, so two tests that both want the same two devices
cannot deadlock: the second simply waits for the first to finish. A test that needs more
devices than the inventory offers is skipped (a JUnit assumption failure in Kotlin,
`pytest.skip` in Python).

## Which serial plays which role

Decided by the client, in this order:

- `tap.device.<role>=<serial>` pins one role (JUnit).
- `tap.serials=a,b` / `TAP_SERIALS` limits the process to those devices; roles take them in
  declaration order.
- With nothing configured, the client asks the service for its inventory and takes devices that
  are online and not quarantined — free ones first, then ones another session holds.

From the SDK you do the same by hand: `run.availableSerials()` / `run.available_serials()` to
look, then `run.openDevice(serial, aut, options = DeviceOptions(waitForDevice = 60.seconds))` /
`run.open_device(serial, aut, wait_for_device=60)` to open; without a wait, a busy device fails
at once with `DeviceBusyException` / `DeviceBusyError`. The inventory carries only serials and
states; anything richer (API level, model) comes from `device.info()` once a session is open,
or from `adb -s <serial> shell getprop` if you need it before.

## Cross-device waits

The receiver's UI is a normal `await(...)`. For conditions that span devices or systems, use
`device.awaitUntil(...)` — see [Actions and waits](actions-and-waits.md#waiting-on-your-own-condition).

## Inspecting the devices

`tap status` prints the running service's address, PID and version. The pool itself is one
call away in either client:

=== "Kotlin"

    ```kotlin
    TapClient().use { client -> client.inventory().forEach(::println) }
    ```

=== "Python"

    ```python
    for d in Service().inventory():
        print(d.serial, pb.DeviceState.Name(d.state), d.leased_by_run, d.quarantine_reason)
    ```

Each device is `FREE`, `LEASED` (with the run and role holding it), `OFFLINE`, or
`QUARANTINED` with a reason. A device is quarantined when a session could not leave it provably
clean — the driver could not be stopped, the journal is corrupt, the boot identity changed
under an active session, or a mutation may still be in flight. It stays out of circulation until
you have checked the device and removed its journal record from `~/.tap/sessions/` (one
`<base64url(serial)>.json` per device). Nothing is retried or reset behind your back.
