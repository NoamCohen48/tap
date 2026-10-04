package io.github.noamcohen48.tap.host

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

class ScrcpyVideoProtocolTest {
    private fun wire(body: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream()
            .also {
                DataOutputStream(it).apply {
                    writeInt(0x68323634)
                    body()
                }
            }.toByteArray()

    private fun DataOutputStream.session(
        width: Int = 498,
        height: Int = 1024,
    ) {
        writeInt(Int.MIN_VALUE)
        writeInt(width)
        writeInt(height)
    }

    private fun DataOutputStream.packet(
        flags: Long,
        payload: ByteArray = byteArrayOf(0, 0, 0, 1, 0x67),
    ) {
        writeLong(flags)
        writeInt(payload.size)
        write(payload)
    }

    @Test fun `reads pinned session, codec config and key frame with unsigned low PTS bits`() {
        val pts = 0x123ffffffffL
        val reader =
            ScrcpyVideoProtocol(
                ByteArrayInputStream(
                    wire {
                        session()
                        packet(1L shl 62)
                        packet((1L shl 61) or pts)
                    },
                ),
            )
        assertEquals(VideoPacket.Session(498, 1024), reader.next())
        val config = reader.next() as VideoPacket.Data
        assertTrue(config.configuration)
        assertFalse(config.key)
        assertEquals(0L, config.ptsUs)
        val frame = reader.next() as VideoPacket.Data
        assertTrue(frame.key)
        assertFalse(frame.configuration)
        assertEquals(pts, frame.ptsUs)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67), frame.bytes)
    }

    @Test fun `session changes are explicit on rotation`() {
        val reader =
            ScrcpyVideoProtocol(
                ByteArrayInputStream(
                    wire {
                        session()
                        session(1024, 498)
                    },
                ),
            )
        reader.next()
        assertEquals(VideoPacket.Session(1024, 498), reader.next())
    }

    @Test fun `rejects packets before metadata and invalid dimensions`() {
        assertThrows(VideoException::class.java) { ScrcpyVideoProtocol(ByteArrayInputStream(wire { packet(1L shl 61) })).next() }
        assertThrows(VideoException::class.java) { ScrcpyVideoProtocol(ByteArrayInputStream(wire { session(-1, 1024) })).next() }
    }

    @Test fun `bounds packet allocation before reading payload`() {
        for (size in listOf(-1, 0, ScrcpyVideoProtocol.MAX_PACKET_BYTES + 1)) {
            val reader =
                ScrcpyVideoProtocol(
                    ByteArrayInputStream(
                        wire {
                            session()
                            writeLong(1)
                            writeInt(size)
                        },
                    ),
                )
            reader.next()
            assertThrows(VideoException::class.java) { reader.next() }
        }
    }

    @Test fun `truncated payload is never emitted`() {
        val reader =
            ScrcpyVideoProtocol(
                ByteArrayInputStream(
                    wire {
                        session()
                        writeLong(1)
                        writeInt(8)
                        writeByte(0)
                    },
                ),
            )
        reader.next()
        assertThrows(EOFException::class.java) { reader.next() }
    }

    @Test fun `launch stays below tested Samsung command length and explicitly disables input audio and wake`() {
        val args = videoServerArguments("7fffffff")
        assertTrue(args.joinToString(" ").toByteArray().size < 256)
        assertTrue(args.containsAll(listOf("control=false", "audio=false", "power_on=false", "cleanup=false")))
        assertTrue(args.contains("--nice-name=tapv-7fffffff"))
        assertFalse(videoServerPath("7fffffff").endsWith("/scrcpy-server.jar"))
        assertThrows(IllegalArgumentException::class.java) { videoServerArguments("../bad") }
    }
}
