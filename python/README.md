# tap-e2e (Python)

Python client and pytest plugin for the Tap host service. All device logic lives in the
service (`host/service`, the `tap` binary); this package is a thin gRPC client generated from
`api/tap.proto` plus a small ergonomic layer that mirrors the Kotlin SDK (`Device`, `Element`,
waits, selectors, `App`).

## Install

```sh
pip install -e python            # from the repository; or `pip install tap-e2e` once published
```

Runtime requirements: a `tap` service binary (`./gradlew :host:service:nativeCompile` →
`host/service/build/native/nativeCompile/tap`, or the JVM distribution from
`:host:service:installDist`) and `adb` on `PATH`.

## Service discovery

`Service()` uses, in order: `TAP_SERVICE=host:port`; a live `service.json` in the state dir
(`TAP_STATE_DIR`, default `~/.tap`); otherwise it starts `tap serve` from `TAP_BIN` or `tap` on
`PATH`. A started service stays running like the ADB server (`tap stop` shuts it down), so
concurrent test processes on one machine share the same device pool and leases.

## Script usage

```python
from tap import Service, text, res_id

service = Service()
with service.open_run("smoke") as run:                  # attaches: if this process dies, the
    facts = run.acquire({"device": {"serial": "emulator-5554"}}, timeout=60)["device"]
    with run.open_device(facts.serial, "com.company.tap.fixture") as device:
        device.app().cold_launch(".MainActivity")
        device.element(res_id("com.company.tap.fixture", "view_button")).tap()
        device.wait(text("View tapped")).visible()
        png = device.screenshot()
```

Driver failures are raised as `CommandError` with `.code` (an `ErrorCode`, e.g. `AMBIGUOUS`,
`NOT_FOUND`, `INDETERMINATE`), `.detail` and the request id/generation; waits raise
`WaitTimeoutError`; app lifecycle problems raise `AppLifecycleError`; service refusals raise
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

@pytest.mark.tap_devices("caller", "callee")     # several roles, acquired all-or-none
def test_call(tap_devices):
    tap_devices["caller"].element(text("Call")).tap()
```

Each test gets fresh sessions (acquired from the pool before, released after); on failure the
plugin writes a screenshot, accessibility hierarchy, device info and the driver log per device
under `tap-artifacts/<nodeid>/`. Tests that need more roles than `tap_serials` lists are skipped.

The sample suite in `tests/` drives the fixture app on the local matrix:

```sh
./gradlew :fixture-app:assembleDebug :host:service:nativeCompile
TAP_BIN=$PWD/host/service/build/native/nativeCompile/tap TAP_SERIALS=emulator-5554,85e49002 pytest python/tests
```

## Generated stubs

`tap/_gen` is generated from `api/tap.proto` and committed. After editing the proto run
`python/scripts/gen_stubs.py` (needs `grpcio-tools`); CI runs `gen_stubs.py --check`.
