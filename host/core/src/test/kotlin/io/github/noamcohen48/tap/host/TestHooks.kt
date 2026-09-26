package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.Frame

/** [TransportHooks] whose points a test sets (and clears) while the client is live. */
internal class TestTransportHooks : TransportHooks {
    @Volatile var onBeforePhysicalWrite: (suspend () -> Unit)? = null

    /** Replaces the socket write while set. */
    @Volatile var onPhysicalWrite: (suspend (Frame) -> Unit)? = null

    @Volatile var onAfterPhysicalWrite: (() -> Unit)? = null

    @Volatile var onBeforeMarkWritten: (() -> Unit)? = null

    @Volatile var onAfterTerminalResponse: (() -> Unit)? = null

    @Volatile var onAfterAwaitCancel: (() -> Unit)? = null

    override suspend fun beforePhysicalWrite() {
        onBeforePhysicalWrite?.invoke()
    }

    override suspend fun physicalWrite(
        frame: Frame,
        write: suspend (Frame) -> Unit,
    ) = (onPhysicalWrite ?: write).invoke(frame)

    override fun afterPhysicalWrite() {
        onAfterPhysicalWrite?.invoke()
    }

    override fun beforeMarkWritten() {
        onBeforeMarkWritten?.invoke()
    }

    override fun afterTerminalResponse() {
        onAfterTerminalResponse?.invoke()
    }

    override fun afterAwaitCancel() {
        onAfterAwaitCancel?.invoke()
    }
}

/** [SessionHooks] whose points a test sets before or during the session. */
internal class TestSessionHooks : SessionHooks {
    @Volatile var onBeforeOwnershipTransfer: (suspend () -> Unit)? = null

    @Volatile var onAfterCancellationCleanup: (() -> Unit)? = null

    @Volatile var onCloseDrainWaitCall: (() -> Unit)? = null

    @Volatile var onBeforeOperationRelease: (suspend () -> Unit)? = null

    override suspend fun beforeOwnershipTransfer() {
        onBeforeOwnershipTransfer?.invoke()
    }

    override fun afterCancellationCleanup() {
        onAfterCancellationCleanup?.invoke()
    }

    override fun onCloseDrainWait() {
        onCloseDrainWaitCall?.invoke()
    }

    override suspend fun beforeOperationRelease() {
        onBeforeOperationRelease?.invoke()
    }
}

/** [AdbHooks] whose points a test sets while the runner is live. */
internal class TestAdbHooks : AdbHooks {
    @Volatile var onAdmissionWaitCall: (() -> Unit)? = null

    @Volatile var onBeforeAdmissionBookkeeping: (suspend () -> Unit)? = null

    override fun onAdmissionWait() {
        onAdmissionWaitCall?.invoke()
    }

    override suspend fun beforeAdmissionBookkeeping() {
        onBeforeAdmissionBookkeeping?.invoke()
    }
}

/** A [ProcessStarter] a test can repoint between calls. */
internal class SwitchableProcessStarter(
    @Volatile var delegate: ProcessStarter,
) : ProcessStarter {
    override fun start(command: List<String>): Process = delegate.start(command)
}

/** A fake-executable [Adb] over [starter] with [hooks]. */
internal fun testAdb(
    starter: ProcessStarter,
    hooks: AdbHooks = AdbHooks.None,
): Adb = Adb("fake-adb", starter, hooks)

/** Whether any lane holds an unreaped residual or a live operation. */
internal suspend fun Adb.isReapGated(): Boolean = admittedTokenCount() > 0
