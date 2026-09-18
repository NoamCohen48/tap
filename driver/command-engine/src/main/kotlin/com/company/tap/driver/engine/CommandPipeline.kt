package com.company.tap.driver.engine

import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Response
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
 * The reader thread (owned by the caller) only calls [submit], [cancel], [pong], and
 * [shutdown]; it never blocks on UI work, so `CANCEL`, `PING`, and transport closure are
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
    private val queue = ArrayDeque<Command>()
    private var running: Command? = null
    private var accepting = true
    private var poisoned = false
    private var poisonReason: String? = null
    @Volatile private var lastInboundMs = clock.nowMs()

    private val outbound = LinkedBlockingQueue<OutboundItem>()
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
        val command = Command(requestId, timeoutMs, clock.nowMs(), work)
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
                error(ErrorCode.DRIVER_UNHEALTHY, ErrorDetail.WATCHDOG, poisonReason),
            )
            Admission.OVERLOADED -> terminate(
                command,
                error(ErrorCode.OVERLOADED, null, "Command queue holds $queueCapacity requests"),
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
                queued.phase = CommandPhase.TERMINAL
                queued
            } else {
                val current = running
                if (current != null && current.requestId == requestId && !current.mutationStarted) {
                    current.cancelRequested = true
                }
                null
            }
        }
        if (removed != null) terminate(removed, error(ErrorCode.CANCELLED, ErrorDetail.CANCELLED_IN_QUEUE))
    }

    /**
     * Cancels every queued command. Used when the transport is lost: work that has not started
     * is dropped rather than run for a host that can no longer observe it.
     */
    fun discardQueued() {
        val discarded = lock.withLock {
            val queued = queue.toList()
            queue.clear()
            queued.forEach { it.phase = CommandPhase.TERMINAL }
            queued
        }
        discarded.forEach { terminate(it, error(ErrorCode.CANCELLED, ErrorDetail.TRANSPORT_CLOSED)) }
    }

    /** Answers a `PING` on the writer lane without touching the executor. */
    fun pong(requestId: Long) {
        outbound.put(OutboundItem.Message(Outbound.Pong(requestId)))
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
     * Waits for the executor to drain and the writer to flush. Returns false if the executor
     * is still busy after [timeoutMs]; the caller then decides whether the process must die.
     */
    fun awaitTermination(timeoutMs: Long): Boolean {
        shutdown()
        if (!executorDone.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
        outbound.put(OutboundItem.Stop)
        writerDone.await()
        return true
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
        val doomed = mutableListOf<Pair<Command, Response>>()
        val firstPoison = lock.withLock {
            if (poisoned) return@withLock false
            poisoned = true
            poisonReason = reason
            while (queue.isNotEmpty()) {
                val queued = queue.removeFirst()
                queued.phase = CommandPhase.TERMINAL
                doomed += queued to error(ErrorCode.DRIVER_UNHEALTHY, detail, reason)
            }
            running?.let { current ->
                val code = if (current.mutationStarted) ErrorCode.INDETERMINATE else ErrorCode.DRIVER_UNHEALTHY
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
        Snapshot(queue.map(Command::requestId), running?.requestId, poisoned, accepting)
    }

    private fun mutationGate(command: Command) {
        lock.withLock {
            if (poisoned) throw CommandInterrupted(ErrorCode.DRIVER_UNHEALTHY, ErrorDetail.WATCHDOG)
            if (command.isTerminal) throw CommandInterrupted(ErrorCode.CANCELLED)
            if (command.cancelRequested) throw CommandInterrupted(ErrorCode.CANCELLED)
            if (clock.nowMs() >= command.deadlineMs) throw CommandInterrupted(ErrorCode.DEADLINE_EXCEEDED)
            command.mutationStarted = true
        }
    }

    private fun runExecutor() {
        try {
            while (true) {
                val command = lock.withLock {
                    while (queue.isEmpty() && accepting && !poisoned) queueChanged.await()
                    if (queue.isEmpty() || poisoned) return
                    queue.removeFirst().also {
                        it.phase = CommandPhase.RUNNING
                        running = it
                    }
                }
                try {
                    execute(command)
                } finally {
                    lock.withLock {
                        running = null
                        command.phase = CommandPhase.TERMINAL
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            executorDone.countDown()
        }
    }

    private fun execute(command: Command) {
        val response = when {
            command.cancelRequested -> error(ErrorCode.CANCELLED, ErrorDetail.CANCELLED_IN_QUEUE)
            clock.nowMs() >= command.deadlineMs -> error(ErrorCode.DEADLINE_EXCEEDED, ErrorDetail.EXPIRED_IN_QUEUE)
            else -> try {
                command.work(CommandContext(command, clock, ::mutationGate, ::transfer))
            } catch (interrupted: CommandInterrupted) {
                error(interrupted.errorCode, interrupted.detail, null, command)
            } catch (error: Throwable) {
                error(ErrorCode.INTERNAL, null, error.toString(), command)
            }
        }
        terminate(command, response)
    }

    private fun error(
        code: ErrorCode,
        detail: String?,
        message: String? = null,
        command: Command? = null,
    ): Response = Response.failure(
        code,
        detail = detail,
        message = message,
        durationMs = command?.let { clock.nowMs() - it.acceptedAtMs } ?: 0,
    )

    private fun terminate(command: Command, response: Response) {
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
                    is OutboundItem.Blob -> item.blob.finish(streamBlob(item.blob))
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

    /** Streams one blob, stopping at a chunk boundary on cancel, deadline, or write failure. */
    private fun streamBlob(blob: BlobTransfer): BlobTransfer.Outcome {
        val command = blob.command
        val requestId = command.requestId
        if (!write(Outbound.BlobStartFrame(requestId, blob.start()))) return BlobTransfer.Outcome.WRITE_FAILED
        for (index in 0 until blob.chunkCount) {
            if (command.cancelRequested) return BlobTransfer.Outcome.CANCELLED
            if (clock.nowMs() >= command.deadlineMs) return BlobTransfer.Outcome.DEADLINE_EXCEEDED
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
        data object Stop : OutboundItem
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 16
        const val DEFAULT_UNINTERRUPTIBLE_GRACE_MS = 10_000L
        const val DEFAULT_WATCHDOG_POLL_MS = 100L
    }
}
