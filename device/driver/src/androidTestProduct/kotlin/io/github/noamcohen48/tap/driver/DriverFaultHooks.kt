package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.os.Bundle

/**
 * The shipped driver has no fault injection. A fault argument means the host meant to run the
 * `validation` flavor; failing loudly beats a fault scenario that silently never fires.
 */
internal fun driverFaultHooks(
    @Suppress("UNUSED_PARAMETER") instrumentation: Instrumentation,
    arguments: Bundle,
): FaultHooks {
    val faultPoint = arguments.getString("tapFaultPoint")
    require(faultPoint == null || faultPoint == "NONE") {
        "tapFaultPoint=$faultPoint needs the validation flavor of the driver; this is the product driver"
    }
    return FaultHooks.NONE
}
