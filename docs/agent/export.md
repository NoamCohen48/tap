# Record steps and turn them into tests

A session records its steps by itself: every action and explicit wait, from the first one until
`release`. **`export`** writes that step history as JSON, **`log`** shows the device log with
the steps in it, and **`media-record`** captures video/audio. None of these automatically
replays input or generates test code.

## The step history

```sh
tap-agent attach emulator-5554
tap-agent app cold-launch com.example.app
tap-agent log mark login                   # a marker, in the device log and the history
tap-agent wait id=email
tap-agent fill id=email person@example.com
tap-agent fill id=password example-value --sensitive
tap-agent tap id=login --settle
tap-agent wait text=Welcome
tap-agent log mark logged-in
tap-agent export                           # JSON on stdout
tap-agent export --since login --until logged-in --logs -o login.json
tap-agent release
tap-agent export -o session.json           # still there after release
```

Nothing starts or stops the recording. The history begins with the session's first step and
stops when the session is released (or its held connection ends); exporting does not stop it.
Without a name, `export` chooses the session's live history, otherwise its most recent stopped
one. `history` lists the saved histories with their name, state (`live`, or `stopped` and why:
`released`, `connection ended`, `full`), step count, start and stop times, markers and session,
and `export NAME` exports an older one. A history whose session ended on the server without a
release (an idle timeout, a daemon restart) is stopped as `connection ended` the next time
`history` or `export` asks the server. The newest 20 stopped histories are kept; older ones are
removed when a new history starts.

### Markers, the device log and the server's calls

`log mark NAME` writes a marker into the [device log](../guide/logs.md) and into the history.
`export --since NAME` exports the steps after the newest marker with that name and
`--until NAME` the steps before it. `@CURSOR` (the cursor `log mark` printed) names exactly
one marker, also when the name was used more than once; the export's `range` records the name
and cursor of each marker it used.

Each step has an `id`. Tap sends it with every device call the step makes, so the device log's
action entries and the server's record of the calls name the step that caused them:

- `export --logs` adds `log`: per device, the log for the same range (the app's logcat lines,
  Tap's actions with their `step`, markers), with secrets redacted by the server. `--package`,
  `--priority`, `--tag` and `--grep` narrow its logcat lines as they narrow `log`, and are
  recorded as `filters`; without them a phone's log is mostly other processes.
- `export --calls` adds `calls`: the server's record of every device call the session made
  (`command`, `app`, `error` and `failure` are `tap.v1` messages in proto3 JSON; 64-bit integers
  are strings), each with its `step`. This includes helper waits and polling a step did. The
  server keeps the newest 2,000 calls; `dropped` counts the ones it no longer has.
- `tap-agent log` prints a `── step N <verb>  [id]` line where a step's actions begin, and
  `tap-agent log --step N` prints only that step's part of the log. `tap-agent log --json`
  returns the log entries alone as data, with the same filters, start points and `--until`.

The device log and the call record live in the server and end with the session, so `--logs` and
`--calls` work only **before release**. The step history itself does not need the server.

### Storage

`history clean NAME` deletes a stopped history; `history clean --all` deletes all stopped ones.
A live session's history cannot be cleaned. Exported files are separate copies and are not
deleted by clean. This is logical deletion, not a secure-erasure guarantee.

Histories live in private SQLite storage under `$TAP_AGENT_DIR/records` (default
`.tap/agent/records`): 0700 directory, 0600 files. CLI and MCP processes share them when
they use the same agent directory. List, clean and a plain export need no daemon.
Storage needs a local filesystem with SQLite locking and POSIX owner-only permissions; Windows
has not been validated.

A history belongs to one held connection, not just its reusable session name. When that
connection is gone (released, timed out, a daemon restart), the next step of a session with the
same name starts a new history and the old one is stopped with `stop_reason: "connection ended"`.

## What a step means

A recording captures **one high-level agent action or explicit wait per call**, including known
failures. Examples: targeted typing remains one step with tap/focus/type options; scroll-until
remains one bounded operation. SDK polls, ref resolution, snapshots, inspections, counts and
automatic post-action settle are not extra steps. Mixed commands record only their action/wait
form: clipboard writes, permission waits/choices, app visibility waits and so on. Session controls,
`log`, media capture and history/export are not app steps.

Every resolved target contains the actual, package-bound SDK selector used, never a transient
`@eN` handle. Drag and scroll-until retain both targets. Each step identifies its actual device;
a recording can contain steps on several attached devices.

The format is **`tap-agent-recording/2`**. It is high-level intent, not a list of raw daemon calls
and not Studio's separate `tap-recording/1` protobuf document. For example:

```json
{
  "format": "tap-agent-recording/2",
  "name": "agent-20261106-120000",
  "session": "agent",
  "connection_id": "opaque-held-connection-id",
  "state": "stopped",
  "started_at": "2026-11-06T12:00:00.000Z",
  "stopped_at": "2026-11-06T12:00:02.000Z",
  "stop_reason": "released",
  "complete": true,
  "markers": [{"name": "login", "after_seq": 0, "at": "2026-11-06T12:00:00.500Z", "serial": "emulator-5554", "cursor": 212}],
  "steps": [{
    "seq": 1,
    "id": "5f0c2a9d41b7e6a3",
    "action": "tap",
    "kind": "action",
    "serial": "emulator-5554",
    "started_at": "2026-11-06T12:00:01.000Z",
    "finished_at": "2026-11-06T12:00:01.100Z",
    "duration_ms": 100,
    "parameters": {
      "target": {"selector_ref": "target"},
      "device": null,
      "long": false,
      "settle": false,
      "double": false,
      "count": 1,
      "interval": 0.1,
      "duration": null,
      "timeout": null
    },
    "targets": {"target": {"node": {"resource": {"name": "login"}}}},
    "outcome": {"status": "succeeded", "error": null}
  }]
}
```

Selectors use `tap.v1.Selector` proto3 JSON with proto field names. Other parameters describe the
agent call (durations in seconds, including explicit defaults); `selector_ref` points into that
step's targets. They are data for a person/agent converting a flow, not ready-to-execute kwargs.

A marker's `after_seq` is the last step before it (0: before the first) and `cursor` its entry
in that device's log. An export cut with `--since` / `--until` also has `range`.

**Complete means the history is stopped and has no unfinished outcomes—not that the flow passed.**
A successful input acknowledges acceptance, not the app-specific result. Assert the effect in
the test you create. A failure keeps its code in `outcome.error`: the driver's error code
(`NOT_FOUND`, `AMBIGUOUS`, `INDETERMINATE`), `WAIT_TIMEOUT`, the server's failure reason
(`SESSION_UNUSABLE`, `DEVICE_BUSY`) or, for a refusal of the agent's own, the error's class name
(`AgentError`). Never the exception text or command output, which can hold typed text. Explicit settle quiet-period expiry is `wait_timeout`; after
an accepted action it is `post_settle_timeout`.

A failed action has one of three statuses, and only two of them are safe to act on:

- `rejected_before_input`: the step was refused before it sent anything (a bad argument, a
  device that isn't attached, a selector the agent could not resolve).
- `failed`: the step sent one command and its error code proves the command changed nothing
  (`NOT_FOUND`, `AMBIGUOUS`, `NOT_INTERACTABLE`, `STALE_BEFORE_INPUT`, a server refusal made
  before the device was touched, ...).
- `unknown`: anything else. The input may have happened: the error code allows it
  (`INDETERMINATE`, `ACTION_REJECTED`, `AUT_CRASHED`, a setting that did not read back, ...), the
  step sends several inputs (a tap before typing, a scroll loop, waking before unlocking), or a
  read after the input failed. Never replay it blindly.

An intent is committed before input and selectors before their mutation. A killed process leaves
`running`/`unknown` or `interrupted` evidence, with `complete: false`.
Later steps are still recorded after it; the export says `complete: false`. If outcome storage
fails after input, the original result is preserved with a warning not to repeat it. An export
of a live history is a consistent snapshot and always non-final.

Limits are 10,000 steps, 1 MiB per step and 32 MiB per history (including metadata allowances).
A full history is stopped (`stop_reason: "full"`) and the next step starts a new one; a single
step over 1 MiB is refused before any input.

## Sensitive data

- `fill`/`type --sensitive` (MCP `sensitive: true`) replace text with `{"redacted": true}` before
  persistence. Text-bearing selectors for these steps are also omitted explicitly; resource/class/
  package selectors can remain. Clipboard writes are always redacted in step recordings.
- Output and exception messages are not stored. Inferred snapshots and poll results are not steps.
- Other intentionally supplied arguments are ordinary recording data. A sensitive flag does **not**
  automatically make a later wait, selector, intent extra or unmarked text input secret. Avoid
  putting secrets there. Keep recordings private; inspect them before publishing.
- A converted test must obtain redacted values/selectors explicitly. No secret substitution,
  automatic inferred wait or replay is hidden in export.

## Privacy of an export

Typed text is verbatim unless marked sensitive; this is not automatic secret classification.
The device log in `--logs` is redacted by the server (`log redact REGEX` adds patterns), the
calls in `--calls` hold what the session sent. New `-o` files are owner-only and never overwrite
existing outputs.

The Python SDK still provides `connection.event_log()` directly
([Held connections and screen snapshots](../sdk/python.md#held-connections-and-screen-snapshots)).
[Tap Studio](../studio/index.md) records browser-selected flows using its own recording contract.
