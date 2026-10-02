# tap-studio

**Experimental.** A browser inspector and action recorder for Android apps on a running `tap`
daemon: see the app's screen with its elements overlaid, select one and choose what to do with
it, and get what you did as `tap-recording/1` JSON: element interactions (`tap res("search")`), never
coordinates. The design is `.docs/recorder.md` in the repository.

So far: pick a device and the app under test, see its screen with the element overlay, select an
element and record a step on it from the composer (Act: tap, long press, swipe and scroll in four
directions with a distance, scroll until shown by scrolling, text and secrets; Assert: a check of
the element now; Wait: element waits), the app panel (launch, stop, clear, grant, app waits) and
the device bar under the phone, inspect an element's selector candidates and properties or the screen tree,
choose which selector candidate a step uses or type one in the SDK's DSL (with a live match
count), edit, insert, reorder and delete steps, replay all or part of the recording (it stops at
the first failure), and export and reopen `tap-recording/1` files. It needs a running daemon
(`tap start`).

```bash
tap-studio              # serves on a free loopback port and opens the page
tap-studio --no-open    # prints the link instead
tap-studio --serial emulator-5554   # also attaches the device at start
```

The link carries a one-time launch token; the page signs in with it and the server refuses
anything else (loopback hosts only, same-origin only).

User guide: `docs/guide/studio.md` (published at
<https://noamcohen48.github.io/tap/guide/studio/>).

## CI and releases

- CI `studio` job: `gen_protos.py --check`, the page's tests and build, the back end's tests,
  and a wheel that must carry the built page (`studio-dists`).
- CI `device-tests`: `.github/scripts/studio_smoke.py` runs that wheel on an API 34 emulator
  through the studio's own API: record, export, New, Open, Replay, SIGTERM frees the device,
  and the exported file replays through `tap-e2e`. Run it locally with
  `TAP_BIN=<tap> SERIAL=<serial> .venv/bin/python .github/scripts/studio_smoke.py`.
- Releases: tag `client-studio/vX.Y.Z` matching `version` in `pyproject.toml`
  (`.github/workflows/release.yml`); the wheel and sdist go on a GitHub Release.

## Layout

- `proto/studio.proto`: the one contract, `tap.studio.v1`: the page's API (`StudioService`,
  served over Connect) and the `tap-recording/1` document (`Recording`). It imports `tap.v1`
  from `contracts/proto`.
- `tap_studio/`: the Python back end (Starlette + connect-python on `tap-e2e`): `server.py`
  (the app, login and access checks), `service.py` (`StudioService`: session, device,
  recording), `screen.py` (the per-device worker thread and the frame loop), `steps.py`
  (completing and running a step), `recording.py` (the `tap-recording/1` document); `_gen/` is
  generated.
- `web/`: the page (React + TypeScript 7, Bun + Vite): `App.tsx` (top bar, the three
  areas), `ScreenView.tsx` (frame, overlay, selecting), `Composer.tsx` (the selected element, the
  Act / Assert / Wait tabs, the scroll until search), `AppPanel.tsx`, `DeviceBar.tsx`,
  `controls.tsx` (rows and direction buttons), `Inspector.tsx` (properties, screen tree), `count.ts` (live
  match counts), `StepsPanel.tsx`
  (steps, replay controls, open, export), `StepEditor.tsx` (a step's selector, value, secret and
  note), `Dialogs.tsx`, `frames.ts` (the `Frames` stream), `replay.ts` (the `Replay` stream),
  `describe.ts` (steps and selectors as SDK calls), `parse.ts` (a typed selector back into the
  proto), `edit.ts` (reading and rewriting a step, its warnings), `geometry.ts` (hit-testing),
  `steps.ts` (`Perform` requests); `src/gen/` is generated. `bun run build` writes it into
  `tap_studio/static/`, which the wheel ships.
- `scripts/gen_protos.py`: regenerates both `_gen/` and `web/src/gen/` from the proto
  (`--check` verifies them; needs `bun install` in `web/` first).

## Development

```bash
# from the repository root, with the repo's .venv
uv pip install --python .venv/bin/python -e clients/python -e "clients/studio[dev]"
(cd clients/studio/web && bun install)
.venv/bin/python clients/studio/scripts/gen_protos.py      # after editing a .proto
.venv/bin/python -m pytest clients/studio/tests            # back end
(cd clients/studio/web && bun run test && bun run build)   # page: Vitest, tsc, Vite

# live page development: the back end on a fixed port, the page from Vite
tap-studio --port 8787 --page-origin http://127.0.0.1:5173 --no-open
(cd clients/studio/web && bun run dev)                     # then open the printed link
```
