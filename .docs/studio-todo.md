# Tap Studio follow-up

Audit baseline: merged PR #40 (counted/timed presses and private app data) and the then-open
PR #41 (agent SDK adapters and durable intent recordings), not yet merged when this was written. This is the explicit remainder of the
owner-approved Studio coverage work, not a promise that every agent command belongs in Studio.

## Approved workflow follow-up (in progress, not deferred)

The owner subsequently approved the audit's recommended improvements. The execution gate,
selector-first composer, complete field editing, recording-only undo/redo/duplicate, browser
control, parameters, private named library, headless replay/Python export and per-run diagnostics
are tracked in [studio-workflows.md](studio-workflows.md). Do not treat this as evidence that
unfinished features are implemented. The remaining sections below are the explicitly deferred
work, including broader follow-ups beyond that approved scope.

## Larger recording-model work

- [ ] Multi-device sessions and per-step routing. Studio deliberately owns one worker/device at a
  time and its recording has one provenance device. Define roles, routing, release and replay
  behavior before replacing that model; merely retaining two attachments would produce ambiguous
  recordings.
- [ ] Agent archive import/conversion. `tap-agent-recording/1` is an intent/outcome archive, not
  Studio's replay contract. Reject automatic replay of uncertain/interrupted actions and require
  explicit device routing and supplied redacted values before designing an interchange adapter.
- [ ] Durable pre-input intents and failed/uncertain capture. Studio intentionally appends only
  successful composer steps; it is not an execution journal. Keep that difference explicit rather
  than claiming process-crash safety or automatically retrying a mutation.

## Operational tooling deferred from this change

- [ ] Streamed browser transfers larger than 1 MiB (private files, whole database bundles and
  diagnostics). The new unary tools deliberately cap bytes and refuse oversized results rather
  than silently truncating or exposing arbitrary host paths. The SDK still supports its existing
  larger/streamed limits. Design bounded upload sessions, download recovery and cancellation
  before increasing browser transport/memory limits.
- [ ] Tools before attachment/after release. The new toolkit is scoped to Studio's attached
  device/connection, including its metadata/history downloads. Supporting offline archives or
  discovery-only metadata in the device picker needs its own UI and ownership semantics.
- [ ] Owned video/audio capture controls and private retained-output recovery in Studio. The
  existing host recorder can acknowledge start before frames exist and has shutdown-budget
  limits; its API 34 empty-output validation failure was explicitly deferred by the owner.
  Exposing capture must not hide this with mutation retries, implicit lifecycle or invented
  readiness guarantees. Passive shared video remains tap-watcher's surface.
- [ ] Paired diagnostic capture bundles and standalone structured screen-snapshot/candidate JSON
  downloads. The inspector already displays paired frames/candidates; the new tools export
  screenshot, hierarchy, logs, metadata and raw history individually, not an agent-style capture
  bundle. Define artifact packaging, bounds and retention rather than claiming these are the same.
- [ ] Shared-storage push/pull/add-media and APK install/uninstall tools. They need an explicit
  browser upload/download and bounded-artifact design, not recorded arbitrary host paths.
- [ ] Parameterized secrets for clipboard and other non-text-input step data. Current named
  secrets apply to Set text and Type keys only; selectors, wait text, notes, extras and clipboard
  values remain ordinary recording data. Redaction must not silently make a replay unusable.

## SDK and editor follow-up

- [ ] Tighten host-poll/search deadline propagation in the shared SDKs. Studio forwards the SDK
  phase budget, but Python `scroll_until` checks it between calls; its existence queries and
  scroll commands retain their own default command budget. Do not describe the phase option as
  a hard end-to-end deadline or silently invent retries. Fixing the shared SDKs requires their
  own Kotlin/Python regressions; the Studio adapter tests explicitly verify forwarding.
- [ ] A complete daemon-RPC/SDK/parameter coverage registry. The new regression guard classifies
  all 42 Execute operations and affected adapters test value forwarding, but it does not claim
  agent-style parity for every daemon RPC, SDK method or Kotlin convenience API.

- [ ] Parameters in selectors, app calls and clipboard values. They stand for typed or expected text
  only, so a selector cannot be changed by data; extending this needs rules for what stays validated
  before input.

## Additional audit follow-ups outside the approved milestone

- [ ] Kotlin/JUnit source export and a cross-language export conformance matrix. Python/pytest
  export is in scope; do not silently promise another generated language or omit unsupported
  semantics.
- [ ] Reusable subflows, branching and loops. Explicit parameters/test cases do not make Studio
  a general workflow language. Design composition, failure and secret boundaries first.
- [ ] Performance profiling on large hierarchies and long flows, followed by measured tree/step
  virtualization if needed. No claim that a particular node count is performant without evidence.
- [ ] Durable, automatically collected failure-evidence/intent archives. Bounded per-run results
  and explicit diagnostic downloads are in scope; recovering accepted input after a process crash,
  storing screenshots/logs by default and retention policies need additional privacy/design work.
- [ ] Cross-platform private-store locking and permission verification beyond supported POSIX
  behavior. Do not infer Windows guarantees from a successful Linux run.

## Explicitly excluded

- Synchronization / `awaitIdle` remains work in progress and is not changed here.
- No root, shell, SQL execution, persistent node handles, coordinate actions, automatic private
  data restore, implicit stop/relaunch, or replay of an uncertain transmitted mutation.

## Verification still needed on real devices

- [ ] Exercise the Studio changes on the approved API 34/API 29 fixture matrix when the owner
  requests device validation. No reboot; API 29 was disconnected during the preceding agent work.
