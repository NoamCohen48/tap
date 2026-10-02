# Tap Watcher (experimental)

Standalone read-only browser client: device ownership, streamed action logs, shared live
video, buffered playback/action seeking, approximate Before/After/Compare, bounded recording,
trim and ZIP export (MP4 + timestamped JSON). Test-marker integration is deferred.

Video requires an external scrcpy **4.1 server JAR** on the daemon host: set `TAP_VIDEO_SERVER`
before `tap start`, or use `/usr/share/scrcpy/scrcpy-server`. No binary is bundled. Use Chromium
on localhost for H.264 WebCodecs. PyAV muxes exports locally, without re-encoding or device
calls. Full limits, time mapping and usage: `docs/guide/watcher.md`.

```sh
# From the repository root; clients never start the daemon.
uv venv .venv
uv pip install --python .venv/bin/python -e clients/python -e 'clients/watcher[dev]'
(cd clients/watcher/web && bun install && bun run build)
.venv/bin/tap-watcher
```

Open the printed login URL. The server binds loopback, guards Host/Origin and uses a per-launch
HttpOnly cookie; the daemon token stays on the backend. The watcher does not Connect, Attach,
Observe, call driver screenshots or renew owner idle timeouts.

Start recording → Stop & save writes clips automatically to
`~/.tap/recordings/watcher/<id>/` (override with `TAP_WATCHER_RECORDINGS`). Resume starts a
separate clip. Saved recordings survive browser/server restart and play from the page;
ZIP and JSON downloads are available. The 2 GiB library limit refuses new saves rather
than deleting your files. Live preview itself only keeps a rolling memory buffer.

Regenerate bindings with `.venv/bin/python clients/watcher/scripts/gen_protos.py` after
contract edits. Unit checks: `pytest clients/watcher/tests`, and `bun run test`/`bun run build`
in `clients/watcher/web`. UI concept and current decisions: `.docs/test-watcher.md`.
