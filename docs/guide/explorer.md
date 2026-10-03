# App Explorer (offline foundation)

App Explorer is experimental and under development. The current `tap-explorer` package manages
local exploration graphs only. It cannot explore a running app, call AI, verify routes, or
generate screen robots yet. No daemon or Android device is needed.

## Install from source

From a Tap checkout:

```bash
python -m venv clients/explorer/.venv
clients/explorer/.venv/bin/python -m pip install -e 'clients/explorer[dev]'
```

Use `clients/explorer/.venv/bin/tap-explorer` below, or activate that environment first.
There is no tagged explorer release yet.

## Create and inspect a graph

Prepare a non-sensitive context file:

```json
{"app":"example.app","build":"test","starting_condition":"Home, logged out"}
```

```bash
tap-explorer --db graph.db init --context context.json --max-actions 100 --max-depth 10
tap-explorer --db graph.db status
tap-explorer --db graph.db export > graph.json
tap-explorer --db restored.db import graph.json
```

The JSON format is `tap-exploration/1`. Import validates IDs, references, and attempt status;
it requires an empty store. Artifact references remain metadata; images are not copied.
The format and Python API are experimental and outside the compatibility promise.

Observations, explicit state classifications, action candidates, and recorded outcomes can be
added through `tap_explorer.GraphStore`. Its docstrings and the
[package README](https://github.com/NoamCohen48/tap/tree/main/clients/explorer) describe the offline
API and provide an example. Action targets and scenarios are opaque metadata at this stage,
not validated executable selectors. Screen recognition and device execution are future work.

## Safety and interruption

Candidates start blocked and require explicit approval. The scheduler orders approved pending
candidates deterministically and enforces finite action/depth budgets when recording intent.
It does not check device state, route reachability, or the safety of arbitrary supplied metadata.

An unfinished intent or indeterminate outcome blocks further attempts. Opening the store does
not automatically change that status. Once the previous executor is definitely stopped:

```bash
tap-explorer --db graph.db recover --executor-stopped
```

This marks interrupted attempts uncertain. It does **not** replay input, requeue actions, or
resolve their effects. Uncertain runs remain blocked in this milestone; automatic reconciliation
is not implemented. Separately approved new trials of completed actions are distinct invocations,
never transport retries.

A successful command with an observed destination creates an evidence-backed edge, not a
verified route or a business-correctness assertion. Reports count configured candidates only;
resolved frontier does not mean that the whole app has been explored.

## Privacy

No redaction or artifact-retention policy is implemented. Do not put credentials or sensitive
observations in these development graphs. Exports are not sanitized; keep files local and
control filesystem access. No data is submitted to AI or any remote service.
