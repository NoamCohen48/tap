# Multi-device tests

!!! info "Examples are in Python"
    In Kotlin, roles are `@TapDevices("sender", "receiver")` with a `Devices` parameter, and
    concurrent steps use coroutines. [Kotlin + JUnit 5](../sdk/kotlin.md#multi-device-tests)
    shows this page's example in Kotlin.

The server sees every device ADB can see and knows nothing but **serials**; which of them a run
uses is decided by the client. A device is in use exactly while a session holds its per-serial
lock (`~/.tap/sessions/<serial>.lock`), taken when the session opens and released when it
closes or its process dies. So two processes, a Gradle test JVM and a pytest run say, never
share a device, and a crashed process's devices come back on their own.

Naming devices, with *roles* such as `sender` and `receiver`, is the client's job: the pytest
plugin and the JUnit extension map roles to serials and then open one session per role.

## Declaring roles

```python
import pytest
from tap_e2e import res, text

@pytest.mark.tap_devices("sender", "receiver")
def test_delivers_a_message(tap_devices):
    sender = tap_devices["sender"].app("com.example.chat")
    receiver = tap_devices["receiver"].app("com.example.chat")
    sender.cold_launch()
    receiver.cold_launch()
    sender.element(res("compose")).set_text("hi")
    sender.element(text("Send")).tap()
    receiver.wait(text("hi"), timeout=20).visible()
```

Sessions are opened **one at a time in sorted serial order**, waiting up to the acquire timeout
(`tap_acquire_timeout`, 300 s) for a device another session holds. Every process takes device
locks in the same order, so two tests that both want the same two devices cannot deadlock: the
second simply waits for the first to finish. A test that needs more devices than the server
lists is skipped.

## Which serial plays which role

Decided by the client, in this order:

- A role pinned to a serial (JUnit: `tap.device.<role>=<serial>`).
- `TAP_SERIALS=a,b` (JUnit: `tap.serials`) limits the process to those devices; roles take
  them in declaration order.
- With nothing configured, the client asks the server for its device list and takes devices
  that are online and not quarantined: free ones first, then ones another session holds.

The unpinned devices are a rotating list: each test starts one device further on (wrapping), so
tests spread over the devices instead of all queueing on the first. A single-role test also
moves on to the next device when one is held by another session right now, and waits only when
every candidate is busy. Multi-role tests rotate the same way but open their assignment in
sorted serial order, waiting for each device, so they cannot deadlock.

## Without the test runner

From the client you do the same by hand: `connection.available_serials()` to look, then
`connection.attach_device(serial, wait_for_device=60)` to open. Without a wait, a busy device
fails at once with `DeviceBusyError`. The device list carries only serials and states; anything
richer (API level, model) comes from `device.info()` once a session is open, or from
`adb -s <serial> shell getprop` before.

## Waiting across devices

The receiver's UI is a normal `app.wait(...)` on that device. For conditions that span devices
or systems, use `device.await_until(...)` ([Your own condition](waits.md#your-own-condition)).
The [server's device list](server.md#the-devices) shows which device is held by whom.
