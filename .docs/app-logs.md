# Device log: streamed logcat with Tap's actions, markers and redaction

Status: implemented (2026-11-14). Unit suites pass (driver engine, protocol, host core, daemon,
Kotlin SDK, Python client, `tap-agent`); **not yet run on the device matrix**, so the ordering of
the driver's action lines among an app's own lines is by design, not yet measured.

## The gap

Tap read logcat once, at failure time, from the device's ring buffers. Three things were
missing, and the other tools have some form of each (Maestro and agent-device stream logs with
markers; Appium exposes `logcat` log types and broadcasts them):

- a long or chatty test lost its oldest lines before the failure capture read them;
- a test could not ask "what did the app log during this step": there was no way to read the
  log mid-test, no position to read from, and no filter by app;
- nothing said which Tap action an app line followed.

## Decisions

1. **Capture starts at attach and is on by default.** The daemon runs one long-lived `adb
   logcat` child per attachment (`LogcatCapture`, host core) from the device time read at
   attach. `AttachRequest.capture_logs = false` (Kotlin `DeviceOptions.captureLogs`, Python
   `attach_device(capture_logs=False)`) turns it off; the device-log calls then fail and failure
   capture falls back to the ring buffers. Not a separate "start logging" call: the lines a test
   wants are usually the ones before it knew it wanted them.
2. **Kept in memory, bounded, per attachment.** Newest 16 MiB of entry text, forgotten at
   detach. Not written to the state directory: nothing unredacted ever leaves the daemon
   process, and a reader that wants the log on disk saves what it reads (the failure artifacts
   do). A read reports how many entries before its starting point were already forgotten.
3. **One ordered stream of four entry kinds**: logcat lines, Tap's actions, markers, and gaps
   (the capture was interrupted). Each has a cursor (1, 2, 3, … per attachment), so a reader
   continues exactly where it stopped.
4. **Markers are names a client writes into the stream** (`MarkLog`), stamped when the daemon gets
   the call. A read can start at the newest marker with a name. They replace "remember the
   time, then filter by time", which breaks on the host/device clock difference. The JUnit
   extension and the pytest plugin write `test <name>` and `test passed|failed <name>` markers
   on their own, best effort.
5. **The driver logs its own actions; the daemon logs the host's.** A command the driver runs
   is logged by the driver into logcat just before it starts and once its response is decided
   (`ActionLog`, `protocol-contract.md` "Action log"): the lines are in the same stream and on
   the same clock as the app's, so their order is exact. The alternative, the daemon inserting
   an entry when it sends a command and when the answer arrives, is off by the transport and
   by logcat's delivery delay, which is the wrong side of "did the app log this before or after
   the tap". Calls that run on the host over ADB (app lifecycle, device settings) have no
   driver to log them, so the daemon writes those entries itself, a few milliseconds from the
   truth. Only mutations and waits are logged: a polled `exists` would bury the app's lines.
   The daemon believes a `Tap`-tagged line only when its uid is the driver's
   (`DeviceInfo.driver_uid`); any app can write a tag.
6. **An action line never carries an argument.** The operation and the selector only: typed
   text, clipboard and file contents must not reach the device log, where every app with
   `READ_LOGS` and every bug report can see them.
7. **Redaction on read, in the daemon.** Built-in patterns for the usual secret shapes
   (authorization and cookie headers, `password=` / `token=` style pairs, bearer tokens, JWTs),
   the text of every `sensitive` `set_text` / `type_text` of the attachment, and patterns the
   caller adds (`RedactLog`). On read rather than on capture, so a pattern added after the secret
   was logged still hides it, and so filters (`message_regex`) cannot be used to probe for a
   redacted value. `DeviceLogs` and the failure artifacts go through the same redactor.
   Redaction is best effort by nature: it hides what matches, and a secret in a shape nobody
   described is still there.
8. **Filtering by app is by uid.** logcat prints the uid with `-v uid`; the daemon resolves
   package → uid with `pm list packages -U` per read. Tags and pids are not identities: a pid
   changes on every app restart, and a tag is whatever the app wrote.
9. **No network view.** A "network dump" built from logs only sees what an app's HTTP library
   was configured to log (in practice OkHttp's logging interceptor at a verbose level), so it
   would work for some apps and silently show nothing for others. Left out; recorded as the
   log parser pipeline in `feature-todo.md`.

10. **A step id ties a client's own record to the log.** A client that records what it did
    (tap-agent's steps, a test runner's lines) needs to find what each step did on the device.
    It sends `tap-step: <id>` with the step's calls; the daemon puts the id on the action
    entries those calls caused (`LogAction.step`) and on their events, and `ReadLog(step)`
    returns the step's slice. Rejected: matching by time or cursor range (logcat lines reach
    the daemon after the call returned, so ranges are off), and the client storing the driver's
    request ids (misses every call that runs over ADB, and ids start again on a reconnect). A
    header rather than a request field because every device, app and app-data call can belong
    to a step.

## Surface

- Server: `DeviceLogService` `ReadLog` / `MarkLog` / `RedactLog` (`device_log.proto`, `server-api.md`).
- Kotlin: `device.logs.read(LogStart, LogFilter)`, `follow(...)` (a `Flow`), `mark(name)`,
  `redact(patterns)`; `withStep(id?) { }` and `LogFilter(step = …)`.
- Python: `device.logs.read(since=…, tail=…, packages=…, min_priority=…, tags=…, grep=…)`,
  `follow(...)`, `mark(name)`, `redact(*patterns)`; `with tap_e2e.step() as id:` and
  `read(step=id)`.
- `tap-agent log [read|mark|redact|driver|evidence]` and the MCP tool `log`: compact lines,
  newest 200 by default, ending with the cursor to continue from; see the next section.
- `DeviceLogs.logcat` (and so `logcat.txt` in failure artifacts) is the captured log as text.

## tap-agent: one history

Owner approved (2026-11-14): the agent's step recording and the device log are one history.

- **Always on.** There is no `record start|stop`. `step_capture` calls `records.ensure(session,
  connection id)` before each action or wait: the history starts with the first step, belongs to
  that held connection and stops at `release`. A different connection id (timeout, daemon
  restart) stops the old one ("connection ended") and starts the next; a full one (10 000 steps
  / 32 MiB) rotates ("full"). The newest 20 stopped histories are kept.
- **Step id.** Each step gets a 16-hex id, stored with the step and sent as `tap-step` on every
  call the step makes (`tap_e2e.step`), so `LogAction.step` and `LoggedEvent.step` point back at
  it. `log` prints `── step N <verb>  [id]` headers and `log --step N|id` reads one step.
- **Closed ranges and data.** `log --until NAME` cuts the read at the newest marker with that
  name (client-side; with no start it reads everything kept), and `log --json` returns the
  entries in the shape `export --logs` uses, plus the steps they name.
- **Markers on both.** `log mark NAME` calls `MarkLog` and stores the marker in the history
  (after which step, device, cursor). `export --since/--until` cuts the steps by markers.
- **Two stores, merged when read.** The steps stay in the agent's SQLite store (they survive
  release and need no daemon); the log lines stay in the daemon (16 MiB, redacted on read, gone
  at detach). `export --logs` / `--calls` merge the device log and the event log into the export
  while the session lives; after release only the steps remain.
- **Verbs.** `record` and `logs` are removed. `history` lists/cleans saved histories; the old raw
  event history is `export --calls`; `logs driver` → `log driver`; `logs device` → `log
  evidence` (it also holds crashes, ANRs and activity/window state, so the device log does not
  replace it); `logs hierarchy` is dropped (`capture` saves the dump, `snapshot` reads the screen).
- **No blocking.** An `unknown` or interrupted step used to refuse further recorded input until
  the recording was stopped. With no stop command that would dead-end a session, so later steps
  are recorded and the export says `complete: false`.
- Format `tap-agent-recording/2` (step `id`, `markers`, optional `range`, `log`, `calls`); the
  catalog migrates a version 1 store in place.

## Limits and what is deferred

- 16 MiB per attachment is a fixed constant, not a setting.
- A follower more than 8 192 entries behind is ended and must read again from its cursor.
- Host-side actions (app and device calls) are placed by the daemon's clock, not the device's.
- logcat's own buffers can still drop lines under extreme load before the daemon reads them;
  that shows as nothing (logcat prints no notice in this format), not as a gap entry.
- Typed records parsed from log lines (HTTP exchanges): `feature-todo.md`.
- Studio does not show the device log yet. The watcher does (below).

## The watcher

`WatchService.WatchLog(attached_device_id, after_cursor)` is the read-only way in: it names no
connection, renews nothing, and returns the same redacted entries as `ReadLog` with `follow`.
`LogEntry.host_monotonic_ns` puts each entry on the clock of the video frames and the actions.

The watcher back end (`tap_watcher/logs.py`) follows every attachment it learns of from the
activity stream (and from the snapshot after a reconnect) until the device is detached, whether
or not a page is open, and keeps the newest 50 000 entries per serial with its own per-serial
sequence (an attachment's cursors restart at 1; a serial outlives its attachments). So the log
of a test that already ended is still there to read and to save. The cost in the daemon is one
follower per attachment.

- **Page**: an **Actions | Log** switch beside the screen. The log is a virtual list with a
  search box and a minimum priority; picking an entry pauses the screen on the last frame the
  daemon received at or before it, a line in the list marks where the video is, and markers,
  errors and gaps are ticks under the scrubber (entries closer than 1/240 of the bar share one).
- **Recordings and clips** save `log.json` (`tap-watch-log/1`) beside `steps.json`: the entries
  the daemon got between a part's first and last frame, each with its part and video offsets.
  A recording notes entries as they arrive (at most 200 000; more are counted as `omitted`); a
  clip takes them from the 50 000 the back end kept.
- Placement is by the daemon's monotonic clock, not the device clock: an entry sits where the
  daemon read the line, a few milliseconds after the app wrote it.
- Not verified on a device yet: checked with the daemon's unit tests, the watcher's tests
  against a fake daemon, and the page in a browser against that fake.
