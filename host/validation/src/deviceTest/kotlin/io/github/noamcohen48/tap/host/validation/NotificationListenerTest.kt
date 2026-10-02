package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.SavedState
import io.github.noamcohen48.tap.host.StateKey
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

/**
 * The driver's notification listener: `cmd notification allow_listener` (as the server gives it)
 * reads back as allowed, the listener binds in the driver's process, the fixture's two
 * notifications are awaited and listed with their actions, the ongoing one is refused as
 * `NOT_CLEARABLE`, the other is dismissed, and restoring (as detach does) takes the access back.
 */
@DeviceTest
class NotificationListenerTest {
    @OnEachDevice
    fun `the listener sees, lists and dismisses the app's notifications`(serial: String) =
        deviceTest(serial) { device ->
            val key = StateKey.DriverNotificationListener.id
            val before = listOf(SavedState(key, device.adb.readState(serial, key)))
            var listed = 0
            try {
                device.adb.writeState(serial, key, "allowed")
                check(device.adb.readState(serial, key) == "allowed") { "allow_listener did not read back" }
                if (device.apiLevel >= 33) device.shell("pm", "grant", FIXTURE_PACKAGE, "android.permission.POST_NOTIFICATIONS")
                device.withSession { session ->
                    val client = session.client
                    device.wakeAndDismissKeyguard()
                    device.launchFixture("FormActivity")
                    val notify = Selectors.androidResource(FIXTURE_PACKAGE, "notify_button")
                    check(client.send(Commands.waitVisible(notify), timeoutMs = 10_000).ok) { "Form activity did not appear" }
                    check(client.send(Commands.tap(notify)).ok)

                    val message = Commands.notificationMatch(FIXTURE_PACKAGE, title = "New message")
                    val awaited = client.send(Commands.awaitNotification(message), timeoutMs = 10_000)
                    check(awaited.ok && awaited.result.notification.text == "from Ada") { "The fixture's notification was not seen: $awaited" }
                    check(awaited.result.notification.actionsList == listOf("Mark as read")) { "Actions: ${awaited.result.notification.actionsList}" }

                    val all = client.send(Commands.listNotifications(), timeoutMs = 10_000)
                    check(all.ok) { "list_notifications failed: $all" }
                    val mine = all.result.notifications.notificationsList.filter { it.packageName == FIXTURE_PACKAGE }
                    listed = all.result.notifications.notificationsCount
                    check(mine.map { it.title }.toSet() == setOf("New message", "Syncing")) { "Listed: $mine" }
                    check(mine.single { it.title == "Syncing" }.clearable.not()) { "The ongoing notification reads as clearable" }

                    val ongoing = client.send(Commands.dismissNotification(Commands.notificationMatch(FIXTURE_PACKAGE, title = "Syncing")))
                    check(!ongoing.ok && ongoing.errorCode == ErrorCode.ERR_ACTION_REJECTED && ongoing.detail == ErrorDetail.NOT_CLEARABLE) {
                        "Dismissing an ongoing notification should be NOT_CLEARABLE: $ongoing"
                    }
                    check(client.send(Commands.dismissNotification(message)).ok) { "Dismiss failed" }
                    val gone = client.send(Commands.listNotifications())
                    check(gone.ok && gone.result.notifications.notificationsList.none { it.packageName == FIXTURE_PACKAGE && it.title == "New message" }) {
                        "The dismissed notification is still listed: $gone"
                    }
                }
            } finally {
                device.forceStopFixture()
                device.adb.restoreState(serial, before)
            }
            check(device.adb.readState(serial, key) == before.single().value) { "The listener access was not put back" }
            report("notification_listener", serial, "before" to before.single().value.orEmpty(), "listed" to listed, "not_clearable" to "ok")
        }
}
