package io.github.noamcohen48.tap.sync

import android.os.SystemClock
import java.util.UUID

/**
 * In-process busy tracking the Tap driver reads through [TapSynchronizationProvider].
 *
 * Wrap asynchronous work the tests must wait for in [busy]; the driver reports idle only when
 * the busy count is zero and has been stable. Identity fields let the host detect a process
 * restart between two observations. This is debug/E2E-only instrumentation: ship it in test
 * builds, never in release.
 */
object TapSynchronization {
    private var initialized = false
    private var processStartUuid = ""
    private var sessionIdentity = ""
    private var generation = 0L
    private var busyCount = 0
    private var lastTransitionElapsedMs = 0L
    private var error: String? = null

    /** Idempotent; [TapSynchronizationProvider] calls it before `Application.onCreate`. */
    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
        processStartUuid = UUID.randomUUID().toString()
        sessionIdentity = UUID.randomUUID().toString()
        lastTransitionElapsedMs = SystemClock.elapsedRealtime()
    }

    /** Marks the process busy until the returned handle is closed (closing twice is a no-op). */
    @Synchronized
    fun busy(): AutoCloseable {
        check(initialized) { "TapSynchronization.initialize() has not run" }
        if (busyCount == Int.MAX_VALUE || generation == Long.MAX_VALUE) {
            error = "SYNCHRONIZATION_COUNTER_OVERFLOW"
            throw IllegalStateException(error)
        }
        busyCount += 1
        generation += 1
        lastTransitionElapsedMs = SystemClock.elapsedRealtime()
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (closed) return@synchronized
                closed = true
                if (busyCount == 0) {
                    error = "BUSY_COUNTER_UNDERFLOW"
                } else {
                    busyCount -= 1
                }
                if (generation == Long.MAX_VALUE) {
                    error = "SYNCHRONIZATION_GENERATION_OVERFLOW"
                } else {
                    generation += 1
                }
                lastTransitionElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        initialized,
        processStartUuid,
        sessionIdentity,
        generation,
        busyCount,
        lastTransitionElapsedMs,
        error,
    )

    data class Snapshot(
        val initialized: Boolean,
        val processStartUuid: String,
        val sessionIdentity: String,
        val generation: Long,
        val busyCount: Int,
        val lastTransitionElapsedMs: Long,
        val error: String?,
    )
}
