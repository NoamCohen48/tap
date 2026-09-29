# Showing a device's screen live — research and decision

Date: 2026-09-29. Research note + decision for Tap Studio (`recorder.md`). Question: how should a
client show a device's screen continuously, with an element overlay that lines up with it?

## Decision

**v1: paired frames, the way Maestro's web Studio did it.** The studio loops *snapshot →
screenshot* on the attached device and pushes each pair to the page. Each frame is a picture
*and* the nodes it shows, so the overlay is always aligned with the picture. No daemon change:
`ScreenSnapshot` and `Screenshot` already exist. Encoded video (`ScreenStream`, below) is a later
layer on top, not a replacement: even with video the overlay and every click come from a
snapshot, so this loop stays.

Owner's call, 2026-09-29: start with the simplest mechanism that is correct; video when the frame
rate proves insufficient.

## How other tools do it

| Tool | Mechanism | Rate / latency | Overlay |
|---|---|---|---|
| **Maestro Studio (web, until 2026-05)** | Server-sent events from an endless loop: under one mutex, `maestro.viewHierarchy()` then `takeScreenshot()`; each event is a JSON `DeviceScreen` (PNG URL, device size, element list); the last 10 screenshots are kept as files (`maestro-studio/server/src/main/java/maestro/studio/DeviceService.kt`, `/api/device-screen/sse`, commit `1942c60`). Removed from the CLI in May 2026 (`f0da81b`, "remove bundled Maestro Studio in favor of desktop app"). | as fast as dump + screenshot (~1–3 frames/s) | paired with each frame |
| **Appium UiAutomator2 server** | MJPEG broadcaster inside the UiAutomator2 instrumentation, device port 7810: a thread loops `UiAutomation.takeScreenshot()` → scale → JPEG → `multipart/x-mixed-replace` to every connected client, sleeping 500 ms when none (`server/mjpeg/MjpegScreenshotStream.java` at `4a81391`). Settings: `mjpegServerFramerate` 1..60 (10), `mjpegScalingFactor` (50 %), quality (50). The driver forwards it with `appium:mjpegServerPort` and can serve screenshots from it (`docs/mjpeg.md` at `7a54db0`). | ~10 fps | none; the inspector refreshes the source separately |
| **Appium `mobile: startScreenStreaming`** | `adb shell screenrecord` H.264 piped through GStreamer on the host into MJPEG | a few hundred ms | none |
| **scrcpy** (Genymobile, Apache-2.0) | Server jar pushed to the device, run with `app_process` as the shell user; captures the display into `MediaCodec` (H.264/H.265/AV1) and streams the raw bitstream over an ADB-forwarded socket. API 21+, no root, no app install. | 30–120 fps, 35–70 ms | none (it is a mirror) |
| **Browser scrcpy clients** (ws-scrcpy, Tango `@yume-chan/scrcpy-decoder-webcodecs`) | Relay the scrcpy bitstream to the page and decode with the WebCodecs `VideoDecoder` (hardware); needs a secure context (`localhost` qualifies) | near scrcpy's | none |
| **`adb exec-out screenrecord --output-format=h264 -`** | Android's own recorder, raw H.264 on stdout | frames only on change; higher latency than scrcpy; a per-run time limit, restart on rotation | none |

## Options for Tap

| Option | Where it runs | Pros | Cons |
|---|---|---|---|
| **A. Paired frames** (chosen for v1) | studio back end calling `ScreenSnapshot` + `Screenshot` | no new daemon/driver code; overlay always aligned; same calls as the agent surface | ~1–3 frames/s; a dump + PNG per frame; each frame occupies the driver's single command executor |
| B. MJPEG from the driver (Appium) | driver | ~10 fps, no new device process | per-frame CPU inside the process whose job is executing commands on time; competes with its `UiAutomation`; no overlay. **Rejected.** |
| C. Encoded video from the daemon: scrcpy video-only | daemon (`:host:core`), a bundled server jar | best latency and rate; no `UiAutomation`, so commands are undisturbed; the same stream can be saved as a video artifact | a second on-device component to bundle, pin and clean up; overlay still needs A |
| D. Encoded video from the daemon: `screenrecord` | daemon, `adb exec-out` | nothing to install | higher latency, time-limit restarts, rotation restarts; overlay still needs A |
| E. The studio runs scrcpy/`adb` itself | client | — | breaks "all ADB lives in `:host:core` behind the server". **Rejected.** |

## v1 design (option A)

- **Order: snapshot, then screenshot.** Both are commands on the device's single executor, so
  they run back to back; on a quiet screen they agree. On a moving screen they can disagree by
  the time between them — inherent to any pairing — so a frame taken while the screen changes is
  marked *moving* (its snapshot shows `ADDED`/removed nodes against the previous one) and the
  page draws its overlay as provisional.
- **Clicks are resolved on the newest frame's snapshot.** Before acting, the back end checks the
  target's selector still matches exactly once (the driver does that anyway: `AMBIGUOUS` /
  `NOT_FOUND` before input), so a stale overlay can at worst make an action fail cleanly, never
  hit the wrong node.
- **Actions have priority over frames.** The loop is paused while an action, assertion or app
  call is in flight and resumes after it (with the post-action settle), so a frame never sits in
  the driver's queue ahead of the user's tap.
- **Adaptive rate.** Fast while frames change, slowing down on an unchanged screen (the
  snapshot's diff says whether anything changed), stopped when no page is open.
- **Transport.** A `StudioService` server-streaming call (Connect, `recorder.md` decision 2):
  each message is the snapshot and the PNG as protobuf `bytes`, binary-encoded, so no base64.
  Frames are not kept on disk.
- **Measure it.** Per-frame cost (snapshot + screenshot) on emulator-5554 and 85e49002 on the
  fixture screens, recorded here, is the evidence for whether and when to add video.

### Measured (2026-09-29, 85e49002 only)

Samsung SM-J810G, API 29, 720x1480, the fixture's main screen (76 nodes, 120 selector
candidates), JVM daemon, `tap-studio`'s `DeviceWorker` loop (medians, p90 in brackets):

| | median |
|---|---|
| `ScreenSnapshot` with selector candidates | 70 ms (73) |
| `Screenshot` (PNG, 73 KiB) | 357 ms (380) |
| a frame (snapshot + screenshot) | ~430 ms, so ~2.3 frames/s while the screen moves |
| `Count` alone / while frames stream | 62 ms / 98 ms (167) |
| `Perform` tap (wait + tap) | 251 ms |
| tap returned → first frame taken after it | 488 ms (906) |

The screenshot is 80 % of a frame. Two frames a second is enough to follow what a tap did but
not to watch a scroll or an animation, which is what video would add. A frame's cost is in the
screenshot, so a cheaper capture (smaller or JPEG) would help the loop before video would.
The first run showed `Count` at 263 ms while frames streamed: every user call woke the
settled loop, so the next count waited behind a screenshot. Only calls that may change the
screen (`Perform`) wake it now. emulator-5554 is not measured yet.

## Later: encoded video (options C/D)

When paired frames are too slow in practice, add `DeviceService.ScreenStream(client_connection_id,
attached_device_id, max_size?, max_fps?, codec?)` → stream of `config` (codec, size, rotation,
codec configuration) and `frame` (bytes, presentation time, key frame) messages, owner-checked,
started only while a client streams and stopped serial-specifically; the page decodes with
WebCodecs. The video then shows motion while the paired-frame loop keeps supplying the overlay,
re-snapshotting when the video goes quiet (encoders emit frames only on change). The producer
(scrcpy video-only vs `screenrecord`) is chosen by a spike on the local matrix: latency, frame
rate while scrolling, device CPU while the fixture suite runs, rotation, time-limit behaviour,
WebCodecs in Chromium and Firefox, and — for scrcpy — bundling, pinning and licence notice. The
same RPC can then back a video artifact (`framework-gaps.md`, artifacts: optional screen
recording).

## Sources

- Maestro Studio server: <https://github.com/mobile-dev-inc/Maestro/blob/1942c60628474c3a56bbe7eb31a0d58422f685dc/maestro-studio/server/src/main/java/maestro/studio/DeviceService.kt>
- Appium MJPEG stream: <https://github.com/appium/appium-uiautomator2-server/blob/4a8139161cbb3aad078e36539995eb734297d56e/app/src/main/java/io/appium/uiautomator2/server/mjpeg/MjpegScreenshotStream.java>
- Appium MJPEG docs: <https://github.com/appium/appium-uiautomator2-driver/blob/7a54db007aa8a44f5df21cd0b52afc13c0d277ae/docs/mjpeg.md>
- scrcpy: <https://github.com/Genymobile/scrcpy>
- WebCodecs decoder for scrcpy (Tango): <https://tangoadb.dev/next/scrcpy/video/webcodecs/>
- ws-scrcpy: <https://github.com/NetrisTV/ws-scrcpy>
