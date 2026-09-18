package com.company.tap.junit5

import com.company.tap.host.DeviceSessionConfig
import com.company.tap.sdk.Device
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler

/**
 * Per-test device sessions for JUnit 5. Before each test it acquires every declared role
 * from the shared [DevicePool] (all or nothing), opens one driver session per role in
 * parallel, and injects [Device]/[Devices] parameters. After the test it captures failure
 * artifacts (screenshot, hierarchy, driver log) while sessions are live, then closes them and
 * releases the pool. Sessions never outlive a test, so a poisoned or quarantined session is
 * contained to the test that hit it.
 */
class TapExtension : BeforeEachCallback, AfterEachCallback, ParameterResolver, TestExecutionExceptionHandler {

    override fun beforeEach(context: ExtensionContext) {
        val config = TapConfig.current
        val roles = declaredRoles(context)
        // Fewer devices than roles is an environment precondition, not a test failure.
        assumeTrue(roles.size <= config.serials.size) {
            "${context.requiredTestMethod.name} needs ${roles.size} devices but tap.serials lists ${config.serials}"
        }
        val assignment = DevicePool.shared.acquire(roles, config.pinnedRoles, config.acquireTimeout)
        val logs = ConcurrentHashMap<String, StringBuilder>()
        val devices = try {
            openAll(assignment, config, logs)
        } catch (error: Throwable) {
            DevicePool.shared.release(assignment.values)
            throw error
        }
        context.store.put(KEY, TestDevices(devices, assignment, logs))
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
            DevicePool.shared.release(state.assignment.values)
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

    private fun openAll(
        assignment: Map<String, String>,
        config: TapConfig,
        logs: MutableMap<String, StringBuilder>,
    ): Map<String, Device> {
        val executor = Executors.newFixedThreadPool(assignment.size)
        try {
            val futures: Map<String, Future<Device>> = assignment.mapValues { (role, serial) ->
                executor.submit<Device> {
                    val log = StringBuilder().also { logs[role] = it }
                    val install = installedDrivers.add(serial)
                    try {
                        Device.connect(
                            DeviceSessionConfig(
                                serial = serial,
                                autPackage = config.autPackage,
                                driverApk = config.driverApk.takeIf { install },
                                driverTestApk = config.driverTestApk.takeIf { install },
                                driverLog = { line -> synchronized(log) { log.appendLine(line) } },
                            ),
                        )
                    } catch (error: Throwable) {
                        // A failed open may have left the driver uninstalled; retry the install next time.
                        if (install) installedDrivers.remove(serial)
                        throw error
                    }
                }
            }
            val opened = linkedMapOf<String, Device>()
            var failure: Throwable? = null
            futures.forEach { (role, future) ->
                try {
                    opened[role] = future.get()
                } catch (error: Throwable) {
                    failure = (failure ?: error).also { if (it !== error) it.addSuppressed(error) }
                }
            }
            failure?.let { error ->
                opened.values.forEach { device -> runCatching { device.close() }.exceptionOrNull()?.let(error::addSuppressed) }
                throw (error as? java.util.concurrent.ExecutionException)?.cause ?: error
            }
            return opened
        } finally {
            executor.shutdown()
        }
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
            state.logs[role]?.let { log -> capture(dir.resolve("$prefix.driver.log")) { synchronized(log) { log.toString().toByteArray() } } }
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
        val logs: Map<String, StringBuilder>,
    ) {
        @Volatile
        var failure: Throwable? = null
    }

    private val ExtensionContext.store: ExtensionContext.Store
        get() = getStore(Namespace.create(TapExtension::class.java, requiredTestMethod))

    private companion object {
        const val KEY = "tap.devices"

        /** Serials whose driver APKs this JVM already installed. */
        val installedDrivers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
