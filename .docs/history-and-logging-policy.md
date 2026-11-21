# History, recordings and daemon logging policy

Decision: owner-approved product direction, implemented by the logging-retention change.
Defaults chosen by the owner: **one hour / 256 MiB** rolling history, **1 GiB** hard storage
budget, **256 MiB** free-disk reserve; **redact before storing**, not an encrypted raw store.

## Product boundaries

- **History: what happened?** Automatic activity and device logs, available after an unprepared
  failure within the reported retention window.
- **Recording: keep this flow.** A named protected range of that same activity and evidence.
  Selection and retention, not implementation fidelity, distinguish it from history.
- **Export: take evidence out of Tap.** An operation over a history or recording, not a third
  kind of data. SDKs and the agent use `tap-history/1` for both, preserving selectors,
  arguments, explicit waits, failures, uncertainty and redaction. Logs are included on request.

The existing agent step policy is unchanged: actions and explicit waits are steps; inspections,
queries and automatic helper polls are not additional steps. This change adds retention and
recording protection, not general query-output capture, inferred assertions, test generation,
a new executable format or automatic replay. SDK recordings protect durable RPC events and
device logs; the agent adds its separately retained high-level intent.

A successful input is not a verified app result. A complete capture is not a passing test.
Sensitive arguments/selectors require explicit replacement when converting a flow into a test.

## Configuration

```sh
tap config logging-policy show
tap config logging-policy rolling --max-age 1h --max-size 256MiB \
  --hard-size 1GiB --min-free 256MiB
# Every leaf command also accepts --state-dir DIR.
```

Limits apply globally to the selected daemon state directory, not once per device, Python
process or agent session. `max-size` bounds ordinary, unprotected disk evidence. Protected
ranges bypass rolling age/size eviction, never the hard budget or free-space reserve.

Settings are validated before an atomic replacement of `logging-policy.properties` (0600).
They can be configured while the daemon is stopped. A running daemon reloads on append/read
and during maintenance every minute; no logcat restart or client-side server startup is needed.
Invalid configuration retains the last valid running policy and emits a warning. Configuring a
state directory does not start a daemon.

## Capture, storage and recovery

- Capture starts at attach and is on by default, one serial-specific logcat child per
  attachment. The existing explicit `capture_logs=false` opt-out remains supported. Starting
  a protected recording refuses an opted-out attachment rather than promising absent logs;
  reattach with capture enabled first.
- `RetainedLogs` stores redacted length-prefixed protobuf entries in private per-attachment
  directories under `<state-dir>/history-logs` (0700 directories, 0600 files). Manifests and
  recording boundaries are atomically replaced; entry writes are synced before acknowledgment.
- Ordinary entries expire by host ingestion time. Disk segments rotate at roughly 256 KiB or
  one minute, whichever comes first (a single bounded entry can make a segment larger).
  Shared protected segments can retain extra bytes, but reads hide expired entries outside
  protected ranges. Cleanup also runs while capture is idle.
- Cursors and attachment/connection identities are never reassigned to a later session on the
  same serial. The original monotonic clock id is preserved for retained reads; values from
  different daemon clock instances must not be compared as one clock.
- Detach stops the logcat child but does not erase retained evidence. The daemon token and
  original identities authorize retained reads after release/restart; discovery lists retained
  attachment identities without opening a new device session.
- Restart recovers complete framed entries, discards a torn tail and marks interrupted capture
  explicitly. Open protected recordings become stopped and incomplete. A crash never invents
  a successful device outcome or retries an input.

Raw in-memory hot logs remain bounded at 16 MiB per attachment for live followers and failure
artifacts. Long retained reads stream disk segments in bounded response batches rather than
loading an entire recording into the daemon heap. Readers lease their snapshot files until
completion/cancellation, so eviction can be delayed by an active read. Live following starts
from the hot buffer; read retained history separately for older evidence. Tail selections have
an explicit 16 MiB server selection limit and fail `RESOURCE_EXHAUSTED` rather than silently
truncating the requested tail.

The policy cannot recover logcat lines Android dropped before capture, capture anything while
the daemon is down, or guarantee an app will reproduce a failure. Logcat interruption entries,
retention gaps, storage failures and unfinished step outcomes remain visible.

## Durable RPC events and the public surface

Every connection has a durable event archive whose identity is its original connection UUID.
It shares the log store, redacted framed format, protection and budgets. Known fields are
copied by generated reflection-free code; unknown fields, bytes and unclassified failure text
are withheld. Cancellation and unclassified RPC failure remain indeterminate. Original serial,
attachment generation, step and clock identities survive redaction. Per-call sanitization uses
the admitting attachment's redactor, never a serial-keyed replacement attachment. New rules
rewrite only that attachment's events; no connection registry retains detached device closures.

`EventLog` remains a bounded live diagnostic ring, not the export source. Durable receipt
cursors are allocated independently from event sequence numbers so interruption gaps cannot
collide with late event receipts. Stop with admitted calls records a gap before freezing the
boundary. Restart/connection removal closes interrupted streams without manufacturing outcomes.

Owner-approved agent surface (all under `tap-agent`):

```text
record list [-L]
record start NAME
record stop NAME
record export NAME [--logs] [--json] [--details]
record clean NAME
history [--logs] [--json] [--details] [--from STEP|TIME] [--until STEP|TIME] [--limit N]
history --last DURATION [--logs] [--json] [--details]
```

Output defaults to readable stdout text; JSON is optional and callers redirect files. No
standalone export, history catalog/cleanup command or release-time file export remains.
History uses stable SQLite AUTOINCREMENT step cursors, including after pruning; latest 200 by
default, oldest-first presentation, inclusive from/exclusive until, timezone-qualified times,
forward `next` and backward `older`. Physical shared segment bytes are reported by `record list
-L`, not advertised as reclaimable bytes.

Python `LogRecordings.export/history` and Kotlin equivalents return portable structured/text/
JSON artifacts through RPCs, never CLI subprocesses. SDK-only activity does not invent agent
steps. `agent_steps_expected` marks an agent-owned range; SDK-only/orphan reads explicitly lack
its local intent. `evidence_complete` describes requested daemon evidence separately from the
whole artifact's `complete`, which also requires any expected local intent.

## Incident windows and independent deaths

Owner-approved refinement: `--from`/`--until` timestamps and `--last 30s` read the entire
retained window, not the duration of selected agent steps. Discover original archives even
with no local intent or live attachment. `--last` fixes an end timestamp once and excludes
other bounds. `--limit` only paginates high-level intent; partial intent is explicitly marked.
Receipt time is common to lines, completed calls and independently observed deaths. Original
RPC start/device times and monotonic clock identities remain visible, never inferred.

Every new parsed crash/ANR is persisted into the connection activity archive outside the
parser lock, with original attachment identity. It is included in all history/record-export
presentation modes, independently of `--logs`. Known messages and available Java/native
stacks are classified diagnostic fields, redacted before storage rather than discarded.
`--details` expands text; JSON always contains retained details. Full ANR thread dumps are
not supplied by this public source. Death follower/catch-up failure leaves an explicit gap.
See [remaining debugging gaps](history-debugging-gaps.md) for what still cannot be recovered.

## Protected ranges

`tap-agent record start NAME` first asks the daemon to protect the current attachment set,
then starts a named range in the same local history store. Capture is already running: start
does not reset logcat, clear buffers, launch an app or alter rolling configuration.

Boundaries use host receipt cursors, not guessed device time. A line delivered after stop can
fall outside the range even if the app emitted it earlier. The recording names the actual
attachment set from start; the agent refuses input on a later/unprotected attachment while
that recording is active. Stop/start a new range with the desired devices already attached.

`record stop NAME` closes the protected interval; release also finalizes an active named
recording. It remains protected until `record clean NAME`. A client crash can leave an open
range; inspect `record list` and stop it explicitly. No control silently continues an ended
connection under a reused session name.

Agent steps stay in its private SQLite store. Ordinary histories retain the newest 20 stopped
partitions. A named recording protects all its partitions, including rotations at the existing
10,000-step / 32 MiB limits. Export joins those partitions in order without creating a separate
step format or duplicating execution. Explicit named cleanup removes steps and daemon
protection. An acknowledged daemon start is preserved if the local intent commit fails, so the
range can be discovered and recovered instead of silently losing protection. The last acknowledged log
capture status is saved locally, so offline step export does not turn a known incomplete log
recording into a complete one.

The daemon hard budget includes protected log files and recording metadata, with reserved
headroom for bookkeeping. If storage is unavailable, ordinary device operation is not replayed
or replaced with a logging error: capture reports its failure and preserves previous evidence.
A new recording cannot be acknowledged without storage for its protection. Persistence halted
by a storage error requires a new attachment to resume; changing limits never pretends the
missing interval was captured. Media and client-owned exported files have separate lifecycles
and are not counted as daemon log storage.

## Reads, exports and privacy

- `ReadLog` can read retained attachment ids using the original connection id after detach.
  `recording` clips the read to a protected range; neither archived nor recording reads follow
  a live stream. Original owner/id mismatches are refused.
- `dropped` counts unavailable entries in the requested cursor interval before user filtering.
  Sum it across response batches: protection can leave holes inside ordinary history, not
  just an evicted prefix. Tail reads intentionally report zero. `capture_error` reports known
  capture/storage interruption, and `clock_id` identifies the original capture clock.
- Package-name filtering needs a live attachment's package-to-uid lookup. Retained reads use
  original numeric uids and support tag, priority and message filters; they do not invent a
  historical package owner from a new attachment's package list.
- Only redacted entries are persisted. The existing built-in secret shapes, registered caller
  patterns and sensitive typed literals apply before storage. Live reads still apply current
  redaction before message filters. Late rules rewrite retained segments; a failed rewrite
  reports failure and prevents that archive from being exported as safely redacted evidence.
- Redaction is best effort, not automatic classification of all app secrets. Previously
  unrecognized values may have existed on disk before a new rule was supplied; earlier files,
  backups and already delivered output cannot be securely erased by adding a pattern. Keep
  storage private and inspect exports. Caller patterns and literal secrets are not saved as
  separate plaintext redaction state.
- `record export NAME --logs` reads retained events and device logs after release/restart.
  No live raw-call option is required. Screenshots/video remain opt-in evidence, not automatic
  history retention.

## Verification

Regression coverage includes policy validation and live reload, global byte/age eviction,
protected ranges, incomplete hard-limit capture, late redaction, torn-tail recovery, restart,
read snapshot eviction, original-id reads after release, typed control errors, late step-id
association, client capture metadata, protected step partition rotation and offline export.
Incident-window coverage includes no-step archive discovery, idle-period logs, inclusive/
exclusive receipt bounds, completed long-running calls, independent crash/ANR text/JSON in
all presentation modes, redacted diagnostic restart recovery, unavailable device-clock
conversion, and intent-page limits that do not trim the evidence window.

Verified after rebasing the durable-event and simplified-interface implementation onto the
current main (including device events, toast history and release 0.2.0):

- 685 JVM tests: host core, daemon, protocol and Kotlin SDK, with zero failures/skips;
  the independent-death path also passed a targeted retained ReadLog RPC check after release.
- 691 Python/agent tests passed, with four opt-in device checks skipped. The original server commit
  independently passed 659 Python/agent tests with those same four skips.
- 643 Studio/watcher backend tests, 183 Studio browser tests and 39 watcher browser tests;
  both production browser builds passed.
- Committed Python/TypeScript binding and event-codec freshness, buf lint/breaking, full
  strict HTML/Markdown documentation builds and `git diff --check`.
- Installed JVM executable smoke, including policy help/show/update, private atomic settings,
  rejected invalid limits and existing administration operations, with fake ADB and no devices.

Device matrix: **not run**; no Android capture timing or throughput claim is made by these
checks. Native image was not rebuilt; native protobuf reachability metadata tests passed.

Related decisions: [Device logs](app-logs.md), [Agent step history](agent-step-recordings.md),
[Daemon API](server-api.md), [CLI parsing](cli-parsing.md).

## API revision (2026-11-21)

The retention and protection rules above stand; the API that exposes them changed. History is
read through `HistoryService` (`contracts/proto/history.proto`, `.docs/server-api.md`): one
timeline merged across sources (a device log per attachment, a connection's calls and deaths),
paged by an opaque cursor, bounded by time or marker, with gaps as entries and timeless missing
evidence as typed issues (no `complete` flag). "Protected ranges" are now history recordings,
owned by a connection, addressed by id, and following devices attached after their start. The
private disk format moved to `host/daemon/src/main/proto/retention.proto`. Python
`client.history` and `tap-agent history` / `record` are the clients; the Kotlin SDK reader is in
`.docs/feature-todo.md`. Design and the user's choices: `.docs/history-api-revision.md`.

A review of that API (same day) changed: marker bounds resolve per device (serial) within the
selection, and the agent limits them to its session's connections; `step` keeps the read's
bounds and filters deaths and gaps to its runs; `EXPIRED` is reported on the first page and for
entries past the age limit inside kept files, with `missing_entries`; recordings stop when their
connection closes and start on a degraded source with that source's issue; per-entry cursors and
`HistoryPageInfo.start_cursor` were removed (cursors are at most 64 KiB raw); a redaction that
could not be applied withholds the source's entries as one `INCOMPLETE` issue; issues appear once
per read. Python separates `since` / `until` (times) from `since_marker` / `until_marker`.
