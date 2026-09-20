# tap-e2e (Python)

Python client and pytest plugin for the Tap host service. All device logic lives in the
service (`host/service`, the `tap` binary); this package is a thin gRPC client generated from
`contracts/api/proto/tap.proto` plus a small ergonomic layer that mirrors the Kotlin SDK (`Device`, `Element`,
waits, selectors, `App`).

## Install

```sh
pip install -e clients/python            # from the repository; or `pip install tap-e2e` once published
```

Runtime requirements: a `tap` service binary (`./gradlew :host:service:nativeCompile` →
`host/service/build/native/nativeCompile/tap`, or the JVM distribution from
`:host:service:installDist`) and `adb` on `PATH`.

## Service discovery

`Service()` uses, in order: `TAP_SERVICE=host:port`; a live `service.json` in the state dir
(`TAP_STATE_DIR`, default `~/.tap`); otherwise it starts `tap serve` from `TAP_BIN` or `tap` on
`PATH`. A started service stays running like the ADB server (`tap stop` shuts it down), so
concurrent test processes on one machine respect each other's device locks.

## Script usage

```python
from tap import Service, text, res

service = Service()
with service.connect("smoke") as connection:           # attaches: if this process dies, the
    with connection.open_device("emulator-5554", "com.company.tap.fixture") as device:
        device.app().cold_launch(".MainActivity")
        device.element(res("view_button")).tap()
        device.wait(text("View tapped")).visible()
        png = device.screenshot()
```

Driver failures are raised as `CommandError` with `.code` (an `ErrorCode`, e.g. `AMBIGUOUS`,
`NOT_FOUND`, `INDETERMINATE`), `.detail` and the request id/generation; waits raise
`WaitTimeoutError` (`.last_observation` carries the driver detail, e.g. `SCREEN_CHANGING` from
`device.await_app_settled()` (hierarchy quiet), `device.await_animation_end()` (pixels quiet) or
`device.await_screen_stable()` (both) — the explicit ways to wait out an animation; no command
settles implicitly); app lifecycle problems raise `AppLifecycleError`; service refusals raise
`ServiceError`. Cancelling a gRPC call (e.g. a thread interrupt) forwards a protocol `CANCEL`
to the driver, which is honoured only before the mutation gate.

## pytest

Configuration comes from ini options or environment variables: `tap_aut`/`TAP_AUT` (required),
`tap_serials`/`TAP_SERIALS`, `tap_artifacts`/`TAP_ARTIFACTS`, `tap_service`/`TAP_SERVICE`,
`tap_acquire_timeout`.

```python
import pytest
from tap import text

def test_login(tap_device):                      # one device, role "device"
    tap_device.element(text("Login")).tap()

@pytest.mark.tap_devices("caller", "callee")     # several roles, opened in serial order
def test_call(tap_devices):
    tap_devices["caller"].element(text("Call")).tap()
```

Each test gets fresh sessions (opened before, closed after); on failure the
plugin writes a screenshot, accessibility hierarchy, device info and the driver log per device
under `tap-artifacts/<nodeid>/`. Tests that need more roles than there are devices are skipped.

The sample suite in `tests/` drives the fixture app on the local matrix:

```sh
./gradlew :fixture-app:assembleDebug :host:service:nativeCompile
TAP_BIN=$PWD/host/service/build/native/nativeCompile/tap TAP_SERIALS=emulator-5554,85e49002 pytest clients/python/tests
```

## Generated stubs

`tap/_gen` is generated from `contracts/api/proto/tap.proto` and committed. After editing the proto run
`clients/python/scripts/gen_stubs.py` (needs `grpcio-tools`); CI runs `gen_stubs.py --check`.
