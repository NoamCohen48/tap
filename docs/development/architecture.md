# Architecture

Tap has three parts: a **driver** on each device, one **server** per machine, and thin
**clients** in each test language. This page explains what each part does, how a command
travels between them, and the rules the whole design is built around.

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

**`tap-agent`** is a client too, built on the Python one. It adds what a coding agent needs on
top: sessions that outlive a single command, screen snapshots whose refs name selectors the
server has checked, and an export of everything the session did.

**`tap-studio`** is another client on the Python one: a local web server and a browser page
(React) that show the device's screen with its elements and record what the user does as
`tap-recording/1` steps. Page and back end share one schema, `clients/studio/proto/studio.proto`.

## Where each part lives

| Part | Directory | Gradle modules / packages |
|---|---|---|
| Contracts | `contracts/` | `contracts/proto` is the one protobuf schema. `:contracts:schema` (messages), `:contracts:api` (gRPC stubs), `:contracts:protocol` (TAP1 framing, handshake, validation) |
| Driver | `device/driver/` | `:device:driver` (the instrumentation APKs), `:device:driver:core` (Android code), `:device:driver:command-engine` (pure-JVM command pipeline) |
| App library | `device/sync-sdk/` | `:device:sync-sdk`, an optional library an app can ship to report when it is busy (experimental) |
| Server | `host/` | `:host:core` (ADB, sessions, journals, driver client, app lifecycle), `:host:daemon` (the `tap` server and CLI), `:host:validation` (device fault-injection suite) |
| Clients | `clients/` | `:clients:kotlin:sdk`, `:clients:kotlin:junit5`, `clients/python` (`tap-e2e`), `clients/agent` (`tap-agent`), `clients/studio` (`tap-studio`) |
| Test app | `fixture-app/`, `samples/` | `:fixture-app`, the app the suites run against; `:samples:fixture-tests`, the Kotlin client suite |

Dependencies only point one way: clients depend on `:contracts:api` and nothing else, and
nothing under `host/` depends on `clients/`.

## Two protocols

- **`tap.v1`** is the gRPC API between clients and the server, defined in
  `contracts/proto/*.proto`. It is the contract every client implements; see the
  [gRPC reference](../reference/grpc.md). A client in another language needs only this.
- **TAP1** is the framed protocol between the server and the driver, over a socket that ADB
  forwards to the device. Its payloads (`tap.wire.v1`) live in `contracts/proto/wire/`. Each
  connection starts with an authenticated handshake that checks the protocol version, the
  driver build and its capabilities.

Both are versioned and only grow: fields and values are added, never removed, renumbered or
retyped, and CI checks that with `buf breaking`.

## Client connections, roles, and attached devices

- A **client connection** is a test process's identity at the server. It keeps an `Observe`
  stream open; if the process dies, the server disconnects it and detaches all devices it owns.
- A device is in use exactly while its host-core **device session** holds the per-serial lock,
  which the OS releases if the daemon dies; there is no separate lease to acquire. Attaching a
  busy device fails at once, or waits if you ask it to. **Roles** (`"device"`, or
  `"sender"`/`"receiver"`) exist only in the clients: the JUnit extension and pytest plugin
  decide which serial plays which role and attach devices in sorted serial order, which makes
  concurrent multi-device tests deadlock-free.
- An **attached device** is the server-facing handle for one device; it names no app, each
  app call and selector names its package. The
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
| **No implicit waits.** Actions do not wait for animations, idleness or "the screen to settle". | Those waits cost time on every step and never converge on live screens (tickers, spinners). Ask for the wait you mean: [Waits](../guide/waits.md). |
| **No retries, ever.** | A retried tap is a double tap. If the transport drops after a mutation was accepted, you get `INDETERMINATE`, not a guess. |
| Every lookup searches **all visible windows**; `app("pkg").element(…)` adds a package predicate, `screen.element(…)` adds none. A touch point another window covers fails as `NOT_INTERACTABLE` / `OBSCURED` before any input. | A test says which app's node it means; a dialog, keyboard or shade over the node is reported instead of receiving the tap. |
| No hierarchy dump on the hot path. | Dumps are diagnostic (`dumpHierarchy()`, failure artifacts); matching runs on the device with window-scoped UiAutomator lookups. |

## What the driver bounds for you

UiAutomator normally waits up to 10 s for the UI to go idle before *every* interaction; on a
screen that never idles (a progress spinner) that stalls every command. The Tap driver caps
that to 1 s, so a busy screen slows a command by at most one second, and gives you the
explicit [`app.awaitSettled()` / `app.awaitAnimationEnd()`](../guide/waits.md#the-app-or-the-screen)
when you actually want to wait for quiet.

## Failure handling

When a test fails, the client captures — while the device is still attached — a PNG screenshot,
the accessibility hierarchy as XML, device info and the driver's own log, into a per-test
directory. Exceptions carry the typed error code, its stable sub-reason, the rendered selector
and the request identity, so a log line is enough to know *what* failed without re-running.
See [Errors and artifacts](../guide/errors.md).

## What Tap is not

- Not a YAML flow runner and not a WebDriver: the test language is Kotlin or Python, on
  purpose.
- Not a screenshot-comparison or visual-testing tool.
- Not iOS. The driver is UiAutomator; the server API is Android-shaped (packages, ADB).
