package com.company.tap.service

import com.company.tap.host.Adb
import com.company.tap.host.AppLifecycle
import com.company.tap.host.CommandTransportException
import com.company.tap.host.DEVICE_SESSION_CLOSE_TIMEOUT_MS
import com.company.tap.host.DeviceSession
import com.company.tap.host.DeviceSessionConfig
import com.company.tap.host.DriverClient
import com.company.tap.host.JournalState
import com.company.tap.host.PERMISSION_CONTROLLER_PACKAGE
import com.company.tap.host.SessionJournalStore
import com.company.tap.protocol.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.UUID

class ServiceConfig(
    val adb: Adb,
    val stateDir: Path,
    val journalRoot: Path = stateDir.resolve("sessions"),
    /** Restrict the service to these serials; null = every device ADB lists. */
    val bundledDriver: BundledDriver?,
    val log: (String) -> Unit = ::println,
)

class UnknownConnectionException(
    id: String,
) : NoSuchElementException("Unknown connection $id")

class UnknownSessionException(
    id: String,
) : NoSuchElementException("Unknown session $id")

/** A second Attach stream for a connection that already has one. Maps to FAILED_PRECONDITION. */
class DuplicateAttachException(
    id: String,
) : IllegalStateException("Connection $id already has an Attach stream")

/** A new connection attempted after service shutdown began. Maps to FAILED_PRECONDITION. */
class ServiceClosingException : IllegalStateException("Service is shutting down")

sealed class DeviceStatus {
    object Free : DeviceStatus()

    /** Locked by a live session: [connectionId] when it is one of ours, null for another process. */
    data class Held(
        val connectionId: String?,
    ) : DeviceStatus()

    data class Quarantined(
        val reason: String,
    ) : DeviceStatus()
}

/** One row of [TapService.devices]: a serial ADB lists and what the lock and journal say about it. */
data class DeviceEntry(
    val serial: String,
    val status: DeviceStatus,
)

/**
 * One client process talking to the service. Every session it opens belongs to it; when the
 * client detaches (its Attach stream drops) or calls Close, all of them are closed.
 *
 * All mutable state is guarded by the owning [TapService]'s lifecycle lock; transitions are
 * short non-suspending synchronized blocks, never held across suspension.
 */
class Connection internal constructor(
    val id: String,
    val name: String,
) {
    internal val sessions = HashMap<String, Session>()
    internal var closed = false

    /** Invoked once when the connection closes, so an Attach stream can complete. */
    internal val onClose = HashSet<() -> Unit>()

    /** The single Attach stream owner; non-null while a stream is registered. */
    internal var attachOwner: Any? = null
}

/**
 * The service's view of one open driver session: lifecycle metadata plus how to close it.
 * Production wraps `:host:core` [DeviceSession]; tests substitute a fake through [DeviceOpener].
 */
internal interface ServiceDevice {
    val serial: String
    val generation: Long
    val autPackage: String
    val client: DriverClient

    fun app(packageName: String): AppLifecycle

    /** Rejects use after sticky reap quarantine; cleanup still runs via [close]. */
    fun checkUsable()

    suspend fun close(timeoutMs: Long)
}

/** Opens one [ServiceDevice]; production delegates to [DeviceSession.open]. */
internal interface DeviceOpener {
    suspend fun open(config: DeviceSessionConfig): ServiceDevice
}

private object RealDeviceOpener : DeviceOpener {
    override suspend fun open(config: DeviceSessionConfig): ServiceDevice = CoreDevice(DeviceSession.open(config))
}

/** Production [ServiceDevice]: a live `:host:core` session. */
private class CoreDevice(
    val delegate: DeviceSession,
) : ServiceDevice {
    override val serial: String get() = delegate.serial
    override val generation: Long get() = delegate.generation
    override val autPackage: String get() = delegate.autPackage
    override val client: DriverClient get() = delegate.client

    override fun app(packageName: String): AppLifecycle = delegate.app(packageName)

    override fun checkUsable() = delegate.checkUsable()

    override suspend fun close(timeoutMs: Long) = delegate.close(timeoutMs)
}

/**
 * One open driver session as the service sees it: the [ServiceDevice] (lock, journal, driver,
 * forward, authenticated client, per-package app) plus what the service adds — the owning
 * [connection], the default command timeout and the captured driver log.
 */
class Session internal constructor(
    val id: String,
    val connection: Connection,
    internal val device: ServiceDevice,
    val defaultTimeoutMs: Long,
    val log: DriverLogBuffer,
)

/** The last [capacity] lines of a session's driver output, kept for failure artifacts. */
class DriverLogBuffer(
    private val capacity: Int = 2_000,
) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}

/** Total bound for [TapService.close]: every connection is still attempted within it. */
const val SERVICE_SHUTDOWN_TOTAL_MS = 30_000L

/** Per-session cap inside [TapService.close]; the remainder of the total goes to later sessions. */
const val SERVICE_SHUTDOWN_SESSION_MS = 10_000L

/**
 * All service state: connections (client ownership) and live sessions. There is no lease table
 * here: exclusive use of a device is the per-serial file lock in `:host:core` (taken by
 * [DeviceSession.open], released when the session closes or its process dies), so the device
 * list is only a view — [devices] probes that lock and the journal. The gRPC servicers are thin
 * adapters over this class so it can be exercised without a server.
 *
 * One synchronized lifecycle lock guards closed state, the single Attach owner, close-callback
 * registration, and global/per-connection session registration and removal. Every synchronized block is short and non-suspending; suspension (device open/close,
 * ADB) always happens outside the lock.
 */
class TapService(
    val config: ServiceConfig,
) {
    internal constructor(
        config: ServiceConfig,
        opener: DeviceOpener,
        shutdownTotalMs: Long = SERVICE_SHUTDOWN_TOTAL_MS,
        shutdownSessionMs: Long = SERVICE_SHUTDOWN_SESSION_MS,
    ) : this(config) {
        this.opener = opener
        this.shutdownTotalMs = shutdownTotalMs
        this.shutdownSessionMs = shutdownSessionMs
    }

    private var opener: DeviceOpener = RealDeviceOpener
    private var shutdownTotalMs: Long = SERVICE_SHUTDOWN_TOTAL_MS
    private var shutdownSessionMs: Long = SERVICE_SHUTDOWN_SESSION_MS
    private val lifecycleLock = Any()
    private val connections = HashMap<String, Connection>()
    private val sessions = HashMap<String, Session>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closing = false

    /** Serials whose bundled driver this service process already installed. */
    private val driverInstalled = HashSet<String>()

    // ---- connections -------------------------------------------------------------------------

    fun openConnection(name: String): Connection {
        val connection = Connection(UUID.randomUUID().toString(), name)
        synchronized(lifecycleLock) {
            if (closing) throw ServiceClosingException()
            connections[connection.id] = connection
        }
        config.log("connection ${connection.id} opened ($name)")
        return connection
    }

    fun connection(id: String): Connection =
        synchronized(lifecycleLock) {
            connections[id]?.takeUnless { it.closed } ?: throw UnknownConnectionException(id)
        }

    /**
     * Atomically claims the single Attach stream for [id]. Unknown or closed → throws
     * [UnknownConnectionException] (NOT_FOUND); already attached → throws
     * [DuplicateAttachException] (FAILED_PRECONDITION). On success [closer] is registered so a
     * concurrent close cancels the stream promptly.
     */
    fun attachAcquire(
        id: String,
        token: Any,
        closer: () -> Unit,
    ): Connection =
        synchronized(lifecycleLock) {
            val connection = connections[id]?.takeUnless { it.closed } ?: throw UnknownConnectionException(id)
            if (connection.attachOwner != null) throw DuplicateAttachException(id)
            connection.attachOwner = token
            connection.onClose.add(closer)
            connection
        }

    /** Releases an Attach claim acquired by [attachAcquire]; a no-op for any other owner. */
    fun attachRelease(
        connection: Connection,
        token: Any,
        closer: () -> Unit,
    ) {
        synchronized(lifecycleLock) {
            if (connection.attachOwner === token) connection.attachOwner = null
            connection.onClose.remove(closer)
        }
    }

    /**
     * Closes every session of the connection. Idempotent. Returns the number of sessions owned
     * (closed or attempted), preserving the explicit-Close contract. Each session close is
     * bounded by [perSessionMs] inside NonCancellable so one stuck session cannot prevent the
     * others; failures and timeouts are logged with quarantine detail and counted, not thrown.
     */
    suspend fun closeConnection(
        id: String,
        reason: String,
        perSessionMs: Long = DEVICE_SESSION_CLOSE_TIMEOUT_MS,
    ): Int = closeConnectionWithin(id, reason, perSessionMs, totalTimeoutMs = null)

    /**
     * Internal seam for the connection-level exhaustion branch: production calls this via
     * [closeConnection] (no total deadline) and [close] (connection share of the shutdown
     * budget). Tests drive it directly with `totalTimeoutMs = 0` for an already-exhausted
     * connection-local deadline without relying on wall-clock expiry.
     */
    internal suspend fun closeConnectionWithin(
        id: String,
        reason: String,
        perSessionMs: Long,
        totalTimeoutMs: Long?,
    ): Int =
        withContext(NonCancellable) {
            val snapshot: Snapshot? =
                synchronized(lifecycleLock) {
                    val connection = connections.remove(id) ?: return@synchronized null
                    if (connection.closed) return@synchronized null
                    connection.closed = true
                    // One lifecycle transaction: every session leaves the global map and its
                    // connection map together, so no orphan window remains for a concurrent lookup.
                    val owned = connection.sessions.values.toList()
                    owned.forEach {
                        sessions.remove(it.id)
                        connection.sessions.remove(it.id)
                    }
                    val hooks = connection.onClose.toList()
                    connection.onClose.clear()
                    connection.attachOwner = null
                    Snapshot(connection, owned, hooks)
                }
            if (snapshot == null) return@withContext 0
            // End Attach promptly once close owns the state transition. Device cleanup may take
            // its full bound and must not keep a dead liveness stream heartbeating meanwhile.
            snapshot.hooks.forEach { runCatching(it) }
            val deadlineNanos = totalTimeoutMs?.let(::deadlineAfterMs)
            var closedSessions = 0
            snapshot.sessions.forEachIndexed { index, session ->
                // Strict shared bound: once the connection's share of the shutdown deadline is
                // exhausted, detach is already done (the snapshot removed every session from both
                // maps up front), so launch every remaining cleanup without awaiting any of them
                // and return immediately. Serially awaiting even 1 ms per remaining session would
                // overrun the deadline by N ms for N uncooperative sessions.
                if (deadlineNanos != null && remainingMs(deadlineNanos) <= 0L) {
                    val rest = snapshot.sessions.subList(index, snapshot.sessions.size)
                    rest.forEach { remaining ->
                        launchDetachedCleanup(remaining.device, shutdownSessionMs, "session ${remaining.id}")
                    }
                    config.log(
                        "connection $id shutdown budget exhausted; " +
                            "${rest.size} session(s) detached with cleanup launched",
                    )
                    return@withContext closedSessions + rest.size
                }
                val sessionsLeft = snapshot.sessions.size - index
                val timeoutMs =
                    deadlineNanos?.let { deadline ->
                        val remainingMs = remainingMs(deadline).coerceAtLeast(1L)
                        minOf(perSessionMs, (remainingMs / sessionsLeft).coerceAtLeast(1L))
                    } ?: perSessionMs
                val detail = closeDeviceBounded(session.device, timeoutMs, "session ${session.id}")
                if (detail != null) config.log("session ${session.id} close quarantined: $detail")
                config.log("session ${session.id} closed" + (detail?.let { " (quarantined: $it)" } ?: ""))
                closedSessions++
            }
            config.log("connection $id closed ($reason): sessions=$closedSessions")
            return@withContext closedSessions
        }

    private data class Snapshot(
        val connection: Connection,
        val sessions: List<Session>,
        val hooks: List<() -> Unit>,
    )

    /**
     * Returns null on clean cleanup, otherwise the quarantine detail (failure message or timeout).
     * The close runs in an independent service scope because the production [DeviceSession.close]
     * is deliberately NonCancellable. Merely wrapping it in `withTimeoutOrNull` would therefore
     * not bound this caller. On a service-side timeout the core close keeps running under its own
     * deadline so it can still journal quarantine and release the lease, while shutdown proceeds
     * to later sessions.
     */
    private suspend fun closeDeviceBounded(
        device: ServiceDevice,
        timeoutMs: Long,
        label: String,
    ): String? {
        val close = cleanupScope.async { runCatching { device.close(timeoutMs) } }
        val outcome = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { close.await() }
        if (outcome == null) {
            val timeoutDetail = "SESSION_CLEANUP_TIMEOUT: $label exceeded ${timeoutMs}ms; cleanup continues under its core deadline"
            config.log(timeoutDetail)
            close.invokeOnCompletion { error ->
                if (error != null) config.log("$label eventual cleanup failed: ${error.message}")
            }
            return timeoutDetail
        }
        return outcome.exceptionOrNull()?.message
    }

    /**
     * Fire-and-forget device cleanup for exhausted shutdown: state is already detached.
     * Best effort: process exit may cut it short; next open recovers a non-terminal journal.
     */
    private fun launchDetachedCleanup(
        device: ServiceDevice,
        timeoutMs: Long,
        label: String,
    ) {
        cleanupScope.launch {
            val outcome = runCatching { device.close(timeoutMs.coerceAtLeast(1L)) }
            val detail = outcome.exceptionOrNull()?.message
            config.log("$label detached cleanup finished" + (detail?.let { " (quarantined: $it)" } ?: ""))
        }
    }

    private fun deadlineAfterMs(timeoutMs: Long): Long {
        val timeoutNanos = timeoutMs.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        return System.nanoTime().let { now -> if (Long.MAX_VALUE - now < timeoutNanos) Long.MAX_VALUE else now + timeoutNanos }
    }

    private fun remainingMs(deadlineNanos: Long): Long = ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L)

    // ---- devices -----------------------------------------------------------------------------

    /** Every device ADB lists with what the journal and the per-serial lock say about it. */
    suspend fun devices(): List<DeviceEntry> {
        val serials = config.adb.devices()
        val live = synchronized(lifecycleLock) { sessions.values.toList() }
        return serials.map { serial ->
            val store = SessionJournalStore(config.journalRoot, serial)
            val status =
                quarantine(store)?.let { DeviceStatus.Quarantined(it) }
                    ?: live.firstOrNull { it.device.serial == serial }?.let { DeviceStatus.Held(it.connection.id) }
                    ?: if (store.isLeased()) DeviceStatus.Held(null) else DeviceStatus.Free
            DeviceEntry(serial, status)
        }
    }

    private fun quarantine(store: SessionJournalStore): String? =
        runCatching {
            store
                .read()
                ?.takeIf { it.state == JournalState.QUARANTINED }
                ?.let { it.quarantineReason ?: "QUARANTINED" }
        }.getOrNull()

    // ---- sessions ----------------------------------------------------------------------------

    data class OpenSessionOptions(
        val driverApk: Path?,
        val driverTestApk: Path?,
        val skipDriverInstall: Boolean,
        val syncAuthority: String?,
        val allowedSystemPackages: Set<String>,
        val defaultTimeoutMs: Long,
        /** How long [openSession] may wait for another session's lock on the serial. */
        val leaseTimeoutMs: Long,
    )

    suspend fun openSession(
        connection: Connection,
        serial: String,
        autPackage: String,
        options: OpenSessionOptions,
    ): Session {
        synchronized(lifecycleLock) {
            val current = connections[connection.id]
            if (current !== connection || connection.closed) throw UnknownConnectionException(connection.id)
        }
        val explicitApks = options.driverApk != null || options.driverTestApk != null
        val useBundled = !explicitApks && !options.skipDriverInstall && config.bundledDriver != null
        // The bundled driver goes on each device once per service lifetime. Whether this open is
        // the one that installs it is decided under the serial's lock (see installDriver), so two
        // opens racing for one device cannot split "I install" from "I start first".
        var installedBundled = false
        val log = DriverLogBuffer()
        val deviceConfig =
            DeviceSessionConfig(
                serial = serial,
                autPackage = autPackage,
                driverApk = options.driverApk ?: config.bundledDriver?.driverApk?.takeIf { useBundled },
                driverTestApk = options.driverTestApk ?: config.bundledDriver?.driverTestApk?.takeIf { useBundled },
                installDriver = {
                    if (!useBundled) {
                        true
                    } else {
                        synchronized(lifecycleLock) { driverInstalled.add(serial) }.also { installedBundled = it }
                    }
                },
                syncAuthority = options.syncAuthority ?: "$autPackage.tap-sync",
                allowedSystemPackages = options.allowedSystemPackages.ifEmpty { setOf(PERMISSION_CONTROLLER_PACKAGE) },
                journalRoot = config.journalRoot,
                adb = config.adb,
                driverLog = log::append,
                leaseTimeoutMs = options.leaseTimeoutMs,
            )
        val device: ServiceDevice =
            try {
                opener.open(deviceConfig)
            } catch (error: Throwable) {
                if (installedBundled) synchronized(lifecycleLock) { driverInstalled.remove(serial) }
                throw error
            }
        // Registration is one lifecycle transaction. When close won while open suspended, the
        // device is closed before exposure and never appears in either map: no orphan lease.
        val registered: Session? =
            synchronized(lifecycleLock) {
                val current = connections[connection.id]
                if (current !== connection || connection.closed) {
                    null
                } else {
                    val session = Session(UUID.randomUUID().toString(), connection, device, options.defaultTimeoutMs, log)
                    sessions[session.id] = session
                    connection.sessions[session.id] = session
                    session
                }
            }
        if (registered == null) {
            withContext(NonCancellable) {
                val detail = closeDeviceBounded(device, DEVICE_SESSION_CLOSE_TIMEOUT_MS, "orphaned open on $serial")
                if (installedBundled) synchronized(lifecycleLock) { driverInstalled.remove(serial) }
                config.log("orphaned open on $serial closed before exposure" + (detail?.let { " (quarantined: $it)" } ?: ""))
            }
            throw UnknownConnectionException(connection.id)
        }
        config.log("session ${registered.id} open on $serial (generation ${device.generation}) for connection ${connection.id}")
        return registered
    }

    /**
     * Returns the session, rejecting use after sticky reap quarantine so a poisoned device
     * cannot be driven through a direct command path that bypasses [AppLifecycle].
     */
    fun session(id: String): Session {
        val found = synchronized(lifecycleLock) { sessions[id] } ?: throw UnknownSessionException(id)
        found.device.checkUsable()
        return found
    }

    /**
     * Returns null when cleanup was clean, otherwise the quarantine detail. The take from both
     * maps is one transaction, so the session is closed exactly once even when an explicit Close
     * races connection teardown; the device close itself is bounded inside NonCancellable.
     */
    suspend fun closeSession(
        id: String,
        timeoutMs: Long = DEVICE_SESSION_CLOSE_TIMEOUT_MS,
    ): String? =
        withContext(NonCancellable) {
            val session =
                synchronized(lifecycleLock) {
                    val found = sessions.remove(id)
                    if (found != null) found.connection.sessions.remove(id)
                    found
                } ?: throw UnknownSessionException(id)
            val detail = closeDeviceBounded(session.device, timeoutMs, "session $id")
            config.log("session $id closed" + (detail?.let { " (quarantined: $it)" } ?: ""))
            return@withContext detail
        }

    /**
     * Awaits a submitted command. Transport loss is reported as an error [Response] (with the
     * transmission state as its detail), not thrown, so the client sees `INDETERMINATE` /
     * `TRANSPORT_LOST` through the normal result path. Caller cancellation propagates as
     * cancellation: it is never mapped to a transport-loss response.
     */
    suspend fun await(pending: DriverClient.PendingCommand): Response =
        try {
            pending.await()
        } catch (loss: CommandTransportException) {
            Response.failure(loss.code, detail = loss.transmissionState.name, message = loss.message, durationMs = 0)
        }

    /**
     * Bounded shutdown: every connection is attempted within [timeoutMs]. Remaining time is
     * propagated to each connection and session close as effective-timeout children inside
     * NonCancellable boundaries; a stuck session is quarantined and the next one is still
     * attempted. Once the shared deadline is exhausted, all remaining connections and sessions
     * are atomically detached, every remaining device cleanup is launched on the owned cleanup
     * scope without serially awaiting any of them, and this function returns immediately — so
     * `close(timeoutMs)` plus the server's `awaitTermination` on the remaining hook budget (see
     * `ServiceMain`) cannot exceed the advertised hook budget beyond scheduling overhead. Detached
     * cleanups are best effort with their own core deadline and may be cut short by process exit;
     * next open recovers a non-terminal journal. Never throws. Connections or sessions that
     * attempt to open during shutdown fail (`ServiceClosingException` / `UnknownConnectionException`)
     * and never escape teardown: `closing` is set before the first close, and registration loses to
     * the detached state so orphans are closed before exposure.
     */
    suspend fun close(timeoutMs: Long = shutdownTotalMs) {
        withContext(NonCancellable) {
            val deadlineNanos = deadlineAfterMs(timeoutMs)
            val ids =
                synchronized(lifecycleLock) {
                    closing = true
                    connections.keys.toList()
                }
            ids.forEachIndexed { index, id ->
                val remainingConnections = ids.size - index
                val remainingMs = remainingMs(deadlineNanos)
                if (remainingMs <= 0L) {
                    // Strict bound: detach everything still registered in one transaction, launch
                    // every remaining cleanup without awaiting, and return immediately. The old code
                    // logged "may remain" here and left later connections registered.
                    val detached =
                        synchronized(lifecycleLock) {
                            ids.subList(index, ids.size).mapNotNull { remainingId ->
                                val connection = connections.remove(remainingId) ?: return@mapNotNull null
                                if (connection.closed) return@mapNotNull null
                                connection.closed = true
                                val owned = connection.sessions.values.toList()
                                owned.forEach {
                                    sessions.remove(it.id)
                                    connection.sessions.remove(it.id)
                                }
                                val hooks = connection.onClose.toList()
                                connection.onClose.clear()
                                connection.attachOwner = null
                                Snapshot(connection, owned, hooks)
                            }
                        }
                    detached.forEach { snapshot -> snapshot.hooks.forEach { runCatching(it) } }
                    val detachedSessions = detached.flatMap { it.sessions }
                    detachedSessions.forEach { session ->
                        launchDetachedCleanup(session.device, shutdownSessionMs, "session ${session.id}")
                    }
                    config.log(
                        "service shutdown budget ${timeoutMs}ms exhausted; " +
                            "${detached.size} connection(s), ${detachedSessions.size} session(s) " +
                            "detached with cleanup launched",
                    )
                    return@withContext
                }
                // Reserve a share for every later connection. closeConnection applies the same
                // rule to its sessions, so one stuck cleanup never consumes all remaining time.
                val connectionBudgetMs = (remainingMs / remainingConnections).coerceAtLeast(1L)
                runCatching {
                    closeConnectionWithin(
                        id,
                        "service shutdown",
                        perSessionMs = minOf(shutdownSessionMs, connectionBudgetMs),
                        totalTimeoutMs = connectionBudgetMs,
                    )
                }.onFailure { config.log("connection $id shutdown failed: ${it.message}") }
            }
        }
    }

    // ---- test hooks --------------------------------------------------------------------------

    internal fun sessionIds(): Set<String> = synchronized(lifecycleLock) { sessions.keys.toSet() }

    internal fun connectionSessionIds(id: String): Set<String>? = synchronized(lifecycleLock) { connections[id]?.sessions?.keys?.toSet() }

    internal fun connectionExists(id: String): Boolean = synchronized(lifecycleLock) { connections.containsKey(id) }

    internal fun attachOwnerPresent(id: String): Boolean = synchronized(lifecycleLock) { connections[id]?.attachOwner != null }

    internal fun onCloseCount(id: String): Int = synchronized(lifecycleLock) { connections[id]?.onClose?.size ?: 0 }
}
