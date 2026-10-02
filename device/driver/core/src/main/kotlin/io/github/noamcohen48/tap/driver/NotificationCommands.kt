package io.github.noamcohen48.tap.driver

import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.noamcohen48.tap.api.v1.DeviceNotification
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.NotificationList
import io.github.noamcohen48.tap.api.v1.NotificationMatch
import io.github.noamcohen48.tap.api.v1.OpenNotification
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * Notifications as data (`await_notification`, `list_notifications`, `open_notification`,
 * `dismiss_notification`), read from the active notifications of the driver app's
 * `TapNotificationListener` rather than from the shade's UI, as Appium's settings app does:
 * the accessibility events notifications raise are not sent for every channel, and the shade's
 * layout differs per OEM. Opening sends the notification's own PendingIntent, as SystemUI does
 * on a tap, and removes an auto-cancel notification as SystemUI would; whether the app reacted
 * is the test's business. Group summaries are left out: they are the header of their group,
 * not a notification a user reads.
 */
internal class NotificationCommands(
    private val instrumentation: Instrumentation,
) {
    private class Active(
        val notification: StatusBarNotification,
        val described: DeviceNotification,
    )

    /** The newest matching notification, waiting until one is active. */
    fun await(
        context: CommandContext,
        match: NotificationMatch,
    ): DeviceNotification {
        val listener = listener(context)
        val matcher = Matcher(match)
        while (true) {
            context.checkCancelled()
            active(listener).firstOrNull { matcher.matches(it.described) }?.let { return it.described }
            if (context.remainingMs() <= 0) {
                throw CommandFailure(ErrorCode.ERR_WAIT_TIMEOUT, ErrorDetail.NO_NOTIFICATION, "No matching notification is active")
            }
            context.sleep(POLL_MS)
        }
    }

    /** The active notifications, newest first. */
    fun list(context: CommandContext): NotificationList =
        NotificationList.newBuilder().addAllNotifications(active(listener(context)).map { it.described }).build()

    fun open(
        context: CommandContext,
        command: OpenNotification,
    ) {
        val listener = listener(context)
        val target = one(listener, command.match)
        val notification = target.notification.notification
        val intent =
            if (command.hasAction()) {
                val action = notification.actions?.firstOrNull { it.title?.toString() == command.action }
                action?.actionIntent ?: throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, ErrorDetail.ACTION_NOT_OFFERED, "The notification has no action '${command.action}'")
            } else {
                notification.contentIntent ?: throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, ErrorDetail.ACTION_NOT_OFFERED, "The notification opens nothing (no content intent)")
            }
        context.checkpoint()
        context.markMutationStarted()
        try {
            intent.send(instrumentation.targetContext, 0, null, null, null, null, sendOptions())
        } catch (cancelled: PendingIntent.CanceledException) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The notification's intent was cancelled by its app: ${cancelled.message}")
        }
        if (!command.hasAction() && notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            listener.cancelNotification(target.notification.key)
        }
    }

    fun dismiss(
        context: CommandContext,
        match: NotificationMatch,
    ) {
        val listener = listener(context)
        val target = one(listener, match)
        if (!target.notification.isClearable) {
            throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, ErrorDetail.NOT_CLEARABLE, "The notification is ongoing: a swipe does not dismiss it")
        }
        context.checkpoint()
        context.markMutationStarted()
        listener.cancelNotification(target.notification.key)
    }

    private fun one(
        listener: NotificationListenerService,
        match: NotificationMatch,
    ): Active {
        val matcher = Matcher(match)
        val matching = active(listener).filter { matcher.matches(it.described) }
        return when (matching.size) {
            1 -> matching.single()
            0 -> throw CommandFailure(ErrorCode.ERR_NOT_FOUND, message = "No active notification matches", matchCount = 0)
            else -> throw CommandFailure(ErrorCode.ERR_AMBIGUOUS, message = "${matching.size} active notifications match", matchCount = matching.size)
        }
    }

    private fun active(listener: NotificationListenerService): List<Active> {
        val notifications =
            try {
                listener.activeNotifications
            } catch (lost: SecurityException) {
                // Android unbound the listener since it was read (its access was taken back).
                throw noAccess("the listener lost notification access: ${lost.message}")
            } ?: throw noAccess("the listener is not bound")
        return notifications
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .map { Active(it, describe(it)) }
    }

    /** The bound listener; asks Android to bind it again and waits [CONNECT_MS] when none is. */
    private fun listener(context: CommandContext): NotificationListenerService {
        connected()?.let { return it }
        runCatching { NotificationListenerService.requestRebind(ComponentName(instrumentation.targetContext.packageName, LISTENER_CLASS)) }
        val until = SystemClock.uptimeMillis() + CONNECT_MS
        while (true) {
            context.checkpoint()
            connected()?.let { return it }
            if (SystemClock.uptimeMillis() >= until) throw noAccess("the server allows it before a notification command; was it disallowed?")
            context.sleep(POLL_MS)
        }
    }

    private fun connected(): NotificationListenerService? {
        val listener =
            try {
                Class.forName(LISTENER_CLASS)
            } catch (_: ClassNotFoundException) {
                throw noAccess("the driver app has no $LISTENER_CLASS (an older driver app?)")
            }
        return listener.getMethod("getConnected").invoke(null) as NotificationListenerService?
    }

    private fun noAccess(why: String) =
        CommandFailure(ErrorCode.ERR_UNSUPPORTED, ErrorDetail.NO_NOTIFICATION_ACCESS, "The driver has no notification listener: $why")

    /** Lets the intent start an activity from the background (API 34+), as the shell-started driver may. */
    private fun sendOptions(): Bundle? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA ->
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS).toBundle()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            else -> null
        }

    companion object {
        /** In the driver app (`:device:driver` main), which this library does not compile against. */
        const val LISTENER_CLASS = "io.github.noamcohen48.tap.driver.TapNotificationListener"
        private const val CONNECT_MS = 5_000L
        private const val POLL_MS = 100L

        fun describe(notification: StatusBarNotification): DeviceNotification {
            val extras = notification.notification.extras
            return DeviceNotification
                .newBuilder()
                .setPackageName(notification.packageName)
                .setClearable(notification.isClearable)
                .setPostedAtMs(notification.postTime)
                .apply {
                    extras.getCharSequence(Notification.EXTRA_TITLE)?.let { setTitle(it.toString()) }
                    extras.getCharSequence(Notification.EXTRA_TEXT)?.let { setText(it.toString()) }
                    notification.notification.actions?.forEach { action -> action.title?.let { addActions(it.toString()) } }
                }.build()
        }
    }

    /** Every field the match gives matches: the package exactly, the title and text under its mode. */
    class Matcher(
        match: NotificationMatch,
    ) {
        private val packageName = if (match.hasPackageName()) match.packageName else null
        private val title = if (match.hasTitle()) TextMatcher(match.mode, match.title) else null
        private val text = if (match.hasText()) TextMatcher(match.mode, match.text) else null

        fun matches(notification: DeviceNotification): Boolean =
            (packageName == null || packageName == notification.packageName) &&
                (title == null || title.matches(notification.title.takeIf { notification.hasTitle() })) &&
                (text == null || text.matches(notification.text.takeIf { notification.hasText() }))
    }
}
