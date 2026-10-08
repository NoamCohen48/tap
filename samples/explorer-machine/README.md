# Explorer state-machine benchmark

Disposable, memory-only Android wizard. **No permissions, network, storage, accounts or
services.** Separate package: `io.github.noamcohen48.tap.explorer.machine`. Ordinary UI exposes
names, validation, Basic/Pro and fictional terms, details, retained edits, review and receipt.
There are no explorer state IDs. With values `""` and `"Ada"`, the intended visible business
machine has 30 states.

## Build and run

```bash
./gradlew :samples:explorer-machine:assembleDebug :samples:explorer-machine:lintDebug
# Start the daemon explicitly with tap start; install the explorer live extra.
.venv/bin/python samples/explorer-machine/benchmark.py --serial YOUR_SERIAL \
  --apk samples/explorer-machine/build/outputs/apk/debug/explorer-machine-debug.apk \
  --out .tap/explorer/machine-fresh --business-controls-only --max-elapsed 1800
```

Output must be new. First use requires the package absent. The script installs only this
fixture and cold-launches it, never starts/stops the daemon. For an **explicitly reviewed,
already installed fixture**, add `--reuse-reviewed-fixture`: no reinstall or automatic resume
occurs; it cold-launches a new activity and starts a new graph. Only a successful run
force-stops the app. It acquires only the named serial, without waiting or stealing.

`benchmark.py` supplies **execution approvals**, not screen recognition or expected routes.
Its fixture-only resource allowlist authorizes local taps/fills with two fictional literals.
`--business-controls-only` leaves input-focus taps blocked; these create transient cursor
windows, not required business-flow transitions. This policy must never be applied to an
unfamiliar downloaded app. All proposals/evidence remain stored, including unsupported controls.

Traversal tries reviewed local candidates, then fresh checked trials of shortest observed
single-outcome routes. No invented Back/reset, coordinates, permissions, clear-data, uninstall,
reboot, mutation retransmission or AI call. Budgets: 400 input attempts, depth 100, 600 traversal
iterations, and 1..1800 elapsed seconds. `reviewed_frontier_resolved` is only this finite policy's
frontier, not a whole-app coverage percentage.

## Independent business assertions

```bash
.venv/bin/python samples/explorer-machine/check_business.py \
  --serial YOUR_SERIAL --out .tap/explorer/machine-business-check
```

Requires the reviewed fixture installed. This is a **seeded oracle**, not discovery: 11 checks
exercise empty/corrected names, conditional terms gating, error clearing, retained edits, text
clear, receipt values, fresh entry and Basic/Pro rules. It uses fresh cold launch and saved
intents/screenshots; the final event audit matches each native input to an attempt.

## Measured results and limits

Physical Samsung SM-J810G/API 29 (`85e49002`): final unseeded recognition/traversal run found
**30 states, 108 distinct edges, 236 successful mutations and 473 PNG/snapshot pairs** in
1121.367 seconds. Reviewed frontier resolved; no conflicts, uncertainty or dropped log events;
ordered input audit passed. Separate business oracle passed 11 checks / 21 mutations. Phone
released and fixture stopped on success. See `.docs/app-explorer.md` section 23 for hashes,
source of approval, retained failed runs and scope restrictions.

Earlier runs exposed a missing-package preflight bug, duplicated selector package predicates,
transient focus-window divergence, and a fixture validation bug (`CheckBox.error` shadowed
`MainActivity.error`). They remain evidence, not overwritten successes. Graph-read caching
improved the measured saved-graph read microbenchmark; traversal is still slow.

This demonstrates finite **visible-state** logic, not hidden/backend-state equivalence,
autonomous risk assessment, unfamiliar-app understanding or broad scalability. Fake-gRPC
oracle tests also demonstrate history-dependent identical screens: unknown divergence stops;
known conflicting outcomes cannot be used as deterministic routes.
