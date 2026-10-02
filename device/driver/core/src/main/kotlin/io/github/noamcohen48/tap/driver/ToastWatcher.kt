package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.app.Notification
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import io.github.noamcohen48.tap.api.v1.AwaitToast
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Toast
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * Remembers recent toasts for `await_toast`. Android announces a toast as a
 * `TYPE_NOTIFICATION_STATE_CHANGED` accessibility event (a notification raises the same type
 * with its `Notification` as parcelable data; those are skipped), as Appium's
 * `NotificationListener` relies on. UiAutomation has a single event listener: this one replaces
 * the one androidx `QueryController` installs, which only feeds the legacy `UiObject` API the
 * driver never uses. `executeAndWaitForEvent` reads UiAutomation's own queue and is unaffected.
 */
internal class ToastWatcher(
    instrumentation: Instrumentation,
) {
    private class Seen(
        val text: String,
        val packageName: String,
        val atMs: Long,
    )

    private val lock = Object()
    private val recent = ArrayDeque<Seen>()

    init {
        instrumentation.uiAutomation.setOnAccessibilityEventListener(::onEvent)
    }

    private fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        if (event.parcelableData is Notification) return
        val seen = Seen(event.text.joinToString(""), event.packageName?.toString().orEmpty(), SystemClock.uptimeMillis())
        synchronized(lock) {
            recent.addLast(seen)
            while (recent.size > CAPACITY) recent.removeFirst()
        }
    }

    /**
     * The newest toast that matches, shown at most [LOOKBACK_MS] before the command started or
     * arriving before its timeout; `WAIT_TIMEOUT` / `NO_TOAST` otherwise. Not consuming: the same
     * toast can answer two calls in a row. Without a package a toast from any package matches.
     */
    fun await(
        context: CommandContext,
        command: AwaitToast,
    ): Toast {
        val since = SystemClock.uptimeMillis() - LOOKBACK_MS
        val text = if (command.hasText()) TextMatcher(command.mode, command.text) else null
        val packageName = if (command.hasPackageName()) command.packageName else null
        while (true) {
            context.checkCancelled()
            val match =
                synchronized(lock) {
                    recent.lastOrNull { seen ->
                        seen.atMs >= since &&
                            (packageName == null || seen.packageName == packageName) &&
                            (text == null || text.matches(seen.text))
                    }
                }
            if (match != null) return Toast.newBuilder().setText(match.text).setPackageName(match.packageName).build()
            if (context.remainingMs() <= 0) {
                val from = packageName ?: "any app"
                throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, ErrorDetail.NO_TOAST, "No matching toast from $from")
            }
            context.sleep(POLL_MS)
        }
    }

    private companion object {
        const val CAPACITY = 32
        const val POLL_MS = 50L

        /** The longest a toast stays up (`Toast.LENGTH_LONG`). */
        const val LOOKBACK_MS = 3_500L
    }
}
