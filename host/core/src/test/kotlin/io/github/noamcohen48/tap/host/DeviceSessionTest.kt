package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.wire.v1.Request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
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
            autPackage = "com.example",
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
                        "com.example",
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
                        "com.example",
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
                            "com.example",
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
                val app = session.app()
                assertFailsWith<AdbReapUncertainException> { app.isRunning() }
                assertFailsWith<DeviceQuarantinedException> { app.isRunning() }
                assertFailsWith<DeviceQuarantinedException> { session.app() }
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
                assertFailsWith<DeviceQuarantinedException> { session.app() }
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
                val app = session.app()
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
                session.app()
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
                withTimeout(5_000) { session.app().launch(".Main", timeoutMs = 2_000) }
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
                val app = session.app()

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
                session.app()

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
        assertTrue("getAutPackage" in methods, "cross-module users need the immutable autPackage")
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
                assertEquals("com.example", session.autPackage)
                val app = session.app()
                // Park close inside exact forward removal, proving close began.
                adb.hangOn += "forward --remove tcp:${fake.port}"
                val closing = async(Dispatchers.IO) { runCatching { session.close(timeoutMs = 10_000) } }
                withTimeout(5_000) {
                    while (adb.calls.none { it.contains("forward --remove") }) delay(10)
                }
                val callsAtReject = adb.calls.size
                // Later operations reject through the session gate before any FakeAdb call.
                assertFailsWith<SessionClosingException> { app.isRunning() }
                assertFailsWith<SessionClosingException> { session.app() }
                assertEquals(callsAtReject, adb.calls.size, "rejected operation must not touch ADB")
                adb.hangOn -= "forward --remove tcp:${fake.port}"
                withTimeout(15_000) { closing.await() }
                journalStore().acquireLease(0).close()
            } finally {
                fake.close()
            }
        }
}
