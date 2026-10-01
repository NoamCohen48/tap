package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.github.noamcohen48.tap.protocol.MAX_BLOB_CHUNK_BYTES
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.wire.v1.ProtocolVersion
import io.github.noamcohen48.tap.wire.v1.BlobStart
import io.github.noamcohen48.tap.wire.v1.Request
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverClientTest {
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    private val driver = FakeDriverServer("session-1", 7, secret)
    private val hooks = TestTransportHooks()
    private lateinit var client: DriverClient
    private val selector = Selectors.text("hello")

    @BeforeTest
    fun setUp() =
        runBlocking {
            client = DriverClient.connect(driver.port, "session-1", 7, secret, heartbeatIntervalMs = 0, hooks = hooks)
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
        assertEquals(5, client.negotiatedVersion.major)
        assertTrue("synchronization.v1" in client.enabledCapabilities)
    }

    @Test
    fun responsesAreDemultiplexedByRequestIdRegardlessOfOrder() =
        runBlocking {
            val first = client.submit(Commands.exists(selector))
            val second = client.submit(Commands.exists(selector))
            val frames = listOf(driver.nextFrame(), driver.nextFrame())
            assertEquals(listOf(1L, 2L), frames.map { it.requestId })
            assertTrue(frames.all { it.type == FrameType.REQUEST })

            driver.respond(2, Responses.of(CommandResult.newBuilder().setBool(false), 1))
            driver.respond(1, Responses.of(CommandResult.newBuilder().setBool(true), 2))

            assertEquals(true, first.await().result.bool)
            assertEquals(false, second.await().result.bool)
            assertEquals(TransmissionState.TERMINAL_RESPONSE, first.transmissionState)
        }

    @Test
    fun cancelWritesCancelFrameAndReturnsDriverTerminalResponse() =
        runBlocking {
            val wait = client.submit(Commands.waitVisible(selector), timeoutMs = 30_000)
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)

            assertTrue(wait.cancel())
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            assertEquals(wait.requestId, cancel.requestId)

            driver.respond(wait.requestId, Responses.failure(ErrorCode.ERR_CANCELLED, durationMs = 40))
            assertEquals(ErrorCode.ERR_CANCELLED, wait.await().errorCode)
            assertFalse(wait.cancel(), "terminal command must not be cancellable")
        }

    @Test
    fun cancellationAlwaysWinsOverATerminalInstalledAtTheRacePoint() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector))
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)
            // Installed synchronously inside await()'s cancellation path, after CANCEL is queued
            // and before the original cancellation is rethrown: the exact race the shortcut lost.
            hooks.onAfterAwaitCancel = {
                tap.complete(Responses.done(12))
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
                val health = client.submit(Requests.health())
                var frame = driver.nextFrame()
                if (frame.type == FrameType.CANCEL) {
                    assertEquals(tap.requestId, frame.requestId)
                    frame = driver.nextFrame()
                }
                assertEquals(FrameType.REQUEST, frame.type)
                driver.respond(frame.requestId, Responses.done(1))
                assertTrue(health.await().ok)
                assertFalse(client.isPoisoned)
            } finally {
                hooks.onAfterAwaitCancel = null
            }
        }

    @Test
    fun awaitingCoroutineCancelPropagatesPromptlyWhileTerminalIsStillRecorded() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector))
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
            driver.respond(tap.requestId, Responses.done(12))
            withTimeout(2_000) { while (tap.responseOrNull == null) delay(10) }
            assertTrue(tap.responseOrNull?.ok == true)
            assertEquals(TransmissionState.TERMINAL_RESPONSE, tap.transmissionState)

            val health = client.submit(Requests.health())
            driver.respond(driver.nextFrame().requestId, Responses.done(1))
            assertTrue(health.await().ok)
        }

    @Test
    fun enclosingDeadlineCancelsPromptlyWithoutPoisoning() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector), timeoutMs = 30_000)
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
            driver.respond(tap.requestId, Responses.done(12))
            withTimeout(2_000) { while (tap.responseOrNull == null) delay(10) }
            assertEquals(TransmissionState.TERMINAL_RESPONSE, tap.transmissionState)

            val health = client.submit(Requests.health())
            driver.respond(driver.nextFrame().requestId, Responses.done(1))
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
                val tap = quick.submit(Commands.tap(selector), timeoutMs = 0)
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
                val rejected = assertFailsWith<CommandTransportException> { quick.submit(Requests.health()) }
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
                val exists = quick.submit(Commands.exists(selector), timeoutMs = 0)
                assertEquals(FrameType.REQUEST, timeoutDriver.nextFrame().type)
                val failure = assertFailsWith<CommandTransportException> { exists.await() }
                assertEquals(ErrorCode.ERR_TRANSPORT_LOST, failure.code)
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
            hooks.onAfterTerminalResponse = { terminalInstalled.countDown() }
            hooks.onBeforeMarkWritten = {
                reachedTransition.countDown()
                check(releaseTransition.await(5, TimeUnit.SECONDS))
            }
            try {
                val submitted = async(Dispatchers.IO) { client.submit(Commands.exists(selector)) }
                val request = driver.nextFrame()
                assertTrue(reachedTransition.await(2, TimeUnit.SECONDS))
                driver.respond(request.requestId, Responses.of(CommandResult.newBuilder().setBool(true), 1))
                assertTrue(terminalInstalled.await(2, TimeUnit.SECONDS))
                releaseTransition.countDown()
                val command = submitted.await()
                assertEquals(TransmissionState.TERMINAL_RESPONSE, command.transmissionState)
                assertEquals(true, command.await().result.bool)
            } finally {
                releaseTransition.countDown()
                hooks.onBeforeMarkWritten = null
                hooks.onAfterTerminalResponse = null
            }
        }

    @Test
    fun awaitCancellationIsPromptWhileCancelWaitsForTransportMutex() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector))
            assertEquals(FrameType.REQUEST, driver.nextFrame().type)
            // The queued CANCEL owns the transport lock but is parked before its physical write,
            // so the cancelled caller must not wait for it.
            val cancelParked = CompletableDeferred<Unit>()
            val releaseCancel = CompletableDeferred<Unit>()
            hooks.onBeforePhysicalWrite = {
                cancelParked.complete(Unit)
                releaseCancel.await()
            }
            try {
                val awaiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { tap.await() }
                awaiting.cancel()
                withTimeout(500) { assertFailsWith<CancellationException> { awaiting.await() } }
                withTimeout(2_000) { cancelParked.await() }
                assertFalse(client.isPoisoned)
            } finally {
                hooks.onBeforePhysicalWrite = null
                releaseCancel.complete(Unit)
            }
            val cancel = driver.nextFrame()
            assertEquals(FrameType.CANCEL, cancel.type)
            assertEquals(tap.requestId, cancel.requestId)
            driver.respond(tap.requestId, Responses.done(1))
            withTimeout(2_000) { while (tap.responseOrNull == null) delay(10) }
        }

    @Test
    fun cancelledWriteBeforePhysicalStartLeavesTransportIntact() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            hooks.onBeforePhysicalWrite = { gate.await() }
            try {
                // Undispatched: submit runs to the writer wait, still parked before the socket.
                val submitted =
                    async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        client.submit(Commands.tap(selector))
                    }
                submitted.cancel()
                assertFailsWith<CancellationException> { submitted.await() }
                // No CANCEL: nothing was ever written, so there is nothing to cancel.
                // The transport is intact: the next command flows normally once the gate opens.
                gate.complete(Unit)
                val health = client.submit(Requests.health())
                val frame = driver.nextFrame()
                assertEquals(FrameType.REQUEST, frame.type)
                driver.respond(frame.requestId, Responses.done(1))
                assertTrue(health.await().ok)
            } finally {
                hooks.onBeforePhysicalWrite = null
            }
        }

    @Test
    fun cancelledWriteAfterPhysicalStartIsTransportLoss() =
        runBlocking {
            val blockedSecret = ByteArray(32).also(SecureRandom()::nextBytes)
            val blockedDriver = FakeDriverServer("session-blocked", 1, blockedSecret)
            val blockedHooks = TestTransportHooks()
            val blocked =
                DriverClient.connect(
                    blockedDriver.port,
                    "session-blocked",
                    1,
                    blockedSecret,
                    heartbeatIntervalMs = 0,
                    hooks = blockedHooks,
                )
            try {
                val enteredSink = CompletableDeferred<Unit>()
                val releaseSink = CompletableDeferred<Unit>()
                var writerFinished = false
                blockedHooks.onPhysicalWrite = {
                    enteredSink.complete(Unit)
                    releaseSink.await()
                }
                blockedHooks.onAfterPhysicalWrite = { writerFinished = true }
                var outcome: Throwable? = null
                val submitted =
                    async(Dispatchers.IO) {
                        // Record, do not rethrow: a rethrown failure would fail the test's parent.
                        runCatching { blocked.submit(Commands.tap(selector)) }.exceptionOrNull()?.let { outcome = it }
                    }
                // The writer is parked inside the physical write, past the started mark.
                withTimeout(2_000) { enteredSink.await() }
                submitted.cancel()
                submitted.join()
                val failure = outcome as? CommandTransportException
                assertTrue(failure != null, "write cancelled after start threw $outcome")
                assertEquals(ErrorCode.ERR_INDETERMINATE, failure.code)
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
            val stuckHooks = TestTransportHooks()
            val stuck =
                DriverClient.connect(
                    stuckDriver.port,
                    "session-stuck",
                    1,
                    stuckSecret,
                    // The overall deadline bounds the 10 s frame-write timeout down to ~300 ms.
                    overallDeadlineNanos = System.nanoTime() + 300_000_000L,
                    heartbeatIntervalMs = 0,
                    hooks = stuckHooks,
                )
            try {
                var writerFinished = false
                stuckHooks.onPhysicalWrite = { awaitCancellation() }
                stuckHooks.onAfterPhysicalWrite = { writerFinished = true }
                val failure =
                    assertFailsWith<CommandTransportException> {
                        stuck.submit(Commands.exists(selector), timeoutMs = 0)
                    }
                assertEquals(ErrorCode.ERR_TRANSPORT_LOST, failure.code)
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
                    val health = beating.submit(Requests.health())
                    assertEquals(FrameType.REQUEST, fake.nextFrame().type)
                    fake.respond(health.requestId, Responses.done(1))
                    assertTrue(health.await().ok)
                    assertEquals(FrameType.PING, fake.nextFrame(1_000).type)
                    // Not answering this one poisons the client.
                    delay(300)
                    val failed =
                        assertFailsWith<CommandTransportException> {
                            beating.execute(Requests.health())
                        }
                    assertEquals(ErrorCode.ERR_TRANSPORT_LOST, failed.code)
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
            assertEquals(Request.BodyCase.SCREENSHOT, Request.parseFrom(request.payload).bodyCase)
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
                val command = client.submit(Requests.screenshot())
                driver.sendArtifact(driver.nextFrame().requestId, bytes, corruption)
                val response = command.await()
                assertEquals(ErrorCode.ERR_ARTIFACT_TRANSFER_FAILED, response.errorCode, corruption.name)
                assertEquals(detail, response.detail, corruption.name)
                assertEquals(null, command.artifact(), corruption.name)
            }
            // The session is still usable: verification failures are per request.
            val health = client.submit(Requests.health())
            driver.respond(driver.nextFrame().requestId, Responses.done(1))
            assertTrue(health.await().ok)
        }

    @Test
    fun driverFailureAfterPartialBlobIsKept() =
        runBlocking {
            val command = client.submit(Requests.screenshot())
            val requestId = driver.nextFrame().requestId
            val blobId = java.util.UUID.randomUUID()
            driver.write(
                Frame(
                    FrameType.BLOB_START,
                    requestId,
                    BlobStart
                        .newBuilder()
                        .setBlobId(blobId.toString())
                        .setMediaType("image/png")
                        .setTotalLength(10)
                        .setSha256("00")
                        .build()
                        .toByteArray(),
                ),
            )
            driver.respond(requestId, Responses.failure(ErrorCode.ERR_CANCELLED, durationMs = 3))
            assertEquals(ErrorCode.ERR_CANCELLED, command.await().errorCode)
        }

    @Test
    fun cancelAfterMutationYieldsDriverDefinitiveResult() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector))
            driver.nextFrame()
            assertTrue(tap.cancel())
            assertEquals(FrameType.CANCEL, driver.nextFrame().type)

            driver.respond(tap.requestId, Responses.done(12))

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
            val tap = client.submit(Commands.tap(selector))
            val exists = client.submit(Commands.exists(selector))
            driver.nextFrame()
            driver.nextFrame()

            driver.dropConnection()

            val tapFailure = assertFailsWith<CommandTransportException> { tap.await() }
            assertEquals(ErrorCode.ERR_INDETERMINATE, tapFailure.code)
            assertEquals(TransmissionState.WRITTEN, tapFailure.transmissionState)
            val existsFailure = assertFailsWith<CommandTransportException> { exists.await() }
            assertEquals(ErrorCode.ERR_TRANSPORT_LOST, existsFailure.code)

            val poisoned = assertFailsWith<CommandTransportException> { client.execute(Requests.health()) }
            assertEquals(TransmissionState.NOT_WRITTEN, poisoned.transmissionState)
            assertEquals(-1, poisoned.requestId)
        }

    @Test
    fun unknownResponseIdPoisonsTheConnection() =
        runBlocking {
            val exists = client.submit(Commands.exists(selector))
            driver.nextFrame()

            driver.respond(99, Responses.done(0))

            val failure = assertFailsWith<CommandTransportException> { exists.await() }
            assertEquals(ErrorCode.ERR_TRANSPORT_LOST, failure.code)
        }

    @Test
    fun malformedCommandsAreRejectedBeforeAnyRequestId() =
        runBlocking {
            // A fresh client: nothing allocated yet.
            val idBefore = 1L
            val noSelector = assertFailsWith<InvalidCommandException> { client.submit(Commands.tap(Selector.getDefaultInstance())) }
            assertEquals(ErrorCode.ERR_INVALID_SELECTOR, noSelector.code)
            val noOperation = assertFailsWith<InvalidCommandException> { client.submit(Request.getDefaultInstance()) }
            assertEquals(ErrorCode.ERR_UNSUPPORTED, noOperation.code)

            // The next valid command takes that ID and carries the session envelope, its optional
            // fields left absent for the driver to default.
            val scroll = client.submit(Commands.scroll(selector, Direction.DIR_DOWN), timeoutMs = 1_500)
            val (frame, request) = driver.nextRequest()
            assertEquals(idBefore, frame.requestId)
            assertEquals("session-1", request.sessionId)
            assertEquals(7L, request.generation)
            assertEquals(1_500L, request.timeoutMs)
            assertFalse(request.command.scroll.hasDistancePercent())
            driver.respond(frame.requestId, Responses.done(1))
            assertTrue(scroll.await().ok)
        }

    @Test
    fun requestIdsAreStrictlyIncreasingAcrossConcurrentSubmitters() =
        runBlocking {
            val commands =
                coroutineScope {
                    (1..8).map { async { client.submit(Requests.health()) } }.awaitAll()
                }
            val ids = List(8) { driver.nextFrame().requestId }
            assertEquals(ids.sorted(), ids)
            assertEquals((1L..8L).toList(), ids)
            commands.forEach { driver.respond(it.requestId, Responses.done(0)) }
            commands.forEach { assertTrue(it.await().ok) }
        }

    @Test
    fun sessionGateRejectionEmitsNoFrameAndConsumesNoRequestId() =
        runBlocking {
            var rejecting = true
            client.bindSessionGate { check(!rejecting) { "session is closing" } }
            assertFailsWith<IllegalStateException> { client.submit(Requests.health()) }
            rejecting = false
            val health = client.submit(Requests.health())
            val frame = driver.nextFrame()
            assertEquals(1L, frame.requestId, "the rejected admission must not consume an ID")
            driver.respond(frame.requestId, Responses.done(0))
            assertTrue(health.await().ok)
        }

    @Test
    @OptIn(ValidationApi::class)
    fun validationRequestsCarryExplicitIdsAndRawPayloads() =
        runBlocking {
            // A request with only its envelope: no operation, which the driver answers UNSUPPORTED.
            val rawPayload = Request.newBuilder().setSessionId("session-1").setGeneration(7).setTimeoutMs(5_000).build().toByteArray()
            val response =
                async(Dispatchers.IO) {
                    client.validationTransport().executeRaw(requestId = 3, payload = rawPayload)
                }
            val frame = driver.nextFrame()
            assertEquals(3L, frame.requestId)
            assertContentEquals(rawPayload, frame.payload)
            driver.respond(3, Responses.failure(ErrorCode.ERR_UNSUPPORTED, durationMs = 0))
            assertEquals(ErrorCode.ERR_UNSUPPORTED, response.await().errorCode)

            // Automatic allocation continues above the explicit ID the driver has already consumed.
            val next = client.submit(Requests.health())
            assertEquals(4L, driver.nextFrame().requestId)
            driver.respond(4, Responses.done(0))
            assertTrue(next.await().ok)
        }

    @Test
    fun awaitOrThrowRaisesTypedRemoteException() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector), timeoutMs = 7_000)
            driver.nextFrame()
            driver.respond(
                tap.requestId,
                Responses.failure(ErrorCode.ERR_AMBIGUOUS, durationMs = 9, message = "3 matches"),
            )

            val failure = assertFailsWith<RemoteCommandException> { tap.awaitOrThrow() }
            assertEquals(ErrorCode.ERR_AMBIGUOUS, failure.code)
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
                    runCatching { client.execute(Commands.scroll(selector, Direction.DIR_DOWN)) }
                }
            val frame = driver.nextFrame()
            driver.respond(frame.requestId, Responses.failure(ErrorCode.ERR_NOT_FOUND, detail = "SOME_DETAIL", durationMs = 1))

            val failure = pending.await().exceptionOrNull() as RemoteCommandException
            assertEquals(ErrorCode.ERR_NOT_FOUND, failure.code)
            assertEquals("SOME_DETAIL", failure.detail)
            assertTrue(failure.retryable)
            assertTrue("NOT_FOUND/SOME_DETAIL" in failure.message.orEmpty())
        }

    @Test
    fun transportExceptionsCarrySelectorAndTimeout() =
        runBlocking {
            val tap = client.submit(Commands.tap(selector), timeoutMs = 1_234)
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
            val tap = client.submit(Commands.tap(selector))
            driver.nextFrame()
            driver.write(Frame(FrameType.CLOSE, 0, "DUPLICATE_OR_STALE: request ID 1".encodeToByteArray()))

            val failure = assertFailsWith<CommandTransportException> { tap.await() }
            assertEquals(ErrorCode.ERR_INDETERMINATE, failure.code)
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
                val exists = async(Dispatchers.IO) { shortLived.send(Commands.exists(selector), timeoutMs = 2_000) }
                val frame = shortDriver.nextFrame()
                shortDriver.respond(frame.requestId, Responses.of(CommandResult.newBuilder().setBool(true), 1))
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
    fun aDriverWithNoCommonProtocolVersionSaysSo() =
        runBlocking {
            val future = ProtocolVersion.newBuilder().setMajor(99).setMinor(0).build()
            val newer = FakeDriverServer("session-5", 7, secret, supportedVersions = listOf(future))
            try {
                val failure =
                    assertFailsWith<DriverHandshakeException> {
                        connectWithRetry(
                            newer.port,
                            "session-5",
                            7,
                            secret,
                            overallDeadlineNanos = System.nanoTime() + 5_000_000_000L,
                            heartbeatIntervalMs = 0,
                        )
                    }
                assertTrue(failure.message.orEmpty().contains("UNSUPPORTED"), failure.message)
                assertTrue(failure.message.orEmpty().contains("no common protocol version"), failure.message)
            } finally {
                newer.close()
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
