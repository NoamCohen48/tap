package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.ScrcpyVideoSource
import io.github.noamcohen48.tap.host.VideoPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Explicitly opted-in passive capture only: no attach, driver commands, AUT changes, input, wake or reboot. */
@Tag("passive-video")
class PassiveVideoTest {
    @TempDir lateinit var temp: Path

    @Test fun `pinned source decodes and cleans up on each approved serial`() =
        runBlocking {
            assumeTrue(java.lang.Boolean.getBoolean("tap.passiveVideo"), "requires -Ptap.passiveVideo=true")
            val serials =
                System
                    .getProperty("tap.serials", "")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            assumeTrue(serials.isNotEmpty(), "requires -Ptap.serials")
            for (serial in serials) {
                var namespace = ""
                var port = 0
                val adb =
                    object : Adb() {
                        override suspend fun pushVideoServer(
                            serial: String,
                            server: Path,
                            scid: String,
                        ) {
                            namespace = scid
                            super.pushVideoServer(serial, server, scid)
                        }

                        override suspend fun forwardVideoSocket(
                            serial: String,
                            scid: String,
                        ): Int {
                            port = super.forwardVideoSocket(serial, scid)
                            return port
                        }
                    }
                val source = ScrcpyVideoSource(adb, serial, Path.of(System.getenv("TAP_VIDEO_SERVER") ?: "/usr/share/scrcpy/scrcpy-server"))
                val ready = CompletableDeferred<Unit>()
                val file = Files.createTempFile(temp, "video-", ".h264")
                val output = Files.newOutputStream(file)
                var frames = 0
                val started = System.nanoTime()
                val capture =
                    async(Dispatchers.IO) {
                        runCatching {
                            source.run { sample ->
                                val packet = sample.packet
                                if (packet is VideoPacket.Data) {
                                    output.write(packet.bytes)
                                    if (!packet.configuration) {
                                        frames++
                                        if (packet.key) ready.complete(Unit)
                                    }
                                }
                            }
                        }.also { result -> result.exceptionOrNull()?.let { ready.completeExceptionally(it) } }
                    }
                try {
                    withTimeout(20_000) { ready.await() }
                    println("PASS first key frame $serial: ${(System.nanoTime() - started) / 1_000_000} ms")
                    delay(1000)
                } finally {
                    source.interrupt()
                    val ended = withTimeout(10_000) { capture.await() }
                    output.close()
                    ended.exceptionOrNull()?.let { failure ->
                        // Expected: interrupt closes the blocked socket; cleanup errors must fail the test.
                        if (failure !is java.net.SocketException && failure.message != "Video reader closed") throw failure
                    }
                }
                assertTrue(frames > 0)
                assertTrue(adb.processIds(serial, "tapv-$namespace").isEmpty(), "remote video child survived")
                val forwards = adb.run(serial, "forward", "--list")
                assertFalse(
                    forwards.lineSequence().any { line ->
                        val fields = line.trim().split(Regex("\\s+"))
                        fields.size == 3 && fields[0] == serial && fields[1] == "tcp:$port"
                    },
                    "private forward survived",
                )
                assertNotEquals(0, adb.runResult(serial, "shell", "test", "-e", "/data/local/tmp/tap-video-$namespace.jar").exitCode)
                val log = Files.createTempFile(temp, "decoder-", ".log")
                val decoder =
                    ProcessBuilder("ffmpeg", "-v", "error", "-threads", "1", "-i", file.toString(), "-frames:v", "1", "-f", "null", "-")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start()
                try {
                    assertTrue(decoder.waitFor(10, TimeUnit.SECONDS), "decoder timed out")
                    assertEquals(0, decoder.exitValue(), Files.readString(log))
                } finally {
                    if (decoder.isAlive) {
                        decoder.destroyForcibly()
                        decoder.waitFor(2, TimeUnit.SECONDS)
                    }
                }
                println("PASS $serial: $frames frames decoded; child, forward and private JAR removed")
            }
        }
}
