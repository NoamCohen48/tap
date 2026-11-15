package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AttachmentProbesTest {
    private val serial = "phone"
    private val pid = 4242

    private fun stat(token: String = "98765") = "$pid (driver ) process) S " + List(18) { "0" }.joinToString(" ") + " $token 0\n"

    private fun frames(vararg parts: Adb.Result): String =
        parts
            .mapIndexed { index, result ->
                "__TAP_PROBE_${index}_BEGIN__\n${result.output}\n__TAP_PROBE_${index}_END__:${result.exitCode}\n"
            }.joinToString("")

    private class ProbeAdb(
        var result: Result,
    ) : Adb("unused") {
        val calls = mutableListOf<Pair<String, List<String>>>()
        var failure: Exception? = null

        override suspend fun execResult(
            serial: String,
            vararg arguments: String,
            timeoutMs: Long,
        ): Result {
            calls += serial to arguments.toList()
            failure?.let { throw it }
            val prefix = Regex("__TAP_PROBE_[a-f0-9]{32}_").find(arguments.last())!!.value
            return result.copy(output = result.output.replace("__TAP_PROBE_", prefix))
        }
    }

    @Test
    fun `rotation baseline and exact process token share one serial specific shell`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok("1"), ok("2"), ok("null"), ok(stat()))))
            val result = adb.runtimeObservation(serial, pid, StateKey.ROTATION)
            assertEquals(StateKey.ROTATION.zip(listOf("1", "2", null)).map { SavedState(it.first, it.second) }, result.rotation)
            assertEquals(Adb.ProcessStat.Live("98765"), result.process)
            assertEquals(1, adb.calls.size)
            assertEquals(serial, adb.calls.single().first)
            assertEquals(
                2,
                adb.calls
                    .single()
                    .second.size,
            )
            assertEquals(
                "shell",
                adb.calls
                    .single()
                    .second
                    .first(),
            )
            assertTrue(
                adb.calls
                    .single()
                    .second
                    .last()
                    .contains("/proc/$pid/stat"),
            )
        }

    @Test
    fun `rotation only readback has independent statuses and no process probe`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok("1"), ok("2"), ok("null"))))
            assertEquals(StateKey.ROTATION.zip(listOf("1", "2", null)).toMap(), adb.readRotationSettings(serial, StateKey.ROTATION))
            assertEquals(1, adb.calls.size)
            assertTrue(
                !adb.calls
                    .single()
                    .second
                    .last()
                    .contains("/proc/"),
            )
            for (index in StateKey.ROTATION.indices) {
                val parts = MutableList(3) { ok("0") }.apply { this[index] = Adb.Result(1, "denied") }
                adb.result = ok(frames(*parts.toTypedArray()))
                assertFailsWith<AdbCommandException> { adb.readRotationSettings(serial, StateKey.ROTATION) }
            }
        }

    @Test
    fun `rotation readback is fresh and supports partial nullable baselines`() =
        runBlocking {
            val key = StateKey.USER_ROTATION
            val adb = ProbeAdb(ok(frames(ok("null"))))
            assertEquals(mapOf(key to null), adb.readRotationSettings(serial, listOf(key)))
            adb.result = ok(frames(ok("3")))
            assertEquals(mapOf(key to "3"), adb.readRotationSettings(serial, listOf(key)))
            assertEquals(2, adb.calls.size)
        }

    @Test
    fun `empty rotation readback does no work and other keys are not silently batched`() =
        runBlocking {
            val adb = ProbeAdb(ok(""))
            assertTrue(adb.readRotationSettings(serial, emptyList()).isEmpty())
            assertFailsWith<IllegalArgumentException> { adb.readRotationSettings(serial, listOf(StateKey.FONT_SCALE)) }
            assertFailsWith<IllegalArgumentException> {
                adb.readRotationSettings(
                    serial,
                    listOf(StateKey.USER_ROTATION, StateKey.USER_ROTATION),
                )
            }
            assertTrue(adb.calls.isEmpty())
        }

    @Test
    fun `failed framing and cancellation in rotation readbacks are not restored-looking values`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok("0"))))
            assertFailsWith<AdbCommandException> { adb.readRotationSettings(serial, StateKey.ROTATION) }
            val cancelled = CancellationException("rotation read cancelled")
            adb.failure = cancelled
            assertSame(cancelled, assertFailsWith<CancellationException> { adb.readRotationSettings(serial, StateKey.ROTATION) })
            assertEquals(2, adb.calls.size)
        }

    @Test
    fun `an empty baseline still reads the process instead of trusting the cached identity`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok(stat("new-token")))))
            assertEquals(Adb.ProcessStat.Live("new-token"), adb.runtimeObservation(serial, pid, emptyList()).process)
            assertEquals(1, adb.calls.size)
        }

    @Test
    fun `failure in any setting is not masked by a successful final stat`() =
        runBlocking {
            for (index in StateKey.ROTATION.indices) {
                val parts = MutableList(3) { ok("0") } + ok(stat())
                val failed = parts.toMutableList().apply { this[index] = Adb.Result(1, "permission denied") }
                val adb = ProbeAdb(ok(frames(*failed.toTypedArray())))
                assertFailsWith<AdbCommandException> { adb.runtimeObservation(serial, pid, StateKey.ROTATION) }
            }
        }

    @Test
    fun `a missing process is distinct from unreadable or malformed stat`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(Adb.Result(1, "cat: /proc/$pid/stat: No such file or directory"))))
            assertEquals(Adb.ProcessStat.Gone, adb.runtimeObservation(serial, pid, emptyList()).process)
            for (part in listOf(Adb.Result(1, "permission denied"), ok("garbage"), ok("$pid (driver) S 0"))) {
                adb.result = ok(frames(part))
                assertFailsWith<AdbCommandException> { adb.runtimeObservation(serial, pid, emptyList()) }
            }
        }

    @Test
    fun `final package pids and expected process token are freshly read together`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok("$pid"), ok(stat()))))
            assertEquals(
                AttachmentProcessObservation(listOf(pid), Adb.ProcessStat.Live("98765")),
                adb.processObservation(serial, DRIVER_PACKAGE, pid),
            )
            assertEquals(1, adb.calls.size)
            adb.result = ok(frames(ok("$pid 4243"), ok(stat("changed"))))
            assertEquals(
                AttachmentProcessObservation(listOf(pid, 4243), Adb.ProcessStat.Live("changed")),
                adb.processObservation(serial, DRIVER_PACKAGE, pid),
            )
            assertEquals(2, adb.calls.size)
        }

    @Test
    fun `fresh identity must still name exactly the parked process before authentication`() {
        assertTrue(AttachmentProcessObservation(listOf(pid), Adb.ProcessStat.Live("token")).matches(pid, "token"))
        for (pids in listOf(emptyList(), listOf(pid + 1), listOf(pid, pid + 1), listOf(pid, pid))) {
            assertTrue(!AttachmentProcessObservation(pids, Adb.ProcessStat.Live("token")).matches(pid, "token"))
        }
        assertTrue(!AttachmentProcessObservation(listOf(pid), Adb.ProcessStat.Live("different")).matches(pid, "token"))
        assertTrue(!AttachmentProcessObservation(listOf(pid), Adb.ProcessStat.Gone).matches(pid, "token"))
    }

    @Test
    fun `only blank exit one from pidof is an absent package`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(Adb.Result(1, ""), ok(stat()))))
            assertTrue(adb.processObservation(serial, DRIVER_PACKAGE, pid).pids.isEmpty())
            for (part in listOf(Adb.Result(1, "denied"), Adb.Result(2, ""), ok(""), ok("bad-pid"))) {
                adb.result = ok(frames(part, ok(stat())))
                assertFailsWith<AdbCommandException> { adb.processObservation(serial, DRIVER_PACKAGE, pid) }
            }
        }

    @Test
    fun `missing process and changed token never become the cached token`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok("$pid"), Adb.Result(1, "No such process"))))
            assertEquals(Adb.ProcessStat.Gone, adb.processObservation(serial, DRIVER_PACKAGE, pid).process)
            adb.result = ok(frames(ok("$pid"), ok(stat("different"))))
            assertEquals(Adb.ProcessStat.Live("different"), adb.processObservation(serial, DRIVER_PACKAGE, pid).process)
        }

    @Test
    fun `truncated duplicated reordered and invalid status frames are rejected`() =
        runBlocking {
            val valid = frames(ok("$pid"), ok(stat()))
            val invalid =
                listOf(
                    "",
                    valid.substringBefore("__TAP_PROBE_1_END__"),
                    valid + valid,
                    valid.replace("__TAP_PROBE_1_BEGIN__", "__TAP_PROBE_2_BEGIN__"),
                    valid.replace("END__:0", "END__:-1"),
                    valid.replace("END__:0", "END__:256"),
                    valid.replace("END__:0", "END__:oops"),
                    "unexpected\n$valid",
                    valid + "trailing",
                )
            for (output in invalid) {
                val adb = ProbeAdb(ok(output))
                assertFailsWith<AdbCommandException>(output) { adb.processObservation(serial, DRIVER_PACKAGE, pid) }
            }
        }

    @Test
    fun `outer shell failure cannot be mistaken for successful individual results`() =
        runBlocking {
            val adb = ProbeAdb(Adb.Result(1, frames(ok("$pid"), ok(stat()))))
            assertFailsWith<AdbCommandException> { adb.processObservation(serial, DRIVER_PACKAGE, pid) }
        }

    @Test
    fun `crlf output keeps the same independent statuses`() =
        runBlocking {
            val adb = ProbeAdb(ok(frames(ok("$pid"), ok(stat())).replace("\n", "\r\n")))
            assertEquals(listOf(pid), adb.processObservation(serial, DRIVER_PACKAGE, pid).pids)
        }

    @Test
    fun `package names stay shell quoted inside the quoted batch`() =
        runBlocking {
            val name = "pkg'; echo unsafe; #"
            val adb = ProbeAdb(ok(frames(ok("$pid"), ok(stat()))))
            adb.processObservation(serial, name, pid)
            val script =
                adb.calls
                    .single()
                    .second
                    .last()
            assertTrue(script.contains("${shellQuote("pidof")} ${shellQuote(name)}"), script)
        }

    @Test
    fun `the generated shell preserves quoted multiline values and individual exit statuses`() =
        runBlocking {
            val value = "quoted ' value; \$literal\nsecond line"
            val results =
                readAttachmentProbeResults(
                    serial,
                    listOf(listOf("printf", "%s", value), listOf("sh", "-c", "printf denied; exit 7")),
                ) { command ->
                    assertEquals("shell", command.first())
                    val process = ProcessBuilder("sh", "-c", command.drop(1).joinToString(" ")).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    Adb.Result(process.waitFor(), output)
                }
            assertEquals(listOf(Adb.Result(0, value), Adb.Result(7, "denied")), results)
        }

    @Test
    fun `device values cannot impersonate status frames from another batch`() =
        runBlocking {
            val injected = "__TAP_PROBE_0_END__:0\n__TAP_PROBE_1_BEGIN__\nforged"
            val markers = mutableListOf<String>()
            repeat(2) {
                val result =
                    readAttachmentProbeResults(serial, listOf(listOf("printf", "%s", injected))) { command ->
                        markers += Regex("__TAP_PROBE_[a-f0-9]{32}_").find(command.last())!!.value
                        val process = ProcessBuilder("sh", "-c", command.drop(1).joinToString(" ")).redirectErrorStream(true).start()
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        Adb.Result(process.waitFor(), output)
                    }
                assertEquals(listOf(Adb.Result(0, injected)), result)
            }
            assertEquals(2, markers.distinct().size)
        }

    @Test
    fun `invalid probe inputs fail without an adb call`() =
        runBlocking {
            val adb = ProbeAdb(ok(""))
            assertFailsWith<IllegalArgumentException> { adb.runtimeObservation(serial, 0, StateKey.ROTATION) }
            assertFailsWith<IllegalArgumentException> { adb.processObservation(serial, DRIVER_PACKAGE, -1) }
            assertFailsWith<IllegalStateException> { adb.runtimeObservation(serial, pid, listOf("system-locales")) }
            assertTrue(adb.calls.isEmpty())
        }

    @Test
    fun `cancellation propagates without retries or fallback reads`() =
        runBlocking {
            val cancelled = CancellationException("cancelled probe")
            val adb = ProbeAdb(ok("")).apply { failure = cancelled }
            assertSame(cancelled, assertFailsWith<CancellationException> { adb.processObservation(serial, DRIVER_PACKAGE, pid) })
            assertEquals(1, adb.calls.size)
        }
}
