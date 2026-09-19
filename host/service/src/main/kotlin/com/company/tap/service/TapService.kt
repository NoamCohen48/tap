package com.company.tap.service

import com.company.tap.api.v1.DeviceConstraints
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
import com.google.protobuf.TextFormat
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
class AcquireTimeoutException(message: String) : RuntimeException(message)

/** Facts gathered once per serial; the emulator flag and API level drive constraints. */
data class Facts(val serial: String, val apiLevel: Int, val manufacturer: String, val model: String, val emulator: Boolean)

sealed class DeviceStatus {
    object Free : DeviceStatus()
    data class Leased(val runId: String, val role: String) : DeviceStatus()
    data class Quarantined(val reason: String) : DeviceStatus()
}

data class PoolEntry(val facts: Facts, val status: DeviceStatus)

class Run(val id: String, val name: String) {
    val sessions = ConcurrentHashMap<String, ManagedSession>()
    /** serial → role */
    val leases = ConcurrentHashMap<String, String>()
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
 * All service state: runs (client ownership), the machine-wide pool, and live sessions. The
 * gRPC servicers are thin adapters over this class so it can be exercised without a server.
 */
class TapService(val config: ServiceConfig) : AutoCloseable {
    private val runs = ConcurrentHashMap<String, Run>()
    private val sessions = ConcurrentHashMap<String, ManagedSession>()
    private val poolLock = ReentrantLock()
    private val poolChanged = poolLock.newCondition()
    private val leases = mutableMapOf<String, DeviceStatus.Leased>()
    private val facts = ConcurrentHashMap<String, Facts>()
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

    /** Closes every session and lease of the run. Idempotent. Returns (sessions, devices). */
    fun closeRun(id: String, reason: String): Pair<Int, Int> {
        val run = runs.remove(id) ?: return 0 to 0
        if (run.closed) return 0 to 0
        run.closed = true
        var closedSessions = 0
        run.sessions.keys.toList().forEach { sessionId ->
            runCatching { closeSession(sessionId) }.onFailure { config.log("session $sessionId close failed: ${it.message}") }
            closedSessions++
        }
        val released = release(run, emptyList())
        run.onClose.forEach { runCatching(it) }
        config.log("run $id closed ($reason): sessions=$closedSessions devices=$released")
        return closedSessions to released
    }

    // ---- pool --------------------------------------------------------------------------------

    fun inventory(): List<PoolEntry> {
        val online = config.adb.devices().filter { config.allowedSerials?.contains(it) ?: true }
        return poolLock.withLock {
            online.map { serial ->
                val status = leases[serial]
                    ?: quarantine(serial)?.let { DeviceStatus.Quarantined(it) }
                    ?: DeviceStatus.Free
                PoolEntry(factsOf(serial), status)
            }
        }
    }

    /**
     * All-or-none assignment of [roles] (role → constraints) for [run]. Waits until every role
     * can be satisfied simultaneously or the deadline passes.
     */
    fun acquire(run: Run, roles: List<Pair<String, DeviceConstraints>>, timeoutMs: Long): Map<String, Facts> {
        require(roles.isNotEmpty()) { "at least one role is required" }
        require(roles.map { it.first }.distinct().size == roles.size) { "roles must be unique: ${roles.map { it.first }}" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        var lastReason = ""
        while (true) {
            val candidates = inventory().filter { it.status == DeviceStatus.Free }.map { it.facts }
            poolLock.withLock {
                if (run.closed) throw UnknownRunException(run.id)
                val assignment = assign(roles, candidates.filter { it.serial !in leases })
                if (assignment != null) {
                    assignment.forEach { (role, device) ->
                        val lease = DeviceStatus.Leased(run.id, role)
                        leases[device.serial] = lease
                        run.leases[device.serial] = role
                    }
                    config.log("run ${run.id} acquired ${assignment.mapValues { it.value.serial }}")
                    return assignment
                }
                lastReason = "free=${candidates.map { it.serial }} leased=${leases.keys}"
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    val wanted = roles.joinToString { (role, c) -> "$role=${TextFormat.shortDebugString(c).ifEmpty { "any" }}" }
                    config.log("run ${run.id} acquire timed out after ${timeoutMs}ms: roles {$wanted} $lastReason")
                    throw AcquireTimeoutException("Timed out after ${timeoutMs}ms acquiring roles ${roles.map { it.first }} ($lastReason)")
                }
                poolChanged.await(minOf(remaining, TimeUnit.SECONDS.toNanos(2)), TimeUnit.NANOSECONDS)
            }
        }
    }

    fun release(run: Run, serials: List<String>): Int = poolLock.withLock {
        val toRelease = if (serials.isEmpty()) run.leases.keys.toList() else serials.filter { run.leases.containsKey(it) }
        toRelease.forEach { serial ->
            require(run.sessions.values.none { it.device.serial == serial }) { "close the session on $serial before releasing it" }
            leases.remove(serial)
            run.leases.remove(serial)
        }
        poolChanged.signalAll()
        toRelease.size
    }

    private fun assign(roles: List<Pair<String, DeviceConstraints>>, free: List<Facts>): Map<String, Facts>? {
        // Pinned and most-constrained roles first so a generic role cannot steal the only match.
        val ordered = roles.sortedByDescending { (_, c) -> constraintWeight(c) }
        val remaining = free.toMutableList()
        val result = linkedMapOf<String, Facts>()
        for ((role, constraints) in ordered) {
            val pick = remaining.firstOrNull { satisfies(it, constraints) } ?: return null
            remaining.remove(pick)
            result[role] = pick
        }
        return roles.associate { (role, _) -> role to result.getValue(role) }
    }

    private fun constraintWeight(c: DeviceConstraints): Int =
        (if (c.hasSerial()) 100 else 0) + listOf(c.hasMinApi(), c.hasMaxApi(), c.hasEmulator(), c.hasModelContains()).count { it }

    private fun satisfies(facts: Facts, c: DeviceConstraints): Boolean =
        (!c.hasSerial() || c.serial == facts.serial) &&
            (!c.hasMinApi() || facts.apiLevel >= c.minApi) &&
            (!c.hasMaxApi() || facts.apiLevel <= c.maxApi) &&
            (!c.hasEmulator() || facts.emulator == c.emulator) &&
            (!c.hasModelContains() || facts.model.contains(c.modelContains, ignoreCase = true))

    private fun factsOf(serial: String): Facts = facts.getOrPut(serial) {
        val adb = config.adb
        fun prop(name: String) = runCatching { adb.run(serial, "shell", "getprop", name).trim() }.getOrDefault("")
        Facts(
            serial = serial,
            apiLevel = prop("ro.build.version.sdk").toIntOrNull() ?: 0,
            manufacturer = prop("ro.product.manufacturer"),
            model = prop("ro.product.model"),
            emulator = serial.startsWith("emulator-") || prop("ro.kernel.qemu") == "1" || prop("ro.boot.qemu") == "1",
        )
    }

    private fun quarantine(serial: String): String? = runCatching {
        SessionJournalStore(config.journalRoot, serial).read()
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
    )

    fun openSession(run: Run, serial: String, autPackage: String, options: OpenSessionOptions): ManagedSession {
        require((run.leases as Map<String, String>).containsKey(serial)) { "run ${run.id} does not hold a lease on $serial; acquire it first" }
        require(run.sessions.values.none { it.device.serial == serial }) { "run ${run.id} already has a session on $serial" }
        val explicitApks = options.driverApk != null || options.driverTestApk != null
        val installBundled = !explicitApks && !options.skipDriverInstall && config.bundledDriver != null && driverInstalled.add(serial)
        val log = RingLog()
        val device = try {
            DeviceSession.open(
                DeviceSessionConfig(
                    serial = serial,
                    autPackage = autPackage,
                    driverApk = options.driverApk ?: config.bundledDriver?.driverApk?.takeIf { installBundled },
                    driverTestApk = options.driverTestApk ?: config.bundledDriver?.driverTestApk?.takeIf { installBundled },
                    syncAuthority = options.syncAuthority ?: "$autPackage.tap-sync",
                    allowedSystemPackages = options.allowedSystemPackages.ifEmpty { setOf(PERMISSION_CONTROLLER_PACKAGE) },
                    journalRoot = config.journalRoot,
                    adb = config.adb,
                    driverLog = log::append,
                ),
            )
        } catch (error: Throwable) {
            if (installBundled) driverInstalled.remove(serial)
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
