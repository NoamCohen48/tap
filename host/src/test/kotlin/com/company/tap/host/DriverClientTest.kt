package com.company.tap.host

import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SelectorKind
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DriverClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    private val driver = FakeDriverServer("session-1", 7, secret)
    private val client = DriverClient(driver.port, "session-1", 7, secret)
    private val selector = Selector(SelectorKind.TEXT, "hello")

    @AfterTest
    fun tearDown() {
        client.close()
        driver.close()
    }

    @Test
    fun handshakeExposesNegotiatedContract() {
        assertEquals("fake-driver", client.driverInstanceId)
        assertEquals(1, client.negotiatedVersion.major)
        assertTrue("synchronization.v1" in client.enabledCapabilities)
    }

    @Test
    fun responsesAreDemultiplexedByRequestIdRegardlessOfOrder() {
        val first = client.submit(Operation.EXISTS, selector)
        val second = client.submit(Operation.EXISTS, selector)
        val frames = listOf(driver.nextFrame(), driver.nextFrame())
        assertEquals(listOf(1L, 2L), frames.map { it.requestId })
        assertTrue(frames.all { it.type == FrameType.REQUEST })

        driver.respond(2, Response(true, value = false, durationMs = 1))
        driver.respond(1, Response(true, value = true, durationMs = 2))

        assertEquals(true, first.await().value)
        assertEquals(false, second.await().value)
        assertEquals(TransmissionState.TERMINAL_RESPONSE, first.transmissionState)
    }

    @Test
    fun cancelWritesCancelFrameAndReturnsDriverTerminalResponse() {
        val wait = client.submit(Operation.WAIT_VISIBLE, selector, timeoutMs = 30_000)
        assertEquals(FrameType.REQUEST, driver.nextFrame().type)

        assertTrue(wait.cancel())
        val cancel = driver.nextFrame()
        assertEquals(FrameType.CANCEL, cancel.type)
        assertEquals(wait.requestId, cancel.requestId)

        driver.respond(wait.requestId, Response(false, errorCode = "CANCELLED", durationMs = 40))
        assertEquals("CANCELLED", wait.await().errorCode)
        assertFalse(wait.cancel(), "terminal command must not be cancellable")
    }

    @Test
    fun cancelAfterMutationYieldsDriverDefinitiveResult() {
        val tap = client.submit(Operation.TAP, selector)
        driver.nextFrame()
        assertTrue(tap.cancel())
        assertEquals(FrameType.CANCEL, driver.nextFrame().type)

        driver.respond(tap.requestId, Response(true, value = true, durationMs = 12))

        assertTrue(tap.await().ok)
    }

    @Test
    fun pingRoundTripsOnConnectionLevelFrames() {
        val latency = CompletableFuture.supplyAsync { client.ping() }
        val ping = driver.nextFrame()
        assertEquals(FrameType.PING, ping.type)
        assertEquals(0L, ping.requestId)
        driver.pong()
        assertTrue(latency.get() >= 0)
    }

    @Test
    fun transportLossClassifiesInFlightCommandsByMutation() {
        val tap = client.submit(Operation.TAP, selector)
        val exists = client.submit(Operation.EXISTS, selector)
        driver.nextFrame()
        driver.nextFrame()

        driver.dropConnection()

        val tapFailure = assertFailsWith<CommandTransportException> { tap.await() }
        assertEquals(CommandErrorCode.INDETERMINATE, tapFailure.code)
        assertEquals(TransmissionState.WRITTEN, tapFailure.transmissionState)
        val existsFailure = assertFailsWith<CommandTransportException> { exists.await() }
        assertEquals(CommandErrorCode.TRANSPORT_LOST, existsFailure.code)

        val poisoned = assertFailsWith<CommandTransportException> { client.execute(Operation.HEALTH) }
        assertEquals(TransmissionState.NOT_WRITTEN, poisoned.transmissionState)
        assertEquals(-1, poisoned.requestId)
    }

    @Test
    fun unknownResponseIdPoisonsTheConnection() {
        val exists = client.submit(Operation.EXISTS, selector)
        driver.nextFrame()

        driver.respond(99, Response(true, durationMs = 0))

        val failure = assertFailsWith<CommandTransportException> { exists.await() }
        assertEquals(CommandErrorCode.TRANSPORT_LOST, failure.code)
    }

    @Test
    fun requestIdsAreStrictlyIncreasingAcrossConcurrentSubmitters() {
        val commands = (1..8).map { CompletableFuture.supplyAsync { client.submit(Operation.HEALTH) } }
            .map { it.get() }
        val ids = List(8) { driver.nextFrame().requestId }
        assertEquals(ids.sorted(), ids)
        assertEquals((1L..8L).toList(), ids)
        commands.forEach { driver.respond(it.requestId, Response(true, durationMs = 0)) }
        commands.forEach { assertTrue(it.await().ok) }
    }

    @Test
    fun validationRequestsCarryExplicitIdsAndVersions() {
        val response = CompletableFuture.supplyAsync {
            client.executeValidationRequest(requestId = 3, operationVersion = 2)
        }
        val frame = driver.nextFrame()
        assertEquals(3L, frame.requestId)
        val request = json.decodeFromString<Request>(frame.payload.decodeToString())
        assertEquals(2, request.operationVersion)
        assertEquals(Operation.HEALTH, request.operation)
        driver.respond(3, Response(false, errorCode = "UNSUPPORTED", durationMs = 0))
        assertEquals("UNSUPPORTED", response.get().errorCode)

        // Automatic allocation continues above the explicit ID the driver has already consumed.
        val next = client.submit(Operation.HEALTH)
        assertEquals(4L, driver.nextFrame().requestId)
        driver.respond(4, Response(true, durationMs = 0))
        assertTrue(next.await().ok)
    }

    @Test
    fun closeSendsCloseFrame() {
        client.close()
        assertEquals(FrameType.CLOSE, driver.nextFrame().type)
    }
}
