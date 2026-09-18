# Tap Protocol Contract

Date: 2026-09-17

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
`REQUEST` (5), `RESPONSE` (6), and `CLOSE` (7). Unknown framing versions, frame types, flags,
and invalid lengths fail the connection before payload decoding.

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

Protocol 1.0 does not yet expose cancellation, heartbeats, events, binary blobs, screenshots,
or a complete typed remote-error model. Those require the planned independent socket reader,
bounded command queue, serialized executor, watchdog, and terminal-state machine. Adding frame
enum values without that execution model would provide unsafe cancellation semantics.
