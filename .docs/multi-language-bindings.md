# Multi-Language Bindings and the Host Daemon

Date: 2026-09-18

Status: implemented on 2026-09-19 along the lines recommended below (option 2.3, proxy
variant) — see `server-api.md` for the contract, `:host:daemon` and `clients/python/` for the
code, and §7 for what was decided differently from the sketch. The rest of this document is
the analysis behind the "Python (or any second) binding" requirement, kept so the
trade-offs stay visible. The design doc already anticipated this
(`android-e2e-framework-design.md` §8: "Kotlin first; Python is the most likely second
binding once the Kotlin API and protocol are proven"). The Kotlin API and protocol are now
proven on two API levels (`phase-1-progress.md`), so the question is open.

## 1. What a binding has to cover

The device side never changes: the wire protocol (`protocol-contract.md`) is
language-neutral by design. All the work is on the host, and the host is four layers of very
different difficulty. Sizes are the current Kotlin line counts.

| Layer | Kotlin | Port effort | Notes |
|---|---:|---|---|
| Wire client — framing, canonical JSON, HMAC handshake, request IDs, reader thread, blob reassembly (`DriverClient`, `BlobReceiver`, `FrameCodec`, `Authentication`) | ~800 | 2–3 days | Mechanical. The golden fixtures under `contracts/protocol/src/test/resources/golden` are a ready-made conformance suite. |
| Test DSL — `Device`, `App`, `Element`, waits, selector builders, exceptions (`:clients:kotlin:sdk`) | ~570 | ~2 days | Thin; builds JSON. |
| Runner integration — roles, device pool, configuration, failure artifacts (`:clients:kotlin:junit5` → a pytest plugin) | ~350 | 2–3 days | Fixtures plus an `xdist`-safe pool. |
| **Session infrastructure** — ADB control plane, driver start with retry, port forwarding, `/proc` start-token process identity, fsync'd journals, machine-wide leases, orphan recovery, quarantine (`Adb`, `DriverLifecycle`, `DeviceSession`, `SessionJournal`) | ~750 | **1–2 weeks, and the risk** | This is the code the Phase 0 fault scenarios exist for. A second copy means every invariant in `CLAUDE.md` is enforced twice and every lifecycle fix lands twice; the destructive validation flow would need a Python twin to prove it. |

"A Python client" is therefore about one week. "A Python client as safe as the Kotlin one"
is three to four weeks plus a permanent double-maintenance cost. That cost is the reason not
to port the bottom layer.

## 2. Options

### 2.1 Full port

Everything in §1 rewritten in Python. Rejected for the reason above: the bottom layer is
the part that must not drift between implementations, and it is also the part that is only
provable with the destructive device flow.

### 2.2 Session service on the device

"Put the session logic in the driver so every language just talks to the device." Rejected
because the hard part of the session layer is exactly what the device cannot do for itself:

| Responsibility | Can the device own it? |
|---|---|
| Install or upgrade the driver APKs | No — nothing is running yet. |
| Start the instrumentation, retry on a busy port, parse `TAP_READY` | No — it *is* the thing being started. |
| `adb forward` to the driver's port | No — host-side ADB state. |
| Detect an orphaned driver from a crashed host run, prove its identity (PID + start token), remove *its* forward, quarantine when unsure | Partly. The driver self-kills on heartbeat expiry, but only the host can prove which process it left behind and which forward belongs to it. |
| Machine-wide lease so two test runs on one workstation do not share a device | No — a contract between host processes. |
| Reboot recovery, boot-ID checks, late-mutation quarantine | No — the device loses everything on reboot; the journal must live on the host. |
| Multi-device pool, all-or-none acquisition | No — cross-device by definition. |

A device-side service would not shrink a binding at all: it would still need the ADB
bootstrap, journal, forwarding, and the full wire client.

What *can* move to the device, as a separate follow-up: the ADB-side parts of `App`.
`UiAutomation.executeShellCommand` can run `am start`, `am force-stop`, `pm clear`,
`pm grant` from the instrumentation (this is what Appium UiAutomator2 does), so
`launch`/`forceStop`/`clearData`/`grantPermission` could become protocol operations
(`APP_LAUNCH`, `APP_FORCE_STOP`, …) and process identity could be reported by the driver
instead of read over ADB. That shrinks the host `App` layer for every language, at the
usual cost of new operations (contract, golden fixtures, validation coverage). It is
orthogonal to the bootstrap problem and does not change the recommendation below.

### 2.3 Host session service (recommended)

A small Kotlin daemon, `tap serve`, that owns everything in the bottom layer — ADB, leases,
journals, driver lifecycle, the device pool — and exposes a language-neutral local API. Two
sub-variants for the command path:

- **Proxy**: the service also owns the `DriverClient` connections and forwards
  `Request`/`Response` JSON on behalf of the binding. The binding has no framing, no HMAC,
  no ADB. Cost: one extra local hop per command (sub-millisecond) and the service is in
  the hot path.
- **Hand-off**: the service opens the session and returns
  `{ hostPort, sessionId, generation, secret }`; the binding then speaks the wire protocol
  to the driver directly and the service only manages lifecycle, pool, and cleanup. Cost:
  the binding ports the ~800-line wire client (well covered by golden fixtures). Benefit:
  nothing Kotlin on the command path, and the same client works against a bare session
  during driver development.

Either way the invariants keep a single implementation, the Kotlin JUnit path should route
through the same service once it exists (one lifecycle code path, not two), and the service
also pays off for TypeScript or any later binding, a CLI, and the Phase 5 inspector.

Effort: about one week for the service, one to one and a half weeks for a Python binding
with pytest integration (proxy variant; add two to three days for hand-off).

## 3. Service API sketch

JSON-RPC over a Unix domain socket (Windows: localhost TCP with a token file). All calls
are per `runId` so the service can clean up when a client disconnects.

```text
pool.inventory()                      -> [{ serial, apiLevel, model, emulator, state }]
pool.acquire(runId, roles, constraints, timeoutMs)
                                      -> { role: serial }          all-or-none, queued
pool.release(runId, serials)
session.open(runId, serial, autPackage, driverApks?, syncAuthority?, allowedSystemPackages?)
                                      -> { sessionId, generation, hostPort, secret? }
session.execute(sessionId, request)   -> response                  (proxy variant)
session.cancel(sessionId, requestId)
session.screenshot(sessionId)         -> { path | bytes, artifact }
session.close(sessionId)              -> { cleanup: CLOSED | QUARANTINED }
```

`request`/`response` are the existing protocol JSON unchanged; the service adds nothing to
the wire contract.

### The pool

Today there are two half-pools: `DevicePool` in `:clients:kotlin:junit5` (the `tap.serials` list,
in-memory, all-or-none, visible only inside that JVM) and the `SessionJournalStore` lease
(`~/.tap/sessions/<serial>.lock`, machine-wide exclusion with no queueing, roles, or
constraints). The service merges them into one pool per machine:

- **Inventory**: devices ADB currently sees, optionally restricted by an allowlist, each
  with `DEVICE_INFO` facts gathered once (API level, model, emulator/physical).
- **State per device**: `free`, `leased(runId, role, since)`, `quarantined(reason, owner,
  expiry)` from the journal, `offline`.
- **`acquire`**: all-or-none, queued with a deadline, per-role constraints (`api >= 30`,
  `physical`, pinned serial) — the plan §18 list the current pool does not implement.
- **`release`**: after the session is closed and journaled `CLOSED`/`QUARANTINED`.
- **Recovery**: a client that disconnects without releasing (crashed test process) has its
  sessions closed and devices returned immediately, instead of on the next start as today.

Result: a Kotlin JUnit run and a pytest run on the same workstation or CI worker draw from
one pool and cannot collide, and CI sharding across processes stops needing hand-partitioned
`tap.serials`.

## 4. Packaging the service as a self-contained binary

The service depends only on `:host` + `:protocol` (kotlinx.serialization, RE2/J — pure JVM,
no coroutines, no reflection-heavy libraries), which is the easiest shape to package.

| Level | What it is | Cost | When |
|---|---|---|---|
| Fat jar | `java -jar tap-service.jar`; needs a JDK 17 on the machine | trivial (`installDist` already does the equivalent) | CI workers |
| jlink / jpackage image | directory or `.deb`/`.msi`/`.dmg` with a trimmed JVM inside, ~40–60 MB per OS, no Java install needed | half a day (`application` + `org.beryx.jlink`) | **default** |
| GraalVM native-image | one executable, ~20–30 MB, fast start | one to two days plus a GraalVM toolchain in CI and a reachability-metadata pass whenever a dependency is added | only if startup or size matters to the Python users |

Two things hold at every level:

- **`adb` is still required.** Locate it via `PATH`/`ANDROID_HOME` (what `Adb(executable)`
  does) and fail with a clear message; bundling platform-tools per OS (Maestro's approach)
  is a later option.
- **The driver APKs ship inside the binary** as resources so `tap serve` can install the
  matching driver on any device without a checkout. That couples the service build to
  `:device:driver:assembleProductDebug` / `:device:driver:assembleProductDebugAndroidTest`.

## 5. Schema for bindings

The contract is currently Kotlin data classes plus golden fixtures; a binding either
hand-writes types against the fixtures or needs a machine-readable schema. Cheapest move:
generate a JSON Schema from the protocol models and check it against every golden fixture
in `GoldenMessageTest`. Switching the wire format to protobuf would give typed clients for
free but is a protocol 2.0 decision (see the rationale section of `protocol-contract.md`).

## 6. Decisions to make before starting

1. Audience: manual testers writing simple flows (design-doc assumption) or full parity
   including multi-device? Parity → service; simple flows could tolerate a direct client
   with a reduced session layer, clearly labelled as such.
2. Proxy or hand-off command path (§2.3).
3. Synchronous or `asyncio` Python API. The Kotlin SDK is synchronous today; a synchronous
   Python API is the cheap, consistent choice, with `asyncio.TaskGroup` as the natural
   multi-device story later.
4. Whether the Kotlin JUnit path moves onto the service in the same change (recommended,
   so there is one lifecycle implementation) or later.

## 7. Outcome (2026-09-19)

Decisions taken against §6, and where the implementation departs from the sketch in §3–§5:

1. **Parity, not a reduced client.** The Python binding covers the full SDK surface
   (elements, waits, app lifecycle, multi-device) because the service makes that as cheap
   as a reduced client.
2. **Proxy command path.** The daemon owns the `DriverClient`; Python has no wire code.
3. **Synchronous API**, threads for multi-device, mirroring the Kotlin SDK.
4. **Kotlin JUnit moved onto the daemon API** (2026-09-19): `:clients:kotlin:sdk` and
   `:clients:kotlin:junit5` are gRPC clients under `clients/`, `App` lifecycle lives in
   `host/core` (`AppLifecycle`), the in-JVM `DevicePool` is gone, and the layout is
   `contracts/ device/ host/ clients/` so the host never depends on a client.
5. **gRPC + protobuf instead of JSON-RPC over a Unix socket.** The files under `contracts/proto/` are the
   source of truth for every binding; Kotlin stubs are generated at build time, Python stubs
   are committed with a `--check` script. Loopback TCP with a `daemon.json` descriptor
   replaced the socket/token file (same on every OS).
6. **The proto mirrors the protocol's selector/command model** rather than tunnelling
   opaque JSON, so bindings get typed selectors; the mirror is guarded by an enum-name test
   and a golden round-trip test against `contracts/protocol/src/test/resources/golden`.
7. **Native image first, not jlink.** GraalVM 21 builds a single ~39 MB executable in under
   a minute with committed reachability metadata; the JVM `installDist` distribution remains
   the fallback. `adb` is still required (§4).
8. **Daemon lifetime follows the ADB server model**: a started daemon stays up for other
   clients; `tap stop` ends it. Starting is explicit (`tap start`, or a test runner told to
   manage it) — clients never spawn the daemon (`.docs/daemon-startup.md`).
9. **Cancellation** is expressed as gRPC call cancellation, forwarded as a protocol `CANCEL`;
   there is no separate `session.cancel` RPC.
10. **JSON Schema (§5) was not needed**: the proto is the schema.
