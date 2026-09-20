package com.company.tap.host

import com.company.tap.protocol.Operation
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Everything needed to bring up one driver session on one device. */
data class DeviceSessionConfig(
    val serial: String,
    /** The application under test; AUT-scoped selectors resolve inside this package. */
    val autPackage: String,
    /** Driver APKs to install before starting; null skips installation (already installed). */
    val driverApk: Path? = null,
    val driverTestApk: Path? = null,
    val syncAuthority: String = "$autPackage.tap-sync",
    val allowedSystemPackages: Set<String> = setOf(PERMISSION_CONTROLLER_PACKAGE),
    val journalRoot: Path = Path.of(System.getProperty("user.home"), ".tap", "sessions"),
    val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    /** Extra instrumentation arguments (fault points, heartbeat overrides). */
    val driverArguments: Map<String, String> = emptyMap(),
    val adb: Adb = Adb(),
    /** Receives the driver's instrumentation output line by line. */
    val driverLog: (String) -> Unit = {},
    /** How long to wait for another session's lock on this serial before giving up (0 = fail at once). */
    val leaseTimeoutMs: Long = 0,
    /**
     * Decides, once the per-serial lock is held, whether [driverApk]/[driverTestApk] are installed
     * for this open. Callers that install "once per device" must decide here, not before the lock:
     * two opens racing for the same serial could otherwise leave the lock winner instrumenting a
     * driver the loser was still going to install.
     */
    val installDriver: () -> Boolean = { true },
)

/**
 * One disposable session unit: device lease, journal, driver instrumentation, ADB forward,
 * authenticated [client], and session generation. [open] either returns a `READY` session or
 * cleans up everything it created; [close] releases in reverse and journals `CLOSED`, or
 * `QUARANTINED` when cleanup could not be proven. A lost or poisoned client is not repaired
 * in place: close this session and open a new one (the generation advances).
 */
class DeviceSession private constructor(
    val config: DeviceSessionConfig,
    private val lease: AutoCloseable,
    private val store: SessionJournalStore,
    private var journal: SessionJournal,
    private val running: RunningInstrumentation,
    private val hostPort: Int,
    val client: DriverClient,
) : AutoCloseable {
    val serial: String get() = config.serial
    val generation: Long get() = journal.generation
    val sessionId: String get() = journal.sessionId
    val adb: Adb get() = config.adb

    private val apps = ConcurrentHashMap<String, AppLifecycle>()

    /**
     * The [AppLifecycle] of [packageName] on this session, one instance per package for the
     * session's lifetime: it carries the sync identity handed out at bootstrap, which must
     * survive across calls for `awaitIdle` to stay guarded against a restarted process.
     */
    fun app(packageName: String = config.autPackage): AppLifecycle =
        apps.computeIfAbsent(packageName) { AppLifecycle(this, it) }

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        var cleanupSuccessful = true
        runCatching { client.close() }.onFailure { cleanupSuccessful = false }
        runCatching { adb.removeForward(serial, hostPort) }.onFailure { cleanupSuccessful = false }
        runCatching { cleanupInstrumentation(adb, serial, running) }.onFailure { cleanupSuccessful = false }
        try {
            store.write(
                journal.copy(
                    state = if (cleanupSuccessful) JournalState.CLOSED else JournalState.QUARANTINED,
                    quarantineReason = if (cleanupSuccessful) null else "SESSION_CLEANUP_UNCERTAIN",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
        } finally {
            lease.close()
        }
        check(cleanupSuccessful) { "Session cleanup on $serial was uncertain; device quarantined" }
    }

    companion object {
        fun open(config: DeviceSessionConfig): DeviceSession {
            val adb = config.adb
            val serial = config.serial
            val store = SessionJournalStore(config.journalRoot, serial)
            val lease = store.acquireLease(config.leaseTimeoutMs)
            try {
                val bootId = adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")
                val prior = recoverJournal(adb, serial, bootId, store)
                adb.wakeAndDismissKeyguard(serial)
                if ((config.driverApk != null || config.driverTestApk != null) && config.installDriver()) {
                    config.driverApk?.let { adb.install(serial, it) }
                    config.driverTestApk?.let { adb.install(serial, it) }
                }

                val generation = Math.addExact(prior?.generation ?: 0L, 1L)
                val sessionId = UUID.randomUUID().toString()
                val secret = ByteArray(32).also(SecureRandom()::nextBytes)
                val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
                var journal = SessionJournal(
                    state = JournalState.CREATING,
                    serial = serial,
                    bootId = bootId,
                    sessionId = sessionId,
                    generation = generation,
                    devicePort = DEVICE_PORT,
                )
                store.write(journal)

                var running: RunningInstrumentation? = null
                var hostPort: Int? = null
                var client: DriverClient? = null
                try {
                    running = startDriverWithRetry(
                        adb = adb,
                        serial = serial,
                        sessionId = sessionId,
                        generation = generation,
                        encodedSecret = encodedSecret,
                        autPackage = config.autPackage,
                        syncAuthority = config.syncAuthority,
                        allowedSystemPackages = config.allowedSystemPackages,
                        driverArguments = config.driverArguments,
                        logSink = config.driverLog,
                    ) { devicePort ->
                        journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
                        store.write(journal)
                    }
                    hostPort = adb.forward(serial, running.devicePort)
                    val driverPid = adb.processIds(serial, DRIVER_PACKAGE).single()
                    journal = journal.copy(
                        state = JournalState.ACTIVE,
                        hostPort = hostPort,
                        driverPid = driverPid,
                        driverStartToken = processStartToken(adb, serial, driverPid),
                        driverInstanceId = running.driverInstanceId,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    store.write(journal)

                    client = connectWithRetry(
                        hostPort, sessionId, generation, secret,
                        serial = serial, heartbeatIntervalMs = config.heartbeatIntervalMs,
                    )
                    val health = client.execute(Operation.HEALTH)
                    check(health.ok) { "Driver health check failed on $serial: $health" }
                    journal = journal.copy(state = JournalState.READY, updatedAtEpochMs = System.currentTimeMillis())
                    store.write(journal)
                    return DeviceSession(config, lease, store, journal, running, hostPort, client)
                } catch (error: Throwable) {
                    var cleanupSuccessful = true
                    runCatching { client?.close() }
                    hostPort?.let { port -> runCatching { adb.removeForward(serial, port) }.onFailure { cleanupSuccessful = false } }
                    running?.let { runCatching { cleanupInstrumentation(adb, serial, it) }.onFailure { cleanupSuccessful = false } }
                    store.write(
                        journal.copy(
                            state = if (cleanupSuccessful) JournalState.CLOSED else JournalState.QUARANTINED,
                            quarantineReason = if (cleanupSuccessful) null else "SESSION_START_CLEANUP_UNCERTAIN",
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    )
                    throw error
                }
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        }
    }
}
