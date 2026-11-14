# Crashes and ANRs

The server follows Android's crash log for as long as a device is attached and keeps a record of
every crash and ANR ("application not responding"): the exception class and message, the
process and pid, the time, and the stack trace. A test meets those records in four ways:

- a command that fails because the app died says so ([Errors](errors.md#a-crashed-app));
- `guard { }` throws when the app dies inside a block, like a `try` around it;
- `expectDeath { }` requires a death, for crash-recovery tests;
- the test framework fails a test whose app died while nobody was looking.

Only **watched** apps count: one started through this attachment (`launch`, `coldLaunch`,
`foreground`, `openLink`), or one a command's selector names (anything inside `device.app(pkg)`),
from that command on. Another app crashing in the background changes nothing.

## Asking

=== "Kotlin"

    ```kotlin
    val deaths = app.deaths()                 // this app's, oldest first
    val all = device.appDeaths()              // every app's; `watched` says which count
    deaths.forEach { println("${it.summary}\n${it.stackTrace}") }
    ```

Each `AppDeath` has `sequence` (1, 2, 3, … per attachment), `kind` (`CRASH` or `ANR`),
`packageName`, `processName`, `pid`, `at`, `exceptionClass`, `message` (an ANR's reason),
`stackTrace` (empty for an ANR, or when Android's crash buffer lost it), `watched` and
`reported` (a command's error already carried it). The list is the server's record, so asking is
cheap.

## A failed command carries the death

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

`guard` throws `AppDiedException` when a watched app died while the block ran:

| What happened in the block | What `guard` does |
|---|---|
| A Tap command failed because the app died | throws `AppDiedException`; the command's `CommandException` is its `cause` |
| The app died but no command failed (a crash after the last step, or in a background process) | throws `AppDiedException` when the block ends |
| Your own code threw (an assertion, say) and the app also died | throws `AppDiedException`, your exception attached as suppressed |
| Nothing died | the block's value or exception, unchanged |

`app.guard` watches only that app; `device.guard` every watched app. `watchDeaths { }` returns
the block's value together with the deaths and never throws for one.

The client learns of a death at the next Tap call, or when the block ends: `guard` then waits
`AppDeath.SETTLE` (300 ms), because Android logs a crash shortly after the input that caused it.
It cannot stop your own code at the moment of the crash.

## expectDeath: a crash on purpose

=== "Kotlin"

    ```kotlin
    val death = app.expectDeath { app.element(desc("crash")).tap() }
    assertEquals("java.lang.IllegalStateException", death.exceptionClass)
    app.coldLaunch()                           // and check what the app restored
    ```

`expectDeath` returns the death the block caused: the one a failing command carried, or the
first one logged during the block or within the timeout after it (the wait timeout by default).
A command that fails because of that death does not fail the block; anything else does. When
no death comes, it fails with `AssertionError`.

## The end-of-test check

After every test the framework asks each of the test's devices for watched deaths that nothing
handed to the test: no command failed with them, and no `guard`, `watchDeaths` or `expectDeath`
claimed them.

- A **passing** test whose app died fails with `AppDiedException`.
- A **failing** test whose app died fails with `AppDiedException` instead, its own failure
  attached as suppressed: the crash is the likelier cause.
- A skipped test stays skipped.

=== "Kotlin"

    On by default (`tap.appDeaths=fail`). `tap.appDeaths=off` turns it off for the run, and
    `@AllowAppDeath` on a test method or class for those tests. Other frameworks can run the same
    check with `device.deathSequence()` before the test and `device.takeUnhandledDeaths(mark)`
    after it.

## Limits

- Android reports an ANR only when input goes unanswered for about 5 s (and on Android 10 and
  older only once more input is waiting), so a hang with no input waiting stays a plain timeout.
- A process that ends with no crash event (killed for memory, `System.exit`) is not a death.
- Stack traces are not symbolicated: an R8-minified app's frames need its `mapping.txt`.
- The record keeps the newest 256 deaths per attachment.
