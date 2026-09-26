package io.github.noamcohen48.tap.driver

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.WaitAppVisible
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure

/** Condition waits that poll the device until the deadline (`wait_visible`, `wait_gone`, `wait_app_visible`). */
internal class WaitCommands(
    private val device: UiDevice,
    private val objects: UiObjectAccess,
) {
    /**
     * Polls presence until it equals [expected]. A stale read (the tree changed under the
     * search) satisfies neither direction, so a re-render never passes `wait_gone`.
     */
    fun waitVisible(
        context: CommandContext,
        target: CompiledSelector,
        expected: Boolean,
    ) = pollUntil(context) { objects.presence(target) == expected }

    /** Waits until the named package owns a focused window. */
    fun waitAppVisible(
        context: CommandContext,
        command: WaitAppVisible,
    ) = pollUntil(context) { device.findWindow(By.Window.pkg(command.packageName).focused(true)) != null }

    private inline fun pollUntil(
        context: CommandContext,
        condition: () -> Boolean,
    ) {
        var satisfied: Boolean
        do {
            context.checkCancelled()
            satisfied = condition()
            if (!satisfied && context.remainingMs() > 0) context.sleep(POLL_MS)
        } while (!satisfied && !context.isExpired())
        if (!satisfied) throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT)
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
