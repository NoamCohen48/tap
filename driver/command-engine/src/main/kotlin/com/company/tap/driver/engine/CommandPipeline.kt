package com.company.tap.driver.engine

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
 * [uninterruptibleGraceMs]; from then on nothing new mutates the device.
 */
class CommandPipeline(
    private val clock: Clock,
    private val sink: OutboundSink,
    private val listener: PipelineListener,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val uninterruptibleGraceMs: Long = DEFAULT_UNINTERRUPTIBLE_GRACE_MS,
    private val watchdogPollMs: Long = DEFAULT_WATCHDOG_POLL_MS,
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
            Admission.UNHEALTHY -> terminate(command, error(EngineErrorCodes.DRIVER_UNHEALTHY, poisonReason))
            Admission.OVERLOADED -> terminate(
                command,
                error(EngineErrorCodes.OVERLOADED, "Command queue holds $queueCapacity requests"),
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
        if (removed != null) terminate(removed, error(EngineErrorCodes.CANCELLED, "Cancelled before execution"))
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
        discarded.forEach { terminate(it, error(EngineErrorCodes.CANCELLED, "Transport closed before execution")) }
    }

    /** Answers a `PING` on the writer lane without touching the executor. */
    fun pong(requestId: Long) {
        outbound.put(OutboundItem.Message(Outbound.Pong(requestId)))
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
        val reason = lock.withLock {
            val current = running ?: return false
            if (poisoned) return false
            val overdueAt = current.deadlineMs + uninterruptibleGraceMs
            if (clock.nowMs() < overdueAt) return false
            "Request ${current.requestId} exceeded its deadline by more than ${uninterruptibleGraceMs}ms"
        }
        poison(reason)
        return true
    }

    /**
     * Marks the executor untrustworthy: queued work fails with `DRIVER_UNHEALTHY`, the running
     * command gets `INDETERMINATE` if it already mutated and `DRIVER_UNHEALTHY` otherwise, and
     * later mutation attempts are refused at the gate.
     */
    fun poison(reason: String) {
        val doomed = mutableListOf<Pair<Command, Response>>()
        val firstPoison = lock.withLock {
            if (poisoned) return@withLock false
            poisoned = true
            poisonReason = reason
            while (queue.isNotEmpty()) {
                val queued = queue.removeFirst()
                queued.phase = CommandPhase.TERMINAL
                doomed += queued to error(EngineErrorCodes.DRIVER_UNHEALTHY, reason)
            }
            running?.let { current ->
                val code = if (current.mutationStarted) EngineErrorCodes.INDETERMINATE else EngineErrorCodes.DRIVER_UNHEALTHY
                doomed += current to error(code, reason)
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
            if (poisoned) throw CommandInterrupted(EngineErrorCodes.DRIVER_UNHEALTHY)
            if (command.isTerminal) throw CommandInterrupted(EngineErrorCodes.CANCELLED)
            if (command.cancelRequested) throw CommandInterrupted(EngineErrorCodes.CANCELLED)
            if (clock.nowMs() >= command.deadlineMs) throw CommandInterrupted(EngineErrorCodes.DEADLINE_EXCEEDED)
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
            command.cancelRequested -> error(EngineErrorCodes.CANCELLED, "Cancelled before execution")
            clock.nowMs() >= command.deadlineMs ->
                error(EngineErrorCodes.DEADLINE_EXCEEDED, "Deadline expired while queued")
            else -> try {
                command.work(CommandContext(command, clock, ::mutationGate))
            } catch (interrupted: CommandInterrupted) {
                error(interrupted.errorCode, null, command)
            } catch (error: Throwable) {
                error(EngineErrorCodes.INTERNAL, error.message, command)
            }
        }
        terminate(command, response)
    }

    private fun error(code: String, message: String?, command: Command? = null): Response = Response(
        ok = false,
        errorCode = code,
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
                    is OutboundItem.Message -> if (!writeFailed) {
                        try {
                            sink.write(item.message)
                        } catch (error: Throwable) {
                            writeFailed = true
                            listener.onWriteFailed(error)
                        }
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

    private sealed interface OutboundItem {
        data class Message(val message: Outbound) : OutboundItem
        data object Stop : OutboundItem
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 16
        const val DEFAULT_UNINTERRUPTIBLE_GRACE_MS = 10_000L
        const val DEFAULT_WATCHDOG_POLL_MS = 100L
    }
}
