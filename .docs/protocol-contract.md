# Tap Protocol Contract

Date: 2026-09-28

Status: application protocol `4.0`; Phase 1 contract is additive and not yet complete.

This document describes the implemented wire contract. Planned but unimplemented features
(events, typed element handles, multi-gesture input) remain design work in
`android-e2e-framework-implementation-plan.md` and are not part of protocol 4.0 yet.

Protocol 4.0 encodes every control payload as protobuf (3.0 introduced that; 4.0 removed
`scroll_until`, the `moved` result, the attach allowlist and `TypeText.selector`, and added the `any_window` scope
and `ElementSnapshot.showing_hint`). The schema is the project's one schema,
`contracts/proto`: the frame payloads are `tap.wire.v1` (`wire/wire.proto`), whose `Request`
carries a public `tap.v1.Command` and whose `Response` carries a `tap.v1.CommandResult` — the
same messages the host server API (`server-api.md`) exposes, so the daemon forwards commands
and results unchanged. 2.0 was canonical JSON over a Kotlin model mirrored by the proto; 1.0 a
flat request/response. Neither is negotiated any more: the driver and the host ship together,
so nothing speaks them. The rationale is under [Design Rationale](#design-rationale).

A change to `tap.v1` therefore changes both the server API and the wire; it also updates the
committed Python stubs (`clients/python/scripts/gen_stubs.py`) and, when encodings move, the
golden bytes under `contracts/protocol/src/test/resources`.

## Framing

Every frame uses this big-endian header:

```text
magic       u32  0x54415031 ("TAP1")
framing     u8   1
frameType   u8
flags       u16  0
requestId   i64
length      i32
payload     length bytes
```

Control payloads are limited to 1 MiB and are protobuf messages of `tap.wire.v1` (see
[Payloads](#payloads)); `BLOB_CHUNK` and the driver's `CLOSE` reason are raw bytes. Connection frames use request ID zero; requests and responses
use a positive monotonically increasing ID. Both ends write each frame as one buffer in a single
`write` and set `TCP_NODELAY`, so a frame never waits on Nagle/delayed-ACK over adb forwarding.

Implemented frame types are `HELLO` (1), `CHALLENGE` (2), `AUTH` (3), `AUTH_RESULT` (4),
`REQUEST` (5), `RESPONSE` (6), `CLOSE` (7), `CANCEL` (8), `PING` (9), `PONG` (10),
`BLOB_START` (11), `BLOB_CHUNK` (12), and `BLOB_END` (13). Unknown framing versions, frame
types, flags, and invalid lengths fail the connection before payload decoding. After
authentication only `REQUEST`, `CANCEL`, `PING`, and `CLOSE` are legal from the host; any other
type (including blob frames) fails the connection. `CANCEL` carries the target request ID
(positive) and an empty payload; `PING`/`PONG` use request ID zero and an empty payload.

The host's `CLOSE` has an empty payload. The driver sends `CLOSE` (request ID zero, payload a
UTF-8 reason of at most 512 characters) when the host violates the protocol after
authentication: a malformed frame header, an illegal frame type, a `CANCEL` with a non-positive
ID or a payload, a `PING` with a non-zero ID or a payload, or a duplicate/stale request ID
(reason prefixed `DUPLICATE_OR_STALE:`). Every driver frame, including rejections and this
`CLOSE`, leaves on the pipeline's single writer thread behind whatever it already queued, so
frames never interleave; after `CLOSE` nothing else is written and the socket is closed. The
host treats a driver `CLOSE` as transport loss with the reason as the cause: the client is
poisoned, in-flight mutations fail `INDETERMINATE` and queries `TRANSPORT_LOST`. Blob frames are
driver-to-host only and are described under [Artifacts](#artifacts).

## Payloads

| Frame | Payload |
|---|---|
| `HELLO` | `Hello` |
| `CHALLENGE` | `Challenge` |
| `AUTH` | `Authentication` |
| `AUTH_RESULT` | `AuthenticationResult` |
| `REQUEST` | `Request` |
| `RESPONSE` | `Response` |
| `BLOB_START` / `BLOB_END` | `BlobStart` / `BlobEnd` |
| `BLOB_CHUNK` | raw (see [Artifacts](#artifacts)) |
| `CLOSE` (driver) | UTF-8 reason |

Encoding is standard proto3 binary. Unknown fields are ignored by both sides, as protobuf does.
The handshake does not need a canonical encoding: the MAC covers the payload bytes exactly as
they were sent, and the negotiation the host authenticates travels inside `AUTH` as the
serialized bytes it MACed (`Authentication.negotiation`), so neither side ever re-encodes a
message to verify it. A payload that does not parse fails the handshake; a `REQUEST` that does
not parse is `INVALID_REQUEST` (below).

## Negotiation

The host sends `HELLO` with `host_build_id`, a 32-byte `host_nonce`, `session_generation`,
`session_id`, and ascending `supported_versions`. The driver validates the
instrumentation-provided identity and nonce, rejects a connection with no common application
version, and returns `CHALLENGE` containing:

- Android API level;
- sorted capability names;
- driver APK, driver-test APK, and UiAutomator build IDs;
- driver instance ID and nonce;
- echoed host nonce, session, and generation;
- sorted supported operation names (`supported_operations`: the `op` values below); and
- sorted supported application-protocol versions.

The host selects the highest exact common `major.minor` version and a sorted subset of offered
capabilities. Protocol 4.0 currently enables:

```text
artifact.screenshot.v1
diagnostic.hierarchy.v1
input.key-events.v1
product.probe.v1
synchronization.v1
```

The `Negotiation` contains `enabled_capabilities` and `selected_version`; the host serializes it
once and sends those bytes in `Authentication.negotiation`.

## Authentication

All nonces and the per-launch secret are 32 bytes; nonces and HMACs are raw `bytes` fields.
The authenticated transcript is:

```text
u32be(len(HELLO)) || HELLO ||
u32be(len(CHALLENGE)) || CHALLENGE ||
u32be(len(NEGOTIATION)) || NEGOTIATION
```

where each part is the payload bytes as sent (`NEGOTIATION` = `Authentication.negotiation`).

Host authentication is HMAC-SHA-256 over `TAP1-HOST-AUTH`, one zero byte, and the transcript.
Driver authentication uses the independent `TAP1-DRIVER-AUTH` domain. `AUTH_RESULT` must echo
the exact selected version and enabled capabilities and carry the valid driver transcript
HMAC. Any mismatch fails authentication.

After the driver's HMAC verifies, the host also requires the challenge's `driver_apk_build_id`
and `driver_test_apk_build_id` to equal its own `DRIVER_APK_BUILD_ID` / `DRIVER_TEST_APK_BUILD_ID` (all
three are the engine version the artifacts were built from). A driver of another build fails the
connection with `DriverBuildMismatchException` (a handshake failure, never retried) and the
session does not open; the remedy is reinstalling the driver APKs that ship with the host. The
check runs only after authentication, so the reported build is known to come from the driver.

## Requests

A request is an envelope around one operation:

```text
Request {
  session_id, generation     the authenticated identity
  timeout_ms                 0..120 000, from acceptance (a Command.timeout_ms is ignored)
  body: command (tap.v1.Command, `oneof op`) | health | screenshot | sync_bootstrap | sync_poll
}
```

The request ID lives in the frame header. `command` is a public command, exactly one `op` case
below; the other `body` cases are host-internal operations clients cannot send. Optional fields
(`optional` in the proto, and the zero `UNSPECIFIED` value of `StabilitySignal`, `MatchMode`, and an unset `scope`/`pick`) take their documented default on
the **driver**, the one place defaults are applied; host core and the daemon forward commands
as the client built them. `ResourceId.aut_package` is likewise resolved by the driver to the
session's AUT package. In Kotlin, `contracts/protocol` `Operations.kt` holds the catalogue
(`Command.op`, `isMutation`, `targetSelector`, the `Commands`/`Requests` factories) and the
driver's exhaustive `Request.dispatch(CommandHandler)`, which gives each operation its own
result type.

| `op` | Message | Fields | Result (`CommandResult.outcome`) |
|---|---|---|---|
| `health` | `Health` (host-internal) | – | `done` |
| `device_info` | `DeviceInfoQuery` | – | `device_info` = API level, manufacturer/model/product, display size and rotation, focused package |
| `press_key` | `PressKey` (mutation) | `key_code` ≥ 0 | `done` after one key press (`3`/`4` route through `pressHome`/`pressBack`); `ACTION_REJECTED` if the platform refused it |
| `exists` | `Exists` | `selector` | `bool` = at least one match now |
| `count` | `Count` | `selector` | `count` = matches in the selector's scope, capped at 1 000 (ignores the match limit) |
| `snapshot` | `Snapshot` | `selector` (exactly one match) | `snapshot` = class, package, resource name, text (as Android reports it: an empty field's hint), `showing_hint`, description, hint, visible bounds, state flags, child count |
| `wait_visible` | `WaitVisible` | `selector` | `done`, or `WAIT_TIMEOUT` |
| `wait_gone` | `WaitGone` | `selector` | `done` once no match exists, or `WAIT_TIMEOUT` |
| `wait_app_visible` | `WaitAppVisible` | `package_name` | `done` once that package owns the focused window, or `WAIT_TIMEOUT` |
| `wait_screen_stable` | `WaitScreenStable` | `package_name`, `stable_for_ms` 1..30 000 (default 500), `signal` `TREE`/`PIXELS`/`ALL` (default `ALL`) | `done` once that package's focused window has not changed (per `signal`) for `stable_for_ms`; `WAIT_TIMEOUT` with detail `SCREEN_CHANGING` (never quiet long enough) or `APP_NOT_VISIBLE` (the package never owned the focused window) |
| `tap`, `long_tap` | `Tap`, `LongTap` (mutations) | `selector` (exactly one match) | `done` after the click; no enabled pre-check |
| `set_text` | `SetText` (mutation) | `selector`, `text` (≤ 256 chars) | `done`: `ACTION_SET_TEXT` accepted by the node (not read back) |
| `type_text` | `TypeText` (mutation) | `text` (≤ 256 chars); no selector (field 1 reserved) | `done`: every key event injected into whatever has input focus; no click, no settling, not read back. `INVALID_REQUEST`/`UNSUPPORTED_CHARACTERS` before input for a character the virtual key map cannot type |
| `clear_text` | `ClearText` (mutation) | `selector` | `done`: `ACTION_SET_TEXT` with "" accepted (not read back) |
| `swipe` | `Swipe` (mutation) | `selector`, `direction` (required), `distance_percent` 1..100 | `done` after one finger gesture across the element |
| `scroll` | `Scroll` (mutation) | `selector`, `direction` (required), `distance_percent` | `done` after one scroll segment; no scrollable pre-check and no report of whether content moved (the clients' `scrollUntil` loops `exists` + `scroll`) |
| `dump_hierarchy` | `DumpHierarchy` | – | `text` = accessibility XML (diagnostic only) |
| `screenshot` | `CaptureScreenshot` (host-internal) | – | `done`; PNG blob + `Response.artifact` metadata (capability `artifact.screenshot.v1`) |
| `sync_bootstrap` | `SyncBootstrap` (host-internal) | `observed_pid`, `observed_start_token` | `done` + `Response.sync` |
| `sync_poll` | `SyncPoll` (host-internal) | `observed_pid`, `observed_start_token`, `expected_process_start_uuid`, `expected_session_identity` | `done` + `Response.sync` |

`direction` is the direction the content moves for scrolls and the finger for swipes.
`distance_percent` (default 80) is the gesture length as a percentage of the element's size.
`CommandValidation` (`contracts/protocol`) holds the range and structure checks and runs three
times: on the daemon as a pre-flight, in host core before a request ID is allocated, and on the
driver, which trusts nothing on the wire. On the driver a payload that does not parse is
`INVALID_REQUEST`; a parsed request is checked for session identity, then against the request-ID
watermark, then validated: an out-of-range argument or an enum value this build does not know
is `INVALID_REQUEST`, a selector problem `INVALID_SELECTOR`, and a `Request` with no `body` or a
`Command` with no `op` (which is also what a newer host's unknown case decodes to) is
`UNSUPPORTED`. All of this is decided on the reader lane before any UI access, and the request
ID is consumed either way.

Text is passed through as Android reports it: an empty `EditText` reports its hint as
accessibility text with `isShowingHintText` set. `SNAPSHOT.text` carries that raw text and
`showing_hint` says so; text selectors match the same raw text (`By.text` and the traversal
predicate agree).

Waits (`wait_visible`, `wait_gone`, `wait_app_visible`) poll on the driver at 50 ms until the
condition holds or the request deadline passes, and honour cancellation between polls. A
timeout is a `WAIT_TIMEOUT` error response, never an exception path.

`wait_screen_stable` is the only settle primitive and it is **explicit**: no other command waits
for animations or a quiet screen, and the driver never retries a command because the screen did
not change. Given `package_name`, `stable_for_ms` and `signal`, the driver samples that
package's focused application window and succeeds once the chosen signal has not changed for
`stable_for_ms`. `TREE` (Maestro's "app settled") is a fingerprint of the accessibility tree
(class, id, text, description, bounds, state flags; capped at 4 000 nodes, no XML dump) and
takes no screenshots; `PIXELS` (Maestro's "animation ended") is a downscaled 48×96 grid of the
window's pixels, changed when more than 0.5 % of cells differ after colour quantisation; `ALL`
requires both. Between samples it blocks on
`TYPE_WINDOW_CONTENT_CHANGED`/`TYPE_WINDOW_STATE_CHANGED` accessibility events from that package
for at most 100 ms, so a change restarts the quiet period immediately while an idle screen still
costs one screenshot per 100 ms. The request deadline bounds the whole wait; the timeout detail
says whether the screen kept changing (`SCREEN_CHANGING`) or was never that package's
(`APP_NOT_VISIBLE`).

Independently, the driver bounds UiAutomator's implicit `waitForIdle` (run before every
`UiDevice`/`UiObject2` interaction) to 1 s instead of the 10 s default, so a permanently
animating screen slows a command by at most one second rather than pushing every request past
its deadline and poisoning the session.

A request ID at or below the accepted watermark (an ID consumed by a rejected payload is not
reusable either) is a protocol violation: the driver closes the connection with a
`DUPLICATE_OR_STALE:` `CLOSE` reason instead of answering, because a response on the reused ID
could complete the host's real pending command. A session-generation or session-ID mismatch returns
`SESSION_MISMATCH`. Mutating element targets and scroll containers require exactly one match.
Zero matches return `NOT_FOUND`; multiple matches return `AMBIGUOUS` before input is injected.

## Responses

A `Response` is a `tap.v1.CommandResult` plus, for a successful host-internal operation, its
data in the `internal` oneof:

```text
Response {
  result: CommandResult {
    duration_ms, request_id, session_generation     filled in by the driver
    outcome: done | bool | count | text | snapshot | device_info | error
  }
  internal: artifact (ArtifactInfo, screenshot) | sync (SyncState, sync_bootstrap/sync_poll)
}
```

Each operation produces exactly one `outcome` case on success (the table above) and `error`
(`code`, optional `detail`, optional `message`) on failure. The daemon hands `result` to the
client as it is.

### Selectors

A selector is a small versioned expression tree (`selector.proto`), never a string expression
or XPath. Every sum type is a `oneof`:

```text
Selector {
  node:  Node
  scope: aut (default)          the AUT's focused window
       | system {package_name}   that package's focused window, any package (historical name)
       | any_window              every window on screen, any package
  pick:  exactly_one (default) | first | at {index ≥ 0}
}
Node.kind =
  | match    {property: TEXT | CONTENT_DESCRIPTION | HINT | CLASS_NAME, value, mode (default EXACT)}
  | flag     {property: ENABLED | CHECKED | CHECKABLE | CLICKABLE | FOCUSED | FOCUSABLE
                        | LONG_CLICKABLE | SCROLLABLE | SELECTED, value}
  | resource {name, package_name? | aut_package}   `pkg:id/name`; aut_package: the session's AUT,
                                                    resolved by the driver; neither: the exact
                                                    resource name (Compose testTag)
  | related  {relation: PARENT | ANCESTOR | CHILD | DESCENDANT, node: Node}
  | all_of   {nodes: [Node, Node, …]}   conjunction, ≥ 2 operands
  | any_of   {nodes: [Node, Node, …]}   disjunction, ≥ 2 operands
mode: EXACT | CONTAINS | STARTS_WITH | ENDS_WITH | REGEX
```

`Nodes.allOf` / `Nodes.anyOf` (and the infix `and` / `or`) normalise: nested combinators of the
same kind are flattened and a single operand is returned as is, so a chain of refinements is
one flat `all_of`; the client DSLs do the same. An unset `scope`/`pick` is the default. The
`UNSPECIFIED` value of `TextProperty`, `NodeFlag` and `Relation`, any unknown enum value, a
node with no `kind`, an `at` with a negative index and a `system` scope with a blank package are
rejected by validation.

Both sides validate the same limits before allocating a request ID or touching the UI: depth
≤ 32, ≤ 256 nodes, ≤ 1024 chars per string, no empty resource name or package, a combinator
needs at least two operands (`EMPTY_NODE`), a `match` with an empty `value` is only valid in
`EXACT` mode (`EMPTY_VALUE`: `CONTAINS ""` and the other modes would match every node, so a
`first` mutation would hit an arbitrary one), and `REGEX` must compile under RE2 (linear time; no
backreferences or lookaround). The rejection reason is returned as an `INVALID_SELECTOR`
detail (`UNSPECIFIED_VALUE` for an unset or unknown enum). Under the `aut` scope a resource with an explicit
`package_name` must be the AUT's (`SCOPE_DENIED`); `system` and `any_window` selectors may name
any package's resources.

`CommandValidation.validate(command)` checks the command's arguments and the selector it
carries. On the device,
`CommandValidation.validateSelector(selector)` also returns the query plan. A selector compiles
to one window-scoped `BySelector` (`ByBuilder` plus `UiWindow.findObjects` on the focused window of
the scope package, or `UiDevice.findObjects` for `any_window`) unless it contains something `BySelector` cannot hold: a `REGEX` match, an
`any_of`, or a conjunction that repeats one of `BySelector`'s single-valued slots (the same
text property twice, the same flag twice, two resources, two parents or two ancestors —
children and descendants are lists and stay native). Those take the traversal plan, which
walks the same window's object tree once, reading each node's `AccessibilityNodeInfo` once
however many predicates the tree holds; both plans return the same match set and neither
dumps the hierarchy. `exactly_one` fetches at most two matches to decide `AMBIGUOUS`; `first`
and `at n` take accessibility order and return `NOT_FOUND` when the index is absent.

## Execution Model

The driver runs one authenticated connection through independent lanes:

```text
socket reader -> bounded queue (16) -> single command executor -> writer -> socket
                                              ^
                                           watchdog
```

- The reader thread never runs UI work. It validates the request-ID watermark, then enqueues.
  `CANCEL`, `PING`, and connection closure are therefore observed while a command runs.
- `timeout_ms` starts when the request is accepted. Queue residence consumes the deadline; a
  command reaching the executor after expiry returns `DEADLINE_EXCEEDED` without running.
- A full queue returns `OVERLOADED` immediately; the request ID is still consumed.
- Every accepted request gets exactly one terminal `RESPONSE`, in whichever order commands
  terminate. Hosts must demultiplex by request ID.
- `PONG` is produced on the writer lane and does not wait for the executor.

### Cancellation

| Driver state at `CANCEL` | Result |
|---|---|
| Queued | Removed; `CANCELLED` |
| Running, no mutation yet | Cooperative stop at the next checkpoint; `CANCELLED` |
| Running, mutation started | Ignored; the definitive action result is returned |
| Terminal or unknown ID | Ignored; never a second response |

Commands checkpoint before selector resolution and between wait polls. Immediately before the first irreversible platform call (`click`, text replacement,
key injection, a gesture) the command passes an atomic gate that refuses on
cancel, deadline, or a poisoned session and otherwise makes the command uncancellable. Once
open the gate stays open: passing it again never refuses. Waits that simply run out of time still report `WAIT_TIMEOUT`;
`DEADLINE_EXCEEDED` is reserved for expiry outside a normal condition result.

After the gate opened, a failure whose code is "may have mutated: no" would be a false promise,
so the pipeline rewrites it to `INDETERMINATE`: the `detail` keeps the original detail (or the
original code name when there was none) and the `message` starts with `<CODE>[/<DETAIL>] after
the mutation started`. Codes that already say "may have mutated: yes" pass through unchanged.

### Watchdog

If the running command is still executing more than the uninterruptible grace period
(`DRIVER_UNINTERRUPTIBLE_GRACE_MS`, 10 s; instrumentation argument `tapUninterruptibleGraceMs`)
after its deadline, the
pipeline is poisoned: the running command terminates with `INDETERMINATE` if it had started
mutating and `DRIVER_UNHEALTHY` otherwise, queued commands terminate with `DRIVER_UNHEALTHY`,
later requests are refused with `DRIVER_UNHEALTHY`, and the mutation gate refuses everything.
The driver emits a `TAP_POISONED` instrumentation status, stops listening, and kills its own
process after a short flush grace. The host must rebuild the session with a new generation.
Only the late-work fault scenario disables the self-kill, so the host's forced termination and
reboot quarantine path can be validated against a genuinely hung driver. The test-only
`CANCEL_AFTER_MUTATION` fault holds a fault-button tap open after its click so the host can prove
that a cancel arriving after the mutation gate is ignored.

### Heartbeat

The driver requires periodic host traffic: any authenticated inbound frame (`REQUEST`,
`CANCEL`, `PING`) resets the heartbeat timer. If the host stays silent for
`tapHeartbeatTimeoutMs` (default 30 000; must be positive) the watchdog poisons the session
with `DRIVER_UNHEALTHY` / `HEARTBEAT_EXPIRED`, emits `TAP_POISONED`, and self-kills as above.
The host client sends `PING` after `heartbeatIntervalMs` (default 5 000) of idle time on a
background thread; `PING` is serialized so a stale `PONG` can never satisfy a later ping.

### Artifacts

A command whose result is binary streams it on the writer lane *before* its terminal
`RESPONSE`, using the command's request ID:

```text
BLOB_START  BlobStart { blob_id (UUID), media_type, total_length, sha256 }
BLOB_CHUNK  16-byte blob id || u32be chunk index || data (1..262144 bytes)
BLOB_END    BlobEnd { blob_id, byte_count, sha256 }
RESPONSE    Response { result.done, artifact: ArtifactInfo { blob_id, media_type, byte_count, sha256, width?, height? } }
```

Chunks are contiguous from index 0. Artifacts larger than 64 MiB are refused before transfer
(`ARTIFACT_TRANSFER_FAILED` / `ARTIFACT_TOO_LARGE`); a capture failure is `CAPTURE_FAILED`.
Cancel and deadline are checked at chunk boundaries: an aborted transfer ends with
`CANCELLED` or `DEADLINE_EXCEEDED` and no `BLOB_END`, never a partial artifact. The host
verifies order, length, and SHA-256 and reports `ARTIFACT_TRANSFER_FAILED` with
`BLOB_UNEXPECTED`, `BLOB_OUT_OF_ORDER`, `BLOB_LENGTH_MISMATCH`, `BLOB_CHECKSUM_MISMATCH`, or
`BLOB_INCOMPLETE`; a driver failure response after a partial blob is returned unchanged. The
session stays usable after a rejected artifact.

### Host client

The host writes each request ID and complete frame under one per-session transport mutex, so
IDs are strictly increasing on the socket even with concurrent callers. A dedicated reader
thread demultiplexes `RESPONSE`, `PONG`, and blob frames by request ID. `cancel` sends `CANCEL` only for a request in the
`WRITTEN` state; the caller still awaits the driver's single terminal response. Each command's
private response budget is its timeout plus `HOST_RESPONSE_PADDING_MS` (the driver grace plus
5 s), so the driver's own watchdog verdict on a late command arrives before the host gives up.
A missing terminal response, an unknown response ID, or any read failure poisons the client: in-flight
mutating requests fail as `INDETERMINATE`, queries as `TRANSPORT_LOST`, and later submissions
fail before writing.

## Errors

An `error` outcome has exactly one `code` from the closed taxonomy below (`tap.v1.ErrorCode`,
`ERR_` prefix on the wire), an optional stable
`detail` sub-reason, and an optional free-text `message` that clients must not branch on. An
`ok` response never carries a code. A host that receives a code it does not know (or
the zero value) reads it as `UNKNOWN` (`normalized()`; treated as may-have-mutated, not
retryable); a driver never sends `UNKNOWN` or `UNSPECIFIED`. Adding a code is a minor protocol version change.

Two properties are defined per code. **May have mutated** means device state may have changed;
such a command must never be replayed blindly. **Retryable** is a hint for a caller-owned
policy; Tap itself never retries.

| Code | May have mutated | Retryable | Meaning / details |
|---|:-:|:-:|---|
| `INVALID_REQUEST` | no | no | Malformed or out-of-range request. `UNSUPPORTED_CHARACTERS`: text has no key-event mapping (rejected before input). |
| `INVALID_SELECTOR` | no | no | Selector rejected before any lookup. `SCOPE_DENIED`, `SELECTOR_TOO_DEEP`, `SELECTOR_TOO_LARGE`, `STRING_TOO_LONG`, `EMPTY_NODE`, `EMPTY_VALUE`, `INVALID_REGEX`, `UNSPECIFIED_VALUE`. |
| `UNSUPPORTED` | no | no | No operation set (or one this driver does not know). |
| `UNAUTHENTICATED` | no | no | Handshake failure (`AUTH_RESULT.error`). |
| `SESSION_MISMATCH` | no | no | Wrong session ID or generation. |
| `DUPLICATE_OR_STALE` | no | no | Request ID at or below the watermark. Not sent as a response: it prefixes the driver's `CLOSE` reason. |
| `OVERLOADED` | no | yes | Command queue full; the ID is still consumed. |
| `AUT_MISMATCH` | no | no | Observed AUT identity differs. `PROCESS_RESTARTED`, `PROCESS_MISMATCH` from synchronization. |
| `NOT_FOUND` | no | yes | Zero matches. |
| `AMBIGUOUS` | no | no | More than one match; returned before any input. |
| `NOT_INTERACTABLE` | no | yes | Reserved; no longer emitted (4.0: the driver does not pre-check enabled/scrollable). |
| `STALE_DURING_COMMAND` | yes | no | Target changed after the mutation began. `TARGET_GONE`, `TARGET_AMBIGUOUS`. |
| `ACTION_REJECTED` | yes | no | Android refused issued input (`ACTION_SET_TEXT` returned false, a key event was not injected). `PARTIAL_INPUT` (deadline mid-typing). Effects are never read back. |
| `WAIT_TIMEOUT` | no | yes | The waited condition stayed false until the timeout. `SCREEN_CHANGING`, `APP_NOT_VISIBLE` for `WAIT_SCREEN_STABLE`. |
| `CANCELLED` | no | yes | Stopped before mutation. `CANCELLED_IN_QUEUE`, `TRANSPORT_CLOSED`. |
| `DEADLINE_EXCEEDED` | no | yes | Deadline passed outside a normal wait result. `EXPIRED_IN_QUEUE`. |
| `AUT_NOT_INSTALLED` | no | no | Reserved; not yet emitted. |
| `AUT_CRASHED` | yes | no | Reserved; not yet emitted. |
| `AUT_ANR` | yes | no | Reserved; not yet emitted. |
| `SYNC_PROVIDER_UNAVAILABLE` | no | yes | Synchronization provider unusable. `CERTIFICATE_MISMATCH`, `UNINITIALIZED`, `MALFORMED_STATE`, `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`, `PROVIDER_POISONED`. |
| `DRIVER_UNHEALTHY` | no | no | Session poisoned by the watchdog (`WATCHDOG`, `HEARTBEAT_EXPIRED`); rebuild the session. |
| `TRANSPORT_LOST` | no | no | Host-side: no response and no mutation risk. |
| `INDETERMINATE` | yes | no | Mutation may have happened without a definitive result. `WATCHDOG`, `KEY_RELEASE_FAILED`; after the gate, the rewritten code's detail or name. |
| `ARTIFACT_TRANSFER_FAILED` | yes | no | Blob capture or transfer failed. Driver: `CAPTURE_FAILED`, `ARTIFACT_TOO_LARGE`, `BLOB_INCOMPLETE`. Host verification: `BLOB_UNEXPECTED`, `BLOB_OUT_OF_ORDER`, `BLOB_LENGTH_MISMATCH`, `BLOB_CHECKSUM_MISMATCH`, `BLOB_INCOMPLETE`. |
| `PAYLOAD_TOO_LARGE` | yes | no | The command ran but its response exceeded the control payload limit. |
| `INTERNAL` | yes | no | Unexpected driver failure. |


### Host exceptions

`CommandException` is sealed: `RemoteCommandException` wraps a driver error response (code,
detail, remote message, duration) and `CommandTransportException` covers `TRANSPORT_LOST` and
`INDETERMINATE` with the transmission state. Both carry operation, request ID, session
generation, device serial, rendered selector, timeout, and the code's retryable/may-have-mutated
flags. `DriverClient.send` returns the `Response` as data; `execute` returns the successful
`CommandResult` (or, for a host-internal `Request`, the whole `Response`) and throws the typed
exception; `submit` validates before allocating a request ID and returns a `PendingCommand` for
cancellation.

## Compatibility Rules

- Framing and application versions are independent.
- Negotiation chooses the highest exact common version and cannot silently downgrade.
- Additive features require an authenticated capability.
- Existing field meanings do not change within a major version.
- Unknown fields are ignored; the schema is append-only (`buf breaking`), numbers are reserved,
  never reused.
- Unknown operations fail with `UNSUPPORTED`; unknown enum values in a command are rejected, never
  mapped to a default.
- Connection loss never causes automatic mutation replay.

Framing, negotiation, transcript-binding, incompatible-version, validation and golden-bytes
tests live under `contracts/protocol/src/test`. The golden bytes pin the encoding of
representative requests and responses, so an accidental wire change fails a test rather than a
device run.

## Design Rationale

Why the contract looks the way it does; the choices marked *judgment call* are the ones
worth revisiting, the last one is not optional.

**One persistent framed socket rather than HTTP** (*judgment call*). HTTP would serve the
request/response half — Appium UiAutomator2 runs NanoHTTPD inside its instrumentation — but
the driver needs more than that on one connection: cancellation of an in-flight command
(over HTTP a second request racing the first, correlated by an ID you invented anyway);
driver-initiated frames (`PONG`, blob chunks streamed before the terminal response,
poison notifications, events later), which HTTP only gets through long-polling or
WebSocket; and the ordering guarantees (strictly increasing IDs, old generation rejected,
one terminal response per accepted ID) that are trivial on one socket and awkward across
independent connections and keep-alive pools. An HTTP server inside the instrumentation is
also a dependency, startup time, and attack surface on a port every app on the device can
reach. The 20-byte header is roughly WebSocket framing without the upgrade. A *host-side*
server (`multi-language-bindings.md`) should nevertheless be HTTP/JSON-RPC; the trade-offs
flip there.

**One protobuf schema for the server API and the wire** (protocol 3.0, replacing 2.0's JSON).
2.0 kept a Kotlin model (kotlinx.serialization, canonical JSON) and a hand-mirrored copy of it in
`tap.v1`, with conversion code in the daemon and mirror/round-trip tests to catch drift; every
command existed three times. Now `tap.v1.Command`/`CommandResult` *are* the wire messages
(wrapped by `tap.wire.v1.Request`/`Response`), so the daemon forwards them, defaults live only on
the driver, and a new command is one proto change plus its driver handler. protobuf-javalite
runs on Android and the host alike. The canonical-JSON requirement disappears because the MAC
covers the bytes sent rather than a re-encoding. What is lost is readable payloads in logs and
fixtures; `Selector.render()` and the golden-bytes tests cover the two places that mattered.

**One message per command, a `oneof` on the wire** (since protocol 2.0). Protocol 1.0 had a
single `Request` with an `operation` enum and every field of every command as an optional,
and a single `Response` with `ok` and every result field as an optional. That shape needs a
hand-written table of which fields each operation requires and callers that read
`response.value` and hope it is the field their command fills. Each command is its own message
with only its own fields, and each `op` has one `outcome` case. The envelope (`session_id`,
`generation`, `timeout_ms`) stays separate from the command because it is the session layer's,
not the command's. The Kotlin catalogue (`Operations.kt`) and the driver's dispatch are
exhaustive `when`s over the generated case enums, so a new case does not compile until it is
classified.

**Selectors are a sum-type expression tree, with `any_of`** (*deliberate departure from plan
§10, approved 2026-09-20*). The same principle as commands: `Node.kind`, `scope` and `pick` are
`oneof`s, so a `system` scope always has its package, an `at` pick always has its
index, and the old shape-validation details (`SCOPE_PACKAGE_REQUIRED`, `INDEX_REQUIRED`,
`ORDER_NOT_ACCEPTED`, …) are unrepresentable rather than checked. The plan listed OR among
the deliberately absent operators; it is now `any_of`, because real screens need it (the
permission dialog's "Allow" / "Allow only while using the app" / "While using the app" variants
across Android versions) and the alternatives — several selectors racing `exists`, or a regex
over text only — are slower and less precise. The costs the plan worried about are contained:
`any_of` is a node predicate, not a search across windows or scopes; it runs on the traversal
plan (one window walk, no dump), and the native `BySelector` fast path is unchanged for every
selector without it. `acceptAccessibilityOrder` is gone: choosing `first`/`at` *is* the opt-in,
a second flag confirming the same choice added nothing. NOT, sibling, nearest and nth-match
remain absent.

**Challenge/response authentication** (*not optional*). The driver listens on a TCP port on
the device and holds `UiAutomation`: it can inject input into any app and read any screen,
including system dialogs. Without authentication, anything that reaches `localhost:27183` —
any app on the device, anyone with ADB access to a shared lab device, a stale driver/host
pairing from a previous run — can drive the phone. The handshake settles three things:

1. *Host → driver*: only the process that launched this driver instance, and therefore
   knows the per-launch secret, can send commands. Challenge/response keeps the secret off
   the socket; nonces stop a recorded handshake from being replayed.
2. *Driver → host*: the host proves it is talking to its own driver — this instance, this
   session, this generation — not an orphan still listening on the port it just forwarded.
   That was a real Phase 0 failure mode, and generation checking is how "never replay a
   mutation" survives reconnects.
3. *Negotiation binding*: the MAC covers `HELLO || CHALLENGE || NEGOTIATION`, so the
   selected version and enabled capabilities cannot be downgraded in transit.

HMAC with one per-launch 32-byte secret was chosen over TLS because there is no PKI on the
device, self-signed certificates add key management for no gain in this threat model, and
the secret is delivered out of band through instrumentation arguments. The known weakness —
that argument is visible in `ps`/`dumpsys` on a rooted device for the process lifetime — is
recorded in `framework-gaps.md` for the security review.

## Not Yet Implemented

Protocol 4.0 does not yet expose events, multi-touch gestures, `session.shutdown`, or
`inspector.snapshot`. `AUT_NOT_INSTALLED`, `AUT_CRASHED`, and
`AUT_ANR` are defined but not yet emitted. See `.docs/framework-gaps.md` for the full list.
