# Shared video — capture implementation checkpoint

Date: 2026-10-02, updated 2026-10-03. Part of the standalone watcher work: the passive scrcpy
capture in `:host:core`, its shared fan-out in the daemon (`WatchService.WatchVideo`) and the
watcher that shows it. The design is in [test-watcher.md](test-watcher.md); the review that
reshaped the watcher in [test-watcher-review.md](test-watcher-review.md).

## Implemented

- `:host:core` contains `ScrcpyVideoSource`, a passive scrcpy **4.1** server adapter, and
  `ScrcpyVideoProtocol`, its independently implemented H.264 socket reader. Reference:
  Genymobile/scrcpy commit `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`, especially
  `server/src/main/java/com/genymobile/scrcpy/device/Streamer.java`. No copied code or
  bundled server binary. The caller supplies the server artifact path.
- Every producer has a random 31-bit SCID, its own `/data/local/tmp/tap-video-<scid>.jar`,
  an ephemeral serial-specific forward, and a `tapv-<scid>` process name. It never
  overwrites `scrcpy-server.jar` or removes unrelated forwards. `cleanup=false` prevents
  scrcpy's global cleanup from touching someone else's server artifact.
- Explicit `control=false`, `audio=false`, `power_on=false`; 1024 px, 15 fps, 2 Mbps,
  requested one-second keyframe interval. No attachment, driver commands, app lifecycle,
  input, audio routing, screen wake or reboot.
- The shell reports its PID before `exec`. Cleanup closes the socket, validates the
  producer-specific process name before terminating that PID, waits for that identity to
  disappear, reaps the host child, removes only its forward and deletes its private JAR.
  Blocking reads on a static display can be interrupted. Logs retain a 4096-character tail.
  A bounded PID-report signal is awaited before publishing: a fast socket previously raced
  the stdout drain and could skip remote termination. Cleanup failure is a typed
  `VideoCleanupException`; a manager must gate that serial rather than restart blindly.
- Framing validates H.264 negotiation, explicit session metadata, dimension bounds,
  packet flags and a 1 MiB packet limit before allocating payload storage. Truncated
  data is never published. 4.1 session headers expose encoder resets/rotation.
- `MediaClock` supplies a process-local id and monotonic nanoseconds. Logged command/app
  events now include optional start/end boundaries and this clock id. They bracket the
  already-validated host RPC block (including queue/transport work), not actual device
  input execution. Duration comes from their difference. Older events remain unaligned;
  epoch timestamps must not be used to fabricate these boundaries. Each `VideoSample`
  records full-packet receipt immediately after the protocol read, before publication,
  using the same clock id and monotonic clock, plus a host epoch display timestamp.

## Passive evidence

The owner approved probes on `emulator-5554` (API 34) and `85e49002` (Samsung J8, API 29).
No inputs, APK installs, app changes, screen wake or reboot were performed.

| Check | Emulator | Samsung |
|---|---|---|
| Direct 4.1 framing probe, short launch argv | First packet ~426 ms; 460×1024 H.264; 11 decoded frames | First packet ~1401 ms; 498×1024 H.264; 11 decoded frames |
| Product `ScrcpyVideoSource` passive validation | Pending: emulator stopped before this run; not restarted | Passed: first key frame ~1451 ms, 11 frames decoded; remote process, private forward and JAR absent after stop |

Long launch argument lists caused a Samsung native `ACodec::reconfigEncoder4OtherApps`
stack-protector abort with both 4.1 and 3.3.4. Disabling encoder-constraint checks did not
fix it. The compact 4.1 invocation succeeded; product arguments are bounded below 256
UTF-8 bytes, with a regression assertion. This matches reports such as scrcpy issues
[#2841](https://github.com/Genymobile/scrcpy/issues/2841) and
[#6900](https://github.com/Genymobile/scrcpy/issues/6900), but does not prove an exact
threshold or establish a workaround for every Samsung firmware.

Static screens stop emitting after a short initial burst on both tested devices, even
with scrcpy's repeat-frame request. Video PTS duration is therefore not recording wall
time. Frame receipt is approximate visual context, not a guaranteed before/after capture.

Run only this explicitly opted-in validation:

```sh
ANDROID_HOME=~/Android/Sdk ./gradlew :host:validation:deviceTest \
  --tests '*PassiveVideoTest' -Ptap.passiveVideo=true \
  -Ptap.serials=emulator-5554,85e49002
```

It requires the optional 4.1 server (`TAP_SCRCPY_SERVER`, default
`/usr/share/scrcpy/scrcpy-server`) and host `ffmpeg` for a one-frame decode check. Gradle
builds the suite's APK dependencies but this test installs none. The flag defaults off.

Device-free checks passed: `:host:core:test :host:daemon:test :contracts:protocol:test`,
including framing, bounds, idle-read interruption, namespace cleanup and event-clock
assertions. Python/Studio/watcher bindings were regenerated; proto lint passed.

## Shared pipeline and watcher — implemented

`WatchService.WatchVideo` (`watch.proto`) is an authenticated generic stream; `SharedVideo`
owns one producer per serial and atomically snapshots decoder header/keyframe preroll before
live fan-out. The scrcpy server JAR is daemon configuration (`tap start --scrcpy-server`,
`TAP_SCRCPY_SERVER`); a server of another version is reported as a version mismatch.
Retention: none beyond the current GOP, which a joining reader needs to decode at once
(otherwise it waits up to scrcpy's ~10 s key-frame interval); a GOP over 16 MiB
(`VIDEO_JOIN_BYTES`) is dropped. Decided 2026-10-03: the daemon stays stateless, look-back
is the watcher's (it was a 120 s / 32 MiB window, a second copy of the watcher's history). Limits:
four producers, eight readers per producer, 128 updates / 4 MiB per reader. Slow readers are
closed (`VideoReaderOverflowException`) without blocking; last-reader exit stops capture.
Cleanup failure gates restart. Daemon shutdown interrupts producers within the existing
shutdown budget.

The watcher's back end is one daemon reader per serial however many pages are open. It keeps
its own 120 s / 32 MiB GOP history (sent to each page first), so a page can look back and a
clip is cut server-side by frame seq. Recordings run in the back end (a sink on the feed),
muxed incrementally to MP4 with PyAV without re-encoding; cuts include keyframe preroll.
`steps.json` preserves original PTS/host/action clocks, offsets, actual origin and partial
steps. Static tails are not filled with invented samples; wall recording and media duration
differ. The page decodes with WebCodecs: one decoder for live, an exact-frame seek, and
pts-scheduled playback.

Verification (first cut, 2026-10-02): actual daemon `WatchVideo` passed on API 34 emulator and
API 29 Samsung: two simultaneous readers received the same producer/header/keyframe,
cancellation of one left the other alive, H.264 decoded, and no connection was
created/changed. These were passive reads only. The reworked watcher (2026-10-03) was
verified against a fake daemon streaming real H.264 (unit suites and Playwright: live, seek,
Before/After, clip save, library playback); it has not been re-run against devices.

## Still to verify / separate scope

- Simultaneous owner recording, encoding CPU and owner command timings under load;
  rotation and prolonged disconnect/recovery. Passive checks do not establish these.
- Native-image runtime smoke with video (reflection messages are registered and tested).
- Owner-enabled exact screenshot tracing remains separate. The watcher never enables it.
