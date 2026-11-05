# Screenshots and artifacts

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`dump_hierarchy` → `dumpHierarchy`, `save_to` →
    `saveTo`) and takes `Path`s. [Kotlin + JUnit 5](../sdk/kotlin.md#screenshots-and-artifacts)
    shows this page's examples in Kotlin.

## Screenshots and dumps

Each returns a typed value, not a file: keep it in memory, assert on it, attach it to a report
or write it where you like. They share one artifact shape: `bytes` (the serialized form),
`media_type`, `extension` and `save(path)` (which creates parent directories).

| Call | Returns |
|---|---|
| `device.screenshot()` | `Screenshot`: the PNG `bytes` (checked against the server's SHA-256), its `format`, `width` and `height` |
| `device.dump_hierarchy()` | `Hierarchy`: the accessibility tree as `xml` (a string) and as UTF-8 `bytes`. Diagnostic only; lookups never use it |
| `device.driver_log()` | `DriverLog`: the driver's recent `lines` for the session, `text` joined with newlines |
| `device.info()` | `DeviceInfo`: API level, manufacturer, model, display size and rotation, the package owning the focused window, and the [device conditions](device-conditions.md); `bytes` is JSON |

```python
shot = device.screenshot()
print(shot.width, shot.height)
shot.save("build/shots/login.png")
```

`device.capture()` takes all four at once, in parallel, each within a timeout (default 30 s),
and returns a `Capture`: the `screenshot`, `hierarchy`, `info` and `driver_log` parts,
`artifacts` (the produced ones by name) and `failures` (why a missing part is missing). It never
raises for the device, so it is safe in an `except`. `save_to(dir)` writes
`<prefix>.<part>.<ext>` files (the prefix defaults to the serial).

```python
capture = device.capture()
capture.save_to("build/evidence/after-login")
for part, why in capture.failures.items():
    print(f"no {part}: {why}")
```

## Recordings

`device.start_recording()` and `device.stop_recording()` return a `Recording` artifact
(`bytes`, `media_type`, `extension`, `save(path)`). Nothing is recorded unless you ask; this is
not a live screen stream or an automatic failure artifact.

```python
device.start_recording(audio_source="output")
app.element(res("play")).tap()
app.wait(text("0:05")).visible()
device.stop_recording().save("build/recordings/playback.mkv")
```

- **Tracks.** The default is video only (MP4, up to 30 seconds or 16 MiB; 1024 px, at most
  15 fps). Add `audio_source="output"` for video and audio in one Matroska file, or
  `video=False` with an audio source for Opus audio only (up to 60 seconds or 3 MiB).
  `max_seconds` ends the capture early.
- **Audio sources.** `output` (Android 11+) records device playback but redirects it away from
  the device speakers; `playback` (Android 13+) keeps local playback, but apps may opt out;
  `mic` records the device microphone. Android 10 and earlier can record video only. `output`
  records *after* the device's media volume, and that route has its own volume (at the default
  5 of 15 a test tone comes out about 32 dB quieter); `playback` records the app's sound before
  volume. Use `playback` on Android 13+ when levels matter, or set the media volume while an
  `output` recording runs.
- **scrcpy.** The server's machine needs [scrcpy](https://github.com/Genymobile/scrcpy)
  (`tap start --scrcpy PATH`, default `scrcpy` on `PATH`). One recording per attached device;
  detach discards an unfinished one.
- **Timing.** The start call returns while scrcpy is still starting on the device (about half a
  second), so the first moment can be missing, and a capture the device refuses is reported by
  the stop call. Do not stop a recording immediately after starting it. A static screen produces
  no new frames, so a video file can be shorter than the time it was recording.

## Run records

The pytest plugin and JUnit Platform listener create a unique directory for each run under
`tap-artifacts` (JUnit: `build/tap-artifacts`). Pytest prints its location at the end. Concurrent
processes, pytest workers and repeated launcher executions get separate run IDs:

```text
<artifacts-root>/<run-id>/
├── run.json          tap-run/1: runner, engine version, API epoch, times, test outcomes
├── events.jsonl      tap-events/1: incrementally flushed lifecycle events
├── manifest.json     tap-artifacts/1: artifact paths, identities, sizes, SHA-256 and errors
└── tests/<test-id>/<attempt>/...
```

Test IDs hash the runner's identity (pytest node ID or JUnit unique ID); attempts start at 1.
Parameterized/repeated invocations cannot overwrite another invocation's evidence. Device
subdirectories hash the role/serial pair; the manifest maps them back to readable identities.
Only tests using Tap fixtures are included in pytest; JUnit includes tests of `@TapTest` classes.
Setup, test and teardown outcomes are recorded, including skips and failed assumptions.
JUnit container failures are recorded even when no test body can start.

`run.json` and `manifest.json` are replaced atomically on the same filesystem. JSONL records
have increasing `seq`, wall-clock `at_epoch_ms` and monotonic `elapsed_ns`; each complete record
is flushed to disk without waiting for the run to end. After an abrupt process death, missing
`run_finished` or `status: running` means the run did not finalize. Readers should ignore an
unterminated final JSONL line. The files are independently published, not one transaction;
`run_finished` is emitted before final metadata publication. `recording_status` is `recording`
until finalization, then `complete`, or `incomplete` after a recoverable reporting I/O error.
A reporting error never changes the real test outcome.

Events cover `run_started`, `test_started`, `device_attached`, `artifact_saved`,
`collection_error`, `test_finished`, `run_finished`, and JUnit `container_finished`. This first
format does **not** include individual command payloads, HTML or a custom JUnit XML report.
Test identities may contain parameter values; screenshots, logs and `failure.txt` can also
contain application data. Treat the directory as sensitive evidence. Error records retain
exception types, not their potentially sensitive messages.

`TAP_REPORT=off` (`tap_report=off` in pytest, `tap.report=off` in JUnit) disables run records.
Automatic failure capture still uses the legacy test directory layout in that mode.
`TAP_CAPTURE=off` disables capture but keeps run metadata and outcomes.

## Failure artifacts

When a test fails, the pytest plugin and the JUnit extension call `device.capture()` for each
device of the test, *before* detaching it, while the screen still shows the failure, and save
the result:

```
<artifacts-root>/<run-id>/tests/<test-id>/<attempt>/
├── failure.txt                    the exception and stack trace
└── <device-id>/<generation>/
    ├── screenshot.png             screenshot
    ├── hierarchy.xml              accessibility hierarchy
    ├── device-info.json           API, model, display, focused package
    ├── driver-log.txt             the driver's log for the session
    ├── logcat.txt                 logcat (main, system, crash) since attachment
    ├── crashes.txt                crashes and ANRs, only when present
    ├── activities.txt             dumpsys activity activities, at the failure
    └── windows.txt                dumpsys window windows, at the failure
```

The logs come from the device itself, so they cover what the driver cannot see: the app's own
log lines, a crash of the app (its `FATAL EXCEPTION` and stack trace) or an ANR, and which
activity and windows were on screen. They start when the device was attached (on the device's
clock), keep going across app restarts, and are capped at the newest 4 MiB of logcat. Android
keeps logcat in ring buffers, so on a busy device the oldest lines of a long test may already be
gone. `device.device_logs()` (Kotlin `deviceLogs()`) returns the same parts on their own.

Each manifest entry names the test/attempt, role, serial, generation, media type, relative
path, byte size and SHA-256. Missing capture parts have a collection-error entry instead.
Devices are captured in parallel, and so are the parts of each device, each within 30 s, so
one slow or hung device or part does not cost the others their artifacts. The same goes inside
the device logs: a `dumpsys` that hangs leaves a line saying so in `windows.txt`, next to a good
`logcat.txt`. Capture never masks
the original failure: a file that cannot be produced (the device went away, the time ran out) is
missing with its error recorded in the manifest, and the test still fails with its real error. `TAP_CAPTURE=off` turns this off
([Python + pytest](../sdk/python.md#configuration), [Kotlin + JUnit 5](../sdk/kotlin.md#configuration)).

`device.capture()` is an ordinary call, so a test can take the same evidence whenever it wants:
after a step, or in an `except`.

## Reading the hierarchy dump

The XML is UiAutomator's view of every window on screen (each window's root under one
`<hierarchy>`), which is what selectors search. The attributes that selectors match are
`resource-id`, `text`, `content-desc`, `hint`, `class`, `package` (what `device.app(pkg)`
checks), plus the boolean state flags.

A node another window (a dialog, the keyboard) covers completely is left out, as Android
reports it not visible; one covered partly is in the dump, and a gesture on it fails with
`NOT_INTERACTABLE` / `OBSCURED` when its touch point is under the other window.

If the element you wanted is missing from the dump, it is missing from the accessibility tree:
a Compose node without `testTag` + `testTagsAsResourceId`, a `View` with
`importantForAccessibility="no"`, or content not yet laid out. Fix the app's semantics rather
than reaching for coordinates.
