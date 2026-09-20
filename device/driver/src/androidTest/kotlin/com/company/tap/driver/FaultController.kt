package com.company.tap.driver

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import com.company.tap.protocol.Command
import com.company.tap.protocol.Tap
import java.net.Socket

internal enum class FaultPoint {
    NONE,
    BEFORE_ACCEPTANCE,
    AFTER_ACCEPTANCE,
    AFTER_MUTATION,
    LATE_UNINTERRUPTIBLE,
    CANCEL_AFTER_MUTATION,
}

internal class FaultController(
    private val instrumentation: Instrumentation,
    private val faultPoint: FaultPoint,
    private val faultAuthority: String,
) {
    private var triggered = false

    fun inject(point: FaultPoint, command: Command, requestId: Long, generation: Long): Boolean {
        if (triggered || faultPoint != point || !command.targetsFaultButton()) return false
        triggered = true
        emit("TAP_FAULT point=${point.name} request=$requestId generation=$generation")
        return true
    }

    /**
     * Keeps a tap command running for a bounded time after its click so the host can send
     * `CANCEL` while the mutation is already definitive. The command must still return its
     * real result; the pipeline ignores the cancel.
     */
    fun holdAfterMutation(command: Command, requestId: Long, generation: Long) {
        if (triggered || faultPoint != FaultPoint.CANCEL_AFTER_MUTATION || !command.targetsFaultButton()) return
        triggered = true
        emit(
            "TAP_FAULT point=${FaultPoint.CANCEL_AFTER_MUTATION.name} phase=MUTATED " +
                "request=$requestId generation=$generation holdMs=$CANCEL_HOLD_MS"
        )
        android.os.SystemClock.sleep(CANCEL_HOLD_MS)
    }

    fun injectLateUninterruptible(socket: Socket, command: Command, requestId: Long, generation: Long) {
        if (triggered || faultPoint != FaultPoint.LATE_UNINTERRUPTIBLE || !command.targetsFaultButton()) return
        triggered = true
        val delayMs = 15_000L
        val result = requireNotNull(
            instrumentation.targetContext.contentResolver.call(
                Uri.parse("content://$faultAuthority"),
                "scheduleFaultMutation",
                delayMs.toString(),
                null,
            )
        )
        require(result.containsKey("dueElapsedMs"))
        emit(
            "TAP_FAULT point=${FaultPoint.LATE_UNINTERRUPTIBLE.name} " +
                "phase=WORK_DELEGATED request=$requestId " +
                "generation=$generation delayMs=$delayMs"
        )
        socket.setSoLinger(true, 0)
        socket.close()
        emit(
            "TAP_FAULT point=${FaultPoint.LATE_UNINTERRUPTIBLE.name} " +
                "phase=BLOCK_ENTER request=$requestId generation=$generation"
        )
        android.os.SystemClock.sleep(30_000)
        throw InjectedTransportLoss()
    }

    private fun emit(marker: String) {
        println(marker)
        instrumentation.sendStatus(2, Bundle().apply { putString("tapFault", marker) })
    }
}

internal class InjectedTransportLoss : RuntimeException()

private const val CANCEL_HOLD_MS = 3_000L

/** Only a `tap` on the fixture's fault button arms a fault. */
private fun Command.targetsFaultButton(): Boolean = this is Tap && selector.node.resource?.name == "fault_button"
