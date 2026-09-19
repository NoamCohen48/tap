# Configuration

Tap has very little to configure: which service to talk to, which app is under test, which
devices to use, and where artifacts go.

## The `tap` CLI

```
tap serve   [--port N] [--state-dir DIR] [--adb PATH] [--serials a,b]
tap status  [--state-dir DIR]
tap stop    [--state-dir DIR]
tap version
```

| | |
|---|---|
| `serve` | runs the service in the foreground on loopback (`--port 0` = ephemeral, the default), writes `<state-dir>/service.json` (`port`, `pid`, `version`, `adb`), and keeps running until `tap stop` |
| `--state-dir` | where `service.json`, `sessions/` (leases and journals) and the extracted driver live; default `$TAP_STATE_DIR` or `~/.tap` |
| `--adb` | the ADB executable; default `$TAP_ADB` or `adb` on `PATH` |
| `--serials` | restrict the pool to these devices; default every device ADB lists |
| `status` | prints `service.json` (exit 1 when no service is running) |
| `stop` | terminates the service recorded in `service.json` |

One service per machine is the intended setup; every test process on that machine connects to
it. Two services with different `--state-dir`s are isolated from each other (separate pools and
leases), which is how the framework's own CI keeps a scratch service apart from `~/.tap`.

## How clients find the service

In order:

1. An explicit address: `tap.service` system property / `TAP_SERVICE` (`host:port`), or the
   pytest `tap_service` option.
2. A live `service.json` in the state dir (`TAP_STATE_DIR`, default `~/.tap`).
3. Otherwise the client starts `tap serve --state-dir <state dir>` itself from `tap.bin` /
   `TAP_BIN` / `tap` on `PATH`, waits for it to be ready, and leaves it running.

Set `TAP_STATE_DIR` for both the service and the clients if you want it anywhere but `~/.tap`.

## Kotlin + JUnit 5

Read once per JVM from system properties, falling back to environment variables with the same
name upper-cased and dotted → underscored (`tap.autPackage` → `TAP_AUTPACKAGE`):

| Property | Meaning | Default |
|---|---|---|
| `tap.autPackage` (or `tap.aut`) | the application under test | **required** |
| `tap.serials` | comma-separated serials to use; roles are pinned to them in order | any pool device |
| `tap.device.<role>` | pin one role to a serial (must be in `tap.serials` when that is set) | — |
| `tap.artifactsDir` | failure artifacts root | `build/tap-artifacts` |
| `tap.acquireTimeoutSeconds` | all-or-none role acquisition timeout | `300` |
| `tap.service` | `host:port` of a running service | discover / auto-start |
| `tap.bin` | the `tap` executable to auto-start | `TAP_BIN`, then `PATH` |

Gradle passes them with `systemProperty(...)` on the test task; a common pattern forwards
`-P` properties:

```kotlin
tasks.test {
    useJUnitPlatform()
    systemProperty("tap.autPackage", "com.shop")
    listOf("tap.serials", "tap.service", "tap.bin").forEach { key ->
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
| `tap_serials` | `TAP_SERIALS` | comma-separated serials; roles pinned in order | any pool device |
| `tap_artifacts` | `TAP_ARTIFACTS` | failure artifact directory | `tap-artifacts` |
| `tap_service` | `TAP_SERVICE` | `host:port` of a running service | discover / auto-start |
| `tap_acquire_timeout` | — | seconds to wait for devices | `120` |
| — | `TAP_BIN` | the `tap` executable to auto-start | `tap` on `PATH` |
| — | `TAP_STATE_DIR` | state dir shared with the service | `~/.tap` |

Fixtures: `tap_device` (the default role), `tap_devices` (dict role → `Device`), plus
`tap_run`, `tap_service` and `tap_config` for scripts that want the lower layers. Marker:
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
