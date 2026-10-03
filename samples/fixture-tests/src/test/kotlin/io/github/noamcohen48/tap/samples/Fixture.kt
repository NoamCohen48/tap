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

    /** A valid 1x1 PNG: the media scanner skips files it cannot decode. */
    val PNG: ByteArray by lazy {
        fun chunk(
            kind: String,
            data: ByteArray,
        ): ByteArray {
            val crc = java.util.zip.CRC32().apply { update(kind.toByteArray() + data) }.value.toInt()
            return int(data.size) + kind.toByteArray() + data + int(crc)
        }
        val header = int(1) + int(1) + byteArrayOf(8, 2, 0, 0, 0)
        val pixels = java.io.ByteArrayOutputStream().also { out -> java.util.zip.DeflaterOutputStream(out).use { it.write(byteArrayOf(0, -1, 0, 0)) } }.toByteArray()
        byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + chunk("IDAT", pixels) + chunk("IEND", ByteArray(0))
    }

    private fun int(value: Int) = java.nio.ByteBuffer.allocate(4).putInt(value).array()

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
