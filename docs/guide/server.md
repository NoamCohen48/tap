# The tap server

Every client, whether a test in Kotlin or Python, `tap-agent`, Tap Studio or `tap-watcher`, talks to one
server per machine: `tap`. It owns ADB, installs Tap's driver on each device, and keeps a device
held by one client at a time. You start it; clients never do.

## The `tap` command

```
tap start   [--port N] [--state-dir DIR] [--adb PATH] [--scrcpy PATH] [--scrcpy-server JAR] [--driver-idle-timeout-ms N]
tap serve   [--port N] [--state-dir DIR] [--adb PATH] [--scrcpy PATH] [--scrcpy-server JAR] [--driver-apk APK --driver-test-apk APK] [--driver-idle-timeout-ms N]
tap status  [--state-dir DIR] [--json] [--watch] [--interval SECONDS]
tap stop    [--state-dir DIR]
tap restart [startup options] [--force]
tap devices [--state-dir DIR] [--json] [--watch] [--interval SECONDS]
tap devices show SERIAL [--state-dir DIR] [--json]
tap sessions [--state-dir DIR] [--json] [--watch] [--interval SECONDS]
tap sessions show SESSION_ID [--state-dir DIR] [--json]
tap sessions close SESSION_ID --force [--reason TEXT] [--state-dir DIR] [--json]
tap logs [--state-dir DIR] [--tail N] [--follow]
tap logs driver --serial SERIAL [--state-dir DIR] [--json]
tap config show [startup options] [--json]
tap diagnostics --out PATH [--state-dir DIR]
tap completion bash|zsh|fish
tap doctor  [--state-dir DIR] [--adb PATH] [--serial SERIAL] [--fix] [--json]
tap unquarantine --serial SERIAL [--state-dir DIR] [--adb PATH]
tap config logging-policy show [--state-dir DIR]
tap config logging-policy rolling [--max-age 1h] [--max-size 256MiB] [--hard-size 1GiB] [--min-free 256MiB] [--state-dir DIR]
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
| `--driver-idle-timeout-ms` | experimental clean instrumentation reuse: `0` (default) disables it; `1000`–`300000` keeps a clean runtime for that many milliseconds after detach, without reserving the device |
| `--driver-apk`, `--driver-test-apk` | a driver build of your own instead of the one bundled in the executable. Clients cannot pick a driver per attach |
| `status` | reports the authenticated daemon's endpoint, PID, running and installed versions and API compatibility (never the token); exit 1 when stopped, unreachable, stale or incompatible |
| `stop` | terminates the server recorded in `daemon.json` after it answered an authenticated call; a stale file is removed and nothing is signalled |
| `doctor` | checks the server and every device ADB lists (or `--serial`) for what a session that is no longer running left behind; `--fix` cleans it up. See [Cleaning up after a crash](#cleaning-up-after-a-crash) |
| `unquarantine` | puts a quarantined device back in service once you have checked it, after retrying the cleanup; prints why it was quarantined and what is still off. See [The devices](#the-devices) |

One server per machine is the intended setup; every test process on that machine connects to
it. Two servers with different `--state-dir`s are isolated from each other (separate journals
and device locks), which is how Tap's own CI keeps a scratch server apart from `~/.tap`.

## Help and exploration

`tap`, `tap --help`, `tap -h` and `tap help` print top-level help and exit successfully.
Every command supports `--help` / `-h`, including nested commands; `tap help sessions close`
is equivalent to `tap sessions close --help`. Help does not require a daemon, ADB or an
interaction client, and never creates state. `tap --version` is an alias for `tap version`.

```bash
tap start
tap devices                    # serials, availability, holding session IDs
tap devices show SERIAL        # properties and quarantine reason
tap sessions                   # live clients and their devices
tap sessions show SESSION_ID   # client name, creation/activity times, mode and in-flight calls
```

A **session** here is a daemon-held client connection: one client may attach several devices.
Use its complete ID, not its client name, for details or closure. IDs differ from the wire
session generations shown for individual attachments. A device held outside this daemon may
have no session ID in the device list.

These commands call the daemon directly. They do not need `tap-agent`, attach a device,
renew a held session or start a missing daemon. For interaction, use a separately installed
[Tap agent](../agent/index.md), a [Kotlin/Python SDK](../sdk/index.md) or [Tap Studio](../studio/index.md).
For example, after installing `tap-agent` separately:

```bash
tap-agent attach SERIAL
tap-agent snapshot
tap-agent release
```

`--state-dir DIR` may precede or follow the command. Unknown, repeated and inappropriate
options are errors. Help and successful output go to stdout; usage errors go to stderr with
exit 2. Operational failures exit 1. JSON inspection, including failures, goes to stdout;
`serve` exits 3 when its state-directory lock is held.

### Close a session or restart

Closing another client's session interrupts that client and cleans up all of its devices:

```bash
tap sessions close SESSION_ID --force --reason 'stuck test'
tap restart                    # refuses when sessions are active
tap restart --force            # explicitly interrupt all clients
tap restart --force --port 0   # choose a new port
```

Closure uses the normal bounded cleanup path. It reports per-device cleanup failures or
timeouts and exits 1 if any remain, even though the session has closed. It never clears
quarantine or bypasses restoration checks. Inspect `tap doctor` before returning a device
to service. A lost close response does not trigger a retry: inspect `tap sessions` and
`tap doctor` to determine the outcome. The optional reason is recorded in lifecycle logs and
sent to observed clients; keep credentials out of it.

Restart preserves the running daemon's startup flags, including ADB, scrcpy, driver overrides
and runtime reuse; explicit flags override them. It uses the installed executable and its
bundled driver when no override was configured. Restart requires a live daemon that reports
its startup settings; older builds need explicit `stop` / `start`. The active-session check
is a preflight, not a lock against other clients connecting: coordinate restarts with tests.
`tap stop` retains its existing unconditional shutdown behavior.

### Watch and structured output

`status`, `devices` and `sessions` support `--watch`, with `--interval SECONDS` (default 2,
range 0.1–3600). They reconnect on each poll and keep watching through daemon outages.
Interrupt with Ctrl-C. Watching does not renew sessions.

`--json` is available on those commands, detail views, session closure, `doctor` and
`config show`. Watch mode prints newline-delimited JSON: one complete snapshot or error per
line with `observed_at`. Other JSON commands print one object. Epoch timestamps are in
milliseconds; in-flight calls keep a session's reported idle time at zero.

### Configuration, logs and diagnostics

`tap logs` reads daemon.log. `tap logs driver --serial SERIAL` instead reads the original
currently attached driver's bounded instrumentation buffer through the authenticated daemon.
It requires a unique existing attachment and never creates a session, attaches a device or
runs `tap-agent`. This buffer is not a durable post-detach archive. For retained device logcat
and activity use `tap-agent history --logs`; for an explicit current-state bundle use `capture`.

```bash
tap config show --json
tap logs --tail 200 --follow
tap logs driver --serial SERIAL
tap diagnostics --out tap-diagnostics.zip
```

`config show` distinguishes the **running** daemon's settings from **next_start**, the values
this invocation would use, with their sources (command line, named environment variable or
default). Startup flags can be supplied to inspect next-start resolution without starting
or reconfiguring anything. It exposes only known configuration fields, never tokens or an
environment dump. If a daemon cannot be contacted, local defaults are still shown with a
warning and exit 1; no descriptor means local inspection succeeds.

`logs` reads `<state-dir>/daemon.log`, defaulting to the last 100 lines (`--tail 0..10000`).
Reads are bounded to the final 1 MiB. `--follow` waits for log creation and follows complete
lines, reopening after detected replacement or truncation. `serve` writes to its terminal,
not this file. Ordinary log viewing is not a sanitized export.

`diagnostics` creates a new ZIP, owner-only on POSIX filesystems, and refuses existing files.
It works even while the daemon is stopped. The archive includes health, allowlisted
configuration, device/session discovery or their errors, and only recognized lifecycle timing
lines from the last 200 log lines. It excludes descriptor credentials, environment dumps,
journals, screenshots, command payloads and arbitrary free-form logs. Known bearer secrets
are redacted. **Review before sharing:** device serials, client names and local paths remain.

`doctor` also checks ADB executable availability, state-directory access, daemon connectivity
and running driver availability. It exits 1 for setup/connectivity failures as well as device
cleanup findings. It repairs device leftovers only with `--fix`; live sessions are skipped.

### Shell completion

Generate static completions; completing a word never contacts the daemon:

```bash
tap completion bash > ~/.tap-completion.bash
source ~/.tap-completion.bash

tap completion zsh > /a/directory/on/your/fpath/_tap
# Enable completion with autoload -Uz compinit; compinit in your zsh configuration.

tap completion fish > ~/.config/fish/completions/tap.fish
```

Create the target directories first. Add the bash `source` line to your shell configuration
to load it in future shells. Bash completion supports Bash 3.2 and newer.

## Device-log retention

Device logs are captured automatically while attached and retained after detach/restart.
`tap config logging-policy show` displays the selected state directory's effective policy;
`rolling` updates any supplied limits atomically, including while the daemon is stopped:

```sh
tap config logging-policy rolling --max-age 1h --max-size 256MiB --hard-size 1GiB --min-free 256MiB
```

Those are the defaults. Age units are `s`, `m`, `h`, `d`; size units are bytes, `B`, `KiB`,
`MiB`, `GiB`. Limits must be positive, except `--min-free` may be zero; hard size must not be
smaller than rolling size. Append `--state-dir DIR` to select another daemon's configuration.
The command does not start a server. Live capture reloads settings on append/read and during
maintenance every minute, without restarting logcat.

Rolling limits are global across ordinary log history, not multiplied per device. Named
recordings protect their range until explicit deletion, even after ordinary age/size eviction.
They still obey the hard storage budget and free-space safeguard; missing capture is reported,
never silently replaced by success. The private files live under `history-logs/` in this state
directory; policy settings are in `logging-policy.properties`. Keep the directory private and
inspect redacted evidence before sharing. Media and exported copies are separate artifacts.

See [Device logs](logs.md#retention-and-protected-recordings) for SDK controls and
[Agent kept ranges](../agent/export.md#kept-ranges) for intentional reproduction capture.

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

## Driver runtime reuse (experimental)

To avoid restarting instrumentation between independent tests, opt in when starting the server:

```sh
tap stop
tap start --driver-idle-timeout-ms 60000
```

Restart to change the setting: `tap start` does not reconfigure an already-running server.
Each attachment still gets a fresh session generation and credentials, restores Tap-managed
changes on detach, and releases the device lock. Only the process and its verified forward
may remain, for at most the configured idle interval. The idle runtime has no automation
connection or input pipeline; it does not reserve the device. Another process can acquire it.
This does not reset the app's data or share a test session.

The default attachment policy is `auto`: use a verified clean runtime when enabled, otherwise
start cold. To force a new process (also stopped on detach):

```python
connection.attach_device("85e49002", driver_start="cold")
```

Kotlin: `DeviceOptions(driverStart = DriverStart.COLD)`. `skip_driver_install` /
`skipDriverInstall` is separate: avoiding installation does not force or prevent process reuse.
New handles expose `driver_reused` / `driverReused`; older servers and resumed held-connection
handles may report `None` / `null` because startup provenance is unavailable.

Reuse is refused after poison, uncertain cleanup, failed restoration, inspection, startup fault
overrides or synchronization calls. Changed builds, boot/process/forward identity or rotation
baseline take the cold path. No transmitted mutation is retried. The explicit disconnect bridge
has been validated on API 29 and API 34; if reset is unsupported, Tap proves cold cleanup instead.
A held agent connection still retains its attachment until release/its own timeout; this runtime
idle interval starts only after detach, not between CLI calls within that held session.

## Attachment timing

With runtime reuse disabled, each attachment starts fresh instrumentation and detach stops it.
Driver APKs stay installed. Before starting, Tap checks the device's
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
not the steady-state cost of every attachment. The startup log also reports `mode=cold` or
`mode=reused`, and warm detach includes `detach_runtime_reset`.

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
once they read back, uses the device as usual. A device whose open session was recorded by
another Tap engine (a newer release or another build, whose journal has fields this one does
not know) is listed as quarantined too, but its journal is left as it is: attach it with that
engine to recover it, or run `tap unquarantine`. A closed journal of another engine is no
obstacle. Any other quarantine keeps the device out of
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
