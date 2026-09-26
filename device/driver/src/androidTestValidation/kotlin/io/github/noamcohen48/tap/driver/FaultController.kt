package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.Tap
import io.github.noamcohen48.tap.wire.v1.Request
import java.util.concurrent.atomic.AtomicBoolean

/** The fault the host's validation suite asks for (`tapFaultPoint`). */
internal enum class FaultPoint {
    NONE,
    BEFORE_ACCEPTANCE,
    AFTER_ACCEPTANCE,
    AFTER_MUTATION,
    LATE_UNINTERRUPTIBLE,
    CANCEL_AFTER_MUTATION,
}

/**
 * Validation-flavor [FaultHooks]: fires the configured [faultPoint] once, and only for a `tap` on
 * the fixture's fault button, printing a `TAP_FAULT` marker the host suite waits for. Never
 * part of the product driver.
 */
internal class FaultController(
    private val instrumentation: Instrumentation,
    private val faultPoint: FaultPoint,
    private val faultAuthority: String,
    private val generation: Long,
) : FaultHooks {
    /** Reader and executor threads both arm faults; exactly one wins. */
    private val triggered = AtomicBoolean(false)

    @Volatile
    private var abortConnection: () -> Unit = {}

    /** The late-work fault must leave a hung driver for the host to terminate. */
    override val watchdogKillsProcess: Boolean get() = faultPoint != FaultPoint.LATE_UNINTERRUPTIBLE

    override fun onConnectionOpened(abortConnection: () -> Unit) {
        this.abortConnection = abortConnection
    }

    override fun beforeAcceptance(
        request: Request,
        requestId: Long,
    ) = dropIfArmed(FaultPoint.BEFORE_ACCEPTANCE, request.faultTap(), requestId)

    override fun afterAcceptance(
        request: Request,
        requestId: Long,
    ) = dropIfArmed(FaultPoint.AFTER_ACCEPTANCE, request.faultTap(), requestId)

    override fun beforeTapClick(
        command: Tap,
        requestId: Long,
    ) {
        if (!arm(FaultPoint.LATE_UNINTERRUPTIBLE, command)) return
        val delayMs = 15_000L
        val result =
            requireNotNull(
                instrumentation.targetContext.contentResolver.call(
                    Uri.parse("content://$faultAuthority"),
                    "scheduleFaultMutation",
                    delayMs.toString(),
                    null,
                ),
            )
        require(result.containsKey("dueElapsedMs"))
        emit(
            "TAP_FAULT point=${FaultPoint.LATE_UNINTERRUPTIBLE.name} " +
                "phase=WORK_DELEGATED request=$requestId " +
                "generation=$generation delayMs=$delayMs",
        )
        abortConnection()
        emit(
            "TAP_FAULT point=${FaultPoint.LATE_UNINTERRUPTIBLE.name} " +
                "phase=BLOCK_ENTER request=$requestId generation=$generation",
        )
        SystemClock.sleep(LATE_WORK_BLOCK_MS)
        throw InjectedTransportLoss()
    }

    override fun afterTapClick(
        command: Tap,
        requestId: Long,
    ) {
        // Keeps the tap running for a bounded time after its click so the host can send CANCEL
        // while the mutation is already definitive. The command must still return its real
        // result; the pipeline ignores the cancel.
        if (arm(FaultPoint.CANCEL_AFTER_MUTATION, command)) {
            emit(
                "TAP_FAULT point=${FaultPoint.CANCEL_AFTER_MUTATION.name} phase=MUTATED " +
                    "request=$requestId generation=$generation holdMs=$CANCEL_HOLD_MS",
            )
            SystemClock.sleep(CANCEL_HOLD_MS)
        }
        dropIfArmed(FaultPoint.AFTER_MUTATION, command, requestId)
    }

    private fun dropIfArmed(
        point: FaultPoint,
        command: Tap?,
        requestId: Long,
    ) {
        if (!arm(point, command)) return
        emit("TAP_FAULT point=${point.name} request=$requestId generation=$generation")
        throw InjectedTransportLoss()
    }

    /** True exactly once: the configured [point], a fault-button tap, and not yet fired. */
    private fun arm(
        point: FaultPoint,
        command: Tap?,
    ): Boolean = faultPoint == point && command.targetsFaultButton() && triggered.compareAndSet(false, true)

    private fun emit(marker: String) {
        println(marker)
        instrumentation.sendStatus(2, Bundle().apply { putString("tapFault", marker) })
    }

    private companion object {
        const val CANCEL_HOLD_MS = 3_000L
        const val LATE_WORK_BLOCK_MS = 30_000L
    }
}

private fun Request.faultTap(): Tap? =
    if (bodyCase == Request.BodyCase.COMMAND && command.opCase == Command.OpCase.TAP) command.tap else null

/** Only a `tap` on the fixture's fault button arms a fault. */
private fun Tap?.targetsFaultButton(): Boolean =
    this != null && selector.node.kindCase == Node.KindCase.RESOURCE && selector.node.resource.name == "fault_button"
