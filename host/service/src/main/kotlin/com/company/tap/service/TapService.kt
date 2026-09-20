package com.company.tap.service

import com.company.tap.host.Adb
import com.company.tap.host.AppLifecycle
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
    /** Restrict the pool to these serials; null = every device ADB lists. */
    val allowedSerials: Set<String>? = null,
    val bundledDriver: BundledDriver?,
    val log: (String) -> Unit = ::println,
)

class UnknownRunException(id: String) : NoSuchElementException("Unknown run $id")
class UnknownSessionException(id: String) : NoSuchElementException("Unknown session $id")

sealed class DeviceStatus {
    object Free : DeviceStatus()
    /** Locked by a live session: [runId] when it is one of ours, null for another process. */
    data class Leased(val runId: String?) : DeviceStatus()
    data class Quarantined(val reason: String) : DeviceStatus()
}

data class PoolEntry(val serial: String, val status: DeviceStatus)

class Run(val id: String, val name: String) {
    val sessions = ConcurrentHashMap<String, ManagedSession>()
    @Volatile
    var closed = false
    /** Invoked once when the run closes, so an Attach stream can complete. */
    val onClose = ConcurrentHashMap.newKeySet<() -> Unit>()
}

class ManagedSession(val id: String, val run: Run, val device: DeviceSession, val defaultTimeoutMs: Long, val log: RingLog) {
    private val apps = ConcurrentHashMap<String, AppLifecycle>()

    /** One [AppLifecycle] per package so the sync identity survives across calls. */
    fun app(packageName: String): AppLifecycle = apps.computeIfAbsent(packageName) { AppLifecycle(device, it) }
}

/** Bounded driver log kept per session for failure artifacts. */
class RingLog(private val capacity: Int = 2_000) {
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
 * All service state: runs (client ownership) and live sessions. There is no lease table here:
 * exclusive use of a device is the per-serial file lock in `:host:core` (taken by
 * [DeviceSession.open], released when the session closes or its process dies), so the pool is
 * only a view — `inventory` probes that lock and the journal. The gRPC servicers are thin
 * adapters over this class so it can be exercised without a server.
 */
class TapService(val config: ServiceConfig) : AutoCloseable {
    private val runs = ConcurrentHashMap<String, Run>()
    private val sessions = ConcurrentHashMap<String, ManagedSession>()
    /** Serials whose bundled driver this service process already installed. */
    private val driverInstalled = ConcurrentHashMap.newKeySet<String>()

    // ---- runs --------------------------------------------------------------------------------

    fun openRun(name: String): Run {
        val run = Run(UUID.randomUUID().toString(), name)
        runs[run.id] = run
        config.log("run ${run.id} opened ($name)")
        return run
    }

    fun run(id: String): Run = runs[id]?.takeUnless { it.closed } ?: throw UnknownRunException(id)

    /** Closes every session of the run. Idempotent. Returns the number closed. */
    fun closeRun(id: String, reason: String): Int {
        val run = runs.remove(id) ?: return 0
        if (run.closed) return 0
        run.closed = true
        var closedSessions = 0
        run.sessions.keys.toList().forEach { sessionId ->
            runCatching { closeSession(sessionId) }.onFailure { config.log("session $sessionId close failed: ${it.message}") }
            closedSessions++
        }
        run.onClose.forEach { runCatching(it) }
        config.log("run $id closed ($reason): sessions=$closedSessions")
        return closedSessions
    }

    // ---- pool --------------------------------------------------------------------------------

    /** Every online device with what the journal and the per-serial lock say about it. */
    fun inventory(): List<PoolEntry> =
        config.adb.devices().filter { config.allowedSerials?.contains(it) ?: true }.map { serial ->
            val store = SessionJournalStore(config.journalRoot, serial)
            val status = quarantine(store)?.let { DeviceStatus.Quarantined(it) }
                ?: sessions.values.firstOrNull { it.device.serial == serial }?.let { DeviceStatus.Leased(it.run.id) }
                ?: if (store.isLeased()) DeviceStatus.Leased(null) else DeviceStatus.Free
            PoolEntry(serial, status)
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

    fun openSession(run: Run, serial: String, autPackage: String, options: OpenSessionOptions): ManagedSession {
        val explicitApks = options.driverApk != null || options.driverTestApk != null
        val useBundled = !explicitApks && !options.skipDriverInstall && config.bundledDriver != null
        // The bundled driver goes on each device once per service lifetime. Whether this open is
        // the one that installs it is decided under the serial's lock (see installDriver), so two
        // opens racing for one device cannot split "I install" from "I start first".
        var installedBundled = false
        val log = RingLog()
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
        val session = ManagedSession(UUID.randomUUID().toString(), run, device, options.defaultTimeoutMs, log)
        sessions[session.id] = session
        run.sessions[session.id] = session
        config.log("session ${session.id} open on $serial (generation ${device.generation}) for run ${run.id}")
        return session
    }

    fun session(id: String): ManagedSession = sessions[id] ?: throw UnknownSessionException(id)

    /** Returns null when cleanup was clean, otherwise the quarantine detail. */
    fun closeSession(id: String): String? {
        val session = sessions.remove(id) ?: throw UnknownSessionException(id)
        session.run.sessions.remove(id)
        val detail = runCatching { session.device.close() }.exceptionOrNull()?.message
        config.log("session $id closed" + (detail?.let { " (quarantined: $it)" } ?: ""))
        return detail
    }

    /**
     * Runs one protocol command. [onStarted] receives the pending command so the caller can wire
     * gRPC cancellation to it. Transport loss is reported as an error [Response], not thrown.
     */
    fun execute(
        session: ManagedSession,
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
        runs.keys.toList().forEach { closeRun(it, "service shutdown") }
    }
}
