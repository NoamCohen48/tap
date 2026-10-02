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
| `ListConnections()` → `repeated ConnectionEntry` | Every live connection: id, name, `hold` when held, `idle_ms` since the last call naming it, and its attached devices (id, serial, generation). How a later process (`tap` CLI) finds a held connection by name. |
| `Events(client_connection_id, after_seq)` → `{repeated LoggedEvent events, dropped}` | The connection's event log (`event_log.proto`), events with `seq > after_seq`, oldest first. Kept for every connection while it lives, the last 2000 (`dropped` counts evictions). Logged: every `Execute` except `device_info` / `dump_hierarchy` (the command as sent), and install / uninstall / force-stop / clear-data / grant / revoke / launch / cold launch / foreground / open link (`AppCall{operation, package_name, activity?, permission?, timeout_ms?, uri?, any_app?, extras}`), each with serial, start, duration, and `error` (driver `Error`) or `failure` (the RPC's `Failure`). Calls rejected before running (invalid argument, unknown or foreign device) and cancelled calls are not logged. Renews a held connection. Unknown connection: `NOT_FOUND`. |
| `WatchEvents(observed_connection_id, after_seq)` → stream `{events | closing}` | Shared authenticated read: retained backlog first (possibly empty), then live events with `seq > after_seq`. Does not create a connection, claim Observe, renew owner activity, or detach on reader cancellation. Backlog/live registration is atomic. Each reader has a bounded 128-update queue; a slow reader receives `RESOURCE_EXHAUSTED` / `DAEMON_PRECONDITION` and can reconnect with its last received sequence number. `dropped` reports retained-log evictions, so gaps must remain visible. The owner disconnecting sends `closing{reason}` after queued events and ends the stream. Unknown connection: `NOT_FOUND`; negative cursor: `INVALID_ARGUMENT`. Anyone with the daemon token can read selectors and typed text already recorded in the log. |

`LoggedEvent` also carries optional `started_monotonic_ns` / `finished_monotonic_ns`
and a `clock_id`. Start is measured at logging-wrapper entry, after validation and before constructing
the event/invoking its block. End is measured after outcome classification, immediately
before appending the event, not after gRPC response delivery. These include the block's
queue/transport waits in `:host:core`'s process-local `MediaClock`; duration is their
nanosecond difference rounded down to milliseconds. They are **not** device execution
or capture times. Compare them with video receipt only when clock ids match. Older
daemons omit these fields; never infer monotonic alignment from epoch start/duration.
A restarted process has a new clock id. Cancelled calls remain unlogged.

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
| `Attach(client_connection_id, serial, skip_driver_install?, default_timeout_ms?, lease_timeout_ms?)` → `{attached_device_id, serial, generation, device_info}` | Takes the per-serial lock. If it is held, the call fails `FAILED_PRECONDITION` at once, or waits up to `lease_timeout_ms` and then fails `DEADLINE_EXCEEDED`. It then installs the driver if needed, starts it with retry and returns `DEVICE_INFO`. An attached device names no app: app calls carry their `package_name`, and selectors their package predicate. If that first query fails, it detaches before returning. `default_timeout_ms` (absent = 10 s) applies when a `Command.timeout_ms` is absent. |
| `Execute(…, command)` → `{result: CommandResult}` | One protocol request. **Driver failures are data**: `outcome = error {code, detail?, message?, match_count?}`. Transport loss after transmission is also data: `TRANSPORT_LOST`, or `INDETERMINATE` for a transmitted mutation. Nothing is ever replayed. Cancelling the gRPC call forwards a protocol `CANCEL`; the driver honours it only before the mutation gate. |
| `Screenshot(…, timeout_ms?)` → `{png, sha256, width?, height?}` | The verified PNG bytes. Writing a file is the client's job; the server takes no host path. |
| `DriverLog(…)` | The instrumentation's stdout ring buffer (last 2 000 lines). |
| `StartRecording(…, video, audio_source?, max_seconds?)` → `{}` | An opt-in capture by a host-owned scrcpy child (`tap serve --scrcpy`, default `scrcpy` on `PATH`, run with `ADB=<--adb>`); the driver encodes nothing. Video only (H.264 MP4, any supported Android), audio only (`video=false`, Opus) or both (H.264/Opus Matroska). `audio_source`: `output` (Android 11+, redirects local playback away from the speakers), `playback` (Android 13+, keeps local playback; apps can opt out) or `mic` (`output` is post-volume: it follows the media volume of the remote-submix route; `playback` is pre-volume); an unsupported API level fails `FAILED_PRECONDITION` before scrcpy starts. Max 30 s with video (2 Mbps, 1024 px, ≤15 fps requested) or 60 s audio only; absent/zero = the maximum. One capture per attached device: a second start fails, and Tap never runs competing scrcpy children for a device. Returns after a fixed 500 ms start check, not on scrcpy readiness (`.docs/audio-recording.md`): the first ~0.6 s can be missing and device-side failures surface at `StopRecording`. |
| `StopRecording(…)` → `{data, format, sha256}` | Finalizes and returns a single file; `format` is `mp4`, `mkv` or `opus`. ≤16 MiB for any video, ≤3 MiB for audio only; client checks SHA-256. A scrcpy failure, missing file or oversize file fails `FAILED_PRECONDITION`. Detach, owner loss and daemon shutdown stop scrcpy and discard an unfinished recording before releasing the device lock. This is an artifact, not a low-latency screen-stream RPC. |
| `ScreenSnapshot(…, timeout_ms?, selector_candidates)` → `{snapshot_id, nodes, removed, rotation}` | A compact outline parsed from the diagnostic hierarchy dump (every window root, visible nodes, pre-order). Each `ScreenNode` has a ref `eN`, depth, window package, class, resource name, text, description, hint, bounds, the true flags, `interactive`, and a selector the daemon synthesised that matched only this node in the dump (absent when none; `by_index` when it needed an `At` pick). With `selector_candidates` each node also carries `candidates`: every `SelectorCandidate{selector, kind}` that matched only it, best first (the first is `selector`); a candidate that only adds predicates to an earlier one is left out, and a `SELECTOR_KIND_BY_INDEX` pick appears only when nothing else is unique. Off by default: it costs more synthesis and a bigger reply, and only an inspector needs it. Refs are aligned with the device's previous snapshot: unchanged nodes keep their ref and are `NODE_UNCHANGED`, new ones get fresh numbers and are `NODE_ADDED`, gone ones are listed in `removed`. A ref is never reused for another node. |
| `ResolveRef(…, ref)` → `{selector, by_index, snapshot_id}` | The selector a ref of the latest snapshot names; the caller sends it in an ordinary `Execute`, where the driver still demands exactly one match. Unknown ref: `NOT_FOUND` / `UNKNOWN_REF`. A node without a selector: `FAILED_PRECONDITION` / `REF_NOT_ADDRESSABLE`. |
| `Detach(…)` → `{clean, detail?}` | `clean=false` means cleanup timed out or the session was quarantined, and `detail` says why. |
| `SetAnimations(…, enabled)` / `SetDarkMode(…, enabled)` / `SetFontScale(…, scale)` / `SetDensity(…, dpi?)` → `{}` | Device conditions, held until detach (see *Saved device state* below). Animations: the three `Settings.Global` animation scales all `0` or all `1`. Dark mode: `cmd uimode night yes|no`, API 29+ (below: `FAILED_PRECONDITION` / `UNSUPPORTED_API`, detail `REQUIRES_API_29`, nothing changed); a device that locks the day/night mode (`mNightModeLocked=true`, Samsung One UI) ignores it, which fails the read-back (`DEVICE_SETTING`, the message names the lock). Font scale: `settings put system font_scale`, 0.5..2.0. Density: `wm density <dpi>` (100..1000) or, with `dpi` absent, `wm density reset`. Out-of-range or non-finite arguments are `INVALID_ARGUMENT` before the device is looked up. Every change is read back over ADB; a value the device did not take is `FAILED_PRECONDITION` / `DEVICE_SETTING`. Logged as `DeviceCall{operation, enabled? / font_scale? / density_dpi?}`. |
| `SetNetwork(…, airplane_mode?, wifi?, mobile_data?)` → `{}` | Real switches, held until detach: `cmd connectivity airplane-mode`, `svc wifi`, `svc data`; API 29+ (`UNSUPPORTED_API` below). At least one must be set (`INVALID_ARGUMENT`). Airplane mode is written first and restored first. Read back from `Settings.Global` (`DEVICE_SETTING` on a mismatch). A serial reached over the network refuses Wi-Fi off / airplane on (`DEVICE_SETTING`) before anything changes. Logged with `airplane_mode`/`wifi`/`mobile_data`. |
| `SetSystemLocales(…, locales)` → `{}` | The device's languages until detach: 1..16 BCP-47 tags (validated, canonicalised, no repeats; else `INVALID_ARGUMENT`), applied by the driver app's `SystemLocaleReceiver` (an `am broadcast` that only shell/system may send; the host grants it `CHANGE_CONFIGURATION` and the `WRITE_SETTINGS` app-op) and read back (`settings get system system_locales`, else `persist.sys.locale`, else `ro.product.locale`). Logged with `locales`. |
| `SetLocation(…, latitude, longitude, accuracy_m?, altitude_m?)` → `{}` | A mock location until detach: the host makes the driver the mock-location app (`appops set … android:mock_location allow`), turns location on (`location_mode` 3) if it was off — both captured and restored — then runs the driver's `set_location`. The driver's test providers outlive it and the app-op, so they are captured too (`mock-location-providers`) and removed first on detach (the driver app's `MockLocationReceiver`). Out-of-range or non-finite arguments are `INVALID_ARGUMENT`. Logged with `latitude`/`longitude` and the `accuracy_m`/`altitude_m` it gave. |
| `SetStayAwake(…, enabled)` → `{}` | The screen stays on while plugged in (USB, AC or wireless; a device on ADB over USB is plugged in) until detach: `Settings.Global` `stay_on_while_plugged_in` `7`, or `0` — what Developer options › Stay awake sets. Read back over ADB (`DEVICE_SETTING` on a mismatch). Logged with `enabled`. |
| `SetAccessibilityDisplay(…, high_contrast_text?, color_inversion?, bold_text?)` → `{}` | The accessibility display settings until detach, as Settings › Accessibility writes them: `Settings.Secure` `high_text_contrast_enabled`, `accessibility_display_inversion_enabled` (`1`/`0`) and `font_weight_adjustment` (`300`/`0`, API 31+; below: `FAILED_PRECONDITION` / `UNSUPPORTED_API` before anything changes). An absent field is left as it is; none set is `INVALID_ARGUMENT`. Read back over ADB. Logged with the fields set. |
| `GetForegroundActivity(…)` → `{package_name?, activity?}` | The resumed activity on top, from `dumpsys activity activities` (`topResumedActivity` on API 29+, the focused one in multi-window; `mResumedActivity` before), `activity` fully qualified. Both absent when none is resumed (a keyguard, or between activities). Changes nothing; not logged. |
| `PushFile(stream {header{…, device_path, size_bytes} \| chunk})` → `{}` | Copies the streamed bytes (≤ 512 MiB, spooled to an owner-only file under the state dir, size checked against the header) to `device_path` with `adb push`. The path must be absolute and normalised, its directory must exist; a file already there is refused unless this attached device pushed it; the size is read back (`stat`). Captured as absent first, so detach (or the next attach after a crash) deletes it. A malformed upload or path is `INVALID_ARGUMENT`; a device-side refusal `FAILED_PRECONDITION` / `DEVICE_FILE`. Logged with `device_path`/`size_bytes`. |
| `PullFile(…, device_path)` → `stream {size_bytes (first), chunk}` | The regular file at `device_path` (≤ 512 MiB), pulled into an owner-only spool file and streamed in 256 KiB chunks. No file, not a regular file or too large: `DEVICE_FILE`. |
| `AddMedia(stream {header{…, file_name, size_bytes} \| chunk})` → `{device_path}` | A photo or video for the gallery: `file_name` (1..127 of letters, digits, `.`, `_`, `-`, space, with a photo or video extension; else `INVALID_ARGUMENT`) is written to `/sdcard/Pictures/Tap/` or `/sdcard/Movies/Tap/`, indexed by the media scanner (`content call … scan_file` on API 30+, the scan broadcast on API 29 and below, polled until indexed for up to 10 s) and read back from MediaProvider (`DEVICE_FILE` when it is not indexed). Same no-overwrite rule and detach removal as `PushFile`; removal also rescans and removes an empty `Tap` folder. |

`Command`:

- It is `optional timeout_ms` (absent = the attachment's default) plus a `oneof op` with one
  message per **public** protocol command.
- The host-internal ops — `health`, `screenshot`, `sync_bootstrap`, `sync_poll` — are not
  `Command` cases: they exist only in the internal `tap.wire.v1.Request` body. Their old field
  numbers are free, and the `artifact`/`sync` outcomes likewise. Screenshot has its own RPC,
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
- A selector has no scope: it matches every window, and `PROPERTY_PACKAGE_NAME` (a `Match`
  property) restricts it to one app's nodes, which is what the SDKs' `app("pkg").element(…)`
  adds. `ResourceId{name, package_name?}`: with a package exactly `pkg:id/name`, without it
  `name` in any package or a bare testTag; a `name` containing `:id/` is `INVALID_ARGUMENT`
  (`QUALIFIED_RESOURCE_NAME`).
- A gesture (`Tap`, `LongTap`, `Swipe`, `Scroll`) whose touch point lies in a window above the
  target's fails `ERR_NOT_INTERACTABLE` / `OBSCURED` before any input. That needs a partly
  covered target: one covered completely is reported not visible, hence `ERR_NOT_FOUND`.
- Protocol 5.0 (2026-10-01, deliberately incompatible, no `reserved` placeholders) removed the
  selector scopes (`AutScope`/`SystemScope`/`AnyWindowScope`), `ResourceId.aut_package`,
  `AttachRequest.aut_package`/`sync_authority`, `AttachedDeviceEntry.aut_package` and
  `LoggedEvent.aut_package`, and added `PROPERTY_PACKAGE_NAME`, `QUALIFIED_RESOURCE_NAME` and
  `OBSCURED`. `AwaitIdle` reaches the sync provider at `<package_name>.tap-sync` of its
  `AppTarget`.
- Protocol 4.0 (2026-09-28) removed `ScrollUntil` (`Command` field 21), `CommandResult.moved`
  (6), `AttachRequest.allowed_system_packages` (6) and `TypeText.selector` (1); all are
  since deleted. `TypeText` types into the current focus; the SDKs' element `typeText` taps,
  waits for focus, then sends it. It added the
  `AnyWindowScope any_window` selector scope (gone in 5.0) and `ElementSnapshot.showing_hint`. The clients'
  `scrollUntil` / `scroll_until` are client-side loops of `Exists` + `Scroll`.
- `OpenSystemPanel` (`Command` field 24, `SystemPanel` enum) opens the notification shade or
  quick settings; `SYSTEM_PANEL_UNSPECIFIED` is `INVALID_ARGUMENT`. Added within protocol 4.0
  (additive; a driver without it does not advertise `open_system_panel`).
- The device actions were added with protocol 5.0 (they were never released under 4.0).
  Rotation: `SetOrientation` (25) chooses portable
  portrait/landscape geometry, `SetDisplayRotation` (26) chooses an exact natural-relative
  rotation, and `UnfreezeRotation` (27) releases the sensor lock. The host lazily captures the
  two Android rotation settings before the first such mutation and restores them during detach;
  a failed restoration makes cleanup visible and quarantines the session rather than silently
  releasing a device with changed state. The driver reports only whether Android accepted the
  rotation (after a bounded 2 s settle wait); what the display did is in `DeviceInfo`.
- Also in the device-actions set: `DismissKeyguard` (28), `DoubleTap` (29),
  `Drag` (30, a source `selector` and a `target`, both exactly one match before input), `Pinch`
  (31, `PinchDirection`, `percent`), `Fling` (32), `WaitPermissionPrompt` (33, result
  `CommandResult.permission_prompt` = `PermissionPrompt{package_name, choices}`) and
  `ChoosePermission` (34, `PermissionChoice`). `DeviceInfo` gained `screen_on` (9),
  `keyguard_locked` (10) and `keyguard_secure` (11). Waking and sleeping the screen are
  `PressKey` 224 / 223 (the SDKs' `wake`/`sleep`); there is no command for them. Errors:
  `ACTION_REJECTED`/`KEYGUARD_SECURE`, `WAIT_TIMEOUT`/`NO_PERMISSION_PROMPT`
  (`.docs/protocol-contract.md`).
- Device actions groups 1–3 (also protocol 5.0, additive): `HideKeyboard` (35),
  `PerformImeAction` (36), `SetClipboard` (37), `GetClipboard` (38), `AwaitToast` (39),
  `PerformAccessibilityAction` (40, `StandardAction` or a custom label), `SetProgress` (41) and
  `SetLocation` (42, no selector). `ChoosePermission.accuracy` and
  `PermissionPrompt.accuracies` (`LocationAccuracy`); `ElementSnapshot.actions`,
  `custom_actions` and `range` (`Range`, `RangeType`); `DeviceInfo.airplane_mode` (18),
  `wifi_enabled` (19), `mobile_data_enabled` (20) and `system_locales` (21). Errors:
  `ACTION_REJECTED`/`ACTION_NOT_OFFERED`, `ACTION_REJECTED`/`OUT_OF_RANGE`.
- Device actions group 4 (protocol 5.0, additive): `AwaitNotification` (43),
  `ListNotifications` (44), `OpenNotification` (45, mutation, optional `action`) and
  `DismissNotification` (46, mutation), each with a `NotificationMatch{package_name?, title?,
  text?, mode}`; results `CommandResult.notification` (16, `DeviceNotification`) and
  `notifications` (17, `NotificationList`). Before the session's first notification command the
  server gives the driver app's `TapNotificationListener` notification access (`cmd
  notification allow_listener`, read back from `dumpsys notification`'s allowed listeners; saved
  state `driver-notification-listener`), which detach takes back. Detach unbinds the listener
  before it closes the driver (Android 10 binds a listener killed while bound again, whatever its
  access), and every driver force-stop first unbinds a bound one (`.docs/device-actions.md`). `DeviceInfo.stay_awake` (22),
  `high_contrast_text` (23), `color_inversion` (24) and `bold_text` (25). Errors:
  `WAIT_TIMEOUT`/`NO_NOTIFICATION`, `ACTION_REJECTED`/`NOT_CLEARABLE`,
  `UNSUPPORTED`/`NO_NOTIFICATION_ACCESS`.

### AppService: AUT lifecycle

Each request carries an `AppTarget{client_connection_id, attached_device_id, package_name}` and has
its own response message. `package_name` may be any package except Tap's driver packages
(`io.github.noamcohen48.tap.driver` and its `.test`), which are `INVALID_ARGUMENT` before the
device is looked up: stopping, clearing or uninstalling them would end the session. The RPCs are:

- `Install` is client-streaming.
  - The first message is an `InstallHeader{app, timeout_ms?, size_bytes}`; every later message
    is a `chunk`.
  - The server spools the upload to an owner-only file under `<state-dir>/uploads` and rejects
    a missing or repeated header, a size outside 1 B..1 GiB, and any mismatch with `size_bytes`.
  - It then installs the file and deletes it.
- `Uninstall`, `IsInstalled`, `ForceStop`, `ClearData`, `GrantPermission`.
- `RevokePermission(permission)`: `pm revoke`, then proof from `dumpsys package` that the
  permission no longer reads as granted (`APP_LIFECYCLE` otherwise); a blank permission is
  `INVALID_ARGUMENT`. Android kills the app's process when a runtime permission is revoked.
- `IsPermissionGranted(permission)` → `granted`: whether `dumpsys package` lists the permission
  as `granted=true` for the package (the check grant and revoke use). A read: not logged; a
  blank permission is `INVALID_ARGUMENT`.
- `SetLocales(locales)` / `GetLocales()` → `locales`: the app's own languages (`cmd locale
  set-app-locales|get-app-locales <pkg> --user current`, API 33+; below, `FAILED_PRECONDITION`
  / `UNSUPPORTED_API` with detail `REQUIRES_API_33`). Tags are checked and canonicalized on the
  server (`Locale.Builder().setLanguageTag(…).toLanguageTag()`, at most 16, no repeats; anything
  else is `INVALID_ARGUMENT`, because Android accepts ill-formed tags); an empty list makes the
  app follow the system. The change is read back (`DEVICE_SETTING` otherwise) and held until
  detach like the device conditions. `SetLocales` is logged as `set_locales` with `locales`;
  `GetLocales` is a read and not logged.
- `Launch` and `ColdLaunch` take an optional `activity`, where absent means the launcher, and
  `extras`: `IntentExtra{key, string | bool | int32 | int64 | float}` sent as `am start
  --es/--ez/--ei/--el/--ef` (shell-quoted). At most 64, distinct keys of 1..256 characters
  without whitespace or control characters, a value set, strings ≤ 4 096 characters without
  NUL, floats finite; anything else is `INVALID_ARGUMENT` before the device is looked up.
  `ColdLaunch` returns `ProcessIdentity{pid, start_token}`, a verified new process. Both
  return once `am start -W` does; they do not wait for the app's window (a client that needs
  it calls `WAIT_APP_VISIBLE` / `WAIT_SCREEN_STABLE`).
- `Foreground` sends the launcher intent (`am start -W -a MAIN -c LAUNCHER -f 0x10200000 -n
  <launcher activity>`), as the home screen does: an existing task comes back as it was left,
  back stack included; with no task the launcher activity starts. `APP_LIFECYCLE` when `am
  start` reports a failure.
- `OpenLink(uri, any_app)` sends a VIEW intent for `uri` (`am start -W -a VIEW -d`), limited to
  the package (`-p`) unless `any_app`, and returns the `activity` `am start` reports
  (`package/class`), or none. The `uri` must be absolute (a scheme), 1..2048 characters, with no
  whitespace or control characters (`INVALID_ARGUMENT`); no app handling it is `APP_LIFECYCLE`.
  With `any_app` a chooser may appear; Tap never taps it.
- `Process`, `IsRunning`.
- `AwaitIdle(stable_for_ms?)`, where the default is 200 ms.

All of them delegate to `AppLifecycle` in `:host:core`.

### VideoService: shared passive screen video

`video.proto` adds authenticated `WatchVideo(serial)`, a server stream independent of
attachments and client connections. It checks inventory but never attaches, claims
Observe, renews owner idle time, enables screenshots, wakes the display or sends input.
One optional scrcpy 4.1 producer serves all readers for that serial (`TAP_VIDEO_SERVER`,
default `/usr/share/scrcpy/scrcpy-server`; not bundled). Source details and evidence are
in [shared-video.md](shared-video.md).

`WatchVideoResponse` contains a `VideoHeader` or `VideoFrame`. Header: stream and clock
identity, encoded dimensions, Annex B SPS/PPS. Frame: sequence, original device PTS in
microseconds, key flag, Annex B access unit, full-packet host receipt monotonic nanoseconds
and epoch milliseconds. These are not device input/capture timestamps. A changed header
resets decoding/retention; clocks are comparable only within the same clock identity.

Subscriptions atomically register and snapshot header/keyframe preroll, then receive
live packets. Retention is 120 seconds / 32 MiB with whole-GOP eviction. Limits: four
producers, eight readers per producer; each reader queue is 128 updates / 4 MiB. Slow
readers receive RESOURCE_EXHAUSTED, never blocking the producer or owner. Cancellation
removes only that reader; last-reader exit stops capture. A stopping producer cannot be
replaced before cleanup ends; unproven cleanup gates that serial. Daemon shutdown stops
all producers within its existing deadline. Optional capture failures are UNAVAILABLE
with DAEMON_PRECONDITION. No owner or journal state is changed.

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
| Device below the call's API level | `FAILED_PRECONDITION` | `UNSUPPORTED_API` (detail `REQUIRES_API_<n>`) |
| A device setting did not read back as written | `FAILED_PRECONDITION` | `DEVICE_SETTING` |
| A device file exists and is not this session's, its directory is missing, it is not a regular file, or it did not read back | `FAILED_PRECONDITION` | `DEVICE_FILE` |
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
- **Saved device state:**
  - Rotation, the device conditions and app languages share one mechanism. Before the session's
    first change of a value, the server reads it (`settings get`, `cmd uimode night`, `wm
    density`, `cmd locale get-app-locales`) and appends it to the journal's `savedState`
    (`{key, value}`; keys `setting:<namespace>/<name>`, `uimode:night`, `wm:density`,
    `locale:<package>`, `network:airplane|wifi|mobile_data`, `system-locales`, `mock-location-providers`, `appop:<package>/<op>`, `driver-notification-listener`,
    `file:<path>`, `media:<path>`; a null value = absent). Later changes of the same value
    capture nothing. Pushed files and added media are only ever captured as absent: restoring
    deletes what the session created.
  - Detach writes the values back newest first (locking auto-rotate first when rotation is
    among them), reads every one back, then clears `savedState`. A value that does not come
    back fails the detach and quarantines the device.
  - When the daemon died before detach, the next attach restores the journaled values right
    after journal recovery; one that does not come back quarantines with reason
    `DEVICE_STATE_RESTORE_FAILED: …`. A journal without saved state omits the field.
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
- No shared live video stream yet. `WatchEvents` supplies connection action logs; optional
  test markers remain deferred.
