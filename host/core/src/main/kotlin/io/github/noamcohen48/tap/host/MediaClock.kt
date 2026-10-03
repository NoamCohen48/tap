package io.github.noamcohen48.tap.host

import java.util.UUID

/** Process-local clock shared by event boundaries and video receipt; never compare different ids. */
object MediaClock {
    val id: String = UUID.randomUUID().toString()
    private val origin = System.nanoTime()

    /** Nanoseconds since this clock was initialized; not a device capture/execution timestamp. */
    fun nowNs(): Long = System.nanoTime() - origin
}
