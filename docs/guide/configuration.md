# Configuration

Tap has very little to configure: which server to talk to, which app is under test, which
devices to use, and where artifacts go.

## The `tap` CLI

```
tap start   [--port N] [--state-dir DIR] [--adb PATH]
tap serve   [--port N] [--state-dir DIR] [--adb PATH] [--driver-apk APK --driver-test-apk APK]
tap status  [--state-dir DIR]
tap stop    [--state-dir DIR]
tap version
```

| | |
|---|---|
| `start` | starts the daemon in the background and returns once it answers: prints `started 127.0.0.1:PORT pid=PID`, or `running …` when one is already up in that state dir (nothing is started twice). Its output goes to `<state-dir>/daemon.log`; exit 1 if it died or never became ready |
| `serve` | the same daemon in the foreground (`--port 0` = ephemeral, the default); what `start` runs for you |
| both | bind loopback only, write `<state-dir>/daemon.json` (`port`, `pid`, `token`, `daemonVersion`, `adb`; readable only by you), and keep running until `tap stop`. One daemon per state dir: a second `serve` exits 3 |
| `--state-dir` | where `daemon.json`, `sessions/` (leases and journals) and the extracted driver live; default `$TAP_STATE_DIR` or `~/.tap` |
| `--adb` | the ADB executable; default `$TAP_ADB` or `adb` on `PATH` |
| `status` | prints `running 127.0.0.1:PORT pid=… version=… adb=…` (never the token); exit 1 when no daemon is running |
| `stop` | terminates the daemon recorded in `daemon.json` after it answered an authenticated call; a stale file is removed and nothing is signalled |

One daemon per machine is the intended setup; every test process on that machine connects to
it. Two daemons with different `--state-dir`s are isolated from each other (separate journals and
device locks), which is how the framework's own CI keeps a scratch daemon apart from `~/.tap`.

## How clients find the server

In order:

1. An explicit address: `tap.server` system property / `TAP_SERVER` (`host:port`), or the
   pytest `tap_server` option.
2. A live `daemon.json` in the state dir (`TAP_STATE_DIR`, default `~/.tap`) — what
   `tap start` wrote.
3. Otherwise the client fails with *no running tap daemon; run `tap start`*.

Every call carries the daemon's bearer token (`authorization: Bearer <token>`). With a
discovered daemon the client reads it from `daemon.json` (only the user running the daemon
can read that file). With an explicit address it comes from `tap.token` / `TAP_TOKEN` (Kotlin)
or `TAP_TOKEN` (Python). A wrong or missing token fails every call with `UNAUTHENTICATED`
*wrong or missing daemon token*.

Clients never start the daemon themselves. Starting is explicit, and there are three ways to do it:

- **By hand or in a CI step**: `tap start` before the tests, `tap stop` after. The daemon is
  shared by every test process on the machine, in any language.
- **From the test runner**: `tap.manageDaemon=true` (JUnit) / `tap_manage_daemon = true`
  (pytest). The runner runs `tap start` before its first session and `tap stop` when the run
  ends — but only if that start created the daemon; one that was already running is left
  running. `tap.bin` / `TAP_BIN` says which executable to use (default `tap` on `PATH`).
- **From code**: Kotlin `TapDaemonProcess.start()` / `stop()`, Python `tap.start_daemon()` /
  `tap.stop_daemon()`. Both return whether the call started the daemon.

Set `TAP_STATE_DIR` for both the daemon and the clients if you want it anywhere but `~/.tap`.

## Kotlin + JUnit 5

Read once per JVM from system properties, falling back to environment variables with the same
name with camelCase and dots turned into underscores, upper-cased (`tap.autPackage` → `TAP_AUT_PACKAGE`, `tap.device.sender` → `TAP_DEVICE_SENDER`):

| Property | Meaning | Default |
|---|---|---|
| `tap.autPackage` (or `tap.aut`) | the application under test | **required** |
| `tap.serials` | comma-separated serials to use; roles map to them in order, starting one device further on per test ([details](multi-device.md#which-serial-plays-which-role)) | any device the server lists |
| `tap.device.<role>` | pin one role to a serial (must be in `tap.serials` when that is set) | — |
| `tap.artifactsDir` | failure artifacts root | `build/tap-artifacts` |
| `tap.capture` | `onFailure` = `device.capture()` every device of a failed test into the artifacts root; `off` = capture nothing | `onFailure` |
| `tap.acquireTimeoutSeconds` | how long to wait for a device another session holds | `300` |
| `tap.server` | `host:port` of a running server | the one in `daemon.json` |
| `tap.token` | bearer token for an explicit `tap.server` | the one in `daemon.json` |
| `tap.manageDaemon` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the daemon | `false` |
| `tap.bin` | the `tap` executable `tap.manageDaemon` runs | `TAP_BIN`, then `PATH` |

Gradle passes them with `systemProperty(...)` on the test task; a common pattern forwards
`-P` properties:

```kotlin
tasks.test {
    useJUnitPlatform()
    systemProperty("tap.autPackage", "com.shop")
    listOf("tap.serials", "tap.server", "tap.manageDaemon", "tap.bin").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
```

Timeouts are per device, not global: `Timeouts(action = 10.seconds, wait = 10.seconds,
lifecycle = 30.seconds, pollInterval = 100.milliseconds)` is the default, and every method also
takes an explicit `timeout`. With the SDK directly, pass `Timeouts` and `DeviceOptions` to
`connection.attach(serial, aut, timeouts, options) { device -> ... }` or
`connection.attachDevice(...)` (both `suspend`; `attachDevice` handles live inside `tapScope`,
`tapTest` in JUnit).

## Python + pytest

Each option is an ini value (`pytest.ini`, `pyproject.toml` `[tool.pytest.ini_options]`,
`setup.cfg`) or an environment variable; the environment wins.

| ini | Environment | Meaning | Default |
|---|---|---|---|
| `tap_aut` | `TAP_AUT` | the application under test | **required** |
| `tap_serials` | `TAP_SERIALS` | comma-separated serials; roles map to them in order, starting one device further on per test | any device the server lists |
| `tap_artifacts` | `TAP_ARTIFACTS` | failure artifact directory | `tap-artifacts` |
| `tap_device_scope` | `TAP_DEVICE_SCOPE` | `function` = attach before each test, detach after it; `class` / `module` / `session` = keep the devices for the next test of the same class / module / run ([Reusing devices](#reusing-devices)) | `function` |
| `tap_capture` | `TAP_CAPTURE` | `onFailure` = `device.capture()` every device of a failed test into that directory; `off` = capture nothing | `onFailure` |
| `tap_server` | `TAP_SERVER` | `host:port` of a running server | the one in `daemon.json` |
| — | `TAP_TOKEN` | bearer token for an explicit `TAP_SERVER` | the one in `daemon.json` |
| `tap_manage_daemon` | `TAP_MANAGE_DAEMON` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the daemon | `false` |
| `tap_acquire_timeout` | `TAP_ACQUIRE_TIMEOUT` | seconds to wait for a device another session holds | `300` |
| — | `TAP_BIN` | the `tap` executable `tap_manage_daemon` runs | `tap` on `PATH` |
| — | `TAP_STATE_DIR` | state dir shared with the daemon | `~/.tap` |

Fixtures: `tap_device` (the default role), `tap_devices` (dict role → `Device`), plus
`tap_connection`, `tap_client` and `tap_config` for scripts that want the lower layers. Marker:
`@pytest.mark.tap_devices("a", "b")`.

## Reusing devices

By default every test attaches its devices and detaches them afterwards, so each test starts
with a fresh driver session. Starting the driver is most of an attach's cost: about 1.2 s on an
emulator and 3.8 s on a mid-range phone (measured on the local API 34 emulator and an API 29
Samsung), per test. A suite can opt out of that:

=== "Kotlin"

    ```kotlin
    @TapTest(deviceLifetime = DeviceLifetime.PER_CLASS)
    class CheckoutTest { ... }
    ```

=== "Python"

    ```ini
    # pytest.ini — or TAP_DEVICE_SCOPE=class
    [pytest]
    tap_device_scope = class
    ```

Consecutive tests of the class (Python: of the class, module or whole run) that declare the same
roles then share the attached devices, which are detached after the last of them. Nothing is
reset between tests: the app keeps whatever state the previous test left, so each test brings
it where it needs it (`coldLaunch()`, `clearData()`). Before each test a reused device is probed
(`info()`, a few milliseconds); one that stopped working — quarantined, driver lost, detached by
the test — is detached and a fresh one attached. Failure artifacts are still captured per test.

## Held connections and screen snapshots (Python)

These are for scripts and tools that drive a device over several processes, such as a coding
agent exploring an app one command at a time. Tests should not use them. For now they are in
the Python client only.

A normal connection lasts as long as the process that opened it. A *held* connection stays
alive in the daemon after the process exits. It ends when some process closes it, when no call
has named it for `hold` seconds, or when the daemon stops. A later process finds it by name, and
gets back the devices attached to it without attaching them again:

```python
from tap_e2e import TapClient

# first process
client = TapClient.create()
connection = client.connect("explore", hold=15 * 60)
connection.attach_device("emulator-5554", "com.example.app")

# any later process
connection = TapClient.create().resume("explore")
(device,) = connection.attached_devices()
snapshot = device.screen_snapshot()
for node in snapshot.nodes:
    print(node.ref, node.class_name, node.text, node.selector)
device.element(device.resolve_ref("e7")).tap()
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
match at the moment of the action.

`connection.event_log()` returns what the connection did on its devices, in order: every
command except the diagnostic device-info and hierarchy queries, and every app call that
changes the device (install, uninstall, force-stop, clear data, grant, launch, cold launch).
Each `LoggedEvent` has the call (`command` or `app`) and its outcome (`error` or `failure`) as
the server API's messages in proto3 JSON, so any language can read it; `to_dict()` gives a
JSON-ready form. A command appears with the selector it sent, so a tap on a ref shows the
selector the ref stood for. The daemon keeps each connection's last 2000 events, until the
connection ends. `tap-agent export` writes this log as a JSON file.

```python
log = connection.event_log()
for event in log.events:
    print(event.seq, event.command or event.app, "ok" if event.ok else event.error or event.failure)
```

## Session options

Both `connection.attachDevice(...)` / `connection.attach_device(...)` accept (`suspend` in Kotlin):

| Option | Meaning |
|---|---|
| `skipDriverInstall` | assume the daemon's driver is already installed (CI images with a pre-provisioned driver) |
| `syncAuthority` | the app's sync provider authority when it is not `<package>.tap-sync` |

The JUnit extension and the pytest plugin use the defaults; override them only from a script or
a custom fixture. The driver itself is the daemon's: the one bundled in the executable, or a
build of your own given when the daemon starts (`tap serve --driver-apk X --driver-test-apk Y`).
Clients cannot pick a driver per attach.

## Devices

- API 26 and up, emulator or physical, visible to `adb devices` as `device`.
- Turn off animations on physical devices you use routinely (`settings put global
  *_animation_scale 0`); Tap does not do it for you, and `awaitAnimationEnd` will simply take
  longer otherwise.
- On emulators used in CI, `settings put secure hide_error_dialogs 1` keeps an unrelated
  system ANR from stealing the focused window from your app.
