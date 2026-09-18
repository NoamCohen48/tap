package com.company.tap.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameCodecTest {
    @Test
    fun roundTripsFrame() {
        val expected = Frame(FrameType.REQUEST, 42, "payload".encodeToByteArray())
        val bytes = ByteArrayOutputStream().also { FrameCodec.write(it, expected) }.toByteArray()
        val actual = FrameCodec.read(ByteArrayInputStream(bytes))

        assertEquals(expected.type, actual.type)
        assertEquals(expected.requestId, actual.requestId)
        assertContentEquals(expected.payload, actual.payload)
    }

    @Test
    fun authenticatesBothSidesWithDifferentDomainMacs() {
        val secret = ByteArray(32) { it.toByte() }
        val transcript = ProtocolAuthentication.transcript(
            "hello".encodeToByteArray(),
            "challenge".encodeToByteArray(),
            "negotiation".encodeToByteArray(),
        )
        val hostMac = ProtocolAuthentication.hostMac(secret, transcript)
        val driverMac = ProtocolAuthentication.driverMac(secret, transcript)

        assertFalse(hostMac == driverMac)
        assertTrue(ProtocolAuthentication.constantTimeEquals(hostMac, hostMac))
        assertFalse(ProtocolAuthentication.constantTimeEquals(hostMac, driverMac))
    }
}
