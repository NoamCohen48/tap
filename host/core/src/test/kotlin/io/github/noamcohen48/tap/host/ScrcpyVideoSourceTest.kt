package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ScrcpyVideoSourceTest {
    @TempDir lateinit var temp: Path

    @Test fun `interrupt unblocks idle video and reaps only its own namespace`() =
        runBlocking {
            val calls = CopyOnWriteArrayList<String>()
            val child = FakeProcess(stdout = "TAP_VIDEO_PID=1234\n", exitDelayMs = FakeProcess.NEVER)
            ServerSocket(0).use { listener ->
                val sender =
                    thread(isDaemon = true) {
                        listener.accept().use { socket ->
                            DataOutputStream(socket.getOutputStream()).apply {
                                writeByte(0)
                                write(ByteArray(64))
                                writeInt(0x68323634)
                                writeInt(Int.MIN_VALUE)
                                writeInt(498)
                                writeInt(1024)
                                writeLong(1L shl 62)
                                writeInt(5)
                                write(byteArrayOf(0, 0, 0, 1, 0x67))
                                writeLong((1L shl 61) or 100)
                                writeInt(5)
                                write(byteArrayOf(0, 0, 0, 1, 0x65))
                                flush()
                            }
                            socket.getInputStream().read() // deliberately no further frames
                        }
                    }
                val adb =
                    object : Adb() {
                        override suspend fun pushVideoServer(
                            serial: String,
                            server: Path,
                            scid: String,
                        ) {
                            calls += "push:$serial:$scid"
                        }

                        override suspend fun forwardVideoSocket(
                            serial: String,
                            scid: String,
                        ): Int {
                            calls += "forward:$serial:$scid"
                            return listener.localPort
                        }

                        override suspend fun startVideoServer(
                            serial: String,
                            scid: String,
                        ): Process {
                            calls += "start:$serial:$scid"
                            return child
                        }

                        override suspend fun stopVideoServer(
                            serial: String,
                            scid: String,
                            pid: Int,
                        ) {
                            calls += "stop:$serial:$scid:$pid"
                        }

                        override suspend fun removeForward(
                            serial: String,
                            hostPort: Int,
                        ) {
                            calls += "remove:$serial:$hostPort"
                        }

                        override suspend fun deleteVideoServer(
                            serial: String,
                            scid: String,
                        ) {
                            calls += "delete:$serial:$scid"
                        }
                    }
                val source = ScrcpyVideoSource(adb, "one-serial", Files.createFile(temp.resolve("server")))
                val ready = CompletableDeferred<VideoSample>()
                val run =
                    async {
                        runCatching {
                            source.run { sample ->
                                val packet = sample.packet
                                if (packet is VideoPacket.Data && packet.key) ready.complete(sample)
                            }
                        }
                    }
                val sample = withTimeout(5000) { ready.await() }
                assertEquals(MediaClock.id, sample.clockId)
                assertTrue(sample.receivedMonotonicNs >= 0 && sample.receivedMonotonicNs <= MediaClock.nowNs())
                assertTrue(sample.receivedEpochMs > 0)
                source.interrupt()
                assertTrue(withTimeout(5000) { run.await() }.isFailure)
                sender.join(1000)
                assertTrue(child.destroyed.get())
                assertFalse(sender.isAlive)
                assertEquals(listOf("push", "forward", "start", "stop", "remove", "delete"), calls.map { it.substringBefore(':') })
                assertTrue(calls.all { it.split(':')[1] == "one-serial" })
                val scid = calls.first().split(':')[2]
                assertEquals("stop:one-serial:$scid:1234", calls[3])
            }
        }

    @Test fun `missing optional server performs no adb calls`() =
        runBlocking {
            val source = ScrcpyVideoSource(Adb("not-an-executable"), "serial", temp.resolve("missing"))
            val failure = runCatching { source.run {} }.exceptionOrNull()
            assertInstanceOf(VideoException::class.java, failure)
            assertTrue(failure!!.message!!.contains("--scrcpy-server"))
        }
}
