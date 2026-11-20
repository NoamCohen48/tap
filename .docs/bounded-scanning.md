# Bounded collection scanning

Status: implemented; local verification recorded below. Device validation is not requested.

## Problem and contract

Virtualized lists expose only currently materialized accessibility nodes. Finding one target
with `scrollUntil` is different from collecting observations across viewports. Add an explicit,
bounded client loop that returns detached values and explains its stopping condition, never
claiming universal completeness. Start at the current position; never rewind automatically.

## Design

- Build on the collection reads now on main: `Snapshots` is already bounded at 1000 matches /
  1 MiB and fails rather than truncates. Preserve that contract instead of introducing a second
  truncated batch primitive. Add Kotlin collection-read parity and container-relative reads.
- A scroll returns independent movement (`MOVED`, `UNCHANGED`, `UNKNOWN`) and edge
  (`REACHED`, `NOT_REACHED`, `UNKNOWN`) evidence. Add optional response metadata alongside
  the existing `done` outcome so older drivers/consumers remain usable; absent evidence means
  unknown. Existing input mechanics, exactly-one resolution and mutation gates stay unchanged.
- Observe only scroll events whose source equals the command-local resolved container. No
  source/node identity survives the command. Missing, ambiguous or unusable evidence is unknown.
  UiAutomator 2.4.0's scroll Boolean is not an end signal: false also means no event; its
  `Until.scrollFinished` is not source-filtered. Checked the published
  [2.4.0 source archive](https://dl.google.com/dl/android/maven2/androidx/test/uiautomator/uiautomator/2.4.0/uiautomator-2.4.0-sources.jar).
  Use usable directional offset/max pairs only for edges: item indices alone do not establish
  a physical boundary (reverse layouts and partially visible final rows). This deliberately
  tightens the proposed indices fallback to honor the approved reliability requirement.
  Zero max ranges stay unknown. Android 9's unset delta (-1) is also unknown, even when a
  genuine one-pixel movement is indistinguishable from it.
- SDK `container.scan(items, key, direction, maxScrolls, maxItems, timeout,
  distancePercent=60, noProgressLimit=2)` requires explicit finite budgets. Items are descendants
  of one uniquely matching container; picked selectors are rejected, never broadened globally.
- Read initially, then scroll once and read again. Always merge the final viewport before an
  edge result. One monotonic deadline is passed through to every read/scroll, with each command
  capped at the protocol's 120-second maximum. No mutation replay. An item cap takes precedence
  over edge evidence because the final viewport may not have been retained in full.
- Keys are explicit, non-null, stable caller identities. Repeated keys across reads merge in
  first-seen order, preserving first/latest snapshot and read count. Same-read collisions fail.
  Distinct rows sharing a key across reads cannot universally be detected: document this.
- Compare ordered keys and visible bounds to detect observable viewport progress. New-key
  count alone is not movement. Ignore volatile state/text in the fingerprint. Consecutive
  unchanged fingerprints with no reliable movement stop at `NO_PROGRESS`; this is not an edge.
- Results expose observations, stop reason (`EDGE_DETECTED`, `SCROLL_LIMIT`, `ITEM_LIMIT`,
  `TIME_LIMIT`, `NO_PROGRESS`), scroll/read counts, elapsed time, latest scroll and warnings.
  Scroll counts include attempted commands (also failed/indeterminate attempts in partial
  evidence); read counts include successful collection batches, not container pre-checks.
  Coverage is always `NOT_GUARANTEED`: starting midway, virtualization, gaps, live changes,
  inaccessible rows and bad keys can all miss data even at a detected edge.
- Command/key failures remain failures with partial observations attached, not successful
  termination. Cancellation remains cancellation. Deadline exhaustion between steps returns
  `TIME_LIMIT`; failed commands retain their original failure semantics.
- No implicit app readiness, sync changes, loading waits, background scrolls or whole-tree dumps.
- Scans are SDK-only orchestration: the agent coverage registry explicitly classifies the
  caller-defined key callback as client-only, not arbitrary code executed through CLI/MCP.
  Studio replay ignores optional gesture return values; Studio and Watcher generated TypeScript
  contracts include the additive metadata. No recorded step or new agent tool is introduced.

## Work plan

1. Add scroll evidence protocol metadata, source-filtered collection and regression tests;
   regenerate stubs and update the protocol/server records.
2. Add Kotlin collection reads and uniquely scoped container collection reads in both SDKs.
3. Implement equivalent bounded scans with detached result models, explicit identity,
   fingerprints, budgets and partial-failure evidence.
4. Exercise overlap, last viewport, edge/unknown evidence, key collisions, changed snapshots,
   moving old keys, all limits, deadline propagation and command failures in unit suites.
5. Document public APIs and limitations; run relevant JVM/Python checks and compile driver.
   Device matrix: not run locally unless requested. Rebase and publish one PR; merge only
   with explicit authorization after every check passes on the up-to-date branch.

## Verification

- JVM: protocol 75 tests, Kotlin SDK 122, daemon 181, driver core 83, command engine 34;
  all passed. Driver core Android lint and driver compilation passed. The new protobuf message
  is registered in native-image reflection metadata; native compilation was not run.
- Python: 297 unit tests passed in an isolated worktree venv. Earlier subprocess import failures
  were caused by a relative PYTHONPATH resolving to the old checkout; absolute paths / the
  isolated editable installation fixed the test environment, without product changes.
- Studio: 616 back-end tests and 183 page tests passed; TypeScript/Vite production build passed
  (existing large-chunk advisory). Agent: 311 tests passed, 4 device-dependent tests skipped;
  the API-coverage check explicitly classifies scan as a callback-based SDK helper.
- Watcher: 27 back-end tests and 39 page tests passed; TypeScript/Vite production build passed.
- All three committed stub generators (Python client, Studio, Watcher) checked current;
  buf lint and breaking checks passed.
- LSP probes run on changed paths. Kotlin's server is inconclusive (Gradle is authoritative).
  Studio's LSP cannot resolve its sibling editable tap-e2e package. A separate Pyright check
  with explicit sibling source paths and Python 3.10 targeting reports zero errors/warnings for
  scanning.py, element.py and Studio steps.py. An unrelated existing logs.py type-check finding
  was deferred, not changed. No claim of a universally clean workspace is made.
- Device matrix: not run locally. No device completeness or source-event delivery claim is made.
