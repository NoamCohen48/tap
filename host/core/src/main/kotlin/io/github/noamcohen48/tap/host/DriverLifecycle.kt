package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The driver's application package (a shell APK: `versionName` = the engine version). */
const val DRIVER_PACKAGE = "io.github.noamcohen48.tap.driver"

/** The driver's instrumentation package, which carries the driver code. AGP stamps no version
 * on it (`versionName=null`, `versionCode=0`); its build is what the handshake reports. */
const val DRIVER_TEST_PACKAGE = "$DRIVER_PACKAGE.test"
const val DRIVER_TEST_RUNNER = "$DRIVER_TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
const val DEVICE_PORT = 27183
val DEVICE_PORT_RANGE = 27183..27187
const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"
const val LATE_MUTATION_QUARANTINE = "UNINTERRUPTIBLE_MUTATION_RESET_REQUIRED"

/**
 * Cleanup-step equivalent of `runCatching(block).onFailure(record)`, except coroutine
 * cancellation always rethrows instead of being recorded. `runCatching` catches
 * `CancellationException` — including a `withTimeoutOrNull` bound firing — which would convert
 * "cleanup exceeded its bound" into a misleading recorded failure and defeat the bound.
 * Non-cancellation failures are passed to [record] (usually `firstFailure = firstFailure ?: it`).
 */
internal suspend inline fun cleanupStep(
    record: (Throwable) -> Unit,
    block: () -> Unit,
) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        record(error)
    }
}

/** One AUT process identity: PID plus its `/proc` start token. PID alone is never identity. */
data class ProcessObservation(
    val pid: Int,
    val startToken: String,
)

/** The `am instrument` child that hosts a running driver, plus its captured output. */
data class RunningInstrumentation(
    val process: Process,
    val output: StringBuilder,
    val outputDrain: Job,
    val devicePort: Int,
    val driverInstanceId: String,
    internal val drainScope: CoroutineScope,
)

suspend fun isPortListening(
    adb: Adb,
    serial: String,
    port: Int,
): Boolean = adb.isPortListening(serial, port)

enum class ResetRecoveryAction {
    REBOOT,
    COMPLETE,
}

fun resetRecoveryAction(
    record: SessionJournal,
    currentBootId: String,
): ResetRecoveryAction {
    require(record.state == JournalState.QUARANTINED && record.resetRequired) {
        "Journal does not require reset recovery"
    }
    val startedBootId = record.resetStartedBootId
    if (startedBootId == null) {
        require(record.bootId == currentBootId) {
            "Reset was not durably started before boot identity changed"
        }
        return ResetRecoveryAction.REBOOT
    }
    require(record.bootId == startedBootId) { "Reset origin does not match quarantined boot identity" }
    return if (currentBootId == startedBootId) ResetRecoveryAction.REBOOT else ResetRecoveryAction.COMPLETE
}

/**
 * Starts the driver instrumentation and waits for its `TAP_READY` marker, walking the reserved
 * port range when a port is occupied. [onStarting] runs before each attempt so the caller can
 * journal the port. Extra instrumentation arguments (fault points, heartbeat overrides) go in
 * [driverArguments]; the driver logs [logSink] line by line.
 *
 * Each attempt owns its process and drain until it hands them to the returned
 * [RunningInstrumentation]: cancellation or failure before the handoff force-stops, kills,
 * reaps and drains boundedly in NonCancellable cleanup, so no attempt can leak a child.
 */
suspend fun startDriverWithRetry(
    adb: Adb,
    serial: String,
    sessionId: String,
    generation: Long,
    encodedSecret: String,
    autPackage: String,
    syncAuthority: String = "$autPackage.tap-sync",
    allowedSystemPackages: Set<String> = setOf(PERMISSION_CONTROLLER_PACKAGE),
    overallDeadlineNanos: Long? = null,
    driverArguments: Map<String, String> = emptyMap(),
    logSink: (String) -> Unit = ::println,
    processStarter: ProcessStarter = DefaultProcessStarter,
    onStarting: (Int) -> Unit,
): RunningInstrumentation {
    var lastOutput = ""
    for (devicePort in DEVICE_PORT_RANGE) {
        if (overallDeadlineNanos != null && System.nanoTime() >= overallDeadlineNanos) {
            throw DriverStartException("Driver startup on $serial exceeded its containing deadline")
        }
        onStarting(devicePort)
        lateinit var process: Process
        // Creation and ownership installation are one non-cancellable step. If cancellation
        // arrives after the OS child exists but before start() returns, the returned Process
        // cannot be discarded before the attempt's finally owns it.
        withContext(NonCancellable) {
            process =
                withContext(Dispatchers.IO) {
                    processStarter.start(
                        listOf(adb.executable, "-s", serial, "shell") +
                            // `adb shell` joins its arguments and the device `sh` re-parses them,
                            // so every token is shell-quoted: a value holding `;`, `$` or a space
                            // stays one argument instead of becoming a command.
                            listOf(
                                "am",
                                "instrument",
                                "-w",
                                "-r",
                                "-e",
                                "class",
                                "io.github.noamcohen48.tap.driver.TapDriverServerTest",
                                "-e",
                                "tapSession",
                                sessionId,
                                "-e",
                                "tapGeneration",
                                generation.toString(),
                                "-e",
                                "tapSecret",
                                encodedSecret,
                                "-e",
                                "tapPort",
                                devicePort.toString(),
                                "-e",
                                "tapAutPackage",
                                autPackage,
                                "-e",
                                "tapSystemPackages",
                                allowedSystemPackages.joinToString(","),
                                "-e",
                                "tapSyncAuthority",
                                syncAuthority,
                                *driverArguments.flatMap { (key, value) -> listOf("-e", key, value) }.toTypedArray(),
                                DRIVER_TEST_RUNNER,
                            ).map(::shellQuote),
                    )
                }
        }
        val output = StringBuilder()
        val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val outputDrain =
            drainScope.launch {
                withContext(Dispatchers.IO) {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.appendLine(line) }
                        logSink(line)
                    }
                }
            }
        var handedOff = false
        var attemptCleanupError: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            val markerPrefix = "TAP_READY session=$sessionId generation=$generation port=$devicePort instance="
            val deadline =
                minOf(
                    System.nanoTime() + 10_000_000_000L,
                    overallDeadlineNanos ?: Long.MAX_VALUE,
                )
            var instanceId: String? = null
            while (System.nanoTime() < deadline && process.isAlive) {
                instanceId =
                    synchronized(output) {
                        output
                            .lineSequence()
                            .firstOrNull { markerPrefix in it }
                            ?.substringAfter(markerPrefix)
                            ?.trim()
                    }
                if (!instanceId.isNullOrEmpty()) break
                delay(25)
            }
            if (!instanceId.isNullOrEmpty()) {
                handedOff = true
                return RunningInstrumentation(process, output, outputDrain, devicePort, instanceId, drainScope)
            }
        } finally {
            if (!handedOff) {
                attemptCleanupError = cleanupAttempt(adb, serial, process, outputDrain, drainScope)
            }
        }
        // A cancelled waiter propagated through the finally above; only a genuinely unready
        // driver reaches the port-occupancy diagnosis.
        attemptCleanupError?.let { throw it }
        lastOutput = synchronized(output) { output.toString() }
        if ("already registered" in lastOutput) {
            throw DriverStartException("Another UiAutomation instrumentation session is active on $serial")
        }
        if ("BindException" !in lastOutput || "EADDRINUSE" !in lastOutput) {
            throw DriverStartException("Driver on $serial failed before readiness for a reason other than port occupancy: $lastOutput")
        }
        if (!isPortListening(adb, serial, devicePort)) {
            throw DriverStartException("Driver on $serial reported EADDRINUSE but the port was free after verified driver death")
        }
    }
    throw DriverStartException("Driver on $serial failed to bind any reserved port: $lastOutput")
}

/**
 * Best-effort cleanup of one instrumentation attempt that never reached handoff: force-stop
 * and verify, kill and reap the child, close its stream, join the drain boundedly. Runs in
 * NonCancellable cleanup (a cancelled waiter is already propagating) and never throws: the
 * first failure is returned so the caller can abort the retry loop after diagnosing the output.
 */
private suspend fun cleanupAttempt(
    adb: Adb,
    serial: String,
    process: Process,
    outputDrain: Job,
    drainScope: CoroutineScope,
): Throwable? =
    withContext(NonCancellable) {
        var failure: Throwable? = null
        cleanupStep({ failure = failure ?: it }) { forceStopDriverAndVerify(adb, serial) }
        cleanupStep({ failure = failure ?: it }) {
            if (process.isAlive) process.destroyForcibly()
        }
        withContext(Dispatchers.IO) {
            cleanupStep({ failure = failure ?: it }) {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    throw DeviceQuarantinedException(serial, "Instrumentation child survived failed startup cleanup on $serial")
                }
            }
        }
        closeProcessStreams(process) { failure = failure ?: it }
        val drainCompleted = withTimeoutOrNull(1_000) { outputDrain.join(); true } == true
        if (!drainCompleted || !outputDrain.isCompleted) {
            failure = failure ?: DeviceQuarantinedException(serial, "Instrumentation output drain survived failed startup cleanup on $serial")
        }
        drainScope.cancel()
        failure
    }

/** Force-stops the driver package, verifies it is gone, and reaps the instrumentation child. */
suspend fun cleanupInstrumentation(
    adb: Adb,
    serial: String,
    running: RunningInstrumentation,
) {
    var driverFailure: Throwable? = null
    cleanupStep({ driverFailure = it }) { forceStopDriverAndVerify(adb, serial) }
    cleanupStep({ driverFailure = driverFailure ?: it }) {
        if (running.process.isAlive) running.process.destroyForcibly()
    }
    withContext(Dispatchers.IO) {
        cleanupStep({ driverFailure = driverFailure ?: it }) {
            if (!running.process.waitFor(3, TimeUnit.SECONDS)) {
                throw DeviceQuarantinedException(serial, "Instrumentation child survived cleanup on $serial")
            }
        }
    }
    closeProcessStreams(running.process) { driverFailure = driverFailure ?: it }
    val drainCompleted = withTimeoutOrNull(1_000) { running.outputDrain.join(); true } == true
    if (!drainCompleted || !running.outputDrain.isCompleted) {
        driverFailure = driverFailure ?: DeviceQuarantinedException(serial, "Instrumentation output drain survived cleanup on $serial")
    }
    running.drainScope.cancel()
    driverFailure?.let { throw it }
}

/**
 * Reconciles the on-disk journal with the device before a new session: proves the old driver
 * identity is dead, removes only journal-owned forwards, and quarantines anything it cannot
 * prove (corrupt journal, changed boot, out-of-range port, existing quarantine).
 */
suspend fun recoverJournal(
    adb: Adb,
    serial: String,
    bootId: String,
    store: SessionJournalStore,
    allowResetRecovery: Boolean = false,
): SessionJournal? {
    val record =
        try {
            store.read()
        } catch (error: Throwable) {
            forceStopDriverAndVerify(adb, serial)
            store.preserveCorrupt()
            store.replaceCorruptWith(
                SessionJournal(
                    state = JournalState.QUARANTINED,
                    serial = serial,
                    bootId = bootId,
                    sessionId = UUID.randomUUID().toString(),
                    generation = 0,
                    devicePort = DEVICE_PORT,
                ),
            )
            throw CorruptJournalException(serial, "Corrupt journal on $serial quarantined; no forwards were removed", error)
        }
    if (record == null) {
        forceStopDriverAndVerify(adb, serial)
        return null
    }
    // Both are enforced by SessionJournalStore.read's validation; a violation here is a bug.
    check(record.serial == serial) { "Journal serial does not match leased device" }
    check(record.version == 1) { "Unsupported journal version: ${record.version}" }
    if (record.state == JournalState.QUARANTINED) {
        if (
            allowResetRecovery && record.resetRequired &&
            record.quarantineReason == LATE_MUTATION_QUARANTINE
        ) {
            return record
        }
        throw DeviceQuarantinedException(
            serial,
            "Device $serial is quarantined by its session journal" +
                (record.quarantineReason?.let { ": $it" } ?: ""),
        )
    }
    if (record.devicePort !in DEVICE_PORT_RANGE) {
        store.write(record.copy(state = JournalState.QUARANTINED))
        throw DeviceQuarantinedException(serial, "Journal device port on $serial is outside the reserved framework range; device quarantined")
    }

    if (record.state != JournalState.CLOSED) {
        if (record.bootId != bootId) {
            store.write(record.copy(state = JournalState.QUARANTINED))
            throw DeviceQuarantinedException(serial, "Active journal boot identity on $serial changed; device quarantined")
        }
        forceStopDriverAndVerify(adb, serial, record.driverPid, record.driverStartToken)
        if (record.hostPort != null) {
            removeExactForward(adb, serial, record.hostPort, record.devicePort)
        } else {
            adb
                .forwards(serial)
                .filter { it.devicePort == record.devicePort }
                .forEach { removeExactForward(adb, serial, it.hostPort, record.devicePort) }
        }
        if (adb.bootId(serial) != bootId) {
            store.write(record.copy(state = JournalState.QUARANTINED))
            throw DeviceQuarantinedException(serial, "Boot identity on $serial changed during recovery; device quarantined")
        }
        val closed =
            record.copy(
                state = JournalState.CLOSED,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        store.write(closed)
        return closed
    }

    forceStopDriverAndVerify(adb, serial, record.driverPid, record.driverStartToken)
    return record
}

suspend fun forceStopDriverAndVerify(
    adb: Adb,
    serial: String,
    oldPid: Int? = null,
    oldStartToken: String? = null,
) {
    adb.forceStop(serial, DRIVER_PACKAGE)
    val deadline = System.nanoTime() + 5_000_000_000L
    while (System.nanoTime() < deadline) {
        val packageGone = adb.processIds(serial, DRIVER_PACKAGE).isEmpty()
        val oldIdentityGone =
            if (oldPid != null && oldStartToken != null) {
                processIdentityIsGone(adb, serial, oldPid, oldStartToken)
            } else {
                true
            }
        if (packageGone && oldIdentityGone) return
        delay(50)
    }
    throw DriverStartException("Driver process on $serial survived package force-stop")
}

private suspend fun processIdentityIsGone(
    adb: Adb,
    serial: String,
    pid: Int,
    startToken: String,
): Boolean =
    when (val stat = adb.processStat(serial, pid)) {
        Adb.ProcessStat.Gone -> true
        is Adb.ProcessStat.Live -> stat.startToken != startToken // the PID was reused by a new process
    }

suspend fun processStartToken(
    adb: Adb,
    serial: String,
    pid: Int,
): String =
    when (val stat = adb.processStat(serial, pid)) {
        Adb.ProcessStat.Gone -> throw DriverStartException("Process $pid on $serial exited before its start token was read")
        is Adb.ProcessStat.Live -> stat.startToken
    }

suspend fun removeExactForward(
    adb: Adb,
    serial: String,
    hostPort: Int,
    devicePort: Int,
) {
    val existing = adb.forwards(serial).firstOrNull { it.hostPort == hostPort } ?: return
    if (existing.devicePort != devicePort) {
        throw DeviceQuarantinedException(serial, "Journal forward tcp:$hostPort on $serial does not target expected tcp:$devicePort")
    }
    adb.removeForward(serial, hostPort)
    if (adb.forwards(serial).any { it.hostPort == hostPort }) {
        throw DeviceQuarantinedException(serial, "Forward tcp:$hostPort on $serial survived exact removal")
    }
}

/** Waits (up to [timeoutMs]) for exactly one process of [packageName] and reads its identity. */
suspend fun observeProcess(
    adb: Adb,
    serial: String,
    packageName: String,
    timeoutMs: Long = 30_000,
): ProcessObservation {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    var lastFailure = "AUT process was absent"
    while (System.nanoTime() < deadline) {
        val pids = adb.processIds(serial, packageName)
        if (pids.size == 1) {
            val pid = pids.single()
            when (val stat = runCatching { adb.processStat(serial, pid, timeoutMs = 5_000) }.getOrNull()) {
                is Adb.ProcessStat.Live -> return ProcessObservation(pid, stat.startToken)
                Adb.ProcessStat.Gone -> lastFailure = "AUT PID $pid exited before its start token was observed"
                null -> lastFailure = "Unable to read /proc stat for PID $pid"
            }
        } else {
            lastFailure = "Expected one AUT process, got $pids"
        }
        delay(50)
    }
    throw AppLifecycleException("$lastFailure on $serial")
}

/**
 * Connects and authenticates, retrying until the driver accepts or the deadline passes. Only
 * transient failures are retried: a refused connect or an I/O failure before the driver's
 * `CHALLENGE` (a forward whose driver is not listening yet accepts and then closes). A
 * [DriverHandshakeException] (authentication, identity or version mismatch), any other failure,
 * and cancellation propagate at once.
 */
suspend fun connectWithRetry(
    hostPort: Int,
    sessionId: String,
    generation: Long,
    secret: ByteArray,
    overallDeadlineNanos: Long? = null,
    serial: String? = null,
    heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
): DriverClient {
    val deadline =
        minOf(
            System.nanoTime() + 20_000_000_000L,
            overallDeadlineNanos ?: Long.MAX_VALUE,
        )
    var lastError: Throwable? = null
    while (System.nanoTime() < deadline) {
        try {
            return DriverClient.connect(hostPort, sessionId, generation, secret, overallDeadlineNanos, serial, heartbeatIntervalMs)
        } catch (transient: IOException) {
            lastError = transient
            delay(100)
        }
    }
    throw DriverStartException("Driver did not accept a connection before the deadline", lastError)
}
