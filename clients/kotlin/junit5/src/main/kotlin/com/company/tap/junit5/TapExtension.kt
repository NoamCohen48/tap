package com.company.tap.junit5

import com.company.tap.sdk.Device
import com.company.tap.sdk.DeviceOptions
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler

/**
 * Per-test device sessions for JUnit 5. Before each test it maps every declared role to a
 * serial, opens one driver session per role — in sorted serial order, waiting up to
 * `tap.acquireTimeoutSeconds` for a device another session holds, so two multi-device tests
 * can never deadlock — and injects [Device]/[Devices] parameters. After the test it captures
 * failure artifacts (screenshot, hierarchy, driver log) while sessions are live, then closes
 * them, which frees the devices. Sessions never outlive a test, so a poisoned or quarantined
 * session is contained to the test that hit it.
 */
class TapExtension : BeforeEachCallback, AfterEachCallback, ParameterResolver, TestExecutionExceptionHandler {

    override fun beforeEach(context: ExtensionContext) {
        val config = TapConfig.current
        val roles = declaredRoles(context)
        val available = config.serials.ifEmpty { TapConnection.connection.availableSerials() }
        // Fewer devices than roles is an environment precondition, not a test failure.
        assumeTrue(roles.size <= available.size) {
            "${context.requiredTestMethod.name} needs ${roles.size} devices but " +
                (if (config.serials.isEmpty()) "the pool has $available" else "tap.serials lists ${config.serials}")
        }
        val assignment = assignSerials(roles, available, config.pinnedRoles)
        context.store.put(KEY, TestDevices(openAll(assignment, config), assignment))
    }

    override fun handleTestExecutionException(context: ExtensionContext, throwable: Throwable) {
        context.store.get(KEY, TestDevices::class.java)?.failure = throwable
        throw throwable
    }

    override fun afterEach(context: ExtensionContext) {
        val state = context.store.remove(KEY, TestDevices::class.java) ?: return
        val failure = state.failure ?: context.executionException.orElse(null)
        try {
            if (failure != null) captureArtifacts(context, state)
        } finally {
            val closeErrors = state.devices.values.mapNotNull { device -> runCatching { device.close() }.exceptionOrNull() }
            closeErrors.firstOrNull()?.let { first ->
                closeErrors.drop(1).forEach(first::addSuppressed)
                // A cleanup failure after a passing test is a real failure: the device may be quarantined.
                if (failure == null) throw first
            }
        }
    }

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean {
        val type = parameter.parameter.type
        return type == Device::class.java || type == Devices::class.java
    }

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Any {
        val state = requireNotNull(context.store.get(KEY, TestDevices::class.java)) {
            "Tap devices are only available inside a test method (not constructors or static callbacks)"
        }
        return when (parameter.parameter.type) {
            Devices::class.java -> Devices(state.devices)
            else -> {
                val role = parameter.findAnnotation(TapDevice::class.java).map { it.role }.orElse(DEFAULT_ROLE)
                state.devices[role] ?: throw IllegalArgumentException(
                    "Parameter asks for role '$role' but the test declared ${state.devices.keys}",
                )
            }
        }
    }

    private fun declaredRoles(context: ExtensionContext): List<String> {
        val method = context.requiredTestMethod
        val fromAnnotation = method.getAnnotation(TapDevices::class.java)?.roles?.toList()
            ?: context.requiredTestClass.getAnnotation(TapDevices::class.java)?.roles?.toList()
        val fromParameters = method.parameters.mapNotNull { it.getAnnotation(TapDevice::class.java)?.role }
        val implicit = if (method.parameters.any { it.type == Device::class.java && it.getAnnotation(TapDevice::class.java) == null }) {
            listOf(DEFAULT_ROLE)
        } else {
            emptyList()
        }
        val roles = (fromAnnotation.orEmpty() + fromParameters + implicit).distinct()
        require(roles.isNotEmpty()) { "${method.name} declares no Tap devices; add a Device parameter or @TapDevices" }
        return roles
    }

    /**
     * Roles → serials, decided by the client: pinned explicitly (`tap.device.<role>`), then
     * [available] in declaration order. [available] is `tap.serials` or, when none are
     * configured, what the service's device list reports.
     */
    private fun assignSerials(roles: List<String>, available: List<String>, pinned: Map<String, String>): Map<String, String> {
        val free = available.filter { it !in pinned.values }.toMutableList()
        return roles.associateWith { role -> pinned[role] ?: free.removeFirst() }
    }

    /**
     * Opens the sessions one at a time in sorted serial order. Every process takes device locks
     * in the same order, so two tests wanting the same two devices cannot deadlock; the second
     * simply waits (bounded by `tap.acquireTimeoutSeconds`) for the first to finish.
     */
    private fun openAll(assignment: Map<String, String>, config: TapConfig): Map<String, Device> {
        val opened = linkedMapOf<String, Device>()
        val options = DeviceOptions(waitForDevice = config.acquireTimeout)
        try {
            assignment.entries.sortedBy { it.value }.forEach { (role, serial) ->
                opened[role] = TapConnection.connection.openDevice(serial, config.autPackage, options = options)
            }
        } catch (error: Throwable) {
            opened.values.forEach { device -> runCatching { device.close() }.exceptionOrNull()?.let(error::addSuppressed) }
            throw error
        }
        return assignment.keys.associateWith { opened.getValue(it) }
    }

    private fun captureArtifacts(context: ExtensionContext, state: TestDevices) {
        val dir = TapConfig.current.artifactsDir
            .resolve(context.requiredTestClass.name)
            .resolve(context.requiredTestMethod.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        Files.createDirectories(dir)
        state.devices.forEach { (role, device) ->
            val prefix = "$role-${device.serial}"
            capture(dir.resolve("$prefix.png")) { device.screenshot() }
            capture(dir.resolve("$prefix.xml")) { device.dumpHierarchy().toByteArray() }
            capture(dir.resolve("$prefix.device-info.txt")) { device.info().toString().toByteArray() }
            capture(dir.resolve("$prefix.driver.log")) { device.driverLog().joinToString("\n").toByteArray() }
        }
        state.failure?.let { failure ->
            capture(dir.resolve("failure.txt")) { failure.stackTraceToString().toByteArray() }
        }
    }

    private fun capture(path: Path, produce: () -> ByteArray) {
        // Artifact capture must never mask the test failure or block cleanup.
        runCatching { Files.write(path, produce()) }
    }

    private class TestDevices(
        val devices: Map<String, Device>,
        val assignment: Map<String, String>,
    ) {
        @Volatile
        var failure: Throwable? = null
    }

    private val ExtensionContext.store: ExtensionContext.Store
        get() = getStore(Namespace.create(TapExtension::class.java, requiredTestMethod))

    private companion object {
        const val KEY = "tap.devices"
    }
}
