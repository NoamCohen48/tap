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

## Verification

Baseline before this follow-up: 318 backend and 118 browser tests passed. Service-level
probes separately reproduced recording replacement/release during Perform, cancellation
unlocking a live worker, and retained success after a secret value change. Add regression
coverage before treating any of those findings as resolved.
