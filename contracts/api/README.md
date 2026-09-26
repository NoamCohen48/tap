# tap.v1 — host server API

`proto/` is the single source of truth for every binding (Kotlin, Python, …). Bindings generate
their stubs from it (`:contracts:api` for the JVM, `clients/python/scripts/gen_stubs.py` for
Python) and the Kotlin server implements it. It sits *above* the device wire protocol
(`.docs/protocol-contract.md`): `command.proto` and `selector.proto` mirror the protocol's
public shapes one-to-one and the server converts between them (`EnumMirrorTest`,
`GoldenRoundTripTest`). Host-internal protocol ops (health, screenshot, sync) have no proto form.

| File | Contents |
| --- | --- |
| `selector.proto` | `MatchMode`, `TextProperty`, `NodeFlag`, `Relation`, `Node` (`oneof kind`: `Match`, `Flag`, `ResourceId`, `Related`, `AllOf`, `AnyOf`), `Selector` (`oneof scope`, `oneof pick`) |
| `command.proto` | `ErrorCode`, `Direction`, `StabilitySignal`, one message per public command, `Command` (`oneof op`), result payloads, `Error`, `CommandResult` (`oneof outcome`) |
| `client_connection.proto` | `ClientConnectionService` — Connect / Observe / Disconnect / Info |
| `device.proto` | `DeviceService` — ListDevices / Attach / Detach / Execute / Screenshot / DriverLog |
| `app.proto` | `AppService` — streamed install, uninstall, launch, cold launch, force-stop, clear-data, permissions, process, idle |

Every call must carry `authorization: Bearer <token>` (the token in `<state dir>/daemon.json`).

Conventions: every RPC has its own `<Rpc>Request`/`<Rpc>Response` (buf `STANDARD` lint), enums
carry a zero `UNSPECIFIED` value, everything nullable in the protocol is `optional`, and names
are the protocol names in snake_case. Fields and enum values are append-only (`buf breaking` in
CI); a number can be reserved, never reused.

`BREAKING_BASELINE` names the last commit before a deliberate incompatible change; CI skips
`buf breaking` for base commits at or before it and enforces it against everything after.
