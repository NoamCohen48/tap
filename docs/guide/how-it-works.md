# How it works

```
 your test process(es)                 one per machine                       per device
┌────────────────────────┐   gRPC     ┌───────────────────────────┐  ADB    ┌──────────────────────┐
│ Kotlin SDK + JUnit 5   │◄──────────►│ tap daemon               │◄───────►│ Tap driver           │
│ Python + pytest        │ loopback   │  client connections       │ forward │  (UiAutomator, own   │
│ (thin gRPC clients)    │            │  attached devices         │  TAP1   │   package)           │
└────────────────────────┘            │  ADB, driver lifecycle    │         │  ┌────────────────┐  │
                                      │  bundled driver APKs      │         │  │ app under test │  │
                                      └───────────────────────────┘         │  └────────────────┘  │
                                                                            └──────────────────────┘
```

## Three parts

**The driver** is an instrumentation package installed next to your app. It runs UiAutomator
and serves a small framed-JSON protocol (TAP1) over a socket that the server forwards through
ADB. It is a *separate* package, so it survives your app being force-stopped, cleared or
reinstalled mid-test. It keeps no element handles between commands: every command carries a
selector, the driver resolves it against the accessibility tree right then, acts, and answers.

**The server** (`tap start`) is the only process that talks to ADB. It installs the bundled
driver, forwards ports, attaches devices, verifies the driver's identity on every handshake,
journals what it does (so a crashed host can recover or quarantine a device instead of leaving
it half-used) and lists the devices. One server per machine serves every test process,
in every language, at once. It listens on loopback and is started explicitly (`tap start`, or
by a test runner told to manage it); clients only ever connect to a running one.

**The clients** are gRPC clients of the server's `tap.v1` API. They hold no device logic:
the Kotlin `Device`/`Element` and the Python `Device`/`Element` build the same protobuf
`Selector` and `Command` messages, so a selector that works in one works in the other.

## Client connections, roles, and attached devices

- A **client connection** is a test process's identity at the server. It keeps an `Observe`
  stream open; if the process dies, the server disconnects it and detaches all devices it owns.
- A device is in use exactly while its host-core **device session** holds the per-serial lock,
  which the OS releases if the daemon dies; there is no separate lease to acquire. Attaching a
  busy device fails at once, or waits if you ask it to. **Roles** (`"device"`, or
  `"sender"`/`"receiver"`) exist only in the clients: the JUnit extension and pytest plugin
  decide which serial plays which role and attach devices in sorted serial order, which makes
  concurrent multi-device tests deadlock-free.
- An **attached device** is the server-facing handle for one device and app under test. The
  JUnit extension and pytest plugin attach one per role before each test and detach it after —
  an attachment never outlives a test. Its underlying device session carries a *generation*:
  after any loss or restart the driver is rebuilt under a new generation and requests from the
  old one are rejected.

## Commands

Every action is one round trip: client → server → driver → back. The driver executes
commands strictly one at a time per device, with a bounded queue, a deadline per command and
cooperative cancellation. The important rules:

| Rule | Why |
|---|---|
| Mutations (`tap`, `setText`, `swipe`, …) need **exactly one** match; `AMBIGUOUS`/`NOT_FOUND` are returned before any input. | A test that "happens to hit the first button" is not a test. |
| **No implicit waits.** Actions do not wait for animations, idleness or "the screen to settle". | Those waits cost time on every step and never converge on live screens (tickers, spinners). Ask for the wait you mean: [Actions and waits](actions-and-waits.md). |
| **No retries, ever.** | A retried tap is a double tap. If the transport drops after a mutation was accepted, you get `INDETERMINATE`, not a guess. |
| Selectors are **scoped to the app under test**. | A stray system dialog cannot be tapped by accident; you opt in per selector for allowlisted system packages (permission dialogs). |
| No hierarchy dump on the hot path. | Dumps are diagnostic (`dumpHierarchy()`, failure artifacts); matching runs on the device with window-scoped UiAutomator lookups. |

## What the driver bounds for you

UiAutomator normally waits up to 10 s for the UI to go idle before *every* interaction; on a
screen that never idles (a progress spinner) that stalls every command. The Tap driver caps
that to 1 s, so a busy screen slows a command by at most one second, and gives you the
explicit [`awaitAppSettled()` / `awaitAnimationEnd()`](actions-and-waits.md#waiting-for-the-app-or-the-screen)
when you actually want to wait for quiet.

## Failure handling

When a test fails, the client captures — while the device is still attached — a PNG screenshot,
the accessibility hierarchy as XML, device info and the driver's own log, into a per-test
directory. Exceptions carry the typed error code, its stable sub-reason, the rendered selector
and the request identity, so a log line is enough to know *what* failed without re-running.
See [Errors and artifacts](errors.md).

## What Tap is not

- Not a YAML flow runner and not a WebDriver: the test language is Kotlin or Python, on
  purpose.
- Not a screenshot-comparison or visual-testing tool.
- Not iOS. The driver is UiAutomator; the server API is Android-shaped (packages, ADB).
