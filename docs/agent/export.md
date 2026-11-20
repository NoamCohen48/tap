# History and recordings

Tap retains redacted activity automatically. A **recording** protects a named range of that
same activity; an **export** reads it. Neither starts a second capture or replays anything.

## Read history

```sh
tap-agent history
tap-agent history --logs
tap-agent history --last 30s --logs
tap-agent history --last 5m --details --json > incident.json
tap-agent history --json > history.json
tap-agent history --from 120 --until 180 --limit 50
tap-agent history --from 2026-11-17T09:00:00Z --until 2026-11-17T10:00:00Z --logs
```

The default is the latest 200 retained agent steps, printed oldest first as readable text.
`--json` returns `tap-history/1`. `--logs` includes retained device logs; durable RPC events
are included without an extra flag when available. Nothing writes an implicit export file.
Use shell redirection if you want a file, and protect it appropriately.

Bounds accept a positive step cursor or an ISO timestamp with a timezone. `--from` is
inclusive; `--until` is exclusive. Cursors are stable across sessions and pruning, not page
row numbers or transient snapshot refs. Follow the returned `next` cursor with `--from`, or
`older` with `--until`. A page is a bounded view, not proof that omitted activity never happened.
Only actions and explicit waits are high-level steps; internal polls and diagnostic snapshots
are not promoted into invented user intent.

## Full time windows and background incidents

`--last 30s` selects a fixed window ending when the command starts. It accepts durations such
as `500ms`, `30s` or `5m`, and cannot be combined with `--from` or `--until`. Timestamp bounds
and `--last` read **all retained activity in that window**, discovering original archives even
when no agent step ran, no local history exists, or the original connection has been released.
`--logs` reads the entire requested log window, including idle periods between or after steps.
`--limit` paginates agent intent only; it does not shorten the requested background evidence
window. A truncated intent page is explicitly marked, not called a complete whole-window export.

Window selection uses the host's **receipt clock**, inclusive from/exclusive until. An RPC may
have started before the window but completed inside it: JSON preserves its original `at` plus
`received_at` and receipt monotonic time. Death entries preserve host observation time and
original device time separately; delayed Android delivery is not guessed to be instantaneous.
Step-cursor bounds resolve to the retained step's start time; an expired boundary is reported
as unavailable rather than inventing its time. Without time bounds, default RPC presentation
follows the step page; retained independent deaths are still included.

## Crashes and ANRs

Crashes and ANRs are independent durable observations, including background deaths with no
active step and deaths of unwatched apps. They appear as explicit **CRASH** / **ANR** entries
in text and a `deaths` array in JSON for history and named recording exports—with or without
`--logs`, time filters or other presentation flags. Named range boundaries and retention still
apply. The original attachment ID disambiguates reattachments of the same serial.

Known diagnostic messages and available Java/native stacks are redacted before storage.
Default text shows a compact summary; `--details` adds retained stack traces. JSON always
contains available redacted details, so no verbose-capture mode had to be enabled before an
unexpected incident. SDK equivalents are `render_text(details=True)` and
`renderText(details = true)`. `stack_trace_available: false` means the source supplied no
stack; full ANR thread dumps are not promised by the public logcat source. Interrupted death
followers and failed catch-up reads leave explicit evidence gaps.

## Protect a range

Attach every device you want included before starting:

```sh
tap-agent record start checkout
tap-agent tap text=Pay,pkg=com.shop
tap-agent wait text=Receipt,pkg=com.shop
tap-agent record stop checkout
tap-agent release
tap-agent record export checkout
tap-agent record export checkout --details
tap-agent record export checkout --logs --json > checkout.json
```

`record start NAME` protects subsequent agent steps, durable RPC events and the attached
devices' logs from ordinary rolling eviction. `record stop NAME` freezes the range; it does
not release devices. Exporting a live range is allowed and does not stop it, but it is an
incomplete snapshot. Stopping while calls are in flight leaves an explicit evidence gap;
their later receipts cannot silently enter the stopped range.

```sh
tap-agent record list
tap-agent record list -L
tap-agent record clean checkout
```

The short list prints names. `-L` adds state, timestamps and retained backing-segment disk
usage. Segments can be shared by overlapping ranges: these bytes are not a promise of how
much cleaning one recording will reclaim. `record clean NAME` explicitly removes a stopped
recording's protection and local history; ordinary retention then applies. Active recordings
must be stopped first. Protection does not override hard storage/free-space limits.

New attachments cannot receive recorded input while a recording protects an older fixed
device set. Stop it and start a new range with the desired set. `media-record` is separate
video/audio capture, not history protection.

## What survives release and restart

The daemon stores redacted RPC events alongside device logs, under the same retention and
protection budget. Exports use those archives, not a newly attached device or the live
connection's bounded `Events` ring. SDK scripts and agent sessions can therefore be inspected
after release or daemon restart while their evidence remains retained.

Agent intent is stored privately in `$TAP_AGENT_DIR/records` (default
`.tap/agent/records`); CLI and MCP processes must share it. If local intent is missing but the
daemon acknowledged a named recording, it remains discoverable and exportable. Missing agent
steps are reported, never reconstructed from RPCs. Offline local exports preserve the last
acknowledged status and explicitly report unavailable daemon evidence. Named stop/clean needs
the daemon so it can update protection.

## Read the evidence honestly

Exports preserve original connection, attachment, step, cursor and clock identities, resolved
selectors, actual input calls and known outcomes. Nested calls use proto3 JSON field names,
enum names and string-encoded 64-bit values. Monotonic times only compare within their original
clock ID. Device log observations are not assertions or a universal cross-device ordering.

High-level action outcomes distinguish:

- `rejected_before_input`: no mutation was transmitted.
- `failed`: the known response proves the operation failed without the uncertain effect.
- `unknown`: the input may have happened. Inspect the device; never replay it blindly.

Cancellation and unclassified transport failures remain indeterminate. A command returning
success is not proof of the app's intended result. Explicit waits retain their actual condition;
count waits do not claim an atomic collection snapshot, and helper polls are not new assertions.
No export generates tests or invents checks.

Check `complete`, `dropped`, `capture_error`, gaps and `log_capture`. `complete: true` means a
finalized capture with known outcomes and no known missing requested evidence—not a passing
test. It is false for live captures, known loss, unknown outcomes or missing expected intent.

## Retention and privacy

Defaults are **one hour / 256 MiB rolling**, **1 GiB hard budget**, and **256 MiB free-space
reserve**. The daemon's administrative policy is configured with `tap config logging-policy`;
history/recording commands stay under `tap-agent`. Agent history also has bounded local
partitions. Full/expired partitions, interrupted writes and failed capture remain explicit.

Redaction happens before durable storage. Mark input secrets with `--sensitive`; clipboard
writes are always withheld. Default patterns and registered literal/regex rules apply to
known string fields. Sensitive inputs are masked even when short; unknown wire fields,
binary payloads and unclassified failure output are not persisted as raw evidence. Rules are
bound to the original attachment, including late completion after reattachment. New rules
also scrub that attachment's retained activity.

This is not automatic secret discovery. Do not put secrets in names, IDs, selectors, extras
or unmarked text. Treat accessibility snapshots, artifacts, raw live diagnostic APIs and shell
redirected exports as potentially sensitive.

## SDK exports

SDKs call the underlying API directly; they do not invoke `tap-agent`. Start/stop a named
range through `client.log_recordings` (Python) or `client.logRecordings` (Kotlin). After your
script releases its connection, a fresh client can export its original connection ID or
named recording without attaching a device:

```python
from tap_e2e import TapClient

with TapClient.create() as client:
    evidence = client.log_recordings.export("checkout", logs=True)
    print(evidence.text)
    document = evidence.to_dict()
    json_text = evidence.to_json()
```

```kotlin
val evidence = client.logRecordings.export("checkout", logs = true)
println(evidence.text)
val jsonText = evidence.toJson()
```

Use `history(original_connection_id, logs=True)` in Python or
`history(originalConnectionId, logs = true)` in Kotlin for ordinary retained activity without
a protected name. SDK-only recordings have RPC events but no fabricated agent steps. Both
SDKs return `tap-history/1`; choosing text or JSON changes presentation, not capture fidelity.
Python history accepts `from_time` / `until_time` as finite epoch seconds; Kotlin accepts
`fromTime` / `untilTime` as `Instant` values. Both filter all entry kinds by host receipt time.
Keep evidence/export failures separate from the script's original result, and never retry a
mutation to repair a recording.

## What cannot be recovered afterward

History is not an unrestricted transcript. Successful query return values and generic human
error output/driver last-state snapshots are not fully retained. Historical screenshots and
hierarchies require explicit `screenshot` / `capture` artifacts; video/audio must start before
the incident and be finalized separately. Transient accessibility-event observations are not
a durable history stream yet. Full ANR thread dumps, inaccessible Android files and
symbolication are outside the public death source's coverage. External app/device changes are
not continuously observed just because Tap is attached.

No flag can recover uncaptured, expired, inaccessible or intentionally withheld evidence.
Marked secrets, clipboard data, unclassified binary payloads and unknown wire fields remain
privacy exclusions. For deeper debugging, collect explicit artifacts/app logs and keep their
privacy and capture prerequisites in mind; a complete retained snapshot is not proof that all
possible diagnostic information existed.
