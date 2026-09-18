# Tap Protocol Contract

Date: 2026-09-18

Status: application protocol `1.0`; Phase 1 contract is additive and not yet complete.

This document describes the implemented wire contract. Planned but unimplemented frame types,
operations, and cancellation semantics remain design work in
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
`REQUEST` (5), `RESPONSE` (6), `CLOSE` (7), `CANCEL` (8), `PING` (9), and `PONG` (10). Unknown
framing versions, frame types, flags, and invalid lengths fail the connection before payload
decoding. After authentication only `REQUEST`, `CANCEL`, `PING`, and `CLOSE` are legal from the
host; any other type fails the connection. `CANCEL` carries the target request ID and an empty
payload; `PING`/`PONG` use request ID zero and an empty payload.

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
`operationVersion`, and bounded `timeoutMs`. Protocol 1.0 supports operation version 1 for:

```text
HEALTH EXISTS TAP WAIT_VISIBLE DUMP_HIERARCHY SET_TEXT TYPE_TEXT
SCROLL_UNTIL SYNC_BOOTSTRAP SYNC_STATE
```

An unknown operation version returns `UNSUPPORTED`. Its request ID is accepted before
validation and cannot be reused. IDs at or below the accepted watermark return
`DUPLICATE_OR_STALE`. A session-generation or session-ID mismatch returns `SESSION_MISMATCH`.
Mutating element targets and scroll containers require exactly one match. Zero matches return
`NOT_FOUND`; multiple matches return `AMBIGUOUS` before input is injected.

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

### Host client

The host writes each request ID and complete frame under one per-session transport mutex, so
IDs are strictly increasing on the socket even with concurrent callers. A dedicated reader
thread demultiplexes `RESPONSE` and `PONG`. `cancel` sends `CANCEL` only for a request in the
`WRITTEN` state; the caller still awaits the driver's single terminal response. A missing
terminal response, an unknown response ID, or any read failure poisons the client: in-flight
mutating requests fail as `INDETERMINATE`, queries as `TRANSPORT_LOST`, and later submissions
fail before writing.

## Compatibility Rules

- Framing and application versions are independent.
- Negotiation chooses the highest exact common version and cannot silently downgrade.
- Additive features require an authenticated capability.
- Existing field meanings do not change within a major version.
- Unknown optional handshake fields may be ignored only when the payload is canonical.
- Unknown operation versions fail with `UNSUPPORTED`; they never fall back implicitly.
- Connection loss never causes automatic mutation replay.

Golden canonical payload, negotiation, transcript-binding, incompatible-version, duplicate
key, and noncanonical JSON tests live under `protocol/src/test`.

## Not Yet Implemented

Protocol 1.0 does not yet expose events, binary blobs, screenshots, or a complete typed
remote-error model. Error codes are still strings; the codes emitted by the execution pipeline
are `CANCELLED`, `DEADLINE_EXCEEDED`, `OVERLOADED`, `DRIVER_UNHEALTHY`, `INDETERMINATE`, and
`INTERNAL`. The driver does not yet require periodic host heartbeats; `PING` is host-initiated
only.
