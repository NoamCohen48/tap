package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.*
import io.github.noamcohen48.tap.protocol.BoolResult
import io.github.noamcohen48.tap.protocol.CanonicalJson
import io.github.noamcohen48.tap.protocol.ClearText
import io.github.noamcohen48.tap.protocol.Command
import io.github.noamcohen48.tap.protocol.Count
import io.github.noamcohen48.tap.protocol.DeviceInfoQuery
import io.github.noamcohen48.tap.protocol.Direction
import io.github.noamcohen48.tap.protocol.Done
import io.github.noamcohen48.tap.protocol.DumpHierarchy
import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Exists
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.HOST_BUILD_ID
import io.github.noamcohen48.tap.protocol.Health
import io.github.noamcohen48.tap.protocol.Hello
import io.github.noamcohen48.tap.protocol.InvalidSelectorException
import io.github.noamcohen48.tap.protocol.KEYCODE_BACK
import io.github.noamcohen48.tap.protocol.LongTap
import io.github.noamcohen48.tap.protocol.MatchMode
import io.github.noamcohen48.tap.protocol.Node
import io.github.noamcohen48.tap.protocol.NodeFlag
import io.github.noamcohen48.tap.protocol.PressKey
import io.github.noamcohen48.tap.protocol.ProtocolVersion
import io.github.noamcohen48.tap.protocol.Response
import io.github.noamcohen48.tap.protocol.Returning
import io.github.noamcohen48.tap.protocol.Scroll
import io.github.noamcohen48.tap.protocol.ScrollUntil
import io.github.noamcohen48.tap.protocol.Selector
import io.github.noamcohen48.tap.protocol.SetText
import io.github.noamcohen48.tap.protocol.Snapshot
import io.github.noamcohen48.tap.protocol.Swipe
import io.github.noamcohen48.tap.protocol.SyncBootstrap
import io.github.noamcohen48.tap.protocol.SyncPoll
import io.github.noamcohen48.tap.protocol.SyncResult
import io.github.noamcohen48.tap.protocol.SyncState
import io.github.noamcohen48.tap.protocol.Tap
import io.github.noamcohen48.tap.protocol.TypeText
import io.github.noamcohen48.tap.protocol.WaitAppVisible
import io.github.noamcohen48.tap.protocol.WaitGone
import io.github.noamcohen48.tap.protocol.WaitVisible
import io.github.noamcohen48.tap.protocol.and
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.or
import io.github.noamcohen48.tap.protocol.result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

private const val FIXTURE_PACKAGE = "io.github.noamcohen48.tap.fixture"
private const val PERMISSION_RESOURCE_PACKAGE = "com.android.permissioncontroller"
private const val SYNC_AUTHORITY = "$FIXTURE_PACKAGE.tap-sync"
private const val FAULT_AUTHORITY = "$FIXTURE_PACKAGE.fault"

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

        val serials =
            positional[0]
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
        require(serials.isNotEmpty()) { "At least one device serial is required" }
        require(serials.distinct().size == serials.size) { "Device serials must be unique" }

        val disconnectProbe =
            if (serials.size > 1) {
                MultiDeviceDisconnectProbe(serials.first(), serials.size)
            } else {
                null
            }
        serials
            .map { serial ->
                async(Dispatchers.IO) {
                    runDevice(serial, driverApk, driverTestApk, fixtureApk, disconnectProbe, skipReboot)
                }
            }.awaitAll()
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
    val journalStore =
        SessionJournalStore(
            Path.of(System.getProperty("user.home"), ".tap", "sessions"),
            serial,
        )
    var lease = journalStore.acquireLease()
    try {
        var bootId = adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id")
        var priorJournal = recoverJournal(adb, serial, bootId, journalStore, allowResetRecovery = true)
        if (priorJournal?.state == JournalState.QUARANTINED && priorJournal.resetRequired) {
            bootId =
                resetQuarantinedDevice(
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
        val recoveredOrphan =
            try {
                val unrelatedForwards = adb.forwards(serial).toSet()
                val creatingGeneration = Math.addExact(priorJournal?.generation ?: 0L, 1L)
                val recoveredCreating =
                    verifyCreatingForwardRecovery(
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
                val orphanProcess =
                    seedOrphanSession(
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

        val recoveredFencing =
            runSessionFencingScenario(
                adb,
                serial,
                bootId,
                recoveredOrphan.generation,
                journalStore,
            )
        val firstFaultGeneration = Math.addExact(recoveredFencing.generation, 1L)
        verifyChangedBootQuarantine(adb, serial, bootId, firstFaultGeneration)
        val recoveredTransport =
            runTransportFaultScenarios(
                adb,
                serial,
                bootId,
                recoveredFencing.generation,
                journalStore,
            )
        // --no-reboot skips only the late-mutation quarantine, whose recovery reboots the device.
        val lateReset =
            if (skipReboot) {
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
        runMainSession(adb, serial, journalStore, bootId, lateReset.journal.generation, apiLevel, disconnectProbe)
    } finally {
        lease.close()
    }
}

private suspend fun runMainSession(
    adb: Adb,
    serial: String,
    journalStore: SessionJournalStore,
    bootId: String,
    previousGeneration: Long,
    apiLevel: Int,
    disconnectProbe: MultiDeviceDisconnectProbe?,
) {
    val sessionId = UUID.randomUUID().toString()
    val generation = Math.addExact(previousGeneration, 1L)
    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    var journal =
        SessionJournal(
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
            startedDriver =
                startDriverWithRetry(
                    adb,
                    serial,
                    sessionId,
                    generation,
                    encodedSecret,
                    autPackage = FIXTURE_PACKAGE,
                    syncAuthority = SYNC_AUTHORITY,
                    driverArguments = fixtureDriverArguments(),
                ) { devicePort ->
                    journal =
                        journal.copy(
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
        val occupierCleanup =
            runCatching {
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
    val outputDrain = running.outputDrain
    val devicePort = running.devicePort

    var hostPort: Int? = null
    var cleanupSuccessful = true
    try {
        hostPort = adb.forward(serial, devicePort)
        check(
            adb.forwards(serial).any {
                it.hostPort == hostPort && it.devicePort == devicePort
            },
        ) { "Created forwarding rule was not observable" }
        journal =
            journal.copy(
                state = JournalState.ACTIVE,
                hostPort = hostPort,
                driverPid = waitForDriverPid(adb, serial),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        journal =
            journal.copy(
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
        try {
            client.also {
                journal = runFixtureChecks(adb, serial, it, journal, journalStore, running.driverInstanceId)

                runSelectorAndGestureChecks(adb, serial, it)

                adb.run(serial, "shell", "pm", "revoke", FIXTURE_PACKAGE, "android.permission.CAMERA")
                adb.run(
                    serial,
                    "shell",
                    "am",
                    "start",
                    "-W",
                    "-n",
                    "$FIXTURE_PACKAGE/.PermissionActivity",
                    timeoutMs = 60_000,
                )
                val requestPermission = Selector.androidResource(FIXTURE_PACKAGE, "request_camera_permission")
                check(it.send(WaitVisible(requestPermission), timeoutMs = 10_000).ok) {
                    "Permission activity did not appear"
                }
                val requestTap = it.send(Tap(requestPermission))
                check(requestTap.ok) { "Permission request tap failed: $requestTap" }
                val permissionChoiceText = if (apiLevel >= 30) "While using the app" else "Allow"
                val autPermissionChoice = it.send(Exists(Selector.text(permissionChoiceText)))
                check(autPermissionChoice.result == BoolResult(false)) {
                    "AUT scope check failed: $autPermissionChoice"
                }
                val deniedScope = it.send(Exists(Selector.text(permissionChoiceText).inSystemPackage("com.android.settings")))
                check(
                    !deniedScope.ok && deniedScope.errorCode == ErrorCode.INVALID_SELECTOR &&
                        deniedScope.detail == ErrorDetail.SCOPE_DENIED,
                )
                val allowPermission =
                    Selector
                        .androidResource(
                            PERMISSION_RESOURCE_PACKAGE,
                            if (apiLevel >= 30) "permission_allow_foreground_only_button" else "permission_allow_button",
                        ).inSystemPackage(PERMISSION_CONTROLLER_PACKAGE)
                check(it.send(WaitVisible(allowPermission), timeoutMs = 10_000).ok)
                check(it.send(Tap(allowPermission)).ok)
                check(
                    it.send(WaitVisible(Selector.text("Camera granted")), timeoutMs = 10_000).ok,
                )
                disconnectProbe?.verify(serial, it)
            }
        } finally {
            client.close()
        }
    } finally {
        val bootStillMatches =
            runCatching {
                adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") == bootId
            }.getOrDefault(false)
        if (!bootStillMatches) cleanupSuccessful = false
        if (bootStillMatches) {
            if (hostPort != null) {
                runCatching { removeExactForward(adb, serial, hostPort, devicePort) }
                    .onFailure { cleanupSuccessful = false }
            } else {
                runCatching {
                    adb
                        .forwards(serial)
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
        withTimeoutOrNull(1_000) { outputDrain.join() }
        if (!outputDrain.isCompleted) {
            runCatching { instrumentation.inputStream.close() }
            withTimeoutOrNull(1_000) { outputDrain.join() }
        }
        if (cleanupSuccessful) {
            cleanupSuccessful =
                runCatching {
                    adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") == bootId
                }.getOrDefault(false)
        }
        journalStore.write(
            journal.copy(
                state = if (cleanupSuccessful) JournalState.CLOSED else JournalState.QUARANTINED,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
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
}

/**
 * Phase 1 selector proof on `AmbiguityActivity`: every mutating operation returns `AMBIGUOUS`
 * before input when two targets match, explicit `first()`/`at()` limits and relations pick one,
 * the regex traversal plan matches, and the new gestures (`LONG_TAP`, `CLEAR_TEXT`) work.
 */

private suspend fun runFixtureChecks(
    adb: Adb,
    serial: String,
    client: DriverClient,
    journal: SessionJournal,
    journalStore: SessionJournalStore,
    driverInstanceId: String,
): SessionJournal {
    var journal = journal
        check(client.driverInstanceId == driverInstanceId) {
            "Authenticated driver instance did not match readiness signal"
        }
        val health = client.send(Health)
        check(health.ok) { "Driver health failed: $health" }
        journal =
            journal.copy(
                state = JournalState.READY,
                driverInstanceId = client.driverInstanceId,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        journalStore.write(journal)
        adb.run(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb.run(serial, "shell", "wm", "dismiss-keyguard")
        adb.run(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        adb.run(
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "$FIXTURE_PACKAGE/.MainActivity",
            timeoutMs = 60_000,
        )

        val composeButton = Selector.rawResource("composeButton")
        check(client.send(WaitVisible(composeButton), timeoutMs = 10_000).ok)
        val processBeforeBootstrap = observeProcess(adb, serial, FIXTURE_PACKAGE)
        val firstSyncIdentity = callSync(adb, serial, client) { pid, token -> SyncBootstrap(pid, token) }.getOrThrow().state
        check(client.send(Tap(composeButton)).ok)
        check(
            client.send(WaitVisible(Selector.text("Compose tapped")), timeoutMs = 5_000).ok,
        )

        val viewButton = Selector.androidResource(FIXTURE_PACKAGE, "view_button")
        check(client.send(Tap(viewButton)).ok)
        check(client.send(WaitVisible(Selector.text("View tapped"))).ok)
        val syncButton = Selector.androidResource(FIXTURE_PACKAGE, "sync_button")
        check(client.send(Tap(syncButton)).ok)
        val busyState =
            callSync(
                adb,
                serial,
                client,
            ) { pid, token ->
                SyncPoll(
                    pid,
                    token,
                    firstSyncIdentity.processStartUuid,
                    firstSyncIdentity.sessionIdentity,
                )
            }.getOrThrow()
        check(!busyState.idle) { "Busy state was not observed: $busyState" }
        awaitSyncIdle(adb, serial, client, firstSyncIdentity, timeoutMs = 10_000)
        check(
            client.send(WaitVisible(Selector.text("Synchronized work complete")), timeoutMs = 5_000).ok,
        )

        adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
        check(client.send(Health).ok) { "Driver died with the AUT" }
        check(adb.run(serial, "shell", "pm", "clear", FIXTURE_PACKAGE) == "Success") {
            "AUT data clearing did not report success"
        }
        adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
        awaitProcessAbsent(adb, serial, FIXTURE_PACKAGE)
        check(client.send(Health).ok) { "Driver died after AUT data clearing" }
        adb.run(
            serial,
            "shell",
            "am",
            "start",
            "-n",
            "$FIXTURE_PACKAGE/.MainActivity",
            timeoutMs = 10_000,
        )
        val relaunched = client.send(WaitVisible(composeButton), timeoutMs = 10_000)
        check(relaunched.ok) { "Fixture did not come back after clear-data: $relaunched" }
        val restartedProcess = observeProcess(adb, serial, FIXTURE_PACKAGE)
        check(restartedProcess != processBeforeBootstrap) { "AUT process identity did not change" }
        val staleSync =
            callSync(
                adb,
                serial,
                client,
            ) { pid, token -> SyncPoll(pid, token, firstSyncIdentity.processStartUuid, firstSyncIdentity.sessionIdentity) }
                .exceptionOrNull()
        check(
            staleSync is RemoteCommandException && staleSync.code == ErrorCode.AUT_MISMATCH &&
                staleSync.detail == ErrorDetail.PROCESS_RESTARTED,
        ) {
            "Synchronization restart was not detected: $staleSync"
        }
        val restartedBootstrap = callSync(adb, serial, client) { pid, token -> SyncBootstrap(pid, token) }.getOrThrow()
        check(restartedBootstrap.state.processStartUuid != firstSyncIdentity.processStartUuid)
        benchmark(serial, client, composeButton)
        val screenshot = client.screenshot()
        val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        check(screenshot.png.copyOf(4).contentEquals(pngSignature)) { "Screenshot is not a PNG" }
        check(screenshot.info.byteCount == screenshot.png.size.toLong() && (screenshot.info.width ?: 0) > 0)
        println(
            "PHASE_1_SCREENSHOT_OK serial=$serial bytes=${screenshot.png.size} " +
                "size=${screenshot.info.width}x${screenshot.info.height}",
        )

        val input = Selector.androidResource(FIXTURE_PACKAGE, "view_input")
        check(client.send(SetText(input, "phase zero")).ok)
        check(client.send(WaitVisible(Selector.text("phase zero"))).ok)
        val keyboardInput = Selector.androidResource(FIXTURE_PACKAGE, "keyboard_input")
        val typedInput = client.send(TypeText(keyboardInput, "keys 42"), timeoutMs = 30_000)
        check(typedInput.ok) { "Keyboard input failed: $typedInput" }
        check(client.send(WaitVisible(Selector.text("keys 42"))).ok)
        check(client.send(WaitVisible(Selector.text("Keyboard event received"))).ok)
        val unsupportedInput = client.send(TypeText(keyboardInput, "emoji \uD83D\uDE00"))
        check(
            !unsupportedInput.ok && unsupportedInput.errorCode == ErrorCode.INVALID_REQUEST &&
                unsupportedInput.detail == ErrorDetail.UNSUPPORTED_CHARACTERS,
        )
        check(client.execute(Exists(Selector.text("keys 42"))).value)
        // A hinted field reports its hint as `text` once empty; clearing must still verify.
        val clearedHinted = client.send(ClearText(keyboardInput))
        check(clearedHinted.ok) { "CLEAR_TEXT on a hinted field failed: $clearedHinted" }
        val clearedSnapshot = client.execute(Snapshot(keyboardInput)).snapshot
        check(clearedSnapshot.text.isNullOrEmpty() && clearedSnapshot.hint == "Keyboard input") {
            "Cleared hinted field should snapshot as empty text with hint: $clearedSnapshot"
        }
        adb.run(serial, "shell", "input", "keyevent", "KEYCODE_BACK")

        val composeScroll =
            client.send(
                ScrollUntil(Selector.rawResource("item-100"), container = Selector.rawResource("composeList"), maxScrolls = 30),
                timeoutMs = 45_000,
            )
        check(composeScroll.ok) { "Compose scroll failed: $composeScroll" }
        val composeEnd =
            client.send(
                ScrollUntil(
                    Selector.rawResource("missing-compose-item"),
                    container = Selector.rawResource("composeList"),
                    maxScrolls = 5,
                ),
                timeoutMs = 30_000,
            )
        check(!composeEnd.ok && composeEnd.errorCode == ErrorCode.INDETERMINATE && composeEnd.detail == ErrorDetail.END_REACHED) {
            "Compose end detection failed: $composeEnd"
        }

        adb.run(
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "$FIXTURE_PACKAGE/.ViewListActivity",
            timeoutMs = 60_000,
        )
        val viewList = Selector.androidResource(FIXTURE_PACKAGE, "view_list")
        check(client.send(WaitVisible(Selector.text("View item 1")), timeoutMs = 10_000).ok)
        val viewScroll =
            client.send(
                ScrollUntil(Selector.text("View item 100"), container = viewList, maxScrolls = 50),
                timeoutMs = 45_000,
            )
        check(viewScroll.ok) { "View scroll failed: $viewScroll" }
        val viewEnd =
            client.send(
                ScrollUntil(Selector.text("Missing View item"), container = viewList, maxScrolls = 10),
                timeoutMs = 45_000,
            )
        check(!viewEnd.ok && viewEnd.errorCode == ErrorCode.INDETERMINATE && viewEnd.detail == ErrorDetail.END_REACHED) {
            "View end detection failed: $viewEnd"
        }
        val scrollAtEnd = client.execute(Scroll(viewList, Direction.DOWN))
        check(!scrollAtEnd.moved) { "Scroll at end should report no movement: $scrollAtEnd" }
        val scrollBack = client.execute(Scroll(viewList, Direction.UP))
        check(scrollBack.moved) { "Scroll up should move: $scrollBack" }
        val swipe = client.execute(Swipe(viewList, Direction.DOWN))
        check(swipe.moved) { "Swipe failed: $swipe" }
        // A scroll without a direction or with an out-of-range distance is unrepresentable on the host.
        check(
            runCatching { Scroll(viewList, Direction.DOWN, distancePercent = 0) }.exceptionOrNull() is IllegalArgumentException,
        ) {
            "SCROLL with an out-of-range distance must be rejected"
        }

    return journal
}

private suspend fun runSelectorAndGestureChecks(
    adb: Adb,
    serial: String,
    client: DriverClient,
) {
    adb.run(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "$FIXTURE_PACKAGE/.AmbiguityActivity",
        timeoutMs = 60_000,
    )
    check(client.send(WaitVisible(Selector.text("Ambiguity fixture ready")), timeoutMs = 10_000).ok)

    val duplicateButton = Selector.text("Duplicate action")
    val duplicateInput = Selector.androidResource(FIXTURE_PACKAGE, "duplicate_input")
    val duplicateScroll = Selector.androidResource(FIXTURE_PACKAGE, "duplicate_scroll")

    suspend fun expectAmbiguous(
        command: Command,
        timeoutMs: Long = 5_000,
    ) {
        val response = client.send(command, timeoutMs)
        check(response.errorCode == ErrorCode.AMBIGUOUS) { "${command.op} should be AMBIGUOUS: $response" }
    }
    expectAmbiguous(Tap(duplicateButton))
    expectAmbiguous(LongTap(duplicateButton))
    expectAmbiguous(SetText(duplicateInput, "leak"))
    expectAmbiguous(TypeText(duplicateInput, "leak"))
    expectAmbiguous(ClearText(duplicateInput))
    expectAmbiguous(Swipe(duplicateScroll, Direction.UP))
    expectAmbiguous(Scroll(duplicateScroll, Direction.DOWN))
    expectAmbiguous(ScrollUntil(Selector.text("never"), container = duplicateScroll), timeoutMs = 10_000)
    check(client.execute(Exists(Selector.text("Duplicate taps: 0"))).value) {
        "An AMBIGUOUS tap changed the fixture"
    }
    check(!client.execute(Exists(Selector.text("leak"))).value) {
        "An AMBIGUOUS text operation changed the fixture"
    }

    // Explicit limits and relations resolve one of the duplicates.
    check(client.send(Tap(duplicateButton.first())).ok)
    check(client.send(WaitVisible(Selector.text("Duplicate taps: 1"))).ok)
    check(client.send(Tap(duplicateButton.at(1))).ok)
    check(client.send(WaitVisible(Selector.text("Duplicate taps: 2"))).ok)
    val missingIndex = client.send(Tap(duplicateButton.at(2)))
    check(!missingIndex.ok && missingIndex.errorCode == ErrorCode.NOT_FOUND) { "at(2) should be NOT_FOUND: $missingIndex" }
    val rightButton = Selector(Node.text("Duplicate action") and Node.ancestor(Node.Resource("right_half", FIXTURE_PACKAGE)))
    check(client.send(Tap(rightButton)).ok)
    check(client.send(WaitVisible(Selector.text("Duplicate taps: 3"))).ok)
    val leftHalfWithButton =
        Selector(
            Node.Resource("left_half", FIXTURE_PACKAGE) and
                Node.child(Node.className("Button", MatchMode.ENDS_WITH) and Node.Flag(NodeFlag.CLICKABLE)),
        )
    check(client.execute(Exists(leftHalfWithButton)).value) { "child relation did not match" }

    // Regex forces the traversal plan; it must agree with the native plan on cardinality.
    val regexButton = Selector.text("^Duplicate act.*", MatchMode.REGEX)
    expectAmbiguous(Tap(regexButton))
    val regexLeft =
        Selector(
            Node.text("^Duplicate act.*", MatchMode.REGEX) and Node.ancestor(Node.Resource("left_half", FIXTURE_PACKAGE)),
        )
    check(client.send(Tap(regexLeft)).ok) { "Traversal-plan tap failed" }
    check(client.send(WaitVisible(Selector.text("^Duplicate taps: \\d+$", MatchMode.REGEX))).ok)
    check(client.execute(Exists(Selector.text("Duplicate taps: 4"))).value)
    check(!client.execute(Exists(Selector.text("^Duplicate taps: 9$", MatchMode.REGEX))).value)

    // any_of and a repeated text constraint also take the traversal plan; both must agree with native counts.
    val duplicates = client.execute(Count(Selector.text("Duplicate action"))).count
    val eitherText = Selector(Node.text("Duplicate action") or Node.text("Ambiguity fixture ready"))
    check(client.execute(Count(eitherText)).count == duplicates + 1) { "any_of count disagrees with the native plan" }
    expectAmbiguous(Tap(Selector(Node.text("Duplicate action") or Node.text("never on screen"))))
    val twoTexts = Selector(Node.text("Duplicate", MatchMode.STARTS_WITH) and Node.text("action", MatchMode.ENDS_WITH))
    check(client.execute(Count(twoTexts)).count == duplicates) { "repeated-text conjunction disagrees with the native plan" }
    val eitherOnRight =
        Selector(
            (Node.text("never on screen") or Node.text("Duplicate action")) and Node.ancestor(Node.Resource("right_half", FIXTURE_PACKAGE)),
        )
    check(client.send(Tap(eitherOnRight)).ok) { "any_of inside a conjunction failed to tap" }
    check(client.send(WaitVisible(Selector.text("Duplicate taps: 5"))).ok)

    // Structural rejections never consume a request on the host and are INVALID_SELECTOR on the driver.
    runCatching { client.send(Exists(Selector(Node.AllOf(emptyList())))) }.exceptionOrNull().let { error ->
        check(error is InvalidSelectorException) { "Host validation should reject an empty conjunction: $error" }
    }
    val foreignResource = client.send(Exists(Selector.androidResource("com.other.app", "duplicate_button")))
    check(
        !foreignResource.ok && foreignResource.errorCode == ErrorCode.INVALID_SELECTOR &&
            foreignResource.detail == ErrorDetail.SCOPE_DENIED,
    ) { "Foreign AUT resource should be SCOPE_DENIED: $foreignResource" }

    // Gestures.
    val gestureTarget = Selector.androidResource(FIXTURE_PACKAGE, "gesture_target")
    check(client.send(LongTap(gestureTarget)).ok)
    check(client.send(WaitVisible(Selector.text("Gesture: long press"))).ok)
    check(client.send(Tap(gestureTarget)).ok)
    check(client.send(WaitVisible(Selector.text("Gesture: tap"))).ok)
    val prefilled = Selector.androidResource(FIXTURE_PACKAGE, "prefilled_input")
    check(client.execute(Exists(Selector.text("prefilled"))).value)
    val cleared = client.send(ClearText(prefilled))
    check(cleared.ok) { "CLEAR_TEXT failed: $cleared" }
    check(!client.execute(Exists(Selector.text("prefilled"))).value) { "CLEAR_TEXT left text" }
    val notEditable = client.send(ClearText(gestureTarget))
    check(!notEditable.ok && notEditable.errorCode == ErrorCode.NOT_INTERACTABLE) {
        "CLEAR_TEXT on a button should be NOT_INTERACTABLE: $notEditable"
    }
    println("PHASE_1_SELECTORS_OK serial=$serial")
    runObservationAndKeyChecks(serial, client, gestureTarget)
}

/**
 * Query and key operations: `COUNT` against duplicates, `SNAPSHOT` state, `DEVICE_INFO`,
 * `WAIT_APP_VISIBLE`, `PRESS_KEY` (back leaves the activity), and `WAIT_GONE`.
 */
private suspend fun runObservationAndKeyChecks(
    serial: String,
    client: DriverClient,
    gestureTarget: Selector,
) {
    val duplicates = client.execute(Count(Selector.text("Duplicate action"))).count
    check(duplicates == 2) { "COUNT should report 2 duplicates: $duplicates" }
    check(client.execute(Count(Selector.text("never on screen"))).count == 0)

    val state = client.execute(Snapshot(gestureTarget)).snapshot
    check(state.clickable && state.longClickable && state.enabled && state.bounds.width > 0) {
        "Unexpected gesture target snapshot: $state"
    }
    check(state.resourceName == "$FIXTURE_PACKAGE:id/gesture_target") { "Unexpected resource: $state" }
    val ambiguousSnapshot = client.send(Snapshot(Selector.text("Duplicate action")))
    check(ambiguousSnapshot.errorCode == ErrorCode.AMBIGUOUS) { "SNAPSHOT should be AMBIGUOUS: $ambiguousSnapshot" }

    val info = client.execute(DeviceInfoQuery).deviceInfo
    check(info.apiLevel >= 26 && info.displayWidth > 0 && info.currentPackage == FIXTURE_PACKAGE) {
        "Unexpected device info: $info"
    }
    check(client.send(WaitAppVisible(FIXTURE_PACKAGE), timeoutMs = 5_000).ok)
    // A negative key code is rejected on the host before any frame is written (PressKey's constructor).
    check(runCatching { PressKey(-1) }.exceptionOrNull() is IllegalArgumentException) { "Negative key code should be invalid" }

    // Back finishes AmbiguityActivity; the fixture's main screen is underneath.
    val back = client.send(PressKey(KEYCODE_BACK))
    check(back.ok) { "PRESS_KEY back failed: $back" }
    val gone = client.send(WaitGone(Selector.text("Ambiguity fixture ready")), timeoutMs = 10_000)
    check(gone.ok) { "Ambiguity screen did not go away after back: $gone" }
    println("PHASE_1_OBSERVATION_OK serial=$serial api=${info.apiLevel} model=${info.model}")
}

internal enum class TransportFaultPoint {
    NONE,
    BEFORE_ACCEPTANCE,
    AFTER_ACCEPTANCE,
    AFTER_MUTATION,
    LATE_UNINTERRUPTIBLE,
    CANCEL_AFTER_MUTATION,
}

/** Fixture-only instrumentation arguments: fault point and the fixture's fault provider. */
private fun fixtureDriverArguments(faultPoint: TransportFaultPoint = TransportFaultPoint.NONE) =
    mapOf(
        "tapFaultPoint" to faultPoint.name,
        "tapFaultAuthority" to FAULT_AUTHORITY,
    )

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

    suspend fun verify(
        serial: String,
        client: DriverClient,
    ) {
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
            for (i in 0 until 3) {
                val health = client.send(Health)
                check(health.ok) {
                    "Device $serial stopped after $disconnectedSerial disconnected: $health"
                }
            }
            println(
                "PHASE_0_DISCONNECT_ISOLATION_OK disconnected=$disconnectedSerial survivor=$serial",
            )
        } finally {
            survivorsComplete.countDown()
        }
    }
}

private suspend fun runSessionFencingScenario(
    adb: Adb,
    serial: String,
    bootId: String,
    previousGeneration: Long,
    store: SessionJournalStore,
): SessionJournal {
    val generation = Math.addExact(previousGeneration, 1L)
    val session =
        startFaultSession(
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
        // ID 1 was consumed by the startup Health check.
        val oldGeneration =
            session.client.executeValidationRequest(
                requestId = 2,
                requestGeneration = generation - 1,
            )
        check(!oldGeneration.ok && oldGeneration.errorCode == ErrorCode.SESSION_MISMATCH) {
            "Old-generation request was not rejected: $oldGeneration"
        }
        val unsupported =
            session.client.executeRawValidationRequest(
                requestId = 3,
                payload = """{"sessionId":"${session.journal.sessionId}","generation":$generation,"timeoutMs":5000,"command":{"op":"teleport"}}""",
            )
        check(!unsupported.ok && unsupported.errorCode == ErrorCode.UNSUPPORTED) {
            "Unknown operation was not rejected: $unsupported"
        }
        runCancellationChecks(serial, session.client)
        // A reused ID (here one consumed by the rejected payload) is a protocol violation: the
        // driver sends CLOSE with a DUPLICATE_OR_STALE reason instead of answering on that ID.
        val duplicate =
            try {
                session.client.executeValidationRequest(requestId = 3)
            } catch (closed: Exception) {
                closed
            }
        check(duplicate is Exception && ErrorCode.DUPLICATE_OR_STALE.name in duplicate.message.orEmpty()) {
            "Consumed request ID did not close the connection: $duplicate"
        }
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
private suspend fun runCancellationChecks(
    serial: String,
    client: DriverClient,
) {
    val absent = Selector.text("tap-cancellation-probe-never-visible")

    val idlePingMs = client.ping()

    // Cancel a running wait: it must stop within the poll interval, not at its 30 s timeout.
    val running = client.submit(WaitVisible(absent), timeoutMs = 30_000)
    delay(500)
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
    val first = client.submit(WaitVisible(absent), timeoutMs = 30_000)
    val second = client.submit(WaitVisible(absent), timeoutMs = 30_000)
    val expiring = client.submit(Health, timeoutMs = 200)
    check(second.cancel())
    val secondResult = second.await()
    check(!secondResult.ok && secondResult.errorCode == ErrorCode.CANCELLED) { "Queued wait was not cancelled: $secondResult" }
    check(!first.isDone) { "First wait completed before it was cancelled" }
    delay(300)
    check(first.cancel())
    val firstResult = first.await()
    check(!firstResult.ok && firstResult.errorCode == ErrorCode.CANCELLED) { "First wait was not cancelled: $firstResult" }
    val expired = expiring.await()
    check(!expired.ok && expired.errorCode == ErrorCode.DEADLINE_EXCEEDED) {
        "Queued command did not consume its deadline while waiting: $expired"
    }

    // The session is still usable after cancellations.
    val health = client.send(Health)
    check(health.ok) { "Session unusable after cancellation: $health" }
    println(
        "PHASE_1_CANCELLATION_OK serial=$serial idlePingMs=$idlePingMs busyPingMs=$busyPingMs " +
            "cancelLatencyMs=$cancelLatencyMs",
    )
}

private suspend fun startPortOccupier(
    adb: Adb,
    serial: String,
    port: Int,
) {
    try {
        adb.run(
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "$FIXTURE_PACKAGE/.PortOccupierActivity",
            "--ei",
            "port",
            port.toString(),
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

private suspend fun waitForPortState(
    adb: Adb,
    serial: String,
    port: Int,
    listening: Boolean,
) {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (System.nanoTime() < deadline) {
        if (isPortListening(adb, serial, port) == listening) return
        delay(50)
    }
    error("Device port $port did not become ${if (listening) "occupied" else "free"}")
}

private suspend fun verifyChangedBootQuarantine(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
) {
    val root =
        java.nio.file.Files
            .createTempDirectory("tap-boot-journal")
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
            ),
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

private suspend fun verifyCreatingForwardRecovery(
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
        ),
    )
    val staleHostPort = adb.forward(serial, devicePort)
    val recovered = requireNotNull(recoverJournal(adb, serial, bootId, store))
    check(adb.forwards(serial).none { it.hostPort == staleHostPort }) {
        "CREATING recovery retained the stale device-port forward"
    }
    return recovered
}

private suspend fun runTransportFaultScenarios(
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
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "$FIXTURE_PACKAGE/.MainActivity",
        timeoutMs = 60_000,
    )
    val fixtureProcess = observeProcess(adb, serial, FIXTURE_PACKAGE)
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
    val faultButton = Selector.androidResource(FIXTURE_PACKAGE, "fault_button")
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
        val session =
            startFaultSession(
                adb,
                serial,
                bootId,
                generation,
                point,
                store,
                workDeadline,
            )
        var cleanupStarted = false
        var primaryError: Throwable? = null
        try {
            check(
                session.client.send(WaitVisible(Selector.text("Fault taps: 0")), timeoutMs = commandTimeoutMs(10_000)).ok,
            ) { "Fault counter changed before $point" }
            val failure =
                runCatching {
                    session.client.send(Tap(faultButton), timeoutMs = commandTimeoutMs(10_000))
                }.exceptionOrNull()
            check(failure is CommandTransportException) { "$point did not lose transport: $failure" }
            check(failure.code == ErrorCode.INDETERMINATE) {
                "$point produced ${failure.code} instead of INDETERMINATE"
            }
            check(failure.transmissionState == TransmissionState.WRITTEN) {
                "$point failed in unexpected host transmission state ${failure.transmissionState}"
            }
            val poisonedFailure =
                runCatching {
                    session.client.send(Health)
                }.exceptionOrNull()
            check(
                poisonedFailure is CommandTransportException &&
                    poisonedFailure.code == ErrorCode.TRANSPORT_LOST &&
                    poisonedFailure.transmissionState == TransmissionState.NOT_WRITTEN,
            ) { "Lost connection accepted another command: $poisonedFailure" }
            session.journal =
                session.journal.copy(
                    state = JournalState.BROKEN,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            store.write(session.journal)
            cleanupStarted = true
            closed = cleanupFaultSession(adb, serial, bootId, session, store)
            val output = synchronized(session.running.output) { session.running.output.toString() }
            check("TAP_FAULT point=${point.name}" in output) { "Missing driver fault marker for $point" }
            check(observeProcess(adb, serial, FIXTURE_PACKAGE) == fixtureProcess) {
                "AUT process changed during $point recovery"
            }
            requireWithinDeadline()
        } catch (error: Throwable) {
            primaryError = error
            throw error
        } finally {
            if (!cleanupStarted) {
                val journalFailure =
                    runCatching {
                        session.journal =
                            session.journal.copy(
                                state = JournalState.BROKEN,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                        store.write(session.journal)
                    }.exceptionOrNull()
                val physicalCleanupFailure =
                    runCatching {
                        cleanupFaultSession(adb, serial, bootId, session, store)
                    }.exceptionOrNull()
                val cleanupFailure = journalFailure ?: physicalCleanupFailure
                if (journalFailure != null && physicalCleanupFailure != null) {
                    journalFailure.addSuppressed(physicalCleanupFailure)
                }
                if (cleanupFailure != null) {
                    if (primaryError != null) {
                        primaryError.addSuppressed(cleanupFailure)
                    } else {
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    requireWithinDeadline()
    requireCleanupBudget()
    generation = Math.addExact(generation, 1L)
    val verification =
        startFaultSession(
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
        check(observeProcess(adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed before verification" }
        check(
            verification.client.send(WaitVisible(Selector.text("Fault taps: 1")), timeoutMs = commandTimeoutMs(10_000)).ok,
        ) { "Post-mutation transport loss did not produce exactly one tap" }
        delay(500)
        val stableCount = verification.client.execute(Exists(Selector.text("Fault taps: 1")))
        check(stableCount.value) { "Uncertain tap was replayed or completed late" }
        check(observeProcess(adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed during verification" }
        verificationCleanupStarted = true
        closed = cleanupFaultSession(adb, serial, bootId, verification, store)
        requireWithinDeadline()
    } catch (error: Throwable) {
        verificationError = error
        throw error
    } finally {
        if (!verificationCleanupStarted) {
            val cleanupFailure =
                runCatching {
                    cleanupFaultSession(adb, serial, bootId, verification, store)
                }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (verificationError != null) {
                    verificationError.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    requireWithinDeadline()
    requireCleanupBudget()
    generation = Math.addExact(generation, 1L)
    closed =
        runCancelAfterMutationScenario(
            adb,
            serial,
            bootId,
            generation,
            store,
            workDeadline,
            fixtureProcess,
            ::commandTimeoutMs,
        )

    requireWithinDeadline()
    requireCleanupBudget()
    generation = Math.addExact(generation, 1L)
    closed = runHeartbeatExpiryScenario(adb, serial, bootId, generation, store, workDeadline)
    return requireNotNull(closed)
}

/**
 * Watchdog poisoning end to end: the driver runs with a 3 s heartbeat timeout, the host sends
 * nothing after starting a long wait, and the driver must fail the wait with
 * `DRIVER_UNHEALTHY/HEARTBEAT_EXPIRED`, report `TAP_POISONED`, and kill its own process. This
 * is the only scenario in which the driver, not the host, ends the instrumentation.
 */
private suspend fun runHeartbeatExpiryScenario(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    store: SessionJournalStore,
    workDeadline: Long,
): SessionJournal {
    val heartbeatTimeoutMs = 3_000L
    val session =
        startFaultSession(
            adb,
            serial,
            bootId,
            generation,
            TransportFaultPoint.NONE,
            store,
            workDeadline,
            driverArguments = mapOf("tapHeartbeatTimeoutMs" to heartbeatTimeoutMs.toString()),
            hostHeartbeatIntervalMs = 0,
        )
    var cleanupStarted = false
    try {
        val silentSince = System.nanoTime()
        val wait = session.client.submit(WaitVisible(Selector.text("tap-heartbeat-probe-never-visible")), timeoutMs = 30_000)
        val outcome = runCatching { wait.await() }
        val elapsedMs = (System.nanoTime() - silentSince) / 1_000_000L
        val response = outcome.getOrNull()
        val transportError = outcome.exceptionOrNull()
        check(
            (
                response != null && response.errorCode == ErrorCode.DRIVER_UNHEALTHY &&
                    response.detail == ErrorDetail.HEARTBEAT_EXPIRED
            ) ||
                (transportError is CommandTransportException && transportError.code == ErrorCode.TRANSPORT_LOST),
        ) { "Heartbeat expiry did not fail the running wait: response=$response error=$transportError" }
        check(elapsedMs >= heartbeatTimeoutMs) { "Driver poisoned before its heartbeat timeout ($elapsedMs ms)" }
        check(elapsedMs < 20_000) { "Heartbeat expiry took $elapsedMs ms" }
        check(waitForInstrumentationMarker(session.running, "TAP_POISONED", 10_000)) {
            "Driver did not report TAP_POISONED after heartbeat expiry"
        }
        check(session.running.process.waitFor(15, TimeUnit.SECONDS)) {
            "Driver did not kill itself after heartbeat expiry"
        }
        awaitProcessAbsent(adb, serial, DRIVER_PACKAGE)
        println(
            "PHASE_1_HEARTBEAT_EXPIRY_OK serial=$serial failedAfterMs=$elapsedMs " +
                "terminal=${response?.errorCode ?: (transportError as CommandTransportException).code}",
        )
        cleanupStarted = true
        return cleanupFaultSession(adb, serial, bootId, session, store)
    } finally {
        if (!cleanupStarted) runCatching { cleanupFaultSession(adb, serial, bootId, session, store) }
    }
}

/**
 * Cancel arriving after the mutation gate: the driver holds a fault-button tap open for 3 s
 * after its click, the host cancels during the hold, and the awaited result must be the
 * definitive `ok` tap, not `CANCELLED`. The counter must advance exactly once and the session
 * must remain usable.
 */
private suspend fun runCancelAfterMutationScenario(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    store: SessionJournalStore,
    workDeadline: Long,
    fixtureProcess: ProcessObservation,
    commandTimeoutMs: (Long) -> Long,
): SessionJournal {
    val session =
        startFaultSession(
            adb,
            serial,
            bootId,
            generation,
            TransportFaultPoint.CANCEL_AFTER_MUTATION,
            store,
            workDeadline,
        )
    val faultButton = Selector.androidResource(FIXTURE_PACKAGE, "fault_button")
    var cleanupStarted = false
    try {
        check(
            session.client.send(WaitVisible(Selector.text("Fault taps: 1")), timeoutMs = commandTimeoutMs(10_000)).ok,
        ) { "Fault counter was not 1 before the cancel-after-mutation tap" }
        val tap = session.client.submit(Tap(faultButton), timeoutMs = commandTimeoutMs(15_000))
        val marker = "TAP_FAULT point=${TransportFaultPoint.CANCEL_AFTER_MUTATION.name} phase=MUTATED"
        check(waitForInstrumentationMarker(session.running, marker, 10_000)) {
            "Driver did not report the post-click hold"
        }
        check(tap.cancel()) { "In-flight tap was not cancellable at the host" }
        val cancelSentAt = System.nanoTime()
        val result = tap.await()
        val awaitedMs = (System.nanoTime() - cancelSentAt) / 1_000_000L
        check(result.result == Done) {
            "Cancel after mutation did not return the definitive tap result: $result"
        }
        check(
            session.client.send(WaitVisible(Selector.text("Fault taps: 2")), timeoutMs = commandTimeoutMs(10_000)).ok,
        ) { "Cancelled-after-mutation tap did not take effect exactly once" }
        delay(500)
        val stable = session.client.execute(Exists(Selector.text("Fault taps: 2")))
        check(stable.value) { "Fault counter moved after the cancelled tap" }
        check(session.client.send(Health).ok) { "Session unusable after cancel-after-mutation" }
        check(observeProcess(adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed during cancel scenario" }
        println("PHASE_1_CANCEL_AFTER_MUTATION_OK serial=$serial awaitedAfterCancelMs=$awaitedMs")
        cleanupStarted = true
        return cleanupFaultSession(adb, serial, bootId, session, store)
    } finally {
        if (!cleanupStarted) runCatching { cleanupFaultSession(adb, serial, bootId, session, store) }
    }
}

private suspend fun runLateMutationQuarantineScenario(
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
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "$FIXTURE_PACKAGE/.MainActivity",
        timeoutMs = 60_000,
    )
    val fixtureProcess = observeProcess(adb, serial, FIXTURE_PACKAGE)
    val generation = Math.addExact(previousGeneration, 1L)
    val workDeadline = minOf(System.nanoTime() + 180_000_000_000L, scenarioDeadline)
    val session =
        startFaultSession(
            adb,
            serial,
            bootId,
            generation,
            TransportFaultPoint.LATE_UNINTERRUPTIBLE,
            store,
            workDeadline,
        )
    val faultButton = Selector.androidResource(FIXTURE_PACKAGE, "fault_button")
    var lateWorkDelegated = false
    var cleanupStarted = false
    var resetResult: LateResetResult? = null
    var primaryError: Throwable? = null
    var hypotheticalMutationAtNanos = Long.MAX_VALUE
    try {
        check(
            session.client.send(WaitVisible(Selector.text("Fault taps: 0")), timeoutMs = 10_000).ok,
        )
        val failure =
            runCatching {
                session.client.send(Tap(faultButton), timeoutMs = 5_000)
            }.exceptionOrNull()
        hypotheticalMutationAtNanos = System.nanoTime() + 15_000_000_000L
        val delegationMarker = "point=${TransportFaultPoint.LATE_UNINTERRUPTIBLE.name} phase=WORK_DELEGATED"
        lateWorkDelegated =
            waitForInstrumentationMarker(
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
        check(observeProcess(adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT changed before late-work cleanup" }

        session.journal =
            session.journal.copy(
                state = JournalState.QUARANTINED,
                quarantineReason = LATE_MUTATION_QUARANTINE,
                resetRequired = true,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        store.write(session.journal)
        cleanupStarted = true
        session.journal =
            cleanupFaultSession(
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
            val quarantineFailure =
                runCatching {
                    session.journal =
                        session.journal.copy(
                            state = JournalState.QUARANTINED,
                            quarantineReason = LATE_MUTATION_QUARANTINE,
                            resetRequired = true,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                    store.write(session.journal)
                }.exceptionOrNull()
            val cleanupFailure =
                runCatching {
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
            val resetFailure =
                runCatching {
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
        val registered =
            adb
                .run(
                    serial,
                    "shell",
                    "pm",
                    "list",
                    "instrumentation",
                    timeoutMs = remainingTimeoutMs(registrationDeadline, 5_000),
                ).contains("$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner")
        if (registered) break
        sleepWithinDeadline(registrationDeadline, 250)
    }
    check(
        adb
            .run(
                serial,
                "shell",
                "pm",
                "list",
                "instrumentation",
                timeoutMs = remainingTimeoutMs(scenarioDeadline, 5_000),
            ).contains("$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner"),
    ) { "Driver instrumentation was not registered after reset reinstall" }
    adb.run(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        "$FIXTURE_PACKAGE/.MainActivity",
        timeoutMs = remainingTimeoutMs(scenarioDeadline, 60_000),
    )
    val remainingDelayMs =
        ((hypotheticalMutationAtNanos - System.nanoTime()) / 1_000_000L)
            .coerceAtLeast(0L)
    if (remainingDelayMs > 0) sleepWithinDeadline(scenarioDeadline, remainingDelayMs + 500)
    val verification =
        startPostResetVerificationSession(
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
            verification.client.send(WaitVisible(Selector.text("Fault taps: 0")), timeoutMs = 10_000).ok,
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

private suspend fun startPostResetVerificationSession(
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
                    failed.generation == generation,
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
                },
            ) { "Post-reset startup cleanup left an owned forwarding rule" }
            store.write(
                failed.copy(
                    state = JournalState.CLOSED,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            sleepWithinDeadline(scenarioDeadline, 5_000)
        }
    }
    throw IllegalStateException("Driver did not restart after explicit reset", lastFailure)
}

private suspend fun waitForPostResetPackageReadiness(
    adb: Adb,
    serial: String,
    deadlineNanos: Long,
) {
    val apiLevel =
        adb
            .run(
                serial,
                "shell",
                "getprop",
                "ro.build.version.sdk",
                timeoutMs = remainingTimeoutMs(deadlineNanos, 5_000),
            ).toInt()
    if (apiLevel >= 31) {
        val idle =
            adb.runResult(
                serial,
                "shell",
                "am",
                "wait-for-broadcast-idle",
                timeoutMs = remainingTimeoutMs(deadlineNanos, 120_000),
            )
        check(idle.exitCode == 0) { "Android did not reach broadcast-idle after reset: ${idle.output}" }
    } else {
        sleepWithinDeadline(deadlineNanos, 30_000)
    }

    check(
        adb
            .run(
                serial,
                "shell",
                "pm",
                "path",
                DRIVER_PACKAGE,
                timeoutMs = remainingTimeoutMs(deadlineNanos, 30_000),
            ).startsWith("package:"),
    ) {
        "Driver package was not available after reset reinstall"
    }
    check(
        adb
            .run(
                serial,
                "shell",
                "pm",
                "path",
                "$DRIVER_PACKAGE.test",
                timeoutMs = remainingTimeoutMs(deadlineNanos, 30_000),
            ).startsWith("package:"),
    ) {
        "Driver test package was not available after reset reinstall"
    }
}

private fun remainingTimeoutMs(
    deadlineNanos: Long,
    maximumMs: Long,
): Long {
    val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
    check(remainingMs > 0) { "Late mutation reset scenario timed out" }
    return minOf(maximumMs, remainingMs).coerceAtLeast(1L)
}

private fun sleepWithinDeadline(
    deadlineNanos: Long,
    requestedMs: Long,
) {
    val timeoutMs = remainingTimeoutMs(deadlineNanos, requestedMs)
    Thread.sleep(timeoutMs)
    check(timeoutMs == requestedMs) { "Late mutation reset scenario timed out" }
}

private suspend fun awaitProcessAbsent(
    adb: Adb,
    serial: String,
    packageName: String,
) {
    val deadline = System.nanoTime() + 10_000_000_000L
    var consecutiveAbsentSamples = 0
    while (System.nanoTime() < deadline) {
        if (adb.processIds(serial, packageName).isEmpty()) {
            consecutiveAbsentSamples += 1
            if (consecutiveAbsentSamples == 3) return
        } else {
            consecutiveAbsentSamples = 0
        }
        delay(250)
    }
    error("$packageName process remained present after force-stop")
}

private suspend fun resetQuarantinedDevice(
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
        record =
            record.copy(
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
        val candidate =
            runCatching {
                adb.run(
                    serial,
                    "shell",
                    "cat",
                    "/proc/sys/kernel/random/boot_id",
                    timeoutMs = remainingTimeoutMs(deadline, 5_000),
                )
            }.getOrNull()
        val completed =
            runCatching {
                adb.run(
                    serial,
                    "shell",
                    "getprop",
                    "sys.boot_completed",
                    timeoutMs = remainingTimeoutMs(deadline, 5_000),
                )
            }.getOrNull()
        val bootAnimation =
            runCatching {
                adb.run(
                    serial,
                    "shell",
                    "getprop",
                    "init.svc.bootanim",
                    timeoutMs = remainingTimeoutMs(deadline, 5_000),
                )
            }.getOrNull()
        val instrumentationReady =
            runCatching {
                adb
                    .run(
                        serial,
                        "shell",
                        "pm",
                        "list",
                        "instrumentation",
                        timeoutMs = remainingTimeoutMs(deadline, 5_000),
                    ).contains("$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner")
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
        "shell",
        "input",
        "keyevent",
        "KEYCODE_WAKEUP",
        timeoutMs = remainingTimeoutMs(scenarioDeadline, 30_000),
    )
    adb.run(
        serial,
        "shell",
        "wm",
        "dismiss-keyguard",
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
        },
    ) { "Quarantined forward survived explicit reset" }
    check(adb.run(serial, "shell", "pm", "clear", FIXTURE_PACKAGE) == "Success") {
        "AUT data reset did not report success after reboot"
    }
    adb.run(serial, "shell", "am", "force-stop", FIXTURE_PACKAGE)
    awaitProcessAbsent(adb, serial, FIXTURE_PACKAGE)
    val closed =
        record.copy(
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

private suspend fun startFaultSession(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    faultPoint: TransportFaultPoint,
    store: SessionJournalStore,
    deadlineNanos: Long,
    driverArguments: Map<String, String> = emptyMap(),
    hostHeartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
): FaultSession {
    check(System.nanoTime() < deadlineNanos) { "Transport fault session started after its deadline" }
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
            devicePort = DEVICE_PORT_RANGE.first,
        )
    var running: RunningInstrumentation? = null
    var hostPort: Int? = null
    var client: DriverClient? = null
    try {
        running =
            startDriverWithRetry(
                adb,
                serial,
                sessionId,
                generation,
                encodedSecret,
                autPackage = FIXTURE_PACKAGE,
                syncAuthority = SYNC_AUTHORITY,
                overallDeadlineNanos = deadlineNanos,
                driverArguments = fixtureDriverArguments(faultPoint) + driverArguments,
            ) { devicePort ->
                journal = journal.copy(devicePort = devicePort, updatedAtEpochMs = System.currentTimeMillis())
                store.write(journal)
            }
        check(System.nanoTime() < deadlineNanos) { "Driver startup exceeded transport deadline" }
        hostPort = adb.forward(serial, running.devicePort)
        journal =
            journal.copy(
                hostPort = hostPort,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        store.write(journal)
        check(System.nanoTime() < deadlineNanos) { "Forwarding exceeded transport deadline" }
        val driverPid = waitForDriverPid(adb, serial)
        check(System.nanoTime() < deadlineNanos) { "PID observation exceeded transport deadline" }
        journal =
            journal.copy(
                state = JournalState.ACTIVE,
                hostPort = hostPort,
                driverPid = driverPid,
                driverStartToken = processStartToken(adb, serial, driverPid),
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        store.write(journal)
        client = connectWithRetry(hostPort, sessionId, generation, secret, deadlineNanos, serial, hostHeartbeatIntervalMs)
        check(System.nanoTime() < deadlineNanos) { "Connection exceeded transport deadline" }
        check(client.driverInstanceId == running.driverInstanceId)
        check(client.send(Health).ok)
        check(System.nanoTime() < deadlineNanos) { "Health check exceeded transport deadline" }
        journal =
            journal.copy(
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

private suspend fun waitForInstrumentationMarker(
    running: RunningInstrumentation,
    marker: String,
    timeoutMs: Long,
): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadline) {
        if (synchronized(running.output) { marker in running.output }) return true
        if (!running.process.isAlive && !running.outputDrain.isCompleted) break
        delay(25)
    }
    return synchronized(running.output) { marker in running.output }
}

private suspend fun cleanupFaultSession(
    adb: Adb,
    serial: String,
    bootId: String,
    session: FaultSession,
    store: SessionJournalStore,
    terminalState: JournalState = JournalState.CLOSED,
    expectForceStop: Boolean = false,
): SessionJournal {
    var cleanupError: Throwable? = null

    suspend fun capture(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
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
    withTimeoutOrNull(1_000) { session.running.outputDrain.join() }
    if (!session.running.outputDrain.isCompleted) {
        runCatching {
            session.running.process.inputStream
                .close()
        }
        withTimeoutOrNull(1_000) { session.running.outputDrain.join() }
    }
    if (!session.running.outputDrain.isCompleted) {
        capture { error("Fault instrumentation output drain survived cleanup") }
    }
    capture {
        check(adb.run(serial, "shell", "cat", "/proc/sys/kernel/random/boot_id") == bootId) {
            "Boot identity changed during transport cleanup"
        }
    }
    val closed =
        session.journal.copy(
            state = if (cleanupError == null) terminalState else JournalState.QUARANTINED,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    store.write(closed)
    cleanupError?.let { throw IllegalStateException("Transport fault cleanup was uncertain", it) }
    return closed
}

private suspend fun seedOrphanSession(
    adb: Adb,
    serial: String,
    bootId: String,
    generation: Long,
    store: SessionJournalStore,
): Process {
    val sessionId = UUID.randomUUID().toString()
    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    var record =
        SessionJournal(
            state = JournalState.CREATING,
            serial = serial,
            bootId = bootId,
            sessionId = sessionId,
            generation = generation,
            devicePort = DEVICE_PORT,
        )
    store.write(record)
    val process =
        ProcessBuilder(
            "adb",
            "-s",
            serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            "io.github.noamcohen48.tap.driver.TapDriverServerTest",
            "-e",
            "tapSession",
            sessionId,
            "-e",
            "tapGeneration",
            generation.toString(),
            "-e",
            "tapSecret",
            encodedSecret,
            "-e",
            "tapPort",
            DEVICE_PORT.toString(),
            "-e",
            "tapAutPackage",
            FIXTURE_PACKAGE,
            "-e",
            "tapSystemPackages",
            PERMISSION_CONTROLLER_PACKAGE,
            "-e",
            "tapSyncAuthority",
            SYNC_AUTHORITY,
            "$DRIVER_PACKAGE.test/androidx.test.runner.AndroidJUnitRunner",
        ).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    val driverPid = waitForDriverPid(adb, serial)
    val hostPort = adb.forward(serial, DEVICE_PORT)
    record =
        record.copy(
            state = JournalState.ACTIVE,
            hostPort = hostPort,
            driverPid = driverPid,
            driverStartToken = processStartToken(adb, serial, driverPid),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    store.write(record)
    return process
}

private suspend fun waitForDriverPid(
    adb: Adb,
    serial: String,
): Int {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (System.nanoTime() < deadline) {
        val pids = adb.processIds(serial, DRIVER_PACKAGE)
        if (pids.size == 1) return pids.single()
        check(pids.size <= 1) { "Multiple driver processes found: $pids" }
        delay(50)
    }
    error("Driver PID did not appear")
}

/** Runs one synchronization command built from the observed AUT process and proves the call left that process alone. */
private suspend inline fun <C> callSync(
    adb: Adb,
    serial: String,
    client: DriverClient,
    timeoutMs: Long = 5_000,
    command: (observedPid: Int, observedStartToken: String) -> C,
): Result<SyncResult> where C : Command, C : Returning<SyncResult> {
    val before = observeProcess(adb, serial, FIXTURE_PACKAGE)
    val outcome = runCatching { client.execute(command(before.pid, before.startToken), timeoutMs) }
    val after = observeProcess(adb, serial, FIXTURE_PACKAGE)
    check(after == before) { "Synchronization call changed the AUT process: $before -> $after" }
    return outcome
}

private suspend fun awaitSyncIdle(
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
        val state =
            callSync(adb, serial, client, timeoutMs = minOf(5_000, remainingMs)) { pid, token ->
                SyncPoll(pid, token, expectedIdentity.processStartUuid, expectedIdentity.sessionIdentity)
            }.getOrThrow().state
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
        delay(50)
    }
}

private suspend fun assertInvalidAuthenticationWithRetry(
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
        val failure =
            runCatching {
                DriverClient.connect(hostPort, sessionId, generation, wrongSecret).close()
            }.exceptionOrNull()
        if (failure is DriverHandshakeException && failure.message.orEmpty().endsWith(": UNAUTHENTICATED")) return
        lastFailure = failure
        delay(25)
    }
    error("Invalid-authentication check timed out: ${lastFailure?.message}")
}

private fun assertUnsupportedProtocolRejected(
    hostPort: Int,
    sessionId: String,
    generation: Long,
) {
    val nonce =
        ByteArray(32).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
    val hello =
        Hello(
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

private suspend fun benchmark(
    serial: String,
    client: DriverClient,
    selector: Selector,
) {
    val directStarted = System.nanoTime()
    repeat(100) { check(client.execute(Exists(selector)).value) }
    val directMs = (System.nanoTime() - directStarted) / 1_000_000.0 / 100

    val dumpStarted = System.nanoTime()
    repeat(5) { check(client.execute(DumpHierarchy).text.isNotEmpty()) }
    val dumpMs = (System.nanoTime() - dumpStarted) / 1_000_000.0 / 5

    println("PHASE_0_BENCHMARK serial=$serial directAvgMs=$directMs dumpAvgMs=$dumpMs")
}
