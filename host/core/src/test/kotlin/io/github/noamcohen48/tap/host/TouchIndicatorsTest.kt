package io.github.noamcohen48.tap.host

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TouchIndicatorsTest {
    @Test
    fun `reads without writing and changes only explicit switches for the named serial and current user`() =
        runBlocking {
            val calls = mutableListOf<List<String>>()
            val settings = mutableMapOf("pointer_location" to "1")
            val adb =
                testAdb(
                    ProcessStarter { command ->
                        calls += command
                        val args = command.dropWhile { it != "settings" }
                        val output =
                            when (args[3]) {
                                "get" -> settings[args[5]] ?: "null"
                                "put" -> "".also { settings[args[5]] = args[6] }
                                else -> error("Unexpected command $command")
                            }
                        FakeProcess(stdout = output)
                    },
                )
            assertEquals(TouchIndicatorState(false, true), adb.touchIndicators("chosen"))
            assertTrue(calls.none { "put" in it })
            assertEquals(TouchIndicatorState(true, true), adb.setTouchIndicators("chosen", true, null))
            assertEquals(mapOf("show_touches" to "1", "pointer_location" to "1"), settings)
            assertEquals(TouchIndicatorState(true, false), adb.setTouchIndicators("chosen", null, false))
            assertEquals(2, calls.count { "put" in it })
            assertTrue(calls.all { it.subList(1, 4) == listOf("-s", "chosen", "shell") })
            assertTrue(calls.all { it.dropWhile { arg -> arg != "settings" }.take(3) == listOf("settings", "--user", "current") })
            val before = calls.size
            assertFailsWith<IllegalArgumentException> { adb.setTouchIndicators("chosen", null, null) }
            assertEquals(before, calls.size)
        }

    @Test
    fun `readback refusal fails once instead of retrying or pretending the setting changed`() =
        runBlocking {
            var writes = 0
            val adb =
                testAdb(
                    ProcessStarter { command ->
                        if ("put" in command) writes++
                        FakeProcess(stdout = if ("get" in command) "0" else "")
                    },
                )
            assertFailsWith<DeviceSettingException> { adb.setTouchIndicators("serial", true, null) }
            assertEquals(1, writes)
        }

    @Test
    fun `invalid settings output is not silently treated as off`() =
        runBlocking {
            val adb = testAdb(ProcessStarter { FakeProcess(stdout = "Permission denial") })
            assertFailsWith<DeviceSettingException> { adb.touchIndicators("serial") }
        }
}
