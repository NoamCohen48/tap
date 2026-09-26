package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.sdk.App
import io.github.noamcohen48.tap.sdk.Device
import java.nio.file.Path

/** Fixture app facts shared by the samples; a product test suite would own an equivalent file. */
object Fixture {
    const val PACKAGE = "io.github.noamcohen48.tap.fixture"
    val apk: Path = Path.of(System.getProperty("tap.fixtureApk"))

    /** Installs once per JVM per device, then cold-launches the given activity. */
    suspend fun launch(
        device: Device,
        activity: String = ".MainActivity",
    ): App {
        val app = device.app(PACKAGE)
        if (installed.add(device.serial) || !app.isInstalled()) app.install(apk)
        app.coldLaunch(activity)
        return app
    }

    private val installed =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
}

/**
 * `kotlin.test.assertFailsWith` takes a non-suspend block, so it cannot wrap the client's
 * suspending calls. This is the same assertion for suspending code: runs [block], returns the
 * expected failure, or fails the test when nothing (or the wrong thing) is thrown.
 */
suspend inline fun <reified T : Throwable> assertFailsSuspend(block: suspend () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw AssertionError("expected ${T::class.simpleName} but was $failure", failure)
    }
    throw AssertionError("expected ${T::class.simpleName} but the block completed")
}
