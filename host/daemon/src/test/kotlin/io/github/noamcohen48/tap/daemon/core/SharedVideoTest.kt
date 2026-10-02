package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.WatchVideoResponse
import io.github.noamcohen48.tap.host.VideoCleanupException
import io.github.noamcohen48.tap.host.VideoException
import io.github.noamcohen48.tap.host.VideoPacket
import io.github.noamcohen48.tap.host.VideoSample
import io.github.noamcohen48.tap.host.VideoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedVideoTest {
    private class Source : VideoSource {
        val packets = Channel<VideoSample>(16)
        val stopped = CompletableDeferred<Unit>()
        val interrupts = AtomicInteger()

        override fun interrupt() {
            interrupts.incrementAndGet()
            packets.close()
        }

        override suspend fun run(receive: suspend (VideoSample) -> Unit) {
            try {
                for (packet in packets) receive(packet)
            } finally {
                stopped.complete(Unit)
            }
        }

        suspend fun send(
            packet: VideoPacket,
            ns: Long = 0,
        ) {
            packets.send(VideoSample(packet, ns, 1234, "clock"))
        }
    }

    @Test fun `readers share one producer and cancelling one leaves the other alive`() =
        runBlocking {
            val source = Source()
            val opened = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val hub =
                SharedVideo {
                    count.incrementAndGet()
                    opened.complete(Unit)
                    source
                }
            val headerA = CompletableDeferred<Unit>()
            val frameA = CompletableDeferred<WatchVideoResponse>()
            val first =
                async {
                    hub.watch("serial").collect {
                        if (it.hasHeader()) headerA.complete(Unit)
                        if (it.hasFrame()) frameA.complete(it)
                    }
                }
            withTimeout(5000) { opened.await() }
            source.send(VideoPacket.Session(100, 200))
            source.send(VideoPacket.Data(byteArrayOf(1), 0, false, true))
            withTimeout(5000) { headerA.await() }
            val headerB = CompletableDeferred<WatchVideoResponse>()
            val frameB = CompletableDeferred<WatchVideoResponse>()
            val second =
                async {
                    hub.watch("serial").collect {
                        if (it.hasHeader()) headerB.complete(it)
                        if (it.hasFrame()) frameB.complete(it)
                    }
                }
            assertEquals("clock", withTimeout(5000) { headerB.await() }.header.clockId)
            source.send(VideoPacket.Data(byteArrayOf(2), 10, true, false), 99)
            assertEquals(withTimeout(5000) { frameA.await() }, withTimeout(5000) { frameB.await() })
            assertEquals(99, frameA.await().frame.receivedMonotonicNs)
            first.cancelAndJoin()
            assertEquals(0, source.interrupts.get())
            second.cancelAndJoin()
            withTimeout(5000) { source.stopped.await() }
            assertEquals(1, count.get())
            assertTrue(source.interrupts.get() > 0)
            hub.stop()
            hub.awaitStop(5000)
        }

    @Test fun `cleanup failure gates restart instead of launching another encoder`() =
        runBlocking {
            val count = AtomicInteger()
            val hub =
                SharedVideo {
                    count.incrementAndGet()
                    object : VideoSource {
                        override fun interrupt() {}

                        override suspend fun run(receive: suspend (VideoSample) -> Unit): Unit =
                            throw VideoCleanupException("unknown child", IllegalStateException())
                    }
                }
            val failure = runCatching { withTimeout(5000) { hub.watch("serial").first() } }.exceptionOrNull()
            assertInstanceOf(VideoCleanupException::class.java, failure)
            val gated = runCatching { hub.watch("serial").first() }.exceptionOrNull()
            assertInstanceOf(VideoException::class.java, gated)
            assertTrue(gated!!.message!!.contains("unproven"))
            assertEquals(1, count.get())
            hub.stop()
            hub.awaitStop(5000)
        }
}
