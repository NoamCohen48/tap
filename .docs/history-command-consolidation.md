# Unify history reading, controls and diagnostic artifacts

Status: implemented and locally verified; delivery is through existing PR #73. This continues the existing
history/retention PR, not a separate capture system or a second PR. No device validation is
authorized by this request.

## Goal and boundaries

There is one user-facing activity reader: `tap-agent history`. It is global across retained
connections/devices in the selected daemon state directory, with agent intent from the shared
`TAP_AGENT_DIR`. Preserve device/connection/attachment identity, capture gaps, redaction,
uncertainty, full incident windows and independent crashes/ANRs. Never replay input or infer
results. No host module may depend on a client module.

The three concepts remain:

- **history**: read/filter activity, add bookmarks, configure attachment redaction;
- **record**: protect/unprotect named ranges of the same evidence;
- **capture**: explicitly collect a current-state artifact bundle.

## Approved command shape

```text
tap-agent history [read] [existing time/step bounds] [--device SERIAL ...]
                  [--logs] [--package PACKAGE ...] [--priority LEVEL]
                  [--tag TAG ...] [--grep REGEX] [--no-actions] [--driver-lines]
                  [--since-marker NAME] [--until-marker NAME]
                  [--step CURSOR|STEP_ID] [--last-action] [--tail N]
                  [--after ARCHIVE_ID:CURSOR] [--limit N] [--details] [--json]
tap-agent history mark NAME [--device SERIAL ...]
tap-agent history redact REGEX [--device SERIAL ...]
tap-agent record list/start/stop/export/clean
tap-agent capture [--device SERIAL] [-o NEW_DIRECTORY]
tap logs driver --serial SERIAL
```

- Remove the agent `log` verb, MCP tool and public `Agent.log` adapter, including driver and
  evidence variants. Do not retain a hidden second reader or obsolete output-file flags.
- CLI, MCP and the in-process Agent API share the history implementation and SDK renderer.
- History reads merge devices by default. Repeated device filters select a set and apply to
  intent, calls, deaths, logs and artifact references before pagination/selection.
- Log-specific filters imply log inclusion, and only filter log lines; they never erase
  independent crashes/ANRs or silently hide capture gaps. Explicit action exclusion affects
  log action rows, not known mutation outcomes or death records.
- Preserve `--last`, inclusive/exclusive time bounds and full-window evidence with no steps.
  The existing `--limit` remains an intent-page limit, not an implicit truncation of window
  logs. Explicit `--tail` limits displayed log entries and reports omitted evidence.
- A global step cursor is not a per-archive log cursor. `--step` resolves a stable global
  cursor or stored step ID. `--after` requires an original archive UUID plus its cursor,
  never an ambiguous bare integer; an expired/unresolvable anchor is explicit. Cursor ties
  must not silently lose other devices' observations.
- Marker names are explicit (`--since-marker` / `--until-marker`), not overloaded as times or
  step cursors. Resolve original attachment markers; do not invent markers on archives that
  never held them. Ambiguity/loss is an error or explicit unavailable evidence, not an open
  bound guessed from a new attachment.
- Package-name filtering requires the original live attachment's package/UID mapping. Reject
  unavailable historical package ownership clearly; tag/priority/regex filters remain usable
  on retained data. Do not infer package ownership from a new attachment.

## Mark and redact controls

These are history controls, not application steps. They operate on selected attachments of
this agent's held session; with no device filter they apply to all attachments in that
session. They never attach a device or act on another client's session. Validate before any
control call; report partial multi-device acknowledgement honestly if an operation fails.
Markers bookmark but do not protect evidence. Redaction keeps the existing caller-pattern
validation, before-storage/read behavior, attachment lifetime and late retained scrubbing.
Neither control is a secure-erasure promise for already delivered exports.

## Driver diagnostics belong to daemon administration

`tap logs` continues reading daemon.log. Add `tap logs driver --serial SERIAL` to read the
original currently attached driver's bounded instrumentation log through the daemon's existing
API/authentication. Require an explicit serial, fail clearly if it is not attached, never
acquire a device or launch an agent, and redact/escape output consistently. This command
changes no driver/app state and does not claim retained driver logs exist after detach.
Use the existing metadata-driven help/parser/completion, not a new parser library.

## Remove evidence duplication; retain capture

`capture` already collects screenshot, hierarchy, device information, driver diagnostics,
device logs, crash evidence, and current activity/task/window state. Remove `log evidence`
rather than adding another `history --device-state` collector. Do not make a historical read
silently run fresh dumpsys or capture a new screen.

After a successful explicit capture, store a bounded private artifact reference containing
original connection/attachment/serial, host capture timestamp, local directory and available/
failed parts. Return its files as before. History and protected recording exports include
references in their selected time/device range, clearly labeled as state captured then—not
state at some earlier incident. Do not embed arbitrary file contents or exception text, follow
untrusted paths, or delete separately saved artifacts when cleaning history. Artifact-index
failure must preserve already saved capture results and warn, never repeat capture/input.

## Implementation notes

- Catalog version 6 adds bounded capture references and original marker receipt/attachment IDs.
  Marker names resolve the newest locally indexed acknowledgement batch; legacy markers without
  receipt identity fail explicitly rather than treating a local timestamp as a server receipt.
  The ending marker is included using an exclusive bound one receipt millisecond later.
- `--step` keeps the original server action span for logs, independently of client step-finish
  time, so delayed driver action lines survive. Full time windows still use receipt bounds.
- Each archive read reports its own failure; a failed filtered log read cannot discard crashes
  already read from an independent activity archive. Only typed failure identity is rendered,
  not unclassified exception text.
- Explicit agent capture creates a new 0700 directory and 0600 files, checks an existing
  destination before collection and indexes only external references. The daemon's storage
  budget does not manage those separately saved files; clean never deletes them.

## Verification and delivery

Regression coverage: merged devices and pre-pagination filtering; all retained/no-step
windows; line filters and historical package limitations; markers/redaction across selected
attachments; stable step/opaque log cursor separation; readable/JSON/MCP parity; no old `log`
surface; persisted capture references and partial capture status; driver-log admin ownership,
help/completion/auth/errors and no device acquisition. Preserve all existing crash/ANR,
retention, uncertainty and privacy tests. Update public docs, installed skill, API coverage,
generated command references, admin smoke and internal decision records in the same change.

Run relevant non-device JVM/Python/browser/binding/schema/docs checks, preserve pre-existing
worktree changes, rebase onto current main and update PR #73. Device/native validation remains
not run unless separately requested. Record remaining diagnostic coverage gaps explicitly in
`history-debugging-gaps.md`; command consolidation must not pretend to capture missing data.

### Verification evidence

- Rebased onto current `origin/main` (`35dd0c37`). Pre-existing edits to Kotlin history text,
  common gRPC imports and two daemon test files were preserved outside this commit.
- **725 JVM tests**, zero failures/skips: host core, daemon, Kotlin SDK, protocol and driver
  command engine. Installed the JVM daemon distribution; authenticated driver-buffer tests
  exercise original identity, no acquisition, required serial, duplicate/missing attachments,
  token redaction, terminal escaping and authentication failure.
- **714 Python/client-agent tests passed**, four opt-in device skips. CLI/MCP/API parameter
  parity, new global selection/control/capture regressions and existing privacy/retention tests
  are included. **643 Studio/watcher backend tests passed**; no browser code changed.
- Installed JVM fake-ADB CLI smoke, generator freshness, `buf lint`, shell syntax, strict
  HTML/Markdown documentation builds and diff checks passed. Broad user-configured Ruff has
  existing findings; package-scoped comparison against the preceding commit introduces none.
- Device matrix: **not run**. Native-image rebuild: **not run**. Kotlin LSP coverage is
  inconclusive; Gradle compilation/tests, not an empty diagnostic cache, are the evidence.
