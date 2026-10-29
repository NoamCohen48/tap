# App Explorer — open work

What remains after the discovery workbench, identity benchmark and verified robot drafts. Items
marked **decision** need the user before work starts. Status as of 2026-10-29.

## 1. Waiting on the user

- **Review the Loop answer key.** `clients/explorer/benchmarks/keys/loop.json` was written from
  Loop Habit Tracker's source by the analyst and is still provisional. The Loop scores in the
  benchmark README count only once the operator has reviewed it.
- **Merge #29** (Host core: stop waiting on a permission dialog left in the app's task). Loop's
  clear-data resets after its Save opened the notification permission dialog depend on it; the
  device runs below used a server built from that branch.
- **A phone run.** `tap-explorer auto --physical` is ready (clear-data resets only, refuses a
  package that was installed before the run); no phone was connected for the device runs.

## 2. Framework changes the explorer depends on

The first three are merged into `main`; #29 is open.

| PR | What it gives the explorer | Explorer side |
|---|---|---|
| #24 Snapshots: field hint, invalid and error state | `ScreenNode.showing_hint` / `content_invalid` / `error`, so validation variants separate | Done: `live.py` reads them with `getattr` and degrades on an older server |
| #25 Driver: stale node before input is retryable | `ERR_STALE_BEFORE_INPUT` instead of `INTERNAL` | Done: in `SAFE_FAILURES` (`failed_before_input`, a new approved trial may follow) |
| #26 Daemon: reinstall a driver whose installed APKs differ | A restored snapshot cannot bring back an old driver build | Done: `auto --reset snapshot:NAME` reinstalls only the app APK |
| #29 Host core: stop waiting on a permission dialog left in the app's task | A clear-data reset after a permission dialog no longer times out in the force-stop wait | Done: a reset `TapError` counts as a failed reset; three stop the run as `reset_failed` |

## 3. Overnight autonomy — implemented and run on emulator-5560

`tap-explorer auto` (`autonomy.py`) is built and tested against the fake daemon; the design is
recorded in `.docs/app-explorer.md` section 28 and the user guide's "Explore unattended". What
each blocker from the proposal became:

| Blocker | Now |
|---|---|
| Approvals | `tap-explorer-policy/1`: hold/allow/deny by whole-word label or resource, held actions reported with their states |
| Choosing what to try | Four tiers (novel control, new context, outcome-dependent experiment, exhaustive), nearest first |
| Fill values | Empty, two different valid values and one malformed value per field kind; per-field overrides |
| Halts | Episodes (clear-data or snapshot reset) in the same graph; `--resume`; uncertain inputs never replayed |
| Hidden state | A diverging route continues from where it landed (the edge stops being routed); history replay to a frontier no single-outcome route reaches |
| Late-rendering screens | Observation retries until the snapshot is stable |
| Login, captcha | Parked (they leave the app or need values the policy does not have) and reported |
| Permission dialogs | Answered with the policy's `permissions` (deny by default) and recorded on the attempt |
| Input kinds | Tap, long press, fill, forward scroll, the keyboard action (focused field, API 30+) and Back |

Device runs on emulator-5560 (results in `.docs/app-explorer.md` section 28 and the benchmark
README): the wizard scores as on the fake daemon; on Loop every keyed method is observed and
both forms' Save preconditions are right (4/4, from deciding experiments), after ten runs that
each fixed what the previous one exposed.

Still open:

- **Budget on a real app.** Loop still runs to its 1400-action budget (21 resets); tier 1
  (untried controls on About, Settings and the pickers) comes first.
- **Held-out apps.** Fossify Notes and Catima were each keyed from source and frozen before
  their run (`.docs/app-explorer.md` section 28). What they drove (in-place joining, optional
  sections, typed echoes, history facts, permission answering, fields carried across tabs) is now
  in-sample for them, so a third app is the test. Catima left open: the card form's tab robots
  are not joined, Settings and pickers outside the key take much of the budget, and the run
  changed the app language. The scheduler could also aim deciding experiments at history facts.
  The phone run (`--physical`) is still to do.
- **A model as the rule proposer.** `robots --rules-out` / `--rules` (`tap-rule-questions/1`,
  `tap-rule-candidates/1`) let anyone propose preconditions, checked against the run; no model
  is wired in. **Decision:** which provider, whether it may read the app's source, and what
  evidence may leave the machine. Then: the scheduler could aim experiments where proposed
  rules disagree, and the lone-exception retry needs its first device run.
- **AI-suggested names** for the morning question batch (the "comfort" step of the proposal).
- **Notifications** beyond standard error (a branch that needs a person is reported in the
  morning, not pushed).
- **Swipe gestures** (swipe-to-dismiss rows, pull to refresh) and sliders are not proposed.
- **Hidden state that never shows** (a value kept after its screen is left) still costs
  conflicts and resets; the run parks what it cannot route to rather than guessing.

## 4. Evidence still missing

- **Held-out accuracy.** Two held-out apps, one run each, analyst keys, scored before anything
  was tuned on them: Fossify Notes 2 / 5 preconditions, method precision 0.346, checks 0.5 / 0.5;
  Catima 2 / 6, 0.351, 0.75 / 0.333 (all 14 key screens reached; its key misses one balance
  rule). Two apps and one run each are not an accuracy figure; the keys are unreviewed.
- **Real AI provider.** `ai.py` (pinned small model) is tested with fakes only; no reviewed
  real-provider run, and the provider, privacy and cost policy is undecided.
- **Human-reviewed identity labels.** The identity benchmark's Loop labels are analyst
  labels; there is no independent held-out set.

## 5. Operational notes

- The `robots-ready` snapshot on the Tap_Explorer AVD only loads into a windowed boot; a
  headless boot (`-no-window`) fails with "different renderer configured" and cold-boots.
- Stop every session before `adb emu avd snapshot load`; loading while a session closes
  quarantines the device (`SESSION_CLEANUP_UNCERTAIN`).
- The CI "Device tests (API 34 emulator)" job is currently flaky for every PR (auto-rotate
  restore does not take effect, the device is quarantined). It is not a required check and not
  caused by the explorer.
