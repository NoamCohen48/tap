# Crashes and ANRs

The server follows Android's crash log for as long as a device is attached and keeps a record of
every crash and ANR ("application not responding"): the exception class and message, the
process and pid, the time, and the stack trace. A test meets those records in four ways:

- a command that fails because the app died says so ([Errors](errors.md#a-crashed-app));
- `guard` throws when the app dies inside a block, like a `try` around it;
- `expect_death` / `expectDeath` requires a death, for crash-recovery tests;
- the test framework fails a test whose app died while nobody was looking.

Only **watched** apps count: one started through this attachment (`launch`, `cold_launch`,
`foreground`, `open_link`), or one a command's selector names (anything inside `device.app(pkg)`),
from that command on. Another app crashing in the background changes nothing.

## Asking

=== "Python"

    ```python
    deaths = app.deaths()                     # this app's, oldest first
    every = device.app_deaths()               # every app's; `watched` says which count
    for death in deaths:
        print(death.summary, death.stack_trace, sep="\n")
    ```

=== "Kotlin"

    ```kotlin
    val deaths = app.deaths()                 // this app's, oldest first
    val all = device.appDeaths()              // every app's; `watched` says which count
    deaths.forEach { println("${it.summary}\n${it.stackTrace}") }
    ```

Each `AppDeath` has `sequence` (1, 2, 3, … per attachment), `kind` (crash or ANR), the package
and process names, `pid`, `at`, the exception class, `message` (an ANR's reason), the stack trace
(empty for an ANR, or when Android's crash buffer lost it), `watched` and `reported` (a command's
error already carried it); Python spells them in snake case (`package_name`, `stack_trace`).
The list is the server's record, so asking is cheap.

## A failed command carries the death

=== "Python"

    ```python
    try:
        app.wait(text("Order placed")).visible()
    except CommandError as e:
        death = e.app_death                    # set for AUT_CRASHED / AUT_ANR
    ```

=== "Kotlin"

    ```kotlin
    try {
        app.await(text("Order placed")).visible()
    } catch (e: CommandException) {
        val death = e.appDeath               // non-null for AUT_CRASHED / AUT_ANR
    }
    ```

A wait that is running when the app dies ends at once instead of running to its timeout.

## guard: catch a death in a block

=== "Python"

    ```python
    try:
        with app.guard():
            app.element(text("Pay")).tap()
            app.wait(text("Order placed")).visible()
    except AppDied as e:
        print(e.death.exception_class)         # java.lang.IllegalStateException
        print(e.death.stack_trace)
    ```

=== "Kotlin"

    ```kotlin
    try {
        app.guard {
            app.element(text("Pay")).tap()
            app.await(text("Order placed")).visible()
        }
    } catch (e: AppDiedException) {
        println(e.death.exceptionClass)       // java.lang.IllegalStateException
        println(e.death.stackTrace)
    }
    ```

`guard` raises `AppDied` (Python) / `AppDiedException` (Kotlin) when a watched app died while
the block ran:

| What happened in the block | What `guard` does |
|---|---|
| A Tap command failed because the app died | raises; the command's error is its cause |
| The app died but no command failed (a crash after the last step, or in a background process) | raises when the block ends |
| Your own code failed (an assertion, say) and the app also died | raises, your failure chained (Python) or suppressed (Kotlin) |
| Nothing died | the block's value or exception, unchanged |

`app.guard` watches only that app; `device.guard` every watched app. `watch_deaths()` /
`watchDeaths { }` hands back the deaths during the block and never raises for one.

The client learns of a death at the next Tap call, or when the block ends: `guard` then waits
300 ms (`AppDeath.SETTLE` in Kotlin), because Android logs a crash shortly after the input that
caused it. It cannot stop your own code at the moment of the crash.

## Expecting a crash

=== "Python"

    ```python
    with app.expect_death() as expected:
        app.element(desc("crash")).tap()
    assert expected.death.exception_class == "java.lang.IllegalStateException"
    app.cold_launch()                          # and check what the app restored
    ```

=== "Kotlin"

    ```kotlin
    val death = app.expectDeath { app.element(desc("crash")).tap() }
    assertEquals("java.lang.IllegalStateException", death.exceptionClass)
    app.coldLaunch()                           // and check what the app restored
    ```

`expect_death` / `expectDeath` hands back the death the block caused: the one a failing command
carried, or the first one logged during the block or within the timeout after it (the wait
timeout by default). A command that fails because of that death does not fail the block;
anything else does. When no death comes, it fails with `AssertionError`.

## The end-of-test check

After every test the framework asks each of the test's devices for watched deaths that nothing
handed to the test: no command failed with them, and no `guard`, `watch_deaths` /
`watchDeaths` or `expect_death` / `expectDeath` claimed them.

- A **passing** test whose app died fails with `AppDied` / `AppDiedException`.
- A **failing** test whose app died fails with it instead, its own failure attached: the crash
  is the likelier cause.
- A skipped test stays skipped.

=== "Python"

    On by default (`tap_app_deaths = fail` / `TAP_APP_DEATHS`). `off` turns it off for the run,
    and `@pytest.mark.tap_allow_app_death` on a test, class or module for those tests. In
    Python the test's own failure is chained (`raise AppDied(...) from failure`) rather than
    suppressed. Other runners can run the same check with `device.death_sequence()` before the
    test and `device.take_unhandled_deaths(mark)` after it.

=== "Kotlin"

    On by default (`tap.appDeaths=fail`). `tap.appDeaths=off` turns it off for the run, and
    `@AllowAppDeath` on a test method or class for those tests. Other frameworks can run the same
    check with `device.deathSequence()` before the test and `device.takeUnhandledDeaths(mark)`
    after it.

## Tap agent

`tap-agent` prints the new deaths of watched apps after every step and takes `--expect-death`
on an action; `tap-agent deaths` lists the record. See
[A session, step by step](../agent/session.md#when-the-app-crashes).

## Limits

- Android reports an ANR only when input goes unanswered for about 5 s (and on Android 10 and
  older only once more input is waiting), so a hang with no input waiting stays a plain timeout.
- A process that ends with no crash event (killed for memory, `System.exit`) is not a death.
- Stack traces are not symbolicated: an R8-minified app's frames need its `mapping.txt`.
- The record keeps the newest 256 deaths per attachment.
