package com.company.tap.host

import com.company.tap.protocol.Done
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    ) : Adb("fake-adb") {
        var driverPresent = false

        init {
            processStarter = ProcessStarter { blockingChild }
        }

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
            val starter =
                ProcessStarter { _ ->
                    FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER)
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
                    assertFailsWith<IllegalStateException> {
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
                config.beforeOwnershipTransfer = {
                    reachedTransfer.complete(Unit)
                    releaseTransfer.await()
                }
                config.afterCancellationCleanup = { cleanupFinished.complete(Unit) }
                val opening = async(Dispatchers.IO) { DeviceSession.open(config) }
                val health = fake.nextFrame(5_000)
                fake.respond(health.requestId, Response.ok(Done, durationMs = 1))
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
                fake.respond(health.requestId, Response.ok(Done, durationMs = 1))
                val session = withTimeout(5_000) { opening.await() }
                assertEquals(JournalState.READY, journalStore().read()?.state)

                adb.hangOn += "forward --remove tcp:${fake.port}"
                val started = System.nanoTime()
                val failure = assertFailsWith<IllegalStateException> { session.close(timeoutMs = 300) }
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
                fake.respond(health.requestId, Response.ok(Done, durationMs = 1))
                val session = withTimeout(5_000) { opening.await() }
                assertEquals(JournalState.READY, journalStore().read()?.state)

                val started = System.nanoTime()
                val failure = assertFailsWith<IllegalStateException> { session.close(timeoutMs = 2_000) }
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
}
