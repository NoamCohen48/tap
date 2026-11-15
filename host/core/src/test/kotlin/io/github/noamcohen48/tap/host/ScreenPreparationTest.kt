package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenPreparationTest {
    private val serial = "85e49002"
    private val awake = "  mWakefulness=Awake\n  mWakefulnessChanging=false\n"
    private val powerCommand = listOf("shell", "dumpsys", "-t", "1", "power")
    private val wakeCommand = listOf("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    private val dismissCommand = listOf("shell", "wm", "dismiss-keyguard")

    private class PowerAdb(
        private val power: Result,
        private val failure: Throwable? = null,
    ) : Adb() {
        val calls = mutableListOf<Pair<String, List<String>>>()

        override suspend fun execResult(
            serial: String,
            vararg arguments: String,
            timeoutMs: Long,
        ): Result {
            calls += serial to arguments.toList()
            if (arguments.toList() == listOf("shell", "dumpsys", "-t", "1", "power")) {
                failure?.let { throw it }
                return power
            }
            return Result(0, "")
        }
    }

    @Test
    fun `awake phone skips input but still dismisses keyguard on the named serial`() =
        runTest {
            val adb = PowerAdb(Adb.Result(0, awake))
            adb.wakeAndDismissKeyguard(serial)
            assertEquals(listOf(serial to powerCommand, serial to dismissCommand), adb.calls)
        }

    @Test
    fun `all display groups must be awake and stable`() {
        assertTrue(powerStateDefinitelyAwake(awake + "Power Groups:\n  mWakefulness=Awake\n"))
        for (extra in listOf("mWakefulness=Asleep", "mWakefulness=Dozing", "mWakefulnessChanging=true")) {
            assertFalse(powerStateDefinitelyAwake(awake + "  $extra\n"), extra)
        }
    }

    @Test
    fun `unknown asleep transitioning and incomplete power reports retain wake path`() =
        runTest {
            for (output in listOf(
                "",
                "Permission Denial",
                "DUMP TIMEOUT",
                awake + "*** SERVICE 'power' DUMP TIMEOUT EXPIRED ***",
                "mWakefulness=Awake",
                "mWakefulnessChanging=false",
                "mWakefulness=Awake\nmWakefulnessChanging=true",
                "mWakefulness=Asleep\nmWakefulnessChanging=false",
                "mWakefulness=Dozing\nmWakefulnessChanging=false",
                "mWakefulness=Dreaming\nmWakefulnessChanging=false",
                "mWakefulness=Unknown\nmWakefulnessChanging=false",
                "mWakefulness=Awake (OEM)\nmWakefulnessChanging=false",
            )) {
                val adb = PowerAdb(Adb.Result(0, output))
                adb.wakeAndDismissKeyguard(serial)
                assertEquals(listOf(serial to powerCommand, serial to wakeCommand, serial to dismissCommand), adb.calls, output)
            }
        }

    @Test
    fun `failed power query does not trust even awake looking output`() =
        runTest {
            val adb = PowerAdb(Adb.Result(1, awake))
            adb.wakeAndDismissKeyguard(serial)
            assertEquals(listOf(serial to powerCommand, serial to wakeCommand, serial to dismissCommand), adb.calls)
        }

    @Test
    fun `power state is not cached across attachments`() =
        runTest {
            val adb = PowerAdb(Adb.Result(0, awake))
            repeat(2) { adb.wakeAndDismissKeyguard(serial) }
            assertEquals(listOf(powerCommand, dismissCommand, powerCommand, dismissCommand), adb.calls.map { it.second })
        }

    @Test
    fun `cancellation of the query is not swallowed into a wake mutation`() =
        runTest {
            val adb = PowerAdb(Adb.Result(0, awake), CancellationException("cancelled"))
            assertFailsWith<CancellationException> { adb.wakeAndDismissKeyguard(serial) }
            assertEquals(listOf(serial to powerCommand), adb.calls)
        }
}
