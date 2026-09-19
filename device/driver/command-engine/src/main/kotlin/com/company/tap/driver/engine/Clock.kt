package com.company.tap.driver.engine

/**
 * Monotonic millisecond clock. The device implementation is `SystemClock.elapsedRealtime()`;
 * tests supply a manual clock so deadlines and watchdog behavior are deterministic.
 */
fun interface Clock {
    fun nowMs(): Long
}
