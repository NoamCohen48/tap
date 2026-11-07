# Test watcher: many devices (the wall)

Date: 2026-11-07. Decision record + what was built. Builds on
[test-watcher.md](test-watcher.md) (one device at a time) and [shared-video.md](shared-video.md).

## The job

The owner wants `tap-watcher` to follow many devices at once, up to about ten. The first idea
was a security-camera wall of every phone. After the mocks below, the owner set the main job:
**seeing that everything runs is not the job; opening one device and investigating it (its
history, its steps) is.** The fleet is context for that, not the point.

## What was compared

Two mocks in this folder (simulated devices, no back end):

- [test-watcher-layouts-demo.html](test-watcher-layouts-demo.html) has three layouts:
  - **Wall**: a grid of live screens.
  - **Spotlight**: one large screen with a filmstrip and "follow failures".
  - **Lanes**: time-first rows per device, with frames at the cursor.

  Owner's verdict: Lanes buries the job, and Spotlight shows it but is not built around it. The
  Wall does it best, because you open a device and investigate it. The Wall's weak point was
  the running step: it was laid over the bottom of the screen and cut off.
- [test-watcher-wall-demo.html](test-watcher-wall-demo.html) compares two designs after a
  separate design review:
  - **Refined wall**: the step moves under the screen. Failures stay until seen. Opening a
    device gives the full investigation view with the other devices beside it.
  - **Triage desk**: devices → steps → evidence, opening on a "needs attention" feed of
    unseen failures.

  The owner chose the refined wall.

Inspiration: security video walls (an alarm puts a camera on the big tile; small tiles get
low-resolution substreams), Playwright's trace viewer (action list → Before / After), and
[appium-device-farm](https://github.com/AppiumTestDistribution/appium-device-farm). Its
device cards are text only, and its session page lists each command with its parameters,
response and screenshot, logs in tabs beside a video player. The second mock's device-log tab came from there; it is not built yet.

## Decisions

1. **The wall first, then the device.** Live opens on the wall (`#/live`). Opening a tile
   (`#/live/<serial>`) shows the existing single-device view: screen, scrubber, Compare,
   clips, actions.
2. **The wall stays beside an opened device as the device pane, and its width decides its
   shape.** The owner liked two of the proposals, "a wide panel over the wall" and "a
   sidebar of phones". Both are the same pane at two widths:
   - Narrow (the default, 152 px) is a column of small live screens.
   - From 380 px it is the wall again, with full captions.

   Drag the edge to resize, or double-click it to switch. The width is kept per browser and
   capped to leave room for the screen and actions. The opened tile scrolls into view; the
   device header names its model, serial, API level, state and holder, with device arrows and
   a keyboard-shortcut disclosure. The narrow layout does not overflow horizontally.
3. **The step is never drawn over the screen.** Under each screen, the tile shows:
   - the state in words, and the model;
   - the step, in full on two lines;
   - how it is going: `running 3.2 s` (amber, "slow", from 10 s), `✓ 0.4 s`, or
     `✕ NOT_FOUND 12 s ago`;
   - the connection.

   State is also shown by the border: dashed while in use between calls, red for an unseen
   failure, and dimmed when free or offline.
4. **A failure stays on its tile until the device is opened** (a red band: code, time, `+N`).
   A failure from a minute ago must still be visible. "Seen" means the device was opened.
   The marks are kept in the browser's localStorage, per viewer. Opening a device with unseen
   failures prefers the newest one within the retention window, falling back to the newest
   unseen one. When the video's gone, the stage names the oldest video available rather than
   implying the full 120 s were kept.
5. **Stable order.** Tiles are sorted by model, then serial, and never by state. A landscape
   screen spans two columns.
6. **Another device failing never takes over the screen.** Its tile turns red; nothing moves.
7. **Live screens can be switched off.** Every streamed screen runs a scrcpy encoder on its
   device. With screens off, the wall still shows steps and outcomes.

## Investigation view

The mock-versus-player review identified nine gaps; all are addressed in the device view:

- The device header and pane identify the opened device; phone-width layout stays within
  the viewport.
- Before / After has a visible approximation note and signed frame offsets. It replaces the
  paused badge instead of showing both. Sub-50 ms offsets read "as it started/ended".
- Previous/next failure buttons and `n` / `Shift+N` select the failure and open comparison.
- Search and filters reset per device. Choosing Failures moves a successful selection to its
  nearest failure (newer on a tie).
- Actions use one-line rows and inline selected details, with session and connection
  separators rather than a connection repeated on every row.
- The scrubber shows the selected duration, running call, unheld gaps and a width-aware axis.
- Record is a secondary button; an active recording remains prominent.
- Offline/unauthorized devices explain why no screen is available, without a Live badge.

The existing search, frame stepping, zoom, rotation ticks, clip bar and resizable pane stay.
The mock's device-log tab remains deferred: it needs daemon logcat streaming, not a page fix.

## How it is built

- **Daemon: calls in progress.** `Watch` now sends a `call_started` activity when each logged
  call begins, so a tile can say what runs now. Before, a call appeared only when it ended.
  The finished `event` follows. The page pairs the two by connection, serial and
  `started_monotonic_ns`. A cancelled call, which is never logged, ends with its device's
  detach or its connection's close (`.docs/server-api.md`).
- **Daemon: 16 devices of video.** `VIDEO_MAX_PRODUCERS` was raised from 4 to 16. Each producer
  encodes on its own device, so the cap bounds host processes and sockets.
- **Watcher back end: `WatchScreens(serials)`.** One stream carries every tile's screen, tagged
  by serial, each from its latest key frame (no history).
  - **Why one stream:** found while testing, a browser keeps at most six HTTP/1.1 connections
    to a host, and every open stream holds one. With one `WatchVideo` per tile, the opened
    device's own video never connected.
  - **Slow page:** its feed drops it and it starts again from the next key frame.
  - **Failed serial:** gets an `error` update and is retried with backoff (3 to 30 s).
- **Page.**
  - `fleet.ts`: the order and the tile readout, unit-tested.
  - `activity.ts`: the running call and unseen failures.
  - `screens.ts`: the hub that splits the shared stream into per-serial buffers.
  - `Wall.tsx`: the layout that fits every tile without scrolling when it can.
  - `DeviceTile.tsx` and `FleetPane.tsx`.
  - `DeviceRail.tsx` is gone.

## Costs and limits

- **Device load.** Each streamed device runs a scrcpy encoder while the wall or pane is open.
  That is the same per device as watching it alone, but now on every device at once.
- **Watcher memory.** The back end keeps 120 s / 32 MiB of history for every serial a page
  streams, which is what lets a failure seen on the wall still be in the opened device's
  video. Ten devices can use about 320 MiB; there is no global cap yet.
- **Browser.** It decodes one H.264 stream per tile, with a buffer of about one GOP each. To
  keep that affordable:
  - A tile draws at its shown size and at most 5 fps; the opened device keeps full rate.
  - A tile scrolled out of view, or in a hidden tab, stops decoding. A hidden tab closes the
    stream, so the devices stop encoding for it.
  - The pane does not stream the opened device a second time.
  - The activity store tells views about changes at most every 200 ms. The stage, the step
    list and each tile re-render only when what they show changes.

  Measured with a fake daemon of ten 472×1024 streams at 15 fps, headless Chromium at
  1440×900, CPU of every browser process:

  | View | Before | After |
  |---|---|---|
  | Wall | 85% (main thread 46%) | 48% (main thread 21%) |
  | Device open | 97% (main thread 57%) | 28% (main thread 12%) |

  Before, 30% of the main thread on the wall was `drawImage` of full-size frames. These
  numbers come from software decoding with no GPU; ten real devices have not been measured.
- **Verification so far.** Page and back-end unit tests, plus a scripted fake daemon of six
  devices streaming real H.264, with calls starting, ending and failing, driven through the
  real back end and checked in a browser. The follow-up uses ten streaming devices plus an
  offline one, with periodic session detach/attach, to check comparison, failure navigation,
  filter selection/reset, session gaps, shortcuts and the 400 px layout. The device matrix
  was not run.

## Deferred

- **Expand in place (option for later).** Opening a device grows its tile inside the wall to
  cover most of it, with screen, steps, scrubber and Before / After. The other devices stay in
  the grid around it as smaller tiles. This works like a security wall, where one camera
  spans several cells. The wall keeps its shape, and the opened device is literally one of
  its tiles. It was not chosen for now, because the pane already keeps the wall beside the
  investigation without a second layout of the stage and actions. Revisit it if users find
  the pane switch too abrupt or want the wall's spatial memory kept while investigating.
- **Triage desk** (in the second mock): a fleet-wide "needs attention" feed of unseen
  failures, each opening its device at that step with Before / After. It could come back
  later as a panel on the wall.
- **Thumbnails instead of full streams.** The back end could decode with PyAV and send small
  JPEGs at about 2 fps. That would cut browser decoding, and with a lower-resolution encoder,
  device load.
- **"Seen" shared between viewers** (today it is per browser).
- **A global cap on the watcher's video memory.**
- Tap Studio multi-device support comes next and follows its own record.
