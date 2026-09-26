package com.company.tap.host

import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.BoolResult
import com.company.tap.protocol.DRIVER_APK_BUILD_ID
import com.company.tap.protocol.Done
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Exists
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Health
import com.company.tap.protocol.MAX_BLOB_CHUNK_BYTES
import com.company.tap.protocol.ProtocolJson
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverClientTest {
    private val json = ProtocolJson.codec
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
    fun cancellationAlwaysWinsOverATerminalInstalledAtTheRacePoint() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)
            // Installed synchronously inside await()'s cancellation path, after CANCEL is queued
            // and before the original cancellation is rethrown: the exact race the shortcut lost.
            client.afterAwaitCancel = {
                tap.complete(Response.ok(Done, durationMs = 12))
            }
            try {
                val original = CancellationException("original cancellation")
                val awaiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { tap.await() }
                awaiting.cancel(original)
                val thrown = assertFailsWith<CancellationException> { awaiting.await() }
                assertEquals("original cancellation", thrown.message)
                assertTrue(tap.responseOrNull?.ok == true)
                assertEquals(TransmissionState.TERMINAL_RESPONSE, tap.transmissionState)
                assertFalse(tap.cancel(), "terminal command must not be cancellable")

                // The CANCEL launch may or may not have written before the terminal install won
                // the race; either way the transport stays ordered and reusable. A stale CANCEL
                // is consumed here so the health REQUEST below is read deterministically.
                val health = client.submit(Health)
                var frame = driver.nextFrame()
                if (frame.type == FrameType.CANCEL) {
                    assertEquals(tap.requestId, frame.requestId)
                    frame = driver.nextFrame()
                }
                assertEquals(FrameType.REQUEST, frame.type)
                driver.respond(frame.requestId, Response.ok(Done, durationMs = 1))
                assertTrue(health.await().ok)
                assertFalse(client.isPoisoned)
            } finally {
                client.afterAwaitCancel = null
            }
        }

    @Test
    fun awaitingCoroutineCancelPropagatesPromptlyWhileTerminalIsStillRecorded() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)

            // Undispatched: the child is guaranteed suspended inside await() before cancel() runs.
            val awaiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { tap.await() }
            awaiting.cancel()
            // Prompt: the cancelled caller is not parked behind the driver's terminal response.
            assertFailsWith<CancellationException> { awaiting.await() }
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            assertEquals(tap.requestId, cancel.requestId)

            // The abandoned entry stays registered: the reader still consumes the terminal frame.
            driver.respond(tap.requestId, Response.ok(Done, durationMs = 12))
            withTimeout(2_000) { while (tap.responseOrNull == null) delay(10) }
            assertTrue(tap.responseOrNull?.ok == true)
            assertEquals(TransmissionState.TERMINAL_RESPONSE, tap.transmissionState)

            val health = client.submit(Health)
            driver.respond(driver.nextFrame().requestId, Response.ok(Done, durationMs = 1))
            assertTrue(health.await().ok)
        }

    @Test
    fun enclosingDeadlineCancelsPromptlyWithoutPoisoning() =
        runBlocking {
            val tap = client.submit(Tap(selector), timeoutMs = 30_000)
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)

            // The private budget is ~35 s; an enclosing 200 ms deadline must win immediately.
            val started = System.nanoTime()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) { tap.await() }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMs < 10_000, "caller cancellation took ${elapsedMs}ms")
            assertFalse(client.isPoisoned, "an enclosing deadline must not poison the transport")

            // Cooperative CANCEL went out and the terminal response is still recorded.
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            driver.respond(tap.requestId, Response.ok(Done, durationMs = 12))
            withTimeout(2_000) { while (tap.responseOrNull == null) delay(10) }
            assertEquals(TransmissionState.TERMINAL_RESPONSE, tap.transmissionState)

            val health = client.submit(Health)
            driver.respond(driver.nextFrame().requestId, Response.ok(Done, durationMs = 1))
            assertTrue(health.await().ok)
        }

    @Test
    fun abandonedCommandIsPoisonedWhenItsPrivateBudgetExpires() =
        runBlocking {
            val abandonedSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val abandonedDriver = FakeDriverServer("session-abandoned", 1, abandonedSecret)
            val quick =
                DriverClient.connect(
                    abandonedDriver.port,
                    "session-abandoned",
                    1,
                    abandonedSecret,
                    heartbeatIntervalMs = 0,
                    responseBudgetPaddingMs = 200,
                )
            try {
                val tap = quick.submit(Tap(selector), timeoutMs = 0)
                assertEquals(FrameType.REQUEST, abandonedDriver.nextFrame().type)

                // Already-cancelled waiter: runs only to its first suspension, then propagates.
                val awaiting = launch(start = CoroutineStart.UNDISPATCHED) { tap.await() }
                awaiting.cancel()
                awaiting.join()
                assertTrue(awaiting.isCancelled)
                assertEquals(FrameType.CANCEL, abandonedDriver.nextFrame().type)

                // The driver never answers: the client-owned watcher enforces the 200 ms budget.
                withTimeout(5_000) { while (!quick.isPoisoned) delay(10) }
                assertEquals(TransmissionState.WRITTEN, tap.transmissionState)
                val rejected = assertFailsWith<CommandTransportException> { quick.submit(Health) }
                assertEquals(TransmissionState.NOT_WRITTEN, rejected.transmissionState)
            } finally {
                quick.close()
                abandonedDriver.close()
            }
        }

    @Test
    fun genuinePrivateBudgetTimeoutStillPoisonsTheClient() =
        runBlocking {
            val timeoutSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val timeoutDriver = FakeDriverServer("session-timeout", 1, timeoutSecret)
            val quick =
                DriverClient.connect(
                    timeoutDriver.port,
                    "session-timeout",
                    1,
                    timeoutSecret,
                    heartbeatIntervalMs = 0,
                    responseBudgetPaddingMs = 200,
                )
            try {
                // No response is ever sent; the 200 ms private budget must expire on its own.
                val exists = quick.submit(Exists(selector), timeoutMs = 0)
                assertEquals(FrameType.REQUEST, timeoutDriver.nextFrame().type)
                val failure = assertFailsWith<CommandTransportException> { exists.await() }
                assertEquals(ErrorCode.TRANSPORT_LOST, failure.code)
                assertTrue(quick.isPoisoned)
            } finally {
                quick.close()
                timeoutDriver.close()
            }
        }

    @Test
    fun writtenNeverRegressesTerminalResponseWhenReaderCompletesBeforeWriterCas() =
        runBlocking {
            val reachedTransition = CountDownLatch(1)
            val terminalInstalled = CountDownLatch(1)
            val releaseTransition = CountDownLatch(1)
            client.afterTerminalResponse = { terminalInstalled.countDown() }
            client.beforeMarkWritten = {
                reachedTransition.countDown()
                check(releaseTransition.await(5, TimeUnit.SECONDS))
            }
            try {
                val submitted = async(Dispatchers.IO) { client.submit(Exists(selector)) }
                val request = driver.nextFrame()
                assertTrue(reachedTransition.await(2, TimeUnit.SECONDS))
                driver.respond(request.requestId, Response.ok(BoolResult(true), durationMs = 1))
                assertTrue(terminalInstalled.await(2, TimeUnit.SECONDS))
                releaseTransition.countDown()
                val command = submitted.await()
                assertEquals(TransmissionState.TERMINAL_RESPONSE, command.transmissionState)
                assertEquals(BoolResult(true), command.await().result)
            } finally {
                releaseTransition.countDown()
                client.beforeMarkWritten = null
                client.afterTerminalResponse = null
            }
        }

    @Test
    fun awaitCancellationIsPromptWhileCancelWaitsForTransportMutex() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)
            val mutexHeld = CompletableDeferred<Unit>()
            val releaseMutex = CompletableDeferred<Unit>()
            val holder =
                launch(Dispatchers.IO) {
                    client.withTransportLock {
                        mutexHeld.complete(Unit)
                        releaseMutex.await()
                    }
                }
            mutexHeld.await()

            val awaiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { tap.await() }
            awaiting.cancel()
            withTimeout(500) { assertFailsWith<CancellationException> { awaiting.await() } }
            assertFalse(client.isPoisoned)

            releaseMutex.complete(Unit)
            holder.join()
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            assertEquals(tap.requestId, cancel.requestId)
            driver.respond(tap.requestId, Response.ok(Done, durationMs = 1))
            withTimeout(2_000) { while (tap.responseOrNull == null) delay(10) }
        }

    @Test
    fun cancelledWriteBeforePhysicalStartLeavesTransportIntact() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            client.beforePhysicalWrite = { gate.await() }
            try {
                // Undispatched: submit runs to the writer wait, still parked before the socket.
                val submitted =
                    async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        client.submit(Tap(selector))
                    }
                submitted.cancel()
                assertFailsWith<CancellationException> { submitted.await() }
                // No CANCEL: nothing was ever written, so there is nothing to cancel.
                // The transport is intact: the next command flows normally once the gate opens.
                gate.complete(Unit)
                val health = client.submit(Health)
                val frame = driver.nextFrame()
                assertEquals(FrameType.REQUEST, frame.type)
                driver.respond(frame.requestId, Response.ok(Done, durationMs = 1))
                assertTrue(health.await().ok)
            } finally {
                client.beforePhysicalWrite = null
            }
        }

    @Test
    fun cancelledWriteAfterPhysicalStartIsTransportLoss() =
        runBlocking {
            val blockedSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val blockedDriver = FakeDriverServer("session-blocked", 1, blockedSecret)
            val blocked =
                DriverClient.connect(
                    blockedDriver.port,
                    "session-blocked",
                    1,
                    blockedSecret,
                    heartbeatIntervalMs = 0,
                )
            try {
                val enteredSink = CompletableDeferred<Unit>()
                val releaseSink = CompletableDeferred<Unit>()
                var writerFinished = false
                blocked.frameSink =
                    FrameSink {
                        enteredSink.complete(Unit)
                        releaseSink.await()
                    }
                blocked.afterPhysicalWrite = { writerFinished = true }
                var outcome: Throwable? = null
                val submitted =
                    async(Dispatchers.IO) {
                        // Record, do not rethrow: a rethrown failure would fail the test's parent.
                        runCatching { blocked.submit(Tap(selector)) }.exceptionOrNull()?.let { outcome = it }
                    }
                // The writer is parked inside the physical write, past the started mark.
                withTimeout(2_000) { enteredSink.await() }
                submitted.cancel()
                submitted.join()
                val failure = outcome as? CommandTransportException
                assertTrue(failure != null, "write cancelled after start threw $outcome")
                assertEquals(ErrorCode.INDETERMINATE, failure.code)
                assertTrue(blocked.isPoisoned)
                releaseSink.complete(Unit)
                // The writer is reaped boundedly: close() must not deadlock behind it.
                withTimeout(5_000) { while (!writerFinished) delay(10) }
                withTimeout(5_000) { blocked.close() }
            } finally {
                blockedDriver.close()
            }
        }

    @Test
    fun writeTimeoutHasBoundedCleanup() =
        runBlocking {
            val stuckSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val stuckDriver = FakeDriverServer("session-stuck", 1, stuckSecret)
            val stuck =
                DriverClient.connect(
                    stuckDriver.port,
                    "session-stuck",
                    1,
                    stuckSecret,
                    // The overall deadline bounds the 10 s frame-write timeout down to ~300 ms.
                    overallDeadlineNanos = System.nanoTime() + 300_000_000L,
                    heartbeatIntervalMs = 0,
                )
            try {
                var writerFinished = false
                stuck.frameSink = FrameSink { awaitCancellation() }
                stuck.afterPhysicalWrite = { writerFinished = true }
                val failure =
                    assertFailsWith<CommandTransportException> {
                        stuck.submit(Exists(selector), timeoutMs = 0)
                    }
                assertEquals(ErrorCode.TRANSPORT_LOST, failure.code)
                assertTrue(stuck.isPoisoned)
                withTimeout(5_000) { while (!writerFinished) delay(10) }
                withTimeout(5_000) { stuck.close() }
            } finally {
                stuckDriver.close()
            }
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
            assertTrue(CommandException::class.java.isAssignableFrom(failure.javaClass))
        }

    @Test
    fun driverCloseFailsInFlightWorkWithItsReason() =
        runBlocking {
            val tap = client.submit(Tap(selector))
            driver.nextFrame()
            driver.write(Frame(FrameType.CLOSE, 0, "DUPLICATE_OR_STALE: request ID 1".encodeToByteArray()))

            val failure = assertFailsWith<CommandTransportException> { tap.await() }
            assertEquals(ErrorCode.INDETERMINATE, failure.code)
            assertTrue("DUPLICATE_OR_STALE" in failure.cause?.message.orEmpty(), failure.cause?.message)
            assertTrue(client.isPoisoned)
        }

    @Test
    fun connectDeadlineBoundsOnlyTheHandshake() =
        runBlocking {
            val shortDriver = FakeDriverServer("session-2", 7, secret)
            val shortLived =
                DriverClient.connect(
                    shortDriver.port,
                    "session-2",
                    7,
                    secret,
                    overallDeadlineNanos = System.nanoTime() + 1_000_000_000L,
                    heartbeatIntervalMs = 0,
                )
            try {
                delay(1_200)
                val exists = async(Dispatchers.IO) { shortLived.send(Exists(selector), timeoutMs = 2_000) }
                val frame = shortDriver.nextFrame()
                shortDriver.respond(frame.requestId, Response.ok(BoolResult(true), durationMs = 1))
                assertTrue(exists.await().ok, "a command after the connect deadline must still run")
                assertFalse(shortLived.isPoisoned)
            } finally {
                shortLived.close()
                shortDriver.close()
            }
        }

    @Test
    fun connectWithRetryDoesNotRetryAnAuthenticationFailure() =
        runBlocking {
            val wrongSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val otherDriver = FakeDriverServer("session-3", 7, wrongSecret)
            try {
                val started = System.nanoTime()
                val failure =
                    assertFailsWith<DriverHandshakeException> {
                        connectWithRetry(
                            otherDriver.port,
                            "session-3",
                            7,
                            secret,
                            overallDeadlineNanos = System.nanoTime() + 1_500_000_000L,
                            heartbeatIntervalMs = 0,
                        )
                    }
                assertTrue(failure.message.orEmpty().startsWith("Driver handshake failed"), failure.message)
                // One attempt, bounded by the handshake deadline; FakeDriverServer accepts once.
                assertTrue((System.nanoTime() - started) / 1_000_000L < 5_000)
            } finally {
                otherDriver.close()
            }
        }

    @Test
    fun aDriverOfAnotherBuildFailsTheHandshake() =
        runBlocking {
            val stale = FakeDriverServer("session-4", 7, secret, driverTestApkBuildId = "0.0.9")
            try {
                val failure =
                    assertFailsWith<DriverBuildMismatchException> {
                        connectWithRetry(
                            stale.port,
                            "session-4",
                            7,
                            secret,
                            overallDeadlineNanos = System.nanoTime() + 5_000_000_000L,
                            serial = "emulator-5554",
                            heartbeatIntervalMs = 0,
                        )
                    }
                assertEquals(DRIVER_APK_BUILD_ID, failure.expected)
                assertEquals("0.0.9", failure.driverTestApkBuildId)
                assertTrue("reinstall" in failure.message.orEmpty(), failure.message)
                assertTrue(failure is DriverStartException)
            } finally {
                stale.close()
            }
        }

    @Test
    fun closeSendsCloseFrame() =
        runBlocking {
            client.close()
            assertEquals(FrameType.CLOSE, driver.nextFrame().type)
        }
}
