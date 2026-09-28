package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.Device
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
 * | `tap.serials` | serials roles map to, in order from a start device that rotates per test (optional; default any pool device) |
 * | `tap.device.<role>` | pin a role to one serial (optional) |
 * | `tap.autPackage` | the application under test (required) |
 * | `tap.artifactsDir` | failure artifacts root (default `build/tap-artifacts`) |
 * | `tap.capture` | `onFailure` (default) = `Device.capture()` every device of a failed test into the artifacts root; `off` = capture nothing |
 * | `tap.acquireTimeoutSeconds` | how long to wait for a device another session holds (default 300) |
 * | `tap.server` | `host:port` of a running server (default: the one `tap start` recorded in the state dir) |
 * | `tap.token` | bearer token for an explicit `tap.server` (default: the one in the state dir's `daemon.json`) |
 * | `tap.manageDaemon` | `true` = run `tap start` before the first test and `tap stop` after the last one if that start created the daemon (default `false`: a daemon must already be running) |
 * | `tap.bin` | the `tap` executable `tap.manageDaemon` uses (default `TAP_BIN` or `tap` on `PATH`) |
 *
 * Sessions come from the host server (`tap start`), which owns ADB, the driver and the
 * machine-wide device pool; the driver APKs ship inside the daemon. Devices are attached per test
 * unless [deviceLifetime] is [DeviceLifetime.PER_CLASS].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(TapExtension::class)
annotation class TapTest(
    /** How long an attached device lives; see [DeviceLifetime]. */
    val deviceLifetime: DeviceLifetime = DeviceLifetime.PER_TEST,
)

/** How long the JUnit extension keeps a test's devices attached ([TapTest.deviceLifetime]). */
enum class DeviceLifetime {
    /** Attach before each test and detach after it (the default): every test gets a fresh session. */
    PER_TEST,

    /**
     * Attach once and reuse the devices for every test of the class; detach after the last one.
     * Saves the driver start of each attach (about 1 s on an emulator, several on a slow phone)
     * but nothing is reset between tests: the app keeps whatever state the previous test left,
     * so each test must bring it where it needs it (`coldLaunch()`, `clearData()`). A reused
     * device is probed before each test; one that stopped working (quarantined, driver lost) is
     * detached and a fresh one attached. Failure artifacts are still captured per test.
     */
    PER_CLASS,
}

/** Injects the [Device] bound to [role]; the default role is `"device"`. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class TapDevice(
    val role: String = DEFAULT_ROLE,
)

/** Declares the named roles a test (or every test in a class) needs; inject them as [Devices]. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TapDevices(
    vararg val roles: String,
)

const val DEFAULT_ROLE = "device"

/** The devices opened for one test, by role. */
class Devices internal constructor(
    private val byRole: Map<String, Device>,
) : Iterable<Device> {
    operator fun get(role: String): Device =
        byRole[role] ?: throw IllegalArgumentException("No device for role '$role'; declared roles: ${byRole.keys}")

    val roles: Set<String> get() = byRole.keys

    override fun iterator(): Iterator<Device> = byRole.values.iterator()
}
