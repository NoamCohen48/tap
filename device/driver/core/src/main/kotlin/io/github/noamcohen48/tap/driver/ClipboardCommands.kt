package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure

/**
 * The device clipboard (`set_clipboard`, `get_clipboard`), plain text only. The manager is used
 * on the main thread, as Appium does (it needs a looper on older versions). From API 29 Android
 * lets only the focused app or the default IME read the clipboard; the driver reads it with the
 * shell's `READ_CLIPBOARD_IN_BACKGROUND`, adopted for the one call, so nothing changes focus.
 */
internal class ClipboardCommands(
    private val instrumentation: Instrumentation,
) {
    fun set(
        context: CommandContext,
        text: String,
    ) {
        context.checkpoint()
        context.markMutationStarted()
        onMain { clipboard().setPrimaryClip(ClipData.newPlainText(LABEL, text)) }
    }

    /** The clipboard's text, `""` when it is empty or holds nothing that reads as text. */
    fun get(context: CommandContext): String {
        context.checkpoint()
        val adopt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val uiAutomation = instrumentation.uiAutomation
        if (adopt) uiAutomation.adoptShellPermissionIdentity(READ_CLIPBOARD_IN_BACKGROUND)
        try {
            return onMain {
                val clip = clipboard().primaryClip
                if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(instrumentation.context).toString()
            }
        } catch (denied: SecurityException) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "Android refused the clipboard read: ${denied.message}")
        } finally {
            if (adopt) uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun clipboard() = instrumentation.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private companion object {
        const val LABEL = "tap"

        /** `Manifest.permission.READ_CLIPBOARD_IN_BACKGROUND`, hidden from the SDK. */
        const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
    }
}
