package com.company.tap.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A fake device: canned results keyed by the command line after `-s <serial>`. */
private class FakeAdb(private val replies: Map<String, Adb.Result>) : Adb("fake-adb") {
    val calls = mutableListOf<String>()

    override suspend fun execResult(serial: String, vararg arguments: String, timeoutMs: Long): Result {
        val command = arguments.joinToString(" ")
        calls += "$serial: $command"
        return replies[command] ?: error("unexpected adb -s $serial $command")
    }
}

private fun ok(output: String) = Adb.Result(0, output)

class AdbTest {
    private val serial = "emulator-5554"

    @Test
    fun `process stat reads the start token after the parenthesised command name`() = runTest {
        val adb = FakeAdb(mapOf("shell cat /proc/1234/stat" to ok("1234 (my app (x)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 424242 20 21")))
        assertEquals(Adb.ProcessStat.Live("424242"), adb.processStat(serial, 1234))
    }

    @Test
    fun `process stat distinguishes a dead process from an unreadable one`() = runTest {
        val gone = FakeAdb(mapOf("shell cat /proc/7/stat" to Adb.Result(1, "cat: /proc/7/stat: No such file or directory")))
        assertEquals(Adb.ProcessStat.Gone, gone.processStat(serial, 7))

        val denied = FakeAdb(mapOf("shell cat /proc/7/stat" to Adb.Result(1, "cat: /proc/7/stat: Permission denied")))
        assertFailsWith<IllegalStateException> { denied.processStat(serial, 7) }

        val malformed = FakeAdb(mapOf("shell cat /proc/7/stat" to ok("7 (short) S 1 2")))
        assertFailsWith<IllegalStateException> { malformed.processStat(serial, 7) }
    }

    @Test
    fun `listening port is matched in hex against LISTEN sockets only`() = runTest {
        val table = """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid
             0: 00000000:6A2F 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000
             1: 0100007F:6A30 0100007F:E4A2 01 00000000:00000000 00:00000000 00000000  2000
        """.trimIndent()
        val adb = FakeAdb(mapOf("shell cat /proc/net/tcp /proc/net/tcp6" to ok(table)))
        assertTrue(adb.isPortListening(serial, 27183)) // 0x6A2F, state 0A
        assertFalse(adb.isPortListening(serial, 27184)) // 0x6A30 is ESTABLISHED, not LISTEN
    }

    @Test
    fun `launcher activity is the last resolved component of the package or null`() = runTest {
        val cmd = "shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.example"
        val adb = FakeAdb(mapOf(cmd to ok("priority=0 preferredOrder=0 match=0x108000\ncom.example/.MainActivity")))
        assertEquals("com.example/.MainActivity", adb.launcherActivity(serial, "com.example"))
        val none = FakeAdb(mapOf(cmd to ok("No activity found")))
        assertNull(none.launcherActivity(serial, "com.example"))
    }

    @Test
    fun `process ids treat pidof exit 1 with no output as no process`() = runTest {
        val adb = FakeAdb(mapOf("shell pidof com.example" to Adb.Result(1, "")))
        assertEquals(emptyList(), adb.processIds(serial, "com.example"))
        val two = FakeAdb(mapOf("shell pidof com.example" to ok("100 200")))
        assertEquals(listOf(100, 200), two.processIds(serial, "com.example"))
    }

    @Test
    fun `every device command is serial specific`() = runTest {
        val adb = FakeAdb(mapOf("shell pm path com.example" to ok("package:/data/app/x.apk")))
        assertTrue(adb.isInstalled(serial, "com.example"))
        assertEquals(listOf("$serial: shell pm path com.example"), adb.calls)
    }
}
