package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.inAnyWindow
import io.github.noamcohen48.tap.protocol.inPackage
import io.github.noamcohen48.tap.protocol.ok

/**
 * A runtime permission dialog: AUT-scoped selectors do not see it, another package's scope
 * sees only that package, and the permission controller's allow button (per API level) is
 * found in its package's scope and in the any-window scope, which grants it.
 */
@DeviceTest
class PermissionTest {
    @OnEachDevice
    fun `permission dialog is reachable through its package scope and any window`(serial: String) =
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
                val otherPackage =
                    client.send(Commands.exists(Selectors.text(permissionChoiceText).inPackage("com.android.settings")))
                check(otherPackage.ok && !otherPackage.result.bool) { "Another package's scope should not see the dialog: $otherPackage" }
                val allowPermission =
                    Selectors.androidResource(
                        PERMISSION_RESOURCE_PACKAGE,
                        if (device.apiLevel >= 30) "permission_allow_foreground_only_button" else "permission_allow_button",
                    )
                check(client.send(Commands.waitVisible(allowPermission.inAnyWindow()), timeoutMs = 10_000).ok) {
                    "Allow button did not appear in any window"
                }
                val inControllerScope = client.send(Commands.exists(allowPermission.inPackage(PERMISSION_CONTROLLER_PACKAGE)))
                check(inControllerScope.ok && inControllerScope.result.bool) { "Controller scope should see the dialog: $inControllerScope" }
                check(client.send(Commands.tap(allowPermission.inAnyWindow())).ok)
                check(client.send(Commands.waitVisible(Selectors.text("Camera granted")), timeoutMs = 10_000).ok)
            }
        }
}

private const val PERMISSION_CONTROLLER_PACKAGE = "com.google.android.permissioncontroller"
