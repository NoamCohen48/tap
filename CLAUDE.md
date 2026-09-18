# Tap

Kotlin host-driven Android E2E framework. Phase 0 is complete; Phase 1 (contract and driver)
is in progress. See `README.md` for build/run commands.

## Documents

- `.docs/android-e2e-framework-implementation-plan.md` — normative design. Wins over every
  other doc on conflicts.
- `.docs/protocol-contract.md` — the *implemented* wire contract (protocol 1.0). Update it in
  the same change as any protocol edit.
- `.docs/project-architecture.md` — current module/file layout.
- `.docs/phase-1-progress.md` — Phase 1 checklist. Keep it honest: only tick items that are
  implemented *and* exercised by a test or the device validation flow.
- `.docs/upstream-reference-audit.md` — adopt/adapt/do-not-copy decisions per upstream tool.

## Learning from other testing tools

Whenever it is useful and possible, take inspiration from existing, proven testing tools
rather than inventing behavior from scratch. Primary references:

- **Appium UiAutomator2** (server + driver): Android/UiAutomator edge cases, input and
  key-event handling, permission dialogs, screenshots, accessibility-cache/staleness
  workarounds, server lifecycle.
- **Maestro**: host ergonomics, condition waits, selector usability, process supervision,
  artifact/report separation (event model → JUnit/HTML adapters), multi-device runner.
- **androidx.test.uiautomator**: the API we build on. Check the current AndroidX source
  (`UiDevice`, `ByMatcher`, `BySelector`, `UiObject2`) before assuming what a selector or
  gesture does, and prefer newer predicate/window-scoped APIs where they fit.

Rules when doing so:

- Use the pinned commits in `.docs/upstream-reference-audit.md`; never a moving branch.
- Follow the decision matrix there: some areas are ADOPT/ADAPT, others (XPath hot path,
  persistent element handles, WebDriver surface, YAML flows, retry-by-default) are
  deliberately DO NOT COPY.
- Adapt narrowly, behind a regression test for the affected API/device family.
- Any copied or substantially adapted code must be recorded in `THIRD_PARTY_NOTICES.md`
  with repo, commit, source/destination path, license, and modification summary.
  Independently written protocol/session/security code stays separate from borrowed code.

## Invariants that must not regress

- Driver is a separate package from the AUT; it survives AUT force-stop/clear-data.
- No hierarchy dump or XPath on the selector hot path; dumps are diagnostic only.
- No persistent `UiObject2`/node handles across commands; elements are lazy selectors.
- Mutations require exactly one match; `AMBIGUOUS`/`NOT_FOUND` are returned before input.
- Never replay a transmitted mutation; transport loss after acceptance is `INDETERMINATE`.
- Request IDs are strictly increasing per session generation; old generations are rejected.
- Every ADB call is serial-specific (`-s`); never `forward --remove-all`.

## Build notes

- JDK 17 is required (`JAVA_HOME`); the workstation default JDK is not compatible with AGP.
- The Phase 0 host flow (`host ... <serials> <apks>`) is destructive and reboots devices.
  Do not run it against shared devices without asking.
- `~/.tap/sessions` holds machine-wide device leases/journals; `.tap/` in the repo is ignored.
