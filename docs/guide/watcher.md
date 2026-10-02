# Watch device actions and video

`tap-watcher` is a standalone, read-only browser client. It shows connected devices,
their owning connections, streamed actions, and shared screen video. It does not attach,
control the device, wake its screen, keep an owner alive, or enable screenshot tracing.
Tap Studio remains a separate inspector/recorder.

## Start

Start the daemon explicitly with `tap start`, then run `tap-watcher` and open the
login URL it prints. The browser server binds only to loopback; the daemon token stays
on the back end. Closing the watcher releases its readers, not the owner's connection.

Video requires an installed **scrcpy 4.1 server JAR** on the daemon host. Set
`TAP_VIDEO_SERVER` before starting the daemon when it is not at
`/usr/share/scrcpy/scrcpy-server`. No server binary is bundled. Video uses no audio or
controls and does not install an app. Unsupported/missing capture is reported in the
viewer; action streaming still works. Use a Chromium browser on localhost with H.264
WebCodecs support.

For a source checkout:

```sh
uv pip install --python .venv/bin/python -e clients/python -e clients/watcher
(cd clients/watcher/web && bun install && bun run build)
.venv/bin/tap-watcher
```

## Playback and clips

Select a device, then an action to seek nearby video. Play, scrub, or return to **Live**.
**Action frames** is off by default. It displays approximate Before/After/Compare samples
from existing video; it never requests screenshots or changes owner configuration.
Missing samples show unavailable.

**Start recording** creates a fresh encoded window with preceding keyframe preroll.
**Stop & save** stops that recording and automatically saves `video.mp4`, `steps.json`, and
`clip.zip` under `~/.tap/recordings/watcher/<recording-id>/` on the watcher server's machine.
Set `TAP_WATCHER_RECORDINGS` before starting `tap-watcher` to use another directory.
**Resume recording** starts a **separate clip**, not an append to the old one.

**Saved recordings** lists clips for this device across browser/server restarts and plays
them from disk; download the ZIP or JSON there. The library is capped at 2 GiB; old recordings
are never silently deleted. Move/delete files yourself when full. Save failures are shown
and **Retry save** retries the in-memory recording; do not switch devices or close the page
before a failed save succeeds.

**Set in** and **Set out** mark a clip; **Export clip** downloads a ZIP containing
`video.mp4` and `steps.json` to your browser's download folder. Export is disabled while
recording. Scrubbing or playing freezes the playback window and leaves Live mode; incoming
preview frames no longer change that slider's range or position. Click **Live** to follow
incoming video again. Live preview keeps a rolling memory buffer, but does not create files
unless a recording is stopped and saved. Closing the page while recording does not save it.

Buffers are limited to **120 seconds and 32 MiB**, with whole-GOP eviction. Recording
also stops after two minutes of wall time. Earlier frames expire; sequence gaps reset
unsafe decoder history. Clips start at the preceding keyframe, so the actual origin can
precede the requested trim-in. JSON retains original action/frame timestamps, clock and
stream identity, actual origin, offsets, partial boundary actions, and retention/gap
information. MP4 muxing preserves variable presentation timestamps without re-encoding.

Host packet receipt is **not device capture or input execution time**. Action boundaries
measure host RPC work. Comparisons require the same monotonic clock identity and remain
approximate because encoding, transport and frame rate introduce delay. Static screens
may emit no new packets: media duration is not recording wall duration. Export does not
invent frames to fill that gap. There are no inferred test names or outcomes; test-runner
markers are deferred.

The daemon shares one producer per serial, capped at four producers and eight readers
per producer. Slow readers are disconnected instead of blocking input; last-reader exit
stops that producer. Unproven cleanup gates capture restart on that serial.
