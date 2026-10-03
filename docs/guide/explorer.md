# App Explorer (experimental)

App Explorer is under development. `tap-explorer` manages local exploration graphs and offers
a **bounded sample-device pilot**, with explicitly approved controls, native landmark checks,
and replay of already-observed routes. It does not automatically understand arbitrary apps,
call AI, generate robots, or resume an interrupted device run.

## Install from source

From a Tap checkout:

```bash
python -m venv .venv
.venv/bin/python -m pip install -e clients/python -e 'clients/explorer[live,dev]'
```

Use `.venv/bin/tap-explorer` below, or activate that environment first. There is no tagged
explorer release. The bare package has no dependencies and supports offline graph management;
`live` adds `tap-e2e`. Developer tests use the SDK's in-process fake daemon, not a real device.

## Explore the disposable sample

Build the sample and ensure the daemon is running (start it explicitly with `tap start`):

```bash
./gradlew :samples:explorer-app:assembleDebug :samples:explorer-app:lintDebug
tap-explorer sample --serial YOUR_SERIAL \
  --apk samples/explorer-app/build/outputs/apk/debug/explorer-app-debug.apk \
  --out .tap/explorer/sample-run-1
```

The sample package `io.github.noamcohen48.tap.explorer.sample` has no permissions, network,
accounts, services, or persistent data. Eight configured states cover navigation, a modal,
empty-form errors, a fictional name (`Ada`), a greeting, and a temporary toggle. Returning Home
and reopening a page resets its temporary values. Seventeen tap/fill candidates are approved
by the sample policy; other controls are not trusted automatically.

The command acquires only the named serial without waiting/stealing, installs/cold-launches
only the sample, captures screenshots/nodes, walks approved actions, and checks the known
fixture outcomes. It force-stops the sample only on success and disconnects on exit. It never
starts/stops the daemon, clears data, grants permissions, uninstalls, reboots, uses coordinates,
or navigates a personal app. Do not pass an unrelated downloaded APK to this sample command.

Output must be new. Files: `graph.db`, `graph.json`, `report.json`, `events.json`, and
`observations/` PNG/snapshot pairs. Snapshot→screenshot→snapshot captures are not atomic;
changes around the screenshot stop execution rather than authorizing input on a drifting frame.

Already-observed routes are checked before/after each step through ordinary selectors. Each
route mutation is a **new approved invocation** with a fresh persisted attempt. Unknown screens,
ambiguous sources, failed checks, drift, or divergence stop before the next input. Transport
uncertainty never triggers a mutation retry. Bootstrap install/launch/clean stop are recorded
in the daemon log outside the graph's tap/fill budget.

This proves a configured slice, not whole-app coverage, AI understanding, arbitrary-app safety,
or backend reset. A generic graph edge alone is not a business-correctness assertion; the
sample's human-defined oracle and native checks establish its expected outcomes. The final
`command_audit` parses the actual protobuf log and compares every ordered tap/fill to saved
attempts; extra inputs, other-package/index selectors or an evicted log cannot pass.

### Verified physical pilot

Two fresh runs on a Samsung SM-J810G (API 29) passed: each discovered eight configured states
and seventeen candidates, with **29 successful attempts** (26 taps, three fills, including
twelve checked route trials) and 59 PNG/snapshot pairs. Both ordered traversals matched; the
device was released. This validates the sample policy, not arbitrary apps or the API 34 CI
emulator result. Detailed evidence and limitations are in `.docs/app-explorer.md` in the checkout.

## Create and inspect an offline graph

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

The experimental JSON format is `tap-exploration/1`. Import validates references/statuses and
requires an empty store. Artifact references remain metadata; images are not copied. Offline
target/scenario objects are opaque, not executable commands trusted by the device pilot.

The [package README](https://github.com/NoamCohen48/tap/tree/main/clients/explorer) documents
`GraphStore`, `BoundedExplorer`, and the sample flow. These APIs/formats are outside the
compatibility promise. Reports count configured candidates only, not app coverage percentages.

## Interruption and privacy

An unfinished intent or indeterminate outcome blocks subsequent attempts. Opening the store
never automatically recovers it. After confirming the previous executor is stopped:

```bash
tap-explorer --db graph.db recover --executor-stopped
```

Recovery marks interrupted attempts uncertain; it does not replay, requeue, or reconcile them.
Old device runs cannot be automatically resumed. An approved new sample run needs a new output
and a fresh explicit sample bootstrap.

No redaction or retention policy is implemented. Do not put credentials or sensitive data in
these graphs/artifacts. Keep files local and control filesystem access. The pilot talks to the
configured Tap daemon; the pilot does not call an AI provider.
