# The Live view

The **Live** view is what the watcher opens on. **Library** in the top bar switches to the
saved clips and recordings ([Clips, recordings and the Library](library.md)).

## The wall

Live opens on the **wall**: every device the server sees, each as a tile with its screen and,
under the screen, what it is doing:

- the state (**Running**, **In use** between calls, **Free**, **Quarantined**, **Offline**)
  and the device's model;
- the step it is running now, in full on up to two lines (`Wait for text("Orders")`), or the
  last one it ran;
- how that step is going: `running 3.2 s` (amber and **slow** from 10 s), `✓ 0.4 s`, or
  `✕ NOT_FOUND 12 s ago`;
- the connection using it ("JUnit · fixture-tests").

A **failure stays on its tile** (a red band with the error and the time) until you open that
device, so a failure from a minute ago is still there when you look. The bar above the wall
counts the unseen failures; clicking it opens the device with the newest one. What you have
seen is remembered by this browser only, so each person watching has their own.

Tiles keep their place (sorted by model, then serial) while devices run, fail and idle. A
landscape screen (a tablet, a rotated phone) takes two columns. **Live screens** switches the
screens off: streaming every screen runs a video encoder on every device, and with it off the
wall still shows each device's step and outcome.

Tile screens update about five times a second, which is enough to see what a device is doing
and keeps ten screens light on the browser. The opened device plays at full frame rate. A
tile scrolled out of view stops decoding, and a hidden tab stops streaming altogether.

## A device

Click a tile to open that device. Its failures count as seen. If it had unseen ones, the
newest within the video retention window is selected, or the newest unseen one if all are
older. When that action's video is no longer kept, a note names the oldest available video. The wall stays beside the device as the **device
pane**: drag its edge to make it a narrow column of small live screens or, wider, the wall
again with full captions (double-click the edge to switch between the two). `[` and `]` open
the previous or next device; **Wall** (or `G`) goes back to the wall. The pane's width is
limited to leave room for the screen and actions. The header names the opened device, its
serial, API level, state and connection; its arrows also switch devices. The selected tile
scrolls into view in the pane.

- **Screen** (centre): the device's video. The badge reads **Live**, or **Paused −N s
  · Go live** when you are looking back.
- **Actions** (right): every action run on that device, newest last, as readable rows:
  `Tap res("login")`, `Set text res("email") ← "ada@…"`, `Tap text("Archive") · AMBIGUOUS`.
  Search filters them; **Failures** shows only failed ones. Clicking a row (or `j` / `k`)
  selects it and shows its details: outcome and error, input, start, duration and the
  connection, inline under the selected row, with the raw event behind a disclosure. Session
  attach/detach rows and connection separators show whose actions follow. Choosing **Failures**
  moves a selected successful action to the nearest failure (the newer one on a tie); opening
  another device resets search and filters.

Offline or unauthorized devices show why no screen is available, not a **Live** badge.

Selecting an action whose video is still retained pauses the screen where it started and
marks it on the scrubber. Failed actions are red on the scrubber; hovering a marker names the
action.

## Before and after

**Before / after** (or `C`) shows the selected action's **Before** and **After** frames side by side:
the screen as the action started, and shortly after it finished (up to 1 s, or until the next
action). Both are fixed frames of that action; `j` / `k` move the comparison to the next or
previous action. Moving through the video (scrubbing, play, `←` / `→`, **Live**) closes it and
shows the screen at that point. Captions show each frame's approximate offset from the call;
a visible note explains that matching uses the daemon's send and reply times, not the exact
instant of input.

**Previous failure** / **Next failure** (or `Shift+N` / `n`) select a failed action and open
its comparison when its video is available.

## Rotations

A rotation (or a restart of the device's capture or of the server) starts a new video stream
at the new size. The screen follows it, and the earlier video stays: the scrubber marks each
change with a dashed tick ("Rotated to landscape"), and looking back, replay and clips work
across it.

## The scrubber

The bar under the screen covers the video the watcher has kept for the device (the last two
minutes, see [Clips, recordings and the Library](library.md)), with a marker for each action.
The selected action's full duration is highlighted; green, red and amber ticks mark completed,
failed and running calls. Hatching marks periods when no connection held the device. A time
axis counts back from the newest frame, with fewer labels on narrow screens.

Click or drag on it to look back: the screen pauses there, **play** replays from there, and
**Live** (or `L`) returns to the device as it is now. While you look back, the bar stays still
instead of sliding along with the live video.

**Zoom** the scrubber with `ctrl` / `⌘` + scroll (or a trackpad pinch) around the pointer,
`+` / `-` around the playhead, or the − / + buttons beside it, down to one second; `0` or the
span between the buttons shows everything again. While zoomed, scrolling pans and a strip
under the scrubber shows which part is in view. Live, a zoomed scrubber keeps to the live end;
paused, it stays where it is, and in replay it pages along with the playhead. The Library
player zooms the same way.

## Keyboard

`space` play / pause, `←` / `→` one frame (`shift` for 15), `j` / `k` next /
previous action, `L` go live, `C` compare, `+` / `-` / `0` zoom, `Esc` closes the selection or
the clip bar, `n` / `Shift+N` next / previous failure, `[` / `]` previous / next device,
`G` back to the wall. The keyboard button in the device header (or `?`) shows these shortcuts.

## What the times mean

Frame times are when the server **received** each frame, not when the device captured it;
action times are when the server sent the call and got its reply. Before / After frames
and action markers are therefore approximate (encoding, transport and frame rate add delay).
A still screen sends no new frames, so a recording's media time can be shorter than the
wall time it covered; no frames are invented. The watcher does not show test names or outcomes yet,
only connections and their actions.

A reader that falls behind the video is disconnected instead of slowing the device down, and
reconnects.
