package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.Error as CommandError
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A failing command names the app's death when Android logged one for an app the attachment started. */
class AppDeathsServiceTest {
    private val pkg = "com.example.shop"
    private val crash = "11-04 08:25:14.107   574  5281 I am_crash: [23554,0,$pkg,547,java.lang.RuntimeException,boom, really,Main.kt,20,0]"
    private val anr = "11-04 08:25:30.000   574  5281 I am_anr  : [0,23554,$pkg,547,Input dispatching timed out]"

    private inner class Events : Adb("fake-adb") {
        @Volatile var lines = listOf("--------- beginning of events")
        val reads = AtomicInteger()

        override suspend fun logcat(
            serial: String,
            buffers: List<String>,
            sinceEpochSeconds: Long?,
            tags: List<String>,
        ): String {
            reads.incrementAndGet()
            assertEquals(listOf("events"), buffers)
            assertEquals(listOf("am_crash", "am_anr"), tags)
            return lines.joinToString("\n")
        }
    }

    private val notFound = CommandResult.newBuilder().setError(CommandError.newBuilder().setCode(ErrorCode.ERR_NOT_FOUND).setMessage("no such node"))
    private val tap = Commands.tap(Selectors.text("Pay"))

    private suspend fun AttachedHarness.started(packageName: String = pkg) = attached.appDeaths.watch(packageName)

    @Test
    fun `a failure after the app crashed names the crash once`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + crash
                val failed = execute(tap, notFound).error
                assertEquals(ErrorCode.ERR_AUT_CRASHED, failed.code)
                assertEquals("NOT_FOUND", failed.detail)
                assertTrue(pkg in failed.message && "RuntimeException" in failed.message && "no such node" in failed.message, failed.message)
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "the same crash is reported once")
            }
        }

    @Test
    fun `an ANR is AUT_ANR and a process of the package counts`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                started()
                adb.lines = adb.lines + anr
                assertEquals(ErrorCode.ERR_AUT_ANR, execute(tap, notFound).error.code)
                adb.lines = adb.lines + crash.replace(",$pkg,", ",$pkg:remote,")
                assertEquals(ErrorCode.ERR_AUT_CRASHED, execute(tap, notFound).error.code)
            }
        }

    @Test
    fun `another app's crash, or one before the app was started, changes nothing`(): Unit =
        runBlocking {
            val adb = Events()
            AttachedHarness.with(adb) {
                adb.lines = adb.lines + crash
                started()
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "crashed before this start")
                adb.lines = adb.lines + crash.replace("$pkg", "com.google.android.gms")
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "an app the test did not start")
                adb.lines = adb.lines + crash.replace("$pkg", "$pkg.other")
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "a longer package name is another app")
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
                adb.lines = adb.lines + crash.replace("boom, really", "am_anr")
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
                object : Adb("fake-adb") {
                    override suspend fun logcat(
                        serial: String,
                        buffers: List<String>,
                        sinceEpochSeconds: Long?,
                        tags: List<String>,
                    ): String = awaitCancellation()
                }
            AttachedHarness.with(silent) {
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code)
                started()
                assertEquals(ErrorCode.ERR_NOT_FOUND, execute(tap, notFound).error.code, "the read timed out")
            }
        }
}
