# Tap Protocol Contract

Date: 2026-09-18

Status: application protocol `1.0`; Phase 1 contract is additive and not yet complete.

This document describes the implemented wire contract. Planned but unimplemented features
(events, typed element handles, multi-gesture input) remain design work in
`android-e2e-framework-implementation-plan.md` and are not part of protocol 1.0 yet.

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
and response payloads are UTF-8 JSON. Connection frames use request ID zero; requests and
responses use a positive monotonically increasing ID.

Implemented frame types are `HELLO` (1), `CHALLENGE` (2), `AUTH` (3), `AUTH_RESULT` (4),
`REQUEST` (5), `RESPONSE` (6), `CLOSE` (7), `CANCEL` (8), `PING` (9), `PONG` (10),
`BLOB_START` (11), `BLOB_CHUNK` (12), and `BLOB_END` (13). Unknown framing versions, frame
types, flags, and invalid lengths fail the connection before payload decoding. After
authentication only `REQUEST`, `CANCEL`, `PING`, and `CLOSE` are legal from the host; any other
type (including blob frames) fails the connection. `CANCEL` carries the target request ID and
an empty payload; `PING`/`PONG` use request ID zero and an empty payload. Blob frames are
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
- sorted supported operation names and operation versions; and
- sorted supported application-protocol versions.

The host selects the highest exact common `major.minor` version and a sorted subset of offered
capabilities. Protocol 1.0 currently enables:

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

## Requests

Every request carries the authenticated `sessionId`, `sessionGeneration`, an operation,
`operationVersion`, and bounded `timeoutMs` (at most 120 000). Protocol 1.0 supports
operation version 1 for:

| Operation | Required fields | Result |
|---|---|---|
| `HEALTH` | – | `value=true` |
| `EXISTS` | `selector` | `value` = at least one match now |
| `WAIT_VISIBLE` | `selector` | `value=true`, or `WAIT_TIMEOUT` with `value=false` |
| `TAP`, `LONG_TAP` | `selector` (exactly one match) | `value=true` after the click |
| `SET_TEXT` | `selector`, `inputText` (≤ 256 chars) | replaces the text; verified within 1 s |
| `TYPE_TEXT` | `selector`, `inputText` (≤ 256 chars) | appends via key events; verified |
| `CLEAR_TEXT` | `selector` | empties the field; verified |
| `SWIPE` | `selector`, `direction`, `distancePercent` 1..100 | finger gesture across the element; `value=true` |
| `SCROLL` | `selector` (scrollable), `direction`, `distancePercent` | one scroll segment; `value` = content moved |
| `SCROLL_UNTIL` | `selector`, `containerSelector`, `maxScrolls` 1..100 | scrolls `direction` (default `DOWN`) until the target is visible |
| `DUMP_HIERARCHY` | – | `text` = accessibility XML (diagnostic only) |
| `SCREENSHOT` | – | PNG blob + `artifact` metadata (capability `artifact.screenshot.v1`) |
| `SYNC_BOOTSTRAP`, `SYNC_STATE` | `observedPid`, `observedStartToken` (+ expected identities for `SYNC_STATE`) | `syncState` |

`direction` is the direction the content moves for scrolls and the finger for swipes.
`distancePercent` (default 80) is the gesture length as a percentage of the element's size.
Missing or out-of-range fields return `INVALID_REQUEST` before any UI access.

An unknown operation version returns `UNSUPPORTED`. Its request ID is accepted before
validation and cannot be reused. IDs at or below the accepted watermark return
`DUPLICATE_OR_STALE`. A session-generation or session-ID mismatch returns `SESSION_MISMATCH`.
Mutating element targets and scroll containers require exactly one match. Zero matches return
`NOT_FOUND`; multiple matches return `AMBIGUOUS` before input is injected.

### Selectors

A selector is a small versioned AST, never a string expression or XPath:

```text
Selector {
  node: NodeSelector
  scope: AUT | SYSTEM          scopePackage required iff SYSTEM (allowlisted packages only)
  limit: EXACTLY_ONE | FIRST | AT
  index: Int?                  required iff AT
  acceptAccessibilityOrder     must be true for FIRST/AT (ORDER_NOT_ACCEPTED otherwise)
}
NodeSelector {
  text, contentDescription, hint, className: StringMatch { value, mode }
  resource: ResourceId { name, packageName? }   packageName defaults to the scope package
  checkable, checked, clickable, enabled, focusable, focused,
  longClickable, scrollable, selected: Boolean?
  parent, ancestor, child, descendant: NodeSelector?
}
mode: EXACT | CONTAINS | STARTS_WITH | ENDS_WITH | REGEX
```

Both sides validate the same limits before allocating a request ID or touching the UI: depth
≤ 32, ≤ 256 nodes, ≤ 1024 chars per string, no empty node or value, and `REGEX` must compile
under RE2 (linear time; no backreferences or lookaround). Every node must constrain something.
The rejection reason is returned as an `INVALID_SELECTOR` detail. A resource with an explicit
`packageName` must match the scope package; a `SYSTEM` scope outside the driver's allowlist is
`SCOPE_DENIED`; a target and container with different scope packages is `SCOPE_MISMATCH`.

Non-regex selectors compile to one window-scoped `BySelector` (`ByBuilder` plus
`UiWindow.findObjects` on the focused window of the scope package). Regex selectors take a
traversal plan that walks the same window's object tree once, reading each node once; both
plans return the same match set and neither dumps the hierarchy. `EXACTLY_ONE` fetches at most
two matches to decide `AMBIGUOUS`; `FIRST` and `AT n` take accessibility order and return
`NOT_FOUND` when the index is absent.

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
cancel, deadline, or a poisoned session and otherwise makes the command uncancellable. Waits
that simply run out of time still report `WAIT_TIMEOUT`; `DEADLINE_EXCEEDED` is reserved for
expiry outside a normal condition result.

### Watchdog

If the running command is still executing more than the uninterruptible grace period
(default 10 s, instrumentation argument `tapUninterruptibleGraceMs`) after its deadline, the
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
RESPONSE    ok=true, artifact = { blobId, mediaType, byteCount, sha256, width?, height? }
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
`WRITTEN` state; the caller still awaits the driver's single terminal response. A missing
terminal response, an unknown response ID, or any read failure poisons the client: in-flight
mutating requests fail as `INDETERMINATE`, queries as `TRANSPORT_LOST`, and later submissions
fail before writing.

## Errors

A failed response has `ok=false`, exactly one `errorCode` from the closed taxonomy below, an
optional stable `detail` sub-reason, and an optional free-text `message` that clients must not
branch on. `ok=true` responses never carry a code. A host that receives a code it does not know
decodes it as `UNKNOWN` (treated as may-have-mutated, not retryable); a driver never sends
`UNKNOWN`. Adding a code is a minor protocol version change.

Two properties are defined per code. **May have mutated** means device state may have changed;
such a command must never be replayed blindly. **Retryable** is a hint for a caller-owned
policy; Tap itself never retries.

| Code | May have mutated | Retryable | Meaning / details |
|---|:-:|:-:|---|
| `INVALID_REQUEST` | no | no | Malformed or out-of-range request. `UNSUPPORTED_CHARACTERS`: text has no key-event mapping (rejected before input). |
| `INVALID_SELECTOR` | no | no | Selector rejected before any lookup. `SCOPE_DENIED`, `SCOPE_PACKAGE_REQUIRED`, `SCOPE_PACKAGE_UNEXPECTED`, `SCOPE_MISMATCH`, `SELECTOR_TOO_DEEP`, `SELECTOR_TOO_LARGE`, `STRING_TOO_LONG`, `EMPTY_NODE`, `EMPTY_VALUE`, `INVALID_REGEX`, `INDEX_REQUIRED`, `INDEX_UNEXPECTED`, `ORDER_NOT_ACCEPTED`. |
| `UNSUPPORTED` | no | no | Unknown operation, operation version, or enum value. |
| `UNAUTHENTICATED` | no | no | Handshake failure (`AUTH_RESULT.error`). |
| `SESSION_MISMATCH` | no | no | Wrong session ID or generation. |
| `DUPLICATE_OR_STALE` | no | no | Request ID at or below the watermark. |
| `OVERLOADED` | no | yes | Command queue full; the ID is still consumed. |
| `AUT_MISMATCH` | no | no | Observed AUT identity differs. `PROCESS_RESTARTED`, `PROCESS_MISMATCH` from synchronization. |
| `NOT_FOUND` | no | yes | Zero matches. `END_REACHED`, `MAX_SCROLLS` for `SCROLL_UNTIL`. |
| `AMBIGUOUS` | no | no | More than one match; returned before any input. |
| `NOT_INTERACTABLE` | no | yes | Target exists but cannot take the action (not editable, not scrollable, focus never arrived: `FOCUS_TIMEOUT`). |
| `STALE_DURING_COMMAND` | yes | no | Target changed after the mutation began. `FOCUS_LOST`, `TARGET_GONE`, `TARGET_AMBIGUOUS`. |
| `ACTION_REJECTED` | yes | no | Input was issued but did not take effect. `TEXT_MISMATCH`, `FOCUS_TIMEOUT`, `DEADLINE_AFTER_FOCUS`, `PARTIAL_INPUT`. |
| `WAIT_TIMEOUT` | no | yes | The waited condition stayed false until the timeout. |
| `CANCELLED` | no | yes | Stopped before mutation. `CANCELLED_IN_QUEUE`, `TRANSPORT_CLOSED`. |
| `DEADLINE_EXCEEDED` | no | yes | Deadline passed outside a normal wait result. `EXPIRED_IN_QUEUE`. |
| `AUT_NOT_INSTALLED` | no | no | Reserved; not yet emitted. |
| `AUT_CRASHED` | yes | no | Reserved; not yet emitted. |
| `AUT_ANR` | yes | no | Reserved; not yet emitted. |
| `SYNC_PROVIDER_UNAVAILABLE` | no | yes | Synchronization provider unusable. `CERTIFICATE_MISMATCH`, `UNINITIALIZED`, `MALFORMED_STATE`, `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`, `PROVIDER_POISONED`. |
| `DRIVER_UNHEALTHY` | no | no | Session poisoned by the watchdog (`WATCHDOG`, `HEARTBEAT_EXPIRED`); rebuild the session. |
| `TRANSPORT_LOST` | no | no | Host-side: no response and no mutation risk. |
| `INDETERMINATE` | yes | no | Mutation may have happened without a definitive result. `WATCHDOG`, `KEY_RELEASE_FAILED`. |
| `ARTIFACT_TRANSFER_FAILED` | yes | no | Blob capture or transfer failed. Driver: `CAPTURE_FAILED`, `ARTIFACT_TOO_LARGE`, `BLOB_INCOMPLETE`. Host verification: `BLOB_UNEXPECTED`, `BLOB_OUT_OF_ORDER`, `BLOB_LENGTH_MISMATCH`, `BLOB_CHECKSUM_MISMATCH`, `BLOB_INCOMPLETE`. |
| `PAYLOAD_TOO_LARGE` | yes | no | The command ran but its response exceeded the control payload limit. |
| `INTERNAL` | yes | no | Unexpected driver failure. |

`SCROLL_UNTIL` reports `NOT_FOUND`/`WAIT_TIMEOUT` after performing scroll gestures because
re-issuing the search is safe; a container that stops resolving mid-search is
`STALE_DURING_COMMAND`.

### Host exceptions

`CommandException` is sealed: `RemoteCommandException` wraps a driver error response (code,
detail, remote message, duration) and `CommandTransportException` covers `TRANSPORT_LOST` and
`INDETERMINATE` with the transmission state. Both carry operation, request ID, session
generation, device serial, rendered selector, timeout, and the code's retryable/may-have-mutated
flags. `DriverClient.execute` still returns the response; `executeOrThrow`/`awaitOrThrow`
throw the typed exception.

## Compatibility Rules

- Framing and application versions are independent.
- Negotiation chooses the highest exact common version and cannot silently downgrade.
- Additive features require an authenticated capability.
- Existing field meanings do not change within a major version.
- Unknown optional handshake fields may be ignored only when the payload is canonical.
- Unknown operation versions fail with `UNSUPPORTED`; they never fall back implicitly.
- Connection loss never causes automatic mutation replay.

Golden canonical payload, negotiation, transcript-binding, incompatible-version, duplicate
key, and noncanonical JSON tests live under `protocol/src/test`. Golden request/response
fixtures — one request per operation and one response per error code — live under
`protocol/src/test/resources/golden` and are checked by `GoldenMessageTest`; regenerate them
with `./gradlew :protocol:test -Dtap.golden.update=true` in the same change as the contract
edit that made them drift.

## Not Yet Implemented

Protocol 1.0 does not yet expose events or multi-touch gestures. `AUT_NOT_INSTALLED`,
`AUT_CRASHED`, and `AUT_ANR` are defined but not yet emitted.
