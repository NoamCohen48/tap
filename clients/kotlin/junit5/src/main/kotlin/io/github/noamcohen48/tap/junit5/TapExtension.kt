package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.DeviceBusyException
import io.github.noamcohen48.tap.sdk.DeviceOptions
import io.github.noamcohen48.tap.sdk.TapContext
import io.github.noamcohen48.tap.sdk.TapException
import io.github.noamcohen48.tap.sdk.capture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-test device sessions for JUnit 5, over the coroutine client. Before each test it maps
 * every declared role to a serial (rotating the starting device from test to test; a
 * single-role test also moves on to the next device when one is busy), creates the per-test
 * root [Job], and opens one driver session per role — in sorted serial order, waiting up to `tap.acquireTimeoutSeconds` for a
 * device another session holds, so two multi-device tests can never deadlock — all as children
 * of the root job. The test body runs only inside [tapTest], which binds this state and
 * installs the SDK [TapContext]; [Device] calls outside it fail with a usage error, so plain
 * metadata-only tests stay possible but cannot touch live devices.
 *
 * JUnit timeout/interruption cancels the root job (via [tapTest]'s `runBlocking`); sibling
 * failure in `coroutineScope`/`async` cancels the other device's in-flight RPC by structured
 * rules. After the test (or setup) it captures failure artifacts while sessions are still
 * live when possible, then cancels the root job and performs bounded non-cancellable cleanup,
 * preserving the primary failure and suppressing cleanup failures into it.
 */
class TapExtension :
    BeforeEachCallback,
    AfterEachCallback,
    ParameterResolver,
    InvocationInterceptor {
    override fun beforeEach(context: ExtensionContext) {
        val config = TapConfig.current
        val method = context.requiredTestMethod.name
        val rootJob = Job()
        // Device opens run as children of the root job in a setup scope (SDK marker required);
        // the test body later uses the same root with its own marker via tapTest.
        try {
            val state =
                runBlocking(rootJob + TapContext("junit:$method:setup")) {
                    val roles = declaredRoles(context)
                    val available =
                        config.serials.ifEmpty {
                            SharedConnection.connection().availableSerials()
                        }
                    // Fewer devices than roles is an environment precondition, not a test failure.
                    assumeTrue(roles.size <= available.size) {
                        "${context.requiredTestMethod.name} needs ${roles.size} devices but " +
                            (if (config.serials.isEmpty()) "the pool has $available" else "tap.serials lists ${config.serials}")
                    }
                    val start = ROTATION.getAndIncrement()
                    val assignment = assignSerials(roles, available, config.pinnedRoles, start)
                    val connection = SharedConnection.connection()
                    val single = roles.singleOrNull()?.takeIf { it !in config.pinnedRoles }
                    val devices =
                        if (single != null) {
                            openSingle(connection, single, rotate(available, start), config)
                        } else {
                            openAll(connection, assignment, config)
                        }
                    TestState(rootJob, devices, devices.mapValues { it.value.serial }, method)
                }
            context.store.put(KEY, state)
        } catch (failure: Throwable) {
            rootJob.cancel(CancellationException("beforeEach failed", failure))
            throw failure
        }
    }

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        val state = extensionContext.store.get(KEY, TestState::class.java)
        if (state == null) {
            invocation.proceed()
            return
        }
        TapTestBinding.current.set(state)
        try {
            invocation.proceed()
        } finally {
            TapTestBinding.current.remove()
        }
    }

    override fun afterEach(context: ExtensionContext) {
        val state = context.store.remove(KEY, TestState::class.java) ?: return
        // JUnit collects whatever the @BeforeEach methods, the test and the @AfterEach methods
        // threw before this callback runs: the one record of the primary failure.
        val failure = context.executionException.orElse(null)
        // Stop test coroutines promptly; sessions stay usable for artifact capture below.
        state.rootJob.cancel(CancellationException("test finished"))
        try {
            if (failure != null && TapConfig.current.capture == CaptureMode.ON_FAILURE) {
                runBlocking(TapContext("junit:${state.method}:artifacts")) {
                    withContext(NonCancellable) { captureArtifacts(context, state, failure) }
                }
            }
        } finally {
            val closeErrors = closeAll(state)
            if (failure == null) {
                closeErrors.firstOrNull()?.let { first ->
                    closeErrors.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            } else {
                closeErrors.forEach(failure::addSuppressed)
                // A cleanup failure after a passing test is thrown above; after a failing test the
                // primary failure (already recorded) stays the report, with cleanup suppressed.
                // When the primary came from executionException (not our stored failure), rethrow
                // is unnecessary: JUnit reports it. When it is stored, it was already thrown.
            }
        }
    }

    override fun supportsParameter(
        parameter: ParameterContext,
        context: ExtensionContext,
    ): Boolean {
        val type = parameter.parameter.type
        return type == Device::class.java || type == Devices::class.java
    }

    override fun resolveParameter(
        parameter: ParameterContext,
        context: ExtensionContext,
    ): Any {
        val state =
            requireNotNull(context.store.get(KEY, TestState::class.java)) {
                "Tap devices are only available inside a test method (not constructors or static callbacks)"
            }
        return when (parameter.parameter.type) {
            Devices::class.java -> {
                Devices(state.devices)
            }

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
        val fromAnnotation =
            method.getAnnotation(TapDevices::class.java)?.roles?.toList()
                ?: context.requiredTestClass
                    .getAnnotation(TapDevices::class.java)
                    ?.roles
                    ?.toList()
        val fromParameters = method.parameters.mapNotNull { it.getAnnotation(TapDevice::class.java)?.role }
        val implicit =
            if (method.parameters.any { it.type == Device::class.java && it.getAnnotation(TapDevice::class.java) == null }) {
                listOf(DEFAULT_ROLE)
            } else {
                emptyList()
            }
        val roles = (fromAnnotation.orEmpty() + fromParameters + implicit).distinct()
        require(roles.isNotEmpty()) { "${method.name} declares no Tap devices; add a Device parameter or @TapDevices" }
        return roles
    }

    /**
     * Roles → serials, decided by the client: pinned explicitly (`tap.device.<role>`), then the
     * unpinned [available] serials in declaration order, starting at index [start] (wrapping).
     * [available] is `tap.serials` or, when none are configured, what the server's device list
     * reports. [beforeEach] advances [start] for every test, so concurrent single-device tests
     * spread over the devices instead of queueing on the first one. Duplicate serials (two
     * roles pinned to the same device) fail here, before any session opens: two sessions on one
     * device would serialize on its lock instead of testing concurrently.
     */
    internal fun assignSerials(
        roles: List<String>,
        available: List<String>,
        pinned: Map<String, String>,
        start: Int = 0,
    ): Map<String, String> {
        val relevantPins = pinned.filterKeys { it in roles }
        val duplicatedPins =
            relevantPins.values
                .groupingBy { it }
                .eachCount()
                .filter { it.value > 1 }
        require(duplicatedPins.isEmpty()) {
            "duplicate tap.device pins for serials ${duplicatedPins.keys}: ${relevantPins.filterValues { it in duplicatedPins }}; " +
                "each role needs its own device"
        }
        val free = rotate(available.filter { it !in relevantPins.values }, start).toMutableList()
        val assignment = roles.associateWith { role -> relevantPins[role] ?: free.removeFirst() }
        require(assignment.values.toSet().size == assignment.size) {
            "duplicate serial assignment $assignment; each role needs its own device"
        }
        return assignment
    }

    /** [serials] starting at index [start] (modulo the size), wrapping around. */
    internal fun rotate(
        serials: List<String>,
        start: Int,
    ): List<String> {
        if (serials.isEmpty()) return serials
        val offset = Math.floorMod(start, serials.size)
        return serials.drop(offset) + serials.take(offset)
    }

    /**
     * Opens one unpinned [role] on the first of [candidates] that is free right now (no wait),
     * trying the next on [DeviceBusyException]. When every candidate is busy it waits for the
     * first one, bounded by `tap.acquireTimeoutSeconds`, as a multi-role test does.
     */
    internal suspend fun openSingle(
        connection: io.github.noamcohen48.tap.sdk.TapConnection,
        role: String,
        candidates: List<String>,
        config: TapConfig,
    ): Map<String, Device> {
        if (candidates.size > 1) {
            for (serial in candidates) {
                try {
                    return mapOf(role to connection.attachDevice(serial, config.autPackage, options = DeviceOptions()))
                } catch (_: DeviceBusyException) {
                    // Held by another session right now: try the next device.
                }
            }
        }
        val options = DeviceOptions(waitForDevice = config.acquireTimeout)
        return mapOf(role to connection.attachDevice(candidates.first(), config.autPackage, options = options))
    }

    /**
     * Opens the sessions one at a time in sorted serial order. Every process takes device locks
     * in the same order, so two tests wanting the same two devices cannot deadlock; the second
     * simply waits (bounded by `tap.acquireTimeoutSeconds`) for the first to finish. Caller
     * runs inside the setup scope (a child of the root job), so setup cancellation propagates.
     * A duplicated serial in [assignment] fails before any session opens (see [assignSerials]).
     * On failure every opened device is closed (bounded, non-cancellable) with the errors
     * suppressed into the opener before rethrow.
     */
    internal suspend fun openAll(
        connection: io.github.noamcohen48.tap.sdk.TapConnection,
        assignment: Map<String, String>,
        config: TapConfig,
    ): Map<String, Device> {
        require(assignment.values.toSet().size == assignment.size) {
            "duplicate serial assignment $assignment; each role needs its own device"
        }
        val opened = linkedMapOf<String, Device>()
        val options = DeviceOptions(waitForDevice = config.acquireTimeout)
        try {
            assignment.entries.sortedBy { it.value }.forEach { (role, serial) ->
                opened[role] = connection.attachDevice(serial, config.autPackage, options = options)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                withTimeoutOrNull(120_000) {
                    opened.values.forEach { device ->
                        runCatching {
                            withContext(TapContext("junit:setup-cleanup")) { device.detach() }
                        }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
            }
            throw error
        }
        return assignment.keys.associateWith { opened.getValue(it) }
    }

    /**
     * Closes every opened device (sorted serial order is open order; close order follows the
     * stored map) under a bounded non-cancellable context, collecting — never throwing —
     * per-device errors for the caller to preserve against the primary failure.
     */
    internal fun closeAll(state: TestState): List<Throwable> {
        val errors = mutableListOf<Throwable>()
        runBlocking(TapContext("junit:${state.method}:teardown")) {
            withContext(NonCancellable) {
                withTimeoutOrNull(120_000) {
                    state.devices.values.forEach { device ->
                        runCatching { device.detach() }.exceptionOrNull()?.let(errors::add)
                    }
                } ?: errors.add(TapException("device teardown timed out"))
            }
        }
        return errors
    }

    /**
     * Failure artifacts: `failure.txt` with the stack trace, then [capture] of every device in
     * parallel, saved as `<role>-<serial>.<part>.<ext>`. Never masks the test failure: a part or
     * file that cannot be produced is simply missing.
     */
    private suspend fun captureArtifacts(
        context: ExtensionContext,
        state: TestState,
        failure: Throwable,
    ) {
        val dir =
            TapConfig.current.artifactsDir
                .resolve(context.requiredTestClass.name)
                .resolve(context.requiredTestMethod.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        withContext(Dispatchers.IO) {
            runCatching {
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("failure.txt"), failure.stackTraceToString())
            }
        }
        coroutineScope {
            state.devices.forEach { (role, device) ->
                launch {
                    val captured = runCatching { device.capture() }.getOrNull() ?: return@launch
                    withContext(Dispatchers.IO) { runCatching { captured.saveTo(dir, "$role-${device.serial}") } }
                }
            }
        }
    }

    private val ExtensionContext.store: ExtensionContext.Store
        get() = getStore(Namespace.create(TapExtension::class.java, requiredTestMethod))

    private companion object {
        const val KEY = "tap.devices"

        /** JVM-wide start index for [assignSerials]; advanced once per test. */
        val ROTATION = AtomicInteger(0)
    }
}
