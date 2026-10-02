package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

/**
 * Toasts are seen from their accessibility event: none shown is `NO_TOAST`, the app's toast is
 * matched by text (exact, contains, regex) and reported with the app's package, a toast already
 * gone from the screen still counts within 3.5 s and can answer twice, and another package's
 * filter does not see it.
 */
@DeviceTest
class ToastTest {
    @OnEachDevice
    fun `the app's toast is matched by text and package`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("FormActivity")
                val button = Selectors.androidResource(FIXTURE_PACKAGE, "toast_button")
                check(client.send(Commands.waitVisible(button), timeoutMs = 10_000).ok) { "Form activity did not appear" }

                val none = client.send(Commands.awaitToast(), timeoutMs = 500)
                check(!none.ok && none.errorCode == ErrorCode.ERR_WAIT_TIMEOUT && none.detail == ErrorDetail.NO_TOAST) {
                    "No toast should be a NO_TOAST timeout: $none"
                }
                check(client.send(Commands.tap(button)).ok)
                val toast = client.send(Commands.awaitToast("Saved 1"), timeoutMs = 5_000)
                check(toast.ok && toast.result.toast.packageName == FIXTURE_PACKAGE) { "The app's toast was not seen: $toast" }
                val again = client.send(Commands.awaitToast("Sav", MatchMode.MATCH_STARTS_WITH), timeoutMs = 500)
                check(again.ok && again.result.toast.text == "Saved 1") { "The same toast should answer a second wait: $again" }
                val regex = client.send(Commands.awaitToast("Saved \\d+", MatchMode.MATCH_REGEX), timeoutMs = 500)
                check(regex.ok) { "Regex match failed: $regex" }
                val other = client.send(Commands.awaitToast(packageName = "com.android.settings"), timeoutMs = 500)
                check(!other.ok && other.detail == ErrorDetail.NO_TOAST) { "Another package's filter should not see it: $other" }
                report("toast", serial, "text" to "'${toast.result.toast.text}'", "package" to toast.result.toast.packageName)
            }
        }
}
