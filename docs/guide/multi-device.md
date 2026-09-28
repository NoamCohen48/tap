# Multi-device tests

The server sees every device ADB can see and knows nothing but **serials**; which of them a
run uses is decided on the client side (`tap.serials` / `tap_serials`). A device is in use exactly while a session holds its
per-serial lock (`~/.tap/sessions/<serial>.lock`), taken when the session opens and released
when it closes or its process dies — so two processes, a Gradle test JVM and a pytest run say,
never share a device, and a crashed process's devices come back on their own. Naming devices —
*roles* such as `sender` and `receiver` — is the client's job; the JUnit extension and the
pytest plugin map roles to serials and then open one session per role.

## Declaring roles

=== "Kotlin"

    ```kotlin
    import kotlinx.coroutines.async
    import kotlinx.coroutines.awaitAll
    import kotlinx.coroutines.coroutineScope

    @TapTest
    class ChatTest {
        @Test
        @TapDevices("sender", "receiver")
        fun deliversAMessage(devices: Devices) {
            tapTest {
                coroutineScope {
                    awaitAll(
                        async { devices["sender"].app().coldLaunch() },
                        async { devices["receiver"].app().coldLaunch() },
                    )
                }
                val sender = devices["sender"]
                val receiver = devices["receiver"]
                sender.element(res("compose")).setText("hi")
                sender.element(text("Send")).tap()
                receiver.await(text("hi"), timeout = 20.seconds).visible()
            }
        }
    }
    ```

    (`awaitAll` takes the deferreds — `coroutineScope { async { } async { } }.awaitAll()`
    does not compile, because the block value is only the last `Deferred`, not a collection.
    The same shape runs sample-backed
    in `samples/fixture-tests` `MultiDeviceTest`.)

    `@TapDevices` goes on the method or the class. With a single default role the parameter is
    just `device: Device`; `@TapDevice("receiver") device: Device` injects one named role.
    Use `coroutineScope` + `async`/`awaitAll` for concurrent phases; a failing sibling cancels
    the other's in-flight RPC. `DeviceBarrier(2)` is only for genuinely simultaneous phases
    (neither side proceeds until both arrive); backend propagation still waits on the
    observing device's UI.

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
devices than the server lists is skipped (a JUnit assumption failure in Kotlin,
`pytest.skip` in Python).

## Which serial plays which role

Decided by the client, in this order:

- `tap.device.<role>=<serial>` pins one role (JUnit).
- `tap.serials=a,b` / `TAP_SERIALS` limits the process to those devices; roles take them in
  declaration order.
- With nothing configured, the client asks the server for its device list and takes devices that
  are online and not quarantined — free ones first, then ones another session holds.

The unpinned devices are a rotating list: each test starts one device further on (wrapping), so
tests spread over the devices instead of all queueing on the first. A single-role test also
moves on to the next device when one is held by another session right now, and waits
(`tap.acquireTimeoutSeconds` / `tap_acquire_timeout`) only when every candidate is busy.
Multi-role tests rotate the same way but open their assignment in sorted serial order, waiting
for each device, so they cannot deadlock.

From the SDK you do the same by hand: `connection.availableSerials()` /
`connection.available_serials()` to look, then
`connection.attachDevice(serial, aut, options = DeviceOptions(waitForDevice = 60.seconds))` /
`connection.attach_device(serial, aut, wait_for_device=60)` to open; without a wait, a busy device fails
at once with `DeviceBusyException` / `DeviceBusyError`. The device list carries only serials
and states; anything richer (API level, model) comes from `device.info()` once a session is open,
or from `adb -s <serial> shell getprop` if you need it before.

## Cross-device waits

The receiver's UI is a normal `await(...)`. For conditions that span devices or systems, use
`device.awaitUntil(...)` — see [Actions and waits](actions-and-waits.md#waiting-on-your-own-condition).

## Inspecting the devices

`tap status` prints the running server's address, PID and version. The device list is one
call away in either client:

=== "Kotlin"

    ```kotlin
    runBlocking {
        val client = TapClient.create()
        try {
            client.devices().forEach(::println)
        } finally {
            client.close()
        }
    }
    ```

=== "Python"

    ```python
    for d in TapClient.create().devices():
        print(d.serial, pb.DeviceState.Name(d.state), d.client_connection_id, d.quarantine_reason)
    ```

Each device is `FREE`, `LEASED` (with the connection holding it, when it is one of this
server's), `OFFLINE`, `UNAUTHORIZED` (ADB lists it but the device has not accepted this
machine's key), or `QUARANTINED` with a reason. Only `FREE` and `LEASED` devices can be
attached. A device is quarantined when a session could not leave it provably
clean — the driver could not be stopped, the journal is corrupt, the boot identity changed
under an active session, or a mutation may still be in flight. It stays out of circulation until
you have checked the device and removed its journal record from `~/.tap/sessions/` (one
`<base64url(serial)>.json` per device). Nothing is retried or reset behind your back.
