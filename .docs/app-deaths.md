# App deaths: crashes and ANRs as results

Decision record + work plan (approved 2026-11-14). Builds on the first cut, `AppDeaths` in the
daemon (2026-11-04), which replaces a failed command's code with `AUT_CRASHED` / `AUT_ANR` when an
app the attachment started has a new `am_crash` / `am_anr` event.

## What the first cut leaves out

1. A death is reported only when a command fails with one of six codes. A test that passes, or
   fails in its own assertion, never learns that the app crashed.
2. The failure comes when the running wait ends, not when the app dies: a 60 s wait on a dead app
   takes 60 s.
3. The message is the raw event line: no stack trace, no fields a client can read.
4. Only apps started through the attachment (`launch`, `cold_launch`, `foreground`, `open_link`)
   are watched; an app opened from the launcher, a notification or another app is not.
5. There is no call that asks whether an app died, and no way to say a death is expected.

## What the competitors do (read at the commits `competitor-comparison.md` pins)

- **Maestro** `0ccf5a9c`: a crash becomes the flow's result only on iOS (`IOSDriver.findAppCrash`,
  from how the process ended); a failed flow whose app crashed throws `AppCrash` with the failing
  command's error suppressed. `AndroidDriver` does not override it: on Android it only writes
  `crash-report.txt` / `anr-report.txt` from logcat (`LogcatReader`: `FATAL EXCEPTION` blocks by
  `Process:`, native `Fatal signal` + `>>> name <<<` blocks, `ANR in` … `Completed ANR of`).
- **agent-device** `ddbfc855`: no Android crash detection; it recognises the "isn't responding /
  keeps stopping" dialog on screen. Crash symbolication is Apple only, and it keeps the agent's
  context small by writing the full report to a file and printing a compact summary.
- **Appium UiAutomator2** `911af30a`: nothing; the error text says to read logcat.

Adopted: Maestro's "a failed test whose app died failed because of the death, the original error
suppressed", its per-pid / `>>> name <<<` grouping of crash-buffer lines, and agent-device's
summary-plus-file shape for agents. Nothing is copied.

## Decisions

- **One record per death, kept by the daemon.** `AppDeaths` keeps every crash and ANR logged
  since the attachment opened, numbered from 1 (`sequence`, per attachment), at most 256 (the
  oldest are dropped). An ANR is a "death" here even though the process may live on: for a test
  both mean the app stopped doing what it was asked.
- **A live reader, not a read per question.** Each attachment runs one
  `adb -s SERIAL logcat -v epoch -b events -b crash -T <attach time> -s am_crash am_anr
  AndroidRuntime DEBUG` child for its lifetime. Its lines become records as they arrive, so asking
  costs nothing and a running wait can end the moment the app dies. The crash buffer's
  `AndroidRuntime` lines (grouped by the dying process's pid) and native `DEBUG` blocks (grouped by
  their `pid:` line) become the record's stack trace when the `am_crash` for that pid arrives. If
  the child exits it is started again from the last line's time (at most once a second); lines
  seen twice are dropped. While it is not running, a question falls back to one `logcat -d` read of
  the same buffers, as the first cut does.
- **Watched apps.** A package is watched from the moment an app call starts it (as now) or a
  command's selector names it with an exact `PROPERTY_PACKAGE_NAME` match at its top level (what
  `app(pkg)` scopes produce). Watching starts by catching up on the log, so a death logged before
  is not counted. A record is `watched` when its package (or a `pkg:process`) was watched before it
  died. Only watched deaths explain failures, end waits or fail tests; the others are listed for
  information.
- **The error carries the death.** `Error.app_death` (`tap.v1`, server-only: the driver never sets
  it) is the record that replaced the code. Each record replaces at most one failure, and none once a client took it: both make it
  `handled` (`AcknowledgeAppDeaths`).
- **Waits end early, mutations never.** While a wait (`wait_visible`, `wait_gone`,
  `wait_app_visible`, `wait_screen_stable`, `wait_state`, `wait_permission_prompt`, `await_toast`,
  `await_notification`) runs and a watched death arrives, the daemon cancels it (`CANCEL`) and
  answers `AUT_CRASHED` / `AUT_ANR` with detail `CANCELLED`. A mutation is never cancelled for a
  death: its outcome rules (`INDETERMINATE`, never replayed) stay as they are.
- **Asking.** `DeviceService.ListAppDeaths(after_sequence, package_name?)` returns the records and
  `last_sequence`; `sequence_only` returns just the latter, which is how a client marks "from here".
  `settle_ms` first waits until that long after the attachment's latest input (a mutating command
  or an app call): Android logs a crash 10–200 ms after the input that caused it. The server
  knows when input last reached the device, so a check after a test that ended long after its
  last tap waits nothing, and one with no watched app never waits.
- **Handled is the server's.** A death is `handled` once a client has it: an error carried it, or
  the client took it with `AcknowledgeAppDeaths` (a block that caught, allowed or expected it, an
  end-of-test check). A handled death explains no later failure and ends no wait. Keeping this in
  the client alone (the first draft) let a crash `expectDeath` had just returned turn the next,
  unrelated `NOT_FOUND` into `AUT_CRASHED`, also across tests on a shared device.
- **Clients decide what a death means for a test.** Each `AppDeath` carries the device's serial
  (client-side), so a failure names the device.
  - `Device.appDeaths()` / `App.deaths()` list them.
  - `guard { }` runs a block and throws `AppDiedException` (Python `AppDiedError`) if a watched
    death happened in it: from the command that failed because of it, at the end of the block when
    nothing failed, or in place of the block's own exception (suppressed in Kotlin, chained in
    Python). Inside a test framework it moves the failure to the point of the block; it is the
    tool for scripts and for code after which a test goes on.
  - `allowDeaths { }` lets watched deaths during the block happen: it takes them, so they fail no
    test, and otherwise changes nothing (exceptions, including a command's `AUT_CRASHED`, pass
    through). For monkey and stress sections. It replaced a `watchDeaths` that claimed to never
    throw for a death but did when a command met one.
  - `expectDeath(kind?) { }` requires one (of that kind) and returns it, waiting up to a timeout
    after the block. It takes only that death: a second crash during recovery still fails the test.
  - The test frameworks check at the end of every test (on by default, opt out per test or class,
    or for the run): a watched death that is not handled fails it; when the test failed anyway,
    the death becomes the failure with the original attached. The check covers the test's own
    setup and teardown (JUnit `@BeforeEach` / `@AfterEach`, pytest function fixtures).
  - Blocks and the check ask with `settle_ms` 300.
  - `tap-agent` is guarded for the whole session: every step's result lists deaths since the
    previous step (top frames inline, full trace in a file) and takes them, `deaths` lists them,
    `--expect-death` makes a step require one, and each step in the session's step history
    (`export`) lists the deaths it reported (from `ListAppDeaths`: the daemon's event log stays a
    log of calls). A step
    that reported an unexpected death exits 4 (MCP: an error result), so a script or an agent
    does not go on with a dead app. `deaths --wait` blocks until a watched app dies, the session
    ends or a timeout, for an agent to run in the background and be told when the app died; it
    only notifies (the next step still reports and takes the death), and its wait
    (`ListAppDeaths.wait_ms`) does not count as use, so it never keeps an idle session alive.

## Native-image compatibility (2026-11-20)

The 0.2.0 sanity run reproduced main CI's GraalVM 21.0.2 compiler failure in
`AppDeaths.follow`: incompatible frame states (`rethrowException`) while parsing the coroutine's
combined suspension and exception paths. Moving only the reader's `use` block was insufficient.
The follower now suspends only for one interruptible blocking iteration and its restart delay;
that iteration owns synchronous startup, reading, error handling and cleanup. The failure
counter still resets after each line, restarted reads still deduplicate, cancellation propagates,
and detach destroys the child and closes the blocked reader.

Verified with `:host:daemon:test` (including a reader-close assertion), `:host:daemon:nativeCompile`
on GraalVM 21.0.2, and the native CLI/RPC smoke scripts. No protocol, API, crash ownership or
retry policy changed; no compiler-check bypass or toolchain upgrade was needed.

## Not in scope

- Deaths with no crash event: low-memory kills, `System.exit`, `am force-stop` from outside
  (`dumpsys activity exit-info`, API 30+).
- Hiding or dismissing the crash / ANR dialog; ANR thread dumps (`/data/anr` is not readable
  without root).
- Symbolication (R8 `mapping.txt`, `ndk-stack`).

## Work plan (one PR, one commit per item)

1. **Daemon: record app deaths and list them** — the live reader, structured records with stack
   traces, watching packages named by selectors, `Error.app_death`, `ListAppDeaths`, waits ended
   early, `handled` and `AcknowledgeAppDeaths`, `settle_ms`. Proto, server-api, protocol-contract,
   errors guide, unit tests.
2. **Kotlin SDK + JUnit** — `AppDeath`, `CommandException.appDeath`, `AppDiedException`,
   `deaths()`, `guard` / `allowDeaths` / `expectDeath`, the end-of-test check (`tap.appDeaths`,
   `@AllowAppDeath`). Guide pages, unit tests, `AppDeathTest` on the fixture.
3. **Python client + pytest** — the same API and check (`--tap-app-deaths`,
   `@pytest.mark.tap_allow_app_death`).
4. **tap-agent** — deaths on every step, `deaths`, `--expect-death`, recordings.

Verification: unit tests per commit; the device suites (`:samples:fixture-tests`, Python) only
when the owner asks.
