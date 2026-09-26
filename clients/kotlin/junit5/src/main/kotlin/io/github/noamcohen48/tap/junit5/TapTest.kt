package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.TapContext
import io.github.noamcohen48.tap.sdk.TapUsageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking

/**
 * Per-test coroutine root and devices, created in `BeforeEach` and consumed by [tapTest].
 * [rootJob] is the ownership domain: device opens run as its children (setup scope) and the
 * test body runs as its child (test scope), so timeout/interruption and sibling failure cancel
 * in-flight RPCs. [failure] records the primary setup/invocation/lifecycle failure for
 * `AfterEach` to preserve.
 */
internal class TestState(
    val rootJob: Job,
    val devices: Map<String, Device>,
    val assignment: Map<String, String>,
    val method: String,
) {
    @Volatile
    var failure: Throwable? = null
}

/**
 * The JUnit-bound test state for the current thread, installed by the extension's
 * `InvocationInterceptor` for the duration of the test invocation only. `null` outside a
 * `@TapTest` test method (scripts, metadata-only tests, setup/teardown callbacks).
 */
internal object TapTestBinding {
    val current = ThreadLocal<TestState?>()

    /** True while inside a `tapTest` body (propagated to child coroutines for nesting checks). */
    val inTapTest = ThreadLocal<Boolean?>()
}

/**
 * The mandatory entry point for suspending framework calls in JUnit tests:
 * `@Test fun x(device: Device) = tapTest { ... }` (a block body works too). [tapTest] returns
 * `Unit`, so the test method compiles to a `void` JUnit can discover whatever the block's last
 * expression is. JUnit test methods are not `suspend`, so this is the real-time
 * `runBlocking`-style bridge onto the extension-owned per-test context
 * (root job + [TapContext]); it never uses virtual time because ADB, devices and command
 * deadlines are external real-time systems.
 *
 * Fails immediately with [TapUsageException] when called without an active `@TapTest` binding
 * (no `TapExtension`, or outside the test invocation), or when nested (`tapTest` inside
 * `tapTest`/`tapScope`). Suspending `Device`/`App`/`Element` calls from a coroutine that does
 * not inherit this context (for example `GlobalScope`) fail in the SDK for the same reason:
 * they would otherwise run uncancelled beside the test. Plain metadata-only tests stay
 * possible — they just cannot touch a live `Device` outside this bridge.
 *
 * JUnit `@Timeout`/thread interruption cancels the blocked `runBlocking`, which cancels the
 * root job and every in-flight RPC; `AfterEach` then performs bounded non-cancellable
 * teardown. The interrupt is consumed here (never re-asserted): `AfterEach` runs its own
 * `runBlocking` boundaries on this same JUnit callback thread, and a set interrupt flag
 * would abort teardown before every device is closed. The original `InterruptedException`
 * stays the primary failure as the cause, with cleanup failures suppressed into it.
 * Sibling failure inside `coroutineScope`/`async` in [block] cancels the other
 * device's work by structured-concurrency rules.
 */
fun tapTest(block: suspend CoroutineScope.() -> Unit) {
    // Nesting first: the flag propagates through the coroutine context, so a child coroutine
    // on another thread (which has no ThreadLocal binding) still reports "nested", not
    // "no binding". Outside any tapTest body the flag is never set, so this costs nothing.
    if (TapTestBinding.inTapTest.get() == true) {
        throw TapUsageException("nested tapTest: tapTest { ... } cannot be called inside another tapTest/tapScope body")
    }
    val state =
        TapTestBinding.current.get()
            ?: throw TapUsageException(
                "tapTest { ... } requires an active @TapTest binding: annotate the class with @TapTest, " +
                    "declare a Device/Devices parameter or @TapDevices, and call tapTest inside the test method",
            )
    // Propagate the nesting flag to child coroutines so `async { tapTest { } }` also fails.
    val context = state.rootJob + TapContext("junit:${state.method}") + TapTestBinding.inTapTest.asContextElement(true)
    try {
        runBlocking(context) { block() }
    } catch (interrupted: InterruptedException) {
        state.rootJob.cancel(CancellationException("JUnit timeout/interruption", interrupted))
        // Consume, never reinterrupt: AfterEach runs runBlocking teardown on this same
        // callback thread and must still close every device. (InterruptedException already
        // clears the flag; the extra Thread.interrupted() is a defensive clear in case the
        // platform delivered the interrupt without throwing.) The original interruption
        // stays primary as the cause; AfterEach suppresses cleanup failures into it.
        Thread.interrupted()
        throw CancellationException("JUnit timeout/interruption", interrupted)
    } catch (failure: Throwable) {
        // Prompt root cancellation on failure so a failing sibling does not leave the other's
        // RPC parked until AfterEach; AfterEach cancels again unconditionally.
        if (failure !is CancellationException) state.rootJob.cancel(CancellationException("tapTest failed", failure))
        throw failure
    }
}
