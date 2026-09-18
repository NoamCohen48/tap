package com.company.tap.host

import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Hello
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.protocol.Operation
import com.company.tap.protocol.ProtocolVersion
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SelectorKind
import com.company.tap.protocol.SyncState
import com.company.tap.protocol.TargetScope
import java.nio.file.Path
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val DRIVER_PACKAGE = "com.company.tap.driver"
private const val FIXTURE_PACKAGE = "com.company.tap.fixture"
private const val DEVICE_PORT = 27183
private val DEVICE_PORT_RANGE = 27183..27187
private const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"
private const val PERMISSION_RESOURCE_PACKAGE = "com.android.permissioncontroller"
private const val SYNC_AUTHORITY = "$FIXTURE_PACKAGE.tap-sync"
private const val LATE_MUTATION_QUARANTINE = "UNINTERRUPTIBLE_MUTATION_RESET_REQUIRED"

fun main(arguments: Array<String>) {
    runBlocking {
        if (arguments.firstOrNull() == "--product-probe") {
            runProductProbe(arguments.drop(1))
            return@runBlocking
        }
        val skipReboot = "--no-reboot" in arguments
        val positional = arguments.filterNot { it == "--no-reboot" }
        require(positional.size == 4) {
            "Usage: host [--no-reboot] <serial[,serial...]> <driver.apk> <driver-test.apk> <fixture.apk> " +
                "or host --product-probe <serial> <driver.apk> <driver-test.apk> <aut.apk> " +
                "<package> <activity> <tap-text|ready-text|screen-name>..."
        }
        val driverApk = Path.of(positional[1])
        val driverTestApk = Path.of(positional[2])
        val fixtureApk = Path.of(positional[3])

        val serials = positional[0]
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        require(serials.isNotEmpty()) { "At least one device serial is required" }
        require(serials.distinct().size == serials.size) { "Device serials must be unique" }

        val disconnectProbe = if (serials.size > 1) {
            MultiDeviceDisconnectProbe(serials.first(), serials.size)
        } else {
            null
        }
        serials
            .map { serial ->
                async(Dispatchers.IO) {
                    runDevice(serial, driverApk, driverTestApk, fixtureApk, disconnectProbe, skipReboot)
                }
            }
            .awaitAll()
    }
}

private suspend fun runDevice(
    serial: String,
    driverApk: Path,
    driverTestApk: Path,
    fixtureApk: Path,
    disconnectProbe: MultiDeviceDisconnectProbe?,
    skipReboot: Boolean = false,
) = withContext(Dispatchers.IO) {
    val adb = Adb()
    val journalStore = SessionJournalStore(
        Path.of(System.getProperty("user.home"), ".tap", "sessions"),
        serial,
    )
    var lease = journalStore.acquireLease()
    try {
    var bootId = adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")
    var priorJournal = recoverJournal(adb, serial, bootId, journalStore, allowResetRecovery = true)
    if (priorJournal?.state == JournalState.QUARANTINED && priorJournal.resetRequired) {
        bootId = resetQuarantinedDevice(
            adb,
            serial,
            bootId,
            journalStore,
            System.nanoTime() + 300_000_000_000L,
        ).bootId
        priorJournal = recoverJournal(adb, serial, bootId, journalStore)
    }
    val apiLevel = adb.run(serial, "shell", "getprop", "ro.build.version.sdk").toInt()
    adb.install(serial, fixtureApk)
    adb.install(serial, driverApk)
    adb.install(serial, driverTestApk)
    adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)

    val sentinelDevicePort = DEVICE_PORT_RANGE.last + 1_000
    val sentinelHostPort = adb.forward(serial, sentinelDevicePort)
    val recoveredOrphan = try {
        val unrelatedForwards = adb.forwards(serial).toSet()
        val creatingGeneration = Math.addExact(priorJournal?.generation ?: 0L, 1L)
        val recoveredCreating = verifyCreatingForwardRecovery(
            adb,
            serial,
            bootId,
            creatingGeneration,
            journalStore,
        )
        check(adb.forwards(serial).toSet() == unrelatedForwards) {
            "CREATING recovery modified unrelated forwarding rules"
        }
        val orphanGeneration = Math.addExact(recoveredCreating.generation, 1L)
        val orphanProcess = seedOrphanSession(
            adb,
            serial,
            bootId,
            orphanGeneration,
            journalStore,
        )
        lease.close()
        lease = journalStore.acquireLease()
        val recovered = requireNotNull(recoverJournal(adb, serial, bootId, journalStore))
        check(orphanProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            "Orphan instrumentation child did not terminate"
        }
        check(adb.forwards(serial).toSet() == unrelatedForwards) {
            "Orphan recovery modified unrelated forwarding rules"
        }
        recovered
    } finally {
        removeExactForward(adb, serial, sentinelHostPort, sentinelDevicePort)
    }

    val recoveredFencing = runSessionFencingScenario(
        adb,
        serial,
        bootId,
        recoveredOrphan.generation,
        journalStore,
    )
    val firstFaultGeneration = Math.addExact(recoveredFencing.generation, 1L)
    verifyChangedBootQuarantine(adb, serial, bootId, firstFaultGeneration)
    val recoveredTransport = runTransportFaultScenarios(
        adb,
        serial,
        bootId,
        recoveredFencing.generation,
        journalStore,
    )
    // --no-reboot skips only the late-mutation quarantine, whose recovery reboots the device.
    val lateReset = if (skipReboot) {
        println("PHASE_0_LATE_MUTATION_SKIPPED serial=$serial reason=--no-reboot")
        LateResetResult(recoveredTransport, bootId)
    } else {
        runLateMutationQuarantineScenario(
            adb,
            serial,
            bootId,
            recoveredTransport.generation,
            journalStore,
            driverApk,
            driverTestApk,
        )
    }
    bootId = lateReset.bootId
    val sessionId = UUID.randomUUID().toString()
    val generation = Math.addExact(lateReset.journal.generation, 1L)
    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    var journal = SessionJournal(
        state = JournalState.CREATING,
        serial = serial,
        bootId = bootId,
        sessionId = sessionId,
        generation = generation,
        devicePort = DEVICE_PORT_RANGE.first,
    )
    var startedDriver: RunningInstrumentation? = null
    try {
        startPortOccupier(adb, serial, DEVICE_PORT_RANGE.first)
        try {
            startedDriver = startDriverWithRetry(
                adb,
                serial,
                sessionId,
                generation,
                encodedSecret,
            ) { devicePort ->
                journal = journal.copy(
                    state = JournalState.CREATING,
                    devicePort = devicePort,
                    hostPort = null,
                    driverPid = null,
                    driverStartToken = null,
                    driverInstanceId = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                journalStore.write(journal)
            }
        } catch (error: Throwable) {
            journalStore.write(journal.copy(state = JournalState.QUARANTINED))
            throw error
        }
    } finally {
        val occupierCleanup = runCatching {
            adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
            waitForPortState(adb, serial, DEVICE_PORT_RANGE.first, listening = false)
        }
        if (occupierCleanup.isFailure) {
            val cleanupError = requireNotNull(occupierCleanup.exceptionOrNull())
            startedDriver?.let { running ->
                runCatching { cleanupInstrumentation(adb, serial, running) }
                    .exceptionOrNull()
                    ?.let(cleanupError::addSuppressed)
            }
            runCatching { journalStore.write(journal.copy(state = JournalState.QUARANTINED)) }
                .exceptionOrNull()
                ?.let(cleanupError::addSuppressed)
            throw cleanupError
        }
    }
    val running = requireNotNull(startedDriver)
    check(running.devicePort != DEVICE_PORT_RANGE.first) {
        "Driver did not retry after the occupied device port"
    }
    val instrumentation = running.process
    val instrumentationOutput = running.output
    val outputThread = running.outputThread
    val devicePort = running.devicePort

    var hostPort: Int? = null
    var cleanupSuccessful = true
    try {
        hostPort = adb.forward(serial, devicePort)
        check(
            adb.forwards(serial).any {
                it.hostPort == hostPort && it.devicePort == devicePort
            }
        ) { "Created forwarding rule was not observable" }
        journal = journal.copy(
            state = JournalState.ACTIVE,
            hostPort = hostPort,
            driverPid = waitForDriverPid(adb, serial),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        journal = journal.copy(
            driverStartToken = processStartToken(adb, serial, requireNotNull(journal.driverPid)),
        )
        journalStore.write(journal)
        assertUnsupportedProtocolRejected(hostPort, sessionId, generation)
        val wrongSecret = secret.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        assertInvalidAuthenticationWithRetry(
            instrumentation,
            hostPort,
            sessionId,
            generation,
            wrongSecret,
        )
        val client = connectWithRetry(hostPort, sessionId, generation, secret, serial = serial)
        client.use {
            check(it.driverInstanceId == running.driverInstanceId) {
                "Authenticated driver instance did not match readiness signal"
            }
            val health = it.execute(Operation.HEALTH)
            check(health.ok) { "Driver health failed: $health" }
            journal = journal.copy(
                state = JournalState.READY,
                driverInstanceId = it.driverInstanceId,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            journalStore.write(journal)
            adb.run(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb.run(serial, "shell", "wm", "dismiss-keyguard")
            adb.run(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
            adb.run(
                serial,
                "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.MainActivity",
                timeoutMs = 60_000,
            )

            val composeButton = Selector(SelectorKind.RAW_RESOURCE, "composeButton")
            check(it.execute(Operation.WAIT_VISIBLE, composeButton, 10_000).ok)
            val processBeforeBootstrap = observeProcess(adb, serial)
            val syncBootstrap = callSync(adb, serial, it, Operation.SYNC_BOOTSTRAP)
            check(syncBootstrap.ok) { "Sync bootstrap failed: $syncBootstrap" }
            val firstSyncIdentity = requireNotNull(syncBootstrap.syncState)
            check(it.execute(Operation.TAP, composeButton).ok)
            check(
                it.execute(
                    Operation.WAIT_VISIBLE,
                    Selector(SelectorKind.TEXT, "Compose tapped"),
                    5_000,
                ).ok
            )

            val viewButton = Selector(
                SelectorKind.ANDROID_RESOURCE,
                value = "view_button",
                packageName = FIXTURE_PACKAGE,
            )
            check(it.execute(Operation.TAP, viewButton).ok)
            check(it.execute(Operation.WAIT_VISIBLE, Selector(SelectorKind.TEXT, "View tapped")).ok)
            val syncButton = Selector(
                SelectorKind.ANDROID_RESOURCE,
                value = "sync_button",
                packageName = FIXTURE_PACKAGE,
            )
            check(it.execute(Operation.TAP, syncButton).ok)
            val busyState = callSync(adb, serial, it, Operation.SYNC_STATE, firstSyncIdentity)
            check(busyState.ok && busyState.value == false) { "Busy state was not observed: $busyState" }
            awaitSyncIdle(adb, serial, it, firstSyncIdentity, timeoutMs = 10_000)
            check(
                it.execute(
                    Operation.WAIT_VISIBLE,
                    Selector(SelectorKind.TEXT, "Synchronized work complete"),
                    5_000,
                ).ok
            )

            adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
            check(it.execute(Operation.HEALTH).ok) { "Driver died with the AUT" }
            check(adb.run(serial, "shell", "pm", "clear", FIXTURE_PACKAGE) == "Success") {
                "AUT data clearing did not report success"
            }
            adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
            awaitProcessAbsent(adb, serial, FIXTURE_PACKAGE)
            check(it.execute(Operation.HEALTH).ok) { "Driver died after AUT data clearing" }
            adb.run(
                serial,
                "shell", "am", "start", "-n", "$FIXTURE_PACKAGE/.MainActivity",
                timeoutMs = 10_000,
            )
            check(it.execute(Operation.WAIT_VISIBLE, composeButton, 10_000).ok)
            val restartedProcess = observeProcess(adb, serial)
            check(restartedProcess != processBeforeBootstrap) { "AUT process identity did not change" }
            val staleSync = callSync(adb, serial, it, Operation.SYNC_STATE, firstSyncIdentity)
            check(!staleSync.ok && staleSync.errorCode == ErrorCode.AUT_MISMATCH && staleSync.detail == ErrorDetail.PROCESS_RESTARTED) {
                "Synchronization restart was not detected: $staleSync"
            }
            val restartedBootstrap = callSync(adb, serial, it, Operation.SYNC_BOOTSTRAP)
            check(restartedBootstrap.ok) { "Synchronization re-bootstrap failed: $restartedBootstrap" }
            check(restartedBootstrap.syncState?.processStartUuid != firstSyncIdentity.processStartUuid)
            benchmark(serial, it, composeButton)

            val input = Selector(
                SelectorKind.ANDROID_RESOURCE,
                value = "view_input",
                packageName = FIXTURE_PACKAGE,
            )
            check(it.execute(Operation.SET_TEXT, input, inputText = "phase zero").ok)
            check(it.execute(Operation.WAIT_VISIBLE, Selector(SelectorKind.TEXT, "phase zero")).ok)
            val keyboardInput = Selector(
                SelectorKind.ANDROID_RESOURCE,
                value = "keyboard_input",
                packageName = FIXTURE_PACKAGE,
            )
            val typedInput = it.execute(
                Operation.TYPE_TEXT,
                keyboardInput,
                timeoutMs = 30_000,
                inputText = "keys 42",
            )
            check(typedInput.ok) { "Keyboard input failed: $typedInput" }
            check(it.execute(Operation.WAIT_VISIBLE, Selector(SelectorKind.TEXT, "keys 42")).ok)
            check(it.execute(Operation.WAIT_VISIBLE, Selector(SelectorKind.TEXT, "Keyboard event received")).ok)
            val unsupportedInput = it.execute(Operation.TYPE_TEXT, keyboardInput, inputText = "emoji \uD83D\uDE00")
            check(!unsupportedInput.ok && unsupportedInput.errorCode == ErrorCode.INVALID_REQUEST && unsupportedInput.detail == ErrorDetail.UNSUPPORTED_CHARACTERS)
            check(it.execute(Operation.EXISTS, Selector(SelectorKind.TEXT, "keys 42")).value == true)
            adb.run(serial, "shell", "input", "keyevent", "KEYCODE_BACK")

            val composeScroll = it.execute(
                operation = Operation.SCROLL_UNTIL,
                selector = Selector(SelectorKind.RAW_RESOURCE, "item-100"),
                timeoutMs = 45_000,
                containerSelector = Selector(SelectorKind.RAW_RESOURCE, "composeList"),
                maxScrolls = 30,
            )
            check(composeScroll.ok) { "Compose scroll failed: $composeScroll" }
            val composeEnd = it.execute(
                operation = Operation.SCROLL_UNTIL,
                selector = Selector(SelectorKind.RAW_RESOURCE, "missing-compose-item"),
                timeoutMs = 30_000,
                containerSelector = Selector(SelectorKind.RAW_RESOURCE, "composeList"),
                maxScrolls = 5,
            )
            check(!composeEnd.ok && composeEnd.errorCode == ErrorCode.NOT_FOUND && composeEnd.detail == ErrorDetail.END_REACHED) {
                "Compose end detection failed: $composeEnd"
            }

            adb.run(
                serial,
                "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.ViewListActivity",
                timeoutMs = 60_000,
            )
            val viewList = Selector(
                SelectorKind.ANDROID_RESOURCE,
                value = "view_list",
                packageName = FIXTURE_PACKAGE,
            )
            check(it.execute(Operation.WAIT_VISIBLE, Selector(SelectorKind.TEXT, "View item 1"), 10_000).ok)
            val viewScroll = it.execute(
                operation = Operation.SCROLL_UNTIL,
                selector = Selector(SelectorKind.TEXT, "View item 100"),
                timeoutMs = 45_000,
                containerSelector = viewList,
                maxScrolls = 50,
            )
            check(viewScroll.ok) { "View scroll failed: $viewScroll" }
            val viewEnd = it.execute(
                operation = Operation.SCROLL_UNTIL,
                selector = Selector(SelectorKind.TEXT, "Missing View item"),
                timeoutMs = 45_000,
                containerSelector = viewList,
                maxScrolls = 10,
            )
            check(!viewEnd.ok && viewEnd.errorCode == ErrorCode.NOT_FOUND && viewEnd.detail == ErrorDetail.END_REACHED) {
                "View end detection failed: $viewEnd"
            }

            adb.run(serial, "shell", "pm", "revoke", FIXTURE_PACKAGE, "android.permission.CAMERA")
            adb.run(
                serial,
                "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.PermissionActivity",
                timeoutMs = 60_000,
            )
            val requestPermission = Selector(
                SelectorKind.ANDROID_RESOURCE,
                value = "request_camera_permission",
                packageName = FIXTURE_PACKAGE,
            )
            check(it.execute(Operation.TAP, requestPermission).ok)
            val permissionChoiceText = if (apiLevel >= 30) "While using the app" else "Allow"
            val autPermissionChoice = it.execute(
                Operation.EXISTS,
                Selector(SelectorKind.TEXT, permissionChoiceText),
            )
            check(autPermissionChoice.ok && autPermissionChoice.value == false) {
                "AUT scope check failed: $autPermissionChoice"
            }
            val deniedScope = it.execute(
                Operation.EXISTS,
                Selector(
                    kind = SelectorKind.TEXT,
                    value = permissionChoiceText,
                    scope = TargetScope.SYSTEM,
                    scopePackage = "com.android.settings",
                ),
            )
            check(!deniedScope.ok && deniedScope.errorCode == ErrorCode.INVALID_SELECTOR && deniedScope.detail == ErrorDetail.SCOPE_DENIED)
            val allowPermission = Selector(
                kind = SelectorKind.ANDROID_RESOURCE,
                value = if (apiLevel >= 30) {
                    "permission_allow_foreground_only_button"
                } else {
                    "permission_allow_button"
                },
                packageName = PERMISSION_RESOURCE_PACKAGE,
                scope = TargetScope.SYSTEM,
                scopePackage = PERMISSION_CONTROLLER_PACKAGE,
            )
            check(it.execute(Operation.WAIT_VISIBLE, allowPermission, 10_000).ok)
            check(it.execute(Operation.TAP, allowPermission).ok)
            check(
                it.execute(
                    Operation.WAIT_VISIBLE,
                    Selector(SelectorKind.TEXT, "Camera granted"),
                    10_000,
                ).ok
            )
            disconnectProbe?.verify(serial, it)
        }
    } finally {
        val bootStillMatches = runCatching {
            adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") == bootId
        }.getOrDefault(false)
        if (!bootStillMatches) cleanupSuccessful = false
        if (bootStillMatches) {
            if (hostPort != null) {
                runCatching { removeExactForward(adb, serial, hostPort, devicePort) }
                    .onFailure { cleanupSuccessful = false }
            } else {
                runCatching {
                    adb.forwards(serial)
                        .filter { it.devicePort == devicePort }
                        .forEach { removeExactForward(adb, serial, it.hostPort, devicePort) }
                }.onFailure { cleanupSuccessful = false }
            }
        }
        if (!instrumentation.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            if (bootStillMatches) {
                runCatching { forceStopDriverAndVerify(adb, serial, journal.driverPid, journal.driverStartToken) }
                    .onFailure { cleanupSuccessful = false }
            }
            instrumentation.destroyForcibly()
            if (!instrumentation.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                cleanupSuccessful = false
            }
        }
        if (bootStillMatches) {
            runCatching { forceStopDriverAndVerify(adb, serial, journal.driverPid, journal.driverStartToken) }
                .onFailure { cleanupSuccessful = false }
        }
        outputThread.join(1_000)
        if (outputThread.isAlive) {
            runCatching { instrumentation.inputStream.close() }
            outputThread.join(1_000)
        }
        if (cleanupSuccessful) {
            cleanupSuccessful = runCatching {
                adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") == bootId
            }.getOrDefault(false)
        }
        journalStore.write(
            journal.copy(
                state = if (cleanupSuccessful) JournalState.CLOSED else JournalState.QUARANTINED,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    val output = synchronized(instrumentationOutput) { instrumentationOutput.toString() }
    check(!instrumentation.isAlive && instrumentation.exitValue() == 0) {
        "Instrumentation did not exit successfully"
    }
    check("OK (1 test)" in output && "Process crashed" !in output) {
        "Instrumentation reported a failure"
    }
    println("PHASE_0_OK serial=$serial")
    } finally {
        lease.close()
    }
}

private data class ProcessObservation(val pid: Int, val startToken: String)

internal data class RunningInstrumentation(
    val process: Process,
    val output: StringBuilder,
    val outputThread: Thread,
    val devicePort: Int,
    val driverInstanceId: String,
)

internal enum class TransportFaultPoint {
    NONE,
    BEFORE_ACCEPTANCE,
    AFTER_ACCEPTANCE,
    AFTER_MUTATION,
    LATE_UNINTERRUPTIBLE,
    CANCEL_AFTER_MUTATION,
}

private data class FaultSession(
    var journal: SessionJournal,
    val running: RunningInstrumentation,
    val hostPort: Int,
    val client: DriverClient,
)

private data class LateResetResult(
    val journal: SessionJournal,
    val bootId: String,
)

private class MultiDeviceDisconnectProbe(
    private val disconnectedSerial: String,
    participantCount: Int,
) {
    private val ready = CyclicBarrier(participantCount)
    private val disconnected = CountDownLatch(1)
    private val survivorsComplete = CountDownLatch(participantCount - 1)

    fun verify(serial: String, client: DriverClient) {
        ready.await(10, TimeUnit.MINUTES)
        if (serial == disconnectedSerial) {
            client.disconnectForValidation()
            disconnected.countDown()
            check(survivorsComplete.await(2, TimeUnit.MINUTES)) {
                "Other devices did not continue after $serial disconnected"
            }
            return
        }

        check(disconnected.await(2, TimeUnit.MINUTES)) {
            "Timed out waiting for $disconnectedSerial to disconnect"
        }
        try {
            repeat(3) {
                val health = client.execute(Operation.HEALTH)
                check(health.ok) {
                    "Device $serial stopped after $disconnectedSerial disconnected: $health"
                }
            }
            println(
                "PHASE_0_DISCONNECT_ISOLATION_OK disconnected=$disconnectedSerial survivor=$serial"
            )
        } finally {
            survivorsComplete.countDown()
        }
    }
}

private fun runSessionFencingScenario(
    adb: Adb,
    serial: String,
    bootId: String,
    previousGeneration: Long,
    store: SessionJournalStore,
): SessionJournal {
    val generation = Math.addExact(previousGeneration, 1L)
    val session = startFaultSession(
        adb,
        serial,
        bootId,
        generation,
        TransportFaultPoint.NONE,
        store,
        System.nanoTime() + 120_000_000_000L,
    )
    var cleanupStarted = false
    try {
        val duplicate = session.client.executeValidationRequest(requestId = 1)
        check(!duplicate.ok && duplicate.errorCode == ErrorCode.DUPLICATE_OR_STALE) {
            "Same-generation duplicate request was not rejected: $duplicate"
        }
        val oldGeneration = session.client.executeValidationRequest(
            requestId = 2,
            requestGeneration = generation - 1,
        )
        check(!oldGeneration.ok && oldGeneration.errorCode == ErrorCode.SESSION_MISMATCH) {
            "Old-generation request was not rejected: $oldGeneration"
        }
        val stale = session.client.executeValidationRequest(requestId = 2)
        check(!stale.ok && stale.errorCode == ErrorCode.DUPLICATE_OR_STALE) {
            "Consumed request ID was not rejected: $stale"
        }
        val unsupported = session.client.executeValidationRequest(
            requestId = 3,
            operationVersion = 2,
        )
        check(!unsupported.ok && unsupported.errorCode == ErrorCode.UNSUPPORTED) {
            "Unsupported operation version was not rejected: $unsupported"
        }
        val unsupportedReplay = session.client.executeValidationRequest(requestId = 3)
        check(!unsupportedReplay.ok && unsupportedReplay.errorCode == ErrorCode.DUPLICATE_OR_STALE) {
            "Unsupported operation request ID was reusable: $unsupportedReplay"
        }
        runCancellationChecks(serial, session.client)
        cleanupStarted = true
        return cleanupFaultSession(adb, serial, bootId, session, store)
    } finally {
        if (!cleanupStarted) cleanupFaultSession(adb, serial, bootId, session, store)
    }
}

/**
 * Proves the driver's reader, queue, executor, and writer lanes are independent: a running wait
 * is cancelled while the executor is busy, queued work is cancelled without running, queue
 * residence consumes the request deadline, heartbeats bypass the executor, and the session stays
 * reusable afterwards. Only non-mutating operations are used, so `CANCELLED` is always legal.
 */
private fun runCancellationChecks(serial: String, client: DriverClient) {
    val absent = Selector(SelectorKind.TEXT, "tap-cancellation-probe-never-visible")

    val idlePingMs = client.ping()

    // Cancel a running wait: it must stop within the poll interval, not at its 30 s timeout.
    val running = client.submit(Operation.WAIT_VISIBLE, absent, timeoutMs = 30_000)
    Thread.sleep(500)
    val busyPingMs = client.ping()
    check(running.cancel()) {
        "Running wait was not cancellable: state=${running.transmissionState} done=${running.isDone} " +
            "response=${running.responseOrNull}"
    }
    val cancelStarted = System.nanoTime()
    val cancelled = running.await()
    val cancelLatencyMs = (System.nanoTime() - cancelStarted) / 1_000_000L
    check(!cancelled.ok && cancelled.errorCode == ErrorCode.CANCELLED) { "Running wait was not cancelled: $cancelled" }
    check(cancelLatencyMs < 5_000) { "Cancellation took $cancelLatencyMs ms" }
    check(!running.cancel()) { "Terminal command accepted a second cancel" }

    // Cancel queued work: the second wait must terminate before the first one does, and a
    // short-deadline command queued behind a long one must expire without running.
    val first = client.submit(Operation.WAIT_VISIBLE, absent, timeoutMs = 30_000)
    val second = client.submit(Operation.WAIT_VISIBLE, absent, timeoutMs = 30_000)
    val expiring = client.submit(Operation.HEALTH, timeoutMs = 200)
    check(second.cancel())
    val secondResult = second.await()
    check(!secondResult.ok && secondResult.errorCode == ErrorCode.CANCELLED) { "Queued wait was not cancelled: $secondResult" }
    check(!first.isDone) { "First wait completed before it was cancelled" }
    Thread.sleep(300)
    check(first.cancel())
    val firstResult = first.await()
    check(!firstResult.ok && firstResult.errorCode == ErrorCode.CANCELLED) { "First wait was not cancelled: $firstResult" }
    val expired = expiring.await()
    check(!expired.ok && expired.errorCode == ErrorCode.DEADLINE_EXCEEDED) {
        "Queued command did not consume its deadline while waiting: $expired"
    }

    // The session is still usable after cancellations.
    val health = client.execute(Operation.HEALTH)
    check(health.ok) { "Session unusable after cancellation: $health" }
    println(
        "PHASE_1_CANCELLATION_OK serial=$serial idlePingMs=$idlePingMs busyPingMs=$busyPingMs " +
            "cancelLatencyMs=$cancelLatencyMs"
    )
}

private fun startPortOccupier(adb: Adb, serial: String, port: Int) {
    try {
        adb.run(
            serial,
            "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.PortOccupierActivity",
            "--ei", "port", port.toString(),
            timeoutMs = 60_000,
        )
        waitForPortState(adb, serial, port, listening = true)
    } catch (error: Throwable) {
        runCatching { adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        throw error
    }
}

private fun waitForPortState(adb: Adb, serial: String, port: Int, listening: Boolean) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (System.nanoTime() < deadline) {
        if (isPortListening(adb, serial, port) == listening) return
        Thread.sleep(50)
    }
    error("Device port $port did not become ${if (listening) "occupied" else "free"}")
}

private fun isPortListening(adb: Adb, serial: String, port: Int): Boolean {
    val expectedPort = port.toString(16).uppercase().padStart(4, '0')
    return adb.run(serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6")
        .lineSequence()
        .map { it.trim().split(Regex("\\s+")) }
        .any { fields ->
            fields.size > 3 && fields[1].endsWith(":$expectedPort") && fields[3] == "0A"
        }
}

private fun verifyChangedBootQuarantine(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
) {
    val root = java.nio.file.Files.createTempDirectory("tap-boot-journal")
    val store = SessionJournalStore(root, serial)
    val devicePort = DEVICE_PORT_RANGE.first
    var hostPort: Int? = null
    var lease: AutoCloseable? = null
    try {
        lease = store.acquireLease()
        hostPort = adb.forward(serial, devicePort)
        store.write(
            SessionJournal(
                state = JournalState.ACTIVE,
                serial = serial,
                bootId = "changed-$bootId",
                sessionId = UUID.randomUUID().toString(),
                generation = generation,
                devicePort = devicePort,
                hostPort = requireNotNull(hostPort),
            )
        )
        val failure = runCatching { recoverJournal(adb, serial, bootId, store) }.exceptionOrNull()
        check(failure != null) { "Changed boot identity was accepted" }
        check(store.read()?.state == JournalState.QUARANTINED) {
            "Changed boot identity was not durably quarantined"
        }
        check(adb.forwards(serial).any { it.hostPort == hostPort && it.devicePort == devicePort }) {
            "Changed-boot recovery removed a forward without matching ownership identity"
        }
    } finally {
        lease?.close()
        hostPort?.let { removeExactForward(adb, serial, it, devicePort) }
        java.nio.file.Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}

private fun verifyCreatingForwardRecovery(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    store: SessionJournalStore,
): SessionJournal {
    val devicePort = DEVICE_PORT_RANGE.first
    store.write(
        SessionJournal(
            state = JournalState.CREATING,
            serial = serial,
            bootId = bootId,
            sessionId = UUID.randomUUID().toString(),
            generation = generation,
            devicePort = devicePort,
        )
    )
    val staleHostPort = adb.forward(serial, devicePort)
    val recovered = requireNotNull(recoverJournal(adb, serial, bootId, store))
    check(adb.forwards(serial).none { it.hostPort == staleHostPort }) {
        "CREATING recovery retained the stale device-port forward"
    }
    return recovered
}

private fun runTransportFaultScenarios(
    adb: Adb,
    serial: String,
    bootId: String,
    previousGeneration: Long,
    store: SessionJournalStore,
): SessionJournal {
    adb.run(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb.run(serial, "shell", "wm", "dismiss-keyguard")
    adb.run(
        serial,
        "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.MainActivity",
        timeoutMs = 60_000,
    )
    val fixtureProcess = observeProcess(adb, serial)
    val startedAt = System.nanoTime()
    val workDeadline = startedAt + 180_000_000_000L
    val scenarioDeadline = startedAt + 600_000_000_000L
    fun requireWithinDeadline() {
        check(System.nanoTime() < scenarioDeadline) { "Transport fault scenario exceeded 600 seconds" }
    }
    fun commandTimeoutMs(maximumMs: Long): Long {
        val remainingMs = (workDeadline - System.nanoTime()) / 1_000_000L
        check(remainingMs > 0) { "Transport fault work exceeded 180 seconds" }
        return minOf(maximumMs, remainingMs)
    }
    fun requireCleanupBudget() {
        check(System.nanoTime() < workDeadline) {
            "Transport fault work exhausted its reserved cleanup budget"
        }
    }
    val faultButton = Selector(
        SelectorKind.ANDROID_RESOURCE,
        value = "fault_button",
        packageName = FIXTURE_PACKAGE,
    )
    var generation = previousGeneration
    var closed: SessionJournal? = null
    for (point in listOf(
        TransportFaultPoint.BEFORE_ACCEPTANCE,
        TransportFaultPoint.AFTER_ACCEPTANCE,
        TransportFaultPoint.AFTER_MUTATION,
    )) {
        requireWithinDeadline()
        requireCleanupBudget()
        generation = Math.addExact(generation, 1L)
        val session = startFaultSession(
            adb, serial, bootId, generation, point, store, workDeadline,
        )
        var cleanupStarted = false
        var primaryError: Throwable? = null
        try {
            check(
                session.client.execute(
                    Operation.WAIT_VISIBLE,
                    Selector(SelectorKind.TEXT, "Fault taps: 0"),
                    commandTimeoutMs(10_000),
                ).ok
            ) { "Fault counter changed before $point" }
            val failure = runCatching {
                session.client.execute(
                    Operation.TAP,
                    faultButton,
                    timeoutMs = commandTimeoutMs(10_000),
                )
            }.exceptionOrNull()
            check(failure is CommandTransportException) { "$point did not lose transport: $failure" }
            check(failure.code == ErrorCode.INDETERMINATE) {
                "$point produced ${failure.code} instead of INDETERMINATE"
            }
            check(failure.transmissionState == TransmissionState.WRITTEN) {
                "$point failed in unexpected host transmission state ${failure.transmissionState}"
            }
            val poisonedFailure = runCatching {
                session.client.execute(Operation.HEALTH)
            }.exceptionOrNull()
            check(
                poisonedFailure is CommandTransportException &&
                    poisonedFailure.code == ErrorCode.TRANSPORT_LOST &&
                    poisonedFailure.transmissionState == TransmissionState.NOT_WRITTEN
            ) { "Lost connection accepted another command: $poisonedFailure" }
            session.journal = session.journal.copy(
                state = JournalState.BROKEN,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            store.write(session.journal)
            cleanupStarted = true
            closed = cleanupFaultSession(adb, serial, bootId, session, store)
            val output = synchronized(session.running.output) { session.running.output.toString() }
            check("TAP_FAULT point=${point.name}" in output) { "Missing driver fault marker for $point" }
            check(observeProcess(adb, serial) == fixtureProcess) {
                "AUT process changed during $point recovery"
            }
            requireWithinDeadline()
        } catch (error: Throwable) {
            primaryError = error
            throw error
        } finally {
            if (!cleanupStarted) {
                val journalFailure = runCatching {
                    session.journal = session.journal.copy(
                        state = JournalState.BROKEN,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    store.write(session.journal)
                }.exceptionOrNull()
                val physicalCleanupFailure = runCatching {
                    cleanupFaultSession(adb, serial, bootId, session, store)
                }.exceptionOrNull()
                val cleanupFailure = journalFailure ?: physicalCleanupFailure
                if (journalFailure != null && physicalCleanupFailure != null) {
                    journalFailure.addSuppressed(physicalCleanupFailure)
                }
                if (cleanupFailure != null) {
                    if (primaryError != null) primaryError.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                }
            }
        }
    }

    requireWithinDeadline()
    requireCleanupBudget()
    generation = Math.addExact(generation, 1L)
    val verification = startFaultSession(
        adb,
        serial,
        bootId,
        generation,
        TransportFaultPoint.NONE,
        store,
        workDeadline,
    )
    var verificationCleanupStarted = false
    var verificationError: Throwable? = null
    try {
        check(observeProcess(adb, serial) == fixtureProcess) { "AUT process changed before verification" }
        check(
            verification.client.execute(
                Operation.WAIT_VISIBLE,
                Selector(SelectorKind.TEXT, "Fault taps: 1"),
                commandTimeoutMs(10_000),
            ).ok
        ) { "Post-mutation transport loss did not produce exactly one tap" }
        Thread.sleep(500)
        val stableCount = verification.client.execute(
            Operation.EXISTS,
            Selector(SelectorKind.TEXT, "Fault taps: 1"),
        )
        check(stableCount.ok && stableCount.value == true) { "Uncertain tap was replayed or completed late" }
        check(observeProcess(adb, serial) == fixtureProcess) { "AUT process changed during verification" }
        verificationCleanupStarted = true
        closed = cleanupFaultSession(adb, serial, bootId, verification, store)
        requireWithinDeadline()
    } catch (error: Throwable) {
        verificationError = error
        throw error
    } finally {
        if (!verificationCleanupStarted) {
            val cleanupFailure = runCatching {
                cleanupFaultSession(adb, serial, bootId, verification, store)
            }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (verificationError != null) verificationError.addSuppressed(cleanupFailure)
                else throw cleanupFailure
            }
        }
    }

    requireWithinDeadline()
    requireCleanupBudget()
    generation = Math.addExact(generation, 1L)
    closed = runCancelAfterMutationScenario(
        adb, serial, bootId, generation, store, workDeadline, fixtureProcess, ::commandTimeoutMs,
    )
    return requireNotNull(closed)
}

/**
 * Cancel arriving after the mutation gate: the driver holds a fault-button tap open for 3 s
 * after its click, the host cancels during the hold, and the awaited result must be the
 * definitive `ok` tap, not `CANCELLED`. The counter must advance exactly once and the session
 * must remain usable.
 */
private fun runCancelAfterMutationScenario(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    store: SessionJournalStore,
    workDeadline: Long,
    fixtureProcess: ProcessObservation,
    commandTimeoutMs: (Long) -> Long,
): SessionJournal {
    val session = startFaultSession(
        adb, serial, bootId, generation, TransportFaultPoint.CANCEL_AFTER_MUTATION, store, workDeadline,
    )
    val faultButton = Selector(
        SelectorKind.ANDROID_RESOURCE,
        value = "fault_button",
        packageName = FIXTURE_PACKAGE,
    )
    var cleanupStarted = false
    try {
        check(
            session.client.execute(
                Operation.WAIT_VISIBLE,
                Selector(SelectorKind.TEXT, "Fault taps: 1"),
                commandTimeoutMs(10_000),
            ).ok
        ) { "Fault counter was not 1 before the cancel-after-mutation tap" }
        val tap = session.client.submit(Operation.TAP, faultButton, timeoutMs = commandTimeoutMs(15_000))
        val marker = "TAP_FAULT point=${TransportFaultPoint.CANCEL_AFTER_MUTATION.name} phase=MUTATED"
        check(waitForInstrumentationMarker(session.running, marker, 10_000)) {
            "Driver did not report the post-click hold"
        }
        check(tap.cancel()) { "In-flight tap was not cancellable at the host" }
        val cancelSentAt = System.nanoTime()
        val result = tap.await()
        val awaitedMs = (System.nanoTime() - cancelSentAt) / 1_000_000L
        check(result.ok && result.value == true) {
            "Cancel after mutation did not return the definitive tap result: $result"
        }
        check(
            session.client.execute(
                Operation.WAIT_VISIBLE,
                Selector(SelectorKind.TEXT, "Fault taps: 2"),
                commandTimeoutMs(10_000),
            ).ok
        ) { "Cancelled-after-mutation tap did not take effect exactly once" }
        Thread.sleep(500)
        val stable = session.client.execute(Operation.EXISTS, Selector(SelectorKind.TEXT, "Fault taps: 2"))
        check(stable.ok && stable.value == true) { "Fault counter moved after the cancelled tap" }
        check(session.client.execute(Operation.HEALTH).ok) { "Session unusable after cancel-after-mutation" }
        check(observeProcess(adb, serial) == fixtureProcess) { "AUT process changed during cancel scenario" }
        println("PHASE_1_CANCEL_AFTER_MUTATION_OK serial=$serial awaitedAfterCancelMs=$awaitedMs")
        cleanupStarted = true
        return cleanupFaultSession(adb, serial, bootId, session, store)
    } finally {
        if (!cleanupStarted) runCatching { cleanupFaultSession(adb, serial, bootId, session, store) }
    }
}

private fun runLateMutationQuarantineScenario(
    adb: Adb,
    serial: String,
    bootId: String,
    previousGeneration: Long,
    store: SessionJournalStore,
    driverApk: Path,
    driverTestApk: Path,
): LateResetResult {
    val scenarioDeadline = System.nanoTime() + 600_000_000_000L
    adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
    adb.run(
        serial,
        "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.MainActivity",
        timeoutMs = 60_000,
    )
    val fixtureProcess = observeProcess(adb, serial)
    val generation = Math.addExact(previousGeneration, 1L)
    val workDeadline = minOf(System.nanoTime() + 180_000_000_000L, scenarioDeadline)
    val session = startFaultSession(
        adb,
        serial,
        bootId,
        generation,
        TransportFaultPoint.LATE_UNINTERRUPTIBLE,
        store,
        workDeadline,
    )
    val faultButton = Selector(
        SelectorKind.ANDROID_RESOURCE,
        value = "fault_button",
        packageName = FIXTURE_PACKAGE,
    )
    var lateWorkDelegated = false
    var cleanupStarted = false
    var resetResult: LateResetResult? = null
    var primaryError: Throwable? = null
    var hypotheticalMutationAtNanos = Long.MAX_VALUE
    try {
        check(
            session.client.execute(
                Operation.WAIT_VISIBLE,
                Selector(SelectorKind.TEXT, "Fault taps: 0"),
                10_000,
            ).ok
        )
        val failure = runCatching {
            session.client.execute(Operation.TAP, faultButton, timeoutMs = 5_000)
        }.exceptionOrNull()
        hypotheticalMutationAtNanos = System.nanoTime() + 15_000_000_000L
        val delegationMarker = "point=${TransportFaultPoint.LATE_UNINTERRUPTIBLE.name} phase=WORK_DELEGATED"
        lateWorkDelegated = waitForInstrumentationMarker(
            session.running,
            delegationMarker,
            remainingTimeoutMs(scenarioDeadline, 5_000),
        )
        check(lateWorkDelegated) { "Driver did not prove delegation of late mutation work" }
        check(failure is CommandTransportException && failure.code == ErrorCode.INDETERMINATE) {
            "Late mutation did not produce INDETERMINATE: $failure"
        }
        check(failure.transmissionState == TransmissionState.WRITTEN)
        check(session.running.process.isAlive) { "Blocked instrumentation exited before cleanup" }
        val oldPid = requireNotNull(session.journal.driverPid)
        check(oldPid in adb.processIds(serial, DRIVER_PACKAGE))
        check(processStartToken(adb, serial, oldPid) == session.journal.driverStartToken)
        check(observeProcess(adb, serial) == fixtureProcess) { "AUT changed before late-work cleanup" }

        session.journal = session.journal.copy(
            state = JournalState.QUARANTINED,
            quarantineReason = LATE_MUTATION_QUARANTINE,
            resetRequired = true,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(session.journal)
        cleanupStarted = true
        session.journal = cleanupFaultSession(
            adb,
            serial,
            bootId,
            session,
            store,
            terminalState = JournalState.QUARANTINED,
            expectForceStop = true,
        )
        check(session.journal.resetRequired)
        check(session.journal.quarantineReason == LATE_MUTATION_QUARANTINE)
        val cleanForwards = adb.forwards(serial).toSet()
        val refusal = runCatching { recoverJournal(adb, serial, bootId, store) }.exceptionOrNull()
        check(refusal != null && store.read()?.state == JournalState.QUARANTINED) {
            "Same-boot reuse was not refused after late mutation"
        }
        check(adb.forwards(serial).toSet() == cleanForwards)
        check(adb.processIds(serial, DRIVER_PACKAGE).isEmpty())
    } catch (error: Throwable) {
        primaryError = error
    } finally {
        if (!cleanupStarted) {
            val quarantineFailure = runCatching {
                session.journal = session.journal.copy(
                    state = JournalState.QUARANTINED,
                    quarantineReason = LATE_MUTATION_QUARANTINE,
                    resetRequired = true,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                store.write(session.journal)
            }.exceptionOrNull()
            val cleanupFailure = runCatching {
                cleanupFaultSession(
                    adb,
                    serial,
                    bootId,
                    session,
                    store,
                    terminalState = JournalState.QUARANTINED,
                )
            }.exceptionOrNull()
            listOfNotNull(quarantineFailure, cleanupFailure).forEach { failure ->
                if (primaryError == null) primaryError = failure else primaryError?.addSuppressed(failure)
            }
        }
        if (lateWorkDelegated || store.read()?.resetRequired == true) {
            val resetFailure = runCatching {
                resetResult = resetQuarantinedDevice(adb, serial, bootId, store, scenarioDeadline)
            }.exceptionOrNull()
            if (resetFailure != null) {
                if (primaryError == null) primaryError = resetFailure else primaryError?.addSuppressed(resetFailure)
            }
        }
    }
    primaryError?.let { throw it }
    val reset = requireNotNull(resetResult)
    check(System.nanoTime() < scenarioDeadline) { "Late mutation reset scenario timed out" }

    adb.install(serial, driverApk, remainingTimeoutMs(scenarioDeadline, 120_000))
    adb.install(serial, driverTestApk, remainingTimeoutMs(scenarioDeadline, 120_000))
    waitForPostResetPackageReadiness(adb, serial, scenarioDeadline)
    val registrationDeadline = minOf(System.nanoTime() + 30_000_000_000L, scenarioDeadline)
    while (System.nanoTime() < registrationDeadline) {
        val registered = adb.run(
            serial,
            "shell", "pm", "list", "instrumentation",
            timeoutMs = remainingTimeoutMs(registrationDeadline, 5_000),
        ).contains("$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner")
        if (registered) break
        sleepWithinDeadline(registrationDeadline, 250)
    }
    check(
        adb.run(
            serial,
            "shell", "pm", "list", "instrumentation",
            timeoutMs = remainingTimeoutMs(scenarioDeadline, 5_000),
        )
            .contains("$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner")
    ) { "Driver instrumentation was not registered after reset reinstall" }
    adb.run(
        serial,
        "shell", "am", "start", "-W", "-n", "$FIXTURE_PACKAGE/.MainActivity",
        timeoutMs = remainingTimeoutMs(scenarioDeadline, 60_000),
    )
    val remainingDelayMs = ((hypotheticalMutationAtNanos - System.nanoTime()) / 1_000_000L)
        .coerceAtLeast(0L)
    if (remainingDelayMs > 0) sleepWithinDeadline(scenarioDeadline, remainingDelayMs + 500)
    val verification = startPostResetVerificationSession(
        adb,
        serial,
        reset.bootId,
        reset.journal.generation,
        store,
        scenarioDeadline,
    )
    var verificationCleanupStarted = false
    try {
        check(
            verification.client.execute(
                Operation.WAIT_VISIBLE,
                Selector(SelectorKind.TEXT, "Fault taps: 0"),
                10_000,
            ).ok
        ) { "Late mutation survived the mandatory reset" }
        verificationCleanupStarted = true
        val closed = cleanupFaultSession(adb, serial, reset.bootId, verification, store)
        return LateResetResult(closed, reset.bootId)
    } finally {
        if (!verificationCleanupStarted) {
            cleanupFaultSession(adb, serial, reset.bootId, verification, store)
        }
    }
}

private fun startPostResetVerificationSession(
    adb: Adb,
    serial: String,
    bootId: String,
    previousGeneration: Long,
    store: SessionJournalStore,
    scenarioDeadline: Long,
): FaultSession {
    var generation = previousGeneration
    var lastFailure: Throwable? = null
    repeat(3) {
        check(System.nanoTime() < scenarioDeadline) { "Post-reset verification timed out" }
        generation = Math.addExact(generation, 1L)
        try {
            return startFaultSession(
                adb,
                serial,
                bootId,
                generation,
                TransportFaultPoint.NONE,
                store,
                minOf(System.nanoTime() + 120_000_000_000L, scenarioDeadline),
            )
        } catch (error: Throwable) {
            lastFailure?.addSuppressed(error)
            lastFailure = lastFailure ?: error
            val failed = requireNotNull(store.read())
            check(
                failed.state == JournalState.QUARANTINED && !failed.resetRequired &&
                    failed.generation == generation
            ) { "Post-reset startup failure was not safely quarantined" }
            check(adb.processIds(serial, DRIVER_PACKAGE).isEmpty())
            val remainingForwards = adb.forwards(serial)
            check(
                if (failed.hostPort != null) {
                    remainingForwards.none {
                        it.hostPort == failed.hostPort && it.devicePort == failed.devicePort
                    }
                } else {
                    remainingForwards.none { it.devicePort == failed.devicePort }
                }
            ) { "Post-reset startup cleanup left an owned forwarding rule" }
            store.write(
                failed.copy(
                    state = JournalState.CLOSED,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
            sleepWithinDeadline(scenarioDeadline, 5_000)
        }
    }
    throw IllegalStateException("Driver did not restart after explicit reset", lastFailure)
}

private fun waitForPostResetPackageReadiness(adb: Adb, serial: String, deadlineNanos: Long) {
    val apiLevel = adb.run(
        serial,
        "shell", "getprop", "ro.build.version.sdk",
        timeoutMs = remainingTimeoutMs(deadlineNanos, 5_000),
    ).toInt()
    if (apiLevel >= 31) {
        val idle = adb.runResult(
            serial,
            "shell", "am", "wait-for-broadcast-idle",
            timeoutMs = remainingTimeoutMs(deadlineNanos, 120_000),
        )
        check(idle.exitCode == 0) { "Android did not reach broadcast-idle after reset: ${idle.output}" }
    } else {
        sleepWithinDeadline(deadlineNanos, 30_000)
    }

    check(
        adb.run(
            serial,
            "shell", "pm", "path", DRIVER_PACKAGE,
            timeoutMs = remainingTimeoutMs(deadlineNanos, 30_000),
        ).startsWith("package:")
    ) {
        "Driver package was not available after reset reinstall"
    }
    check(
        adb.run(
            serial,
            "shell", "pm", "path", "$DRIVER_PACKAGE.test",
            timeoutMs = remainingTimeoutMs(deadlineNanos, 30_000),
        ).startsWith("package:")
    ) {
        "Driver test package was not available after reset reinstall"
    }
}

private fun remainingTimeoutMs(deadlineNanos: Long, maximumMs: Long): Long {
    val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
    check(remainingMs > 0) { "Late mutation reset scenario timed out" }
    return minOf(maximumMs, remainingMs).coerceAtLeast(1L)
}

private fun sleepWithinDeadline(deadlineNanos: Long, requestedMs: Long) {
    val timeoutMs = remainingTimeoutMs(deadlineNanos, requestedMs)
    Thread.sleep(timeoutMs)
    check(timeoutMs == requestedMs) { "Late mutation reset scenario timed out" }
}

private fun awaitProcessAbsent(adb: Adb, serial: String, packageName: String) {
    val deadline = System.nanoTime() + 10_000_000_000L
    var consecutiveAbsentSamples = 0
    while (System.nanoTime() < deadline) {
        if (adb.processIds(serial, packageName).isEmpty()) {
            consecutiveAbsentSamples += 1
            if (consecutiveAbsentSamples == 3) return
        } else {
            consecutiveAbsentSamples = 0
        }
        Thread.sleep(250)
    }
    error("$packageName process remained present after force-stop")
}

private fun resetQuarantinedDevice(
    adb: Adb,
    serial: String,
    currentBootId: String,
    store: SessionJournalStore,
    scenarioDeadline: Long,
): LateResetResult {
    var record = requireNotNull(store.read())
    check(record.state == JournalState.QUARANTINED)
    check(record.resetRequired && record.quarantineReason == LATE_MUTATION_QUARANTINE)
    val resetAction = resetRecoveryAction(record, currentBootId)
    val resetOriginBootId = record.resetStartedBootId ?: record.bootId
    if (record.resetStartedBootId == null) {
        record = record.copy(
            resetStartedBootId = resetOriginBootId,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(record)
    }
    if (resetAction == ResetRecoveryAction.REBOOT) {
        adb.runResult(serial, "reboot", timeoutMs = remainingTimeoutMs(scenarioDeadline, 10_000))
    }

    val deadline = minOf(System.nanoTime() + 240_000_000_000L, scenarioDeadline)
    var newBootId: String? = null
    while (System.nanoTime() < deadline) {
        val candidate = runCatching {
            adb.run(
                serial,
                "shell", "cat", "/proc/sys/kernel/random/boot_id",
                timeoutMs = remainingTimeoutMs(deadline, 5_000),
            )
        }.getOrNull()
        val completed = runCatching {
            adb.run(
                serial,
                "shell", "getprop", "sys.boot_completed",
                timeoutMs = remainingTimeoutMs(deadline, 5_000),
            )
        }.getOrNull()
        val bootAnimation = runCatching {
            adb.run(
                serial,
                "shell", "getprop", "init.svc.bootanim",
                timeoutMs = remainingTimeoutMs(deadline, 5_000),
            )
        }.getOrNull()
        val instrumentationReady = runCatching {
            adb.run(
                serial,
                "shell", "pm", "list", "instrumentation",
                timeoutMs = remainingTimeoutMs(deadline, 5_000),
            )
                .contains("$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner")
        }.getOrDefault(false)
        if (
            !candidate.isNullOrBlank() && candidate != resetOriginBootId && completed == "1" &&
            bootAnimation == "stopped" && instrumentationReady
        ) {
            newBootId = candidate
            break
        }
        sleepWithinDeadline(deadline, 500)
    }
    val observedBootId = requireNotNull(newBootId) { "Device did not complete reboot with a new boot identity" }
    sleepWithinDeadline(scenarioDeadline, 15_000)
    adb.run(
        serial,
        "shell", "input", "keyevent", "KEYCODE_WAKEUP",
        timeoutMs = remainingTimeoutMs(scenarioDeadline, 30_000),
    )
    adb.run(
        serial,
        "shell", "wm", "dismiss-keyguard",
        timeoutMs = remainingTimeoutMs(scenarioDeadline, 30_000),
    )
    check(adb.processIds(serial, DRIVER_PACKAGE).isEmpty()) {
        "Driver process existed after explicit reboot reset"
    }
    val remainingForwards = adb.forwards(serial)
    check(
        if (record.hostPort != null) {
            remainingForwards.none {
                it.hostPort == record.hostPort && it.devicePort == record.devicePort
            }
        } else {
            remainingForwards.none { it.devicePort == record.devicePort }
        }
    ) { "Quarantined forward survived explicit reset" }
    check(adb.run(serial, "shell", "pm", "clear", FIXTURE_PACKAGE) == "Success") {
        "AUT data reset did not report success after reboot"
    }
    adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
    awaitProcessAbsent(adb, serial, FIXTURE_PACKAGE)
    val closed = record.copy(
        state = JournalState.CLOSED,
        bootId = observedBootId,
        quarantineReason = null,
        resetRequired = false,
        resetStartedBootId = null,
        updatedAtEpochMs = System.currentTimeMillis(),
    )
    store.write(closed)
    return LateResetResult(closed, observedBootId)
}

private fun startFaultSession(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    faultPoint: TransportFaultPoint,
    store: SessionJournalStore,
    deadlineNanos: Long,
): FaultSession {
    check(System.nanoTime() < deadlineNanos) { "Transport fault session started after its deadline" }
    val sessionId = UUID.randomUUID().toString()
    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    var journal = SessionJournal(
        state = JournalState.CREATING,
        serial = serial,
        bootId = bootId,
        sessionId = sessionId,
        generation = generation,
        devicePort = DEVICE_PORT_RANGE.first,
    )
    var running: RunningInstrumentation? = null
    var hostPort: Int? = null
    var client: DriverClient? = null
    try {
        running = startDriverWithRetry(
            adb,
            serial,
            sessionId,
            generation,
            encodedSecret,
            faultPoint,
            deadlineNanos,
        ) { devicePort ->
            journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
            store.write(journal)
        }
        check(System.nanoTime() < deadlineNanos) { "Driver startup exceeded transport deadline" }
        hostPort = adb.forward(serial, running.devicePort)
        journal = journal.copy(
            hostPort = hostPort,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(journal)
        check(System.nanoTime() < deadlineNanos) { "Forwarding exceeded transport deadline" }
        val driverPid = waitForDriverPid(adb, serial)
        check(System.nanoTime() < deadlineNanos) { "PID observation exceeded transport deadline" }
        journal = journal.copy(
            state = JournalState.ACTIVE,
            hostPort = hostPort,
            driverPid = driverPid,
            driverStartToken = processStartToken(adb, serial, driverPid),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(journal)
        client = connectWithRetry(hostPort, sessionId, generation, secret, deadlineNanos, serial)
        check(System.nanoTime() < deadlineNanos) { "Connection exceeded transport deadline" }
        check(client.driverInstanceId == running.driverInstanceId)
        check(client.execute(Operation.HEALTH).ok)
        check(System.nanoTime() < deadlineNanos) { "Health check exceeded transport deadline" }
        journal = journal.copy(
            state = JournalState.READY,
            driverInstanceId = client.driverInstanceId,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(journal)
        return FaultSession(journal, running, hostPort, client)
    } catch (error: Throwable) {
        runCatching { client?.close() }.exceptionOrNull()?.let(error::addSuppressed)
        if (hostPort != null && running != null) {
            runCatching { removeExactForward(adb, serial, hostPort, running.devicePort) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
        }
        running?.let {
            runCatching { cleanupInstrumentation(adb, serial, it) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
        }
        runCatching { store.write(journal.copy(state = JournalState.QUARANTINED)) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
        throw error
    }
}

internal enum class ResetRecoveryAction {
    REBOOT,
    COMPLETE,
}

internal fun resetRecoveryAction(record: SessionJournal, currentBootId: String): ResetRecoveryAction {
    require(record.state == JournalState.QUARANTINED && record.resetRequired) {
        "Journal does not require reset recovery"
    }
    val startedBootId = record.resetStartedBootId
    if (startedBootId == null) {
        require(record.bootId == currentBootId) {
            "Reset was not durably started before boot identity changed"
        }
        return ResetRecoveryAction.REBOOT
    }
    require(record.bootId == startedBootId) { "Reset origin does not match quarantined boot identity" }
    return if (currentBootId == startedBootId) ResetRecoveryAction.REBOOT else ResetRecoveryAction.COMPLETE
}

private fun waitForInstrumentationMarker(
    running: RunningInstrumentation,
    marker: String,
    timeoutMs: Long,
): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (synchronized(running.output) { marker in running.output }) return true
        if (!running.process.isAlive && !running.outputThread.isAlive) break
        Thread.sleep(25)
    }
    return synchronized(running.output) { marker in running.output }
}

private fun cleanupFaultSession(
    adb: Adb,
    serial: String,
    bootId: String,
    session: FaultSession,
    store: SessionJournalStore,
    terminalState: JournalState = JournalState.CLOSED,
    expectForceStop: Boolean = false,
): SessionJournal {
    var cleanupError: Throwable? = null
    fun capture(block: () -> Unit) {
        runCatching(block).exceptionOrNull()?.let { error ->
            if (cleanupError == null) cleanupError = error else cleanupError?.addSuppressed(error)
        }
    }

    capture { session.client.close() }
    capture { removeExactForward(adb, serial, session.hostPort, session.running.devicePort) }
    var forceStopRequired = false
    if (!session.running.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
        forceStopRequired = true
        capture {
            forceStopDriverAndVerify(
                adb,
                serial,
                session.journal.driverPid,
                session.journal.driverStartToken,
            )
        }
        if (session.running.process.isAlive) session.running.process.destroyForcibly()
        if (!session.running.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            capture { error("Fault instrumentation child survived cleanup") }
        }
    }
    if (expectForceStop && !forceStopRequired) {
        capture { error("Late mutation instrumentation exited without forced termination") }
    }
    capture {
        forceStopDriverAndVerify(
            adb,
            serial,
            session.journal.driverPid,
            session.journal.driverStartToken,
        )
    }
    session.running.outputThread.join(1_000)
    if (session.running.outputThread.isAlive) {
        runCatching { session.running.process.inputStream.close() }
        session.running.outputThread.join(1_000)
    }
    if (session.running.outputThread.isAlive) {
        capture { error("Fault instrumentation output thread survived cleanup") }
    }
    capture {
        check(adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") == bootId) {
            "Boot identity changed during transport cleanup"
        }
    }
    val closed = session.journal.copy(
        state = if (cleanupError == null) terminalState else JournalState.QUARANTINED,
        updatedAtEpochMs = System.currentTimeMillis(),
    )
    store.write(closed)
    cleanupError?.let { throw IllegalStateException("Transport fault cleanup was uncertain", it) }
    return closed
}

internal fun startDriverWithRetry(
    adb: Adb,
    serial: String,
    sessionId: String,
    generation: Long,
    encodedSecret: String,
    faultPoint: TransportFaultPoint = TransportFaultPoint.NONE,
    overallDeadlineNanos: Long? = null,
    autPackage: String = FIXTURE_PACKAGE,
    syncAuthority: String = SYNC_AUTHORITY,
    onStarting: (Int) -> Unit,
): RunningInstrumentation {
    var lastOutput = ""
    for (devicePort in DEVICE_PORT_RANGE) {
        check(overallDeadlineNanos == null || System.nanoTime() < overallDeadlineNanos) {
            "Driver startup exceeded its containing deadline"
        }
        onStarting(devicePort)
        val process = ProcessBuilder(
            "adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.company.tap.driver.TapDriverServerTest",
            "-e", "tapSession", sessionId,
            "-e", "tapGeneration", generation.toString(),
            "-e", "tapSecret", encodedSecret,
            "-e", "tapPort", devicePort.toString(),
            "-e", "tapAutPackage", autPackage,
            "-e", "tapSystemPackages", PERMISSION_CONTROLLER_PACKAGE,
            "-e", "tapSyncAuthority", syncAuthority,
            "-e", "tapFaultPoint", faultPoint.name,
            "$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner",
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val outputThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
                println(line)
            }
        }.apply {
            isDaemon = true
            start()
        }
        val markerPrefix = "TAP_READY session=$sessionId generation=$generation port=$devicePort instance="
        val deadline = minOf(
            System.nanoTime() + 10_000_000_000L,
            overallDeadlineNanos ?: Long.MAX_VALUE,
        )
        var instanceId: String? = null
        while (System.nanoTime() < deadline && process.isAlive) {
            instanceId = synchronized(output) {
                output.lineSequence()
                    .firstOrNull { markerPrefix in it }
                    ?.substringAfter(markerPrefix)
                    ?.trim()
            }
            if (!instanceId.isNullOrEmpty()) break
            Thread.sleep(25)
        }
        if (!instanceId.isNullOrEmpty()) {
            return RunningInstrumentation(process, output, outputThread, devicePort, instanceId)
        }

        cleanupInstrumentation(
            adb,
            serial,
            RunningInstrumentation(process, output, outputThread, devicePort, ""),
        )
        lastOutput = synchronized(output) { output.toString() }
        check("already registered" !in lastOutput) {
            "Another UiAutomation instrumentation session is active on $serial"
        }
        check("BindException" in lastOutput && "EADDRINUSE" in lastOutput) {
            "Driver failed before readiness for a reason other than port occupancy: $lastOutput"
        }
        check(isPortListening(adb, serial, devicePort)) {
            "Driver reported EADDRINUSE but the port was free after verified driver death"
        }
    }
    error("Driver failed to bind any reserved port: $lastOutput")
}

internal fun cleanupInstrumentation(
    adb: Adb,
    serial: String,
    running: RunningInstrumentation,
) {
    val driverCleanup = runCatching { forceStopDriverAndVerify(adb, serial) }
    if (running.process.isAlive) running.process.destroyForcibly()
    val childExited = running.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    runCatching { running.process.inputStream.close() }
    running.outputThread.join(1_000)
    driverCleanup.getOrThrow()
    check(childExited) { "Instrumentation child survived cleanup" }
    check(!running.outputThread.isAlive) { "Instrumentation output thread survived cleanup" }
}

internal fun recoverJournal(
    adb: Adb,
    serial: String,
    bootId: String,
    store: SessionJournalStore,
    allowResetRecovery: Boolean = false,
): SessionJournal? {
    val record = try {
        store.read()
    } catch (error: Throwable) {
        forceStopDriverAndVerify(adb, serial)
        store.preserveCorrupt()
        store.replaceCorruptWith(
            SessionJournal(
                state = JournalState.QUARANTINED,
                serial = serial,
                bootId = bootId,
                sessionId = UUID.randomUUID().toString(),
                generation = 0,
                devicePort = DEVICE_PORT,
            )
        )
        throw IllegalStateException("Corrupt journal quarantined; no forwards were removed", error)
    }
    if (record == null) {
        forceStopDriverAndVerify(adb, serial)
        return null
    }
    check(record.serial == serial) { "Journal serial does not match leased device" }
    check(record.version == 1) { "Unsupported journal version: ${record.version}" }
    if (record.state == JournalState.QUARANTINED) {
        if (
            allowResetRecovery && record.resetRequired &&
            record.quarantineReason == LATE_MUTATION_QUARANTINE
        ) return record
        error("Device is quarantined by its session journal")
    }
    if (record.devicePort !in DEVICE_PORT_RANGE) {
        store.write(record.copy(state = JournalState.QUARANTINED))
        error("Journal device port is outside the reserved framework range")
    }

    if (record.state != JournalState.CLOSED) {
        if (record.bootId != bootId) {
            store.write(record.copy(state = JournalState.QUARANTINED))
            error("Active journal boot identity changed; device quarantined")
        }
        forceStopDriverAndVerify(adb, serial, record.driverPid, record.driverStartToken)
        if (record.hostPort != null) {
            removeExactForward(adb, serial, record.hostPort, record.devicePort)
        } else {
            adb.forwards(serial)
                .filter { it.devicePort == record.devicePort }
                .forEach { removeExactForward(adb, serial, it.hostPort, record.devicePort) }
        }
        if (adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") != bootId) {
            store.write(record.copy(state = JournalState.QUARANTINED))
            error("Boot identity changed during recovery; device quarantined")
        }
        val closed = record.copy(
            state = JournalState.CLOSED,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.write(closed)
        return closed
    }

    forceStopDriverAndVerify(adb, serial, record.driverPid, record.driverStartToken)
    return record
}

private fun seedOrphanSession(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    store: SessionJournalStore,
): Process {
    val sessionId = UUID.randomUUID().toString()
    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    var record = SessionJournal(
        state = JournalState.CREATING,
        serial = serial,
        bootId = bootId,
        sessionId = sessionId,
        generation = generation,
        devicePort = DEVICE_PORT,
    )
    store.write(record)
    val process = ProcessBuilder(
        "adb", "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", "com.company.tap.driver.TapDriverServerTest",
        "-e", "tapSession", sessionId,
        "-e", "tapGeneration", generation.toString(),
        "-e", "tapSecret", encodedSecret,
        "-e", "tapPort", DEVICE_PORT.toString(),
        "-e", "tapAutPackage", FIXTURE_PACKAGE,
        "-e", "tapSystemPackages", PERMISSION_CONTROLLER_PACKAGE,
        "-e", "tapSyncAuthority", SYNC_AUTHORITY,
        "$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner",
    )
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
    val driverPid = waitForDriverPid(adb, serial)
    val hostPort = adb.forward(serial, DEVICE_PORT)
    record = record.copy(
        state = JournalState.ACTIVE,
        hostPort = hostPort,
        driverPid = driverPid,
        driverStartToken = processStartToken(adb, serial, driverPid),
        updatedAtEpochMs = System.currentTimeMillis(),
    )
    store.write(record)
    return process
}

private fun waitForDriverPid(adb: Adb, serial: String): Int {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (System.nanoTime() < deadline) {
        val pids = adb.processIds(serial, DRIVER_PACKAGE)
        if (pids.size == 1) return pids.single()
        check(pids.size <= 1) { "Multiple driver processes found: $pids" }
        Thread.sleep(50)
    }
    error("Driver PID did not appear")
}

private fun forceStopDriverAndVerify(
    adb: Adb,
    serial: String,
    oldPid: Int? = null,
    oldStartToken: String? = null,
) {
    adb.run(serial, "shell", "am", "force-stop", DRIVER_PACKAGE)
    val deadline = System.nanoTime() + 5_000_000_000L
    while (System.nanoTime() < deadline) {
        val packageGone = adb.processIds(serial, DRIVER_PACKAGE).isEmpty()
        val oldIdentityGone = if (oldPid != null && oldStartToken != null) {
            processIdentityIsGone(adb, serial, oldPid, oldStartToken)
        } else {
            true
        }
        if (packageGone && oldIdentityGone) return
        Thread.sleep(50)
    }
    error("Driver process survived package force-stop")
}

private fun processIdentityIsGone(
    adb: Adb,
    serial: String,
    pid: Int,
    startToken: String,
): Boolean {
    val result = adb.runResult(serial, "shell", "cat", "/proc/$pid/stat")
    if (result.exitCode != 0) {
        check("No such file" in result.output || "No such process" in result.output) {
            "Unable to verify old process identity: ${result.output}"
        }
        return true
    }
    val closingName = result.output.lastIndexOf(')')
    check(closingName >= 0) { "Malformed /proc stat for PID $pid" }
    val fieldsFromState = result.output.substring(closingName + 1).trim().split(Regex("\\s+"))
    check(fieldsFromState.size > 19) { "Incomplete /proc stat for PID $pid" }
    return fieldsFromState[19] != startToken
}

internal fun processStartToken(adb: Adb, serial: String, pid: Int): String {
    val result = adb.runResult(serial, "shell", "cat", "/proc/$pid/stat")
    check(result.exitCode == 0) { "Process $pid is not observable" }
    val closingName = result.output.lastIndexOf(')')
    check(closingName >= 0) { "Malformed /proc stat for PID $pid" }
    val fieldsFromState = result.output.substring(closingName + 1).trim().split(Regex("\\s+"))
    check(fieldsFromState.size > 19) { "Incomplete /proc stat for PID $pid" }
    return fieldsFromState[19]
}

private fun removeExactForward(
    adb: Adb,
    serial: String,
    hostPort: Int,
    devicePort: Int,
) {
    val existing = adb.forwards(serial).firstOrNull { it.hostPort == hostPort } ?: return
    check(existing.devicePort == devicePort) {
        "Journal forward tcp:$hostPort does not target expected tcp:$devicePort"
    }
    adb.removeForward(serial, hostPort)
    check(adb.forwards(serial).none { it.hostPort == hostPort }) {
        "Forward tcp:$hostPort survived exact removal"
    }
}

private fun callSync(
    adb: Adb,
    serial: String,
    client: DriverClient,
    operation: Operation,
    expectedIdentity: SyncState? = null,
    timeoutMs: Long = 5_000,
): Response {
    val before = observeProcess(adb, serial)
    val outcome = runCatching {
        client.execute(
            operation = operation,
            timeoutMs = timeoutMs,
            observedPid = before.pid,
            observedStartToken = before.startToken,
            expectedProcessStartUuid = expectedIdentity?.processStartUuid,
            expectedSessionIdentity = expectedIdentity?.sessionIdentity,
        )
    }
    val after = observeProcess(adb, serial)
    check(after == before) { "Synchronization call changed the AUT process: $before -> $after" }
    return outcome.getOrThrow()
}

private fun awaitSyncIdle(
    adb: Adb,
    serial: String,
    client: DriverClient,
    expectedIdentity: SyncState,
    timeoutMs: Long,
) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var zeroGeneration: Long? = null
    var zeroObservedAt = 0L
    while (true) {
        val remainingMs = (deadline - System.nanoTime()) / 1_000_000
        check(remainingMs > 0) { "Synchronization idle wait timed out" }
        val response = callSync(
            adb,
            serial,
            client,
            Operation.SYNC_STATE,
            expectedIdentity,
            timeoutMs = minOf(5_000, remainingMs),
        )
        check(response.ok) { "Synchronization state failed: $response" }
        val state = requireNotNull(response.syncState)
        val now = System.nanoTime()
        check(now < deadline) { "Synchronization idle wait timed out" }
        if (state.busyCount == 0) {
            if (zeroGeneration == state.generation && now - zeroObservedAt >= 200_000_000) return
            if (zeroGeneration != state.generation) {
                zeroGeneration = state.generation
                zeroObservedAt = now
            }
        } else {
            zeroGeneration = null
        }
        Thread.sleep(50)
    }
}

private fun observeProcess(adb: Adb, serial: String): ProcessObservation {
    val deadline = System.nanoTime() + 30_000_000_000L
    var lastFailure = "AUT process was absent"
    while (System.nanoTime() < deadline) {
        val pids = adb.processIds(serial, FIXTURE_PACKAGE)
        if (pids.size == 1) {
            val pid = pids.single()
            val stat = adb.runResult(serial, "shell", "cat", "/proc/$pid/stat", timeoutMs = 5_000)
            if (stat.exitCode == 0) {
                val closingName = stat.output.lastIndexOf(')')
                if (closingName >= 0) {
                    val fieldsFromState = stat.output.substring(closingName + 1).trim().split(Regex("\\s+"))
                    if (fieldsFromState.size > 19) return ProcessObservation(pid, fieldsFromState[19])
                    lastFailure = "Incomplete /proc stat for PID $pid"
                } else {
                    lastFailure = "Malformed /proc stat for PID $pid"
                }
            } else {
                lastFailure = "AUT PID $pid exited before its start token was observed"
            }
        } else {
            lastFailure = "Expected one AUT process, got $pids"
        }
        Thread.sleep(50)
    }
    error(lastFailure)
}

private fun assertInvalidAuthenticationWithRetry(
    instrumentation: Process,
    hostPort: Int,
    sessionId: String,
    generation: Long,
    wrongSecret: ByteArray,
) {
    val deadline = System.nanoTime() + 20_000_000_000L
    var lastFailure: Throwable? = null
    while (System.nanoTime() < deadline) {
        check(instrumentation.isAlive) { "Instrumentation exited before readiness" }
        val failure = runCatching {
            DriverClient(hostPort, sessionId, generation, wrongSecret).close()
        }.exceptionOrNull()
        if (failure?.message == "UNAUTHENTICATED") return
        lastFailure = failure
        Thread.sleep(25)
    }
    error("Invalid-authentication check timed out: ${lastFailure?.message}")
}

private fun assertUnsupportedProtocolRejected(
    hostPort: Int,
    sessionId: String,
    generation: Long,
) {
    val nonce = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    val hello = Hello(
        hostBuildId = HOST_BUILD_ID,
        hostNonce = nonce,
        sessionGeneration = generation,
        sessionId = sessionId,
        supportedVersions = listOf(ProtocolVersion(99, 0)),
    )
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", hostPort), 10_000)
        socket.soTimeout = 10_000
        FrameCodec.write(
            socket.getOutputStream(),
            Frame(FrameType.HELLO, 0, CanonicalJson.encode(hello)),
        )
        val result = runCatching { FrameCodec.read(socket.getInputStream()) }
        check(result.isFailure) { "Driver accepted an incompatible application protocol version" }
    }
}

private fun benchmark(serial: String, client: DriverClient, selector: Selector) {
    val directStarted = System.nanoTime()
    repeat(100) { check(client.execute(Operation.EXISTS, selector).value == true) }
    val directMs = (System.nanoTime() - directStarted) / 1_000_000.0 / 100

    val dumpStarted = System.nanoTime()
    repeat(5) { check(!client.execute(Operation.DUMP_HIERARCHY).text.isNullOrEmpty()) }
    val dumpMs = (System.nanoTime() - dumpStarted) / 1_000_000.0 / 5

    println("PHASE_0_BENCHMARK serial=$serial directAvgMs=$directMs dumpAvgMs=$dumpMs")
}

private fun connectWithRetry(
    hostPort: Int,
    sessionId: String,
    generation: Long,
    secret: ByteArray,
    overallDeadlineNanos: Long? = null,
    serial: String? = null,
): DriverClient {
    val deadline = minOf(
        System.nanoTime() + 20_000_000_000L,
        overallDeadlineNanos ?: Long.MAX_VALUE,
    )
    var lastError: Throwable? = null
    while (System.nanoTime() < deadline) {
        try {
            return DriverClient(hostPort, sessionId, generation, secret, overallDeadlineNanos, serial)
        } catch (error: Throwable) {
            lastError = error
            Thread.sleep(100)
        }
    }
    throw IllegalStateException("Driver did not become ready", lastError)
}
