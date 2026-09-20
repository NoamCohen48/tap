# Pool, leases and the journal lock — what the service owns and why

Date: 2026-09-20. Decision record for the Phase 2 pool. It explains the pieces that existed
after the first cut, why two of them were removed, and why the third stays. The plan
(`android-e2e-framework-implementation-plan.md` §18) described a host-side pool with roles and
constraints; this record supersedes that section for the pool (see `CLAUDE.md`: the plan is a
design reference, not a source of truth, and changes to it need the owner's approval, which
this had).

## The three pieces the first cut had

| Piece | Where | What it was |
|---|---|---|
| **Roles + constraints** | `TapService.acquire`, proto `DeviceConstraints`/`RoleRequest`/`Assignment`/`DeviceFacts` | A test asked for named roles (`sender`, `receiver`), each with optional constraints (serial, API range, emulator, model substring); the service matched free devices to roles, most-constrained first, all-or-none. `Facts` (API level, manufacturer, model, emulator flag), gathered once per serial with `getprop`, existed to be matched against. |
| **Pool lease** | `TapService.leases: serial → Leased(run)`, proto `Acquire`/`Release` | An in-memory reservation: "run X owns this serial until it releases it or its liveness stream drops". `Open` refused a serial the run had not leased; `Release` refused a serial with an open session. |
| **Journal lock** | `SessionJournalStore.acquireLease()` in `:host:core` | `FileChannel.tryLock()` on `~/.tap/sessions/<serial>.lock`, taken by `DeviceSession.open` and held until close. |

## Decision 1 — roles and constraints leave the service (commit e7d6004)

The service should be a simple executor over **serials**. Which device plays which part in a
test is the runner's business, and the runners (JUnit extension, pytest plugin) already had
the role names; they now map roles to serials themselves: `tap.device.<role>` pins, then
`tap.serials` in declaration order, otherwise the service inventory (free devices first).

`DeviceFacts` first stayed in the device list (then `Inventory`, now `ListDevices`) as information, then went too (the commit after
4213140): with no matcher there is nothing to feed, the inventory's job is "which serials
exist and are they free or quarantined", and anything richer is in the driver's `DeviceInfo`
once a session is open. It also removes a `getprop` round trip per new serial. A client that
wants to choose by API level or emulator-ness reads `getprop` itself. The removed matcher and
facts are under `archive/pool-roles/`.

Consequence to know about: with nothing configured, two processes starting at once may pick
the same free serial; the second waits for the first rather than being steered to another
device. Fixing that belongs in the client's selection, not the service.

## Decision 2 — the pool lease goes too (this change)

What the service must guarantee is "one session per device at a time, across every process on
the machine". The journal lock already guarantees exactly that, at the OS level, with no
bookkeeping. Measured against it the pool lease added:

| It added | Verdict |
|---|---|
| A reservation before the (slow) session open, so a busy device is refused at `Acquire` instead of mid-open | Marginal. `Open` takes the lock first and refuses the same way. |
| Waiting for a busy device | Useful — kept, as a bounded wait **inside `Open`** on the file lock (`OpenSessionRequest.lease_timeout_ms`, `DEADLINE_EXCEEDED` when still held). No table, no run→serial state. |
| All-or-none for multi-device "so two tests each holding one of two devices cannot deadlock" | Not the only way. Clients open their devices in **sorted serial order**; with a global lock order deadlock is impossible. Cost: the first device idles for the seconds it takes to open the second. |
| Freeing devices when the client dies | Already covered: run close tears down sessions, and the OS drops the file lock with the process. |

So the pool lease was a second, softer copy of the lock plus scheduling rules the service had
to maintain and expose (`LEASED(run)`, "acquire before open", "close before release"). It is
gone: the device service only lists devices; the connection (then `Run`) has no leases;
`CloseConnectionResponse.devices_released` is reserved. `ListDevices` still reports `LEASED` — derived by probing the lock (`isLeased()`),
with `held_by_connection` set when the holder is one of this service's own sessions. The removed
code is under `archive/pool-leases/`.

## Decision 3 — the journal lock stays

It is a different kind of thing: not scheduling, but the guard that makes the **journal**
trustworthy, and several "must not regress" invariants are built on the journal:

- **Generation is monotonic per device across processes.** "Request IDs are strictly
  increasing per session generation; old generations are rejected" only holds if the
  generation never repeats. `open` reads `prior.generation + 1` from the journal. Without a
  durable record a fresh process would restart at 1 and a leftover driver from a crashed
  process would accept its requests.
- **Serial-specific forward cleanup.** "Never `forward --remove-all`" means we must know which
  forward we own; the journal records the host port so recovery removes exactly that rule and
  force-stops exactly that driver PID.
- **Quarantine** must outlive the process that detected the problem.
- **Crash recovery**: a `CREATING`/`ACTIVE` record left behind is reconciled against the real
  device before anything starts.

All of that is read-modify-write on one file per device by whichever process opens next. Two
processes doing it concurrently would both read generation *n*, both write *n+1*, both start a
driver on the same port — the corruption the journal exists to prevent. The lock is the
cheapest guard: one `tryLock`, held for the session, released by the OS on death, nothing to
expire or reconcile. It is also the only thing that stops `host/validation` (which uses
`:host:core` directly, by design), a stray second `tap serve`, or a future non-service client
from stepping on a live session. Removing it would mean building a worse replacement.

## Decision 4 — no service-side device allow-list (September 2026)

`tap serve --serials a,b` (`ServiceConfig.allowedSerials`) is removed. It only filtered
`ListDevices`; `OpenSession` never checked it, so it was an advertising knob that looked like
access control. Device choice is the client's (`tap.serials` / `tap_serials`, Decision 1), and
isolating two groups of devices on one machine is done with two services on two `--state-dir`s.
The service offers every device ADB lists.

## Why the lock and the journal at all — compared with Appium and Maestro

Asked in September 2026: why not just kill and reinstall the driver when a connection opens,
like other tools? The honest answer separates the two mechanisms.

**The lock is essential and cheap.** Tap's model is several independent processes (a Gradle
test JVM and a pytest run, or two JVMs) sharing one machine's devices through one service.
Two processes driving one device is garbage — a second `am instrument` kills the first, and
interleaved input corrupts both tests — so something must say "this serial is taken". An OS
file lock is the smallest possible arbiter: no lease table, no expiry, no heartbeat, released
by the kernel when the holder dies.

Appium and Maestro do not have this lock because they do not have this problem: they are
**single-owner by convention**. Appium runs one session per UDID and a second session on the
same device simply kills the previous UiAutomator2 server and takes over; allocation across
processes is pushed up to Selenium Grid / DeviceFarmer / the CI's device pool. Maestro is one
CLI process that picks a device and never coordinates with another Maestro. If Tap ever became
"one process per machine", the lock would be redundant; as long as the shared service is the
model, it is the minimum.

**The journal is more than the minimum.** "Reconcile by force on open" — kill any running
driver instrumentation, remove our forward, reinstall the driver APK only when its
version/checksum differs, start fresh, remember nothing — is exactly what Appium and Maestro
do, and it works. Tap already does most of that (`recoverJournal` → `forceStopDriverAndVerify`,
forward cleanup; the driver is installed once per serial per process, not per session —
reinstalling every session would cost ~3–5 s each, prohibitive with per-test sessions). What
the journal adds beyond reconcile-on-open, ranked by how much it earns its keep:

1. **The generation counter.** The invariant "request IDs strictly increasing per generation;
   old generations rejected" needs the next session to know the previous generation. This is
   the one thing that genuinely must survive across processes. (A random 64-bit per-session
   nonce would give the same rejection property without persistence; the plan chose monotonic.)
2. **Quarantine memory.** "The previous host died mid-reset / its cleanup could not be verified"
   is something Appium cannot remember — it hands the device out and the next test fails
   mysteriously. Tap refuses the device and says why in `ListDevices`. Real value on a
   long-lived shared pool; near-zero on a CI runner whose emulator is discarded after the job.
3. **Driver identity** (`pid` / start token / instance id) distinguishes "same driver still
   running, reuse" from "stale, kill". Reconcile-by-killing reaches the same state at the cost
   of a restart.
4. **`bootId`** — a reboot makes all prior state moot; cheap and useful.

All of it descends from the implementation plan's "a crashed host must not leave a device
half-used", which is a device-farm requirement.

**Standing position.** Keep it as is — it works and the device validation flow exercises it —
but do not grow it. If the real deployment turns out to be "ephemeral CI emulators plus a
developer's local phone", the honest simplification is: keep the lock and the generation
counter (a ~50-line file), drop quarantine / reset recovery, and reconcile by force on open like
Appium. That would be a deliberate departure from the plan and needs the user's approval and a
note here before it happens.

## Resulting model

- **Service**: sessions, commands, app lifecycle, journals. `ListDevices` is a view.
- **Exclusive use** = the per-serial file lock, held by a live session. `Open` may wait for it.
- **Roles / device choice / ordering** = the client. Runners open in sorted serial order and
  skip a test when the inventory has fewer usable devices than roles.
- **Deadlock freedom** comes from lock ordering, not from an atomic multi-acquire.
- `tap.acquireTimeoutSeconds` / `tap_acquire_timeout` keep their names; they now bound the wait
  in `Open` for a device another session holds.
