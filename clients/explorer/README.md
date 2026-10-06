# tap-explorer (experimental)

App Explorer stores an evidence-backed exploration graph. Offline graph commands make no
remote calls. An optional `live` extra provides a **bounded, explicitly configured sample-device
pilot** with native landmark checks and observed-route replay. The new discovery workbench
provides snapshot-derived states/candidates and opt-in nano-model proposals, not autonomous
authorization or robot generation. Its unfamiliar-APK/live-provider validation remains pending.
Its API and `tap-exploration/1` JSON format are experimental. No tagged release is configured.

## Install and check

From the repository root:

```bash
python -m venv .venv
.venv/bin/python -m pip install -e clients/python -e 'clients/explorer[dev]'
.venv/bin/python -m pytest clients/explorer/tests
.venv/bin/python -m pyright --project clients/explorer/pyrightconfig.json \
  --pythonpath .venv/bin/python
```

Activate this environment, or use its `bin/tap-explorer` executable for the commands below.
The workspace editor configuration (`pyrightconfig.json`) checks only this first typed client;
it assumes a repository-root `.venv` containing the installed explorer and dev dependencies.
CI uses a root `.venv` too, with an explicit interpreter/project for the type check.

## Manage a run

Create `context.json` with non-sensitive app/build/starting-condition metadata:

```json
{"app":"example.app","build":"test","starting_condition":"Home, logged out"}
```

```bash
tap-explorer --db graph.db init --context context.json --max-actions 100 --max-depth 10
tap-explorer --db graph.db status
tap-explorer --db graph.db export > graph.json
tap-explorer --db restored.db import graph.json
```

Import requires an empty store and validates references/statuses. Exports contain metadata;
artifact paths are not followed or copied. Unknown format versions are rejected. `status`
reports configured candidates only, not percentage coverage of an app.

## Populate an offline graph

```python
from tap_explorer import GraphStore

with GraphStore("graph.db") as graph:
    graph.add_observation("home-1", {"snapshot": "home.json", "screenshot": "home.png"})
    graph.add_state("home", "home-1", signature="home-v1", depth=0)
    graph.add_action("open-profile", "home", "tap", target={"resource": "profile"})
    graph.approve_action("open-profile", "operator: approved fixture navigation")
    assert graph.next_action()["action"] == "open-profile"
    graph.begin_attempt("attempt-1", "open-profile", "home-1")
    # In this milestone a recorded observation supplies the outcome; no input is executed.
    graph.add_observation("login-1", {"snapshot": "login.json", "screenshot": "login.png"})
    graph.add_state("login", "login-1", signature="login-empty-v1", depth=1)
    graph.finish_attempt("attempt-1", "succeeded", after="login-1", destination="login")
    print(graph.transitions())
```

IDs are supplied by callers. Observations and completed attempts cannot be overwritten.
State grouping is explicit; equal signatures never merge automatically. Target/scenario
objects are opaque metadata, **not validated executable selectors or commands** yet.

Candidates start blocked. Explicit approval makes them pending. The scheduler orders pending
candidates by priority (lower first), source depth, source ID, then action ID. `begin_attempt`
atomically enforces that choice and the finite action/depth budgets. Attempts, including rejected
input and new trials, consume the action budget. Depth is caller-supplied state depth, not a
computed reachability guarantee. `GraphStore` itself does not execute or check device routes;
the optional `BoundedExplorer` layer checks native preconditions and already-observed routes.

## Uncertainty and new trials

Persist intent **before** input; persist its result afterward. Opening a database does not
modify unfinished intents. An intent or indeterminate outcome blocks all further attempts.
After confirming the previous executor has stopped:

```bash
tap-explorer --db graph.db recover --executor-stopped
```

Recovery marks unfinished intents indeterminate. It does not replay, requeue, or reconcile
anything. In this milestone uncertain runs remain blocked; there is no automatic reconciliation
or override API. Never mistake missing results for proof that input was not sent.

`finish_attempt` distinguishes `succeeded`, `failed_before_input`, and `indeterminate`.
Success alone need not have a recognized destination. Transitions include only successful
attempts with an explicitly classified destination; they do not establish correctness or route
verification. A source/action can have multiple destinations or self-loops.

A completed action is not automatically rescheduled. `begin_trial(..., approval="...")`
records an explicitly approved **new invocation**, after the caller re-establishes/checks the
source condition. It obeys budgets and the uncertainty gate, and preserves prior attempts.
It is not a transport retry.

## Storage and privacy

SQLite commits each graph change transactionally; the current implementation stores a whole
versioned graph document in one row. This is deliberately an offline small-run foundation,
not a large-graph storage optimization. Already validated immutable JSON is cached for reads;
SQLite data_version and own-connection changes invalidate it, and returned documents stay
detached. Connections serialize writes; use one connection per thread. Operator recovery requires exclusive control over the previous executor's lifecycle.

The bounded pilot captures screenshots and node snapshots. No automatic redaction, secret
handling or artifact retention policy is implemented. Optional AI submission requires explicit
non-sensitive evidence review; it is not automatic redaction. Do not store credentials or sensitive observations; exports are not
sanitized reports. Keep files local with appropriate filesystem permissions.

## Discovery workbench

Build the React Flow page, then open a historical run without acquiring a device:

```bash
(cd clients/explorer/web && bun install && bun run test && bun run build)
tap-explorer view --run .tap/explorer/sample-run-1
```

For a **new** human-reviewed test session, with an explicitly running daemon and a trusted APK
whose package is not installed:

```bash
tap-explorer explore --serial YOUR_SERIAL --package YOUR_TEST_PACKAGE \
  --apk trusted-test.apk --out .tap/explorer/discovery-1 --max-actions 20
```

`discovery.py` retains inspectable structural/text identity with explicit volatile-resource
exclusions, not AI names or pixel hashes. `session.py` gates native input on fresh source,
selector quality, operator approval and finite budgets. `web.py` serializes SQLite/SDK ownership,
constrains artifact paths, and requires loopback Host, same Origin and session-token checks for
POST operations. The browser cannot submit selectors, commands or arbitrary file paths.

Interpretation uses `OpenAIVision` in `ai.py`, strictly pinned to deprecated
`gpt-5-nano-2025-08-07` (four low-detail calls / 2048 output tokens by default). Set
`OPENAI_API_KEY` on the backend and review evidence first. No flagship, automatic retry or
model fallback; fake tests cost zero. AI cannot approve input or merge states. Real-provider
and unfamiliar-APK validation are not yet complete. See the [public guide](../../docs/guide/explorer.md)
for operation, safety, privacy and known limits.

## Measured complex-fixture discovery

The [state-machine fixture](../../samples/explorer-machine/README.md) exercises validation,
conditional plan/terms choices, retained edits and confirmation without explorer state labels.
Physical API 29 result: **30 states / 108 distinct edges / 236 successful executions / 473
stable screenshot pairs**, reviewed frontier resolved, ordered event audit passed; 18.7 minutes.
Separate seeded business assertions passed 11 checks / 21 mutations. This uses an explicit
fixture-only control policy, two fictional input values and no focus-only field taps; it is
not autonomous unfamiliar-app safety or a large-app performance result.

Known divergent source/action outcomes cannot serve as deterministic routes. Unknown hidden
history remains a limitation: a fresh trial may diverge, then stop without retry or further
input. Prior physical failures are retained in `.docs/app-explorer.md` section 23.

## Offline identity benchmark

```bash
tap-explorer benchmark --corpus clients/explorer/benchmarks/corpus-v2.json \
  --policy similarity --require-no-screen-merges
```

Scores 114 retained observations and owned raw snapshot nodes against fixture-oracle and
provisional analyst labels. Adds a deterministic complete-link similarity candidate to the
exact/skeleton baselines, with pair and cluster-level errors. Any labeled screen merge fails
the optional CLI gate. The candidate has zero screen errors on this in-sample corpus; missing
error metadata still causes variant merges. No independent held-out or human-reviewed Loop
validation yet. Standard library only: no SDK/provider calls or live identity/approval changes.
These are not coverage or robot-accuracy scores. See [the corpus documentation](benchmarks/README.md).

## Robot drafts

```bash
tap-explorer robots --run .tap/explorer/discovery-1 --out robots/ [--review robots/review.json]
```

`robots.py` builds unverified page-object drafts (`tap-robots/1`), offline and without AI:
- screens are complete-link similarity groups;
- `verify()` landmarks form a greedy discriminating set;
- methods come from succeeded attempts, one per observed outcome, with the separating source
  conditions as a precondition to confirm;
- expectations are parameterized from what varied (role-normalized `similarity.variant_items`).

Output is `robots.py`, `robots.json` and `REVIEW.md`. Human decisions live in `review.json`
and are re-applied on every regeneration. See the
[public guide](../../docs/guide/explorer.md#draft-robots-from-a-run).

`score.py` (`tap-explorer robots-score --run DIR --key KEY`) scores a draft against a
hand-written answer key (`tap-robots-key/1`, in `benchmarks/keys/`), with no human input and
after a key-knowing reviewer answers the `--ask` questions. Results:
[benchmarks/README.md](benchmarks/README.md#robot-answer-keys).

## Bounded sample-device pilot

Build the disposable, no-permission sample and install the live extra:

```bash
./gradlew :samples:explorer-app:assembleDebug :samples:explorer-app:lintDebug
uv pip install --python .venv/bin/python -e clients/python -e 'clients/explorer[live,dev]'
# Requires a running daemon, started explicitly by the operator with tap start.
.venv/bin/tap-explorer sample --serial YOUR_SERIAL \
  --apk samples/explorer-app/build/outputs/apk/debug/explorer-app-debug.apk \
  --out .tap/explorer/sample-run-1
```

Only the named serial and `io.github.noamcohen48.tap.explorer.sample` are used. Output must be
new; the pilot will not resume an old graph. It installs/cold-launches the sample, observes
screenshots/nodes, discovers seventeen configured tap/fill candidates across eight known states,
walks them using checked observed routes, checks the sample oracle, and releases the device.
It force-stops the sample only after success. No daemon restart, clear-data, permissions,
uninstall, reboot, coordinates, implicit Back, external package, or AI is involved.

`graph.db`, `graph.json`, `events.json`, `report.json`, and PNG/snapshot pairs are retained.
Route replay is a new approved invocation with a new persisted attempt, never a transport retry.
Unknown screens, ambiguous sources, drift and route divergence stop before the next input;
uncertain failures stop immediately. A known command success with failed post-observation has
no destination edge and also stops. Bootstrap install/launch/clean stop are logged outside the
graph's tap/fill budget. See [the sample guide](../../samples/explorer-app/README.md).

Design and subsequent phases: [`.docs/app-explorer.md`](../../.docs/app-explorer.md).
