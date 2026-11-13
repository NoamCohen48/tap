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

- [ ] **Custom on-phone automation overlay.** Add an opt-in overlay to Tap’s driver that
  draws tap circles and swipe/gesture trails for injected test actions on the physical phone.
  Android’s native Show taps option only marks physical finger touches; it does not provide
  this. Make overlay permission and enable/disable controls explicit. Keep the overlay
  non-interactive and verify that it neither intercepts input nor affects touch reachability,
  occlusion checks or test behavior. Define cleanup and rotation behavior, and cover the
  supported device families with regression tests. Not implemented by the watcher’s current
  browser marks or native Android indicator controls; see [Test watcher](test-watcher.md).

## References

- [Private app data](app-data.md)
- [Device actions](device-actions.md)
- [Framework gaps](framework-gaps.md)
