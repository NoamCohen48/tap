# tap-watcher: watch actions and video

!!! warning "Experimental"
    `tap-watcher`, its page and the `tap-watch-steps/2` file are experimental: they may change in
    any release, including after 1.0, until this notice goes away.

`tap-watcher` shows, in your browser, every device the `tap` server sees: which connection is
using it, every action that connection runs on it, and the device's screen as video. Watch a
test suite while it runs, look back at what happened just before a failure, compare the screen
before and after an action, and save clips and recordings to a Library.

It is **read-only**: it never attaches a device, sends a command, wakes the screen, keeps a
connection alive or turns on screenshot tracing, so it can watch any test run (Kotlin, Python,
`tap-agent`, Tap Studio) without changing it. To inspect elements and record steps, use
[Tap Studio](../studio/index.md) instead.

## Install

It needs the `tap` server ([The tap server](../guide/server.md)), Python 3.10+ and a Chromium
browser (Chrome, Edge, …: the video is decoded with WebCodecs). Install the Python client
first, then the watcher:

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
pip install https://github.com/NoamCohen48/tap/releases/download/client-watcher/v0.0.1/tap_watcher-0.0.1-py3-none-any.whl
```

The [one-download bundle](../download.md) includes it too. Or install it from a checkout of the
repository; the page is built with [Bun](https://bun.sh). Run these from the repository root:

```bash
(cd clients/watcher/web && bun install && bun run build)
pip install -e clients/python -e clients/watcher
```

## Run

The screen video needs a **scrcpy 4.1 server JAR** on the server's machine, passed to
`tap start` (or `tap serve`) as `--scrcpy-server JAR`, or set as `TAP_SCRCPY_SERVER`. It is not
bundled: it comes with [scrcpy](https://github.com/Genymobile/scrcpy) (for example
`/usr/share/scrcpy/scrcpy-server`), and a server of another version is reported as a version
mismatch. Without one, the watcher still shows the devices and their actions, and the screen
says why there is no video.

```bash
tap start --scrcpy-server /usr/share/scrcpy/scrcpy-server   # once; the watcher never starts it
tap-watcher                                                  # prints the link to open
```

| Option | Effect |
|---|---|
| `--port N` | Serve on loopback port `N` instead of a free one. |
| `--keep-days N` | Delete saved clips and recordings older than `N` days, at start and hourly. By default they stay until you delete them. |
| `--version` | Print the version and exit. |

Open the printed link (`http://127.0.0.1:PORT/login?t=…`): it signs the page in with a cookie
that is new on every start, and the watcher listens on `127.0.0.1` only. The server's token
stays in the watcher's back end. If the server is not running, the top bar says so and the
watcher reconnects on its own once it starts (also after a restart). Ctrl-C stops the watcher;
a recording in progress is saved first.

The video carries no audio and installs nothing on the device. The server shares one capture
per device (at most four devices at once, eight readers each), and the watcher is one reader
per device however many pages are open.

## Next

- [The Live view](live.md): devices, the actions list, the screen and its scrubber, Before /
  After, zoom and the keyboard.
- [Clips, recordings and the Library](library.md): saving video, what is saved, and playing it
  back.
