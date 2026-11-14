# Record steps and turn them into tests

Use **`record`** for intentional, named action-and-wait recordings. Use **`history`** for the
bounded raw SDK event trail (the old `export` behavior). **`media-record`** captures video/audio.
None of these automatically replays input or generates test code.

## Named recordings

```sh
tap-agent attach emulator-5554
tap-agent record start checkout
tap-agent app cold-launch com.example.app
tap-agent wait id=email
tap-agent fill id=email person@example.com
tap-agent fill id=password example-value --sensitive
tap-agent tap id=login --settle
tap-agent wait text=Welcome
tap-agent record stop
tap-agent release
tap-agent export checkout                  # JSON on stdout, even after release
tap-agent export checkout -o checkout.json # new owner-only file
```

`record list` lists names, state, session and step count. `record start NAME` never overwrites
or appends to an existing name; one recording is active per agent session. `record stop [NAME]`
retains it. Exporting does **not** stop capture. Without a name, export chooses the session's
active recording, otherwise its most recent archive.

`record clean NAME` deletes a stopped archive; `record clean --all` explicitly deletes all
stopped archives and refuses if any are active. Exported files are separate copies and are not
deleted by clean. This is logical deletion, not a secure-erasure guarantee.

Recordings live in private SQLite storage under `$TAP_AGENT_DIR/records` (default
`.tap/agent/records`): 0700 directory, 0600 files. CLI and MCP processes share recordings when
they use the same agent directory. Stored list/stop/clean/export operations need no daemon.
Names use 1–80 ASCII letters/digits/dots/dashes/underscores, starting with a letter/digit.
Storage needs a local filesystem with SQLite locking and POSIX owner-only permissions; Windows
has not been validated.

Start binds to the current held connection, not just its reusable session name. Release stops
that connection's recording after releasing it successfully. A daemon/client crash can leave
its last known state active; a newly attached connection cannot silently continue it. Inspect
and stop that archive, then start another name.

## What a step means

A recording captures **one high-level agent action or explicit wait per call**, including known
failures. Examples: targeted typing remains one step with tap/focus/type options; scroll-until
remains one bounded operation. SDK polls, ref resolution, snapshots, inspections, counts and
automatic post-action settle are not extra steps. Mixed commands record only their action/wait
form: clipboard writes, permission waits/choices, app visibility waits and so on. Session controls,
recording controls, media capture and history/export are not app steps.

Every resolved target contains the actual, package-bound SDK selector used, never a transient
`@eN` handle. Drag and scroll-until retain both targets. Each step identifies its actual device;
a recording can contain steps on several attached devices.

The format is **`tap-agent-recording/1`**. It is high-level intent, not a list of raw daemon calls
and not Studio's separate `tap-recording/1` protobuf document. For example:

```json
{
  "format": "tap-agent-recording/1",
  "name": "checkout",
  "session": "agent",
  "connection_id": "opaque-held-connection-id",
  "state": "stopped",
  "started_at": "2026-11-06T12:00:00.000Z",
  "stopped_at": "2026-11-06T12:00:02.000Z",
  "stop_reason": "explicit",
  "complete": true,
  "steps": [{
    "seq": 1,
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
    "outcome": {"status": "succeeded", "error_type": null}
  }]
}
```

Selectors use `tap.v1.Selector` proto3 JSON with proto field names. Other parameters describe the
agent call (durations in seconds, including explicit defaults); `selector_ref` points into that
step's targets. They are data for a person/agent converting a flow, not ready-to-execute kwargs.

**Complete means capture is final and has no unfinished outcomes—not that the flow passed.**
A successful input acknowledges acceptance, not the app-specific result. Assert the effect in
the test you create. Known failures retain their exception type, not potentially sensitive
exception text or command output. Explicit settle quiet-period expiry is `wait_timeout`; after
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

A step's `app_deaths` lists the watched app crashes and ANRs the step reported: serial,
sequence, `kind` (`crash` or `anr`), package and process names, exception class and `expected`
(the step ran with `--expect-death`). Like other app output, the exception message and stack
trace are not stored.

An intent is committed before input and selectors before their mutation. A killed process leaves
`running`/`unknown` or `interrupted` evidence, with `complete: false`.
Further recorded steps are refused for an unfinished/interrupted intent until the user inspects/stops the archive. Stop cannot
race an in-flight step. If recording outcome storage fails after input, the original result is
preserved with a warning not to repeat it; the pending intent remains. Active exports are
consistent snapshots and always non-final.

Limits are 10,000 steps, 1 MiB per step and 32 MiB per recording (including metadata allowances).
Reaching a limit refuses new input rather than evicting earlier steps silently.

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

## Raw history

```sh
tap-agent history                         # tap-events/1 JSON on stdout
tap-agent history --after-seq 12 -o history.json
```

This is the previous export: SDK commands/app calls and their outcomes in execution order,
including helper waits and polling. `command`, `app`, `error` and `failure` are `tap.v1`
messages in proto3 JSON; their 64-bit integer fields are strings. The server retains the newest
2,000 events, with `dropped` counting evictions. Read history **before release**: it ends with
the session. Typed text is verbatim unless marked sensitive; this is not automatic secret
classification. New `-o` files are owner-only and never overwrite existing outputs.

The Python SDK still provides `connection.event_log()` directly
([Held connections and screen snapshots](../sdk/python.md#held-connections-and-screen-snapshots)).
[Tap Studio](../studio/index.md) records browser-selected flows using its own recording contract.
