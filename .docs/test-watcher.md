# Test watcher — a read-only live view of running tests

Date: 2026-10-02. Handout + decision record (**proposed**; the direction was approved by the
owner on 2026-10-02, the details below are for the implementing session to confirm or change
with the owner). Worktree: `.worktrees/feat/test-watcher`, branch `feat/test-watcher`.

## Current scope after owner discussion

These decisions supersede the corresponding parts of the original proposal below:

- Build a **separate watcher client**, not a mode in Tap Studio.
- The daemon exposes generic shared capabilities (metadata, event subscriptions,
  video) independently of attachment-owned command APIs. Studio can consume the same
  video API; the daemon does not encode the subscriber's purpose.
- **Test markers and JUnit/pytest integration are deferred.** The initial UI shows
  connection/device action logs, not test names or test outcomes. The future proposal
  is saved in [test-watcher-test-markers.md](test-watcher-test-markers.md).
- The standalone UI concept is [test-watcher-demo.html](test-watcher-demo.html): a
  fullscreen app with panel-local scrolling and default-off action-frame inspection.
- Video-derived before/after views are approximate. Selecting frames by host receive
  timestamps is reproducible for a fixed buffer, but is not deterministic evidence
  of device state immediately before/after an input. Exact tracing remains a separate,
  owner-configured opt-in capability; no viewer toggle enables driver captures.
- The original phase list and marker-dependent trace retention/final capture below
  are historical proposals, not an approved implementation checklist for this scope.

### Implementation checkpoint

Reworked 2026-10-03 after [test-watcher-review.md](test-watcher-review.md), which lists
every finding and how it was fixed. As built:

- **Daemon**: `WatchService` (`watch.proto`). `Watch(after_seq)` is one daemon-wide,
  resumable activity stream: connections opened/closed, devices attached/detached and every
  logged call, with a snapshot of live connections first. `WatchVideo(serial)` is the shared
  scrcpy video; its server JAR is `tap start --scrcpy-server` (`TAP_SCRCPY_SERVER`).
  `ClientConnectionService.WatchEvents` and `VideoService` are gone. Neither RPC names or
  renews a connection.
- **Watcher back end** (`clients/watcher/tap_watcher`): one upstream `Watch` (its own seq, so a
  page's cursor survives daemon restarts; a restart closes the old connections), one
  `WatchVideo` per serial with a 120 s / 32 MiB GOP history, and recordings and clips written
  by the back end: a closed tab loses nothing. The history is held only by the watcher (in
  memory, while a page watches or a recording runs, plus 15 s); the daemon keeps only the current
  GOP, for readers that join mid-stream. Library: `~/.tap/recordings/watcher/<id>/`
  (`TAP_WATCHER_RECORDINGS`), `video-N.mp4` parts + `steps.json` (`tap-watch-steps/2`), ZIP
  built on download, 2 GiB cap that refuses new saves. Entries are deleted only from the
  Library view (one or many) or, opt-in, after `--keep-days N`; leftover `.saving-*` / `.zip-*`
  from an interrupted save are swept at start. The writer holds `flock` on its `.saving-*`
  directory until commit, so the sweep never removes a save in progress (another watcher on
  the same library, or an hourly `--keep-days` sweep during a recording); without flock
  (Windows) it removes only staging untouched for a day. Recording limits: 30 min (wall time) / 1 GiB.
- **Stream changes** (decided 2026-10-03, for tests that rotate): a new header — rotation,
  encoder restart or daemon restart — starts a new *segment* instead of clearing the history
  (back end `History`, page `FrameBuffer`), so look-back, replay and clips cross it; a seq gap
  only waits for the next key frame. Recordings and clips write one MP4 per stream (an MP4
  cannot change SPS/size mid-file without re-encoding, which we never do), played back to
  back. Segments on different daemon clocks are joined on one timeline by wall time;
  playback pauses at most 1 s at a boundary. `SaveClip` names both ends by (stream id, seq).
- **Page**: Live (devices, screen, actions) and Library views; readable action rows; a
  Before / After pair for the selected action behind a Compare toggle (fixed frames on their
  own canvases; moving through the video leaves it); clip mode on the scrubber; keyboard
  shortcuts; light/dark. Contract: `clients/watcher/proto/watcher.proto` (`tap.watcher.v1`).

Timing definitions and capture evidence are in [shared-video.md](shared-video.md); user
instructions in `docs/watcher/`. The HTML demo stays a mock. The sections below are
the original proposal and remain historical.

### Video frame correlation — proposed approach and limits

No per-action screenshot RPC is needed for nearby visual context: keep a bounded video
buffer and associate frames with each command's start/end timestamps. A viewer toggle
controls local buffering/inspection, not attachment configuration or screenshot capture.
Buffering only has evidence from the period the stream was active; earlier actions,
evicted frames and dropped frames must show an unavailable state, not a fabricated pair.

Record command boundaries and frame receive times in the same **daemon monotonic clock**
for correlation and durations; keep epoch timestamps for display. Define exactly where
boundaries are measured (queue admission, dispatch, response), since a host RPC interval
is not the same as device input execution. Include a stream/daemon generation so clock
values across restarts or producers are never compared accidentally. Existing epoch
start plus duration fields alone should not be presented as proving this alignment.

A deterministic selection policy can choose the last received frame at/before start and
first received frame at/after completion plus an explicit optional margin. This policy
is deterministic only for the same recorded buffer; it does not make the selected
images exact. Video encoding, transport and queueing delay vary. A frame received after
completion may have been captured before the action, and low FPS can miss transient
states. A margin neither proves the app settled nor prevents the next action appearing
in the selected frame. Label views as approximate and show sample age/delta and gaps.

Preserve device presentation timestamps where available. A producer spike can measure
capture-to-host latency and investigate device/host clock mapping and uncertainty. PTS
alone does not establish that mapping, and improved timing still cannot guarantee a
sample on each side of every action. Exact execution boundaries would need additional
device instrumentation (requiring owner approval); no such driver change is proposed
here. Opt-in ordered captures provide stronger call-path evidence, at an explicit cost,
but a pre-action screenshot also does not prove a settled post-action state.

### Playback, clips and export — UI requirements

- Selecting an action seeks retained video to the command's mapped start timestamp.
  It does not reconstruct device state, replay input, or issue a screenshot command.
  Automatic before/after sampling is optional; manual seeking works independently.
- A video slider shows elapsed time and action markers. Show command start/end in the
  action details, and let the user scrub nearby footage to judge transitions themselves.
  Seeking may need a preceding keyframe and decoding forward to the requested sample.
  If the timestamp is outside the retained video window, report unavailable rather than
  silently substituting unrelated footage.
- Provide New recording / Stop and temporal trimming (clip in/out). Starting a new
  recording creates a new recording window; it must not accumulate an unbounded file.
  The real implementation needs explicit byte/time caps, gap reporting and expiry
  behavior. Displaying live video and retaining it for export are distinct capabilities.
- Download a video plus `steps.json` (an archive is a possible packaging choice). Include
  connection/serial, command sequence, payload, outcome/error, original start/end times,
  duration and offsets relative to the exported video. Record the clock mapping,
  recording generation, actual clip origin and gaps. Identify steps overlapping a clip
  boundary as partial; do not pretend trimming changes their original timestamps.
  Keyframe-aligned cutting or re-encoding must preserve the documented clip time mapping.
- The current HTML is **only a UI mock**: screens and times are simulated; trim and
  recording controls illustrate states. Download intentionally does nothing. No media
  capture, encoding, daemon call or actual export is part of this demo.

## Goal

A read-only page, like Tap Studio, where the owner can:

1. see which test runs (JUnit / pytest connections) are using which devices right now;
2. follow the test currently running on a device: its name, and each action it performs, as
   it happens, with outcome and duration;
3. watch the device's screen live;
4. see the screen **before / after each action**: the agent-device demo
   (<https://github.com/callstack/agent-device/blob/main/website/docs/public/agent-device-contacts.gif>)
   is the target experience, Playwright's trace viewer the closest product analogy.

The watcher never acts on the device and must not noticeably slow the tests it watches.

## What already exists (read these first)

| Piece | Where | Use for the watcher |
|---|---|---|
| Per-connection event log: every `Execute` except `device_info` / `dump_hierarchy`, and the mutating app calls, with selector as sent, serial, start, duration, driver `Error` / RPC `Failure`; last 2000 | `contracts/proto/event_log.proto`, `host/daemon/.../daemon/core/EventLog.kt`, `.../daemon/grpc/EventRecording.kt` (`AttachedDevice.recorded`) | The action timeline. Already readable for **any** connection by any token holder (`ClientConnectionService.Events`, polled by `after_seq`). |
| `ListConnections` | `client_connection.proto`, `ClientConnectionService.kt` | Which runs hold which devices. JUnit names its connection `junit <pid> <user.dir>` (`clients/kotlin/junit5/.../ConnectionMemo.kt`), pytest `pytest <pid> <rootdir name>` (`clients/python/tap_e2e/pytest_plugin.py`). |
| `ScreenSnapshot` (dump → compact nodes, selectors, refs, diff) and `Screenshot` | `DeviceService.kt`, `host/daemon/.../daemon/snapshot/` | Frames and element overlays. **Owner-only** today (`daemon.attachedDevice(id, clientConnectionId)`). |
| Tap Studio: Starlette + Connect back end, React/Vite page, paired-frame loop (`DeviceWorker`), screen view with overlay | `clients/studio/tap_studio/{server,service,screen}.py`, `clients/studio/web/src/{ScreenView,Inspector,frames}.tsx/ts`, `clients/studio/proto/studio.proto` | The viewer UI and back end to extend. |
| Host-owned scrcpy child for recordings | `host/core/.../host/ScrcpyRecorder.kt`, `.docs/audio-recording.md` | Starting point for live video. Writes a file returned on stop; not a live stream. |
| Live-screen research | `.docs/screen-streaming.md` | Measured frame costs; the sketched `ScreenStream` RPC (option C/D). |

How tests use connections: one connection per JVM / pytest session, devices attached per test
in `TapExtension.beforeEach` / the pytest fixtures, detached after. Concurrent single-device
tests can share one connection (`TapExtension.kt`, the `start` rotation comment). So a
connection's log interleaves tests; **serial** is what separates them.

## Why the naive approach is wrong

Screenshots and dumps are driver commands. They run on the same single command executor, via
`UiAutomation`, as the test's own commands (`device/driver/core/.../ArtifactCommands.kt`). Measured
on 85e49002 (Samsung SM-J810G, API 29) in `screen-streaming.md`:

| | median |
|---|---|
| `ScreenSnapshot` (dump + parse) | ~70 ms |
| `Screenshot` (PNG) | ~357 ms |
| one paired frame | ~430 ms |
| a `Perform` tap, for comparison | ~251 ms |
| `Count` alone / while frames stream | 62 ms / 98 ms (263 ms before Studio stopped waking on every call) |

- A viewer polling frames competes with the test's commands and changes their timing.
- A before *and* after frame for every action adds ~860 ms per action on this device (3–4×
  slower), and moves the test's timing enough to hide or create flakiness.
- A frame polled by the viewer is never exactly "before action N": only the daemon, in the
  call path, can capture that.

## Decision (proposed)

Three layers, cheapest first. Each is useful alone.

### 1. Timeline: live events + test markers (daemon + both test plugins)

- **Test markers.** Add to `LoggedEvent.call` a `TestMark` case: `id` (JUnit unique id /
  pytest nodeid), `display_name`, `phase` (`STARTED` / `FINISHED`), `outcome` on finish
  (`PASSED` / `FAILED` / `SKIPPED` / `ABORTED`), optional `message` (the failure's one-line
  summary), and the `serials` the test holds. New RPC
  `ClientConnectionService.Mark(client_connection_id, TestMark)` appends it to the log.
  `TapExtension` marks `STARTED` after its devices are attached in `beforeEach`, and `FINISHED`
  in `afterEach` after failure artifacts are captured and before detach. The pytest plugin does
  the same around its device fixtures (`pytest_runtest_makereport` gives the outcome). Both
  marks are best effort: a failed Mark never fails a test (log it and continue).
- **Attach / detach events.** Log attach and detach as a `DeviceLifecycle` case (attached
  device id, serial, generation, `ATTACHED` / `DETACHED` with reason). Today neither is logged.
  It shows device turnover between tests and makes the log self-describing.
  Update `.docs/agent-surface.md` and the `tap-events/1` export notes, since `tap-agent export`
  will now contain these cases (additive).
- **Push, not poll.** Add a server-streaming `Watch` RPC (below) that replays the kept backlog
  after `after_seq` and then pushes new events. `EventLog` gains subscribers (a
  `MutableSharedFlow` or listener list). Subscribe before reading the backlog and dedupe by `seq`,
  so no event falls in the gap between them.

Cost to tests: one extra cheap RPC per test. Nothing on the device.

### 2. Live screen: video, not frames (daemon, `:host:core`)

- `WatchService.ScreenStream(serial, max_size?, max_fps?)` → stream of `config` (codec, size,
  rotation, codec config) and `frame` (bytes, pts, key frame, **host receive time in epoch
  ms**) messages. This is the RPC `screen-streaming.md` sketched, made read-only and keyed by serial
  rather than by owner.
- The producer is left to the spike `screen-streaming.md` already defines: scrcpy video-only
  (`--no-control --no-audio`, speaking scrcpy's socket protocol or a raw-stream output) vs
  `adb exec-out screenrecord --output-format=h264 -`. Extra spike questions for this use:
  whether a second scrcpy server can coexist with a running `StartRecording` (scrcpy's `scid`);
  device CPU while the fixture suite runs; behaviour across the per-test reinstall/force-stop of
  the AUT.
- Started only while at least one watcher streams that serial; stopped serial-specifically
  (`-s`, never `forward --remove-all`), and when the device is detached or the daemon stops.
  All ADB/scrcpy stays in `:host:core` (CLAUDE.md layering).
- The page decodes with WebCodecs and keeps the last N seconds in memory. Each event has
  `at_epoch_ms`, so the page can show, from the video alone, a **"before" frame (last frame at
  or before `at_epoch_ms`) and an "after" frame (first frame after `at_epoch_ms + duration_ms`
  plus a settle margin)**. This gives the agent-device effect at zero driver cost. Align clocks
  by host receive time, since device pts and host clock differ.

Cost to tests: no driver commands, no `UiAutomation`; some device CPU for encoding. Measure it.

### 3. Trace mode: exact per-action captures (opt-in, daemon)

For "what did the screen look like when this command was sent, and which element did it hit".
Modelled on Playwright's `trace: off | on | retain-on-failure`.

- Configured per attached device at attach time (`AttachRequest.trace`: `OFF` default,
  `DUMP`, `DUMP_AND_SCREENSHOT`) plus a retention policy (`ALWAYS` / `ON_FAILURE`). It is set by
  the test config (`tap.trace` system property / `--tap-trace` pytest option), not by the
  watcher, so a viewer opening a page never changes a run's timing.
- In `AttachedDevice.recorded()`, for the **mutating** commands only (tap, long tap, set text,
  type, clear, swipe, scroll, key, and the app calls that change the screen): before sending the
  command, take a dump (and screenshot if configured) and attach a `trace_frame_id` to the
  `LoggedEvent`. Add a final capture on `TestMark FINISHED`. "After action N" is "before action
  N+1", so one capture per action is enough. Do not capture twice.
- The trace dump goes through `ScreenSnapshots.screen(...)`, but **must not** call
  `attachedDevice.screen.record(...)`: that would advance the owner's ref alignment and corrupt
  the diff an agent sees. Give traces their own parse with no refs.
- Store frames in a bounded per-connection store (count and byte caps, e.g. 64 MiB, oldest
  evicted, evictions counted like `EventLog.dropped`), in memory or under the state dir. Fetch
  bytes with `WatchService.GetTraceFrame(id)`. The `Watch` stream carries only ids and metadata.
  With `ON_FAILURE`, `TestMark FINISHED PASSED` drops that test's frames for its serials.
- Later, not in v1: failure artifacts (`<artifactsDir>/<class>/<method>/…`) include the
  failed test's trace.

Cost to tests: ~70 ms per mutating action with `DUMP`, ~430 ms with `DUMP_AND_SCREENSHOT` on
the Samsung. Off by default. Phase 5 measures the real overhead.

**Invariant note for the owner:** the trace dump is diagnostic. It is taken *before* the command
and never used to resolve a selector, so "no hierarchy dump on the selector hot path" holds in
spirit. It does add latency on the action path when trace mode is on. Confirm this before
implementing.

### The `WatchService` (new, `contracts/proto/watch.proto`, `tap.v1`)

A separate service so the read-only surface is visibly separate from the owning one:

```proto
service WatchService {
  // Backlog after `after_seq`, then live events, for one connection or every connection
  // holding `serial`. Ends with the connection (closing event) or the client.
  rpc Watch(WatchRequest) returns (stream WatchResponse);
  rpc ScreenStream(ScreenStreamRequest) returns (stream ScreenStreamResponse); // layer 2
  rpc GetTraceFrame(GetTraceFrameRequest) returns (GetTraceFrameResponse);     // layer 3
  // Optional, rate-limited: one paired frame now, for a quiet screen with no trace/video.
  rpc Peek(PeekRequest) returns (PeekResponse);
}
```

- No `client_connection_id` of the watcher's own is needed: watchers own nothing. Auth is the
  daemon token, the same trust boundary as `Events` today. Note it in `server-api.md`: anyone
  with the token can see screens and typed text (typed text is already logged verbatim;
  `framework-gaps.md`, observability).
- `Peek` (optional) runs a dump + screenshot on the owner's executor, so it slows the test.
  Allow at most one in flight per serial, enforce a minimum interval (e.g. 2 s), use no refs,
  and leave `attachedDevice.screen` untouched. It's an escape hatch for "show me the overlay
  now". Skip it if layers 2 and 3 cover the need.
- Watch must never extend a held connection's idle timeout (it doesn't "name" the connection
  as a caller). Check `markInUse` in `TapDaemon.kt`; `Events` today *does* renew, see its comment.

### The viewer: a Watch mode in Tap Studio

- `tap-studio` gains a read-only **Watch** mode next to recording (same back end, same page
  build, its own route). In Watch mode the back end never attaches and never calls a mutating
  RPC. Enforce that in the back end, not just the UI.
- `studio.proto` additions: `ListRuns` (from `ListConnections`, grouped into runs by connection
  name/pid), `WatchRun` (server stream relaying `WatchService.Watch`, plus trace-frame ids), and
  a video relay (or the page talks Connect to a studio endpoint that proxies `ScreenStream`).
  Regenerate with `clients/studio/scripts/gen_protos.py`.
- Page layout: device list / runs on the left; centre the live video (layer 2) or the selected
  action's frame (layer 3, else extracted from video); right a timeline grouped by test
  (`TestMark`), each action showing selector, outcome, duration, and a before/after toggle with
  the target element highlighted when a trace dump exists. Failed tests and actions are
  highlighted, with the `Failure`/`Error` details. Reuse `ScreenView`/`Inspector` for frames and
  overlays.
- Target highlight: a mutating command's result is `Done {}` and carries no element bounds.
  Options: (a) evaluate the logged selector against the trace dump in the daemon (best effort,
  label it as such; check what `ScreenSnapshots` can already evaluate); (b) have the driver
  return the matched element's bounds in `Done`. (b) is a driver + wire change: **ask the owner
  first** (memory: driver behaviour changes need approval; `protocol-contract.md` must change
  with it).

## Options considered

| Option | Verdict |
|---|---|
| Viewer polls `Screenshot` / `ScreenSnapshot` on someone else's device | Rejected: competes with the test on the driver executor, and frames don't line up with actions. |
| Before *and* after capture per action | Rejected: twice the cost of before-only for the same information. |
| MJPEG from the driver (Appium) | Rejected in `screen-streaming.md`: CPU inside the command process, competes with `UiAutomation`. |
| Studio runs scrcpy/adb itself | Rejected: all ADB lives in `:host:core` behind the server. |
| Watcher attaches the device | Impossible and wrong: the per-serial lock is the exclusive-use rule (`pool-and-leases.md`). |
| Test names from connection names | Insufficient: one connection spans many tests, sometimes concurrently. |

## Phases (one commit each; ask the owner how to split them into PRs)

1. **Contract + docs.** This record finalised with the owner. `watch.proto`, `TestMark` /
   `DeviceLifecycle` in `event_log.proto`, `Mark` RPC, `AttachRequest.trace` (if layer 3 is
   approved). Run `:contracts:protocol:test`, `buf lint`; regenerate Python stubs
   (`clients/python/scripts/gen_stubs.py`) and studio protos; update `.docs/server-api.md`. All
   changes are additive, so no `BREAKING_BASELINE` bump unless something is removed.
2. **Daemon timeline.** `EventLog` subscribers, attach/detach events, `Mark`, `Watch` stream
   (backlog + live, by connection or serial, closing on disconnect). Unit tests in
   `:host:daemon:test`: ordering, no gap between backlog and live, eviction during a watch, a
   watcher outliving / not renewing a held connection, closing on disconnect.
3. **Clients mark tests.** Kotlin SDK `TapConnection.mark(...)` (KDoc) + `TapExtension`; Python
   `TapConnection.mark(...)` (docstring) + pytest plugin; `watch()` in both clients as public API
   (or Python only, if the Kotlin side isn't needed; decide with the owner). Unit tests on the
   fake daemon (`clients/python/tests/unit`, `TapTestBridgeTest`-style for JUnit). A mark failure
   never fails a test.
4. **Studio Watch mode (timeline only).** Runs list, live timeline grouped by test, failures, and
   an optional `Peek` frame. Back-end tests (`pytest clients/studio/tests`), page tests
   (`bun run test`), `bun run build`. Update `docs/guide/studio.md`.
5. **Trace mode** (if approved). Daemon capture in `recorded()`, separate parse (no ref state),
   bounded store, `GetTraceFrame`, retention; config in both test plugins; the page shows
   before/after frames and the highlight. Measure fixture-suite wall time with `OFF` / `DUMP` /
   `DUMP_AND_SCREENSHOT` on both local devices and record it here.
6. **Live video.** The producer spike first (results recorded in `screen-streaming.md`), then
   `ScreenStream` in `:host:core` + daemon, the WebCodecs player, and before/after frames from
   video by timestamp. Measure suite wall time with a watcher streaming vs not. Re-record
   native-image metadata if a new dependency needs reflection.
7. **Docs.** `.docs/server-api.md`, `.docs/agent-surface.md` (log contents), `.docs/screen-streaming.md`
   (ScreenStream decided), `.docs/framework-gaps.md` (§19 progress, if any), `docs/guide/studio.md`,
   and add this file to the Documents list in `CLAUDE.md`.

## Verification

- JVM: `nice ./gradlew --max-workers=2 :contracts:protocol:test :host:core:test :host:daemon:test`
  and the Kotlin client/JUnit tests. Python: `pytest clients/python/tests/unit clients/agent/tests
  clients/studio/tests`; `bun run test` in `clients/studio/web`.
- **Device runs only with the owner's go-ahead** (devices are shared): the fixture suites
  (`:samples:fixture-tests:test`, the Python suite) with a Watch page open on both
  emulator-5554 and 85e49002. Check: every test appears with the right name and outcome; actions
  in order; a concurrent pair of single-device tests separated by serial; trace overhead numbers;
  no extra failures with a watcher attached vs without.
- Native image smoke (`:host:daemon:nativeCompile`) once a new dependency or reflection path is added.

## Constraints to keep (from CLAUDE.md and memory)

- The watcher is read-only: no mutations, no attach, no ref-state changes on the owner's device.
- `host/*` never depends on `clients/`; clients depend only on `:contracts:api`. All ADB/scrcpy
  stays in `:host:core`; every ADB call uses `-s`.
- Proto edit: same change updates `server-api.md`, Python stubs, and studio protos.
- Gradle with `nice` and `--max-workers=2`; no device tests without asking.
- Git: work on `feat/test-watcher`; when done, rebase on `origin/main`, push with
  `--force-with-lease`, `gh pr create`; never merge; no co-author / "Generated with" lines.
- The driver assumes nothing about the app; any driver/wire change (e.g. bounds in `Done`)
  needs the owner's approval first.

## Open questions for the owner

1. Approve trace mode (layer 3) and its latency on the action path while it's on?
2. Target highlight: daemon-side selector evaluation on the trace dump, or driver-returned bounds?
3. Keep `Peek` at all, or rely on video + traces only?
4. PR split: one PR per layer (timeline → trace → video) is suggested.
5. Should traces also land in failure artifacts in this work, or later?
