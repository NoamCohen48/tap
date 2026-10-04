# Python + pytest

The Python client, `tap-e2e` (imported as `tap_e2e`), and the pytest plugin it registers. The
[Guide](../guide/selectors.md)'s examples are all in Python; this page covers what is specific
to the client: installing it, the plugin's fixtures and options, and using it from a script.
The [Python API reference](../reference/python/index.md) has every signature.

## Install

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
```

Python 3.10 or newer. The [server](../guide/server.md) must be running (`tap start`), or let
the test run start it with `tap_manage_daemon = true`.

## A first test

```python
from tap_e2e import res, text

def test_opens_the_home_screen(tap_device):
    app = tap_device.app("com.shop")
    app.install("build/outputs/apk/debug/shop-debug.apk")
    app.cold_launch()                         # resolves the launcher activity, waits for its window
    app.wait(text("Welcome")).visible()       # only com.shop's nodes count
    app.element(res("search")).set_text("socks")
```

```bash
TAP_SERIALS=emulator-5554 pytest
```

## Fixtures and marker

| Fixture | What it is |
|---|---|
| `tap_device` | a `Device` for the default role, attached before the test and detached after it |
| `tap_devices` | a dict role → `Device`, for the roles of `@pytest.mark.tap_devices(...)` |
| `tap_connection` | the `TapConnection` the devices are attached through |
| `tap_client` | the `TapClient` for the run |
| `tap_config` | the resolved options below |

```python
import pytest

@pytest.mark.tap_devices("sender", "receiver")
def test_delivers_a_message(tap_devices):
    sender = tap_devices["sender"].app("com.example.chat")
    ...
```

How roles map to serials is on [Multi-device tests](../guide/multi-device.md). When a test
fails, the plugin saves its devices' [failure artifacts](../guide/artifacts.md#failure-artifacts)
under `tap-artifacts/<nodeid>/`.

## Configuration

Each option is an ini value (`pytest.ini`, `pyproject.toml` `[tool.pytest.ini_options]`,
`setup.cfg`) or an environment variable; the environment wins.

| ini | Environment | Meaning | Default |
|---|---|---|---|
| `tap_serials` | `TAP_SERIALS` | comma-separated serials; roles map to them in order, starting one device further on per test | any device the server lists |
| `tap_artifacts` | `TAP_ARTIFACTS` | failure artifact directory | `tap-artifacts` |
| `tap_device_scope` | `TAP_DEVICE_SCOPE` | `function` = attach before each test, detach after it; `class` / `module` / `session` = keep the devices for the next test of the same class / module / run ([Reusing devices](#reusing-devices)) | `function` |
| `tap_capture` | `TAP_CAPTURE` | `onFailure` = `device.capture()` every device of a failed test into that directory; `off` = capture nothing | `onFailure` |
| `tap_server` | `TAP_SERVER` | `host:port` of a running server | the one in `daemon.json` |
| — | `TAP_TOKEN` | bearer token for an explicit `TAP_SERVER` | the one in `daemon.json` |
| `tap_manage_daemon` | `TAP_MANAGE_DAEMON` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the server | `false` |
| `tap_acquire_timeout` | `TAP_ACQUIRE_TIMEOUT` | seconds to wait for a device another session holds | `300` |
| — | `TAP_BIN` | the `tap` executable `tap_manage_daemon` runs | `tap` on `PATH` |
| — | `TAP_STATE_DIR` | state dir shared with the server | `~/.tap` |

## Reusing devices

By default every test attaches its devices and detaches them afterwards, so each test starts
with a fresh driver session. Starting the driver is most of an attach's cost: about 1.2 s on an
emulator and 3.8 s on a mid-range phone (measured on an API 34 emulator and an API 29 Samsung),
per test. A suite can keep its devices instead:

```ini
# pytest.ini (or TAP_DEVICE_SCOPE=class)
[pytest]
tap_device_scope = class
```

Consecutive tests of the class (or module, or whole run) that declare the same roles then share
the attached devices, which are detached after the last of them. Nothing is reset between tests:
the app keeps whatever state the previous test left, so each test brings it where it needs it
(`cold_launch()`, `clear_data()`). Before each test a reused device is probed (`info()`, a few
milliseconds); one that stopped working (quarantined, driver lost, detached by the test) is
detached and a fresh one attached. Failure artifacts are still captured per test.

## Without pytest

A client connects to the server, then attaches each device it needs:

```python
from tap_e2e import TapClient, text

with TapClient.create().connect("smoke") as connection:
    with connection.attach_device("emulator-5554") as device:
        app = device.app("com.shop")
        app.cold_launch()
        print(app.element(text("Welcome")).exists())
```

`connect` opens the connection's liveness stream before it returns, so if the process dies the
server notices the stream closing and frees its devices. If the stream ends while the process
lives (the server restarted), the connection becomes unusable: further attaches and device calls
fail at once, and a new `connect` is needed. The pytest plugin does that for you on the next
test.

`tap_e2e.start_daemon()` / `stop_daemon()` start and stop the server from code, and report
whether the call started it ([Who starts it](../guide/server.md#who-starts-it)).

## Timeouts and session options

Timeouts are per device: `Timeouts(action=10.0, wait=10.0, lifecycle=30.0, poll_interval=0.1)`
(seconds) is the default, and every method also takes an explicit `timeout`. Pass them, and the
session options, to `connection.attach_device(serial, ...)`:

| Argument | Meaning |
|---|---|
| `timeouts` | a `Timeouts` for this device |
| `skip_driver_install` | assume the server's driver is already installed (CI images with a pre-provisioned driver) |
| `wait_for_device` | seconds to wait for a device another session holds; zero (the default) fails at once with `DeviceBusyError` |

The pytest plugin uses the defaults; override them from a script or a custom fixture. The
driver itself is the server's: clients cannot pick one per attach.

## Held connections and screen snapshots

These are for scripts and tools that drive a device over several processes, such as a coding
agent exploring an app one command at a time ([tap-agent](../agent/index.md) is built on them).
Tests should not use them. They are in the Python client only.

A normal connection lasts as long as the process that opened it. A *held* connection stays
alive in the server after the process exits. It ends when some process closes it, when no call
has named it for `hold` seconds, or when the server stops. A later process finds it by name, and
gets back the devices attached to it without attaching them again:

```python
from tap_e2e import TapClient

# first process
connection = TapClient.create().connect("explore", hold=15 * 60)
connection.attach_device("emulator-5554")

# any later process
connection = TapClient.create().resume("explore")
(device,) = connection.attached_devices()
snapshot = device.screen_snapshot()
for node in snapshot.nodes:
    print(node.ref, node.class_name, node.text, node.selector)
device.screen.element(device.resolve_ref("e7")).tap()
connection.close()  # ends it for everyone; leave it open for the next process
```

Only one held connection can have a given name at a time. The idle clock restarts with every
call on the connection or its devices. A crashed process leaves the devices attached until the
connection's hold time runs out, which is why tests keep the default.

`screen_snapshot()` returns the visible screen, taken from the diagnostic hierarchy dump. Each
node has a ref (`e7`) and, when one exists, a selector that matched only that node at snapshot
time. It also has a `change` value saying whether it was added since the previous snapshot of
that device; nodes that disappeared are listed in `removed`. A ref keeps pointing at the same
node from one snapshot to the next, and is never given to a different node. A ref only names a
selector, so acting on it still goes through the normal rule: the device must find exactly one
match at the moment of the action. `screen_snapshot(selector_candidates=True)` also fills each
node's `candidates`: every selector that matched only that node, best first (the first is
`selector`), each with its `SelectorKind` (`PLAIN`, `COMBINED`, `ANCESTOR` or `BY_INDEX`), for
tools that let a person choose between them.

## The event log

`connection.event_log()` returns what the connection did on its devices, in order: every
command except the diagnostic device-info and hierarchy queries, and every app call that
changes the device (install, uninstall, force-stop, clear data, grant, launch, cold launch).
Each `LoggedEvent` has the call (`command` or `app`) and its outcome (`error` or `failure`) as
the server API's messages in proto3 JSON, so any language can read it; `to_dict()` gives a
JSON-ready form. A command appears with the selector it sent, so a tap on a ref shows the
selector the ref stood for. The server keeps each connection's last 2000 events, until the
connection ends. `tap-agent export` writes this log as a JSON file.

```python
log = connection.event_log()
for event in log.events:
    print(event.seq, event.command or event.app, "ok" if event.ok else event.error or event.failure)
```
