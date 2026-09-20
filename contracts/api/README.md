# tap.v1 — host service API

`proto/` is the single source of truth for every binding (Kotlin, Python, …). Bindings generate
their stubs from it (`:contracts:api` for the JVM, `clients/python/scripts/gen_stubs.py` for
Python) and the Kotlin service implements it. It sits *above* the device wire protocol
(`.docs/protocol-contract.md`): `command.proto` and `selector.proto` mirror the protocol's
shapes one-to-one and the service converts between them (`EnumMirrorTest`,
`GoldenRoundTripTest`).

| File | Contents |
| --- | --- |
| `selector.proto` | `MatchMode`, `TextProperty`, `NodeFlag`, `Relation`, `Node` (`oneof kind`: `Match`, `Flag`, `ResourceId`, `Related`, `AllOf`, `AnyOf`), `Selector` (`oneof scope`, `oneof pick`) |
| `command.proto` | `ErrorCode`, `Direction`, `StabilitySignal`, one message per command, `Command` (`oneof op`), result payloads, `Error`, `CommandResult` (`oneof outcome`) |
| `connection.proto` | `ConnectionService` — Open / Attach / Close / Info |
| `device.proto` | `DeviceService` — ListDevices |
| `session.proto` | `SessionService` — Open / Close / Execute / Screenshot / DriverLog |
| `app.proto` | `AppService` — install, launch, force-stop, clear-data, permissions, idle |

Conventions: enums carry a zero `UNSPECIFIED` value (proto3), everything nullable in the
protocol is `optional`, and names are the protocol names in snake_case. Fields and enum values
are append-only (`buf breaking` in CI); a number can be reserved, never reused.

`BREAKING_BASELINE` names the last commit before a deliberate incompatible change; CI skips
`buf breaking` for base commits at or before it and enforces it against everything after
(`.docs/service-api.md` §7, §8).
