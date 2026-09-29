package io.github.noamcohen48.tap.host

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrcpyAudioRecorderTest {
    @TempDir lateinit var directory: Path

    private class Child : Process() {
        var alive = true
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { alive = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { alive = false }
        override fun isAlive(): Boolean = alive
    }

    @Test fun `start stops and returns bytes while cleaning temporary files`() = runBlocking {
        val child = Child()
        var args = emptyList<String>()
        val recorder = ScrcpyAudioRecorder("emulator-5554", directory, launch = { cmd, _ ->
            args = cmd
            Files.write(Path.of(cmd.last().removePrefix("--record=")), byteArrayOf(1, 2, 3))
            child
        })
        recorder.start("playback", 10)
        assertEquals("emulator-5554", args[2])
        assertTrue("--audio-dup" in args)
        assertFailsWith<AudioRecordingException> { recorder.start("mic", 10) }
        assertContentEquals(byteArrayOf(1, 2, 3), recorder.stop())
        assertFalse(child.isAlive)
        assertEquals(0L, Files.list(directory.resolve("recordings")).use { it.count() })
        recorder.close()
        assertFailsWith<AudioRecordingException> { recorder.start("output", 10) }
    }

    @Test fun `detach closes a running capture and discards its file`() = runBlocking {
        val child = Child()
        val recorder = ScrcpyAudioRecorder("test-serial", directory, launch = { cmd, _ ->
            Files.write(Path.of(cmd.last().removePrefix("--record=")), byteArrayOf(1))
            child
        })
        recorder.start("output", 1)
        recorder.close()
        recorder.close()
        assertFalse(child.isAlive)
        assertEquals(0L, Files.list(directory.resolve("recordings")).use { it.count() })
    }

    @Test fun `validation prevents launching an unbounded capture`() = runBlocking {
        var launches = 0
        val recorder = ScrcpyAudioRecorder("test-serial", directory, launch = { _, _ -> launches++; Child() })
        assertFailsWith<AudioRecordingException> { recorder.start("call", 10) }
        assertFailsWith<AudioRecordingException> { recorder.start("output", 61) }
        assertEquals(0, launches)
    }
}
