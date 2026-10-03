# Tap Watcher (experimental)

Standalone read-only browser client of the Tap daemon: devices and who uses them, every
connection's actions per device, shared live video, look-back with Before / After frames per
action, clips and recordings saved to a local library (MP4 + `steps.json`, ZIP on download).
Test-marker integration is deferred.

Video needs a scrcpy **4.1 server JAR** on the daemon host: `tap start --scrcpy-server JAR`
(or `TAP_SCRCPY_SERVER`). No binary is bundled. Use Chromium on localhost for H.264
WebCodecs. Usage and limits: `docs/watcher/`.

```sh
# From the repository root; clients never start the daemon.
uv venv .venv
uv pip install --python .venv/bin/python -e clients/python -e 'clients/watcher[dev]'
(cd clients/watcher/web && bun install && bun run build)
.venv/bin/tap-watcher
```

Open the printed login URL. The server binds loopback, guards Host/Origin and uses a
per-launch HttpOnly cookie; the daemon token stays in the back end, which calls only
`Info`, `ListDevices`, `WatchService.Watch` and `WatchService.WatchVideo`. It never
Connects, Attaches, Observes, takes screenshots or renews a connection.

Layout:

- `tap_watcher/daemon.py` — the read-only daemon channel.
- `activity.py` — one upstream `Watch`, renumbered so the page's cursor survives daemon
  restarts.
- `video.py` — one `WatchVideo` per serial, a 120 s / 32 MiB GOP history kept as one
  segment per stream (a rotation starts the next), readers and sinks.
- `recorder.py`, `media.py`, `recordings.py` — recordings and clips muxed to MP4 with PyAV,
  one part per stream (`video-1.mp4`…), `steps.json`, and the library
  (`~/.tap/recordings/watcher`, `TAP_WATCHER_RECORDINGS`; `--keep-days N` deletes old entries).
- `service.py`, `server.py` — the `tap.watcher.v1` Connect service (`proto/watcher.proto`)
  and the HTTP routes.
- `web/` — the React page.

Regenerate bindings with `.venv/bin/python clients/watcher/scripts/gen_protos.py` after
contract edits. Checks: `pytest clients/watcher/tests`, and `bun run test` / `bun run build`
in `clients/watcher/web`. Design: `.docs/test-watcher.md`; review and its fix status:
`.docs/test-watcher-review.md`.
