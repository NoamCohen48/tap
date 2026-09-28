package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/** Condition waits that poll the device until the deadline (`wait_visible`, `wait_gone`, `wait_app_visible`). */
internal class WaitCommands(
    private val device: UiDevice,
    private val objects: UiObjectAccess,
) {
    /**
     * Polls until [target] matches: one or more nodes, or exactly one with [exactlyOne]. A
     * timeout says why from the last poll that was not stale: `NO_MATCH`, or `AMBIGUOUS` with
     * the count (only with [exactlyOne]; without it several matches satisfy the wait).
     */
    fun waitVisible(
        context: CommandContext,
        target: CompiledSelector,
        exactlyOne: Boolean,
    ) {
        var lastCount: Int? = null
        val satisfied =
            pollUntil(context) {
                if (exactlyOne) {
                    objects.count(target)?.also { lastCount = it } == 1
                } else {
                    objects.presence(target)?.also { lastCount = if (it) 1 else 0 } == true
                }
            }
        if (satisfied) return
        val count = lastCount ?: throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT)
        throw CommandFailure(
            ErrorCode.ERR_WAIT_TIMEOUT,
            detail = if (count == 0) ErrorDetail.NO_MATCH else ErrorDetail.AMBIGUOUS,
            matchCount = count,
        )
    }

    /**
     * Polls presence until [target] is gone. A stale read (the tree changed under the search)
     * does not count as gone, so a re-render never passes. A timeout is `STILL_PRESENT` with
     * the number of matches, counted once after the deadline.
     */
    fun waitGone(
        context: CommandContext,
        target: CompiledSelector,
    ) {
        if (pollUntil(context) { objects.presence(target) == false }) return
        val count = objects.count(target)?.takeIf { it > 0 }
        throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, detail = ErrorDetail.STILL_PRESENT, matchCount = count)
    }

    /** Waits until the named package owns a focused window; a timeout is `APP_NOT_VISIBLE`. */
    fun waitAppVisible(
        context: CommandContext,
        command: WaitAppVisible,
    ) {
        if (pollUntil(context) { device.findWindow(By.Window.pkg(command.packageName).focused(true)) != null }) return
        throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, detail = ErrorDetail.APP_NOT_VISIBLE)
    }

    /** Polls [condition] every [POLL_MS] until it holds (true) or the deadline passes (false). */
    private inline fun pollUntil(
        context: CommandContext,
        condition: () -> Boolean,
    ): Boolean {
        var satisfied: Boolean
        do {
            context.checkCancelled()
            satisfied = condition()
            if (!satisfied && context.remainingMs() > 0) context.sleep(POLL_MS)
        } while (!satisfied && !context.isExpired())
        return satisfied
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
