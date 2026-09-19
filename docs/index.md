# Tap

<img src="assets/logo-lockup.svg" alt="Tap" width="200" height="64" style="display:block;margin:0 0 1rem">

**Host-driven end-to-end testing for Android.** Your tests run on the host, in Kotlin (JUnit 5)
or Python (pytest). A per-machine service (`tap serve`) owns ADB, the devices and a small
on-device driver; the tests talk to it over gRPC. Nothing about your app changes: the driver
is a separate package and never links into the app under test.

```kotlin
@TapTest
class CheckoutTest {
    @Test
    fun buysAnItem(device: Device) {
        device.app().coldLaunch()
        device.element(res("buy_button")).tap()
        device.await(text("Order placed")).visible()
    }
}
```

```python
def test_buys_an_item(tap_device):
    tap_device.app().cold_launch()
    tap_device.element(res("buy_button")).tap()
    tap_device.wait(text("Order placed")).visible()
```

## What you get

- **Deterministic actions.** Every action re-resolves its selector on the device and requires
  exactly one match; `AMBIGUOUS` or `NOT_FOUND` comes back *before* any input is injected.
  There are no cached element handles that can go stale.
- **Explicit waits, no hidden ones.** `await(...).visible()`, `awaitAppVisible()`,
  `awaitAppSettled()`, `awaitAnimationEnd()`. No command sleeps or settles on its own, so
  actions stay fast on busy screens and the wait you wrote is the wait you get.
- **Honest failures.** A closed error taxonomy with stable sub-reasons (`WAIT_TIMEOUT` /
  `SCREEN_CHANGING`, `NOT_INTERACTABLE` / `FOCUS_TIMEOUT`, …), and an explicit
  `INDETERMINATE` when the transport dropped after a mutation was accepted. Tap never replays a
  mutation to guess its way out.
- **Failure artifacts for free.** Screenshot, accessibility hierarchy, device info and the
  driver log per failed test, captured while the session is still live.
- **Multi-device out of the box.** A machine-wide pool with leases; tests declare named roles
  (`@TapDevices("sender", "receiver")`) and get them all-or-none, across processes and
  languages.
- **Survives the app.** The driver lives outside the app under test: force-stop, clear data
  and reinstall in the middle of a test and keep going.
- **One engine, thin clients.** ADB, sessions, journals, leases and the driver live in the
  service; Kotlin and Python are ~1 000-line gRPC clients of the same API, so behaviour is
  identical in both.

## Where to go

| | |
|---|---|
| [Getting started](guide/getting-started.md) | install the service, run the first test in Kotlin or Python |
| [How it works](guide/how-it-works.md) | service, driver, sessions, pool — the model behind the API |
| [Selectors](guide/selectors.md) | the selector DSL, scoping rules, what is deliberately not supported |
| [Actions and waits](guide/actions-and-waits.md) | taps, text, gestures, scrolling, every kind of wait |
| [App lifecycle and sync](guide/app-lifecycle.md) | launch/cold launch, clear data, permissions, app-owned idle |
| [Multi-device tests](guide/multi-device.md) | roles, constraints, the pool |
| [Configuration](guide/configuration.md) | properties, environment, the `tap` CLI |
| [Errors and artifacts](guide/errors.md) | the error codes, what they mean, what to look at |
| [Coming from Maestro or Appium](guide/coming-from.md) | mapping table |
| [API reference](reference/index.md) | generated Kotlin, Python and gRPC references |

## Status

Tap is pre-1.0. The device protocol, the service API and both clients are in use against real
devices (API 29 physical, API 34 emulator) and exercised by CI on every change, but the API can
still change between minor versions. See [Releases and versions](reference/releases.md).
