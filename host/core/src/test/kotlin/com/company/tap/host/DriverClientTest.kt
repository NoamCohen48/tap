package com.company.tap.host

import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.BoolResult
import com.company.tap.protocol.Done
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Exists
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Health
import com.company.tap.protocol.MAX_BLOB_CHUNK_BYTES
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Screenshot
import com.company.tap.protocol.ScrollUntil
import com.company.tap.protocol.Selector
import com.company.tap.protocol.Tap
import com.company.tap.protocol.WaitVisible
import com.company.tap.protocol.detail
import com.company.tap.protocol.errorCode
import com.company.tap.protocol.result
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    private val driver = FakeDriverServer("session-1", 7, secret)
    private lateinit var client: DriverClient
    private val selector = Selector.text("hello")

    @BeforeTest
    fun setUp() =
        runBlocking {
            client = DriverClient.connect(driver.port, "session-1", 7, secret, heartbeatIntervalMs = 0)
        }

    @AfterTest
    fun tearDown() =
        runBlocking {
            client.close()
            driver.close()
        }

    @Test
    fun handshakeExposesNegotiatedContract() {
        assertEquals("fake-driver", client.driverInstanceId)
        assertEquals(2, client.negotiatedVersion.major)
        assertTrue("synchronization.v1" in client.enabledCapabilities)
    }

    @Test
    fun responsesAreDemultiplexedByRequestIdRegardlessOfOrder() =
        runBlocking {
            val first = client.submit(Exists(selector))
            val second = client.submit(Exists(selector))
            val frames = listOf(driver.nextFrame(), driver.nextFrame())
            assertEquals(listOf(1L, 2L), frames.map { it.requestId })
            assertTrue(frames.all { it.type == FrameType.REQUEST })

            driver.respond(2, Response.ok(BoolResult(false), durationMs = 1))
            driver.respond(1, Response.ok(BoolResult(true), durationMs = 2))

            assertEquals(BoolResult(true), first.await().result)
            assertEquals(BoolResult(false), second.await().result)
            assertEquals(TransmissionState.TERMINAL_RESPONSE, first.transmissionState)
        }

    @Test
    fun cancelWritesCancelFrameAndReturnsDriverTerminalResponse() =
        runBlocking {
            val wait = client.submit(WaitVisible(selector), timeoutMs = 30_000)
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)

            assertTrue(wait.cancel())
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            assertEquals(wait.requestId, cancel.requestId)

            driver.respond(wait.requestId, Response.failure(ErrorCode.CANCELLED, durationMs = 40))
            assertEquals(ErrorCode.CANCELLED, wait.await().errorCode)
            assertFalse(wait.cancel(), "terminal command must not be cancellable")
        }

    @Test
    fun awaitingCoroutineCancelSendsCancelButRecordsDriverTerminalResponse() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)

            var outcome: Response? = null
            // Undispatched: the child is guaranteed suspended inside await() before cancel() runs.
            val awaiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { outcome = tap.await() }
            awaiting.cancel()
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            assertEquals(tap.requestId, cancel.requestId)

            driver.respond(tap.requestId, Response.ok(Done, durationMs = 12))
            awaiting.join()
            assertTrue(outcome?.ok == true)
            assertEquals(TransmissionState.TERMINAL_RESPONSE, tap.transmissionState)

            val health = client.submit(Health)
            driver.respond(driver.nextFrame().requestId, Response.ok(Done, durationMs = 1))
            assertTrue(health.await().ok)
        }

    @Test
    fun idleClientSendsHeartbeatPingsAndPoisonsWhenUnanswered() =
        runBlocking {
            val heartbeatSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val fake = FakeDriverServer("session-2", 1, heartbeatSecret)
            try {
                val beating = DriverClient.connect(fake.port, "session-2", 1, heartbeatSecret, heartbeatIntervalMs = 100)
                try {
                    repeat(2) {
                        assertEquals(FrameType.PING, fake.nextFrame(1_000).type)
                        fake.pong()
                    }
                    // A regular command is host activity too; the next PING waits for another idle interval.
                    val health = beating.submit(Health)
                    assertEquals(FrameType.REQUEST, fake.nextFrame().type)
                    fake.respond(health.requestId, Response.ok(Done, durationMs = 1))
                    assertTrue(health.await().ok)
                    assertEquals(FrameType.PING, fake.nextFrame(1_000).type)
                    // Not answering this one poisons the client.
                    delay(300)
                    val failed =
                        assertFailsWith<CommandTransportException> {
                            beating.execute(Health)
                        }
                    assertEquals(ErrorCode.TRANSPORT_LOST, failed.code)
                } finally {
                    beating.close()
                }
            } finally {
                fake.close()
            }
        }

    @Test
    fun screenshotReassemblesAndVerifiesTheBlob() =
        runBlocking {
            val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES * 2 + 5) { (it * 7).toByte() }
            val pending = async(Dispatchers.IO) { client.screenshot() }
            val request = driver.nextFrame()
            assertEquals(Screenshot, json.decodeFromString<Request>(request.payload.decodeToString()).command)
            val info = driver.sendArtifact(request.requestId, bytes)
            val screenshot = pending.await()
            assertTrue(bytes.contentEquals(screenshot.png))
            assertEquals(info, screenshot.info)
        }

    @Test
    fun corruptedBlobsBecomeArtifactTransferFailed() =
        runBlocking {
            val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES + 1) { it.toByte() }
            val expectations =
                mapOf(
                    FakeDriverServer.Corruption.FLIP_BYTE to ErrorDetail.BLOB_CHECKSUM_MISMATCH,
                    FakeDriverServer.Corruption.DROP_CHUNK to ErrorDetail.BLOB_OUT_OF_ORDER,
                    FakeDriverServer.Corruption.REORDER to ErrorDetail.BLOB_OUT_OF_ORDER,
                    FakeDriverServer.Corruption.NO_END to ErrorDetail.BLOB_INCOMPLETE,
                )
            expectations.forEach { (corruption, detail) ->
                val command = client.submit(Screenshot)
                driver.sendArtifact(driver.nextFrame().requestId, bytes, corruption)
                val response = command.await()
                assertEquals(ErrorCode.ARTIFACT_TRANSFER_FAILED, response.errorCode, corruption.name)
                assertEquals(detail, response.detail, corruption.name)
                assertEquals(null, command.artifact(), corruption.name)
            }
            // The session is still usable: verification failures are per request.
            val health = client.submit(Health)
            driver.respond(driver.nextFrame().requestId, Response.ok(Done, durationMs = 1))
            assertTrue(health.await().ok)
        }

    @Test
    fun driverFailureAfterPartialBlobIsKept() =
        runBlocking {
            val command = client.submit(Screenshot)
            val requestId = driver.nextFrame().requestId
            val blobId = java.util.UUID.randomUUID()
            driver.write(
                Frame(
                    FrameType.BLOB_START,
                    requestId,
                    json.encodeToString(BlobStart(blobId.toString(), "image/png", 10, "00")).encodeToByteArray(),
                ),
            )
            driver.respond(requestId, Response.failure(ErrorCode.CANCELLED, durationMs = 3))
            assertEquals(ErrorCode.CANCELLED, command.await().errorCode)
        }

    @Test
    fun cancelAfterMutationYieldsDriverDefinitiveResult() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            driver.nextFrame()
            assertTrue(tap.cancel())
            assertEquals(FrameType.CANCEL, driver.nextFrame().type)

            driver.respond(tap.requestId, Response.ok(Done, durationMs = 12))

            assertTrue(tap.await().ok)
        }

    @Test
    fun pingRoundTripsOnConnectionLevelFrames() =
        runBlocking {
            val latency = async(Dispatchers.IO) { client.ping() }
            val ping = driver.nextFrame()
            assertEquals(FrameType.PING, ping.type)
            assertEquals(0L, ping.requestId)
            driver.pong()
            assertTrue(latency.await() >= 0)
        }

    @Test
    fun transportLossClassifiesInFlightCommandsByMutation() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            val exists = client.submit(Exists(selector))
            driver.nextFrame()
            driver.nextFrame()

            driver.dropConnection()

            val tapFailure = assertFailsWith<CommandTransportException> { tap.await() }
            assertEquals(ErrorCode.INDETERMINATE, tapFailure.code)
            assertEquals(TransmissionState.WRITTEN, tapFailure.transmissionState)
            val existsFailure = assertFailsWith<CommandTransportException> { exists.await() }
            assertEquals(ErrorCode.TRANSPORT_LOST, existsFailure.code)

            val poisoned = assertFailsWith<CommandTransportException> { client.execute(Health) }
            assertEquals(TransmissionState.NOT_WRITTEN, poisoned.transmissionState)
            assertEquals(-1, poisoned.requestId)
        }

    @Test
    fun unknownResponseIdPoisonsTheConnection() =
        runBlocking {
            val exists = client.submit(Exists(selector))
            driver.nextFrame()

            driver.respond(99, Response.ok(Done, durationMs = 0))

            val failure = assertFailsWith<CommandTransportException> { exists.await() }
            assertEquals(ErrorCode.TRANSPORT_LOST, failure.code)
        }

    @Test
    fun requestIdsAreStrictlyIncreasingAcrossConcurrentSubmitters() =
        runBlocking {
            val commands =
                coroutineScope {
                    (1..8).map { async { client.submit(Health) } }.awaitAll()
                }
            val ids = List(8) { driver.nextFrame().requestId }
            assertEquals(ids.sorted(), ids)
            assertEquals((1L..8L).toList(), ids)
            commands.forEach { driver.respond(it.requestId, Response.ok(Done, durationMs = 0)) }
            commands.forEach { assertTrue(it.await().ok) }
        }

    @Test
    fun validationRequestsCarryExplicitIdsAndRawPayloads() =
        runBlocking {
            val response =
                async(Dispatchers.IO) {
                    client.executeRawValidationRequest(requestId = 3, payload = """{"command":{"op":"teleport"}}""")
                }
            val frame = driver.nextFrame()
            assertEquals(3L, frame.requestId)
            assertEquals("""{"command":{"op":"teleport"}}""", frame.payload.decodeToString())
            driver.respond(3, Response.failure(ErrorCode.UNSUPPORTED, durationMs = 0))
            assertEquals(ErrorCode.UNSUPPORTED, response.await().errorCode)

            // Automatic allocation continues above the explicit ID the driver has already consumed.
            val next = client.submit(Health)
            assertEquals(4L, driver.nextFrame().requestId)
            driver.respond(4, Response.ok(Done, durationMs = 0))
            assertTrue(next.await().ok)
        }

    @Test
    fun awaitOrThrowRaisesTypedRemoteException() =
        runBlocking {
            val tap = client.submit(Tap(selector), timeoutMs = 7_000)
            driver.nextFrame()
            driver.respond(
                tap.requestId,
                Response.failure(ErrorCode.AMBIGUOUS, durationMs = 9, message = "3 matches"),
            )

            val failure = assertFailsWith<RemoteCommandException> { tap.awaitOrThrow() }
            assertEquals(ErrorCode.AMBIGUOUS, failure.code)
            assertEquals("tap", failure.operation)
            assertEquals(tap.requestId, failure.requestId)
            assertEquals(7L, failure.sessionGeneration)
            assertEquals("text=\"hello\"", failure.selector)
            assertEquals(7_000L, failure.timeoutMs)
            assertEquals("3 matches", failure.remoteMessage)
            assertEquals(9L, failure.durationMs)
            assertFalse(failure.retryable)
            assertFalse(failure.mayHaveMutated)
            assertTrue("AMBIGUOUS from tap text=\"hello\"" in failure.message.orEmpty(), failure.message)
        }

    @Test
    fun executeOrThrowCarriesDetailAndRetryability() =
        runBlocking {
            val pending =
                async(Dispatchers.IO) {
                    runCatching { client.execute(ScrollUntil(selector, container = selector)) }
                }
            val frame = driver.nextFrame()
            driver.respond(frame.requestId, Response.failure(ErrorCode.NOT_FOUND, detail = "END_REACHED", durationMs = 1))

            val failure = pending.await().exceptionOrNull() as RemoteCommandException
            assertEquals(ErrorCode.NOT_FOUND, failure.code)
            assertEquals("END_REACHED", failure.detail)
            assertTrue(failure.retryable)
            assertTrue("NOT_FOUND/END_REACHED" in failure.message.orEmpty())
        }

    @Test
    fun transportExceptionsCarrySelectorAndTimeout() =
        runBlocking {
            val tap = client.submit(Tap(selector), timeoutMs = 1_234)
            driver.nextFrame()
            driver.dropConnection()

            val failure = assertFailsWith<CommandTransportException> { tap.await() }
            assertEquals("text=\"hello\"", failure.selector)
            assertEquals(1_234L, failure.timeoutMs)
            assertTrue(failure.mayHaveMutated)
            assertTrue(failure is CommandException)
        }

    @Test
    fun closeSendsCloseFrame() =
        runBlocking {
            client.close()
            assertEquals(FrameType.CLOSE, driver.nextFrame().type)
        }
}
