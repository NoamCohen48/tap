package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.DEFAULT_HEARTBEAT_INTERVAL_MS
import io.github.noamcohen48.tap.host.DEVICE_PORT_RANGE
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.host.RunningInstrumentation
import io.github.noamcohen48.tap.host.SessionJournal
import io.github.noamcohen48.tap.host.SessionJournalStore
import io.github.noamcohen48.tap.host.cleanupInstrumentation
import io.github.noamcohen48.tap.host.connectWithRetry
import io.github.noamcohen48.tap.host.forceStopDriverAndVerify
import io.github.noamcohen48.tap.host.isPortListening
import io.github.noamcohen48.tap.host.processStartToken
import io.github.noamcohen48.tap.host.recoverJournal
import io.github.noamcohen48.tap.host.removeExactForward
import io.github.noamcohen48.tap.host.startDriverWithRetry
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal const val FIXTURE_PACKAGE = "io.github.noamcohen48.tap.fixture"
internal const val PERMISSION_RESOURCE_PACKAGE = "com.android.permissioncontroller"
internal const val SYNC_AUTHORITY = "$FIXTURE_PACKAGE.tap-sync"
internal const val FAULT_AUTHORITY = "$FIXTURE_PACKAGE.fault"

/** How long a test waits for another holder of a device's journal lock. */
private const val LEASE_TIMEOUT_MS = 60_000L

/** The suite's inputs, from the system properties the `deviceTest` task sets. */
object Devices {
    /** `-Ptap.serials=a,b`; empty skips every device test. */
    val configured: List<String> =
        System
            .getProperty("tap.serials")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    @JvmStatic
    fun serials(): List<String> = configured

    val driverApk: Path get() = apk("tap.driverApk")
    val driverTestApk: Path get() = apk("tap.driverTestApk")
    val fixtureApk: Path get() = apk("tap.fixtureApk")

    /** `TAP_STATE_DIR`, default `~/.tap`, exactly like the daemon. */
    val stateDir: Path =
        System.getenv("TAP_STATE_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".tap")

    val journalRoot: Path get() = stateDir.resolve("sessions")

    /** `-Ptap.reboot=true`; the only way the rebooting scenario runs. */
    val allowReboot: Boolean get() = System.getProperty("tap.allowReboot").toBoolean()

    private fun apk(property: String): Path {
        val value = System.getProperty(property)
        check(!value.isNullOrBlank()) { "System property $property is not set" }
        val path = Path.of(value)
        check(Files.isRegularFile(path)) { "$property points at a missing APK: $path (build it or pass -P$property=...)" }
        return path
    }
}

/** Skips (never fails) a device test class when no serial is configured. */
class DevicesConfigured : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (Devices.configured.isEmpty()) {
            ConditionEvaluationResult.disabled("No devices configured: pass -Ptap.serials=SERIAL[,SERIAL...]")
        } else {
            ConditionEvaluationResult.enabled("Devices: ${Devices.configured}")
        }
}

/** A device validation class: tagged `device`, skipped without `-Ptap.serials`. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DevicesConfigured::class)
@Tag("device")
annotation class DeviceTest

/** Runs the test once per configured serial, which it receives as its `String` parameter. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ParameterizedTest(name = "[{0}]")
@MethodSource("io.github.noamcohen48.tap.host.validation.Devices#serials")
annotation class OnEachDevice

/** Installs the fixture and driver APKs once per serial per JVM, not once per test. */
private object Installer {
    private val installed = ConcurrentHashMap.newKeySet<String>()

    suspend fun ensure(
        adb: Adb,
        serial: String,
    ) {
        if (serial in installed) return
        adb.install(serial, Devices.fixtureApk)
        adb.install(serial, Devices.driverApk)
        adb.install(serial, Devices.driverTestApk)
        installed += serial
    }
}

/** Runs [block] against a freshly leased, recovered and provisioned [serial]; always releases it. */
fun deviceTest(
    serial: String,
    allowResetRecovery: Boolean = false,
    block: suspend (DeviceHarness) -> Unit,
) = runBlocking(Dispatchers.IO) {
    val device = DeviceHarness.open(serial, allowResetRecovery)
    try {
        block(device)
    } finally {
        device.close()
    }
}

/** A scenario-visible timing or measurement line in the test output. */
fun report(
    scenario: String,
    serial: String,
    vararg values: Pair<String, Any>,
) {
    println("TAP_VALIDATION $scenario serial=$serial " + values.joinToString(" ") { (key, value) -> "$key=$value" })
}

/**
 * One test's hold on a device: the per-serial journal lease (so no daemon or other suite touches
 * it meanwhile), a recovered journal, and the installed APKs. Each driver session a test starts
 * takes the next generation after whatever the journal last recorded, so tests never depend on
 * one another's outcome.
 */
class DeviceHarness private constructor(
    val serial: String,
    val adb: Adb,
    val store: SessionJournalStore,
    private var lease: AutoCloseable,
    /** The journal [recoverJournal] returned when the test started. */
    val priorJournal: SessionJournal?,
    val bootId: String,
    val apiLevel: Int,
) {
    companion object {
        suspend fun open(
            serial: String,
            allowResetRecovery: Boolean = false,
        ): DeviceHarness {
            val adb = Adb()
            val store = SessionJournalStore(Devices.journalRoot, serial)
            val lease = store.acquireLease(LEASE_TIMEOUT_MS)
            try {
                val bootId = adb.bootId(serial)
                val prior = recoverJournal(adb, serial, bootId, store, allowResetRecovery)
                val apiLevel = adb.run(serial, "shell", "getprop", "ro.build.version.sdk").toInt()
                Installer.ensure(adb, serial)
                adb.forceStop(serial, FIXTURE_PACKAGE)
                return DeviceHarness(serial, adb, store, lease, prior, bootId, apiLevel)
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        }
    }

    fun close() = lease.close()

    /** Releases and retakes the lease, as a new host process would between sessions. */
    suspend fun reacquireLease() {
        lease.close()
        lease = store.acquireLease(LEASE_TIMEOUT_MS)
    }

    fun nextGeneration(): Long = Math.addExact(store.read()?.generation ?: 0L, 1L)

    suspend fun shell(
        vararg arguments: String,
        timeoutMs: Long = 30_000,
    ): String = adb.run(serial, "shell", *arguments, timeoutMs = timeoutMs)

    suspend fun wakeAndDismissKeyguard() {
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
    }

    /** `am start -W` of a fixture activity. */
    suspend fun launchFixture(
        activity: String,
        vararg extras: String,
    ) {
        shell("am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.$activity", *extras, timeoutMs = 60_000)
    }

    suspend fun forceStopFixture() {
        shell("am", "force-stop", FIXTURE_PACKAGE)
    }

    /**
     * Starts a driver session under the next generation with [faultPoint], journaling
     * `CREATING` → `ACTIVE` → (after connect and health) `READY`. With [connect] false the
     * driver is left listening for [FaultSession.connect]. A failed start cleans up and journals
     * `QUARANTINED`.
     */
    suspend fun startSession(
        faultPoint: TransportFaultPoint = TransportFaultPoint.NONE,
        driverArguments: Map<String, String> = emptyMap(),
        hostHeartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        deadlineNanos: Long = System.nanoTime() + 120_000_000_000L,
        connect: Boolean = true,
        generation: Long = nextGeneration(),
    ): FaultSession {
        check(System.nanoTime() < deadlineNanos) { "Driver session started after its deadline" }
        val sessionId = UUID.randomUUID().toString()
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        var journal =
            SessionJournal(
                state = JournalState.CREATING,
                serial = serial,
                bootId = bootId,
                sessionId = sessionId,
                generation = generation,
                devicePort = DEVICE_PORT_RANGE.first,
            )
        var running: RunningInstrumentation? = null
        var hostPort: Int? = null
        try {
            running =
                startDriverWithRetry(
                    adb,
                    serial,
                    sessionId,
                    generation,
                    encodedSecret,
                    autPackage = FIXTURE_PACKAGE,
                    syncAuthority = SYNC_AUTHORITY,
                    overallDeadlineNanos = deadlineNanos,
                    driverArguments = fixtureDriverArguments(faultPoint) + driverArguments,
                    logSink = {},
                ) { devicePort ->
                    journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
                    store.write(journal)
                }
            check(System.nanoTime() < deadlineNanos) { "Driver startup exceeded its deadline" }
            hostPort = adb.forward(serial, running.devicePort)
            journal = journal.copy(hostPort = hostPort, updatedAtEpochMs = System.currentTimeMillis())
            store.write(journal)
            val driverPid = waitForDriverPid()
            journal =
                journal.copy(
                    state = JournalState.ACTIVE,
                    driverPid = driverPid,
                    driverStartToken = processStartToken(adb, serial, driverPid),
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            store.write(journal)
            val session = FaultSession(this, journal, running, hostPort, secret, deadlineNanos, hostHeartbeatIntervalMs)
            if (connect) session.connect()
            return session
        } catch (error: Throwable) {
            if (hostPort != null && running != null) {
                runCatching { removeExactForward(adb, serial, hostPort, running.devicePort) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            running?.let {
                runCatching { cleanupInstrumentation(adb, serial, it) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            runCatching { store.write(journal.copy(state = JournalState.QUARANTINED)) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /** [startSession], run [block], then [FaultSession.cleanup] — which must prove a clean end
     * when [block] succeeded, and is best effort (suppressed) when it failed. */
    suspend fun <T> withSession(
        faultPoint: TransportFaultPoint = TransportFaultPoint.NONE,
        driverArguments: Map<String, String> = emptyMap(),
        hostHeartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        connect: Boolean = true,
        block: suspend (FaultSession) -> T,
    ): T {
        val session =
            startSession(
                faultPoint,
                driverArguments,
                hostHeartbeatIntervalMs,
                connect = connect,
            )
        val result =
            try {
                block(session)
            } catch (error: Throwable) {
                println("Driver output on $serial before the failure:\n${session.outputTail()}")
                runCatching { session.cleanup() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        session.cleanup()
        return result
    }

    suspend fun waitForDriverPid(): Int {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val pids = adb.processIds(serial, DRIVER_PACKAGE)
            if (pids.size == 1) return pids.single()
            check(pids.size <= 1) { "Multiple driver processes found: $pids" }
            delay(50)
        }
        error("Driver PID did not appear")
    }

    /** Waits until [packageName] has had no process for three consecutive samples. */
    suspend fun awaitProcessAbsent(packageName: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var consecutiveAbsentSamples = 0
        while (System.nanoTime() < deadline) {
            if (adb.processIds(serial, packageName).isEmpty()) {
                consecutiveAbsentSamples += 1
                if (consecutiveAbsentSamples == 3) return
            } else {
                consecutiveAbsentSamples = 0
            }
            delay(250)
        }
        error("$packageName process remained present after force-stop")
    }

    suspend fun waitForPortState(
        port: Int,
        listening: Boolean,
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (isPortListening(adb, serial, port) == listening) return
            delay(50)
        }
        error("Device port $port did not become ${if (listening) "occupied" else "free"}")
    }
}

/**
 * A driver session started by the harness from its raw parts (instrumentation, forward,
 * journal, client) rather than `DeviceSession`, so a scenario can inject faults and assert on
 * every journal state and on the driver's own output.
 */
class FaultSession internal constructor(
    private val device: DeviceHarness,
    var journal: SessionJournal,
    val running: RunningInstrumentation,
    val hostPort: Int,
    val secret: ByteArray,
    private val deadlineNanos: Long,
    private val hostHeartbeatIntervalMs: Long,
) {
    private var connected: DriverClient? = null
    private var cleanedUp = false

    val client: DriverClient get() = checkNotNull(connected) { "Session is not connected" }
    val sessionId: String get() = journal.sessionId
    val generation: Long get() = journal.generation

    /** Authenticates, proves the driver instance and health, and journals `READY`. */
    suspend fun connect(): DriverClient {
        check(connected == null) { "Session is already connected" }
        val client =
            connectWithRetry(hostPort, sessionId, generation, secret, deadlineNanos, device.serial, hostHeartbeatIntervalMs)
        connected = client
        check(client.driverInstanceId == running.driverInstanceId) {
            "Authenticated driver instance did not match its readiness signal"
        }
        val health = client.send(Requests.health())
        check(health.ok) { "Driver health failed: $health" }
        journal =
            journal.copy(
                state = JournalState.READY,
                driverInstanceId = client.driverInstanceId,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        device.store.write(journal)
        return client
    }

    fun writeJournal(state: JournalState) {
        journal = journal.copy(state = state, updatedAtEpochMs = System.currentTimeMillis())
        device.store.write(journal)
    }

    fun output(): String = synchronized(running.output) { running.output.toString() }

    fun outputTail(maxChars: Int = 4_000): String = output().takeLast(maxChars)

    /** Waits for [marker] in the driver's instrumentation output. */
    suspend fun awaitMarker(
        marker: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (marker in output()) return true
            if (!running.process.isAlive && !running.outputDrain.isCompleted) break
            delay(25)
        }
        return marker in output()
    }

    /**
     * Closes the client, removes the exact forward, proves the driver's identity gone (forcing
     * it when the instrumentation does not exit by itself), drains the output, re-checks the
     * boot identity, and journals [terminalState] — or `QUARANTINED` and throws when any of that
     * could not be proven. Idempotent.
     */
    suspend fun cleanup(
        terminalState: JournalState = JournalState.CLOSED,
        expectForceStop: Boolean = false,
    ): SessionJournal {
        if (cleanedUp) return journal
        cleanedUp = true
        val adb = device.adb
        val serial = device.serial
        var cleanupError: Throwable? = null

        suspend fun capture(block: suspend () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                if (cleanupError == null) cleanupError = error else cleanupError?.addSuppressed(error)
            }
        }

        connected?.let { client -> capture { client.close() } }
        capture { removeExactForward(adb, serial, hostPort, running.devicePort) }
        var forceStopRequired = false
        if (!running.process.waitFor(3, TimeUnit.SECONDS)) {
            forceStopRequired = true
            capture { forceStopDriverAndVerify(adb, serial, journal.driverPid, journal.driverStartToken) }
            if (running.process.isAlive) running.process.destroyForcibly()
            if (!running.process.waitFor(3, TimeUnit.SECONDS)) {
                capture { error("Instrumentation child survived cleanup") }
            }
        }
        if (expectForceStop && !forceStopRequired) {
            capture { error("Instrumentation exited without forced termination") }
        }
        capture { forceStopDriverAndVerify(adb, serial, journal.driverPid, journal.driverStartToken) }
        withTimeoutOrNull(1_000) { running.outputDrain.join() }
        if (!running.outputDrain.isCompleted) {
            runCatching { running.process.inputStream.close() }
            withTimeoutOrNull(1_000) { running.outputDrain.join() }
        }
        if (!running.outputDrain.isCompleted) {
            capture { error("Instrumentation output drain survived cleanup") }
        }
        capture {
            check(adb.bootId(serial) == device.bootId) { "Boot identity changed during session cleanup" }
        }
        journal =
            journal.copy(
                state = if (cleanupError == null) terminalState else JournalState.QUARANTINED,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        device.store.write(journal)
        cleanupError?.let { throw IllegalStateException("Session cleanup on $serial was uncertain", it) }
        return journal
    }
}

/** Driver fault points the validation-flavor driver implements (`tapFaultPoint`). */
enum class TransportFaultPoint {
    NONE,
    BEFORE_ACCEPTANCE,
    AFTER_ACCEPTANCE,
    AFTER_MUTATION,
    LATE_UNINTERRUPTIBLE,
    CANCEL_AFTER_MUTATION,
}

/** Fixture-only instrumentation arguments: fault point and the fixture's fault provider. */
private fun fixtureDriverArguments(faultPoint: TransportFaultPoint) =
    mapOf(
        "tapFaultPoint" to faultPoint.name,
        "tapFaultAuthority" to FAULT_AUTHORITY,
    )

/**
 * Whether host validation refuses [command] as `INVALID_REQUEST` before transmitting it: the
 * builders accept any value, so an out-of-range argument is caught by the shared validation
 * (the driver repeats it) rather than by construction.
 */
suspend fun rejectedByHost(
    client: DriverClient,
    command: Command,
): Boolean {
    val error = runCatching { client.send(command) }.exceptionOrNull()
    return error is InvalidCommandException && error.code == ErrorCode.ERR_INVALID_REQUEST
}

/** The fixture's main screen, launched fresh and proven visible through [client]. */
suspend fun DeviceHarness.openFixtureMain(client: DriverClient) {
    wakeAndDismissKeyguard()
    launchFixture("MainActivity")
    val ready = client.send(Commands.waitVisible(FIXTURE_MAIN_READY), timeoutMs = 10_000)
    check(ready.ok) { "Fixture main screen did not appear: $ready" }
}

/** A Compose button on the fixture's main screen; visible once the screen is ready. */
val FIXTURE_MAIN_READY: Selector = Selectors.rawResource("composeButton")
