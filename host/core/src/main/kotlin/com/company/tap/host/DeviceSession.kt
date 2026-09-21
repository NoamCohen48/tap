package com.company.tap.host

import com.company.tap.protocol.Health
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    /** Starts the instrumentation child; tests substitute a [FakeProcess] for deterministic
     * startup-cancellation tests without a device. */
    val processStarter: ProcessStarter = DefaultProcessStarter,
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
) {
    /** Test-only ownership-transfer gate. Production leaves this as the no-op default. */
    internal var beforeOwnershipTransfer: suspend () -> Unit = {}

    /** Test-only observer proving cancellation cleanup completed and its scope was stopped. */
    internal var afterCancellationCleanup: () -> Unit = {}
}

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
) {
    val serial: String get() = config.serial
    val generation: Long get() = journal.generation
    val sessionId: String get() = journal.sessionId
    val adb: Adb get() = config.adb

    private val apps = ConcurrentHashMap<String, AppLifecycle>()

    private val reapUncertain = AtomicReference<AdbReapUncertainException?>()

    /** Records sticky reap uncertainty; further session/app use is rejected, close quarantines. */
    fun noteReapUncertain(error: AdbReapUncertainException) {
        reapUncertain.compareAndSet(null, error)
    }

    /** Rejects use after sticky reap uncertainty; close still runs and quarantines. */
    fun checkUsable() {
        reapUncertain.get()?.let {
            throw IllegalStateException("Session on $serial is quarantined: ${it.message}", it)
        }
    }

    /** Runs an ADB block, poisoning this session sticky on reap uncertainty. */
    suspend fun <T> guardAdb(block: suspend () -> T): T {
        checkUsable()
        try {
            return block()
        } catch (error: AdbReapUncertainException) {
            noteReapUncertain(error)
            throw error
        }
    }

    /**
     * The [AppLifecycle] of [packageName] on this session, one instance per package for the
     * session's lifetime: it carries the sync identity handed out at bootstrap, which must
     * survive across calls for `awaitIdle` to stay guarded against a restarted process.
     */
    fun app(packageName: String = config.autPackage): AppLifecycle {
        checkUsable()
        return apps.computeIfAbsent(packageName) { AppLifecycle(this, it) }
    }

    private val closeStarted = AtomicBoolean(false)
    private val cancellationCleanupScheduled = AtomicBoolean(false)
    private val cancellationCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Releases in reverse — client, exact forward, instrumentation — then finalizes the journal
     * and releases the lease. The whole cleanup runs NonCancellable with an explicit
     * [timeoutMs]: journal finalization and lease release always run, and on timeout or
     * uncertainty the journal records quarantine. Throws the first cleanup failure (or a timeout
     * describing the quarantine); the journal detail is what the service reports.
     */
    suspend fun close(timeoutMs: Long = DEVICE_SESSION_CLOSE_TIMEOUT_MS) {
        withContext(NonCancellable) {
            if (!closeStarted.compareAndSet(false, true)) return@withContext
            var firstFailure: Throwable? = null
            val finished =
                withTimeoutOrNull(timeoutMs) {
                    // cleanupStep, not runCatching: a bound firing must reach withTimeoutOrNull
                    // as cancellation (finished == null below), never as a recorded failure.
                    cleanupStep({ firstFailure = it }) { client.close() }
                    cleanupStep({ error -> firstFailure = firstFailure ?: error }) {
                        adb.removeForward(serial, hostPort)
                    }
                    cleanupStep({ error -> firstFailure = firstFailure ?: error }) {
                        cleanupInstrumentation(adb, serial, running)
                    }
                    true
                }
            if (finished == null) {
                firstFailure =
                    firstFailure ?: IllegalStateException(
                        "Session cleanup on $serial exceeded ${timeoutMs}ms; device quarantined",
                    )
            }
            val poison = reapUncertain.get()
            val quarantined = firstFailure != null || poison != null
            try {
                store.write(
                    journal.copy(
                        state = if (!quarantined) JournalState.CLOSED else JournalState.QUARANTINED,
                        quarantineReason =
                            (firstFailure ?: poison)?.let { "SESSION_CLEANUP_UNCERTAIN: ${it.message}" },
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            } finally {
                lease.close()
                cancellationCleanupScope.cancel()
            }
            firstFailure?.let { throw it }
        }
    }

    /**
     * Called synchronously by the cancellable return handoff. Scheduling is synchronous and
     * exactly once through [cancellationCleanupScheduled]; cleanup itself stays suspend, bounded, NonCancellable,
     * and owned by this session rather than GlobalScope. Caller cancellation remains prompt and
     * may complete before journal/lease finalization; the cleanup job self-closes its scope.
     */
    private fun scheduleCancellationCleanup() {
        if (!cancellationCleanupScheduled.compareAndSet(false, true)) return
        cancellationCleanupScope.launch {
            try {
                close(DEVICE_OPEN_CLEANUP_TIMEOUT_MS)
            } finally {
                cancellationCleanupScope.cancel()
                config.afterCancellationCleanup()
            }
        }
    }

    companion object {
        suspend fun open(config: DeviceSessionConfig): DeviceSession {
            val adb = config.adb
            val serial = config.serial
            val store = SessionJournalStore(config.journalRoot, serial)
            val lease = store.acquireLease(config.leaseTimeoutMs)
            var leaseOwnedBySession = false
            try {
                val bootId = adb.bootId(serial)
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
                var journal =
                    SessionJournal(
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
                var session: DeviceSession? = null
                var transferAttempted = false
                try {
                    running =
                        startDriverWithRetry(
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
                            processStarter = config.processStarter,
                        ) { devicePort ->
                            journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
                            store.write(journal)
                        }
                    hostPort = adb.forward(serial, running.devicePort)
                    val driverPid = adb.processIds(serial, DRIVER_PACKAGE).single()
                    journal =
                        journal.copy(
                            state = JournalState.ACTIVE,
                            hostPort = hostPort,
                            driverPid = driverPid,
                            driverStartToken = processStartToken(adb, serial, driverPid),
                            driverInstanceId = running.driverInstanceId,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    store.write(journal)

                    client =
                        connectWithRetry(
                            hostPort,
                            sessionId,
                            generation,
                            secret,
                            serial = serial,
                            heartbeatIntervalMs = config.heartbeatIntervalMs,
                        )
                    client.execute(Health)
                    journal = journal.copy(state = JournalState.READY, updatedAtEpochMs = System.currentTimeMillis())
                    store.write(journal)
                    session = DeviceSession(config, lease, store, journal, running, hostPort, client)
                    leaseOwnedBySession = true
                    // Cancellation may arrive after READY but before the caller accepts the
                    // returned value. Keep the deterministic seam non-cancellable, then use a
                    // cancellable continuation as the ownership-transfer point: cancellation
                    // that wins that race throws into this catch and cleans the still-owned
                    // session; successful resumption transfers cleanup responsibility.
                    withContext(NonCancellable) { config.beforeOwnershipTransfer() }
                    transferAttempted = true
                    return suspendCancellableCoroutine { continuation ->
                        continuation.resume(session) { _, unaccepted, _ ->
                            unaccepted.scheduleCancellationCleanup()
                        }
                    }
                } catch (error: Throwable) {
                    if (session != null) {
                        if (!transferAttempted) {
                            try {
                                session.close(DEVICE_OPEN_CLEANUP_TIMEOUT_MS)
                            } catch (cleanup: Throwable) {
                                error.addSuppressed(cleanup)
                            }
                        }
                        // Once transfer was attempted, resume(onCancellation) synchronously
                        // scheduled cleanup before this cancellation reached the catch.
                        throw error
                    }
                    // Failure cleanup runs bounded NonCancellable: journal finalization and lease
                    // release below always run, even when the failure is a coroutine cancellation.
                    // The primary failure is preserved; cleanup failures are suppressed into it.
                    withContext(NonCancellable) {
                        var cleanupFailure: Throwable? = null
                        val finished =
                            withTimeoutOrNull(DEVICE_OPEN_CLEANUP_TIMEOUT_MS) {
                                // cleanupStep, not runCatching: a bound firing must reach
                                // withTimeoutOrNull as cancellation, never as a recorded failure.
                                cleanupStep({ cleanupFailure = it }) { client?.close() }
                                hostPort?.let { port ->
                                    cleanupStep({ failure -> cleanupFailure = cleanupFailure ?: failure }) {
                                        adb.removeForward(serial, port)
                                    }
                                }
                                running?.let {
                                    cleanupStep({ failure -> cleanupFailure = cleanupFailure ?: failure }) {
                                        cleanupInstrumentation(adb, serial, it)
                                    }
                                }
                                true
                            }
                        if (finished == null) {
                            cleanupFailure =
                                cleanupFailure ?: IllegalStateException(
                                    "Session open cleanup on $serial exceeded ${DEVICE_OPEN_CLEANUP_TIMEOUT_MS}ms",
                                )
                        }
                        val startupUncertain = findReapUncertain(error)
                        val quarantined = cleanupFailure != null || startupUncertain != null
                        try {
                            store.write(
                                journal.copy(
                                    state =
                                        if (!quarantined) JournalState.CLOSED else JournalState.QUARANTINED,
                                    quarantineReason =
                                        (cleanupFailure ?: startupUncertain)?.let { "SESSION_START_CLEANUP_UNCERTAIN: ${it.message}" },
                                    updatedAtEpochMs = System.currentTimeMillis(),
                                ),
                            )
                        } finally {
                            lease.close()
                        }
                        cleanupFailure?.let { error.addSuppressed(it) }
                    }
                    throw error
                }
            } catch (error: Throwable) {
                // A constructed session owns the lease; either close() finalized it or the
                // cancellable return handoff scheduled the session-owned cleanup job.
                if (!leaseOwnedBySession) lease.close()
                throw error
            }
        }
    }
}

/** Bound for `DeviceSession.close` cleanup; journal finalization and lease release always run. */
const val DEVICE_SESSION_CLOSE_TIMEOUT_MS = 60_000L

/** Bound for `DeviceSession.open` failure cleanup under an already-cancelled caller. */
const val DEVICE_OPEN_CLEANUP_TIMEOUT_MS = 60_000L

/** Walks the cause/suppressed chain for sticky reap uncertainty. */
internal fun findReapUncertain(error: Throwable): AdbReapUncertainException? {
    val seen = HashSet<Throwable>()
    val queue = ArrayDeque<Throwable>()
    queue.add(error)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!seen.add(current)) continue
        if (current is AdbReapUncertainException) return current
        current.cause?.let(queue::add)
        current.suppressed.forEach(queue::add)
    }
    return null
}
