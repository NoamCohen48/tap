package com.company.tap.host

import java.util.UUID
import java.util.concurrent.TimeUnit

const val DRIVER_PACKAGE = "com.company.tap.driver"
const val DRIVER_TEST_RUNNER = "$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"
const val DEVICE_PORT = 27183
val DEVICE_PORT_RANGE = 27183..27187
const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"
const val LATE_MUTATION_QUARANTINE = "UNINTERRUPTIBLE_MUTATION_RESET_REQUIRED"

/** One AUT process identity: PID plus its `/proc` start token. PID alone is never identity. */
data class ProcessObservation(val pid: Int, val startToken: String)

/** The `am instrument` child that hosts a running driver, plus its captured output. */
data class RunningInstrumentation(
    val process: Process,
    val output: StringBuilder,
    val outputThread: Thread,
    val devicePort: Int,
    val driverInstanceId: String,
)

fun isPortListening(adb: Adb, serial: String, port: Int): Boolean {
    val expectedPort = port.toString(16).uppercase().padStart(4, '0')
    return adb.run(serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")
        .lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .any { fields ->
            fields.size > 3 && fields[1].endsWith(":$expectedPort") && fields[3] == "0A"
        }
}

enum class ResetRecoveryAction {
    REBOOT,
    COMPLETE,
}

fun resetRecoveryAction(record: SessionJournal, currentBootId: String): ResetRecoveryAction {
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
 */
fun startDriverWithRetry(
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
    onStarting: (Int) -> Unit,
): RunningInstrumentation {
    var lastOutput = ""
    for (devicePort in DEVICE_PORT_RANGE) {
        check(overallDeadlineNanos == null || System.nanoTime() < overallDeadlineNanos) {
            "Driver startup exceeded its containing deadline"
        }
        onStarting(devicePort)
        val process = ProcessBuilder(
            adb.executable, "-s", serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.company.tap.driver.TapDriverServerTest",
            "-e", "tapSession", sessionId,
            "-e", "tapGeneration", generation.toString(),
            "-e", "tapSecret", encodedSecret,
            "-e", "tapPort", devicePort.toString(),
            "-e", "tapAutPackage", autPackage,
            "-e", "tapSystemPackages", allowedSystemPackages.joinToString(","),
            "-e", "tapSyncAuthority", syncAuthority,
            *driverArguments.flatMap { (key, value) -> listOf("-e", key, value) }.toTypedArray(),
            DRIVER_TEST_RUNNER,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val outputThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
                logSink(line)
            }
        }.apply {
            isDaemon = true
            start()
        }
        val markerPrefix = "TAP_READY session=$sessionId generation=$generation port=$devicePort instance="
        val deadline = minOf(
            System.nanoTime() + 10_000_000_000L,
            overallDeadlineNanos ?: Long.MAX_VALUE,
        )
        var instanceId: String? = null
        while (System.nanoTime() < deadline && process.isAlive) {
            instanceId = synchronized(output) {
                output.lineSequence()
                    .firstOrNull { markerPrefix in it }
                    ?.substringAfter(markerPrefix)
                    ?.trim()
            }
            if (!instanceId.isNullOrEmpty()) break
            Thread.sleep(25)
        }
        if (!instanceId.isNullOrEmpty()) {
            return RunningInstrumentation(process, output, outputThread, devicePort, instanceId)
        }

        cleanupInstrumentation(
            adb,
            serial,
            RunningInstrumentation(process, output, outputThread, devicePort, ""),
        )
        lastOutput = synchronized(output) { output.toString() }
        check("already registered" !in lastOutput) {
            "Another UiAutomation instrumentation session is active on $serial"
        }
        check("BindException" in lastOutput && "EADDRINUSE" in lastOutput) {
            "Driver failed before readiness for a reason other than port occupancy: $lastOutput"
        }
        check(isPortListening(adb, serial, devicePort)) {
            "Driver reported EADDRINUSE but the port was free after verified driver death"
        }
    }
    error("Driver failed to bind any reserved port: $lastOutput")
}

/** Force-stops the driver package, verifies it is gone, and reaps the instrumentation child. */
fun cleanupInstrumentation(
    adb: Adb,
    serial: String,
    running: RunningInstrumentation,
) {
    val driverCleanup = runCatching { forceStopDriverAndVerify(adb, serial) }
    if (running.process.isAlive) running.process.destroyForcibly()
    val childExited = running.process.waitFor(3, TimeUnit.SECONDS)
    runCatching { running.process.inputStream.close() }
    running.outputThread.join(1_000)
    driverCleanup.getOrThrow()
    check(childExited) { "Instrumentation child survived cleanup" }
    check(!running.outputThread.isAlive) { "Instrumentation output thread survived cleanup" }
}

/**
 * Reconciles the on-disk journal with the device before a new session: proves the old driver
 * identity is dead, removes only journal-owned forwards, and quarantines anything it cannot
 * prove (corrupt journal, changed boot, out-of-range port, existing quarantine).
 */
fun recoverJournal(
    adb: Adb,
    serial: String,
    bootId: String,
    store: SessionJournalStore,
    allowResetRecovery: Boolean = false,
): SessionJournal? {
    val record = try {
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
            )
        )
        throw IllegalStateException("Corrupt journal quarantined; no forwards were removed", error)
    }
    if (record == null) {
        forceStopDriverAndVerify(adb, serial)
        return null
    }
    check(record.serial == serial) { "Journal serial does not match leased device" }
    check(record.version == 1) { "Unsupported journal version: ${record.version}" }
    if (record.state == JournalState.QUARANTINED) {
        if (
            allowResetRecovery && record.resetRequired &&
            record.quarantineReason == LATE_MUTATION_QUARANTINE
        ) return record
        error("Device is quarantined by its session journal")
    }
    if (record.devicePort !in DEVICE_PORT_RANGE) {
        store.write(record.copy(state = JournalState.QUARANTINED))
        error("Journal device port is outside the reserved framework range")
    }

    if (record.state != JournalState.CLOSED) {
        if (record.bootId != bootId) {
            store.write(record.copy(state = JournalState.QUARANTINED))
            error("Active journal boot identity changed; device quarantined")
        }
        forceStopDriverAndVerify(adb, serial, record.driverPid, record.driverStartToken)
        if (record.hostPort != null) {
            removeExactForward(adb, serial, record.hostPort, record.devicePort)
        } else {
            adb.forwards(serial)
                .filter { it.devicePort == record.devicePort }
                .forEach { removeExactForward(adb, serial, it.hostPort, record.devicePort) }
        }
        if (adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") != bootId) {
            store.write(record.copy(state = JournalState.QUARANTINED))
            error("Boot identity changed during recovery; device quarantined")
        }
        val closed = record.copy(
            state = JournalState.CLOSED,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(closed)
        return closed
    }

    forceStopDriverAndVerify(adb, serial, record.driverPid, record.driverStartToken)
    return record
}

fun forceStopDriverAndVerify(
    adb: Adb,
    serial: String,
    oldPid: Int? = null,
    oldStartToken: String? = null,
) {
    adb.run(serial, "shell", "am", "force-stop", DRIVER_PACKAGE)
    val deadline = System.nanoTime() + 5_000_000_000L
    while (System.nanoTime() < deadline) {
        val packageGone = adb.processIds(serial, DRIVER_PACKAGE).isEmpty()
        val oldIdentityGone = if (oldPid != null && oldStartToken != null) {
            processIdentityIsGone(adb, serial, oldPid, oldStartToken)
        } else {
            true
        }
        if (packageGone && oldIdentityGone) return
        Thread.sleep(50)
    }
    error("Driver process survived package force-stop")
}

private fun processIdentityIsGone(
    adb: Adb,
    serial: String,
    pid: Int,
    startToken: String,
): Boolean {
    val result = adb.runResult(serial, "shell", "cat", "/proc/$pid/stat")
    if (result.exitCode != 0) {
        check("No such file" in result.output || "No such process" in result.output) {
            "Unable to verify old process identity: ${result.output}"
        }
        return true
    }
    val closingName = result.output.lastIndexOf(')')
    check(closingName >= 0) { "Malformed /proc stat for PID $pid" }
    val fieldsFromState = result.output.substring(closingName + 1).trim().split(Regex("\\s+"))
    check(fieldsFromState.size > 19) { "Incomplete /proc stat for PID $pid" }
    return fieldsFromState[19] != startToken
}

fun processStartToken(adb: Adb, serial: String, pid: Int): String {
    val result = adb.runResult(serial, "shell", "cat", "/proc/$pid/stat")
    check(result.exitCode == 0) { "Process $pid is not observable" }
    val closingName = result.output.lastIndexOf(')')
    check(closingName >= 0) { "Malformed /proc stat for PID $pid" }
    val fieldsFromState = result.output.substring(closingName + 1).trim().split(Regex("\\s+"))
    check(fieldsFromState.size > 19) { "Incomplete /proc stat for PID $pid" }
    return fieldsFromState[19]
}

fun removeExactForward(
    adb: Adb,
    serial: String,
    hostPort: Int,
    devicePort: Int,
) {
    val existing = adb.forwards(serial).firstOrNull { it.hostPort == hostPort } ?: return
    check(existing.devicePort == devicePort) {
        "Journal forward tcp:$hostPort does not target expected tcp:$devicePort"
    }
    adb.removeForward(serial, hostPort)
    check(adb.forwards(serial).none { it.hostPort == hostPort }) {
        "Forward tcp:$hostPort survived exact removal"
    }
}

/** Waits (up to [timeoutMs]) for exactly one process of [packageName] and reads its identity. */
fun observeProcess(adb: Adb, serial: String, packageName: String, timeoutMs: Long = 30_000): ProcessObservation {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    var lastFailure = "AUT process was absent"
    while (System.nanoTime() < deadline) {
        val pids = adb.processIds(serial, packageName)
        if (pids.size == 1) {
            val pid = pids.single()
            val stat = adb.runResult(serial, "shell", "cat", "/proc/$pid/stat", timeoutMs = 5_000)
            if (stat.exitCode == 0) {
                val closingName = stat.output.lastIndexOf(')')
                if (closingName >= 0) {
                    val fieldsFromState = stat.output.substring(closingName + 1).trim().split(Regex("\\s+"))
                    if (fieldsFromState.size > 19) return ProcessObservation(pid, fieldsFromState[19])
                    lastFailure = "Incomplete /proc stat for PID $pid"
                } else {
                    lastFailure = "Malformed /proc stat for PID $pid"
                }
            } else {
                lastFailure = "AUT PID $pid exited before its start token was observed"
            }
        } else {
            lastFailure = "Expected one AUT process, got $pids"
        }
        Thread.sleep(50)
    }
    error(lastFailure)
}

/** Connects and authenticates, retrying until the driver accepts or the deadline passes. */
fun connectWithRetry(
    hostPort: Int,
    sessionId: String,
    generation: Long,
    secret: ByteArray,
    overallDeadlineNanos: Long? = null,
    serial: String? = null,
    heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
): DriverClient {
    val deadline = minOf(
        System.nanoTime() + 20_000_000_000L,
        overallDeadlineNanos ?: Long.MAX_VALUE,
    )
    var lastError: Throwable? = null
    while (System.nanoTime() < deadline) {
        try {
            return DriverClient(hostPort, sessionId, generation, secret, overallDeadlineNanos, serial, heartbeatIntervalMs)
        } catch (error: Throwable) {
            lastError = error
            Thread.sleep(100)
        }
    }
    throw IllegalStateException("Driver did not become ready", lastError)
}
