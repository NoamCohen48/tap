package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.Requests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A frozen UiAutomation snapshot stores physical rotation, which can differ from USER_ROTATION
 * when the foreground app requests an orientation. Only an explicitly unfrozen, complete
 * baseline can use readback alone after disconnect; frozen/unknown modes need full restore. */
internal fun canUseReadOnlyRotationRestore(
    baseline: List<SavedState>,
    rotationTouched: Boolean,
): Boolean =
    !rotationTouched && baseline.size == StateKey.ROTATION.size &&
        baseline.map { it.key }.toSet() == StateKey.ROTATION.toSet() &&
        baseline.single { it.key == StateKey.ACCELEROMETER_ROTATION }.value == "1"

/** Everything needed to bring up one driver session on one device. */
data class DeviceSessionConfig(
    val serial: String,
    /** Driver APKs to install before starting; null skips installation (already installed). */
    val driverApk: Path? = null,
    val driverTestApk: Path? = null,
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
     * Attach even when the device is quarantined, to inspect (and fix) it: the session works as
     * usual, but it starts with the quarantine's unrestored saved state and the device goes back
     * to QUARANTINED when it closes ([DeviceSession.inspectedQuarantine]). A quarantine that waits
     * for a reboot reset (`resetRequired`) is still refused.
     */
    val inspectQuarantined: Boolean = false,
    /**
     * Decides, once the per-serial lock is held, whether [driverApk]/[driverTestApk] are installed
     * for this open. Callers that skip an install they consider current (the daemon compares the
     * installed APKs' digests) must decide here, not before the lock: another open of the serial
     * could otherwise install a different build between the check and this session's driver start.
     */
    val installDriver: suspend () -> Boolean = { true },
    /** Receives duration-only lifecycle diagnostics; never authentication or command payloads. */
    val lifecycleLog: (String) -> Unit = {},
    /** Optional daemon-local cache; null retains the original per-session process lifecycle. */
    val runtimeCache: DriverRuntimeCache? = null,
    val driverStart: DriverStart = DriverStart.AUTO,
) {
    // Fault hooks and startup overrides must never leak into another session.
    internal val allowsRuntimeReuse: Boolean get() =
        runtimeCache != null && driverStart == DriverStart.AUTO &&
            !inspectQuarantined && driverArguments.isEmpty()
}

/**
 * One disposable session unit: device lease, journal, driver instrumentation, ADB forward,
 * authenticated [client], and session generation. [open] either returns a `READY` session or
 * cleans up everything it created; [close] releases in reverse and journals `CLOSED`,
 * `BROKEN` when cleanup was clean but the driver connection had been poisoned (a mutation may
 * have been left half-applied, so the next open runs full journal recovery), or `QUARANTINED`
 * when cleanup could not be proven. A lost or poisoned client is not repaired in place: close
 * this session and open a new one (the generation advances).
 */
class DeviceSession private constructor(
    internal val config: DeviceSessionConfig,
    private val lease: AutoCloseable,
    private val store: SessionJournalStore,
    private var journal: SessionJournal,
    private val running: RunningInstrumentation,
    private val hostPort: Int,
    val client: DriverClient,
    private val hooks: SessionHooks,
    /** Fresh session, but the verified instrumentation process was retained from the last one. */
    val driverReused: Boolean = false,
    private val runtimeRotationBaseline: List<SavedState> = emptyList(),
) {
    val serial: String get() = config.serial
    val generation: Long get() = journal.generation
    val sessionId: String get() = journal.sessionId

    /** The raw runner. Internal: cross-module users go through typed [AppLifecycle] operations. */
    internal val adb: Adb get() = config.adb

    private val timings = SessionLifecycleTimings(config.serial, config.lifecycleLog)
    private val apps = ConcurrentHashMap<String, AppLifecycle>()

    /** Serialize explicit app lifecycle changes with stopped-app private-data operations. */
    internal val appDataMutex = Mutex()

    /** Device-wide conditions (animations, dark mode, font scale, density) on this session. */
    val conditions: DeviceConditions = DeviceConditions(this)

    /** Files pushed to and pulled from the device, and media added to its gallery. */
    val files: DeviceFiles = DeviceFiles(this)

    /**
     * The resumed activity on top, as package and fully qualified class name; null when none is
     * resumed (a keyguard, or between activities).
     */
    suspend fun foregroundActivity(): Pair<String, String>? = guardAdb { adb.foregroundActivity(serial) }

    /** Guards [savedState] and the journal writes that persist it. */
    private val savedStateMutex = Mutex()

    // Starts with what a quarantine left unrestored, so detach puts back the values from before it.
    private val savedState = journal.savedState.toMutableList()

    /** Why the device was quarantined when this session attached it to inspect it; null otherwise. */
    val inspectedQuarantine: String? get() = journal.inspectedQuarantine
    private var cachedApiLevel: Int? = null

    private val reapUncertain = AtomicReference<AdbReapUncertainException?>()

    /** Records sticky reap uncertainty; further session/app use is rejected, close quarantines. */
    fun noteReapUncertain(error: AdbReapUncertainException) {
        reapUncertain.compareAndSet(null, error)
    }

    /**
     * Rejects use after sticky reap uncertainty, once close started, or once the driver client
     * is poisoned (its transport is gone; only close and reopen help). Close still runs and
     * journals `QUARANTINED` / `BROKEN` accordingly.
     */
    fun checkUsable() {
        checkAdmissible()
        if (client.isPoisoned) {
            throw SessionUnusableException(
                serial,
                "Driver connection on $serial was lost; close this session and open a new one",
                client.poisonCause,
            )
        }
    }

    /** The session-level half of [checkUsable]: reap uncertainty and closing. ADB work and the
     * client's own admission use it, so a poisoned client still reports its own typed
     * `TRANSPORT_LOST`/`INDETERMINATE` and ADB-only lifecycle work stays possible. */
    private fun checkAdmissible() {
        reapUncertain.get()?.let {
            throw DeviceQuarantinedException(serial, "Session on $serial is quarantined: ${it.message}", it)
        }
        if (closeStarted.get()) throw SessionClosingException(serial)
    }

    /**
     * Session operation lease: [guardAdb] admits under [operationMutex], close marks closing and
     * awaits admitted operations through their poison-recording point before choosing the terminal
     * journal state. Admission after close starts is rejected atomically; the drain is bounded by
     * the close timeout and quarantines on expiry.
     */
    private val operationMutex = Mutex()
    private var operationsClosing = false
    private var inFlightOperations = 0

    /** Runs an ADB block, poisoning this session sticky only when THIS started command left
     * reap uncertainty. A temporary [AdbRunnerGatedException] (never started: another serial owns
     * residual capacity) never poisons. Admission atomically rejects after close starts.
     * Internal: cross-module users go through [AppLifecycle]; the session never hands raw runner
     * access back out. */
    internal suspend fun <T> guardAdb(block: suspend () -> T): T {
        operationMutex.withLock {
            checkAdmissible()
            if (operationsClosing) throw SessionClosingException(serial)
            inFlightOperations++
        }
        try {
            try {
                return block()
            } catch (error: AdbReapUncertainException) {
                noteReapUncertain(error)
                throw error
            }
        } finally {
            // NonCancellable: the caller is typically already cancelled when this runs, and close
            // may own operationMutex at that moment. A cancellable decrement would skip the
            // lease, strand close in a false timeout quarantine, and mask the original
            // cancellation. Never throws, so the primary failure propagates intact.
            withContext(NonCancellable) {
                hooks.beforeOperationRelease()
                operationMutex.withLock { inFlightOperations-- }
            }
        }
    }

    /** Waits for admitted operations through their poison-recording point, bounded by [timeoutMs].
     * Returns null when drained, otherwise the timeout failure that must quarantine. */
    private suspend fun awaitAdmittedOperations(timeoutMs: Long): Throwable? {
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        operationMutex.withLock { operationsClosing = true }
        while (true) {
            val remaining = operationMutex.withLock { inFlightOperations }
            if (remaining <= 0) return null
            if (System.nanoTime() >= deadlineNanos) {
                return DeviceQuarantinedException(
                    serial,
                    "Session cleanup on $serial exceeded ${timeoutMs}ms waiting for $remaining in-flight operation(s); device quarantined",
                )
            }
            hooks.onCloseDrainWait()
            delay(10)
        }
    }

    /**
     * The [AppLifecycle] of [packageName] on this session, one instance per package for the
     * session's lifetime: it carries the sync identity handed out at bootstrap, which must
     * survive across calls for `awaitIdle` to stay guarded against a restarted process.
     */
    fun app(packageName: String): AppLifecycle {
        require(!isDriverPackage(packageName)) { "$packageName is Tap's driver, not an app lifecycle target" }
        checkAdmissible()
        return apps.computeIfAbsent(packageName) { AppLifecycle(this, it) }
    }

    /** The device's API level, read once per session. */
    internal suspend fun apiLevel(): Int = cachedApiLevel ?: guardAdb { adb.apiLevel(serial) }.also { cachedApiLevel = it }

    /** Throws [UnsupportedApiException] below [required]. */
    internal suspend fun requireApi(
        required: Int,
        what: String,
    ) {
        val level = apiLevel()
        if (level < required) throw UnsupportedApiException(serial, required, level, what)
    }

    /**
     * Captures the [keys] this session has not captured yet, as they are now, and journals them
     * before the caller changes anything: the first value is the one detach restores, whatever
     * the session changes in between, and a daemon that dies leaves the record for the next
     * attach.
     */
    internal suspend fun captureBeforeChange(keys: List<String>) {
        savedStateMutex.withLock {
            val missing = keys.filter { key -> savedState.none { it.key == key } }
            if (missing.isEmpty()) return
            guardAdb {
                val captured = missing.map { SavedState(it, adb.readState(serial, it)) }
                savedState += captured
                journal = journal.copy(savedState = savedState.toList(), updatedAtEpochMs = System.currentTimeMillis())
                store.write(journal)
            }
        }
    }

    /** Whether this session has captured [key] (and so restores it on detach). */
    internal suspend fun hasCaptured(key: String): Boolean = savedStateMutex.withLock { savedState.any { it.key == key } }

    /**
     * Captures the keys of [values], writes them and reads them back. A value the device did not
     * take is a [DeviceSettingException]; what was captured is still restored on detach.
     */
    internal suspend fun change(values: Map<String, String?>) {
        captureBeforeChange(values.keys.toList())
        guardAdb {
            values.forEach { (key, value) -> adb.writeState(serial, key, value) }
            // Compared as values: a setting the device reports absent (null) is a mismatch too.
            // Restoring compares the same way (StateKey.holds).
            val wrong =
                values.mapNotNull { (key, value) ->
                    adb.readState(serial, key).let { actual ->
                        "$key=$actual (expected $value)".takeUnless { StateKey.holds(key, value, actual) }
                    }
                }
            if (wrong.isNotEmpty()) {
                throw DeviceSettingException(
                    serial,
                    "The device did not take the change on $serial: ${wrong.joinToString()}",
                )
            }
        }
    }

    /** Restore one teardown phase; retain unproven entries for cleanup/quarantine recovery. */
    @Volatile private var rotationTouched = false

    private suspend fun restoreSavedState(rotation: Boolean = false) {
        savedStateMutex.withLock {
            val entries = savedState.filter { (it.key in StateKey.ROTATION) == rotation }
            if (entries.isEmpty()) return
            if (rotation && canUseReadOnlyRotationRestore(runtimeRotationBaseline, rotationTouched) &&
                adb.rotationSettingsHold(serial, entries)
            ) {
                // No rotation command ran and the matching baseline was unfrozen. Destroy's
                // thaw is already a no-op: do not introduce settings writes (and their SystemUI
                // settle window). A frozen snapshot is never eligible for this fast path.
                savedState.removeAll(entries.toSet())
                return
            }
            adb.restoreState(serial, entries)
            savedState.removeAll(entries.toSet())
        }
    }

    private suspend fun captureRotation() {
        rotationTouched = true
        captureBeforeChange(StateKey.ROTATION)
    }

    private val closeStarted = AtomicBoolean(false)
    private val cancellationCleanupScheduled = AtomicBoolean(false)
    private val cancellationCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Releases in reverse — client, exact forward, instrumentation — restores rotation after
     * instrumentation stops, then finalizes the journal and releases the lease. Other saved
     * state restores before driver shutdown. The whole cleanup runs NonCancellable with an explicit
     * [timeoutMs]: journal finalization and lease release always run, and on timeout or
     * uncertainty the journal records quarantine. Throws the first cleanup failure (or a timeout
     * describing the quarantine); the journal detail is what the server reports.
     */
    suspend fun close(timeoutMs: Long = DEVICE_SESSION_CLOSE_TIMEOUT_MS) {
        withContext(NonCancellable) {
            if (!closeStarted.compareAndSet(false, true)) return@withContext
            var firstFailure: Throwable? = null
            // Whether every failure was a saved-state restore that did not read back: the next
            // attach can then retry it (SessionJournal.restoreRetryable).
            var onlyRestoreFailed = true
            val failed: (Throwable) -> Unit = { error ->
                firstFailure = firstFailure ?: error
                onlyRestoreFailed = false
            }
            val restoreFailed: (Throwable) -> Unit = { error ->
                // An ADB child the restore could not reap may still change the device later.
                if (findReapUncertain(error) != null) failed(error) else firstFailure = firstFailure ?: error
            }
            var clientPoison: Throwable? = null
            var runtimeRetained = false
            val finished =
                withTimeoutOrNull(timeoutMs) {
                    // Admitted operations drain first, through their poison-recording point, so the
                    // CLOSED vs QUARANTINED choice below observes every in-flight uncertainty and no
                    // lease handoff or final CLOSED can race a late poison. The drain shares this
                    // bound and quarantines on expiry.
                    val drainFailure = timings.measure("detach_drain") { awaitAdmittedOperations(timeoutMs) }
                    if (drainFailure != null) failed(drainFailure)
                    // Read before client.close(): a deliberate close never poisons, so this is
                    // exactly "the transport failed while the session was in use".
                    clientPoison = if (client.isPoisoned) client.poisonCause ?: IllegalStateException("poisoned") else null
                    // cleanupStep, not runCatching: a bound firing must reach withTimeoutOrNull
                    // as cancellation (finished == null below), never as a recorded failure.
                    // Before the driver ends: Android force-stops the package when the instrumentation
                    // finishes, and a listener bound then comes back (Adb.releaseDriverNotificationListener).
                    cleanupStep(failed) { timings.measure("detach_listener") { adb.releaseDriverNotificationListener(serial) } }
                    val cache = config.runtimeCache
                    var next: io.github.noamcohen48.tap.wire.v1.ParkRuntime? = null
                    var expires = 0L
                    if (cache != null && config.allowsRuntimeReuse && journal.inspectedQuarantine == null &&
                        firstFailure == null && clientPoison == null && reapUncertain.get() == null
                    ) {
                        try {
                            val prepared = cache.nextSession(generation)
                            expires = System.nanoTime() + cache.idleTimeoutMs * 1_000_000L
                            timings.measure("detach_runtime_reset") { client.parkForReuse(prepared) }
                            next = prepared
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Optional reuse failed before any future-session input. Prove cold
                            // cleanup below; never claim a warm runtime or replay a mutation.
                            runCatching { config.lifecycleLog("driver runtime serial=$serial reuse=refused") }
                        }
                    }
                    cleanupStep(failed) { timings.measure("detach_client") { client.close() } }
                    cleanupStep(restoreFailed) { timings.measure("detach_restore") { restoreSavedState() } }
                    if (next != null && firstFailure == null) {
                        // PARKED proves UiAutomation was destroyed, not merely a socket closed.
                        cleanupStep(restoreFailed) { timings.measure("detach_rotation") { restoreSavedState(rotation = true) } }
                        if (firstFailure == null) {
                            cleanupStep(failed) {
                                val idle = journal.copy(state = JournalState.CLOSED, savedState = emptyList(), runtimeIdle = true)
                                runtimeRetained = cache!!.offer(IdleDriverRuntime(idle, running, next, expires, runtimeRotationBaseline))
                            }
                        }
                    }
                    var instrumentationGone = false
                    if (!runtimeRetained) {
                        cleanupStep(failed) { timings.measure("detach_forward") { adb.removeForward(serial, hostPort) } }
                        cleanupStep(failed) {
                            timings.measure("detach_instrumentation") { cleanupInstrumentation(adb, serial, running) }
                            instrumentationGone = true
                        }
                    }
                    // UiAutomation.disconnect restores Android's own rotation snapshot. A closed
                    // protocol socket does not prove that disconnect has finished. Put our saved
                    // rotation back only after instrumentation is gone; if cleanup fails, leave
                    // these entries journaled instead of claiming restore.
                    if (instrumentationGone) {
                        cleanupStep(
                            restoreFailed,
                        ) { timings.measure("detach_rotation") { restoreSavedState(rotation = true) } }
                    }
                    true
                }
            if (finished == null) {
                failed(DeviceQuarantinedException(serial, "Session cleanup on $serial exceeded ${timeoutMs}ms; device quarantined"))
            }
            val poison = reapUncertain.get()
            val quarantined = firstFailure != null || poison != null
            // Cleanup was proven, but a poisoned client may have left a mutation half-applied:
            // BROKEN makes the next open run full recovery (identity-checked driver stop, forward
            // removal, boot check) instead of trusting a clean CLOSED; it does not block the
            // device the way QUARANTINED does, because nothing about the device is unproven.
            val inspected = journal.inspectedQuarantine
            val state =
                when {
                    quarantined || inspected != null -> JournalState.QUARANTINED
                    clientPoison != null -> JournalState.BROKEN
                    else -> JournalState.CLOSED
                }
            try {
                store.write(
                    journal.copy(
                        state = state,
                        runtimeIdle = runtimeRetained && state == JournalState.CLOSED,
                        // A failed or timed-out restore keeps what is still to put back.
                        savedState = savedState.toList(),
                        restoreRetryable =
                            inspected == null && quarantined && poison == null && onlyRestoreFailed && savedState.isNotEmpty(),
                        // An inspected device stays quarantined for the reason it was, and a new
                        // cleanup failure says both.
                        quarantineReason =
                            (firstFailure ?: poison)?.let {
                                "SESSION_CLEANUP_UNCERTAIN: ${it.message}" +
                                    (inspected?.let { was -> " (quarantined before: $was)" } ?: "")
                            }
                                ?: inspected
                                ?: clientPoison?.let { "DRIVER_CONNECTION_POISONED: ${it.message}" },
                        inspectedQuarantine = null,
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
                hooks.afterCancellationCleanup()
            }
        }
    }

    companion object {
        suspend fun open(config: DeviceSessionConfig): DeviceSession =
            SessionLifecycleTimings(config.serial, config.lifecycleLog).measure("attach_total") {
                try {
                    open(config, SessionHooks.None)
                } catch (warm: WarmDriverAttachException) {
                    // No client ever received this session; only authentication/health ran.
                    try {
                        open(config.copy(driverStart = DriverStart.COLD), SessionHooks.None)
                    } catch (
                        cold: Throwable,
                    ) {
                        cold.addSuppressed(warm.cause ?: warm)
                        throw cold
                    }
                }
            }

        /** [open] with session [hooks]; host/core tests park or observe the race points. */
        internal suspend fun open(
            config: DeviceSessionConfig,
            hooks: SessionHooks,
        ): DeviceSession {
            val adb = config.adb
            val serial = config.serial
            val store = SessionJournalStore(config.journalRoot, serial)
            val timings = SessionLifecycleTimings(serial, config.lifecycleLog)
            val lease = timings.measure("attach_lock") { store.acquireLease(config.leaseTimeoutMs) }
            var leaseOwnedBySession = false
            var observedBootId: String? = null
            var observedPrior: SessionJournal? = null
            var removedRuntime: IdleDriverRuntime? = null
            var warmAttempt = false
            try {
                val bootId = timings.measure("attach_boot") { adb.bootId(serial) }
                observedBootId = bootId
                val candidate = config.runtimeCache?.take(serial).also { removedRuntime = it }
                val recorded = if (candidate != null) runCatching { store.read() }.getOrNull() else null
                observedPrior = recorded
                val hasApks = config.driverApk != null || config.driverTestApk != null
                var installChecked: Boolean? = null
                val warm =
                    if (candidate != null && config.allowsRuntimeReuse &&
                        timings.measure("attach_runtime_verify") { config.runtimeCache!!.verify(candidate, recorded, bootId) }
                    ) {
                        installChecked = if (hasApks) timings.measure("attach_apk_check") { config.installDriver() } else false
                        candidate.takeUnless { installChecked == true }
                    } else {
                        null
                    }
                warmAttempt = warm != null
                val prior =
                    if (warm != null) {
                        recorded
                    } else {
                        timings.measure("attach_recovery") {
                            recoverJournal(adb, serial, bootId, store, inspect = config.inspectQuarantined)?.let {
                                when {
                                    it.state != JournalState.QUARANTINED -> {
                                        restorePriorState(adb, serial, store, it)
                                    }

                                    it.restoreRetryable -> {
                                        try {
                                            retryRestore(adb, serial, store, it)
                                        } catch (still: DeviceQuarantinedException) {
                                            // Still not reading back: inspecting goes ahead with it.
                                            if (!config.inspectQuarantined) throw still
                                            checkNotNull(store.read())
                                        }
                                    }

                                    else -> {
                                        it
                                    } // inspectQuarantined: recoverJournal refuses it otherwise
                                }
                            }
                        }
                    }
                observedPrior = prior
                timings.measure("attach_screen") { adb.wakeAndDismissKeyguard(serial) }
                if (candidate != null && warm == null) {
                    cleanupInstrumentationProcess(serial, candidate.running)
                    removedRuntime = null
                }
                val installNeeded = hasApks && (installChecked ?: timings.measure("attach_apk_check") { config.installDriver() })
                if (installNeeded) {
                    timings.measure("attach_apk_install") {
                        config.driverApk?.let { installDriverPackage(adb, serial, DRIVER_PACKAGE, it) }
                        config.driverTestApk?.let { installDriverPackage(adb, serial, DRIVER_TEST_PACKAGE, it) }
                    }
                }

                val generation = Math.addExact(prior?.generation ?: 0L, 1L)
                val sessionId = warm?.next?.nextSessionId ?: UUID.randomUUID().toString()
                val secret = warm?.next?.nextSecret?.toByteArray() ?: ByteArray(32).also(SecureRandom()::nextBytes)
                check(warm == null || warm.next.nextGeneration == generation) { "Runtime generation did not advance" }
                val baselineRotation =
                    warm?.rotationBaseline
                        ?: if (config.allowsRuntimeReuse) {
                            timings.measure(
                                "attach_rotation_baseline",
                            ) { StateKey.ROTATION.map { SavedState(it, adb.readState(serial, it)) } }
                        } else {
                            emptyList()
                        }
                val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
                var journal =
                    SessionJournal(
                        state = JournalState.CREATING,
                        serial = serial,
                        bootId = bootId,
                        sessionId = sessionId,
                        generation = generation,
                        devicePort = warm?.record?.devicePort ?: DEVICE_PORT,
                        hostPort = warm?.record?.hostPort,
                        driverPid = warm?.record?.driverPid,
                        driverStartToken = warm?.record?.driverStartToken,
                        driverInstanceId = warm?.record?.driverInstanceId,
                        // A quarantine's unrestored state carries over; a retryable one (its retry
                        // just failed) is an ordinary session that restores it, an unproven one an
                        // inspection that goes back to QUARANTINED.
                        savedState = prior?.takeIf { it.state == JournalState.QUARANTINED }?.savedState ?: baselineRotation,
                        inspectedQuarantine =
                            prior
                                ?.takeIf { it.state == JournalState.QUARANTINED && !it.restoreRetryable }
                                ?.let { it.quarantineReason ?: "QUARANTINED" },
                    )
                store.write(journal)

                var running: RunningInstrumentation? = null
                var hostPort: Int? = null
                var client: DriverClient? = null
                var session: DeviceSession? = null
                var transferAttempted = false
                try {
                    val started =
                        warm?.running ?: timings.measure("attach_instrumentation") {
                            startDriverWithRetry(
                                adb = adb,
                                serial = serial,
                                sessionId = sessionId,
                                generation = generation,
                                encodedSecret = encodedSecret,
                                driverArguments =
                                    config.driverArguments +
                                        (
                                            if (config.allowsRuntimeReuse) {
                                                mapOf("tapReusable" to "true")
                                            } else {
                                                emptyMap()
                                            }
                                        ),
                                logSink = config.driverLog,
                                processStarter = config.processStarter,
                            ) { devicePort ->
                                journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
                                store.write(journal)
                            }
                        }
                    running = started
                    removedRuntime = null // The inner startup cleanup now owns this process.
                    started.logSink.set(config.driverLog)
                    hostPort = warm?.record?.hostPort ?: timings.measure("attach_forward") { adb.forward(serial, started.devicePort) }
                    val observation =
                        warm?.let {
                            timings
                                .measure("attach_process_observation") {
                                    adb.processObservation(serial, DRIVER_PACKAGE, checkNotNull(it.record.driverPid))
                                }.also { observed ->
                                    if (!observed.matches(checkNotNull(it.record.driverPid), checkNotNull(it.record.driverStartToken))) {
                                        throw DriverStartException("Driver identity on $serial changed during reusable startup")
                                    }
                                }
                        }
                    val driverPid =
                        observation?.pids?.single() ?: timings.measure("attach_pid") {
                            adb.processIds(serial, DRIVER_PACKAGE).singleOrNull()
                                ?: throw DriverStartException("Driver on $serial reported ready but is not exactly one process")
                        }
                    val startToken =
                        (observation?.process as? Adb.ProcessStat.Live)?.startToken
                            ?: timings.measure("attach_process_identity") { processStartToken(adb, serial, driverPid) }
                    journal =
                        journal.copy(
                            state = JournalState.ACTIVE,
                            hostPort = hostPort,
                            driverPid = driverPid,
                            driverStartToken = startToken,
                            driverInstanceId = started.driverInstanceId,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    store.write(journal)

                    val connected =
                        timings.measure("attach_authentication") {
                            connectWithRetry(
                                hostPort,
                                sessionId,
                                generation,
                                secret,
                                serial = serial,
                                heartbeatIntervalMs = config.heartbeatIntervalMs,
                            )
                        }
                    client = connected
                    check(
                        warm == null || connected.driverInstanceId == started.driverInstanceId,
                    ) { "Driver instance changed during attach" }
                    runCatching { config.lifecycleLog("driver startup serial=$serial mode=${if (warm == null) "cold" else "reused"}") }
                    timings.measure("attach_health") { connected.execute(Requests.health()) }
                    journal = journal.copy(state = JournalState.READY, updatedAtEpochMs = System.currentTimeMillis())
                    store.write(journal)
                    session =
                        DeviceSession(
                            config,
                            lease,
                            store,
                            journal,
                            started,
                            hostPort,
                            connected,
                            hooks,
                            driverReused = warm != null,
                            runtimeRotationBaseline = baselineRotation,
                        )
                    // Bind the session-owned usability gate once: every later command admission on
                    // this client — including through previously captured references — consults the
                    // same sticky poison/closing state under the transport mutex, before any ID or
                    // frame. Standalone clients keep the no-op default.
                    session.client.bindSessionGate(session::checkAdmissible)
                    session.client.bindRotationMutationHook(session::captureRotation)
                    leaseOwnedBySession = true
                    // Cancellation may arrive after READY but before the caller accepts the
                    // returned value. Keep the deterministic seam non-cancellable, then use a
                    // cancellable continuation as the ownership-transfer point: cancellation
                    // that wins that race throws into this catch and cleans the still-owned
                    // session; successful resumption transfers cleanup responsibility.
                    withContext(NonCancellable) { hooks.beforeOwnershipTransfer() }
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
                                var instrumentationGone = false
                                running?.let {
                                    cleanupStep({ failure -> cleanupFailure = cleanupFailure ?: failure }) {
                                        cleanupInstrumentation(adb, serial, it)
                                        instrumentationGone = true
                                    }
                                }
                                if (running == null && baselineRotation.isNotEmpty()) {
                                    cleanupStep({ failure -> cleanupFailure = cleanupFailure ?: failure }) {
                                        forceStopDriverAndVerify(adb, serial)
                                        instrumentationGone = true
                                    }
                                }
                                if (instrumentationGone && baselineRotation.isNotEmpty()) {
                                    cleanupStep({ failure -> cleanupFailure = cleanupFailure ?: failure }) {
                                        timings.measure("attach_failure_rotation") { adb.restoreState(serial, baselineRotation) }
                                        journal = journal.copy(savedState = journal.savedState.filterNot { it.key in StateKey.ROTATION })
                                    }
                                }
                                true
                            }
                        if (finished == null) {
                            cleanupFailure =
                                cleanupFailure ?: DeviceQuarantinedException(
                                    serial,
                                    "Session open cleanup on $serial exceeded ${DEVICE_OPEN_CLEANUP_TIMEOUT_MS}ms; device quarantined",
                                )
                        }
                        val startupUncertain = findReapUncertain(error)
                        val quarantined = cleanupFailure != null || startupUncertain != null
                        try {
                            store.write(
                                journal.copy(
                                    state =
                                        if (!quarantined &&
                                            journal.inspectedQuarantine == null
                                        ) {
                                            JournalState.CLOSED
                                        } else {
                                            JournalState.QUARANTINED
                                        },
                                    quarantineReason =
                                        (cleanupFailure ?: startupUncertain)?.let { "SESSION_START_CLEANUP_UNCERTAIN: ${it.message}" }
                                            ?: journal.inspectedQuarantine,
                                    inspectedQuarantine = null,
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
                // Whole-open quarantine: any reap uncertainty from the lease-owned prefix (bootId,
                // prior-journal recovery force-stop, wake/dismiss, installs — all before CREATING)
                // persists a truthful QUARANTINED record before lease release, so the next open's
                // recovery never mistakes it for CLOSED. When the boot identity itself is unproven,
                // the prior journal is preserved where available; otherwise a sentinel unknown-boot
                // record carries QUARANTINED (never CLOSED).
                val startupUncertain = findReapUncertain(error)
                if (!leaseOwnedBySession && startupUncertain != null) {
                    runCatching {
                        quarantineEarlyOpen(store, serial, observedBootId, observedPrior, startupUncertain)
                    }.exceptionOrNull()?.let { error.addSuppressed(it) }
                }
                // A constructed session owns the lease; either close() finalized it or the
                // cancellable return handoff scheduled the session-owned cleanup job.
                if (!leaseOwnedBySession) {
                    removedRuntime?.let { entry ->
                        withContext(NonCancellable) {
                            runCatching {
                                cleanupInstrumentationProcess(
                                    serial,
                                    entry.running,
                                )
                            }.exceptionOrNull()?.let(error::addSuppressed)
                        }
                    }
                    lease.close()
                }
                // A cancelled open reports cancellation even when the failure it tripped over was
                // the transport closing under a write (the cause is kept).
                if (error !is CancellationException && !currentCoroutineContext().isActive) {
                    throw CancellationException("Session open on $serial was cancelled", error)
                }
                if (warmAttempt && !leaseOwnedBySession && error !is CancellationException && findReapUncertain(error) == null) {
                    throw WarmDriverAttachException(error)
                }
                throw error
            }
        }
    }
}

private class WarmDriverAttachException(
    cause: Throwable,
) : RuntimeException("Warm driver attach failed before exposure", cause)

/** Sentinel boot identity for an open that never proved the kernel's boot id. QUARANTINED-only;
 * recovery rejects QUARANTINED before any boot comparison, so it is never mistaken for CLOSED. */
const val UNKNOWN_BOOT_ID = "UNKNOWN-BOOT-UNPROVEN"

/** Persists a truthful QUARANTINED record for a lease-owned open that failed before CREATING. */
internal fun quarantineEarlyOpen(
    store: SessionJournalStore,
    serial: String,
    bootId: String?,
    prior: SessionJournal?,
    uncertain: AdbReapUncertainException,
) {
    val reason = "SESSION_START_CLEANUP_UNCERTAIN: ${uncertain.message}"
    val now = System.currentTimeMillis()
    val known: SessionJournal? = prior ?: runCatching { store.read() }.getOrNull()
    if (known != null) {
        store.write(
            known.copy(
                state = JournalState.QUARANTINED,
                runtimeIdle = false,
                quarantineReason = reason,
                restoreRetryable = false,
                updatedAtEpochMs = now,
            ),
        )
        return
    }
    store.write(
        SessionJournal(
            state = JournalState.QUARANTINED,
            serial = serial,
            bootId = bootId ?: UNKNOWN_BOOT_ID,
            sessionId = UUID.randomUUID().toString(),
            generation = 0,
            devicePort = DEVICE_PORT,
            quarantineReason = reason,
            updatedAtEpochMs = now,
        ),
    )
}

private val REPLACEABLE_INSTALL_FAILURES = listOf("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_VERSION_DOWNGRADE")

/**
 * Installs one of Tap's own driver packages. A copy signed by another build (another machine's
 * debug key) cannot be updated in place (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), nor can a newer
 * engine's copy be replaced by an older one (`INSTALL_FAILED_VERSION_DOWNGRADE`); the driver
 * keeps no state worth preserving, so that copy is uninstalled and the install retried once.
 * Never used for the app under test.
 */
internal suspend fun installDriverPackage(
    adb: Adb,
    serial: String,
    packageName: String,
    apk: java.nio.file.Path,
) {
    try {
        adb.install(serial, apk)
    } catch (incompatible: AdbCommandException) {
        if (REPLACEABLE_INSTALL_FAILURES.none { it in incompatible.output }) throw incompatible
        adb.uninstall(serial, packageName)
        try {
            adb.install(serial, apk)
        } catch (retry: Throwable) {
            retry.addSuppressed(incompatible)
            throw retry
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

/**
 * Writes back the device state a previous session changed and never restored (its daemon died
 * before detach, or its restore did not read back), then clears it from the journal. A restore
 * that does not take quarantines, retryably: the device is not in the state its owner left it in,
 * and the next attach tries again.
 */
internal suspend fun restorePriorState(
    adb: Adb,
    serial: String,
    store: SessionJournalStore,
    prior: SessionJournal,
): SessionJournal {
    if (prior.savedState.isEmpty()) return prior
    try {
        adb.restoreState(serial, prior.savedState)
    } catch (error: Exception) {
        if (error is AdbReapUncertainException) throw error
        store.write(
            prior.copy(
                state = JournalState.QUARANTINED,
                quarantineReason = "DEVICE_STATE_RESTORE_FAILED: ${error.message}",
                restoreRetryable = true,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        throw DeviceQuarantinedException(
            serial,
            "Device state a previous session changed on $serial could not be restored; device quarantined",
            error,
        )
    }
    return prior.copy(savedState = emptyList(), updatedAtEpochMs = System.currentTimeMillis()).also(store::write)
}

/**
 * Lifts a [SessionJournal.restoreRetryable] quarantine: [record] is what [recoverJournal] returned
 * for it (driver stopped, forwards removed). Writes the saved state again; once it reads back, the
 * journal is CLOSED. Otherwise it stays quarantined, still retryable
 * ([DeviceQuarantinedException]).
 */
internal suspend fun retryRestore(
    adb: Adb,
    serial: String,
    store: SessionJournalStore,
    record: SessionJournal,
): SessionJournal {
    check(record.state == JournalState.QUARANTINED && record.restoreRetryable)
    return restorePriorState(
        adb,
        serial,
        store,
        record.copy(state = JournalState.CLOSED, quarantineReason = null, restoreRetryable = false),
    )
}
