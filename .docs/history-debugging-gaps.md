# Debugging evidence: retained facts and remaining gaps

Owner requirement: history and recordings should support application debugging as completely
as possible. Optional presentation is acceptable; silently missing information is not. A read
flag cannot recover evidence that was never captured. Preserve redaction before storage and
never infer results or replay input to repair evidence.

## Addressed with time-window history

- Explicit time bounds and `history --last 30s` discover original retained archives even with
  no local steps, no current session and no live attachment. Step-page limits do not trim the
  requested device-log/RPC/death window. Receipt times use the host clock; original RPC start
  and device death times remain separate facts.
- Crashes and ANRs are independent durable entries in the connection activity archive, not
  just metadata on the next failing step. They share retention, named protection, original
  attachment identity, clock metadata and loss reporting with RPC activity.
- Known crash diagnostics (message and available Java/native stack) are redacted and retained.
  Default text shows explicit crash/ANR summaries; `--details` shows available stack traces;
  JSON includes retained diagnostics without requiring `--logs` or `--details`.
- Interrupted death followers/catch-up reads leave explicit gaps. Missing source stack traces
  are identified, not fabricated. Original device milliseconds survive even outside Python's
  supported calendar range.

## Remaining gaps (not implemented by this change)

| Evidence | Current boundary / way to obtain it |
|---|---|
| Successful query results | Durable RPC events describe calls and known failures, not full return values. A count, exists result, inspected node or arbitrary script assertion cannot generally be reconstructed afterward. Read it live or save it explicitly in the test report. Future work: bounded, classified, redacted result capture. |
| Full generic error context | Structured codes/reasons/detail are retained; driver `Error.message` / `last_state`, human gRPC status descriptions, agent command output and exception text are not a durable transcript. Crash diagnostics are now a classified exception, not permission to store arbitrary raw failure output. Future work: redacted error-context capture with password-node and size handling. |
| Historical screen and hierarchy | `screenshot` / `capture` create separate explicit artifacts. History does not automatically take, retain or embed a screenshot/hierarchy for every step or idle moment, nor index all those files in its portable document. It cannot reconstruct a past screen. Future work: optional evidence capture and durable artifact associations. |
| Video/audio | `media-record` is opt-in and separate. It must start before the incident and be finalized before release; unfinished media is discarded. History cannot create past video. Existing recording readiness/timing limits remain (`audio-recording.md`). |
| Transient accessibility events | Device `watch`/event observation and bounded toast history exist, but their full observations are not an independent durable history stream. Events Android never emits cannot be recovered, and observer expectations do not imply causality. Future work: bounded durable observation capture with explicit stream loss. |
| Full ANR thread dumps / complete native tombstones | The public logcat source supplies ANR reason metadata, not `/data/anr` thread dumps. Native debug blocks are retained when actually supplied by the source. Root/protected files, R8/NDK symbolication and exit-info/low-memory/`System.exit` diagnostics are outside the implemented death contract (`app-deaths.md`). No flag can promise inaccessible Android data. |
| Out-of-band app/device state changes | Tap-issued changes have RPC evidence. Arbitrary external changes, networking traffic, database contents and app business state are not continuously observed or inferred. Explicit SDK diagnostics/artifacts or app-side logging are needed. |
| Coverage outside capture | No attachment/daemon, opted-out device logs, Android buffer loss, unavailable source, expired evidence or hard storage/free-space failure can leave missing information. Protection bypasses rolling eviction, not hard limits. Gaps/errors must remain visible; cleanup and redaction do not securely erase previously delivered copies. |
| Deliberately sensitive/unclassified payloads | Marked secret input, clipboard data, binary contents and unknown wire fields are not an unrestricted durable raw transcript. Keep this privacy boundary; any richer classified capture needs its own redaction, size and ownership contract. |

The first priorities for additional work are redacted error context and explicit artifact
associations, followed by optional query-result and transient-event capture. They need
owner-approved API/capture choices and regression coverage, not a claim that a new export flag
retroactively supplies them. No synchronization redesign is implied.
