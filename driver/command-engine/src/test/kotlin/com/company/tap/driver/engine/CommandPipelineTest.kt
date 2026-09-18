package com.company.tap.driver.engine

import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Response
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
    private val listener = object : PipelineListener {
        override fun onPoisoned(reason: String) { poisonReasons.put(reason) }
        override fun onWriteFailed(error: Throwable) { writeFailures.put(error) }
    }
    private val pipeline = CommandPipeline(
        clock = clock,
        sink = { message ->
            if (failWrites.get()) throw IllegalStateException("transport down")
            written.put(message)
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
            Response(true, durationMs = 0)
        }
        pipeline.submit(2, 5_000) { ctx ->
            synchronized(order) { order += ctx.requestId }
            Response(true, value = true, durationMs = 0)
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
        pipeline.submit(1, 5_000) { release.await(); Response(true, durationMs = 0) }
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
        assertEquals(ErrorCode.OVERLOADED, overloaded.response.errorCode)
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
        assertEquals(ErrorCode.CANCELLED, cancelled.response.errorCode)
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
        assertEquals(ErrorCode.CANCELLED, response.response.errorCode)
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

        assertEquals(ErrorCode.CANCELLED, nextResponse().response.errorCode)
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
            Response(true, value = true, durationMs = 0)
        }
        assertTrue(mutating.await(1, TimeUnit.SECONDS))

        pipeline.cancel(1)
        finish.countDown()

        val response = nextResponse()
        assertTrue(response.response.ok)
        assertEquals(true, response.response.value)
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
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, expired.response.errorCode)
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

        assertEquals(ErrorCode.DEADLINE_EXCEEDED, nextResponse().response.errorCode)
    }

    @Test
    fun handlerExceptionBecomesInternal() {
        pipeline.submit(1, 5_000) { throw IllegalStateException("boom") }

        val response = nextResponse()
        assertEquals(ErrorCode.INTERNAL, response.response.errorCode)
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
        assertEquals(ErrorCode.DRIVER_UNHEALTHY, responses.getValue(1L).response.errorCode)
        assertEquals(ErrorCode.DRIVER_UNHEALTHY, responses.getValue(2L).response.errorCode)
        assertEquals(1, poisonReasons.size)
        assertTrue(pipeline.isPoisoned)

        assertEquals(
            CommandPipeline.Admission.UNHEALTHY,
            pipeline.submit(3, 5_000) { ok() },
        )
        assertEquals(ErrorCode.DRIVER_UNHEALTHY, nextResponse().response.errorCode)

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
            Response(true, value = true, durationMs = 0)
        }
        assertTrue(mutating.await(1, TimeUnit.SECONDS))

        now.addAndGet(600)
        assertTrue(pipeline.checkWatchdog())

        assertEquals(ErrorCode.INDETERMINATE, nextResponse().response.errorCode)
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

        assertEquals(ErrorCode.CANCELLED, nextResponse().response.errorCode)
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
            Response.failure(ErrorCode.WAIT_TIMEOUT, durationMs = 0)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        now.addAndGet(500)
        proceed.countDown()
        assertEquals(ErrorCode.WAIT_TIMEOUT, nextResponse().response.errorCode)

        val entered2 = CountDownLatch(1)
        pipeline.submit(2, 5_000) { ctx ->
            entered2.countDown()
            while (!ctx.isCancelRequested) Thread.sleep(2)
            ctx.checkCancelled()
            fail("continued after cancel")
        }
        assertTrue(entered2.await(1, TimeUnit.SECONDS))
        pipeline.cancel(2)
        assertEquals(ErrorCode.CANCELLED, nextResponse().response.errorCode)
    }

    private fun ok() = Response(true, durationMs = 0)

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
}
