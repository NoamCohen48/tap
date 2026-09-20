package com.company.tap.service

import com.company.tap.host.Adb
import com.company.tap.host.CommandTransportException
import com.company.tap.host.DeviceSession
import com.company.tap.host.DeviceSessionConfig
import com.company.tap.host.DriverClient
import com.company.tap.host.JournalState
import com.company.tap.host.PERMISSION_CONTROLLER_PACKAGE
import com.company.tap.host.SessionJournalStore
import com.company.tap.protocol.Response
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ServiceConfig(
    val adb: Adb,
    val stateDir: Path,
    val journalRoot: Path = stateDir.resolve("sessions"),
    /** Restrict the service to these serials; null = every device ADB lists. */
    val allowedSerials: Set<String>? = null,
    val bundledDriver: BundledDriver?,
    val log: (String) -> Unit = ::println,
)

class UnknownConnectionException(id: String) : NoSuchElementException("Unknown connection $id")
class UnknownSessionException(id: String) : NoSuchElementException("Unknown session $id")

sealed class DeviceStatus {
    object Free : DeviceStatus()
    /** Locked by a live session: [connectionId] when it is one of ours, null for another process. */
    data class Held(val connectionId: String?) : DeviceStatus()
    data class Quarantined(val reason: String) : DeviceStatus()
}

/** One row of [TapService.devices]: a serial ADB lists and what the lock and journal say about it. */
data class DeviceEntry(val serial: String, val status: DeviceStatus)

/**
 * One client process talking to the service. Every session it opens belongs to it; when the
 * client detaches (its Attach stream drops) or calls Close, all of them are closed.
 */
class Connection(val id: String, val name: String) {
    val sessions = ConcurrentHashMap<String, Session>()
    @Volatile
    var closed = false
    /** Invoked once when the connection closes, so an Attach stream can complete. */
    val onClose = ConcurrentHashMap.newKeySet<() -> Unit>()
}

/**
 * One open driver session as the service sees it: the `:host:core` [DeviceSession] (lock,
 * journal, driver, forward, authenticated client, per-package [DeviceSession.app]) plus what
 * the service adds — the owning [connection], the default command timeout and the captured
 * driver log.
 */
class Session(val id: String, val connection: Connection, val device: DeviceSession, val defaultTimeoutMs: Long, val log: DriverLogBuffer)

/** The last [capacity] lines of a session's driver output, kept for failure artifacts. */
class DriverLogBuffer(private val capacity: Int = 2_000) {
    private val lines = ArrayDeque<String>()
    @Synchronized
    fun append(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }
    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}

/**
 * All service state: connections (client ownership) and live sessions. There is no lease table
 * here: exclusive use of a device is the per-serial file lock in `:host:core` (taken by
 * [DeviceSession.open], released when the session closes or its process dies), so the device
 * list is only a view — [devices] probes that lock and the journal. The gRPC servicers are thin
 * adapters over this class so it can be exercised without a server.
 */
class TapService(val config: ServiceConfig) : AutoCloseable {
    private val connections = ConcurrentHashMap<String, Connection>()
    private val sessions = ConcurrentHashMap<String, Session>()
    /** Serials whose bundled driver this service process already installed. */
    private val driverInstalled = ConcurrentHashMap.newKeySet<String>()

    // ---- connections -------------------------------------------------------------------------

    fun openConnection(name: String): Connection {
        val connection = Connection(UUID.randomUUID().toString(), name)
        connections[connection.id] = connection
        config.log("connection ${connection.id} opened ($name)")
        return connection
    }

    fun connection(id: String): Connection = connections[id]?.takeUnless { it.closed } ?: throw UnknownConnectionException(id)

    /** Closes every session of the connection. Idempotent. Returns the number closed. */
    fun closeConnection(id: String, reason: String): Int {
        val connection = connections.remove(id) ?: return 0
        if (connection.closed) return 0
        connection.closed = true
        var closedSessions = 0
        connection.sessions.keys.toList().forEach { sessionId ->
            runCatching { closeSession(sessionId) }.onFailure { config.log("session $sessionId close failed: ${it.message}") }
            closedSessions++
        }
        connection.onClose.forEach { runCatching(it) }
        config.log("connection $id closed ($reason): sessions=$closedSessions")
        return closedSessions
    }

    // ---- devices -----------------------------------------------------------------------------

    /** Every device ADB lists with what the journal and the per-serial lock say about it. */
    fun devices(): List<DeviceEntry> =
        config.adb.devices().filter { config.allowedSerials?.contains(it) ?: true }.map { serial ->
            val store = SessionJournalStore(config.journalRoot, serial)
            val status = quarantine(store)?.let { DeviceStatus.Quarantined(it) }
                ?: sessions.values.firstOrNull { it.device.serial == serial }?.let { DeviceStatus.Held(it.connection.id) }
                ?: if (store.isLeased()) DeviceStatus.Held(null) else DeviceStatus.Free
            DeviceEntry(serial, status)
        }

    private fun quarantine(store: SessionJournalStore): String? = runCatching {
        store.read()
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

    fun openSession(connection: Connection, serial: String, autPackage: String, options: OpenSessionOptions): Session {
        val explicitApks = options.driverApk != null || options.driverTestApk != null
        val useBundled = !explicitApks && !options.skipDriverInstall && config.bundledDriver != null
        // The bundled driver goes on each device once per service lifetime. Whether this open is
        // the one that installs it is decided under the serial's lock (see installDriver), so two
        // opens racing for one device cannot split "I install" from "I start first".
        var installedBundled = false
        val log = DriverLogBuffer()
        val device = try {
            DeviceSession.open(
                DeviceSessionConfig(
                    serial = serial,
                    autPackage = autPackage,
                    driverApk = options.driverApk ?: config.bundledDriver?.driverApk?.takeIf { useBundled },
                    driverTestApk = options.driverTestApk ?: config.bundledDriver?.driverTestApk?.takeIf { useBundled },
                    installDriver = { !useBundled || driverInstalled.add(serial).also { installedBundled = it } },
                    syncAuthority = options.syncAuthority ?: "$autPackage.tap-sync",
                    allowedSystemPackages = options.allowedSystemPackages.ifEmpty { setOf(PERMISSION_CONTROLLER_PACKAGE) },
                    journalRoot = config.journalRoot,
                    adb = config.adb,
                    driverLog = log::append,
                    leaseTimeoutMs = options.leaseTimeoutMs,
                ),
            )
        } catch (error: Throwable) {
            if (installedBundled) driverInstalled.remove(serial)
            throw error
        }
        val session = Session(UUID.randomUUID().toString(), connection, device, options.defaultTimeoutMs, log)
        sessions[session.id] = session
        connection.sessions[session.id] = session
        config.log("session ${session.id} open on $serial (generation ${device.generation}) for connection ${connection.id}")
        return session
    }

    fun session(id: String): Session = sessions[id] ?: throw UnknownSessionException(id)

    /** Returns null when cleanup was clean, otherwise the quarantine detail. */
    fun closeSession(id: String): String? {
        val session = sessions.remove(id) ?: throw UnknownSessionException(id)
        session.connection.sessions.remove(id)
        val detail = runCatching { session.device.close() }.exceptionOrNull()?.message
        config.log("session $id closed" + (detail?.let { " (quarantined: $it)" } ?: ""))
        return detail
    }

    /**
     * Runs one protocol command. [onStarted] receives the pending command so the caller can wire
     * gRPC cancellation to it. Transport loss is reported as an error [Response], not thrown.
     */
    fun execute(
        session: Session,
        arguments: Conversions.CommandArguments,
        onStarted: (DriverClient.PendingCommand) -> Unit = {},
    ): Pair<Response, Long> {
        val client = session.device.client
        val pending = client.submit(
            arguments.operation, arguments.selector, arguments.timeoutMs, arguments.containerSelector,
            arguments.inputText, arguments.maxScrolls, arguments.direction, arguments.distancePercent,
            arguments.keyCode, arguments.packageName, arguments.stableForMs, arguments.stableSignal, arguments.observedPid, arguments.observedStartToken,
            arguments.expectedProcessStartUuid, arguments.expectedSessionIdentity,
        )
        onStarted(pending)
        return try {
            pending.await() to pending.requestId
        } catch (loss: CommandTransportException) {
            Response.failure(
                loss.code,
                detail = loss.transmissionState.name,
                message = loss.message,
                durationMs = 0,
            ) to pending.requestId
        }
    }

    override fun close() {
        connections.keys.toList().forEach { closeConnection(it, "service shutdown") }
    }
}
