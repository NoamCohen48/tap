# App Explorer (experimental)

App Explorer is under development. `tap-explorer` manages local exploration graphs and offers
a **bounded sample-device pilot**, with explicitly approved controls, native landmark checks,
and replay of already-observed routes. A new **experimental discovery workbench** derives
states and blocked action proposals from snapshots, with optional reviewed nano-model vision.
It does not automatically authorize unfamiliar actions, generate robots, or resume an
interrupted device run. A first real-app trial has partial results and significant coverage
gaps; broad real-app reliability and real-provider validation remain unproven.

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

## View an evidence graph

Build the local page once (Bun required):

```bash
cd clients/explorer/web
bun install && bun run test && bun run build
cd ../../..
tap-explorer view --run .tap/explorer/sample-run-1
```

Open the loopback URL printed by the command. The viewer acquires **no device**. Select a
state or transition to inspect screenshots, selectors and append-only attempts. Search states,
zoom/pan the graph, and switch light/dark themes. Repeated executions share a visual edge but
retain separate attempts; self-loops and distinct action/destination edges are preserved.
Screenshot paths must resolve inside the selected run directory; artifacts are not copied.

## Fresh reviewed discovery (experimental)

Use only a trusted, disposable APK whose package is **not already installed**. Start the Tap
daemon explicitly, then:

```bash
tap-explorer explore --serial YOUR_SERIAL --package YOUR_TEST_PACKAGE \
  --apk trusted-test.apk --out .tap/explorer/discovery-1 --max-actions 20
```

No screen/action catalog is supplied. Conservative structural/text signatures classify states.
Other apps' windows (a dialog, the keyboard, the notification shade) count only by their root
and its direct children, so a status-bar notification icon is not a new state
(`tap-state-signature/2`). Capabilities propose tap/fill candidates, initially blocked. Inspect
evidence and provide an approval reason before executing one scenario. Password, duplicate, disabled, index and
unsupported selectors cannot be approved. Input is native exact-one, preceded by fresh source
checks and durable intent. Observed routes are checked fresh invocations, never transport retries.
An approval can be withdrawn until it runs (**Withdraw**). If the screen changed after you
approved, for example a list that finished rendering late, the stale approval would otherwise
stay first in line and hold every later step.

The session has finite action/depth/time budgets. Permission/foreign focus, drift, uncertainty,
or post-state failure blocks input; there is no automated Back/reset, broad permission grant,
resume or cleanup mutation. Closing releases its connection without blindly stopping the app.
Installation/cold-launch are explicit bootstrap mutations outside the graph's action budget.
A restricted app may expose very little; this is not a whole-app coverage claim.

### Measured branching-wizard result

A separate [no-permission state-machine fixture](https://github.com/NoamCohen48/tap/blob/main/samples/explorer-machine/README.md)
passed snapshot-derived discovery on a Samsung API 29 phone: **30 states, 108 distinct
transitions, 236 successful mutations and 473 stable screenshot/snapshot pairs**. An independent
seeded oracle passed 11 business checks in 21 audited mutations. No screen catalog or transition
map guided discovery; a fixture-specific reviewed control policy and two literal input values
provided execution authority. Focus-only input taps were outside the final business-flow policy.

The run took 18.7 minutes, so large-app performance is not established. Earlier failed runs
exposed transient cursor-window divergence and a real fixture validation bug; their evidence
was retained. Matching visible features does not prove equal hidden/backend state. Known
conflicting source/action outcomes are excluded from routing; an unknown conflict stops a
fresh trial after detecting its unexpected outcome. This is not unfamiliar-app or AI validation.

### First real-app result: partial coverage

A reviewed trial of F-Droid **Loop Habit Tracker 2.3.1** on API 29 recorded **24 states,
25 successful native actions and 54 stable capture pairs**. Fourteen independent checks covered
Yes/No versus Measurable forms, name validation, custom frequency, At most/weekly numeric
values and three saved fictional records. All input matched the saved approvals/intents.
No reminders, permissions, accounts, deletion, import/export or AI calls were used.

This also exposed concrete weaknesses: a validation icon was absent from state features;
transient creation Snackbars produced extra variants; custom habit/day controls and
filter rows lacked non-index executable proposals. Details/history/check-ins/filter branches
were not validated. **This is not complete real-app coverage or a general accuracy percentage.**
The failed initial capture, fresh trial and fixes are documented in `.docs/app-explorer.md`
section 24 in the checkout.

### Optional AI interpretation

Set `OPENAI_API_KEY` only in the backend environment. Review the retained screenshot/nodes for
sensitive content before using **Ask nano model**. The prototype pins
`gpt-5-nano-2025-08-07`: four calls maximum, 2048 output tokens each, low-detail images, no
retry or more expensive fallback. This deprecated model may be unavailable; interpretation
then fails closed. Automated tests use fake transport and incur no provider cost.

Titles/intents/risk are suggestions, never state-merging or input approval. Requests reserve
budget durably; accepted identical cached results avoid another call. No real-provider call
has yet been validated. Screenshots and minimized node text are sent to OpenAI only after
explicit review; `store=false` is not a promise of provider-side zero retention.

## Compare discovery policies offline

The checkout includes an evidence-only regression corpus and a scorer requiring no SDK, device,
AI provider or database:

```bash
tap-explorer benchmark --corpus clients/explorer/benchmarks/corpus-v2.json \
  --policy similarity --require-no-screen-merges
```

The 114 cases retain owned raw snapshot nodes as well as original features. A complete-link
similarity candidate compares structure/text overlap and cannot chain distant observations
together. Reports include pair and cluster-level errors; the optional flag returns 1 for any
labeled screen merge. The candidate separates Loop's dropdown menus and groups its list
with/without a Snackbar on the supplied provisional labels. Error-condition merges remain.
Those numbers are in-sample. A held-out wizard set (four fresh runs on another device, labeled
by the fixture's oracle before scoring) gives `similarity-roles` no screen or variant error, but
it is the same app: no held-out app has been scored yet.
No policy changes live identities, approvals or routes. See the
[corpus README](https://github.com/NoamCohen48/tap/blob/main/clients/explorer/benchmarks/README.md) for label definitions and limits.

### Review the provisional labels

Loop's labels were written by the analyst who designed the policy. `label-review` serves a
compact loopback page: each case's screenshot, its proposed screen and variant label, and
**Accept**, **Correct** and **Clear** buttons (keys `j`/`k`, `a`, `c`, `x`):

```bash
tap-explorer label-review --corpus clients/explorer/benchmarks/corpus-v2.json --source loop \
  --evidence .tap/explorer/real-app-loop/run-2/observations --decisions loop-labels.json
tap-explorer label-review --corpus clients/explorer/benchmarks/corpus-v2.json \
  --decisions loop-labels.json --apply corpus-reviewed.json
```

A screenshot appears only when the local snapshot still has the SHA-256 the corpus pinned.
Each decision records the label it saw. `--apply` writes a **new** corpus in which the decided
cases are `human-reviewed`, and refuses a decision whose label has changed since.

## Draft robots from a run

`tap-explorer robots` turns a retained run into **unverified** robot (page-object) drafts. It
needs no device, daemon or AI provider:

```bash
tap-explorer robots --run .tap/explorer/discovery-1 --out robots/ [--review robots/review.json]
tap-explorer robots --run .tap/explorer/discovery-1 --out robots/ --review robots/review.json --ask
```

With `--ask` the command asks the review questions on the terminal and saves the answers to
`--review` before writing the draft. `s` skips a question (it comes back next time) and `q`
stops.

It writes three files:

- `robots.py`: one class per screen on the public `tap_e2e` API.
- `robots.json`: the language-neutral `tap-robots/1` document.
- `REVIEW.md`: the decisions a human must make, most consequential first.

How the draft is built:

- **One robot per screen.** Screens are the complete-link similarity groups from the benchmark,
  so the variants of a page (empty or filled form, a checked box) share one robot.
- **`verify()`** waits for a small set of landmarks: resource IDs or static texts that occur
  exactly once and that, together, no other observed screen has.
- **Methods come only from successful attempts.** They are named from the app's resource ID
  (`tap_save`, `enter_name`), falling back to the visible label. A method returns the robot of
  the screen it reached.
- **Outcome-dependent controls.** When the same control led to different screens, each outcome
  gets its own method (`tap_next_rejected`, `tap_next_to_review`). Each method documents the
  observed source conditions that separated the outcomes, for example `name is empty`, or
  `terms unchecked and tier checked`. These are what the evidence showed, not a proven rule:
  confirm them in review.
- **Expectations cover only what varied on the screen.**
  - Text that took several values becomes a parameterized check: `expect_summary(value)`.
  - A control whose checked state varied becomes `expect_terms_checked(checked=True)`.
  - A message that appeared only sometimes becomes a named check, plus `expect_no_<id>()`.
  - A list that was empty in some observations and had rows in others becomes
    `expect_<id>_empty(empty=True)`, waiting on the observed row class.
  - A field error (`TextView.setError`) that appeared only sometimes becomes
    `expect_<id>_error(error)`. No element API reads a field error yet (the screen snapshot is
    diagnostic, not an assertion), so its body raises `NotImplementedError` for review.
  - Inputs the test sets itself are never expectations.
  - All of these are marked *observed, not business assertions* until promoted.
- **Unexplored controls** are listed per robot and in `REVIEW.md` as the next frontier.

What the tool asks rather than guesses:

- **Preconditions.** For an outcome-dependent method it offers the observed condition. When
  several facts each separate the outcomes alone, it asks which one is the cause. When no
  single condition explains them, it offers an *either/or* reading, such as
  `name is empty or terms unchecked`. When a fact the test controls was only ever wrong
  together with the observed condition, the run cannot say whether it matters. In Loop, Save
  was rejected with the name and the target both empty, then with only the target empty, so
  `targetInput is not empty` separates. Whether the name matters too was never tried. The draft
  keeps the observed condition, names the combination that was never tried, and offers the
  combined condition (`targetInput is not empty and nameInput is not empty`); the other outcome
  gets the complement (`... is empty or ... is empty`). Trying the combination in the workbench
  answers it from evidence instead. A field error is never a precondition: it is what a
  rejection left behind.
- **Title-less screens.** Dialogs and menus without a title are named after their first
  options (`DiscardKeepEditingRobot`), and the question shows their texts so you can name them.
- **Workflows.** A screen with inputs and one control that leaves it gets a proposed
  `fill_and_<method>(…)` that fills the inputs and then taps forward. It is emitted only once
  accepted.
- **List checks.** Whether a test should check that a list is empty.

Record decisions in `review.json` (`tap-robots-review/1`) and regenerate. The generator applies
the file and never rewrites it. A screen's entry is keyed by its generated robot name and may
contain:

- `name`: rename the robot;
- `methods`: rename methods or expectations;
- `drop`: remove methods or expectations;
- `confirmed`: accept an outcome method's precondition;
- `preconditions`: replace one, as `{"mode": "all" | "any", "facts": [...]}`;
- `expectations`: promote an observed check to a reviewed one;
- `workflows`: accept (`true`) or decline (`false`) a proposed workflow method.

Renames propagate to return types in other robots.

### Verify the drafts by replay

A draft method is only a hypothesis until it has run. `robots-verify` replays every method on a
disposable emulator, restoring the same snapshot before each one:

```bash
tap-explorer robots-verify --run .tap/explorer/discovery-1 --out robots/ \
  --serial emulator-5560 --snapshot robots-ready --package com.example.app
tap-explorer robots --run .tap/explorer/discovery-1 --out robots/ \
  --review robots/review.json --verification robots/verification.json
```

For each method it plans a route from the run's first screen to the screen the method's evidence
started on, using only transitions that always led to the same screen. It then calls those robot
methods in order, and finally the method itself; each returned robot runs `verify()`. The
results go to `verification.json` (`tap-robots-verification/1`): `verified`, `failed` with the
error, or `unplanned` when no such route was observed. Regenerating with `--verification`
documents each method as verified, or adds a `replay-failed` question to the review.

It needs the live extra and a running server, and it resets only `emulator-` serials: a snapshot
load discards the device's state, so a physical device is refused. Take the snapshot with the
app installed and past any first-launch screens. A replay proves the code reproduces the
observed path on this build and snapshot, not that a precondition is the cause of an outcome.

### Score the drafts against an answer key

`robots-score` measures a draft against a hand-written answer key (`tap-robots-key/1`): the
screens, the methods with their destinations and preconditions, and the checks a tester would
write. Write the key from the app's source or spec, never from explorer output:

```bash
tap-explorer robots-score --run .tap/explorer/discovery-1 --key key.json \
  [--review robots/review.json] [--verification robots/verification.json] [--json]
```

It scores two readings, plus a third when you pass `--review`:

- **draft**: the draft with no human input;
- **answered**: after a reviewer who knows the key answers the real `--ask` questions. An
  answer counts only when the draft offered it, so this is the best one pass of review can do;
- **reviewed**: your own `review.json`.

It also counts the effort: the questions asked, and those with no right answer on offer.
Names never count. A robot is scored as the key screen most of its observations show
(the key's `recognize` rules), and a method by its screen, verb, control and destination.
Preconditions are *right*, *offered* (a question offers the right one), *wrong* or *missing*.
`verify()` landmarks must hold on every observation of the screen and on no other. The keys
and measured results are in `clients/explorer/benchmarks/` (`keys/`, `README.md`).

Limits: one run is the only evidence, so a condition seen once is still only a hypothesis.
List rows become a `label` parameter only for list-row targets. Index-only selectors produce a
method that raises `NotImplementedError` for review. Error text and the hint state are read
from snapshots once the server reports them (`ScreenNode.error`, `showing_hint`); until then
error-only variants still merge, and no error check is drafted.

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
configured Tap daemon. The sample and historical viewer do not call an AI provider;
only an explicitly reviewed discovery interpretation can do so.
