package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.Frame

/*
 * Observation and gating points inside host/core's concurrency-critical paths. Each is a
 * constructor argument of the class it instruments, never a mutable field, and every method
 * defaults to a no-op: production passes the `None` instance, unit tests pass an implementation
 * that parks or records at the exact race point they need to make deterministic. Internal on
 * purpose — no hook is part of the supported surface.
 */

/** Hook points of [DriverTransport] and its pending commands. */
internal interface TransportHooks {
    /** In the writer child before the physical write is marked started; may suspend to park it. */
    suspend fun beforePhysicalWrite() {}

    /** Performs the physical write of [frame]; the default hands it to [write] (the socket). */
    suspend fun physicalWrite(
        frame: Frame,
        write: suspend (Frame) -> Unit,
    ) = write(frame)

    /** In the writer child after the physical write returned or failed. */
    fun afterPhysicalWrite() {}

    /** Before a pending command's `WRITING -> WRITTEN` transition. */
    fun beforeMarkWritten() {}

    /** After a pending command installed its terminal response, before its waiters resume. */
    fun afterTerminalResponse() {}

    /** In `await`'s caller-cancellation path, after the cooperative cancel was queued. */
    fun afterAwaitCancel() {}

    object None : TransportHooks
}

/** Hook points of [DeviceSession] open and close. */
internal interface SessionHooks {
    /** After `READY`, before the cancellable ownership handoff; runs NonCancellable. */
    suspend fun beforeOwnershipTransfer() {}

    /** After a cancelled open's session-owned cleanup finished and stopped its scope. */
    fun afterCancellationCleanup() {}

    /** Each time close's drain observes an admitted operation still in flight. */
    fun onCloseDrainWait() {}

    /** In a guarded operation's lease release, NonCancellable, before the operation lock. */
    suspend fun beforeOperationRelease() {}

    object None : SessionHooks
}

/** Hook points of [Adb]'s admission. */
internal interface AdbHooks {
    /** A caller found its lane or the global cap held by a live operation and is about to wait. */
    fun onAdmissionWait() {}

    /** Token bookkeeping (release or residual transfer), NonCancellable, before the admission lock. */
    suspend fun beforeAdmissionBookkeeping() {}

    object None : AdbHooks
}
