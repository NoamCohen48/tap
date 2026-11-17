# Device logs

!!! info "Examples are in Python"
    Kotlin has the same calls on `device.logs`: `read(LogStart.Marker("checkout"),
    LogFilter(packages = listOf("com.shop"), minPriority = LogPriority.WARN))`, `follow(...)`
    (a `Flow<LogEntry>`), `mark(name)` and `redact(patterns)`.

From the moment a device is attached, the Tap server reads its logcat (the main, system and
crash buffers) and retains redacted evidence under a rolling daemon policy, including after
detach or restart. `device.logs` reads it during the test: app lines, Tap actions, markers and
capture gaps, in receipt order.

- **Actions**: what Tap did. Every tap, typed text, gesture and wait is logged by Tap's driver
  on the device itself, when it starts and when it finishes, so it sits exactly where it
  happened among the app's lines. App and device calls (launch, a setting) are there too.
- **Markers**: names your test writes into the log, to read from later.

```
11-14 12:01:47.600 ── marker checkout
11-14 12:01:47.636 ── tap  text="Pay"
11-14 12:01:47.879 10123  2346  2357 I Payments: charging card
11-14 12:01:48.102 10123  2346  2357 W Payments: retrying (timeout)
11-14 12:01:48.110 ── tap  → ok  243 ms
```

## Read what the app logged during a step

```python
device.logs.mark("checkout")
app.element(text("Pay")).tap()
app.element(text("Paid")).wait().visible()

log = device.logs.read(since="checkout", packages=["com.shop"], min_priority="warn")
assert not log.lines, log.text
```

`read` returns the entries so far, oldest first. Each `LogEntry` has a `cursor`, the server's
time `at`, and exactly one of `line` (a logcat line: `priority`, `tag`, `message`, `uid`, `pid`,
`tid`, `device_time`, and `packages`: the apps installed under its uid, none for a system
process), `action`, `marker` or `gap`. `device_time` is the device's own clock, which can be far
from the server's; `at` is the one to line entries up with other records. `log.lines` are the logcat lines,
`log.messages` their messages, `log.text` everything as text: one line per entry, led by `at`
in local time (`MM-DD HH:MM:SS.mmm`), then uid, pid, tid, priority, tag and message, like
`logcat -v threadtime`. `entry.summary` is an entry without its time.

Where a read starts (at most one; without any, at the oldest entry the server still has):

| Argument | Starts at |
|---|---|
| `since="checkout"` | the newest marker with that name (an unknown name is an error) |
| `since=entry` or `since=entry.cursor` | what came after that entry |
| `tail=50` | the newest 50 entries that pass the filters |
| `since_last_action=True` | the start of the newest action |

`until` ends the read there instead of now, that entry included: a marker's name (the newest
marker with it among the entries read), a cursor or an entry. `read(since="checkout",
until="paid")` is the stretch between two markers; a marker that is not there raises
`ValueError`.

Filters keep the matching logcat lines; markers, actions and gaps always stay:

| Argument | Keeps |
|---|---|
| `packages=["com.shop"]` | lines of these apps (all their processes, across restarts) |
| `min_priority="warn"` | lines at least this severe (`verbose`, `debug`, `info`, `warn`, `error`, `fatal`) |
| `tags=["Payments"]` | lines with one of these tags |
| `grep="order \\d+"` | lines whose message matches the regular expression (Java syntax) |
| `actions=False` | (leaves Tap's actions out) |

## Match your own steps to the log

When your code keeps its own record of what it did (a test runner's report, a tool's history),
run each unit of work as a step. The server writes the step's id on every action the step
caused, and a read can ask for exactly that slice:

```python
import tap_e2e

with tap_e2e.step() as checkout:          # or step("checkout-1"): 1 to 64 of A-Z a-z 0-9 . _ -
    cart.tap()
    pay.tap()

log = device.logs.read(step=checkout)     # from the step's first action to its last
for entry in log:
    if entry.action:
        assert entry.action.step == checkout
```

In Kotlin: `val checkout = withStep { id -> cart.tap(); pay.tap(); id }` and
`device.logs.read(filter = LogFilter(step = checkout))`. A step covers the calls made on the
thread (Kotlin: the coroutine) that opened it; the same id is on the calls' events in the
connection's event log.

## Wait for a line

`follow` takes the same arguments and keeps going: it yields the entries so far, then every new
one as it is logged, until you close it or the device is detached.

```python
with device.logs.follow(since="checkout", tags=["Payments"]) as log:
    settled = next(e for e in log if e.line and "settled" in e.line.message)
```

Give such a wait its own time limit (a `pytest-timeout`, a thread that calls `log.close()`): a
line that never comes is not an error by itself.

## Retention and protected recordings

The default policy keeps **one hour / 256 MiB globally**, with a **1 GiB hard log-storage
budget** and **256 MiB free-disk reserve**. Configure the selected daemon state directory:

```sh
tap config logging-policy show
tap config logging-policy rolling --max-age 1h --max-size 256MiB --hard-size 1GiB --min-free 256MiB
```

Add `--state-dir DIR` to either command for a separate daemon. Settings work while stopped;
a running daemon applies them on its next append/read or maintenance pass (within a minute),
without restarting logcat. See [Server configuration](server.md#device-log-retention).

For an intentional reproduction, protect a named range instead of hoping it stays in rolling
history. Using an open `client`, its `connection` and an attached `app`:

```python
client.log_recordings.start(connection, "checkout")
app.element(text("Pay")).tap()
app.wait(text("Paid")).visible()
client.log_recordings.stop("checkout")
connection.close()

# The daemon still runs; the original connection no longer needs to exist.
evidence = client.log_recordings.export("checkout", logs=True)
print(evidence.text)  # durable RPC events and device logs
json_text = evidence.to_json()  # tap-history/1; no implicit file
for serial, entries in client.log_recordings.read("checkout").items():
    print(serial, entries.capture_error, entries.text)
client.log_recordings.delete("checkout")  # explicitly remove protection
```

Kotlin exposes the same operations through `client.logRecordings`, including
`export("checkout", logs = true)` returning `.text` / `.toJson()`. For ordinary post-script
evidence use Python `history(original_connection_id, logs=True)` or Kotlin
`history(originalConnectionId, logs = true)`; no device reattachment or CLI is needed. See
[history and recordings](../agent/export.md#sdk-exports).

A recording protects the
attachment set present at start. It does not restart capture, record video or automatically
replay a flow. Boundaries are receipt cursors: a logcat line delivered after stop may fall
outside the range even if the app emitted it earlier. Complete capture is not a passing test.

After an unexpected failure, ordinary retained logs can be discovered without reattaching:

```python
for archive in client.log_recordings.archives():
    entries = client.log_recordings.read_archive(archive, min_priority="warn")
    print(archive.serial, entries.dropped, entries.capture_error, entries.text)
```

Pass the original connection id to `archives(connection_id)` to narrow discovery. Retained
reads support tag, priority and message filters; package-name filtering needs a live
attachment. SDK `read` methods collect their results, so narrow filters for large recordings.

Protected ranges bypass rolling eviction, not hard disk limits. Storage failures preserve
older evidence and report `capture_error`; missing entries are counted in `dropped` before
user filtering. A stopped recording stays protected until explicitly deleted. An interrupted
open recording becomes stopped and incomplete on daemon restart. Reattach to resume durable
capture after a storage failure; raising a limit does not recover the missing interval.

## Secrets

Logs are redacted before disk storage and again when read: a match is replaced by `<redacted>`.
Without doing
anything, that covers `Authorization` and `Cookie` style headers, `password=` / `token=` /
`api_key=` style values, bearer tokens, JWTs, and whatever you typed with `sensitive=True`.
Add your own patterns for anything else:

```python
device.logs.redact(r"session=(\w+)", r"\b4\d{15}\b")
```

A match is replaced whole; a pattern with a group has only the group replaced, so `session=`
stays readable. A new pattern also applies to lines logged before it was added, and to the
failure artifacts. Late rules rewrite retained segments; a failed rewrite is reported and
blocks that archive's retained reads. Previously unrecognized values may have been on disk
before a rule was added, and earlier exports/backups cannot be securely erased this way.
Sensitive typed literals shorter than four characters are not registered automatically.
Redaction hides known matches, not every possible app secret; keep storage private and inspect exports.

## Limits

- Disk retention follows the global policy above. Reads preserve the original `clock_id`;
  monotonic timestamps from different clock ids are not comparable.
- Live followers and failure-artifact previews start from a bounded 16 MiB hot buffer. Read
  retained history separately for older evidence. Tail selections exceeding 16 MiB fail with
  `RESOURCE_EXHAUSTED` rather than silently truncating the requested selection.
- `log.dropped` includes unavailable entries inside the requested interval, not only an evicted
  prefix; tail reads intentionally report zero. Logcat may drop lines before Tap reads them,
  so zero does not prove that Android emitted no additional lines.
- When the device disconnects or logcat stops, the log has a `gap` entry saying why, and the
  capture resumes.
- `attach_device(serial, capture_logs=False)` (Kotlin `DeviceOptions(captureLogs = false)`)
  turns the capture off; `device.logs` calls then fail, and failure artifacts hold what the
  device's own log buffers still have. A protected recording refuses an opted-out attachment;
  reattach with capture enabled before starting it.
- The pytest plugin and the JUnit extension write `test <name>` and `test passed <name>` /
  `test failed <name>` markers for each test.
