package com.company.tap.fixture

import android.app.Application
import android.os.SystemClock
import java.util.UUID

class FixtureApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TapSynchronization.initialize()
    }
}

object TapSynchronization {
    private var initialized = false
    private var processStartUuid = ""
    private var sessionIdentity = ""
    private var generation = 0L
    private var busyCount = 0
    private var lastTransitionElapsedMs = 0L
    private var error: String? = null

    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true
        processStartUuid = UUID.randomUUID().toString()
        sessionIdentity = UUID.randomUUID().toString()
        lastTransitionElapsedMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun busy(): AutoCloseable {
        check(initialized)
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

object FaultTapCounter {
    private var count = 0

    @Synchronized
    fun value(): Int = count

    @Synchronized
    fun increment(): Int {
        count = Math.addExact(count, 1)
        return count
    }
}
