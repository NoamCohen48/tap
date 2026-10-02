package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.IntentExtra
import io.github.noamcohen48.tap.api.v1.Orientation
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.wire.v1.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session startup and teardown without a device: a [FakeAdb] for every typed ADB call, a
 * [FakeProcess] (via [ProcessStarter]) for the instrumentation child, and a [FakeDriverServer]
 * for the authenticated driver connection.
 */
class DeviceSessionTest {
    @TempDir
    lateinit var tempDir: Path

    private val serial = "emulator-5554"

    /** A fake whose canned replies open a full session with the forward landing on [hostPort].
     * The driver package is absent during recovery and present (pid 4242) once started. */
    private fun openAdb(hostPort: Int): FakeAdb {
        lateinit var adb: FakeAdb
        adb =
            FakeAdb(
                mapOf(
                    "shell cat /proc/sys/kernel/random/boot_id" to ok("boot-1"),
                    "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                    "shell input keyevent KEYCODE_WAKEUP" to ok(""),
                    "shell wm dismiss-keyguard" to ok(""),
                    "forward tcp:0 tcp:$DEVICE_PORT" to ok(hostPort.toString()),
                    "shell cat /proc/4242/stat" to
                        ok("4242 (app_process) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 99999 20 21"),
                    "forward --remove tcp:$hostPort" to ok(""),
                ),
            )
        // Invoked only after this function returns, so `adb` is always initialized here. The
        // driver is absent during recovery, present once forwarded, and gone again after the
        // second force-stop (recovery's, then cleanup's), mirroring a real force-stop.
        adb.responder = { _, command ->
            when (command) {
                "shell pidof $DRIVER_PACKAGE" -> {
                    val forwarded = adb.calls.any { it.contains("forward tcp:0") }
                    val forceStops = adb.calls.count { it.contains("am force-stop") }
                    if (forwarded && forceStops < 2) ok("4242") else Adb.Result(1, "")
                }

                else -> {
                    null
                }
            }
        }
        return adb
    }

    /**
     * A starter that emits the `TAP_READY` marker for whatever session the command asks for, and
     * hands the session secret from the command to the fake driver: the session generates the
     * secret itself, so the driver only learns it here, before any client connects.
     */
    private fun readyStarter(
        fake: FakeDriverServer,
        processes: MutableList<FakeProcess>,
    ): ProcessStarter =
        ProcessStarter { command ->
            val args = command.drop(3) // executable, -s, serial

            fun option(name: String): String {
                val index = args.indexOf(name)
                require(index >= 0 && index + 1 < args.size) { "no $name in $command" }
                return args[index + 1]
            }
            fake.secret =
                java.util.Base64
                    .getUrlDecoder()
                    .decode(option("tapSecret"))
            val marker =
                "INSTRUMENTATION_RESULT: ok\n" +
                    "TAP_READY session=${option("tapSession")} " +
                    "generation=${option("tapGeneration")} " +
                    "port=${option("tapPort")} instance=test-instance\n"
            FakeProcess(stdout = marker, exitDelayMs = FakeProcess.NEVER).also(processes::add)
        }

    private fun sessionConfig(
        adb: Adb,
        fake: FakeDriverServer,
        processes: MutableList<FakeProcess>,
    ): DeviceSessionConfig =
        DeviceSessionConfig(
            serial = serial,
            journalRoot = tempDir.resolve("sessions"),
            adb = adb,
            processStarter = readyStarter(fake, processes),
        )

    /**
     * An [Adb] that answers every session-open call from canned state but runs `removeForward`
     * through the real process path, so an unreapable child exercises close's quarantine without
     * a device. The driver is absent during recovery, present once forwarded, and gone again
     * after a force-stop, mirroring a real force-stop.
     */
    private class ReapUncertainAdb(
        val hostPort: Int,
        blockingChild: FakeProcess,
    ) : Adb("fake-adb", ProcessStarter { blockingChild }) {
        var driverPresent = false

        override suspend fun bootId(serial: String) = "boot-1"

        override suspend fun wakeAndDismissKeyguard(serial: String) = Unit

        override suspend fun forceStop(
            serial: String,
            packageName: String,
        ) {
            driverPresent = false
        }

        override suspend fun processIds(
            serial: String,
            packageName: String,
        ) = if (driverPresent && packageName == DRIVER_PACKAGE) listOf(4242) else emptyList()

        override suspend fun processStat(
            serial: String,
            pid: Int,
            timeoutMs: Long,
        ) = ProcessStat.Live("99999")

        override suspend fun forwards(serial: String) = emptyList<Forwarding>()

        override suspend fun forward(
            serial: String,
            devicePort: Int,
        ) = hostPort.also { driverPresent = true }
        // removeForward intentionally runs the real path: it reaps the blocking child above.
    }

    private fun journalStore() = SessionJournalStore(tempDir.resolve("sessions"), serial)

    @Test
    fun `cancelled instrumentation attempt cleans up its process and drain`() =
        runBlocking {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                        "shell pidof $DRIVER_PACKAGE" to Adb.Result(1, ""),
                    ),
                )
            val processStarted = CompletableDeferred<FakeProcess>()
            // Stdout stays open until the child is destroyed: with an empty one the attempt could
            // see the output end and fail on its own before the cancel below (seen on CI).
            val starter =
                ProcessStarter { _ ->
                    FakeProcess(exitDelayMs = FakeProcess.NEVER, blockingStdout = true, stdoutEndsOnDestroy = true)
                        .also { processStarted.complete(it) }
                }
            val starting =
                async(Dispatchers.IO) {
                    startDriverWithRetry(
                        adb,
                        serial,
                        "session-1",
                        1,
                        "secret",
                        processStarter = starter,
                        onStarting = {},
                    )
                }
            val process = withTimeout(2_000) { processStarted.await() }
            starting.cancel()
            starting.join()
            assertTrue(
                process.destroyed.get() || process.destroyedForcibly.get(),
                "cancelled attempt must destroy its child",
            )
            withTimeout(2_000) { while (process.isAlive) delay(10) }
            assertTrue(process.stdinClosed.get())
            assertTrue(process.stdoutClosed.get())
            assertTrue(process.stderrClosed.get())
            assertTrue(adb.calls.any { "force-stop" in it }, "cancelled attempt must force-stop")
        }

    @Test
    fun `cancellation during instrumentation process start retains cleanup ownership`() =
        runBlocking {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                        "shell pidof $DRIVER_PACKAGE" to Adb.Result(1, ""),
                    ),
                )
            val child = FakeProcess(exitDelayMs = FakeProcess.NEVER)
            val published = CountDownLatch(1)
            val releaseReturn = CountDownLatch(1)
            val starter =
                ProcessStarter {
                    published.countDown()
                    check(releaseReturn.await(5, TimeUnit.SECONDS))
                    child
                }
            val starting =
                async(Dispatchers.IO) {
                    startDriverWithRetry(
                        adb,
                        serial,
                        "session-1",
                        1,
                        "secret",
                        processStarter = starter,
                        onStarting = {},
                    )
                }
            assertTrue(published.await(2, TimeUnit.SECONDS))
            val original = CancellationException("original cancellation")
            starting.cancel(original)
            assertFalse(starting.isCompleted, "process return must install cleanup ownership")
            releaseReturn.countDown()
            val thrown = assertFailsWith<CancellationException> { starting.await() }
            assertEquals(original.message, thrown.message)
            assertTrue(child.destroyed.get() || child.destroyedForcibly.get())
            assertFalse(child.isAlive)
            assertTrue(child.stdinClosed.get())
            assertTrue(child.stdoutClosed.get())
            assertTrue(child.stderrClosed.get())
        }

    @Test
    fun `failed attempt aborts retries when child or drain cannot be reaped`() =
        runBlocking {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                        "shell pidof $DRIVER_PACKAGE" to Adb.Result(1, ""),
                    ),
                )
            val stubborn =
                FakeProcess(
                    exitDelayMs = FakeProcess.NEVER,
                    survivesDestroy = true,
                    blockingStdout = true,
                    timedWaitAlwaysFalse = true,
                )
            var attempts = 0
            try {
                val failure =
                    assertFailsWith<DeviceQuarantinedException> {
                        startDriverWithRetry(
                            adb,
                            serial,
                            "session-1",
                            1,
                            "secret",
                            overallDeadlineNanos = System.nanoTime() + 100_000_000L,
                            processStarter = ProcessStarter { stubborn },
                            onStarting = { attempts++ },
                        )
                    }
                assertTrue("child survived" in failure.message.orEmpty(), failure.message.orEmpty())
                assertEquals(1, attempts, "an unreaped attempt must prevent the next port retry")
                assertTrue(stubborn.stdinClosed.get())
                assertTrue(stubborn.stdoutClosed.get())
                assertTrue(stubborn.stderrClosed.get())
            } finally {
                stubborn.releaseStdout()
                stubborn.forceExit()
            }
        }

    @Test
    fun `open cancelled after forward creation removes the forward and finalizes the journal`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-open", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                // The forward exists; the open is now suspended in connect or the first command.
                withTimeout(5_000) {
                    while (adb.calls.none { it.contains("forward tcp:0") }) delay(10)
                }
                opening.cancel()
                opening.join()
                assertTrue(opening.isCancelled)
                // Exact forward removal ran despite the cancellation.
                assertTrue(
                    adb.calls.any { it.contains("forward --remove tcp:${fake.port}") },
                    adb.calls.toString(),
                )
                val record =
                    withTimeout(5_000) {
                        var latest = journalStore().read()
                        while (latest == null ||
                            latest.state == JournalState.CREATING ||
                            latest.state == JournalState.ACTIVE
                        ) {
                            delay(10)
                            latest = journalStore().read()
                        }
                        latest
                    }
                assertTrue(
                    record.state == JournalState.CLOSED || record.state == JournalState.QUARANTINED,
                    "journal must be finalized, was ${record.state}",
                )
                // The lease is free again for the next session.
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open cancellation after ready cleans up before ownership transfer`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-ready-handoff", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val config = sessionConfig(adb, fake, processes)
                val reachedTransfer = CompletableDeferred<Unit>()
                val releaseTransfer = CompletableDeferred<Unit>()
                val cleanupFinished = CompletableDeferred<Unit>()
                val hooks = TestSessionHooks()
                hooks.onBeforeOwnershipTransfer = {
                    reachedTransfer.complete(Unit)
                    releaseTransfer.await()
                }
                hooks.onAfterCancellationCleanup = { cleanupFinished.complete(Unit) }
                val opening = async(Dispatchers.IO) { DeviceSession.open(config, hooks) }
                val health = fake.nextFrame(5_000)
                fake.respond(health.requestId, Responses.done(1))
                withTimeout(5_000) { reachedTransfer.await() }
                assertEquals(JournalState.READY, journalStore().read()?.state)

                val original = CancellationException("cancel at ready handoff")
                opening.cancel(original)
                assertFalse(opening.isCompleted, "the ownership transfer gate is still held")
                releaseTransfer.complete(Unit)
                val thrown = assertFailsWith<CancellationException> { opening.await() }
                assertEquals(original.message, thrown.message)

                withTimeout(5_000) { cleanupFinished.await() }
                assertEquals(
                    1,
                    adb.calls.count { it.contains("forward --remove tcp:${fake.port}") },
                    adb.calls.toString(),
                )
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
                assertEquals(1, processes.size)
                val process = processes.single()
                assertTrue(process.destroyed.get() || process.destroyedForcibly.get())
                assertFalse(process.isAlive)
                assertTrue(process.stdinClosed.get())
                assertTrue(process.stdoutClosed.get())
                assertTrue(process.stderrClosed.get())
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open failing after forward creation cleans up and preserves the failure`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-open-fail", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                // The Health command is in flight (WRITTEN) before the driver dies: the drop is
                // sequenced on the received frame, never raced against the connect, so the open
                // fails with transport loss deterministically instead of hanging in a retry. A
                // plain thread drops (not an async child): a failed async child would cancel
                // this scope on top of failing the open, and the test asserts the open's own
                // exception, not scope teardown.
                val droppedFrame = AtomicReference<Frame?>()
                val dropper =
                    thread(isDaemon = true, name = "drop-after-health") {
                        droppedFrame.set(fake.nextFrame(5_000))
                        fake.dropConnection()
                    }
                assertFailsWith<CommandTransportException> {
                    DeviceSession.open(sessionConfig(adb, fake, processes))
                }
                dropper.join(5_000)
                assertFalse(dropper.isAlive, "dropper thread survived")
                assertEquals(FrameType.REQUEST, droppedFrame.get()?.type)
                assertTrue(
                    adb.calls.any { it.contains("forward --remove tcp:${fake.port}") },
                    adb.calls.toString(),
                )
                val record = journalStore().read()
                assertTrue(
                    record != null &&
                        (record.state == JournalState.CLOSED || record.state == JournalState.QUARANTINED),
                    "journal must be finalized, was ${record?.state}",
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `close is bounded when cleanup hangs and still quarantines`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-close", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                assertEquals(JournalState.READY, journalStore().read()?.state)

                adb.hangOn += "forward --remove tcp:${fake.port}"
                val started = System.nanoTime()
                val failure = assertFailsWith<DeviceQuarantinedException> { session.close(timeoutMs = 300) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertTrue(elapsedMs < 10_000, "close took ${elapsedMs}ms")
                assertTrue("quarantined" in failure.message.orEmpty(), failure.message.orEmpty())
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `close maps an unreapable adb child to quarantine and still releases the lease`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-reap", 1, secret, acceptAnySession = true)
            // removeForward's drain ignores cancellation and stream closure; close must still
            // return boundedly, quarantine the journal, and free the per-serial lease.
            val blockingChild = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER, blockingStdout = true)
            val adb = ReapUncertainAdb(fake.port, blockingChild)
            try {
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                assertEquals(JournalState.READY, journalStore().read()?.state)

                val started = System.nanoTime()
                val failure = assertFailsWith<AdbReapUncertainException> { session.close(timeoutMs = 2_000) }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                assertTrue(elapsedMs < 15_000, "close took ${elapsedMs}ms")
                assertTrue(
                    "survived bounded reap" in failure.message.orEmpty(),
                    failure.message.orEmpty(),
                )
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                blockingChild.releaseStdout()
                blockingChild.forceExit()
                fake.close()
            }
        }

    @Test
    fun `open failing with forward reap uncertainty quarantines even when cleanup succeeds`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-forward-uncertain", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val priorResponder = adb.responder
                adb.responder = { serial, command ->
                    if (command.startsWith("forward tcp:0")) {
                        throw AdbReapUncertainException(
                            "ADB process or output drain survived bounded reap: $command",
                            listOf("adb", "-s", serial) + command.split(" "),
                            serial,
                        )
                    }
                    priorResponder?.invoke(serial, command)
                }
                val processes = mutableListOf<FakeProcess>()
                assertFailsWith<AdbReapUncertainException> {
                    DeviceSession.open(sessionConfig(adb, fake, processes))
                }
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_START_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open failing with later reap uncertainty still removes the known forward and quarantines`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-late-uncertain", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val priorResponder = adb.responder
                adb.responder = { serial, command ->
                    val forwarded = adb.calls.any { it.contains("forward tcp:0") }
                    if (forwarded && command == "shell pidof $DRIVER_PACKAGE") {
                        throw AdbReapUncertainException(
                            "ADB process or output drain survived bounded reap: $command",
                            listOf("adb", "-s", serial) + command.split(" "),
                            serial,
                        )
                    }
                    priorResponder?.invoke(serial, command)
                }
                val processes = mutableListOf<FakeProcess>()
                assertFailsWith<AdbReapUncertainException> {
                    DeviceSession.open(sessionConfig(adb, fake, processes))
                }
                assertTrue(
                    adb.calls.any { it.contains("forward --remove tcp:${fake.port}") },
                    "known forward must be removed exactly, was ${adb.calls}",
                )
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_START_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `active reap uncertainty poisons the session and close quarantines without regressing`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-active-poison", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                assertEquals(JournalState.READY, journalStore().read()?.state)

                val priorResponder = adb.responder
                adb.responder = { serial, command ->
                    if (command == "shell pidof com.example") {
                        throw AdbReapUncertainException(
                            "ADB process or output drain survived bounded reap: $command",
                            listOf("adb", "-s", serial) + command.split(" "),
                            serial,
                        )
                    }
                    priorResponder?.invoke(serial, command)
                }
                val app = session.app("com.example")
                assertFailsWith<AdbReapUncertainException> { app.isRunning() }
                assertFailsWith<DeviceQuarantinedException> { app.isRunning() }
                assertFailsWith<DeviceQuarantinedException> { session.app("com.example") }
                assertFailsWith<DeviceQuarantinedException> { session.checkUsable() }

                session.close(timeoutMs = 5_000)
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
                session.close(timeoutMs = 5_000)
                assertEquals(JournalState.QUARANTINED, journalStore().read()?.state)
            } finally {
                fake.close()
            }
        }

    @Test
    fun `rotation mutation captures once and close restores the initial lock`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-rotation", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                var accelerometer = 1
                var userRotation = 2
                val priorResponder = adb.responder
                adb.responder = { requestSerial, command ->
                    when {
                        command == "shell settings get system accelerometer_rotation" -> ok(accelerometer.toString())
                        command == "shell settings get system user_rotation" -> ok(userRotation.toString())
                        command.startsWith("shell settings put system accelerometer_rotation ") -> {
                            accelerometer = command.substringAfterLast(' ').toInt()
                            ok("")
                        }
                        command.startsWith("shell settings put system user_rotation ") -> {
                            userRotation = command.substringAfterLast(' ').toInt()
                            ok("")
                        }
                        else -> priorResponder?.invoke(requestSerial, command)
                    }
                }
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }

                val rotating = async(Dispatchers.IO) {
                    session.client.execute(Commands.setOrientation(Orientation.ORIENTATION_LANDSCAPE))
                }
                val (frame, request) = withTimeout(5_000) { fake.nextRequest() }
                assertTrue(request.command.hasSetOrientation())
                // Model the device-side mutation after the host captured the initial values.
                accelerometer = 0
                userRotation = 1
                fake.respond(frame.requestId, Responses.done(1))
                rotating.await()

                session.close(timeoutMs = 5_000)
                assertEquals(1, accelerometer)
                assertEquals(2, userRotation)
                assertEquals(2, adb.calls.count { it.endsWith("settings get system accelerometer_rotation") })
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            } finally {
                fake.close()
            }
        }

    /** Opens a session over [adb] with the fake driver's health check answered. */
    private suspend fun openWithState(
        name: String,
        state: FakeDeviceState,
        block: suspend (DeviceSession, FakeAdb) -> Unit,
    ) = openWithDriver(name, state) { session, adb, _ -> block(session, adb) }

    private suspend fun openWithDriver(
        name: String,
        state: FakeDeviceState,
        block: suspend (DeviceSession, FakeAdb, FakeDriverServer) -> Unit,
    ) = coroutineScope {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val fake = FakeDriverServer(name, 1, secret, acceptAnySession = true)
        try {
            val adb = openAdb(fake.port)
            val priorResponder = adb.responder
            adb.responder = { requestSerial, command -> state.answer(command) ?: priorResponder?.invoke(requestSerial, command) }
            val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, mutableListOf())) }
            val health = withTimeout(5_000) { fake.nextFrame() }
            fake.respond(health.requestId, Responses.done(1))
            block(withTimeout(5_000) { opening.await() }, adb, fake)
        } finally {
            fake.close()
        }
    }

    @Test
    fun `conditions capture the first value, journal it and close restores it`() =
        runBlocking {
            val animations = StateKey.ANIMATIONS
            val state =
                FakeDeviceState(
                    mapOf(animations[0] to "1.0", animations[1] to "1.0", StateKey.FONT_SCALE to "1.1", StateKey.NightMode.id to "no"),
                )
            openWithState("session-conditions", state) { session, _ ->
                session.conditions.setAnimations(false)
                session.conditions.setFontScale(1.3f)
                session.conditions.setFontScale(1.5f)
                session.conditions.setDarkMode(true)
                session.conditions.setDensity(320)
                session.app("com.example").setLocales(listOf("fr-fr", "en"))
                assertEquals(listOf("fr-FR", "en"), session.app("com.example").locales())
                assertEquals("0", state.values[animations[2]])
                assertEquals("1.5", state.values[StateKey.FONT_SCALE])
                assertEquals("yes", state.values[StateKey.NightMode.id])
                assertEquals("320", state.values[StateKey.Density.id])

                val journaled = journalStore().read()?.savedState.orEmpty()
                assertEquals(
                    animations.zip(listOf("1.0", "1.0", null)).map { SavedState(it.first, it.second) } +
                        listOf(
                            SavedState(StateKey.FONT_SCALE, "1.1"),
                            SavedState(StateKey.NightMode.id, "no"),
                            SavedState(StateKey.Density.id, null),
                            SavedState(StateKey.AppLocales("com.example").id, ""),
                        ),
                    journaled,
                )

                session.close(timeoutMs = 5_000)
                assertEquals(
                    mapOf(
                        animations[0] to "1.0",
                        animations[1] to "1.0",
                        animations[2] to null,
                        StateKey.FONT_SCALE to "1.1",
                        StateKey.NightMode.id to "no",
                        StateKey.Density.id to null,
                        StateKey.AppLocales("com.example").id to "",
                    ),
                    state.values,
                )
                val closed = journalStore().read()
                assertEquals(JournalState.CLOSED, closed?.state)
                assertEquals(emptyList(), closed?.savedState)
                val file = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(serial.encodeToByteArray()) + ".json"
                assertFalse("savedState" in Files.readString(tempDir.resolve("sessions").resolve(file)))
            }
        }

    @Test
    fun `network switches capture all three, airplane mode goes first and comes back first`() =
        runBlocking {
            val state = FakeDeviceState(mapOf(FakeDeviceState.AIRPLANE to "0", FakeDeviceState.WIFI to "1", FakeDeviceState.DATA to "1"))
            openWithState("session-network", state) { session, adb ->
                session.conditions.setNetwork(airplaneMode = true, wifi = true, mobileData = false)
                assertEquals(listOf("1", "2", "0"), listOf(FakeDeviceState.AIRPLANE, FakeDeviceState.WIFI, FakeDeviceState.DATA).map { state.values[it] })
                val writes = adb.calls.filter { "airplane-mode" in it || "svc" in it }
                assertTrue(writes.first().endsWith("airplane-mode enable"), "$writes")
                assertEquals(
                    listOf(
                        SavedState(StateKey.Network.WIFI.id, "1"),
                        SavedState(StateKey.Network.MOBILE_DATA.id, "1"),
                        SavedState(StateKey.Network.AIRPLANE.id, "0"),
                    ),
                    journalStore().read()?.savedState,
                )
                session.conditions.setNetwork(wifi = false, airplaneMode = null, mobileData = null)
                assertEquals("0", state.values[FakeDeviceState.WIFI])

                val before = adb.calls.size
                session.close(timeoutMs = 5_000)
                val restores = adb.calls.drop(before).filter { "airplane-mode" in it || "svc" in it }
                assertTrue(restores.first().endsWith("airplane-mode disable"), "$restores")
                assertEquals(listOf("0", "1", "1"), listOf(FakeDeviceState.AIRPLANE, FakeDeviceState.WIFI, FakeDeviceState.DATA).map { state.values[it] })
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            }
        }

    @Test
    fun `the device locale goes through the driver receiver, is read back and comes back on detach`() =
        runBlocking {
            val state = FakeDeviceState(productLocale = "en-GB")
            openWithState("session-system-locale", state) { session, adb ->
                session.conditions.setSystemLocales(listOf("fr-fr", "en-US"))
                assertEquals("fr-FR,en-US", state.values[FakeDeviceState.SYSTEM_LOCALES])
                assertTrue(adb.calls.any { "pm grant $DRIVER_PACKAGE android.permission.CHANGE_CONFIGURATION" in it }, "${adb.calls}")
                assertTrue(adb.calls.any { "appops set $DRIVER_PACKAGE WRITE_SETTINGS allow" in it }, "${adb.calls}")
                // Never set before: the build's locale is what was there.
                assertEquals(listOf(SavedState(StateKey.SystemLocales.id, "en-GB")), journalStore().read()?.savedState)
                session.conditions.setSystemLocales(listOf("de-DE"))

                session.close(timeoutMs = 5_000)
                assertEquals("en-GB", state.values[FakeDeviceState.SYSTEM_LOCALES])
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            }
        }

    @Test
    fun `a locale the receiver refuses is a failure, and nothing changed stays captured`() =
        runBlocking {
            val state = FakeDeviceState(mapOf(FakeDeviceState.SYSTEM_LOCALES to "en-US"), localeReceiverFails = true)
            openWithState("session-system-locale-refused", state) { session, _ ->
                val error = assertFailsWith<AdbCommandException> { session.conditions.setSystemLocales(listOf("fr-FR")) }
                assertTrue("result=2" in error.message.orEmpty(), error.message)
                assertFailsWith<IllegalArgumentException> { session.conditions.setSystemLocales(emptyList()) }
                assertFailsWith<IllegalArgumentException> { session.conditions.setSystemLocales(listOf("not a tag")) }
                assertEquals("en-US", state.values[FakeDeviceState.SYSTEM_LOCALES])
            }
        }

    @Test
    fun `a pushed file reads back, is pulled, never overwrites a device file and is removed on detach`() =
        runBlocking {
            val state = FakeDeviceState()
            state.files["/data/local/tmp/theirs.txt"] = "keep".toByteArray()
            state.files["/system/build.prop"] = "ro.build".toByteArray()
            state.unreadable += "/system/build.prop"
            val local = Files.createTempFile("tap-push", ".txt").also { Files.writeString(it, "hello") }
            val pulled = Files.createTempFile("tap-pull", ".txt")
            try {
                openWithState("session-files", state) { session, _ ->
                    session.files.push(local, "/data/local/tmp/notes.txt")
                    assertEquals("hello", state.files["/data/local/tmp/notes.txt"]?.decodeToString())
                    assertEquals(
                        listOf(SavedState(StateKey.DeviceFile("/data/local/tmp/notes.txt").id, null)),
                        journalStore().read()?.savedState,
                    )
                    // Its own file it may replace; one it did not create it never touches.
                    Files.writeString(local, "hello again")
                    session.files.push(local, "/data/local/tmp/notes.txt")
                    assertFailsWith<DeviceFileException> { session.files.push(local, "/data/local/tmp/theirs.txt") }
                    assertFailsWith<DeviceFileException> { session.files.push(local, "/data/local/tmp/missing/notes.txt") }
                    assertFailsWith<IllegalArgumentException> { session.files.push(local, "relative.txt") }
                    assertFailsWith<IllegalArgumentException> { session.files.push(local, "/data/local/tmp/../notes.txt") }

                    session.files.pull("/data/local/tmp/theirs.txt", pulled)
                    assertEquals("keep", Files.readString(pulled))
                    assertFailsWith<DeviceFileException> { session.files.pull("/data/local/tmp/absent.txt", pulled) }
                    assertFailsWith<DeviceFileException> { session.files.pull("/data/local/tmp", pulled) }
                    // Refused before adb pull, not reported as a failed adb command.
                    assertFailsWith<DeviceFileException> { session.files.pull("/system/build.prop", pulled) }

                    session.close(timeoutMs = 5_000)
                    assertEquals(setOf("/data/local/tmp/theirs.txt", "/system/build.prop"), state.files.keys)
                    assertEquals(JournalState.CLOSED, journalStore().read()?.state)
                }
            } finally {
                Files.deleteIfExists(local)
                Files.deleteIfExists(pulled)
            }
        }

    @Test
    fun `media goes to the gallery folders, is indexed and leaves the device and the index on detach`() =
        runBlocking {
            val state = FakeDeviceState()
            val local = Files.createTempFile("tap-media", ".bin").also { Files.write(it, byteArrayOf(1, 2, 3)) }
            try {
                openWithState("session-media", state) { session, _ ->
                    assertEquals("/sdcard/Pictures/Tap/cat.jpg", session.files.addMedia(local, "cat.jpg"))
                    assertEquals("/sdcard/Movies/Tap/clip.mp4", session.files.addMedia(local, "clip.mp4"))
                    assertEquals(setOf("/sdcard/Pictures/Tap/cat.jpg", "/sdcard/Movies/Tap/clip.mp4"), state.mediaIndex)
                    assertFailsWith<IllegalArgumentException> { session.files.addMedia(local, "notes.txt") }
                    assertFailsWith<IllegalArgumentException> { session.files.addMedia(local, "../cat.jpg") }

                    session.close(timeoutMs = 5_000)
                    assertEquals(emptySet(), state.files.keys)
                    assertEquals(emptySet(), state.mediaIndex)
                    assertFalse("/sdcard/Pictures/Tap" in state.directories)
                    assertTrue("/sdcard/Pictures" in state.directories)
                }
            } finally {
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `on API 29 media is indexed by the scan broadcast, which lands after it returns`() =
        runBlocking {
            val state = FakeDeviceState(apiLevel = 29).apply { broadcastScanQueries = 3 }
            val local = Files.createTempFile("tap-media", ".bin").also { Files.write(it, byteArrayOf(1)) }
            try {
                openWithState("session-media-api29", state) { session, adb ->
                    assertEquals("/sdcard/Pictures/Tap/cat.png", session.files.addMedia(local, "cat.png"))
                    assertEquals(setOf("/sdcard/Pictures/Tap/cat.png"), state.mediaIndex)
                    assertEquals(1, adb.calls.count { "MEDIA_SCANNER_SCAN_FILE" in it })
                    session.close(timeoutMs = 5_000)
                    assertEquals(emptySet(), state.files.keys)
                }
            } finally {
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `media the scanner does not index is a failure, and the file still leaves on detach`() =
        runBlocking {
            val state = FakeDeviceState().apply { mediaScannerIgnores = true }
            val local = Files.createTempFile("tap-media", ".bin").also { Files.write(it, byteArrayOf(1)) }
            try {
                openWithState("session-media-unindexed", state) { session, _ ->
                    assertFailsWith<DeviceFileException> { session.files.addMedia(local, "cat.png") }
                    session.close(timeoutMs = 5_000)
                    assertEquals(emptySet(), state.files.keys)
                }
            } finally {
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a mock location makes the driver the mock app, turns location on and both come back on detach`() =
        runBlocking {
            val state = FakeDeviceState(mapOf(StateKey.LOCATION_MODE to "0"))
            openWithDriver("session-location", state) { session, _, fake ->
                val setting = async(Dispatchers.IO) { session.conditions.setLocation(48.8584, 2.2945, 3.5f, null) }
                val (frame, request) = withTimeout(5_000) { fake.nextRequest() }
                assertEquals(48.8584, request.command.setLocation.latitude)
                assertTrue(request.command.setLocation.hasAccuracyM() && !request.command.setLocation.hasAltitudeM())
                // The app-op and location are on before the driver is asked.
                assertEquals("allow", state.values[StateKey.DRIVER_MOCK_LOCATION])
                assertEquals("3", state.values[StateKey.LOCATION_MODE])
                fake.respond(frame.requestId, Responses.done())
                setting.await()
                assertEquals(
                    listOf(SavedState(StateKey.LOCATION_MODE, "0"), SavedState(StateKey.DRIVER_MOCK_LOCATION, "default")),
                    journalStore().read()?.savedState,
                )

                session.close(timeoutMs = 5_000)
                assertEquals("0", state.values[StateKey.LOCATION_MODE])
                assertEquals("default", state.values[StateKey.DRIVER_MOCK_LOCATION])
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            }
        }

    @Test
    fun `wifi turned on under airplane mode is restored off under it`() =
        runBlocking {
            val state = FakeDeviceState(mapOf(FakeDeviceState.AIRPLANE to "1", FakeDeviceState.WIFI to "0", FakeDeviceState.DATA to "0"))
            openWithState("session-network-airplane", state) { session, _ ->
                session.conditions.setNetwork(airplaneMode = null, wifi = true, mobileData = null)
                assertEquals("2", state.values[FakeDeviceState.WIFI])
                session.close(timeoutMs = 5_000)
                assertEquals(listOf("1", "0"), listOf(FakeDeviceState.AIRPLANE, FakeDeviceState.WIFI).map { state.values[it] })
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            }
        }

    @Test
    fun `a device on adb over the network refuses to cut it`() {
        assertTrue(overNetwork("192.168.1.20:5555"))
        assertTrue(overNetwork("adb-R58M123-AbCdEf._adb-tls-connect._tcp"))
        assertFalse(overNetwork("emulator-5554"))
        assertFalse(overNetwork("85e49002"))
    }

    @Test
    fun `a condition below its API level is refused before anything is captured`() =
        runBlocking {
            val state = FakeDeviceState(apiLevel = 28)
            openWithState("session-conditions-api", state) { session, adb ->
                val refused = assertFailsWith<UnsupportedApiException> { session.conditions.setDarkMode(true) }
                assertEquals(29, refused.requiredApi)
                assertFailsWith<UnsupportedApiException> { session.app("com.example").setLocales(listOf("fr-FR")) }
                assertFailsWith<IllegalArgumentException> { session.app("com.example").setLocales(listOf("not a tag")) }
                assertTrue(adb.calls.none { "uimode" in it || "locale" in it }, "${adb.calls}")
                assertEquals(1, adb.calls.count { it.endsWith("getprop ro.build.version.sdk") })
                assertEquals(emptyList(), journalStore().read()?.savedState)
                session.close(timeoutMs = 5_000)
            }
        }

    @Test
    fun `dark mode on a device that locks it is refused and says so`() =
        runBlocking {
            val state = FakeDeviceState(mapOf(StateKey.NightMode.id to "no"), nightModeLocked = true)
            openWithState("session-conditions-night-locked", state) { session, _ ->
                val refused = assertFailsWith<DeviceSettingException> { session.conditions.setDarkMode(true) }
                assertTrue("locks the day/night mode" in refused.message.orEmpty(), refused.message)
                session.close(timeoutMs = 5_000)
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            }
        }

    @Test
    fun `a value the device does not take is reported and the original still comes back`() =
        runBlocking {
            val state = FakeDeviceState(mapOf(StateKey.FONT_SCALE to "1.0"))
            openWithState("session-conditions-stuck", state) { session, _ ->
                state.stuck += StateKey.FONT_SCALE
                state.values[StateKey.FONT_SCALE] = "1.15"
                val failure = assertFailsWith<DeviceSettingException> { session.conditions.setFontScale(1.3f) }
                assertTrue("font_scale=1.15 (expected 1.3)" in failure.message.orEmpty(), failure.message)
                state.stuck.clear()
                session.close(timeoutMs = 5_000)
                assertEquals("1.15", state.values[StateKey.FONT_SCALE])
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
            }
        }

    @Test
    fun `rotation restoration failure is visible and quarantines`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-rotation-failure", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val priorResponder = adb.responder
                adb.responder = { requestSerial, command ->
                    when (command) {
                        "shell settings get system accelerometer_rotation" -> ok("0")
                        "shell settings get system user_rotation" -> ok("2")
                        "shell settings put system accelerometer_rotation 0" ->
                            throw AdbCommandException(requestSerial, command.split(' '), 1, "denied")
                        else -> priorResponder?.invoke(requestSerial, command)
                    }
                }
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, mutableListOf())) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }

                val rotating = async(Dispatchers.IO) {
                    session.client.execute(Commands.setOrientation(Orientation.ORIENTATION_PORTRAIT))
                }
                val (frame, request) = withTimeout(5_000) { fake.nextRequest() }
                assertTrue(request.command.hasSetOrientation())
                fake.respond(frame.requestId, Responses.done(1))
                rotating.await()

                assertFailsWith<AdbCommandException> { session.close(timeoutMs = 5_000) }
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(record?.quarantineReason?.contains("SESSION_CLEANUP_UNCERTAIN") == true)
            } finally {
                fake.close()
            }
        }

    @Test
    fun `captured client after poison emits no frame`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-captured", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                val captured = session.client
                // Poison through the session's sticky state, as a reap-uncertain APP call would.
                session.noteReapUncertain(
                    AdbReapUncertainException(
                        "ADB process or output drain survived bounded reap",
                        listOf("adb", "-s", serial, "shell", "pidof", "com.example"),
                        serial,
                    ),
                )
                assertFailsWith<DeviceQuarantinedException> { captured.submit(io.github.noamcohen48.tap.protocol.Requests.health()) }
                // No frame was emitted: the next frame poll times out instead of delivering a REQUEST.
                val noFrame =
                    try {
                        withTimeout(300) { fake.nextFrame() }
                        false
                    } catch (_: Exception) {
                        true
                    }
                assertTrue(noFrame, "poisoned submit emitted a frame")
                assertFailsWith<DeviceQuarantinedException> { session.app("com.example") }
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open failing at bootId quarantines with the unknown-boot sentinel and later open rejects without erasing it`() =
        runBlocking {
            var bootIdAttempts = 0
            val adb = FakeAdb(mapOf("shell am force-stop $DRIVER_PACKAGE" to ok("")))
            adb.responder = { failSerial, command ->
                if (command == "shell cat /proc/sys/kernel/random/boot_id") {
                    bootIdAttempts++
                    if (bootIdAttempts == 1) {
                        throw AdbReapUncertainException(
                            "ADB process or output drain survived bounded reap: $command",
                            listOf("adb", "-s", failSerial) + command.split(" "),
                            failSerial,
                        )
                    }
                    ok("boot-1")
                } else {
                    null
                }
            }
            val fake = FakeDriverServer("unused", 1, ByteArray(32), acceptAnySession = true)
            try {
                assertFailsWith<AdbReapUncertainException> {
                    DeviceSession.open(sessionConfig(adb, fake, mutableListOf()))
                }
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                // Sentinel: the boot identity was never proven and no prior journal existed.
                assertEquals(UNKNOWN_BOOT_ID, record?.bootId)
                assertEquals(0, record?.generation)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_START_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                val reason = record?.quarantineReason
                journalStore().acquireLease(0).close()
                // A later open rejects on the quarantine and reconciles nothing away: the
                // sentinel record (state and reason) survives instead of being erased.
                val rejected =
                    assertFailsWith<DeviceQuarantinedException> {
                        DeviceSession.open(sessionConfig(adb, fake, mutableListOf()))
                    }
                assertTrue("quarantined" in rejected.message.orEmpty(), rejected.message.orEmpty())
                val reread = journalStore().read()
                assertEquals(JournalState.QUARANTINED, reread?.state)
                assertEquals(UNKNOWN_BOOT_ID, reread?.bootId)
                assertEquals(reason, reread?.quarantineReason)
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open failing in closed-journal recovery quarantines and frees the lease`() =
        runBlocking {
            // Seed an actual CLOSED journal: recovery must reconcile it (force-stop, forward
            // cleanup) rather than treat the device as never-opened, and the reap-uncertain
            // force-stop must still quarantine without losing the prior generation/identity.
            val store = journalStore()
            val seeded =
                SessionJournal(
                    state = JournalState.CLOSED,
                    serial = serial,
                    bootId = "boot-1",
                    sessionId = "prior-session",
                    generation = 5,
                    devicePort = DEVICE_PORT,
                    hostPort = 41001,
                )
            store.write(seeded)
            val adb = FakeAdb(mapOf("shell cat /proc/sys/kernel/random/boot_id" to ok("boot-1")))
            adb.responder = { failSerial, command ->
                if (command == "shell am force-stop $DRIVER_PACKAGE") {
                    throw AdbReapUncertainException(
                        "ADB process or output drain survived bounded reap: $command",
                        listOf("adb", "-s", failSerial) + command.split(" "),
                        failSerial,
                    )
                }
                null
            }
            val fake = FakeDriverServer("unused", 1, ByteArray(32), acceptAnySession = true)
            try {
                assertFailsWith<AdbReapUncertainException> {
                    DeviceSession.open(sessionConfig(adb, fake, mutableListOf()))
                }
                val record = store.read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertEquals(5, record?.generation)
                assertEquals("prior-session", record?.sessionId)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_START_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open failing at wake-dismiss quarantines and frees the lease`() =
        runBlocking {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell cat /proc/sys/kernel/random/boot_id" to ok("boot-1"),
                        "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                        "shell pidof $DRIVER_PACKAGE" to Adb.Result(1, ""),
                        "shell input keyevent KEYCODE_WAKEUP" to ok(""),
                    ),
                )
            adb.responder = { serial, command ->
                if (command == "shell wm dismiss-keyguard") {
                    throw AdbReapUncertainException(
                        "ADB process or output drain survived bounded reap: $command",
                        listOf("adb", "-s", serial) + command.split(" "),
                        serial,
                    )
                }
                null
            }
            val fake = FakeDriverServer("unused", 1, ByteArray(32), acceptAnySession = true)
            try {
                assertFailsWith<AdbReapUncertainException> {
                    DeviceSession.open(sessionConfig(adb, fake, mutableListOf()))
                }
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_START_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `open failing at install quarantines and frees the lease`() =
        runBlocking {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell cat /proc/sys/kernel/random/boot_id" to ok("boot-1"),
                        "shell am force-stop $DRIVER_PACKAGE" to ok(""),
                        "shell pidof $DRIVER_PACKAGE" to Adb.Result(1, ""),
                        "shell input keyevent KEYCODE_WAKEUP" to ok(""),
                        "shell wm dismiss-keyguard" to ok(""),
                    ),
                )
            adb.responder = { serial, command ->
                if (command.startsWith("install ")) {
                    throw AdbReapUncertainException(
                        "ADB process or output drain survived bounded reap: $command",
                        listOf("adb", "-s", serial) + command.split(" "),
                        serial,
                    )
                }
                null
            }
            val fake = FakeDriverServer("unused", 1, ByteArray(32), acceptAnySession = true)
            try {
                val processes = mutableListOf<FakeProcess>()
                val apk = tempDir.resolve("driver.apk").also { java.nio.file.Files.write(it, byteArrayOf(1)) }
                val base = sessionConfig(adb, fake, processes)
                val config = base.copy(driverApk = apk)
                assertFailsWith<AdbReapUncertainException> { DeviceSession.open(config) }
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state)
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_START_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `close awaits an in-flight adb through its poison point and quarantines`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-close-race", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val hooks = TestSessionHooks()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes), hooks) }
                val health = withTimeout(5_000) { fake.nextFrame() }
                fake.respond(health.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                // Barrier: the APP call is admitted inside guardAdb before close begins; its reap
                // failure is released only after close started waiting for it.
                val admitted = CompletableDeferred<Unit>()
                val releaseFailure = CompletableDeferred<Unit>()
                val priorResponder = adb.responder
                adb.responder = { serial, command ->
                    if (command == "shell pidof com.example") {
                        admitted.complete(Unit)
                        runBlocking { releaseFailure.await() }
                        throw AdbReapUncertainException(
                            "ADB process or output drain survived bounded reap: $command",
                            listOf("adb", "-s", serial) + command.split(" "),
                            serial,
                        )
                    }
                    priorResponder?.invoke(serial, command)
                }
                val app = session.app("com.example")
                val inFlight = async(Dispatchers.IO) { runCatching { app.isRunning() } }
                withTimeout(5_000) { admitted.await() }
                // Explicit barrier: the probe proves close entered its admitted-operation drain
                // before the failure is released, so the late poison deterministically wins.
                val closeWaiting = CompletableDeferred<Unit>()
                hooks.onCloseDrainWaitCall = { closeWaiting.complete(Unit) }
                val closing = async(Dispatchers.IO) { runCatching { session.close(timeoutMs = 10_000) } }
                withTimeout(5_000) { closeWaiting.await() }
                assertFalse(closing.isCompleted, "close must still wait for the admitted operation")
                releaseFailure.complete(Unit)
                withTimeout(10_000) { inFlight.await() }
                withTimeout(10_000) { closing.await() }
                val record = journalStore().read()
                assertEquals(JournalState.QUARANTINED, record?.state, "late poison must win over CLOSED")
                assertTrue(
                    record?.quarantineReason?.startsWith("SESSION_CLEANUP_UNCERTAIN") == true,
                    record?.quarantineReason,
                )
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `gated guardAdb never poisons and the session still closes clean`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-gate-clean", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                // A never-started gate rejection (another serial owns residual capacity) must not
                // poison: the guarded block throws before any process start, with attempted and
                // blocking diagnostics preserved.
                val callsBefore = adb.calls.size
                val gated =
                    assertFailsWith<AdbRunnerGatedException> {
                        session.guardAdb {
                            throw AdbRunnerGatedException(
                                "ADB runner gated by unreaped drain (adb -s other-serial shell pidof x on other-serial); refusing start for $serial",
                                serial,
                                "other-serial",
                                listOf("adb", "-s", "other-serial", "shell", "pidof", "x"),
                            )
                        }
                    }
                assertEquals(serial, gated.attemptSerial)
                assertEquals("other-serial", gated.blockingSerial)
                assertEquals(callsBefore, adb.calls.size, "gated call must start no process")
                // The session is still fully usable and closes CLOSED: temporary, never sticky.
                session.checkUsable()
                session.app("com.example")
                session.close(timeoutMs = 5_000)
                assertEquals(JournalState.CLOSED, journalStore().read()?.state)
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `cancelled guarded operation still releases its lease so close drains clean`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-op-lease", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val hooks = TestSessionHooks()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes), hooks) }
                fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                // Admit an operation and park it inside the guarded block, then cancel it with its
                // lease release parked (NonCancellable) while close starts draining: the release
                // must still land after the barrier opens, so close drains instead of timing out.
                val opGate = CompletableDeferred<Unit>()
                val opAdmitted = CompletableDeferred<Unit>()
                val releaseParked = CompletableDeferred<Unit>()
                val releaseGate = CompletableDeferred<Unit>()
                hooks.onBeforeOperationRelease = {
                    releaseParked.complete(Unit)
                    releaseGate.await()
                }
                val op =
                    async {
                        session.guardAdb {
                            opAdmitted.complete(Unit)
                            opGate.await()
                        }
                    }
                withTimeout(5_000) { opAdmitted.await() }
                val original = CancellationException("guarded operation cancelled")
                op.cancel(original)
                withTimeout(5_000) { releaseParked.await() }
                val closing = async { session.close(timeoutMs = 10_000) }
                assertFalse(op.isCompleted, "the lease release is parked on the barrier")
                releaseGate.complete(Unit)
                // The original cancellation survives the lease release ...
                val thrown = assertFailsWith<CancellationException> { op.await() }
                assertEquals(original.message, thrown.message)
                // ... and close drains without a false timeout: CLOSED, never quarantine.
                withTimeout(15_000) { closing.await() }
                val record = journalStore().read()
                assertEquals(JournalState.CLOSED, record?.state)
                assertNull(record?.quarantineReason)
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    @Test
    fun `am start output is judged by line prefix, not by substring`() {
        val ok =
            """
            Starting: Intent { cmp=com.x.exceptions/.ErrorActivity }
            Status: ok
            LaunchState: COLD
            Activity: com.x.exceptions/.ErrorActivity
            TotalTime: 412
            Complete
            """.trimIndent()
        assertNull(AmStartOutput.failure(ok))
        assertNull(AmStartOutput.failure("Starting: Intent { cmp=a/.B }\nStatus: timeout\nComplete"))
        assertNull(
            AmStartOutput.failure(
                "Warning: Activity not started, its current task has been brought to the front\nStatus: ok",
            ),
        )
        assertEquals(
            "Error: Activity class {com.example/.Missing} does not exist.",
            AmStartOutput.failure("Starting: Intent { cmp=com.example/.Missing }\nError: Activity class {com.example/.Missing} does not exist."),
        )
        assertEquals("Error type 3", AmStartOutput.failure("Starting: x\nError type 3\nError: nope"))
        assertEquals(
            "java.lang.SecurityException: Permission Denial: starting Intent",
            AmStartOutput.failure("Starting: x\njava.lang.SecurityException: Permission Denial: starting Intent"),
        )
        assertEquals("Status: error", AmStartOutput.failure("Starting: x\nStatus: error"))
        assertEquals("com.x.exceptions/.ErrorActivity", AmStartOutput.activity(ok))
        assertNull(AmStartOutput.activity("Starting: Intent { cmp=a/.B }\nStatus: ok\nComplete"))
        assertNull(AmStartOutput.activity("Status: ok\nActivity: unknown\nComplete"))
    }

    @Test
    fun `clearData returns only once the app's activity is destroyed too`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-clear-data", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val session = openSession(adb, fake)
                val exiting = "    * Hist  #0: ActivityRecord{1017c6d u0 com.example/.Main t794 f} isExiting}"
                val priorResponder = adb.responder
                adb.responder = { serial, command ->
                    when (command) {
                        "shell pm clear com.example" -> ok("Success")
                        "shell pidof com.example" -> Adb.Result(1, "")
                        // The process is gone at once; the record outlives it for two polls.
                        "shell dumpsys activity activities" ->
                            ok(if (adb.calls.count { "dumpsys activity" in it } <= 2) exiting else "")
                        else -> priorResponder?.invoke(serial, command)
                    }
                }
                withTimeout(5_000) { session.app("com.example").clearData(timeoutMs = 2_000) }
                assertEquals(3, adb.calls.count { "dumpsys activity activities" in it })

                // A record that never goes is a timeout naming it, not a silent return.
                adb.responder = { serial, command ->
                    when (command) {
                        "shell am force-stop com.example" -> ok("")
                        "shell pidof com.example" -> Adb.Result(1, "")
                        "shell dumpsys activity activities" -> ok(exiting)
                        else -> priorResponder?.invoke(serial, command)
                    }
                }
                val timeout = assertFailsWith<HostWaitTimeoutException> { session.app("com.example").forceStop(timeoutMs = 300) }
                assertTrue("an activity still exiting" in timeout.message.orEmpty(), timeout.message)
                session.close(timeoutMs = 5_000)
            } finally {
                fake.close()
            }
        }

    @Test
    fun `launch returns after am start without waiting on the driver`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-launch-deadline", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val session = openSession(adb, fake)
                val priorResponder = adb.responder
                adb.responder = { serial, command ->
                    if (command == "shell am start -W -n com.example/.Main") {
                        Thread.sleep(400)
                        ok("Starting: Intent { cmp=com.example/.ErrorActivity }\nStatus: ok\nComplete")
                    } else {
                        priorResponder?.invoke(serial, command)
                    }
                }
                // Nothing answers driver frames here: a visibility wait would time out and fail the launch.
                withTimeout(5_000) { session.app("com.example").launch(".Main", timeoutMs = 2_000) }
                session.close(timeoutMs = 5_000)
            } finally {
                fake.close()
            }
        }

    @Test
    fun `foreground sends the launcher intent, openLink reports the started activity, the driver is no app`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-foreground-link", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val session = openSession(adb, fake)
                val priorResponder = adb.responder
                val starts = java.util.concurrent.CopyOnWriteArrayList<String>()
                adb.responder = { serial, command ->
                    when {
                        command.startsWith("shell cmd package resolve-activity") -> ok("priority=0\ncom.example/.Main")
                        command.startsWith("shell am start") -> {
                            starts += command
                            when {
                                "missing://" in command -> ok("Starting: Intent { act=android.intent.action.VIEW }\nError: Activity not started, unable to resolve Intent")
                                "-p com.example" in command -> ok("Starting: Intent { dat=test://orders/42 }\nStatus: ok\nActivity: com.example/.Orders\nComplete")
                                else -> ok("Starting: Intent { cmp=com.example/.Main }\nStatus: ok\nComplete")
                            }
                        }
                        else -> priorResponder?.invoke(serial, command)
                    }
                }
                val app = session.app("com.example")
                app.foreground(timeoutMs = 2_000)
                assertEquals("com.example/.Orders", app.openLink("test://orders/42", anyApp = false, timeoutMs = 2_000))
                assertNull(app.openLink("https://example.com/a?b=1&c=2", anyApp = true, timeoutMs = 2_000))
                val failure = assertFailsWith<AppLifecycleException> { app.openLink("missing://x", anyApp = false, timeoutMs = 2_000) }
                assertTrue("unable to resolve Intent" in failure.message.orEmpty(), failure.message)
                assertEquals(
                    listOf(
                        "shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.example/.Main",
                        "shell am start -W -a android.intent.action.VIEW -d test://orders/42 -p com.example",
                        "shell am start -W -a android.intent.action.VIEW -d 'https://example.com/a?b=1&c=2'",
                        "shell am start -W -a android.intent.action.VIEW -d missing://x -p com.example",
                    ),
                    starts,
                )
                for (driver in listOf(DRIVER_PACKAGE, DRIVER_TEST_PACKAGE)) {
                    assertFailsWith<IllegalArgumentException> { session.app(driver) }
                }
                session.close(timeoutMs = 5_000)
            } finally {
                fake.close()
            }
        }

    @Test
    fun `launch carries its extras, revokePermission is proven by dumpsys`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-extras-revoke", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val session = openSession(adb, fake)
                val priorResponder = adb.responder
                val starts = java.util.concurrent.CopyOnWriteArrayList<String>()
                var granted = "granted=true"
                adb.responder = { serial, command ->
                    when {
                        command.startsWith("shell am start") -> {
                            starts += command
                            ok("Starting: Intent { cmp=com.example/.Main }\nStatus: ok\nComplete")
                        }
                        command.startsWith("shell pm revoke") -> ok("")
                        command == "shell dumpsys package com.example" -> ok("    android.permission.CAMERA: $granted, flags=[ USER_SET ]")
                        else -> priorResponder?.invoke(serial, command)
                    }
                }
                val app = session.app("com.example")
                val extras =
                    listOf(
                        IntentExtra.newBuilder().setKey("query").setStringValue("shoes red").build(),
                        IntentExtra.newBuilder().setKey("id").setLongValue(42).build(),
                    )
                app.launch(".Main", timeoutMs = 2_000, extras = extras)
                assertEquals(listOf("shell am start -W --es query 'shoes red' --el id 42 -n com.example/.Main"), starts)
                val stillGranted = assertFailsWith<AppLifecycleException> { app.revokePermission("android.permission.CAMERA") }
                assertTrue("still granted" in stillGranted.message.orEmpty(), stillGranted.message)
                granted = "granted=false"
                app.revokePermission("android.permission.CAMERA")
                session.close(timeoutMs = 5_000)
            } finally {
                fake.close()
            }
        }

    @Test
    fun `awaitAppVisible converts only WAIT_TIMEOUT to a host timeout`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-app-visible", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val session = openSession(adb, fake)
                val app = session.app("com.example")

                val timingOut = async(Dispatchers.IO) { runCatching { app.awaitAppVisible(1_000) } }
                fake.respond(
                    withTimeout(5_000) { fake.nextFrame() }.requestId,
                    Responses.failure(ErrorCode.ERR_WAIT_TIMEOUT, detail = ErrorDetail.APP_NOT_VISIBLE, durationMs = 1_000),
                )
                // The diagnostic device-info lookup fails; the timeout must still win.
                fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Responses.failure(ErrorCode.ERR_INTERNAL, durationMs = 1))
                val timeout = withTimeout(5_000) { timingOut.await() }.exceptionOrNull()
                assertTrue(timeout is HostWaitTimeoutException, "got $timeout")
                assertTrue("currentPackage=null" in timeout.message.orEmpty())

                val unhealthy = async(Dispatchers.IO) { runCatching { app.awaitAppVisible(1_000) } }
                fake.respond(
                    withTimeout(5_000) { fake.nextFrame() }.requestId,
                    Responses.failure(ErrorCode.ERR_DRIVER_UNHEALTHY, detail = ErrorDetail.WATCHDOG, durationMs = 1),
                )
                val failure = withTimeout(5_000) { unhealthy.await() }.exceptionOrNull()
                assertTrue(failure is RemoteCommandException && failure.code == ErrorCode.ERR_DRIVER_UNHEALTHY, "got $failure")
                session.close(timeoutMs = 5_000)
            } finally {
                fake.close()
            }
        }

    @Test
    @OptIn(ValidationApi::class)
    fun `a poisoned client makes close journal BROKEN, never a clean CLOSED`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-poisoned", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val session = openSession(adb, fake)
                session.checkUsable()
                session.client.validationTransport().disconnect()

                val unusable = assertFailsWith<SessionUnusableException> { session.checkUsable() }
                assertTrue("Driver connection" in unusable.message.orEmpty(), unusable.message)
                // ADB-only lifecycle work does not need the driver and stays admissible.
                session.app("com.example")

                session.close(timeoutMs = 5_000)
                val record = journalStore().read()
                assertEquals(JournalState.BROKEN, record?.state)
                assertTrue(record?.quarantineReason?.startsWith("DRIVER_CONNECTION_POISONED") == true, record?.quarantineReason)
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }

    private suspend fun openSession(
        adb: FakeAdb,
        fake: FakeDriverServer,
    ): DeviceSession =
        coroutineScope {
            val processes = mutableListOf<FakeProcess>()
            val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
            fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Responses.done(1))
            withTimeout(5_000) { opening.await() }
        }

    @Test
    fun `raw session adb escapes are not public`() {
        // The supported surface is typed operations ([AppLifecycle]), immutable metadata and the
        // client; the raw runner, its config and the guard never come back out of a session.
        val methods = DeviceSession::class.java.declaredMethods.map { it.name }
        assertFalse("getConfig" in methods, "DeviceSession.config must not be public")
        assertFalse("getAdb" in methods, "DeviceSession.adb must not be public")
        assertFalse("guardAdb" in methods, "DeviceSession.guardAdb must not be public")
        assertTrue("getClient" in methods)
        assertTrue("getSerial" in methods)
        assertTrue("checkUsable" in methods)
    }

    @Test
    fun `operation after close starts is rejected before touching adb`() =
        runBlocking {
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-close-reject", 1, secret, acceptAnySession = true)
            try {
                val adb = openAdb(fake.port)
                val processes = mutableListOf<FakeProcess>()
                val opening = async(Dispatchers.IO) { DeviceSession.open(sessionConfig(adb, fake, processes)) }
                fake.respond(withTimeout(5_000) { fake.nextFrame() }.requestId, Responses.done(1))
                val session = withTimeout(5_000) { opening.await() }
                val app = session.app("com.example")
                // Park close inside exact forward removal, proving close began.
                adb.hangOn += "forward --remove tcp:${fake.port}"
                val closing = async(Dispatchers.IO) { runCatching { session.close(timeoutMs = 10_000) } }
                withTimeout(5_000) {
                    while (adb.calls.none { it.contains("forward --remove") }) delay(10)
                }
                val callsAtReject = adb.calls.size
                // Later operations reject through the session gate before any FakeAdb call.
                assertFailsWith<SessionClosingException> { app.isRunning() }
                assertFailsWith<SessionClosingException> { session.app("com.example") }
                assertEquals(callsAtReject, adb.calls.size, "rejected operation must not touch ADB")
                adb.hangOn -= "forward --remove tcp:${fake.port}"
                withTimeout(15_000) { closing.await() }
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }
}
