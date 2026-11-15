# Crashes and ANRs

The server follows Android's crash log for as long as a device is attached and keeps a record of
every crash and ANR ("application not responding"): the exception class and message, the
process and pid, the time, and the stack trace. A test meets those records in these ways:

- a command that fails because the app died says so ([Errors](errors.md#a-crashed-app));
- the test framework fails a test whose app died while nobody was looking;
- `expect_death` / `expectDeath` requires a death, for crash-recovery tests;
- `allow_deaths` / `allowDeaths` lets the app die in a block, for monkey and stress sections;
- `guard` throws when the app dies inside a block, like a `try` around it.

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
(empty for an ANR, or when Android's crash buffer lost it), `watched`, `handled` and `serial` (the
device it died on); Python spells them in snake case (`package_name`, `stack_trace`). The list
is the server's record, so asking is cheap.

To wait for the next death, `device.await_app_deaths(mark, timeout)` /
`device.awaitAppDeaths(mark, timeout)` blocks until one after `mark` (from `death_sequence()` /
`deathSequence()`) is recorded, or the timeout passes, and changes nothing.

A death is **handled** once your test has it: a command's error carried it, or a block or the
end-of-test check below took it. The server keeps that flag, so a handled death fails no test
and explains no later failure: after `expect_death`, a missing element is a plain `NOT_FOUND`
again, not the crash you expected.

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

## The end-of-test check

After every test the framework asks each of the test's devices for watched deaths that are not
handled: no command failed with them, and no `guard`, `allow_deaths` / `allowDeaths` or
`expect_death` / `expectDeath` took them. It covers the test's setup and teardown too.

- A **passing** test whose app died fails with `AppDiedError` / `AppDiedException`.
- A **failing** test whose app died fails with it instead, its own failure attached: the crash
  is the likelier cause.
- A skipped test stays skipped.

Each death in the message names its device (`[emulator-5554] com.shop crashed …`). The check
first waits until 300 ms after the device's latest input, because Android logs a crash shortly
after the input that caused it; a test that ended later than that waits nothing.

=== "Python"

    On by default (`tap_app_deaths = fail` / `TAP_APP_DEATHS`). `off` turns it off for the run,
    and `@pytest.mark.tap_allow_app_death` on a test, class or module for those tests. A death
    while another fixture of the test is set up is the test's; one during that fixture's
    teardown fails the teardown of a test that passed. The test's own failure is chained
    (`raise AppDiedError(...) from failure`) rather than suppressed. Other runners can run the
    same check with `device.death_sequence()` before the test and
    `device.take_unhandled_deaths(mark)` after it.

=== "Kotlin"

    On by default (`tap.appDeaths=fail`). `tap.appDeaths=off` turns it off for the run, and
    `@AllowAppDeath` on a test method or class (its subclasses and `@Nested` classes too) for
    those tests. It covers `@BeforeEach` and `@AfterEach`: a death in an `@AfterEach` after a
    failing test is attached to that failure. Other frameworks can run the same check with
    `device.deathSequence()` before the test and `device.takeUnhandledDeaths(mark)` after it.

## Expecting a crash

=== "Python"

    ```python
    with app.expect_death(AppDeathKind.CRASH) as expected:
        app.element(desc("crash")).tap()
    assert expected.death.exception_class == "java.lang.IllegalStateException"
    app.cold_launch()                          # and check what the app restored
    ```

=== "Kotlin"

    ```kotlin
    val death = app.expectDeath(AppDeath.Kind.CRASH) { app.element(desc("crash")).tap() }
    assertEquals("java.lang.IllegalStateException", death.exceptionClass)
    app.coldLaunch()                           // and check what the app restored
    ```

`expect_death` / `expectDeath` hands back the death the block caused (of that kind, when you pass one): the one a
failing command carried, or the first one logged during the block or within the timeout after it
(the wait timeout by default). A command that fails because of that death
does not fail the block; anything else does. When no death comes, it fails with
`AssertionError`. Only that death is taken: a second crash while the app recovers still fails
the test. In Python, `expected.death` is set once the block has ended.

## Letting the app die

=== "Python"

    ```python
    with app.allow_deaths() as allowed:
        for _ in range(200):
            random_gesture(app)
    print([d.summary for d in allowed.deaths])
    ```

=== "Kotlin"

    ```kotlin
    app.allowDeaths {
        repeat(200) { randomGesture(app) }
    }
    println(app.deaths().map { it.summary })
    ```

`allow_deaths` / `allowDeaths` takes the watched deaths during the block, so they fail no test,
and changes nothing else: what the block returns or raises comes through as it is, including a
command's `AUT_CRASHED` error. Use it for a section of a test; `@AllowAppDeath` /
`tap_allow_app_death` for a whole test. `app.allow_deaths` takes only that app's deaths;
`device.allow_deaths` every watched app's.

## guard: catch a death in a block

=== "Python"

    ```python
    try:
        with app.guard():
            app.element(text("Pay")).tap()
            app.wait(text("Order placed")).visible()
    except AppDiedError as e:
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

`guard` raises `AppDiedError` (Python) / `AppDiedException` (Kotlin) when a watched app died
while the block ran:

| What happened in the block | What `guard` does |
|---|---|
| A Tap command failed because the app died | raises; the command's error is its cause |
| The app died but no command failed (a crash after the last step, or in a background process) | raises when the block ends |
| Your own code failed (an assertion, say) and the app also died | raises, your failure chained (Python) or suppressed (Kotlin) |
| Nothing died | the block's value or exception, unchanged |

`app.guard` watches only that app; `device.guard` every watched app. The deaths it raises for
are handled.

Inside a test, the end-of-test check already fails a test whose app died, so `guard` is for
code that must react at a known point: a script with no test framework, a helper that retries a
flow once after relaunching, or a test that goes on after the block. The client learns of a
death at the next Tap call, or when the block ends (up to 300 ms after the latest input); it
cannot stop your own code at the moment of the crash.

## Tap agent

`tap-agent` prints and takes the new deaths of watched apps after every step (such a step exits
4), and takes `--expect-death` on an action; `tap-agent deaths` lists the record, and
`tap-agent deaths --wait` in the background tells an agent when an app dies between steps. See
[A session, step by step](../agent/session.md#when-the-app-crashes).

## Limits

- Android reports an ANR only when input goes unanswered for about 5 s (and on Android 10 and
  older only once more input is waiting), so a hang with no input waiting stays a plain timeout.
- A process that ends with no crash event (killed for memory, `System.exit`) is not a death.
- Stack traces are not symbolicated: an R8-minified app's frames need its `mapping.txt`.
- The record keeps the newest 256 deaths per attachment.
