# Device logs

!!! info "Examples are in Python"
    Kotlin has the same calls on `device.logs`: `read(LogStart.Marker("checkout"),
    LogFilter(packages = listOf("com.shop"), minPriority = LogPriority.WARN))`, `follow(...)`
    (a `Flow<LogEntry>`), `mark(name)` and `redact(patterns)`.

From the moment a device is attached, the Tap server reads its logcat (the main, system and
crash buffers) and keeps it for as long as the device stays attached. `device.logs` reads it
back during the test: the app's lines in order, with two more kinds of entry in between.

- **Actions**: what Tap did. Every tap, typed text, gesture and wait is logged by Tap's driver
  on the device itself, when it starts and when it finishes, so it sits exactly where it
  happened among the app's lines. App and device calls (launch, a setting) are there too.
- **Markers**: names your test writes into the log, to read from later.

```
── marker checkout
── tap  text="Pay"
1794608507.879 10123  2346  2357 I Payments: charging card
1794608508.102 10123  2346  2357 W Payments: retrying (timeout)
── tap  → ok  243 ms
```

## Read what the app logged during a step

```python
device.logs.mark("checkout")
app.element(text("Pay")).tap()
app.element(text("Paid")).wait.visible()

log = device.logs.read(since="checkout", packages=["com.shop"], min_priority="warn")
assert not log.lines, log.text
```

`read` returns the entries so far, oldest first. Each `LogEntry` has a `cursor`, the server's
time `at`, and exactly one of `line` (a logcat line: `priority`, `tag`, `message`, `uid`, `pid`,
`tid`, `device_time`, and `packages`: the apps installed under its uid, none for a system
process), `action`, `marker` or `gap`. `device_time` is the device's own clock, which can be far
from the server's; `at` is the one to line entries up with other records. `log.lines` are the logcat lines,
`log.messages` their messages, `log.text` everything as text.

Where a read starts (at most one; without any, at the oldest entry the server still has):

| Argument | Starts at |
|---|---|
| `since="checkout"` | the newest marker with that name (an unknown name is an error) |
| `since=entry` or `since=entry.cursor` | what came after that entry |
| `tail=50` | the newest 50 entries that pass the filters |
| `since_last_action=True` | the start of the newest action |

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

## Secrets

What you read is redacted by the server: the match is replaced by `<redacted>`. Without doing
anything, that covers `Authorization` and `Cookie` style headers, `password=` / `token=` /
`api_key=` style values, bearer tokens, JWTs, and whatever you typed with `sensitive=True`.
Add your own patterns for anything else:

```python
device.logs.redact(r"session=(\w+)", r"\b4\d{15}\b")
```

A match is replaced whole; a pattern with a group has only the group replaced, so `session=`
stays readable. A new pattern also applies to lines logged before it was added, and to the
failure artifacts. Redaction hides what matches a pattern: a secret in a shape nobody described
is still in the log, so treat saved logs as sensitive.

## Limits

- The server keeps the newest 16 MiB per attached device and forgets them at detach. A read
  says how many older entries were already gone (`log.dropped`).
- When the device disconnects or logcat stops, the log has a `gap` entry saying why, and the
  capture resumes.
- `attach_device(serial, capture_logs=False)` (Kotlin `DeviceOptions(captureLogs = false)`)
  turns the capture off; `device.logs` calls then fail, and failure artifacts hold what the
  device's own log buffers still have.
- The pytest plugin and the JUnit extension write `test <name>` and `test passed <name>` /
  `test failed <name>` markers for each test.
