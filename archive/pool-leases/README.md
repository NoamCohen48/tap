# Archived: the service-side pool lease (`Acquire` / `Release`)

Removed from the host service on 2026-09-20 (after commit e7d6004); the reasoning is in
`.docs/pool-and-leases.md`. In short: exclusive use of a device is already enforced by the
per-serial file lock in `:host:core` (`SessionJournalStore.acquireLease`), held by every live
session and dropped by the OS if the process dies. The in-memory lease table was a second,
softer copy of that lock plus scheduling rules (acquire before open, close before release,
all-or-none waits). `SessionService.Open` now waits on the file lock itself
(`lease_timeout_ms`), and clients open several devices in sorted serial order, which makes
concurrent multi-device tests deadlock-free without an atomic multi-acquire.

| File | Was |
|---|---|
| `tap.leases.proto` | `PoolService.Acquire`/`Release`, `AcquireRequest`/`AcquireResponse`/`ReleaseRequest`/`ReleaseResponse`, `CloseRunResponse.devices_released`. Field numbers and names are `reserved` in the live proto. |
| `RunLeases.kt` | Kotlin SDK `Run.acquire(serials, timeout)` / `Run.release(serials)`, and where the JUnit extension called them. |
| `run_leases.py` | Python `Run.acquire` / `Run.release` and the plugin's parallel `_open_all`. |

The service-side implementation (`TapService.acquire`/`release`, `leases`, `poolLock`,
`Run.leases`, `PoolServicer.acquire`/`release`) is in git history at e7d6004; nothing here is on
a build path. See also `../pool-roles/` for the role/constraint matcher that preceded it.
