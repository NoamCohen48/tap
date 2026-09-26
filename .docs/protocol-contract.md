# Tap Protocol Contract

Date: 2026-09-20

Status: application protocol `2.0`; Phase 1 contract is additive and not yet complete.

This document describes the implemented wire contract. Planned but unimplemented features
(events, typed element handles, multi-gesture input) remain design work in
`android-e2e-framework-implementation-plan.md` and are not part of protocol 2.0 yet.

Protocol 2.0 replaced 1.0's flat request (one `operation` enum plus every optional field of
every command) and flat response (`ok` plus every optional result field) with one class per
command and per result kind, discriminated on the wire (`op`, `kind`, `type`). 1.0 is not
negotiated any more; the driver and the host ship together, so nothing speaks it. The
rationale is under [Design Rationale](#design-rationale).

`contracts/api/proto/command.proto` (the host server API, `server-api.md`) mirrors this
contract's enums, selector AST and command/result models as `oneof`s; `EnumMirrorTest` and
`GoldenRoundTripTest` in `:host:daemon` fail when they drift. A change here therefore also
updates the proto and the committed Python stubs (`clients/python/scripts/gen_stubs.py`).

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

Control payloads are limited to 1 MiB. Handshake payloads are canonical UTF-8 JSON. Request
and response payloads are UTF-8 JSON (`ProtocolJson`: defaults always encoded, null optionals
omitted, unknown keys ignored). Connection frames use request ID zero; requests and responses
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

## Canonical JSON

Handshake JSON recursively sorts object keys lexicographically, retains array order, has no
insignificant whitespace, and uses standard JSON scalar representations. The receiver parses
and re-canonicalizes every handshake payload and rejects it if the bytes differ. This rejects
duplicate keys and noncanonical whitespace. Unknown canonical optional fields are ignored.

## Negotiation

The host sends canonical `HELLO` with `hostBuildId`, a 32-byte Base64URL `hostNonce`,
`sessionGeneration`, `sessionId`, and sorted `supportedVersions`. The driver validates the
instrumentation-provided identity and nonce, rejects a connection with no common application
version, and returns canonical `CHALLENGE` containing:

- Android API level;
- sorted capability names;
- driver APK, driver-test APK, and UiAutomator build IDs;
- driver instance ID and nonce;
- echoed host nonce, session, and generation;
- sorted supported command names (`supportedOperations`: the `op` values below); and
- sorted supported application-protocol versions.

The host selects the highest exact common `major.minor` version and a sorted subset of offered
capabilities. Protocol 2.0 currently enables:

```text
artifact.screenshot.v1
diagnostic.hierarchy.v1
input.key-events.v1
product.probe.v1
synchronization.v1
```

The canonical negotiation contains `enabledCapabilities` and `selectedVersion`.

## Authentication

All nonces and the per-launch secret are 32 bytes. Nonces and HMAC values use unpadded
Base64URL. The authenticated transcript is:

```text
u32be(len(HELLO)) || HELLO ||
u32be(len(CHALLENGE)) || CHALLENGE ||
u32be(len(NEGOTIATION)) || NEGOTIATION
```

Host authentication is HMAC-SHA-256 over `TAP1-HOST-AUTH`, one zero byte, and the transcript.
Driver authentication uses the independent `TAP1-DRIVER-AUTH` domain. `AUTH_RESULT` must echo
the exact selected version and enabled capabilities and carry the valid driver transcript
HMAC. Any mismatch fails authentication.

After the driver's HMAC verifies, the host also requires the challenge's `driverApkBuildId` and
`driverTestApkBuildId` to equal its own `DRIVER_APK_BUILD_ID` / `DRIVER_TEST_APK_BUILD_ID` (all
three are the engine version the artifacts were built from). A driver of another build fails the
connection with `DriverBuildMismatchException` (a handshake failure, never retried) and the
session does not open; the remedy is reinstalling the driver APKs that ship with the host. The
check runs only after authentication, so the reported build is known to come from the driver.

## Requests

A request is an envelope around one command:

```json
{"sessionId":"…","generation":3,"timeoutMs":10000,"command":{"op":"scroll_until","selector":{…},"container":{…},"direction":"DOWN","distancePercent":80,"maxScrolls":20}}
```

`sessionId` and `generation` are the authenticated identity; `timeoutMs` is bounded (0..120 000)
and starts when the request is accepted; the request ID lives in the frame header. `command`
is exactly one of the classes below, discriminated by `op`; every field that is not marked
optional is required, defaults are filled in by the sender and always present on the wire
(including a selector's default `scope`/`pick` and a match's default `mode`), so a receiver's
own defaults never decide a value; the golden request fixtures show every default and change
when one does.
In Kotlin (`contracts/protocol`, `Commands.kt`) each command is a `@Serializable` class in the
sealed `Command` hierarchy; `Mutation` marks the commands that may change device state and
`Targeted` the ones with a primary `selector`. `Returning<R>` types the result each command
produces, so `DriverClient.execute(Exists(selector))` is a `BoolResult` and
`execute(DeviceInfoQuery)` a `DeviceInfoResult` without a cast at the call site.

| `op` | Class | Fields | Result kind |
|---|---|---|---|
| `health` | `Health` | – | `done` |
| `device_info` | `DeviceInfoQuery` | – | `device_info` = API level, manufacturer/model/product, display size and rotation, focused package |
| `press_key` | `PressKey` (mutation) | `keyCode` ≥ 0 | `done` after one key press (`3`/`4` route through `pressHome`/`pressBack`); `ACTION_REJECTED` if the platform refused it |
| `exists` | `Exists` | `selector` | `bool` = at least one match now |
| `count` | `Count` | `selector` | `count` = matches in the focused window, capped at 1 000 (ignores the match limit) |
| `snapshot` | `Snapshot` | `selector` (exactly one match) | `snapshot` = class, package, resource name, text (hint excluded), description, hint, visible bounds, state flags, child count |
| `wait_visible` | `WaitVisible` | `selector` | `done`, or `WAIT_TIMEOUT` |
| `wait_gone` | `WaitGone` | `selector` | `done` once no match exists, or `WAIT_TIMEOUT` |
| `wait_app_visible` | `WaitAppVisible` | `packageName` | `done` once that package owns the focused window, or `WAIT_TIMEOUT` |
| `wait_screen_stable` | `WaitScreenStable` | `packageName`, `stableForMs` 1..30 000 (default 500), `signal` `TREE`/`PIXELS`/`ALL` (default `ALL`) | `done` once that package's focused window has not changed (per `signal`) for `stableForMs`; `WAIT_TIMEOUT` with detail `SCREEN_CHANGING` (never quiet long enough) or `APP_NOT_VISIBLE` (the package never owned the focused window) |
| `tap`, `long_tap` | `Tap`, `LongTap` (mutations) | `selector` (exactly one match) | `done` after the click |
| `set_text` | `SetText` (mutation) | `selector`, `text` (≤ 256 chars) | `done`: replaces the text; verified within 1 s |
| `type_text` | `TypeText` (mutation) | `selector`, `text` (≤ 256 chars) | `done`: appends via key events; verified |
| `clear_text` | `ClearText` (mutation) | `selector` | `done`: empties the field; verified |
| `swipe` | `Swipe` (mutation) | `selector`, `direction`, `distancePercent` 1..100 | `moved` = true: finger gesture across the element |
| `scroll` | `Scroll` (mutation) | `selector` (scrollable), `direction`, `distancePercent` | one scroll segment; `moved` = true while more content remains in that direction (UiAutomator semantics), false at the end or when no scroll event was observed |
| `scroll_until` | `ScrollUntil` (mutation) | `selector`, `container`, `direction` (default `DOWN`), `distancePercent`, `maxScrolls` 1..100 | `done` once the target is visible inside the container |
| `dump_hierarchy` | `DumpHierarchy` | – | `text` = accessibility XML (diagnostic only) |
| `screenshot` | `Screenshot` | – | PNG blob + `artifact` metadata (capability `artifact.screenshot.v1`) |
| `sync_bootstrap` | `SyncBootstrap` | `observedPid`, `observedStartToken` | `sync` |
| `sync_poll` | `SyncPoll` | `observedPid`, `observedStartToken`, `expectedProcessStartUuid`, `expectedSessionIdentity` | `sync` |

`direction` is the direction the content moves for scrolls and the finger for swipes.
`distancePercent` (default 80) is the gesture length as a percentage of the element's size.
Range checks live in the command constructors, so an out-of-range value cannot be built on
the host; a payload that violates them anyway (or is otherwise malformed) is `INVALID_REQUEST`,
and an unknown `op` is `UNSUPPORTED`, both decided on the reader lane before any UI access.
Either way the request ID is consumed.

Text observations (`SNAPSHOT.text`, and the verification behind `SET_TEXT`/`TYPE_TEXT`/
`CLEAR_TEXT`) exclude a displayed hint: an empty `EditText` reports its hint as accessibility
text with `isShowingHintText` set, and the driver reads that as empty text. Text *selectors*
still match what UiAutomator's `By.text` sees (see the gaps document).

Waits (`wait_visible`, `wait_gone`, `wait_app_visible`) poll on the driver at 50 ms until the
condition holds or the request deadline passes, and honour cancellation between polls. A
timeout is a `WAIT_TIMEOUT` error response, never an exception path.

`wait_screen_stable` is the only settle primitive and it is **explicit**: no other command waits
for animations or a quiet screen, and the driver never retries a command because the screen did
not change. Given `packageName`, `stableForMs` and `signal`, the driver samples that
package's focused application window and succeeds once the chosen signal has not changed for
`stableForMs`. `TREE` (Maestro's "app settled") is a fingerprint of the accessibility tree
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

A response is one of two variants, discriminated by `type`:

```json
{"type":"ok","result":{"kind":"count","count":3},"durationMs":20}
{"type":"error","code":"NOT_FOUND","detail":"END_REACHED","message":"…","durationMs":5}
```

`result` is one of the kinds below (`Results.kt`, sealed `CommandResult`); each command
produces exactly one kind, fixed by its `Returning<R>` type.

| `kind` | Class | Payload |
|---|---|---|
| `done` | `Done` | – (the command completed) |
| `bool` | `BoolResult` | `value` |
| `moved` | `Moved` | `moved` |
| `count` | `CountResult` | `count` |
| `text` | `TextResult` | `text` |
| `snapshot` | `SnapshotResult` | `snapshot` (`ElementSnapshot`) |
| `device_info` | `DeviceInfoResult` | `deviceInfo` |
| `artifact` | `ArtifactResult` | `artifact` (`ArtifactInfo`, see [Artifacts](#artifacts)) |
| `sync` | `SyncResult` | `state` (`SyncState`) |

### Selectors

A selector is a small versioned expression tree, never a string expression or XPath. Every
sum type is a discriminated object (`kind`), exactly like commands are discriminated on `op`:

```text
Selector {
  node:  Node
  scope: {kind: "aut"} (default) | {kind: "system", packageName}     allowlisted packages only
  pick:  {kind: "exactly_one"} (default) | {kind: "first"} | {kind: "at", index ≥ 0}
}
Node =
  | {kind: "match",    property: TEXT | CONTENT_DESCRIPTION | HINT | CLASS_NAME, value, mode = EXACT}
  | {kind: "flag",     property: ENABLED | CHECKED | CHECKABLE | CLICKABLE | FOCUSED | FOCUSABLE
                                 | LONG_CLICKABLE | SCROLLABLE | SELECTED, value = true}
  | {kind: "resource", name, packageName?}   with packageName: `packageName:id/name`; without: the exact resource name (Compose testTag)
  | {kind: "related",  relation: PARENT | ANCESTOR | CHILD | DESCENDANT, node: Node}
  | {kind: "all_of",   nodes: [Node, Node, …]}   conjunction, ≥ 2 operands
  | {kind: "any_of",   nodes: [Node, Node, …]}   disjunction, ≥ 2 operands
mode: EXACT | CONTAINS | STARTS_WITH | ENDS_WITH | REGEX
```

`Node.allOf` / `Node.anyOf` (and the infix `and` / `or`) normalise: nested combinators of the
same kind are flattened and a single operand is returned as is, so a chain of refinements is
one flat `all_of`. An unset `scope`/`pick` is the default; `at` with a negative index and
`system` with a blank package are unconstructible (constructor `require`, so a wire payload
carrying them fails to decode as `INVALID_REQUEST`).

Both sides validate the same limits before allocating a request ID or touching the UI: depth
≤ 32, ≤ 256 nodes, ≤ 1024 chars per string, no empty resource name or package, a combinator
needs at least two operands (`EMPTY_NODE`), a `match` with an empty `value` is only valid in
`EXACT` mode (`EMPTY_VALUE`: `CONTAINS ""` and the other modes would match every node, so a
`first` mutation would hit an arbitrary one), and `REGEX` must compile under RE2 (linear time; no
backreferences or lookaround). The rejection reason is returned as an `INVALID_SELECTOR`
detail. A resource with an explicit `packageName` must match the scope package; a `system`
scope outside the driver's allowlist is `SCOPE_DENIED`; a target and container with different
scope packages is `SCOPE_MISMATCH`.

`CommandValidation.validate(command)` dispatches by command type and validates every selector
it carries; `ScrollUntil` validates both its target and container. On the device,
`CommandValidation.validateSelector(selector)` also returns the query plan. A selector compiles
to one window-scoped `BySelector` (`ByBuilder` plus `UiWindow.findObjects` on the focused window of
the scope package) unless it contains something `BySelector` cannot hold: a `REGEX` match, an
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
- `timeoutMs` starts when the request is accepted. Queue residence consumes the deadline; a
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

Commands checkpoint before selector resolution, between wait polls, and between scroll
attempts. Immediately before the first irreversible platform call (`click`, text replacement,
key injection, the first scroll gesture) the command passes an atomic gate that refuses on
cancel, deadline, or a poisoned session and otherwise makes the command uncancellable. Once
open the gate stays open: passing it again (`scroll_until` gates once, before its first gesture)
never refuses. Waits that simply run out of time still report `WAIT_TIMEOUT`;
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
BLOB_START  JSON { blobId (UUID), mediaType, totalLength, sha256 }
BLOB_CHUNK  16-byte blobId || u32be chunkIndex || data (1..262144 bytes)
BLOB_END    JSON { blobId, byteCount, sha256 }
RESPONSE    {"type":"ok","result":{"kind":"artifact","artifact":{ blobId, mediaType, byteCount, sha256, width?, height? }}}
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

An `error` response has exactly one `code` from the closed taxonomy below, an optional stable
`detail` sub-reason, and an optional free-text `message` that clients must not branch on. An
`ok` response never carries a code. A host that receives a code it does not know
decodes it as `UNKNOWN` (treated as may-have-mutated, not retryable); a driver never sends
`UNKNOWN`. Adding a code is a minor protocol version change.

Two properties are defined per code. **May have mutated** means device state may have changed;
such a command must never be replayed blindly. **Retryable** is a hint for a caller-owned
policy; Tap itself never retries.

| Code | May have mutated | Retryable | Meaning / details |
|---|:-:|:-:|---|
| `INVALID_REQUEST` | no | no | Malformed or out-of-range request. `UNSUPPORTED_CHARACTERS`: text has no key-event mapping (rejected before input). |
| `INVALID_SELECTOR` | no | no | Selector rejected before any lookup. `SCOPE_DENIED`, `SCOPE_MISMATCH`, `SELECTOR_TOO_DEEP`, `SELECTOR_TOO_LARGE`, `STRING_TOO_LONG`, `EMPTY_NODE`, `EMPTY_VALUE`, `INVALID_REGEX`. |
| `UNSUPPORTED` | no | no | Unknown `op`. |
| `UNAUTHENTICATED` | no | no | Handshake failure (`AUTH_RESULT.error`). |
| `SESSION_MISMATCH` | no | no | Wrong session ID or generation. |
| `DUPLICATE_OR_STALE` | no | no | Request ID at or below the watermark. Not sent as a response: it prefixes the driver's `CLOSE` reason. |
| `OVERLOADED` | no | yes | Command queue full; the ID is still consumed. |
| `AUT_MISMATCH` | no | no | Observed AUT identity differs. `PROCESS_RESTARTED`, `PROCESS_MISMATCH` from synchronization. |
| `NOT_FOUND` | no | yes | Zero matches. `END_REACHED`, `MAX_SCROLLS` for `SCROLL_UNTIL`. |
| `AMBIGUOUS` | no | no | More than one match; returned before any input. |
| `NOT_INTERACTABLE` | no | yes | Target exists but cannot take the action (not editable, not scrollable, focus never arrived: `FOCUS_TIMEOUT`). |
| `STALE_DURING_COMMAND` | yes | no | Target changed after the mutation began. `FOCUS_LOST`, `TARGET_GONE`, `TARGET_AMBIGUOUS`. |
| `ACTION_REJECTED` | yes | no | Input was issued but did not take effect. `TEXT_MISMATCH`, `FOCUS_TIMEOUT`, `DEADLINE_AFTER_FOCUS`, `PARTIAL_INPUT`. |
| `WAIT_TIMEOUT` | no | yes | The waited condition stayed false until the timeout. `SCREEN_CHANGING`, `APP_NOT_VISIBLE` for `WAIT_SCREEN_STABLE`. |
| `CANCELLED` | no | yes | Stopped before mutation. `CANCELLED_IN_QUEUE`, `TRANSPORT_CLOSED`. |
| `DEADLINE_EXCEEDED` | no | yes | Deadline passed outside a normal wait result. `EXPIRED_IN_QUEUE`. |
| `AUT_NOT_INSTALLED` | no | no | Reserved; not yet emitted. |
| `AUT_CRASHED` | yes | no | Reserved; not yet emitted. |
| `AUT_ANR` | yes | no | Reserved; not yet emitted. |
| `SYNC_PROVIDER_UNAVAILABLE` | no | yes | Synchronization provider unusable. `CERTIFICATE_MISMATCH`, `UNINITIALIZED`, `MALFORMED_STATE`, `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`, `PROVIDER_POISONED`. |
| `DRIVER_UNHEALTHY` | no | no | Session poisoned by the watchdog (`WATCHDOG`, `HEARTBEAT_EXPIRED`); rebuild the session. |
| `TRANSPORT_LOST` | no | no | Host-side: no response and no mutation risk. |
| `INDETERMINATE` | yes | no | Mutation may have happened without a definitive result. `WATCHDOG`, `KEY_RELEASE_FAILED`; after the gate, the rewritten code's detail or name (`END_REACHED`, `MAX_SCROLLS`, `WAIT_TIMEOUT`, …). |
| `ARTIFACT_TRANSFER_FAILED` | yes | no | Blob capture or transfer failed. Driver: `CAPTURE_FAILED`, `ARTIFACT_TOO_LARGE`, `BLOB_INCOMPLETE`. Host verification: `BLOB_UNEXPECTED`, `BLOB_OUT_OF_ORDER`, `BLOB_LENGTH_MISMATCH`, `BLOB_CHECKSUM_MISMATCH`, `BLOB_INCOMPLETE`. |
| `PAYLOAD_TOO_LARGE` | yes | no | The command ran but its response exceeded the control payload limit. |
| `INTERNAL` | yes | no | Unexpected driver failure. |

`scroll_until` that scrolled and still did not find its target reports `INDETERMINATE` with
detail `END_REACHED` / `MAX_SCROLLS` / `WAIT_TIMEOUT` (the list position changed, so the
"nothing happened" codes would be false); one that fails before its first gesture still reports
`NOT_FOUND`/`AMBIGUOUS`/`WAIT_TIMEOUT`. A container that stops resolving after the first gesture
is `STALE_DURING_COMMAND` (`TARGET_GONE`/`TARGET_AMBIGUOUS`).

### Host exceptions

`CommandException` is sealed: `RemoteCommandException` wraps a driver error response (code,
detail, remote message, duration) and `CommandTransportException` covers `TRANSPORT_LOST` and
`INDETERMINATE` with the transmission state. Both carry operation, request ID, session
generation, device serial, rendered selector, timeout, and the code's retryable/may-have-mutated
flags. `DriverClient.send` returns the `Response` as data; `execute` returns the typed result
and throws the typed exception; `submit` returns a `PendingCommand` for cancellation.

## Compatibility Rules

- Framing and application versions are independent.
- Negotiation chooses the highest exact common version and cannot silently downgrade.
- Additive features require an authenticated capability.
- Existing field meanings do not change within a major version.
- Unknown optional handshake fields may be ignored only when the payload is canonical.
- Unknown `op` values fail with `UNSUPPORTED`; they never fall back implicitly.
- Connection loss never causes automatic mutation replay.

Golden canonical payload, negotiation, transcript-binding, incompatible-version, duplicate
key, and noncanonical JSON tests live under `contracts/protocol/src/test`. Golden request/response
fixtures — one request per command, one response per result kind and one per error code — live under
`contracts/protocol/src/test/resources/golden` and are checked by `GoldenMessageTest`; regenerate them
with `./gradlew :contracts:protocol:test -Dtap.golden.update=true` in the same change as the contract
edit that made them drift.

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

**JSON rather than protobuf** (*judgment call*). Payloads are tiny (a selector AST and a
few fields) and latency is dominated by UiAutomator, not encoding; `kotlinx.serialization`
is already shared by host and driver with no codegen in the Android build; fixtures and logs
are readable; and the handshake needs a canonical byte form to authenticate, which is
simple to define for JSON. The plan allowed either. What JSON lacks is a machine-readable
schema for other languages — today the contract is Kotlin data classes plus golden
fixtures. The cheap remedy is a JSON Schema generated from the models and checked against
the fixtures; protobuf would give typed clients for free but would be another wire change.

**One class per command, discriminated on the wire** (protocol 2.0). Protocol 1.0 had a
single `Request` with an `operation` enum and every field of every command as an optional,
and a single `Response` with `ok` and every result field as an optional. That shape needs a
hand-written table of which fields each operation requires, range checks that run after
decoding, callers that read `response.value` and hope it is the field their command fills, and
a matching pile of optionals in the proto. 2.0 puts each command in its own class with its
required fields and range checks in the constructor, marks mutations and targeted commands
with interfaces instead of an enum switch, and types the result per command (`Returning<R>`),
so an impossible request cannot be built and a result cannot be misread. The envelope
(`sessionId`, `generation`, `timeoutMs`) stays separate from the command because it is the
session layer's, not the command's. The wire discriminators (`op`, `kind`, `type`) are the
`@SerialName`s of the classes and nothing else; `Command.names`, the driver's dispatch and the
proto `oneof` case names are all derived from or checked against them. The server API mirrors
the same structure as `oneof`s so a client in any language sees the same per-command shape.

**Selectors are a sum-type expression tree, with `any_of`** (*deliberate departure from plan
§10, approved 2026-09-20*). The same principle as commands: `Node`, `Scope` and `Pick` are
discriminated objects, so a `system` scope always has its package, an `at` pick always has its
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

Protocol 2.0 does not yet expose events, multi-touch gestures, `session.shutdown`, or
`inspector.snapshot`. `AUT_NOT_INSTALLED`, `AUT_CRASHED`, and
`AUT_ANR` are defined but not yet emitted. See `.docs/framework-gaps.md` for the full list.
