package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.AppDeath
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.Error as CommandError
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.ListAppDeathsRequest
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Nodes
import io.github.noamcohen48.tap.protocol.Selectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deaths Android logged become records; a failing command names a watched app's death, and a wait ends at it. */
class AppDeathsServiceTest {
    private val pkg = "com.example.shop"

    // Android 14 `am_crash` ends in `recoverable`; Android 10 has no such field.
    private val crash = "  1790000014.107   574  5281 I am_crash: [0,23554,$pkg,547,java.lang.RuntimeException,boom, really,Main.kt,20,0]"
    private val crash29 = "  1790000014.107   574  5281 I am_crash: [0,23554,$pkg,547,java.lang.RuntimeException,boom, really,Main.kt,20]"
    private val anr = "  1790000030.000   574  5281 I am_anr  : [0,23554,$pkg,547,Input dispatching timed out (Waiting to send, 5001ms)]"
    private val stack =
        listOf(
            "  1790000014.100 23554 23554 E AndroidRuntime: FATAL EXCEPTION: main",
            "  1790000014.100 23554 23554 E AndroidRuntime: Process: $pkg, PID: 23554",
            "  1790000014.100 23554 23554 E AndroidRuntime: java.lang.RuntimeException: boom, really",
            "  1790000014.100 23554 23554 E AndroidRuntime: \tat com.example.shop.Main.pay(Main.kt:20)",
        )

    /** One-shot reads return [lines]; the follower is never available, so every question reads. */
    private open inner class Events : Adb("fake-adb") {
        @Volatile var lines = listOf("--------- beginning of events")
        val reads = AtomicInteger()

        override suspend fun logcatEpoch(
            serial: String,
            buffers: List<String>,
            sinceEpochMs: Long?,
            tags: List<String>,
        ): String {
            reads.incrementAndGet()
            assertEquals(listOf("events", "crash"), buffers)
            assertTrue("am_crash" in tags && "am_anr" in tags && "AndroidRuntime" in tags, tags.toString())
            return lines.joinToString("\n")
        }

        override fun startLogcatEpoch(
            serial: String,
            buffers: List<String>,
            sinceEpochMs: Long?,
            tags: List<String>,
        ): Process = throw java.io.IOException("no follower in this fake")
    }

    /** The follower's output is written line by line; one-shot reads see nothing. */
    private inner class Followed : Events() {
        val child = LineProcess()
        val started = CountDownLatch(1)

        override suspend fun logcatEpoch(
            serial: String,
            buffers: List<String>,
            sinceEpochMs: Long?,
            tags: List<String>,
        ): String = ""

        override fun startLogcatEpoch(
            serial: String,
            buffers: List<String>,
            sinceEpochMs: Long?,
            tags: List<String>,
        ): Process = child.also { started.countDown() }
    }

    private val notFound = CommandResult.newBuilder().setError(CommandError.newBuilder().setCode(ErrorCode.ERR_NOT_FOUND).setMessage("no such node"))
    private val tap = Commands.tap(Selectors.text("Pay"))

    private suspend fun AttachedHarness.started(packageName: String = pkg) = attached.appDeaths.watch(packageName)

    private suspend fun AttachedHarness.deaths(
        after: Long = 0,
        packageName: String? = null,
        sequenceOnly: Boolean = false,
    ) = devices.listAppDeaths(
        ListAppDeathsRequest
            .newBuilder()
            .setClientConnectionId(connectionId)
            .setAttachedDeviceId(attached.id)
            .setAfterSequence(after)
            .setSequenceOnly(sequenceOnly)
            .apply { packageName?.let { setPackageName(it) } }
            .build(),
    )

    @Test
    fun `a failure after the app crashed names the crash once, with the record`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + stack + crash
                val failed = execute(tap, notFound).error
                assertEquals(ErrorCode.ERR_AUT_CRASHED, failed.code)
                assertEquals("NOT_FOUND", failed.detail)
                assertEquals(
                    "$pkg crashed (pid 23554): java.lang.RuntimeException: boom, really; the command reported NOT_FOUND: no such node",
                    failed.message,
                )
                val death = failed.appDeath
                assertEquals(AppDeath.Kind.KIND_CRASH, death.kind)
                assertEquals(1, death.sequence)
                assertEquals(pkg, death.packageName)
                assertEquals(pkg, death.processName)
                assertEquals(23554, death.pid)
                assertEquals(1_790_000_014_107, death.atEpochMs)
                assertEquals("java.lang.RuntimeException", death.exceptionClass)
                assertEquals("boom, really", death.message)
                assertTrue(death.stackTrace.startsWith("FATAL EXCEPTION: main\n") && "\tat com.example.shop.Main.pay(Main.kt:20)" in death.stackTrace, death.stackTrace)
                assertTrue(death.watched && death.reported)
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "the same crash is reported once")
            }
        }

    @Test
    fun `Android 10's am_crash, an ANR and a process of the package are read`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + anr
                val stopped = execute(tap, notFound).error
                assertEquals(ErrorCode.ERR_AUT_ANR, stopped.code)
                assertEquals("Input dispatching timed out (Waiting to send, 5001ms)", stopped.appDeath.message)
                assertEquals("", stopped.appDeath.stackTrace)
                adb.lines = adb.lines + crash29.replace(",$pkg,", ",$pkg:remote,").replace("14.107", "15.000")
                val remote = execute(tap, notFound).error
                assertEquals(ErrorCode.ERR_AUT_CRASHED, remote.code)
                assertEquals("$pkg:remote", remote.appDeath.processName)
                assertEquals(pkg, remote.appDeath.packageName)
                assertEquals("boom, really", remote.appDeath.message, "the message ends before file and line")
            }
        }

    @Test
    fun `another app's crash, or one before the app was started, explains nothing but is listed`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                adb.lines = adb.lines + crash
                started()
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "crashed before this start")
                adb.lines = adb.lines + crash.replace(pkg, "com.google.android.gms").replace("14.107", "16.000")
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "an app the test did not start")
                adb.lines = adb.lines + crash.replace(pkg, "$pkg.other").replace("14.107", "17.000")
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "a longer package name is another app")
                val listed = deaths()
                assertEquals(listOf(pkg, "com.google.android.gms", "$pkg.other"), listed.deathsList.map { it.packageName })
                assertEquals(listOf(false, false, false), listed.deathsList.map { it.watched }, "none was watched when it died")
                assertEquals(3, listed.lastSequence)
                assertEquals(listOf(3L), deaths(after = 2).deathsList.map { it.sequence })
                assertEquals(listOf("com.google.android.gms"), deaths(packageName = "com.google.android.gms").deathsList.map { it.packageName })
                val only = deaths(sequenceOnly = true)
                assertEquals(0, only.deathsCount)
                assertEquals(3, only.lastSequence)
            }
        }

    @Test
    fun `exception text cannot identify the app or turn a crash into an ANR`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + crash.replace(",$pkg,", ",com.other.app,").replace("boom, really", pkg)
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "package mentioned only in another app's exception")
                adb.lines = adb.lines + crash.replace("boom, really", "am_anr").replace("14.107", "18.000")
                assertEquals(ErrorCode.ERR_AUT_CRASHED, execute(tap, notFound).error.code, "the event tag, not the exception text, decides the code")
            }
        }

    @Test
    fun `a crash not yet reported is old once the app is started again`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + crash
                started()
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code)
                assertTrue(deaths().deathsList.single().watched, "it was watched when it died")
            }
        }

    @Test
    fun `a package a selector names is watched from that command on`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                adb.lines = adb.lines + crash
                val inApp = Commands.tap(Selectors.of(Nodes.allOf(Nodes.text("Pay"), Nodes.packageName(pkg))))
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(inApp, notFound).error.code, "its death before the command is old")
                adb.lines = adb.lines + crash.replace("14.107", "19.000")
                val failed = execute(inApp, notFound).error
                assertEquals(ErrorCode.ERR_AUT_CRASHED, failed.code)
                assertTrue(failed.appDeath.watched)
                val reads = adb.reads.get()
                execute(inApp, notFound)
                assertEquals(reads + 1, adb.reads.get(), "a watched package is not caught up on again; the failure reads once")
            }
        }

    @Test
    fun `passing commands read nothing and safety codes are never replaced`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + crash
                val reads = adb.reads.get()
                val done = execute(tap, CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                assertFalse(done.hasError())
                val indeterminate = CommandResult.newBuilder().setError(CommandError.newBuilder().setCode(ErrorCode.ERR_INDETERMINATE))
                assertEquals(ErrorCode.ERR_INDETERMINATE, execute(tap, indeterminate).error.code)
                assertEquals(reads, adb.reads.get(), "no ADB call on these paths")
            }
        }

    @Test
    fun `no read at all before an app was started, and an unreadable log leaves the failure`(): Unit =
        runBlocking {
            val silent =
                object : Events() {
                    override suspend fun logcatEpoch(
                        serial: String,
                        buffers: List<String>,
                        sinceEpochMs: Long?,
                        tags: List<String>,
                    ): String = awaitCancellation()
                }
            AttachedHarness.with(silent) {
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code)
                started()
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "the read timed out")
            }
        }

    @Test
    fun `the followed log records deaths as they come and needs no read`(): Unit =
        runBlocking {
            val adb = Followed()
            AttachedHarness.with(adb) {
                assertTrue(withContext(Dispatchers.IO) { adb.started.await(5, TimeUnit.SECONDS) }, "the follower started")
                started()
                (stack + crash).forEach(adb.child::emit)
                withTimeout(5_000) { while (deaths().deathsCount == 0) delay(20) }
                val death = deaths().deathsList.single()
                assertTrue("Main.kt:20" in death.stackTrace, death.stackTrace)
                assertFalse(death.reported)
                assertEquals(ErrorCode.ERR_AUT_CRASHED, execute(tap, notFound).error.code)
                assertTrue(deaths().deathsList.single().reported)
            }
            assertTrue(adb.child.destroyed, "detach stops the follower")
        }

    @Test
    fun `a wait ends when a watched app dies while it runs, a mutation does not`(): Unit =
        runBlocking {
            val adb = Followed()
            AttachedHarness.with(adb) {
                assertTrue(withContext(Dispatchers.IO) { adb.started.await(5, TimeUnit.SECONDS) })
                started()
                val wait = Commands.waitVisible(Selectors.text("Paid"))
                val call =
                    async {
                        devices.execute(
                            ExecuteRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).setCommand(wait).build(),
                        )
                    }
                val frame = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextFrame() } }
                adb.child.emit(crash)
                // The daemon cancels the wait; the driver answers the CANCEL with CANCELLED.
                val cancel = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextCancel() } }
                assertEquals(frame.requestId, cancel)
                respond(frame.requestId, CommandResult.newBuilder().setError(CommandError.newBuilder().setCode(ErrorCode.ERR_CANCELLED)))
                val failed = withTimeout(5_000) { call.await() }.result.error
                assertEquals(ErrorCode.ERR_AUT_CRASHED, failed.code)
                assertEquals("CANCELLED", failed.detail)
                assertTrue(failed.message.endsWith("; the wait was ended (CANCELLED)"), failed.message)
                assertEquals(1, failed.appDeath.sequence)

                // A death during a tap leaves the tap to finish as the driver says.
                val taps =
                    async {
                        devices.execute(
                            ExecuteRequest.newBuilder().setClientConnectionId(connectionId).setAttachedDeviceId(attached.id).setCommand(tap).build(),
                        )
                    }
                val tapFrame = withTimeout(2_000) { withContext(Dispatchers.IO) { driver.nextFrame() } }
                adb.child.emit(crash.replace("14.107", "20.000"))
                delay(300)
                respond(tapFrame.requestId, CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                assertFalse(withTimeout(5_000) { taps.await() }.result.hasError())
            }
        }

    @Test
    fun `a wait is not ended by a death logged before it started`(): Unit =
        runBlocking {
            val adb = Followed()
            AttachedHarness.with(adb) {
                assertTrue(withContext(Dispatchers.IO) { adb.started.await(5, TimeUnit.SECONDS) })
                started()
                adb.child.emit(crash)
                withTimeout(5_000) { while (deaths().deathsCount == 0) delay(20) }
                val done = execute(Commands.waitVisible(Selectors.text("Paid")), CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                assertFalse(done.hasError())
            }
        }
}

/** A child whose stdout is written line by line ([emit]) and ends when it is destroyed. */
internal class LineProcess : Process() {
    private val sink = PipedOutputStream()
    private val source = PipedInputStream(sink, 64 * 1024)
    private val exited = CountDownLatch(1)

    @Volatile var destroyed = false

    fun emit(line: String) {
        sink.write((line + "\n").toByteArray())
        sink.flush()
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = source

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        exited.await()
        return 0
    }

    override fun exitValue(): Int = if (exited.count == 0L) 0 else throw IllegalThreadStateException()

    override fun destroy() {
        destroyed = true
        runCatching { sink.close() }
        exited.countDown()
    }
}
