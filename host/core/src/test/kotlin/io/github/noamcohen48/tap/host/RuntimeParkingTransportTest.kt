package io.github.noamcohen48.tap.host

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.protocolVersion
import io.github.noamcohen48.tap.wire.v1.ParkRuntime
import io.github.noamcohen48.tap.wire.v1.RuntimeParked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RuntimeParkingTransportTest {
    private val secret = ByteArray(32) { 1 }

    private fun next() =
        ParkRuntime
            .newBuilder()
            .setNextSessionId("next")
            .setNextGeneration(8)
            .setNextSecret(ByteString.copyFrom(ByteArray(32) { 2 }))
            .setIdleTimeoutMs(60_000)
            .build()

    @Test
    fun `acknowledged handoff closes old transport and new client starts at request one`() =
        runBlocking {
            FakeDriverServer("old", 7, secret, acceptAnySession = true, allowReconnect = true).use { fake ->
                val old = DriverClient.connect(fake.port, "old", 7, secret, heartbeatIntervalMs = 0)
                try {
                    val parking = async(Dispatchers.IO) { old.parkForReuse(next()) }
                    val frame = fake.nextFrame()
                    assertEquals(FrameType.PARK_RUNTIME, frame.type)
                    assertEquals(0L, frame.requestId)
                    fake.acknowledgePark(frame)
                    withTimeout(5_000) { parking.await() }
                    assertFalse(old.isPoisoned)
                    assertFailsWith<CommandTransportException> { old.execute(Requests.health()) }
                    val fresh = DriverClient.connect(fake.port, "next", 8, next().nextSecret.toByteArray(), heartbeatIntervalMs = 0)
                    try {
                        val health = async(Dispatchers.IO) { fresh.execute(Requests.health()) }
                        val request = fake.nextFrame()
                        assertEquals(1L, request.requestId)
                        fake.respond(request.requestId, Responses.done(8))
                        withTimeout(5_000) { health.await() }
                    } finally {
                        fresh.close()
                    }
                } finally {
                    old.close()
                }
            }
        }

    @Test
    fun `pending work prevents parking without poisoning or transmitting a handoff`() =
        runBlocking {
            FakeDriverServer("old", 7, secret).use { fake ->
                val client = DriverClient.connect(fake.port, "old", 7, secret, heartbeatIntervalMs = 0)
                try {
                    val health = async(Dispatchers.IO) { client.execute(Requests.health()) }
                    val request = fake.nextFrame()
                    assertFailsWith<IllegalStateException> { client.parkForReuse(next()) }
                    assertFalse(client.isPoisoned)
                    fake.respond(request.requestId, Responses.done(7))
                    withTimeout(5_000) { health.await() }
                    val parking = async(Dispatchers.IO) { client.parkForReuse(next()) }
                    fake.acknowledgePark(fake.nextFrame())
                    withTimeout(5_000) { parking.await() }
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `wrong acknowledgement identity cannot be treated as successful reuse`() =
        runBlocking {
            FakeDriverServer("old", 7, secret).use { fake ->
                val client = DriverClient.connect(fake.port, "old", 7, secret, heartbeatIntervalMs = 0)
                try {
                    val parking = async(Dispatchers.IO) { runCatching { client.parkForReuse(next()) } }
                    assertEquals(FrameType.PARK_RUNTIME, fake.nextFrame().type)
                    val bad =
                        RuntimeParked
                            .newBuilder()
                            .setNextSessionId("wrong")
                            .setNextGeneration(8)
                            .build()
                    fake.write(Frame(FrameType.RUNTIME_PARKED, 0, bad.toByteArray()))
                    val result = withTimeout(5_000) { parking.await() }
                    assertEquals(IllegalStateException::class, result.exceptionOrNull()!!::class)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `protocol 5_1 cannot opt into runtime parking even with an incorrect capability advert`() =
        runBlocking {
            FakeDriverServer("old", 7, secret, supportedVersions = listOf(protocolVersion(5, 1))).use { fake ->
                val client = DriverClient.connect(fake.port, "old", 7, secret, heartbeatIntervalMs = 0)
                try {
                    assertFailsWith<IllegalStateException> { client.parkForReuse(next()) }
                } finally {
                    client.close()
                }
            }
        }
}
