# Archived: role- and constraint-based pool acquisition

Removed from the host service on 2026-09-20 (after commit 3afba99). The service pool now leases
**serials** only (`PoolService.Acquire(run_id, serials[], timeout_ms)`); which device plays
which *role* in a test is decided by the client (JUnit extension, pytest plugin), and no client
filters devices by API level, emulator or model yet.

This folder keeps the removed code verbatim, uncompiled and unpackaged, in case role naming or
constraint matching is wanted again — most likely on the client side, over `DeviceFacts` from
`Inventory`, or in a separate scheduler.

| File | Was |
|---|---|
| `tap.roles.proto` | `DeviceConstraints`, `RoleRequest`, `Assignment`, the role-shaped `AcquireRequest`/`AcquireResponse`, `PoolDevice.leased_role`. The live proto `reserved`s these numbers and names. |
| `TapService.roles.kt` | `TapService.acquire(run, roles, timeout)` with `assign` (most-constrained role first), `constraintWeight`, `satisfies`. |
| `PoolServicer.roles.kt` | The gRPC adapter for the above. |
| `DeviceConstraints.kt` | Kotlin SDK `DeviceConstraints` value class and `Run.acquire(Map<role, DeviceConstraints>)`. |
| `TapExtension.constraints.kt` | JUnit: roles → `DeviceConstraints` from `tap.device.<role>` / `tap.serials`. |
| `acquire_roles.py` | Python `Run.acquire(dict[role, constraints])` and the pytest plugin's constraint building. |

Nothing here is on any build path: not a Gradle module, not under `clients/python/tap`, not in
`docs/`. Do not `import` from it; copy what is needed and give it a test.
