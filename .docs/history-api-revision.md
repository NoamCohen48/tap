# History API revision

Status: implemented (Kotlin SDK `client.history` is in `.docs/feature-todo.md`). Continue PR #73 with **one new commit
after `766c6efd`**. Do not amend earlier commits. The previous worktree's four unrelated edits
remain there, untouched; this revision uses `.worktrees/feat/history-api-model`.

## Approved product choices

The operator accepted these choices explicitly:

- Protected names belong to a connection, not the daemon. Parallel connections may both use
  `checkout`. Return a stable recording ID for reads and controls after the connection ends.
- Protection follows the connection: devices attached later join from their attachment time.
  Stopping the recording freezes its bounds. This replaces the previous frozen attachment set.
- Agent `--from` / `--until` accept timestamps only. Keep a dedicated `--step` selector and
  opaque continuation cursors; do not overload a number as both a step and log position.
- One `--limit` counts merged timeline entries, with explicit pagination and a continuation
  cursor. Remove the separate intent-limit/log-tail interpretation.
- `history --recording NAME` reads a protected range. Remove the second `record export` reader.
- Keep related mark/redact controls under history; their existing placement was deliberate.

## Contract and SDK shape

### Separate streams, one durable reader

Add a dedicated `HistoryService` and history entry/source messages. A device-log source names
an actual device attachment; a connection source names connection activity. There are no fake
attachment IDs or `events` discriminator booleans in the public retained-history API.
`LogEntry` and `DeviceLogService` remain device-log concepts for live reads/follow/mark/redact.
Durable calls, independently observed deaths, log entries and capture gaps are explicit typed
history variants with original source identity and times.

There is one ordinary SDK reader: `client.history.read(...)`, accepting source/connection/device,
time bounds, filters, a recording ID and pagination. The returned typed page/snapshot owns
serialization and readable rendering; export is not a second data-retrieval operation.
`client.history.sources()` and recording listing return metadata, not competing readers.
`client.history.start_recording(connection, name)`, `stop_recording` and `delete_recording`
control named recordings. Remove
`client.log_recordings` / Kotlin `logRecordings` and their overlapping ordinary/recorded readers.
Keep `device.logs.read/follow` for low-level live streams and `connection.event_log()` as the
explicitly documented bounded diagnostic ring, not as durable history.

Python uses dataclasses and discriminated entry types, matching Kotlin's typed models. Avoid
an untyped dictionary as the SDK's primary API. Typed pagination and coverage/loss information
replace the ambiguous `complete` versus `evidence_complete` distinction. Agent-local intent
availability stays in the agent adapter, not a server `agent_steps_expected` field. Version the
portable JSON format if its shape changes; never silently reinterpret an existing format.

### Bounds, package ownership and paging

Filter time windows on the server, skipping irrelevant retained segments where their indexed
bounds allow it. Do not transmit the whole retention store for `--last 30s`. Preserve original
RPC start/device times separately from the time the daemon received each entry.

Persist bounded package ownership with the device-log evidence while its attachment is live.
Historical package filters use that evidence, never a later attachment's current UID map.
Handle shared UIDs, unknown ownership and changes/reinstallation explicitly; one timeless
package-to-UID map is insufficient. Known gaps and source failures must survive line filters.

Server continuation tokens bind the selection and fixed read boundary, and order ties by source
and per-source cursor. Agent pagination merges local intent/capture references with server
facts under the same entry budget, preserving tied timestamps and independent failures. Reject
invalid, incompatible or expired cursors explicitly; never invent a lost boundary.

### Trust and protection

The daemon token authorizes daemon-wide history inspection and protection administration.
Connection IDs identify the evidence owner; they are not authenticated principals or a security
boundary. Name scoping prevents accidental worker collisions, not malicious access by another
token holder. Use explicit source/owner fields rather than repurposing a live caller field.
Document this local-daemon trust model plainly.

New attachments join every active protection of their connection atomically with registration;
protect from their first retained entry. Existing original attachment IDs are never replaced by
serial. Preserve stop-with-in-flight-call gaps, redaction, no replay, rolling limits, shared
physical bytes, hard disk/free-space safeguards and restart recovery.

Private retention envelopes may retain a compatible on-disk representation, but public device
logs must not expose connection events through that envelope. Do not silently discard old
retained evidence during the API migration; either decode it or report its unavailability.

## Contract review outcome (applied)

A review of the first `history.proto` draft changed these points before any SDK was built on it:

- **One name: history recording.** The CLI verb is `record`, so the contract says recording,
  not "protection": `StartHistoryRecording` / `StopHistoryRecording` / `ListHistoryRecordings`
  / `DeleteHistoryRecording`, `HistoryRecording`, `HistorySelection.recording_id`. "History"
  keeps it apart from the scrcpy media `StartRecording`. SDKs follow:
  `client.history.start_recording(connection, name)` → `HistoryRecording`, `stop_recording(id)`,
  `recordings()`, `delete_recording(id)` (Kotlin the same in camelCase).
- **Order is requested, never inferred.** `ReadHistoryRequest.newest_first`; with `limit` it
  gives the most recent entries of a range (the old `--tail`). The first draft read forward
  only when `from_time_ms` was set, so `--last 30s --limit 200` returned the oldest 200.
- **Flat entries.** `HistoryEntry.kind` is `line | action | marker | call | death | gap`
  directly; the entry alone carries sequence, receipt time and monotonic time (the draft
  embedded `LogEntry`, which repeated its cursor and both clocks).
- **One meaning per form of missing evidence.** A `gap` entry is positioned (capture stopped
  and resumed there); a `HistoryIssue` is loss without a position (`EXPIRED`, `UNAVAILABLE`,
  `INCOMPLETE`, `PACKAGE_UNKNOWN`). Gaps no longer also become a recording error string, and
  `HistoryGap` / `LogEntry.gap` inside history / `CAPTURE_INTERRUPTED` are gone.
- **Marker bounds on the daemon.** `from_marker` / `until_marker` (a oneof with the times)
  resolve to the exact position of the newest such marker on the selected devices, earliest
  for the start and latest for the end, so `history --since-marker` needs no client lookup and
  same-millisecond lines before a marker stay out.
- **Explicit log inclusion.** `include_logs` plus `log_filter`; a filter without
  `include_logs` is `INVALID_ARGUMENT` instead of an empty message meaning "on".
- **Smaller page metadata.** `next_cursor` empty means done (no `has_more`), no response
  direction, issues inline (no `HistoryCoverage` wrapper), and `ListHistoryRecordings` takes
  `connection_ids[]` like the selection. A continuation may change only `limit`.
- **The disk format is the daemon's.** `tap.daemon.storage.v1` moved from
  `contracts/proto/storage/` (compiled into the published `tap-schema` and the driver APK) to
  `host/daemon/src/main/proto/retention.proto`, generated in `:host:daemon` only. Its embedded
  tap.v1 messages are part of the disk format; the file says how to change them.

## Documentation and delivery

Rewrite public examples/docstrings in plain words; keep detailed invariants in the contract
and this decision record. Update the agent skill, MCP instructions, API coverage, generated
references, CLI/admin smoke, changelog, server API and wire documentation as applicable.

Regenerate Kotlin/Python/Studio/watcher bindings rather than hand-merging generated files.
This is an intentional 0.x API change: remove superseded proto/API surfaces outright, update
BREAKING_BASELINE to the current main commit as required, and test the new contract.

Regression tests must cover typed source/entry variants, server-side time/limit/filter behavior,
post-detach package ownership, tied timestamp paging, scoped duplicate names, later attachments,
restart/redaction/budget/failure behavior, SDK parity and agent merging including local-only
intent/capture references. Run relevant non-device checks and update the existing PR. Do not
run a local device matrix or local native rebuild unless separately requested. Preserve all
unrelated edits and keep this entire revision in one new commit.
