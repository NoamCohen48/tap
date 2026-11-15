package io.github.noamcohen48.tap.host

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.protocol.MAX_RUNTIME_IDLE_MS
import io.github.noamcohen48.tap.protocol.MIN_RUNTIME_IDLE_MS
import io.github.noamcohen48.tap.wire.v1.ParkRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** User-requested start policy; neither policy ever resumes an old test session. */
enum class DriverStart { AUTO, COLD }

internal class IdleDriverRuntime(
    val record: SessionJournal,
    val running: RunningInstrumentation,
    val next: ParkRuntime,
    val expiresNanos: Long,
    val rotationBaseline: List<SavedState>,
) {
    fun matches(record: SessionJournal?): Boolean =
        record != null && record.runtimeIdle &&
            record.state == JournalState.CLOSED && record.serial == this.record.serial &&
            record.generation == this.record.generation && record.sessionId == this.record.sessionId &&
            record.bootId == this.record.bootId && record.driverInstanceId == this.record.driverInstanceId &&
            record.driverPid == this.record.driverPid && record.driverStartToken == this.record.driverStartToken &&
            record.hostPort == this.record.hostPort && record.devicePort == this.record.devicePort

    override fun toString(): String = "IdleDriverRuntime(${record.serial}, generation=${record.generation})"
}

/**
 * Daemon-local optimization, not a device pool or lease. All device-side verification and
 * mutation, including eviction, happens while holding the same per-serial journal lock as open.
 * Secrets exist only in memory; another daemon takes the ordinary cold recovery path.
 */
class DriverRuntimeCache(
    private val adb: Adb,
    private val journalRoot: Path,
    val idleTimeoutMs: Long,
    private val log: (String) -> Unit = {},
) {
    init {
        require(idleTimeoutMs in MIN_RUNTIME_IDLE_MS..MAX_RUNTIME_IDLE_MS)
    }

    private val entries = ConcurrentHashMap<String, IdleDriverRuntime>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Any()
    private var closed = false

    internal fun nextSession(generation: Long): ParkRuntime =
        ParkRuntime
            .newBuilder()
            .setNextSessionId(UUID.randomUUID().toString())
            .setNextGeneration(Math.addExact(generation, 1))
            .setNextSecret(ByteString.copyFrom(ByteArray(32).also(SecureRandom()::nextBytes)))
            .setIdleTimeoutMs(idleTimeoutMs)
            .build()

    internal fun take(serial: String): IdleDriverRuntime? = synchronized(lifecycle) { entries.remove(serial) }

    internal fun offer(entry: IdleDriverRuntime): Boolean =
        synchronized(lifecycle) {
            if (closed || System.nanoTime() >= entry.expiresNanos) return false
            check(entries.putIfAbsent(entry.record.serial, entry) == null) { "An idle runtime already exists" }
            entry.running.logSink.set({})
            scope.launch {
                // Restoration already consumed part of the parked runtime's absolute lifetime.
                delay(((entry.expiresNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L))
                while (entries[entry.record.serial] === entry) {
                    if (evict(entry)) break
                    delay(200)
                }
            }
            true
        }

    /** Caller holds the serial lock. Identity is checked before any warm authentication. */
    internal suspend fun verify(
        entry: IdleDriverRuntime,
        current: SessionJournal?,
        bootId: String,
    ): Boolean {
        if (!entry.matches(current) || entry.record.bootId != bootId ||
            System.nanoTime() >= entry.expiresNanos || !entry.running.process.isAlive
        ) {
            return false
        }
        // The platform connection retains its first frozen-rotation snapshot. A different
        // baseline must get a new process, not a reconnect that can restore a stale snapshot.
        val pid = entry.record.driverPid ?: return false
        val observed = adb.runtimeObservation(entry.record.serial, pid, entry.rotationBaseline.map { it.key })
        if (observed.rotation.size != entry.rotationBaseline.size) return false
        for ((saved, actual) in entry.rotationBaseline.zip(observed.rotation)) {
            if (saved.key != actual.key || !StateKey.holds(saved.key, saved.value, actual.value)) return false
        }
        if (observed.process != Adb.ProcessStat.Live(entry.record.driverStartToken!!)) return false
        return adb.forwards(entry.record.serial).any {
            it.serial == entry.record.serial && it.hostPort == entry.record.hostPort && it.devicePort == entry.record.devicePort
        }
    }

    private suspend fun evict(
        entry: IdleDriverRuntime,
        budgetMs: Long = 5_000,
    ): Boolean {
        val serial = entry.record.serial
        val store = SessionJournalStore(journalRoot, serial)
        val lease =
            try {
                store.acquireLease(0)
            } catch (_: DeviceBusyException) {
                return false
            }
        return withContext(NonCancellable) {
            try {
                val finished =
                    kotlinx.coroutines.withTimeoutOrNull(budgetMs.coerceAtLeast(1)) {
                        if (!entries.remove(serial, entry)) return@withTimeoutOrNull true
                        val current = store.read()
                        if (entry.matches(current)) {
                            try {
                                val pid = entry.record.driverPid!!
                                // A stale timer must not kill a new process, even if its PID was reused.
                                if (adb.bootId(serial) == entry.record.bootId &&
                                    adb.processStat(serial, pid) == Adb.ProcessStat.Live(entry.record.driverStartToken!!)
                                ) {
                                    forceStopDriverAndVerify(adb, serial, pid, entry.record.driverStartToken)
                                }
                                val forward =
                                    adb.forwards(serial).any {
                                        it.serial == serial && it.hostPort == entry.record.hostPort &&
                                            it.devicePort == entry.record.devicePort
                                    }
                                if (forward) adb.removeForward(serial, entry.record.hostPort!!)
                                store.write(current!!.copy(runtimeIdle = false, updatedAtEpochMs = System.currentTimeMillis()))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                store.write(
                                    current!!.copy(
                                        state = JournalState.QUARANTINED,
                                        runtimeIdle = false,
                                        quarantineReason = "RUNTIME_CLEANUP_UNCERTAIN: ${failure.message}",
                                    ),
                                )
                                throw failure
                            }
                        }
                        cleanupInstrumentationProcess(serial, entry.running)
                        true
                    }
                if (finished == null) {
                    val current = store.read()
                    if (entry.matches(current)) {
                        store.write(
                            current!!.copy(
                                state = JournalState.QUARANTINED,
                                runtimeIdle = false,
                                quarantineReason = "RUNTIME_CLEANUP_TIMEOUT",
                            ),
                        )
                    }
                }
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                runCatching { log("idle runtime cleanup failed on $serial: ${failure.javaClass.simpleName}") }
                true
            } finally {
                lease.close()
            }
        }
    }

    /** Prevent active sessions from caching themselves while the daemon is shutting down. */
    fun stopAccepting() {
        synchronized(lifecycle) { closed = true }
    }

    /** Called after session shutdown. Busy serials belong to someone else and are never touched. */
    suspend fun close(timeoutMs: Long = 5_000) {
        stopAccepting()
        kotlinx.coroutines.coroutineScope {
            entries.values
                .toList()
                .map { entry -> launch { evict(entry, timeoutMs) } }
                .forEach { it.join() }
        }
        scope.cancel()
        // The driver itself also enforces an absolute idle deadline if host cleanup cannot run.
    }
}
