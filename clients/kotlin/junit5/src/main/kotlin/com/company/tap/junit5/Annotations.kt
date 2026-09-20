package com.company.tap.junit5

import com.company.tap.sdk.Device
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Enables Tap for a test class: one driver session per declared role is opened before each
 * test and closed (with failure artifacts captured first) after it.
 *
 * Configuration comes from JVM system properties (Gradle: `systemProperty(...)` on the test
 * task) or `TAP_*` environment variables:
 *
 * | Property | Meaning |
 * |---|---|
 * | `tap.serials` | serials roles are pinned to, in order (optional; default any pool device) |
 * | `tap.device.<role>` | pin a role to one serial (optional) |
 * | `tap.autPackage` | the application under test (required) |
 * | `tap.artifactsDir` | failure artifacts root (default `build/tap-artifacts`) |
 * | `tap.acquireTimeoutSeconds` | how long to wait for a device another session holds (default 300) |
 * | `tap.service` | `host:port` of a running service (default: the one `tap start` recorded in the state dir) |
 * | `tap.manageService` | `true` = run `tap start` before the first test and `tap stop` after the last one if that start created the service (default `false`: a service must already be running) |
 * | `tap.bin` | the `tap` executable `tap.manageService` uses (default `TAP_BIN` or `tap` on `PATH`) |
 *
 * Sessions come from the host service (`tap start`), which owns ADB, the driver and the
 * machine-wide device pool; the driver APKs ship inside the service.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(TapExtension::class)
annotation class TapTest

/** Injects the [Device] bound to [role]; the default role is `"device"`. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class TapDevice(val role: String = DEFAULT_ROLE)

/** Declares the named roles a test (or every test in a class) needs; inject them as [Devices]. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TapDevices(vararg val roles: String)

const val DEFAULT_ROLE = "device"

/** The devices opened for one test, by role. */
class Devices internal constructor(private val byRole: Map<String, Device>) : Iterable<Device> {
    operator fun get(role: String): Device =
        byRole[role] ?: throw IllegalArgumentException("No device for role '$role'; declared roles: ${byRole.keys}")

    val roles: Set<String> get() = byRole.keys
    override fun iterator(): Iterator<Device> = byRole.values.iterator()
}
