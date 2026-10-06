# App Explorer sample AUT

A separate disposable Android package: `io.github.noamcohen48.tap.explorer.sample`.
It requests **no permissions**, has no network/storage/accounts/services, and keeps no user data.
It does not depend on sync-sdk or host validation. Never install it over a personal app.

The bounded pilot discovers eight configured states and seventeen approved tap/fill candidates:

```text
Home → About dialog → Home
Home → Profile empty → validation error → Profile ready → Welcome
Home → Preferences off ⇄ Preferences on
```

Every non-home state has an explicit return control. Reopening Profile resets the field;
reopening Preferences resets the temporary option. This avoids pretending that a visually
identical Home state restores hidden backend/app data in arbitrary applications.

## Build and run on an explicitly chosen device

```bash
./gradlew :samples:explorer-app:assembleDebug :samples:explorer-app:lintDebug
uv pip install --python .venv/bin/python -e clients/python -e 'clients/explorer[live,dev]'
# Start a daemon explicitly first if one is not running; never let a client start it.
tap start
.venv/bin/tap-explorer sample --serial YOUR_SERIAL \
  --apk samples/explorer-app/build/outputs/apk/debug/explorer-app-debug.apk \
  --out .tap/explorer/sample-run-1
```

`--out` must be new. The command installs/cold-launches **only this sample**, acquires the serial
without waiting/stealing, observes nodes and screenshots, walks the finite policy, checks native
landmarks and sample assertions, and replays already-observed routes with fresh attempts.
It force-stops the sample only after a clean run and disconnects on exit. It does not stop the
daemon, clear app data, uninstall, grant permissions, reboot, or touch another device.

## Evidence and interpretation

Output: `graph.db`, `graph.json`, `report.json`, `events.json`, and an `observations/` directory
with PNGs and snapshot JSON. The graph preserves all route trials and observed edges, including
invalid-submit self-loops. Native selector checks validate the error, name, greeting and toggle
values. The final protobuf-aware command audit compares all actual tap/fill calls and package-
qualified resource selectors with the ordered persisted attempts, rejecting extra input or
log eviction.
Bootstrap install/cold-launch/clean force-stop are logged separately, not counted as graph edges.

This is a **configured sample pilot**, not automatic understanding of an arbitrary app, AI
vision, exhaustive exploration, safe downloaded-APK validation, or robot generation. Unknown
screens, frame drift, route divergence, failed actions and uncertain transport stop execution.
No mutation is retried. An interrupted/old run is not automatically resumed; use a fresh output
for an explicitly approved new run of this disposable sample.

## Physical evidence

Two fresh Samsung SM-J810G/API 29 runs passed: eight states, seventeen candidates, twenty-nine
successful attempts each (26 taps + 3 fills, including twelve route trials), 59 stable paired
captures and no dropped events. The ordered traversals matched. Dialog/error/greeting/toggle
screenshots were inspected, native checks passed, SQLite/export/import matched, and the phone
was FREE afterward. The emulator was not touched locally; its CI smoke is a separate check.
Detailed commands, APK hash, failed pre-input environment attempt and local artifact paths:
[`.docs/app-explorer.md`](../../.docs/app-explorer.md#21-bounded-sample-device-pilot--implemented-and-physically-verified).
