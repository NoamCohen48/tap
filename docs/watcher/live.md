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

Selecting an action whose video is still retained pauses the screen where it started and
marks it on the scrubber. Failed actions are red on the scrubber; hovering a marker names the
action.

## Action marks

**Marks** toggles touch indicators on the screen: a circle for a tap, concentric rings for
repeated taps, a dashed ring for a held press, an arrow trail for swipe/scroll/fling/drag,
and two arrows for a pinch. Marks are on by default; the browser remembers your choice.
They disappear after about 1.4 seconds live, follow the action times during replay, and stay
visible when you select an action or compare its Before / After frames.

Locations come from the driver's resolved touch geometry, not from guessing where a selector
matches. Trails and timing are illustrative, not a sampled finger recording. Failed calls,
non-touch actions (such as Back or typing), older drivers without geometry, and incompatible
screen orientations show no mark. Marks do not change the device, saved clips, recordings or
Library playback.

## Before and after

**Compare** (or `C`) shows the selected action's **Before** and **After** frames side by side:
the screen as the action started, and shortly after it finished (up to 1 s, or until the next
action). Both are fixed frames of that action; `j` / `k` move the comparison to the next or
previous action. Moving through the video (scrubbing, play, `←` / `→`, **Live**) closes it and
shows the screen at that point.

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
action times are when the server sent the call and got its reply. Before / After frames
and action markers are therefore approximate (encoding, transport and frame rate add delay).
A still screen sends no new frames, so a recording's media time can be shorter than the
wall time it covered; no frames are invented. The watcher does not show test names or outcomes yet,
only connections and their actions.

A reader that falls behind the video is disconnected instead of slowing the device down, and
reconnects.
