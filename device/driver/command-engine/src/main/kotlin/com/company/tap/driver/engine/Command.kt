package com.company.tap.driver.engine

import com.company.tap.protocol.Response
import java.util.concurrent.atomic.AtomicReference

internal enum class CommandPhase { QUEUED, RUNNING, TERMINAL }

internal class Command(
    val requestId: Long,
    val timeoutMs: Long,
    val acceptedAtMs: Long,
    val work: (CommandContext) -> Response,
) {
    val deadlineMs: Long = acceptedAtMs + timeoutMs
    private val terminal = AtomicReference<Response?>(null)

    @Volatile var phase: CommandPhase = CommandPhase.QUEUED
    @Volatile var cancelRequested: Boolean = false
    @Volatile var mutationStarted: Boolean = false

    val isTerminal: Boolean get() = terminal.get() != null

    /** Records the single terminal response. Returns false if one was already recorded. */
    fun complete(response: Response): Boolean = terminal.compareAndSet(null, response)
}
