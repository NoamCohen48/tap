package com.company.tap.driver.engine

/** Error codes the execution pipeline itself emits, independent of any UI command. */
object EngineErrorCodes {
    const val CANCELLED = "CANCELLED"
    const val DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    const val DRIVER_UNHEALTHY = "DRIVER_UNHEALTHY"
    const val INDETERMINATE = "INDETERMINATE"
    const val INTERNAL = "INTERNAL"
    const val OVERLOADED = "OVERLOADED"
}
