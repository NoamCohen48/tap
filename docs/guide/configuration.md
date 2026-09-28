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
`connection.attachDevice(...)` (a `suspend` call; device open/use/close live inside `tapScope`,
`tapTest` in JUnit).

## Python + pytest

Each option is an ini value (`pytest.ini`, `pyproject.toml` `[tool.pytest.ini_options]`,
`setup.cfg`) or an environment variable; the environment wins.

| ini | Environment | Meaning | Default |
|---|---|---|---|
| `tap_aut` | `TAP_AUT` | the application under test | **required** |
| `tap_serials` | `TAP_SERIALS` | comma-separated serials; roles map to them in order, starting one device further on per test | any device the server lists |
| `tap_artifacts` | `TAP_ARTIFACTS` | failure artifact directory | `tap-artifacts` |
| `tap_server` | `TAP_SERVER` | `host:port` of a running server | the one in `daemon.json` |
| — | `TAP_TOKEN` | bearer token for an explicit `TAP_SERVER` | the one in `daemon.json` |
| `tap_manage_daemon` | `TAP_MANAGE_DAEMON` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the daemon | `false` |
| `tap_acquire_timeout` | `TAP_ACQUIRE_TIMEOUT` | seconds to wait for a device another session holds | `300` |
| — | `TAP_BIN` | the `tap` executable `tap_manage_daemon` runs | `tap` on `PATH` |
| — | `TAP_STATE_DIR` | state dir shared with the daemon | `~/.tap` |

Fixtures: `tap_device` (the default role), `tap_devices` (dict role → `Device`), plus
`tap_connection`, `tap_client` and `tap_config` for scripts that want the lower layers. Marker:
`@pytest.mark.tap_devices("a", "b")`.

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
