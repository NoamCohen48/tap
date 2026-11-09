# Changelog

From 0.1.0, every Tap component shares one version (`docs/reference/releases.md`); entries
name the components affected. Earlier releases used independent version lines. Tap is alpha:
0.x minor releases may change the API, and experimental parts may change in any release.

## Unreleased

### Engine

- A device quarantined only because a setting it changed did not read back when it was put
  back (another writer changing it again, ADB failing mid-restore) is no longer out of service
  until someone clears it. ListDevices marks it `retry_on_attach`; the next attach, or
  `tap doctor --fix`, writes the settings again and, once they read back, goes ahead. Other
  quarantines (a driver that may still run, an unreaped ADB child, a reboot during a session,
  a corrupt journal) still keep the device out of service. Quarantines for a reboot, a corrupt
  journal or an out-of-range port now carry a reason.
- `tap unquarantine --serial SERIAL` and the `Unquarantine` RPC put a quarantined device back in
  service once someone has checked it, instead of deleting its journal file by hand. They retry
  the cleanup first (stop the driver, remove Tap's forwards, write the saved settings back) and
  report what is still off. `tap doctor` points to it.
- Attach takes `inspect_quarantined`, so a person or an agent can attach a quarantined device to
  look at and fix it (screenshots, snapshots, commands) before deciding to unquarantine it. The
  device stays quarantined for its original reason after detach, also when the server dies
  meanwhile; ListDevices shows it `under_inspection`.
- Writing a private app file or importing a database works on Android 10 and older: the write
  used `mv -T`, which their toybox lacks, so every write failed with `IO_ERROR`.
- Wait until an element satisfies a condition, on the device. `app.wait(sel).until(condition)`
  (Kotlin `await(sel).until(...)`) waits until the one node the selector picks satisfies a
  condition written with the selector builders, for example `enabled() & text("Pay")`,
  `checked(False)` or `text(r"\d+ €", REGEX)`. All parts are checked on that node at the same
  moment, polled by the driver every 50 ms. New top-level builders: `enabled`, `checked`,
  `checkable`, `focused`, `focusable`, `long_clickable` / `longClickable`, `selected`.
  `enabled()`, `disabled()`, `checked()`, `unchecked()`, `focused()`, `text_equals()` and
  `text_contains()` are now shortcuts for `until` and run on the device, instead of a host
  snapshot every 100 ms. Their meaning is unchanged: the selector's `first()` / `at(n)` picks
  the node first, a missing or re-rendering node keeps the wait polling, and several matches
  without a pick fail at once with `AMBIGUOUS`. A timeout now has a `reason`:
  `STATE_NOT_REACHED`, with `last_state` (the node at the end) and `condition_parts` (which
  part held), or `NO_MATCH`. `text_contains("")` is now rejected, as `text("", CONTAINS)` is in
  a selector. Protocol 5.1 adds the `wait_state` operation, `Error.last_state` and
  `Error.condition_holds`.
- A selector that matches nothing says why. `NOT_FOUND` and a `NO_MATCH` wait timeout carry
  `narrowing`: for each part of the selector, how many nodes match it alone and how many match
  everything else. Text-like parts also list up to three values the nearest nodes really have.
  A failed pick reports how many nodes there were. The error message shows the same on its
  following lines, and so does `tap-agent`. It is bounded (at most eight parts, about a second
  on the device) and left out after `snapshot()`. Protocol 5.1 adds `Error.narrowing`.

### Clients and tools

- Kotlin: `TapClient.unquarantine(serial)`, `DeviceOptions(inspectQuarantined = true)`,
  `Device.quarantineReason`, `DeviceEntry.retryOnAttach` / `underInspection`. Python:
  `TapClient.unquarantine(serial)`, `attach_device(serial, inspect_quarantined=True)`,
  `Device.quarantine_reason`, `DeviceEntry.retry_on_attach` / `under_inspection`. Both pick
  retryable quarantined devices last in `availableSerials` / `available_serials`.
- Tap agent: `attach SERIAL --quarantined` and `unquarantine SERIAL` (MCP: `inspect_quarantined`,
  `unquarantine`); `devices` marks quarantines that are retried on attach or under inspection.
- Tap Studio: the device list inspects and unquarantines a quarantined device. Tap watcher
  shows the quarantine reason and, as a tooltip, the `tap unquarantine` command.
- Tap watcher says why a failed action failed. The selected action's details list, under its
  error, the selector parts that ruled every node out (each part alone and without it, with the
  values seen) or, for a state wait that timed out, which parts of the condition held and what
  the node had.

## 0.1.0 — 2026-11-08

**One Tap release.** Server, bundled driver, contracts, Kotlin/JUnit and Python clients,
Tap agent, Tap Studio, Tap watcher and the experimental sync SDK all use 0.1.0. The shared
`v0.1.0` release includes individual artifacts and complete platform bundles. After main CI
passes, one pipeline stages and verifies every download, publishes the packages, then makes
the complete release public. Website links use that same tag; product labels are Tap agent
and Tap watcher, while commands and package names stay unchanged.

Python bindings target server 0.1.0; agent, Studio and watcher require `tap-e2e>=0.1.0`.
The API compatibility epoch is unchanged by this version-only bump. Features and breaking
changes increment the shared minor version during 0.x; fixes increment the patch.

Engine:

- **Restore rotation after instrumentation stops.** Android's UiAutomation teardown can no
  longer overwrite the session's restored auto-rotate and frozen-rotation settings. If driver
  cleanup is uncertain, the device is quarantined and its saved rotation remains journaled.
- **Restore rotation reliably on devices whose SystemUI syncs the rotation lock** (API 33+
  images with per-device-state rotation locks, such as the API 34 emulator). SystemUI's own copy
  of the lock is now saved and restored with it, restoring auto-rotate *on* no longer writes a
  momentary lock that SystemUI could re-apply afterwards, and the restored rotation must hold
  for a second (written again if Android changes it back, for up to five seconds) before
  detach counts it as restored. This intermittently quarantined the device after a rotation test.

tap-agent:

- **Concurrent recording startup.** Two first starts sharing an empty catalog now wait through
  SQLite journal-mode contention instead of failing immediately with “storage unavailable”.
  Only database initialization is retried; no device input is replayed.
- **Named step recordings.** `record start|stop|list|clean` manages durable private recordings of
  actions and explicit waits, with resolved selectors, device identity, sensitive redaction and
  explicit unfinished outcomes. `export [NAME] [-o FILE]` exports them even after release.
  The old raw event export is now `history`; video/audio capture is `media-record`. Capture is
  client-owned, bounded, shared across CLI/MCP processes and never automatically replays input.
  A failed action is `rejected_before_input`, `failed` (its error code proves nothing changed) or
  `unknown` (the input may have happened), and `unknown` blocks further recorded input.
- **MCP annotations.** Tools that write local files (`history`, `export`, `logs`, `screenshot`,
  `capture`, `pull_file`) are no longer marked read-only.
- **Element queries and richer selectors.** CLI/MCP `inspect`, `exists` and `count`, state/text/
  count waits, boolean and text-mode predicates, and bounded JSON selectors for logical and
  relational expressions reuse SDK semantics. No XPath or persistent handles. Command budgets
  reject values above the protocol's 120-second maximum locally; MCP settle exposes its timeout.
- **Practical SDK workflows.** Add installed/process/visibility queries, device properties and
  metadata, per-device detach, bounded scroll-until, gesture distance/budgets, targeted typing
  with a separate focus wait, tree/pixel stability controls, selector candidates, incremental
  event export and private diagnostics. CLI and MCP expose matching parameter surfaces.
- **Explicit recording.** Bounded SDK video/audio capture on an owned device, private verified
  outputs and retained canonical artifacts after custom-copy failure. No implicit lifecycle or
  replay. Synchronization, observed liveness and passive watching remain excluded. A checked API
  registry makes new daemon/SDK APIs and CLI/MCP parameter drift visible in tests.

Tap Studio:

- **Accepted operations own their completion.** Recording replacement, edits, tools, pause,
  attachment and release cannot race an in-flight action. Canceling its browser request keeps
  the operation gate until its result is reconciled; a canceled replay finishes only its accepted
  step. Shutdown drains it before detach. Changing a shared secret invalidates old outcomes.
- **Headless replay and pytest export.** `tap-studio replay` runs a recording with no browser and
  `tap-studio export` (or the export drawer) writes a pytest file on the public client API. Both
  need `tap start`, retry nothing and refuse rather than lose meaning.
- **Per-run results.** Each replay has an identity and bounded step results with known secret values
  scrubbed. Evidence from the device is captured only when asked.
- **Saved recordings, a private library of named flows,** usable with no device or Tap server, with opt-in
  autosave. It stores definitions only, never secret values or results, and refuses to overwrite
  an entry another Studio changed.
- **Parameters.** Named, non-secret test data for the text a step types or expects, kept in the
  recording and resolved before any input.
- **Harder to lose work.** Unsaved step edits and Clear data ask first. Dialogs trap and restore
  focus and stop stealing it when frames arrive, and the screen tree can finish a drag or a scroll
  until by keyboard.
- **Every step value is editable,** with undo, redo and duplicate. These change the recording only,
  never the device, and an old result does not come back for a step that has changed.
- **Checks start from a selector.** Assert and Wait take a typed selector without needing a live
  element, offer absence and zero-count checks, and can add a step without running it. The open
  tooltip and export drawer name the real format.
- **One tab controls a Studio.** Other tabs watch and the server refuses their changes. Take
  control moves it once nothing is running; a closed or reloaded tab passes it on. The page's busy
  state now comes from the server, so it stays locked until a cancelled step has really finished.
- **Secret typing is sensitive.** Type keys now marks named secret values sensitive on both
  direct execution and replay, as Set text already did. The device receives the value but the
  daemon's event history, watcher and event exports omit it; recording exports keep only its name.
- **Gesture and execution controls.** Counted taps, physical hold duration, command/query,
  selector-precondition and typing-focus budgets, and whole-screen/package stability with custom
  quiet periods and signals can be recorded, edited and replayed. Invalid parameters and bounded
  selectors are rejected before input. New recordings use `tap-recording/2` so older readers cannot
  silently discard the added budgets; legacy `/1` files upgrade on open.
- **Explicit tools, not replay steps.** Read app installed/running/process/locale/permission
  state; use the SDK private file and SQLite bundle APIs with confirmation, explicit overwrite
  and the daemon's stopped-UID safeguards; download screenshots, hierarchy, logs, incremental
  raw history and metadata. Browser artifacts are bounded to 1 MiB and never silently truncated.
  No implicit stop/relaunch, data restoration, arbitrary host paths, shell, SQL or mutation retry.

Server, Kotlin/Python clients and tap-agent:

- **Debuggable private app data.** `app.data` probes actual run-as access, lists/reads/writes/
  deletes relative private files, and exports/imports SQLite bundles including committed WAL
  and rollback journals (not SHM). Binary-safe, bounded transfers; no private paths/bytes in
  event records. Requires a stopped app, including secondary/shared-UID processes; stop and
  relaunch explicitly. No root, arbitrary shell, SQL execution or data restore on detach.
  Replacement requires explicit overwrite; interrupted multi-file import can require repair.
  Agent CLI/MCP use owner-only local outputs and never print contents.

Driver, server, clients (additive, protocol 5.1):

- **Counted taps and timed presses.** Kotlin/Python `tap(count=…)`, `tripleTap` /
  `triple_tap`, and `longTap(duration=…)` / `long_tap(duration=…)` send one bounded touch
  sequence at the initially resolved point, without retries. Agent CLI/MCP expose count,
  release-to-press interval, duration and timeout. Unsafe targets or an insufficient remaining
  deadline fail before input. Counted taps and explicit-duration presses require the default
  display; existing single/double/default long press behavior is unchanged.

- **Typed secrets stay out of the event log.** `SetText` and `TypeText` have a `sensitive`
  flag: Kotlin `setText(…, sensitive = true)` / `typeText(…, sensitive = true)`, Python
  `set_text(…, sensitive=True)` / `type_text(…, sensitive=True)`, `tap-agent fill/type
  --sensitive` (MCP `sensitive`). The device gets the text; the server logs the command
  without it (the flag stays), so `Events`, `tap-watcher` (which shows "(hidden)") and
  `tap-agent export` never hold it. Tap Studio sends its recorded secrets this way. They used
  to be logged verbatim, passwords included.
- **A node re-rendered before input is `STALE_BEFORE_INPUT`.** When the matched node goes stale
  between the lookup and the input (a list re-binding, a re-laid-out form), the command fails
  with the new `STALE_BEFORE_INPUT` code: nothing was sent and it is retryable. It used to be
  `INTERNAL`. Staleness after input started is `STALE_DURING_COMMAND` / `TARGET_GONE`. Kotlin
  and Python property waits (`enabled()`, `checked()`, …) keep polling through it; `tap-agent`
  says to run the command again.
- **Taps touch the point that was checked.** `tap` and `long_tap` inject the touch at the
  visible centre the occlusion check used instead of letting `UiObject2` read the node again
  after the mutation gate (a node on a secondary display still uses `UiObject2`). Tap, long
  tap, double tap and drag inject finger events without waiting for the app to handle each one,
  as UiAutomator does, so a busy main thread no longer holds a double tap or drag past its
  deadline.

Clients (Kotlin, Python):

- **Test runs have structured records.** JUnit and pytest write `run.json`, incrementally
  flushed lifecycle `events.jsonl`, and an artifact manifest with identities, sizes, SHA-256
  and collection errors. Automatic evidence lives under a unique run/test/attempt/device/
  generation path, so parameterized tests, retries and concurrent workers cannot overwrite
  each other. `TAP_REPORT=off` opts out; `TAP_CAPTURE=off` disables evidence but keeps outcomes.
  Reporting errors never replace the test failure. Individual command payloads and HTML
  reports are not part of this first format.

- **An incompatible newer server is refused too.** Connect (and Python resume) requires the
  server's explicit API compatibility epoch to match the client's generated bindings before
  applying the minimum engine-version check. A missing epoch is refused: upgrade an older
  daemon together with the client. Ordinary compatible releases keep the same epoch.

- **An older server is refused at connect.** The first `connect` of a client (and Python
  `resume`) compares the server's `Info().daemon_version` with the engine the client's stubs
  come from (Kotlin `TAP_API_VERSION` in `tap-api`, Python `tap_e2e._gen.engine_version`) and
  fails with both versions named when the server is older. Such a server used to ignore fields
  it did not know, or fail one call deep in a test with `UNIMPLEMENTED`. A newer server is
  accepted; a version that is not `major.minor.patch` is not checked.

Driver:

- **`first()` and `at(n)` count the same way for every selector.** A selector with a regular
  expression, an `any_of` or a repeated property counted its matches outer node first, while
  every other selector counted a container after the matching nodes inside it (UiAutomator's
  order). On a screen with nested matches the same `at(n)` picked a different node depending on
  how it was written. Both now use UiAutomator's order.

Server, clients (additive):

- **Failure artifacts include the device's own logs.** A new `DeviceLogs` call returns logcat
  (main, system, crash) since the device was attached, the crashes and ANRs since then, and
  `dumpsys activity activities` / `dumpsys window windows` at the time of the call. Kotlin
  `device.deviceLogs()`, Python `device.device_logs()`; `capture()` includes them, so the JUnit
  extension and the pytest plugin save `logcat.txt`, `crashes.txt` (when something crashed),
  `activities.txt` and `windows.txt` next to the screenshot of a failed test. An app crash used
  to leave no trace in the artifacts. One stuck read (a `dumpsys` that hangs) costs only that
  file: each read has its own deadline, shorter than the call's.
- **The device list says what each device is.** `ListDevices` reports each online device's API
  level, manufacturer, model and whether it is an emulator (`DeviceEntry.properties`, read
  once per serial with `getprop`; Kotlin and Python `DeviceEntry.properties`).

JUnit:

- **Roles choose devices by what they are.** `@RequireDevice(role = "receiver", minApi = 30, kind =
  DeviceKind.PHYSICAL)` on a test or class, with `minApi`, `maxApi`, `kind`, `manufacturer` and
  `modelContains`, the same criteria as the pytest marker. A test no device fits is skipped
  before anything is attached, naming the devices it saw.
- **`@BeforeEach`, `@AfterEach` and test templates can use devices.** `tapTest` was bound only
  around a plain `@Test`, so calling it in a `@BeforeEach`, an `@AfterEach`, a `@RepeatedTest` or a
  `@ParameterizedTest` failed with a usage error. An `@AfterEach` also keeps working after a
  failing test. A lifecycle method's failure saves the same artifacts as a test's.
- **A skipped test saves no failure artifacts.** A failed assumption used to capture them.

Python client:

- **pytest roles choose devices by what they are.** The marker takes `DeviceEntry.properties`
  per role: `@pytest.mark.tap_devices("sender", receiver={"min_api": 30, "emulator": False})`,
  with `min_api`, `max_api`, `emulator`, `manufacturer` and `model_contains`. A test no device
  fits is skipped, naming the devices it saw.

Server:

- **A command failing because the app died says so.** When a command fails with `NOT_FOUND`,
  `WAIT_TIMEOUT` or similar and an app started through the attachment has a new crash or ANR in
  Android's event log, the failure becomes `AUT_CRASHED` / `AUT_ANR`, naming the app and the
  exception, with the replaced code as its detail. Code that catches `NOT_FOUND` or a wait timeout
  to handle a missing node now sees these instead after a crash. Passing commands pay nothing.
- **`tap doctor`.** Reports, per device, what a session that is no longer running left behind
  (an unclosed journal, the driver process, Tap's port forwards, unrestored settings) and a
  stale server descriptor; `tap doctor --fix` runs the recovery an attach would run, now.
  Devices a live session holds are skipped, and quarantines are reported, never cleared.
- **Force-stop and clear data finish while a permission dialog is open.** A runtime permission
  dialog the app opened lives in the app's task but belongs to the permission controller, so
  `am force-stop` never removes it. `forceStop`, `clearData` and the stop before a cold launch
  waited for that task to disappear and timed out after ten seconds, every time, until someone
  dismissed the dialog. A task of the app that holds only other packages' activities no longer
  counts as the app running.
- **The driver on a device is checked byte for byte.** Each attachment compares the SHA-256 of
  the driver APKs installed on the device with the server's own and reinstalls them within the
  same attachment when they differ. A different build of the same version (for example an
  emulator snapshot restored with an older driver) used to pass the `versionName` check and run
  silently. The check runs on every attachment under the device lock (falling back to pulling
  the APKs where the device has no `sha256sum`). Bundled APKs are extracted to
  `driver/<version>-<digest of both APKs>/` in the state directory.

Packaging:

- **One-download bundles.** Each release set is also published as `bundle/vX.Y.Z` with one zip
  per platform (linux-x86_64, macos-aarch64, jvm): server, Kotlin artifacts as a local Maven
  repository (no GitHub token), Python wheels, docs, `install.sh` and `INSTALL.md`. The site has
  a Download page for them.
- **The Markdown docs moved into the bundles.** The per-family releases no longer attach
  `tap-docs-<version>.zip` / `.tar.gz`.

Breaking, in every family (protocol 5.0; `.docs/app-and-screen.md`):

- **An attached device names no app.** Kotlin `connection.attachDevice(serial)` /
  `attach(serial) { }`, Python `connection.attach_device(serial)`; `tap.autPackage` /
  `tap_aut` / `TAP_AUT` and `DeviceOptions.syncAuthority` are gone. `device.app(pkg)` takes a
  package.
- **App or screen.** `app.element(…)` / `app.await(…)` (Python `app.wait(…)`) match only that
  app's nodes; `device.screen.element(…)` / `.await(…)` match anywhere. Both search every window.
  `device.element` / `device.await`, `inPackage`, `inAnyWindow`, `rawRes` and `SCOPE_DENIED` are
  gone. App waits moved to `App`: `awaitVisible`, `awaitSettled`, `awaitScreenStable`,
  `awaitAnimationEnd` (snake_case in Python).
- **Resource ids.** `res(name)` matches the id in any package (or a Compose testTag),
  `resId(pkg, name)` exactly that package's; a name containing `:id/` fails with
  `QUALIFIED_RESOURCE_NAME`.
- **Covered elements.** A tap, long tap, swipe or scroll on an element partly under another
  window (keyboard, dialog, shade, overlay), with its touch point underneath, fails with
  `NOT_INTERACTABLE` / `OBSCURED` before any input, instead of touching the covering window.
  An element covered completely is, as before, not found.
- `tap-agent`: `attach <serial>` (no package, `--launch`, `--cold`); `app <action> <package>
  [argument]`; `pkg=` restricts a target to one app, otherwise it matches the whole screen.
  The event log and `tap-recording/1` lose `aut_package`. Tap Studio attaches a device
  alone (`--serial S`, no `--package`; `AttachRequest` / `AttachedDevice` lose `app_package`):
  the package the App menu acts on is entered in that menu, and its code shows `app("pkg").…` or
  `screen.…` per step.

Added:

- **Docs site** (docs, `tap-agent`): every page is also published as Markdown next to its HTML
  (`guide/selectors.md`), with `llms.txt` and `llms-full.txt` for agents; a `tap-agent` command
  reference generated from the CLI and the MCP server; a Tap Studio walkthrough with
  screenshots; a rewritten home page; highlighted `tap-agent` sessions. The site has a tab per
  client (SDKs, `tap-agent`, Tap Studio) beside the Guide, whose pages are split by topic with
  Python examples; the Kotlin SDK page shows each guide page's examples in Kotlin. The old
  guide URLs redirect to their new pages. The Python API reference has a page per class (or
  group of small types), and the types in its signatures link to their entries.
- **Recordings** (daemon, Kotlin and Python clients): `device.startRecording()` /
  `stopRecording()` (`start_recording()` / `stop_recording()`) record the device with scrcpy on
  the daemon host: video (MP4), audio (Opus) or both (Matroska), bounded to 30 s with video and
  60 s audio only, checksummed, and discarded on detach. Audio needs Android 11+. scrcpy is an
  external dependency: `tap start --scrcpy PATH` (or `TAP_SCRCPY`), default `scrcpy` on `PATH`;
  it runs with Tap's own ADB. Guide: `docs/guide/artifacts.md`.
- **tap-watcher** (new family `client-watcher`, experimental; daemon): a read-only browser view
  of every device the server sees: the connection using it, each action it runs, and the
  screen as video, with look-back, a scrubber with action markers and zoom, Before / After
  frames of an action, clips, recordings and a Library. It never attaches or sends commands.
  The daemon's new `WatchService` (`Watch`, `WatchVideo`) serves it; the video needs a scrcpy
  4.1 server JAR (`tap start --scrcpy-server JAR` or `TAP_SCRCPY_SERVER`). In the bundles.
  Docs: `docs/watcher/`.

Device actions (engine, Kotlin and Python clients, `tap-agent`), new in protocol 5.0; the engine
and the clients must be updated together to use them:


- **Rotation**: `setOrientation(PORTRAIT|LANDSCAPE)` (the right geometry on phones and
  tablets), `setDisplayRotation(NATURAL|LEFT|UPSIDE_DOWN|RIGHT)` and `unfreezeRotation()`.
  They fail only when Android refuses the rotation; an app that locks its orientation is for
  the test to observe. Detach restores the device's own auto-rotate settings; a failed restore
  quarantines the device.
- **Screen and lock screen**: `wake()`, `sleep()`, `dismissKeyguard()` (never unlocks a PIN,
  pattern or password: `ACTION_REJECTED` / `KEYGUARD_SECURE`); `DeviceInfo` reports
  `screenOn`, `keyguardLocked`, `keyguardSecure`.
- **App**: `foreground()` returns to the app as the home screen does (its task as it was
  left), `background()` presses Home, `openLink(uri, anyApp = false)` opens a deep link or app
  link in the app and returns the activity Android started (`AppService.Foreground` /
  `OpenLink`).
- **Permission dialogs**: `awaitPermissionPrompt()` returns the choices the runtime-permission
  dialog offers, `choosePermission(choice)` presses one; found by resource id, not by label.
- `device.app(pkg)` refuses Tap's driver packages (`INVALID_ARGUMENT`): force-stopping or
  clearing the driver would end the session.
- **Gestures**: `doubleTap()`, `dragTo(destination)`, `pinchOpen()` / `pinchClose()`,
  `fling(direction)`; like a tap, each fails with `OBSCURED` when another window covers its
  touch point (the centre; a fling's start).
- `tap-agent`: `tap --double`, `fling`, `drag`, `pinch`, `rotate`, `screen`, `permission`, and
  `app foreground|background|open-link` (CLI and MCP).
- **Keyboard**: `keyboardShown()` (`DeviceInfo.keyboardShown`, any IME), `hideKeyboard()`
  (Back only when a keyboard is up), `Element.imeAction()` runs a focused field's action key
  (Search, Go, Send, Done; API 30+, `UNSUPPORTED` below).
- **Clipboard**: `setClipboard(text)`, `clipboard()`; reading does not move focus (on API 31+
  Android shows its "pasted from your clipboard" notice).
- **Toasts**: `device.awaitToast(text?, mode, packageName?)` returns `Toast{text,
  packageName}` seen in the last 3.5 s or arriving before the timeout (`WAIT_TIMEOUT` /
  `NO_TOAST`), from any package unless one is named; `app.awaitToast(text?, mode)` names the
  app's.
- **App**: `launch` / `coldLaunch` take typed intent extras (string, boolean, int, long,
  float; Python `tap_e2e.Long` for 64-bit), `revokePermission(name)` (`AppService.RevokePermission`), and
  `isPermissionGranted(name)` to read it back (`AppService.IsPermissionGranted`).
- `tap-agent`: `submit`, `keyboard`, `clipboard`, `toast [--package]`, `app revoke` (CLI and MCP).
- `DeviceInfo.autoRotate` / `auto_rotate`: whether the sensor turns the display (false while a
  rotation is frozen), so a test can tell what detach restored.
- **Device conditions** for the session: `setAnimations(enabled)` (the three animation
  scales), `setDarkMode(enabled)` (API 29+), `setFontScale(scale)` (0.5..2.0), `setDensity(dpi)`
  (100..1000, `null` = physical); Python `set_animations`, `set_dark_mode`, `set_font_scale`,
  `set_density` (`DeviceService.SetAnimations/SetDarkMode/SetFontScale/SetDensity`). Each change
  is read back, and `DeviceInfo` reports `animationsEnabled`, `darkMode`, `fontScale`,
  `densityDpi`.
- **App languages** (API 33+): `App.setLocales(tags)` / `locales()` (Python `set_locales` /
  `locales`; `AppService.SetLocales/GetLocales`), BCP-47 tags checked and canonicalized by the
  server.
- Rotation, the device conditions and app languages are captured before the session's first
  change, journaled, and restored on detach; when the server died first, the next attach
  restores them (a restore that does not read back quarantines the device; a setting
  Android writes back at its default once deleted, `font_scale` 1.0, counts as restored). New failure
  reasons `UNSUPPORTED_API` (detail `REQUIRES_API_<n>`) and `DEVICE_SETTING`.
- `tap-agent`: `condition [animations|dark-mode|font-scale|density] [value]` and
  `app locale <package> [tags|system]` (CLI and MCP).
- **Network switches** (API 29+): `setNetwork(airplaneMode?, wifi?, mobileData?)` (Python
  `set_network`; `DeviceService.SetNetwork`) turns the real switches on or off, nothing mocked;
  read back, `DeviceInfo.airplaneMode` / `wifiEnabled` / `mobileDataEnabled`. A device reached
  over ADB on the network refuses Wi-Fi off or airplane mode on (`DEVICE_SETTING`).
- **Device languages**: `setSystemLocales(tags)` (Python `set_system_locales`;
  `DeviceService.SetSystemLocales`) sets the device-wide locale list through the Tap driver app,
  read back in `DeviceInfo.systemLocales`.
- **Mock location**: `setLocation(latitude, longitude, accuracyM?, altitudeM?)` (Python
  `set_location`; `DeviceService.SetLocation`, driver command `set_location`): the gps and
  network providers (and fused, API 31+) report the fix, re-sent every second; the driver
  becomes the mock-location app and location is turned on if it was off. Detach removes the
  test providers again (they outlive the driver and its app-op).
- Network, languages and location are restored on detach like the other conditions.
- **Accessibility actions**: `Element.performAction(StandardAction)` and
  `performCustomAction(label)` (Python `perform_action` / `perform_custom_action`) run a node's
  action as a screen reader does; one the node does not offer fails before input
  (`ACTION_REJECTED` / `ACTION_NOT_OFFERED`). `Element.setProgress(value)` (`set_progress`)
  sets a slider in its own units (`OUT_OF_RANGE` outside them). `ElementSnapshot` gains
  `actions`, `customActions` and `range`.
- **Location accuracy**: `choosePermission(choice, accuracy)` picks Precise or Approximate on
  the Android 12+ location dialog; `PermissionPrompt.accuracies` lists what it offers.
- **Files and gallery**: `pushFile(devicePath, bytes | path)`, `pullFile(devicePath[, path])`,
  `addMedia(fileName, bytes)` / `addMedia(path)` (Python `push_file`, `pull_file`, `add_media`;
  `DeviceService.PushFile/PullFile/AddMedia`, streamed, at most 512 MiB). Tap never overwrites a
  device file it did not create, reads every write back, and removes what it created on detach;
  media goes to `Pictures/Tap` or `Movies/Tap` and is indexed by the media scanner. New failure
  reason `DEVICE_FILE`.
- `tap-agent`: `condition airplane-mode|wifi|mobile-data|locale`, `location`, `action`,
  `progress`, `permission --accuracy`, `push`, `pull`, `media` (CLI and MCP: `set_location`,
  `accessibility_action`, `set_progress`, `push_file`, `pull_file`, `add_media`).
- **Notifications as data**: `awaitNotification`, `notifications()`, `openNotification(…,
  action?)` and `dismissNotification` (Python `await_notification`, `notifications`,
  `open_notification`, `dismiss_notification`; `App.awaitNotification` for one app's; driver
  commands `await_notification`, `list_notifications`, `open_notification`,
  `dismiss_notification`) read the notifications through a notification listener in the Tap
  driver app, which gets notification access for the session and loses it on detach. Open and
  dismiss act on exactly one match; an ongoing notification is not dismissed (`NOT_CLEARABLE`).
  New `Notification` model and wait reason `NO_NOTIFICATION`.
- **Stay awake and accessibility display**: `setStayAwake(enabled)` and
  `setAccessibilityDisplay(highContrastText?, colorInversion?, boldText?)` (bold text API 31+;
  Python `set_stay_awake`, `set_accessibility_display`; `DeviceService.SetStayAwake`,
  `SetAccessibilityDisplay`), restored on detach; `DeviceInfo` reports `stayAwake`,
  `highContrastText`, `colorInversion`, `boldText`.
- `foregroundActivity()` (Python `foreground_activity()`; `DeviceService.GetForegroundActivity`):
  the resumed activity on top, or null.
- Fixed: a setting the device reported absent after a change or a restore was taken as read
  back; it is now a `DEVICE_SETTING` failure.
- `tap-agent`: `notification [list|await|open|dismiss]`, `activity`, `condition
  stay-awake|high-contrast-text|color-inversion|bold-text` (CLI and MCP: `notification`,
  `foreground_activity`).
- **Breaking** (Kotlin, Python positional): `DeviceInfo` gains `keyboardShown`, `autoRotate`,
  `animationsEnabled`, `darkMode`, `fontScale`, `densityDpi`, `airplaneMode`, `wifiEnabled` and
  `mobileDataEnabled` constructor parameters (and `systemLocales`, defaulted).
- **Breaking** (Kotlin, Python): `DeviceInfo.displayRotation` / `display_rotation` is now a
  `DisplayRotation` instead of an int (the wire field is unchanged); `DeviceInfo.orientation`
  derives portrait or landscape from the size.
- Tap Studio records every device action: on an element, double tap, drag to, pinch, fling,
  submit (IME action), its accessibility and custom actions and a range's value; in the App tab,
  foreground, background, open link, revoke, the app's languages and launch extras; and a new
  **Device** tab (key 5) that reads the device back and records rotation, wake/sleep/unlock, the
  keyboard and clipboard, the permission dialog, notifications and toasts, the conditions and a
  mock location, and device assertions (foreground activity, keyboard, clipboard).
  `tap-recording/1` gains the `device_wait`, `device` and `device_assertion` steps; the studio
  API gains `DescribeElement`, `GetDeviceStatus` and `ListNotifications`.
- **Field state in screen snapshots** (driver, server, Python client, protocol additive):
  `ScreenNode` gains `showing_hint`, `content_invalid` and `error` (the text a
  `TextView.setError` put on the node), so a snapshot tells an empty field from one holding its
  hint's text and shows validation errors. The driver writes the hierarchy dump itself (the same
  XML as UiAutomator's, plus these attributes); it stays diagnostic only. Tap Studio forwards
  the fields to the page. `tap-agent` prints `empty` / `hint=`, `invalid` and `error=`; its diff
  includes current validation errors even on nodes whose refs are unchanged.
- The event log's `set_location` call records the `accuracy_m` and `altitude_m` it gave.
- `tap-agent`: `app launch|cold-launch --extra KEY[:TYPE]=VALUE` (MCP `extras`) and
  `location --altitude` (MCP `altitude_m`).

Fixed:

- Server: `forceStop` and `clearData` return only once the app's task is gone too, not just its
  process and activities. Android removes the emptied task about a second later and kills the
  app's running process with it: a launch right after `clearData` lost its new process and
  waited ~40 s for Android to start the app again (the launch timed out).
- Server: `clearData` force-stops the app before `pm clear`. On API 34, clearing a running app
  left a kill of its processes pending for up to a second after its task was gone, which still
  took the process of a launch right after `clearData`.
- Server: detach turns bold text off when the session set it on a device that had no
  `font_weight_adjustment` setting; deleting the setting alone left text bold until a reboot.
- Server: `addMedia` indexes the file on API 30+ (the scan went to the internal volume and
  failed, so apps' gallery queries could miss it) and checks it is in the images or video
  collection.

## 0.0.2 — 2026-09-30 (alpha)

`daemon/v0.0.2`, `client-kotlin/v0.0.2`, `client-python/v0.0.2`, `client-agent/v0.0.2`, and the
first `client-studio/v0.0.1`. `tap-agent` and `tap-studio` need `tap-e2e` 0.0.2.

- Licensed under Apache-2.0 (`LICENSE`; the wheels and POMs carry it).
- Every GitHub Release carries the complete documentation as Markdown
  (`tap-docs-<version>.zip` / `.tar.gz`).
- **Tap Studio** (experimental, new family `client-studio`: `tap-studio`): a browser inspector
  and action recorder on a running server. It shows the live screen with its elements. Act,
  Assert and Inspect modes record element steps (taps, text and secrets, scrolls, swipes, keys,
  app calls, checks), never coordinates. Steps can be edited (another selector candidate, or
  one typed in the Kotlin DSL with a live match count), reordered and replayed. Recordings are
  exported and reopened as `tap-recording/1` JSON. Guide: `docs/studio/`.
- **Notifications and quick settings** (engine, Kotlin and Python clients, Tap Studio): the
  `open_system_panel` command (`tap.v1.OpenSystemPanel`) opens the notification shade or quick
  settings with the system's accessibility action. The clients expose it as
  `device.openNotifications()` / `openQuickSettings()` (`open_notifications()` /
  `open_quick_settings()`). Tap Studio records it from the device rail beside the screen, which
  also has Back, Home and Recent apps. `tap-agent` has `panel notifications|quick-settings`
  (MCP `open_panel`) and the `recents` key.
- **Selector candidates** (engine, Python client): `ScreenSnapshot(selector_candidates=true)`
  gives each node every selector that matched only it, best first
  (`device.screen_snapshot(selector_candidates=True)`), for inspectors such as Tap Studio.
- **Fix** (engine): `forceStop` and `clearData` (so also `coldLaunch`) now return only once
  Android has destroyed the app's activities, not just its process. On API 34 a launch right
  after `clearData` could otherwise be killed with the old task and hang on its splash screen
  until `am start -W` timed out.

## 0.0.1 — 2026-09-29 (alpha)

First release of the engine (`daemon/v0.0.1`: the `tap` server with the bundled driver, plus
`tap-schema` / `tap-api`), the Kotlin client (`client-kotlin/v0.0.1`: `tap-client`,
`tap-junit5`), the Python client (`client-python/v0.0.1`: `tap-e2e`) and the agent tools
(`client-agent/v0.0.1`: `tap-agent`). `tap-sync-sdk` is not released yet.

- **Server** (`tap start` / `stop` / `status`, JVM or native binary for Linux x86-64 and macOS
  arm64): one per machine, loopback gRPC with a bearer token, per-device locks shared across
  processes, driver install and session lifecycle, app lifecycle (install, launch, cold launch,
  force-stop, clear data, grant permission, process identity), failure capture.
- **Driver**: a separate package from the app under test; selectors re-resolved on every
  command with exactly one match required before any input; explicit waits (visible, gone,
  exactly one, app visible, screen stable, animation end); taps, text input, keys, swipes,
  scrolls, screenshots, hierarchy dumps; transport loss after acceptance reported as
  `INDETERMINATE`, never replayed.
- **Kotlin client**: coroutine SDK (`Device`, `App`, `Element`, selector DSL, typed errors
  and models) and the JUnit 5 extension (`@TapTest`, `tapTest { }`, named roles for several
  devices, `DeviceBarrier`, failure artifacts, opt-in per-class device reuse).
- **Python client**: the same API, synchronous, with a pytest plugin (`tap_device`,
  `tap_devices`, failure artifacts, device reuse by scope).
- **Agent tools** (experimental): `tap-agent`, a CLI and MCP server for coding agents — held
  sessions, screen snapshots with refs, `--settle` diffs, and `export` of the session's device
  calls as JSON (`tap-events/1`).
- **Experimental**: app synchronization (`awaitIdle` / `await_idle`, `syncAuthority`; Kotlin
  `@ExperimentalTapApi`) and the agent surface (held connections, `resume`, screen snapshots,
  `resolve_ref`, `event_log`, the `Events` RPC, `tap-events/1`).

Known limits: see `.docs/framework-gaps.md` — no crash/ANR error codes yet, no helper for
runtime permission dialogs, no WebView support, app synchronization only works with apps the
driver can see (the fixture app on Android 11+), and typed text (passwords included) is
recorded verbatim in the event log.
