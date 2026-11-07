# Tap Studio follow-up

Audit baseline: merged PR #40 (counted/timed presses and private app data) and the then-open
PR #41 (agent SDK adapters and durable intent recordings), not yet merged when this was written. This is the explicit remainder of the
owner-approved Studio coverage work, not a promise that every agent command belongs in Studio.

## Larger recording-model work

- [ ] Multi-device sessions and per-step routing. Studio deliberately owns one worker/device at a
  time and its recording has one provenance device. Define roles, routing, release and replay
  behavior before replacing that model; merely retaining two attachments would produce ambiguous
  recordings.
- [ ] Persistent named recording library (start/stop/list/clean, offline exports and crash
  recovery). Studio currently owns an in-memory editable flow with manual export/open. Define
  private storage, process locking, secret retention, migration and archive ownership first.
- [ ] Agent archive import/conversion. `tap-agent-recording/1` is an intent/outcome archive, not
  Studio's replay contract. Reject automatic replay of uncertain/interrupted actions and require
  explicit device routing and supplied redacted values before designing an interchange adapter.
- [ ] Durable pre-input intents and failed/uncertain capture. Studio intentionally appends only
  successful composer steps; it is not an execution journal. Keep that difference explicit rather
  than claiming process-crash safety or automatically retrying a mutation.

## Operational tooling deferred from this change

- [ ] Owned video/audio capture controls and private retained-output recovery in Studio. The
  existing host recorder can acknowledge start before frames exist and has shutdown-budget
  limits; its API 34 empty-output validation failure was explicitly deferred by the owner.
  Exposing capture must not hide this with mutation retries, implicit lifecycle or invented
  readiness guarantees. Passive shared video remains tap-watcher's surface.
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
- [ ] Broader value editing for existing step kinds (for example app extras, condition values and
  count assertions). This change adds editors for the new press parameters, budgets and stability;
  it is not a complete form generator for every older step field.

## Explicitly excluded

- Synchronization / `awaitIdle` remains work in progress and is not changed here.
- No root, shell, SQL execution, persistent node handles, coordinate actions, automatic private
  data restore, implicit stop/relaunch, or replay of an uncertain transmitted mutation.

## Verification still needed on real devices

- [ ] Exercise the Studio changes on the approved API 34/API 29 fixture matrix when the owner
  requests device validation. No reboot; API 29 was disconnected during the preceding agent work.
