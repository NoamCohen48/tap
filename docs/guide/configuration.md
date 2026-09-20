# Configuration

Tap has very little to configure: which service to talk to, which app is under test, which
devices to use, and where artifacts go.

## The `tap` CLI

```
tap start   [--port N] [--state-dir DIR] [--adb PATH]
tap serve   [--port N] [--state-dir DIR] [--adb PATH]
tap status  [--state-dir DIR]
tap stop    [--state-dir DIR]
tap version
```

| | |
|---|---|
| `start` | starts the service in the background and returns once it answers: prints `started 127.0.0.1:PORT pid=PID`, or `running …` when one is already up in that state dir (nothing is started twice). Its output goes to `<state-dir>/service.log`; exit 1 if it died or never became ready |
| `serve` | the same service in the foreground (`--port 0` = ephemeral, the default); what `start` runs for you |
| both | bind loopback only, write `<state-dir>/service.json` (`port`, `pid`, `version`, `adb`), and keep running until `tap stop` |
| `--state-dir` | where `service.json`, `sessions/` (leases and journals) and the extracted driver live; default `$TAP_STATE_DIR` or `~/.tap` |
| `--adb` | the ADB executable; default `$TAP_ADB` or `adb` on `PATH` |
| `status` | prints `service.json` (exit 1 when no service is running) |
| `stop` | terminates the service recorded in `service.json` |

One service per machine is the intended setup; every test process on that machine connects to
it. Two services with different `--state-dir`s are isolated from each other (separate journals and
device locks), which is how the framework's own CI keeps a scratch service apart from `~/.tap`.

## How clients find the service

In order:

1. An explicit address: `tap.service` system property / `TAP_SERVICE` (`host:port`), or the
   pytest `tap_service` option.
2. A live `service.json` in the state dir (`TAP_STATE_DIR`, default `~/.tap`) — what
   `tap start` wrote.
3. Otherwise the client fails with *no running tap service; run `tap start`*.

Clients never start a service themselves. Starting is explicit, and there are three ways to do it:

- **By hand or in a CI step**: `tap start` before the tests, `tap stop` after. The service is
  shared by every test process on the machine, in any language.
- **From the test runner**: `tap.manageService=true` (JUnit) / `tap_manage_service = true`
  (pytest). The runner runs `tap start` before its first session and `tap stop` when the run
  ends — but only if that start created the service; one that was already running is left
  running. `tap.bin` / `TAP_BIN` says which executable to use (default `tap` on `PATH`).
- **From code**: Kotlin `TapServiceProcess.start()` / `stop()`, Python `tap.start_service()` /
  `tap.stop_service()`. Both return whether the call started the service.

Set `TAP_STATE_DIR` for both the service and the clients if you want it anywhere but `~/.tap`.

## Kotlin + JUnit 5

Read once per JVM from system properties, falling back to environment variables with the same
name upper-cased and dotted → underscored (`tap.autPackage` → `TAP_AUTPACKAGE`):

| Property | Meaning | Default |
|---|---|---|
| `tap.autPackage` (or `tap.aut`) | the application under test | **required** |
| `tap.serials` | comma-separated serials to use; roles map to them in order | any device the service lists |
| `tap.device.<role>` | pin one role to a serial (must be in `tap.serials` when that is set) | — |
| `tap.artifactsDir` | failure artifacts root | `build/tap-artifacts` |
| `tap.acquireTimeoutSeconds` | how long to wait for a device another session holds | `300` |
| `tap.service` | `host:port` of a running service | the one in `service.json` |
| `tap.manageService` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the service | `false` |
| `tap.bin` | the `tap` executable `tap.manageService` runs | `TAP_BIN`, then `PATH` |

Gradle passes them with `systemProperty(...)` on the test task; a common pattern forwards
`-P` properties:

```kotlin
tasks.test {
    useJUnitPlatform()
    systemProperty("tap.autPackage", "com.shop")
    listOf("tap.serials", "tap.service", "tap.manageService", "tap.bin").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
```

Timeouts are per device, not global: `Timeouts(action = 10.seconds, wait = 10.seconds,
lifecycle = 30.seconds, pollInterval = 100.milliseconds)` is the default, and every method also
takes an explicit `timeout`. With the SDK directly, pass `Timeouts` and `DeviceOptions` to
`run.openDevice(...)`.

## Python + pytest

Each option is an ini value (`pytest.ini`, `pyproject.toml` `[tool.pytest.ini_options]`,
`setup.cfg`) or an environment variable; the environment wins.

| ini | Environment | Meaning | Default |
|---|---|---|---|
| `tap_aut` | `TAP_AUT` | the application under test | **required** |
| `tap_serials` | `TAP_SERIALS` | comma-separated serials; roles map to them in order | any device the service lists |
| `tap_artifacts` | `TAP_ARTIFACTS` | failure artifact directory | `tap-artifacts` |
| `tap_service` | `TAP_SERVICE` | `host:port` of a running service | the one in `service.json` |
| `tap_manage_service` | `TAP_MANAGE_SERVICE` | `true` = `tap start` before the first test, `tap stop` after the last one if that start created the service | `false` |
| `tap_acquire_timeout` | `TAP_ACQUIRE_TIMEOUT` | seconds to wait for a device another session holds | `120` |
| — | `TAP_BIN` | the `tap` executable `tap_manage_service` runs | `tap` on `PATH` |
| — | `TAP_STATE_DIR` | state dir shared with the service | `~/.tap` |

Fixtures: `tap_device` (the default role), `tap_devices` (dict role → `Device`), plus
`tap_connection`, `tap_service` and `tap_config` for scripts that want the lower layers. Marker:
`@pytest.mark.tap_devices("a", "b")`.

## Session options

Both `run.openDevice(...)` / `run.open_device(...)` accept:

| Option | Meaning |
|---|---|
| `driverApk`, `driverTestApk` | use these driver APKs instead of the ones bundled in the service |
| `skipDriverInstall` | assume the driver is already installed (CI images with a pre-provisioned driver) |
| `syncAuthority` | the app's sync provider authority when it is not `<package>.tap-sync` |
| `allowedSystemPackages` | extra system packages selectors may opt into (default: the permission controller) |

The JUnit extension and the pytest plugin use the defaults; override them only from a script or
a custom fixture.

## Devices

- API 26 and up, emulator or physical, visible to `adb devices` as `device`.
- Turn off animations on physical devices you use routinely (`settings put global
  *_animation_scale 0`); Tap does not do it for you, and `awaitAnimationEnd` will simply take
  longer otherwise.
- On emulators used in CI, `settings put secure hide_error_dialogs 1` keeps an unrelated
  system ANR from stealing the focused window from your app.
