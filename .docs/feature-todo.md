# Feature TODO

Ideas recorded during the device-actions/private-data work. These are future work, not
requirements for the current API improvements. Do not expand the current implementation to
include them without approval.

## Recommended order

1. Private-data recovery tooling.
2. Scroll-end feedback.
3. SQLite bundle manifests.
4. WebView accessibility boundaries.
5. Emulator biometric simulation.

## Backlog

- [ ] **Agent event inbox beyond toasts.** Add `tap-agent events` / an MCP `events` tool only
  when it reports more than toast history. Today `toast --history` covers retained toast reads;
  snapshots and settle responses automatically deliver unread toasts. Candidate observations:
  - Toasts, reusing the current accessibility listener and bounded history.
  - Window/panel appearance and dismissal: dialogs, permission prompts, menus, drawers,
    notification shade and keyboard visibility. Report observed window transitions, not an
    assumed semantic outcome; identify windows without retaining live accessibility handles.
  - Foreground package/activity changes, including app switches and returns from system UI.
    State what accessibility events prove versus what needs host-side activity inspection.
  - Accessibility announcements, live-region updates, and exposed snackbar/error/banner text.
    Custom-drawn or inaccessible transient UI may require separate visual observation; do not
    promise complete coverage or infer a snackbar from every content-change event.
  - Notification posts, updates and removals through the existing notification-listener path;
    permission, package scope and sensitive-content handling must be explicit.
  - App crashes/ANRs, reusing the existing host death record rather than a second detector.
  - Relevant device transitions such as rotation, display on/off, keyguard and connection loss,
    with a clear source and lifecycle for each event type.
  Design before implementation: ordered stream identities/cursors, timestamps and source,
  bounded retention with explicit gaps/resets, shared CLI/MCP acknowledgement, package/type
  filters, and coalescing/deduplication for noisy accessibility content changes. Separate
  observed facts from inferred meaning; keep untrusted app text as data and apply privacy
  limits. Collection must continue while the agent thinks without hierarchy polling, changing
  snapshot refs, keeping idle sessions alive or replaying mutations. Define any optional
  screenshot/video evidence separately, with cost and coverage limits. Exercise retention,
  filtering, ordering, runtime reuse, loss/recovery and representative transient UI on the
  device matrix before claiming support. This is future work, not scope for the toast feature.

- [ ] **Private-data recovery tooling.** Add a read-only status API for interrupted database
  imports, then explicit restore/discard commands. Preserve retained recovery data; never
  silently repair, discard, restart, clear or replay a mutation. Today repair is manual or
  requires an explicit clear. Test interrupted replacement and failed recovery paths.
- [ ] **SQLite bundle manifests.** Include a format version, checksums and appropriately
  privacy-scoped source metadata with exported directories. Detect missing sidecars, corrupt
  files and accidentally mixed bundles before import. Preserve database/WAL/journal together;
  exclude SHM. No SQL execution or decryption.
- [ ] **Scroll-end feedback.** Expose whether content can move further after a fling, so
  scroll-until loops can terminate with useful diagnostics. Define what the feedback proves
  and exercise it on both device families rather than inferring app-specific behavior.
- [ ] **WebView accessibility boundaries.** Document and test accessibility-visible HTML
  controls and provide clear limitations for inaccessible content. Keep the accessibility-only
  boundary: no WebDriver surface, XPath hot path or persistent element handles. Follow the
  existing decision records; seek approval for any departure.
- [ ] **Biometric simulation.** Support emulator fingerprint success/failure for authentication
  tests, with capability detection and explicit unsupported-device errors. Do not imply
  physical-device biometric injection or bypass Android security.
- [ ] **Recover a lost command response.** Today a connection lost after a mutation was sent
  fails the command `INDETERMINATE` (the session goes `BROKEN`, and the next attach recovers it;
  it does not quarantine). The driver could keep each request's terminal response so that,
  after reconnecting under the same session, the host asks for the outcome of request N instead
  of reporting `INDETERMINATE`: a brief USB drop would then return the real result. Never a
  replay: the request is not run again, only its recorded response is read. Open questions:
  the driver usually dies with the connection (heartbeat self-kill, cleanup force-stop), so
  outcomes must survive a driver restart (stored on the device, bound to session id and
  generation); a mutation still running past the watchdog (the late-mutation quarantine) has no
  outcome to return; it needs a protocol capability and the owner's approval
  (`protocol-contract.md` "connection loss never causes automatic mutation replay" still holds).
  **Deferred (2026-11-09): the cost is high and the gain is small.** No `INDETERMINATE` has
  shown up in the local or CI device runs, and the mildest case is the only one it helps:
  a drop that kills the driver or lasts past the heartbeat timeout stays `INDETERMINATE`
  either way. A response cache alone would never be read: `TapDriverServer.serve()` serves
  exactly one authenticated connection and returns when it ends, so the instrumentation exits
  on any drop. The minimum design found: the driver keeps listening for the heartbeat window
  (30 s), keeps the terminal responses of its last few requests in memory (bound to session,
  generation and instance id; no on-device store, since a restarted driver has nothing in
  flight), a resume handshake re-authenticates as the same instance, and the host re-creates
  the forward and asks for request N's outcome (the recorded response, still running (wait up
  to the command's deadline) or unknown → `INDETERMINATE`). It narrows the
  "transport loss after acceptance is `INDETERMINATE`" invariant to "unless the same driver
  instance confirms the outcome", so it needs a decision record. Revisit if `INDETERMINATE`
  failures appear in CI or real use.

## References

- [Private app data](app-data.md)
- [Device actions](device-actions.md)
- [Framework gaps](framework-gaps.md)
- [ ] **Log parser pipeline.** The device log (`app-logs.md`) is lines of text; a test that
  wants "the HTTP calls the app made during this step" has to grep them. Add a pipeline of
  parsers over log entries that turn a library's own log format into typed records, starting
  with HTTP exchanges (method, URL, status, duration, optionally headers and body) from
  OkHttp's `HttpLoggingInterceptor` lines, read back as e.g. `device.logs.http(since=…)`.
  Deliberately left out of the first cut (2026-11-14): it works only for apps that log through
  a library Tap has a parser for and at a verbose enough level, so it must be opt-in per
  parser, say what it could not parse, and never look like a network capture (it sees what
  the app chose to log, after redaction). Open questions: where parsers run (daemon, so every
  client gets them, or client-side plug-ins), how an app's own format is registered, and how
  multi-line records interleaved across threads are grouped (tid + tag).
