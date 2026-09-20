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
| **Roles + constraints** | `TapService.acquire`, proto `DeviceConstraints`/`RoleRequest`/`Assignment` | A test asked for named roles (`sender`, `receiver`), each with optional constraints (serial, API range, emulator, model substring); the service matched free devices to roles, most-constrained first, all-or-none. `Facts` (API level, manufacturer, model, emulator flag), gathered once per serial with `getprop`, existed to be matched against. |
| **Pool lease** | `TapService.leases: serial → Leased(run)`, proto `Acquire`/`Release` | An in-memory reservation: "run X owns this serial until it releases it or its liveness stream drops". `Open` refused a serial the run had not leased; `Release` refused a serial with an open session. |
| **Journal lock** | `SessionJournalStore.acquireLease()` in `:host:core` | `FileChannel.tryLock()` on `~/.tap/sessions/<serial>.lock`, taken by `DeviceSession.open` and held until close. |

## Decision 1 — roles and constraints leave the service (commit e7d6004)

The service should be a simple executor over **serials**. Which device plays which part in a
test is the runner's business, and the runners (JUnit extension, pytest plugin) already had
the role names; they now map roles to serials themselves: `tap.device.<role>` pins, then
`tap.serials` in declaration order, otherwise the service inventory (free devices first).

`DeviceFacts` stays in `Inventory` as **information**, not a filter: a client that wants an
emulator or an API ≥ 33 device reads the inventory and chooses. No client does that yet
(`framework-gaps.md`). The removed matcher is under `archive/pool-roles/`.

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
gone: `PoolService` has only `Inventory`; `Run` has no leases; `CloseRunResponse.devices_released`
is reserved. `Inventory` still reports `LEASED` — derived by probing the lock (`isLeased()`),
with `leased_by_run` set when the holder is one of this service's own sessions. The removed
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

## Resulting model

- **Service**: sessions, commands, app lifecycle, journals. `Inventory` is a view.
- **Exclusive use** = the per-serial file lock, held by a live session. `Open` may wait for it.
- **Roles / device choice / ordering** = the client. Runners open in sorted serial order and
  skip a test when the inventory has fewer usable devices than roles.
- **Deadlock freedom** comes from lock ordering, not from an atomic multi-acquire.
- `tap.acquireTimeoutSeconds` / `tap_acquire_timeout` keep their names; they now bound the wait
  in `Open` for a device another session holds.
