# Test watcher — review of the first cut and its fix status

Date: 2026-10-03. Review of `feat/test-watcher` (3 commits plus an uncommitted `Adb.kt`), and
how each item was fixed. The design is in [test-watcher.md](test-watcher.md), the capture
work in [shared-video.md](shared-video.md).

How the review was done: the branch was read in full, and the real watcher app was run
against a fake back end (synthetic H.264 test pattern, scripted taps, text entry and
failures) and inspected with Playwright at 1440×900 and 1024×700. No device or daemon was
touched. Python (12) and web (8) tests passed at the time.

## Verdict

The daemon and `:host:core` capture work is solid. The page was a debug console rather than a
test viewer. Decision: keep the capture path, change how activity is subscribed (one
daemon-wide stream), move recording into the watcher back end, and rewrite the page.

What was already good and is kept:

- `ScrcpyVideoSource`: a private SCID, JAR and forward per producer; cleanup targets the
  reported PID; unproven cleanup gates a restart; launch arguments stay under the Samsung limit.
- `EventLog.watch` took the backlog and registered the reader under one lock (no gap or
  overlap), with bounded reader queues and no renewal of the watched connection. The same
  pattern is used by the new daemon-wide activity log.
- `SharedVideo`: one producer per serial, whole-GOP retention, slow readers dropped without
  blocking the producer.
- Honest timing semantics: correlation is labelled approximate everywhere.

## Findings and status

Status: **fixed**, **decided** (no code change, with the reason), or **open**.

### Architecture

| # | Finding | Status |
|---|---|---|
| A1 | Events were watched per connection while the UI is per device. The page polled `ListDevices` every 3 s, found the owner, watched that connection and filtered by serial in the browser. A connection that started and ended between polls was never seen; a closed connection's events were gone. | **fixed** — `WatchService.Watch`: one daemon-wide, resumable activity stream (connection opened/closed, device attached/detached, logged actions) with a snapshot of live connections first. `ClientConnectionService.WatchEvents` is removed. |
| A2 | Recording and export ran in the browser tab. Frames went daemon → back end → browser → back end again as base64 JSON (up to 48 MiB per POST). Closing the tab lost an in-progress recording. `clip.zip` duplicated `video.mp4` + `steps.json` on disk. | **fixed** — the back end keeps one upstream per serial, a bounded frame history, recordings and the activity history; the page sends `{serial, streamId, fromSeq, toSeq}` for a clip and `start`/`stop` for a recording. Recordings store `video.mp4` + `steps.json`; the zip is built on download. |
| A3 | The scrcpy video server path came from `TAP_VIDEO_SERVER` read inside `TapDaemon`, with a hardcoded `/usr/share/scrcpy/scrcpy-server` default, beside the existing `config.scrcpy`. A server of another version failed with an opaque log tail. | **fixed** — `DaemonConfig.scrcpyServer` and `tap start/serve --scrcpy-server`; a version mismatch is reported as such. |
| A4 | Retention limits were literals in `SharedVideo.kt` and duplicated in the page (`MAX_BYTES`, `MAX_SECONDS`). | **fixed** — named constants in `SharedVideo.kt` and `tap_watcher/video.py`; the back end owns the history and reports its limits in `Status.history`, which the page's buffer mirrors (no literals in the page). |
| A5 | `EventReaderOverflowException` was reused for slow video readers. | **fixed** — `VideoReaderOverflowException`. |
| A6 | `VideoService` was constructed with an inline fully qualified name in `TapDaemonMain.kt`. | **fixed** — imported. |
| A7 | `watcher.proto` wrapped `tap.v1.WatchEventsResponse` in a same-named message. | **fixed** — the watcher reuses `tap.v1` messages directly. |
| A8 | `Guard` / `cookie_name` are copied from Studio. | **decided** — kept as a copy: the two are separate packages with no shared web module, and a shared package for ~60 lines is not worth a fourth Python distribution. Both carry the same tests. Revisit if a third browser client appears. |
| A9 | Formatter-only churn in `TapDaemon.kt`, `common.kt`, `TapDaemonMain.kt`, tests, and an uncommitted 255-line reformat of `Adb.kt`; some of it worse than before (`if (probe ==\n Probe.REJECTED\n)`). | **fixed** — reverted; the diff holds only the feature. |
| A10 | `style.css`, `video.css`, `index.html`, `tsconfig.json`, `vite.config.ts` were hand-minified onto one line. | **fixed** — rewritten as readable source. |

### Front-end code

| # | Finding | Status |
|---|---|---|
| F1 | `VideoPane.tsx` kept its state in 7 mutable refs and re-rendered the pane every 100 ms forever, even when idle. | **fixed** — one explicit `Mode` (`live` / `paused` / `saved`) in the page; no idle timer. |
| F2 | Play called `show()` every 100 ms, which closed the decoder and decoded again from the previous keyframe each tick (~10 fps, wasteful). | **fixed** — one decoder fed continuously, frames scheduled by pts. |
| F3 | `windowNow()` copied the whole frame array on every frame and tick; search ran `JSON.stringify(toJson(…))` over up to 2000 events per keystroke and per event; frame lookups were linear. | **fixed** — no copies, a precomputed search text per action, binary search. |
| F4 | Bug: with the Failures filter on, the details panel still showed a hidden, succeeded action. | **fixed** — selection is cleared when it leaves the visible list. |
| F5 | Bug: the status read "Live" while the video was paused. | **fixed** — one live badge shows "Live" or "Paused −N s · Go live". |
| F6 | Bug: with nothing selected, the details panel followed each new event, so it could not be read during a run. | **fixed** — details show only an explicit selection. |

### UI/UX

| # | Finding | Status |
|---|---|---|
| U1 | Rows did not say what happened ("Tap · 10:08:21 · #2"): no target, typed text or error; those were only in raw JSON. | **fixed** — rows read `Tap res("email")`, `Set text res("email") ← "ada@…"`, `Tap text("Archive") · AMBIGUOUS`. |
| U2 | The video was starved: ~440 px tall at 1440×900, ~130 px at 1024×700, under a title, toolbar, play row, slider, markers, trim row, a note and a saved-recordings footer with a filesystem path. | **fixed** — the video fills the centre; transport is one compact bar; clip tools appear only in Clip mode; the library is its own view. |
| U3 | The details panel was a debug dump (monotonic ns, connection id, clock id, raw JSON) taking half the timeline height. | **fixed** — action, target, input, outcome or error, duration and time; raw JSON behind a disclosure. |
| U4 | Before/after was a toolbar toggle that rendered two ~150 px thumbnails. | **fixed** — selecting an action shows large Before / After frames on the stage, with keyboard stepping between actions. |
| U5 | Scrubber markers were 1 px grey ticks: no failure colour, no selected state, no label, overlapping. | **fixed** — failures in red, the selected action highlighted, a tooltip with the action summary. |
| U6 | "Live" appeared three times; the "approximate" caveat three times. | **fixed** — one live badge, one info note. |
| U7 | The device list showed the raw connection name (`junit 48211 /home/…`) and "device leased". | **fixed** — "JUnit · fixture-tests", last action, time ago, a live dot and a failure count; free devices de-emphasised. |
| U8 | 10–11 px text, all buttons alike, ✓/! glyphs, a 64 px header holding only the logo, no dark mode, no keyboard shortcuts. | **fixed** — 12–13 px minimum, one primary action, SVG icons, a 48 px bar with daemon status, light/dark themes, shortcuts (space, ←/→, j/k, L, Esc). |

### Scope

| # | Finding | Status |
|---|---|---|
| S1 | Without test markers the timeline cannot name the running test (goal 2). | **open** — owner decision; the deferred proposal is [test-watcher-test-markers.md](test-watcher-test-markers.md). The activity stream has room for a `TestMark` case. |
| S2 | CLAUDE.md now requires one commit per PR; the branch has 3. | **open** — squash before the PR. |

## Verification of the fixes

- JVM: `:contracts:protocol:test :host:core:test :host:daemon:test` (new `ActivityLogTest`,
  `WatchServiceTest`).
- Python: client unit tests, `pytest clients/studio/tests`, `pytest clients/agent/tests`,
  `pytest clients/watcher/tests` (16: activity resume and daemon restart, video history and
  readers, recording/clip/library, MP4 + `steps.json`, server routes), ruff, pyright;
  `gen_stubs.py --check`, `gen_protos.py --check`.
- Page: `bun run test` (13: action descriptions, activity store, frame buffer) and
  `bun run build`.
- The real back end and page against a fake daemon streaming H.264 and scripted actions,
  inspected with Playwright at 1440×900 and 1024×700, light and dark: live video, action
  rows, Before / After on selection, failure markers, clip save, library playback.
- Not run: the device matrix and the native image with the new `WatchService`.

## Follow-up: rotation and library management (2026-10-03)

Asked for after the review: tests that rotate ended a recording and wiped look-back at the
rotation, and nothing managed the library. Done (design in `test-watcher.md`, "Stream
changes"):

- History and the page buffer keep one segment per stream; recordings and clips are saved as
  one MP4 per stream (`video-N.mp4`, `tap-watch-steps/2`) and the Library plays them back to
  back. `SaveClip` takes (stream id, seq) for both ends; `DeleteRecordings` takes many ids;
  `ListRecordings` reports usage, cap and retention.
- Library view: usage meter, search and kind filter, multi-select delete, entries by day.
  `--keep-days N` (opt-in) deletes old entries; interrupted saves are swept at start.
- The recording time limit is wall time (a still screen or daemon outage still ends it).
- Found while checking it: media timestamps after a stream change could be fractional µs, and
  WebCodecs truncates them, so a seek never matched its frame (fixed: whole µs). The page's
  HTML was served without `Cache-Control`, so a browser kept an old build's `index.html`
  (fixed: `no-cache` on HTML).
- Checks: `pytest clients/watcher/tests` (21), ruff, pyright, `bun run test` (15),
  `bun run build`, `gen_protos.py --check`; the fake daemon rotating every 10 s, in Chromium:
  seek and replay across rotations (canvas 360×760 ↔ 760×360), a clip across a rotation saved
  as 2 parts, a 50 s recording as 5 parts (verified with ffprobe), Library parts and
  jump-to-action. Not run: the device matrix.

## Follow-up: Library design pass (2026-10-03)

A UI/UX review of the Library against the ui-ux-pro-max checklist, with fixes:

- The player used native `<video>` controls, which cover one part: an 11 s clip in two parts
  read "0:00 / 0:08", and there were no action markers. Now one transport spans the whole
  recording (play/pause, time, a track with action markers, failures in red, the current
  action highlighted, dashed ticks where the stream changed) and part chips are gone; keys
  space, ←/→ (shift 5 s), j/k as in the Live view; the actions list follows playback.
- Kind badges were red/orange like failures; they are neutral now, so red means a failure.
- `window.confirm` → an in-page `<dialog>` (what goes, the space it frees, focus on Cancel,
  Esc cancels, focus returns); a toast confirms a delete.
- Empty states say what to do (Go to Live; Clear the filters), and an empty viewer says why.
- Contrast (WCAG AA, 4.5:1): white on `--accent-fill` (every primary button) was 2.85:1 and on
  dark `--bad` 2.69:1 → `--on-accent` / `--on-bad` text tokens (≈6.7:1); `--faint` text was
  3.1:1 (light) / 3.8:1 (dark) → retuned to ≥4.5:1 on surface and surface-2.
- 900–1100 px keeps actions beside the video (stacking left a ~300 px screen at 1024×700);
  actions wrap to two lines so an outcome such as `· AMBIGUOUS` is not cut off; at phone
  width the viewer header wraps (it overflowed by 168 px) and the video is capped at 70vh.
- Bug found in the demo log: the start-up sweep (and the hourly `--keep-days` sweep) deleted
  the `.saving-*` directory of a recording still in progress, so its save failed. Fixed with
  a `flock` held by the writer (test: a second `Library` on the same directory sweeps while
  a save is in progress).
- Checks: `pytest clients/watcher/tests` (22), ruff, pyright, `bun run test` (15), `tsc`,
  `bun run build`; Chromium against the rotating fake daemon at 1440×900, 1024×700 and
  390×844, light and dark: seek, j/k and playback across parts, the dialog (Esc, confirm),
  filters, no horizontal overflow. Not run: the device matrix.

## Follow-up: stateless daemon video, frozen live scrubber (2026-10-03)

- The daemon's `SharedVideo` no longer keeps 120 s / 32 MiB: only the header and the current
  GOP, so a joining reader decodes at once (a GOP over 16 MiB is dropped and the reader waits
  for the next key frame). Look-back is the watcher's alone. Test: a joining reader gets the
  header and only the latest GOP; frames before the first key frame are never sent.
- Live view: after leaving live, the track kept growing at its live end, so the head and
  markers drifted left under the pointer (~14 px/s at 1440 px). The track's scale now freezes
  when live is left (a replay past the end stretches it; going live unfreezes it); the badge
  still counts how far live has moved on.
- Library list: the selection highlight covers the whole row (checkbox and entry), and the
  row checkboxes line up with Select all.
- `WatchServiceTest` raced: when `later`'s event and its close came in one response,
  `takeWhile` dropped both; it now keeps the closing response (`transformWhile`).
- Checks: `:host:daemon:test` (86), `:contracts:protocol:test`, stub checks, the watcher and
  Python client tests, ruff, pyright, `bun run test`, `bun run build`; Chromium against the
  demo. Not run: the device matrix.

## Follow-up: Compare (2026-10-03)

Reported: Before / After appeared only by clicking an action (nothing said it existed), and
scrubbing while it showed moved Before but not After. Cause: compare switched on with any
selection, and its Before pane was the main player's canvas. Now:

- A **Compare** toggle (`C`) in the transport, off by default; with nothing selected the stage
  says to pick an action.
- Before and After are fixed frames of the selected action, each on its own canvas; `j` / `k`
  move the pair to the next action. Scrubbing, play, `←` / `→` and Live leave compare and show
  the screen there. The bar under the pair names the action (the "approximate" caveat is its
  tooltip).
- Selecting an action jumps the view to it once; frames that arrive later no longer pull the
  view back after the user moved on.
- A narrow stage (container query, ≤700 px) shows the transport buttons as icons with titles
  and labels, so the scrubber keeps its room; the shortcut hint wraps.
- Checked in Chromium (921 and 1440 px, light and dark): toggle, j with compare on, scrub
  leaving it, the no-selection hint, no transport overflow.

## Follow-up: scrubber zoom (2026-10-03)

Asked for: zoom in and out on the scrubber (a long live buffer or recording packs its action
markers together). `web/src/zoom.tsx` holds the window math (`windowOf`, `zoomView`,
`panView`, `followView`, unit-tested in `zoom.test.ts`) and the `useZoom` hook, shared by the
Live scrubber and the Library player:

- `ctrl` / `⌘` + wheel (and trackpad pinch) zooms around the pointer; plain wheel pans while
  zoomed; `+` / `-` zoom ×2 around the playhead, `0` fits; − / span / + buttons; minimum span
  1 s. An overview strip under the track shows the window's place in the whole.
- Live, the window is pinned to the live end; leaving live keeps it where it was on screen
  (consistent with the frozen scrubber); replay pages it so the playhead sits at 10 %.
  Markers, stream ticks and the head outside the window are hidden; seeking maps through it.
- The Live transport now puts the scrubber and its zoom on their own row above the buttons
  (it had shrunk to ~180 px at 1440 px with the zoom controls added).
- Checked in Chromium (1440 px): pinch zoom, pinned-live window, paused stability after
  seeking, `-` / `0`, Library `+`, wheel pan and seek within the window; no overflow.

