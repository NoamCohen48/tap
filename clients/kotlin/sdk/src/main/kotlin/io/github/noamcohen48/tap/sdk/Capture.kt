package io.github.noamcohen48.tap.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The diagnostics of one device at one moment ([Device.capture]): a [Screenshot], the
 * [Hierarchy], the [DeviceInfo] and the [DriverLog]. A part is null when it could not be
 * produced; [failures] says why, by part name.
 */
class Capture internal constructor(
    val serial: String,
    val screenshot: Screenshot?,
    val hierarchy: Hierarchy?,
    val info: DeviceInfo?,
    val driverLog: DriverLog?,
    /** Why each missing part is missing, keyed like [artifacts]. */
    val failures: Map<String, Throwable>,
) {
    /**
     * The parts that were produced, by name: `screenshot`, `hierarchy`, `device-info`,
     * `driver-log`, in that order.
     */
    val artifacts: Map<String, Artifact>
        get() =
            buildMap {
                screenshot?.let { put(SCREENSHOT, it) }
                hierarchy?.let { put(HIERARCHY, it) }
                info?.let { put(DEVICE_INFO, it) }
                driverLog?.let { put(DRIVER_LOG, it) }
            }

    /**
     * Writes every produced part to [dir] as `<prefix>.<name>.<extension>` (for example
     * `emulator-5554.screenshot.png`), creating [dir], and returns the written paths.
     */
    fun saveTo(
        dir: Path,
        prefix: String = serial,
    ): List<Path> {
        Files.createDirectories(dir)
        return artifacts.map { (name, artifact) -> artifact.save(dir.resolve("$prefix.$name.${artifact.extension}")) }
    }

    override fun toString(): String = "Capture($serial: ${artifacts.keys}${if (failures.isEmpty()) "" else ", missing ${failures.keys}"})"

    companion object {
        const val SCREENSHOT = "screenshot"
        const val HIERARCHY = "hierarchy"
        const val DEVICE_INFO = "device-info"
        const val DRIVER_LOG = "driver-log"
    }
}

/**
 * Takes a screenshot, the hierarchy, the device info and the driver log at once, each within
 * [timeout], and returns them as one [Capture]. Device failures never escape: a part that fails
 * or runs out of time is null in the result, with its cause in [Capture.failures]. Only the
 * caller's own cancellation propagates. Use it wherever a test wants evidence (after a step, in
 * a `catch`); the JUnit extension and the pytest plugin call it for every failed test.
 */
suspend fun Device.capture(timeout: Duration = 30.seconds): Capture {
    ensureTapBound("Device.capture")
    val failures = ConcurrentHashMap<String, Throwable>()

    suspend fun <T : Any> part(
        name: String,
        produce: suspend () -> T,
    ): T? =
        try {
            val produced = withTimeoutOrNull(timeout) { produce() }
            if (produced == null) failures[name] = TapException("$name of $serial not produced within $timeout")
            produced
        } catch (cancelled: CancellationException) {
            // The caller's cancellation propagates; a part's own (an inner timeout) is a failure.
            if (!currentCoroutineContext().isActive) throw cancelled
            failures[name] = cancelled
            null
        } catch (failure: Exception) {
            failures[name] = failure
            null
        }

    return coroutineScope {
        val screenshot = async { part(Capture.SCREENSHOT) { screenshot(timeout) } }
        val hierarchy = async { part(Capture.HIERARCHY) { dumpHierarchy(timeout) } }
        val info = async { part(Capture.DEVICE_INFO) { info() } }
        val driverLog = async { part(Capture.DRIVER_LOG) { driverLog() } }
        Capture(serial, screenshot.await(), hierarchy.await(), info.await(), driverLog.await(), failures.toMap())
    }
}
