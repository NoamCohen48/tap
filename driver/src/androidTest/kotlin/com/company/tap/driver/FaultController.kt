package com.company.tap.driver

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import com.company.tap.protocol.Operation
import com.company.tap.protocol.Request
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

    fun inject(point: FaultPoint, request: Request, requestId: Long): Boolean {
        if (
            triggered || faultPoint != point || request.operation != Operation.TAP ||
            !request.targetsFaultButton()
        ) {
            return false
        }
        triggered = true
        emit("TAP_FAULT point=${point.name} request=$requestId generation=${request.sessionGeneration}")
        return true
    }

    /**
     * Keeps a tap command running for a bounded time after its click so the host can send
     * `CANCEL` while the mutation is already definitive. The command must still return its
     * real result; the pipeline ignores the cancel.
     */
    fun holdAfterMutation(request: Request, requestId: Long) {
        if (
            triggered || faultPoint != FaultPoint.CANCEL_AFTER_MUTATION ||
            !request.targetsFaultButton()
        ) {
            return
        }
        triggered = true
        emit(
            "TAP_FAULT point=${FaultPoint.CANCEL_AFTER_MUTATION.name} phase=MUTATED " +
                "request=$requestId generation=${request.sessionGeneration} holdMs=$CANCEL_HOLD_MS"
        )
        android.os.SystemClock.sleep(CANCEL_HOLD_MS)
    }

    fun injectLateUninterruptible(socket: Socket, request: Request, requestId: Long) {
        if (
            triggered || faultPoint != FaultPoint.LATE_UNINTERRUPTIBLE ||
            !request.targetsFaultButton()
        ) {
            return
        }
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
                "generation=${request.sessionGeneration} delayMs=$delayMs"
        )
        socket.setSoLinger(true, 0)
        socket.close()
        emit(
            "TAP_FAULT point=${FaultPoint.LATE_UNINTERRUPTIBLE.name} " +
                "phase=BLOCK_ENTER request=$requestId generation=${request.sessionGeneration}"
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

private fun Request.targetsFaultButton(): Boolean = selector?.node?.resource?.name == "fault_button"
