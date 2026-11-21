# History and recordings

The server keeps what every session did automatically: its calls, the crashes and ANRs on its
devices, capture gaps and each attachment's device log. `tap-agent history` reads it, merged with
the agent's own steps. A **recording** keeps a named range of it past rolling eviction. Neither
starts a second capture or replays anything.

## Read history

```sh
tap-agent history
tap-agent history --logs
tap-agent history --last 30s --logs
tap-agent history --last 5m --details --json > incident.json
tap-agent history --from 2026-11-17T09:00:00Z --until 2026-11-17T10:00:00Z --logs
tap-agent history --since-marker checkout --until-marker paid --logs
tap-agent history --step 3
```

A window that ends now (no start, `--last`, `--last-action`) shows its newest entries first
(up to `--limit`, default 200), printed oldest first as readable text; its last line
`older: --after POSITION` reads the page before, so the crash at the end of a busy window is on
the first page. An explicit start (`--from`, `--since-marker`, `--step`) reads forward and
prints `next: --after POSITION`. `--after` takes no other bound or filter: the position carries
them, `--device` included. In `--json` the position is `next`. `--json` returns `tap-history/2` on stdout; nothing writes an implicit file.

`--limit` counts the server's entries of one timeline merged across devices and the connection,
ordered by the time the server received them. A step replaces its calls with one line, such as
`step 2: tap text("Pay") & app("com.shop") count=2 → succeeded` (the target as a test would
write it, options only when they differ from the default), on the page that has its first call.
A step that made no call (refused before input) and capture bundles are placed by time, on the
one page whose time slice holds them, so a page can show a few lines more or fewer than
`--limit`. Markers you wrote with `history mark` are shown without `--logs`. Only actions and
explicit waits are steps; internal polls and diagnostic snapshots are not promoted into
invented intent. A page is a bounded view, not proof that nothing else happened.

## Bounds and time windows

`--from` and `--until` take ISO 8601 times with a zone; `--from` is inclusive, `--until`
exclusive. `--last 30s` is the window ending when the command starts (`500ms`, `30s`, `5m`) and
takes no other start or `--until`. Markers (`history mark NAME`) are resolved by the server
among this session's connections: each device's newest marker with that name, the marker
included by `--since-marker` and by `--until-marker`. `--step` reads one step: its calls, the
crashes and gaps while they ran and, with `--logs`, its device-log span.

Time bounds read **everything the server kept in the window**, also when no agent step ran, no
local history exists or the session was released. Windows use the server's **receipt clock**:
an RPC that started before the window but finished inside it is included, and JSON keeps its
own `at` next to the entry's receipt time. Crashes keep the device's time separately; delayed
Android delivery is not guessed to be instantaneous.

## Crashes and ANRs

Crashes and ANRs are independent durable observations, including background deaths with no
active step and deaths of unwatched apps. They appear as explicit **CRASH** / **ANR** entries
in text and `death` entries in JSON for every history read, recordings included, with or
without `--logs` or log filters. The read's bounds (times, markers, `--step`) and retention
still apply. The original attachment ID disambiguates reattachments of the same serial.

Known diagnostic messages and available Java/native stacks are redacted before storage.
Default text shows a compact summary; `--details` adds retained stack traces. JSON always
contains available redacted details, so no verbose-capture mode had to be enabled before an
unexpected incident. The SDK equivalent is `page.render_text(details=True)`. An empty `stack_trace` means the
source supplied no stack; full ANR thread dumps are not promised by the public logcat source. Interrupted death
followers and failed catch-up reads leave explicit evidence gaps.

## Recordings

```sh
tap-agent history keep start checkout
tap-agent tap text=Pay,pkg=com.shop
tap-agent wait text=Receipt,pkg=com.shop
tap-agent history keep stop checkout
tap-agent release
tap-agent history --kept checkout
tap-agent history --kept checkout --details
tap-agent history --kept checkout --logs --json > checkout.json
```

`history keep start NAME` keeps the session's history from now on: its calls, crashes and the device
logs of every device attached to it, including devices attached after the start. `history keep stop
NAME` ends the range; it does not release devices, and `release` stops an open recording.
Reading a live recording is allowed and shows what it has so far. `history --kept NAME`
takes every other flag, including bounds inside the range.

```sh
tap-agent history keep list
tap-agent history keep list -L
tap-agent history keep clean checkout
```

Names are per session: another session may use the same name. Outside the session that made it,
`--kept` needs the recording's id (printed by `history keep list -L`) when the name is not unique.
`-L` adds state, times, the id and retained disk bytes; ranges can share stored segments, so the
bytes are not what cleaning one will reclaim. `history keep clean NAME` deletes a stopped recording;
its range then expires like the rest. Recordings bypass rolling eviction, not hard storage or
free-space limits. `media-record` is separate video/audio capture.

## What survives release and restart

The server stores redacted calls alongside device logs, under the same retention policy, and
reads them back without a live connection or attachment. SDK scripts and agent sessions can be
inspected after release or a server restart while their history is kept.

Agent steps are stored privately in `$TAP_AGENT_DIR/records` (default
`.tap/agent/records`); CLI and MCP processes must share it. Missing agent steps are never
reconstructed from calls. When the server cannot be reached, `history` still shows the local
steps with an `unavailable` warning. `history keep` needs the server.

## Read the evidence honestly

History keeps the original connection, attachment, step and clock identities, resolved
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
History generates no tests and invents no checks.

Check the gaps and the `issues`. A **gap** entry marks where capture was interrupted. An
**issue** is missing evidence with no place in time, printed as `WARNING:`: `expired` (retention
removed entries the read asked for; JSON has their count when the server knows it), `unavailable` (a source could not be read), `incomplete` (a
recording's capture failed, such as a storage limit) or `package_unknown` (a package filter the
server cannot resolve). No issues is not a passing test.

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

## From an SDK script

SDKs call the server directly; they do not invoke `tap-agent`. In Python, `client.history`
starts and stops recordings and reads history after the script released its connection, with
no device attached:

```python
from tap_e2e import TapClient

with TapClient.create() as client:
    (recording,) = [r for r in client.history.recordings() if r.name == "checkout"]
    page = client.history.read(recording=recording, logs=True)
    print(page.text)
    json_text = page.to_json()  # tap-history/2
```

`read(connections=[connection_id], logs=True)` reads a connection's history without a
recording; `since=` / `until=` take times (epoch seconds, a `datetime` or an ISO 8601 string)
and `since_marker=` / `until_marker=` marker names. SDK history has calls but no agent steps. The Kotlin SDK has no history reader yet.
Keep history-read failures separate from the script's own result, and never retry a mutation
to repair a recording.

## One bundle: capture

`capture` saves the device's state now (screenshot, full hierarchy, device info, driver log,
logcat, crashes, activity stack, windows) and its history, in one new private directory:

```sh
tap-agent capture                 # all the history that is kept
tap-agent capture --last 10m -o evidence/checkout-failure
```

`<serial>.snapshot.txt` is the hierarchy as the outline `snapshot --all --keyboard` prints, with
the refs the agent uses, next to the full XML (like any snapshot, it is the baseline the next
`--settle` diff compares with). `<serial>.history.txt` is what `history --logs --details` prints
for this session on the device, every page in one file; `<serial>.history.json` is the same as one `tap-history/2`
document. A part that cannot be read (the history too) is listed as failed and the others are
still saved. The current screen is perishable, so when it matters take a `capture` first;
history stays readable afterward.

```sh
tap-agent capture checkout        # a named bundle; its first line says where it went
tap-agent capture list -L         # newest first: time, name or id, device, size, folder
tap-agent capture show checkout   # the folder and each file
tap-agent capture clean checkout  # or --older-than 2h, or --all
```

`clean` deletes only the files the agent saved and then the folder; a folder that also holds
something else is left as it is, with a message naming what is in the way. History keeps showing
a cleaned capture, marked cleaned. A name labels one bundle until it is cleaned.

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
