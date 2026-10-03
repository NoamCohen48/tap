# tap-explorer (experimental)

App Explorer stores an evidence-backed exploration graph. Offline graph commands make no
remote calls. An optional `live` extra provides a **bounded, explicitly configured sample-device
pilot** with native landmark checks and observed-route replay. It does not infer unknown screens,
call AI, explore arbitrary downloaded apps, or generate robots. Its API and `tap-exploration/1`
JSON format are experimental. No tagged release is configured.

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
not a large-graph storage optimization. Connections serialize writes; use one connection per
thread. Operator recovery requires exclusive control over the previous executor's lifecycle.

The bounded pilot captures screenshots and node snapshots. No automatic redaction, secret
handling, remote AI submission, or artifact retention policy is implemented. Do not store credentials or sensitive observations; exports are not
sanitized reports. Keep files local with appropriate filesystem permissions.

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
