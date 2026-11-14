# Named agent step recordings

> **Superseded in part (2026-11-14, owner approved; see `.docs/app-logs.md`, "tap-agent: one
> history").** `record start|stop` is gone: every session records its steps by itself, per held
> connection, until release. `record list|clean` are `history [list|clean]`; the raw event
> history (`tap-events/1`) is `export --calls`; the format is `tap-agent-recording/2` (step
> `id`, `markers`). An unfinished or `unknown` step no longer blocks later input, and a full
> history rotates instead of refusing input. The text below is the original decision.

Decision (owner approved): `record` records high-level actions and explicit waits; `history`
prints the session's raw event log (`tap-events/1`); `export [NAME] [-o FILE]` exports a named
recording (`tap-agent-recording/1`); media capture is `media-record`. Start/stop/list/clean are
explicit, with names retained after session release.

## Ownership and persistence

Like Studio, a step recording belongs to its client: it contains intent the daemon event ring
cannot reconstruct. Do not tail that bounded ring or duplicate its SDK internals. No daemon,
wire or synchronization change is needed. Studio's `tap-recording/1` is not reused: it has its own
single-device/inferred-wait/protobuf step contract and is a different client. The agent writes
`tap-agent-recording/1`, describing its own high-level calls, actual devices, resolved SDK
selectors, timing and outcomes. No replay or generated test code is added.

Storage is private local SQLite under `TAP_AGENT_DIR/records` (default `.tap/agent/records`).
CLI and MCP processes share recordings when they share that directory. Names are unique within
it. One recording is active per agent session name, pinned to the held connection ID: a new
connection reusing a name cannot silently continue it. Stored exports/list/stop/clean work
without a running daemon. Explicit stop freezes a recording; release also stops its active
recording after successfully releasing the session. Unexpected termination can leave an active
recording or unfinished step for the user to inspect/stop. A name addresses one recording in the
directory, so `stop NAME`, `export NAME` and `clean NAME` work from any session or process; only
the name-less forms are scoped to the calling session.

The catalog uses short SQLite transactions; a separate per-recording SQLite lease serializes
steps and stop/clean across processes without holding a global writer lock during device calls.
An intent is committed before input, resolved selectors before their mutation, and outcome after.
A crash leaves an explicit unfinished step, never a falsely successful or silently missing one.
Further recorded input is refused until the user stops that recording and inspects the outcome.
No transmitted mutation is ever retried. Exports are consistent snapshots; exporting an active
recording does not stop it and is marked non-final.

## Outcomes

A step's status says what the device may have seen. `succeeded`; `rejected_before_input` when the
step failed before it sent anything; `failed` only when it sent one command and the error code
proves that command changed nothing (driver codes the protocol contract marks "may have
mutated: no", or a server refusal made before the device was touched); `unknown` for every other
failed action: a code that allows a change, a step that sends several inputs (tap then type, a
scroll loop, wake then unlock), or a read after the input. `unknown`, `running` and
`interrupted` block further recorded input until the recording is stopped. Agent methods wrap each
input call in `sending()`; a unit test runs every recorded action shape and checks that it does.

## Content and safety

Record actions and explicit waits, including known failures, once per public agent call. Do not
record snapshots, inspections, counts, polls, ref resolution, automatic post-action settle as a
separate step, recording controls, media capture or history/export. Mixed read/write commands
are classified by their arguments. Attach/detach/release are session controls, not app steps.
Resolved selectors replace transient ref strings; every step identifies its actual device.

Honor sensitive text flags before persistence; do not store command output or exception messages
(which can echo secrets). Sensitive selector text is omitted explicitly rather than pretending
a redacted selector is executable. Other intentionally supplied input is ordinary recording data:
users must avoid secret-bearing selectors/extras and mark secret text inputs sensitive. This is
not automatic secret detection or a promise that arbitrary app data is safe to publish.

Directories/files are 0700/0600; outputs are new exclusive files. Clean deletes stopped named
recordings, not active/in-flight ones. `clean --all` is explicit and refuses if any selected record
is active. It is logical deletion, not a secure-erasure guarantee. Bound recorded steps and bytes;
refuse new input before reaching a limit rather than evicting history silently. If outcome storage
fails after input, preserve that input's result and clearly warn not to repeat it; the durable
pending intent remains evidence of an incomplete outcome.

## Commands

```sh
tap-agent record start checkout
tap-agent record list
tap-agent record stop [checkout]
tap-agent export [checkout] [-o checkout.json]
tap-agent record clean checkout
tap-agent record clean --all
tap-agent history [-o history.json] [--after-seq N]
tap-agent media-record start --max-seconds 10
tap-agent media-record stop [-o evidence.mp4]
```

An omitted export name chooses the current active recording, otherwise the latest recording for
the selected agent session. Start never overwrites/appends to an existing name. Ordinary command
execution remains unchanged when no recording is active.

## Verification

- Agent unit tests, with the Python client's in-process fake daemon: the on-disk store (process
  exclusion through the SQLite lease, killed processes, private files, size bounds, a catalog
  created by another process without its schema, a failed schema creation), capture (resolved
  refs, redacted text and selectors, no exception text, pre/post-input storage failures), the
  outcome table per error code, compound and post-input failures, the input mark of every
  recorded action, offline export and CLI/MCP coverage.
- Pyright on the package and the strict documentation build.
- API 34 emulator, with a native daemon and no reboot: the three opt-in agent device checks,
  including a named recording started, filled, stopped and exported from separate CLI processes
  sharing one directory (resolved ref, redacted text, explicit wait, export after release, clean).
  API 29 was not run. CI's device job runs `.github/scripts/agent-smoke.sh`, which records a tap
  and a wait and checks both the history and the recording.
- Known gap: on the local machine under memory pressure the opt-in media check failed with
  `scrcpy produced no recording` while the UI and named-step checks passed. Its cause is not
  confirmed; the owner deferred it, and no retry or readiness workaround was added.

Storage requires a local filesystem supporting SQLite locking, hard links and owner-only POSIX
permissions; Windows has not been validated. Exported copies are separate artifacts, not removed
by clean.
