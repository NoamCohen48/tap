package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.os.Bundle

/**
 * The validation driver's fault controller, configured by the instrumentation arguments
 * `tapFaultPoint` (a [FaultPoint], default `NONE`) and `tapFaultAuthority` (the fixture provider
 * the late-work fault schedules through).
 */
internal fun driverFaultHooks(
    instrumentation: Instrumentation,
    arguments: Bundle,
): FaultHooks =
    FaultController(
        instrumentation,
        faultPoint = FaultPoint.valueOf(arguments.getString("tapFaultPoint") ?: FaultPoint.NONE.name),
        faultAuthority = arguments.getString("tapFaultAuthority").orEmpty(),
        generation = requireNotNull(arguments.getString("tapGeneration")).toLong(),
    )
