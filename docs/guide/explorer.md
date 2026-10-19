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
and its direct children, so a status-bar notification icon is not a new state. Window decor in
the app's own windows is left out too: the text cursor's handles, which fade a few seconds after
typing, and the system bar backgrounds, drawn or not with the keyboard (`tap-state-signature/3`;
robot drafts group screens without them as well). Capabilities propose tap/fill candidates, initially blocked. Inspect
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
it is the same app. The robot drafts have been scored on one held-out app (Fossify Notes, keyed
from its source before the run): see the benchmark README.
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
  so the variants of a page (empty or filled form, a checked box) share one robot. Window decor
  (the text cursor's handles, the system bar backgrounds) is not part of a screen, and the pages
  a recorded scroll moved between are one screen even when they share few texts (a long list of
  contributors), as long as they are built from the same views. A screen before and after a
  fill, or a tap on a checkbox or radio button, that left that control on screen is one screen
  too: the input edited it in place.
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
- **Expectations cover only what varied on the screen**, and never what a scroll changed (a
  header scrolled out of view is the scroll position, not a condition).
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
A key may cover only part of an app: a method that starts or ends on a screen the key does not
describe is listed as *outside* the key, and one whose verb the key does not list as *unkeyed*;
neither counts against precision.
Preconditions are *right*, *offered* (a question offers the right one), *wrong* or *missing*.
`verify()` landmarks must hold on every observation of the screen and on no other. The keys
and measured results are in `clients/explorer/benchmarks/` (`keys/`, `README.md`).

Limits: one run is the only evidence, so a condition seen once is still only a hypothesis.
List rows become a `label` parameter only for list-row targets. Index-only selectors produce a
method that raises `NotImplementedError` for review. Error text and the hint state are read
from snapshots once the server reports them (`ScreenNode.error`, `showing_hint`); until then
error-only variants still merge, and no error check is drafted.

`--baseline earlier.json` (an earlier `robots-score --json` report for the same package) turns
the score into a gate: every headline number that got worse is printed as `regression: …` on
standard error and the command exits 1. Lower is better for splits, mixed robots and wrong,
missing or spurious preconditions; higher is better for the rest.

## Explore unattended

`tap-explorer auto` explores an app on a disposable emulator with no one approving actions. You
approve a **policy** once, before the run; in the morning you read one report and answer one
batch of review questions.

```bash
tap-explorer auto --serial emulator-5560 --package com.example.app --apk app.apk \
  --out .tap/explorer/night-1 [--policy policy.json] [--reset clear-data | --reset snapshot:NAME] \
  [--max-actions 300] [--max-minutes 60] [--max-resets 50] [--activity .MainActivity] [--physical]
```

It needs the live extra and a running server, and refuses any serial that is not `emulator-`:
every reset wipes the app's data (`clear-data`, the default) or the whole emulator
(`snapshot:NAME`, which detaches, loads the snapshot, reattaches and reinstalls the APK so an
old build in the snapshot is never explored). `--physical` allows a phone: resets are
`clear-data` only, and the run refuses a package that was already installed before it, so it
never wipes data a person kept there (uninstall it first). `--resume` on a phone needs the
earlier run to have installed the app. The run installs the APK, explores, writes
`graph.json`, drafts robots, replays every draft method (`robots/verification.json`; skip with
`--no-verify`), redrafts with the replay results and writes `REPORT.md` / `report.json`
(`tap-explorer-report/1`). `events.json` keeps the server's event log. While it runs, actions
the policy holds back and branches it gives up on are printed to standard error.

**What it can do.** Every control the snapshot marks: tap, long press (a long-clickable node,
or a row whose text sits inside one; never a text field, where it only opens the system's text
selection), fill a text field, scroll a scrollable container forward
(down, or right for pagers and horizontal scroll views; a scroll that reveals new controls is
repeated from where it landed), the field's keyboard action (Search, Go, Done: `ime_action()`,
API 30 and later), and the system Back key. A long press, a scroll and Back reach branches a
tap cannot: a context menu, a button below the fold, a "discard changes?" dialog.

**What it tries, nearest first.** Each candidate gets a tier; the lowest tier wins, then the
shortest observed route to its screen:

1. a control never tried on its screen (a fill counts once per value);
2. a tried tap, keyboard action or Back in a new *context* on its screen: inputs empty, filled
   or holding the run's malformed value, checkboxes, lists empty or not, and which of the run's
   own typed values the screen shows. Carrying a second value through the screens that show it
   is what lets a check on that text be drafted. Each control is retried in at most
   `max_contexts` new contexts, the one adding the most facts it was never tried with first (the
   form with its name filled in, or with everything filled in, before one more combination of an
   empty name), then the nearest. *Deciding experiments* come before all of these and are not
   capped: when a control's outcome depended on state and several inputs could each be the
   cause (Save succeeded once, with every field filled), the run empties (or fills) one of them
   at a time and tries the control again, so the draft names the fields Save needs instead of
   asking;
3. a control whose outcome already depended on state, tried from more states (at most
   `max_experiments`), so the drafts' preconditions are settled by evidence rather than asked;
4. everything else, only when the policy is `exhaustive`.

A text field is filled with `set_text`, and tapped once per screen: that shows what tapping it
opens (a date picker, a dropdown) and gives it focus, which is when Android offers its keyboard
action. The keyboard action is tried where it is offered; when navigation lost the focus, the
recorded field tap is repeated first. On a device below API 30 the first keyboard action is
refused before input, and the run stops offering it (the report says so). Fill values come from
what the field is for: empty, two different valid values (names, e-mail addresses, phone numbers,
URLs or numbers) and, for a field with a format, one malformed value (`ada.example.com`, `12`,
`example dot com`, `0`) to reach its validation, unless the policy sets them.

**Resets and replay.** At a dead end (nothing left to try is reachable from the current screen),
the run first presses that screen's untried Back: it often leads back into the app without a
reset. The report counts these as escapes. After a reset the run routes over single-outcome
edges it observed; a route crosses a keyboard action only when a recorded tap on its field kept
the screen, because the focus that offers the action is not part of a screen's identity. When
none reaches the next candidate, it replays the shortest recorded run of successful inputs from
the start of an episode to that screen, each step checked as a new trial; a replay that diverges
is not tried again for that screen. A route or replay that lands on another screen than it
observed before (hidden state: a dialog that returns to whichever form opened it, a color
picker showing the color picked earlier) does not cost a reset: the input succeeded and its
result was observed, so the run carries on from there and stops routing over that edge. The
report counts these. The workbench, where a person approved the route, still stops.

**Episodes.** A reset starts a new episode in the same graph: when the run starts or resumes,
when an outcome is uncertain, when a success left no verified
destination, and when the next candidate cannot be reached from where the app is. An action that
failed before input twice, or that left the app, is parked and reported. A Back that left the
app is not retried in the screen's other contexts (in a single-activity app it closes the app
from every screen); a tap that left is, because that can depend on state (a Save that asks for
the notification permission only when a reminder is set). An uncertain action is
never replayed; the reset is what lets the run continue past it. The run stops when nothing is
left to try (`frontier_resolved`), nothing left is reachable (`unreachable_frontier`), or at a
budget (`action_budget`, `time_budget`, `reset_budget`), or when three resets in a row failed
or never showed the app (`reset_failed`). The report then quotes the last reset error and names
the window the last screen showed, with its screenshot. Android's one-time "Viewing full screen" notice over an app that hides the system
bars is the usual one on a fresh emulator: `adb -s SERIAL shell settings put secure
immersive_mode_confirmations confirmed` keeps it away; the run never taps another package's
window. `--resume` continues a stopped run in
the same directory with the same emulator, package, APK and policy, and may raise the action
budget; an action whose outcome was never recorded becomes `indeterminate`, not a retry.

**The policy** is a `tap-explorer-policy/1` JSON file; unknown keys are refused:

```json
{
  "format": "tap-explorer-policy/1",
  "name": "night",
  "hold": ["delete", "pay", "send", "log out"],
  "allow": ["Delete filter"],
  "deny": ["settings_reset"],
  "values": null,
  "field_values": {"habit_name": ["", "Read", "Run"]},
  "focus_taps": false,
  "exhaustive": false,
  "max_contexts": 8,
  "max_experiments": 8
}
```

`hold` phrases are matched as whole words against a control's label and resource name; the
default list covers deleting, paying, sending, sharing, publishing, calling, rating, reporting,
signing out and closing an account. A policy's `hold` replaces that list, so keep the defaults
you still want. `allow` exempts labels or resource names from `hold`; `deny` names controls
never touched. Held actions are listed in the report with the states they were offered on.
`"values": null` keeps the generated values; a list replaces them for every field, and
`field_values` for one resource name. `"focus_taps": true` retries a field's tap in every context
rather than once per screen. `"exhaustive": false` skips tier 4: on the wizard fixture it settles
the same preconditions and checks in less than half the attempts.

What it does not do: log in with real credentials (a login screen is explored with the generated
values only, and password fields are never filled), solve a captcha, or grant permissions (a
permission dialog belongs to another package, so the action that opened it is parked as leaving
the app). It does not decide whether an outcome was correct either. The drafts are still
unreviewed hypotheses; the replay proves only that they reproduce what was observed.

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
