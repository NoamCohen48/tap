# The Live view

The **Live** view is what the watcher opens on. **Library** in the top bar switches to the
saved clips and recordings ([Clips, recordings and the Library](library.md)).

- **Devices** (left): each device with a status dot, the connection using it ("JUnit ·
  fixture-tests"), its last action and how long ago, and its failure count. Free and offline
  devices are listed below the ones in use.
- **Screen** (centre): the selected device's video. The badge reads **Live**, or **Paused −N s
  · Go live** when you are looking back.
- **Actions** (right): every action run on that device, newest last, as readable rows:
  `Tap res("login")`, `Set text res("email") ← "ada@…"`, `Tap text("Archive") · AMBIGUOUS`.
  Search filters them; **Failures** shows only failed ones. Clicking a row (or `j` / `k`)
  selects it and shows its details: outcome and error, input, start, duration and the
  connection, with the raw event behind a disclosure. A failed action also says why, when the
  driver said: for a selector that matched nothing, how many nodes each part matched alone and
  without it (`text("Pay"): 0 alone, 1 without it`); for a state wait that timed out, which
  parts of the condition held and what the node had (`✗ text("Pay") (has "Paying…")`).
- **Log** (right, beside **Actions**): the device's log, see [The device log](#the-device-log).

Selecting an action whose video is still retained pauses the screen where it started and
marks it on the scrubber. Failed actions are red on the scrubber; hovering a marker names the
action.

## Before and after

**Compare** (or `C`) shows the selected action's **Before** and **After** frames side by side:
the screen as the action started, and shortly after it finished (up to 1 s, or until the next
action). Both are fixed frames of that action; `j` / `k` move the comparison to the next or
previous action. Moving through the video (scrubbing, play, `←` / `→`, **Live**) closes it and
shows the screen at that point.

## The device log

**Log** shows what the device logged while a test had it attached: the app's and the system's
`logcat` lines, the actions Tap ran (`▸ tap text("Pay") → ok · 42 ms`), the markers a test
wrote (`⚑ checkout`) and a notice where lines may be missing. It is the same log a test reads
with `device.logs` ([Device logs](../guide/logs.md)), with secrets replaced the same way.

- **Click an entry** to see the screen at that moment: the video pauses on the last frame
  received at or before it, and the entry's full text and process open below the list. If that
  moment is older than the retained video (the last two minutes), the watcher says so.
- While you look back, a line in the list marks where the video is: entries above it were
  logged before the frame on screen. The list follows it as you scrub or replay; live, it
  follows the newest entry.
- **Markers, errors and gaps are dots under the scrubber** (errors red). Click one to go there
  and open the entry; entries that fall on the same spot share a dot, which says how many.
- **Search** matches the tag and the text; the level menu hides lines below a priority (actions
  and markers always stay).

The watcher reads the log of every attached device from the moment it is attached, whether or
not the page is open, and keeps the newest 50 000 entries per device, also after the test
detached it. A device attached with log capture turned off has no log, and the tab says so.

## Rotations

A rotation (or a restart of the device's capture or of the server) starts a new video stream
at the new size. The screen follows it, and the earlier video stays: the scrubber marks each
change with a dashed tick ("Rotated to landscape"), and looking back, replay and clips work
across it.

## The scrubber

The bar under the screen covers the video the watcher has kept for the device (the last two
minutes, see [Clips, recordings and the Library](library.md)), with a marker for each action.
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
the clip bar.

## What the times mean

Frame times are when the server **received** each frame, not when the device captured it;
action times are when the server sent the call and got its reply; a log entry's time is when
the server read the line from the device. Before / After frames
and action markers are therefore approximate (encoding, transport and frame rate add delay).
A still screen sends no new frames, so a recording's media time can be shorter than the
wall time it covered; no frames are invented. The watcher does not show test names or outcomes yet,
only connections and their actions.

A reader that falls behind the video is disconnected instead of slowing the device down, and
reconnects.
