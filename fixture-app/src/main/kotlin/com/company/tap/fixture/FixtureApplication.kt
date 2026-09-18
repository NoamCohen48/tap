package com.company.tap.fixture

import android.app.Application

class FixtureApplication : Application()

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
