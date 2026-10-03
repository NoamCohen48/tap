# The tap server

Every client, whether a test in Kotlin or Python, `tap-agent`, Tap Studio or `tap-watcher`, talks to one
server per machine: `tap`. It owns ADB, installs Tap's driver on each device, and keeps a device
held by one client at a time. You start it; clients never do.

## The `tap` command

```
tap start   [--port N] [--state-dir DIR] [--adb PATH] [--scrcpy PATH] [--scrcpy-server JAR]
tap serve   [--port N] [--state-dir DIR] [--adb PATH] [--scrcpy PATH] [--scrcpy-server JAR] [--driver-apk APK --driver-test-apk APK]
tap status  [--state-dir DIR]
tap stop    [--state-dir DIR]
tap version
```

| | |
|---|---|
| `start` | starts the server in the background and returns once it answers: prints `started 127.0.0.1:PORT pid=PID`, or `running …` when one is already up in that state dir (nothing is started twice). Its output goes to `<state-dir>/daemon.log`; exit 1 if it died or never became ready |
| `serve` | the same server in the foreground (`--port 0` = ephemeral, the default); what `start` runs for you |
| both | bind loopback only, write `<state-dir>/daemon.json` (`port`, `pid`, `token`, `daemonVersion`, `adb`; readable only by you), and keep running until `tap stop`. One server per state dir: a second `serve` exits 3 |
| `--state-dir` | where `daemon.json`, `sessions/` (leases and journals) and the extracted driver live; default `$TAP_STATE_DIR` or `~/.tap` |
| `--adb` | the ADB executable; default `$TAP_ADB` or `adb` on `PATH` |
| `--scrcpy` | the [scrcpy](https://github.com/Genymobile/scrcpy) executable, used only by [recordings](artifacts.md#recordings); default `$TAP_SCRCPY` or `scrcpy` on `PATH`. Tap runs it with its own ADB |
| `--scrcpy-server` | a scrcpy **4.1** server JAR for the shared screen video that [`tap-watcher`](../watcher/index.md) shows; default `$TAP_SCRCPY_SERVER`, otherwise no shared video. Recordings do not need it |
| `--driver-apk`, `--driver-test-apk` | a driver build of your own instead of the one bundled in the executable. Clients cannot pick a driver per attach |
| `status` | prints `running 127.0.0.1:PORT pid=… version=… adb=…` (never the token); exit 1 when no server is running |
| `stop` | terminates the server recorded in `daemon.json` after it answered an authenticated call; a stale file is removed and nothing is signalled |

One server per machine is the intended setup; every test process on that machine connects to
it. Two servers with different `--state-dir`s are isolated from each other (separate journals
and device locks), which is how Tap's own CI keeps a scratch server apart from `~/.tap`.

## Who starts it

Starting is explicit, and there are three ways to do it:

- **By hand or in a CI step**: `tap start` before the tests, `tap stop` after. The server is
  shared by every test process on the machine, in any language.
- **From the test runner**: `TAP_MANAGE_DAEMON=true` (pytest ini `tap_manage_daemon`, JUnit
  `tap.manageDaemon`). The runner runs `tap start` before its first session and `tap stop` when
  the run ends, but only if that start created the server: one that was already running is left
  running. `TAP_BIN` says which executable to use (default `tap` on `PATH`).
- **From code**: Python `tap_e2e.start_daemon()` / `stop_daemon()`, Kotlin
  `TapDaemonProcess.start()` / `stop()`. Both report whether the call started the server.

## How clients find it

In order:

1. An explicit address: `TAP_SERVER` (`host:port`; pytest ini `tap_server`, JUnit
   `tap.server`).
2. A live `daemon.json` in the state dir (`TAP_STATE_DIR`, default `~/.tap`): what `tap start`
   wrote.
3. Otherwise the client fails with *no running tap daemon; run `tap start`*.

Every call carries the server's bearer token. With a discovered server the client reads it from
`daemon.json` (only the user running the server can read that file); with an explicit address it
comes from `TAP_TOKEN` (JUnit: `tap.token` too). A wrong or missing token fails every call with
`UNAUTHENTICATED`. Set `TAP_STATE_DIR` for both the server and the clients if you want it
anywhere but `~/.tap`.

## The devices

- API 26 and up, emulator or physical, visible to `adb devices` as `device`.
- The driver is installed on each device the first time a session opens there.
- On emulators used in CI, `adb shell settings put secure hide_error_dialogs 1` keeps an
  unrelated system ANR from stealing the focused window from your app.

The device list is one call away:

```python
from tap_e2e import TapClient

for d in TapClient.create().devices():
    print(d.serial, d.state.name, d.client_connection_id, d.quarantine_reason)
```

Each device is `FREE`, `LEASED` (with the connection holding it, when it is one of this
server's), `OFFLINE`, `UNAUTHORIZED` (ADB lists it but the device has not accepted this
machine's key), or `QUARANTINED` with a reason. Only `FREE` and `LEASED` devices can be
attached; a `LEASED` one waits until its holder lets go.

A device is quarantined when a session could not leave it provably clean: the driver could not
be stopped, the journal is corrupt, the boot identity changed under an active session, or a
mutation may still be in flight. It stays out of circulation until you have checked the device
and removed its journal record from `~/.tap/sessions/` (one `<base64url(serial)>.json` per
device). Nothing is retried or reset behind your back.

## What a session puts back

Whatever a session changes on a device through Tap (rotation, [device conditions](device-conditions.md),
app languages, the mock location, [files it pushed](files.md)) is captured before the first
change, journaled, and restored when the device is detached. When the server died first, the
next attach restores it. A restore that does not read back quarantines the device rather than
leaving it changed silently.
