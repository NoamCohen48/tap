# tap-studio

**Experimental.** A browser inspector and action recorder for Android apps on a running `tap`
daemon: see the app's screen with its elements overlaid, act on them by clicking, and get what
you did as `tap-recording/1` JSON: element interactions (`tap res("search")`), never
coordinates. The design is `.docs/recorder.md` in the repository.

This is the first cut: the server, its access control, the recording format and the page shell.
The screen, inspector and recording arrive in the next phases.

```bash
tap-studio              # serves on a free loopback port and opens the page
tap-studio --no-open    # prints the link instead
```

The link carries a one-time launch token; the page signs in with it and the server refuses
anything else (loopback hosts only, same-origin only).

## Layout

- `proto/studio.proto`: the one contract, `tap.studio.v1`: the page's API (`StudioService`,
  served over Connect) and the `tap-recording/1` document (`Recording`). It imports `tap.v1`
  from `contracts/proto`.
- `tap_studio/`: the Python back end (Starlette + connect-python on `tap-e2e`); `_gen/` is
  generated.
- `web/`: the page (React + TypeScript 7, Bun + Vite); `src/gen/` is generated. `bun run build`
  writes it into `tap_studio/static/`, which the wheel ships.
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
