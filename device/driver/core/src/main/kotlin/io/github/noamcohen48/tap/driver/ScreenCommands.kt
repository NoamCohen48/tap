package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * Screen power and keyguard: the state `device_info` reports, and `dismiss_keyguard`. Waking and
 * sleeping the screen are plain key presses (`KEYCODE_WAKEUP` / `KEYCODE_SLEEP`, which do nothing
 * when the screen is already in that state), so they need no command of their own.
 */
internal class ScreenCommands(
    private val instrumentation: Instrumentation,
) {
    private val power get() = instrumentation.context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard get() = instrumentation.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    val screenOn: Boolean get() = power.isInteractive
    val keyguardLocked: Boolean get() = keyguard.isKeyguardLocked
    val keyguardSecure: Boolean get() = keyguard.isKeyguardSecure

    /**
     * `wm dismiss-keyguard` for a keyguard without a PIN, pattern or password. With no keyguard
     * showing there is nothing to do and nothing is sent. A secure keyguard is refused before any
     * input: Tap never unlocks one (it would only raise the bouncer). The result is only that the
     * request was sent; the test waits for what it needs on screen.
     */
    fun dismissKeyguard(context: CommandContext) {
        context.checkpoint()
        if (!keyguardLocked) return
        if (keyguardSecure) {
            throw CommandFailure(
                ErrorCode.ERR_ACTION_REJECTED,
                ErrorDetail.KEYGUARD_SECURE,
                "The keyguard is secured with a PIN, pattern or password; Tap does not unlock it",
            )
        }
        context.markMutationStarted()
        // UiAutomation runs the command as the shell user, without a shell: nothing to quote.
        // Reading its output to the end waits for it to finish.
        val output = instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
