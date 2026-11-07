# App Explorer: open review findings

A review of PR #23 at `7258adf`, stopped part-way on 2026-11-07 because the PR is work in progress.
These findings are to be solved before the PR lands; none is fixed yet. "Verified" means the
reviewer read the code path; nothing was run on a device.

State at review: one commit, +19,155 lines over 95 files, 20 commits behind `origin/main` (merge
base `57202bc`, 2026-10-11). A trial merge shows no text conflicts.

## Blockers

- [ ] **The PR's own device check fails in CI** (verified). `.github/scripts/explorer-smoke.sh`, run
  from `.github/scripts/device-tests.sh:55`, fails on run 40828375149 with `tap-explorer sample:
  PilotStopped: snapshot changed around screenshot; no action on a drifting frame`. The simplest
  sample flow stops on a screen that keeps redrawing on the CI emulator; the PR's claims rest on
  runs on emulator-5560. Make `sample` tolerate late rendering as `auto` does (`captures=3`,
  `confirm_after`) or fix the sample app, then get a green device job.
- [ ] **The explorer runs ADB itself** (verified). `clients/explorer/tap_explorer/verify.py:142-161`
  (used by `autonomy.py:1090-1101`): `emulator_snapshot_reset` runs `adb -s <serial> emu avd
  snapshot load` and polls `getprop`. CLAUDE.md puts all ADB and device lifecycle in `:host:core`,
  reached through the server, and the PR body says the explorer has no ADB code. A snapshot load
  outside the server's per-serial journal lock can replace a device another connection holds, and
  the server never learns of it. Add a server RPC for a snapshot restore or reset, or drop
  snapshot resets from the client.

## High

- [ ] **Automatic exploration can run on a physical device** (verified). `autonomy.py:1110-1128`
  accepts `--physical`, though the body says emulator serials only. On a real phone it clears data
  and taps freely; a tap that opens share, dialer, browser or mail acts on the owner's device.
  Remove `--physical`, or have the owner decide on it, document it and test it.
- [ ] **The safety filter checks only a control's own label and id** (verified). `autonomy.py:60-62`
  (`HOLD_WORDS`), `214-227` (`verdict`), `discovery.py:177-180` (keyboard action).
  - The keyboard action on a focused field takes the field's label ("Message"), so a Send action
    is never held and `ime_action()` sends a real message.
  - Confirmations pass: "OK", "Yes", "Confirm", "Discard", "Clear", "Reset", "Archive",
    "Unsubscribe". "Clear all" opens a dialog whose "OK" is tapped.
  - Icon buttons with no content description are labelled from their class name and are not held.
  - The match is English only.

  Run the keyboard action only for kinds known to be safe (Search, Done); judge a dialog's buttons
  by the dialog's text and the control that opened it; hold unlabelled controls unless the policy
  allows them.

## Medium

- [ ] **A permission dialog can be answered with no recorded intent** (verified). `session.py:132-135`,
  `180-193`, `293`: `observe()` calls `choose_permission` (a device mutation) before any attempt
  starts, and `self.answered` is cleared at line 293 during the check before an input. Under
  `allow` this grants a permission outside the journal. Answer only after an attempt is recorded,
  or record the answer as its own attempt.
- [ ] **`--resume` cannot recover from a crash at one point** (verified). `session.py:107-130` opens
  the observation's files with `"xb"`/`"x"` before adding the observation to the store; a crash in
  between makes the resumed run reuse `obs-N` and raise `FileExistsError`, which
  `Autonomy._episode` does not catch. Add to the store first, or write under a temporary name and
  rename once committed.
- [ ] **"Verified" covers only the routes replay could plan** (verified). `verify.py:85-130`: a method
  is verified when its replay raises nothing; unplanned methods are "unplanned", and an
  outcome-dependent method replays one route only. Replay has no overall time limit and, in
  `unattended`, runs after `max_minutes` has run out. Give replay a whole-run budget and report
  what fraction of methods it covered.
- [ ] **One commit holds many changes** (verified). `7258adf` carries the explorer client, two sample
  APKs, CI changes, fake-daemon changes in `clients/python/tests/unit/conftest.py` (`ClearData`,
  `installed`), docs, a CHANGELOG entry and a root `pyrightconfig.json` that hard-codes `.venv`.
  The body refers to a "separate PR" and to "before the last two changes" (PR history). Split into
  commits that each build on their own (fake daemon, explorer core, workbench, benchmark data,
  samples, CI) and rewrite the body for a reader of `git log`.
- [ ] **About 4.5 MB of generated benchmark data is committed** (verified).
  `clients/explorer/benchmarks/corpus-v2.json` (2.76 MB) and `corpus-held-out-wizard.json`
  (1.73 MB), each one line; `build_corpus.py` regenerates the first. Build them in CI or keep them
  as release assets; if they stay in git, pretty-print them.

## Low

- [ ] **The benchmark is weak evidence for identity** (verified). "0 merges, 0 splits" is in-sample
  (thresholds tuned on the same corpus); the held-out set is the same wizard app; the Loop labels
  and answer key are provisional. Notes method precision went 0.346 → 0.45 and Catima is 0.351.
  Label the scores in-sample or provisional in the docs and body, not as proof.

## Not verified

- [ ] **Compatibility with current main.** Main has since added server API epoch checks (`52fa1ac`,
  `a4c8907`), changed how `first()`/`at(n)` count matches (`f1fe416`), and removed sensitive typed
  text from the event log (`8657318`). The explorer uses `Selector.to_proto().WhichOneof("pick")`,
  `set_text` and `event_log`. The explorer suite against main was not completed.

## Not reviewed

Most of `robots.py`; `score.py`, `similarity.py`, `labels.py`, `rules.py`, `ai.py`, `cli.py`,
`sample.py`; `store.py`'s transactions and interrupted-attempt recovery; the front end
(`web/src/App.tsx`, including how app-controlled text is rendered); both sample apps; the tests;
the docs. The uncommitted `robots-propose` work (`propose.py`) was committed after this review and
was not reviewed.

Reviewed and found sound: the `web.py` server's token, Host and Origin checks, loopback binding and
file-path checks; quoting in the code that writes `robots.py`.
