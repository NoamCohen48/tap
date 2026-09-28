package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.DEVICE_SESSION_CLOSE_TIMEOUT_MS
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.DeviceSession
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverBuildMismatchException
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.host.SessionJournalStore
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.UUID

class DaemonConfig(
    val adb: Adb,
    val stateDir: Path,
    val journalRoot: Path = stateDir.resolve("sessions"),
    /** The driver APKs attach installs (bundled in this build, or `tap serve --driver-apk`); null = none. */
    val driver: DriverApks?,
    val log: (String) -> Unit = ::println,
)

class UnknownClientConnectionException(
    id: String,
) : NoSuchElementException("Unknown client connection $id")

class UnknownAttachedDeviceException(
    id: String,
) : NoSuchElementException("Unknown AttachedDevice $id")

/** A call on an attached device by a connection that does not own it. Maps to PERMISSION_DENIED. */
class NotOwnerException(
    attachedDeviceId: String,
    clientConnectionId: String,
) : RuntimeException("AttachedDevice $attachedDeviceId is not owned by client connection $clientConnectionId")

/** A request the daemon cannot serve in its current state; maps to FAILED_PRECONDITION. */
open class DaemonPreconditionException(
    message: String,
) : RuntimeException(message)

/** A second Observe stream for a connection that already has one. */
class DuplicateClientObserveException(
    id: String,
) : DaemonPreconditionException("Client connection $id already has an Observe stream")

/** A new connection attempted after daemon shutdown began. */
class DaemonClosingException : DaemonPreconditionException("Daemon is shutting down")

sealed class DeviceStatus {
    object Free : DeviceStatus()

    /** Locked by a live attached device: [ownerConnectionId] when it is one of ours, null for another process. */
    data class Held(
        val ownerConnectionId: String?,
    ) : DeviceStatus()

    data class Quarantined(
        val reason: String,
    ) : DeviceStatus()

    /** Listed by ADB but not usable: [state] OFFLINE, UNAUTHORIZED or OTHER ([raw] is adb's text). */
    data class Unavailable(
        val state: AdbDeviceState,
        val raw: String,
    ) : DeviceStatus()
}

/** One row of [TapDaemon.connections]: a live connection and the devices it has attached. */
data class ConnectionInfo(
    val id: String,
    val name: String,
    val holdIdleMs: Long?,
    val idleMs: Long,
    val attachedDevices: List<AttachedDevice>,
)

/** A held connection's name is already taken by another held connection. */
class HeldNameTakenException(
    name: String,
) : DaemonPreconditionException("A held client connection named '$name' already exists")

/** Observe on a held connection, which has no stream by definition. */
class HeldConnectionObserveException(
    id: String,
) : DaemonPreconditionException("Client connection $id is held: it has no Observe stream")

/** One row of [TapDaemon.devices]: a serial ADB lists and what the lock and journal say about it. */
data class DeviceEntry(
    val serial: String,
    val status: DeviceStatus,
)

/**
 * One client talking to the daemon. Every attached device belongs to it; when it ends, all owned
 * devices are detached. An observed connection ([holdIdleMs] null) ends with its Observe stream; a
 * held one ends [holdIdleMs] after the last call that named it (`.docs/agent-surface.md`).
 *
 * All mutable state is guarded by the owning [TapDaemon]'s lifecycle lock; transitions are
 * short non-suspending synchronized blocks, never held across suspension.
 */
class ConnectedClient internal constructor(
    val id: String,
    val name: String,
    /** Set for a held connection: how long it may go without a call before it is ended. */
    val holdIdleMs: Long? = null,
) {
    internal var closed = false

    /** Held connections: when the last call naming it started or finished ([System.nanoTime]). */
    internal var lastUsedNanos = System.nanoTime()

    /** Held connections: calls naming it that have not finished; it is never idle while any run. */
    internal var inFlight = 0

    /** Invoked once with the reason when the connection closes, so an Observe stream can complete. */
    internal val onDisconnect = HashSet<(String) -> Unit>()

    /** The single Observe stream owner; non-null while a stream is registered. */
    internal var observeOwner: Any? = null

    /** The device calls this connection made (`Events`). */
    val events = EventLog()
}

/**
 * The daemon's view of one open driver session: lifecycle metadata plus how to close it.
 * Production wraps `:host:core` [DeviceSession]; tests substitute a fake through [DeviceSessionOpener].
 */
internal interface DaemonDeviceSession {
    val serial: String
    val generation: Long
    val autPackage: String
    val client: DriverClient

    fun app(packageName: String): AppLifecycle

    /** Rejects use after sticky reap quarantine; cleanup still runs via [close]. */
    fun checkUsable()

    suspend fun close(timeoutMs: Long)
}

/** Opens one [DaemonDeviceSession]; production delegates to [DeviceSession.open]. */
internal interface DeviceSessionOpener {
    suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession
}

private object RealDeviceSessionOpener : DeviceSessionOpener {
    override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession = CoreDeviceSessionAdapter(DeviceSession.open(config))
}

/** Production [DaemonDeviceSession]: a live `:host:core` session. */
private class CoreDeviceSessionAdapter(
    val delegate: DeviceSession,
) : DaemonDeviceSession {
    override val serial: String get() = delegate.serial
    override val generation: Long get() = delegate.generation
    override val autPackage: String get() = delegate.autPackage
    override val client: DriverClient get() = delegate.client

    override fun app(packageName: String): AppLifecycle = delegate.app(packageName)

    override fun checkUsable() = delegate.checkUsable()

    override suspend fun close(timeoutMs: Long) = delegate.close(timeoutMs)
}

/**
 * One open driver session as the daemon sees it: the [DaemonDeviceSession] (lock, journal, driver,
 * forward, authenticated client, per-package app) plus what the daemon adds — the owning
 * [ownerConnectionId], the default command timeout and the captured driver log.
 */
class AttachedDevice internal constructor(
    val id: String,
    val ownerConnectionId: String,
    internal val deviceSession: DaemonDeviceSession,
    val defaultTimeoutMs: Long,
    val driverLog: DriverLogBuffer,
    /** The owning connection's log; calls on this device are recorded into it. */
    val events: EventLog,
) {
    /** The latest screen snapshot and its refs (`DeviceService.ScreenSnapshot` / `ResolveRef`). */
    internal val screen = io.github.noamcohen48.tap.daemon.snapshot.ScreenSnapshotState()
}

/** The last [capacity] lines of an attached device's driver output, kept for artifacts. */
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

/** What [TapDaemon] takes from outside besides its config: production defaults, fakes in tests. */
internal class DaemonDeps(
    val opener: DeviceSessionOpener = RealDeviceSessionOpener,
    val shutdownTotalMs: Long = DAEMON_SHUTDOWN_TOTAL_MS,
    val shutdownAttachedDeviceMs: Long = DAEMON_SHUTDOWN_ATTACHED_DEVICE_MS,
    val observeGraceMs: Long = OBSERVE_GRACE_MS,
)

/** Total bound for [TapDaemon.close]: every connection is still attempted within it. */
const val DAEMON_SHUTDOWN_TOTAL_MS = 30_000L

/** Slack between the core close deadline and the daemon's wait for it. */
private const val CLOSE_TIMEOUT_MARGIN_MS = 1_000L

/** How long a new connection may go without an Observe stream before it is reaped. */
const val OBSERVE_GRACE_MS = 30_000L

/** Per-attached-device cap inside [TapDaemon.close]. */
const val DAEMON_SHUTDOWN_ATTACHED_DEVICE_MS = 10_000L

/**
 * All daemon state: client connections and attached devices. There is no lease table
 * here: exclusive use of a device is the per-serial file lock in `:host:core` (taken by
 * [DeviceSession.open], released when the session closes or its process dies), so the device
 * list is only a view — [devices] probes that lock and the journal. The gRPC servicers are thin
 * adapters over this class so it can be exercised without a server.
 *
 * One synchronized lifecycle lock guards closed state, the single Observe owner, close-callback
 * registration, and attached-device registration and removal. Every synchronized block is short and non-suspending; suspension (device open/close,
 * ADB) always happens outside the lock.
 */
class TapDaemon internal constructor(
    val config: DaemonConfig,
    private val deps: DaemonDeps,
) {
    constructor(config: DaemonConfig) : this(config, DaemonDeps())

    /** Test convenience: the same seams as [DaemonDeps], by name. */
    internal constructor(
        config: DaemonConfig,
        opener: DeviceSessionOpener,
        shutdownTotalMs: Long = DAEMON_SHUTDOWN_TOTAL_MS,
        shutdownAttachedDeviceMs: Long = DAEMON_SHUTDOWN_ATTACHED_DEVICE_MS,
        observeGraceMs: Long = OBSERVE_GRACE_MS,
    ) : this(config, DaemonDeps(opener, shutdownTotalMs, shutdownAttachedDeviceMs, observeGraceMs))

    private val opener get() = deps.opener
    private val shutdownTotalMs get() = deps.shutdownTotalMs
    private val shutdownAttachedDeviceMs get() = deps.shutdownAttachedDeviceMs
    private val observeGraceMs get() = deps.observeGraceMs
    private val lifecycleLock = Any()
    private val clientConnectionsById = HashMap<String, ConnectedClient>()
    private val attachedDevicesById = HashMap<String, AttachedDevice>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closing = false

    /** Serials whose bundled driver this daemon process already installed. */
    private val driverInstalled = HashSet<String>()

    // ---- connections -------------------------------------------------------------------------

    /**
     * Registers a connection. Without [holdIdleMs] it is observed: it must open its Observe stream
     * within the grace period and ends with it. With [holdIdleMs] it is held: no stream, a name
     * unique among held connections, and it ends [holdIdleMs] after the last call that named it.
     */
    fun connectClient(
        name: String,
        holdIdleMs: Long? = null,
    ): ConnectedClient {
        val connection = ConnectedClient(UUID.randomUUID().toString(), name, holdIdleMs)
        synchronized(lifecycleLock) {
            if (closing) throw DaemonClosingException()
            if (holdIdleMs != null && clientConnectionsById.values.any { it.holdIdleMs != null && it.name == name }) {
                throw HeldNameTakenException(name)
            }
            clientConnectionsById[connection.id] = connection
        }
        if (holdIdleMs != null) {
            config.log("client connection ${connection.id} connected ($name, held, idle ${holdIdleMs}ms)")
            launchIdleReaper(connection)
            return connection
        }
        config.log("client connection ${connection.id} connected ($name)")
        // Observe is the only liveness signal, so a client that dies between Connect and Observe
        // would otherwise leak its connection (and anything it attached) until daemon shutdown.
        // The reaper holds only the id, so a connection disconnected earlier is not retained.
        val id = connection.id
        cleanupScope.launch {
            delay(observeGraceMs)
            val unobserved = synchronized(lifecycleLock) { clientConnectionsById[id]?.let { !it.closed && it.observeOwner == null } ?: false }
            if (unobserved) disconnectClient(id, "no Observe stream within ${observeGraceMs}ms")
        }
        return connection
    }

    fun clientConnection(id: String): ConnectedClient =
        synchronized(lifecycleLock) {
            clientConnectionsById[id]?.takeUnless { it.closed } ?: throw UnknownClientConnectionException(id)
        }

    /** The event log of connection [id]; like any call naming a held connection, it renews it. */
    suspend fun eventLog(id: String): EventLog {
        val connection = clientConnection(id)
        markInUse(id)
        return connection.events
    }

    /** Every live connection with its attached devices, in no particular order. */
    fun connections(): List<ConnectionInfo> {
        val now = System.nanoTime()
        return synchronized(lifecycleLock) {
            clientConnectionsById.values.filterNot { it.closed }.map { connection ->
                ConnectionInfo(
                    id = connection.id,
                    name = connection.name,
                    holdIdleMs = connection.holdIdleMs,
                    idleMs = if (connection.inFlight > 0) 0L else (now - connection.lastUsedNanos) / 1_000_000L,
                    attachedDevices = attachedDevicesById.values.filter { it.ownerConnectionId == connection.id },
                )
            }
        }
    }

    /**
     * Marks a call naming held connection [id] as running until the calling coroutine's job
     * completes (the gRPC call), so the idle clock starts only when it has finished. Observed
     * and unknown connections are left alone.
     */
    private suspend fun markInUse(id: String) {
        val connection =
            synchronized(lifecycleLock) {
                clientConnectionsById[id]?.takeIf { it.holdIdleMs != null && !it.closed }?.also {
                    it.inFlight++
                    it.lastUsedNanos = System.nanoTime()
                }
            } ?: return
        val finished = {
            synchronized(lifecycleLock) {
                connection.inFlight--
                connection.lastUsedNanos = System.nanoTime()
            }
        }
        val job = currentCoroutineContext()[Job]
        if (job == null) finished() else job.invokeOnCompletion { finished() }
    }

    /** Ends held [connection] once it has had no call for its idle timeout. */
    private fun launchIdleReaper(connection: ConnectedClient) {
        val idleMs = checkNotNull(connection.holdIdleMs)
        cleanupScope.launch {
            while (true) {
                val waitMs =
                    synchronized(lifecycleLock) {
                        if (connection.closed) return@launch
                        val idleForMs = (System.nanoTime() - connection.lastUsedNanos) / 1_000_000L
                        when {
                            connection.inFlight > 0 -> idleMs
                            idleForMs >= idleMs -> null
                            else -> idleMs - idleForMs
                        }
                    }
                if (waitMs == null) {
                    disconnectClient(connection.id, "idle for ${idleMs}ms")
                    return@launch
                }
                delay(waitMs)
            }
        }
    }

    /**
     * Atomically claims the single Observe stream for [id]. Unknown or closed → throws
     * [UnknownClientConnectionException] (NOT_FOUND); already observed → throws
     * [DuplicateClientObserveException] (FAILED_PRECONDITION). On success [closer] is registered so
     * a concurrent disconnect cancels the stream promptly.
     */
    fun observeAcquire(
        id: String,
        token: Any,
        closer: (String) -> Unit,
    ): ConnectedClient =
        synchronized(lifecycleLock) {
            val connection =
                clientConnectionsById[id]?.takeUnless { it.closed }
                    ?: throw UnknownClientConnectionException(id)
            if (connection.holdIdleMs != null) throw HeldConnectionObserveException(id)
            if (connection.observeOwner != null) throw DuplicateClientObserveException(id)
            connection.observeOwner = token
            connection.onDisconnect.add(closer)
            connection
        }

    /**
     * Atomically disconnects the connection whose Observe stream owns [token]. A stale stream
     * cannot disconnect a connection observed by another token: ownership verification,
     * connection removal, and attached-device detachment share one lifecycle-lock transaction.
     */
    suspend fun disconnectObservedClient(
        id: String,
        token: Any,
        closer: (String) -> Unit,
        reason: String,
    ): Int =
        disconnectClientWithin(
            id = id,
            reason = reason,
            perAttachedDeviceMs = DEVICE_SESSION_CLOSE_TIMEOUT_MS,
            totalTimeoutMs = null,
            expectedObserveOwner = token,
            completedObserveCloser = closer,
        )

    /**
     * Disconnects the client and detaches every owned device. Idempotent. Returns the number
     * detached (or attempted), preserving the explicit-Disconnect contract. Each cleanup is
     * bounded by [perAttachedDeviceMs] inside NonCancellable so one stuck cleanup cannot prevent
     * the others; failures and timeouts are logged with quarantine detail and counted, not thrown.
     */
    suspend fun disconnectClient(
        id: String,
        reason: String,
        perAttachedDeviceMs: Long = DEVICE_SESSION_CLOSE_TIMEOUT_MS,
    ): Int = disconnectClientWithin(id, reason, perAttachedDeviceMs, totalTimeoutMs = null)

    /**
     * Internal seam for the connection-level exhaustion branch: production calls this via
     * [disconnectClient] (no total deadline) and [close] (connection share of the shutdown
     * budget). Tests drive it directly with `totalTimeoutMs = 0` for an already-exhausted
     * connection-local deadline without relying on wall-clock expiry.
     */
    internal suspend fun disconnectClientWithin(
        id: String,
        reason: String,
        perAttachedDeviceMs: Long,
        totalTimeoutMs: Long?,
        expectedObserveOwner: Any? = null,
        completedObserveCloser: ((String) -> Unit)? = null,
    ): Int =
        withContext(NonCancellable) {
            val snapshot =
                synchronized(lifecycleLock) { removeConnectionLocked(id, expectedObserveOwner, completedObserveCloser) }
                    ?: return@withContext 0
            // End Observe promptly once disconnect owns the state transition. Device cleanup may take
            // its full bound and must not keep a dead liveness stream heartbeating meanwhile.
            snapshot.endObserve(reason)
            val deadlineNanos = totalTimeoutMs?.let(::deadlineAfterMs)
            val devices = snapshot.attachedDevices
            // Once the connection's shutdown share is exhausted, the snapshot has already removed
            // every owned device from the authoritative registry. Launch every cleanup without
            // awaiting so N uncooperative devices cannot add N ms.
            if (deadlineNanos != null && remainingMs(deadlineNanos) <= 0L) {
                devices.forEach { launchDetachedCleanup(it.deviceSession, shutdownAttachedDeviceMs, "attached device ${it.id}") }
                config.log("connection $id shutdown budget exhausted; ${devices.size} attached device(s) detached with cleanup launched")
                return@withContext devices.size
            }
            // Devices are independent (one lock each), so they close concurrently: disconnect takes
            // one device's bound, not the sum.
            val timeoutMs = deadlineNanos?.let { minOf(perAttachedDeviceMs, remainingMs(it).coerceAtLeast(1L)) } ?: perAttachedDeviceMs
            coroutineScope {
                devices.map { attachedDevice ->
                    async {
                        val detail = closeDeviceBounded(attachedDevice.deviceSession, timeoutMs, "attached device ${attachedDevice.id}")
                        config.log("attached device ${attachedDevice.id} detached" + (detail?.let { " (quarantined: $it)" } ?: ""))
                    }
                }.awaitAll()
            }
            config.log("connection $id disconnected ($reason): attachedDevices=${devices.size}")
            return@withContext devices.size
        }

    private data class Snapshot(
        val attachedDevices: List<AttachedDevice>,
        val hooks: List<(String) -> Unit>,
    ) {
        fun endObserve(reason: String) = hooks.forEach { hook -> runCatching { hook(reason) } }
    }

    /**
     * The one connection teardown transition, called with [lifecycleLock] held: removes the
     * connection and every device it owns from the registries and takes its Observe hooks. Null
     * when the connection is gone, already closed, or (with [expectedObserveOwner]) observed by
     * another stream. Slow device cleanup happens after the lock is released.
     */
    private fun removeConnectionLocked(
        id: String,
        expectedObserveOwner: Any? = null,
        completedObserveCloser: ((String) -> Unit)? = null,
    ): Snapshot? {
        val connection = clientConnectionsById[id] ?: return null
        if (connection.closed) return null
        if (expectedObserveOwner != null && connection.observeOwner !== expectedObserveOwner) return null
        check(clientConnectionsById.remove(id, connection))
        connection.closed = true
        // AttachedDevice.ownerConnectionId is the sole ownership index.
        val owned = detachOwnedAttachedDevices(connection.id)
        completedObserveCloser?.let { connection.onDisconnect.remove(it) }
        val hooks = connection.onDisconnect.toList()
        connection.onDisconnect.clear()
        connection.observeOwner = null
        return Snapshot(owned, hooks)
    }

    /** Called with [lifecycleLock] held; scans and removes every device owned by [connectionId]. */
    private fun detachOwnedAttachedDevices(connectionId: String): List<AttachedDevice> {
        val owned = attachedDevicesById.values.filter { it.ownerConnectionId == connectionId }
        owned.forEach { attachedDevicesById.remove(it.id) }
        return owned
    }

    /**
     * Returns null on clean cleanup, otherwise the quarantine detail (failure message or timeout).
     * The close runs in an independent daemon scope because the production [DeviceSession.close]
     * is deliberately NonCancellable. Merely wrapping it in `withTimeoutOrNull` would therefore
     * not bound this caller. On a daemon-side timeout the core close keeps running under its own
     * deadline so it can still journal quarantine and release the lease, while shutdown proceeds
     * to later attached devices.
     */
    private suspend fun closeDeviceBounded(
        device: DaemonDeviceSession,
        timeoutMs: Long,
        label: String,
    ): String? {
        // A session already poisoned by an uncertain reap still closes (the lease must release),
        // but its detach is not clean.
        val poison = runCatching { device.checkUsable() }.exceptionOrNull()
        // The core close gets a slightly shorter deadline than this wait, so a close that honours
        // its own deadline is observed finishing instead of racing the outer timeout.
        val coreTimeoutMs = (timeoutMs - minOf(CLOSE_TIMEOUT_MARGIN_MS, timeoutMs / 10)).coerceAtLeast(1L)
        val close = cleanupScope.async { runCatching { device.close(coreTimeoutMs) } }
        val outcome = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { close.await() }
        if (outcome == null) {
            // Returned, not logged: the caller logs the detach once with this detail.
            val timeoutDetail = "SESSION_CLEANUP_TIMEOUT: $label exceeded ${timeoutMs}ms; cleanup continues under its core deadline"
            close.invokeOnCompletion { error ->
                if (error != null) config.log("$label eventual cleanup failed: ${error.message}")
            }
            return timeoutDetail
        }
        return outcome.exceptionOrNull()?.let { it.message ?: it.toString() } ?: poison?.let { it.message ?: it.toString() }
    }

    /**
     * Fire-and-forget device cleanup for exhausted shutdown: state is already detached.
     * Best effort: process exit may cut it short; the next attachment recovers a non-terminal journal.
     */
    private fun launchDetachedCleanup(
        device: DaemonDeviceSession,
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
        val listed = config.adb.deviceStates()
        val live = synchronized(lifecycleLock) { attachedDevicesById.values.toList() }
        return listed.map { device ->
            val serial = device.serial
            if (device.state != AdbDeviceState.ONLINE) return@map DeviceEntry(serial, DeviceStatus.Unavailable(device.state, device.rawState))
            val store = SessionJournalStore(config.journalRoot, serial)
            val status =
                quarantine(store)?.let { DeviceStatus.Quarantined(it) }
                    ?: live.firstOrNull { it.deviceSession.serial == serial }?.let { DeviceStatus.Held(it.ownerConnectionId) }
                    ?: if (store.isLeased()) DeviceStatus.Held(null) else DeviceStatus.Free
            DeviceEntry(serial, status)
        }
    }

    /** The quarantine reason, or null when usable. An unreadable journal is not "free": attach would fail on it. */
    private fun quarantine(store: SessionJournalStore): String? =
        runCatching {
            store
                .read()
                ?.takeIf { it.state == JournalState.QUARANTINED }
                ?.let { it.quarantineReason ?: "QUARANTINED" }
        }.getOrElse { "journal unreadable: ${it.message ?: it}" }

    // ---- attached devices --------------------------------------------------------------------

    data class AttachDeviceOptions(
        val skipDriverInstall: Boolean,
        val syncAuthority: String?,
        val defaultTimeoutMs: Long,
        /** How long [attachDevice] may wait for another DeviceSession's lock on the serial. */
        val leaseTimeoutMs: Long,
    )

    suspend fun attachDevice(
        ownerConnectionId: String,
        serial: String,
        autPackage: String,
        options: AttachDeviceOptions,
    ): AttachedDevice {
        synchronized(lifecycleLock) {
            clientConnectionsById[ownerConnectionId]?.takeUnless { it.closed }
                ?: throw UnknownClientConnectionException(ownerConnectionId)
        }
        markInUse(ownerConnectionId)
        val useBundled = !options.skipDriverInstall && config.driver != null
        // The install cache is only a shortcut: a device whose driver is missing or of another
        // build (another daemon, a reused emulator, a manual uninstall) is reinstalled. The test
        // package carries no version, so a stale one is caught by the handshake's build-id check.
        if (useBundled && config.driver?.bundled == true) {
            val installed = runCatching { config.adb.installedPackage(serial, DRIVER_PACKAGE) }.getOrNull()
            if (installed?.versionName != DRIVER_APK_BUILD_ID) synchronized(lifecycleLock) { driverInstalled.remove(serial) }
        }
        // The bundled driver goes on each device once per daemon lifetime. Installation is
        // decided under the serial lock, so racing attachments cannot split installation from
        // the first driver start.
        var installedBundled = false
        val log = DriverLogBuffer()
        val deviceConfig =
            DeviceSessionConfig(
                serial = serial,
                autPackage = autPackage,
                driverApk = config.driver?.driverApk?.takeIf { useBundled },
                driverTestApk = config.driver?.driverTestApk?.takeIf { useBundled },
                installDriver = {
                    if (!useBundled) {
                        true
                    } else {
                        synchronized(lifecycleLock) { driverInstalled.add(serial) }.also { installedBundled = it }
                    }
                },
                syncAuthority = options.syncAuthority ?: "$autPackage.tap-sync",
                journalRoot = config.journalRoot,
                adb = config.adb,
                driverLog = log::append,
                leaseTimeoutMs = options.leaseTimeoutMs,
            )
        val device: DaemonDeviceSession =
            try {
                opener.open(deviceConfig)
            } catch (error: Throwable) {
                // A build mismatch means the cached install is wrong: the next attach reinstalls.
                if (installedBundled || error is DriverBuildMismatchException) synchronized(lifecycleLock) { driverInstalled.remove(serial) }
                throw error
            }
        // Registration is one lifecycle transaction. If disconnect wins while attachment is
        // suspended, the DeviceSession is closed before exposure: no orphan lease.
        val registered: AttachedDevice? =
            synchronized(lifecycleLock) {
                val owner = clientConnectionsById[ownerConnectionId]?.takeUnless { it.closed }
                if (owner == null) {
                    null
                } else {
                    val attachedDevice =
                        AttachedDevice(UUID.randomUUID().toString(), ownerConnectionId, device, options.defaultTimeoutMs, log, owner.events)
                    attachedDevicesById[attachedDevice.id] = attachedDevice
                    attachedDevice
                }
            }
        if (registered == null) {
            withContext(NonCancellable) {
                val detail = closeDeviceBounded(device, DEVICE_SESSION_CLOSE_TIMEOUT_MS, "orphaned attachment on $serial")
                if (installedBundled) synchronized(lifecycleLock) { driverInstalled.remove(serial) }
                config.log("orphaned attachment on $serial cleaned before exposure" + (detail?.let { " (quarantined: $it)" } ?: ""))
            }
            throw UnknownClientConnectionException(ownerConnectionId)
        }
        config.log(
            "attached device ${registered.id} attached on $serial (generation ${device.generation}) for client connection $ownerConnectionId",
        )
        return registered
    }

    /**
     * Returns the attached device, rejecting use after sticky reap quarantine so a poisoned device
     * cannot be driven through a direct command path that bypasses [AppLifecycle].
     */
    suspend fun attachedDevice(
        id: String,
        clientConnectionId: String,
    ): AttachedDevice {
        val found = synchronized(lifecycleLock) { attachedDevicesById[id] } ?: throw UnknownAttachedDeviceException(id)
        if (found.ownerConnectionId != clientConnectionId) throw NotOwnerException(id, clientConnectionId)
        found.deviceSession.checkUsable()
        markInUse(clientConnectionId)
        return found
    }

    /**
     * Returns null when cleanup was clean, otherwise the quarantine detail. Removal from the
     * registry is one transaction, so cleanup happens exactly once even when explicit Detach
     * races connection teardown; the device close itself is bounded inside NonCancellable.
     */
    suspend fun detachDevice(
        id: String,
        clientConnectionId: String,
        timeoutMs: Long = DEVICE_SESSION_CLOSE_TIMEOUT_MS,
    ): String? =
        withContext(NonCancellable) {
            val attachedDevice =
                synchronized(lifecycleLock) {
                    val found = attachedDevicesById[id] ?: throw UnknownAttachedDeviceException(id)
                    if (found.ownerConnectionId != clientConnectionId) throw NotOwnerException(id, clientConnectionId)
                    attachedDevicesById.remove(id)
                } ?: throw UnknownAttachedDeviceException(id)
            markInUse(clientConnectionId)
            val detail = closeDeviceBounded(attachedDevice.deviceSession, timeoutMs, "attached device $id")
            config.log("attached device $id detached" + (detail?.let { " (quarantined: $it)" } ?: ""))
            return@withContext detail
        }

    /**
     * Bounded shutdown: every connection is attempted within [timeoutMs]. Remaining time is
     * propagated to each connection disconnect and attached-device detach as effective-timeout children inside
     * NonCancellable boundaries; a stuck cleanup is quarantined and the next one is still
     * attempted. Once the shared deadline is exhausted, all remaining connections and attached devices
     * are atomically detached, every remaining device cleanup is launched on the owned cleanup
     * scope without serially awaiting any of them, and this function returns immediately — so
     * `close(timeoutMs)` plus the server's `awaitTermination` on the remaining hook budget (see
     * `TapDaemonMain`) cannot exceed the advertised hook budget beyond scheduling overhead. Detached
     * cleanups are best effort with their own core deadline and may be cut short by process exit;
     * the next attachment recovers a non-terminal journal. Never throws. Connections or attachments
     * attempted during shutdown fail (`DaemonClosingException` / `UnknownClientConnectionException`)
     * and never escape teardown: `closing` is set before the first close, and registration loses to
     * the detached state so orphans are closed before exposure.
     */
    suspend fun close(timeoutMs: Long = shutdownTotalMs) {
        withContext(NonCancellable) {
            val deadlineNanos = deadlineAfterMs(timeoutMs)
            val ids =
                synchronized(lifecycleLock) {
                    closing = true
                    clientConnectionsById.keys.toList()
                }
            ids.forEachIndexed { index, id ->
                val remainingConnections = ids.size - index
                val remainingMs = remainingMs(deadlineNanos)
                if (remainingMs <= 0L) {
                    // Strict bound: detach everything still registered in one transaction, launch
                    // every remaining cleanup without awaiting, and return immediately. The old code
                    // logged "may remain" here and left later connections registered.
                    val detached =
                        synchronized(lifecycleLock) { ids.subList(index, ids.size).mapNotNull { removeConnectionLocked(it) } }
                    detached.forEach { it.endObserve("daemon shutdown") }
                    val detachedDevices = detached.flatMap { it.attachedDevices }
                    detachedDevices.forEach { attachedDevice ->
                        launchDetachedCleanup(
                            attachedDevice.deviceSession,
                            shutdownAttachedDeviceMs,
                            "attached device ${attachedDevice.id}",
                        )
                    }
                    config.log(
                        "daemon shutdown budget ${timeoutMs}ms exhausted; " +
                            "${detached.size} connection(s), ${detachedDevices.size} attached device(s) " +
                            "detached with cleanup launched",
                    )
                    return@withContext
                }
                // Reserve a share for every later connection. disconnectClient applies the same
                // rule to its attached devices, so one stuck cleanup never consumes all remaining time.
                val connectionBudgetMs = (remainingMs / remainingConnections).coerceAtLeast(1L)
                runCatching {
                    disconnectClientWithin(
                        id,
                        "daemon shutdown",
                        perAttachedDeviceMs = minOf(shutdownAttachedDeviceMs, connectionBudgetMs),
                        totalTimeoutMs = connectionBudgetMs,
                    )
                }.onFailure { config.log("connection $id shutdown failed: ${it.message}") }
            }
        }
    }

    // ---- test hooks --------------------------------------------------------------------------

    internal fun attachedDeviceIds(): Set<String> = synchronized(lifecycleLock) { attachedDevicesById.keys.toSet() }

    internal fun attachedDeviceIdsForConnection(id: String): Set<String>? =
        synchronized(lifecycleLock) {
            clientConnectionsById[id]?.let {
                attachedDevicesById.values
                    .filter { device -> device.ownerConnectionId == id }
                    .mapTo(mutableSetOf()) { it.id }
            }
        }

    internal fun clientConnectionExists(id: String): Boolean = synchronized(lifecycleLock) { clientConnectionsById.containsKey(id) }

    internal fun observeOwnerPresent(id: String): Boolean = synchronized(lifecycleLock) { clientConnectionsById[id]?.observeOwner != null }

    internal fun onDisconnectCount(id: String): Int = synchronized(lifecycleLock) { clientConnectionsById[id]?.onDisconnect?.size ?: 0 }
}
