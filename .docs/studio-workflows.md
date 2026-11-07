# Studio authoring and reusable flows

Owner-approved follow-up to the Studio audit on PR #42: implement the recommended scope;
keep the larger remainder in `studio-todo.md`. This supersedes the original recorder's
JSON-only output and manual-file-only decisions, not its selector, SDK or no-retry invariants.

## Scope and implementation order

1. Server-owned operation lifetime: one mutation at a time, retained through request
   cancellation until accepted work settles. Recording replacement, attachment, release and
   shutdown must not race completion. Secret value changes invalidate affected outcomes.
2. Selector-first authoring, complete typed step-field editing, duplicate and recording-only
   undo/redo. Draft-loss protection, destructive-action confirmation and accessible dialogs/
   target picking. Correct format labels.
3. One controlling browser tab with explicit control transfer; observers cannot mutate stale
   shared state. Control transfer cannot interrupt an operation. No device-command retries.
4. Explicit non-secret parameters/test data and separately supplied secrets. Parameter
   resolution is validated before input and never turns selectors into coordinates or SQL.
5. Private named recording storage, rename/list/delete and offline access; opt-in draft
   autosave, bounded documents, process locking and atomic transactions. Secret values stay
   memory-only. Draft recovery is not an execution-intent journal and cannot prove whether
   input happened before a crash.
6. Headless replay and Python/pytest export using public SDK adapters and the same validation,
   explicit daemon startup and no-retry policy. Unsupported export fails rather than losing
   semantics. No raw Execute escape hatch.
7. Bounded, privacy-aware per-run results and explicit diagnostic evidence. Preserve run and
   step identity, not a promise of automatic screenshots or durable accepted-command recovery.

All these changes remain in the existing Studio PR with tests and public documentation in
its implementation commits. Device validation is not authorized by this task.

## Boundaries

Single-device ownership remains. Multi-device roles, agent-intent archive conversion,
large streamed browser transfers, owned media controls/readiness and other unresolved
platform work stay in `studio-todo.md`. No synchronization work, automatic app restoration,
implicit lifecycle or replay of uncertain input.

## Status

All seven are implemented. What the implementation deliberately stops short of:

- With no device, the page can open, read and export a recording. Editing and replay still need one.
- The library's locks and permissions are POSIX only (`studio-todo.md`).
- Parameters stand for typed and expected text only (`studio-todo.md`).
- Run results are in memory and cover finished steps; they are not an execution journal.

## Verification

Baseline before this follow-up: 318 backend and 118 browser tests passed. Service-level probes
separately reproduced recording replacement/release during Perform, cancellation unlocking a live
worker, and retained success after a secret value change; each now has a regression test. Automated
checks run on the in-process fake daemon.

Device run: the page, `tap-studio replay` and the exported pytest test on emulator-5554 (API 34, no
reboot) with the daemon CI built from the same commit. It covered recording, a selector-only wait and
absent checks, editing, undo, redo, duplicate, parameters, secret typing, two tabs, Stop, a failing
run, per-run results, on-request evidence, Clear data, the library and the headless exit codes. A
marked secret stayed out of the daemon log, journals, raw event history, library file, export, run
results and pytest artifacts. It was not run on API 29.

That run found what the fake daemon could not: a stopped replay reported its count while the accepted
step went on to fail; open sections left the step list no height; the device list stayed stale after
release; an empty "contains" wait could be recorded; a reload flashed the other-tab banner. Each is
fixed with a page test, except the step list's height, which was checked in a browser.

Known limit: a hierarchy captured on request holds the text on screen, so a secret typed into a field
Android does not mask is in that file. The screenshot can show it too.
