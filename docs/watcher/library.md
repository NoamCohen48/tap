# Clips, recordings and the Library

The watcher keeps the last **120 seconds (at most 32 MiB)** of each watched device's video,
in whole GOPs, while a page watches the device or a recording runs (and 15 s after).

- **Clip** saves part of that retained video: choose the start and end with **Set start
  here** / **Set end here** while scrubbing, then **Save clip**. A clip starts at the key frame
  at or before its start.
- **Record** records the device from its latest key frame until **Stop**, for at most 30
  minutes or 1 GiB. Recording runs in the watcher's back end: closing the tab does not stop
  or lose it. It carries on through rotations and through a server restart (it waits for the
  video to come back); stopping the watcher saves it.

A video file holds one stream, and the watcher never re-encodes, so a clip or recording that
spans a rotation is saved in **parts**: `video-1.mp4` (portrait), `video-2.mp4` (landscape)
and so on, each at its own size. The Library plays them back to back.

## The Library

Clips and recordings are saved in `~/.tap/recordings/watcher/<id>/` (or
`TAP_WATCHER_RECORDINGS`): the video parts and `steps.json`. The **Library** view lists them
by day, with their device, length, actions, failures and size, and:

- shows how much of the **2 GiB** cap is used (a full library refuses new saves);
- searches by device or connection and filters recordings or clips;
- deletes one, or several at once (tick them, then **Delete**), after a confirmation that
  says what goes and how much space it frees;
- plays one on a single timeline, its parts back to back: action markers (failures in red)
  and a dashed tick where the device rotated; click an action or a marker to jump to it, and
  download it as a ZIP of all its files.

Keyboard in the player: `space` play / pause, `←` / `→` one second (`shift` for 5), `j` / `k`
next / previous action.

Saved videos are kept until you delete them, also across watcher restarts. To have old ones
deleted for you, start the watcher with `--keep-days N`: entries older than N days are
removed at start and hourly after. A save interrupted by a crash leaves an unfinished
folder; the watcher removes those when it starts, never one another watcher on the same
library is still writing.

`steps.json` (`tap-watch-steps/2`) lists the parts (file, stream and clock identity, size,
where each starts on the joint timeline, and every frame's timestamps) and each action that
overlaps the video with its original event, its part, its offset in that part and on the
whole recording, and whether it began or ended outside the video. MP4 files keep the
variable frame timing without re-encoding.
