package io.github.noamcohen48.tap.driver

import android.service.notification.NotificationListenerService

/**
 * The driver's notification access, as Appium's settings app has it: Android binds this service
 * once the host allows it (`cmd notification allow_listener`, before the first notification
 * command; disallowed again on detach), and the driver instrumentation, which runs in this
 * app's process, reads the active notifications and opens or dismisses them through [connected]
 * (driver core's `NotificationCommands`, by reflection: core does not compile against the app).
 * The service itself does nothing on a posted notification; commands read the active ones.
 */
class TapNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    companion object {
        /** The bound listener, or null while Android has none bound in this process. */
        @JvmStatic
        @Volatile
        var connected: NotificationListenerService? = null
            private set
    }
}
