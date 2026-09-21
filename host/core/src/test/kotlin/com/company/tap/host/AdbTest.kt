package com.company.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbTest {
    private val serial = "emulator-5554"

    @Test
    fun `process stat reads the start token after the parenthesised command name`() =
        runTest {
            val adb =
                FakeAdb(
                    mapOf(
                        "shell cat /proc/1234/stat" to ok("1234 (my app (x)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 424242 20 21"),
                    ),
                )
            assertEquals(Adb.ProcessStat.Live("424242"), adb.processStat(serial, 1234))
        }

    @Test
    fun `process stat distinguishes a dead process from an unreadable one`() =
        runTest {
            val gone = FakeAdb(mapOf("shell cat /proc/7/stat" to Adb.Result(1, "cat: /proc/7/stat: No such file or directory")))
            assertEquals(Adb.ProcessStat.Gone, gone.processStat(serial, 7))

            val denied = FakeAdb(mapOf("shell cat /proc/7/stat" to Adb.Result(1, "cat: /proc/7/stat: Permission denied")))
            assertFailsWith<IllegalStateException> { denied.processStat(serial, 7) }

            val malformed = FakeAdb(mapOf("shell cat /proc/7/stat" to ok("7 (short) S 1 2")))
            assertFailsWith<IllegalStateException> { malformed.processStat(serial, 7) }
        }

    @Test
    fun `listening port is matched in hex against LISTEN sockets only`() =
        runTest {
            val table =
                """
                sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid
                 0: 00000000:6A2F 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000
                 1: 0100007F:6A30 0100007F:E4A2 01 00000000:00000000 00:00000000 00000000  2000
                """.trimIndent()
            val adb = FakeAdb(mapOf("shell cat /proc/net/tcp /proc/net/tcp6" to ok(table)))
            assertTrue(adb.isPortListening(serial, 27183)) // 0x6A2F, state 0A
            assertFalse(adb.isPortListening(serial, 27184)) // 0x6A30 is ESTABLISHED, not LISTEN
        }

    @Test
    fun `launcher activity is the last resolved component of the package or null`() =
        runTest {
            val cmd = "shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.example"
            val adb = FakeAdb(mapOf(cmd to ok("priority=0 preferredOrder=0 match=0x108000\ncom.example/.MainActivity")))
            assertEquals("com.example/.MainActivity", adb.launcherActivity(serial, "com.example"))
            val none = FakeAdb(mapOf(cmd to ok("No activity found")))
            assertNull(none.launcherActivity(serial, "com.example"))
        }

    @Test
    fun `process ids treat pidof exit 1 with no output as no process`() =
        runTest {
            val adb = FakeAdb(mapOf("shell pidof com.example" to Adb.Result(1, "")))
            assertEquals(emptyList(), adb.processIds(serial, "com.example"))
            val two = FakeAdb(mapOf("shell pidof com.example" to ok("100 200")))
            assertEquals(listOf(100, 200), two.processIds(serial, "com.example"))
        }

    @Test
    fun `every device command is serial specific`() =
        runTest {
            val adb = FakeAdb(mapOf("shell pm path com.example" to ok("package:/data/app/x.apk")))
            assertTrue(adb.isInstalled(serial, "com.example"))
            assertEquals(listOf("$serial: shell pm path com.example"), adb.calls)
        }

    @Test
    @OptIn(RawAdb::class)
    fun `local timeout keeps the documented message and reaps the child`() =
        runBlocking {
            val adb = Adb("fake-adb")
            val child = FakeProcess(stdout = "partial", exitDelayMs = FakeProcess.NEVER)
            adb.processStarter = ProcessStarter { child }
            val failure =
                assertFailsWith<IllegalStateException> {
                    adb.runResult(serial, "shell", "sleep", "30", timeoutMs = 100)
                }
            assertTrue("timed out" in failure.message.orEmpty(), failure.message.orEmpty())
            assertTrue(
                child.destroyed.get() || child.destroyedForcibly.get(),
                "timed-out child must be destroyed",
            )
            withTimeout(2_000) { while (child.isAlive) delay(10) }
            assertTrue(child.stdinClosed.get(), "timed-out child stdin must close")
            assertTrue(child.stdoutClosed.get(), "timed-out child stdout must close")
            assertTrue(child.stderrClosed.get(), "timed-out child stderr must close")
        }

    @Test
    fun `enclosing timeout stays cancellation and still reaps the child`() =
        runBlocking {
            val adb = Adb("fake-adb")
            val child = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER)
            adb.processStarter = ProcessStarter { child }
            assertFailsWith<CancellationException> {
                withTimeout(200) { adb.devices(timeoutMs = 30_000) }
            }
            assertTrue(
                child.destroyed.get() || child.destroyedForcibly.get(),
                "cancelled child must be destroyed",
            )
            withTimeout(2_000) { while (child.isAlive) delay(10) }
            assertTrue(child.stdinClosed.get(), "cancelled child stdin must close")
            assertTrue(child.stdoutClosed.get(), "cancelled child stdout must close")
            assertTrue(child.stderrClosed.get(), "cancelled child stderr must close")
        }

    @Test
    fun `cancellation during process start cannot discard the created child`() =
        runBlocking {
            val adb = Adb("fake-adb")
            val child = FakeProcess(exitDelayMs = FakeProcess.NEVER)
            val published = CountDownLatch(1)
            val releaseReturn = CountDownLatch(1)
            adb.processStarter =
                ProcessStarter {
                    published.countDown()
                    check(releaseReturn.await(5, TimeUnit.SECONDS))
                    child
                }
            val running = async(Dispatchers.IO) { adb.devices(timeoutMs = 30_000) }
            assertTrue(published.await(2, TimeUnit.SECONDS), "starter did not publish child")
            val original = CancellationException("original cancellation")
            running.cancel(original)
            assertFalse(running.isCompleted, "ownership installation must finish before cancellation")
            releaseReturn.countDown()
            val thrown = assertFailsWith<CancellationException> { running.await() }
            assertEquals(original.message, thrown.message)
            assertTrue(child.destroyed.get() || child.destroyedForcibly.get())
            assertFalse(child.isAlive)
            assertTrue(child.stdinClosed.get())
            assertTrue(child.stdoutClosed.get())
            assertTrue(child.stderrClosed.get())
        }

    @Test
    @OptIn(RawAdb::class)
    fun `successful process returns trimmed output through the real path`() =
        runBlocking {
            val adb = Adb("fake-adb")
            adb.processStarter =
                ProcessStarter { FakeProcess(stdout = "  hello\n", exitCode = 0) }
            assertEquals("hello", adb.run(serial, "shell", "echo", "hello"))
        }

    @Test
    fun `cancelled call with a blocking drain returns within the aggregate reap bound as uncertain`() =
        runBlocking {
            val adb = Adb("fake-adb")
            val child = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER, blockingStdout = true)
            adb.processStarter = ProcessStarter { child }
            try {
                val started = System.nanoTime()
                val failure =
                    assertFailsWith<AdbReapUncertainException> {
                        withTimeout(200) { adb.devices(timeoutMs = 30_000) }
                    }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                // Aggregate bound: one ADB_REAP_TIMEOUT_MS deadline shared by drain join plus
                // process wait/destroy, plus the 200ms outer timeout and CI scheduling slack.
                assertTrue(
                    elapsedMs < 200 + ADB_REAP_TIMEOUT_MS + 3_000,
                    "cancelled call took ${elapsedMs}ms; the aggregate reap deadline is " +
                        "${ADB_REAP_TIMEOUT_MS}ms plus the 200ms outer timeout",
                )
                assertTrue(
                    "survived bounded reap" in failure.message.orEmpty(),
                    failure.message.orEmpty(),
                )
                // The original cancellation rides in the message: cancellation machinery may drop
                // the suppressed chain on delivery, but the report must still name it.
                assertTrue(
                    "Timed out waiting for 200 ms" in failure.message.orEmpty(),
                    failure.message.orEmpty(),
                )
                assertTrue(child.destroyed.get() || child.destroyedForcibly.get())
                assertTrue(child.stdinClosed.get())
                assertTrue(child.stdoutClosed.get())
                assertTrue(child.stderrClosed.get())
            } finally {
                child.releaseStdout()
                child.forceExit()
            }
            // The owned drain executor is shut down: the released child still answers a later call.
            assertEquals(emptyList(), adb.devices(timeoutMs = 30_000))
        }

    @Test
    fun `unreaped drain gates further starts until it completes then recovers`() =
        runBlocking {
            val adb = Adb("fake-adb")
            val starts =
                java.util.concurrent.atomic
                    .AtomicInteger(0)
            val blocked = FakeProcess(stdout = "", exitDelayMs = FakeProcess.NEVER, blockingStdout = true)
            adb.processStarter =
                ProcessStarter {
                    if (starts.incrementAndGet() == 1) {
                        blocked
                    } else {
                        FakeProcess(stdout = "List of devices attached\n", exitCode = 0)
                    }
                }

            fun drainThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "adb-output-drain" && it.isAlive }
            // First call leaves its drain blocked: typed reap uncertainty, gate installed.
            assertFailsWith<AdbReapUncertainException> {
                withTimeout(200) { adb.devices(timeoutMs = 30_000) }
            }
            assertEquals(1, starts.get())
            assertTrue(adb.isReapGatedForTest(), "unreaped drain must gate the runner")
            // Repeated calls are rejected before start: no additional processes or drains.
            repeat(3) {
                val gated =
                    assertFailsWith<AdbReapUncertainException> {
                        adb.devices(timeoutMs = 5_000)
                    }
                assertTrue("gated" in gated.message.orEmpty(), gated.message.orEmpty())
            }
            assertEquals(1, starts.get(), "gated calls must not start new processes")
            // Release the residual: the gate clears, the old executor terminates, reuse succeeds.
            blocked.releaseStdout()
            blocked.forceExit()
            val reused =
                withTimeout(10_000) {
                    adb.devices(timeoutMs = 5_000)
                }
            assertEquals(emptyList(), reused)
            assertEquals(2, starts.get())
            assertFalse(adb.isReapGatedForTest(), "gate must clear once the residual completes")
            withTimeout(5_000) {
                while (drainThreads() > 0) delay(10)
            }
        }
}
