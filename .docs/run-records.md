# Client-owned run records

Status: implemented first format. This is the reporting foundation from plan §19, not the
HTML/JUnit adapter, protocol-action stream or cross-worker aggregator.

## Ownership and scope

A test run belongs to the runner, not the daemon: one pytest process/xdist worker or one JUnit
Platform launcher execution. The server still owns no roles or run leases. Both runners use
one on-disk contract, with no host dependency on either client. Scripts using the SDK directly
continue to save typed artifacts themselves; there is no new implicit recording for scripts.

The pytest plugin starts records before setup for a test whose fixture closure includes
`tap_client`, `tap_connection`, `tap_devices` or `tap_device`. It reads all three pytest reports
and finalizes the test after teardown. The session hook finalizes the run even on a nonzero
exit. Each worker has an independent UUID, so no shared file locking or controller-side merge
is necessary.

The JUnit Platform `TestExecutionListener`, discovered through ServiceLoader, starts a run for
a test plan containing `@TapTest` classes. It observes final outcomes, rather than deciding
results in `AfterEachCallback`, and records container failures when class/launcher setup
prevents a test body. The extension only contributes device identities and evidence while
attachments are live. A thread-local stack binds the listener's current test to its extension;
nested launcher executions cannot borrow an outer test's evidence directory. Report failures
are isolated from test results. A process killed before finalization leaves a running record.

## Format

Default root: `tap-artifacts` for pytest, `build/tap-artifacts` for JUnit. Every run gets a UUID:

```text
ROOT/RUN_ID/
  run.json
  events.jsonl
  manifest.json
  tests/TEST_ID/ATTEMPT/
    failure.txt
    DEVICE_ID/GENERATION/PART.EXT
```

`TEST_ID` is SHA-256 of the runner identity (pytest node ID or JUnit unique ID). `ATTEMPT` is a
positive per-identity counter. Pytest starts a new attempt for each setup/teardown cycle,
including retry plugins that loop the internal protocol without repeating the outer hook.
Retries entirely inside a test body are not separate runner attempts. Parameterized and
repeated invocations have distinct runner identities. `DEVICE_ID` hashes the role/serial pair
instead of interpolating unsafe strings into paths. The manifest retains readable identities.
This refines the plan's illustrative `ROLE-SERIAL` component for path safety; the hierarchy
and ownership boundaries are unchanged.

- **run.json (`schema: tap-run/1`)**: run ID, runner, pid, engine version of the client's
  bindings, API compatibility epoch, wall-clock start/end, run status, `recording_status` and a
  test-attempt list. Each attempt has its test ID, original identity, attempt number, status,
  and wall-clock start/end, plus the primary exception type when reported (not its message).
  Test statuses are running, passed, failed, skipped or incomplete.
  Run statuses are running, passed, failed or interrupted (pytest). These are runner outcomes,
  not assertions that a daemon/device remained healthy.
- **events.jsonl (`schema: tap-events/1` in every row)**: run ID, increasing sequence,
  wall-clock milliseconds, elapsed monotonic nanoseconds, type and type-specific fields.
  Types: run_started/run_finished, test_started/test_finished, device_attached, artifact_saved,
  collection_error, and JUnit container_finished. Test start/end rows carry the attempt record;
  artifact/device/error rows use `test: TEST_ID/ATTEMPT`. Collection errors carry exception
  type, part and device identity when available, never the exception message. No bearer tokens,
  command payloads or full exception text are deliberately added to lifecycle records.
- **manifest.json (`schema: tap-artifacts/1`)**: run ID, artifact rows and collection errors.
  Each artifact row holds test/attempt, part, slash-separated relative path, media type, byte
  size, SHA-256, and optional role/serial/generation. Artifacts without a device (failure stack)
  have null device fields. SDK capture failures and per-file write failures are independent;
  a missing screenshot does not prevent saving logcat. Unavailable device-log text stays in
  the relevant artifact, as in the existing DeviceLogs contract.

JSONL is append-only under a per-run lock, flushed and fsynced per complete record; events are
not accumulated in memory. A killed process may leave an unterminated last line, which readers
must ignore. Metadata and manifests are flushed then replaced using a same-directory atomic
rename. They are independent snapshots, not a multi-file transaction. The final event precedes
final metadata publication; missing final metadata still means incomplete finalization.

`recording_status` is recording until finalization, complete on normal publication, or
incomplete after a recoverable reporting failure. A reporting error never changes the runner's
real exit status. If storage is inaccessible, even that marker may be impossible to publish;
the runner prints a generic diagnostic without copying exception messages into it. Artifacts
and test-attempt metadata may stay in memory; the event stream itself does not.

## Configuration and privacy

Run records are enabled by default. `TAP_REPORT=off` / pytest `tap_report=off` / JUnit
`tap.report=off` disables them and preserves legacy automatic-failure paths. Capture is a
separate decision: `TAP_CAPTURE=off` suppresses device evidence, not test outcomes. Manual
`capture().save_to`/`saveTo` calls keep their existing behavior and are not automatically indexed.

Test identities can contain parameter values. Failure stacks, screenshots, hierarchy dumps
and logcat can contain application data. Hashing paths does not anonymize the manifest or
identity list. The run directory is sensitive evidence, not an anonymized telemetry feed;
no environment dump, argv, endpoint descriptor or authentication token is collected.

## Verification and remaining work

`RunRecordsTest` exercises atomic snapshots, parallel complete JSONL rows, attempt isolation,
hashes, ServiceLoader registration, launcher outcomes, container failures and reporting I/O
failure isolation. The bridge test exercises evidence indexing through the real extension
with an in-process fake daemon. Python `test_run_records.py` exercises the same format, parallel
writes, partial capture, and real pytest subprocesses with setup/call/teardown/skip outcomes,
report opt-out and unwritable output. Device checks are recorded in the PR, not inferred from
these unit tests.

Still deferred: per-command events, cross-worker aggregation, manual-artifact indexing,
streamed device logcat, custom JUnit XML/HTML, and reporting adapters. Capture keeps its
existing teardown ordering: a failure observed only after attachments have already been
detached still gets an outcome but cannot produce new live device evidence. The existing public
artifact guide documents this first format without claiming those features are complete.
