# tap-e2e (Python)

Python client and pytest plugin for the Tap host daemon. All device logic lives in the
daemon (`host/daemon`, the `tap` binary); this package is a thin gRPC client generated from
`contracts/api/proto/*.proto` plus a small ergonomic layer that mirrors the Kotlin SDK (`Device`, `Element`,
waits, selectors, `App`).

## Install

```sh
pip install -e clients/python            # from the repository; or `pip install tap-e2e` once published
```

Runtime requirements: a `tap` daemon binary (`./gradlew :host:daemon:nativeCompile` →
`host/daemon/build/native/nativeCompile/tap`, or the JVM distribution from
`:host:daemon:installDist`) and `adb` on `PATH`.

## Server discovery and lifecycle

`TapServer()` uses, in order: `TAP_SERVER=host:port`; a live `daemon.json` in the state dir
(`TAP_STATE_DIR`, default `~/.tap`), written by `tap start`. Every call carries the daemon's
bearer token: from `daemon.json`, or from `TAP_TOKEN` with an explicit `TAP_SERVER` (or
`TapServer(address, token)`); a wrong or missing one raises `ServerError` `UNAUTHENTICATED`. It never starts the daemon; without
one it raises `TapError: no running tap daemon … run `tap start``. A started daemon stays
running like the ADB server (`tap stop`), so concurrent test processes on one machine share it
and respect each other's device locks.

To start and stop it from Python, `tap.start_daemon()` runs `tap start` (from `TAP_BIN` or
`tap` on `PATH`) and returns a `DaemonStartResult(address, started)` — `started` is `False` when one
was already running — and `tap.stop_daemon()` runs `tap stop`. The pytest plugin does both
when `tap_manage_daemon = true` / `TAP_MANAGE_DAEMON=1`, stopping only a daemon it started.

## Script usage

```python
from tap import TapServer, text, res

server = TapServer()
with server.connect("smoke") as connection:  # observing: if this process dies, the server detaches its devices
    with connection.attach_device("emulator-5554", "com.company.tap.fixture") as device:
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
settles implicitly); app lifecycle problems raise `AppLifecycleError`; server refusals raise
`ServerError`. Cancelling a gRPC call (e.g. a thread interrupt) forwards a protocol `CANCEL`
to the driver, which is honoured only before the mutation gate.

## pytest

Configuration comes from ini options or environment variables: `tap_aut`/`TAP_AUT` (required),
`tap_serials`/`TAP_SERIALS`, `tap_artifacts`/`TAP_ARTIFACTS`, `tap_server`/`TAP_SERVER`,
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
./gradlew :fixture-app:assembleDebug :host:daemon:nativeCompile
TAP_BIN=$PWD/host/daemon/build/native/nativeCompile/tap TAP_SERIALS=emulator-5554,85e49002 pytest clients/python/tests
```

## Generated stubs

`tap/_gen` is generated from `contracts/api/proto/*.proto` and committed. After editing the proto run
`clients/python/scripts/gen_stubs.py` (needs `grpcio-tools`); CI runs `gen_stubs.py --check`.
