package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.withEnvelope
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.MAX_BLOB_CHUNK_BYTES
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.label
import io.github.noamcohen48.tap.protocol.mayHaveMutated
import io.github.noamcohen48.tap.protocol.message
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.wire.v1.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class CommandPipelineTest {
    private val now = AtomicLong(1_000)
    private val clock = Clock { now.get() }
    private val written = LinkedBlockingQueue<Outbound>()
    private val poisonReasons = LinkedBlockingQueue<String>()
    private val writeFailures = LinkedBlockingQueue<Throwable>()
    private val failWrites = AtomicBoolean(false)
    private val cancelOnFirstChunk = AtomicBoolean(false)
    private val pingOnFirstChunk = AtomicBoolean(false)
    private val listener = object : PipelineListener {
        override fun onPoisoned(reason: String) { poisonReasons.put(reason) }
        override fun onWriteFailed(error: Throwable) { writeFailures.put(error) }
    }
    private val pipeline: CommandPipeline = CommandPipeline(
        clock = clock,
        sink = { message ->
            if (failWrites.get()) throw IllegalStateException("transport down")
            written.put(message)
            if (message is Outbound.BlobChunkFrame && cancelOnFirstChunk.compareAndSet(true, false)) {
                pipeline.cancel(message.requestId)
            }
            if (message is Outbound.BlobChunkFrame && pingOnFirstChunk.compareAndSet(true, false)) pipeline.pong(77)
        },
        listener = listener,
        queueCapacity = 2,
        uninterruptibleGraceMs = 500,
        watchdogPollMs = 0,
    ).start()

    @AfterTest
    fun tearDown() {
        pipeline.awaitTermination(1_000)
    }

    @Test
    fun executesCommandsSeriallyAndWritesOneResponseEach() {
        val order = mutableListOf<Long>()
        val gate = CountDownLatch(1)
        pipeline.submit(1, 5_000) { ctx ->
            gate.await(1, TimeUnit.SECONDS)
            synchronized(order) { order += ctx.requestId }
            Responses.done()
        }
        awaitRunning(1)
        pipeline.submit(2, 5_000) { ctx ->
            synchronized(order) { order += ctx.requestId }
            boolResponse(true)
        }
        assertEquals(listOf(2L), pipeline.snapshot().queued)
        gate.countDown()

        assertEquals(1L, nextResponse().requestId)
        assertEquals(2L, nextResponse().requestId)
        assertEquals(listOf(1L, 2L), order)
        assertTrue(written.isEmpty())
    }

    @Test
    fun overloadIsAStructuredErrorWithoutRunning() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); Responses.done() }
        awaitRunning(1)
        assertEquals(CommandPipeline.Admission.QUEUED, pipeline.submit(2, 5_000) { ok() })
        assertEquals(CommandPipeline.Admission.QUEUED, pipeline.submit(3, 5_000) { ok() })
        val ran = AtomicBoolean(false)
        assertEquals(
            CommandPipeline.Admission.OVERLOADED,
            pipeline.submit(4, 5_000) { ran.set(true); ok() },
        )

        val overloaded = nextResponse()
        assertEquals(4L, overloaded.requestId)
        assertEquals(ErrorCode.ERR_OVERLOADED, overloaded.response.errorCode)
        release.countDown()
        assertEquals(listOf(1L, 2L, 3L), List(3) { nextResponse().requestId })
        assertFalse(ran.get())
    }

    @Test
    fun cancelRemovesQueuedCommandWithoutRunningIt() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)
        val ran = AtomicBoolean(false)
        pipeline.submit(2, 5_000) { ran.set(true); ok() }

        pipeline.cancel(2)

        val cancelled = nextResponse()
        assertEquals(2L, cancelled.requestId)
        assertEquals(ErrorCode.ERR_CANCELLED, cancelled.response.errorCode)
        assertEquals(emptyList(), pipeline.snapshot().queued)
        release.countDown()
        assertEquals(1L, nextResponse().requestId)
        assertFalse(ran.get())
        assertTrue(written.isEmpty())
    }

    @Test
    fun cancelInterruptsRunningCommandAtCheckpointBeforeMutation() {
        val entered = CountDownLatch(1)
        val mutated = AtomicBoolean(false)
        pipeline.submit(1, 5_000) { ctx ->
            entered.countDown()
            while (!ctx.isCancelRequested) Thread.sleep(5)
            ctx.checkpoint()
            ctx.markMutationStarted()
            mutated.set(true)
            ok()
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        pipeline.cancel(1)

        val response = nextResponse()
        assertEquals(1L, response.requestId)
        assertEquals(ErrorCode.ERR_CANCELLED, response.response.errorCode)
        assertFalse(mutated.get())
    }

    @Test
    fun mutationGateRefusesCancelledCommandEvenWithoutCheckpoint() {
        val entered = CountDownLatch(1)
        val mutated = AtomicBoolean(false)
        pipeline.submit(1, 5_000) { ctx ->
            entered.countDown()
            while (!ctx.isCancelRequested) Thread.sleep(5)
            ctx.markMutationStarted()
            mutated.set(true)
            ok()
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        pipeline.cancel(1)

        assertEquals(ErrorCode.ERR_CANCELLED, nextResponse().response.errorCode)
        assertFalse(mutated.get())
    }

    @Test
    fun cancelAfterMutationStartedIsIgnoredAndDefinitiveResultWins() {
        val mutating = CountDownLatch(1)
        val finish = CountDownLatch(1)
        pipeline.submit(1, 5_000) { ctx ->
            ctx.markMutationStarted()
            mutating.countDown()
            finish.await()
            ctx.checkpoint()
            boolResponse(true)
        }
        assertTrue(mutating.await(1, TimeUnit.SECONDS))

        pipeline.cancel(1)
        finish.countDown()

        val response = nextResponse()
        assertTrue(response.response.ok)
        assertTrue(response.response.result.bool)
        assertTrue(written.isEmpty())
    }

    @Test
    fun cancelOfTerminalOrUnknownRequestIsIgnored() {
        pipeline.submit(1, 5_000) { ok() }
        assertEquals(1L, nextResponse().requestId)

        pipeline.cancel(1)
        pipeline.cancel(99)

        assertNull(written.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun queueResidenceConsumesDeadline() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)
        val ran = AtomicBoolean(false)
        pipeline.submit(2, 100) { ran.set(true); ok() }

        now.addAndGet(150)
        release.countDown()

        assertEquals(1L, nextResponse().requestId)
        val expired = nextResponse()
        assertEquals(2L, expired.requestId)
        assertEquals(ErrorCode.ERR_DEADLINE_EXCEEDED, expired.response.errorCode)
        assertFalse(ran.get())
    }

    @Test
    fun deadlineCheckpointStopsRunningCommandBeforeMutation() {
        val entered = CountDownLatch(1)
        val advance = CountDownLatch(1)
        pipeline.submit(1, 100) { ctx ->
            entered.countDown()
            advance.await()
            ctx.checkpoint()
            fail("Command continued past its deadline")
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        now.addAndGet(100)
        advance.countDown()

        assertEquals(ErrorCode.ERR_DEADLINE_EXCEEDED, nextResponse().response.errorCode)
    }

    @Test
    fun handlerExceptionBecomesInternal() {
        pipeline.submit(1, 5_000) { throw IllegalStateException("boom") }

        val response = nextResponse()
        assertEquals(ErrorCode.ERR_INTERNAL, response.response.errorCode)
        assertEquals("java.lang.IllegalStateException: boom", response.response.message)
    }

    @Test
    fun watchdogPoisonsStuckExecutorBeforeMutationAsUnhealthy() {
        val stuck = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mutated = AtomicBoolean(false)
        pipeline.submit(1, 100) { ctx ->
            stuck.countDown()
            release.await()
            ctx.markMutationStarted()
            mutated.set(true)
            ok()
        }
        assertTrue(stuck.await(1, TimeUnit.SECONDS))
        val queuedRan = AtomicBoolean(false)
        pipeline.submit(2, 5_000) { queuedRan.set(true); ok() }

        now.addAndGet(599)
        assertFalse(pipeline.checkWatchdog())
        now.addAndGet(1)
        assertTrue(pipeline.checkWatchdog())

        val responses = List(2) { nextResponse() }.associateBy { it.requestId }
        assertEquals(ErrorCode.ERR_DRIVER_UNHEALTHY, responses.getValue(1L).response.errorCode)
        assertEquals(ErrorCode.ERR_DRIVER_UNHEALTHY, responses.getValue(2L).response.errorCode)
        assertEquals(1, poisonReasons.size)
        assertTrue(pipeline.isPoisoned)

        assertEquals(
            CommandPipeline.Admission.UNHEALTHY,
            pipeline.submit(3, 5_000) { ok() },
        )
        assertEquals(ErrorCode.ERR_DRIVER_UNHEALTHY, nextResponse().response.errorCode)

        release.countDown()
        assertTrue(pipeline.awaitTermination(1_000))
        assertFalse(mutated.get(), "Late work mutated after the watchdog poisoned the session")
        assertFalse(queuedRan.get())
        assertNull(written.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun watchdogReportsIndeterminateAfterMutationStartedAndDropsLateResult() {
        val mutating = CountDownLatch(1)
        val release = CountDownLatch(1)
        pipeline.submit(1, 100) { ctx ->
            ctx.markMutationStarted()
            mutating.countDown()
            release.await()
            boolResponse(true)
        }
        assertTrue(mutating.await(1, TimeUnit.SECONDS))

        now.addAndGet(600)
        assertTrue(pipeline.checkWatchdog())

        assertEquals(ErrorCode.ERR_INDETERMINATE, nextResponse().response.errorCode)
        release.countDown()
        pipeline.awaitTermination(1_000)
        assertNull(written.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun watchdogIsQuietWhenIdleOrWithinGrace() {
        assertFalse(pipeline.checkWatchdog())
        val release = CountDownLatch(1)
        pipeline.submit(1, 100) { release.await(); ok() }
        awaitRunning(1)
        now.addAndGet(100)
        assertFalse(pipeline.checkWatchdog())
        release.countDown()
        assertEquals(1L, nextResponse().requestId)
        assertTrue(poisonReasons.isEmpty())
    }

    @Test
    fun pongBypassesBusyExecutor() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)

        pipeline.pong(0)

        assertEquals(Outbound.Pong(0), written.poll(1, TimeUnit.SECONDS))
        release.countDown()
        assertEquals(1L, nextResponse().requestId)
    }

    @Test
    fun writeFailureIsReportedAndFurtherOutputIsDropped() {
        failWrites.set(true)
        pipeline.submit(1, 5_000) { ok() }
        pipeline.submit(2, 5_000) { ok() }

        val failure = writeFailures.poll(1, TimeUnit.SECONDS)
        assertEquals("transport down", failure?.message)
        assertTrue(pipeline.awaitTermination(1_000))
        assertTrue(writeFailures.isEmpty(), "Write failure was reported more than once")
        assertTrue(written.isEmpty())
    }

    @Test
    fun shutdownDrainsQueuedWorkAndRefusesNewWork() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)
        pipeline.submit(2, 5_000) { ok() }

        pipeline.shutdown()
        assertEquals(CommandPipeline.Admission.CLOSED, pipeline.submit(3, 5_000) { ok() })
        release.countDown()

        assertEquals(listOf(1L, 2L), List(2) { nextResponse().requestId })
        assertTrue(pipeline.awaitTermination(1_000))
        assertNull(written.poll(50, TimeUnit.MILLISECONDS))
    }

    @Test
    fun awaitTerminationReportsBlockedExecutor() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)

        assertFalse(pipeline.awaitTermination(100))
        release.countDown()
        assertTrue(pipeline.awaitTermination(1_000))
    }

    @Test
    fun discardQueuedDropsUnstartedWorkAndKeepsRunningCommand() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)
        val ran = AtomicBoolean(false)
        pipeline.submit(2, 5_000) { ran.set(true); ok() }

        pipeline.discardQueued()

        assertEquals(ErrorCode.ERR_CANCELLED, nextResponse().response.errorCode)
        assertEquals(1L, pipeline.snapshot().running)
        release.countDown()
        assertEquals(1L, nextResponse().requestId)
        assertFalse(ran.get())
    }

    @Test
    fun checkCancelledIgnoresDeadlineButHonorsCancel() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        pipeline.submit(1, 100) { ctx ->
            entered.countDown()
            proceed.await()
            ctx.checkCancelled()
            Responses.failure(ErrorCode.ERR_WAIT_TIMEOUT, durationMs = 0)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        now.addAndGet(500)
        proceed.countDown()
        assertEquals(ErrorCode.ERR_WAIT_TIMEOUT, nextResponse().response.errorCode)

        val entered2 = CountDownLatch(1)
        pipeline.submit(2, 5_000) { ctx ->
            entered2.countDown()
            while (!ctx.isCancelRequested) Thread.sleep(2)
            ctx.checkCancelled()
            fail("continued after cancel")
        }
        assertTrue(entered2.await(1, TimeUnit.SECONDS))
        pipeline.cancel(2)
        assertEquals(ErrorCode.ERR_CANCELLED, nextResponse().response.errorCode)
    }

    @Test
    fun blobFramesPrecedeTheTerminalResponseInOrder() {
        val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES * 2 + 17) { it.toByte() }
        pipeline.submit(1, 5_000) { ctx ->
            val (blob, outcome) = ctx.transferBlob("image/png", bytes)
            assertEquals(BlobTransfer.Outcome.COMPLETED, outcome)
            Response.newBuilder(Responses.done()).setArtifact(blob.artifactInfo(1, 2)).build()
        }
        val start = next<Outbound.BlobStartFrame>()
        assertEquals(bytes.size.toLong(), start.start.totalLength)
        assertEquals(BlobFrames.sha256Hex(bytes), start.start.sha256)
        val reassembled = java.io.ByteArrayOutputStream()
        repeat(3) { index ->
            val chunk = BlobFrames.decodeChunk(next<Outbound.BlobChunkFrame>().payload)
            assertEquals(index, chunk.index)
            assertEquals(start.start.blobId, chunk.blobId.toString())
            reassembled.write(chunk.data)
        }
        val end = next<Outbound.BlobEndFrame>()
        assertEquals(start.start.blobId, end.end.blobId)
        assertEquals(bytes.size.toLong(), end.end.byteCount)
        assertTrue(bytes.contentEquals(reassembled.toByteArray()))
        val response = nextResponse()
        assertEquals(1L, response.requestId)
        assertEquals(start.start.blobId, response.response.artifact.blobId)
        assertTrue(written.isEmpty())
    }

    @Test
    fun cancelAbortsAnActiveBlobAtTheNextChunkBoundary() {
        val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES * 4)
        cancelOnFirstChunk.set(true)
        pipeline.submit(1, 5_000) { ctx ->
            val (_, outcome) = ctx.transferBlob("image/png", bytes)
            assertEquals(BlobTransfer.Outcome.CANCELLED, outcome)
            ctx.checkCancelled()
            fail("continued after an aborted blob")
        }
        next<Outbound.BlobStartFrame>()
        next<Outbound.BlobChunkFrame>()
        assertEquals(ErrorCode.ERR_CANCELLED, nextResponse().response.errorCode)
        assertTrue(written.isEmpty())
    }

    @Test
    fun aPongIsNotQueuedBehindTheRestOfABlob() {
        val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES * 4)
        pingOnFirstChunk.set(true)
        pipeline.submit(1, 5_000) { ctx ->
            assertEquals(BlobTransfer.Outcome.COMPLETED, ctx.transferBlob("image/png", bytes).outcome)
            Responses.done()
        }
        next<Outbound.BlobStartFrame>()
        next<Outbound.BlobChunkFrame>()
        // The PING arrived while chunk 0 was written: it is answered before chunk 1, exactly once.
        assertEquals(77L, next<Outbound.Pong>().requestId)
        repeat(3) { next<Outbound.BlobChunkFrame>() }
        next<Outbound.BlobEndFrame>()
        assertEquals(1L, nextResponse().requestId)
        assertTrue(written.isEmpty())
    }

    @Test
    fun sleepFollowsTheInjectedClockAndEndsOnCancel() {
        val sleeping = CountDownLatch(1)
        pipeline.submit(1, 60_000) { ctx ->
            sleeping.countDown()
            ctx.sleep(30_000)
            Responses.done()
        }
        assertTrue(sleeping.await(1, TimeUnit.SECONDS))
        now.addAndGet(30_000)
        assertTrue(nextResponse().response.ok, "a manual clock advance ends the sleep")

        val started = System.nanoTime()
        pipeline.submit(2, 60_000) { ctx ->
            ctx.sleep(30_000)
            ctx.checkCancelled()
            fail("slept through a cancel")
        }
        while (pipeline.snapshot().running != 2L) Thread.sleep(1)
        pipeline.cancel(2)
        assertEquals(ErrorCode.ERR_CANCELLED, nextResponse().response.errorCode)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun deadlineAbortsAnActiveBlob() {
        val bytes = ByteArray(MAX_BLOB_CHUNK_BYTES * 3)
        pipeline.submit(1, 100) { ctx ->
            now.addAndGet(200)
            val (_, outcome) = ctx.transferBlob("image/png", bytes)
            assertEquals(BlobTransfer.Outcome.DEADLINE_EXCEEDED, outcome)
            Responses.failure(ErrorCode.ERR_DEADLINE_EXCEEDED, durationMs = 0)
        }
        next<Outbound.BlobStartFrame>()
        assertEquals(ErrorCode.ERR_DEADLINE_EXCEEDED, nextResponse().response.errorCode)
    }

    @Test
    fun heartbeatSilencePoisonsEvenAnIdleExecutorAndInboundFramesResetIt() {
        val heartbeatWritten = LinkedBlockingQueue<Outbound>()
        val heartbeatPipeline = CommandPipeline(
            clock = clock,
            sink = { heartbeatWritten.put(it) },
            listener = listener,
            watchdogPollMs = 0,
            heartbeatTimeoutMs = 1_000,
        ).start()
        try {
            now.addAndGet(900)
            assertFalse(heartbeatPipeline.checkWatchdog())
            heartbeatPipeline.heartbeat()
            now.addAndGet(900)
            assertFalse(heartbeatPipeline.checkWatchdog(), "inbound frame must reset the heartbeat window")

            val release = CountDownLatch(1)
            heartbeatPipeline.submit(1, 30_000) { release.await(); ok() }
            now.addAndGet(200)
            assertTrue(heartbeatPipeline.checkWatchdog())
            assertTrue(poisonReasons.poll(1, TimeUnit.SECONDS)!!.contains("heartbeat"))
            val response = (heartbeatWritten.poll(2, TimeUnit.SECONDS) as Outbound.TerminalResponse).response
            assertEquals(ErrorCode.ERR_DRIVER_UNHEALTHY, response.errorCode)
            assertEquals(ErrorDetail.HEARTBEAT_EXPIRED, response.detail)
            assertEquals(CommandPipeline.Admission.UNHEALTHY, heartbeatPipeline.submit(2, 1_000) { ok() })
            release.countDown()
        } finally {
            heartbeatPipeline.awaitTermination(1_000)
        }
    }

    @Test
    fun readerRejectionsLeaveOnTheWriterLaneInOrder() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { release.await(); ok() }
        awaitRunning(1)

        pipeline.respond(2, Responses.failure(ErrorCode.ERR_UNSUPPORTED, durationMs = 0))
        release.countDown()

        val rejected = nextResponse()
        assertEquals(2L, rejected.requestId)
        assertEquals(ErrorCode.ERR_UNSUPPORTED, rejected.response.errorCode)
        assertEquals(1L, nextResponse().requestId)
    }

    @Test
    fun invalidRequestIsAnsweredOnTheReaderLaneWhileAnotherCommandRuns() {
        val release = CountDownLatch(1)
        pipeline.submit(1, 30_000) { release.await(); ok() }
        awaitRunning(1)
        // Fill the queue: a request that reached the pipeline now would come back OVERLOADED.
        pipeline.submit(2, 30_000) { ok() }
        pipeline.submit(3, 30_000) { ok() }

        // The reader lane screens before submitting, so each rejection goes straight to the writer.
        val rejected =
            mapOf(
                4L to Requests.of(Commands.tap(Selector.getDefaultInstance())).withEnvelope(SESSION, GENERATION, 5_000),
                5L to Requests.of(Commands.tap(Selectors.resource(""))).withEnvelope(SESSION, GENERATION, 5_000),
                6L to Requests.health().withEnvelope(SESSION, GENERATION, MAX_REQUEST_TIMEOUT_MS + 1),
                7L to Requests.health().withEnvelope(SESSION, GENERATION + 1, 5_000),
            )
        rejected.forEach { (id, request) ->
            val rejection = RequestScreening.screen(request, SESSION, GENERATION) ?: fail("request $id must be rejected")
            pipeline.respond(id, rejection)
        }

        val answers = List(rejected.size) { nextResponse() }
        assertEquals(listOf(4L, 5L, 6L, 7L), answers.map { it.requestId })
        assertEquals(
            listOf(
                ErrorCode.ERR_INVALID_SELECTOR,
                ErrorCode.ERR_INVALID_SELECTOR,
                ErrorCode.ERR_INVALID_REQUEST,
                ErrorCode.ERR_SESSION_MISMATCH,
            ),
            answers.map { it.response.errorCode },
        )
        assertEquals(ErrorDetail.EMPTY_NODE, answers[0].response.detail)
        assertEquals(ErrorDetail.EMPTY_VALUE, answers[1].response.detail)
        assertEquals(1L, pipeline.snapshot().running, "the long command is still running")
        assertEquals(listOf(2L, 3L), pipeline.snapshot().queued)

        release.countDown()
        assertEquals(listOf(1L, 2L, 3L), List(3) { nextResponse().requestId })
    }

    @Test
    fun screeningPassesAWellFormedRequestForTheSession() {
        val tap = Requests.of(Commands.tap(Selectors.text("OK"))).withEnvelope(SESSION, GENERATION, 5_000)
        assertNull(RequestScreening.screen(tap, SESSION, GENERATION))
        assertNull(RequestScreening.screen(Requests.health().withEnvelope(SESSION, GENERATION, 0), SESSION, GENERATION))
        val otherSession = RequestScreening.screen(tap, "other", GENERATION)
        assertEquals(ErrorCode.ERR_SESSION_MISMATCH, otherSession?.errorCode)
    }

    @Test
    fun closeIsWrittenAfterQueuedFramesAndNothingFollowsIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        pipeline.submit(1, 5_000) { entered.countDown(); release.await(); ok() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        pipeline.respond(2, Responses.failure(ErrorCode.ERR_UNSUPPORTED, durationMs = 0))

        assertTrue(pipeline.close("DUPLICATE_OR_STALE: request ID 1", 1_000))

        assertEquals(2L, nextResponse().requestId)
        assertEquals("DUPLICATE_OR_STALE: request ID 1", next<Outbound.Close>().reason)
        assertEquals(CommandPipeline.Admission.CLOSED, pipeline.submit(3, 5_000) { ok() })
        release.countDown()
        pipeline.awaitTermination(1_000)
        assertNull(written.poll(100, TimeUnit.MILLISECONDS), "nothing may follow CLOSE")
    }

    @Test
    fun gateStaysOpenOnceMutationStarted() {
        pipeline.submit(1, 1_000) { ctx ->
            ctx.markMutationStarted()
            now.addAndGet(5_000)
            pipeline.cancel(1)
            // A second gate (e.g. a later scroll) after deadline and cancel must not throw.
            ctx.markMutationStarted()
            ok()
        }
        val response = nextResponse().response
        assertTrue(response.ok, "second gate reported $response")
    }

    @Test
    fun nonMutatingFailureAfterGateIsReportedAsIndeterminate() {
        val nonMutating = ErrorCode.entries.filter { !it.mayHaveMutated }
        nonMutating.forEachIndexed { index, code ->
            val requestId = index + 1L
            pipeline.submit(requestId, 5_000) { ctx ->
                ctx.markMutationStarted()
                Responses.failure(code, detail = "SUB", message = "why", durationMs = 0)
            }
            val response = nextResponse()
            assertEquals(requestId, response.requestId)
            assertEquals(ErrorCode.ERR_INDETERMINATE, response.response.errorCode, "$code after the gate")
            assertEquals("SUB", response.response.detail)
            assertTrue(response.response.message!!.startsWith("${code.label}/SUB after the mutation started: why"))
        }
        pipeline.submit(100, 5_000) { ctx ->
            ctx.markMutationStarted()
            Responses.failure(ErrorCode.ERR_WAIT_TIMEOUT, durationMs = 0)
        }
        assertEquals("WAIT_TIMEOUT", nextResponse().response.detail, "the code name stands in for a missing detail")
    }

    @Test
    fun failuresBeforeGateOrAlreadyMutatingAreUnchanged() {
        pipeline.submit(1, 5_000) { Responses.failure(ErrorCode.ERR_NOT_FOUND, durationMs = 0) }
        assertEquals(ErrorCode.ERR_NOT_FOUND, nextResponse().response.errorCode)
        pipeline.submit(2, 5_000) { ctx ->
            ctx.markMutationStarted()
            Responses.failure(ErrorCode.ERR_ACTION_REJECTED, detail = ErrorDetail.PARTIAL_INPUT, durationMs = 0)
        }
        val rejected = nextResponse().response
        assertEquals(ErrorCode.ERR_ACTION_REJECTED, rejected.errorCode)
        assertEquals(ErrorDetail.PARTIAL_INPUT, rejected.detail)
    }

    private inline fun <reified T : Outbound> next(): T {
        val message = written.poll(2, TimeUnit.SECONDS) ?: fail("Nothing written")
        return message as? T ?: fail("Unexpected outbound $message")
    }

    private fun ok() = Responses.done()

    private fun boolResponse(value: Boolean): Response = Response.newBuilder().setResult(CommandResult.newBuilder().setBool(value)).build()

    private fun nextResponse(): Outbound.TerminalResponse {
        val message = written.poll(2, TimeUnit.SECONDS) ?: fail("No response written")
        return message as? Outbound.TerminalResponse ?: fail("Unexpected outbound $message")
    }

    private fun awaitRunning(requestId: Long) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (pipeline.snapshot().running != requestId) {
            check(System.nanoTime() < deadline) { "Request $requestId never started" }
            Thread.sleep(2)
        }
    }

    private companion object {
        const val SESSION = "session-1"
        const val GENERATION = 3L
        const val SCREEN_AUT = "com.example.aut"
    }
}
