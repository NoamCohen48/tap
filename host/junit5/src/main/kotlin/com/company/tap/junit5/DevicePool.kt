package com.company.tap.junit5

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * JVM-wide pool of serials. [acquire] hands out every requested role at once or nothing, so
 * two multi-device tests can never deadlock holding one device each. Cross-JVM exclusivity is
 * still guaranteed by the per-serial session lease in `~/.tap/sessions`.
 */
class DevicePool internal constructor(private val serials: List<String>) {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private val busy = mutableSetOf<String>()

    /** Roles → serials. Pinned roles get their serial; others take any free one. */
    fun acquire(roles: List<String>, pinned: Map<String, String>, timeout: Duration): Map<String, String> {
        require(roles.isNotEmpty() && roles.distinct().size == roles.size) { "Roles must be unique and non-empty: $roles" }
        require(roles.size <= serials.size) { "Test needs ${roles.size} devices but the pool has ${serials.size}: $serials" }
        val deadlineNanos = System.nanoTime() + timeout.inWholeNanoseconds
        lock.withLock {
            while (true) {
                val assignment = tryAssign(roles, pinned)
                if (assignment != null) {
                    busy += assignment.values
                    return assignment
                }
                val remaining = deadlineNanos - System.nanoTime()
                check(remaining > 0) { "Timed out after $timeout acquiring devices for roles $roles (busy: $busy)" }
                released.await(remaining, TimeUnit.NANOSECONDS)
            }
        }
    }

    fun release(serialsToRelease: Collection<String>) = lock.withLock {
        busy -= serialsToRelease.toSet()
        released.signalAll()
    }

    private fun tryAssign(roles: List<String>, pinned: Map<String, String>): Map<String, String>? {
        val free = serials.filterNot { it in busy }.toMutableList()
        val assignment = linkedMapOf<String, String>()
        roles.filter { it in pinned }.forEach { role ->
            val serial = pinned.getValue(role)
            if (!free.remove(serial)) return null
            assignment[role] = serial
        }
        roles.filterNot { it in pinned }.forEach { role ->
            val serial = free.removeFirstOrNull() ?: return null
            assignment[role] = serial
        }
        return assignment
    }

    companion object {
        val shared: DevicePool by lazy { DevicePool(TapConfig.current.serials) }
    }
}
