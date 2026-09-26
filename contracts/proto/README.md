# Tap schema — `tap.v1` and `tap.wire.v1`

This directory is the one schema of the project. Two packages share the message definitions:

- **`tap.v1`** (the top-level `*.proto`): the public host server API, used by every client
  binding (Kotlin, Python, …) and implemented by the daemon.
- **`tap.wire.v1`** (`wire/wire.proto`): the TAP1 frame payloads between the host and the
  on-device driver (`.docs/protocol-contract.md`). It wraps a `tap.v1.Command` in a `Request`
  and a `tap.v1.CommandResult` in a `Response`, so the daemon forwards commands and results
  unchanged. It adds only what clients must not send or see: the handshake, the host-internal
  operations (health, screenshot capture, sync provider access) and blob metadata. It is
  internal: clients never generate or ship it.

Builds: `:contracts:schema` compiles everything to protobuf-javalite + the Kotlin lite DSL (the
driver is Android, so every JVM consumer uses lite); `:contracts:api` adds only the gRPC service
stubs for `tap.v1`; `clients/python/scripts/gen_stubs.py` generates the Python stubs from the
top-level files only.

| File | Contents |
| --- | --- |
| `selector.proto` | `MatchMode`, `TextProperty`, `NodeFlag`, `Relation`, `Node` (`oneof kind`: `Match`, `Flag`, `ResourceId`, `Related`, `AllOf`, `AnyOf`), `Selector` (`oneof scope`, `oneof pick`) |
| `command.proto` | `ErrorCode`, `Direction`, `StabilitySignal`, one message per public command, `Command` (`oneof op`), result payloads, `Error`, `CommandResult` (`oneof outcome`) |
| `client_connection.proto` | `ClientConnectionService` — Connect / Observe / Disconnect / Info |
| `device.proto` | `DeviceService` — ListDevices / Attach / Detach / Execute / Screenshot / DriverLog |
| `app.proto` | `AppService` — streamed install, uninstall, launch, cold launch, force-stop, clear-data, permissions, process, idle |
| `wire/wire.proto` | handshake (`Hello`, `Challenge`, `Negotiation`, `Authentication`, `AuthenticationResult`), `Request`, `Response`, host-internal operations, `SyncState`, `ArtifactInfo`, `BlobStart`, `BlobEnd` |

Every `tap.v1` call must carry `authorization: Bearer <token>` (the token in
`<state dir>/daemon.json`).

Conventions: every RPC has its own `<Rpc>Request`/`<Rpc>Response` (buf `STANDARD` lint), enums
carry a zero `UNSPECIFIED` value, optional arguments are `optional` and take their default on
the driver only, and names are the protocol names in snake_case. Fields and enum values are
append-only (`buf breaking` in CI); a number can be reserved, never reused. `wire/` follows the
same rule even though the host and driver ship together, so a golden-bytes test failure always
means a deliberate change.

`BREAKING_BASELINE` names the last commit before a deliberate incompatible change; CI skips
`buf breaking` for base commits at or before it and enforces it against everything after.
