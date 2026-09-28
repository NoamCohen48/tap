# Host daemon server (`tap serve`) and the `tap.v1` API

Status: implemented (`:host:daemon`, `contracts/proto/*.proto`). Revised 2026-09-26 for the
code-review fixes (token auth, `*Service` names, ownership checks, streamed install and
screenshot bytes, host-internal ops removed from the API). That revision passed the JVM suites,
the Kotlin fixture suite (13 tests) and the Python suite against the native image (49 tests),
and `host --no-reboot`, on emulator-5554 (API 34) and 85e49002 (API 29). The device wire protocol
is documented in `protocol-contract.md`.

## 1. Purpose

Every language binding needs the host session layer: the ADB control plane, driver install and
start, port forwarding, journals, machine-wide per-device locks, orphan recovery, quarantine
and the device list. That layer is implemented once, in Kotlin (`:host:core`), and exposed by a
small local daemon over gRPC. Every client, including the Kotlin SDK/JUnit extension, is a
generated client plus ergonomics. The server also owns the `DriverClient` connections, so
bindings never touch framing, HMAC, request IDs or blobs.

## 2. Process model

```text
pytest / script  --gRPC (loopback, bearer token)-->  tap serve  --ADB + TAP1-->  driver  -->  AUT
JUnit (Kotlin)   --gRPC (loopback, bearer token)-->  (same server, same devices)
```

- `tap serve [--port N] [--state-dir DIR] [--adb PATH] [--driver-apk APK --driver-test-apk APK]`
  - Takes an exclusive lock on `<state-dir>/daemon.lock`. If another server holds it, `serve`
    exits 3. One server per state dir.
  - Binds `127.0.0.1` only.
  - Generates a random per-instance token (32 bytes, hex).
  - Writes `<state-dir>/daemon.json` = `{"port","pid","token","daemonVersion","adb"}`
    atomically, mode 0600.
  - On exit it removes the descriptor only if it still carries its own token.
  - The state dir defaults to `TAP_STATE_DIR` or `~/.tap`. Journals and device locks live in
    `<state-dir>/sessions`, the same root the `host` validation executable uses.
  - Options are validated per command, and unknown or repeated options are rejected. The two
    driver APK flags go together: they replace the bundled driver with a local build.
- `tap start [same options]` is the only thing that spawns a server; **clients never start one**
  (`daemon-startup.md`).
  - If a live, token-verified server is running, it prints `running 127.0.0.1:PORT pid=…` and
    spawns nothing.
  - Otherwise it re-executes itself as `serve --port N` detached, with output to
    `<state-dir>/daemon.log` (0600). The re-exec forwards the JVM's own arguments.
  - It then polls an authenticated `Info` until that answers, the child exits, or 30 s pass.
  - If the child lost the lock race to another `start`, it waits for the winner and reports it.
- `tap status` prints `running 127.0.0.1:PORT pid= version= adb=` and never the token, or exits 1.
- `tap stop` signals the pid only after an `Info` authenticated with the descriptor's token
  answered. A stale descriptor is removed and nothing is signalled, so a recycled pid is never
  killed.
- `tap version`.
- Like the ADB server, a started daemon stays up until `tap stop` and every client may share
  it. The clients expose the pair as `TapDaemonProcess.start()/stop()` (Kotlin) and
  `tap.start_daemon()/stop_daemon()` (Python). The test runners run it around a whole run when
  told to (`tap.manageDaemon` / `tap_manage_daemon`), and stop only a server they started.
- **Authentication:**
  - Every RPC must carry `authorization: Bearer <token>`. `TokenAuthInterceptor` rejects
    anything else with `UNAUTHENTICATED`, comparing in constant time.
  - Clients read the token from `daemon.json` in the same state dir, or from `TAP_TOKEN` /
    `tap.token` together with an explicit endpoint.
  - The descriptor is owner-only, so another local user cannot drive the devices. The
    per-session driver secret never leaves the server.
- **Driver APKs:**
  - The driver APKs are embedded as resources and extracted once per build id to
    `<state-dir>/driver/<DRIVER_APK_BUILD_ID>/` (`DriverApks.extractBundled`).
  - On the first attachment per serial per process, the daemon installs them unless the client
    passes `skip_driver_install`. If the installed driver's `versionName` is a different build
    id, it installs again.
  - When the driver handshake reports a build mismatch, the serial is re-installed on the next
    attach.
  - Clients never send host paths: there is no per-attach APK override.
- `adb` is resolved from `--adb`, `TAP_ADB`, or `PATH`. Every ADB call is serial-specific.
- **Builds:**
  - `:host:daemon:installDist` produces the JVM distribution (`host/daemon/build/install/tap/bin/tap`).
  - `:host:daemon:nativeCompile` produces the GraalVM native image
    (`host/daemon/build/native/nativeCompile/tap`).
  - Reachability metadata lives under `host/daemon/src/main/resources/META-INF/native-image/`.

## 3. API (`contracts/proto/`, package `tap.v1`)

The proto files are the single source of truth. Kotlin stubs are generated at build time, and
Python stubs are generated by `clients/python/scripts/gen_stubs.py` and committed. The file map
is in `contracts/api/README.md`. Every RPC has its own `<Rpc>Request`/`<Rpc>Response` messages
(buf `STANDARD` lint; only `ENUM_VALUE_PREFIX` and `PACKAGE_DIRECTORY_MATCH` are excepted).

### ClientConnectionService: client identity and liveness

| RPC | Semantics |
|---|---|
| `Connect(name, hold?)` → `client_connection_id` | A connection is one client process. Every attached device belongs to it. With `hold{idle_timeout_ms}` (1 s..24 h) it is a **held** connection (`agent-surface.md`): no Observe stream (opening one is `FAILED_PRECONDITION`), not reaped by the 30 s observe grace, ends on `Disconnect`, daemon shutdown, or `idle_timeout_ms` after the last call that named it. A held connection's name must be non-blank and unique among held connections (`FAILED_PRECONDITION`, `DAEMON_PRECONDITION`). |
| `Observe(client_connection_id)` → stream `ObserveResponse` | Liveness. Events are a `oneof`: `observing` first, then `heartbeat` every 15 s (idle-proxy traffic, not a death detector). A daemon-side disconnect (Disconnect, reaping, shutdown) sends `closing{reason}` and completes the stream normally. **When the stream ends for any reason, the client is disconnected** and every owned device is detached. A second concurrent Observe is `FAILED_PRECONDITION`. |
| `Disconnect(client_connection_id)` → `{attached_devices_detached}` | Explicit teardown. |
| `Info()` | Daemon version, host build id, protocol version, adb path, state dir, `driver_available`, `pid`. |
| `ListConnections()` → `repeated ConnectionEntry` | Every live connection: id, name, `hold` when held, `idle_ms` since the last call naming it, and its attached devices (id, serial, AUT package, generation). How a later process (`tap` CLI) finds a held connection by name. |
| `Events(client_connection_id, after_seq)` → `{repeated LoggedEvent events, dropped}` | The connection's event log (`event_log.proto`), events with `seq > after_seq`, oldest first. Kept for every connection while it lives, the last 2000 (`dropped` counts evictions). Logged: every `Execute` except `device_info` / `dump_hierarchy` (the command as sent), and install / uninstall / force-stop / clear-data / grant / launch / cold launch (`AppCall{operation, package_name, activity?, permission?, timeout_ms?}`), each with serial, AUT, start, duration, and `error` (driver `Error`) or `failure` (the RPC's `Failure`). Calls rejected before running (invalid argument, unknown or foreign device) and cancelled calls are not logged. Renews a held connection. Unknown connection: `NOT_FOUND`. |

A connection whose Observe is not open 30 s after `Connect` is reaped. A client that crashes
between Connect and Observe therefore cannot leak a connection.

### DeviceService: inventory and attached devices

There is no lease RPC. A device is in use exactly while an attached device's `DeviceSession`
holds its per-serial file lock (`<state-dir>/sessions/<serial>.lock`, `pool-and-leases.md`).
Roles, device choice and multi-device ordering are client concerns.

Every call on an attached device names the owning `client_connection_id`. A call from another
connection is `PERMISSION_DENIED`, and an unknown id is `NOT_FOUND`.

| RPC | Semantics |
|---|---|
| `ListDevices()` → `repeated DeviceEntry` | Every device ADB lists. Its `state` is one of: <br>• `DEVICE_FREE` <br>• `DEVICE_LEASED`, with `client_connection_id` when the holder is this daemon <br>• `DEVICE_QUARANTINED`, with `quarantine_reason`; an unreadable journal is quarantined with reason `journal unreadable: …` <br>• `DEVICE_UNAUTHORIZED` <br>• `DEVICE_OFFLINE`, which also covers any other non-`device` ADB state |
| `Attach(client_connection_id, serial, aut_package, skip_driver_install?, sync_authority?, default_timeout_ms?, lease_timeout_ms?)` → `{attached_device_id, serial, generation, device_info}` | Takes the per-serial lock. If it is held, the call fails `FAILED_PRECONDITION` at once, or waits up to `lease_timeout_ms` and then fails `DEADLINE_EXCEEDED`. It then installs the driver if needed, starts it with retry and returns `DEVICE_INFO`. If that first query fails, it detaches before returning. `default_timeout_ms` (absent = 10 s) applies when a `Command.timeout_ms` is absent. |
| `Execute(…, command)` → `{result: CommandResult}` | One protocol request. **Driver failures are data**: `outcome = error {code, detail?, message?, match_count?}`. Transport loss after transmission is also data: `TRANSPORT_LOST`, or `INDETERMINATE` for a transmitted mutation. Nothing is ever replayed. Cancelling the gRPC call forwards a protocol `CANCEL`; the driver honours it only before the mutation gate. |
| `Screenshot(…, timeout_ms?)` → `{png, sha256, width?, height?}` | The verified PNG bytes. Writing a file is the client's job; the server takes no host path. |
| `DriverLog(…)` | The instrumentation's stdout ring buffer (last 2 000 lines). |
| `ScreenSnapshot(…, timeout_ms?)` → `{snapshot_id, nodes, removed, rotation}` | A compact outline parsed from the diagnostic hierarchy dump (every window root, visible nodes, pre-order). Each `ScreenNode` has a ref `eN`, depth, window package, class, resource name, text, description, hint, bounds, the true flags, `interactive`, and a selector the daemon synthesised that matched only this node in the dump (absent when none; `by_index` when it needed an `At` pick). Refs are aligned with the device's previous snapshot: unchanged nodes keep their ref and are `NODE_UNCHANGED`, new ones get fresh numbers and are `NODE_ADDED`, gone ones are listed in `removed`. A ref is never reused for another node. |
| `ResolveRef(…, ref)` → `{selector, by_index, snapshot_id}` | The selector a ref of the latest snapshot names; the caller sends it in an ordinary `Execute`, where the driver still demands exactly one match. Unknown ref: `NOT_FOUND` / `UNKNOWN_REF`. A node without a selector: `FAILED_PRECONDITION` / `REF_NOT_ADDRESSABLE`. |
| `Detach(…)` → `{clean, detail?}` | `clean=false` means cleanup timed out or the session was quarantined, and `detail` says why. |

`Command`:

- It is `optional timeout_ms` (absent = the attachment's default) plus a `oneof op` with one
  message per **public** protocol command.
- The host-internal ops — `health`, `screenshot`, `sync_bootstrap`, `sync_poll` — are not
  `Command` cases: they exist only in the internal `tap.wire.v1.Request` body. Their old field
  numbers are reserved, and the `artifact`/`sync` outcomes likewise. Screenshot has its own RPC,
  and sync is driven by `AwaitIdle`.
- `Command` and `CommandResult` are the device wire messages themselves
  (`.docs/protocol-contract.md`): `Execute` validates the command (`CommandValidation`, the same
  code the driver runs; a failure is `INVALID_ARGUMENT`), wraps it in a wire `Request` and
  returns the driver's `CommandResult` unchanged. No conversion layer exists.
- Optional fields take their default on the driver only (`distance_percent` = 80, …); the server never fills them in.

`CommandResult` is `duration_ms`, the driver's `request_id`/`session_generation`, and a
`oneof outcome` of the public result kinds or `error`.

`Selector`, `Node`, `ResourceId`, `ElementSnapshot`, `DeviceInfo` and the enums are likewise the
wire types:

- The sum types are `oneof`s.
- Enum values carry a prefix (`ERR_`, `DIR_`, …) plus a `*_UNSPECIFIED` zero value. Validation
  rejects that zero value (`INVALID_ARGUMENT`) where the field has no default, and any value this
  build does not know.
- `ResourceId.aut_package = true` is resolved by the driver to the attached device's AUT package.
- Protocol 4.0 (2026-09-28) removed `ScrollUntil` (`Command` field 21), `CommandResult.moved`
  (6), `AttachRequest.allowed_system_packages` (6) and `TypeText.selector` (1); all are
  `reserved`. `TypeText` types into the current focus; the SDKs' element `typeText` taps,
  waits for focus, then sends it. It added the
  `AnyWindowScope any_window` selector scope and `ElementSnapshot.showing_hint`. The clients'
  `scrollUntil` / `scroll_until` are client-side loops of `Exists` + `Scroll`.

### AppService: AUT lifecycle

Each request carries an `AppTarget{client_connection_id, attached_device_id, package_name}` and has
its own response message. The RPCs are:

- `Install` is client-streaming.
  - The first message is an `InstallHeader{app, timeout_ms?, size_bytes}`; every later message
    is a `chunk`.
  - The server spools the upload to an owner-only file under `<state-dir>/uploads` and rejects
    a missing or repeated header, a size outside 1 B..1 GiB, and any mismatch with `size_bytes`.
  - It then installs the file and deletes it.
- `Uninstall`, `IsInstalled`, `ForceStop`, `ClearData`, `GrantPermission`.
- `Launch` and `ColdLaunch` take an optional `activity`, where absent means the launcher.
  `ColdLaunch` returns `ProcessIdentity{pid, start_token}`, a verified new process. Both
  return once `am start -W` does; they do not wait for the app's window (a client that needs
  it calls `WAIT_APP_VISIBLE` / `WAIT_SCREEN_STABLE`).
- `Process`, `IsRunning`.
- `AwaitIdle(stable_for_ms?)`, where the default is 200 ms.

All of them delegate to `AppLifecycle` in `:host:core`.

### Failures (`failure.proto`, `daemon/grpc/common.kt` `Throwable.toStatus()`)

Every non-OK status carries a serialized `tap.v1.Failure` in the binary trailer
`tap-failure-bin`: a `FailureReason`, the `serial` when the failure is about one device,
`waited_ms` for a busy lock or a host wait, and the driver `error_code`/`detail` for a failed
host-issued command. Clients switch on `reason`; the status message is for humans and may
change. A status without the trailer did not come from the daemon (a proxy, a transport
failure, a client-side deadline).

| Condition | gRPC status | `FailureReason` |
|---|---|---|
| Missing or wrong token | `UNAUTHENTICATED` | `UNAUTHENTICATED` |
| Unknown client connection / attached device | `NOT_FOUND` | `UNKNOWN_CLIENT_CONNECTION` / `UNKNOWN_ATTACHED_DEVICE` |
| Attached device owned by another connection | `PERMISSION_DENIED` | `NOT_OWNER` |
| Invalid argument (`InvalidArgumentException` from servicer checks, `InvalidCommandException` from pre-flight command validation) | `INVALID_ARGUMENT` | `INVALID_ARGUMENT` |
| Lock held: no wait / after `lease_timeout_ms` | `FAILED_PRECONDITION` / `DEADLINE_EXCEEDED` | `DEVICE_BUSY` |
| Host wait timeout / ADB timeout | `DEADLINE_EXCEEDED` | `HOST_WAIT_TIMEOUT` / `ADB_TIMEOUT` |
| Device quarantined | `FAILED_PRECONDITION` | `DEVICE_QUARANTINED` |
| App lifecycle postcondition | `FAILED_PRECONDITION` | `APP_LIFECYCLE` |
| Driver build mismatch | `FAILED_PRECONDITION` | `DRIVER_BUILD_MISMATCH` |
| ADB reap uncertain | `FAILED_PRECONDITION` | `ADB_REAP_UNCERTAIN` |
| Host-issued driver command failed | `FAILED_PRECONDITION` | `DRIVER_COMMAND` |
| Duplicate Observe, daemon closing | `FAILED_PRECONDITION` | `DAEMON_PRECONDITION` |
| Ref not in the latest snapshot / ref without a selector | `NOT_FOUND` / `FAILED_PRECONDITION` | `UNKNOWN_REF` / `REF_NOT_ADDRESSABLE` |
| Session unusable (poisoned driver connection) | `ABORTED` | `SESSION_UNUSABLE` |
| Driver start failure | `UNAVAILABLE` | `DRIVER_START_FAILED` |
| ADB command failure, gated ADB runner | `UNAVAILABLE` | `ADB_FAILED` |
| Command transport failure | `UNAVAILABLE` | `DRIVER_TRANSPORT` |
| Anything else, including a bare `IllegalArgumentException`/`IllegalStateException` | `INTERNAL`; the stack goes to the daemon log | `INTERNAL` |

### Defaults

`Info` returns `defaults` (`action_timeout_ms` 10 s, `wait_timeout_ms` 10 s,
`lifecycle_timeout_ms` 30 s, `idle_stable_ms` 200 ms, `acquire_timeout_ms` 300 s), the values
the daemon applies to an omitted timeout (`daemon/grpc/common.kt` `Defaults`). The clients' own
defaults are pinned to the same numbers by `contracts/conformance/client-conformance.json`,
which the daemon, Kotlin and Python unit suites all load.

Driver-level outcomes never become gRPC errors; they are `CommandResult` values.

## 4. Implementation notes

`TapDaemon` (`host/daemon/.../daemon/`) holds all state:

- client connections;
- attached devices, each with an `ownerConnectionId`;
- the once-per-serial driver install memo.

It has no gRPC types. The three `*Service` classes in `io.github.noamcohen48.tap.daemon.grpc` extend the
grpc-kotlin `*CoroutineImplBase` classes. They only unwrap the request, call `TapDaemon` or
`:host:core` and wrap the reply, through `reply { … }`, which keeps cancellation intact.

- **Observe:**
  - `observeAcquire` claims the sole observer and registers a close callback that completes the
    stream with `closing`.
  - The stream's `finally` calls `disconnectObservedClient`, which ignores a stale token.
  - Disconnect removes the connection and its devices under the lifecycle lock, then closes
    those devices concurrently outside it.
- **Held connections:**
  - `connectClient(name, holdIdleMs)` registers one and launches its idle reaper instead of the
    observe-grace reaper; `observeAcquire` refuses it.
  - Every lookup that names the connection (`attachDevice`, `attachedDevice`, `detachDevice`)
    counts the call as running until the calling coroutine's job completes — for a servicer,
    the gRPC call — so a call longer than the idle timeout never has its connection ended
    under it. The idle clock restarts when the last running call finishes.
  - Expiry is an ordinary `disconnectClient(…, "idle for Nms")`.
- **Detach / close:**
  - Each device close has a bounded budget. The `DeviceSession` gets that budget minus a small
    margin, so its own cleanup finishes before the daemon gives up on it.
  - A session poisoned before close reports the poison as the detach `detail`.
- **Shutdown:**
  - One total deadline (30 s) covers `TapDaemon.close` and gRPC termination, with 10 s per
    attached device.
  - Once it is exhausted, the remaining cleanup is launched without being awaited. A
    non-terminal journal is recovered by the next attachment.

## 5. Client expectations

A conforming client:

1. Reads the endpoint and token from `daemon.json`, or is given both, and sends the token on
   every call.
2. Connects and starts `Observe` within 30 s, before attaching any device, and keeps the stream
   open for the connection's life. It treats `closing` as the end of the connection.
3. Attaches several devices in sorted serial order (a global lock order) and detaches every
   device it attached.
4. Treats an `error` outcome as a typed failure keyed on `error.code`, and never retries a
   mutation on `INDETERMINATE`. Maps RPC failures by the `tap-failure-bin` reason, never by
   the status message.
5. Puts a client-side deadline on every call that is longer than the command's own timeout.

## 6. Not implemented

- No remote (non-loopback) mode.
- No event stream beyond heartbeats and `closing`. Per-connection structured events (plan §19)
  belong here when they are built.
