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

## References

- [Private app data](app-data.md)
- [Device actions](device-actions.md)
- [Framework gaps](framework-gaps.md)
