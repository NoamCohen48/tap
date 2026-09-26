package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.wire.v1.Request

/**
 * Fault-injection seam at the driver's fixed fault points. The product driver runs [NONE]; the
 * `validation` flavor of `:device:driver` installs its fault controller here so the host's
 * fault-validation suite can prove transport loss and late-work handling. Nothing in the
 * product code depends on what an implementation does.
 *
 * A hook aborts the connection without an answer by throwing [InjectedTransportLoss]. Reader-lane
 * hooks run on the connection's reader thread; tap hooks run on the command executor, after
 * the mutation gate.
 */
interface FaultHooks {
    /**
     * Called once per authenticated connection, before any request is read. [abortConnection]
     * resets the socket (RST, no `CLOSE`), for faults that must cut the transport while
     * the executor is still busy.
     */
    fun onConnectionOpened(abortConnection: () -> Unit) {}

    /** Reader lane, before [requestId] is accepted (the watermark has not moved). */
    fun beforeAcceptance(
        request: Request,
        requestId: Long,
    ) {}

    /** Reader lane, right after [requestId] was accepted and before it is screened. */
    fun afterAcceptance(
        request: Request,
        requestId: Long,
    ) {}

    /** Executor: a `tap` passed the mutation gate and is about to click. */
    fun beforeTapClick(
        command: Tap,
        requestId: Long,
    ) {}

    /** Executor: a `tap` clicked; its result is definitive. */
    fun afterTapClick(
        command: Tap,
        requestId: Long,
    ) {}

    /**
     * Whether a poisoned session kills the instrumentation process. A fault that deliberately
     * leaves a hung driver for the host to terminate turns this off.
     */
    val watchdogKillsProcess: Boolean get() = true

    companion object {
        /** No faults: what the shipped driver runs. */
        val NONE: FaultHooks = object : FaultHooks {}
    }
}

/** Thrown by a [FaultHooks] implementation to drop the connection without answering. */
class InjectedTransportLoss : RuntimeException("Injected transport loss")
