package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.PERMISSION_CONTROLLER_PACKAGE
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.inSystemPackage
import io.github.noamcohen48.tap.protocol.ok

/**
 * A runtime permission dialog: AUT-scoped selectors do not see it, an unlisted system package
 * is `SCOPE_DENIED`, and the permission controller's allow button (per API level) grants it.
 */
@DeviceTest
class PermissionTest {
    @OnEachDevice
    fun `permission dialog is reachable only through the permission controller scope`(serial: String) =
        deviceTest(serial) { device ->
            device.shell("pm", "revoke", FIXTURE_PACKAGE, "android.permission.CAMERA")
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("PermissionActivity")
                val requestPermission = Selectors.androidResource(FIXTURE_PACKAGE, "request_camera_permission")
                check(client.send(Commands.waitVisible(requestPermission), timeoutMs = 10_000).ok) {
                    "Permission activity did not appear"
                }
                val requestTap = client.send(Commands.tap(requestPermission))
                check(requestTap.ok) { "Permission request tap failed: $requestTap" }
                val permissionChoiceText = if (device.apiLevel >= 30) "While using the app" else "Allow"
                val autPermissionChoice = client.send(Commands.exists(Selectors.text(permissionChoiceText)))
                check(autPermissionChoice.ok && !autPermissionChoice.result.bool) {
                    "AUT scope check failed: $autPermissionChoice"
                }
                val deniedScope =
                    client.send(Commands.exists(Selectors.text(permissionChoiceText).inSystemPackage("com.android.settings")))
                check(
                    !deniedScope.ok && deniedScope.errorCode == ErrorCode.ERR_INVALID_SELECTOR &&
                        deniedScope.detail == ErrorDetail.SCOPE_DENIED,
                ) { "Unlisted system package should be SCOPE_DENIED: $deniedScope" }
                val allowPermission =
                    Selectors
                        .androidResource(
                            PERMISSION_RESOURCE_PACKAGE,
                            if (device.apiLevel >= 30) "permission_allow_foreground_only_button" else "permission_allow_button",
                        ).inSystemPackage(PERMISSION_CONTROLLER_PACKAGE)
                check(client.send(Commands.waitVisible(allowPermission), timeoutMs = 10_000).ok) { "Allow button did not appear" }
                check(client.send(Commands.tap(allowPermission)).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Camera granted")), timeoutMs = 10_000).ok)
            }
        }
}
