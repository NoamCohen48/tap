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

class ScrcpyRecorderTest {
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

    @Test fun `audio starts stops and cleans temporary files`() = runBlocking {
        val child = Child()
        var args = emptyList<String>()
        val recorder = ScrcpyRecorder("emulator-5554", directory, launch = { cmd, _ ->
            args = cmd
            Files.write(Path.of(cmd.last().removePrefix("--record=")), byteArrayOf(1, 2, 3))
            child
        })
        recorder.start("playback", 10)
        assertEquals("emulator-5554", args[2])
        assertTrue("--audio-dup" in args)
        assertFailsWith<RecordingException> { recorder.start("mic", 10) }
        assertContentEquals(byteArrayOf(1, 2, 3), recorder.stop())
        assertFalse(child.isAlive)
        assertEquals(0L, Files.list(directory.resolve("recordings")).use { it.count() })
        recorder.close()
        assertFailsWith<RecordingException> { recorder.start("output", 10) }
    }

    @Test fun `one child records video and audio as matroska`() = runBlocking {
        val child = Child()
        var args = emptyList<String>()
        val recorder = ScrcpyRecorder("emulator-5554", directory, launch = { cmd, _ ->
            args = cmd
            Files.write(Path.of(cmd.last().removePrefix("--record=")), byteArrayOf(4, 5))
            child
        })
        recorder.start(video = true, audioSource = "playback", maxSeconds = 10)
        assertTrue("--audio-dup" in args)
        assertTrue("--video-bit-rate=2M" in args)
        assertTrue("--max-size=1024" in args && "--max-fps=15" in args)
        assertTrue("--no-video" !in args && "--no-audio" !in args)
        assertTrue(args.last().endsWith(".mkv"))
        assertFailsWith<RecordingException> { recorder.start("mic", 10) }
        assertFailsWith<RecordingException> { recorder.stop() }
        val media = recorder.stopMedia()
        assertEquals("mkv", media.format)
        assertContentEquals(byteArrayOf(4, 5), media.bytes)
        assertFalse(child.isAlive)
    }

    @Test fun `video without audio uses MP4`() = runBlocking {
        var args = emptyList<String>()
        val recorder = ScrcpyRecorder("emulator-5554", directory, launch = { cmd, _ ->
            args = cmd
            Files.write(Path.of(cmd.last().removePrefix("--record=")), byteArrayOf(1))
            Child()
        })
        recorder.start(video = true, audioSource = null, maxSeconds = 30)
        assertTrue("--no-audio" in args)
        assertEquals("mp4", recorder.stopMedia().format)
        assertFailsWith<RecordingException> { recorder.start(video = true, audioSource = null, maxSeconds = 31) }
    }

    @Test fun `detach closes and discards a running capture`() = runBlocking {
        val child = Child()
        val recorder = ScrcpyRecorder("test-serial", directory, launch = { cmd, _ ->
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
        val recorder = ScrcpyRecorder("test-serial", directory, launch = { _, _ -> launches++; Child() })
        assertFailsWith<RecordingException> { recorder.start("call", 10) }
        assertFailsWith<RecordingException> { recorder.start("output", 61) }
        assertFailsWith<RecordingException> { recorder.start(video = false, audioSource = null, maxSeconds = 10) }
        assertEquals(0, launches)
    }
}
