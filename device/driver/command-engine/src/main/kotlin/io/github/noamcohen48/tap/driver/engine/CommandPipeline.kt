package io.github.noamcohen48.tap.driver.engine

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.DRIVER_UNINTERRUPTIBLE_GRACE_MS
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Responses
import io.github.noamcohen48.tap.protocol.errorOrNull
import io.github.noamcohen48.tap.protocol.label
import io.github.noamcohen48.tap.protocol.mayHaveMutated
import io.github.noamcohen48.tap.wire.v1.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Driver-side execution lanes for one authenticated session:
 *
 * ```
 * socket reader -> bounded queue -> single command executor -> writer -> socket
 *                                         ^
 *                                      watchdog
 * ```
 *
 * The reader thread (owned by the caller) only calls [submit], [respond], [cancel], [pong],
 * [close], and [shutdown]; it never writes to the transport and never blocks on UI work, so `CANCEL`, `PING`, and transport closure are
 * observed while a command runs. Every accepted command gets exactly one terminal response.
 * The watchdog poisons the pipeline when the executor is stuck past a command's deadline plus
 * [uninterruptibleGraceMs], or when the host has been silent for [heartbeatTimeoutMs] (the
 * reader reports every inbound frame through [heartbeat]); from then on nothing new mutates the
 * device and the owner is expected to exit.
 */
class CommandPipeline(
    private val clock: Clock,
    private val sink: OutboundSink,
    private val listener: PipelineListener,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val uninterruptibleGraceMs: Long = DEFAULT_UNINTERRUPTIBLE_GRACE_MS,
    private val watchdogPollMs: Long = DEFAULT_WATCHDOG_POLL_MS,
    private val heartbeatTimeoutMs: Long = 0,
    threadNamePrefix: String = "tap-driver",
) {
    enum class Admission { QUEUED, OVERLOADED, UNHEALTHY, CLOSED }

    data class Snapshot(
        val queued: List<Long>,
        val running: Long?,
        val poisoned: Boolean,
        val accepting: Boolean,
    )

    private val lock = ReentrantLock()
    private val queueChanged = lock.newCondition()
    private val queue = ArrayDeque<PendingCommand>()
    private var running: PendingCommand? = null
    private var accepting = true
    private var poisoned = false
    private var poisonReason: String? = null
    @Volatile private var lastInboundMs = clock.nowMs()

    private val outbound = LinkedBlockingQueue<OutboundItem>()

    /** PING answers, a priority lane: the writer drains it between blob chunks too (E-4). */
    private val pendingPongs = LinkedBlockingQueue<Long>()
    private val executorDone = CountDownLatch(1)
    private val writerDone = CountDownLatch(1)
    @Volatile private var writeFailed = false

    private val executorThread = Thread(::runExecutor, "$threadNamePrefix-executor").apply { isDaemon = true }
    private val writerThread = Thread(::runWriter, "$threadNamePrefix-writer").apply { isDaemon = true }
    private val watchdogThread = Thread(::runWatchdog, "$threadNamePrefix-watchdog").apply { isDaemon = true }

    init {
        require(queueCapacity >= 1) { "queueCapacity must be positive" }
        require(uninterruptibleGraceMs >= 0) { "uninterruptibleGraceMs must not be negative" }
        require(heartbeatTimeoutMs >= 0) { "heartbeatTimeoutMs must not be negative" }
    }

    /** Records inbound host activity (any authenticated frame) for heartbeat expiry. */
    fun heartbeat() {
        lastInboundMs = clock.nowMs()
    }

    fun start(): CommandPipeline {
        writerThread.start()
        executorThread.start()
        if (watchdogPollMs > 0) watchdogThread.start()
        return this
    }

    val isPoisoned: Boolean get() = lock.withLock { poisoned }

    /**
     * Accepts a command whose deadline starts now. Overload and poisoned states produce an
     * immediate terminal response; a closed pipeline drops the request because the transport
     * that would carry the response is gone.
     */
    fun submit(requestId: Long, timeoutMs: Long, work: (CommandContext) -> Response): Admission {
        require(timeoutMs >= 0) { "timeoutMs must not be negative" }
        val command = PendingCommand(requestId, timeoutMs, clock.nowMs(), work)
        val admission = lock.withLock {
            when {
                !accepting -> Admission.CLOSED
                poisoned -> Admission.UNHEALTHY
                queue.size >= queueCapacity -> Admission.OVERLOADED
                else -> {
                    queue.addLast(command)
                    queueChanged.signalAll()
                    Admission.QUEUED
                }
            }
        }
        when (admission) {
            Admission.UNHEALTHY -> terminate(
                command,
                error(ErrorCode.ERR_DRIVER_UNHEALTHY, ErrorDetail.WATCHDOG, poisonReason),
            )
            Admission.OVERLOADED -> terminate(
                command,
                error(ErrorCode.ERR_OVERLOADED, null, "PendingCommand queue holds $queueCapacity requests"),
            )
            Admission.QUEUED, Admission.CLOSED -> Unit
        }
        return admission
    }

    /**
     * Cancels a queued command with `CANCELLED`, requests cooperative cancellation of the
     * running command if it has not started mutating, and ignores everything else. A command
     * that already mutated returns its definitive result; a terminal command never gets a
     * second response.
     */
    fun cancel(requestId: Long) {
        val removed = lock.withLock {
            val queued = queue.firstOrNull { it.requestId == requestId }
            if (queued != null) {
                queue.remove(queued)
                queued
            } else {
                val current = running
                if (current != null && current.requestId == requestId && !current.mutationStarted) {
                    current.requestCancel()
                }
                null
            }
        }
        if (removed != null) terminate(removed, error(ErrorCode.ERR_CANCELLED, ErrorDetail.CANCELLED_IN_QUEUE))
    }

    /**
     * Cancels every queued command. Used when the transport is lost: work that has not started
     * is dropped rather than run for a host that can no longer observe it.
     */
    fun discardQueued() {
        val discarded = lock.withLock {
            val queued = queue.toList()
            queue.clear()
            queued
        }
        discarded.forEach { terminate(it, error(ErrorCode.ERR_CANCELLED, ErrorDetail.TRANSPORT_CLOSED)) }
    }

    /**
     * Writes [response] for a request that never entered the pipeline (a payload the decoder
     * rejected). It goes through the writer lane like every other frame, so the reader thread
     * never touches the transport and frames can never interleave.
     */
    fun respond(requestId: Long, response: Response) {
        outbound.put(OutboundItem.Message(Outbound.TerminalResponse(requestId, response)))
    }

    /**
     * Orderly close after a protocol violation: stops accepting commands and queues a `CLOSE`
     * frame carrying [reason] behind whatever the writer already holds. Nothing is written after
     * it. Returns true once the writer has handled the close (written or failed), false if it is
     * still busy after [timeoutMs]; the caller then closes the transport regardless.
     */
    fun close(reason: String, timeoutMs: Long): Boolean {
        shutdown()
        val handled = CountDownLatch(1)
        outbound.put(OutboundItem.Close(Outbound.Close(reason), handled))
        return handled.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Answers a `PING` on the writer lane without touching the executor. A `PONG` never waits
     * behind a whole blob: the writer also sends pending ones between chunks.
     */
    fun pong(requestId: Long) {
        pendingPongs.put(requestId)
        outbound.put(OutboundItem.PongsPending)
    }

    /** Queues a blob for the writer lane; called by [CommandContext.transferBlob] on the executor. */
    internal fun transfer(blob: BlobTransfer) {
        if (writeFailed) {
            blob.finish(BlobTransfer.Outcome.WRITE_FAILED)
            return
        }
        outbound.put(OutboundItem.Blob(blob))
    }

    /** Stops accepting new commands. Queued commands still run; the caller awaits termination. */
    fun shutdown() {
        lock.withLock {
            accepting = false
            queueChanged.signalAll()
        }
    }

    /**
     * Waits for the executor to drain and the writer to flush, both within [timeoutMs]. Returns
     * false if either is still busy (a writer blocked on a stalled transport included); the
     * caller then closes the transport and decides whether the process must die.
     */
    fun awaitTermination(timeoutMs: Long): Boolean {
        shutdown()
        val deadline = clock.nowMs() + timeoutMs
        if (!executorDone.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        outbound.put(OutboundItem.Stop)
        return writerDone.await((deadline - clock.nowMs()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    /** Runs one watchdog check. Exposed so tests can drive it with a manual clock. */
    fun checkWatchdog(): Boolean {
        val now = clock.nowMs()
        val (reason, detail) = lock.withLock {
            if (poisoned) return false
            val silentMs = now - lastInboundMs
            val current = running
            when {
                heartbeatTimeoutMs > 0 && silentMs >= heartbeatTimeoutMs ->
                    "Host sent nothing for ${silentMs}ms (heartbeat timeout ${heartbeatTimeoutMs}ms)" to
                        ErrorDetail.HEARTBEAT_EXPIRED
                current != null && now >= current.deadlineMs + uninterruptibleGraceMs ->
                    "Request ${current.requestId} exceeded its deadline by more than ${uninterruptibleGraceMs}ms" to
                        ErrorDetail.WATCHDOG
                else -> return false
            }
        }
        poison(reason, detail)
        return true
    }

    /**
     * Marks the executor untrustworthy: queued work fails with `DRIVER_UNHEALTHY`, the running
     * command gets `INDETERMINATE` if it already mutated and `DRIVER_UNHEALTHY` otherwise, and
     * later mutation attempts are refused at the gate.
     */
    fun poison(reason: String, detail: String = ErrorDetail.WATCHDOG) {
        val doomed = mutableListOf<Pair<PendingCommand, Response>>()
        val firstPoison = lock.withLock {
            if (poisoned) return@withLock false
            poisoned = true
            poisonReason = reason
            while (queue.isNotEmpty()) {
                val queued = queue.removeFirst()
                doomed += queued to error(ErrorCode.ERR_DRIVER_UNHEALTHY, detail, reason)
            }
            running?.let { current ->
                val code = if (current.mutationStarted) ErrorCode.ERR_INDETERMINATE else ErrorCode.ERR_DRIVER_UNHEALTHY
                doomed += current to error(code, detail, reason)
            }
            queueChanged.signalAll()
            true
        }
        if (!firstPoison) return
        doomed.forEach { (command, response) -> terminate(command, response) }
        listener.onPoisoned(reason)
    }

    fun snapshot(): Snapshot = lock.withLock {
        Snapshot(queue.map(PendingCommand::requestId), running?.requestId, poisoned, accepting)
    }

    private fun mutationGate(command: PendingCommand) {
        lock.withLock {
            // Once open the gate stays open: a later call must never report a non-mutating
            // CANCELLED/DEADLINE_EXCEEDED for a command that already touched the device.
            if (command.mutationStarted) return
            if (poisoned) throw CommandInterrupted(ErrorCode.ERR_DRIVER_UNHEALTHY, ErrorDetail.WATCHDOG)
            if (command.isTerminal) throw CommandInterrupted(ErrorCode.ERR_CANCELLED)
            if (command.cancelRequested) throw CommandInterrupted(ErrorCode.ERR_CANCELLED)
            if (clock.nowMs() >= command.deadlineMs) throw CommandInterrupted(ErrorCode.ERR_DEADLINE_EXCEEDED)
            command.mutationStarted = true
        }
    }

    private fun runExecutor() {
        try {
            while (true) {
                val command = lock.withLock {
                    while (queue.isEmpty() && accepting && !poisoned) queueChanged.await()
                    if (queue.isEmpty() || poisoned) return
                    queue.removeFirst().also { running = it }
                }
                try {
                    execute(command)
                } finally {
                    lock.withLock { running = null }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            executorDone.countDown()
        }
    }

    private fun execute(command: PendingCommand) {
        val response = when {
            command.cancelRequested -> error(ErrorCode.ERR_CANCELLED, ErrorDetail.CANCELLED_IN_QUEUE)
            clock.nowMs() >= command.deadlineMs -> error(ErrorCode.ERR_DEADLINE_EXCEEDED, ErrorDetail.EXPIRED_IN_QUEUE)
            else -> try {
                command.work(CommandContext(command, clock, ::mutationGate, ::transfer))
            } catch (interrupted: CommandInterrupted) {
                error(interrupted.errorCode, interrupted.detail, null, command)
            } catch (error: Throwable) {
                error(ErrorCode.ERR_INTERNAL, null, error.toString(), command)
            }
        }
        terminate(command, afterGate(command, response))
    }

    /**
     * A failure whose code claims "nothing changed" is only true before the mutation gate
     * opened. After it, the handler may already have injected input, so the response is
     * rewritten to `INDETERMINATE`; the original code travels in the message, and its detail is
     * kept (or the original code name when there was none) so callers can still see why it ended.
     */
    private fun afterGate(command: PendingCommand, response: Response): Response {
        val error = response.errorOrNull
        if (!command.mutationStarted || error == null || error.code.mayHaveMutated) return response
        val detail = error.takeIf { it.hasDetail() }?.detail
        val original = listOfNotNull(error.code.label, detail).joinToString("/")
        val message = "$original after the mutation started" + (error.takeIf { it.hasMessage() }?.let { ": ${it.message}" } ?: "")
        val rewritten =
            error.toBuilder()
                .setCode(ErrorCode.ERR_INDETERMINATE)
                .setDetail(detail ?: error.code.label)
                .setMessage(message)
        return response.toBuilder().setResult(response.result.toBuilder().setError(rewritten)).build()
    }

    private fun error(
        code: ErrorCode,
        detail: String?,
        message: String? = null,
        command: PendingCommand? = null,
    ): Response = Responses.failure(
        code,
        detail = detail,
        message = message,
        durationMs = command?.let { clock.nowMs() - it.acceptedAtMs } ?: 0,
    )

    private fun terminate(command: PendingCommand, response: Response) {
        if (command.complete(response)) {
            outbound.put(OutboundItem.Message(Outbound.TerminalResponse(command.requestId, response)))
        }
    }

    private fun runWriter() {
        try {
            while (true) {
                when (val item = outbound.take()) {
                    OutboundItem.Stop -> return
                    is OutboundItem.Message -> if (!writeFailed) write(item.message)
                    OutboundItem.PongsPending -> writePendingPongs()
                    is OutboundItem.Blob -> item.blob.finish(streamBlob(item.blob))
                    is OutboundItem.Close -> try {
                        write(item.message)
                        // The transport is finished: later responses and blobs are dropped.
                        writeFailed = true
                    } finally {
                        item.handled.countDown()
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            writerDone.countDown()
        }
    }

    private fun runWatchdog() {
        try {
            while (executorDone.count > 0) {
                checkWatchdog()
                Thread.sleep(watchdogPollMs)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun write(message: Outbound): Boolean {
        if (writeFailed) return false
        return try {
            sink.write(message)
            true
        } catch (error: Throwable) {
            writeFailed = true
            listener.onWriteFailed(error)
            false
        }
    }

    /** Writes every queued `PONG`; one already sent between blob chunks is not sent again. */
    private fun writePendingPongs(): Boolean {
        while (true) {
            val requestId = pendingPongs.poll() ?: return true
            if (!write(Outbound.Pong(requestId))) return false
        }
    }

    /**
     * Streams one blob, stopping at a chunk boundary on cancel, deadline, or write failure.
     * Pending `PONG`s go out between chunks so a large transfer cannot starve the heartbeat.
     */
    private fun streamBlob(blob: BlobTransfer): BlobTransfer.Outcome {
        val command = blob.command
        val requestId = command.requestId
        if (!write(Outbound.BlobStartFrame(requestId, blob.start()))) return BlobTransfer.Outcome.WRITE_FAILED
        for (index in 0 until blob.chunkCount) {
            if (command.cancelRequested) return BlobTransfer.Outcome.CANCELLED
            if (clock.nowMs() >= command.deadlineMs) return BlobTransfer.Outcome.DEADLINE_EXCEEDED
            if (!writePendingPongs()) return BlobTransfer.Outcome.WRITE_FAILED
            if (!write(Outbound.BlobChunkFrame(requestId, blob.chunkPayload(index)))) {
                return BlobTransfer.Outcome.WRITE_FAILED
            }
        }
        if (!write(Outbound.BlobEndFrame(requestId, blob.end()))) return BlobTransfer.Outcome.WRITE_FAILED
        return BlobTransfer.Outcome.COMPLETED
    }

    private sealed interface OutboundItem {
        data class Message(val message: Outbound) : OutboundItem
        class Blob(val blob: BlobTransfer) : OutboundItem
        class Close(val message: Outbound.Close, val handled: CountDownLatch) : OutboundItem
        data object PongsPending : OutboundItem
        data object Stop : OutboundItem
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 16
        const val DEFAULT_UNINTERRUPTIBLE_GRACE_MS = DRIVER_UNINTERRUPTIBLE_GRACE_MS
        const val DEFAULT_WATCHDOG_POLL_MS = 100L
    }
}
