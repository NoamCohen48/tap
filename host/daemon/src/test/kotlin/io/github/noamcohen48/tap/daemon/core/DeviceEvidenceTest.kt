package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AdbCommandException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceEvidenceTest {
    private val banner = "--------- beginning of crash\n"

    @Test
    fun `logs start at the attachment and a failing part does not fail the others`(): Unit =
        runBlocking {
            val calls = CopyOnWriteArrayList<String>()
            val adb =
                object : Adb("fake-adb") {
                    override suspend fun logcat(
                        serial: String,
                        buffers: List<String>,
                        sinceEpochSeconds: Long?,
                        tags: List<String>,
                    ): String {
                        calls += "logcat $buffers since=$sinceEpochSeconds tags=$tags"
                        return when (buffers) {
                            listOf("crash") -> banner + "E AndroidRuntime: FATAL EXCEPTION: main\n"
                            listOf("events") -> "--------- beginning of events\n"
                            else -> "I ActivityManager: Start proc com.example\n"
                        }
                    }

                    override suspend fun dumpsys(
                        serial: String,
                        vararg arguments: String,
                    ): String =
                        if (arguments.first() == "window") {
                            throw AdbCommandException(serial, arguments.toList(), 1, "", "dumpsys window failed")
                        } else {
                            "ACTIVITY MANAGER ACTIVITIES"
                        }
                }
            val evidence = DeviceEvidence.collect(adb, "emulator-5554", 1_700_000_000L)
            assertEquals("I ActivityManager: Start proc com.example\n", evidence.logcat)
            assertFalse(evidence.logcatTruncated)
            assertEquals(banner + "E AndroidRuntime: FATAL EXCEPTION: main\n", evidence.crashes)
            assertEquals("ACTIVITY MANAGER ACTIVITIES", evidence.activities)
            assertTrue(evidence.windows.startsWith("<unavailable: dumpsys window failed"), evidence.windows)
            assertTrue(calls.all { "since=1700000000" in it }, calls.toString())
            assertTrue("logcat [events] since=1700000000 tags=[am_crash, am_anr]" in calls, calls.toString())
        }

    @Test
    fun `a stuck part costs only itself`(): Unit =
        runBlocking {
            val adb =
                object : Adb("fake-adb") {
                    override suspend fun logcat(
                        serial: String,
                        buffers: List<String>,
                        sinceEpochSeconds: Long?,
                        tags: List<String>,
                    ): String = "line\n"

                    override suspend fun dumpsys(
                        serial: String,
                        vararg arguments: String,
                    ): String {
                        if (arguments.first() == "window") awaitCancellation()
                        return "ACTIVITIES"
                    }
                }
            val evidence = DeviceEvidence.collect(adb, "emulator-5554", null, partTimeoutMs = 50)
            assertEquals("line\n", evidence.logcat)
            assertEquals("ACTIVITIES", evidence.activities)
            assertEquals("<unavailable: not read within 50 ms>", evidence.windows)
        }

    @Test
    fun `a part's deadline leaves room for the reply within the caller's`() {
        assertEquals(DeviceEvidence.PART_TIMEOUT_MS, DeviceEvidence.partTimeoutMs(null))
        assertEquals(DeviceEvidence.PART_TIMEOUT_MS, DeviceEvidence.partTimeoutMs(3_600_000))
        assertEquals(7_500, DeviceEvidence.partTimeoutMs(10_000))
        assertEquals(1, DeviceEvidence.partTimeoutMs(-5))
    }

    @Test
    fun `long logcat keeps its newest whole lines`() {
        assertEquals("ab\ncd\n" to false, DeviceEvidence.newest("ab\ncd\n", 10))
        assertEquals("cd\nef\n" to true, DeviceEvidence.newest("ab\ncd\nef\n", 7))
    }

    @Test
    fun `crashes are empty when the buffers hold only their banners`() {
        assertEquals("", DeviceEvidence.crashes(banner, "--------- beginning of events\n"))
        val anr = "I am_anr  : [0,1234,com.example,0,Input dispatching timed out]\n"
        assertEquals(anr, DeviceEvidence.crashes("", anr))
        assertEquals("crash\n\n$anr", DeviceEvidence.crashes("crash\n", anr))
    }
}
