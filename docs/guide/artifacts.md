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

## Failure artifacts

When a test fails, the pytest plugin and the JUnit extension call `device.capture()` for each
device of the test, *before* detaching it, while the screen still shows the failure, and save
the result:

```
tap-artifacts/<nodeid>/                       (JUnit: build/tap-artifacts/<class>/<method>/)
├── failure.txt                               the exception and stack trace
├── device-emulator-5554.screenshot.png       screenshot
├── device-emulator-5554.hierarchy.xml        accessibility hierarchy
├── device-emulator-5554.device-info.json     API, model, display, focused package
└── device-emulator-5554.driver-log.txt       the driver's log for the session
```

The file prefix is `<role>-<serial>`, so a two-device test yields `sender-…` and `receiver-…`.
Devices are captured in parallel, and so are the four parts of each device, each within 30 s, so
one slow or hung device or part does not cost the others their artifacts. Capture never masks
the original failure: a file that cannot be produced (the device went away, the time ran out) is
simply missing, and the test still fails with its real error. `TAP_CAPTURE=off` turns this off
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
