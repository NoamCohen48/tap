package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.sdk.AppDeath
import io.github.noamcohen48.tap.sdk.AppDiedException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Namespace
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.opentest4j.TestAbortedException
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
 * installs the SDK [TapContext], in the test method and in its `@BeforeEach` / `@AfterEach`
 * methods; [Device] calls outside it fail with a usage error, so plain metadata-only tests stay
 * possible but cannot touch live devices.
 *
 * JUnit timeout/interruption cancels the root job (via [tapTest]'s `runBlocking`); sibling
 * failure in `coroutineScope`/`async` cancels the other device's in-flight RPC by structured
 * rules. After the test (or setup) it captures failure artifacts while sessions are still
 * live when possible, then cancels the root job and performs bounded non-cancellable cleanup,
 * preserving the primary failure and suppressing cleanup failures into it. With
 * `@TapTest(deviceLifetime = PER_CLASS)` the devices are kept in the class store instead,
 * probed before each reuse, and detached in `afterAll`.
 */
class TapExtension :
    BeforeEachCallback,
    AfterEachCallback,
    AfterAllCallback,
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
                    val criteria = declaredCriteria(context.requiredTestClass, context.requiredTestMethod, roles)
                    val held = if (perClass(context)) reusable(context, roles, criteria) else null
                    if (held != null) return@runBlocking TestState(rootJob, held, held.mapValues { it.value.serial }, method)
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
                    val connection = SharedConnection.connection()
                    val single = roles.singleOrNull()?.takeIf { it !in config.pinnedRoles }
                    val devices =
                        when {
                            criteria.isNotEmpty() -> openMatching(connection, roles, criteria, rotate(available, start), config, method)
                            single != null -> openSingle(connection, single, rotate(available, start), config)
                            else -> openAll(connection, assignSerials(roles, available, config.pinnedRoles, start), config)
                        }
                    if (perClass(context)) context.classStore.put(HELD, HeldDevices(roles, criteria, devices))
                    TestState(rootJob, devices, devices.mapValues { it.value.serial }, method)
                }
            context.store.put(KEY, state)
            RunReports.current(context.uniqueId)?.let { recording ->
                state.devices.forEach { (role, device) ->
                    runCatching {
                        recording.run.event("device_attached", mapOf(
                            "test" to recording.key, "role" to role, "serial" to device.serial, "generation" to device.generation,
                        ))
                    }.onFailure { recording.run.markIncomplete() }
                }
            }
        } catch (failure: Throwable) {
            rootJob.cancel(CancellationException("beforeEach failed", failure))
            throw failure
        }
    }

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = checkingAppDeaths(extensionContext) { bound(invocation, extensionContext) }

    /** `@RepeatedTest`, `@ParameterizedTest` and other templates reach their method here. */
    override fun interceptTestTemplateMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = checkingAppDeaths(extensionContext) { bound(invocation, extensionContext) }

    /**
     * The end-of-test app death check (`tap.appDeaths`, [AllowAppDeath]): marks each device's death
     * sequence before the test method and, after it, asks for watched deaths nothing handed to the
     * test ([Device.takeUnhandledDeaths]). With any, a passing test fails with [AppDiedException];
     * a failing one with it instead, its own failure suppressed (the death is the likelier cause).
     * A failed assumption stays a skip. A device that cannot answer is left out, never a failure.
     */
    private fun checkingAppDeaths(
        context: ExtensionContext,
        run: () -> Unit,
    ) {
        val state = context.store.get(KEY, TestState::class.java)
        if (state == null || TapConfig.current.appDeaths == AppDeathMode.OFF || allowsAppDeath(context)) return run()
        checkAppDeaths(state.devices, state.method, run)
    }

    /** [checkingAppDeaths] over [devices]: runs [run] between the marks and the check. */
    internal fun checkAppDeaths(
        devices: Map<String, Device>,
        method: String,
        run: () -> Unit,
    ) {
        val marks =
            runBlocking(TapContext("junit:$method:deaths")) {
                devices.mapValues { (_, device) -> runCatching { device.deathSequence() }.getOrNull() }
            }
        val failure = runCatching(run).exceptionOrNull()
        if (failure is TestAbortedException) throw failure
        val deaths =
            runBlocking(TapContext("junit:$method:deaths")) {
                withContext(NonCancellable) {
                    // A crash the test's last input caused is logged shortly after it.
                    if (marks.values.any { it != null }) delay(AppDeath.SETTLE)
                    devices.flatMap { (role, device) ->
                        val mark = marks[role] ?: return@flatMap emptyList()
                        runCatching { device.takeUnhandledDeaths(mark) }.getOrDefault(emptyList())
                    }
                }
            }
        if (deaths.isEmpty()) {
            failure?.let { throw it }
            return
        }
        throw AppDiedException(deaths.sortedBy { it.at }).apply { failure?.let(::addSuppressed) }
    }

    private fun allowsAppDeath(context: ExtensionContext): Boolean =
        context.requiredTestMethod.isAnnotationPresent(AllowAppDeath::class.java) ||
            context.requiredTestClass.isAnnotationPresent(AllowAppDeath::class.java)

    override fun interceptBeforeEachMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) = bound(invocation, extensionContext)

    /**
     * An `@AfterEach` is cleanup: it still runs after a failing test, whose `tapTest` cancelled
     * the root job, so it gets a job of its own and can use the devices.
     */
    override fun interceptAfterEachMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        val cleanupJob = Job()
        try {
            bound(invocation, extensionContext) { TestState(cleanupJob, it.devices, it.assignment, it.method) }
        } finally {
            cleanupJob.cancel()
        }
    }

    /** Runs a test or lifecycle method with [tapTest] bound to the test's state. */
    private fun bound(
        invocation: InvocationInterceptor.Invocation<Void>,
        context: ExtensionContext,
        binding: (TestState) -> TestState = { it },
    ) {
        val state = context.store.get(KEY, TestState::class.java)
        if (state == null) {
            invocation.proceed()
            return
        }
        TapTestBinding.current.set(binding(state))
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
            // An aborted test (a failed assumption) was skipped, not failed: nothing to explain.
            if (failure != null && failure !is TestAbortedException && TapConfig.current.capture == CaptureMode.ON_FAILURE) {
                runBlocking(TapContext("junit:${state.method}:artifacts")) {
                    withContext(NonCancellable) { captureArtifacts(context, state, failure) }
                }
            }
        } finally {
            // PER_CLASS keeps the devices for the next test; afterAll detaches them.
            val closeErrors = if (perClass(context)) emptyList() else closeAll(state)
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

    override fun afterAll(context: ExtensionContext) {
        val held = context.classStore.remove(HELD, HeldDevices::class.java) ?: return
        val errors = closeAll(TestState(Job(), held.devices, emptyMap(), "afterAll"))
        errors.firstOrNull()?.let { first ->
            errors.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun perClass(context: ExtensionContext): Boolean =
        context.requiredTestClass.getAnnotation(TapTest::class.java)?.deviceLifetime == DeviceLifetime.PER_CLASS

    /**
     * The class's held devices when they serve [roles] under the same [criteria] and all still answer (`info()`, a few ms);
     * otherwise null, after detaching whatever was held so the caller attaches afresh. A detach
     * failure of a device that already stopped working is dropped: the attach that follows
     * reports the device's state (quarantined, offline) itself.
     */
    private suspend fun reusable(
        context: ExtensionContext,
        roles: List<String>,
        criteria: Map<String, DeviceCriteria>,
    ): Map<String, Device>? {
        val held = context.classStore.get(HELD, HeldDevices::class.java) ?: return null
        val alive = held.roles == roles && held.criteria == criteria && held.devices.values.all { !it.isDetached && runCatching { it.info() }.isSuccess }
        if (alive) return held.devices
        context.classStore.remove(HELD)
        withContext(NonCancellable) {
            withTimeoutOrNull(120_000) { held.devices.values.forEach { runCatching { it.detach() } } }
        }
        return null
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
                    return mapOf(role to connection.attachDevice(serial, options = DeviceOptions()))
                } catch (_: DeviceBusyException) {
                    // Held by another session right now: try the next device.
                }
            }
        }
        val options = DeviceOptions(waitForDevice = config.acquireTimeout)
        return mapOf(role to connection.attachDevice(candidates.first(), options = options))
    }

    /**
     * Opens devices for roles with [RequireDevice] criteria: an unpinned single role moves past
     * busy matching devices as usual; otherwise one matching assignment is opened in sorted
     * serial order. Skips the test, with nothing attached, when no device or combination fits.
     */
    private suspend fun openMatching(
        connection: io.github.noamcohen48.tap.sdk.TapConnection,
        roles: List<String>,
        criteria: Map<String, DeviceCriteria>,
        available: List<String>,
        config: TapConfig,
        method: String,
    ): Map<String, Device> {
        val entries = connection.client.devices().associateBy { it.serial }
        val assignment = chooseSerials(roles, criteria, available, entries, config.pinnedRoles)
        assumeTrue(assignment != null) {
            val wanted = criteria.entries.joinToString("; ") { (role, wanted) -> "$role: $wanted" }
            "$method needs $wanted; no fit among ${available.joinToString { describeDevice(it, entries[it]) }}"
        }
        val single = roles.singleOrNull()?.takeIf { it !in config.pinnedRoles }
        if (single != null) {
            val matching = available.filter { (criteria[single] ?: DeviceCriteria()).matches(entries[it]) }
            return openSingle(connection, single, matching, config)
        }
        return openAll(connection, assignment!!, config)
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
                opened[role] = connection.attachDevice(serial, options = options)
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
        val recording = RunReports.current(context.uniqueId)
        if (recording != null) {
            captureRecorded(recording, state, failure)
            return
        }
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

    private suspend fun captureRecorded(recording: Recording, state: TestState, failure: Throwable) {
        val run = recording.run
        val key = recording.key
        fun recordError(part: String, error: Throwable, identity: Map<String, Any?> = emptyMap()) {
            runCatching { run.error(key, part, error, identity) }.onFailure { run.markIncomplete() }
        }
        withContext(Dispatchers.IO) {
            runCatching { run.artifact(key, "failure", failure.stackTraceToString().encodeToByteArray(), "txt", "text/plain") }
                .onFailure { recordError("failure", it) }
        }
        coroutineScope {
            state.devices.forEach { (role, device) ->
                launch {
                    val identity = mapOf("role" to role, "serial" to device.serial, "generation" to device.generation)
                    val captured = runCatching { device.capture() }.getOrElse { error ->
                        recordError("capture", error, identity)
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        captured.failures.forEach { (part, error) -> recordError(part, error, identity) }
                        captured.artifacts.forEach { (part, artifact) ->
                            runCatching { run.artifact(key, part, artifact.bytes, artifact.extension, artifact.mediaType, role, device.serial, device.generation) }
                                .onFailure { error -> recordError(part, error, identity) }
                        }
                    }
                }
            }
        }
    }

    private val ExtensionContext.store: ExtensionContext.Store
        get() = getStore(Namespace.create(TapExtension::class.java, requiredTestMethod))

    /** The test class's store, which outlives its methods (PER_CLASS devices). */
    private val ExtensionContext.classStore: ExtensionContext.Store
        get() {
            val classContext = generateSequence(this) { it.parent.orElse(null) }.first { it.testMethod.isEmpty }
            return classContext.getStore(Namespace.create(TapExtension::class.java, classContext.requiredTestClass))
        }

    /** Devices a PER_CLASS test class holds across its tests, with the roles and criteria they were opened for. */
    private class HeldDevices(
        val roles: List<String>,
        val criteria: Map<String, DeviceCriteria>,
        val devices: Map<String, Device>,
    )

    private companion object {
        const val KEY = "tap.devices"
        const val HELD = "tap.classDevices"

        /** JVM-wide start index for [assignSerials]; advanced once per test. */
        val ROTATION = AtomicInteger(0)
    }
}
