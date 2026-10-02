# tap-explorer (experimental, offline only)

The first App Explorer milestone stores an evidence-backed exploration graph. It does **not**
connect to Android, run actions, call AI, recognize screens, verify routes, or generate robots.
Its API and `tap-exploration/1` JSON format are experimental. No tagged release is configured.

## Install and check

From the repository root:

```bash
python -m venv clients/explorer/.venv
clients/explorer/.venv/bin/python -m pip install -e 'clients/explorer[dev]'
clients/explorer/.venv/bin/python -m pytest clients/explorer/tests
clients/explorer/.venv/bin/python -m pyright --project clients/explorer/pyrightconfig.json \
  --pythonpath clients/explorer/.venv/bin/python
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
computed reachability guarantee. Routes and device preconditions are not checked yet.

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

No automatic redaction, screenshot capture, secret handling, remote submission, or artifact
retention is implemented. Do not store credentials or sensitive observations; exports are not
sanitized reports. Keep files local with appropriate filesystem permissions.

Design and subsequent phases: [`.docs/app-explorer.md`](../../.docs/app-explorer.md).
