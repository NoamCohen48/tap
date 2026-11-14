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
tap doctor  [--state-dir DIR] [--adb PATH] [--serial SERIAL] [--fix]
tap unquarantine --serial SERIAL [--state-dir DIR] [--adb PATH]
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
| `--scrcpy-server` | a scrcpy **4.1** server JAR for the shared screen video that [Tap watcher](../watcher/index.md) shows; default `$TAP_SCRCPY_SERVER`, otherwise no shared video. Recordings do not need it |
| `--driver-apk`, `--driver-test-apk` | a driver build of your own instead of the one bundled in the executable. Clients cannot pick a driver per attach |
| `status` | prints `running 127.0.0.1:PORT pid=… version=… adb=…` (never the token); exit 1 when no server is running |
| `stop` | terminates the server recorded in `daemon.json` after it answered an authenticated call; a stale file is removed and nothing is signalled |
| `doctor` | checks the server and every device ADB lists (or `--serial`) for what a session that is no longer running left behind; `--fix` cleans it up. See [Cleaning up after a crash](#cleaning-up-after-a-crash) |
| `unquarantine` | puts a quarantined device back in service once you have checked it, after retrying the cleanup; prints why it was quarantined and what is still off. See [The devices](#the-devices) |

One server per machine is the intended setup; every test process on that machine connects to
it. Two servers with different `--state-dir`s are isolated from each other (separate journals
and device locks), which is how Tap's own CI keeps a scratch server apart from `~/.tap`.

## Client and server versions

A client needs a server at least as new as the engine it was built against: the Kotlin client's
`tap-api` version, the Python client's `tap_e2e._gen.engine_version`. It also needs the same
**API compatibility epoch**: newer servers are accepted only when their API remains compatible.
The first `connect` (or Python `resume`) checks both before opening a connection. A missing
or different epoch fails with the reported and required values and instructions to install
matching server/client builds. This includes older daemons that do not report an epoch.
Upgrade the server with `tap stop`, then `tap start` from the matching `tap`. The epoch is
separate from the server-driver wire protocol version and stays unchanged for compatible releases.

## Extracted driver cache

The bundled driver APKs live in `<state-dir>/driver/<version>-<digest>/`. Different builds keep
separate directories; old builds are not deleted automatically because another server may still
be using them. To reclaim space, stop every Tap server using that state directory, then delete
its `driver/` directory (including any older version-only subdirectories). The next start
extracts its bundled APKs again. Leave `sessions/` intact: it holds device locks and recovery
journals, not cached files.

## Attachment timing

Each attachment starts fresh driver instrumentation; detaching stops it and restores the
session's tracked changes. Driver APKs stay installed. Before starting, Tap checks the device's
power state and sends the wake key only when it cannot confirm that the device is already
awake and stable. It still attempts to dismiss an insecure keyguard; a secure lock is not bypassed.

The server log (`<state-dir>/daemon.log` with `tap start`) includes lifecycle stage durations:

```text
[tap] lifecycle serial=85e49002 stage=attach_instrumentation outcome=ok elapsed_ms=2056
```

Stages distinguish lock waiting, boot/recovery, screen preparation, APK checking/installation,
instrumentation startup, forwarding, process identity, authentication, health and detach cleanup.
Durations use a monotonic clock; failed and cancelled stages are reported without request payloads
or error messages. `attach_total` is the host-core open, including its stages, not the entire
client RPC or a value to add to the other durations. An APK installation is a deployment cost,
not the steady-state cost of every attachment.

## Cleaning up after a crash

When the server or a test process dies hard (a `kill -9`, a power cut), the device can keep
what its session had set up: Tap's driver still running, a port forward, a journal that says
the session is active, device settings it changed. The next attach recovers all of this on its
own, so nothing needs doing before a test run. `tap doctor` shows it without waiting for one:

```
$ tap doctor
server: not responding: daemon pid 2283187 at 127.0.0.1:35097 does not answer
  `tap stop` removes the stale daemon.json; `tap start` starts a new server
emulator-5554:
  - a session (generation 314) ended without closing
  - port forward tcp:46397 -> tcp:27183 has no session
85e49002: in use by a session; skipped
run `tap doctor --fix` to clean up what ended sessions left
```

`tap doctor --fix` runs the same recovery an attach would: it stops the driver, removes the
forwards to Tap's device ports (never anyone else's), closes the journal and puts back the
settings the session saved. A device that a live session holds, from any server or process, is
skipped and never touched, so it is safe to run while tests are running. A quarantined device is
reported with its reason and never cleared (`tap unquarantine` does that, your decision), except
one Tap can prove clean again: see [The devices](#the-devices).
`doctor` exits 1 while something is left unfixed or a device is quarantined.

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
- Each attachment checks both installed driver APKs against the server's files and reinstalls
  them if they differ or cannot be verified, including an older build restored from an emulator
  snapshot. An unchanged build is not reinstalled.
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
machine's key), or `QUARANTINED` with a reason. Only `FREE` and `LEASED` devices, and
`QUARANTINED` ones with `retry_on_attach`, can be attached; a `LEASED` one waits until its
holder lets go.

A device is quarantined when a session could not leave it provably clean: the driver could not
be stopped, the journal is corrupt, the boot identity changed under an active session, a
mutation may still be in flight, or a setting the session changed did not read back when it was
put back. That last one Tap can prove later by itself: the device is listed with
`retry_on_attach`, and the next attach (or `tap doctor --fix`) writes the settings again and,
once they read back, uses the device as usual. Any other quarantine keeps the device out of
circulation until you have checked it and put it back yourself: Tap never lifts what it cannot
prove.

```bash
tap doctor                          # lists quarantined devices and why
tap unquarantine --serial 85e49002  # once you have checked the phone
```

To look at a quarantined phone first (or let an agent look and fix it), attach it with
`inspect_quarantined` (Kotlin `DeviceOptions(inspectQuarantined = true)`, Python
`attach_device(serial, inspect_quarantined=True)`, `tap-agent attach SERIAL --quarantined`,
**Inspect** in Tap Studio): screenshots, snapshots, taps and settings work as usual, and Tap still puts
back what sessions changed before the quarantine when you detach. Detaching leaves the device
quarantined for the same reason, so no test picks it up until you unquarantine it. ListDevices
shows it as `under_inspection` meanwhile.

`tap unquarantine` (or `unquarantine(serial)` in the clients) stops any leftover driver,
removes Tap's port forwards and writes back the settings the session saved, then puts the device
back in service and prints what it could not put right. It refuses a device another session
holds.

## What a session puts back

Whatever a session changes on a device through Tap (rotation, [device conditions](device-conditions.md),
app languages, the mock location, [files it pushed](files.md)) is captured before the first
change, journaled, and restored when the device is detached. When the server died first, the
next attach restores it. A restore that does not read back quarantines the device rather than
leaving it changed silently.
